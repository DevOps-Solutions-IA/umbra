"""Bounded ASGI ingress. No request bodies, authorization values or raw IPs are logged."""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import secrets
import threading
import time
from collections import OrderedDict

from starlette.responses import JSONResponse


class InvalidJSON(ValueError):
    pass


def strict_json(body: bytes) -> dict:
    text = body.decode("utf-8", errors="strict")
    if not text or text.startswith("\ufeff"):
        raise InvalidJSON("Invalid JSON")
    depth, escaped, quoted = 0, False, False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in "[{":
            depth += 1
            if depth > 12:
                raise InvalidJSON("JSON nesting limit")
        elif char in "]}":
            depth -= 1
            if depth < 0:
                raise InvalidJSON("Invalid JSON")

    def pairs(values):
        result = {}
        for key, value in values:
            if key in result or len(key) > 128:
                raise InvalidJSON("Duplicate or oversized key")
            result[key] = value
        return result

    def integer(value):
        if len(value) > 20:
            raise InvalidJSON("Integer limit")
        result = int(value)
        if not -(2**63) <= result < 2**63:
            raise InvalidJSON("Integer limit")
        return result

    def reject_number(_):
        raise InvalidJSON("Protocol requires finite integer numbers")

    value = json.loads(text, object_pairs_hook=pairs, parse_int=integer,
                       parse_float=reject_number, parse_constant=reject_number)
    if not isinstance(value, dict):
        raise InvalidJSON("Object required")
    pending, nodes = [value], 0
    while pending:
        item = pending.pop()
        nodes += 1
        if nodes > 4096:
            raise InvalidJSON("JSON node limit")
        if isinstance(item, dict):
            pending.extend(item.keys()); pending.extend(item.values())
        elif isinstance(item, list):
            pending.extend(item)
        elif isinstance(item, str):
            item.encode("utf-8", errors="strict")  # Reject unpaired escaped surrogates.
    return value


class RequestLimits:
    """Absolute body deadline, active-memory limits and short-lived rate-limit digests.

    Uvicorn ignores forwarded headers. Behind one proxy this IP cap is aggregate,
    intentionally not a claim of authenticated per-user rate limiting.
    """
    def __init__(self, app, per_minute=240, max_body=1_000_000, body_timeout=10.0, max_active=32):
        if per_minute < 1 or max_active < 1 or body_timeout <= 0:
            raise ValueError("Invalid ingress limits")
        self.app, self.per_minute, self.max_body = app, per_minute, max_body
        self.body_timeout, self.max_active = body_timeout, max_active
        self._buckets = OrderedDict()
        self._lock = threading.Lock()
        self._salt = secrets.token_bytes(32)
        self._active = 0

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)

        async def secure_send(message):
            if message["type"] == "http.response.start":
                headers = [(k, v) for k, v in message.get("headers", [])
                           if k.lower() not in (b"cache-control", b"x-content-type-options", b"referrer-policy")]
                headers += [(b"cache-control", b"no-store"), (b"x-content-type-options", b"nosniff"),
                            (b"referrer-policy", b"no-referrer")]
                message = dict(message, headers=headers)
            await send(message)

        async def reject(status, detail):
            headers = {"Retry-After": "60"} if status in (429, 503) else {}
            await JSONResponse({"detail": detail}, status_code=status, headers=headers)(scope, receive, secure_send)

        headers = scope.get("headers", [])
        if sum(len(k) + len(v) for k, v in headers) > 16_384:
            return await reject(431, "Headers too large")
        if len(scope.get("raw_path", b"")) + len(scope.get("query_string", b"")) > 2048:
            return await reject(414, "Target too long")
        selected = {}
        for key, value in headers:
            key = key.lower()
            if key in (b"content-length", b"content-type", b"authorization", b"content-encoding", b"transfer-encoding"):
                if key in selected:
                    return await reject(400, "Ambiguous headers")
                selected[key] = value
        length = selected.get(b"content-length")
        if length is not None:
            if not length.isdigit() or len(length) > 10:
                return await reject(400, "Invalid content length")
            if int(length) > self.max_body:
                return await reject(413, "Body too large")
            if b"transfer-encoding" in selected:
                return await reject(400, "Ambiguous framing")
        if selected.get(b"content-encoding", b"identity").lower() != b"identity":
            return await reject(415, "Content encoding not supported")
        now = time.monotonic()
        ip = str((scope.get("client") or ("unknown",))[0])
        key = hmac.new(self._salt, ip.encode("utf-8"), hashlib.sha256).digest()
        with self._lock:
            # Old digests expire after one minute and are never stored on disk.
            while self._buckets and next(iter(self._buckets.values()))[0] <= now - 60:
                self._buckets.popitem(last=False)
            started, count = self._buckets.get(key, (now, 0))
            if now - started >= 60:
                started, count = now, 0
            count += 1
            self._buckets[key] = (started, count)
            while len(self._buckets) > 10_000:
                self._buckets.popitem(last=False)
            limited, busy = count > self.per_minute, self._active >= self.max_active
            if not limited and not busy:
                self._active += 1
        if limited:
            return await reject(429, "Rate limit")
        if busy:
            return await reject(503, "Server busy")
        try:
            body = bytearray()
            try:
                async with asyncio.timeout(self.body_timeout):
                    while True:
                        message = await receive()
                        if message["type"] == "http.disconnect":
                            return
                        if message["type"] != "http.request":
                            return await reject(400, "Invalid request stream")
                        chunk = message.get("body", b"")
                        if len(body) + len(chunk) > self.max_body:
                            return await reject(413, "Body too large")
                        body.extend(chunk)
                        if not message.get("more_body", False):
                            break
            except TimeoutError:
                return await reject(408, "Request body timeout")
            if length is not None and len(body) != int(length):
                return await reject(400, "Content length mismatch")
            if body:
                media_type = selected.get(b"content-type", b"").split(b";", 1)[0].strip().lower()
                if media_type != b"application/json":
                    return await reject(415, "JSON content type required")
                try:
                    strict_json(bytes(body))
                except (ValueError, UnicodeError, RecursionError):
                    return await reject(422, "Invalid request schema")
            delivered = False

            async def limited_receive():
                nonlocal delivered
                if not delivered:
                    delivered = True
                    return {"type": "http.request", "body": bytes(body), "more_body": False}
                return await receive()
            await self.app(scope, limited_receive, secure_send)
        finally:
            with self._lock:
                self._active -= 1

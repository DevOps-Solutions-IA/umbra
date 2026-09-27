"""Default-deny admission gate, additional to existing opaque mailbox capabilities."""
import sqlite3
from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict, Field
from starlette.concurrency import run_in_threadpool
from starlette.responses import JSONResponse
from .admission_protocol import AdmissionError, ChallengeUnavailable, digest
from .admission_context import transaction_authorization

PUBLIC = frozenset({("GET", "/healthz"), ("GET", "/v1/admission/realm"),
                    ("POST", "/v1/admission/requests"), ("POST", "/v1/admission/credentials"),
                    ("POST", "/v1/admission/revocations"), ("POST", "/v1/admission/challenges"), ("POST", "/v1/admission/challenge-batch"), ("POST", "/v1/admission/rejections"),
                    ("POST", "/v1/admission/result-challenge"), ("POST", "/v1/admission/result"), ("POST", "/v1/admission/renewals")})


def operation_hash(method, target, content, capability):
    """Exact request octets, including query order and mailbox capability, are authenticated."""
    from .admission_protocol import encode
    return digest(("UMBRA-ADMISSION-HTTP-1\n" + method + "\n" + encode(target) + "\n" +
                   digest(content) + "\n" + digest(capability) + "\n").encode("ascii"))


class AdmissionGate:
    def __init__(self, app, store, health):
        self.app, self.store, self.health = app, store, health

    async def __call__(self, scope, receive, send):
        if scope["type"] == "websocket":
            # v1 defines HTTP possession only. A future WebSocket route must not bypass this gate.
            await send({"type": "websocket.close", "code": 1008})
            return
        if scope["type"] != "http" or (scope.get("method"), scope.get("path")) in PUBLIC:
            return await self.app(scope, receive, send)
        try:
            if self.store is None:
                raise AdmissionError()
            selected = {}
            for key, value in scope.get("headers", []):
                key = key.lower()
                if key in (b"x-umbra-credential", b"x-umbra-challenge", b"x-umbra-proof", b"authorization"):
                    if key in selected:
                        raise AdmissionError()
                    selected[key] = value
            # RequestLimits is the outer middleware: bounded body, deadline and rate limit apply first.
            event = await receive()
            if event["type"] != "http.request" or event.get("more_body", False):
                raise AdmissionError()
            content = event.get("body", b"")
            target = scope.get("raw_path", b"")
            if scope.get("query_string"):
                target += b"?" + scope["query_string"]
            operation = operation_hash(scope["method"], target, content, selected.get(b"authorization", b""))
            try:
                wire, challenge, proof = [selected[key].decode("ascii") for key in
                                          (b"x-umbra-credential", b"x-umbra-challenge", b"x-umbra-proof")]
            except (KeyError, UnicodeError):
                raise AdmissionError() from None

            def authenticate():
                with self.store.database.connect(write=True) as conn:
                    return self.store.consume(conn, wire, challenge, proof, operation)
            credential = await run_in_threadpool(authenticate)
            context = transaction_authorization.set(lambda conn: self.store.authorize(conn, credential))
            delivered = False

            async def buffered_receive():
                nonlocal delivered
                if not delivered:
                    delivered = True
                    return event
                return await receive()
            try:
                await self.app(scope, buffered_receive, send)
            finally:
                transaction_authorization.reset(context)
        except sqlite3.Error:
            self.health.healthy = False
            await JSONResponse({"detail": "Storage temporarily unavailable"}, status_code=503,
                headers={"Retry-After": "60"})(scope, receive, send)
        except ChallengeUnavailable:
            await JSONResponse({"detail": "Admission unavailable"}, status_code=403,
                headers={"X-Umbra-Admission-Retry": "fresh-challenge"})(scope, receive, send)
        except AdmissionError:
            await JSONResponse({"detail": "Admission unavailable"}, status_code=403)(scope, receive, send)


def install_admission(app, store):
    class SignedObject(BaseModel):
        model_config = ConfigDict(extra="forbid", strict=True)
        wire: str = Field(min_length=1, max_length=4096)

    class ChallengeRequest(BaseModel):
        model_config = ConfigDict(extra="forbid", strict=True)
        credential: str = Field(min_length=1, max_length=4096)
        operation: str = Field(pattern=r"^[a-f0-9]{64}$")

    def configured():
        if store is None:
            raise HTTPException(503, "Admission provisioning required")
        return store

    @app.exception_handler(AdmissionError)
    async def unavailable(request, exc):
        return JSONResponse({"detail": "Admission unavailable"}, status_code=403)

    @app.get("/v1/admission/realm")
    def realm():
        return {"realm": configured().realm.encode()}

    @app.post("/v1/admission/requests", status_code=201)
    def submit(data: SignedObject):
        configured().submit(data.wire)
        return {"submitted": True}

    @app.post("/v1/admission/credentials", status_code=201)
    def publish(data: SignedObject):
        # Public distribution of an authority-signed result, not an unsigned administrative command.
        configured().publish(data.wire)
        return {"installed": True}

    @app.post("/v1/admission/revocations", status_code=201)
    def revoke(data: SignedObject):
        configured().revoke(data.wire)
        return {"revoked": True}

    @app.post("/v1/admission/challenges")
    def challenge(data: ChallengeRequest):
        return {"challenge": configured().challenge(data.credential, data.operation).encode()}

    @app.post("/v1/admission/challenge-batch")
    def batch(data: SignedObject):
        return {"challenges": [c.encode() for c in configured().batch(data.wire)]}

    class ResultRequest(BaseModel):
        model_config = ConfigDict(extra="forbid", strict=True)
        wire: str = Field(min_length=1, max_length=4096)
        challenge: str = Field(min_length=1, max_length=2048)
        proof: str = Field(min_length=1, max_length=4096)

    @app.post("/v1/admission/rejections", status_code=201)
    def reject(data: SignedObject):
        configured().reject(data.wire)
        return {"rejected": True}

    @app.post("/v1/admission/result-challenge")
    def result_challenge(data: SignedObject):
        return {"challenge": configured().result_challenge(data.wire).encode()}

    @app.post("/v1/admission/result")
    def result(data: ResultRequest):
        return configured().result(data.wire, data.challenge, data.proof)

    class Renewal(BaseModel):
        model_config = ConfigDict(extra="forbid", strict=True)
        credential: str = Field(min_length=1, max_length=4096)
        revocation: str = Field(min_length=1, max_length=4096)

    @app.post("/v1/admission/renewals", status_code=201)
    def renew(data: Renewal):
        configured().renew(data.credential, data.revocation)
        return {"renewed": True}

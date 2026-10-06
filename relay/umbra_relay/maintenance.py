"""Bounded retention retries and opaque health state; never log tokens or SQL input."""
from __future__ import annotations
import asyncio
import sqlite3
from collections.abc import Awaitable, Callable
from dataclasses import dataclass


@dataclass
class RetentionHealth:
    healthy: bool = True


async def retention_loop(cleanup: Callable[[], None], health: RetentionHealth,
                         *, interval: float = 60,
                         delay: Callable[[float], Awaitable[None]] = asyncio.sleep) -> None:
    while True:
        await delay(interval)
        try:
            await asyncio.to_thread(cleanup)
        except sqlite3.Error:
            # Disk errors/locks do not permanently kill expiry. Retry at the same
            # bounded cadence and report unhealthy until a cleanup really succeeds.
            health.healthy = False
        except Exception:
            health.healthy = False
            raise  # Do not turn programming errors into silently successful maintenance.
        else:
            health.healthy = True

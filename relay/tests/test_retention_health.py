"""Retention failures must remain visible; tests use synthetic state and no secrets."""
from types import SimpleNamespace
from fastapi.testclient import TestClient
from umbra_relay.app import create_app


def test_health_reports_failed_retention_without_error_details(tmp_path):
    app = create_app(str(tmp_path / 'relay.sqlite3'))
    app.state.retention_health = SimpleNamespace(healthy=False)
    client = TestClient(app)
    response = client.get('/healthz')
    assert response.status_code == 503
    assert response.json() == {'status': 'degraded'}
    app.state.retention_health.healthy = True
    assert client.get('/healthz').json() == {'status': 'ok'}


def test_cleanup_recovers_after_database_error_at_bounded_cadence():
    import asyncio
    import sqlite3
    from umbra_relay.maintenance import RetentionHealth, retention_loop
    async def run():
        health = RetentionHealth()
        calls, delays, statuses = [], [], []
        def cleanup():
            calls.append(1)
            if len(calls) == 1:
                raise sqlite3.OperationalError('synthetic database locked')
        async def delay(seconds):
            delays.append(seconds); statuses.append(health.healthy)
            if len(delays) == 3:
                raise asyncio.CancelledError()
        try:
            await retention_loop(cleanup, health, delay=delay)
        except asyncio.CancelledError:
            pass
        assert len(calls) == 2
        assert delays == [60, 60, 60]
        assert statuses == [True, False, True]
    asyncio.run(run())


def test_repeated_database_error_never_reports_recovery_or_hot_loops():
    import asyncio
    import sqlite3
    from umbra_relay.maintenance import RetentionHealth, retention_loop
    async def run():
        health = RetentionHealth()
        delays = []
        def cleanup():
            raise sqlite3.DatabaseError('synthetic unavailable storage')
        async def delay(seconds):
            delays.append(seconds)
            if len(delays) == 4:
                raise asyncio.CancelledError()
        try:
            await retention_loop(cleanup, health, delay=delay)
        except asyncio.CancelledError:
            pass
        assert not health.healthy
        assert delays == [60, 60, 60, 60]
    asyncio.run(run())


def test_programming_failure_is_unhealthy_and_not_suppressed():
    import asyncio
    import pytest
    from umbra_relay.maintenance import RetentionHealth, retention_loop
    async def run():
        health = RetentionHealth()
        async def delay(_):
            pass
        def cleanup():
            raise RuntimeError('synthetic programming failure')
        with pytest.raises(RuntimeError, match='synthetic programming failure'):
            await retention_loop(cleanup, health, delay=delay)
        assert not health.healthy
    asyncio.run(run())


def test_shutdown_cancellation_does_not_run_cleanup():
    import asyncio
    import pytest
    from umbra_relay.maintenance import RetentionHealth, retention_loop
    async def run():
        calls = []
        async def delay(_):
            raise asyncio.CancelledError()
        with pytest.raises(asyncio.CancelledError):
            await retention_loop(lambda: calls.append(1), RetentionHealth(), delay=delay)
        assert calls == []
    asyncio.run(run())

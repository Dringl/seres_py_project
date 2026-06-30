import asyncio
import contextlib

import app.engine_loop as engine_loop


def test_run_engine_survives_failing_tick(monkeypatch):
    calls = {"n": 0}
    stop = asyncio.Event()

    @contextlib.contextmanager
    def fake_scope():
        yield None  # no real session

    def fake_tick(session, dt_seconds):
        calls["n"] += 1
        if calls["n"] == 1:
            raise RuntimeError("boom")  # first tick fails
        stop.set()  # second tick stops the loop

    monkeypatch.setattr(engine_loop, "session_scope", fake_scope)
    monkeypatch.setattr(engine_loop, "tick", fake_tick)

    # run_engine must return normally and NOT propagate the RuntimeError.
    asyncio.run(engine_loop.run_engine(stop, interval=0.0))

    assert calls["n"] >= 2  # loop continued past the exception

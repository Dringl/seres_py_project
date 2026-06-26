import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.config import get_settings
from app.database import init_app_engine, session_scope
from app.seed import seed_if_empty
from app.simulation import tick

ENGINE_INTERVAL_SECONDS = 1.0


async def run_engine(stop_event: asyncio.Event, interval: float = ENGINE_INTERVAL_SECONDS) -> None:
    while not stop_event.is_set():
        with session_scope() as session:
            tick(session, dt_seconds=interval)
        try:
            await asyncio.wait_for(stop_event.wait(), timeout=interval)
        except asyncio.TimeoutError:
            pass


@asynccontextmanager
async def engine_lifespan(app: FastAPI):
    settings = get_settings()
    init_app_engine(settings.db_path)
    with session_scope() as session:
        seed_if_empty(session)
    stop_event = asyncio.Event()
    task = asyncio.create_task(run_engine(stop_event))
    try:
        yield
    finally:
        stop_event.set()
        await task

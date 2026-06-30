import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.auth import seed_admin_if_empty
from app.config import get_settings
from app.database import init_app_engine, session_scope
from app.seed import seed_if_empty
from app.simulation import tick

logger = logging.getLogger(__name__)

ENGINE_INTERVAL_SECONDS = 0.25  # 更密的位置更新让 app/大屏动画更连贯（速度不变：step=speed/3600*dt）


async def run_engine(stop_event: asyncio.Event, interval: float = ENGINE_INTERVAL_SECONDS) -> None:
    while not stop_event.is_set():
        try:
            with session_scope() as session:
                tick(session, dt_seconds=interval)
        except Exception:
            logger.exception("engine tick failed; continuing")
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
        seed_admin_if_empty(session, settings.admin_username, settings.admin_password)
    stop_event = asyncio.Event()
    task = asyncio.create_task(run_engine(stop_event))
    try:
        yield
    finally:
        stop_event.set()
        await task

import os

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from starlette.middleware.sessions import SessionMiddleware

from app.config import get_settings
from app.engine_loop import engine_lifespan
from app.routers import admin_api, admin_auth, admin_pages, mobile
from app.templating import STATIC_DIR

_TESTING = os.getenv("EVTOL_TESTING") == "1"

import logging

_logger = logging.getLogger(__name__)
_settings = get_settings()
if not _TESTING:
    if _settings.secret_key == "dev-secret":
        _logger.warning("SECRET_KEY 仍为默认值，请在 /etc/evtol.env 设置随机密钥（openssl rand -hex 32）")
    if _settings.admin_password == "change-me-please":
        _logger.warning("ADMIN_PASSWORD 仍为默认值，请在 /etc/evtol.env 修改")

app = FastAPI(
    title="eVTOL Dispatch Server",
    lifespan=None if _TESTING else engine_lifespan,
)
app.add_middleware(SessionMiddleware, secret_key=_settings.secret_key, same_site="lax", https_only=not _TESTING)
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
app.include_router(mobile.router)
app.include_router(admin_auth.router)
app.include_router(admin_api.router)
app.include_router(admin_pages.router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}

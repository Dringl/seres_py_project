import os

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from starlette.middleware.sessions import SessionMiddleware

from app.config import get_settings
from app.engine_loop import engine_lifespan
from app.routers import admin_api, admin_auth, admin_pages, mobile
from app.templating import STATIC_DIR

_TESTING = os.getenv("EVTOL_TESTING") == "1"

app = FastAPI(
    title="eVTOL Dispatch Server",
    lifespan=None if _TESTING else engine_lifespan,
)
app.add_middleware(SessionMiddleware, secret_key=get_settings().secret_key, same_site="lax")
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
app.include_router(mobile.router)
app.include_router(admin_auth.router)
app.include_router(admin_api.router)
app.include_router(admin_pages.router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}

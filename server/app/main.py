import os

from fastapi import FastAPI
from starlette.middleware.sessions import SessionMiddleware

from app.config import get_settings
from app.engine_loop import engine_lifespan
from app.routers import admin_auth, mobile

_TESTING = os.getenv("EVTOL_TESTING") == "1"

app = FastAPI(
    title="eVTOL Dispatch Server",
    lifespan=None if _TESTING else engine_lifespan,
)
app.add_middleware(SessionMiddleware, secret_key=get_settings().secret_key, same_site="lax")
app.include_router(mobile.router)
app.include_router(admin_auth.router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}

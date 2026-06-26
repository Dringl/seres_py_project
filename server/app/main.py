import os

from fastapi import FastAPI

from app.engine_loop import engine_lifespan
from app.routers import mobile

# 测试时由 conftest 自行管理引擎与库，避免后台循环与测试 tick 抢库
_TESTING = os.getenv("EVTOL_TESTING") == "1"

app = FastAPI(
    title="eVTOL Dispatch Server",
    lifespan=None if _TESTING else engine_lifespan,
)
app.include_router(mobile.router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}

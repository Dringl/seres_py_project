from fastapi import FastAPI

from app.routers import mobile

app = FastAPI(title="eVTOL Dispatch Server")
app.include_router(mobile.router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}

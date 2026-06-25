from fastapi import FastAPI

app = FastAPI(title="eVTOL Dispatch Server")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}

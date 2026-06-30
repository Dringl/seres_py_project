from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    db_path: str = "./evtol.db"
    app_api_key: str | None = None
    admin_username: str = "admin"
    admin_password: str = "change-me-please"
    secret_key: str = "dev-secret"
    gaode_web_key: str = ""
    gaode_web_secret: str = ""


@lru_cache
def get_settings() -> Settings:
    return Settings()

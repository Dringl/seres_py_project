from pathlib import Path

from fastapi.templating import Jinja2Templates

_WEB = Path(__file__).resolve().parent / "web"
templates = Jinja2Templates(directory=str(_WEB / "templates"))
STATIC_DIR = str(_WEB / "static")

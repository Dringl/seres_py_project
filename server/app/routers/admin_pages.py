from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse, RedirectResponse

from app.config import get_settings
from app.routers.admin_auth import current_admin
from app.templating import templates

router = APIRouter()


def _login_redirect(request: Request) -> RedirectResponse:
    root = request.scope.get("root_path", "")
    return RedirectResponse(url=f"{root}/admin/login", status_code=303)


@router.get("/admin/login", response_class=HTMLResponse, name="admin_login_page")
def login_page(request: Request):
    return templates.TemplateResponse(request, "login.html", {})


@router.get("/admin", response_class=HTMLResponse, name="admin_dashboard")
def dashboard(request: Request):
    if not current_admin(request):
        return _login_redirect(request)
    return templates.TemplateResponse(
        request, "dashboard.html", {"admin": current_admin(request), "gaode_key": get_settings().gaode_web_key}
    )

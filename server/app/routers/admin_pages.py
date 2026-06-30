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
    settings = get_settings()
    return templates.TemplateResponse(
        request,
        "dashboard.html",
        {
            "admin": current_admin(request),
            "gaode_key": settings.gaode_web_key,
            "gaode_secret": settings.gaode_web_secret,
        },
    )


@router.get("/admin/vehicles", response_class=HTMLResponse, name="admin_vehicles")
def vehicles_page(request: Request):
    if not current_admin(request):
        return _login_redirect(request)
    return templates.TemplateResponse(request, "vehicles.html", {"admin": current_admin(request)})


@router.get("/admin/vertiports", response_class=HTMLResponse, name="admin_vertiports")
def vertiports_page(request: Request):
    if not current_admin(request):
        return _login_redirect(request)
    return templates.TemplateResponse(request, "vertiports.html", {"admin": current_admin(request)})


@router.get("/admin/orders", response_class=HTMLResponse, name="admin_orders")
def orders_page(request: Request):
    if not current_admin(request):
        return _login_redirect(request)
    return templates.TemplateResponse(request, "orders.html", {"admin": current_admin(request)})


@router.get("/admin/users", response_class=HTMLResponse, name="admin_users")
def users_page(request: Request):
    if not current_admin(request):
        return _login_redirect(request)
    return templates.TemplateResponse(request, "users.html", {"admin": current_admin(request)})

from fastapi import APIRouter, Depends, Form, HTTPException, Request
from fastapi.responses import RedirectResponse
from sqlalchemy.orm import Session

from app.auth import authenticate
from app.database import get_session

router = APIRouter()


def current_admin(request: Request) -> str | None:
    return request.session.get("admin")


def require_admin(request: Request) -> str:
    user = request.session.get("admin")
    if not user:
        raise HTTPException(status_code=401, detail="login required")
    return user


@router.post("/admin/login")
def login(
    request: Request,
    username: str = Form(...),
    password: str = Form(...),
    session: Session = Depends(get_session),
):
    if not authenticate(session, username, password):
        raise HTTPException(status_code=401, detail="invalid credentials")
    request.session["admin"] = username
    root = request.scope.get("root_path", "")
    return RedirectResponse(url=f"{root}/admin", status_code=303)


@router.post("/admin/logout")
def logout(request: Request):
    request.session.pop("admin", None)
    root = request.scope.get("root_path", "")
    return RedirectResponse(url=f"{root}/admin/login", status_code=303)


@router.get("/admin/whoami")
def whoami(admin: str = Depends(require_admin)) -> dict[str, str]:
    return {"admin": admin}

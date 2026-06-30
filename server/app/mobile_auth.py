"""App 端用户鉴权：itsdangerous 签名 token（持久不过期）+ Bearer 解析依赖。"""
from fastapi import Depends, Header, HTTPException
from itsdangerous import BadSignature, URLSafeSerializer
from sqlalchemy.orm import Session

from app.config import get_settings
from app.database import get_session
from app.models import User

_SALT = "evtol-mobile-auth"


def _serializer() -> URLSafeSerializer:
    return URLSafeSerializer(get_settings().secret_key, salt=_SALT)


def issue_token(user_id: int) -> str:
    return _serializer().dumps({"uid": user_id})


def parse_token(token: str) -> int | None:
    try:
        data = _serializer().loads(token)
        return int(data["uid"])
    except (BadSignature, KeyError, ValueError, TypeError):
        return None


def get_current_user(
    authorization: str | None = Header(default=None),
    session: Session = Depends(get_session),
) -> User:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="未登录")
    uid = parse_token(authorization.split(" ", 1)[1].strip())
    if uid is None:
        raise HTTPException(status_code=401, detail="登录已失效，请重新登录")
    user = session.get(User, uid)
    if user is None:
        raise HTTPException(status_code=401, detail="用户不存在")
    return user

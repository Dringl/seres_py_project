import bcrypt
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import AdminUser
from app.services import now_millis


def hash_password(pw: str) -> str:
    return bcrypt.hashpw(pw.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")


def verify_password(pw: str, hashed: str) -> bool:
    try:
        return bcrypt.checkpw(pw.encode("utf-8"), hashed.encode("utf-8"))
    except (ValueError, TypeError):
        return False


def seed_admin_if_empty(session: Session, username: str, password: str) -> None:
    if session.execute(select(AdminUser)).first() is not None:
        return
    session.add(
        AdminUser(
            username=username,
            password_hash=hash_password(password),
            created_at=now_millis(),
        )
    )


def authenticate(session: Session, username: str, password: str) -> bool:
    admin = session.get(AdminUser, username)
    if admin is None:
        return False
    return verify_password(password, admin.password_hash)

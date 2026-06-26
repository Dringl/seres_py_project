from app.auth import authenticate, hash_password, seed_admin_if_empty, verify_password
from app.database import Base, make_engine, new_session
from app.models import AdminUser
from sqlalchemy import select


def test_hash_verify_roundtrip():
    h = hash_password("secret123")
    assert h != "secret123"
    assert verify_password("secret123", h) is True
    assert verify_password("wrong", h) is False


def _session():
    engine = make_engine("sqlite://")
    Base.metadata.create_all(engine)
    return new_session(engine)


def test_seed_admin_idempotent():
    s = _session()
    seed_admin_if_empty(s, "admin", "secret123")
    s.commit()
    seed_admin_if_empty(s, "admin", "other")
    s.commit()
    rows = s.execute(select(AdminUser)).scalars().all()
    assert len(rows) == 1
    assert rows[0].username == "admin"


def test_authenticate():
    s = _session()
    seed_admin_if_empty(s, "admin", "secret123")
    s.commit()
    assert authenticate(s, "admin", "secret123") is True
    assert authenticate(s, "admin", "bad") is False
    assert authenticate(s, "nobody", "secret123") is False

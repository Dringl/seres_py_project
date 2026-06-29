from app.auth import authenticate, seed_admin_if_empty
from app.config import get_settings
from app.database import Base, make_engine, new_session


def test_admin_seed_from_settings_authenticates():
    settings = get_settings()
    engine = make_engine("sqlite://")
    Base.metadata.create_all(engine)
    s = new_session(engine)
    seed_admin_if_empty(s, settings.admin_username, settings.admin_password)
    s.commit()
    assert authenticate(s, settings.admin_username, settings.admin_password) is True

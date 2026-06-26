import os
os.environ["EVTOL_TESTING"] = "1"

import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import sessionmaker

import app.database as database
from app.database import Base, make_engine
from app.main import app
from app.seed import seed_if_empty


@pytest.fixture
def client():
    engine = make_engine("sqlite://")
    Base.metadata.create_all(engine)
    database._engine = engine
    database.SessionLocal = sessionmaker(bind=engine, expire_on_commit=False)
    with database.SessionLocal() as s:
        seed_if_empty(s)
        s.commit()
    yield TestClient(app)

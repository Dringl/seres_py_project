from contextlib import contextmanager
from collections.abc import Iterator

from sqlalchemy import create_engine, event
from sqlalchemy.engine import Engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker
from sqlalchemy.pool import StaticPool


class Base(DeclarativeBase):
    pass


def make_engine(db_path: str) -> Engine:
    if db_path == "sqlite://" or db_path.startswith("sqlite://"):
        url = db_path
    else:
        url = f"sqlite:///{db_path}"
    kwargs: dict = {"connect_args": {"check_same_thread": False}}
    if url == "sqlite://":
        # 共享同一连接以保证内存库在多线程（如 TestClient）下可见
        kwargs["poolclass"] = StaticPool
    engine = create_engine(url, **kwargs)

    @event.listens_for(engine, "connect")
    def _set_sqlite_pragma(dbapi_conn, _rec):  # noqa: ANN001
        cur = dbapi_conn.cursor()
        cur.execute("PRAGMA journal_mode=WAL")
        cur.execute("PRAGMA foreign_keys=ON")
        cur.execute("PRAGMA busy_timeout=5000")
        cur.close()

    return engine


def new_session(engine: Engine) -> Session:
    return Session(engine)


# 运行期单例：在 app 启动时由 init_app_engine 绑定
SessionLocal: sessionmaker | None = None
_engine: Engine | None = None


def init_app_engine(db_path: str) -> Engine:
    global SessionLocal, _engine
    _engine = make_engine(db_path)
    Base.metadata.create_all(_engine)
    SessionLocal = sessionmaker(bind=_engine, expire_on_commit=False)
    return _engine


def get_engine() -> Engine:
    assert _engine is not None, "engine 未初始化，请先调用 init_app_engine"
    return _engine


def get_session() -> Iterator[Session]:
    assert SessionLocal is not None, "SessionLocal 未初始化"
    session = SessionLocal()
    try:
        yield session
    finally:
        session.close()


@contextmanager
def session_scope() -> Iterator[Session]:
    assert SessionLocal is not None, "SessionLocal 未初始化"
    session = SessionLocal()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()

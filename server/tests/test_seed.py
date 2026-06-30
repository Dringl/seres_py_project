from sqlalchemy import select

from app.database import Base, make_engine, new_session
from app.models import Vehicle, Vertiport
from app.seed import seed_if_empty


def test_seed_populates_once():
    engine = make_engine("sqlite://")
    Base.metadata.create_all(engine)
    with new_session(engine) as session:
        seed_if_empty(session)
        session.commit()
        assert len(session.execute(select(Vertiport)).scalars().all()) == 12
        vehicles = session.execute(select(Vehicle)).scalars().all()
        assert len(vehicles) == 3
        # 飞行器初始停靠在某个停机坪
        assert all(v.current_vertiport_id is not None for v in vehicles)
        # 再次调用不重复插入
        seed_if_empty(session)
        session.commit()
        assert len(session.execute(select(Vertiport)).scalars().all()) == 12

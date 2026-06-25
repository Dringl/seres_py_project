from sqlalchemy import select

from app.database import Base, make_engine, new_session
from app.models import Vehicle, Vertiport


def test_can_create_and_query_vehicle():
    engine = make_engine("sqlite://")  # in-memory
    Base.metadata.create_all(engine)
    with new_session(engine) as session:
        session.add(Vertiport(id="vp001", name="测试坪", latitude=29.5, longitude=106.5))
        session.add(
            Vehicle(
                id="ev001",
                name="Eagle-01",
                latitude=29.5,
                longitude=106.5,
                battery_percent=88,
                online=True,
                status="IDLE",
                current_vertiport_id="vp001",
                updated_at=1000,
            )
        )
        session.commit()
        rows = session.execute(select(Vehicle)).scalars().all()
        assert len(rows) == 1
        assert rows[0].status == "IDLE"
        assert rows[0].current_vertiport_id == "vp001"

from app.database import Base, make_engine, new_session
from app.models import Vehicle, Vertiport
from app.seed import seed_if_empty
from app.services import (
    estimate_price,
    nearest_vehicle_for,
    vehicles_at_vertiport,
)


def _seeded():
    engine = make_engine("sqlite://")
    Base.metadata.create_all(engine)
    session = new_session(engine)
    seed_if_empty(session)
    session.commit()
    return session


def test_estimate_price_matches_fake_formula():
    amount, dist = estimate_price(10.0)
    assert dist == 10.0
    assert amount == 2800 + int(10.0 * 760)


def test_estimate_price_floor_distance():
    amount, dist = estimate_price(0.2)
    assert dist == 1.0
    assert amount == 2800 + int(1.0 * 760)


def test_nearest_vehicle_picks_idle_charged():
    session = _seeded()
    # 上车点取 vp001(江北嘴) 坐标，ev002 就在该坪
    v = nearest_vehicle_for(session, 29.5792, 106.5758)
    assert v is not None
    assert v.id == "ev002"


def test_nearest_vehicle_none_when_low_battery():
    session = _seeded()
    for v in session.query(Vehicle).all():
        v.battery_percent = 10
    session.commit()
    assert nearest_vehicle_for(session, 29.5792, 106.5758) is None


def test_vehicles_at_vertiport():
    session = _seeded()
    at = vehicles_at_vertiport(session, "vp001")
    assert [v.id for v in at] == ["ev002"]
    assert vehicles_at_vertiport(session, "vp005") == []

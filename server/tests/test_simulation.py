from app.database import Base, make_engine, new_session
from app.models import Order, Vehicle, Vertiport
from app.seed import seed_if_empty
from app.services import now_millis
from app.simulation import tick


def _order_in_status(session, status, vehicle_id, pickup="vp001", dest="vp006"):
    v = session.get(Vehicle, vehicle_id)
    v.status = "RESERVED" if status == "RESERVED" else "IN_FLIGHT"
    pvp = session.get(Vertiport, pickup)
    now = now_millis()
    o = Order(
        id=f"O-{status}", pickup_lat=pvp.latitude, pickup_lng=pvp.longitude,
        pickup_vertiport_id=pickup, vehicle_origin_lat=v.latitude, vehicle_origin_lng=v.longitude,
        destination_id=dest, vehicle_id=vehicle_id, status=status,
        amount_cents=5000, distance_km=10.0, currency="CNY", cancellation_fee_cents=0,
        created_at=now, updated_at=now,
    )
    session.add(o)
    session.commit()
    return o


def _seeded():
    engine = make_engine("sqlite://")
    Base.metadata.create_all(engine)
    s = new_session(engine)
    seed_if_empty(s)
    s.commit()
    return s


def test_reserved_vehicle_moves_toward_pickup():
    s = _seeded()
    # 让 ev003(两江机场) 接 vp001 的单，距离远，单步不会到达
    v = s.get(Vehicle, "ev003")
    v.current_vertiport_id = None
    s.commit()
    _order_in_status(s, "RESERVED", "ev003", pickup="vp001")
    before = (v.latitude, v.longitude)
    tick(s, dt_seconds=1.0)
    s.refresh(v)
    assert (v.latitude, v.longitude) != before  # 移动了
    assert v.status == "RESERVED"


def test_reserved_arrives_becomes_boarding():
    s = _seeded()
    v = s.get(Vehicle, "ev002")  # 就在 vp001
    v.current_vertiport_id = None
    v.status = "RESERVED"
    s.commit()
    _order_in_status(s, "RESERVED", "ev002", pickup="vp001")
    tick(s, dt_seconds=1.0)
    order = s.get(Order, "O-RESERVED")
    s.refresh(v)
    assert order.status == "BOARDING"
    assert v.status == "BOARDING"


def test_in_flight_arrival_completes_and_parks():
    s = _seeded()
    v = s.get(Vehicle, "ev003")  # 两江机场=vp006，目的也 vp006 → 立即到达
    v.status = "IN_FLIGHT"
    v.current_vertiport_id = None
    s.commit()
    _order_in_status(s, "IN_FLIGHT", "ev003", pickup="vp001", dest="vp006")
    tick(s, dt_seconds=1.0)
    order = s.get(Order, "O-IN_FLIGHT")
    s.refresh(v)
    assert order.status == "DONE"
    assert v.status == "IDLE"
    assert v.current_vertiport_id == "vp006"


def test_in_flight_arrival_drains_battery():
    s = _seeded()
    v = s.get(Vehicle, "ev003")  # 种子电量 81，在 vp006，目的也 vp006 → 一拍到达
    v.status = "IN_FLIGHT"
    v.current_vertiport_id = None
    s.commit()
    _order_in_status(s, "IN_FLIGHT", "ev003", pickup="vp001", dest="vp006")
    tick(s, dt_seconds=1.0)
    s.refresh(v)
    assert v.battery_percent == 81 - 12  # 航段扣电 12，得 69


def test_battery_drain_has_floor_of_10():
    s = _seeded()
    v = s.get(Vehicle, "ev003")  # 一拍到达的航段
    v.status = "IN_FLIGHT"
    v.current_vertiport_id = None
    v.battery_percent = 10  # 已在地板，扣电后不应为负
    s.commit()
    _order_in_status(s, "IN_FLIGHT", "ev003", pickup="vp001", dest="vp006")
    tick(s, dt_seconds=1.0)
    s.refresh(v)
    assert v.battery_percent >= 10

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.geo import haversine_km, step_toward
from app.models import Order, Vehicle, Vertiport
from app.services import now_millis

SPEEDS = {"RESERVED": 1140.0, "IN_FLIGHT": 2160.0, "RETURNING": 1320.0}  # 基准 95/180/110 km/h ×12
ACTIVE = {"RESERVED", "BOARDING", "IN_FLIGHT", "RETURNING"}
LEG_BATTERY_DROP = {"RESERVED": 4, "IN_FLIGHT": 12, "RETURNING": 5}
BOARDING_TIMEOUT_MS = 180_000   # 飞行器到达上客点后 3 分钟未登机则自动取消
NO_SHOW_FEE_CENTS = 5000        # 未登机自动取消的额外费用（¥50）
CHARGE_INTERVAL_MS = 2000       # 停靠充电：每 2 秒恢复 1% 电量
CHARGE_FULL = 100


def _nearest_vertiport(session: Session, lat: float, lng: float) -> Vertiport:
    ports = session.execute(select(Vertiport)).scalars().all()
    return min(ports, key=lambda p: haversine_km(lat, lng, p.latitude, p.longitude))


def _recover_battery(session: Session, now: int) -> None:
    """停靠在停机坪的空闲飞行器缓慢恢复电量（每 CHARGE_INTERVAL_MS 充 1%）。"""
    parked = session.execute(
        select(Vehicle).where(
            Vehicle.status == "IDLE",
            Vehicle.current_vertiport_id.is_not(None),
            Vehicle.battery_percent < CHARGE_FULL,
        )
    ).scalars().all()
    for v in parked:
        if now - v.updated_at >= CHARGE_INTERVAL_MS:
            v.battery_percent = min(v.battery_percent + 1, CHARGE_FULL)
            v.updated_at = now


def tick(session: Session, dt_seconds: float = 1.0) -> None:
    now = now_millis()
    orders = session.execute(
        select(Order).where(Order.status.in_(ACTIVE))
    ).scalars().all()
    for order in orders:
        vehicle = session.get(Vehicle, order.vehicle_id)
        if vehicle is None:
            continue

        if order.status == "BOARDING":
            # 到达上客点后超时未登机：自动取消 + 额外费用，飞行器恢复空闲（仍停在该坪）
            if now - order.updated_at >= BOARDING_TIMEOUT_MS:
                order.status = "CANCELED"
                order.cancellation_fee_cents = NO_SHOW_FEE_CENTS
                order.updated_at = now
                vehicle.status = "IDLE"
            continue

        speed = SPEEDS.get(order.status)
        if speed is None:
            continue
        max_km = speed / 3600.0 * dt_seconds

        if order.status == "RESERVED":
            target = session.get(Vertiport, order.pickup_vertiport_id)
        elif order.status == "IN_FLIGHT":
            target = session.get(Vertiport, order.destination_id)
        else:  # RETURNING
            target = _nearest_vertiport(session, vehicle.latitude, vehicle.longitude)

        new_lat, new_lng, arrived = step_toward(
            vehicle.latitude, vehicle.longitude, target.latitude, target.longitude, max_km
        )
        vehicle.latitude, vehicle.longitude = new_lat, new_lng
        vehicle.updated_at = now
        order.updated_at = now

        if not arrived:
            continue

        if order.status == "RESERVED":
            vehicle.battery_percent = max(
                vehicle.battery_percent - LEG_BATTERY_DROP["RESERVED"], 10
            )
            order.status = "BOARDING"
            vehicle.status = "BOARDING"
            vehicle.current_vertiport_id = target.id
        elif order.status == "IN_FLIGHT":
            vehicle.battery_percent = max(
                vehicle.battery_percent - LEG_BATTERY_DROP["IN_FLIGHT"], 10
            )
            order.status = "DONE"
            vehicle.status = "IDLE"
            vehicle.current_vertiport_id = target.id
        elif order.status == "RETURNING":
            vehicle.battery_percent = max(
                vehicle.battery_percent - LEG_BATTERY_DROP["RETURNING"], 10
            )
            order.status = "CANCELED"
            vehicle.status = "IDLE"
            vehicle.current_vertiport_id = target.id

    _recover_battery(session, now)
    session.commit()

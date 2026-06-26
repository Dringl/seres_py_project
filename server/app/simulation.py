from sqlalchemy import select
from sqlalchemy.orm import Session

from app.geo import haversine_km, step_toward
from app.models import Order, Vehicle, Vertiport
from app.services import now_millis

SPEEDS = {"RESERVED": 95.0, "IN_FLIGHT": 180.0, "RETURNING": 110.0}
ACTIVE = {"RESERVED", "BOARDING", "IN_FLIGHT", "RETURNING"}
LEG_BATTERY_DROP = {"RESERVED": 4, "IN_FLIGHT": 12, "RETURNING": 5}


def _nearest_vertiport(session: Session, lat: float, lng: float) -> Vertiport:
    ports = session.execute(select(Vertiport)).scalars().all()
    return min(ports, key=lambda p: haversine_km(lat, lng, p.latitude, p.longitude))


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

    session.commit()

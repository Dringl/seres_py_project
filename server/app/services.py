import random
import time

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.geo import haversine_km
from app.models import Vehicle

BASE_FARE_CENTS = 2800
PER_KM_CENTS = 760
MIN_DISTANCE_KM = 1.0
MIN_BATTERY = 40


class NoAvailableVehicle(Exception):
    pass


def now_millis() -> int:
    return int(time.time() * 1000)


def new_order_id() -> str:
    # 完整毫秒 + 4 位随机，降低同毫秒主键冲突概率
    return f"ODR-{now_millis()}-{random.randint(1000, 9999)}"


def estimate_price(distance_km: float) -> tuple[int, float]:
    dist = max(distance_km, MIN_DISTANCE_KM)
    amount = BASE_FARE_CENTS + int(dist * PER_KM_CENTS)
    return amount, dist


def nearest_vehicle_for(session: Session, pickup_lat: float, pickup_lng: float) -> Vehicle | None:
    candidates = session.execute(
        select(Vehicle).where(
            Vehicle.online.is_(True),
            Vehicle.status == "IDLE",
            Vehicle.battery_percent >= MIN_BATTERY,
        )
    ).scalars().all()
    if not candidates:
        return None
    return min(
        candidates,
        key=lambda v: haversine_km(v.latitude, v.longitude, pickup_lat, pickup_lng),
    )


def vehicles_at_vertiport(session: Session, vertiport_id: str) -> list[Vehicle]:
    return session.execute(
        select(Vehicle).where(
            Vehicle.current_vertiport_id == vertiport_id,
            Vehicle.online.is_(True),
        )
    ).scalars().all()

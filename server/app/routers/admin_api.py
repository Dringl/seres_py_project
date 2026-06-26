from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database import get_session
from app.mappers import order_to_dto
from app.models import Order, Vehicle, Vertiport
from app.routers.admin_auth import require_admin
from app.services import vehicles_at_vertiport

router = APIRouter(prefix="/admin/api", dependencies=[Depends(require_admin)])


def vehicle_admin_dict(v: Vehicle) -> dict:
    return {
        "id": v.id,
        "name": v.name,
        "latitude": v.latitude,
        "longitude": v.longitude,
        "batteryPercent": v.battery_percent,
        "online": v.online,
        "status": v.status,
        "currentVertiportId": v.current_vertiport_id,
        "updatedAt": v.updated_at,
    }


def order_admin_dict(session: Session, o: Order) -> dict:
    pickup_vp = session.get(Vertiport, o.pickup_vertiport_id)
    dest_vp = session.get(Vertiport, o.destination_id)
    return order_to_dto(o, pickup_vp, dest_vp).model_dump()


@router.get("/overview")
def overview(session: Session = Depends(get_session)) -> dict:
    vehicles = session.execute(select(Vehicle)).scalars().all()
    orders = session.execute(select(Order)).scalars().all()
    vstatus: dict[str, int] = {}
    for v in vehicles:
        key = v.status.lower()
        vstatus[key] = vstatus.get(key, 0) + 1
    ostatus: dict[str, int] = {}
    for o in orders:
        key = o.status.lower()
        ostatus[key] = ostatus.get(key, 0) + 1
    active = sum(
        1 for o in orders if o.status in {"CREATED", "ASSIGNED", "RESERVED", "BOARDING", "IN_FLIGHT", "RETURNING"}
    )
    occupied = sum(1 for v in vehicles if v.current_vertiport_id and v.online)
    occupied_ports = len({v.current_vertiport_id for v in vehicles if v.current_vertiport_id and v.online})
    return {
        "vehicles": {
            "total": len(vehicles),
            "idle": vstatus.get("idle", 0),
            "reserved": vstatus.get("reserved", 0),
            "boarding": vstatus.get("boarding", 0),
            "in_flight": vstatus.get("in_flight", 0),
            "charging": vstatus.get("charging", 0),
            "offline": vstatus.get("offline", 0),
        },
        "orders": {"total": len(orders), "active": active, "done": ostatus.get("done", 0), "canceled": ostatus.get("canceled", 0)},
        "parkedVehicles": occupied,
        "occupiedVertiports": occupied_ports,
    }


@router.get("/vehicles")
def list_vehicles(session: Session = Depends(get_session)) -> list[dict]:
    rows = session.execute(select(Vehicle)).scalars().all()
    return [vehicle_admin_dict(v) for v in rows]


@router.get("/vertiports")
def list_vertiports(session: Session = Depends(get_session)) -> list[dict]:
    rows = session.execute(select(Vertiport)).scalars().all()
    out = []
    for vp in rows:
        at = vehicles_at_vertiport(session, vp.id)
        out.append(
            {
                "id": vp.id,
                "name": vp.name,
                "latitude": vp.latitude,
                "longitude": vp.longitude,
                "occupied": len(at) > 0,
                "vehicleCount": len(at),
                "vehicleIds": [v.id for v in at],
            }
        )
    return out


@router.get("/orders")
def list_orders(session: Session = Depends(get_session)) -> list[dict]:
    rows = session.execute(select(Order).order_by(Order.created_at.desc())).scalars().all()
    return [order_admin_dict(session, o) for o in rows]

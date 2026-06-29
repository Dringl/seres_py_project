import random

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.database import get_session
from app.mappers import order_to_dto
from app.models import Order, Vehicle, Vertiport
from app.routers.admin_auth import require_admin
from app.services import now_millis, vehicles_at_vertiport

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


class VehicleCreate(BaseModel):
    name: str
    latitude: float
    longitude: float
    batteryPercent: int = 100
    status: str = "IDLE"
    currentVertiportId: str | None = None
    id: str | None = None


class VehicleUpdate(BaseModel):
    name: str | None = None
    latitude: float | None = None
    longitude: float | None = None
    batteryPercent: int | None = None
    online: bool | None = None
    status: str | None = None
    currentVertiportId: str | None = None


def _gen_vehicle_id(session: Session) -> str:
    while True:
        cand = f"ev{random.randint(100, 999)}"
        if session.get(Vehicle, cand) is None:
            return cand


@router.post("/vehicles", status_code=201)
def create_vehicle(body: VehicleCreate, session: Session = Depends(get_session)) -> dict:
    vid = body.id or _gen_vehicle_id(session)
    if session.get(Vehicle, vid) is not None:
        raise HTTPException(status_code=409, detail="vehicle id exists")
    v = Vehicle(
        id=vid,
        name=body.name,
        latitude=body.latitude,
        longitude=body.longitude,
        battery_percent=body.batteryPercent,
        online=True,
        status=body.status,
        current_vertiport_id=body.currentVertiportId,
        updated_at=now_millis(),
    )
    session.add(v)
    session.commit()
    session.refresh(v)
    return vehicle_admin_dict(v)


@router.put("/vehicles/{vehicle_id}")
def update_vehicle(vehicle_id: str, body: VehicleUpdate, session: Session = Depends(get_session)) -> dict:
    v = session.get(Vehicle, vehicle_id)
    if v is None:
        raise HTTPException(status_code=404, detail="vehicle not found")
    if body.name is not None:
        v.name = body.name
    if body.latitude is not None:
        v.latitude = body.latitude
    if body.longitude is not None:
        v.longitude = body.longitude
    if body.batteryPercent is not None:
        v.battery_percent = body.batteryPercent
    if body.online is not None:
        v.online = body.online
    if body.status is not None:
        v.status = body.status
    if body.currentVertiportId is not None:
        v.current_vertiport_id = body.currentVertiportId or None
    v.updated_at = now_millis()
    session.commit()
    session.refresh(v)
    return vehicle_admin_dict(v)


@router.delete("/vehicles/{vehicle_id}", status_code=204)
def delete_vehicle(vehicle_id: str, session: Session = Depends(get_session)) -> None:
    v = session.get(Vehicle, vehicle_id)
    if v is None:
        raise HTTPException(status_code=404, detail="vehicle not found")
    session.delete(v)
    session.commit()


class VertiportCreate(BaseModel):
    id: str
    name: str
    latitude: float
    longitude: float


class VertiportUpdate(BaseModel):
    name: str | None = None
    latitude: float | None = None
    longitude: float | None = None


@router.post("/vertiports", status_code=201)
def create_vertiport(body: VertiportCreate, session: Session = Depends(get_session)) -> dict:
    if session.get(Vertiport, body.id) is not None:
        raise HTTPException(status_code=409, detail="vertiport id exists")
    vp = Vertiport(id=body.id, name=body.name, latitude=body.latitude, longitude=body.longitude)
    session.add(vp)
    session.commit()
    return {"id": vp.id, "name": vp.name, "latitude": vp.latitude, "longitude": vp.longitude}


@router.put("/vertiports/{vertiport_id}")
def update_vertiport(vertiport_id: str, body: VertiportUpdate, session: Session = Depends(get_session)) -> dict:
    vp = session.get(Vertiport, vertiport_id)
    if vp is None:
        raise HTTPException(status_code=404, detail="vertiport not found")
    if body.name is not None:
        vp.name = body.name
    if body.latitude is not None:
        vp.latitude = body.latitude
    if body.longitude is not None:
        vp.longitude = body.longitude
    session.commit()
    return {"id": vp.id, "name": vp.name, "latitude": vp.latitude, "longitude": vp.longitude}


@router.delete("/vertiports/{vertiport_id}", status_code=204)
def delete_vertiport(vertiport_id: str, session: Session = Depends(get_session)) -> None:
    vp = session.get(Vertiport, vertiport_id)
    if vp is None:
        raise HTTPException(status_code=404, detail="vertiport not found")
    try:
        session.delete(vp)
        session.commit()
    except IntegrityError:
        session.rollback()
        raise HTTPException(status_code=409, detail="vertiport is referenced by vehicles or orders")

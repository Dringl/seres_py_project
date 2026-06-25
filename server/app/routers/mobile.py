from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database import get_session
from app.geo import haversine_km
from app.mappers import vehicle_to_dto, vertiport_to_dto
from app.models import Vehicle, Vertiport
from app.schemas import EvtolDto, NearbyRequest, OccupancyDto, VertiportDto
from app.services import vehicles_at_vertiport

router = APIRouter()


@router.get("/vertiports", response_model=list[VertiportDto])
def get_vertiports(session: Session = Depends(get_session)):
    rows = session.execute(select(Vertiport)).scalars().all()
    return [vertiport_to_dto(v) for v in rows]


@router.get("/vehicles", response_model=list[EvtolDto])
def get_vehicles(session: Session = Depends(get_session)):
    rows = session.execute(select(Vehicle)).scalars().all()
    return [vehicle_to_dto(v) for v in rows]


@router.post("/vehicles/nearby", response_model=list[EvtolDto])
def nearby(req: NearbyRequest, session: Session = Depends(get_session)):
    rows = session.execute(select(Vehicle)).scalars().all()
    rows.sort(key=lambda v: haversine_km(v.latitude, v.longitude, req.latitude, req.longitude))
    return [vehicle_to_dto(v) for v in rows[:3]]


@router.get("/vertiports/{vertiport_id}/occupancy", response_model=OccupancyDto)
def occupancy(vertiport_id: str, session: Session = Depends(get_session)):
    vp = session.get(Vertiport, vertiport_id)
    if vp is None:
        raise HTTPException(status_code=404, detail="vertiport not found")
    at = vehicles_at_vertiport(session, vertiport_id)
    return OccupancyDto(
        vertiportId=vp.id,
        name=vp.name,
        occupied=len(at) > 0,
        vehicles=[vehicle_to_dto(v) for v in at],
    )

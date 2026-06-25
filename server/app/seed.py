from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Vehicle, Vertiport

VERTIPORTS = [
    {"id": "vp001", "name": "江北嘴商务区停机坪", "latitude": 29.5792, "longitude": 106.5758},
    {"id": "vp002", "name": "解放碑停机坪", "latitude": 29.5582, "longitude": 106.5755},
    {"id": "vp003", "name": "观音桥停机坪", "latitude": 29.5845, "longitude": 106.5332},
    {"id": "vp004", "name": "南滨路停机坪", "latitude": 29.5468, "longitude": 106.5851},
    {"id": "vp005", "name": "重庆北站停机坪", "latitude": 29.6142, "longitude": 106.5511},
    {"id": "vp006", "name": "两江机场停机坪", "latitude": 29.7211, "longitude": 106.6439},
    {"id": "vp007", "name": "重庆西站停机坪", "latitude": 29.5036, "longitude": 106.4301},
    {"id": "vp008", "name": "大学城停机坪", "latitude": 29.5976, "longitude": 106.2998},
    {"id": "vp009", "name": "照母山停机坪", "latitude": 29.6318, "longitude": 106.5357},
    {"id": "vp010", "name": "巴南龙洲湾停机坪", "latitude": 29.4042, "longitude": 106.5414},
    {"id": "vp011", "name": "磁器口停机坪", "latitude": 29.5829, "longitude": 106.4498},
    {"id": "vp012", "name": "南岸茶园停机坪", "latitude": 29.4947, "longitude": 106.6378},
]

# location 与 vp003 / vp001 / vp006 对应，初始停靠在该坪
VEHICLES = [
    {"id": "ev001", "name": "Eagle-01", "latitude": 29.5845, "longitude": 106.5332,
     "battery_percent": 88, "current_vertiport_id": "vp003"},
    {"id": "ev002", "name": "Eagle-02", "latitude": 29.5792, "longitude": 106.5758,
     "battery_percent": 74, "current_vertiport_id": "vp001"},
    {"id": "ev003", "name": "Eagle-03", "latitude": 29.7211, "longitude": 106.6439,
     "battery_percent": 81, "current_vertiport_id": "vp006"},
]


def seed_if_empty(session: Session) -> None:
    if session.execute(select(Vertiport)).first() is not None:
        return
    for vp in VERTIPORTS:
        session.add(Vertiport(**vp))
    for v in VEHICLES:
        session.add(
            Vehicle(
                id=v["id"],
                name=v["name"],
                latitude=v["latitude"],
                longitude=v["longitude"],
                battery_percent=v["battery_percent"],
                online=True,
                status="IDLE",
                current_vertiport_id=v["current_vertiport_id"],
                updated_at=0,
            )
        )

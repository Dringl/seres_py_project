import pytest

import app.database as database
from app.simulation import tick


@pytest.fixture(autouse=True)
def _mobile_auth(client):
    token = client.post("/auth/register", json={"username": "rider", "password": "ridepass"}).json()["token"]
    client.headers["Authorization"] = f"Bearer {token}"


def _create(client):
    return client.post(
        "/orders",
        json={"pickup": {"latitude": 29.5792, "longitude": 106.5758},
              "destinationVertiportId": "vp006",
              "pickupVertiportId": "vp001"},
    ).json()


def _drive(max_ticks=2000):
    # 用与请求同一个引擎的 session 反复 tick，直到无活动订单
    for _ in range(max_ticks):
        with database.session_scope() as s:
            from sqlalchemy import select
            from app.models import Order
            active = s.execute(
                select(Order).where(Order.status.in_(["RESERVED", "IN_FLIGHT", "RETURNING"]))
            ).scalars().all()
            if not active:
                return
            tick(s, dt_seconds=5.0)


def test_full_trip_reaches_done(client):
    order = _create(client)
    oid = order["id"]
    # 推进到 BOARDING
    for _ in range(2000):
        with database.session_scope() as s:
            tick(s, dt_seconds=5.0)
        cur = client.get(f"/orders/{oid}").json()
        if cur["status"] == "BOARDING":
            break
    assert client.get(f"/orders/{oid}").json()["status"] == "BOARDING"
    # 确认登机 → 推进到 DONE
    assert client.post(f"/orders/{oid}/board").status_code == 200
    _drive()
    final = client.get(f"/orders/{oid}").json()
    assert final["status"] == "DONE"
    # 目的坪现在有车
    assert client.get("/vertiports/vp006/occupancy").json()["occupied"] is True

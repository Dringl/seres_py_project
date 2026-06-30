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


def test_board_rejected_when_not_boarding(client):
    order = _create(client)  # 初始 RESERVED
    resp = client.post(f"/orders/{order['id']}/board")
    assert resp.status_code == 409


def test_cancel_when_reserved_sets_returning(client):
    order = _create(client)
    resp = client.post(f"/orders/{order['id']}/cancel")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "RETURNING"
    assert body["price"]["cancellationFeeCents"] >= 1200


def test_cancel_unknown_404(client):
    assert client.post("/orders/nope/cancel").status_code == 404


def test_inflight_vehicle_frees_pickup_pad(client):
    # 下单：起点 vp001（ev002 初始停靠于此），目的地 vp006
    order = _create(client)
    oid = order["id"]

    # 推进模拟直到登机（BOARDING）
    for _ in range(2000):
        with database.session_scope() as s:
            tick(s, dt_seconds=5.0)
        cur = client.get(f"/orders/{oid}").json()
        if cur["status"] == "BOARDING":
            break
    assert client.get(f"/orders/{oid}").json()["status"] == "BOARDING"

    # 登机时车辆已停靠 vp001，停机坪被占用
    occ = client.get("/vertiports/vp001/occupancy").json()
    assert occ["occupied"] is True
    assert "ev002" in [v["id"] for v in occ["vehicles"]]

    # 确认登机 → 起飞（IN_FLIGHT）
    assert client.post(f"/orders/{oid}/board").status_code == 200

    # 起飞后停机坪应被释放，列表中不再包含 ev002
    occ = client.get("/vertiports/vp001/occupancy").json()
    assert occ["occupied"] is False
    assert "ev002" not in [v["id"] for v in occ["vehicles"]]

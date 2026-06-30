import pytest


@pytest.fixture(autouse=True)
def _mobile_auth(client):
    token = client.post("/auth/register", json={"username": "rider", "password": "ridepass"}).json()["token"]
    client.headers["Authorization"] = f"Bearer {token}"


def test_estimate_price(client):
    resp = client.post(
        "/price/estimate",
        json={"pickup": {"latitude": 29.5792, "longitude": 106.5758},
              "destinationVertiportId": "vp006"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["currency"] == "CNY"
    assert body["amountCents"] > 2800


def test_create_order_and_fetch(client):
    resp = client.post(
        "/orders",
        json={"pickup": {"latitude": 29.5792, "longitude": 106.5758},
              "destinationVertiportId": "vp006",
              "pickupVertiportId": "vp001"},
    )
    assert resp.status_code == 200
    order = resp.json()
    assert order["status"] == "RESERVED"
    assert order["vehicleId"] == "ev002"
    assert order["pickupVertiport"]["id"] == "vp001"
    assert order["destination"]["id"] == "vp006"
    # 车已离坪
    occ = client.get("/vertiports/vp001/occupancy").json()
    assert occ["occupied"] is False
    # 可按 id 取回
    again = client.get(f"/orders/{order['id']}")
    assert again.status_code == 200
    assert again.json()["id"] == order["id"]
    # 出现在历史
    history = client.get("/orders").json()
    assert any(o["id"] == order["id"] for o in history)


def test_create_order_no_vehicle_returns_409(client):
    # 把所有车改为离线
    client_get = client.get("/vehicles").json()
    assert client_get  # 有车
    # 直接连下 4 单，第 4 单应 409（只有 3 架车）
    statuses = []
    for _ in range(4):
        r = client.post(
            "/orders",
            json={"pickup": {"latitude": 29.5792, "longitude": 106.5758},
                  "destinationVertiportId": "vp006",
                  "pickupVertiportId": "vp001"},
        )
        statuses.append(r.status_code)
    assert statuses.count(409) >= 1


def test_get_order_404(client):
    assert client.get("/orders/nope").status_code == 404

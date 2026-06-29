def _create_order(auth_client):
    return auth_client.post(
        "/orders",
        json={"pickup": {"latitude": 29.5792, "longitude": 106.5758},
              "destinationVertiportId": "vp006", "pickupVertiportId": "vp001"},
    ).json()


def test_force_cancel(auth_client):
    o = _create_order(auth_client)
    resp = auth_client.post(f"/admin/api/orders/{o['id']}/cancel")
    assert resp.status_code == 200
    assert resp.json()["status"] == "CANCELED"
    # 车回到 IDLE 且停靠某坪
    veh = next(v for v in auth_client.get("/admin/api/vehicles").json() if v["id"] == o["vehicleId"])
    assert veh["status"] == "IDLE"
    assert veh["currentVertiportId"] is not None


def test_force_status(auth_client):
    o = _create_order(auth_client)
    resp = auth_client.post(f"/admin/api/orders/{o['id']}/status", json={"status": "FAILED"})
    assert resp.status_code == 200
    assert resp.json()["status"] == "FAILED"


def test_force_status_invalid(auth_client):
    o = _create_order(auth_client)
    assert auth_client.post(f"/admin/api/orders/{o['id']}/status", json={"status": "NONSENSE"}).status_code == 422


def test_force_cancel_404(auth_client):
    assert auth_client.post("/admin/api/orders/nope/cancel").status_code == 404

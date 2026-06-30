def test_create_vehicle(auth_client):
    resp = auth_client.post(
        "/admin/api/vehicles",
        json={"name": "Eagle-09", "latitude": 29.6, "longitude": 106.5, "batteryPercent": 90, "currentVertiportId": "vp005"},
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["name"] == "Eagle-09"
    assert body["status"] == "IDLE"
    assert auth_client.get("/admin/api/vehicles").json().__len__() == 4
    # vp005 现在被占用
    assert auth_client.get("/vertiports/vp005/occupancy").json()["occupied"] is True


def test_update_vehicle(auth_client):
    resp = auth_client.put("/admin/api/vehicles/ev001", json={"status": "MAINTENANCE", "batteryPercent": 30})
    assert resp.status_code == 200
    assert resp.json()["status"] == "MAINTENANCE"
    assert resp.json()["batteryPercent"] == 30


def test_update_vehicle_404(auth_client):
    assert auth_client.put("/admin/api/vehicles/nope", json={"status": "IDLE"}).status_code == 404


def test_delete_vehicle(auth_client):
    assert auth_client.delete("/admin/api/vehicles/ev003").status_code == 204
    assert auth_client.get("/admin/api/vehicles").json().__len__() == 2


def test_vehicle_crud_requires_auth(client):
    assert client.post("/admin/api/vehicles", json={"name": "x", "latitude": 1, "longitude": 2}).status_code == 401

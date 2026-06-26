def test_overview_requires_auth(client):
    assert client.get("/admin/api/overview").status_code == 401


def test_overview(auth_client):
    data = auth_client.get("/admin/api/overview").json()
    assert data["vehicles"]["total"] == 3
    assert data["vehicles"]["idle"] == 3
    assert "occupiedVertiports" in data
    assert data["orders"]["total"] == 0


def test_admin_vehicles(auth_client):
    rows = auth_client.get("/admin/api/vehicles").json()
    assert len(rows) == 3
    ev = next(r for r in rows if r["id"] == "ev002")
    assert ev["currentVertiportId"] == "vp001"
    assert ev["batteryPercent"] == 74


def test_admin_vertiports(auth_client):
    rows = auth_client.get("/admin/api/vertiports").json()
    assert len(rows) == 12
    vp001 = next(r for r in rows if r["id"] == "vp001")
    assert vp001["occupied"] is True
    assert vp001["vehicleCount"] == 1


def test_admin_orders_empty(auth_client):
    assert auth_client.get("/admin/api/orders").json() == []

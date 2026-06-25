def test_get_vertiports(client):
    resp = client.get("/vertiports")
    assert resp.status_code == 200
    data = resp.json()
    assert len(data) == 12
    assert data[0]["location"]["latitude"] == 29.5792


def test_nearby_returns_three_sorted(client):
    resp = client.post("/vehicles/nearby", json={"latitude": 29.5792, "longitude": 106.5758})
    assert resp.status_code == 200
    data = resp.json()
    assert len(data) == 3
    assert data[0]["id"] == "ev002"  # 江北嘴最近
    assert "batteryPercent" in data[0]


def test_occupancy_occupied(client):
    resp = client.get("/vertiports/vp001/occupancy")
    assert resp.status_code == 200
    data = resp.json()
    assert data["vertiportId"] == "vp001"
    assert data["occupied"] is True
    assert data["vehicles"][0]["id"] == "ev002"


def test_occupancy_empty(client):
    resp = client.get("/vertiports/vp005/occupancy")
    assert resp.json()["occupied"] is False
    assert resp.json()["vehicles"] == []


def test_occupancy_404(client):
    assert client.get("/vertiports/nope/occupancy").status_code == 404

def test_create_vertiport(auth_client):
    resp = auth_client.post(
        "/admin/api/vertiports",
        json={"id": "vp099", "name": "测试新坪", "latitude": 29.7, "longitude": 106.4},
    )
    assert resp.status_code == 201
    assert resp.json()["id"] == "vp099"
    assert len(auth_client.get("/admin/api/vertiports").json()) == 13


def test_update_vertiport(auth_client):
    resp = auth_client.put("/admin/api/vertiports/vp001", json={"name": "江北嘴改名"})
    assert resp.status_code == 200
    assert resp.json()["name"] == "江北嘴改名"


def test_delete_unreferenced_vertiport(auth_client):
    auth_client.post("/admin/api/vertiports", json={"id": "vp099", "name": "临时", "latitude": 1, "longitude": 2})
    assert auth_client.delete("/admin/api/vertiports/vp099").status_code == 204


def test_delete_referenced_vertiport_409(auth_client):
    # vp001 有 ev002 停靠（current_vertiport_id 外键引用）
    assert auth_client.delete("/admin/api/vertiports/vp001").status_code == 409

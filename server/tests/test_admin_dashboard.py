def test_dashboard_requires_auth(client):
    resp = client.get("/admin", follow_redirects=False)
    assert resp.status_code == 303


def test_dashboard_renders_when_auth(auth_client):
    resp = auth_client.get("/admin")
    assert resp.status_code == 200
    html = resp.text
    for marker in ["id=\"overview-cards\"", "id=\"fleet-table\"", "id=\"occupancy-panel\"", "id=\"map\"", "dashboard.js"]:
        assert marker in html

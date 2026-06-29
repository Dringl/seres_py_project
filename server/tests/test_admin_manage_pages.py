import pytest


@pytest.mark.parametrize("path", ["/admin/vehicles", "/admin/vertiports"])
def test_manage_redirects_unauth(client, path):
    resp = client.get(path, follow_redirects=False)
    assert resp.status_code == 303


def test_vehicles_page(auth_client):
    html = auth_client.get("/admin/vehicles").text
    assert "id=\"vehicles-table\"" in html and "admin.js" in html


def test_vertiports_page(auth_client):
    html = auth_client.get("/admin/vertiports").text
    assert "id=\"vertiports-table\"" in html and "admin.js" in html

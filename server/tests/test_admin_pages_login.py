def test_login_page_renders(client):
    resp = client.get("/admin/login")
    assert resp.status_code == 200
    assert "text/html" in resp.headers["content-type"]
    assert "<form" in resp.text and "password" in resp.text


def test_dashboard_redirects_when_unauth(client):
    resp = client.get("/admin", follow_redirects=False)
    assert resp.status_code == 303
    assert "/admin/login" in resp.headers["location"]


def test_static_css_served(client):
    resp = client.get("/static/admin.css")
    assert resp.status_code == 200
    assert "text/css" in resp.headers["content-type"]

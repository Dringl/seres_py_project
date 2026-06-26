def test_login_wrong_password_401(client):
    resp = client.post(
        "/admin/login",
        data={"username": "admin", "password": "nope"},
        follow_redirects=False,
    )
    assert resp.status_code == 401


def test_login_success_sets_session(client):
    resp = client.post(
        "/admin/login",
        data={"username": "admin", "password": "secret123"},
        follow_redirects=False,
    )
    assert resp.status_code == 303
    # session cookie 已设置，后续访问受保护资源不再 401（用一个受保护探针）
    probe = client.get("/admin/whoami")
    assert probe.status_code == 200
    assert probe.json() == {"admin": "admin"}


def test_protected_requires_login(client):
    assert client.get("/admin/whoami").status_code == 401


def test_logout_clears_session(auth_client):
    assert auth_client.get("/admin/whoami").status_code == 200
    auth_client.post("/admin/logout")
    assert auth_client.get("/admin/whoami").status_code == 401

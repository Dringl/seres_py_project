def _register(client, username, password="ridepass"):
    return client.post("/auth/register", json={"username": username, "password": password})


def _auth(token):
    return {"Authorization": f"Bearer {token}"}


def test_register_and_me(client):
    r = _register(client, "alice")
    assert r.status_code == 200
    body = r.json()
    assert body["username"] == "alice"
    assert body["userId"] >= 1
    assert body["token"]
    me = client.get("/auth/me", headers=_auth(body["token"]))
    assert me.status_code == 200
    assert me.json()["username"] == "alice"


def test_register_duplicate_409(client):
    _register(client, "bob")
    assert _register(client, "bob").status_code == 409


def test_register_too_short_422(client):
    assert client.post("/auth/register", json={"username": "ab", "password": "ridepass"}).status_code == 422
    assert client.post("/auth/register", json={"username": "abcd", "password": "123"}).status_code == 422


def test_login_ok_and_wrong(client):
    _register(client, "carol", "secret1")
    ok = client.post("/auth/login", json={"username": "carol", "password": "secret1"})
    assert ok.status_code == 200 and ok.json()["token"]
    assert client.post("/auth/login", json={"username": "carol", "password": "nope"}).status_code == 401
    assert client.post("/auth/login", json={"username": "ghost", "password": "secret1"}).status_code == 401


def test_protected_requires_auth(client):
    assert client.get("/vertiports").status_code == 401
    assert client.get("/orders").status_code == 401
    assert client.post("/orders", json={
        "pickup": {"latitude": 29.5792, "longitude": 106.5758},
        "destinationVertiportId": "vp006",
        "pickupVertiportId": "vp001",
    }).status_code == 401


def test_orders_isolated_per_user(client):
    ta = _register(client, "user_a").json()["token"]
    tb = _register(client, "user_b").json()["token"]
    created = client.post("/orders", headers=_auth(ta), json={
        "pickup": {"latitude": 29.5792, "longitude": 106.5758},
        "destinationVertiportId": "vp006",
        "pickupVertiportId": "vp001",
    })
    assert created.status_code == 200
    oid = created.json()["id"]
    assert created.json()["userId"] is not None
    assert any(o["id"] == oid for o in client.get("/orders", headers=_auth(ta)).json())
    assert all(o["id"] != oid for o in client.get("/orders", headers=_auth(tb)).json())
    assert client.get(f"/orders/{oid}", headers=_auth(tb)).status_code == 404
    assert client.get(f"/orders/{oid}", headers=_auth(ta)).status_code == 200

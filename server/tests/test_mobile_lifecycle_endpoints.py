def _create(client):
    return client.post(
        "/orders",
        json={"pickup": {"latitude": 29.5792, "longitude": 106.5758},
              "destinationVertiportId": "vp006",
              "pickupVertiportId": "vp001"},
    ).json()


def test_board_rejected_when_not_boarding(client):
    order = _create(client)  # 初始 RESERVED
    resp = client.post(f"/orders/{order['id']}/board")
    assert resp.status_code == 409


def test_cancel_when_reserved_sets_returning(client):
    order = _create(client)
    resp = client.post(f"/orders/{order['id']}/cancel")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "RETURNING"
    assert body["price"]["cancellationFeeCents"] >= 1200


def test_cancel_unknown_404(client):
    assert client.post("/orders/nope/cancel").status_code == 404

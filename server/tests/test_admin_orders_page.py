def test_orders_page_redirects_unauth(client):
    assert client.get("/admin/orders", follow_redirects=False).status_code == 303


def test_orders_page_renders(auth_client):
    html = auth_client.get("/admin/orders").text
    assert "id=\"orders-table\"" in html and "orders.js" in html

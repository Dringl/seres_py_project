import math

from app.geo import haversine_km, interpolate, step_toward


def test_haversine_zero():
    assert haversine_km(29.5, 106.5, 29.5, 106.5) == 0.0


def test_haversine_known_distance():
    # 江北嘴(29.5792,106.5758) → 解放碑(29.5582,106.5755) 约 2.3km
    d = haversine_km(29.5792, 106.5758, 29.5582, 106.5755)
    assert 2.0 < d < 2.7


def test_interpolate_midpoint():
    lat, lng = interpolate(0.0, 0.0, 2.0, 4.0, 0.5)
    assert math.isclose(lat, 1.0)
    assert math.isclose(lng, 2.0)


def test_step_toward_arrives_when_close():
    lat, lng, arrived = step_toward(29.50, 106.50, 29.5001, 106.5001, max_km=1.0)
    assert arrived is True
    assert math.isclose(lat, 29.5001)
    assert math.isclose(lng, 106.5001)


def test_step_toward_partial_move():
    lat, lng, arrived = step_toward(29.50, 106.50, 30.50, 106.50, max_km=1.0)
    assert arrived is False
    assert 29.50 < lat < 30.50

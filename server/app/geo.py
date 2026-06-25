import math

EARTH_RADIUS_KM = 6371.0


def haversine_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    d_lat = math.radians(lat2 - lat1)
    d_lng = math.radians(lng2 - lng1)
    a = (
        math.sin(d_lat / 2) ** 2
        + math.sin(d_lng / 2) ** 2 * math.cos(math.radians(lat1)) * math.cos(math.radians(lat2))
    )
    c = 2 * math.asin(math.sqrt(min(max(a, 0.0), 1.0)))
    return EARTH_RADIUS_KM * c


def interpolate(lat1: float, lng1: float, lat2: float, lng2: float, ratio: float) -> tuple[float, float]:
    r = min(max(ratio, 0.0), 1.0)
    return (lat1 + (lat2 - lat1) * r, lng1 + (lng2 - lng1) * r)


def step_toward(lat: float, lng: float, tlat: float, tlng: float, max_km: float) -> tuple[float, float, bool]:
    remaining = haversine_km(lat, lng, tlat, tlng)
    if remaining <= max_km or remaining == 0.0:
        return (tlat, tlng, True)
    ratio = max_km / remaining
    new_lat, new_lng = interpolate(lat, lng, tlat, tlng, ratio)
    return (new_lat, new_lng, False)

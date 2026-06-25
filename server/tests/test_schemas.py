from app.mappers import vehicle_to_dto, vertiport_to_dto
from app.models import Vehicle, Vertiport
from app.schemas import EvtolDto, OrderDto


def test_evtol_dto_json_keys_match_android():
    v = Vehicle(id="ev001", name="Eagle-01", latitude=29.5, longitude=106.5,
                battery_percent=88, online=True, status="IDLE",
                current_vertiport_id="vp001", updated_at=0)
    dto = vehicle_to_dto(v)
    data = dto.model_dump()
    assert set(data.keys()) == {"id", "name", "location", "batteryPercent", "online", "status"}
    assert data["location"] == {"latitude": 29.5, "longitude": 106.5}
    assert data["batteryPercent"] == 88


def test_vertiport_dto_shape():
    vp = Vertiport(id="vp001", name="坪", latitude=1.0, longitude=2.0)
    data = vertiport_to_dto(vp).model_dump()
    assert data == {"id": "vp001", "name": "坪", "location": {"latitude": 1.0, "longitude": 2.0}}


def test_order_dto_has_full_fields():
    fields = set(OrderDto.model_fields.keys())
    assert fields == {
        "id", "vehicleId", "status", "price", "createdAt", "updatedAt",
        "pickup", "pickupVertiport", "vehicleOrigin", "destination",
    }

from app.models import Order, Vehicle, Vertiport
from app.schemas import (
    EvtolDto,
    GeoPointDto,
    OrderDto,
    PriceEstimateDto,
    VertiportDto,
)


def vertiport_to_dto(vp: Vertiport) -> VertiportDto:
    return VertiportDto(
        id=vp.id,
        name=vp.name,
        location=GeoPointDto(latitude=vp.latitude, longitude=vp.longitude),
    )


def vehicle_to_dto(v: Vehicle) -> EvtolDto:
    return EvtolDto(
        id=v.id,
        name=v.name,
        location=GeoPointDto(latitude=v.latitude, longitude=v.longitude),
        batteryPercent=v.battery_percent,
        online=v.online,
        status=v.status,
    )


def order_to_dto(order: Order, pickup_vp: Vertiport, dest_vp: Vertiport) -> OrderDto:
    return OrderDto(
        id=order.id,
        vehicleId=order.vehicle_id,
        status=order.status,
        price=PriceEstimateDto(
            amountCents=order.amount_cents,
            distanceKm=order.distance_km,
            currency=order.currency,
            cancellationFeeCents=order.cancellation_fee_cents,
        ),
        createdAt=order.created_at,
        updatedAt=order.updated_at,
        pickup=GeoPointDto(latitude=order.pickup_lat, longitude=order.pickup_lng),
        pickupVertiport=vertiport_to_dto(pickup_vp),
        vehicleOrigin=GeoPointDto(
            latitude=order.vehicle_origin_lat, longitude=order.vehicle_origin_lng
        ),
        destination=vertiport_to_dto(dest_vp),
    )

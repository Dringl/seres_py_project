from pydantic import BaseModel


class GeoPointDto(BaseModel):
    latitude: float
    longitude: float


class VertiportDto(BaseModel):
    id: str
    name: str
    location: GeoPointDto


class EvtolDto(BaseModel):
    id: str
    name: str
    location: GeoPointDto
    batteryPercent: int
    online: bool
    status: str


class PriceEstimateDto(BaseModel):
    amountCents: int
    distanceKm: float
    currency: str
    cancellationFeeCents: int = 0


class OrderDto(BaseModel):
    id: str
    vehicleId: str
    status: str
    price: PriceEstimateDto
    createdAt: int
    updatedAt: int
    pickup: GeoPointDto
    pickupVertiport: VertiportDto
    vehicleOrigin: GeoPointDto
    destination: VertiportDto


class OccupancyDto(BaseModel):
    vertiportId: str
    name: str
    occupied: bool
    vehicles: list[EvtolDto]


class NearbyRequest(BaseModel):
    latitude: float
    longitude: float
    radiusKm: float = 15.0


class EstimateRequest(BaseModel):
    pickup: GeoPointDto
    destinationVertiportId: str


class CreateOrderRequest(BaseModel):
    pickup: GeoPointDto
    destinationVertiportId: str
    pickupVertiportId: str

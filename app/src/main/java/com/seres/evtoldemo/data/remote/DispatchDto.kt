package com.seres.evtoldemo.data.remote

data class GeoPointDto(
    val latitude: Double,
    val longitude: Double
)

data class VertiportDto(
    val id: String,
    val name: String,
    val location: GeoPointDto
)

data class EvtolDto(
    val id: String,
    val name: String,
    val location: GeoPointDto,
    val batteryPercent: Int,
    val online: Boolean,
    val status: String
)

data class PriceEstimateDto(
    val amountCents: Int,
    val distanceKm: Double,
    val currency: String,
    val cancellationFeeCents: Int = 0
)

data class NearbyRequestDto(
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Double = 15.0
)

data class EstimateRequestDto(
    val pickup: GeoPointDto,
    val destinationVertiportId: String
)

data class CreateOrderRequestDto(
    val pickup: GeoPointDto,
    val destinationVertiportId: String,
    val pickupVertiportId: String
)

data class OrderDto(
    val id: String,
    val vehicleId: String,
    val status: String,
    val price: PriceEstimateDto,
    val createdAt: Long,
    val updatedAt: Long,
    val pickup: GeoPointDto,
    val pickupVertiport: VertiportDto,
    val vehicleOrigin: GeoPointDto,
    val destination: VertiportDto
)

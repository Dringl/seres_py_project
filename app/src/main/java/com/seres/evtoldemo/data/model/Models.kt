package com.seres.evtoldemo.data.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class VehicleStatus {
    IDLE,
    RESERVED,
    BOARDING,
    IN_FLIGHT,
    CHARGING,
    MAINTENANCE,
    OFFLINE
}

enum class OrderStatus {
    CREATED,
    ASSIGNED,
    RESERVED,
    BOARDING,
    IN_FLIGHT,
    RETURNING,
    DONE,
    CANCELED,
    FAILED
}

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

data class Vertiport(
    val id: String,
    val name: String,
    val location: GeoPoint
)

data class Evtol(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val batteryPercent: Int,
    val online: Boolean,
    val status: VehicleStatus
)

data class PriceEstimate(
    val amountCents: Int,
    val distanceKm: Double,
    val currency: String = "CNY",
    val cancellationFeeCents: Int = 0
) {
    val amountDisplay: String
        get() = "%.2f".format(amountCents / 100.0)

    val cancellationFeeDisplay: String
        get() = "%.2f".format(cancellationFeeCents / 100.0)
}

data class Order(
    val id: String,
    val pickup: GeoPoint,
    val pickupVertiport: Vertiport,
    val vehicleOrigin: GeoPoint,
    val destination: Vertiport,
    val vehicleId: String,
    val status: OrderStatus,
    val priceEstimate: PriceEstimate,
    val createdAt: Long,
    val updatedAt: Long
)

fun GeoPoint.distanceTo(other: GeoPoint): Double {
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(other.latitude - latitude)
    val dLng = Math.toRadians(other.longitude - longitude)
    val lat1 = Math.toRadians(latitude)
    val lat2 = Math.toRadians(other.latitude)

    val a = sin(dLat / 2).pow(2.0) +
        sin(dLng / 2).pow(2.0) * cos(lat1) * cos(lat2)
    val c = 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    return earthRadiusKm * c
}

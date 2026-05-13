package com.seres.evtoldemo.data.local

import com.seres.evtoldemo.data.model.GeoPoint
import com.seres.evtoldemo.data.model.Order
import com.seres.evtoldemo.data.model.OrderStatus
import com.seres.evtoldemo.data.model.PriceEstimate
import com.seres.evtoldemo.data.model.Vertiport

fun Order.toEntity(): OrderEntity = OrderEntity(
    id = id,
    vehicleId = vehicleId,
    pickupLat = pickup.latitude,
    pickupLng = pickup.longitude,
    pickupVertiportId = pickupVertiport.id,
    pickupVertiportName = pickupVertiport.name,
    pickupVertiportLat = pickupVertiport.location.latitude,
    pickupVertiportLng = pickupVertiport.location.longitude,
    vehicleOriginLat = vehicleOrigin.latitude,
    vehicleOriginLng = vehicleOrigin.longitude,
    destinationId = destination.id,
    destinationName = destination.name,
    destinationLat = destination.location.latitude,
    destinationLng = destination.location.longitude,
    priceCents = priceEstimate.amountCents,
    distanceKm = priceEstimate.distanceKm,
    cancellationFeeCents = priceEstimate.cancellationFeeCents,
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun OrderEntity.toDomain(): Order = Order(
    id = id,
    pickup = GeoPoint(pickupLat, pickupLng),
    pickupVertiport = Vertiport(
        id = pickupVertiportId,
        name = pickupVertiportName,
        location = GeoPoint(pickupVertiportLat, pickupVertiportLng)
    ),
    vehicleOrigin = GeoPoint(vehicleOriginLat, vehicleOriginLng),
    destination = Vertiport(
        id = destinationId,
        name = destinationName,
        location = GeoPoint(destinationLat, destinationLng)
    ),
    vehicleId = vehicleId,
    status = OrderStatus.valueOf(status),
    priceEstimate = PriceEstimate(
        amountCents = priceCents,
        distanceKm = distanceKm,
        cancellationFeeCents = cancellationFeeCents
    ),
    createdAt = createdAt,
    updatedAt = updatedAt
)

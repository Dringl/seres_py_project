package com.seres.evtoldemo.data.remote

import com.seres.evtoldemo.data.model.Evtol
import com.seres.evtoldemo.data.model.GeoPoint
import com.seres.evtoldemo.data.model.Order
import com.seres.evtoldemo.data.model.OrderStatus
import com.seres.evtoldemo.data.model.PriceEstimate
import com.seres.evtoldemo.data.model.VehicleStatus
import com.seres.evtoldemo.data.model.Vertiport

fun GeoPointDto.toDomain(): GeoPoint = GeoPoint(latitude, longitude)

fun GeoPoint.toDto(): GeoPointDto = GeoPointDto(latitude, longitude)

fun VertiportDto.toDomain(): Vertiport = Vertiport(
    id = id,
    name = name,
    location = location.toDomain()
)

fun EvtolDto.toDomain(): Evtol = Evtol(
    id = id,
    name = name,
    location = location.toDomain(),
    batteryPercent = batteryPercent,
    online = online,
    status = runCatching { VehicleStatus.valueOf(status) }.getOrDefault(VehicleStatus.OFFLINE)
)

fun PriceEstimateDto.toDomain(): PriceEstimate = PriceEstimate(
    amountCents = amountCents,
    distanceKm = distanceKm,
    currency = currency,
    cancellationFeeCents = cancellationFeeCents
)

fun OrderDto.toDomain(): Order = Order(
    id = id,
    pickup = pickup.toDomain(),
    pickupVertiport = pickupVertiport.toDomain(),
    vehicleOrigin = vehicleOrigin.toDomain(),
    destination = destination.toDomain(),
    vehicleId = vehicleId,
    status = runCatching { OrderStatus.valueOf(status) }.getOrDefault(OrderStatus.FAILED),
    priceEstimate = price.toDomain(),
    createdAt = createdAt,
    updatedAt = updatedAt
)

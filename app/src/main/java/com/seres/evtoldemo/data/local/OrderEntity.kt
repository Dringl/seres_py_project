package com.seres.evtoldemo.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey val id: String,
    val vehicleId: String,
    val pickupLat: Double,
    val pickupLng: Double,
    val pickupVertiportId: String,
    val pickupVertiportName: String,
    val pickupVertiportLat: Double,
    val pickupVertiportLng: Double,
    val vehicleOriginLat: Double,
    val vehicleOriginLng: Double,
    val destinationId: String,
    val destinationName: String,
    val destinationLat: Double,
    val destinationLng: Double,
    val priceCents: Int,
    val distanceKm: Double,
    val cancellationFeeCents: Int,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)

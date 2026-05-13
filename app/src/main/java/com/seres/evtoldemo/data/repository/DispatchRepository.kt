package com.seres.evtoldemo.data.repository

import com.seres.evtoldemo.data.model.Evtol
import com.seres.evtoldemo.data.model.GeoPoint
import com.seres.evtoldemo.data.model.Order
import com.seres.evtoldemo.data.model.PriceEstimate
import com.seres.evtoldemo.data.model.Vertiport
import kotlinx.coroutines.flow.Flow

class NoAvailableVehicleException : IllegalStateException()

interface DispatchRepository {
    suspend fun getVertiports(): List<Vertiport>
    suspend fun getNearbyVehicles(userLocation: GeoPoint): List<Evtol>
    suspend fun estimatePrice(pickup: GeoPoint, destination: Vertiport): PriceEstimate
    suspend fun createOrder(
        pickup: GeoPoint,
        destination: Vertiport,
        pickupVertiport: Vertiport
    ): Order
    fun observeOrder(orderId: String): Flow<Order>
    suspend fun confirmPassengerBoarded(orderId: String): Boolean
    suspend fun cancelOrder(orderId: String): Boolean
    suspend fun getOrderHistory(): List<Order>
}

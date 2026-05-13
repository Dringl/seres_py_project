package com.seres.evtoldemo.domain

import com.seres.evtoldemo.data.model.Evtol
import com.seres.evtoldemo.data.model.GeoPoint
import com.seres.evtoldemo.data.model.Order
import com.seres.evtoldemo.data.model.PriceEstimate
import com.seres.evtoldemo.data.model.Vertiport
import com.seres.evtoldemo.data.repository.DispatchRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetVertiportsUseCase @Inject constructor(
    private val repository: DispatchRepository
) {
    suspend operator fun invoke(): List<Vertiport> = repository.getVertiports()
}

class GetNearbyVehiclesUseCase @Inject constructor(
    private val repository: DispatchRepository
) {
    suspend operator fun invoke(userLocation: GeoPoint): List<Evtol> =
        repository.getNearbyVehicles(userLocation)
}

class EstimatePriceUseCase @Inject constructor(
    private val repository: DispatchRepository
) {
    suspend operator fun invoke(
        pickup: GeoPoint,
        destination: Vertiport
    ): PriceEstimate = repository.estimatePrice(pickup, destination)
}

class CreateOrderUseCase @Inject constructor(
    private val repository: DispatchRepository
) {
    suspend operator fun invoke(
        pickup: GeoPoint,
        destination: Vertiport,
        pickupVertiport: Vertiport
    ): Order = repository.createOrder(pickup, destination, pickupVertiport)
}

class ObserveOrderUseCase @Inject constructor(
    private val repository: DispatchRepository
) {
    operator fun invoke(orderId: String): Flow<Order> = repository.observeOrder(orderId)
}

class ConfirmPassengerBoardedUseCase @Inject constructor(
    private val repository: DispatchRepository
) {
    suspend operator fun invoke(orderId: String): Boolean =
        repository.confirmPassengerBoarded(orderId)
}

class CancelOrderUseCase @Inject constructor(
    private val repository: DispatchRepository
) {
    suspend operator fun invoke(orderId: String): Boolean = repository.cancelOrder(orderId)
}

class GetOrderHistoryUseCase @Inject constructor(
    private val repository: DispatchRepository
) {
    suspend operator fun invoke(): List<Order> = repository.getOrderHistory()
}

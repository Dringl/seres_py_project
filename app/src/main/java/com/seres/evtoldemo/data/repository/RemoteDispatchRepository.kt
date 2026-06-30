package com.seres.evtoldemo.data.repository

import com.seres.evtoldemo.data.local.OrderDao
import com.seres.evtoldemo.data.local.toDomain as entityToDomain
import com.seres.evtoldemo.data.local.toEntity
import com.seres.evtoldemo.data.model.Evtol
import com.seres.evtoldemo.data.model.GeoPoint
import com.seres.evtoldemo.data.model.Order
import com.seres.evtoldemo.data.model.OrderStatus
import com.seres.evtoldemo.data.model.PriceEstimate
import com.seres.evtoldemo.data.model.Vertiport
import com.seres.evtoldemo.data.remote.CreateOrderRequestDto
import com.seres.evtoldemo.data.remote.DispatchApi
import com.seres.evtoldemo.data.remote.EstimateRequestDto
import com.seres.evtoldemo.data.remote.NearbyRequestDto
import com.seres.evtoldemo.data.remote.toDomain
import com.seres.evtoldemo.data.remote.toDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * 通过真实后端（[DispatchApi]）实现调度仓库，服务器为唯一数据源；
 * 订单历史写入本地 Room 作离线缓存，observeOrder 以轮询获取订单状态变化。
 */
@Singleton
class RemoteDispatchRepository @Inject constructor(
    private val api: DispatchApi,
    private val orderDao: OrderDao
) : DispatchRepository {

    override suspend fun getVertiports(): List<Vertiport> =
        api.getVertiports().map { it.toDomain() }

    override suspend fun getNearbyVehicles(userLocation: GeoPoint): List<Evtol> =
        api.getNearbyVehicles(
            NearbyRequestDto(userLocation.latitude, userLocation.longitude)
        ).map { it.toDomain() }

    override suspend fun estimatePrice(pickup: GeoPoint, destination: Vertiport): PriceEstimate =
        api.estimatePrice(EstimateRequestDto(pickup.toDto(), destination.id)).toDomain()

    override suspend fun createOrder(
        pickup: GeoPoint,
        destination: Vertiport,
        pickupVertiport: Vertiport
    ): Order {
        val dto = try {
            api.createOrder(
                CreateOrderRequestDto(pickup.toDto(), destination.id, pickupVertiport.id)
            )
        } catch (e: HttpException) {
            if (e.code() == 409) throw NoAvailableVehicleException() else throw e
        }
        val order = dto.toDomain()
        persist(order)
        return order
    }

    override fun observeOrder(orderId: String): Flow<Order> = flow {
        var last: OrderStatus? = null
        while (true) {
            val order = runCatching { api.getOrder(orderId).toDomain() }.getOrNull()
            if (order != null && order.status != last) {
                last = order.status
                persist(order)
                emit(order)
                if (order.status in TERMINAL_STATUSES) break
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    override suspend fun confirmPassengerBoarded(orderId: String): Boolean =
        runCatching { api.board(orderId) }.isSuccess

    override suspend fun cancelOrder(orderId: String): Boolean =
        runCatching { api.cancelOrder(orderId).toDomain().also { persist(it) } }.isSuccess

    override suspend fun getOrderHistory(): List<Order> = withContext(Dispatchers.IO) {
        runCatching {
            val remote = api.getOrders().map { it.toDomain() }
            remote.forEach { orderDao.upsert(it.toEntity()) }
            remote
        }.getOrElse {
            orderDao.getAll().map { it.entityToDomain() }
        }
    }

    private suspend fun persist(order: Order) = withContext(Dispatchers.IO) {
        orderDao.upsert(order.toEntity())
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1500L
        val TERMINAL_STATUSES = setOf(OrderStatus.DONE, OrderStatus.CANCELED, OrderStatus.FAILED)
    }
}

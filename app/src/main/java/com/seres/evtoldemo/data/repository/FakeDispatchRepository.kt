package com.seres.evtoldemo.data.repository

import com.seres.evtoldemo.data.local.OrderDao
import com.seres.evtoldemo.data.local.toDomain
import com.seres.evtoldemo.data.local.toEntity
import com.seres.evtoldemo.data.model.Evtol
import com.seres.evtoldemo.data.model.GeoPoint
import com.seres.evtoldemo.data.model.Order
import com.seres.evtoldemo.data.model.OrderStatus
import com.seres.evtoldemo.data.model.PriceEstimate
import com.seres.evtoldemo.data.model.VehicleStatus
import com.seres.evtoldemo.data.model.Vertiport
import com.seres.evtoldemo.data.model.distanceTo
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

@Singleton
class FakeDispatchRepository @Inject constructor(
    private val orderDao: OrderDao
) : DispatchRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val vertiports = listOf(
        Vertiport("vp001", "江北嘴商务区停机坪", GeoPoint(29.5792, 106.5758)),
        Vertiport("vp002", "解放碑停机坪", GeoPoint(29.5582, 106.5755)),
        Vertiport("vp003", "观音桥停机坪", GeoPoint(29.5845, 106.5332)),
        Vertiport("vp004", "南滨路停机坪", GeoPoint(29.5468, 106.5851)),
        Vertiport("vp005", "重庆北站停机坪", GeoPoint(29.6142, 106.5511)),
        Vertiport("vp006", "两江机场停机坪", GeoPoint(29.7211, 106.6439)),
        Vertiport("vp007", "重庆西站停机坪", GeoPoint(29.5036, 106.4301)),
        Vertiport("vp008", "大学城停机坪", GeoPoint(29.5976, 106.2998)),
        Vertiport("vp009", "照母山停机坪", GeoPoint(29.6318, 106.5357)),
        Vertiport("vp010", "巴南龙洲湾停机坪", GeoPoint(29.4042, 106.5414)),
        Vertiport("vp011", "磁器口停机坪", GeoPoint(29.5829, 106.4498)),
        Vertiport("vp012", "南岸茶园停机坪", GeoPoint(29.4947, 106.6378))
    )

    private val vehicles = mutableListOf(
        Evtol("ev001", "Eagle-01", GeoPoint(29.5845, 106.5332), 88, true, VehicleStatus.IDLE),
        Evtol("ev002", "Eagle-02", GeoPoint(29.5792, 106.5758), 74, true, VehicleStatus.IDLE),
        Evtol("ev003", "Eagle-03", GeoPoint(29.7211, 106.6439), 81, true, VehicleStatus.IDLE)
    )

    private val orders = mutableMapOf<String, Order>()
    private val orderFlows = mutableMapOf<String, MutableStateFlow<Order>>()
    private val lifecycleJobs = mutableMapOf<String, Job>()
    private val boardingSignals = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun getVertiports(): List<Vertiport> = vertiports

    override suspend fun getNearbyVehicles(userLocation: GeoPoint): List<Evtol> {
        return lock.withLock {
            vehicles
                .sortedBy { it.location.distanceTo(userLocation) }
                .take(3)
        }
    }

    override suspend fun estimatePrice(pickup: GeoPoint, destination: Vertiport): PriceEstimate {
        return estimatePriceInternal(pickup, destination)
    }

    override suspend fun createOrder(
        pickup: GeoPoint,
        destination: Vertiport,
        pickupVertiport: Vertiport
    ): Order {
        val order = lock.withLock {
            val candidate = vehicles
                .filter { it.online && it.status == VehicleStatus.IDLE && it.batteryPercent >= 40 }
                .minByOrNull { it.location.distanceTo(pickupVertiport.location) }
                ?: throw NoAvailableVehicleException()

            val candidateIndex = vehicles.indexOfFirst { it.id == candidate.id }
            vehicles[candidateIndex] = candidate.copy(status = VehicleStatus.RESERVED)

            val now = System.currentTimeMillis()
            val price = estimatePriceInternal(pickupVertiport.location, destination)
            val created = Order(
                id = generateOrderId(),
                pickup = pickup,
                pickupVertiport = pickupVertiport,
                vehicleOrigin = candidate.location,
                destination = destination,
                vehicleId = candidate.id,
                status = OrderStatus.RESERVED,
                priceEstimate = price,
                createdAt = now,
                updatedAt = now
            )

            orders[created.id] = created
            orderFlows[created.id] = MutableStateFlow(created)
            boardingSignals[created.id] = CompletableDeferred()
            created
        }

        persistAsync(order)
        startLifecycle(order.id, pickupVertiport, destination)
        return order
    }

    override fun observeOrder(orderId: String): Flow<Order> {
        return orderFlows[orderId]?.asStateFlow() ?: flow {
            val existing = lock.withLock { orders[orderId] }
            if (existing != null) {
                emit(existing)
            }
        }
    }

    override suspend fun confirmPassengerBoarded(orderId: String): Boolean {
        return lock.withLock {
            val current = orders[orderId] ?: return false
            if (current.status != OrderStatus.BOARDING) {
                return false
            }

            val signal = boardingSignals[orderId] ?: return false
            if (!signal.isCompleted) {
                signal.complete(Unit)
            }
            true
        }
    }

    override suspend fun cancelOrder(orderId: String): Boolean {
        val canceled = lock.withLock {
            val current = orders[orderId] ?: return false
            val canCancel = current.status in setOf(
                OrderStatus.CREATED,
                OrderStatus.ASSIGNED,
                OrderStatus.RESERVED,
                OrderStatus.BOARDING
            )
            if (!canCancel) {
                return false
            }

            val vehicleIndex = vehicles.indexOfFirst { it.id == current.vehicleId }
            if (vehicleIndex < 0) {
                return false
            }
            val vehicle = vehicles[vehicleIndex]
            val nearestVertiport = vertiports.minByOrNull { it.location.distanceTo(vehicle.location) } ?: return false
            val pickupRouteDistanceKm = current.vehicleOrigin.distanceTo(current.pickupVertiport.location)
            val traveledKm = current.vehicleOrigin.distanceTo(vehicle.location).coerceAtMost(pickupRouteDistanceKm)
            val progressRatio = if (pickupRouteDistanceKm > 0.0) {
                (traveledKm / pickupRouteDistanceKm).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val cancellationFeeCents = (1200 + current.priceEstimate.amountCents * 0.45 * progressRatio).toInt()
                .coerceAtLeast(1200)
            val now = System.currentTimeMillis()
            val next = current.copy(
                status = OrderStatus.RETURNING,
                priceEstimate = current.priceEstimate.copy(cancellationFeeCents = cancellationFeeCents),
                updatedAt = now
            )
            orders[orderId] = next
            orderFlows[orderId]?.value = next
            vehicles[vehicleIndex] = vehicle.copy(
                status = VehicleStatus.RESERVED,
                location = vehicle.location
            )
            CancelSeed(next, nearestVertiport, vehicle.location)
        }

        lock.withLock {
            boardingSignals.remove(orderId)?.cancel()
        }
        persistAsync(canceled.order)
        startReturnLifecycle(canceled.order.id, canceled.returnPort, canceled.startLocation)
        return true
    }

    override suspend fun getOrderHistory(): List<Order> {
        return withContext(Dispatchers.IO) {
            orderDao.getAll().map { it.toDomain() }
        }
    }

    private fun estimatePriceInternal(pickup: GeoPoint, destination: Vertiport): PriceEstimate {
        val distanceKm = pickup.distanceTo(destination.location).coerceAtLeast(1.0)
        val baseFareCents = 2800
        val distanceFareCents = (distanceKm * 760).toInt()
        val amountCents = baseFareCents + distanceFareCents
        return PriceEstimate(
            amountCents = amountCents,
            distanceKm = distanceKm,
            currency = "CNY"
        )
    }

    private fun startLifecycle(
        orderId: String,
        pickupVertiport: Vertiport,
        destination: Vertiport
    ) {
        lifecycleJobs.remove(orderId)?.cancel()
        lifecycleJobs[orderId] = scope.launch {
            val pickupMotion = movementSpec(
                from = lock.withLock { orders[orderId]?.vehicleOrigin ?: pickupVertiport.location },
                to = pickupVertiport.location,
                speedKmPerHour = 95.0,
                minSteps = 72
            )
            moveVehicle(
                orderId = orderId,
                target = pickupVertiport.location,
                enRouteStatus = VehicleStatus.RESERVED,
                steps = pickupMotion.steps,
                stepDelayMs = pickupMotion.stepDelayMs,
                batteryDropTotal = 4
            )

            val readyToBoard = transitionOrder(orderId, OrderStatus.BOARDING) { vehicle ->
                vehicle.copy(
                    status = VehicleStatus.BOARDING,
                    location = pickupVertiport.location
                )
            }
            if (!readyToBoard) {
                cleanupOrderRuntime(orderId)
                return@launch
            }

            val boardingSignal = lock.withLock { boardingSignals[orderId] }
            boardingSignal?.await() ?: run {
                cleanupOrderRuntime(orderId)
                return@launch
            }

            val started = transitionOrder(orderId, OrderStatus.IN_FLIGHT) { vehicle ->
                vehicle.copy(status = VehicleStatus.IN_FLIGHT)
            }
            if (!started) {
                cleanupOrderRuntime(orderId)
                return@launch
            }

            val flightMotion = movementSpec(
                from = pickupVertiport.location,
                to = destination.location,
                speedKmPerHour = 180.0,
                minSteps = 90
            )
            moveVehicle(
                orderId = orderId,
                target = destination.location,
                enRouteStatus = VehicleStatus.IN_FLIGHT,
                steps = flightMotion.steps,
                stepDelayMs = flightMotion.stepDelayMs,
                batteryDropTotal = 12
            )

            transitionOrder(orderId, OrderStatus.DONE) { vehicle ->
                vehicle.copy(
                    status = VehicleStatus.IDLE,
                    location = parkedVehicleLocation(destination.location),
                    batteryPercent = (vehicle.batteryPercent - 4).coerceAtLeast(10)
                )
            }
            cleanupOrderRuntime(orderId)
        }
    }

    private fun startReturnLifecycle(orderId: String, returnPort: Vertiport, startLocation: GeoPoint) {
        lifecycleJobs.remove(orderId)?.cancel()
        lifecycleJobs[orderId] = scope.launch {
            val returnMotion = movementSpec(
                from = startLocation,
                to = returnPort.location,
                speedKmPerHour = 110.0,
                minSteps = 72
            )
            moveVehicle(
                orderId = orderId,
                target = returnPort.location,
                enRouteStatus = VehicleStatus.RESERVED,
                steps = returnMotion.steps,
                stepDelayMs = returnMotion.stepDelayMs,
                batteryDropTotal = 5
            )
            transitionOrder(orderId, OrderStatus.CANCELED) { vehicle ->
                vehicle.copy(
                    status = VehicleStatus.IDLE,
                    location = parkedVehicleLocation(returnPort.location),
                    batteryPercent = (vehicle.batteryPercent - 2).coerceAtLeast(10)
                )
            }
            cleanupOrderRuntime(orderId)
        }
    }

    private suspend fun moveVehicle(
        orderId: String,
        target: GeoPoint,
        enRouteStatus: VehicleStatus,
        steps: Int,
        stepDelayMs: Long,
        batteryDropTotal: Int
    ) {
        val seed = lock.withLock {
            val order = orders[orderId] ?: return@withLock null
            val vehicleIndex = vehicles.indexOfFirst { it.id == order.vehicleId }
            if (vehicleIndex < 0) return@withLock null
            MoveSeed(
                vehicleId = order.vehicleId,
                start = vehicles[vehicleIndex].location,
                startBattery = vehicles[vehicleIndex].batteryPercent
            )
        } ?: return

        for (step in 1..steps) {
            delay(stepDelayMs)
            val progress = step.toDouble() / steps.toDouble()
            val updated = lock.withLock {
                val order = orders[orderId] ?: return@withLock false
                if (order.status == OrderStatus.CANCELED || order.status == OrderStatus.FAILED) {
                    return@withLock false
                }

                val vehicleIndex = vehicles.indexOfFirst { it.id == seed.vehicleId }
                if (vehicleIndex < 0) {
                    return@withLock false
                }

                val current = vehicles[vehicleIndex]
                val consumed = (batteryDropTotal * progress).toInt()
                vehicles[vehicleIndex] = current.copy(
                    status = enRouteStatus,
                    location = interpolate(seed.start, target, progress),
                    batteryPercent = (seed.startBattery - consumed).coerceAtLeast(10)
                )
                true
            }
            if (!updated) {
                return
            }
        }
    }

    private suspend fun transitionOrder(
        orderId: String,
        nextStatus: OrderStatus,
        transformVehicle: (Evtol) -> Evtol
    ): Boolean {
        val updated = lock.withLock {
            val current = orders[orderId] ?: return false
            if (current.status == OrderStatus.CANCELED || current.status == OrderStatus.FAILED) {
                return false
            }

            val now = System.currentTimeMillis()
            val next = current.copy(status = nextStatus, updatedAt = now)
            orders[orderId] = next
            orderFlows[orderId]?.value = next

            val vehicleIndex = vehicles.indexOfFirst { it.id == current.vehicleId }
            if (vehicleIndex >= 0) {
                vehicles[vehicleIndex] = transformVehicle(vehicles[vehicleIndex])
            }
            next
        }

        persistAsync(updated)
        return true
    }

    private fun persistAsync(order: Order) {
        scope.launch(Dispatchers.IO) {
            orderDao.upsert(order.toEntity())
        }
    }

    private fun generateOrderId(): String {
        return "ODR-${System.currentTimeMillis().toString().takeLast(6)}-${Random.nextInt(100, 999)}"
    }

    private suspend fun cleanupOrderRuntime(orderId: String) {
        lock.withLock {
            lifecycleJobs.remove(orderId)
            boardingSignals.remove(orderId)
        }
    }

    private fun interpolate(from: GeoPoint, to: GeoPoint, progress: Double): GeoPoint {
        val ratio = progress.coerceIn(0.0, 1.0)
        return GeoPoint(
            latitude = from.latitude + (to.latitude - from.latitude) * ratio,
            longitude = from.longitude + (to.longitude - from.longitude) * ratio
        )
    }

    private fun parkedVehicleLocation(vertiportLocation: GeoPoint): GeoPoint {
        return GeoPoint(
            latitude = vertiportLocation.latitude,
            longitude = vertiportLocation.longitude
        )
    }

    private fun movementSpec(
        from: GeoPoint,
        to: GeoPoint,
        speedKmPerHour: Double,
        minSteps: Int
    ): MovementSpec {
        val distanceKm = from.distanceTo(to).coerceAtLeast(0.08)
        val durationMs = (((distanceKm / speedKmPerHour) * 3_600_000.0) / 30.0)
            .toLong()
            .coerceAtLeast(640L)
        val stepDelayMs = 16L
        val steps = (durationMs / stepDelayMs)
            .toInt()
            .coerceAtLeast((minSteps / 3).coerceAtLeast(36))
        return MovementSpec(steps = steps, stepDelayMs = stepDelayMs)
    }

    private data class MoveSeed(
        val vehicleId: String,
        val start: GeoPoint,
        val startBattery: Int
    )

    private data class CancelSeed(
        val order: Order,
        val returnPort: Vertiport,
        val startLocation: GeoPoint
    )

    private data class MovementSpec(
        val steps: Int,
        val stepDelayMs: Long
    )
}

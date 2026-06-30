package com.seres.evtoldemo.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seres.evtoldemo.BuildConfig
import com.seres.evtoldemo.R
import com.seres.evtoldemo.data.navigation.DrivingRouteEngine
import com.seres.evtoldemo.data.model.GeoPoint
import com.seres.evtoldemo.data.model.Order
import com.seres.evtoldemo.data.model.OrderStatus
import com.seres.evtoldemo.data.model.Vertiport
import com.seres.evtoldemo.data.model.distanceTo
import com.seres.evtoldemo.data.repository.NoAvailableVehicleException
import com.seres.evtoldemo.domain.CancelOrderUseCase
import com.seres.evtoldemo.domain.ConfirmPassengerBoardedUseCase
import com.seres.evtoldemo.domain.CreateOrderUseCase
import com.seres.evtoldemo.domain.EstimatePriceUseCase
import com.seres.evtoldemo.domain.GetNearbyVehiclesUseCase
import com.seres.evtoldemo.domain.GetOrderHistoryUseCase
import com.seres.evtoldemo.domain.GetVertiportsUseCase
import com.seres.evtoldemo.domain.ObserveOrderUseCase
import com.seres.evtoldemo.location.LocationTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.ceil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val getVertiportsUseCase: GetVertiportsUseCase,
    private val getNearbyVehiclesUseCase: GetNearbyVehiclesUseCase,
    private val estimatePriceUseCase: EstimatePriceUseCase,
    private val createOrderUseCase: CreateOrderUseCase,
    private val observeOrderUseCase: ObserveOrderUseCase,
    private val confirmPassengerBoardedUseCase: ConfirmPassengerBoardedUseCase,
    private val cancelOrderUseCase: CancelOrderUseCase,
    private val getOrderHistoryUseCase: GetOrderHistoryUseCase,
    private val drivingRouteEngine: DrivingRouteEngine,
    private val locationTracker: LocationTracker
) : ViewModel() {

    private val tag = "MainViewModel"

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    private fun debugError(message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.e(tag, message, throwable)
        }
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()

    private var observeOrderJob: Job? = null
    private var vehiclePollingJob: Job? = null
    private var fleetPollingJob: Job? = null
    private var tripResetJob: Job? = null

    init {
        loadInitialData()
    }

    fun onLocationPermissionChanged(granted: Boolean) {
        _uiState.update {
            it.copy(
                hasLocationPermission = granted,
                hasRequestedLocationPermission = true,
                shouldShowLocationPermissionCard = !granted
            )
        }
        if (granted) {
            refreshLocation()
        }
    }

    fun dismissLocationPermissionCard() {
        _uiState.update { it.copy(shouldShowLocationPermissionCard = false) }
    }

    fun refreshLocation() {
        if (!_uiState.value.hasLocationPermission) {
            showSnackbar(
                messageRes = R.string.permission_required_message,
                actionRes = R.string.snackbar_enable,
                action = SnackbarAction.REQUEST_LOCATION
            )
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, snackbarEvent = null) }

            val fetchedLocation = locationTracker.getCurrentLocation()
            val fallback = _uiState.value.currentLocation ?: DEFAULT_LOCATION
            val resolvedLocation = fetchedLocation ?: fallback

            val allVertiports = ensureVertiportsLoaded()
            val nearbyVertiports = pickNearbyVertiports(resolvedLocation, allVertiports)
            val pickupVertiport = nearestVertiport(resolvedLocation, nearbyVertiports.ifEmpty { allVertiports })

            val selectedDestination = _uiState.value.selectedDestination
            val estimate = if (pickupVertiport != null && selectedDestination != null) {
                estimatePriceUseCase(pickupVertiport.location, selectedDestination)
            } else {
                null
            }

            val groundRouteInfo = pickupVertiport?.let {
                resolveGroundRoute(resolvedLocation, it.location)
            } ?: GroundRouteInfo(
                points = emptyList(),
                distanceKm = null,
                durationMinutes = null
            )
            val flightRoute = if (pickupVertiport != null && selectedDestination != null) {
                buildRoute(pickupVertiport.location, selectedDestination.location)
            } else {
                emptyList()
            }

            val vehicles = getNearbyVehiclesUseCase(resolvedLocation)
            val activeVehicleLocation = _uiState.value.activeOrder?.let { activeOrder ->
                vehicles.firstOrNull { it.id == activeOrder.vehicleId }?.location
            }

            _uiState.update { state ->
                val initialPoint = state.initialLocatePoint ?: resolvedLocation
                state.copy(
                    currentLocation = resolvedLocation,
                    initialLocatePoint = initialPoint,
                    allVertiports = allVertiports,
                    vertiports = nearbyVertiports,
                    pickupVertiport = pickupVertiport,
                    groundRoute = groundRouteInfo.points,
                    groundDistanceKm = groundRouteInfo.distanceKm,
                    groundDurationMinutes = groundRouteInfo.durationMinutes,
                    flightRoute = flightRoute,
                    nearbyVehicles = vehicles,
                    activeVehicleLocation = activeVehicleLocation,
                    activeVehicleTrack = appendTrack(
                        currentTrack = state.activeVehicleTrack,
                        nextPoint = activeVehicleLocation,
                        status = state.activeOrder?.status
                    ),
                    estimatedArrivalMinutes = estimateArrivalMinutes(
                        activeOrder = state.activeOrder,
                        activeVehicleLocation = activeVehicleLocation,
                        pickupVertiport = pickupVertiport,
                        destination = selectedDestination
                    ),
                    tripHintRes = estimateTripHint(
                        activeOrder = state.activeOrder,
                        estimatedArrivalMinutes = estimateArrivalMinutes(
                            activeOrder = state.activeOrder,
                            activeVehicleLocation = activeVehicleLocation,
                            pickupVertiport = pickupVertiport,
                            destination = selectedDestination
                        )
                    ),
                    priceEstimate = estimate,
                    isLoading = false
                )
            }

            if (fetchedLocation == null) {
                showSnackbar(
                    messageRes = R.string.location_fallback_message,
                    actionRes = R.string.snackbar_retry,
                    action = SnackbarAction.REFRESH_LOCATION
                )
            }

            updateVehiclePollingByOrderState(_uiState.value.activeOrder)
        }
    }

    fun onDestinationSelected(vertiportId: String) {
        viewModelScope.launch {
            val state = _uiState.value
            val all = state.allVertiports.ifEmpty { ensureVertiportsLoaded() }
            val destination = all.firstOrNull { it.id == vertiportId } ?: return@launch
            val location = state.currentLocation
            cancelPendingTripReset()
            if (location == null) {
                showSnackbar(
                    messageRes = R.string.permission_pick_destination_message,
                    actionRes = R.string.snackbar_enable,
                    action = SnackbarAction.REQUEST_LOCATION
                )
                return@launch
            }

            val pickup = state.pickupVertiport ?: nearestVertiport(location, all)
            debugLog("onDestinationSelected destination=${destination.id} pickup=${pickup?.id} activeOrder=${state.activeOrder?.status}")
            val estimate = if (pickup != null) {
                runCatching { estimatePriceUseCase(pickup.location, destination) }
                    .onFailure { throwable ->
                        if (throwable is NoAvailableVehicleException) {
                            showSnackbar(messageRes = R.string.order_unavailable_vehicle_message)
                        }
                    }
                    .getOrNull()
            } else {
                runCatching { estimatePriceUseCase(location, destination) }
                    .onFailure { throwable ->
                        if (throwable is NoAvailableVehicleException) {
                            showSnackbar(messageRes = R.string.order_unavailable_vehicle_message)
                        }
                    }
                    .getOrNull()
            }
            val flightRoute = if (pickup != null) {
                buildRoute(pickup.location, destination.location)
            } else {
                emptyList()
            }

            _uiState.update {
                it.copy(
                    selectedDestination = destination,
                    priceEstimate = estimate,
                    flightRoute = flightRoute,
                    estimatedArrivalMinutes = pickup?.let { port ->
                        estimateMinutesBySpeed(port.location.distanceTo(destination.location), averageSpeedKmPerHour = 1080.0, minMinutes = 1)
                    }
                )
            }
        }
    }

    fun placeOrder() {
        viewModelScope.launch {
            val state = _uiState.value
            val location = state.currentLocation
            val destination = state.selectedDestination
            val pickupVertiport = state.pickupVertiport

            cancelPendingTripReset()

            if (location == null || destination == null || pickupVertiport == null) {
                showSnackbar(
                    messageRes = R.string.permission_order_message,
                    actionRes = R.string.snackbar_enable,
                    action = SnackbarAction.REQUEST_LOCATION
                )
                return@launch
            }
            if (pickupVertiport.id == destination.id) {
                showSnackbar(messageRes = R.string.same_vertiport_order_message)
                return@launch
            }

            runCatching {
                _uiState.update { current -> current.copy(isLoading = true, snackbarEvent = null) }
                debugLog("placeOrder start destination=${destination.id} pickup=${pickupVertiport.id} activeOrder=${state.activeOrder?.status}")
                createOrderUseCase(location, destination, pickupVertiport)
            }.onSuccess { order ->
                _uiState.update {
                    it.copy(
                        activeOrder = order,
                        activeVehicleTrack = emptyList(),
                        activeVehicleLocation = state.nearbyVehicles.firstOrNull { vehicle -> vehicle.id == order.vehicleId }?.location,
                        groundRoute = buildRoute(order.vehicleOrigin, order.pickupVertiport.location),
                        flightRoute = buildRoute(order.pickupVertiport.location, order.destination.location),
                        estimatedArrivalMinutes = estimateArrivalMinutes(
                            activeOrder = order,
                            activeVehicleLocation = state.activeVehicleLocation,
                            pickupVertiport = pickupVertiport,
                            destination = destination
                        ),
                        tripHintRes = null,
                        isLoading = false,
                        snackbarEvent = SnackbarEvent(
                            messageRes = R.string.order_pickup_instruction_message,
                            messageArgs = listOf(pickupVertiport.name)
                        )
                    )
                }
                debugLog("placeOrder success order=${order.id} vehicle=${order.vehicleId} pickup=${pickupVertiport.id} destination=${destination.id}")
                observeOrder(order)
                updateVehiclePollingByOrderState(order)
                reloadNearbyVehicles()
                reloadHistory()
            }.onFailure { throwable ->
                debugError("placeOrder failed destination=${destination.id} pickup=${pickupVertiport.id}", throwable)
                _uiState.update { it.copy(isLoading = false) }
                if (throwable is NoAvailableVehicleException) {
                    showSnackbar(messageRes = R.string.order_unavailable_vehicle_message)
                } else {
                    showSnackbar(
                        messageRes = R.string.pricing_empty,
                        actionRes = R.string.snackbar_retry,
                        action = SnackbarAction.RETRY_ORDER
                    )
                }
            }
        }
    }

    fun confirmPassengerBoarded() {
        val order = _uiState.value.activeOrder
        if (order == null) {
            showSnackbar(messageRes = R.string.order_missing_message)
            return
        }
        if (order.status != OrderStatus.BOARDING) {
            showSnackbar(messageRes = R.string.boarding_unavailable_message)
            return
        }

        viewModelScope.launch {
            val confirmed = confirmPassengerBoardedUseCase(order.id)
            if (confirmed) {
                showSnackbar(messageRes = R.string.boarding_confirmed_message)
            } else {
                showSnackbar(
                    messageRes = R.string.boarding_failed_message,
                    actionRes = R.string.snackbar_retry,
                    action = SnackbarAction.RETRY_ORDER
                )
            }
        }
    }

    fun cancelActiveOrder() {
        val orderId = _uiState.value.activeOrder?.id ?: return

        viewModelScope.launch {
            debugLog("cancelActiveOrder start orderId=$orderId status=${_uiState.value.activeOrder?.status}")
            val canceled = cancelOrderUseCase(orderId)
            if (canceled) {
                debugLog("cancelActiveOrder success orderId=$orderId newStatus=${_uiState.value.activeOrder?.status}")
                showSnackbar(messageRes = R.string.order_returning_message)
                updateVehiclePollingByOrderState(_uiState.value.activeOrder)
                reloadNearbyVehicles()
                reloadHistory()
            } else {
                showSnackbar(messageRes = R.string.order_cancel_failed_message)
            }
        }
    }

    fun consumeSnackbarEvent() {
        _uiState.update { it.copy(snackbarEvent = null) }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            val allVertiports = getVertiportsUseCase()
            val history = getOrderHistoryUseCase()
            _uiState.update {
                it.copy(
                    allVertiports = allVertiports,
                    vertiports = allVertiports.take(8),
                    orderHistory = history
                )
            }
        }
    }

    private fun observeOrder(order: Order) {
        observeOrderJob?.cancel()
        observeOrderJob = viewModelScope.launch {
            observeOrderUseCase(order.id).collect { updated ->
                _uiState.update {
                    it.copy(activeOrder = updated)
                }
                debugLog("observeOrder update order=${updated.id} status=${updated.status}")
                updateVehiclePollingByOrderState(updated)
                if (updated.status == OrderStatus.DONE || updated.status == OrderStatus.CANCELED || updated.status == OrderStatus.FAILED) {
                    scheduleTripReset(updated.status)
                }
                reloadNearbyVehicles()
                reloadHistory()
            }
        }
    }

    private fun scheduleTripReset(status: OrderStatus) {
        tripResetJob?.cancel()
        val expectedOrderId = _uiState.value.activeOrder?.id
        tripResetJob = viewModelScope.launch {
            delay(
                when (status) {
                    OrderStatus.CANCELED -> 4_000
                    OrderStatus.FAILED -> 3_000
                    else -> 2_000
                }
            )
            val state = _uiState.value
            if (state.activeOrder?.id != expectedOrderId || state.activeOrder?.status != status) {
                return@launch
            }
            val location = state.currentLocation ?: DEFAULT_LOCATION
            val all = state.allVertiports
            val nearby = pickNearbyVertiports(location, all)
            val pickup = nearestVertiport(location, nearby.ifEmpty { all })
            val groundRouteInfo = pickup?.let {
                resolveGroundRoute(location, it.location)
            } ?: GroundRouteInfo(
                points = emptyList(),
                distanceKm = null,
                durationMinutes = null
            )

            _uiState.update {
                it.copy(
                    activeOrder = null,
                    selectedDestination = null,
                    priceEstimate = null,
                    pickupVertiport = pickup,
                    vertiports = nearby,
                    groundRoute = groundRouteInfo.points,
                    groundDistanceKm = groundRouteInfo.distanceKm,
                    groundDurationMinutes = groundRouteInfo.durationMinutes,
                    flightRoute = emptyList(),
                    activeVehicleLocation = null,
                    activeVehicleTrack = emptyList(),
                    estimatedArrivalMinutes = null,
                    tripHintRes = null
                )
            }
            showSnackbar(
                messageRes = when (status) {
                    OrderStatus.CANCELED -> R.string.order_canceled_message
                    OrderStatus.FAILED -> R.string.status_failed_message
                    else -> R.string.trip_finished_message
                },
                actionRes = R.string.snackbar_reselect,
                action = SnackbarAction.CLEAR_DESTINATION
            )
            updateVehiclePollingByOrderState(null)
        }
    }

    private fun cancelPendingTripReset() {
        tripResetJob?.cancel()
        tripResetJob = null
    }

    private fun updateVehiclePollingByOrderState(order: Order?) {
        // 机队位置改为常驻轮询：无论本机有无进行中订单，都持续刷新附近飞行器，
        // 这样任意设备都能实时看到其他设备订单的飞行器在移动。
        ensureFleetPolling()
    }

    private fun ensureFleetPolling() {
        if (fleetPollingJob?.isActive == true) {
            return
        }
        fleetPollingJob = viewModelScope.launch {
            while (isActive) {
                if (_uiState.value.currentLocation != null) {
                    reloadNearbyVehicles()
                }
                delay(250)
            }
        }
    }

    private suspend fun reloadNearbyVehicles() {
        val location = _uiState.value.currentLocation ?: return
        val vehicles = getNearbyVehiclesUseCase(location)
        _uiState.update { state ->
            val activeVehicleLocation = state.activeOrder?.let { activeOrder ->
                vehicles.firstOrNull { it.id == activeOrder.vehicleId }?.location
            }
            val eta = estimateArrivalMinutes(
                activeOrder = state.activeOrder,
                activeVehicleLocation = activeVehicleLocation,
                pickupVertiport = state.pickupVertiport,
                destination = state.selectedDestination
            )
            state.copy(
                nearbyVehicles = vehicles,
                activeVehicleLocation = activeVehicleLocation,
                activeVehicleTrack = appendTrack(
                    currentTrack = state.activeVehicleTrack,
                    nextPoint = activeVehicleLocation,
                    status = state.activeOrder?.status
                ),
                estimatedArrivalMinutes = eta,
                tripHintRes = estimateTripHint(
                    activeOrder = state.activeOrder,
                    estimatedArrivalMinutes = eta
                )
            )
        }
    }

    private suspend fun reloadHistory() {
        val history = getOrderHistoryUseCase()
        _uiState.update { it.copy(orderHistory = history) }
    }

    private suspend fun ensureVertiportsLoaded(): List<Vertiport> {
        val existing = _uiState.value.allVertiports
        if (existing.isNotEmpty()) {
            return existing
        }

        val loaded = getVertiportsUseCase()
        _uiState.update { it.copy(allVertiports = loaded) }
        return loaded
    }

    private fun pickNearbyVertiports(location: GeoPoint, all: List<Vertiport>): List<Vertiport> {
        if (all.isEmpty()) {
            return emptyList()
        }

        val sorted = all.sortedBy { it.location.distanceTo(location) }
        val inRange = sorted.filter { it.location.distanceTo(location) <= 45.0 }
        return (if (inRange.size >= 4) inRange else sorted).take(8)
    }

    private fun nearestVertiport(location: GeoPoint, ports: List<Vertiport>): Vertiport? {
        return ports.minByOrNull { it.location.distanceTo(location) }
    }

    private fun buildRoute(from: GeoPoint, to: GeoPoint, segments: Int = 14): List<GeoPoint> {
        if (segments <= 1) {
            return listOf(from, to)
        }

        return (0..segments).map { step ->
            val ratio = step.toDouble() / segments.toDouble()
            GeoPoint(
                latitude = from.latitude + (to.latitude - from.latitude) * ratio,
                longitude = from.longitude + (to.longitude - from.longitude) * ratio
            )
        }
    }

    private suspend fun resolveGroundRoute(from: GeoPoint, to: GeoPoint): GroundRouteInfo {
        val drivingRoute = drivingRouteEngine.planDrivingRoute(from, to)
        if (drivingRoute != null && drivingRoute.points.size >= 2) {
            val fallbackDistance = from.distanceTo(to)
            val distance = drivingRoute.distanceKm ?: fallbackDistance
            val duration = drivingRoute.durationMinutes ?: estimateDriveMinutes(distance)
            return GroundRouteInfo(
                points = drivingRoute.points,
                distanceKm = distance,
                durationMinutes = duration
            )
        }

        val fallbackDistance = from.distanceTo(to)
        return GroundRouteInfo(
            points = buildRoute(from, to),
            distanceKm = fallbackDistance,
            durationMinutes = estimateDriveMinutes(fallbackDistance)
        )
    }

    private fun estimateDriveMinutes(distanceKm: Double): Int {
        val averageSpeedKmPerHour = 210.0
        return ceil((distanceKm / averageSpeedKmPerHour) * 60.0).toInt().coerceAtLeast(3)
    }

    private fun estimateArrivalMinutes(
        activeOrder: Order?,
        activeVehicleLocation: GeoPoint?,
        pickupVertiport: Vertiport?,
        destination: Vertiport?
    ): Int? {
        val order = activeOrder ?: return null
        val vehiclePoint = activeVehicleLocation ?: return null
        return when (order.status) {
            OrderStatus.RESERVED -> {
                val target = pickupVertiport?.location ?: return null
                estimateMinutesBySpeed(vehiclePoint.distanceTo(target), averageSpeedKmPerHour = 570.0, minMinutes = 1)
            }
            OrderStatus.BOARDING -> 1
            OrderStatus.IN_FLIGHT -> {
                val target = destination?.location ?: return null
                estimateMinutesBySpeed(vehiclePoint.distanceTo(target), averageSpeedKmPerHour = 1080.0, minMinutes = 1)
            }
            OrderStatus.RETURNING -> {
                stateEstimateReturnMinutes(vehiclePoint)
            }
            else -> null
        }
    }

    private fun stateEstimateReturnMinutes(vehiclePoint: GeoPoint): Int {
        val target = _uiState.value.allVertiports.minByOrNull { it.location.distanceTo(vehiclePoint) }?.location
            ?: return 1
        return estimateMinutesBySpeed(vehiclePoint.distanceTo(target), averageSpeedKmPerHour = 720.0, minMinutes = 1)
    }

    private fun estimateMinutesBySpeed(
        distanceKm: Double,
        averageSpeedKmPerHour: Double,
        minMinutes: Int
    ): Int {
        return ceil((distanceKm / averageSpeedKmPerHour) * 60.0).toInt().coerceAtLeast(minMinutes)
    }

    private fun estimateTripHint(activeOrder: Order?, estimatedArrivalMinutes: Int?): Int? {
        val order = activeOrder ?: return null
        val eta = estimatedArrivalMinutes ?: return null
        return when {
            order.status == OrderStatus.RESERVED && eta >= 3 -> R.string.shuttle_delay_hint
            order.status == OrderStatus.BOARDING -> R.string.boarding_timeout_hint
            order.status == OrderStatus.IN_FLIGHT && eta <= 3 -> R.string.arrival_soon_hint
            else -> null
        }
    }

    private fun appendTrack(
        currentTrack: List<GeoPoint>,
        nextPoint: GeoPoint?,
        status: OrderStatus?
    ): List<GeoPoint> {
        if (nextPoint == null || status == null) {
            return currentTrack
        }
        if (status !in setOf(OrderStatus.RESERVED, OrderStatus.BOARDING, OrderStatus.IN_FLIGHT, OrderStatus.RETURNING)) {
            return currentTrack
        }

        val last = currentTrack.lastOrNull()
        return if (last == null || last.distanceTo(nextPoint) > 0.008) {
            (currentTrack + nextPoint).takeLast(160)
        } else {
            currentTrack
        }
    }

    private fun showSnackbar(
        messageRes: Int,
        actionRes: Int? = null,
        action: SnackbarAction? = null
    ) {
        _uiState.update {
            it.copy(
                snackbarEvent = SnackbarEvent(
                    messageRes = messageRes,
                    actionRes = actionRes,
                    action = action
                )
            )
        }
    }

    private companion object {
        val DEFAULT_LOCATION = GeoPoint(29.5630, 106.5516)
    }

    private data class GroundRouteInfo(
        val points: List<GeoPoint>,
        val distanceKm: Double?,
        val durationMinutes: Int?
    )
}

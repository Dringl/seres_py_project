package com.seres.evtoldemo.ui

import com.seres.evtoldemo.data.model.Evtol
import com.seres.evtoldemo.data.model.GeoPoint
import com.seres.evtoldemo.data.model.Order
import com.seres.evtoldemo.data.model.PriceEstimate
import com.seres.evtoldemo.data.model.Vertiport

enum class SnackbarAction {
    REQUEST_LOCATION,
    REFRESH_LOCATION,
    RETRY_ORDER,
    CLEAR_DESTINATION
}

enum class MainTab {
    MAP,
    TRIP,
    MORE
}

data class SnackbarEvent(
    val messageRes: Int,
    val messageArgs: List<String> = emptyList(),
    val actionRes: Int? = null,
    val action: SnackbarAction? = null
)

data class MainUiState(
    val hasLocationPermission: Boolean = false,
    val hasRequestedLocationPermission: Boolean = false,
    val shouldShowLocationPermissionCard: Boolean = true,
    val currentLocation: GeoPoint? = null,
    val initialLocatePoint: GeoPoint? = null,
    val initialLocateZoom: Double = 16.0,
    val allVertiports: List<Vertiport> = emptyList(),
    val vertiports: List<Vertiport> = emptyList(),
    val pickupVertiport: Vertiport? = null,
    val groundRoute: List<GeoPoint> = emptyList(),
    val groundDistanceKm: Double? = null,
    val groundDurationMinutes: Int? = null,
    val flightRoute: List<GeoPoint> = emptyList(),
    val nearbyVehicles: List<Evtol> = emptyList(),
    val activeVehicleLocation: GeoPoint? = null,
    val activeVehicleTrack: List<GeoPoint> = emptyList(),
    val selectedDestination: Vertiport? = null,
    val priceEstimate: PriceEstimate? = null,
    val activeOrder: Order? = null,
    val orderHistory: List<Order> = emptyList(),
    val isLoading: Boolean = false,
    val snackbarEvent: SnackbarEvent? = null,
    val estimatedArrivalMinutes: Int? = null,
    val tripHintRes: Int? = null
)

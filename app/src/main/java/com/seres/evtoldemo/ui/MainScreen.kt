package com.seres.evtoldemo.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.seres.evtoldemo.R
import com.seres.evtoldemo.data.model.GeoPoint
import com.seres.evtoldemo.data.model.OrderStatus
import com.seres.evtoldemo.data.model.VehicleStatus
import com.seres.evtoldemo.data.model.Vertiport
import com.seres.evtoldemo.data.model.distanceTo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

@Composable
fun MainRoute(viewModel: MainViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            viewModel.onLocationPermissionChanged(granted)
        }
    )

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.onLocationPermissionChanged(granted)
    }

    LaunchedEffect(uiState.snackbarEvent) {
        val event = uiState.snackbarEvent ?: return@LaunchedEffect
        val message = if (event.messageArgs.isEmpty()) {
            context.getString(event.messageRes)
        } else {
            context.getString(event.messageRes, *event.messageArgs.toTypedArray())
        }
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = event.actionRes?.let(context::getString),
            duration = if (event.actionRes != null) SnackbarDuration.Long else SnackbarDuration.Short
        )
        viewModel.consumeSnackbarEvent()
        if (result == SnackbarResult.ActionPerformed) {
            when (event.action) {
                SnackbarAction.REQUEST_LOCATION -> launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                SnackbarAction.REFRESH_LOCATION -> viewModel.refreshLocation()
                SnackbarAction.RETRY_ORDER -> viewModel.placeOrder()
                SnackbarAction.CLEAR_DESTINATION -> viewModel.refreshLocation()
                null -> Unit
            }
        }
    }

    MainScreen(
        state = uiState,
        snackbarHostState = snackbarHostState,
        onRequestLocationPermission = {
            launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        },
        onDismissLocationPermissionCard = viewModel::dismissLocationPermissionCard,
        onRefreshLocation = viewModel::refreshLocation,
        onDestinationSelected = viewModel::onDestinationSelected,
        onPlaceOrder = viewModel::placeOrder,
        onConfirmBoarded = viewModel::confirmPassengerBoarded,
        onCancelOrder = viewModel::cancelActiveOrder
    )
}

private fun isPlaceOrderEnabled(state: MainUiState): Boolean {
    val activeOrder = state.activeOrder
    val reusableOrder = when (activeOrder?.status) {
        OrderStatus.CANCELED,
        OrderStatus.FAILED -> true
        OrderStatus.RETURNING -> state.selectedDestination?.id != activeOrder.destination.id
        else -> false
    }
    return state.selectedDestination != null &&
        state.currentLocation != null &&
        state.pickupVertiport != null &&
        (activeOrder == null || reusableOrder) &&
        !state.isLoading
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    state: MainUiState,
    snackbarHostState: SnackbarHostState,
    onRequestLocationPermission: () -> Unit,
    onDismissLocationPermissionCard: () -> Unit,
    onRefreshLocation: () -> Unit,
    onDestinationSelected: (String) -> Unit,
    onPlaceOrder: () -> Unit,
    onConfirmBoarded: () -> Unit,
    onCancelOrder: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var currentTab by rememberSaveable { mutableStateOf(MainTab.MAP) }
    var showCancelConfirm by rememberSaveable { mutableStateOf(false) }
    val canPlaceOrder = isPlaceOrderEnabled(state)
    val currentMapMode = deriveMapHomeMode(state)
    val snackbarBottomPadding = when (currentTab) {
        MainTab.MAP -> when (currentMapMode) {
            MapHomeMode.READY_TO_BOOK -> 186.dp
            MapHomeMode.ACTIVE_PICKUP -> 178.dp
            MapHomeMode.ACTIVE_FLIGHT, MapHomeMode.RETURNING -> 170.dp
            MapHomeMode.POST_TRIP, MapHomeMode.IDLE -> 154.dp
        }
        MainTab.TRIP, MainTab.MORE -> 96.dp
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        colorScheme.background,
                        colorScheme.surfaceContainer,
                        colorScheme.background
                    )
                )
            )
    ) {
        if (showCancelConfirm) {
            AlertDialog(
                onDismissRequest = { showCancelConfirm = false },
                title = { Text(stringResource(R.string.cancel_confirm_title)) },
                text = { Text(stringResource(R.string.cancel_confirm_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        showCancelConfirm = false
                        onCancelOrder()
                    }) {
                        Text(stringResource(R.string.cancel_confirm_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCancelConfirm = false }) {
                        Text(stringResource(R.string.cancel_keep_action))
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            colorScheme.primary.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
        )

        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.padding(bottom = snackbarBottomPadding)
                )
            },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colorScheme.surfaceContainer.copy(alpha = 0.96f),
                        titleContentColor = colorScheme.onSurface,
                        actionIconContentColor = colorScheme.primary
                    ),
                    title = {
                        val titleRes = when (currentTab) {
                            MainTab.MAP -> R.string.map_home_title
                            MainTab.TRIP -> R.string.trip_title
                            MainTab.MORE -> R.string.more_title
                        }
                        val subtitleRes = when (currentTab) {
                            MainTab.MAP -> R.string.map_home_subtitle
                            MainTab.TRIP -> R.string.trip_subtitle
                            MainTab.MORE -> R.string.more_subtitle
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(titleRes),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(subtitleRes),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = if (state.hasLocationPermission) onRefreshLocation else onRequestLocationPermission
                        ) {
                            Text(
                                text = stringResource(
                                    if (state.hasLocationPermission) R.string.refresh_location
                                    else R.string.enable_location
                                ),
                                color = colorScheme.primary
                            )
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar(containerColor = colorScheme.surfaceContainer.copy(alpha = 0.96f)) {
                    MainTab.entries.forEach { tab ->
                        val labelRes = when (tab) {
                            MainTab.MAP -> R.string.tab_map
                            MainTab.TRIP -> R.string.tab_trip
                            MainTab.MORE -> R.string.tab_more
                        }
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = {
                                TabDot(
                                    active = currentTab == tab,
                                    color = when (tab) {
                                        MainTab.MAP -> colorScheme.primary
                                        MainTab.TRIP -> colorScheme.secondary
                                        MainTab.MORE -> colorScheme.tertiary
                                    }
                                )
                            },
                            label = { Text(stringResource(labelRes)) }
                        )
                    }
                }
            }
        ) { contentPadding ->
            when (currentTab) {
                MainTab.MAP -> MapHomeTab(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    state = state,
                    canPlaceOrder = canPlaceOrder,
                    onRequestLocationPermission = onRequestLocationPermission,
                    onDismissLocationPermissionCard = onDismissLocationPermissionCard,
                    onRefreshLocation = onRefreshLocation,
                    onDestinationSelected = onDestinationSelected,
                    onPlaceOrder = onPlaceOrder,
                    onConfirmBoarded = onConfirmBoarded,
                    onCancelOrder = { showCancelConfirm = true },
                    onOpenTripTab = { currentTab = MainTab.TRIP }
                )

                MainTab.TRIP -> TripTab(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    state = state,
                    onConfirmBoarded = onConfirmBoarded,
                    onCancelOrder = { showCancelConfirm = true },
                )

                MainTab.MORE -> MoreTab(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    state = state,
                    onRequestLocationPermission = onRequestLocationPermission,
                    onDismissLocationPermissionCard = onDismissLocationPermissionCard
                )
            }
        }
    }
}

@Composable
private fun TabDot(active: Boolean, color: Color) {
    Box(
        modifier = Modifier
            .size(if (active) 12.dp else 10.dp)
            .background(
                color = if (active) color else color.copy(alpha = 0.45f),
                shape = CircleShape
            )
    )
}

private enum class MapHomeMode {
    IDLE,
    READY_TO_BOOK,
    ACTIVE_PICKUP,
    ACTIVE_FLIGHT,
    RETURNING,
    POST_TRIP
}

private fun deriveMapHomeMode(state: MainUiState): MapHomeMode {
    return when (state.activeOrder?.status) {
        OrderStatus.CREATED,
        OrderStatus.ASSIGNED,
        OrderStatus.RESERVED,
        OrderStatus.BOARDING -> MapHomeMode.ACTIVE_PICKUP
        OrderStatus.IN_FLIGHT -> MapHomeMode.ACTIVE_FLIGHT
        OrderStatus.RETURNING -> if (isPlaceOrderEnabled(state)) {
            MapHomeMode.READY_TO_BOOK
        } else {
            MapHomeMode.RETURNING
        }
        OrderStatus.CANCELED,
        OrderStatus.FAILED -> if (isPlaceOrderEnabled(state)) {
            MapHomeMode.READY_TO_BOOK
        } else {
            MapHomeMode.POST_TRIP
        }
        OrderStatus.DONE -> MapHomeMode.POST_TRIP
        else -> if (state.selectedDestination != null) MapHomeMode.READY_TO_BOOK else MapHomeMode.IDLE
    }
}

private fun markerTitleForVertiport(context: Context, state: MainUiState, vertiport: Vertiport): String {
    val isPickup = state.pickupVertiport?.id == vertiport.id
    val isDestination = state.selectedDestination?.id == vertiport.id
    return when {
        isPickup -> context.getString(R.string.marker_pickup_suffix, vertiport.name)
        isDestination -> context.getString(R.string.marker_destination_suffix, vertiport.name)
        else -> vertiport.name
    }
}

@Composable
private fun MapHomeTab(
    modifier: Modifier,
    state: MainUiState,
    canPlaceOrder: Boolean,
    onRequestLocationPermission: () -> Unit,
    onDismissLocationPermissionCard: () -> Unit,
    onRefreshLocation: () -> Unit,
    onDestinationSelected: (String) -> Unit,
    onPlaceOrder: () -> Unit,
    onConfirmBoarded: () -> Unit,
    onCancelOrder: () -> Unit,
    onOpenTripTab: () -> Unit
) {
    val mode = deriveMapHomeMode(state)

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        MapHeroSection(
            modifier = Modifier.fillMaxSize(),
            state = state,
            onRefreshLocation = onRefreshLocation,
            onDestinationSelected = onDestinationSelected,
            bottomInset = when (mode) {
                MapHomeMode.ACTIVE_FLIGHT, MapHomeMode.RETURNING -> 92.dp
                MapHomeMode.ACTIVE_PICKUP -> 104.dp
                MapHomeMode.READY_TO_BOOK -> 116.dp
                MapHomeMode.POST_TRIP, MapHomeMode.IDLE -> 92.dp
            },
            dimMapControls = mode == MapHomeMode.ACTIVE_PICKUP ||
                mode == MapHomeMode.ACTIVE_FLIGHT ||
                mode == MapHomeMode.RETURNING
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!state.hasLocationPermission && state.shouldShowLocationPermissionCard) {
                LocationPermissionSection(
                    onRequestLocationPermission = onRequestLocationPermission,
                    onDismiss = onDismissLocationPermissionCard,
                    compact = true
                )
            } else if (!state.hasLocationPermission) {
                PermissionFallbackBanner(onRequestLocationPermission = onRequestLocationPermission)
            }
            MapStatusOverlay(state = state, mode = mode)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.isLoading) {
                LoadingIndicator(state.isLoading)
            }
            MapBottomCollapsedBar(
                state = state,
                mode = mode,
                canPlaceOrder = canPlaceOrder,
                onToggleExpanded = onOpenTripTab,
                onPlaceOrder = onPlaceOrder,
                onConfirmBoarded = onConfirmBoarded,
                onCancelOrder = onCancelOrder
            )
        }
    }
}

@Composable
private fun TripTab(
    modifier: Modifier,
    state: MainUiState,
    onConfirmBoarded: () -> Unit,
    onCancelOrder: () -> Unit
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { StatusPanel(state) }
        item { RouteSection(state) }
        item {
            CyberPanel(
                title = stringResource(R.string.trip_title),
                subtitle = state.activeOrder?.status?.let { statusLabel(it) } ?: stringResource(R.string.status_idle),
                accent = MaterialTheme.colorScheme.primary
            ) {
                state.activeVehicleLocation?.let { point ->
                    Text(
                        text = stringResource(
                            R.string.vehicle_location_format,
                            point.latitude,
                            point.longitude
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } ?: Text(
                    text = stringResource(R.string.gps_locking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            PricingAndOrderSection(
                state = state,
                canPlaceOrder = false,
                onPlaceOrder = {},
                onConfirmBoarded = onConfirmBoarded,
                onCancelOrder = onCancelOrder,
                emphasizeActionsOnly = true
            )
        }
    }
}

@Composable
private fun MoreTab(
    modifier: Modifier,
    state: MainUiState,
    onRequestLocationPermission: () -> Unit,
    onDismissLocationPermissionCard: () -> Unit
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!state.hasLocationPermission && state.shouldShowLocationPermissionCard) {
            item {
                LocationPermissionSection(
                    onRequestLocationPermission = onRequestLocationPermission,
                    onDismiss = onDismissLocationPermissionCard
                )
            }
        }
        item { VehicleSection(state) }
        item { OrderHistorySection(state) }
        item {
            CyberPanel(
                title = stringResource(R.string.more_title),
                subtitle = stringResource(R.string.map_subtitle),
                accent = MaterialTheme.colorScheme.tertiary
            ) {
                Text(
                    text = stringResource(R.string.map_overlay_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LoadingIndicator(isLoading: Boolean) {
    if (!isLoading) return
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.secondaryContainer
    )
}

@Composable
private fun LocationPermissionSection(
    onRequestLocationPermission: () -> Unit,
    onDismiss: () -> Unit,
    compact: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme

    CyberPanel(
        title = stringResource(R.string.permission_card_title),
        subtitle = stringResource(R.string.enable_location),
        accent = colorScheme.primary,
        modifier = if (compact) Modifier.width(320.dp) else Modifier
    ) {
        Text(
            text = stringResource(R.string.permission_card_body),
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRequestLocationPermission) {
                Text(stringResource(R.string.enable_location))
            }
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.permission_card_dismiss))
            }
        }
    }
}

@Composable
private fun PermissionFallbackBanner(onRequestLocationPermission: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.permission_denied_banner),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSecondaryContainer
            )
            TextButton(onClick = onRequestLocationPermission) {
                Text(stringResource(R.string.enable_location))
            }
        }
    }
}

@Composable
private fun MapStatusOverlay(state: MainUiState, mode: MapHomeMode) {
    val colorScheme = MaterialTheme.colorScheme
    val order = state.activeOrder
    val statusText = order?.status?.let { statusLabel(it) } ?: stringResource(R.string.status_idle)
    val hint = when (mode) {
        MapHomeMode.IDLE -> stringResource(R.string.map_bottom_select_hint)
        MapHomeMode.READY_TO_BOOK -> stringResource(R.string.map_bottom_ready_brief)
        MapHomeMode.ACTIVE_PICKUP -> when (order?.status) {
            OrderStatus.BOARDING -> stringResource(R.string.map_bottom_boarding_brief)
            else -> stringResource(R.string.map_bottom_waiting_hint)
        }
        MapHomeMode.ACTIVE_FLIGHT -> stringResource(R.string.map_bottom_flight_brief)
        MapHomeMode.RETURNING -> stringResource(R.string.order_returning_message)
        MapHomeMode.POST_TRIP -> stringResource(R.string.trip_finished_message)
    }
    val compact = mode == MapHomeMode.ACTIVE_PICKUP || mode == MapHomeMode.ACTIVE_FLIGHT || mode == MapHomeMode.RETURNING
    val priceEtaSummary = if (mode == MapHomeMode.READY_TO_BOOK) {
        listOfNotNull(
            state.priceEstimate?.let { stringResource(R.string.price_format, it.amountDisplay) },
            state.estimatedArrivalMinutes?.let { stringResource(R.string.destination_eta_format, it) }
        ).joinToString("   ")
    } else {
        ""
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 20.dp else 22.dp),
        color = colorScheme.surfaceContainer.copy(alpha = if (compact) 0.72f else 0.78f),
        border = BorderStroke(1.dp, colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (compact) 14.dp else 16.dp,
                vertical = if (compact) 12.dp else 14.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)
        ) {
            Text(
                text = stringResource(R.string.map_overlay_title),
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                color = colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = statusText,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (compact) {
                val inlineSummary = listOfNotNull(
                    state.pickupVertiport?.name,
                    state.selectedDestination?.name?.let {
                        stringResource(R.string.map_compact_eta_prefix) + " · " + it
                    },
                    state.activeOrder?.priceEstimate?.takeIf { fee -> fee.cancellationFeeCents > 0 }?.let {
                        stringResource(R.string.cancellation_fee_format, it.cancellationFeeDisplay)
                    }
                ).joinToString("   ")
                if (inlineSummary.isNotBlank()) {
                    Text(
                        text = inlineSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                state.pickupVertiport?.let {
                    Text(
                        text = stringResource(R.string.pickup_label, it.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                state.selectedDestination?.let {
                    Text(
                        text = stringResource(R.string.destination_label, it.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                if (priceEtaSummary.isNotBlank()) {
                    Text(
                        text = priceEtaSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.primary
                    )
                }
            }
            state.tripHintRes?.let { hintRes ->
                TripHintBadge(hintRes)
            }
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TripHintBadge(messageRes: Int) {
    val colorScheme = MaterialTheme.colorScheme
    val (accent, symbol) = when (messageRes) {
        R.string.arrival_soon_hint -> colorScheme.tertiary to "◆"
        R.string.boarding_timeout_hint -> colorScheme.error to "!"
        R.string.shuttle_delay_hint -> colorScheme.secondary to "●"
        else -> colorScheme.primary to "•"
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = symbol,
                color = accent,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = stringResource(messageRes),
                color = accent,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun MapHeroSection(
    modifier: Modifier,
    state: MainUiState,
    onRefreshLocation: () -> Unit,
    onDestinationSelected: (String) -> Unit,
    bottomInset: Dp,
    dimMapControls: Boolean
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colorScheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val bottomPaddingPx = remember(bottomInset, density) { with(density) { bottomInset.roundToPx() } }
    val pickupRouteColor = Color(0xFF11D7FF)
    val flightRouteColor = Color(0xFF8E7BFF)
    val returnRouteColor = Color(0xFFFF5F6D)
    val mapView = remember {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)

        MapView(context).apply {
            onCreate(null)
            map.apply {
                mapType = AMap.MAP_TYPE_NORMAL
                uiSettings.isZoomControlsEnabled = false
                uiSettings.isMyLocationButtonEnabled = false
                uiSettings.isCompassEnabled = false
                uiSettings.isScaleControlsEnabled = false
                uiSettings.isScrollGesturesEnabled = true
            }
        }
    }
    val userMarkerIcon = remember(context) {
        context.bitmapDescriptorFromVector(R.drawable.ic_map_user, sizeDp = 34f)
    }
    val vertiportMarkerIcon = remember(context) {
        context.bitmapDescriptorFromVector(R.drawable.ic_map_vertiport, sizeDp = 32f)
    }
    val vehicleMarkerIcon = remember(context) {
        context.bitmapDescriptorFromVector(R.drawable.ic_map_plane, sizeDp = 28f)
    }
    val activeVehicleMarkerIcon = remember(context) {
        context.bitmapDescriptorFromVector(R.drawable.ic_map_plane_flying, sizeDp = 28f)
    }
    var initialCameraApplied by remember(mapView) { mutableStateOf(false) }
    var lastRouteFocusKey by remember(mapView) { mutableStateOf("") }
    val renderCache = remember(mapView) { MapRenderCache() }
    var smoothedActiveVehiclePoint by remember(mapView) { mutableStateOf<GeoPoint?>(null) }
    var smoothingTargetPoint by remember(mapView) { mutableStateOf<GeoPoint?>(null) }
    var smoothedActiveVehicleHeading by remember(mapView) { mutableStateOf<Float?>(null) }
    var smoothingTargetHeading by remember(mapView) { mutableStateOf<Float?>(null) }

    LaunchedEffect(state.activeVehicleLocation, state.activeOrder?.status) {
        val target = state.activeVehicleLocation
        if (target == null) {
            smoothedActiveVehiclePoint = null
            smoothingTargetPoint = null
            smoothedActiveVehicleHeading = null
            smoothingTargetHeading = null
            return@LaunchedEffect
        }
        val current = smoothedActiveVehiclePoint
        if (current == null) {
            smoothedActiveVehiclePoint = target
            smoothingTargetPoint = target
            val resolvedHeading = resolveActiveVehicleHeading(state, target)
            smoothedActiveVehicleHeading = resolvedHeading
            smoothingTargetHeading = resolvedHeading
            return@LaunchedEffect
        }
        smoothingTargetPoint = target
        val resolvedHeading = resolveActiveVehicleHeading(state, target)
        smoothingTargetHeading = resolvedHeading
        if (resolvedHeading == null) {
            smoothedActiveVehicleHeading = null
        }
    }

    LaunchedEffect(mapView) {
        while (true) {
            val target = smoothingTargetPoint
            val current = smoothedActiveVehiclePoint
            if (target != null && current != null) {
                val next = interpolateGeoPoint(current, target, 0.18)
                smoothedActiveVehiclePoint = if (next.distanceTo(target) <= 0.0012) target else next
            }
            val currentHeading = smoothedActiveVehicleHeading
            val targetHeading = smoothingTargetHeading
            if (currentHeading != null && targetHeading != null) {
                smoothedActiveVehicleHeading = interpolateHeading(currentHeading, targetHeading, 0.22f)
            } else if (targetHeading == null) {
                smoothedActiveVehicleHeading = null
            }
            delay(16)
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
        }
    }

    Box(
        modifier = modifier
            .border(
                border = BorderStroke(1.dp, colorScheme.outlineVariant),
                shape = RoundedCornerShape(28.dp)
            )
            .background(colorScheme.surfaceVariant, RoundedCornerShape(28.dp))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->
                val aMap = view.map
                aMap.setOnMarkerClickListener { marker ->
                    val markerKey = marker.`object` as? String
                    val portId = markerKey
                        ?.takeIf { it.startsWith("vertiport:") }
                        ?.substringAfter("vertiport:")
                        ?.substringBefore(":")
                    val port = portId?.let { id ->
                        state.allVertiports.firstOrNull { vertiport -> vertiport.id == id }
                    } ?: state.allVertiports.firstOrNull { vertiport ->
                        vertiport.location.distanceTo(GeoPoint(marker.position.latitude, marker.position.longitude)) < 0.05
                    }
                    val selectionLocked = state.activeOrder?.status in setOf(
                        OrderStatus.CREATED,
                        OrderStatus.ASSIGNED,
                        OrderStatus.RESERVED,
                        OrderStatus.BOARDING,
                        OrderStatus.IN_FLIGHT
                    )
                    if (port != null && !selectionLocked) {
                        onDestinationSelected(port.id)
                        true
                    } else {
                        false
                    }
                }
                if (!initialCameraApplied) {
                    val overviewPoints = collectOverviewPoints(state)
                    initialCameraApplied = if (overviewPoints.size >= 2) {
                        applyOverviewCamera(
                            aMap,
                            overviewPoints,
                            viewportWidth = view.width,
                            viewportHeight = (view.height - bottomPaddingPx).coerceAtLeast(1)
                        )
                    } else {
                        val initialPoint = state.initialLocatePoint
                            ?: state.currentLocation
                            ?: state.pickupVertiport?.location
                            ?: state.selectedDestination?.location
                        if (initialPoint != null) {
                            aMap.moveCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    initialPoint.toLatLng(),
                                    state.initialLocateZoom.toFloat()
                                )
                            )
                            true
                        } else false
                    }
                }

                val routeFocusKey = listOf(
                    state.activeOrder?.id,
                    state.selectedDestination?.id,
                    state.activeOrder?.status?.name,
                    state.groundRoute.size.toString(),
                    state.flightRoute.size.toString(),
                    state.activeVehicleTrack.size.toString()
                ).joinToString(":")
                if (routeFocusKey != lastRouteFocusKey && (state.selectedDestination != null || state.activeOrder != null)) {
                    val overviewPoints = collectOverviewPoints(state)
                    if (overviewPoints.size >= 2) {
                        applyOverviewCamera(
                            aMap,
                            overviewPoints,
                            animate = true,
                            viewportWidth = view.width,
                            viewportHeight = (view.height - bottomPaddingPx).coerceAtLeast(1)
                        )
                    }
                    lastRouteFocusKey = routeFocusKey
                }

                aMap.drawEntityMarkers(
                    context = context,
                    state = state,
                    userMarkerIcon = userMarkerIcon,
                    vertiportMarkerIcon = vertiportMarkerIcon,
                    vehicleMarkerIcon = vehicleMarkerIcon,
                    activeVehicleMarkerIcon = activeVehicleMarkerIcon,
                    renderCache = renderCache,
                    smoothedActiveVehiclePoint = smoothedActiveVehiclePoint,
                    smoothedActiveVehicleHeading = smoothedActiveVehicleHeading
                )
                aMap.renderRoutePolylines(
                    state = state,
                    pickupRouteColor = pickupRouteColor,
                    flightRouteColor = flightRouteColor,
                    returnRouteColor = returnRouteColor,
                    renderCache = renderCache
                )
            }
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp)
                .alpha(if (dimMapControls) 0.58f else 1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            MapZoomControl(
                dimmed = dimMapControls,
                onZoomIn = { mapView.map.animateCamera(CameraUpdateFactory.zoomIn()) },
                onZoomOut = { mapView.map.animateCamera(CameraUpdateFactory.zoomOut()) }
            )
            MapControlButton(
                text = stringResource(R.string.map_overview),
                contentDescription = stringResource(R.string.map_overview),
                dimmed = dimMapControls,
                onClick = {
                    val points = collectOverviewPoints(state)
                    if (points.size >= 2) {
                        applyOverviewCamera(
                            mapView.map,
                            points,
                            animate = true,
                            viewportWidth = mapView.width,
                            viewportHeight = (mapView.height - bottomPaddingPx).coerceAtLeast(1)
                        )
                    } else {
                        val point = state.initialLocatePoint ?: state.currentLocation
                        if (point != null) {
                            mapView.map.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    point.toLatLng(),
                                    state.initialLocateZoom.toFloat()
                                )
                            )
                        } else {
                            onRefreshLocation()
                        }
                    }
                }
            )
            MapControlButton(
                text = stringResource(R.string.map_locate),
                contentDescription = stringResource(R.string.map_locate),
                dimmed = dimMapControls,
                onClick = {
                    val locatePoint = state.initialLocatePoint ?: state.currentLocation
                    if (locatePoint != null) {
                        mapView.map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                locatePoint.toLatLng(),
                                state.initialLocateZoom.toFloat()
                            )
                        )
                    } else {
                        onRefreshLocation()
                    }
                }
            )
        }
    }
}

@Composable
private fun MapBottomCollapsedBar(
    state: MainUiState,
    mode: MapHomeMode,
    canPlaceOrder: Boolean,
    onToggleExpanded: () -> Unit,
    onPlaceOrder: () -> Unit,
    onConfirmBoarded: () -> Unit,
    onCancelOrder: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val compact = mode == MapHomeMode.ACTIVE_PICKUP || mode == MapHomeMode.ACTIVE_FLIGHT || mode == MapHomeMode.RETURNING
    val title = when (mode) {
        MapHomeMode.IDLE -> stringResource(R.string.destination_current_empty)
        MapHomeMode.READY_TO_BOOK -> state.selectedDestination?.let {
            stringResource(R.string.destination_current_format, it.name)
        } ?: stringResource(R.string.destination_current_empty)
        MapHomeMode.ACTIVE_PICKUP -> state.activeOrder?.status?.let { statusLabel(it) }
            ?: stringResource(R.string.status_reserved)
        MapHomeMode.ACTIVE_FLIGHT -> state.activeOrder?.status?.let { statusLabel(it) }
            ?: stringResource(R.string.status_in_flight)
        MapHomeMode.RETURNING -> stringResource(R.string.status_returning)
        MapHomeMode.POST_TRIP -> state.activeOrder?.status?.let { statusLabel(it) }
            ?: stringResource(R.string.trip_title)
    }
    val subtitle = when (mode) {
        MapHomeMode.IDLE -> stringResource(R.string.map_bottom_select_hint)
        MapHomeMode.READY_TO_BOOK -> listOfNotNull(
            state.priceEstimate?.let { stringResource(R.string.price_format, it.amountDisplay) },
            state.estimatedArrivalMinutes?.let { stringResource(R.string.destination_eta_format, it) }
        ).joinToString("  ·  ").ifBlank { stringResource(R.string.pricing_ready) }
        MapHomeMode.ACTIVE_PICKUP -> stringResource(R.string.map_bottom_waiting_hint)
        MapHomeMode.ACTIVE_FLIGHT -> stringResource(R.string.map_bottom_flight_hint)
        MapHomeMode.RETURNING -> state.activeOrder?.priceEstimate?.let {
            stringResource(R.string.cancellation_fee_format, it.cancellationFeeDisplay)
        } ?: stringResource(R.string.order_returning_message)
        MapHomeMode.POST_TRIP -> stringResource(R.string.trip_finished_message)
    }
    val primaryActionText = when {
        mode == MapHomeMode.READY_TO_BOOK -> stringResource(R.string.place_order)
        state.activeOrder?.status == OrderStatus.BOARDING -> stringResource(R.string.confirm_boarded)
        else -> stringResource(R.string.map_open_trip)
    }
    val primaryEnabled = when {
        mode == MapHomeMode.READY_TO_BOOK -> canPlaceOrder && !state.isLoading
        state.activeOrder?.status == OrderStatus.BOARDING -> true
        else -> true
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 22.dp else 26.dp),
        color = colorScheme.surfaceContainer.copy(alpha = if (compact) 0.88f else 0.92f),
        shadowElevation = if (compact) 4.dp else 8.dp,
        border = BorderStroke(1.dp, colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 14.dp else 16.dp,
                vertical = if (compact) 10.dp else 14.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == MapHomeMode.ACTIVE_PICKUP && state.activeOrder?.status in setOf(
                        OrderStatus.CREATED,
                        OrderStatus.ASSIGNED,
                        OrderStatus.RESERVED,
                        OrderStatus.BOARDING
                    )
                ) {
                    OutlinedButton(
                        onClick = onCancelOrder,
                        border = BorderStroke(1.dp, colorScheme.error),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.error)
                    ) {
                        Text(stringResource(R.string.cancel_order), maxLines = 1)
                    }
                }
                Button(
                    onClick = {
                        when {
                            mode == MapHomeMode.READY_TO_BOOK -> onPlaceOrder()
                            state.activeOrder?.status == OrderStatus.BOARDING -> onConfirmBoarded()
                            else -> onToggleExpanded()
                        }
                    },
                    enabled = primaryEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.primary,
                        contentColor = colorScheme.onPrimary
                    )
                ) {
                    Text(primaryActionText, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun MapBottomSheetContent(
    state: MainUiState,
    mode: MapHomeMode,
    canPlaceOrder: Boolean,
    onDestinationSelected: (String) -> Unit,
    onPlaceOrder: () -> Unit,
    onConfirmBoarded: () -> Unit,
    onCancelOrder: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = colorScheme.surfaceContainer.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(42.dp)
                    .height(4.dp)
                    .background(colorScheme.outlineVariant, CircleShape)
            )
            when (mode) {
                MapHomeMode.IDLE, MapHomeMode.READY_TO_BOOK -> {
                    DestinationSection(
                        state = state,
                        onDestinationSelected = onDestinationSelected,
                        embedded = true
                    )
                    PricingAndOrderSection(
                        state = state,
                        canPlaceOrder = canPlaceOrder,
                        onPlaceOrder = onPlaceOrder,
                        onConfirmBoarded = onConfirmBoarded,
                        onCancelOrder = onCancelOrder,
                        embedded = true,
                        emphasizeActionsOnly = false
                    )
                }
                MapHomeMode.ACTIVE_PICKUP -> {
                    StatusPanel(state)
                    PricingAndOrderSection(
                        state = state,
                        canPlaceOrder = false,
                        onPlaceOrder = onPlaceOrder,
                        onConfirmBoarded = onConfirmBoarded,
                        onCancelOrder = onCancelOrder,
                        embedded = true,
                        emphasizeActionsOnly = true
                    )
                }
                MapHomeMode.ACTIVE_FLIGHT,
                MapHomeMode.POST_TRIP -> {
                    PricingAndOrderSection(
                        state = state,
                        canPlaceOrder = false,
                        onPlaceOrder = onPlaceOrder,
                        onConfirmBoarded = onConfirmBoarded,
                        onCancelOrder = onCancelOrder,
                        embedded = true,
                        emphasizeActionsOnly = true
                    )
                }
                MapHomeMode.RETURNING -> {
                    StatusPanel(state)
                    PricingAndOrderSection(
                        state = state,
                        canPlaceOrder = false,
                        onPlaceOrder = onPlaceOrder,
                        onConfirmBoarded = onConfirmBoarded,
                        onCancelOrder = onCancelOrder,
                        embedded = true,
                        emphasizeActionsOnly = true
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(state: MainUiState) {
    val colorScheme = MaterialTheme.colorScheme
    val order = state.activeOrder
    val statusText = order?.status?.let { statusLabel(it) } ?: stringResource(R.string.status_idle)
    val accent = when (order?.status) {
        OrderStatus.IN_FLIGHT -> colorScheme.tertiary
        OrderStatus.BOARDING -> colorScheme.secondary
        OrderStatus.DONE -> colorScheme.primary
        OrderStatus.CANCELED, OrderStatus.FAILED -> colorScheme.error
        null -> colorScheme.primary
        else -> colorScheme.primary
    }

    val content: @Composable ColumnScope.() -> Unit = {
        val destinationSummary = state.selectedDestination?.let {
            stringResource(R.string.destination_current_format, it.name)
        }
        val liveHint = when (state.activeOrder?.status) {
            OrderStatus.RETURNING -> state.activeOrder.priceEstimate.takeIf { it.cancellationFeeCents > 0 }?.let {
                stringResource(R.string.cancellation_fee_format, it.cancellationFeeDisplay)
            }
            OrderStatus.IN_FLIGHT -> stringResource(R.string.map_bottom_flight_hint)
            OrderStatus.RESERVED -> stringResource(R.string.map_bottom_waiting_hint)
            OrderStatus.BOARDING -> stringResource(R.string.map_bottom_boarding_brief)
            OrderStatus.FAILED -> stringResource(R.string.status_failed_detail)
            else -> null
        }
        val extraHint = state.tripHintRes?.let { stringResource(it) }
        val etaText = state.estimatedArrivalMinutes?.let {
            stringResource(R.string.eta_format, it)
        }
        if (destinationSummary != null || liveHint != null || etaText != null || extraHint != null) {
            Text(
                text = listOfNotNull(destinationSummary, liveHint, etaText, extraHint).joinToString("  ·  "),
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        order?.let {
            Text(
                text = stringResource(R.string.order_vehicle_format, it.id, it.vehicleId),
                color = colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium
            )
        }

        state.pickupVertiport?.let { pickup ->
            Text(
                text = stringResource(R.string.pickup_label, pickup.name),
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        state.selectedDestination?.let { destination ->
            Text(
                text = stringResource(R.string.destination_label, destination.name),
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text(
            text = state.currentLocation?.let {
                stringResource(R.string.gps_format, it.latitude, it.longitude)
            } ?: stringResource(R.string.gps_locking),
            color = colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }

    CyberPanel(
        title = stringResource(R.string.status_panel_title),
        subtitle = statusText,
        accent = accent
    ) {
        content()
    }
}

private fun GeoPoint.toLatLng(): LatLng = LatLng(latitude, longitude)

@Composable
private fun MapZoomControl(
    dimmed: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.width(52.dp),
        shape = RoundedCornerShape(16.dp),
        color = colorScheme.surface.copy(alpha = if (dimmed) 0.62f else 0.92f),
        shadowElevation = if (dimmed) 2.dp else 8.dp,
        border = BorderStroke(1.dp, colorScheme.outlineVariant)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MapZoomItem(
                text = "+",
                contentDescription = stringResource(R.string.zoom_in),
                onClick = onZoomIn
            )
            HorizontalDivider(color = colorScheme.outlineVariant, thickness = 1.dp)
            MapZoomItem(
                text = "－",
                contentDescription = stringResource(R.string.zoom_out),
                onClick = onZoomOut
            )
        }
    }
}

@Composable
private fun MapZoomItem(
    text: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun MapControlButton(
    text: String,
    contentDescription: String,
    dimmed: Boolean,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .size(52.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = colorScheme.surface.copy(alpha = if (dimmed) 0.6f else 0.94f),
        shadowElevation = if (dimmed) 2.dp else 8.dp,
        border = BorderStroke(1.dp, colorScheme.outlineVariant)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurface
            )
        }
    }
}

private fun AMap.drawEntityMarkers(
    context: Context,
    state: MainUiState,
    userMarkerIcon: BitmapDescriptor,
    vertiportMarkerIcon: BitmapDescriptor,
    vehicleMarkerIcon: BitmapDescriptor,
    activeVehicleMarkerIcon: BitmapDescriptor,
    renderCache: MapRenderCache,
    smoothedActiveVehiclePoint: GeoPoint?,
    smoothedActiveVehicleHeading: Float?
) {
    val allVertiports = state.allVertiports.ifEmpty { state.vertiports }
    val activeOrder = state.activeOrder
    val activeVehicleId = activeOrder?.vehicleId
    val visibleMarkerKeys = mutableSetOf<String>()

    state.currentLocation?.let { current ->
        visibleMarkerKeys += "user"
        upsertMarker(
            cache = renderCache.markers,
            key = "user",
            options = MarkerOptions()
                .position(current.toLatLng())
                .title(context.getString(R.string.marker_me))
                .snippet(context.getString(R.string.marker_passenger))
                .icon(userMarkerIcon)
                .anchor(0.5f, 0.5f)
                .zIndex(30f)
        )
    }

    val displayVertiports = allVertiports.ifEmpty { state.vertiports }

    displayVertiports.forEach { vertiport ->
        val isPickup = state.pickupVertiport?.id == vertiport.id
        val isDestination = state.selectedDestination?.id == vertiport.id
        val roleKey = when {
            isPickup && isDestination -> "pickup-destination"
            isPickup -> "pickup"
            isDestination -> "destination"
            else -> "normal"
        }
        val key = "vertiport:${vertiport.id}:$roleKey"
        visibleMarkerKeys += key
        val markerTitle = when {
            isPickup -> context.getString(R.string.marker_pickup_suffix, vertiport.name)
            isDestination -> context.getString(R.string.marker_destination_suffix, vertiport.name)
            else -> vertiport.name
        }
        upsertMarker(
            cache = renderCache.markers,
            key = key,
            options = MarkerOptions()
                .position(vertiport.location.toLatLng())
                .title(markerTitle)
                .snippet(context.getString(R.string.marker_vertiport))
                .icon(vertiportMarkerIcon)
                .anchor(0.5f, 0.58f)
                .zIndex(0f)
        )
    }

    val renderedVehicleIds = mutableSetOf<String>()
    state.nearbyVehicles.forEach { vehicle ->
        val isActiveVehicle = activeVehicleId == vehicle.id
        if (isActiveVehicle && activeOrder != null) {
            return@forEach
        }
        renderedVehicleIds += vehicle.id
        val rawVehiclePoint = if (isActiveVehicle) {
            smoothedActiveVehiclePoint ?: state.activeVehicleLocation ?: vehicle.location
        } else {
            vehicle.location
        }
        val isFlying = if (isActiveVehicle && activeOrder != null) {
            activeOrder.status == OrderStatus.IN_FLIGHT
        } else {
            vehicle.status == VehicleStatus.IN_FLIGHT
        }
        val vehiclePoint = resolveVehicleDisplayPoint(
            rawPoint = rawVehiclePoint,
            isFlying = isFlying,
            isActiveVehicle = isActiveVehicle,
            destination = state.selectedDestination,
            allVertiports = allVertiports,
            orderStatus = if (isActiveVehicle) activeOrder?.status else null,
            pickupVertiport = if (isActiveVehicle) activeOrder?.pickupVertiport ?: state.pickupVertiport else null
        )
        val statusText = if (isActiveVehicle && activeOrder != null) {
            statusLabel(context, activeOrder.status)
        } else {
            vehicleStatusLabel(context, vehicle.status)
        }
        val heading = if (isActiveVehicle) smoothedActiveVehicleHeading ?: resolveActiveVehicleHeading(state, vehiclePoint) else null
        val key = "vehicle:${vehicle.id}"
        visibleMarkerKeys += key
        val markerAnchor = resolveVehicleAnchor(isFlying)
        upsertMarker(
            cache = renderCache.markers,
            key = key,
            options = MarkerOptions()
                .position(vehiclePoint.toLatLng())
                .title(vehicle.name)
                .snippet(context.getString(R.string.vehicle_status_battery_format, statusText, vehicle.batteryPercent))
                .icon(if (isFlying) activeVehicleMarkerIcon else vehicleMarkerIcon)
                .anchor(markerAnchor.first, markerAnchor.second)
                .setFlat(isFlying)
                .zIndex(if (isActiveVehicle) 120f else 96f),
            heading = heading,
            flat = isFlying
        )
    }

    if (activeOrder != null) {
        val activeVehiclePoint = smoothedActiveVehiclePoint
            ?: state.activeVehicleLocation
            ?: state.nearbyVehicles.firstOrNull { it.id == activeOrder.vehicleId }?.location
        if (activeVehiclePoint != null) {
            val isFlying = activeOrder.status == OrderStatus.IN_FLIGHT
            val heading = smoothedActiveVehicleHeading ?: resolveActiveVehicleHeading(state, activeVehiclePoint)
            val anchoredPoint = resolveVehicleDisplayPoint(
                rawPoint = activeVehiclePoint,
                isFlying = isFlying,
                isActiveVehicle = true,
                destination = activeOrder.destination,
                allVertiports = allVertiports,
                orderStatus = activeOrder.status,
                pickupVertiport = activeOrder.pickupVertiport
            )
            val key = "vehicle:${activeOrder.vehicleId}"
            visibleMarkerKeys += key
            val markerAnchor = resolveVehicleAnchor(isFlying)
            upsertMarker(
                cache = renderCache.markers,
                key = key,
                options = MarkerOptions()
                    .position(anchoredPoint.toLatLng())
                    .title(activeOrder.vehicleId)
                    .snippet(context.getString(R.string.marker_vehicle_track))
                    .icon(if (isFlying) activeVehicleMarkerIcon else vehicleMarkerIcon)
                    .anchor(markerAnchor.first, markerAnchor.second)
                    .setFlat(isFlying)
                    .zIndex(140f),
                heading = heading,
                flat = isFlying
            )
        }
    }

    renderCache.markers.keys
        .filterNot { it in visibleMarkerKeys }
        .toList()
        .forEach { key ->
            renderCache.markers.remove(key)?.remove()
        }
}

private fun AMap.renderRoutePolylines(
    state: MainUiState,
    pickupRouteColor: Color,
    flightRouteColor: Color,
    returnRouteColor: Color,
    renderCache: MapRenderCache
) {
    val visiblePolylineKeys = mutableSetOf<String>()

    if (state.groundRoute.size >= 2 && state.activeOrder?.status !in setOf(OrderStatus.IN_FLIGHT, OrderStatus.RETURNING)) {
        visiblePolylineKeys += "ground-glow"
        visiblePolylineKeys += "ground-main"
        upsertPolyline(
            cache = renderCache.polylines,
            key = "ground-glow",
            points = state.groundRoute,
            color = pickupRouteColor.copy(alpha = 0.22f).toArgb(),
            width = if (state.activeOrder == null) 16f else 14f
        )
        upsertPolyline(
            cache = renderCache.polylines,
            key = "ground-main",
            points = state.groundRoute,
            color = pickupRouteColor.toArgb(),
            width = if (state.activeOrder == null) 9f else 8f
        )
    }

    if (state.flightRoute.size >= 2 && state.activeOrder?.status != OrderStatus.RETURNING) {
        visiblePolylineKeys += "flight-glow"
        visiblePolylineKeys += "flight-main"
        val glowAlpha = if (state.activeOrder?.status == OrderStatus.IN_FLIGHT) 0.18f else 0.12f
        upsertPolyline(
            cache = renderCache.polylines,
            key = "flight-glow",
            points = state.flightRoute,
            color = flightRouteColor.copy(alpha = glowAlpha).toArgb(),
            width = if (state.activeOrder?.status == OrderStatus.IN_FLIGHT) 24f else 18f
        )
        upsertPolyline(
            cache = renderCache.polylines,
            key = "flight-main",
            points = state.flightRoute,
            color = flightRouteColor.toArgb(),
            width = if (state.activeOrder?.status == OrderStatus.IN_FLIGHT) 14f else 10f
        )
    }

    if (state.activeVehicleTrack.size >= 2 && state.activeOrder?.status == OrderStatus.RETURNING) {
        visiblePolylineKeys += "return-glow"
        visiblePolylineKeys += "return-main"
        upsertPolyline(
            cache = renderCache.polylines,
            key = "return-glow",
            points = state.activeVehicleTrack,
            color = returnRouteColor.copy(alpha = 0.22f).toArgb(),
            width = 18f
        )
        upsertPolyline(
            cache = renderCache.polylines,
            key = "return-main",
            points = state.activeVehicleTrack,
            color = returnRouteColor.toArgb(),
            width = 10f
        )
    }

    renderCache.polylines.keys
        .filterNot { it in visiblePolylineKeys }
        .toList()
        .forEach { key ->
            renderCache.polylines.remove(key)?.remove()
        }
}

private fun AMap.upsertMarker(
    cache: MutableMap<String, Marker>,
    key: String,
    options: MarkerOptions,
    heading: Float? = null,
    flat: Boolean = false
) {
    val marker = cache[key]
    if (marker == null) {
        cache[key] = addMarker(options).also { it.`object` = key }
        return
    }
    marker.`object` = key
    marker.position = options.position
    marker.title = options.title
    marker.snippet = options.snippet
    marker.setIcon(options.icons.first())
    marker.zIndex = options.zIndex
    marker.setAnchor(options.anchorU, options.anchorV)
    marker.isFlat = flat
    heading?.let { marker.rotateAngle = toAMapRotateAngle(it) } ?: run { marker.rotateAngle = 0f }
}

private fun AMap.upsertPolyline(
    cache: MutableMap<String, Polyline>,
    key: String,
    points: List<GeoPoint>,
    color: Int,
    width: Float
) {
    val latLngPoints = points.map { it.toLatLng() }
    val polyline = cache[key]
    if (polyline == null) {
        cache[key] = addPolyline(
            PolylineOptions()
                .addAll(latLngPoints)
                .color(color)
                .width(width)
        )
        return
    }
    polyline.points = latLngPoints
    polyline.color = color
    polyline.width = width
}

private class MapRenderCache {
    val markers = mutableMapOf<String, Marker>()
    val polylines = mutableMapOf<String, Polyline>()
}

private fun groundedVehicleAnchor(isFlying: Boolean): Pair<Float, Float> {
    return if (isFlying) {
        0.5f to 0.5f
    } else {
        0.5f to 0.5f
    }
}

private fun resolveVehicleAnchor(
    isFlying: Boolean
): Pair<Float, Float> {
    if (!isFlying) {
        return groundedVehicleAnchor(false)
    }
    return 0.5f to 0.5f
}

private fun resolveActiveVehicleHeading(state: MainUiState, currentPoint: GeoPoint): Float? {
    val track = state.activeVehicleTrack
    if (track.size >= 2) {
        val previous = track[track.lastIndex - 1]
        val latest = track.last()
        if (previous.latitude != latest.latitude || previous.longitude != latest.longitude) {
            return bearingDegrees(previous, latest)
        }
    }

    val target = when (state.activeOrder?.status) {
        OrderStatus.RESERVED, OrderStatus.BOARDING -> state.pickupVertiport?.location
        OrderStatus.IN_FLIGHT -> state.selectedDestination?.location
        OrderStatus.RETURNING -> state.activeVehicleTrack.lastOrNull()
        else -> null
    }
    return target?.takeIf { it.latitude != currentPoint.latitude || it.longitude != currentPoint.longitude }
        ?.let { bearingDegrees(currentPoint, it) }
}

private fun resolveVehicleDisplayPoint(
    rawPoint: GeoPoint,
    isFlying: Boolean,
    isActiveVehicle: Boolean,
    destination: Vertiport?,
    allVertiports: List<Vertiport>,
    orderStatus: OrderStatus?,
    pickupVertiport: Vertiport?
): GeoPoint {
    if (isFlying) {
        return rawPoint
    }
    val preferredGroundPoint = when (orderStatus) {
        OrderStatus.RESERVED, OrderStatus.BOARDING -> pickupVertiport?.location
        OrderStatus.DONE, OrderStatus.CANCELED, OrderStatus.RETURNING -> destination?.location
        else -> if (isActiveVehicle) destination?.location else null
    }
    return snappedGroundedVehiclePoint(
        rawPoint = rawPoint,
        preferredPoint = preferredGroundPoint,
        allVertiports = allVertiports
    )
}

private fun snappedGroundedVehiclePoint(
    rawPoint: GeoPoint,
    preferredPoint: GeoPoint?,
    allVertiports: List<Vertiport>
): GeoPoint {
    preferredPoint?.takeIf { rawPoint.distanceTo(it) <= 0.35 }?.let { return it }
    return allVertiports
        .minByOrNull { it.location.distanceTo(rawPoint) }
        ?.takeIf { nearest -> rawPoint.distanceTo(nearest.location) <= 0.28 }
        ?.location
        ?: rawPoint
}

private fun offsetGroundedVehiclePoint(center: GeoPoint): GeoPoint {
    return GeoPoint(
        latitude = center.latitude,
        longitude = center.longitude
    )
}

private fun approachVehiclePoint(center: GeoPoint): GeoPoint {
    return GeoPoint(
        latitude = center.latitude + 0.00042,
        longitude = center.longitude
    )
}

private fun interpolateGeoPoint(from: GeoPoint, to: GeoPoint, ratio: Double): GeoPoint {
    val t = ratio.coerceIn(0.0, 1.0)
    return GeoPoint(
        latitude = from.latitude + (to.latitude - from.latitude) * t,
        longitude = from.longitude + (to.longitude - from.longitude) * t
    )
}

private fun interpolateHeading(from: Float, to: Float, ratio: Float): Float {
    val delta = ((to - from + 540f) % 360f) - 180f
    return (from + delta * ratio + 360f) % 360f
}

private fun bearingDegrees(from: GeoPoint, to: GeoPoint): Float {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLon = Math.toRadians(to.longitude - from.longitude)

    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val heading = Math.toDegrees(atan2(y, x))
    return ((heading + 360.0) % 360.0).toFloat()
}

private fun toAMapRotateAngle(headingDegrees: Float): Float {
    return ((360f - headingDegrees) + 360f) % 360f
}

private fun Context.bitmapDescriptorFromVector(
    @DrawableRes drawableId: Int,
    sizeDp: Float
): BitmapDescriptor {
    val drawable = checkNotNull(ContextCompat.getDrawable(this, drawableId)) {
        "Drawable not found: $drawableId"
    }
    val sizePx = (sizeDp * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(canvas)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun formatOrderTimestamp(timestamp: Long): String {
    return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
}

private fun collectOverviewPoints(state: MainUiState): List<LatLng> {
    val points = mutableListOf<LatLng>()
    val status = state.activeOrder?.status
    state.currentLocation?.let { points += it.toLatLng() }
    state.activeVehicleLocation?.let { points += it.toLatLng() }
    state.activeVehicleTrack.forEach { points += it.toLatLng() }

    when (status) {
        OrderStatus.RETURNING -> {
            state.pickupVertiport?.let { points += it.location.toLatLng() }
            state.activeVehicleTrack.forEach { points += it.toLatLng() }
        }
        OrderStatus.IN_FLIGHT -> {
            state.selectedDestination?.let { points += it.location.toLatLng() }
            state.flightRoute.forEach { points += it.toLatLng() }
        }
        OrderStatus.RESERVED, OrderStatus.BOARDING, OrderStatus.CREATED, OrderStatus.ASSIGNED -> {
            state.pickupVertiport?.let { points += it.location.toLatLng() }
            state.groundRoute.forEach { points += it.toLatLng() }
        }
        else -> {
            state.pickupVertiport?.let { points += it.location.toLatLng() }
            state.selectedDestination?.let { points += it.location.toLatLng() }
            state.flightRoute.forEach { points += it.toLatLng() }
            state.vertiports.forEach { points += it.location.toLatLng() }
            state.nearbyVehicles.forEach { points += it.location.toLatLng() }
            state.groundRoute.forEach { points += it.toLatLng() }
        }
    }

    return points.distinctBy {
        ((it.latitude * 1_000_000).toInt() to (it.longitude * 1_000_000).toInt())
    }
}

private fun applyOverviewCamera(
    map: AMap,
    rawPoints: List<LatLng>,
    animate: Boolean = false,
    viewportWidth: Int = 0,
    viewportHeight: Int = 0,
    padding: Int = 120
): Boolean {
    val points = rawPoints.distinctBy {
        ((it.latitude * 1_000_000).toInt() to (it.longitude * 1_000_000).toInt())
    }
    if (points.size < 2) return false

    val boundsBuilder = LatLngBounds.builder()
    points.forEach(boundsBuilder::include)
    val bounds = boundsBuilder.build()
    val cameraUpdate = if (viewportWidth > 0 && viewportHeight > 0) {
        CameraUpdateFactory.newLatLngBounds(bounds, viewportWidth, viewportHeight, padding)
    } else {
        CameraUpdateFactory.newLatLngBounds(bounds, padding)
    }

    return runCatching {
        if (animate) map.animateCamera(cameraUpdate) else map.moveCamera(cameraUpdate)
    }.isSuccess
}

@Composable
private fun DestinationSection(
    state: MainUiState,
    onDestinationSelected: (String) -> Unit,
    embedded: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme
    val content: @Composable ColumnScope.() -> Unit = {
        Text(
            text = state.selectedDestination?.let {
                stringResource(R.string.destination_current_format, it.name)
            } ?: stringResource(R.string.destination_current_empty),
            color = colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.vertiports, key = { it.id }) { port ->
                FilterChip(
                    selected = state.selectedDestination?.id == port.id,
                    onClick = { onDestinationSelected(port.id) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colorScheme.secondaryContainer,
                        selectedLabelColor = colorScheme.onSecondaryContainer,
                        containerColor = colorScheme.surface,
                        labelColor = colorScheme.onSurface
                    ),
                    label = {
                        Text(
                            text = port.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }

    if (embedded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    } else {
        CyberPanel(
            title = stringResource(R.string.destination_title),
            subtitle = state.selectedDestination?.name ?: stringResource(R.string.destination_unselected),
            accent = colorScheme.secondary
        ) { content() }
    }
}

@Composable
private fun RouteSection(state: MainUiState) {
    val colorScheme = MaterialTheme.colorScheme

    CyberPanel(
        title = stringResource(R.string.route_title),
        subtitle = state.pickupVertiport?.name ?: stringResource(R.string.route_wait_gps),
        accent = colorScheme.secondary
    ) {
        val distance = state.groundDistanceKm
        val minutes = state.groundDurationMinutes

        if (state.pickupVertiport == null || distance == null || minutes == null) {
            Text(
                text = stringResource(R.string.route_empty),
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                text = stringResource(R.string.route_pickup_format, state.pickupVertiport.name),
                color = colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = stringResource(R.string.route_distance_format, distance),
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = stringResource(R.string.route_duration_format, minutes),
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PricingAndOrderSection(
    state: MainUiState,
    canPlaceOrder: Boolean,
    onPlaceOrder: () -> Unit,
    onConfirmBoarded: () -> Unit,
    onCancelOrder: () -> Unit,
    embedded: Boolean = false,
    emphasizeActionsOnly: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme
    val boardEnabled = state.activeOrder?.status == OrderStatus.BOARDING
    val cancelEnabled = state.activeOrder?.status in setOf(
        OrderStatus.CREATED,
        OrderStatus.ASSIGNED,
        OrderStatus.RESERVED,
        OrderStatus.BOARDING
    )

    val content: @Composable ColumnScope.() -> Unit = {
        if (!emphasizeActionsOnly) {
            val estimate = state.priceEstimate
            if (estimate != null) {
                Text(
                    text = stringResource(R.string.price_format, estimate.amountDisplay),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.distance_short_format, estimate.distanceKm),
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurfaceVariant
                )
                state.pickupVertiport?.let { pickup ->
                    Text(
                        text = stringResource(
                            R.string.flight_segment_format,
                            pickup.name,
                            state.selectedDestination?.name ?: stringResource(R.string.destination_unselected)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                state.estimatedArrivalMinutes?.let { eta ->
                    Text(
                        text = stringResource(R.string.destination_eta_format, eta),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = if (state.selectedDestination != null) {
                        stringResource(R.string.pricing_unavailable)
                    } else {
                        stringResource(R.string.pricing_empty)
                    },
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!emphasizeActionsOnly) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onPlaceOrder,
                    enabled = canPlaceOrder && !state.isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.primary,
                        contentColor = colorScheme.onPrimary
                    )
                ) {
                    Text(stringResource(R.string.place_order))
                }
            }

            if (state.activeOrder != null) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onConfirmBoarded,
                    enabled = boardEnabled,
                    border = BorderStroke(1.dp, colorScheme.secondary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.secondary)
                ) {
                    Text(stringResource(R.string.confirm_boarded))
                }
            }

            if (cancelEnabled) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCancelOrder,
                    border = BorderStroke(1.dp, colorScheme.error),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.error)
                ) {
                    Text(stringResource(R.string.cancel_order))
                }
            }
        }

        state.activeOrder?.let { order ->
            Text(
                text = stringResource(R.string.order_vehicle_format, order.id, order.vehicleId),
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            if (order.priceEstimate.cancellationFeeCents > 0) {
                Text(
                    text = stringResource(R.string.cancellation_fee_format, order.priceEstimate.cancellationFeeDisplay),
                    color = colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.activeVehicleLocation?.let { point ->
                Text(
                    text = stringResource(
                        R.string.vehicle_location_format,
                        point.latitude,
                        point.longitude
                    ),
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.estimatedArrivalMinutes?.let { eta ->
                Text(
                    text = stringResource(R.string.eta_format, eta),
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (embedded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    } else {
        CyberPanel(
            title = stringResource(R.string.pricing_title),
            subtitle = state.activeOrder?.status?.let { statusLabel(it) } ?: stringResource(R.string.pricing_ready),
            accent = colorScheme.primary
        ) { content() }
    }
}

@Composable
private fun VehicleSection(state: MainUiState) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    CyberPanel(
        title = stringResource(R.string.vehicle_title),
        subtitle = stringResource(R.string.vehicle_online_format, state.nearbyVehicles.size),
        accent = colorScheme.tertiary
    ) {
        if (state.nearbyVehicles.isEmpty()) {
            Text(
                text = stringResource(R.string.vehicle_empty),
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            state.nearbyVehicles.forEach { vehicle ->
                val statusColor = when (vehicle.status) {
                    VehicleStatus.IDLE -> colorScheme.primary
                    VehicleStatus.IN_FLIGHT -> colorScheme.tertiary
                    VehicleStatus.BOARDING -> colorScheme.secondary
                    else -> colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = vehicle.name,
                        color = colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(
                            R.string.vehicle_status_battery_format,
                            vehicleStatusLabel(context, vehicle.status),
                            vehicle.batteryPercent
                        ),
                        color = statusColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderHistorySection(state: MainUiState) {
    val colorScheme = MaterialTheme.colorScheme
    val recentOrders = state.orderHistory.take(3)

    CyberPanel(
        title = stringResource(R.string.history_title),
        subtitle = stringResource(R.string.history_count_format, recentOrders.size),
        accent = colorScheme.outline
    ) {
        if (recentOrders.isEmpty()) {
            Text(
                text = stringResource(R.string.history_empty),
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            recentOrders.forEach { order ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = order.id,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurface
                        )
                        Text(
                            text = if (order.priceEstimate.cancellationFeeCents > 0) {
                                stringResource(
                                    R.string.history_cancellation_item_format,
                                    statusLabel(order.status),
                                    order.priceEstimate.amountDisplay,
                                    order.priceEstimate.cancellationFeeDisplay
                                )
                            } else {
                                stringResource(
                                    R.string.history_item_format,
                                    statusLabel(order.status),
                                    order.priceEstimate.amountDisplay
                                )
                            },
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.history_detail_format,
                            order.pickupVertiport.name,
                            order.destination.name,
                            formatOrderTimestamp(order.createdAt)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun statusLabel(status: OrderStatus): String {
    return when (status) {
        OrderStatus.CREATED -> stringResource(R.string.status_created)
        OrderStatus.ASSIGNED -> stringResource(R.string.status_assigned)
        OrderStatus.RESERVED -> stringResource(R.string.status_reserved)
        OrderStatus.BOARDING -> stringResource(R.string.status_boarding)
        OrderStatus.IN_FLIGHT -> stringResource(R.string.status_in_flight)
        OrderStatus.RETURNING -> stringResource(R.string.status_returning)
        OrderStatus.DONE -> stringResource(R.string.status_done)
        OrderStatus.CANCELED -> stringResource(R.string.status_canceled)
        OrderStatus.FAILED -> stringResource(R.string.status_failed)
    }
}

private fun statusLabel(context: Context, status: OrderStatus): String {
    return when (status) {
        OrderStatus.CREATED -> context.getString(R.string.status_created)
        OrderStatus.ASSIGNED -> context.getString(R.string.status_assigned)
        OrderStatus.RESERVED -> context.getString(R.string.status_reserved)
        OrderStatus.BOARDING -> context.getString(R.string.status_boarding)
        OrderStatus.IN_FLIGHT -> context.getString(R.string.status_in_flight)
        OrderStatus.RETURNING -> context.getString(R.string.status_returning)
        OrderStatus.DONE -> context.getString(R.string.status_done)
        OrderStatus.CANCELED -> context.getString(R.string.status_canceled)
        OrderStatus.FAILED -> context.getString(R.string.status_failed)
    }
}

private fun vehicleStatusLabel(context: Context, status: VehicleStatus): String {
    return when (status) {
        VehicleStatus.IDLE -> context.getString(R.string.vehicle_idle)
        VehicleStatus.RESERVED -> context.getString(R.string.vehicle_reserved)
        VehicleStatus.BOARDING -> context.getString(R.string.vehicle_boarding)
        VehicleStatus.IN_FLIGHT -> context.getString(R.string.vehicle_in_flight)
        VehicleStatus.CHARGING -> context.getString(R.string.vehicle_charging)
        VehicleStatus.MAINTENANCE -> context.getString(R.string.vehicle_maintenance)
        VehicleStatus.OFFLINE -> context.getString(R.string.vehicle_offline)
    }
}

@Composable
private fun CyberPanel(
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(accent, CircleShape)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = colorScheme.outlineVariant, thickness = 1.dp)
            content()
        }
    }
}

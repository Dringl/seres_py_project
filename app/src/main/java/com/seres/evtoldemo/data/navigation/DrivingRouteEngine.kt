package com.seres.evtoldemo.data.navigation

import android.content.Context
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviListener
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapModelCross
import com.amap.api.navi.model.AMapNaviCameraInfo
import com.amap.api.navi.model.AMapNaviCross
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.AMapNaviRouteNotifyData
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo
import com.amap.api.navi.model.AMapServiceAreaInfo
import com.amap.api.navi.model.AimLessModeCongestionInfo
import com.amap.api.navi.model.AimLessModeStat
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng
import com.seres.evtoldemo.data.model.GeoPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class DrivingRouteEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun planDrivingRoute(
        origin: GeoPoint,
        destination: GeoPoint
    ): DrivingRouteResult? {
        return withContext(Dispatchers.Main.immediate) {
            val navi = runCatching { AMapNavi.getInstance(context.applicationContext) }.getOrNull()
                ?: return@withContext null

            withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : AMapNaviListener {
                        override fun onInitNaviFailure() {
                            finish(null)
                        }

                        override fun onInitNaviSuccess() = Unit

                        override fun onStartNavi(type: Int) = Unit

                        override fun onTrafficStatusUpdate() = Unit

                        override fun onLocationChange(location: AMapNaviLocation?) = Unit

                        override fun onGetNavigationText(type: Int, text: String?) = Unit

                        override fun onGetNavigationText(text: String?) = Unit

                        override fun onEndEmulatorNavi() = Unit

                        override fun onArriveDestination() = Unit

                        override fun onCalculateRouteFailure(errorCode: Int) {
                            finish(null)
                        }

                        override fun onReCalculateRouteForYaw() = Unit

                        override fun onReCalculateRouteForTrafficJam() = Unit

                        override fun onArrivedWayPoint(wayID: Int) = Unit

                        override fun onGpsOpenStatus(enabled: Boolean) = Unit

                        override fun onNaviInfoUpdate(naviInfo: NaviInfo?) = Unit

                        override fun updateCameraInfo(infos: Array<AMapNaviCameraInfo>?) = Unit

                        override fun updateIntervalCameraInfo(
                            info: AMapNaviCameraInfo?,
                            nextInfo: AMapNaviCameraInfo?,
                            distance: Int
                        ) = Unit

                        override fun onServiceAreaUpdate(serviceAreaInfos: Array<AMapServiceAreaInfo>?) = Unit

                        override fun showCross(cross: AMapNaviCross?) = Unit

                        override fun hideCross() = Unit

                        override fun showModeCross(modelCross: AMapModelCross?) = Unit

                        override fun hideModeCross() = Unit

                        override fun showLaneInfo(
                            laneInfos: Array<AMapLaneInfo>?,
                            laneBackgroundInfo: ByteArray?,
                            laneRecommendedInfo: ByteArray?
                        ) = Unit

                        override fun showLaneInfo(laneInfo: AMapLaneInfo?) = Unit

                        override fun hideLaneInfo() = Unit

                        override fun onCalculateRouteSuccess(routeIds: IntArray?) {
                            finish(extractRouteResult(navi, routeIds))
                        }

                        override fun notifyParallelRoad(type: Int) = Unit

                        override fun OnUpdateTrafficFacility(trafficFacilityInfos: Array<AMapNaviTrafficFacilityInfo>?) = Unit

                        override fun OnUpdateTrafficFacility(trafficFacilityInfo: AMapNaviTrafficFacilityInfo?) = Unit

                        override fun updateAimlessModeStatistics(stat: AimLessModeStat?) = Unit

                        override fun updateAimlessModeCongestionInfo(info: AimLessModeCongestionInfo?) = Unit

                        override fun onPlayRing(type: Int) = Unit

                        override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
                            finish(extractRouteResult(navi, result?.routeid))
                        }

                        override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
                            finish(null)
                        }

                        override fun onNaviRouteNotify(notifyData: AMapNaviRouteNotifyData?) = Unit

                        override fun onGpsSignalWeak(isWeak: Boolean) = Unit

                        private fun finish(result: DrivingRouteResult?) {
                            if (!continuation.isActive) {
                                return
                            }
                            navi.removeAMapNaviListener(this)
                            continuation.resume(result)
                        }
                    }

                    continuation.invokeOnCancellation {
                        navi.removeAMapNaviListener(listener)
                    }

                    navi.addAMapNaviListener(listener)

                    val startList = listOf(NaviLatLng(origin.latitude, origin.longitude))
                    val endList = listOf(NaviLatLng(destination.latitude, destination.longitude))
                    val strategy = runCatching {
                        navi.strategyConvert(
                            false,
                            false,
                            false,
                            false,
                            false
                        )
                    }.getOrDefault(0)

                    val started = navi.calculateDriveRoute(startList, endList, null, strategy)
                    if (!started && continuation.isActive) {
                        navi.removeAMapNaviListener(listener)
                        continuation.resume(null)
                    }
                }
            }
        }
    }

    private fun extractRouteResult(
        navi: AMapNavi,
        routeIds: IntArray?
    ): DrivingRouteResult? {
        val path = when {
            routeIds != null && routeIds.isNotEmpty() -> {
                val routeMap = navi.naviPaths
                routeMap?.get(routeIds.first()) ?: navi.naviPath
            }

            else -> navi.naviPath
        } ?: return null

        val coords = path.coordList
        if (coords == null || coords.size < 2) {
            return null
        }

        val points = coords.map { latLng ->
            GeoPoint(
                latitude = latLng.latitude,
                longitude = latLng.longitude
            )
        }

        val distanceKm = (path.allLength / 1000.0).takeIf { it > 0 }
        val durationMinutes = (path.allTime / 60).takeIf { it > 0 }

        return DrivingRouteResult(
            points = points,
            distanceKm = distanceKm,
            durationMinutes = durationMinutes
        )
    }
}

data class DrivingRouteResult(
    val points: List<GeoPoint>,
    val distanceKm: Double?,
    val durationMinutes: Int?
)

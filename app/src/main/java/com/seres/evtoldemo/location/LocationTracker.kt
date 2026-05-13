package com.seres.evtoldemo.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.seres.evtoldemo.data.model.GeoPoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class LocationTracker @Inject constructor(
    private val context: Context
) {

    suspend fun getCurrentLocation(): GeoPoint? {
        if (!hasLocationPermission()) {
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            val locationClient = AMapLocationClient(context.applicationContext)
            val option = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isNeedAddress = false
                isMockEnable = false
                isWifiScan = true
                httpTimeOut = 10_000
            }

            locationClient.setLocationOption(option)
            locationClient.setLocationListener { location ->
                if (!continuation.isActive) {
                    return@setLocationListener
                }

                val point = if (location != null && location.errorCode == 0) {
                    GeoPoint(location.latitude, location.longitude)
                } else {
                    null
                }

                continuation.resume(point)
                locationClient.stopLocation()
                locationClient.onDestroy()
            }

            continuation.invokeOnCancellation {
                locationClient.stopLocation()
                locationClient.onDestroy()
            }

            locationClient.startLocation()
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }
}

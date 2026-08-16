package io.github.eightbrows.gpslogger

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.eightbrows.gpslogger.calc.DopCalculator
import io.github.eightbrows.gpslogger.log.LogEvent
import io.github.eightbrows.gpslogger.state.GnssStateHolder

/**
 * 記録せず測位だけ行う（アプリ表示中のプレビュー用）。
 * 記録中はサービス側が担当するため、こちらは停止する。
 */
object PreviewLocator {

    private var locationManager: LocationManager? = null
    private var active = false

    private val locationListener = LocationListener { location ->
        GnssStateHolder.updateLocationPreviewOnly(location)
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val sats = ArrayList<LogEvent.Sat>(status.satelliteCount)
            for (i in 0 until status.satelliteCount) {
                sats.add(
                    LogEvent.Sat(
                        constellation = status.getConstellationType(i),
                        svid = status.getSvid(i),
                        cn0DbHz = status.getCn0DbHz(i),
                        basebandCn0DbHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            status.getBasebandCn0DbHz(i) else 0f,
                        elevationDeg = status.getElevationDegrees(i),
                        azimuthDeg = status.getAzimuthDegrees(i),
                        carrierFrequencyHz = if (status.hasCarrierFrequencyHz(i))
                            status.getCarrierFrequencyHz(i) else 0f,
                        usedInFix = status.usedInFix(i),
                        hasAlmanac = status.hasAlmanacData(i),
                        hasEphemeris = status.hasEphemerisData(i)
                    )
                )
            }
            GnssStateHolder.updateSatellites(sats, System.currentTimeMillis())
            GnssStateHolder.updateDop(
                DopCalculator.calculate(sats)
            )
        }
    }

    @SuppressLint("MissingPermission")  // 呼び出し側で許可を確認
    fun start(context: Context) {
        if (active) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        locationManager = lm
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,  // プレビューは常に1秒
                0f,
                locationListener,
                Looper.getMainLooper()
            )
            lm.registerGnssStatusCallback(gnssStatusCallback, Handler(Looper.getMainLooper()))
            active = true
            Log.d(TAG, "preview started")
        } catch (e: SecurityException) {
            Log.e(TAG, "permission missing", e)
        }
    }

    fun stop() {
        if (!active) return
        locationManager?.removeUpdates(locationListener)
        locationManager?.unregisterGnssStatusCallback(gnssStatusCallback)
        active = false
        Log.d(TAG, "preview stopped")
    }

    private const val TAG = "PreviewLocator"
}
package io.github.eightbrows.gpslogger.log

import android.location.GnssStatus
import android.location.Location
import io.github.eightbrows.gpslogger.calc.Dop

sealed interface LogEvent {
    data class Fix(val location: Location, val dop: Dop? = null) : LogEvent

    data class Sats(
        val epochId: Long,
        val epochMs: Long,
        val elapsedRealtimeNs: Long,
        val satellites: List<Sat>
    ) : LogEvent

    data class Sat(
        val constellation: Int,
        val svid: Int,
        val cn0DbHz: Float,
        val basebandCn0DbHz: Float,
        val elevationDeg: Float,
        val azimuthDeg: Float,
        val carrierFrequencyHz: Float,
        val usedInFix: Boolean,
        val hasAlmanac: Boolean,
        val hasEphemeris: Boolean
    )
}
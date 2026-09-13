package io.github.eightbrows.gpslogger.log

import android.location.Location
import io.github.eightbrows.gpslogger.calc.Dop

sealed interface LogEvent {
    data class Fix(
        val location: Location,
        val dop: Dop? = null,
        /** 一時停止からの再開直後の点か */
        val gapBefore: Boolean = false,
        /** 記録時の設定間隔（秒）。0は未設定 */
        val intervalSec: Int = 0,
        /** 気圧センサーの生値（hPa）。未取得は null */
        val pressureHpa: Float? = null
    ) : LogEvent

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

/** GnssStatus.CONSTELLATION_IRNSS は API 29+ のため、値を自前で保持 */
const val CONSTELLATION_IRNSS = 7
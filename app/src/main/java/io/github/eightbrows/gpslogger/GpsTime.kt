package io.github.eightbrows.gpslogger.calc

/** GPS週番号と週内秒 */
data class GpsWeekTime(
    val week: Int,      // GPS週番号（ロールオーバーなし通算）
    val tow: Double     // 週内秒（0〜604800）
)

object GpsTime {

    /** GPSエポック 1980-01-06 00:00:00 UTC のUnixミリ秒 */
    private const val GPS_EPOCH_MS = 315_964_800_000L
    private const val SECONDS_PER_WEEK = 604_800.0

    /**
     * UTC(Unixミリ秒)からGPS週番号・週内秒を算出する。
     * leapSeconds: GPS時刻とUTCの差（2026年時点で18秒）
     */
    fun fromUtcMillis(utcMs: Long, leapSeconds: Int): GpsWeekTime {
        // GPS時刻 = UTC + うるう秒
        val gpsMs = utcMs - GPS_EPOCH_MS + leapSeconds * 1000L
        val totalSec = gpsMs / 1000.0
        val week = (totalSec / SECONDS_PER_WEEK).toInt()
        val tow = totalSec - week * SECONDS_PER_WEEK
        return GpsWeekTime(week, tow)
    }
}
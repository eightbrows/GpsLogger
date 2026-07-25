package io.github.eightbrows.gpslogger.settings

import kotlin.math.abs
import kotlin.math.floor

object CoordFormatter {

    /** 緯度を表示用文字列に */
    fun latitude(value: Double, format: CoordFormat): String = when (format) {
        CoordFormat.DECIMAL -> "%.7f".format(value)
        CoordFormat.DMS -> toDms(value, "N", "S")
    }

    /** 経度を表示用文字列に */
    fun longitude(value: Double, format: CoordFormat): String = when (format) {
        CoordFormat.DECIMAL -> "%.7f".format(value)
        CoordFormat.DMS -> toDms(value, "E", "W")
    }

    /** 35.1234567 → 35°07'24.44"N */
    private fun toDms(value: Double, positive: String, negative: String): String {
        val hemisphere = if (value >= 0) positive else negative
        val a = abs(value)
        val deg = floor(a).toInt()
        val minFull = (a - deg) * 60.0
        val min = floor(minFull).toInt()
        val sec = (minFull - min) * 60.0
        return "%d°%02d'%05.2f\"%s".format(deg, min, sec, hemisphere)
    }
}
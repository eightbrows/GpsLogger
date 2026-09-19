package io.github.eightbrows.gpslogger.settings

import kotlin.math.abs
import kotlin.math.floor

object CoordFormatter {

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

    /** 度分秒表記のみ（数値ページの併記用） */
    fun latitudeDms(value: Double): String = toDms(value, "N", "S")
    fun longitudeDms(value: Double): String = toDms(value, "E", "W")

    /** 度と度分秒を1行で併記。設定に応じて主従を入れ替える */
    fun latitudeBoth(value: Double, format: CoordFormat): String = when (format) {
        CoordFormat.DECIMAL -> "%.7f (%s)".format(value, toDms(value, "N", "S"))
        CoordFormat.DMS -> "%s (%.7f)".format(toDms(value, "N", "S"), value)
    }

    fun longitudeBoth(value: Double, format: CoordFormat): String = when (format) {
        CoordFormat.DECIMAL -> "%.7f (%s)".format(value, toDms(value, "E", "W"))
        CoordFormat.DMS -> "%s (%.7f)".format(toDms(value, "E", "W"), value)
    }
}
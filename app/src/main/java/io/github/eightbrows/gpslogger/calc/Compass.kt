package io.github.eightbrows.gpslogger.calc

import io.github.eightbrows.gpslogger.settings.CompassNorth
import java.util.Locale
import kotlin.math.roundToInt

/**
 * コンパス（端末の背面が向いている方位）の最新値。
 * GPS の進行方向（bearing）とは別物。
 */
data class CompassReading(
    /** 磁北基準の方位角（0〜360、北=0、時計回り）。端末が立っていない・信頼できないときは null */
    val azimuthDeg: Float?,
    /** 推定誤差（度）。センサーが出さなければ null */
    val accuracyDeg: Float?,
    /** 取得時刻（SystemClock.elapsedRealtimeNanos） */
    val elapsedRealtimeNs: Long
)

/** コンパスの値の計算と書式。Android に依存しない（単体テストできる）部分をまとめる */
object Compass {

    /** これより古い値は「無し」として扱う（3 秒） */
    const val MAX_AGE_NS = 3_000_000_000L

    /** 端末の上端と鉛直上向きのなす角がこれを超えたら「立っていない」とみなす（度） */
    const val MAX_TILT_DEG = 45.0

    /** 角度を 0 以上 360 未満に収める（負の値・360 以上にも対応） */
    fun normalizeDeg(deg: Float): Float {
        val r = deg % 360f
        // -0.0 や、丸め誤差で 360.0 ちょうどになる場合を 0 に寄せる
        val n = if (r < 0f) r + 360f else r
        return if (n >= 360f || n == 0f) 0f else n
    }

    /** 真北基準の方位角 = 磁北基準 + 偏角（東偏がプラス）。0 以上 360 未満 */
    fun trueAzimuthDeg(magneticDeg: Float, declinationDeg: Float): Float =
        normalizeDeg(magneticDeg + declinationDeg)

    /**
     * 記録・表示に使える値だけを返す。
     * 方位角が無い、または取得から [MAX_AGE_NS] より古ければ null。
     */
    fun usable(reading: CompassReading?, nowElapsedNs: Long): CompassReading? {
        if (reading == null || reading.azimuthDeg == null) return null
        return if (nowElapsedNs - reading.elapsedRealtimeNs > MAX_AGE_NS) null else reading
    }

    /**
     * 数値ページの表示。
     *  - 真北: "T123 deg (+/- 12)"（磁北 + 偏角）
     *  - 磁北: "M131 deg (+/- 12)"
     * 精度が無ければ括弧を省く。方位角が無い、または真北で偏角が無いときは [NONE]。
     */
    fun format(
        magneticDeg: Float?,
        accuracyDeg: Float?,
        declinationDeg: Float?,
        north: CompassNorth
    ): String {
        if (magneticDeg == null) return NONE
        val (prefix, deg) = when (north) {
            CompassNorth.MAGNETIC_NORTH -> "M" to normalizeDeg(magneticDeg)
            CompassNorth.TRUE_NORTH ->
                "T" to trueAzimuthDeg(magneticDeg, declinationDeg ?: return NONE)
        }
        // 359.6 は 360 ではなく 0 と表示する
        val whole = deg.roundToInt().mod(360)
        val acc = accuracyDeg?.let { " (+/- %d)".format(Locale.US, it.roundToInt()) }.orEmpty()
        return "%s%d deg%s".format(Locale.US, prefix, whole, acc)
    }

    /** track.csv 用の小数 1 桁。null は空欄 */
    fun csv(value: Float?): String =
        if (value == null) "" else "%.1f".format(Locale.US, value)

    /** track.csv 用の方位角。小数 1 桁に丸めた結果が 360.0 にならないようにする */
    fun csvAzimuth(magneticDeg: Float?): String {
        if (magneticDeg == null) return ""
        val text = csv(normalizeDeg(magneticDeg))
        return if (text == "360.0") "0.0" else text
    }

    const val NONE = "—"
}

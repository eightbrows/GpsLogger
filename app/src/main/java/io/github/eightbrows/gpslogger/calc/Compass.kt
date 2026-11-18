package io.github.eightbrows.gpslogger.calc

import io.github.eightbrows.gpslogger.settings.CompassNorth
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    val elapsedRealtimeNs: Long,
    /** 測った磁場の強さ（µT）。磁気センサーが無い・値が古いときは null。端末の傾きには関係なく入る */
    val magStrengthUt: Float? = null,
    /** 測った伏角（度、下向きがプラス）。磁場の強さと組で入る */
    val magInclinationDeg: Float? = null,
    /**
     * 磁場の強さ・伏角を測った時刻（SystemClock.elapsedRealtimeNanos）。
     * 時刻の合う組が取れなかった回は前の値を持ち越すので、[elapsedRealtimeNs] より古いことがある
     */
    val magElapsedRealtimeNs: Long = elapsedRealtimeNs,
    /** 磁気センサーが報告している状態（0〜3）。センサーが無い・未取得は null。記録だけに使う */
    val magSensorStatus: Int? = null
)

/** 実際に測った磁場（強さ µT、伏角 度・下向きがプラス） */
data class MagMeasurement(val strengthUt: Float, val inclinationDeg: Float)

/** その地点・時刻の地球の磁場のモデル値（偏角 度・東偏がプラス、強さ µT、伏角 度・下向きがプラス） */
data class MagModel(val declinationDeg: Float, val strengthUt: Float, val inclinationDeg: Float)

/** 測った磁場とモデル値のずれ。コンパスの信頼性の指標 */
data class MagIndex(
    /** 測った強さ ÷ モデルの強さ */
    val ratio: Float,
    /** 測った伏角 − モデルの伏角（度） */
    val inclinationDiffDeg: Float
)

/**
 * track.csv のコンパス関連 6 列に書く値。無い値は null（CSV では空欄）。
 * コンパスがオフの記録では [EMPTY] を使う
 */
data class CompassRecord(
    /** 端末の背面の方位（磁北基準 0〜360）。無効・古い値・センサー無しは null */
    val azimuthDeg: Float? = null,
    /** 推定誤差（度）。取れなければ null */
    val accuracyDeg: Float? = null,
    /** この測位点の偏角（度、東偏がプラス）。方位角が無ければ null */
    val declinationDeg: Float? = null,
    /** 測った磁場の強さ ÷ モデル値。値が無ければ null */
    val fieldRatio: Float? = null,
    /** 測った伏角 − モデル値（度）。値が無ければ null */
    val inclinationDiffDeg: Float? = null,
    /** 磁気センサーが報告している状態（0〜3）。無ければ null。記録だけに使う */
    val sensorStatus: Int? = null
) {
    companion object {
        val EMPTY = CompassRecord()
    }
}

/**
 * センサーから届いた最新の値を持ち、記録用と画面用に渡し分ける。
 *  - 記録用 [latest]: 間引く前の最新の方位角と、時刻の合う組で測った最新の磁場の指標
 *  - 画面用: [onRotation] の戻り値。[onRotation] に渡した間隔まで間引く
 */
class CompassSamples {
    private var azimuthDeg: Float? = null
    private var accuracyDeg: Float? = null

    /** 最新の方位の取得時刻。未取得なら 0 */
    private var rotationNs = 0L
    private var lastPublishedNs = 0L

    private var magnetic: MagMeasurement? = null
    private var magneticNs = 0L
    private var magneticStatus: Int? = null

    /**
     * 回転ベクトルから求めた値を控える。前回の公開から [minIntervalNs] 以上たっていれば
     * 画面に出す値を返し、まだなら null を返す
     */
    fun onRotation(
        azimuthDeg: Float?,
        accuracyDeg: Float?,
        nowNs: Long,
        minIntervalNs: Long
    ): CompassReading? {
        this.azimuthDeg = azimuthDeg
        this.accuracyDeg = accuracyDeg
        rotationNs = nowNs
        if (nowNs - lastPublishedNs < minIntervalNs) return null
        lastPublishedNs = nowNs
        return latest()
    }

    /** 時刻の合う組で測れたときだけ、最新の測定値として控える */
    fun onMagnetic(measurement: MagMeasurement?, nowNs: Long) {
        if (measurement == null) return
        magnetic = measurement
        magneticNs = nowNs
    }

    fun onMagneticStatus(status: Int) {
        magneticStatus = status
    }

    /** センサーが「信頼できない」と知らせてきたら、次の値を待たずに方位角を無効にする */
    fun clearAzimuth() {
        azimuthDeg = null
    }

    /**
     * 間引く前の最新の値。方位が一度も届いていなければ null。
     * 時刻の合う組が取れていない間は、磁場の指標を測った時刻ごと持ち越す
     * （3 秒より古くなれば、使う側で「無し」になる）
     */
    fun latest(): CompassReading? {
        if (rotationNs == 0L) return null
        return CompassReading(
            azimuthDeg = azimuthDeg,
            accuracyDeg = accuracyDeg,
            elapsedRealtimeNs = rotationNs,
            magStrengthUt = magnetic?.strengthUt,
            magInclinationDeg = magnetic?.inclinationDeg,
            magElapsedRealtimeNs = magneticNs,
            magSensorStatus = magneticStatus
        )
    }

    fun reset() {
        azimuthDeg = null
        accuracyDeg = null
        rotationNs = 0L
        lastPublishedNs = 0L
        magnetic = null
        magneticNs = 0L
        magneticStatus = null
    }
}

/**
 * 磁場と鉛直上向き（回転行列の 3 行目）を、センサーのイベント時刻が近いものどうしで組にする。
 * 2 つのセンサーは別々のタイミングで届くので、時刻がずれた組で伏角を計算しないためのもの。
 *
 * どちらかが届くたびに、相手の直近の値のうちイベント時刻がいちばん近いものを探し、
 * 差が [maxDiffNs] 以内なら測定値を返す。届く順番はイベント時刻の順とは限らない
 * （片方だけ遅れて届く端末がある）ので、相手は最新の 1 件ではなく直近 [capacity] 件から探す。
 */
class MagPairer(
    private val maxDiffNs: Long = Compass.MAG_PAIR_MAX_DIFF_NS,
    capacity: Int = 16
) {
    /** 直近の値を、届いた順に上書きしながら持つ */
    private class Recent(capacity: Int) {
        val ts = LongArray(capacity)
        val x = FloatArray(capacity)
        val y = FloatArray(capacity)
        val z = FloatArray(capacity)
        var count = 0
        private var next = 0

        fun add(timestampNs: Long, vx: Float, vy: Float, vz: Float) {
            ts[next] = timestampNs; x[next] = vx; y[next] = vy; z[next] = vz
            next = (next + 1) % ts.size
            if (count < ts.size) count++
        }

        /** イベント時刻がいちばん近い値の位置。空なら -1 */
        fun nearest(timestampNs: Long): Int {
            var best = -1
            var bestDiff = Long.MAX_VALUE
            for (i in 0 until count) {
                val diff = abs(ts[i] - timestampNs)
                if (diff < bestDiff) { bestDiff = diff; best = i }
            }
            return best
        }

        fun clear() { count = 0; next = 0 }
    }

    private val fields = Recent(capacity)
    private val ups = Recent(capacity)

    /** 磁場（端末座標、µT）が届いた。時刻の合う上向きがあれば測定値、無ければ null */
    fun onField(timestampNs: Long, x: Float, y: Float, z: Float): MagMeasurement? {
        fields.add(timestampNs, x, y, z)
        val i = ups.nearest(timestampNs)
        if (i < 0 || abs(ups.ts[i] - timestampNs) > maxDiffNs) return null
        return Compass.measureMagnetic(x, y, z, ups.x[i], ups.y[i], ups.z[i])
    }

    /** 鉛直上向き（端末座標）が届いた。時刻の合う磁場があれば測定値、無ければ null */
    fun onUp(timestampNs: Long, x: Float, y: Float, z: Float): MagMeasurement? {
        ups.add(timestampNs, x, y, z)
        val i = fields.nearest(timestampNs)
        if (i < 0 || abs(fields.ts[i] - timestampNs) > maxDiffNs) return null
        return Compass.measureMagnetic(fields.x[i], fields.y[i], fields.z[i], x, y, z)
    }

    fun reset() {
        fields.clear()
        ups.clear()
    }
}

/** コンパスの値の計算と書式。Android に依存しない（単体テストできる）部分をまとめる */
object Compass {

    /** これより古い値は「無し」として扱う（3 秒） */
    const val MAX_AGE_NS = 3_000_000_000L

    /** 端末の上端と鉛直上向きのなす角がこれを超えたら「立っていない」とみなす（度） */
    const val MAX_TILT_DEG = 45.0

    /** 磁場と回転行列のイベント時刻の差がこれ以内の組だけで、強さ・伏角を計算する（100 ms） */
    const val MAG_PAIR_MAX_DIFF_NS = 100_000_000L

    // ---- 「磁気の乱れ」の判定のしきい値（走行データの解析から決めた値） ----

    /** 測った強さ ÷ モデルの強さ がこの範囲（両端を含む）なら正常 */
    const val MAG_RATIO_MIN = 0.8f
    const val MAG_RATIO_MAX = 1.3f

    /** 伏角のずれの絶対値がこれ以下（度）なら正常 */
    const val MAG_INCLINATION_DIFF_MAX_DEG = 15f

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

    // ---- 磁場の強さ・伏角（信頼性の指標） ----

    /**
     * 磁場 B（端末座標、µT）と鉛直上向き u（端末座標）から、強さと伏角を求める。
     * 伏角は下向きがプラス: atan2(-(B·u), sqrt(|B|² − (B·u)²))。
     * 磁場が 0・値が数値でないときは null。
     */
    fun measureMagnetic(
        bx: Float, by: Float, bz: Float,
        ux: Float, uy: Float, uz: Float
    ): MagMeasurement? {
        val b2 = bx.toDouble() * bx + by.toDouble() * by + bz.toDouble() * bz
        val uLen = sqrt(ux.toDouble() * ux + uy.toDouble() * uy + uz.toDouble() * uz)
        if (!(b2 > 0.0) || !(uLen > 0.0) || !b2.isFinite() || !uLen.isFinite()) return null
        // u は回転行列の行なので長さ 1 のはずだが、誤差に備えて正規化する
        val vertical = (bx * ux + by * uy + bz * uz) / uLen
        val horizontal = sqrt((b2 - vertical * vertical).coerceAtLeast(0.0))
        val inclination = Math.toDegrees(atan2(-vertical, horizontal))
        return MagMeasurement(sqrt(b2).toFloat(), inclination.toFloat())
    }

    /**
     * 最新値から磁場の測定値を取り出す。端末の傾き（方位角の有無）には関係しない。
     * 磁場の値が無い、または測ってから [MAX_AGE_NS] より古ければ null。
     */
    fun magMeasurement(reading: CompassReading?, nowElapsedNs: Long): MagMeasurement? {
        val strength = reading?.magStrengthUt ?: return null
        val inclination = reading.magInclinationDeg ?: return null
        if (nowElapsedNs - reading.magElapsedRealtimeNs > MAX_AGE_NS) return null
        return MagMeasurement(strength, inclination)
    }

    /**
     * 最新値から磁気センサーの状態（0〜3）を取り出す。
     * 状態が無い・0〜3 以外・最新値が [MAX_AGE_NS] より古ければ null。
     */
    fun magSensorStatus(reading: CompassReading?, nowElapsedNs: Long): Int? {
        val status = reading?.magSensorStatus ?: return null
        if (status !in 0..3) return null
        return if (nowElapsedNs - reading.elapsedRealtimeNs > MAX_AGE_NS) null else status
    }

    /** 測定値とモデル値のずれ。どちらかが無い・モデルの強さが 0 以下なら null */
    fun magIndex(measured: MagMeasurement?, model: MagModel?): MagIndex? {
        if (measured == null || model == null || !(model.strengthUt > 0f)) return null
        return MagIndex(
            ratio = measured.strengthUt / model.strengthUt,
            inclinationDiffDeg = measured.inclinationDeg - model.inclinationDeg
        )
    }

    /**
     * 測位点に記録する値を決める。コンパスがオフの記録は [enabled] = false で、すべて空欄。
     * [reading] は間引く前の最新の値（[CompassSamples.latest]）。3 秒より古い値は使わない。
     * [model] は方位角か磁場の測定値があるときだけ呼ぶ
     */
    fun recordValues(
        enabled: Boolean,
        reading: CompassReading?,
        nowElapsedNs: Long,
        model: () -> MagModel
    ): CompassRecord {
        if (!enabled) return CompassRecord.EMPTY
        val compass = usable(reading, nowElapsedNs)
        // 磁場の強さ・伏角は、端末を寝かせていて方位角が無いときも使える
        val magnetic = magMeasurement(reading, nowElapsedNs)
        // モデル値はどちらかがあるときだけ求める（偏角・強さ・伏角を 1 回で）
        val magModel = if (compass != null || magnetic != null) model() else null
        val index = magIndex(magnetic, magModel)
        return CompassRecord(
            azimuthDeg = compass?.azimuthDeg,
            accuracyDeg = compass?.accuracyDeg,
            // 偏角は方位角があるときだけ書く（磁北の生値と組にして保存する）
            declinationDeg = compass?.let { magModel?.declinationDeg },
            fieldRatio = index?.ratio,
            inclinationDiffDeg = index?.inclinationDiffDeg,
            sensorStatus = magSensorStatus(reading, nowElapsedNs)
        )
    }

    /**
     * 「磁気の乱れ」か。正常は [MAG_RATIO_MIN] ≤ ratio ≤ [MAG_RATIO_MAX] かつ
     * |inclDiff| ≤ [MAG_INCLINATION_DIFF_MAX_DEG]。指標の値が無いときは false（注記しない）。
     */
    fun isDisturbed(ratio: Float?, inclinationDiffDeg: Float?): Boolean {
        if (ratio == null || inclinationDiffDeg == null) return false
        val normal = ratio in MAG_RATIO_MIN..MAG_RATIO_MAX &&
            abs(inclinationDiffDeg) <= MAG_INCLINATION_DIFF_MAX_DEG
        return !normal
    }

    /**
     * 数値ページの表示。
     *  - 真北: "T123 deg (+/- 12)"（磁北 + 偏角）
     *  - 磁北: "M131 deg (+/- 12)"
     * 精度が無ければ括弧を省く。方位角が無い、または真北で偏角が無いときは [NONE]。
     * [note]（「磁気の乱れ」など）は、方位角を表示できるときだけ末尾に付ける。
     */
    fun format(
        magneticDeg: Float?,
        accuracyDeg: Float?,
        declinationDeg: Float?,
        north: CompassNorth,
        note: String? = null
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
        val tail = if (note.isNullOrEmpty()) "" else "  $note"
        return "%s%d deg%s".format(Locale.US, prefix, whole, acc) + tail
    }

    /** track.csv 用の小数 1 桁。null は空欄 */
    fun csv(value: Float?): String =
        if (value == null) "" else "%.1f".format(Locale.US, value)

    /** track.csv 用の小数 2 桁（磁場の強さの比）。null は空欄 */
    fun csvRatio(value: Float?): String =
        if (value == null) "" else "%.2f".format(Locale.US, value)

    /** track.csv 用の磁気センサーの状態（整数 0〜3）。null は空欄 */
    fun csvStatus(status: Int?): String = status?.toString() ?: ""

    /** track.csv 用の方位角。小数 1 桁に丸めた結果が 360.0 にならないようにする */
    fun csvAzimuth(magneticDeg: Float?): String {
        if (magneticDeg == null) return ""
        val text = csv(normalizeDeg(magneticDeg))
        return if (text == "360.0") "0.0" else text
    }

    /**
     * track.csv のコンパス関連 6 列（カンマ区切り、前後のカンマは含まない）。
     * 方位角が無いときは先頭 3 列が空欄。磁場の指標は端末の傾きに関係なく書く
     */
    fun csvColumns(record: CompassRecord): String {
        val hasAzimuth = record.azimuthDeg != null
        return listOf(
            csvAzimuth(record.azimuthDeg),
            if (hasAzimuth) csv(record.accuracyDeg) else "",
            if (hasAzimuth) csv(record.declinationDeg) else "",
            csvRatio(record.fieldRatio),
            csv(record.inclinationDiffDeg),
            csvStatus(record.sensorStatus)
        ).joinToString(",")
    }

    const val NONE = "—"
}

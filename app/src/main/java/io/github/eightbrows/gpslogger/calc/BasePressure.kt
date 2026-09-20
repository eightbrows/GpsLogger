package io.github.eightbrows.gpslogger.calc

import io.github.eightbrows.gpslogger.session.SessionMeta
import io.github.eightbrows.gpslogger.state.BarometerReader

/**
 * 測位点の時刻から、気圧高度の計算に使う基準気圧（hPa）を決める。
 *
 * 優先順位:
 *  1. その時刻を含む区間に basePressureHpa があれば、その値
 *  2. 区間に設定が無い・どの区間にも入らない場合は、セッション全体の basePressureHpa
 *  3. meta.json が無い（meta == null）場合は fallbackHpa（既定は標準大気 1013.25 hPa）
 *
 * 区間の範囲は [start, end + 1秒) とする。meta.json の時刻は秒未満を切り捨てて保存しているため、
 * 区間の最後の1秒に記録した点が end をわずかに超えることがあるから。
 * 区間が重なった場合（同じ秒に一時停止と再開をした場合）は、先に並んでいる区間を採用する。
 *
 * 区間の時刻は生成時に一度だけミリ秒へ変換するので、点ごとの呼び出しは区間数に比例するだけで済む。
 */
class BasePressureResolver(
    meta: SessionMeta?,
    fallbackHpa: Float = BarometerReader.STANDARD_PRESSURE_HPA
) {

    private val sessionHpa: Float =
        meta?.basePressureHpa?.let(::usable) ?: usable(fallbackHpa.toDouble())
        ?: BarometerReader.STANDARD_PRESSURE_HPA

    // 時刻が欠けた区間は判定できないので除外する
    private val ranges: List<Range> = meta?.segments.orEmpty().mapNotNull { s ->
        val start = s.start?.toInstant()?.toEpochMilli() ?: return@mapNotNull null
        val end = s.end?.toInstant()?.toEpochMilli() ?: return@mapNotNull null
        Range(start, end + SECOND_MS, s.basePressureHpa?.let(::usable))
    }

    /** 時刻 [timeMs]（エポックミリ秒）の基準気圧 */
    fun at(timeMs: Long): Float {
        for (r in ranges) {
            if (timeMs >= r.startMs && timeMs < r.endExclusiveMs) return r.hpa ?: sessionHpa
        }
        return sessionHpa
    }

    /** 複数の時刻をまとめて解決する。戻り値は [timesMs] と同じ並び */
    fun resolveAll(timesMs: LongArray): FloatArray = FloatArray(timesMs.size) { at(timesMs[it]) }

    private class Range(val startMs: Long, val endExclusiveMs: Long, val hpa: Float?)

    private companion object {
        const val SECOND_MS = 1000L

        /** 0 以下・非有限の値では高度を計算できないので、未設定と同じに扱う */
        fun usable(hpa: Double): Float? =
            hpa.toFloat().takeIf { it.isFinite() && it > 0f }
    }
}

/** 1点だけ解決する。多数の点を扱うときは [BasePressureResolver] を使い回すこと */
fun resolveBasePressureHpa(
    meta: SessionMeta?,
    timeMs: Long,
    fallbackHpa: Float = BarometerReader.STANDARD_PRESSURE_HPA
): Float = BasePressureResolver(meta, fallbackHpa).at(timeMs)

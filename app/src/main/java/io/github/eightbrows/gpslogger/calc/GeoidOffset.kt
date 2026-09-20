package io.github.eightbrows.gpslogger.calc

import io.github.eightbrows.gpslogger.session.SessionMeta

/**
 * 測位点の時刻から、GPX / KMZ の標高換算に使うジオイド高（m）を決める。基準気圧と同じ決め方。
 *
 * 優先順位:
 *  1. その時刻を含む区間に geoidOffsetM があれば、その値
 *  2. 区間に設定が無い・どの区間にも入らない場合は、セッション全体の geoidOffsetM
 *  3. セッション全体にも無い（この項目より前に記録した・meta.json が無い）場合は fallbackM
 *     （アプリ全体の設定値）
 *
 * 区間の範囲は [start, end + 1秒) とする。meta.json の時刻は秒未満を切り捨てて保存しているため、
 * 区間の最後の1秒に記録した点が end をわずかに超えることがあるから。
 * 区間が重なった場合（同じ秒に一時停止と再開をした場合）は、先に並んでいる区間を採用する。
 */
class GeoidOffsetResolver(meta: SessionMeta?, fallbackM: Double) {

    private val sessionM: Double = meta?.geoidOffsetM?.let(::usable) ?: usable(fallbackM) ?: 0.0

    // 時刻が欠けた区間は判定できないので除外する
    private val ranges: List<Range> = meta?.segments.orEmpty().mapNotNull { s ->
        val start = s.start?.toInstant()?.toEpochMilli() ?: return@mapNotNull null
        val end = s.end?.toInstant()?.toEpochMilli() ?: return@mapNotNull null
        Range(start, end + SECOND_MS, s.geoidOffsetM?.let(::usable))
    }

    /** 時刻 [timeMs]（エポックミリ秒）のジオイド高（m） */
    fun at(timeMs: Long): Double {
        for (r in ranges) {
            if (timeMs >= r.startMs && timeMs < r.endExclusiveMs) return r.offsetM ?: sessionM
        }
        return sessionM
    }

    private class Range(val startMs: Long, val endExclusiveMs: Long, val offsetM: Double?)

    companion object {
        private const val SECOND_MS = 1000L

        /** 補正しない（楕円体高のまま）／固定値だけを使うときの解決役 */
        fun constant(offsetM: Double): GeoidOffsetResolver =
            GeoidOffsetResolver(meta = null, fallbackM = offsetM)

        /** NaN や無限大では高度を計算できないので、未設定と同じに扱う。0 と負の値は有効 */
        private fun usable(m: Double): Double? = m.takeIf { it.isFinite() }
    }
}

/** 1点だけ解決する。多数の点を扱うときは [GeoidOffsetResolver] を使い回すこと */
fun resolveGeoidOffsetM(meta: SessionMeta?, timeMs: Long, fallbackM: Double): Double =
    GeoidOffsetResolver(meta, fallbackM).at(timeMs)

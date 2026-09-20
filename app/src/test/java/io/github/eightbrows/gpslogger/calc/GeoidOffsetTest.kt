package io.github.eightbrows.gpslogger.calc

import io.github.eightbrows.gpslogger.session.Segment
import io.github.eightbrows.gpslogger.session.SessionMeta
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

/** 区間別 → セッション全体 → アプリ全体の設定、の順でジオイド高が決まることを確認する */
class GeoidOffsetTest {

    private val appSetting = 36.0

    private fun time(text: String): OffsetDateTime = OffsetDateTime.parse(text)
    private fun ms(text: String): Long = time(text).toInstant().toEpochMilli()

    /** 09:00:00〜09:00:10 と 09:01:00〜09:01:10 の 2 区間 */
    private fun meta(
        sessionOffset: Double? = 40.0,
        first: Double? = null,
        second: Double? = null
    ) = SessionMeta(
        geoidOffsetM = sessionOffset,
        segments = listOf(
            Segment(time("2026-09-20T09:00:00+09:00"), time("2026-09-20T09:00:10+09:00"), 11, null, first),
            Segment(time("2026-09-20T09:01:00+09:00"), time("2026-09-20T09:01:10+09:00"), 11, null, second)
        )
    )

    @Test
    fun noMeta_usesAppSetting() {
        val r = GeoidOffsetResolver(null, appSetting)
        assertEquals(36.0, r.at(ms("2026-09-20T09:00:05+09:00")), 0.0)
    }

    @Test
    fun metaWithoutGeoid_usesAppSetting() {
        // この項目より前に記録したセッション（meta.json に geoidOffsetM が無い）
        val r = GeoidOffsetResolver(meta(sessionOffset = null), appSetting)
        assertEquals(36.0, r.at(ms("2026-09-20T09:00:05+09:00")), 0.0)
    }

    @Test
    fun sessionValue_isUsedWhenSegmentHasNone() {
        val r = GeoidOffsetResolver(meta(), appSetting)
        assertEquals(40.0, r.at(ms("2026-09-20T09:00:05+09:00")), 0.0)
        assertEquals(40.0, r.at(ms("2026-09-20T09:01:05+09:00")), 0.0)
        // どの区間にも入らない時刻
        assertEquals(40.0, r.at(ms("2026-09-20T09:00:30+09:00")), 0.0)
    }

    @Test
    fun segmentValue_winsOverSessionValue() {
        val r = GeoidOffsetResolver(meta(first = 37.5, second = 41.25), appSetting)
        assertEquals(37.5, r.at(ms("2026-09-20T09:00:00+09:00")), 0.0)
        assertEquals(41.25, r.at(ms("2026-09-20T09:01:05+09:00")), 0.0)
        // 区間の外はセッション全体の値
        assertEquals(40.0, r.at(ms("2026-09-20T09:00:30+09:00")), 0.0)
    }

    @Test
    fun segmentRangeIncludesTheLastSecond() {
        val r = GeoidOffsetResolver(meta(first = 37.5), appSetting)
        // 区間は [start, end + 1秒)。end の秒に記録した点も区間に含める
        assertEquals(37.5, r.at(ms("2026-09-20T09:00:10+09:00") + 999), 0.0)
        assertEquals(40.0, r.at(ms("2026-09-20T09:00:11+09:00")), 0.0)
        assertEquals(40.0, r.at(ms("2026-09-20T09:00:00+09:00") - 1), 0.0)
    }

    @Test
    fun zeroAndNegativeValues_areValid() {
        // 0 は「補正しない」という設定なので、未設定として扱わない
        assertEquals(0.0, GeoidOffsetResolver(meta(sessionOffset = 0.0), appSetting).at(0L), 0.0)
        assertEquals(0.0, GeoidOffsetResolver(meta(first = 0.0), appSetting)
            .at(ms("2026-09-20T09:00:05+09:00")), 0.0)
        // ジオイドが楕円体より下にある地域
        assertEquals(-30.0, GeoidOffsetResolver(meta(sessionOffset = -30.0), appSetting).at(0L), 0.0)
    }

    @Test
    fun nonFiniteValues_fallBack() {
        assertEquals(36.0, GeoidOffsetResolver(meta(sessionOffset = Double.NaN), appSetting).at(0L), 0.0)
        assertEquals(
            40.0,
            GeoidOffsetResolver(meta(first = Double.POSITIVE_INFINITY), appSetting)
                .at(ms("2026-09-20T09:00:05+09:00")),
            0.0
        )
        // アプリ全体の設定まで壊れていれば補正なし
        assertEquals(0.0, GeoidOffsetResolver(null, Double.NaN).at(0L), 0.0)
    }

    @Test
    fun segmentWithoutTimes_isIgnored() {
        val meta = SessionMeta(
            geoidOffsetM = 40.0,
            segments = listOf(Segment(null, null, 5, null, 12.0))
        )
        assertEquals(40.0, GeoidOffsetResolver(meta, appSetting).at(ms("2026-09-20T09:00:05+09:00")), 0.0)
    }

    @Test
    fun constant_ignoresSegments() {
        assertEquals(36.0, GeoidOffsetResolver.constant(36.0).at(0L), 0.0)
        assertEquals(0.0, GeoidOffsetResolver.constant(0.0).at(12345L), 0.0)
    }

    @Test
    fun resolveGeoidOffsetM_matchesResolver() {
        val meta = meta(first = 37.5)
        val t = ms("2026-09-20T09:00:05+09:00")
        assertEquals(GeoidOffsetResolver(meta, appSetting).at(t), resolveGeoidOffsetM(meta, t, appSetting), 0.0)
    }

    @Test
    fun inHgConversion_roundTrips() {
        assertEquals(1013.25, PressureUnits.hpaFromInHg(PressureUnits.inHgFromHpa(1013.25)), 1e-9)
        // 標準大気 = 29.92 inHg
        assertEquals(29.92, PressureUnits.inHgFromHpa(1013.25), 0.005)
        assertEquals(1014.55, PressureUnits.hpaFromInHg(29.9597), 0.01)
    }
}

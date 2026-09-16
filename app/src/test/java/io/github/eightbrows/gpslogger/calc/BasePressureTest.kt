package io.github.eightbrows.gpslogger.calc

import io.github.eightbrows.gpslogger.session.Segment
import io.github.eightbrows.gpslogger.session.SessionMeta
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

class BasePressureTest {

    private val standard = 1013.25f
    private val eps = 0.0001f

    private fun t(text: String): OffsetDateTime = OffsetDateTime.parse(text)
    private fun ms(text: String): Long = t(text).toInstant().toEpochMilli()

    // 区間1: 09:20:00〜11:20:00（個別 1009.5）
    // 一時停止: 11:20〜12:00
    // 区間2: 12:00:00〜15:30:00（個別設定なし）
    private val seg1 = Segment(
        start = t("2026-09-15T09:20:00+09:00"),
        end = t("2026-09-15T11:20:00+09:00"),
        points = 7200,
        basePressureHpa = 1009.5
    )
    private val seg2 = Segment(
        start = t("2026-09-15T12:00:00+09:00"),
        end = t("2026-09-15T15:30:00+09:00"),
        points = 12600,
        basePressureHpa = null
    )
    private val meta = SessionMeta(basePressureHpa = 1011.2, segments = listOf(seg1, seg2))

    // --- meta.json の有無 ---

    @Test
    fun noMeta_usesStandardAtmosphere() {
        assertEquals(standard, resolveBasePressureHpa(null, 0L), eps)
        assertEquals(standard, resolveBasePressureHpa(null, ms("2026-09-15T10:00:00+09:00")), eps)
    }

    @Test
    fun metaWithoutSegments_usesSessionValue() {
        val m = SessionMeta(basePressureHpa = 1005.0)
        assertEquals(1005.0f, resolveBasePressureHpa(m, ms("2026-09-15T10:00:00+09:00")), eps)
    }

    @Test
    fun defaultMeta_usesDefaultSessionValue() {
        // 記録停止時に書き出される既定の meta.json（ユーザー未編集）
        assertEquals(standard, resolveBasePressureHpa(SessionMeta(), 123_456L), eps)
    }

    // --- 区間の個別設定 ---

    @Test
    fun insideSegmentWithValue_usesSegmentValue() {
        assertEquals(1009.5f, resolveBasePressureHpa(meta, ms("2026-09-15T10:00:00+09:00")), eps)
    }

    @Test
    fun insideSegmentWithoutValue_usesSessionValue() {
        assertEquals(1011.2f, resolveBasePressureHpa(meta, ms("2026-09-15T13:00:00+09:00")), eps)
    }

    // --- 区間外 ---

    @Test
    fun outsideAllSegments_usesSessionValue() {
        // 記録開始前・一時停止中・終了後
        assertEquals(1011.2f, resolveBasePressureHpa(meta, ms("2026-09-15T09:00:00+09:00")), eps)
        assertEquals(1011.2f, resolveBasePressureHpa(meta, ms("2026-09-15T11:40:00+09:00")), eps)
        assertEquals(1011.2f, resolveBasePressureHpa(meta, ms("2026-09-15T16:00:00+09:00")), eps)
    }

    // --- 境界 ---

    @Test
    fun segmentStart_isInclusive() {
        val start = ms("2026-09-15T09:20:00+09:00")
        assertEquals(1009.5f, resolveBasePressureHpa(meta, start), eps)
        assertEquals(1011.2f, resolveBasePressureHpa(meta, start - 1), eps)
    }

    @Test
    fun segmentEnd_coversTheWholeTruncatedSecond() {
        // end は秒未満切り捨てで保存されるため、end の秒の終わりまでを区間とみなす
        val end = ms("2026-09-15T11:20:00+09:00")
        assertEquals(1009.5f, resolveBasePressureHpa(meta, end), eps)
        assertEquals(1009.5f, resolveBasePressureHpa(meta, end + 600), eps)
        assertEquals(1009.5f, resolveBasePressureHpa(meta, end + 999), eps)
        assertEquals(1011.2f, resolveBasePressureHpa(meta, end + 1000), eps)
    }

    @Test
    fun overlappingSegmentsWithinOneSecond_preferEarlierSegment() {
        // 11:20:00.3 に一時停止、11:20:00.8 に再開 → 両区間の境界が同じ秒になる
        val a = seg1
        val b = seg2.copy(start = t("2026-09-15T11:20:00+09:00"), basePressureHpa = 1000.0)
        val m = meta.copy(segments = listOf(a, b))
        assertEquals(1009.5f, resolveBasePressureHpa(m, ms("2026-09-15T11:20:00.500+09:00")), eps)
        assertEquals(1000.0f, resolveBasePressureHpa(m, ms("2026-09-15T11:20:01+09:00")), eps)
    }

    // --- 時差・壊れた値 ---

    @Test
    fun segmentOffset_doesNotAffectMatching() {
        // 同じ瞬間を UTC で表した区間でも一致する
        val utcSeg = seg1.copy(
            start = t("2026-09-15T00:20:00Z"),
            end = t("2026-09-15T02:20:00Z")
        )
        val m = meta.copy(segments = listOf(utcSeg))
        assertEquals(1009.5f, resolveBasePressureHpa(m, ms("2026-09-15T10:00:00+09:00")), eps)
    }

    @Test
    fun segmentWithMissingTime_isIgnored() {
        val broken = Segment(start = null, end = seg1.end, basePressureHpa = 900.0)
        val m = meta.copy(segments = listOf(broken, seg1))
        assertEquals(1009.5f, resolveBasePressureHpa(m, ms("2026-09-15T10:00:00+09:00")), eps)
    }

    @Test
    fun unusableValues_fallBackToNextLevel() {
        val zeroSeg = seg1.copy(basePressureHpa = 0.0)
        val negativeSeg = seg1.copy(basePressureHpa = -1.0)
        val at = ms("2026-09-15T10:00:00+09:00")
        assertEquals(1011.2f, resolveBasePressureHpa(meta.copy(segments = listOf(zeroSeg)), at), eps)
        assertEquals(1011.2f, resolveBasePressureHpa(meta.copy(segments = listOf(negativeSeg)), at), eps)
        // セッション全体の値も使えなければ標準大気
        val m = SessionMeta(basePressureHpa = 0.0, segments = listOf(seg2))
        assertEquals(standard, resolveBasePressureHpa(m, ms("2026-09-15T13:00:00+09:00")), eps)
    }

    // --- まとめて解決 ---

    @Test
    fun resolveAll_matchesPointwiseResolution() {
        val times = longArrayOf(
            ms("2026-09-15T09:19:59+09:00"),
            ms("2026-09-15T09:20:00+09:00"),
            ms("2026-09-15T11:20:00.999+09:00"),
            ms("2026-09-15T11:30:00+09:00"),
            ms("2026-09-15T12:00:00+09:00"),
            ms("2026-09-15T15:30:00.500+09:00"),
            ms("2026-09-15T15:30:01+09:00")
        )
        val resolved = BasePressureResolver(meta).resolveAll(times)
        assertArrayEquals(
            floatArrayOf(1011.2f, 1009.5f, 1009.5f, 1011.2f, 1011.2f, 1011.2f, 1011.2f),
            resolved,
            eps
        )
        times.forEachIndexed { i, time ->
            assertEquals(resolveBasePressureHpa(meta, time), resolved[i], 0f)
        }
        assertEquals(0, BasePressureResolver(meta).resolveAll(LongArray(0)).size)
    }

    @Test
    fun metaReadBackFromJson_resolvesTheSame() {
        val restored = SessionMeta.parse(meta.toJson().toString())!!
        val at = ms("2026-09-15T10:00:00+09:00")
        assertEquals(resolveBasePressureHpa(meta, at), resolveBasePressureHpa(restored, at), 0f)
    }
}

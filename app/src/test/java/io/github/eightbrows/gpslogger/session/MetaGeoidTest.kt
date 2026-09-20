package io.github.eightbrows.gpslogger.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/** meta.json のジオイド高（セッション全体・区間別）の読み書きと、編集画面での編集 */
class MetaGeoidTest {

    private val start = OffsetDateTime.parse("2026-09-20T09:00:00+09:00")
    private val end = OffsetDateTime.parse("2026-09-20T09:00:10+09:00")

    private fun meta(sessionOffset: Double? = 36.0, segmentOffset: Double? = null) = SessionMeta(
        start = start,
        end = end,
        geoidOffsetM = sessionOffset,
        segments = listOf(Segment(start, end, 11, null, segmentOffset))
    )

    // ===== JSON =====

    @Test
    fun json_roundTripsSessionAndSegmentValues() {
        val original = meta(sessionOffset = 36.5, segmentOffset = -12.25)
        val restored = SessionMeta.parse(original.toJson().toString())
        assertEquals(original, restored)
        assertEquals(36.5, restored!!.geoidOffsetM!!, 0.0)
        assertEquals(-12.25, restored.segments[0].geoidOffsetM!!, 0.0)
    }

    @Test
    fun json_writesNullWhenUnset() {
        val json = meta(sessionOffset = null).toJson()
        assertTrue(json.isNull("geoidOffsetM"))
        assertTrue(json.getJSONArray("segments").getJSONObject(0).isNull("geoidOffsetM"))
    }

    @Test
    fun json_withoutGeoidKey_readsAsUnset() {
        // この項目より前に書いた meta.json
        val json = JSONObject(
            """{"schemaVersion":1,"basePressureHpa":1013.25,"segments":[{"points":3}]}"""
        )
        val restored = SessionMeta.fromJson(json)
        assertNull(restored.geoidOffsetM)
        assertNull(restored.segments[0].geoidOffsetM)
        // 気圧の既定値はこれまでどおり
        assertEquals(1013.25, restored.basePressureHpa, 0.0)
    }

    // ===== 編集画面（平坦化ビュー） =====

    @Test
    fun flatten_showsGeoidRows_asEditable() {
        val rows = MetaPaths.flatten(meta(sessionOffset = 36.0, segmentOffset = 33.5))
        val session = rows.single { it.path == "geoidOffsetM" }
        assertEquals("36", session.value)
        assertTrue(session.editable)
        val segment = rows.single { it.path == "segments[0].geoidOffsetM" }
        assertEquals("33.5", segment.value)
        assertTrue(segment.editable)
        // 気圧の行はそのまま残っている
        assertTrue(rows.single { it.path == "basePressureHpa" }.editable)
    }

    @Test
    fun flatten_unsetGeoid_showsAsNull() {
        val rows = MetaPaths.flatten(meta(sessionOffset = null))
        assertNull(rows.single { it.path == "geoidOffsetM" }.value)
        assertNull(rows.single { it.path == "segments[0].geoidOffsetM" }.value)
    }

    @Test
    fun editText_returnsEmptyWhenUnset() {
        assertEquals("36", MetaPaths.editText(meta(), "geoidOffsetM"))
        assertEquals("", MetaPaths.editText(meta(sessionOffset = null), "geoidOffsetM"))
        assertEquals("12.5", MetaPaths.editText(meta(segmentOffset = 12.5), "segments[0].geoidOffsetM"))
        assertEquals("", MetaPaths.editText(meta(), "segments[0].geoidOffsetM"))
    }

    @Test
    fun apply_setsSessionGeoid_includingZeroAndNegative() {
        assertEquals(41.5, success(meta(), "geoidOffsetM", "41.5").geoidOffsetM!!, 0.0)
        assertEquals(0.0, success(meta(), "geoidOffsetM", "0").geoidOffsetM!!, 0.0)
        assertEquals(-30.0, success(meta(), "geoidOffsetM", " -30 ").geoidOffsetM!!, 0.0)
    }

    @Test
    fun apply_blankClearsGeoid() {
        assertNull(success(meta(), "geoidOffsetM", "").geoidOffsetM)
        assertNull(success(meta(segmentOffset = 20.0), "segments[0].geoidOffsetM", "").segments[0].geoidOffsetM)
    }

    @Test
    fun apply_setsSegmentGeoid_withoutTouchingPressure() {
        val base = meta().let { it.copy(segments = listOf(it.segments[0].copy(basePressureHpa = 1005.0))) }
        val updated = success(base, "segments[0].geoidOffsetM", "33.5")
        assertEquals(33.5, updated.segments[0].geoidOffsetM!!, 0.0)
        assertEquals(1005.0, updated.segments[0].basePressureHpa!!, 0.0)
    }

    @Test
    fun apply_rejectsOutOfRangeAndNonNumbers() {
        for (input in listOf("121", "-120.5", "abc", "1e2", "３６")) {
            val result = MetaPaths.apply(meta(), "geoidOffsetM", input)
            assertEquals(
                "input=$input",
                MetaEditResult.Failure(MetaEditResult.Reason.INVALID_GEOID),
                result
            )
        }
        // 範囲の端は受け付ける
        assertEquals(120.0, success(meta(), "geoidOffsetM", "120").geoidOffsetM!!, 0.0)
        assertEquals(-120.0, success(meta(), "geoidOffsetM", "-120").geoidOffsetM!!, 0.0)
    }

    @Test
    fun apply_rejectsMissingSegment() {
        assertEquals(
            MetaEditResult.Failure(MetaEditResult.Reason.SEGMENT_NOT_FOUND, 3),
            MetaPaths.apply(meta(), "segments[3].geoidOffsetM", "10")
        )
    }

    @Test
    fun apply_negativePressureIsStillRejectedAsPressure() {
        assertEquals(
            MetaEditResult.Failure(MetaEditResult.Reason.INVALID_PRESSURE),
            MetaPaths.apply(meta(), "segments[0].basePressureHpa", "-1000")
        )
    }

    // ===== うるう秒（セッション全体のみ） =====

    @Test
    fun leapSeconds_roundTripsThroughJson() {
        val restored = SessionMeta.parse(meta().copy(leapSeconds = 17).toJson().toString())
        assertEquals(17, restored!!.leapSeconds)
        assertTrue(meta().copy(leapSeconds = null).toJson().isNull("leapSeconds"))
        // この項目より前に書いた meta.json
        assertNull(SessionMeta.fromJson(JSONObject("""{"schemaVersion":1}""")).leapSeconds)
    }

    @Test
    fun leapSeconds_isEditable_andShownAsUnsetWhenNull() {
        val rows = MetaPaths.flatten(meta().copy(leapSeconds = 18))
        val row = rows.single { it.path == "leapSeconds" }
        assertEquals("18", row.value)
        assertTrue(row.editable)
        assertNull(MetaPaths.flatten(meta()).single { it.path == "leapSeconds" }.value)
        // 区間別には持たない
        assertTrue(rows.none { it.path.startsWith("segments[") && it.path.endsWith("leapSeconds") })
    }

    @Test
    fun leapSeconds_acceptsWholeNumbersInRange_andBlankClears() {
        assertEquals(17, success(meta(), "leapSeconds", "17").leapSeconds)
        assertEquals(0, success(meta(), "leapSeconds", " 0 ").leapSeconds)
        assertEquals(99, success(meta(), "leapSeconds", "99").leapSeconds)
        assertNull(success(meta().copy(leapSeconds = 18), "leapSeconds", "").leapSeconds)
    }

    @Test
    fun leapSeconds_rejectsOutOfRangeAndNonIntegers() {
        for (input in listOf("100", "-1", "17.5", "abc", "1e1")) {
            assertEquals(
                "input=$input",
                MetaEditResult.Failure(MetaEditResult.Reason.INVALID_LEAP_SECONDS),
                MetaPaths.apply(meta(), "leapSeconds", input)
            )
        }
    }

    @Test
    fun segmentOf_returnsTheSegmentForSegmentPaths() {
        val m = meta(segmentOffset = 12.0)
        assertEquals(m.segments[0], MetaPaths.segmentOf(m, "segments[0].geoidOffsetM"))
        assertEquals(m.segments[0], MetaPaths.segmentOf(m, "segments[0].basePressureHpa"))
        assertNull(MetaPaths.segmentOf(m, "segments[9].geoidOffsetM"))
        assertNull(MetaPaths.segmentOf(m, "geoidOffsetM"))
    }

    private fun success(meta: SessionMeta, path: String, input: String): SessionMeta =
        (MetaPaths.apply(meta, path, input) as MetaEditResult.Success).meta
}

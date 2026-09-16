package io.github.eightbrows.gpslogger.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class SessionMetaTest {

    private val jst = ZoneOffset.ofHours(9)

    private fun t(text: String): OffsetDateTime = OffsetDateTime.parse(text)

    private val sample = SessionMeta(
        comment = "朝の散歩 \"テスト\"\n2行目",
        tags = listOf("walk", "晴れ"),
        basePressureHpa = 1008.6,
        start = t("2026-09-15T09:20:00+09:00"),
        end = t("2026-09-15T15:30:00+09:00"),
        pointCount = 15600,
        distanceM = 8420.5,
        segments = listOf(
            Segment(
                start = t("2026-09-15T09:20:00+09:00"),
                end = t("2026-09-15T11:20:00+09:00"),
                points = 7200,
                basePressureHpa = null
            ),
            Segment(
                start = t("2026-09-15T12:00:00+09:00"),
                end = t("2026-09-15T15:30:00+09:00"),
                points = 8400,
                basePressureHpa = 1011.2
            )
        ),
        useWakeLock = true,
        deviceModel = "Pixel 8",
        osVersion = "16",
        appVersion = "20260913-R01"
    )

    @Test
    fun roundTrip_restoresAllFields() {
        val text = sample.toJson().toString(2)
        val restored = SessionMeta.fromJson(JSONObject(text))
        assertEquals(sample, restored)
    }

    @Test
    fun roundTrip_integralDoubleStaysDouble() {
        // 8420.0 のような値は JSON 上で整数になるが、読み戻しても Double として一致すること
        val meta = sample.copy(distanceM = 8420.0, basePressureHpa = 1013.0)
        assertEquals(meta, SessionMeta.parse(meta.toJson().toString()))
    }

    @Test
    fun toJson_writesIsoOffsetTimeAndNullSegmentPressure() {
        val json = sample.toJson()
        assertEquals("2026-09-15T09:20:00+09:00", json.getString("start"))
        val seg0 = json.getJSONArray("segments").getJSONObject(0)
        assertTrue(seg0.isNull("basePressureHpa"))
        assertEquals(1, json.getInt("schemaVersion"))
    }

    @Test
    fun toJson_nonFiniteNumbersBecomeNullAndReadBackAsDefault() {
        val meta = sample.copy(distanceM = Double.NaN)
        val json = meta.toJson()
        assertTrue(json.isNull("distanceM"))
        assertEquals(0.0, SessionMeta.fromJson(json).distanceM, 0.0)
    }

    @Test
    fun fromJson_emptyObject_givesDefaults() {
        assertEquals(SessionMeta(), SessionMeta.fromJson(JSONObject()))
    }

    @Test
    fun fromJson_brokenFields_fallBackPerField() {
        val text = """
            {
              "schemaVersion": "one",
              "comment": 42,
              "tags": ["ok", 3, null, "also-ok"],
              "basePressureHpa": "1013.25",
              "start": "2026-09-15 09:20",
              "end": "2026-09-15T15:30:00+09:00",
              "pointCount": null,
              "distanceM": true,
              "segments": [1, "x", {"start": "bad", "points": "many", "basePressureHpa": "hi"}],
              "useWakeLock": "yes",
              "deviceModel": null,
              "osVersion": 16,
              "appVersion": "R01"
            }
        """.trimIndent()

        val meta = SessionMeta.parse(text)!!
        val d = SessionMeta()
        assertEquals(d.schemaVersion, meta.schemaVersion)
        assertEquals(d.comment, meta.comment)
        assertEquals(listOf("ok", "also-ok"), meta.tags)
        assertEquals(d.basePressureHpa, meta.basePressureHpa, 0.0)
        assertNull(meta.start)
        // 壊れていない項目はそのまま読める
        assertEquals(t("2026-09-15T15:30:00+09:00"), meta.end)
        assertEquals(d.pointCount, meta.pointCount)
        assertEquals(d.distanceM, meta.distanceM, 0.0)
        assertEquals(listOf(Segment(start = null, end = null, points = 0, basePressureHpa = null)), meta.segments)
        assertEquals(d.useWakeLock, meta.useWakeLock)
        assertEquals(d.deviceModel, meta.deviceModel)
        assertEquals(d.osVersion, meta.osVersion)
        assertEquals("R01", meta.appVersion)
    }

    @Test
    fun fromJson_wrongContainerTypes_giveDefaults() {
        val meta = SessionMeta.parse("""{"tags": "walk", "segments": {"points": 1}}""")!!
        assertEquals(emptyList<String>(), meta.tags)
        assertEquals(emptyList<Segment>(), meta.segments)
    }

    @Test
    fun parse_invalidJson_returnsNull() {
        assertNull(SessionMeta.parse(""))
        assertNull(SessionMeta.parse("{ broken"))
        assertNull(SessionMeta.parse("[1, 2, 3]"))
        assertNull(SessionMeta.parse("null"))
    }

    @Test
    fun timeOf_usesZoneOffsetAndTruncatesToSeconds() {
        // 2026-09-15T00:20:00.789Z
        val ms = 1_789_431_600_789L
        val time = SessionMeta.timeOf(ms, ZoneId.of("Asia/Tokyo"))
        assertEquals(jst, time.offset)
        assertEquals(0, time.nano)
        assertEquals("2026-09-15T09:20:00+09:00", SessionMeta(start = time).toJson().getString("start"))
    }
}

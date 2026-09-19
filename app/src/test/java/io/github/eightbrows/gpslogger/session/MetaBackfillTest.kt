package io.github.eightbrows.gpslogger.session

import io.github.eightbrows.gpslogger.calc.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.time.OffsetDateTime
import java.time.ZoneId

/** track.csv から meta.json を後付けで作る処理 */
class MetaBackfillTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tokyo = ZoneId.of("Asia/Tokyo")

    private val v1Header =
        "utc_iso8601,epoch_ms,elapsed_realtime_ns,provider,latitude,longitude," +
            "altitude_ellipsoid_m,horizontal_acc_m,vertical_acc_m,speed_mps,speed_acc_mps," +
            "bearing_deg,bearing_acc_deg,gdop,pdop,hdop,vdop,tdop,is_mock"

    /** 2026-09-15 09:20:00.500 JST から 1 秒ごと */
    private fun v1Row(i: Int, lat: Double, lon: Double) =
        "t,${1_789_431_600_500L + i * 1000L},0,gps,$lat,$lon,40.0,3,5,0,0,0,0,,,,,,false"

    private fun pt(i: Int, lat: Double, lon: Double, gap: Boolean = false) = TrackRecord(
        epochMs = 1_789_431_600_500L + i * 1000L, elapsedRealtimeNs = 0L,
        latitude = lat, longitude = lon, altitude = 40.0,
        accuracy = 3f, verticalAccuracy = 5f, speed = 0f, bearing = 0f, bearingAccuracy = 0f,
        dop = null, gapBefore = gap, intervalSec = 1, pressureHpa = null
    )

    @Test
    fun build_fillsAllItemsFromTrack() {
        val track = listOf(pt(0, 35.0, 139.0), pt(1, 35.001, 139.0), pt(2, 35.002, 139.0))
        val meta = MetaBackfill.build(track, tokyo)

        val start = OffsetDateTime.parse("2026-09-15T09:20:00+09:00")
        val end = OffsetDateTime.parse("2026-09-15T09:20:02+09:00")
        assertEquals(start, meta.start)
        assertEquals(end, meta.end)
        assertEquals(3, meta.pointCount)
        assertEquals(listOf(Segment(start, end, 3, null)), meta.segments)
        assertEquals("", meta.comment)
        assertEquals(emptyList<String>(), meta.tags)
        assertEquals(1013.25, meta.basePressureHpa, 0.0)
        assertFalse(meta.useWakeLock)
        assertEquals("Unknown", meta.deviceModel)
        assertEquals("Unknown", meta.osVersion)
        assertEquals("Unknown", meta.appVersion)
        assertEquals(SessionMeta.SCHEMA_VERSION, meta.schemaVersion)
        // 緯度 0.002° ≒ 222 m
        assertEquals(222.4, meta.distanceM, 0.5)
    }

    @Test
    fun build_distanceIgnoresPauses_countsWholeTrackAsOneLine() {
        val track = listOf(pt(0, 35.0, 139.0), pt(1, 35.001, 139.0), pt(2, 35.002, 139.0, gap = true))
        val meta = MetaBackfill.build(track, tokyo)
        val whole = Geo.pathLengthM(track.map { it.latitude to it.longitude })
        assertEquals(whole, meta.distanceM, 0.0)
        assertEquals(1, meta.segments.size)
    }

    @Test
    fun build_emptyTrack_givesNoTimesAndNoSegments() {
        val meta = MetaBackfill.build(emptyList(), tokyo)
        assertNull(meta.start)
        assertNull(meta.end)
        assertEquals(0, meta.pointCount)
        assertEquals(0.0, meta.distanceM, 0.0)
        assertEquals(emptyList<Segment>(), meta.segments)
    }

    @Test
    fun create_readsV1TrackCsv_writesMetaJson_thatReadsBack() {
        val dir = File(tmp.root, "session_20260915_092000").apply { mkdirs() }
        File(dir, "track.csv").writeText(
            listOf(v1Header, v1Row(0, 35.0, 139.0), "broken", v1Row(1, 35.001, 139.0)).joinToString("\n")
        )
        val created = MetaBackfill.create(dir, tokyo)

        // 読み込めた行だけを数える
        assertEquals(2, created.pointCount)
        assertEquals(OffsetDateTime.parse("2026-09-15T09:20:01+09:00"), created.end)
        val file = File(dir, SessionMeta.FILE_NAME)
        assertTrue(file.exists())
        assertFalse(File(dir, SessionMeta.FILE_NAME + ".tmp").exists())
        assertEquals(created, SessionMeta.parse(file.readText()))
    }

    @Test
    fun create_replacesBrokenMetaJson() {
        val dir = tmp.newFolder()
        File(dir, "track.csv").writeText(listOf(v1Header, v1Row(0, 35.0, 139.0)).joinToString("\n"))
        File(dir, SessionMeta.FILE_NAME).writeText("{ broken")
        MetaBackfill.create(dir, tokyo)
        assertEquals(1, SessionMeta.parse(File(dir, SessionMeta.FILE_NAME).readText())!!.pointCount)
    }

    @Test(expected = FileNotFoundException::class)
    fun create_withoutTrackCsv_throws_andWritesNothing() {
        val dir = tmp.newFolder()
        try {
            MetaBackfill.create(dir, tokyo)
        } finally {
            assertFalse(File(dir, SessionMeta.FILE_NAME).exists())
        }
    }
}

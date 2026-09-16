package io.github.eightbrows.gpslogger.session

import io.github.eightbrows.gpslogger.calc.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** track.csv を実際に読ませて、gap_before 列で区間が切れることを確認する */
class TrackDistanceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private data class Pt(val lat: Double, val lon: Double, val gapBefore: Boolean = false)

    /** track.csv v2（22列）の1行。距離の計算に関係する列以外は固定値 */
    private fun row(i: Int, p: Pt): String = listOf(
        "2026-09-15T00:00:00.000Z",      // utc_iso8601
        (1_789_431_600_000L + i * 1000L).toString(),
        (i * 1_000_000_000L).toString(), // elapsed_realtime_ns
        "gps",
        p.lat.toString(),
        p.lon.toString(),
        "10.0", "3.0", "5.0",            // altitude, h/v acc
        "0.0", "0.0", "0.0", "0.0",      // speed, speed acc, bearing, bearing acc
        "", "", "", "", "",              // dop ×5（空欄）
        "false",                         // is_mock
        p.gapBefore.toString(),          // gap_before
        "1",                             // interval_sec
        ""                               // pressure_hpa（空欄）
    ).joinToString(",")

    private fun session(points: List<Pt>): File {
        val dir = tmp.newFolder()
        val header = "utc_iso8601,epoch_ms,elapsed_realtime_ns,provider,latitude,longitude," +
            "altitude_ellipsoid_m,horizontal_acc_m,vertical_acc_m,speed_mps,speed_acc_mps," +
            "bearing_deg,bearing_acc_deg,gdop,pdop,hdop,vdop,tdop,is_mock," +
            "gap_before,interval_sec,pressure_hpa"
        val lines = listOf(header) + points.mapIndexed { i, p -> row(i, p) }
        File(dir, "track.csv").writeText(lines.joinToString("\n", postfix = "\n"))
        return dir
    }

    private fun leg(a: Pt, b: Pt) = Geo.haversineM(a.lat, a.lon, b.lat, b.lon)

    // 東京駅付近を北へ約 111 m ずつ歩き、休憩中に約 11 km 北へ移動して再開する想定
    private val a = Pt(35.6800, 139.7670)
    private val b = Pt(35.6810, 139.7670)
    private val c = Pt(35.7810, 139.7670, gapBefore = true)
    private val d = Pt(35.7820, 139.7670)

    @Test
    fun pausedSession_excludesMovementDuringPause() {
        val dist = SessionReader.trackDistanceM(session(listOf(a, b, c, d)))
        assertEquals(leg(a, b) + leg(c, d), dist, 1e-9)
        // 休憩中の移動（約 11 km）が入っていれば 1 km を大きく超える
        assertTrue("distance=$dist", dist < 300.0)
    }

    @Test
    fun unpausedSession_matchesPlainPathLength() {
        val points = listOf(a, b, c.copy(gapBefore = false), d)
        val dist = SessionReader.trackDistanceM(session(points))
        assertEquals(Geo.pathLengthM(points.map { it.lat to it.lon }), dist, 0.0)
    }

    @Test
    fun missingTrackFile_isZero() {
        assertEquals(0.0, SessionReader.trackDistanceM(tmp.newFolder()), 0.0)
    }
}

package io.github.eightbrows.gpslogger.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧形式・現行形式の track.csv を読めることを確かめる。
 *  - v1（19 列）: …, tdop, is_mock
 *  - v2（22 列）: + gap_before, interval_sec, pressure_hpa
 *  - コンパス付き（25 列）: + compass_azimuth_deg, compass_accuracy_deg, magnetic_declination_deg
 */
class TrackCsvCompatTest {

    private val v1Header =
        "utc_iso8601,epoch_ms,elapsed_realtime_ns,provider,latitude,longitude," +
            "altitude_ellipsoid_m,horizontal_acc_m,vertical_acc_m,speed_mps,speed_acc_mps," +
            "bearing_deg,bearing_acc_deg,gdop,pdop,hdop,vdop,tdop,is_mock"
    private val v2bHeader = "$v1Header,gap_before,interval_sec,pressure_hpa"
    private val compassHeader =
        "$v2bHeader,compass_azimuth_deg,compass_accuracy_deg,magnetic_declination_deg"

    /** 19 列の共通部分 */
    private fun base(i: Int, dop: Boolean = true): String = listOf(
        "2026-09-15T00:20:0$i.000Z",
        (1_789_431_600_000L + i * 1000L).toString(),
        (5_000_000_000L + i * 1_000_000_000L).toString(),
        "gps",
        "35.68${i}",
        "139.76${i}",
        "4$i.5",
        "3.0", "5.0", "1.25", "0.1", "90.0", "2.0",
        if (dop) "1.90" else "", if (dop) "1.60" else "", if (dop) "0.90" else "",
        if (dop) "1.30" else "", if (dop) "1.00" else "",
        "false"
    ).joinToString(",")

    private fun parse(vararg lines: String) = SessionReader.parseTrack(lines.asSequence())

    @Test
    fun v1_19columns_readsWithDefaultsForNewColumns() {
        val (track, skipped) = parse(v1Header, base(0), base(1))
        assertEquals(0, skipped)
        assertEquals(2, track.size)
        val p = track[0]
        assertEquals(1_789_431_600_000L, p.epochMs)
        assertEquals(5_000_000_000L, p.elapsedRealtimeNs)
        assertEquals(35.680, p.latitude, 0.0)
        assertEquals(139.760, p.longitude, 0.0)
        assertEquals(40.5, p.altitude, 0.0)
        assertEquals(1.25f, p.speed, 0f)
        assertNotNull(p.dop)
        assertEquals(1.60, p.dop!!.pdop, 0.0)
        // v1 に無い列は既定値
        assertFalse(p.gapBefore)
        assertEquals(0, p.intervalSec)
        assertNull(p.pressureHpa)
    }

    @Test
    fun v2current_22columns_readsAllColumns() {
        val (track, skipped) = parse(
            v2bHeader,
            base(0) + ",false,4,1009.5",
            base(1) + ",true,4,"
        )
        assertEquals(0, skipped)
        assertEquals(1009.5f, track[0].pressureHpa!!, 0f)
        assertEquals(4, track[0].intervalSec)
        assertTrue(track[1].gapBefore)
        assertNull(track[1].pressureHpa)
    }

    @Test
    fun compass_25columns_readsCompassColumns() {
        val (track, skipped) = parse(
            compassHeader,
            base(0) + ",false,1,1009.5,131.2,12.0,-7.9",
            base(1) + ",false,1,1009.5,,,"          // 寝かせている・センサー無し
        )
        assertEquals(0, skipped)
        assertEquals(131.2f, track[0].compassAzimuthDeg!!, 0f)
        assertEquals(12.0f, track[0].compassAccuracyDeg!!, 0f)
        assertEquals(-7.9f, track[0].magneticDeclinationDeg!!, 0f)
        assertEquals(1009.5f, track[0].pressureHpa!!, 0f)
        assertNull(track[1].compassAzimuthDeg)
        assertNull(track[1].compassAccuracyDeg)
        assertNull(track[1].magneticDeclinationDeg)
    }

    @Test
    fun filesWithoutCompassColumns_readWithNullCompass() {
        // コンパスの 3 列が無い古いファイル（v1・v2）も読める
        for ((header, row) in listOf(v1Header to base(0), v2bHeader to base(0) + ",false,4,1009.5")) {
            val (track, skipped) = parse(header, row)
            assertEquals(0, skipped)
            assertEquals(1, track.size)
            assertNull(track[0].compassAzimuthDeg)
            assertNull(track[0].compassAccuracyDeg)
            assertNull(track[0].magneticDeclinationDeg)
        }
    }

    @Test
    fun emptyDop_givesNullDop() {
        val (track, _) = parse(v1Header, base(0, dop = false))
        assertNull(track[0].dop)
    }

    @Test
    fun columnsAreFoundByName_notByPosition() {
        // 列の順番が違っても、ヘッダーの名前で読む
        val header = "latitude,longitude,epoch_ms,pressure_hpa,gap_before"
        val (track, skipped) = parse(header, "35.1,139.2,1000,1011.5,true")
        assertEquals(0, skipped)
        assertEquals(35.1, track[0].latitude, 0.0)
        assertEquals(139.2, track[0].longitude, 0.0)
        assertEquals(1000L, track[0].epochMs)
        assertEquals(1011.5f, track[0].pressureHpa!!, 0f)
        assertTrue(track[0].gapBefore)
        // 無い列は既定値
        assertEquals(0L, track[0].elapsedRealtimeNs)
        assertEquals(0.0, track[0].altitude, 0.0)
    }

    @Test
    fun brokenRows_areSkippedAndCounted_blankLinesIgnored() {
        val (track, skipped) = parse(
            v2bHeader,
            base(0) + ",false,1,",
            "",
            "garbage",                                   // 列が足りない
            base(1).replace("35.681", "north") + ",false,1,", // 緯度が数値でない
            base(2) + ",false,1,"
        )
        assertEquals(2, track.size)
        assertEquals(2, skipped)
    }

    @Test
    fun unknownHeader_fallsBackToCommonLayout() {
        // ヘッダーが読めないときは、全形式で共通の先頭 19 列の並びとして読む
        val (track, skipped) = parse("foo,bar", base(0))
        assertEquals(0, skipped)
        assertEquals(1, track.size)
        assertEquals(35.680, track[0].latitude, 0.0)
    }

    @Test
    fun byteOrderMarkInHeader_isIgnored() {
        val (track, _) = parse("﻿" + v2bHeader, base(0) + ",true,1,1000")
        assertTrue(track[0].gapBefore)
        assertEquals(1000f, track[0].pressureHpa!!, 0f)
    }

    @Test
    fun emptyInput_givesNoPoints() {
        assertEquals(emptyList<TrackRecord>() to 0, parse())
        assertEquals(emptyList<TrackRecord>() to 0, parse(v2bHeader))
    }
}

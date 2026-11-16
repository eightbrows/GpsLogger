package io.github.eightbrows.gpslogger.calc

import io.github.eightbrows.gpslogger.settings.CompassNorth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.util.Locale

class CompassTest {

    private lateinit var savedLocale: Locale

    @Before
    fun useCommaDecimalLocale() {
        // 小数点がカンマの言語でも、CSV と表示は "." で出ること
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    // ---- 方位角の正規化 ----

    @Test
    fun normalize_keepsValuesInRange() {
        assertEquals(0f, Compass.normalizeDeg(0f), 0f)
        assertEquals(123.4f, Compass.normalizeDeg(123.4f), 0f)
        assertEquals(359.9f, Compass.normalizeDeg(359.9f), 0f)
    }

    @Test
    fun normalize_negativeValues() {
        assertEquals(270f, Compass.normalizeDeg(-90f), 0f)
        assertEquals(359f, Compass.normalizeDeg(-1f), 0f)
        assertEquals(180f, Compass.normalizeDeg(-180f), 0f)
        assertEquals(350f, Compass.normalizeDeg(-370f), 0f)
    }

    @Test
    fun normalize_360AndAbove() {
        assertEquals(0f, Compass.normalizeDeg(360f), 0f)
        assertEquals(10f, Compass.normalizeDeg(370f), 0f)
        assertEquals(1f, Compass.normalizeDeg(721f), 0f)
        assertEquals(0f, Compass.normalizeDeg(-360f), 0f)
    }

    @Test
    fun normalize_neverReturns360OrNegativeZero() {
        // 負のごく小さい値は +360 で 360.0 に丸まるが、0 として返す
        val r = Compass.normalizeDeg(-1e-7f)
        assertEquals(0f, r, 0f)
        assertEquals("0.0", r.toString())
        assertEquals("0.0", Compass.normalizeDeg(-0f).toString())
    }

    // ---- 真北の計算 ----

    @Test
    fun trueAzimuth_addsDeclination() {
        // 日本付近は西偏（マイナス）: 磁北 131 → 真北 123
        assertEquals(123f, Compass.trueAzimuthDeg(131f, -8f), 1e-4f)
        // 東偏（プラス）
        assertEquals(141f, Compass.trueAzimuthDeg(131f, 10f), 1e-4f)
    }

    @Test
    fun trueAzimuth_wrapsAroundNorth() {
        assertEquals(357f, Compass.trueAzimuthDeg(5f, -8f), 1e-4f)
        assertEquals(5f, Compass.trueAzimuthDeg(355f, 10f), 1e-4f)
        assertEquals(0f, Compass.trueAzimuthDeg(8f, -8f), 1e-4f)
    }

    // ---- 表示書式 ----

    @Test
    fun format_trueNorth() {
        assertEquals(
            "T123 deg (+/- 12)",
            Compass.format(131f, 12f, -8f, CompassNorth.TRUE_NORTH)
        )
    }

    @Test
    fun format_magneticNorth() {
        assertEquals(
            "M131 deg (+/- 12)",
            Compass.format(131f, 12f, -8f, CompassNorth.MAGNETIC_NORTH)
        )
        // 磁北表示は偏角が無くても出せる
        assertEquals("M131 deg (+/- 12)", Compass.format(131f, 12f, null, CompassNorth.MAGNETIC_NORTH))
    }

    @Test
    fun format_withoutAccuracy_omitsParentheses() {
        assertEquals("T123 deg", Compass.format(131f, null, -8f, CompassNorth.TRUE_NORTH))
        assertEquals("M131 deg", Compass.format(131f, null, -8f, CompassNorth.MAGNETIC_NORTH))
    }

    @Test
    fun format_withoutAzimuth_isDash() {
        assertEquals("—", Compass.format(null, 12f, -8f, CompassNorth.TRUE_NORTH))
        assertEquals("—", Compass.format(null, 12f, -8f, CompassNorth.MAGNETIC_NORTH))
    }

    @Test
    fun format_trueNorthWithoutDeclination_isDash() {
        assertEquals("—", Compass.format(131f, 12f, null, CompassNorth.TRUE_NORTH))
    }

    @Test
    fun format_roundsToWholeDegrees_andWrapsAt360() {
        assertEquals("M0 deg (+/- 3)", Compass.format(359.6f, 2.5f, null, CompassNorth.MAGNETIC_NORTH))
        assertEquals("T0 deg", Compass.format(7.7f, null, -8f, CompassNorth.TRUE_NORTH))
        assertEquals("M5 deg", Compass.format(4.5f, null, null, CompassNorth.MAGNETIC_NORTH))
    }

    // ---- 古い値・無効な値 ----

    @Test
    fun usable_dropsStaleOrInvalidReadings() {
        val now = 10_000_000_000L
        val fresh = CompassReading(90f, 5f, now - 1_000_000_000L)
        assertSame(fresh, Compass.usable(fresh, now))
        // ちょうど 3 秒はまだ使う。3 秒を超えたら無し
        val limit = CompassReading(90f, 5f, now - Compass.MAX_AGE_NS)
        assertSame(limit, Compass.usable(limit, now))
        assertNull(Compass.usable(CompassReading(90f, 5f, now - Compass.MAX_AGE_NS - 1), now))
        // 方位角が無い（寝かせている・信頼できない）
        assertNull(Compass.usable(CompassReading(null, 5f, now), now))
        assertNull(Compass.usable(null, now))
    }

    // ---- CSV の書式 ----

    @Test
    fun csv_oneDecimal_blankForNull() {
        assertEquals("131.3", Compass.csvAzimuth(131.26f))
        assertEquals("", Compass.csvAzimuth(null))
        assertEquals("-7.9", Compass.csv(-7.94f))
        assertEquals("12.0", Compass.csv(12f))
        assertEquals("", Compass.csv(null))
    }

    @Test
    fun csvAzimuth_neverWrites360() {
        assertEquals("0.0", Compass.csvAzimuth(359.97f))
        assertEquals("359.9", Compass.csvAzimuth(359.94f))
        assertEquals("0.0", Compass.csvAzimuth(360f))
    }
}

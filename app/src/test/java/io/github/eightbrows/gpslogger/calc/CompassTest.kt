package io.github.eightbrows.gpslogger.calc

import io.github.eightbrows.gpslogger.settings.CompassNorth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    // ---- 伏角・磁場の強さ（合成したベクトル） ----

    /** 端末を水平に置いた状態（端末の Z 軸が鉛直上向き） */
    private fun flat(bx: Float, by: Float, bz: Float) =
        Compass.measureMagnetic(bx, by, bz, 0f, 0f, 1f)!!

    @Test
    fun inclination_northernHemisphere_isPositive() {
        // 北向き水平 20 µT、下向き 40 µT → 伏角 atan(40/20) = 63.43 度
        val m = flat(0f, 20f, -40f)
        assertEquals(63.435f, m.inclinationDeg, 1e-3f)
        assertEquals(44.721f, m.strengthUt, 1e-3f)
    }

    @Test
    fun inclination_southernHemisphere_isNegative() {
        // 上向き 40 µT → 伏角はマイナス
        val m = flat(0f, 20f, 40f)
        assertEquals(-63.435f, m.inclinationDeg, 1e-3f)
        assertEquals(44.721f, m.strengthUt, 1e-3f)
    }

    @Test
    fun inclination_horizontalField_isZero() {
        val m = flat(18f, 24f, 0f)
        assertEquals(0f, m.inclinationDeg, 1e-4f)
        assertEquals(30f, m.strengthUt, 1e-4f)
    }

    @Test
    fun inclination_straightDownAndUp() {
        assertEquals(90f, flat(0f, 0f, -50f).inclinationDeg, 1e-3f)
        assertEquals(-90f, flat(0f, 0f, 50f).inclinationDeg, 1e-3f)
    }

    @Test
    fun inclination_doesNotDependOnDeviceAttitude() {
        // 端末を立てた状態（端末の Y 軸が鉛直上向き）。同じ磁場なら同じ伏角・強さ
        val upright = Compass.measureMagnetic(0f, -40f, -20f, 0f, 1f, 0f)!!
        assertEquals(63.435f, upright.inclinationDeg, 1e-3f)
        assertEquals(44.721f, upright.strengthUt, 1e-3f)
        // 斜め（上向きが Y と Z の中間）。下向き 30 µT ＋ 水平 30 µT → 45 度
        val s = (1.0 / Math.sqrt(2.0)).toFloat()
        val tilted = Compass.measureMagnetic(30f, -30f * s, -30f * s, 0f, s, s)!!
        assertEquals(45f, tilted.inclinationDeg, 1e-3f)
        // 上向きベクトルの長さが 1 でなくても結果は同じ
        val scaled = Compass.measureMagnetic(0f, 20f, -40f, 0f, 0f, 2f)!!
        assertEquals(63.435f, scaled.inclinationDeg, 1e-3f)
    }

    @Test
    fun measureMagnetic_invalidInput_isNull() {
        assertNull(Compass.measureMagnetic(0f, 0f, 0f, 0f, 0f, 1f))
        assertNull(Compass.measureMagnetic(10f, 0f, 0f, 0f, 0f, 0f))
        assertNull(Compass.measureMagnetic(Float.NaN, 0f, 0f, 0f, 0f, 1f))
    }

    // ---- モデル値との比較 ----

    private val tokyo = MagModel(declinationDeg = -8.2f, strengthUt = 46.5f, inclinationDeg = 49.8f)

    @Test
    fun magIndex_ratioAndInclinationDiff() {
        val i = Compass.magIndex(MagMeasurement(51.15f, 46.6f), tokyo)!!
        assertEquals(1.1f, i.ratio, 1e-4f)
        assertEquals(-3.2f, i.inclinationDiffDeg, 1e-4f)
        // 測定値がモデルと同じなら 1.0・0.0
        val same = Compass.magIndex(MagMeasurement(46.5f, 49.8f), tokyo)!!
        assertEquals(1f, same.ratio, 0f)
        assertEquals(0f, same.inclinationDiffDeg, 0f)
    }

    @Test
    fun magIndex_missingInput_isNull() {
        assertNull(Compass.magIndex(null, tokyo))
        assertNull(Compass.magIndex(MagMeasurement(46f, 50f), null))
        assertNull(Compass.magIndex(MagMeasurement(46f, 50f), tokyo.copy(strengthUt = 0f)))
    }

    // ---- 「磁気の乱れ」の判定（境界値） ----

    @Test
    fun isDisturbed_ratioBoundaries() {
        assertEquals(false, Compass.isDisturbed(Compass.MAG_RATIO_MIN, 0f))
        assertEquals(false, Compass.isDisturbed(Compass.MAG_RATIO_MAX, 0f))
        assertEquals(false, Compass.isDisturbed(1f, 0f))
        assertEquals(true, Compass.isDisturbed(0.79f, 0f))
        assertEquals(true, Compass.isDisturbed(1.31f, 0f))
        // 走行データの解析から決めた範囲: 比 0.8〜1.3
        assertEquals(0.8f, Compass.MAG_RATIO_MIN, 0f)
        assertEquals(1.3f, Compass.MAG_RATIO_MAX, 0f)
        assertEquals(false, Compass.isDisturbed(1.21f, 0f))
    }

    @Test
    fun isDisturbed_inclinationBoundaries() {
        val limit = Compass.MAG_INCLINATION_DIFF_MAX_DEG
        assertEquals(15f, limit, 0f)
        assertEquals(false, Compass.isDisturbed(1f, 10.1f))
        assertEquals(false, Compass.isDisturbed(1f, limit))
        assertEquals(false, Compass.isDisturbed(1f, -limit))
        assertEquals(true, Compass.isDisturbed(1f, limit + 0.1f))
        assertEquals(true, Compass.isDisturbed(1f, -limit - 0.1f))
        // 片方だけ外れていても乱れ
        assertEquals(true, Compass.isDisturbed(1.5f, 0f))
        assertEquals(true, Compass.isDisturbed(1f, 25f))
    }

    @Test
    fun isDisturbed_withoutValues_isFalse() {
        assertEquals(false, Compass.isDisturbed(null, null))
        assertEquals(false, Compass.isDisturbed(1.5f, null))
        assertEquals(false, Compass.isDisturbed(null, 25f))
    }

    @Test
    fun format_appendsNoteOnlyWhenAzimuthIsShown() {
        assertEquals(
            "T123 deg (+/- 12)  磁気の乱れ",
            Compass.format(131f, 12f, -8f, CompassNorth.TRUE_NORTH, note = "磁気の乱れ")
        )
        assertEquals(
            "M131 deg  mag. disturbed",
            Compass.format(131f, null, null, CompassNorth.MAGNETIC_NORTH, note = "mag. disturbed")
        )
        assertEquals("M131 deg", Compass.format(131f, null, null, CompassNorth.MAGNETIC_NORTH, note = null))
        // 方位角を出せないときは「—」だけ
        assertEquals("—", Compass.format(null, null, null, CompassNorth.MAGNETIC_NORTH, note = "磁気の乱れ"))
    }

    @Test
    fun magMeasurement_validWhenFlat_droppedWhenStale() {
        val now = 10_000_000_000L
        // 寝かせていて方位角が無くても、磁場の値は使える
        val flatReading = CompassReading(null, null, now - 1_000_000_000L, 46.1f, 50.2f)
        assertNull(Compass.usable(flatReading, now))
        assertEquals(MagMeasurement(46.1f, 50.2f), Compass.magMeasurement(flatReading, now))
        // 3 秒より古い・磁場の値が無い
        val stale = now - Compass.MAX_AGE_NS - 1
        assertNull(Compass.magMeasurement(CompassReading(null, null, stale, 46.1f, 50.2f), now))
        assertNull(Compass.magMeasurement(CompassReading(90f, 5f, now), now))
        assertNull(Compass.magMeasurement(null, now))
    }

    @Test
    fun csvRatio_twoDecimals_inclinationDiff_oneDecimalSigned() {
        assertEquals("1.03", Compass.csvRatio(1.034f))
        assertEquals("0.80", Compass.csvRatio(0.8f))
        assertEquals("", Compass.csvRatio(null))
        assertEquals("-3.2", Compass.csv(-3.24f))
        assertEquals("12.5", Compass.csv(12.46f))
    }

    // ---- 磁場と回転行列の時刻合わせ ----

    private val ms = 1_000_000L

    @Test
    fun pairer_pairsEventsWithin100ms_inEitherOrder() {
        val p = MagPairer()
        // 相手がまだ無い
        assertNull(p.onUp(1_000 * ms, 0f, 0f, 1f))
        // 磁場が 60 ms 後に届く → 組になる
        val m = p.onField(1_060 * ms, 0f, 20f, -40f)!!
        assertEquals(63.435f, m.inclinationDeg, 1e-3f)
        // 次の上向きが、その磁場の 90 ms 後 → 組になる
        assertEquals(44.721f, p.onUp(1_150 * ms, 0f, 0f, 1f)!!.strengthUt, 1e-3f)
    }

    @Test
    fun pairer_boundary_exactly100msPairs_over100msDoesNot() {
        val p = MagPairer()
        p.onField(2_000 * ms, 0f, 20f, -40f)
        assertEquals(63.435f, p.onUp(2_100 * ms, 0f, 0f, 1f)!!.inclinationDeg, 1e-3f)
        assertNull(p.onUp(2_100 * ms + 1, 0f, 0f, 1f))
        // 磁場のほうが後の場合も同じ
        val q = MagPairer()
        q.onUp(5_000 * ms, 0f, 0f, 1f)
        assertNull(q.onField(5_100 * ms + 1, 0f, 20f, -40f))
        assertEquals(63.435f, q.onField(5_100 * ms, 0f, 20f, -40f)!!.inclinationDeg, 1e-3f)
    }

    @Test
    fun pairer_usesLatestCounterpart_andStaleCounterpartDoesNotPair() {
        val p = MagPairer()
        p.onField(1_000 * ms, 0f, 20f, 40f)     // 古い磁場（伏角マイナス）
        p.onField(1_950 * ms, 0f, 20f, -40f)    // 新しい磁場（伏角プラス）
        assertEquals(63.435f, p.onUp(2_000 * ms, 0f, 0f, 1f)!!.inclinationDeg, 1e-3f)
        // 磁場が 1 秒届かない（1 Hz の磁場と組にしていた以前の状態）と、組にならない
        assertNull(p.onUp(2_950 * ms, 0f, 0f, 1f))
    }

    /** 2 つのセンサーを指定の間隔で 60 秒動かし、組が取れなかった回転行列の数を返す */
    private fun unpairedUps(fieldPeriodNs: Long, upPeriodNs: Long): Int {
        val p = MagPairer()
        // (時刻, 磁場か) を時刻順に並べる。磁場は 37 ms 遅れて始まる
        val events = ArrayList<Pair<Long, Boolean>>()
        var t = 37 * ms
        while (t < 60_000 * ms) { events += t to true; t += fieldPeriodNs }
        t = 0L
        while (t < 60_000 * ms) { events += t to false; t += upPeriodNs }
        events.sortBy { it.first }

        var unpaired = 0
        var pairedSinceUp = true
        for ((ts, isField) in events) {
            if (!isField) {
                if (!pairedSinceUp) unpaired++
                pairedSinceUp = false
            }
            val m = if (isField) p.onField(ts, 0f, 20f, -40f) else p.onUp(ts, 0f, 0f, 1f)
            if (m != null) pairedSinceUp = true
        }
        return unpaired
    }

    @Test
    fun pairer_fieldAt100ms_alwaysFindsPair_evenWhenPeriodsDrift() {
        // 実際の間隔が指定と少し違っても（磁場 104 ms、回転行列 203 ms）、毎回組が取れる
        assertEquals(0, unpairedUps(fieldPeriodNs = 104 * ms, upPeriodNs = 203 * ms))
        assertEquals(0, unpairedUps(fieldPeriodNs = 100 * ms, upPeriodNs = 1_000 * ms))
    }

    @Test
    fun pairer_fieldAt200ms_missesPairsWhenPeriodsDrift() {
        // 磁場も 200 ms 程度だと、位相がずれる時間帯に組が取れない（100 ms にした理由）
        assertTrue(unpairedUps(fieldPeriodNs = 204 * ms, upPeriodNs = 203 * ms) > 0)
    }

    @Test
    fun pairer_matchesByEventTime_evenWhenOneSideArrivesLate() {
        // 回転行列だけ 250 ms 遅れて届く端末。届いた時点の「最新の磁場」は 200 ms 以上先の値だが、
        // イベント時刻の近い磁場（10 ms 差）と組にする
        val p = MagPairer()
        assertNull(p.onField(1_000 * ms, 0f, 20f, 40f))
        assertNull(p.onField(1_100 * ms, 0f, 20f, -40f))   // これが時刻の合う磁場（伏角プラス）
        assertNull(p.onField(1_200 * ms, 0f, 20f, 40f))
        assertNull(p.onField(1_300 * ms, 0f, 20f, 40f))
        assertEquals(63.435f, p.onUp(1_110 * ms, 0f, 0f, 1f)!!.inclinationDeg, 1e-3f)
    }

    @Test
    fun pairer_keepsOnlyRecentValues() {
        // 直近 4 件より古い磁場は忘れる
        val p = MagPairer(capacity = 4)
        p.onField(1_000 * ms, 0f, 20f, -40f)
        for (i in 1..4) p.onField((2_000 + i * 100) * ms, 0f, 20f, 40f)
        assertNull(p.onUp(1_010 * ms, 0f, 0f, 1f))
    }

    @Test
    fun pairer_reset_forgetsBothSides() {
        val p = MagPairer()
        p.onField(1_000 * ms, 0f, 20f, -40f)
        p.reset()
        assertNull(p.onUp(1_010 * ms, 0f, 0f, 1f))
        p.reset()
        assertNull(p.onField(1_020 * ms, 0f, 20f, -40f))
    }

    @Test
    fun magMeasurement_usesItsOwnTimestamp() {
        val now = 10_000_000_000L
        // 方位は今の値だが、磁場は組が取れず 4 秒前の値を持ち越している → 磁場だけ「無し」
        val carried = CompassReading(90f, null, now, 46.1f, 50.2f, magElapsedRealtimeNs = now - 4_000_000_000L)
        assertSame(carried, Compass.usable(carried, now))
        assertNull(Compass.magMeasurement(carried, now))
        // 2 秒前の値ならまだ使う
        val recent = carried.copy(magElapsedRealtimeNs = now - 2_000_000_000L)
        assertEquals(MagMeasurement(46.1f, 50.2f), Compass.magMeasurement(recent, now))
    }

    // ---- 磁気センサーの状態 ----

    @Test
    fun magSensorStatus_freshValueInRange() {
        val now = 10_000_000_000L
        for (status in 0..3) {
            assertEquals(status, Compass.magSensorStatus(CompassReading(null, null, now, magSensorStatus = status), now))
        }
        // 範囲外（-1 = 接触なし など）・未取得・古い値は無し
        assertNull(Compass.magSensorStatus(CompassReading(null, null, now, magSensorStatus = -1), now))
        assertNull(Compass.magSensorStatus(CompassReading(null, null, now, magSensorStatus = 4), now))
        assertNull(Compass.magSensorStatus(CompassReading(90f, 5f, now), now))
        assertNull(Compass.magSensorStatus(CompassReading(null, null, now - Compass.MAX_AGE_NS - 1, magSensorStatus = 3), now))
        assertNull(Compass.magSensorStatus(null, now))
    }

    @Test
    fun csvStatus_integer_blankForNull() {
        assertEquals("0", Compass.csvStatus(0))
        assertEquals("2", Compass.csvStatus(2))
        assertEquals("3", Compass.csvStatus(3))
        assertEquals("", Compass.csvStatus(null))
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

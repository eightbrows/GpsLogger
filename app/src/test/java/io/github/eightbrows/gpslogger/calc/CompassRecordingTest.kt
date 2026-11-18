package io.github.eightbrows.gpslogger.calc

import io.github.eightbrows.gpslogger.log.LogWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** コンパス（試験機能）のオン・オフと、記録に使う値の選び方 */
class CompassRecordingTest {

    private val model = MagModel(declinationDeg = -8f, strengthUt = 46f, inclinationDeg = 50f)
    private val now = 100_000_000_000L
    private val minIntervalNs = 900_000_000L
    private val stepNs = 200_000_000L

    /** 端末の起動からの時刻（elapsedRealtimeNanos）は、最初の値が届く時点で十分大きい */
    private val startNs = 50_000_000_000L

    private fun fresh() = CompassReading(
        azimuthDeg = 131.26f,
        accuracyDeg = 12f,
        elapsedRealtimeNs = now - 100_000_000L,
        magStrengthUt = 46f,
        magInclinationDeg = 53f,
        magElapsedRealtimeNs = now - 100_000_000L,
        magSensorStatus = 3
    )

    // ---- オフのとき ----

    @Test
    fun disabled_allValuesAreEmpty_andModelIsNotUsed() {
        val record = Compass.recordValues(false, fresh(), now) {
            fail("オフのときはモデル値を計算しない")
            model
        }
        assertEquals(CompassRecord.EMPTY, record)
    }

    @Test
    fun disabled_columnsAreBlank() {
        assertEquals(",,,,,", Compass.csvColumns(CompassRecord.EMPTY))
    }

    @Test
    fun header_keepsCompassColumns_andMatchesColumnCount() {
        val header = LogWriter.TRACK_HEADER.split(",")
        assertEquals(28, header.size)
        assertEquals(
            listOf(
                "compass_azimuth_deg", "compass_accuracy_deg", "magnetic_declination_deg",
                "mag_field_ratio", "mag_inclination_diff_deg", "mag_sensor_status"
            ),
            header.takeLast(6)
        )
        // オフでもオンでも、6 列分の区切りになる
        assertEquals(6, Compass.csvColumns(CompassRecord.EMPTY).split(",").size)
        val enabled = Compass.recordValues(true, fresh(), now) { model }
        assertEquals(6, Compass.csvColumns(enabled).split(",").size)
    }

    // ---- オンのとき ----

    @Test
    fun enabled_writesSameFormatAsBefore() {
        val record = Compass.recordValues(true, fresh(), now) { model }
        assertEquals("131.3,12.0,-8.0,1.00,3.0,3", Compass.csvColumns(record))
    }

    @Test
    fun enabled_withoutAzimuth_keepsMagneticIndicators() {
        val flat = fresh().copy(azimuthDeg = null, accuracyDeg = null)
        val record = Compass.recordValues(true, flat, now) { model }
        assertEquals(",,,1.00,3.0,3", Compass.csvColumns(record))
    }

    @Test
    fun enabled_staleValuesBecomeBlank() {
        val old = now - Compass.MAX_AGE_NS - 1
        val stale = fresh().copy(elapsedRealtimeNs = old, magElapsedRealtimeNs = old)
        val record = Compass.recordValues(true, stale, now) {
            fail("使える値が無いときはモデル値を計算しない")
            model
        }
        assertEquals(",,,,,", Compass.csvColumns(record))
    }

    @Test
    fun enabled_exactlyThreeSecondsOld_isStillUsed() {
        val edge = now - Compass.MAX_AGE_NS
        val reading = fresh().copy(elapsedRealtimeNs = edge, magElapsedRealtimeNs = edge)
        val record = Compass.recordValues(true, reading, now) { model }
        assertEquals(131.26f, record.azimuthDeg!!, 0f)
        assertNotNull(record.fieldRatio)
    }

    // ---- 記録に使う値の選び方 ----

    /** 200 ms ごとに方位が届き、画面用は 1 秒ごとに間引かれる */
    private fun feed(samples: CompassSamples, count: Int): List<CompassReading> {
        val published = mutableListOf<CompassReading>()
        for (i in 1..count) {
            samples.onRotation(i.toFloat(), null, startNs + i * stepNs, minIntervalNs)?.let { published += it }
        }
        return published
    }

    @Test
    fun latest_isNewestValue_whilePublishedOnesAreThinned() {
        val samples = CompassSamples()
        val published = feed(samples, 8)
        // 画面用は 1, 6 回目だけ（200 ms × 5 = 1 秒ごと）
        assertEquals(listOf(1f, 6f), published.map { it.azimuthDeg })
        // 記録用は 8 回目（間引かれた値）
        val latest = samples.latest()!!
        assertEquals(8f, latest.azimuthDeg!!, 0f)
        assertEquals(startNs + 8 * stepNs, latest.elapsedRealtimeNs)
    }

    @Test
    fun latest_usesNewestPairedMagnetic_andKeepsItsOwnTime() {
        val samples = CompassSamples()
        samples.onMagnetic(MagMeasurement(40f, 45f), 150_000_000L)
        samples.onMagneticStatus(2)
        feed(samples, 2)
        samples.onMagnetic(MagMeasurement(47f, 51f), 350_000_000L)
        // 時刻の合う組が取れなかった回（null）は、前の組を測った時刻ごと保つ
        samples.onMagnetic(null, 390_000_000L)
        samples.onMagneticStatus(3)

        val latest = samples.latest()!!
        assertEquals(47f, latest.magStrengthUt!!, 0f)
        assertEquals(51f, latest.magInclinationDeg!!, 0f)
        assertEquals(350_000_000L, latest.magElapsedRealtimeNs)
        assertEquals(3, latest.magSensorStatus)
    }

    @Test
    fun latest_isNullUntilRotationArrives_andAfterReset() {
        val samples = CompassSamples()
        samples.onMagnetic(MagMeasurement(46f, 50f), 1L)
        assertNull(samples.latest())
        feed(samples, 1)
        assertNotNull(samples.latest())
        samples.reset()
        assertNull(samples.latest())
    }

    @Test
    fun clearAzimuth_appliesToLatestImmediately() {
        val samples = CompassSamples()
        feed(samples, 2)
        samples.clearAzimuth()
        assertNull(samples.latest()!!.azimuthDeg)
    }

    @Test
    fun latestValueThatStoppedUpdating_becomesBlankAfterThreeSeconds() {
        val samples = CompassSamples()
        feed(samples, 1)
        val arrived = startNs + stepNs
        val inTime = Compass.recordValues(true, samples.latest(), arrived + Compass.MAX_AGE_NS) { model }
        assertNotNull(inTime.azimuthDeg)
        val late = Compass.recordValues(true, samples.latest(), arrived + Compass.MAX_AGE_NS + 1) { model }
        assertNull(late.azimuthDeg)
    }
}

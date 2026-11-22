package io.github.eightbrows.gpslogger.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepsTest {

    private fun spec(wakeUp: Boolean = false) = StepSensorSpec(
        name = "Step Detector", vendor = "AOSP", version = 1, wakeUp = wakeUp,
        reportingMode = 2, minDelayUs = 0, maxDelayUs = 0,
        fifoReservedEventCount = 0, fifoMaxEventCount = 0
    )

    // ---- steps.csv の書式 ----

    @Test
    fun header_isFixed() {
        assertEquals("type,event_elapsed_ns,recv_elapsed_ns,epoch_ms,count", Steps.HEADER)
        assertEquals("steps.csv", Steps.FILE_NAME)
    }

    @Test
    fun csvLine_detectorHasBlankCount() {
        val s = Steps.sample(StepSensorType.DETECTOR, 5_000_000_000L, 5_120_000_000L, 1_795_000_000_000L, 1f)
        assertNull(s.count)
        assertEquals("detector,5000000000,5120000000,1794999999880,", Steps.csvLine(s))
    }

    @Test
    fun csvLine_counterHasIntegerCount() {
        val s = Steps.sample(StepSensorType.COUNTER, 5_000_000_000L, 5_000_500_000L, 1_795_000_000_000L, 12345f)
        assertEquals(12345L, s.count)
        assertEquals("counter,5000000000,5000500000,1795000000000,12345", Steps.csvLine(s))
        // 列の数はヘッダーと同じ
        assertEquals(Steps.HEADER.split(",").size, Steps.csvLine(s).split(",").size)
    }

    // ---- 壁時計への換算 ----

    @Test
    fun epochMs_subtractsDeliveryDelay() {
        // 受け取りが 2.5 秒遅れ（画面オフ中にためられていた）
        assertEquals(1_000_000L - 2_500L, Steps.epochMs(10_000_000_000L, 12_500_000_000L, 1_000_000L))
        // 遅れ 0
        assertEquals(1_000_000L, Steps.epochMs(10_000_000_000L, 10_000_000_000L, 1_000_000L))
        // ms 未満の遅れは切り捨て
        assertEquals(1_000_000L, Steps.epochMs(10_000_000_000L, 10_000_999_999L, 1_000_000L))
        // 遅れが負（時刻基準の異常）でも同じ式で換算する
        assertEquals(1_000_000L + 3_000L, Steps.epochMs(10_000_000_000L, 7_000_000_000L, 1_000_000L))
    }

    // ---- 時刻基準の異常 ----

    @Test
    fun timebaseAnomaly_negativeOrOverSixtySeconds() {
        val event = 100_000_000_000L
        assertFalse(Steps.isTimebaseAnomaly(event, event))
        assertFalse(Steps.isTimebaseAnomaly(event, event + 60_000_000_000L))
        assertTrue(Steps.isTimebaseAnomaly(event, event + 60_000_000_001L))
        assertTrue(Steps.isTimebaseAnomaly(event, event - 1))
        // 起動からの時間と関係ない基準（例: 壁時計の ns）なら大きく外れる
        assertTrue(Steps.isTimebaseAnomaly(1_795_000_000_000_000_000L, event))
    }

    // ---- 購読の計画（設定・権限・センサー） ----

    @Test
    fun plan_disabledComesFirst() {
        val p = Steps.plan(enabled = false, permissionGranted = false, detector = spec(), counter = spec())
        assertEquals(StepsStatus.DISABLED, p.status)
        assertFalse(p.record)
        assertFalse(p.useDetector || p.useCounter)
    }

    @Test
    fun plan_noPermission() {
        val p = Steps.plan(enabled = true, permissionGranted = false, detector = spec(), counter = spec())
        assertEquals(StepsStatus.NO_PERMISSION, p.status)
        assertFalse(p.record)
    }

    @Test
    fun plan_noSensor() {
        val p = Steps.plan(enabled = true, permissionGranted = true, detector = null, counter = null)
        assertEquals(StepsStatus.NO_SENSOR, p.status)
        assertFalse(p.record)
    }

    @Test
    fun plan_wakeUpOnlySensorsAreNotUsed() {
        val p = Steps.plan(true, true, detector = spec(wakeUp = true), counter = spec(wakeUp = true))
        assertEquals(StepsStatus.NO_SENSOR, p.status)
    }

    @Test
    fun plan_usesEachAvailableSensor() {
        val both = Steps.plan(true, true, spec(), spec())
        assertEquals(StepsStatus.RECORDED, both.status)
        assertTrue(both.record && both.useDetector && both.useCounter)

        val counterOnly = Steps.plan(true, true, detector = null, counter = spec())
        assertTrue(counterOnly.record)
        assertFalse(counterOnly.useDetector)
        assertTrue(counterOnly.useCounter)

        val detectorOnly = Steps.plan(true, true, detector = spec(), counter = spec(wakeUp = true))
        assertTrue(detectorOnly.useDetector)
        assertFalse(detectorOnly.useCounter)
    }

    @Test
    fun maxReportLatency_isZero() {
        assertEquals(0, Steps.MAX_REPORT_LATENCY_US)
    }
}

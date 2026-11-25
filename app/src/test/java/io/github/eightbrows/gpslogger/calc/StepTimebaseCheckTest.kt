package io.github.eightbrows.gpslogger.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** counter の基準値（登録ごとの最初の 1 件）を、時刻基準の異常として数えないこと */
class StepTimebaseCheckTest {

    private val recv = 5_000_000_000_000L
    private val sec = 1_000_000_000L

    /** 受け取った時刻より [ago] 秒前のイベント */
    private fun StepTimebaseCheck.check(type: StepSensorType, ago: Long) =
        isAnomaly(type, recv - ago * sec, recv)

    @Test
    fun firstCounterAfterStart_isNotAnomaly_evenIfOld() {
        val c = StepTimebaseCheck().apply { onRegistered() }
        // 207 秒前（実機で見られた基準値）
        assertFalse(c.check(StepSensorType.COUNTER, 207))
    }

    @Test
    fun laterCountersOverSixtySeconds_areStillAnomalies() {
        val c = StepTimebaseCheck().apply { onRegistered() }
        assertFalse(c.check(StepSensorType.COUNTER, 207))   // 基準値
        assertFalse(c.check(StepSensorType.COUNTER, 3))     // 通常の遅れ
        assertTrue(c.check(StepSensorType.COUNTER, 61))
        assertTrue(c.check(StepSensorType.COUNTER, 120))
        // 負の遅れも今までどおり
        assertTrue(c.isAnomaly(StepSensorType.COUNTER, recv + 1, recv))
    }

    @Test
    fun firstCounterAfterResume_isNotAnomaly() {
        val c = StepTimebaseCheck().apply { onRegistered() }
        assertFalse(c.check(StepSensorType.COUNTER, 90))    // 記録開始時の基準値
        assertTrue(c.check(StepSensorType.COUNTER, 90))     // 2 件目は数える
        // 一時停止 → 再開で登録し直す
        c.onRegistered()
        assertFalse(c.check(StepSensorType.COUNTER, 300))   // 再開時の基準値
        assertTrue(c.check(StepSensorType.COUNTER, 300))
    }

    @Test
    fun detectorBeforeCounter_doesNotConsumeBaseline() {
        val c = StepTimebaseCheck().apply { onRegistered() }
        // 登録直後に detector が先に届いても、counter の最初の 1 件は基準値のまま
        assertFalse(c.check(StepSensorType.DETECTOR, 1))
        assertFalse(c.check(StepSensorType.COUNTER, 207))
        assertTrue(c.check(StepSensorType.COUNTER, 207))
    }

    @Test
    fun detector_isJudgedAsBefore() {
        val c = StepTimebaseCheck().apply { onRegistered() }
        // 登録直後の detector の最初の 1 件も、除外しない
        assertTrue(c.check(StepSensorType.DETECTOR, 61))
        assertFalse(c.check(StepSensorType.DETECTOR, 60))
        assertFalse(c.check(StepSensorType.DETECTOR, 0))
        assertTrue(c.isAnomaly(StepSensorType.DETECTOR, recv + 1, recv))
        c.onRegistered()
        assertTrue(c.check(StepSensorType.DETECTOR, 61))
        // Steps.isTimebaseAnomaly と同じ結果
        for (ago in listOf(-1L, 0L, 1L, 59L, 60L, 61L, 600L)) {
            assertEquals(
                Steps.isTimebaseAnomaly(recv - ago * sec, recv),
                c.check(StepSensorType.DETECTOR, ago)
            )
        }
    }
}

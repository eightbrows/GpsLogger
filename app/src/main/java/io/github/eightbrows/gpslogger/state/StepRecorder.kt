package io.github.eightbrows.gpslogger.state

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import io.github.eightbrows.gpslogger.calc.StepSample
import io.github.eightbrows.gpslogger.calc.StepSensorSpec
import io.github.eightbrows.gpslogger.calc.StepSensorType
import io.github.eightbrows.gpslogger.calc.StepTimebaseCheck
import io.github.eightbrows.gpslogger.calc.Steps
import io.github.eightbrows.gpslogger.calc.StepsPlan
import io.github.eightbrows.gpslogger.session.StepSensorMeta

/**
 * 歩数センサー（TYPE_STEP_DETECTOR / TYPE_STEP_COUNTER）の記録用の購読。記録中のサービスだけが使う。
 *
 * getDefaultSensor の既定（wake-up でないセンサー）だけを使い、1 歩ごとに CPU を起こさない。
 * 画面オフ中の値はセンサー側にため込まれ、CPU が起きたときにまとめて届く前提。
 * 届いた値は判定・加工せず、そのまま [onSample] に渡す。
 * start / stop はメインスレッドから呼ぶこと。
 */
class StepRecorder(context: Context) {

    private val sensorManager: SensorManager? =
        context.applicationContext.getSystemService(SensorManager::class.java)

    private val detector: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val counter: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    /** センサーの諸元。無ければ null */
    val detectorSpec: StepSensorSpec? = detector?.let(::specOf)
    val counterSpec: StepSensorSpec? = counter?.let(::specOf)

    /** 1 件ごとに呼ぶ（メインスレッド） */
    var onSample: ((StepSample) -> Unit)? = null

    private var registered = false
    private val events = LongArray(StepSensorType.entries.size)
    private val anomalies = LongArray(StepSensorType.entries.size)

    /** 時刻基準の異常の判定（登録ごとの counter の基準値は数えない） */
    private val timebaseCheck = StepTimebaseCheck()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val recvNs = SystemClock.elapsedRealtimeNanos()
            val recvEpochMs = System.currentTimeMillis()
            val type = when (event.sensor.type) {
                Sensor.TYPE_STEP_DETECTOR -> StepSensorType.DETECTOR
                Sensor.TYPE_STEP_COUNTER -> StepSensorType.COUNTER
                else -> return
            }
            val sample = Steps.sample(
                type, event.timestamp, recvNs, recvEpochMs, event.values.getOrElse(0) { 0f }
            )
            events[type.ordinal]++
            if (timebaseCheck.isAnomaly(type, event.timestamp, recvNs)) {
                anomalies[type.ordinal]++
                // 毎歩出さないよう、最初の数件だけ詳しく出す（件数は meta.json に残る）
                if (anomalies[type.ordinal] <= ANOMALY_LOG_LIMIT) {
                    Log.w(
                        TAG,
                        "step timestamp may not be elapsedRealtime-based: ${type.csvName} " +
                            "event=${event.timestamp} recv=$recvNs delay=${recvNs - event.timestamp}ns"
                    )
                }
            }
            onSample?.invoke(sample)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** 計画どおりに購読する。すでに購読中なら何もしない（一時停止からの再開でも呼ぶ） */
    fun start(plan: StepsPlan) {
        if (!plan.record || registered) return
        val sm = sensorManager ?: return
        // 登録し直すたびに counter は基準値を送ってくるので、その 1 件を異常の判定から外す
        timebaseCheck.onRegistered()
        if (plan.useDetector) detector?.let { register(sm, it) }
        if (plan.useCounter) counter?.let { register(sm, it) }
        registered = true
    }

    private fun register(sm: SensorManager, sensor: Sensor) {
        val ok = sm.registerListener(
            listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, Steps.MAX_REPORT_LATENCY_US
        )
        Log.d(TAG, "step sensor registered: ${sensor.name} ok=$ok")
    }

    /** 購読を解除する。件数は保つ（記録の終わりに meta.json へ書く） */
    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(listener)
        registered = false
        Log.d(TAG, "step sensors stopped")
    }

    /** meta.json に書くセンサーごとの状態 */
    fun sensorMeta(type: StepSensorType, used: Boolean): StepSensorMeta = StepSensorMeta.of(
        spec = if (type == StepSensorType.DETECTOR) detectorSpec else counterSpec,
        used = used,
        events = events[type.ordinal],
        timebaseAnomalies = anomalies[type.ordinal]
    )

    private fun specOf(s: Sensor) = StepSensorSpec(
        name = s.name.orEmpty(),
        vendor = s.vendor.orEmpty(),
        version = s.version,
        wakeUp = s.isWakeUpSensor,
        reportingMode = s.reportingMode,
        minDelayUs = s.minDelay,
        maxDelayUs = s.maxDelay,
        fifoReservedEventCount = s.fifoReservedEventCount,
        fifoMaxEventCount = s.fifoMaxEventCount
    )

    private companion object {
        const val TAG = "StepRecorder"
        const val ANOMALY_LOG_LIMIT = 3L
    }
}

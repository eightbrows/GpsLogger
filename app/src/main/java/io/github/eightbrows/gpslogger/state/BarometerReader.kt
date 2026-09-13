package io.github.eightbrows.gpslogger.state

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 気圧センサーを購読し、最新の生値（hPa）を公開する。
 * センサー非搭載・誰も購読していないときは pressureHpa が null。
 *
 * 記録中はサービス、記録していないときは画面が購読する。
 * 片方の stop で相手の購読まで止まらないよう、購読者ごとに管理する。
 * start / stop はメインスレッドから呼ぶこと。
 */
object BarometerReader {

    /** 標準大気の海面気圧（hPa）。基準気圧は meta.json 対応までこの固定値 */
    const val STANDARD_PRESSURE_HPA = 1013.25f

    private val owners = mutableSetOf<Any>()
    private var sensorManager: SensorManager? = null

    private val _pressureHpa = MutableStateFlow<Float?>(null)
    val pressureHpa: StateFlow<Float?> = _pressureHpa.asStateFlow()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            _pressureHpa.value = event.values[0]
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** owner の購読を開始する。最初の購読者のときだけセンサーを登録する */
    fun start(context: Context, owner: Any) {
        owners.add(owner)
        if (sensorManager != null) return

        // シングルトンが Service / Activity を握らないよう applicationContext を使う
        val sm = context.applicationContext.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (sensor == null) {
            Log.d(TAG, "pressure sensor not available")
            return
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager = sm
        Log.d(TAG, "barometer started: ${sensor.name}")
    }

    /** owner の購読を終了する。購読者がいなくなったらセンサーを解除する */
    fun stop(owner: Any) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        sensorManager?.let {
            it.unregisterListener(listener)
            Log.d(TAG, "barometer stopped")
        }
        sensorManager = null
        // 止めた後に古い値を記録・表示しないよう戻す
        _pressureHpa.value = null
    }

    /** 気圧から高度（m）を求める。p0 は基準気圧（hPa） */
    fun altitudeM(pressureHpa: Float, p0: Float = STANDARD_PRESSURE_HPA): Float =
        SensorManager.getAltitude(p0, pressureHpa)

    private const val TAG = "BarometerReader"
}

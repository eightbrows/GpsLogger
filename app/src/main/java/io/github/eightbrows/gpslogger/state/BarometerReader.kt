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

    /** 標準大気の海面気圧（hPa）。meta.json が無いときの基準気圧 */
    const val STANDARD_PRESSURE_HPA = 1013.25f

    /** 購読者 → 希望するサンプリング周期（マイクロ秒） */
    private val owners = mutableMapOf<Any, Int>()
    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null

    /** 今センサーに登録している周期（マイクロ秒）。未登録なら null */
    private var registeredPeriodUs: Int? = null

    private val _pressureHpa = MutableStateFlow<Float?>(null)
    val pressureHpa: StateFlow<Float?> = _pressureHpa.asStateFlow()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            _pressureHpa.value = event.values[0]
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * owner の購読を開始する。
     *
     * [samplingPeriodUs] は希望するサンプリング周期（マイクロ秒）。
     * 使うのは測位時点の最新値だけなので、取りすぎて捨てずに済む周期を呼び出し側が決める。
     * 購読者が複数いるときは、いちばん細かい周期に合わせる（全員が必要な分を受け取れる）。
     * 実際の周期はセンサーの対応範囲に丸められる。
     */
    fun start(context: Context, owner: Any, samplingPeriodUs: Int = DEFAULT_PERIOD_US) {
        owners[owner] = samplingPeriodUs

        if (sensorManager == null) {
            // シングルトンが Service / Activity を握らないよう applicationContext を使う
            val sm = context.applicationContext.getSystemService(SensorManager::class.java) ?: return
            val s = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
            if (s == null) {
                Log.d(TAG, "pressure sensor not available")
                return
            }
            sensorManager = sm
            sensor = s
        }
        applyPeriod()
    }

    /** 周期の指定が無いときは1秒ごと */
    const val DEFAULT_PERIOD_US = 1_000_000

    /** owner の購読を終了する。購読者がいなくなったらセンサーを解除する */
    fun stop(owner: Any) {
        if (owners.remove(owner) == null) return
        if (owners.isNotEmpty()) {
            applyPeriod()   // 残った購読者の周期に合わせ直す
            return
        }
        sensorManager?.let {
            it.unregisterListener(listener)
            Log.d(TAG, "barometer stopped")
        }
        sensorManager = null
        sensor = null
        registeredPeriodUs = null
        // 止めた後に古い値を記録・表示しないよう戻す
        _pressureHpa.value = null
    }

    /** 必要な周期が変わったときだけ登録し直す（同じリスナーの二重登録は避ける） */
    private fun applyPeriod() {
        val sm = sensorManager ?: return
        val s = sensor ?: return
        val periodUs = owners.values.minOrNull() ?: return
        if (periodUs == registeredPeriodUs) return

        if (registeredPeriodUs != null) sm.unregisterListener(listener)
        sm.registerListener(listener, s, periodUs)
        registeredPeriodUs = periodUs
        Log.d(TAG, "barometer started: ${s.name} @${periodUs}us")
    }

    /** 気圧から高度（m）を求める。p0 は基準気圧（hPa） */
    fun altitudeM(pressureHpa: Float, p0: Float = STANDARD_PRESSURE_HPA): Float =
        SensorManager.getAltitude(p0, pressureHpa)

    private const val TAG = "BarometerReader"
}

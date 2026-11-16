package io.github.eightbrows.gpslogger.state

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import io.github.eightbrows.gpslogger.calc.Compass
import io.github.eightbrows.gpslogger.calc.CompassReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos

/**
 * 地磁気回転ベクトル（TYPE_GEOMAGNETIC_ROTATION_VECTOR、ジャイロ不使用）を購読し、
 * 端末の背面が向いている方位（磁北基準）の最新値を公開する。
 * 端末は車載ホルダーなどに立てて使う前提で、立っていないときは方位角を出さない。
 * センサー非搭載・誰も購読していないときは reading が null（別のセンサーへの切り替えはしない）。
 *
 * 作りは BarometerReader と同じ。記録中はサービス、記録していないときは画面が購読する。
 * 片方の stop で相手の購読まで止まらないよう、購読者ごとに管理する。
 * start / stop はメインスレッドから呼ぶこと。
 */
object CompassReader {

    /** 購読者 → 希望するサンプリング周期（マイクロ秒） */
    private val owners = mutableMapOf<Any, Int>()
    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null

    /** 今センサーに登録している周期（マイクロ秒）。未登録なら null */
    private var registeredPeriodUs: Int? = null

    /** センサーが「信頼できない」と知らせてきている間は方位角を出さない */
    private var unreliable = false

    /** 最後に採用した値の取得時刻（間引き用）。未取得なら 0 */
    private var lastAcceptedNs = 0L

    private val _reading = MutableStateFlow<CompassReading?>(null)

    /**
     * 最新値（方位角・精度・取得時刻）。古い値も入ったままなので、
     * 使うときは [Compass.usable] か [current] で 3 秒より古い値を落とすこと。
     */
    val reading: StateFlow<CompassReading?> = _reading.asStateFlow()

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // センサーの最低レートが希望より速い端末では、希望より細かく届く
            // （例: 最低 5 Hz の端末では 1 秒を指定しても 200 ms ごと）。
            // 使うのは測位時点の最新値だけなので、希望の周期まで間引いて画面の更新を増やさない
            val now = SystemClock.elapsedRealtimeNanos()
            val minIntervalNs = (registeredPeriodUs ?: 0) * THROTTLE_NS_PER_US
            if (now - lastAcceptedNs < minIntervalNs) return
            lastAcceptedNs = now

            val values = event.values
            SensorManager.getRotationMatrixFromVector(rotation, values)

            // rotation[7] = 端末の Y 軸（上端方向）の鉛直上向き成分 = なす角の余弦
            val upright = rotation[7] >= MIN_UPRIGHT_COS
            val azimuth = if (upright && !unreliable) {
                // 立てた状態に合わせて軸を入れ替え、背面の向きを方位角として取り出す
                SensorManager.remapCoordinateSystem(
                    rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped
                )
                SensorManager.getOrientation(remapped, orientation)
                Compass.normalizeDeg(Math.toDegrees(orientation[0].toDouble()).toFloat())
            } else null

            // 5 要素目は推定誤差（ラジアン）。出さない端末は要素が無いか負の値
            val accuracy = values.getOrNull(4)
                ?.takeIf { it >= 0f }
                ?.let { Math.toDegrees(it.toDouble()).toFloat() }

            _reading.value = CompassReading(azimuth, accuracy, now)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d(TAG, "compass accuracy status: $accuracy")
            unreliable = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
            if (unreliable) {
                // 次の値を待たずに、今の方位角を無効にする
                _reading.value = _reading.value?.copy(azimuthDeg = null)
            }
        }
    }

    /** 記録・表示に使える最新値。方位角が無い・3 秒より古い・購読していないときは null */
    fun current(): CompassReading? =
        Compass.usable(_reading.value, SystemClock.elapsedRealtimeNanos())

    /**
     * その地点・時刻の偏角（度、東偏がプラス）。
     * [altitudeM] は楕円体高、[timeMs] は測位時刻。
     */
    fun declinationDeg(latitude: Double, longitude: Double, altitudeM: Double, timeMs: Long): Float =
        GeomagneticField(
            latitude.toFloat(), longitude.toFloat(), altitudeM.toFloat(), timeMs
        ).declination

    /**
     * owner の購読を開始する。
     * 購読者が複数いるときは、いちばん細かい周期に合わせる（BarometerReader と同じ）。
     */
    fun start(context: Context, owner: Any, samplingPeriodUs: Int = DEFAULT_PERIOD_US) {
        owners[owner] = samplingPeriodUs

        if (sensorManager == null) {
            // シングルトンが Service / Activity を握らないよう applicationContext を使う
            val sm = context.applicationContext.getSystemService(SensorManager::class.java) ?: return
            val s = sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            if (s == null) {
                Log.d(TAG, "geomagnetic rotation vector sensor not available")
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
            Log.d(TAG, "compass stopped")
        }
        sensorManager = null
        sensor = null
        registeredPeriodUs = null
        unreliable = false
        lastAcceptedNs = 0L
        // 止めた後に古い値を記録・表示しないよう戻す
        _reading.value = null
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
        Log.d(TAG, "compass started: ${s.name} @${periodUs}us")
    }

    private val MIN_UPRIGHT_COS = cos(Math.toRadians(Compass.MAX_TILT_DEG)).toFloat()

    /** 希望の周期（µs）の 9 割（ns）より短い間隔で届いた値は捨てる。1 割は届く時刻の揺れの分 */
    private const val THROTTLE_NS_PER_US = 900L

    private const val TAG = "CompassReader"
}

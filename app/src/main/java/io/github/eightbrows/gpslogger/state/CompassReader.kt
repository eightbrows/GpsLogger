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
import io.github.eightbrows.gpslogger.calc.MagMeasurement
import io.github.eightbrows.gpslogger.calc.MagModel
import io.github.eightbrows.gpslogger.calc.MagPairer
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
 * あわせて較正済みの磁場（TYPE_MAGNETIC_FIELD）も同じタイミングで購読し、
 * 磁場の強さと伏角を最新値に載せる（モデル値と比べて、方位の信頼性の指標にする）。
 * 磁場と回転行列はイベント時刻の近い組だけで計算するため、磁場は 100 ms ごとに受け取る
 * （公開は方位と同じ周期に間引く）。
 * こちらは端末の傾きに関係なく測る。磁気センサーが無ければ購読せず、値は null。
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

    /** 較正済みの磁場のセンサー。無い端末では null */
    private var magneticSensor: Sensor? = null

    /** 今センサーに登録している周期（マイクロ秒）。未登録なら null */
    private var registeredPeriodUs: Int? = null

    /** センサーが「信頼できない」と知らせてきている間は方位角を出さない */
    private var unreliable = false

    /** 最後に採用した値の取得時刻（間引き用）。未取得なら 0 */
    private var lastAcceptedNs = 0L

    /** 磁場と回転行列を、イベント時刻の近いものどうしで組にする */
    private val pairer = MagPairer()

    /** 時刻の合う組で測った最新の強さ・伏角と、その取得時刻。未取得なら null・0 */
    private var pairedMagnetic: MagMeasurement? = null
    private var pairedNs = 0L

    /** 磁気センサーが報告している状態（0〜3 など）。未取得なら null */
    private var magneticStatus: Int? = null

    private val _reading = MutableStateFlow<CompassReading?>(null)

    /**
     * 最新値（方位角・精度・磁場の強さと伏角・取得時刻）。古い値も入ったままなので、
     * 使うときは [Compass.usable]・[Compass.magMeasurement] で 3 秒より古い値を落とすこと。
     */
    val reading: StateFlow<CompassReading?> = _reading.asStateFlow()

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                // 測定値を控えるだけ。公開（画面の更新）は回転ベクトルの側で 1 秒ごとにまとめて行う
                val v = event.values
                magneticStatus = event.accuracy
                keepPaired(pairer.onField(event.timestamp, v[0], v[1], v[2]))
                return
            }

            // 回転行列は届くたびに求め、鉛直上向き（リマップ前の 3 行目）を磁場と組にする。
            // 間引く前に行うのは、磁場とイベント時刻の近い組を逃さないため
            val values = event.values
            SensorManager.getRotationMatrixFromVector(rotation, values)
            keepPaired(pairer.onUp(event.timestamp, rotation[6], rotation[7], rotation[8]))

            // センサーの最低レートが希望より速い端末では、希望より細かく届く
            // （例: 最低 5 Hz の端末では 1 秒を指定しても 200 ms ごと）。
            // 使うのは測位時点の最新値だけなので、希望の周期まで間引いて画面の更新を増やさない
            val now = SystemClock.elapsedRealtimeNanos()
            val minIntervalNs = (registeredPeriodUs ?: 0) * THROTTLE_NS_PER_US
            if (now - lastAcceptedNs < minIntervalNs) return
            lastAcceptedNs = now

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

            // 磁場の強さと伏角は、時刻の合う組で測った最新の値。この 1 秒に組が取れていなければ
            // 前の値を測った時刻ごと持ち越す（3 秒より古くなれば、使う側で「無し」になる）
            val magnetic = pairedMagnetic
            _reading.value = CompassReading(
                azimuthDeg = azimuth,
                accuracyDeg = accuracy,
                elapsedRealtimeNs = now,
                magStrengthUt = magnetic?.strengthUt,
                magInclinationDeg = magnetic?.inclinationDeg,
                magElapsedRealtimeNs = pairedNs,
                magSensorStatus = magneticStatus
            )
        }

        /** 時刻の合う組で測れたときだけ、最新の測定値として控える */
        private fun keepPaired(measurement: MagMeasurement?) {
            if (measurement == null) return
            pairedMagnetic = measurement
            pairedNs = SystemClock.elapsedRealtimeNanos()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
                Log.d(TAG, "magnetic field accuracy status: $accuracy")
                magneticStatus = accuracy
                return
            }
            Log.d(TAG, "compass accuracy status: $accuracy")
            unreliable = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
            if (unreliable) {
                // 次の値を待たずに、今の方位角を無効にする
                _reading.value = _reading.value?.copy(azimuthDeg = null)
            }
        }
    }

    /**
     * その地点・時刻の地球の磁場のモデル値（偏角・強さ・伏角）。
     * [altitudeM] は楕円体高、[timeMs] は測位時刻。
     */
    fun model(latitude: Double, longitude: Double, altitudeM: Double, timeMs: Long): MagModel {
        val f = GeomagneticField(
            latitude.toFloat(), longitude.toFloat(), altitudeM.toFloat(), timeMs
        )
        // fieldStrength は nT
        return MagModel(f.declination, f.fieldStrength / 1000f, f.inclination)
    }

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
            // 鉛直上向きは回転ベクトルから求めるので、磁場はそれがあるときだけ購読する
            magneticSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (magneticSensor == null) Log.d(TAG, "magnetic field sensor not available")
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
            it.unregisterListener(listener)   // 回転ベクトル・磁場の両方が外れる
            Log.d(TAG, "compass stopped")
        }
        sensorManager = null
        sensor = null
        magneticSensor = null
        registeredPeriodUs = null
        unreliable = false
        lastAcceptedNs = 0L
        pairer.reset()
        pairedMagnetic = null
        pairedNs = 0L
        magneticStatus = null
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
        // 磁場は回転ベクトルの実際の間隔（最低レートの速い端末で約 200 ms）より細かく受け取る
        val magneticPeriodUs = minOf(periodUs, MAGNETIC_PERIOD_US)
        magneticSensor?.let { sm.registerListener(listener, it, magneticPeriodUs) }
        registeredPeriodUs = periodUs
        Log.d(
            TAG,
            "compass started: ${s.name} @${periodUs}us + " +
                "${magneticSensor?.name ?: "no magnetometer"} @${magneticPeriodUs}us"
        )
    }

    private val MIN_UPRIGHT_COS = cos(Math.toRadians(Compass.MAX_TILT_DEG)).toFloat()

    /** 希望の周期（µs）の 9 割（ns）より短い間隔で届いた値は捨てる。1 割は届く時刻の揺れの分 */
    private const val THROTTLE_NS_PER_US = 900L

    /**
     * 磁場のサンプリング周期（100 ms）。回転行列と時刻の近い組を作るため、公開の周期より細かくする。
     * 回転行列と同じ 200 ms だと、2 つのセンサーの実際の間隔がわずかに違うときに位相がずれていき、
     * いちばん近い磁場でも 100 ms を超える時間帯ができうる。100 ms なら、どの回転行列にも
     * 約 50 ms 以内の磁場がある
     */
    private const val MAGNETIC_PERIOD_US = 100_000

    private const val TAG = "CompassReader"
}

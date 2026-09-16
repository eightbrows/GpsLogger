package io.github.eightbrows.gpslogger.state

import android.location.Location
import io.github.eightbrows.gpslogger.log.LogEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.eightbrows.gpslogger.calc.Dop

/** 軌跡上の1点 */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timeMs: Long,
    /** 一時停止からの再開直後の点か（暗色描画に使う） */
    val gapBefore: Boolean = false,
    /** 測位時点の気圧の生値（hPa）。高度は表示時に基準気圧から計算する */
    val pressureHpa: Float? = null,
    /** この点の気圧高度に使う基準気圧（hPa）。meta.json の区間・セッション設定から解決済み */
    val basePressureHpa: Float = BarometerReader.STANDARD_PRESSURE_HPA
)

/** 時刻Tにおける位置＋その瞬間の衛星セット。UIはこれ1つで駆動される。 */
data class GnssSnapshot(
    val location: Location? = null,
    val satellites: List<LogEvent.Sat> = emptyList(),
    val satEpochMs: Long = 0L,
    val dop: Dop? = null
)

/** サービスが書き、UIが読む。シングルトン。 */
object GnssStateHolder {

    private val _snapshot = MutableStateFlow(GnssSnapshot())
    val snapshot: StateFlow<GnssSnapshot> = _snapshot.asStateFlow()

    private val _isLogging = MutableStateFlow(false)
    val isLogging: StateFlow<Boolean> = _isLogging.asStateFlow()

    /**
     * 一時停止中か。記録は継続扱いなので isLogging は true のまま。
     * （false にすると PreviewLocator が起動してしまう）
     */
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    /** 記録中セッションのフォルダ。停止中は null */
    private val _currentSessionDir = MutableStateFlow<java.io.File?>(null)
    val currentSessionDir: StateFlow<java.io.File?> = _currentSessionDir.asStateFlow()

    fun setCurrentSession(dir: java.io.File?) {
        _currentSessionDir.value = dir
    }

    /** 軌跡用の全測位点（緯度経度のみ・安全上限付き） */
    private val _trackPoints = MutableStateFlow<List<TrackPoint>>(emptyList())
    val trackPoints: StateFlow<List<TrackPoint>> = _trackPoints.asStateFlow()

    fun updateLocation(
        location: Location,
        gapBefore: Boolean = false,
        pressureHpa: Float? = null
    ) {
        _snapshot.value = _snapshot.value.copy(location = location)
        _lastFixElapsedNs.value = android.os.SystemClock.elapsedRealtimeNanos()

        val points = _trackPoints.value
        if (points.size < MAX_TRACK_POINTS) {
            _trackPoints.value = points + TrackPoint(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                timeMs = location.time,
                gapBefore = gapBefore,
                pressureHpa = pressureHpa,
                basePressureHpa = _basePressureHpa.value
            )
        }
    }

    fun updateSatellites(sats: List<LogEvent.Sat>, epochMs: Long) {
        _snapshot.value = _snapshot.value.copy(satellites = sats, satEpochMs = epochMs)
    }

    fun setLogging(logging: Boolean) {
        _isLogging.value = logging
    }

    /** reset() では戻らない。停止時にサービスが明示的に false を入れる */
    fun setPaused(paused: Boolean) {
        _isPaused.value = paused
    }

    /**
     * 新しく積む測位点の基準気圧（hPa）。記録中はサービスが開始時に解決した値、
     * それ以外は標準大気。
     */
    private val _basePressureHpa = MutableStateFlow(BarometerReader.STANDARD_PRESSURE_HPA)
    val basePressureHpa: StateFlow<Float> = _basePressureHpa.asStateFlow()

    fun setBasePressure(hpa: Float) {
        _basePressureHpa.value = hpa
    }

    /** 記録開始時にリセット */
    fun reset() {
        _snapshot.value = GnssSnapshot()
        _basePressureHpa.value = BarometerReader.STANDARD_PRESSURE_HPA
        _trackPoints.value = emptyList()
        _loggingError.value = null
    }

    /** 軌跡のみクリア（プレビュー中の使用を想定） */
    fun clearTrackPoints() {
        _trackPoints.value = emptyList()
    }

    private const val MAX_TRACK_POINTS = 100_000  // 安全上限（1Hzで約27時間）

    fun updateDop(dop: Dop?) {
        _snapshot.value = _snapshot.value.copy(dop = dop)
    }

    private val _lastFixElapsedNs = MutableStateFlow(0L)
    val lastFixElapsedNs: StateFlow<Long> = _lastFixElapsedNs.asStateFlow()

    /** 記録中に発生したエラー（正常時は null） */
    private val _loggingError = MutableStateFlow<String?>(null)
    val loggingError: StateFlow<String?> = _loggingError.asStateFlow()

    fun setLoggingError(message: String?) {
        _loggingError.value = message
    }

    /** 記録開始時刻（停止中は0） */
    private val _recordingStartMs = MutableStateFlow(0L)
    val recordingStartMs: StateFlow<Long> = _recordingStartMs.asStateFlow()

    fun setRecordingStart(ms: Long) {
        _recordingStartMs.value = ms
    }
}

/** 表示用スナップショット。ライブ・再生の両方でこれを使う。 */
data class ViewSnapshot(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val verticalAccuracy: Float = 0f,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val bearingAccuracy: Float = 0f,
    val satellites: List<LogEvent.Sat> = emptyList(),
    val dop: Dop? = null,
    /** 気圧の生値（hPa）。高度は表示時に基準気圧から計算する */
    val pressureHpa: Float? = null,
    /** pressureHpa から高度を計算するときの基準気圧（hPa） */
    val basePressureHpa: Float = BarometerReader.STANDARD_PRESSURE_HPA,
    val trackPoints: List<TrackPoint> = emptyList(),
    val timeMs: Long = 0L,
    val markerIndex: Int? = null,  // 再生時の選択位置。nullなら末尾＝現在地
    val isRecording: Boolean = false,
    val sessionStartMs: Long = 0L,
    val sessionEndMs: Long = 0L
) {
    val satsInView: Int get() = satellites.size
    val satsUsed: Int get() = satellites.count { it.usedInFix }

}
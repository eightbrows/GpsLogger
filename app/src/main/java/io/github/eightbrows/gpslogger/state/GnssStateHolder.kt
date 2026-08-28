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
    val timeMs: Long
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

    /** 記録中セッションのフォルダ。停止中は null */
    private val _currentSessionDir = MutableStateFlow<java.io.File?>(null)
    val currentSessionDir: StateFlow<java.io.File?> = _currentSessionDir.asStateFlow()

    fun setCurrentSession(dir: java.io.File?) {
        _currentSessionDir.value = dir
    }

    /** 軌跡用の全測位点（緯度経度のみ・安全上限付き） */
    private val _trackPoints = MutableStateFlow<List<TrackPoint>>(emptyList())
    val trackPoints: StateFlow<List<TrackPoint>> = _trackPoints.asStateFlow()

    fun updateLocation(location: Location) {
        _snapshot.value = _snapshot.value.copy(location = location)
        _lastFixElapsedNs.value = android.os.SystemClock.elapsedRealtimeNanos()

        val points = _trackPoints.value
        if (points.size < MAX_TRACK_POINTS) {
            _trackPoints.value = points + TrackPoint(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                timeMs = location.time
            )
        }
    }

    fun updateSatellites(sats: List<LogEvent.Sat>, epochMs: Long) {
        _snapshot.value = _snapshot.value.copy(satellites = sats, satEpochMs = epochMs)
    }

    fun setLogging(logging: Boolean) {
        _isLogging.value = logging
    }

    /** 記録開始時にリセット */
    fun reset() {
        _snapshot.value = GnssSnapshot()
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
}

/** 表示用スナップショット。ライブ・再生の両方でこれを使う。 */
data class ViewSnapshot(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val satellites: List<LogEvent.Sat> = emptyList(),
    val dop: Dop? = null,
    val trackPoints: List<TrackPoint> = emptyList(),
    val timeMs: Long = 0L,
    val markerIndex: Int? = null,  // 再生時の選択位置。nullなら末尾＝現在地
    val isRecording: Boolean = false
) {
    val satsInView: Int get() = satellites.size
    val satsUsed: Int get() = satellites.count { it.usedInFix }

}
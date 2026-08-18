package io.github.eightbrows.gpslogger.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit

/** 座標の表示形式 */
enum class CoordFormat { DECIMAL, DMS }

/** テーマの選択 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

object Settings {

    private lateinit var prefs: SharedPreferences

    private val _intervalSec = MutableStateFlow(1)
    val intervalSec: StateFlow<Int> = _intervalSec.asStateFlow()

    private val _useWakeLock = MutableStateFlow(false)
    val useWakeLock: StateFlow<Boolean> = _useWakeLock.asStateFlow()

    private val _coordFormat = MutableStateFlow(CoordFormat.DECIMAL)
    val coordFormat: StateFlow<CoordFormat> = _coordFormat.asStateFlow()

    private val _splitRatio = MutableStateFlow(0.5f)
    val splitRatio: StateFlow<Float> = _splitRatio.asStateFlow()

    private const val KEY_LEAP_SECONDS = "leap_seconds"

    private val _recordingColor = MutableStateFlow(DEFAULT_RECORDING_COLOR)
    val recordingColor: StateFlow<Int> = _recordingColor.asStateFlow()

    private val _previewColor = MutableStateFlow(DEFAULT_PREVIEW_COLOR)
    val previewColor: StateFlow<Int> = _previewColor.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext
            .getSharedPreferences("gpslogger_settings", Context.MODE_PRIVATE)
        _intervalSec.value = prefs.getInt(KEY_INTERVAL, 1)
        _useWakeLock.value = prefs.getBoolean(KEY_WAKELOCK, false)
        _coordFormat.value = CoordFormat.valueOf(
            prefs.getString(KEY_COORD_FORMAT, CoordFormat.DECIMAL.name)!!
        )
        _splitRatio.value = prefs.getFloat(KEY_SPLIT_RATIO, 0.5f)
        _leapSeconds.value = prefs.getInt(KEY_LEAP_SECONDS, 18)

        _recordingColor.value = prefs.getInt(KEY_RECORDING_COLOR, DEFAULT_RECORDING_COLOR)
        _previewColor.value = prefs.getInt(KEY_PREVIEW_COLOR, DEFAULT_PREVIEW_COLOR)

        _themeMode.value = ThemeMode.valueOf(
            prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)!!
        )
    }

    fun setIntervalSec(sec: Int) {
        _intervalSec.value = sec
        prefs.edit { putInt(KEY_INTERVAL, sec) }
    }

    fun setUseWakeLock(use: Boolean) {
        _useWakeLock.value = use
        prefs.edit { putBoolean(KEY_WAKELOCK, use)}
    }

    fun setCoordFormat(format: CoordFormat) {
        _coordFormat.value = format
        prefs.edit { putString(KEY_COORD_FORMAT, format.name)}
    }

    fun setSplitRatio(ratio: Float) {
        _splitRatio.value = ratio
        prefs.edit { putFloat(KEY_SPLIT_RATIO, ratio)}
    }

    fun setLeapSeconds(sec: Int) {
        _leapSeconds.value = sec
        prefs.edit { putInt(KEY_LEAP_SECONDS, sec)}
    }

    /** 選択可能な記録間隔（秒） */
    val intervalOptions = listOf(1, 2, 4, 8, 16, 32)

    private const val KEY_INTERVAL = "interval_sec"
    private const val KEY_WAKELOCK = "use_wakelock"
    private const val KEY_COORD_FORMAT = "coord_format"
    private const val KEY_SPLIT_RATIO = "split_ratio"

    private val _leapSeconds = MutableStateFlow(18)
    val leapSeconds: StateFlow<Int> = _leapSeconds.asStateFlow()

    fun setRecordingColor(color: Int) {
        _recordingColor.value = color
        prefs.edit { putInt(KEY_RECORDING_COLOR, color) }
    }

    fun setPreviewColor(color: Int) {
        _previewColor.value = color
        prefs.edit { putInt(KEY_PREVIEW_COLOR, color) }
    }

    /** 軌跡色の選択肢 */
    val trackColorOptions = listOf(
        0xFFE91E63.toInt(),  // ピンク
        0xFF00BCD4.toInt(),  // 水色
        0xFF4CAF50.toInt(),  // 緑
        0xFFFF9800.toInt(),  // 橙
        0xFF9C27B0.toInt(),  // 紫
        0xFF2196F3.toInt(),  // 青
        0xFFF44336.toInt(),  // 赤
        0xFF9E9E9E.toInt()   // グレー
    )

    private const val DEFAULT_RECORDING_COLOR = 0xFFE91E63.toInt()  // ピンク
    private const val DEFAULT_PREVIEW_COLOR = 0xFF00BCD4.toInt()    // 水色
    private const val KEY_RECORDING_COLOR = "recording_color"
    private const val KEY_PREVIEW_COLOR = "preview_color"

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit {putString(KEY_THEME_MODE, mode.name) }
    }

    private const val KEY_THEME_MODE = "theme_mode"
}
package io.github.eightbrows.gpslogger.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 座標の表示形式 */
enum class CoordFormat { DECIMAL, DMS }

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
    }

    fun setIntervalSec(sec: Int) {
        _intervalSec.value = sec
        prefs.edit().putInt(KEY_INTERVAL, sec).apply()
    }

    fun setUseWakeLock(use: Boolean) {
        _useWakeLock.value = use
        prefs.edit().putBoolean(KEY_WAKELOCK, use).apply()
    }

    fun setCoordFormat(format: CoordFormat) {
        _coordFormat.value = format
        prefs.edit().putString(KEY_COORD_FORMAT, format.name).apply()
    }

    fun setSplitRatio(ratio: Float) {
        _splitRatio.value = ratio
        prefs.edit().putFloat(KEY_SPLIT_RATIO, ratio).apply()
    }

    /** 選択可能な記録間隔（秒） */
    val intervalOptions = listOf(1, 2, 4, 8, 16, 32)

    private const val KEY_INTERVAL = "interval_sec"
    private const val KEY_WAKELOCK = "use_wakelock"
    private const val KEY_COORD_FORMAT = "coord_format"
    private const val KEY_SPLIT_RATIO = "split_ratio"
}
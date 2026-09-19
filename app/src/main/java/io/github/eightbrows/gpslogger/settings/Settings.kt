package io.github.eightbrows.gpslogger.settings

import io.github.eightbrows.gpslogger.R
import androidx.annotation.StringRes
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit
import kotlin.math.roundToInt

/** 座標の表示形式 */
enum class CoordFormat { DECIMAL, DMS }

/** テーマの選択 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 数値ページの高度の単位（表示順） */
enum class AltitudeUnit(val symbol: String) { M("m"), FT("ft") }

/** 数値ページの速度の単位（表示順） */
enum class SpeedUnit(val symbol: String) { MPS("m/s"), KMH("km/h"), KT("kt") }

/**
 * 数値ページの衛星種別（RINEX の系統記号、表示順）。
 * G=GPS, R=GLONASS, E=Galileo, C=BeiDou, J=QZSS, S=SBAS, I=NavIC(IRNSS)。
 * 名前をそのまま保存するので、保存内容は RINEX 記号の集合になる。
 */
enum class ConstellationCode { G, R, E, C, J, S, I }

/** 数値ページの DOP の指標（表示順）。名前をそのまま保存する */
enum class DopMetric { P, H, V, G, T }

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

    private const val KEY_PERMISSION_NOTICE = "permission_notice_shown"

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
        _geoidHeightM.value = prefs.getInt(KEY_GEOID_HEIGHT_DM, DEFAULT_GEOID_HEIGHT_DM) / 10.0

        _recordingColor.value = prefs.getInt(KEY_RECORDING_COLOR, DEFAULT_RECORDING_COLOR)
        _previewColor.value = prefs.getInt(KEY_PREVIEW_COLOR, DEFAULT_PREVIEW_COLOR)

        _themeMode.value = ThemeMode.valueOf(
            prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)!!
        )

        _permissionNoticeShown.value = prefs.getBoolean(KEY_PERMISSION_NOTICE, false)

        _languageMode.value = runCatching {
            LanguageMode.valueOf(prefs.getString(KEY_LANGUAGE_MODE, LanguageMode.SYSTEM.name)!!)
        }.getOrDefault(LanguageMode.SYSTEM)

        _altitudeEmphasis.value = readUnitSet(KEY_ALTITUDE_EMPHASIS, AltitudeUnit.entries)
        _speedEmphasis.value = readUnitSet(KEY_SPEED_EMPHASIS, SpeedUnit.entries)
        _constellationEmphasis.value =
            readUnitSet(KEY_CONSTELLATION_EMPHASIS, ConstellationCode.entries)
        _dopEmphasis.value = readUnitSet(KEY_DOP_EMPHASIS, DopMetric.entries)
    }

    /**
     * 単位の集合を読む。一度も保存していなければ全単位（初期値）、
     * 空で保存されていれば空集合のまま返す。知らない名前は無視する。
     */
    private fun <E : Enum<E>> readUnitSet(key: String, all: List<E>): Set<E> {
        val names = prefs.getStringSet(key, null) ?: return all.toSet()
        return all.filterTo(LinkedHashSet()) { it.name in names }
    }

    private fun <E : Enum<E>> writeUnitSet(key: String, units: Set<E>) {
        // getStringSet の戻り値は変更不可なので、毎回新しい集合を渡す
        prefs.edit { putStringSet(key, units.mapTo(HashSet()) { it.name }) }
    }

    // ---- 数値ページの単位の強調（選んだ単位を枠で囲む。衛星種別・DOP も同じ） ----

    private val _altitudeEmphasis = MutableStateFlow(AltitudeUnit.entries.toSet())
    val altitudeEmphasis: StateFlow<Set<AltitudeUnit>> = _altitudeEmphasis.asStateFlow()

    private val _speedEmphasis = MutableStateFlow(SpeedUnit.entries.toSet())
    val speedEmphasis: StateFlow<Set<SpeedUnit>> = _speedEmphasis.asStateFlow()

    /** 高度の単位の強調を切り替える。すべて外してもよい */
    fun toggleAltitudeEmphasis(unit: AltitudeUnit) {
        val next = _altitudeEmphasis.value.let { if (unit in it) it - unit else it + unit }
        _altitudeEmphasis.value = next
        writeUnitSet(KEY_ALTITUDE_EMPHASIS, next)
    }

    /** 速度の単位の強調を切り替える。すべて外してもよい */
    fun toggleSpeedEmphasis(unit: SpeedUnit) {
        val next = _speedEmphasis.value.let { if (unit in it) it - unit else it + unit }
        _speedEmphasis.value = next
        writeUnitSet(KEY_SPEED_EMPHASIS, next)
    }

    private const val KEY_ALTITUDE_EMPHASIS = "altitude_emphasis"
    private const val KEY_SPEED_EMPHASIS = "speed_emphasis"

    // ---- 数値ページの衛星種別・DOP の強調（選んだ項目を枠で囲む） ----

    private val _constellationEmphasis = MutableStateFlow(ConstellationCode.entries.toSet())
    val constellationEmphasis: StateFlow<Set<ConstellationCode>> = _constellationEmphasis.asStateFlow()

    private val _dopEmphasis = MutableStateFlow(DopMetric.entries.toSet())
    val dopEmphasis: StateFlow<Set<DopMetric>> = _dopEmphasis.asStateFlow()

    /** 衛星種別の強調を切り替える。すべて外してもよい */
    fun toggleConstellationEmphasis(code: ConstellationCode) {
        val next = _constellationEmphasis.value.let { if (code in it) it - code else it + code }
        _constellationEmphasis.value = next
        writeUnitSet(KEY_CONSTELLATION_EMPHASIS, next)
    }

    /** DOP の指標の強調を切り替える。すべて外してもよい */
    fun toggleDopEmphasis(metric: DopMetric) {
        val next = _dopEmphasis.value.let { if (metric in it) it - metric else it + metric }
        _dopEmphasis.value = next
        writeUnitSet(KEY_DOP_EMPHASIS, next)
    }

    private const val KEY_CONSTELLATION_EMPHASIS = "constellation_emphasis"
    private const val KEY_DOP_EMPHASIS = "dop_emphasis"

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

    // ---- ジオイド高（GPX / KMZ の高度を「楕円体高 − ジオイド高」で標高に換算する） ----

    private val _geoidHeightM = MutableStateFlow(DEFAULT_GEOID_HEIGHT_M)

    /** ジオイド高（m、0.1 m 単位）。0 なら補正なし（楕円体高をそのまま出す） */
    val geoidHeightM: StateFlow<Double> = _geoidHeightM.asStateFlow()

    /**
     * ジオイド高を設定する。0.1 m 単位に丸め、設定できる範囲に収める。
     * 小数の丸め誤差を避けるため、保存は 0.1 m 単位の整数で行う。
     */
    fun setGeoidHeightM(m: Double) {
        val dm = (m * 10).roundToInt().coerceIn(-GEOID_LIMIT_DM, GEOID_LIMIT_DM)
        _geoidHeightM.value = dm / 10.0
        prefs.edit { putInt(KEY_GEOID_HEIGHT_DM, dm) }
    }

    /** 日本付近の平均的なジオイド高の目安 */
    const val DEFAULT_GEOID_HEIGHT_M = 36.0
    private const val DEFAULT_GEOID_HEIGHT_DM = 360
    /** 設定できる範囲（±120 m。地球上のジオイド高はおよそ −107〜+86 m） */
    const val GEOID_LIMIT_M = 120.0
    private const val GEOID_LIMIT_DM = 1200
    private const val KEY_GEOID_HEIGHT_DM = "geoid_height_dm"

    fun setRecordingColor(color: Int) {
        _recordingColor.value = color
        prefs.edit { putInt(KEY_RECORDING_COLOR, color) }
    }

    fun setPreviewColor(color: Int) {
        _previewColor.value = color
        prefs.edit { putInt(KEY_PREVIEW_COLOR, color) }
    }

    /** 軌跡色の選択肢（通話料金確認アプリの WIDGET_COLOR_PALETTE に準拠） */
    val trackColorOptions = listOf(
        TrackColorOption(R.string.color_white, 0xFFFFFFFF.toInt()),
        TrackColorOption(R.string.color_teal, 0xFF26C6DA.toInt()),
        TrackColorOption(R.string.color_blue, 0xFF0000FF.toInt()),
        TrackColorOption(R.string.color_indigo, 0xFF3F51B5.toInt()),
        TrackColorOption(R.string.color_purple, 0xFF7B1FA2.toInt()),
        TrackColorOption(R.string.color_pink, 0xFFE91E63.toInt()),
        TrackColorOption(R.string.color_red, 0xFFFF0000.toInt()),
        TrackColorOption(R.string.color_orange, 0xFFFF5722.toInt()),
        TrackColorOption(R.string.color_yellow, 0xFFFBC02D.toInt()),
        TrackColorOption(R.string.color_olive, 0xFF6B6E1E.toInt()),
        TrackColorOption(R.string.color_green, 0xFF388E3C.toInt()),
        TrackColorOption(R.string.color_black, 0xFF000000.toInt())
    )

    private const val DEFAULT_RECORDING_COLOR = 0xFFE91E63.toInt()  // ピンク
    private const val DEFAULT_PREVIEW_COLOR = 0xFF0000FF.toInt()    // 青
    private const val KEY_RECORDING_COLOR = "recording_color"
    private const val KEY_PREVIEW_COLOR = "preview_color"

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit {putString(KEY_THEME_MODE, mode.name) }
    }

    private const val KEY_THEME_MODE = "theme_mode"

    private val _permissionNoticeShown = MutableStateFlow(false)
    val permissionNoticeShown: StateFlow<Boolean> = _permissionNoticeShown.asStateFlow()

    fun setPermissionNoticeShown(shown: Boolean) {
        _permissionNoticeShown.value = shown
        prefs.edit { putBoolean(KEY_PERMISSION_NOTICE, shown) }
    }

    private val _languageMode = MutableStateFlow(LanguageMode.SYSTEM)
    val languageMode: StateFlow<LanguageMode> = _languageMode.asStateFlow()

    /** 表示言語を切り替える。Activity が作り直され、再起動しなくても反映される */
    fun setLanguageMode(mode: LanguageMode) {
        _languageMode.value = mode
        prefs.edit { putString(KEY_LANGUAGE_MODE, mode.name) }
        AppLanguage.apply(mode)
    }

    /**
     * 実際に適用されている言語に合わせる。Activity を作るたびに呼ぶ。
     * Android 13 以降はシステム設定の「アプリの言語」からも変えられるため、OS 側を正とする。
     */
    fun syncLanguageMode() {
        val applied = AppLanguage.current()
        if (_languageMode.value == applied) return
        _languageMode.value = applied
        if (::prefs.isInitialized) prefs.edit { putString(KEY_LANGUAGE_MODE, applied.name) }
    }

    private const val KEY_LANGUAGE_MODE = "language_mode"
}

/** 軌跡色の1色分（パレット表示用に色名を持つ） */
data class TrackColorOption(@param:StringRes val nameRes: Int, val color: Int)
package io.github.eightbrows.gpslogger.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.annotation.StringRes
import io.github.eightbrows.gpslogger.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.unit.Dp
import io.github.eightbrows.gpslogger.calc.PressureUnits
import io.github.eightbrows.gpslogger.ui.BasePressureDialog
import io.github.eightbrows.gpslogger.ui.GeoidOffsetDialog
import io.github.eightbrows.gpslogger.ui.LeapSecondsDialog
import io.github.eightbrows.gpslogger.ui.SegmentedControl
import io.github.eightbrows.gpslogger.ui.formatGeoid
import io.github.eightbrows.gpslogger.ui.formatHpa
import io.github.eightbrows.gpslogger.ui.formatInHg
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import java.util.Locale
import kotlin.math.abs
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.eightbrows.gpslogger.BuildConfig
import io.github.eightbrows.gpslogger.state.GnssStateHolder
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight

@Composable
fun SettingsScreen() {
    val intervalSec by Settings.intervalSec.collectAsState()
    val useWakeLock by Settings.useWakeLock.collectAsState()
    val coordFormat by Settings.coordFormat.collectAsState()
    val isLogging by GnssStateHolder.isLogging.collectAsState()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
    ) {
        // --- 記録間隔 ---
        SectionLabel(stringResource(R.string.settings_interval), top = 16.dp)
        val lockedNote = stringResource(R.string.settings_interval_locked)
        val sparseNote = stringResource(R.string.settings_interval_sparse)
        val noteSeparator = stringResource(R.string.settings_note_separator)
        val intervalNote = buildString {
            if (isLogging) append(lockedNote)
            if (intervalSec >= 16) {
                if (isNotEmpty()) append(noteSeparator)
                append(sparseNote)
            }
        }
        if (intervalNote.isNotEmpty()) {
            SubText(intervalNote)
        }
        SegmentedControl(
            options = Settings.intervalOptions,
            selected = intervalSec,
            label = { "$it" },
            enabled = !isLogging,
            onSelect = { Settings.setIntervalSec(it) }
        )

        // --- 座標表示形式 ---
        SectionLabel(stringResource(R.string.settings_coord_format), top = 14.dp)
        SubText(stringResource(R.string.settings_coord_format_note))
        SegmentedControl(
            options = CoordFormat.entries,
            selected = coordFormat,
            label = {
                stringResource(
                    if (it == CoordFormat.DECIMAL) R.string.settings_coord_decimal
                    else R.string.settings_coord_dms
                )
            },
            onSelect = { Settings.setCoordFormat(it) }
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(
                "35.1234567",
                Modifier.weight(1f),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )
            Text(
                "35°07'24.4\"",
                Modifier.weight(1f),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )
        }

        // --- 表示の強調 ---
        val altitudeEmphasis by Settings.altitudeEmphasis.collectAsState()
        val speedEmphasis by Settings.speedEmphasis.collectAsState()
        val constellationEmphasis by Settings.constellationEmphasis.collectAsState()
        val dopEmphasis by Settings.dopEmphasis.collectAsState()
        SectionLabel(stringResource(R.string.settings_emphasis), top = 14.dp)
        SubText(stringResource(R.string.settings_emphasis_note))
        EmphasisRow(
            label = stringResource(R.string.settings_emphasis_altitude),
            options = AltitudeUnit.entries,
            selected = altitudeEmphasis,
            optionLabel = { it.symbol },
            onToggle = { Settings.toggleAltitudeEmphasis(it) }
        )
        EmphasisRow(
            label = stringResource(R.string.settings_emphasis_speed),
            options = SpeedUnit.entries,
            selected = speedEmphasis,
            optionLabel = { it.symbol },
            onToggle = { Settings.toggleSpeedEmphasis(it) },
            modifier = Modifier.padding(top = 6.dp)
        )
        EmphasisRow(
            label = stringResource(R.string.settings_emphasis_constellation),
            options = ConstellationCode.entries,
            selected = constellationEmphasis,
            optionLabel = { it.name },
            onToggle = { Settings.toggleConstellationEmphasis(it) },
            modifier = Modifier.padding(top = 6.dp)
        )
        EmphasisNote(stringResource(R.string.settings_emphasis_constellation_note))
        EmphasisRow(
            // DOP は日英共通の専門用語
            label = "DOP",
            options = DopMetric.entries,
            selected = dopEmphasis,
            optionLabel = { it.name },
            onToggle = { Settings.toggleDopEmphasis(it) },
            modifier = Modifier.padding(top = 6.dp)
        )
        EmphasisNote(stringResource(R.string.settings_emphasis_dop_note))

        // --- 軌跡の色 ---
        SectionLabel(stringResource(R.string.settings_track_color), top = 14.dp)
        SubText(stringResource(R.string.settings_track_color_note))
        TrackColorRow()

        Divider12()

        // --- WakeLock ---
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_wakelock), fontSize = 14.sp)
                Text(
                    stringResource(R.string.settings_wakelock_note),
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Switch(
                checked = useWakeLock,
                onCheckedChange = { Settings.setUseWakeLock(it) },
                enabled = !isLogging,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // --- うるう秒 ---
        LeapSecondsRow()

        // --- 基準気圧（新しい記録の meta.json に書く初期値） ---
        BasePressureRow()

        // --- ジオイド高（GPX / KMZ の標高換算） ---
        GeoidHeightRow()

        Divider14()

        // --- テーマ ---
        val themeMode by Settings.themeMode.collectAsState()
        SectionLabel(stringResource(R.string.settings_theme), top = 0.dp)
        SubText(stringResource(R.string.settings_theme_note))
        SegmentedControl(
            options = ThemeMode.entries,
            selected = themeMode,
            label = {
                when (it) {
                    ThemeMode.SYSTEM -> stringResource(R.string.settings_follow_system)
                    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                }
            },
            onSelect = { Settings.setThemeMode(it) }
        )

        Divider14()

        // --- 言語 ---
        val languageMode by Settings.languageMode.collectAsState()
        SectionLabel(stringResource(R.string.settings_language), top = 0.dp)
        SubText(stringResource(R.string.settings_language_note))
        SegmentedControl(
            options = LanguageMode.entries,
            selected = languageMode,
            label = {
                when (it) {
                    LanguageMode.SYSTEM -> stringResource(R.string.settings_follow_system)
                    LanguageMode.JAPANESE -> stringResource(R.string.language_japanese)
                    LanguageMode.ENGLISH -> stringResource(R.string.language_english)
                }
            },
            onSelect = { Settings.setLanguageMode(it) }
        )

        Divider14()

        // --- 権限 ---
        SectionLabel(stringResource(R.string.settings_permissions), top = 0.dp)
        PermissionSection()

        Divider14()

        // --- 情報 ---
        SectionLabel(stringResource(R.string.settings_about), top = 0.dp)
        InfoRow(stringResource(R.string.settings_storage), stringResource(R.string.settings_storage_value))
        InfoRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
        LinkRow(stringResource(R.string.settings_license), "Apache 2.0", "https://github.com/eightbrows/GpsLogger/blob/main/LICENSE")
        LinkRow(stringResource(R.string.settings_website), "eightbrows.github.io", "https://eightbrows.github.io/")

        Box(Modifier.height(16.dp))
    }
}

// ==================== 共通部品 ====================

@Composable
private fun SectionLabel(text: String, top: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = top, bottom = 2.dp)
    )
}

@Composable
private fun SubText(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun Divider12() {
    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 12.dp))
}

@Composable
private fun Divider14() {
    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 14.dp))
}

/** 強調の選択肢（記号）の意味を示す注記。「うるう秒」の説明文と同じ見た目 */
@Composable
private fun EmphasisNote(text: String) {
    Text(
        text,
        Modifier.padding(top = 2.dp),
        fontSize = 11.sp,
        // 狭い画面で折り返したときに行間が空きすぎないよう詰める
        lineHeight = 14.sp,
        color = MaterialTheme.colorScheme.outline
    )
}

/** 「ラベル＋複数選択の帯」を1行に並べる（縦の場所を取らないよう、ラベルは帯の左） */
@Composable
private fun <T> EmphasisRow(
    label: String,
    options: List<T>,
    selected: Set<T>,
    optionLabel: (T) -> String,
    onToggle: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.width(64.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MultiSegmentedControl(
            options = options,
            selected = selected,
            label = optionLabel,
            onToggle = onToggle,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * SegmentedControl の複数選択版。見た目は同じで、各セグメントを個別にオン／オフする。
 * 読み上げでは各セグメントをチェックボックスとして扱う。
 */
@Composable
private fun <T> MultiSegmentedControl(
    options: List<T>,
    selected: Set<T>,
    label: (T) -> String,
    onToggle: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .clip(shape)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, shape)
    ) {
        options.forEachIndexed { index, option ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
            val isSelected = option in selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .toggleable(
                        value = isSelected,
                        enabled = enabled,
                        role = Role.Checkbox,
                        onValueChange = { onToggle(option) }
                    )
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(option),
                    fontSize = 12.sp,
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.onPrimary
                        !enabled -> MaterialTheme.colorScheme.outline
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** うるう秒: ラベル＋補足を左、+/-・数値・↺ を右に1行で収める。↺ で初期値（18）に戻す */
@Composable
private fun LeapSecondsRow() {
    val leapSeconds by Settings.leapSeconds.collectAsState()
    var editing by remember { mutableStateOf(false) }

    StepperRow(
        title = stringResource(R.string.settings_leap_seconds),
        note = stringResource(R.string.settings_leap_seconds_note),
        value = "$leapSeconds",
        onIncrease = { Settings.setLeapSeconds(leapSeconds + 1) },
        onDecrease = { Settings.setLeapSeconds(leapSeconds - 1) },
        onEdit = { editing = true },
        onReset = { Settings.resetLeapSeconds() },
        resetDescription = stringResource(
            R.string.settings_reset_to, stringResource(R.string.settings_leap_seconds)
        )
    )

    if (editing) {
        LeapSecondsDialog(
            initial = leapSeconds,
            onDismiss = { editing = false },
            onSave = { value ->
                value?.let { Settings.setLeapSeconds(it) }
                editing = false
            }
        )
    }
}

/**
 * ジオイド高: うるう秒と同じ形。+/- は 1 m 刻み、数値をタップすると 0.1 m 単位で直接入力できる。
 * ↺ で初期値（36.0 m）に戻す
 */
@Composable
private fun GeoidHeightRow() {
    val geoidHeight by Settings.geoidHeightM.collectAsState()
    var editing by remember { mutableStateOf(false) }

    StepperRow(
        title = stringResource(R.string.settings_geoid_height),
        note = stringResource(R.string.settings_geoid_height_note),
        value = formatGeoid(geoidHeight),
        onIncrease = { Settings.setGeoidHeightM(geoidHeight + 1.0) },
        onDecrease = { Settings.setGeoidHeightM(geoidHeight - 1.0) },
        onEdit = { editing = true },
        onReset = { Settings.resetGeoidHeightM() },
        resetDescription = stringResource(
            R.string.settings_reset_to, stringResource(R.string.settings_geoid_height)
        )
    )

    if (editing) {
        GeoidOffsetDialog(
            initialM = geoidHeight,
            onDismiss = { editing = false },
            onSave = { value ->
                value?.let { Settings.setGeoidHeightM(it) }
                editing = false
            }
        )
    }
}

/**
 * 基準気圧: 気圧高度の基準。新しい記録の meta.json に初期値として書き込む。
 * +/- は 1 hPa 刻み、数値をタップすると hPa と inHg（水銀柱インチ）のどちらでも直接入力できる。
 * ↺ で初期値（標準大気 1013.25 hPa）に戻す
 */
@Composable
private fun BasePressureRow() {
    val basePressure by Settings.basePressureHpa.collectAsState()
    var editing by remember { mutableStateOf(false) }

    StepperRow(
        title = stringResource(R.string.settings_base_pressure),
        note = stringResource(R.string.settings_base_pressure_note),
        value = formatHpa(basePressure),
        // hPa の下に inHg（水銀柱インチ）換算を常に出す
        subValue = stringResource(
            R.string.settings_base_pressure_inhg,
            formatInHg(PressureUnits.inHgFromHpa(basePressure))
        ),
        valueWidth = 74.dp,
        onIncrease = { Settings.setBasePressureHpa(basePressure + 1.0) },
        onDecrease = { Settings.setBasePressureHpa(basePressure - 1.0) },
        onEdit = { editing = true },
        onReset = { Settings.resetBasePressureHpa() },
        resetDescription = stringResource(
            R.string.settings_reset_to, stringResource(R.string.settings_base_pressure)
        )
    )

    if (editing) {
        BasePressureDialog(
            initialHpa = basePressure,
            onDismiss = { editing = false },
            onSave = { value ->
                value?.let { Settings.setBasePressureHpa(it) }
                editing = false
            }
        )
    }
}

/** ラベルと補足を左、+/-・数値・リセットを右に1行で収める（うるう秒の行と同じ並び） */
@Composable
private fun StepperRow(
    title: String,
    note: String,
    value: String,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onReset: () -> Unit,
    resetDescription: String,
    /** 数値の下に小さく添える換算値など。null なら数値だけ */
    subValue: String? = null,
    /** null なら数値をタップしても何も起きない（直接入力できない項目） */
    onEdit: (() -> Unit)? = null,
    valueWidth: Dp = 48.dp
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(
                note,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("+", onIncrease)
            Column(
                Modifier
                    .width(valueWidth)
                    .clip(RoundedCornerShape(4.dp))
                    .then(if (onEdit != null) Modifier.clickable(onClick = onEdit) else Modifier)
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(value, fontSize = 13.sp, textAlign = TextAlign.Center)
                if (subValue != null) {
                    Text(
                        subValue,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                }
            }
            StepButton("−", onDecrease)
            Box(
                Modifier
                    .padding(start = 6.dp)
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onReset),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = resetDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 14.sp)
    }
}

// ==================== 軌跡の色 ====================

private enum class TrackColorRole(@param:StringRes val labelRes: Int) {
    PREVIEW(R.string.settings_color_preview),
    RECORDING(R.string.settings_color_recording)
}

@Composable
private fun TrackColorRow() {
    val recordingColor by Settings.recordingColor.collectAsState()
    val previewColor by Settings.previewColor.collectAsState()
    var editing by remember { mutableStateOf<TrackColorRole?>(null) }

    fun selectedColor(role: TrackColorRole) =
        if (role == TrackColorRole.RECORDING) recordingColor else previewColor

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackColorChip(TrackColorRole.PREVIEW, selectedColor(TrackColorRole.PREVIEW)) {
            editing = TrackColorRole.PREVIEW
        }
        Text("→", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        TrackColorChip(TrackColorRole.RECORDING, selectedColor(TrackColorRole.RECORDING)) {
            editing = TrackColorRole.RECORDING
        }
    }

    val role = editing
    if (role != null) {
        TrackColorPickerDialog(
            title = stringResource(R.string.settings_color_dialog_title, stringResource(role.labelRes)),
            selectedColor = selectedColor(role),
            onSelect = { color ->
                if (role == TrackColorRole.RECORDING) Settings.setRecordingColor(color)
                else Settings.setPreviewColor(color)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun TrackColorChip(role: TrackColorRole, color: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(color))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(role.labelRes),
            fontSize = 12.sp,
            color = trackTextColorOn(color)
        )
    }
}

private const val PALETTE_COLUMNS = 4

@Composable
private fun TrackColorPickerDialog(
    title: String,
    selectedColor: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(PALETTE_COLUMNS),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(Settings.trackColorOptions) { option ->
                    TrackColorSwatch(
                        option = option,
                        selected = option.color == selectedColor,
                        onClick = { onSelect(option.color) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun TrackColorSwatch(option: TrackColorOption, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .background(Color(option.color), RoundedCornerShape(4.dp))
                .border(
                    width = if (selected) 2.5.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(4.dp)
                )
        ) {
            if (selected) {
                Text("✓", color = trackTextColorOn(option.color), fontSize = 14.sp)
            }
        }
        Text(
            stringResource(option.nameRes),
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** 背景色に対するコントラストの高い文字色（黒 or 白）を返す */
private fun trackTextColorOn(argb: Int): Color {
    fun linearize(channel: Int): Double {
        val s = channel / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val luminance = 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
    val blackContrast = (luminance + 0.05) / 0.05
    val whiteContrast = 1.05 / (luminance + 0.05)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

// ==================== 権限 ====================

@Composable
private fun PermissionSection() {
    val context = LocalContext.current
    var permKey by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { permKey++ }

    AppPermission.entries.filter { it.isApplicable }.forEach { perm ->
        val granted = remember(permKey) { PermissionUtil.isGranted(context, perm) }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    if (granted || !perm.requestable) {
                        PermissionUtil.openAppSettings(context)
                    } else {
                        permissionLauncher.launch(perm.manifestName)
                    }
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(perm.labelRes), fontSize = 13.sp)
                Text(
                    stringResource(perm.descriptionRes),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Text(
                stringResource(if (granted) R.string.permission_granted else R.string.permission_denied),
                fontSize = 14.sp,
                color = if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
    }
}

// ==================== 情報 ====================

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LinkRow(label: String, value: String, url: String) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                context.startActivity(intent)
            }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.padding(start = 3.dp).size(12.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
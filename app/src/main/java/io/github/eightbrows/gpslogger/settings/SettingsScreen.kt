package io.github.eightbrows.gpslogger.settings

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
        SectionLabel("記録間隔（秒）", top = 16.dp)
        if (isLogging) {
            SubText("記録中は変更できません")
        }
        SegmentedControl(
            options = Settings.intervalOptions,
            selected = intervalSec,
            label = { "$it" },
            enabled = !isLogging,
            onSelect = { Settings.setIntervalSec(it) }
        )

        // --- 座標表示形式 ---
        SectionLabel("座標表示形式", top = 14.dp)
        SubText("数値ページと軌跡のグリッドに反映されます")
        SegmentedControl(
            options = CoordFormat.entries,
            selected = coordFormat,
            label = { if (it == CoordFormat.DECIMAL) "度" else "度分秒" },
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

        // --- 軌跡の色 ---
        SectionLabel("軌跡の色", top = 14.dp)
        SubText("記録中とプレビュー中で色を分けられます")
        TrackColorRow()

        Divider12()

        // --- WakeLock ---
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("WakeLockを使用", fontSize = 14.sp)
                Text(
                    "画面OFF時の取りこぼしを防止（電池消費が増えます）",
                    fontSize = 11.sp,
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

        Divider14()

        // --- テーマ ---
        val themeMode by Settings.themeMode.collectAsState()
        SectionLabel("テーマ", top = 0.dp)
        SubText("アプリ全体の配色を切り替えます")
        SegmentedControl(
            options = ThemeMode.entries,
            selected = themeMode,
            label = {
                when (it) {
                    ThemeMode.SYSTEM -> "システムに従う"
                    ThemeMode.LIGHT -> "ライト"
                    ThemeMode.DARK -> "ダーク"
                }
            },
            onSelect = { Settings.setThemeMode(it) }
        )

        Divider14()

        // --- 権限 ---
        SectionLabel("権限", top = 0.dp)
        PermissionSection()

        Divider14()

        // --- 情報 ---
        SectionLabel("情報", top = 0.dp)
        InfoRow("保存先", "アプリ専用ディレクトリ")
        InfoRow("バージョン", BuildConfig.VERSION_NAME)
        LinkRow("ライセンス", "Apache 2.0", "https://github.com/eightbrows/GpsLogger/blob/main/LICENSE")
        LinkRow("公式サイト", "eightbrows.github.io", "https://eightbrows.github.io/")

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

/** N択の排他選択を横並びの帯（セグメントコントロール）で表す */
@Composable
private fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, shape)
    ) {
        options.forEachIndexed { index, option ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = enabled) { onSelect(option) }
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

/** うるう秒: ラベル＋補足を左、+/-と数値を右に1行で収める */
@Composable
private fun LeapSecondsRow() {
    val leapSeconds by Settings.leapSeconds.collectAsState()

    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text("うるう秒（GPS − UTC）", fontSize = 14.sp)
            Text(
                "Z-countの算出に使用（初期値 18）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("+") {
                Settings.setLeapSeconds((leapSeconds + 1).coerceIn(0, 99))
            }
            Text(
                "$leapSeconds",
                Modifier.width(24.dp),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            StepButton("−") {
                Settings.setLeapSeconds((leapSeconds - 1).coerceIn(0, 99))
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

private enum class TrackColorRole(val label: String) {
    PREVIEW("プレビュー"),
    RECORDING("記録中")
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
            title = "${role.label}の色を選択",
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
            role.label,
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
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
            option.name,
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
                Text(perm.label, fontSize = 13.sp)
                Text(
                    perm.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Text(
                if (granted) "許可済み" else "未許可",
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
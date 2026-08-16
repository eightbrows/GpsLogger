package io.github.eightbrows.gpslogger.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eightbrows.gpslogger.state.GnssStateHolder
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color

@Composable
fun SettingsScreen() {
    val intervalSec by Settings.intervalSec.collectAsState()
    val useWakeLock by Settings.useWakeLock.collectAsState()
    val coordFormat by Settings.coordFormat.collectAsState()
    val isLogging by GnssStateHolder.isLogging.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionTitle("記録間隔（秒）")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Settings.intervalOptions.forEach { sec ->
                FilterChip(
                    selected = intervalSec == sec,
                    onClick = { Settings.setIntervalSec(sec) },
                    label = {
                        Text(
                            "$sec",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    },
                    enabled = !isLogging,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (isLogging) {
            Text(
                "記録中は変更できません",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle("座標表示形式")
        CoordFormat.entries.forEach { format ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { Settings.setCoordFormat(format) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = coordFormat == format,
                    onClick = { Settings.setCoordFormat(format) }
                )
                Text(
                    when (format) {
                        CoordFormat.DECIMAL -> "度（35.1234567）"
                        CoordFormat.DMS -> "度分秒（35°07'24.4\"）"
                    },
                    fontSize = 14.sp
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle("電源管理")
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("WakeLockを使用", fontSize = 14.sp)
                Text(
                    "画面OFF時の取りこぼしを防ぎますが、電池消費が増えます",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Switch(
                checked = useWakeLock,
                onCheckedChange = { Settings.setUseWakeLock(it) },
                enabled = !isLogging
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle("テーマ")
        val themeMode by Settings.themeMode.collectAsState()
        ThemeMode.entries.forEach { mode ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { Settings.setThemeMode(mode) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = themeMode == mode,
                    onClick = { Settings.setThemeMode(mode) }
                )
                Text(
                    when (mode) {
                        ThemeMode.SYSTEM -> "システムに従う"
                        ThemeMode.LIGHT -> "ライト"
                        ThemeMode.DARK -> "ダーク"
                    },
                    fontSize = 14.sp
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle("軌跡の色")
        val recordingColor by Settings.recordingColor.collectAsState()
        val previewColor by Settings.previewColor.collectAsState()

        Text("記録中", fontSize = 13.sp)
        ColorPicker(
            selected = recordingColor,
            onSelect = { Settings.setRecordingColor(it) }
        )

        Text("プレビュー中", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        ColorPicker(
            selected = previewColor,
            onSelect = { Settings.setPreviewColor(it) }
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle("GPS時刻")
        val leapSeconds by Settings.leapSeconds.collectAsState()
        var leapText by remember(leapSeconds) { mutableStateOf(leapSeconds.toString()) }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("うるう秒（GPS - UTC）", fontSize = 14.sp)
                Text(
                    "Z-countの算出に使用します（初期値：18）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            OutlinedTextField(
                value = leapText,
                onValueChange = { input ->
                    if (input.length <= 2 && input.all { it.isDigit() }) {
                        leapText = input
                        input.toIntOrNull()?.let { Settings.setLeapSeconds(it) }
                    }
                },
                modifier = Modifier.width(72.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle("保存先")
        Text(
            "アプリ専用ディレクトリ\nAndroid/data/io.github.eightbrows.gpslogger/files/",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun ColorPicker(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Settings.trackColorOptions.forEach { color ->
            Box(
                Modifier
                    .size(28.dp)
                    .background(Color(color), CircleShape)
                    .border(
                        width = if (selected == color) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape
                    )
                    .clickable { onSelect(color) }
            )
        }
    }
}
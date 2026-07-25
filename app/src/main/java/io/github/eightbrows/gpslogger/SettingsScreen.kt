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
        SectionTitle("記録間隔")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Settings.intervalOptions.forEach { sec ->
                FilterChip(
                    selected = intervalSec == sec,
                    onClick = { Settings.setIntervalSec(sec) },
                    label = { Text("${sec}秒") },
                    enabled = !isLogging
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
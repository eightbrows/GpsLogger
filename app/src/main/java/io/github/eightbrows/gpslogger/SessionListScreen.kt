package io.github.eightbrows.gpslogger.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionListScreen(onSelect: (File) -> Unit) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<File>>(emptyList()) }

    // 画面表示のたびに一覧を読み直す
    LaunchedEffect(Unit) {
        val base = context.getExternalFilesDir(null)
        sessions = if (base != null) SessionReader.listSessions(base) else emptyList()
    }

    if (sessions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("記録がありません", color = MaterialTheme.colorScheme.outline)
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(sessions) { dir ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(dir) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(formatSessionName(dir.name), fontSize = 15.sp)
                Text(
                    describeSession(dir),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            HorizontalDivider()
        }
    }
}

/** session_20260720_143000 → 2026-07-20 14:30:00 */
private fun formatSessionName(name: String): String {
    val raw = name.removePrefix("session_")
    return runCatching {
        val parsed = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(raw)
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(parsed!!)
    }.getOrDefault(name)
}

/** ファイルサイズと更新時刻から概要を作る（全読み込みはしない） */
private fun describeSession(dir: File): String {
    val track = File(dir, "track.csv")
    val sats = File(dir, "sats.csv")
    val totalKb = (track.length() + sats.length()) / 1024
    val updated = SimpleDateFormat("HH:mm", Locale.US).format(Date(dir.lastModified()))
    return "更新 $updated ・ ${totalKb} KB"
}
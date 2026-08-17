package io.github.eightbrows.gpslogger.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(onSelect: (File) -> Unit) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showConfirm by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        val base = context.getExternalFilesDir(null)
        sessions = if (base != null) SessionReader.listSessions(base) else emptyList()
    }

    fun exitSelectMode() {
        selectMode = false
        selected = emptySet()
    }

    Column(Modifier.fillMaxSize()) {
        // 選択モードのツールバー
        if (selectMode) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { exitSelectMode() }) {
                        Icon(Icons.Filled.Close, contentDescription = "選択解除")
                    }
                    Text("${selected.size} 件選択", fontSize = 14.sp)
                }
                IconButton(
                    onClick = { if (selected.isNotEmpty()) showConfirm = true },
                    enabled = selected.isNotEmpty()
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "削除",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            HorizontalDivider()
        }

        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("記録がありません", color = MaterialTheme.colorScheme.outline)
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(sessions) { dir ->
                val isSelected = dir.name in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surface
                        )
                        .combinedClickable(
                            onClick = {
                                if (selectMode) {
                                    selected = if (isSelected) selected - dir.name
                                    else selected + dir.name
                                    if (selected.isEmpty()) selectMode = false
                                } else {
                                    onSelect(dir)
                                }
                            },
                            onLongClick = {
                                if (!selectMode) {
                                    selectMode = true
                                    selected = setOf(dir.name)
                                }
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = null,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                    Column {
                        Text(formatSessionName(dir.name), fontSize = 15.sp)
                        Text(
                            describeSession(dir),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }

    // 削除確認ダイアログ
    if (showConfirm) {
        val targets = sessions.filter { it.name in selected }
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("記録を削除") },
            text = {
                Column {
                    Text("以下の ${targets.size} 件を削除します。", fontSize = 14.sp)
                    Text(
                        "この操作は取り消せません。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    targets.take(10).forEach {
                        Text("・${formatSessionName(it.name)}", fontSize = 12.sp)
                    }
                    if (targets.size > 10) {
                        Text("ほか ${targets.size - 10} 件", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    targets.forEach { it.deleteRecursively() }
                    showConfirm = false
                    exitSelectMode()
                    reloadKey++
                }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("キャンセル") }
            }
        )
    }
}

/** session_20260720_143000 → 2026-07-20 (月) 14:30:00 */
private fun formatSessionName(name: String): String {
    val raw = name.removePrefix("session_")
    return runCatching {
        val parsed = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(raw)
        SimpleDateFormat("yyyy-MM-dd (E) HH:mm:ss", Locale.JAPAN).format(parsed!!)
    }.getOrDefault(name)
}

/** ファイルサイズから概算した点数と容量 */
private fun describeSession(dir: File): String {
    val track = File(dir, "track.csv")
    val sats = File(dir, "sats.csv")
    val totalKb = (track.length() + sats.length()) / 1024
    val approxPoints = ((track.length() - TRACK_HEADER_BYTES) / TRACK_ROW_BYTES)
        .coerceAtLeast(0)
    return "約 ${approxPoints} 点 ・ ${totalKb} KB"
}

private const val TRACK_HEADER_BYTES = 180L
private const val TRACK_ROW_BYTES = 180L
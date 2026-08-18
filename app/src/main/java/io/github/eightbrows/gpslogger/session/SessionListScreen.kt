package io.github.eightbrows.gpslogger.session

import androidx.compose.foundation.background
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import androidx.compose.runtime.collectAsState
import io.github.eightbrows.gpslogger.state.GnssStateHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(onSelect: (File) -> Unit) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showConfirm by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    var importing by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<ImportResult?>(null) }

    var exporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var exportError by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { importZip(context, uri) }
                importing = false
                importResult = result
                reloadKey++
            }
        }
    }

    val currentSession by GnssStateHolder.currentSessionDir.collectAsState()

    fun exitSelectMode() {
        selectMode = false
        selected = emptySet()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            val targets = sessions.filter { it.name in selected && it.name != currentSession?.name }
            exporting = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                                targets.forEach { dir ->
                                    dir.listFiles()?.forEach { file ->
                                        zip.putNextEntry(ZipEntry("${dir.name}/${file.name}"))
                                        file.inputStream().use { it.copyTo(zip) }
                                        zip.closeEntry()
                                    }
                                }
                            }
                        } != null
                    }.getOrDefault(false)
                }
                exporting = false
                if (ok) {
                    exitSelectMode()
                } else {
                    exportError = "エクスポートに失敗しました"
                }
            }
        }
    }

    LaunchedEffect(reloadKey) {
        val base = context.getExternalFilesDir(null)
        sessions = if (base != null) SessionReader.listSessions(base) else emptyList()
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
                    IconButton(
                        onClick = { if (selected.isNotEmpty()) exportLauncher.launch(defaultZipName(selected)) },
                        enabled = selected.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "エクスポート")
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
            }
            HorizontalDivider()
        }

        if (exporting) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  エクスポート中…", fontSize = 12.sp)
            }
        }

        if (sessions.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("記録がありません", color = MaterialTheme.colorScheme.outline)
                if (importing) {
                    Row(
                        Modifier.padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text("  インポート中…", fontSize = 13.sp)
                    }
                } else {
                    TextButton(onClick = {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    }) {
                        Text("ZIPからインポート")
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(sessions) { dir ->
                    val isRecording = currentSession?.name == dir.name
                    val isSelected = dir.name in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else -> MaterialTheme.colorScheme.surface
                                }
                            )
                            .combinedClickable(
                                onClick = {
                                    if (selectMode) {
                                        // 記録中は選択させない
                                        if (!isRecording) {
                                            selected = if (isSelected) selected - dir.name
                                            else selected + dir.name
                                            if (selected.isEmpty()) selectMode = false
                                        }
                                    } else {
                                        onSelect(dir)
                                    }
                                },
                                onLongClick = {
                                    if (!selectMode && !isRecording) {
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
                                enabled = !isRecording,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatSessionName(dir.name), fontSize = 15.sp)
                                if (isRecording) {
                                    Text(
                                        "  ● 記録中",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Text(
                                describeSession(dir),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    HorizontalDivider()
                }

                item {
                    Box(
                        Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (importing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text("  インポート中…", fontSize = 13.sp)
                            }
                        } else {
                            TextButton(onClick = {
                                importLauncher.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/octet-stream"
                                    )
                                )
                            }) {
                                Text("ZIPからインポート")
                            }
                        }
                    }
                }
            }
        }
    }

    // 削除確認ダイアログ
    if (showConfirm) {
        val targets = sessions.filter { it.name in selected && it.name != currentSession?.name }
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

    importResult?.let { result ->
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = { Text("インポート結果") },
            text = {
                Column {
                    if (result.error != null) {
                        Text(result.error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                    } else {
                        Text("${result.imported} 件のセッションをインポートしました", fontSize = 14.sp)
                    }
                    if (result.skipped > 0) {
                        Text(
                            "${result.skipped} 件のファイルは形式が不正のためスキップしました",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { importResult = null }) { Text("OK") }
            }
        )
    }

    exportError?.let { message ->
        AlertDialog(
            onDismissRequest = { exportError = null },
            title = { Text("エクスポート") },
            text = { Text(message, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { exportError = null }) { Text("OK") }
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

/** エクスポート時の既定ファイル名 */
private fun defaultZipName(selected: Set<String>): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return if (selected.size == 1) "${selected.first()}.zip"
    else "gpslogger_${selected.size}sessions_$stamp.zip"
}

/** インポート結果 */
private data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val error: String? = null
)

/** エントリ名の許可パターン（これ以外は全て拒否） */
private val ENTRY_PATTERN = Regex("^(session_\\d{8}_\\d{6})/(track|sats)\\.csv$")

private fun importZip(
    context: android.content.Context,
    uri: android.net.Uri
): ImportResult {
    val baseDir = context.getExternalFilesDir(null)
        ?: return ImportResult(0, 0, "保存先が利用できません")
    val basePath = baseDir.canonicalPath + File.separator

    val importedSessions = mutableSetOf<String>()
    var skipped = 0

    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val match = ENTRY_PATTERN.matchEntire(entry.name)
                    if (match == null || entry.isDirectory) {
                        skipped++
                    } else {
                        val sessionName = match.groupValues[1]
                        val sessionDir = File(baseDir, sessionName)
                        val target = File(sessionDir, entry.name.substringAfterLast('/'))

                        // 展開先が想定ディレクトリ配下か確認
                        if (!target.canonicalPath.startsWith(basePath)) {
                            skipped++
                        } else {
                            sessionDir.mkdirs()
                            target.outputStream().use { out -> zip.copyTo(out) }
                            importedSessions.add(sessionName)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: return ImportResult(0, 0, "ファイルを開けません")

        ImportResult(importedSessions.size, skipped)
    }.getOrElse {
        ImportResult(importedSessions.size, skipped, "読み込みに失敗しました")
    }
}
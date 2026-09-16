package io.github.eightbrows.gpslogger.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(onSelect: (File) -> Unit) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showConfirm by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    // 初回の一覧取得が終わるまで「記録がありません」を出さない
    var listLoaded by remember { mutableStateOf(false) }

    // セッション名 → タグ。読み込み済みの分で先に表示し、最新の読み込み結果で差し替える
    var tagsByName by remember { mutableStateOf(SessionTagCache.cached()) }
    // 絞り込みに使うタグ（AND 条件）。再生画面から戻っても残す
    var selectedTags by remember { mutableStateOf(TagFilterMemory.selected) }

    // 絞り込み後に表示するセッション。選択・全選択・削除・エクスポートはすべてこちらが対象
    val visibleSessions = remember(sessions, tagsByName, selectedTags) {
        if (selectedTags.isEmpty()) sessions
        else sessions.filter { TagFilter.matches(tagsByName[it.name].orEmpty(), selectedTags) }
    }
    // 絞り込みの候補は、絞り込み前の全セッションから集める（チップの並びが選択で変わらないように）
    val tagCounts = remember(sessions, tagsByName) {
        TagFilter.collect(tagsByName, sessions.map { it.name })
    }

    /** 見えなくなったセッションを選択から外す（見えない記録を削除・エクスポートしないため） */
    fun keepSelectionWithin(visibleNames: Set<String>) {
        if (selected.any { it !in visibleNames }) {
            selected = selected.filterTo(HashSet()) { it in visibleNames }
            if (selected.isEmpty()) selectMode = false
        }
    }

    fun applyTagFilter(tags: Set<String>) {
        selectedTags = tags
        TagFilterMemory.selected = tags
        val names = TagFilter.filter(sessions.map { it.name }, tagsByName, tags)
        keepSelectionWithin(names.toSet())
    }

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
            val targets = visibleSessions.filter { it.name in selected && it.name != currentSession?.name }
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

    // 一覧とタグの読み込みはIOスレッドで。タグは変わったセッションの分だけ読み直す
    LaunchedEffect(reloadKey) {
        val base = context.getExternalFilesDir(null)
        val list = withContext(Dispatchers.IO) {
            if (base != null) SessionReader.listSessions(base) else emptyList()
        }
        sessions = list
        listLoaded = true

        val tags = withContext(Dispatchers.IO) { SessionTagCache.load(list) }
        tagsByName = tags

        // どのセッションにも無くなったタグは選択から外す（削除・編集の後など）
        val existing = tags.values.flatten().toSet()
        if (!existing.containsAll(selectedTags)) {
            applyTagFilter(selectedTags.intersect(existing))
        }
    }

    // 一覧の再読み込みで見えなくなったセッションも選択から外す
    LaunchedEffect(visibleSessions) {
        keepSelectionWithin(visibleSessions.mapTo(HashSet()) { it.name })
    }

    Column(Modifier.fillMaxSize()) {
        // 常設ツールバー
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 左: 補助操作
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { exitSelectMode() },
                    enabled = selectMode
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "選択解除")
                }
                IconButton(
                    onClick = {
                        // 絞り込み中は表示されているセッションだけを選ぶ
                        val selectable = visibleSessions
                            .filter { it.name != currentSession?.name }
                            .map { it.name }
                            .toSet()
                        if (selectable.isNotEmpty()) {
                            selectMode = true
                            selected = selectable
                        }
                    },
                    enabled = visibleSessions.isNotEmpty()
                ) {
                    Icon(Icons.Filled.SelectAll, contentDescription = "全選択")
                }
                if (selectMode) {
                    Text("${selected.size} 件", fontSize = 13.sp)
                }
            }

            // 右: 主要操作
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                }) {
                    Icon(Icons.Filled.FileDownload, contentDescription = "インポート")
                }
                IconButton(
                    onClick = { if (selected.isNotEmpty()) exportLauncher.launch(defaultZipName(selected)) },
                    enabled = selected.isNotEmpty()
                ) {
                    Icon(Icons.Filled.FileUpload, contentDescription = "エクスポート")
                }
                IconButton(
                    onClick = { if (selected.isNotEmpty()) showConfirm = true },
                    enabled = selected.isNotEmpty()
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "削除",
                        tint = if (selected.isNotEmpty()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
            }
        }
        HorizontalDivider()

        // タグによる絞り込み。タグを持つセッションが無ければ出さない
        if (tagCounts.isNotEmpty()) {
            TagFilterBar(
                tags = tagCounts,
                selected = selectedTags,
                onToggle = { tag ->
                    applyTagFilter(if (tag in selectedTags) selectedTags - tag else selectedTags + tag)
                },
                onClear = { applyTagFilter(emptySet()) }
            )
            if (selectedTags.isNotEmpty()) {
                Text(
                    "${visibleSessions.size} / ${sessions.size} 件を表示（選択したタグをすべて含む記録）",
                    Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
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

        when {
            !listLoaded -> Unit

            sessions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("記録がありません", color = MaterialTheme.colorScheme.outline)
            }

            visibleSessions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "選択したタグをすべて含む記録はありません",
                    color = MaterialTheme.colorScheme.outline
                )
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(visibleSessions, key = { it.name }) { dir ->
                    val isRecording = currentSession?.name == dir.name
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
                            val tags = tagsByName[dir.name].orEmpty()
                            if (tags.isNotEmpty()) {
                                Text(
                                    tags.joinToString("  ") { "#$it" },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    // 削除確認ダイアログ
    if (showConfirm) {
        val targets = visibleSessions.filter { it.name in selected && it.name != currentSession?.name }
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

/** 絞り込みの選択状態。一覧画面は再生画面へ移ると破棄されるので、プロセスの間ここに残す */
private object TagFilterMemory {
    var selected: Set<String> = emptySet()
}

/** タグのチップを横に並べる。はみ出す分は横スクロール */
@Composable
private fun TagFilterBar(
    tags: List<TagCount>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("タグ", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            tags.forEach { t ->
                FilterChip(
                    selected = t.tag in selected,
                    onClick = { onToggle(t.tag) },
                    label = { Text("${t.tag}  ${t.count}", fontSize = 12.sp) }
                )
            }
        }
        if (selected.isNotEmpty()) {
            TextButton(onClick = onClear) { Text("解除", fontSize = 12.sp) }
        }
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
    return "gpslogger_${stamp}_${selected.size}sessions.zip"
}

/** インポート結果 */
private data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val error: String? = null
)

/** エントリ名の許可パターン（これ以外は全て拒否） */
private val ENTRY_PATTERN = Regex("^(session_\\d{8}_\\d{6})/(track\\.csv|sats\\.csv|meta\\.json)$")

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
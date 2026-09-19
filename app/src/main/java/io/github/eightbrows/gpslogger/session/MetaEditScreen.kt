package io.github.eightbrows.gpslogger.session

import android.content.res.Resources
import androidx.compose.ui.platform.LocalResources
import androidx.annotation.StringRes
import io.github.eightbrows.gpslogger.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * meta.json の編集画面。階層を「パス → 値」の平坦な一覧で見せ、
 * 編集できる項目だけタップで値を変えられる。生の JSON は触らせない。
 */
@Composable
fun MetaEditScreen(sessionDir: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val resources = LocalResources.current

    var meta by remember { mutableStateOf<SessionMeta?>(null) }
    var fileExists by remember { mutableStateOf(true) }
    var editingPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionDir) {
        val loaded = withContext(Dispatchers.IO) { SessionReader.readMeta(sessionDir) }
        fileExists = loaded != null
        // 無い・壊れている場合は既定値から始め、保存時に新しく作る
        meta = loaded ?: SessionMeta()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.meta_title), fontSize = 14.sp)
                Text(
                    sessionDir.name,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider()

        val current = meta
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        if (!fileExists) {
            Text(
                stringResource(R.string.meta_missing),
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Text(
            stringResource(R.string.meta_hint_tap),
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(Modifier.fillMaxSize()) {
            items(MetaPaths.flatten(current), key = { it.path }) { entry ->
                MetaRow(entry, onClick = { editingPath = entry.path })
            }
        }
    }

    val path = editingPath
    val base = meta
    if (path != null && base != null) {
        val numeric = path != "comment" && path != "tags"
        MetaEditDialog(
            path = path,
            initialText = MetaPaths.editText(base, path),
            singleLine = path != "comment",
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            hint = stringResource(hintFor(path)),
            onDismiss = { editingPath = null },
            onSave = { input ->
                // 失敗時はメッセージを返し、ダイアログは閉じない
                when (val result = MetaPaths.apply(base, path, input)) {
                    is MetaEditResult.Failure -> failureMessage(resources, result)
                    is MetaEditResult.Success -> try {
                        withContext(Dispatchers.IO) {
                            result.meta.writeTo(sessionDir)
                            // 履歴一覧のタグ表示に反映させる
                            SessionTagCache.invalidate(sessionDir)
                        }
                        meta = result.meta
                        fileExists = true
                        editingPath = null
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        resources.getString(R.string.meta_save_failed, e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        )
    }
}

@StringRes
private fun hintFor(path: String): Int = when (MetaPaths.pattern(path)) {
    "comment" -> R.string.meta_hint_comment
    "tags" -> R.string.meta_hint_tags
    "basePressureHpa" -> R.string.meta_hint_base_pressure
    else -> R.string.meta_hint_segment_pressure
}

private fun failureMessage(resources: Resources, failure: MetaEditResult.Failure): String =
    when (failure.reason) {
        MetaEditResult.Reason.NOT_EDITABLE ->
            resources.getString(R.string.meta_error_not_editable)
        MetaEditResult.Reason.PRESSURE_REQUIRED ->
            resources.getString(R.string.meta_error_pressure_required)
        MetaEditResult.Reason.INVALID_PRESSURE ->
            resources.getString(R.string.meta_error_invalid_pressure)
        MetaEditResult.Reason.SEGMENT_NOT_FOUND ->
            resources.getString(R.string.meta_error_segment_not_found, failure.segmentIndex ?: -1)
    }

@Composable
private fun MetaRow(entry: MetaEntry, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val empty = entry.value.isNullOrEmpty()

    // 読み取り専用の行はクリックを受け付けない（押しても波紋が出ない）
    val clickable = if (entry.editable) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .then(clickable)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            entry.path,
            Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (entry.editable) scheme.primary else scheme.onSurfaceVariant
        )
        Text(
            when {
                entry.value == null -> stringResource(R.string.meta_unset)
                entry.value.isEmpty() -> stringResource(R.string.meta_empty)
                else -> entry.value
            },
            Modifier.weight(1.1f),
            fontSize = 13.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            color = when {
                empty -> scheme.outline
                entry.editable -> scheme.onSurface
                else -> scheme.onSurfaceVariant
            }
        )
        if (entry.editable) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(R.string.meta_edit),
                tint = scheme.primary,
                modifier = Modifier.size(16.dp)
            )
        } else {
            Spacer(Modifier.width(16.dp))
        }
    }
    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
}

/**
 * 1項目の入力ダイアログ。onSave はエラーメッセージ（成功なら null）を返す。
 * 保存中は閉じられないようにする。
 */
@Composable
private fun MetaEditDialog(
    path: String,
    initialText: String,
    singleLine: Boolean,
    keyboardType: KeyboardType,
    hint: String,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> String?
) {
    var text by remember(path) { mutableStateOf(initialText) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var saving by remember(path) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(path, fontFamily = FontFamily.Monospace, fontSize = 15.sp) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 3,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                supportingText = { Text(error ?: hint) }
            )
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        error = onSave(text)
                        saving = false
                    }
                }
            ) { Text(stringResource(if (saving) R.string.meta_saving else R.string.meta_save)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

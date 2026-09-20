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
import java.io.FileNotFoundException
import io.github.eightbrows.gpslogger.ui.BasePressureDialog
import io.github.eightbrows.gpslogger.ui.GeoidOffsetDialog
import io.github.eightbrows.gpslogger.ui.LeapSecondsDialog
import io.github.eightbrows.gpslogger.ui.formatGeoid
import io.github.eightbrows.gpslogger.ui.formatHpa
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.draw.clip

/**
 * meta.json の編集画面。階層を「パス → 値」の平坦な一覧で見せ、
 * 編集できる項目だけタップで値を変えられる。生の JSON は触らせない。
 */
@Composable
fun MetaEditScreen(sessionDir: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val resources = LocalResources.current

    var state by remember { mutableStateOf<MetaState>(MetaState.Loading) }
    var editingPath by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionDir) {
        state = withContext(Dispatchers.IO) {
            val loaded = SessionReader.readMeta(sessionDir)
            when {
                loaded != null -> MetaState.Loaded(loaded)
                else -> MetaState.Missing(broken = File(sessionDir, SessionMeta.FILE_NAME).exists())
            }
        }
    }

    /** track.csv を読み直して meta.json を作り、できたら編集できる一覧に切り替える */
    fun createFromTrack() {
        val missing = state as? MetaState.Missing ?: return
        state = MetaState.Creating
        scope.launch {
            state = try {
                MetaState.Loaded(withContext(Dispatchers.IO) { MetaBackfill.create(sessionDir) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                missing.copy(error = resources.getString(R.string.meta_create_no_track))
            } catch (e: Exception) {
                missing.copy(
                    error = resources.getString(R.string.meta_create_failed, e.message ?: e.javaClass.simpleName)
                )
            }
        }
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

        val current = when (val s = state) {
            MetaState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            MetaState.Creating -> {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.meta_creating),
                        Modifier.padding(top = 12.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }
            // meta.json が無いうちは編集させず、作成の案内だけを出す
            is MetaState.Missing -> {
                MissingMetaBanner(s, onCreate = ::createFromTrack)
                return@Column
            }
            is MetaState.Loaded -> s.meta
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

    // 編集は meta.json ができているとき（Loaded）だけ
    val path = editingPath
    val base = (state as? MetaState.Loaded)?.meta
    if (path != null && base != null) {
        /** 入力値を書き戻して保存する。失敗したらメッセージを返す（ダイアログは閉じない） */
        suspend fun save(input: String): String? =
            when (val result = MetaPaths.apply(base, path, input)) {
                is MetaEditResult.Failure -> failureMessage(resources, result)
                is MetaEditResult.Success -> try {
                    withContext(Dispatchers.IO) {
                        result.meta.writeTo(sessionDir)
                        // 履歴一覧のタグ表示に反映させる
                        SessionTagCache.invalidate(sessionDir)
                    }
                    state = MetaState.Loaded(result.meta)
                    editingPath = null
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    resources.getString(R.string.meta_save_failed, e.message ?: e.javaClass.simpleName)
                }
            }

        // 設定画面と同じ入力ダイアログを使う項目。値の書式は MetaPaths が受け付ける形に揃える
        val pattern = MetaPaths.pattern(path)
        val segment = pattern.startsWith("segments[")
        val unsetNote = stringResource(
            if (segment) R.string.meta_unset_uses_session else R.string.meta_unset_uses_app
        )
        val segmentValue = MetaPaths.segmentOf(base, path)

        when (pattern) {
            "basePressureHpa", "segments[*].basePressureHpa" -> BasePressureDialog(
                initialHpa = if (segment) segmentValue?.basePressureHpa else base.basePressureHpa,
                onDismiss = { editingPath = null },
                onSave = { value ->
                    scope.launch { save(value?.let(::formatHpa) ?: "") }
                },
                // セッション全体の基準気圧は未設定にできない（気圧高度の計算に必ず要る）
                allowUnset = segment,
                unsetNote = unsetNote
            )

            "geoidOffsetM", "segments[*].geoidOffsetM" -> GeoidOffsetDialog(
                initialM = if (segment) segmentValue?.geoidOffsetM else base.geoidOffsetM,
                onDismiss = { editingPath = null },
                onSave = { value ->
                    scope.launch { save(value?.let(::formatGeoid) ?: "") }
                },
                allowUnset = true,
                unsetNote = unsetNote
            )

            "leapSeconds" -> LeapSecondsDialog(
                initial = base.leapSeconds,
                onDismiss = { editingPath = null },
                onSave = { value ->
                    scope.launch { save(value?.toString() ?: "") }
                },
                allowUnset = true,
                unsetNote = unsetNote
            )

            else -> MetaEditDialog(
                path = path,
                initialText = MetaPaths.editText(base, path),
                singleLine = path != "comment",
                keyboardType = if (path == "comment" || path == "tags") KeyboardType.Text
                else KeyboardType.Decimal,
                hint = stringResource(hintFor(path)),
                onDismiss = { editingPath = null },
                onSave = { input -> save(input) }
            )
        }
    }
}

/** 編集画面の状態 */
private sealed interface MetaState {
    /** meta.json を読んでいる */
    data object Loading : MetaState

    /**
     * meta.json が無い（broken = true ならあるが読めない）。
     * error は直前の作成に失敗したときのメッセージ
     */
    data class Missing(val broken: Boolean, val error: String? = null) : MetaState

    /** track.csv から meta.json を作っている */
    data object Creating : MetaState

    /** meta.json がある。この状態のときだけ編集できる */
    data class Loaded(val meta: SessionMeta) : MetaState
}

/** meta.json が無いときの案内。タップで track.csv から作成する */
@Composable
private fun MissingMetaBanner(state: MetaState.Missing, onCreate: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.secondaryContainer)
            .clickable(onClick = onCreate)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = scheme.onSecondaryContainer,
            modifier = Modifier.size(20.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (state.broken) R.string.meta_broken else R.string.meta_missing),
                fontSize = 14.sp,
                color = scheme.onSecondaryContainer
            )
            Text(
                stringResource(if (state.broken) R.string.meta_recreate_action else R.string.meta_create_action),
                Modifier.padding(top = 4.dp),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = scheme.onSecondaryContainer.copy(alpha = 0.8f)
            )
            state.error?.let {
                Text(
                    it,
                    Modifier.padding(top = 6.dp),
                    fontSize = 12.sp,
                    color = scheme.error
                )
            }
        }
    }
}

@StringRes
private fun hintFor(path: String): Int = when (MetaPaths.pattern(path)) {
    "comment" -> R.string.meta_hint_comment
    "tags" -> R.string.meta_hint_tags
    "basePressureHpa" -> R.string.meta_hint_base_pressure
    "geoidOffsetM" -> R.string.meta_hint_geoid
    "segments[*].geoidOffsetM" -> R.string.meta_hint_segment_geoid
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
        MetaEditResult.Reason.INVALID_GEOID ->
            resources.getString(R.string.meta_error_invalid_geoid)
        MetaEditResult.Reason.INVALID_LEAP_SECONDS ->
            resources.getString(R.string.meta_error_invalid_leap_seconds)
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

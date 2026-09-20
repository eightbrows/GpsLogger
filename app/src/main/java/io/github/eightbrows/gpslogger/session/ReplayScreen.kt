package io.github.eightbrows.gpslogger.session

import io.github.eightbrows.gpslogger.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import io.github.eightbrows.gpslogger.ui.TrajectoryViewport
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import io.github.eightbrows.gpslogger.calc.BasePressureResolver
import io.github.eightbrows.gpslogger.state.BarometerReader
import io.github.eightbrows.gpslogger.state.TrackPoint
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eightbrows.gpslogger.state.ViewSnapshot
import io.github.eightbrows.gpslogger.ui.BottomPager
import io.github.eightbrows.gpslogger.ui.SplitScreen
import io.github.eightbrows.gpslogger.ui.TrajectoryPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedIconButton
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import io.github.eightbrows.gpslogger.state.GnssStateHolder

/**
 * 再生中のセッションと、その再生状態（シーク位置・軌跡の表示範囲・下ペインのページ・読み込んだ記録）。
 * 画面の外（AppRoot）に持たせ、記録タブ・設定タブへ移って戻っても同じ状態で再開できるようにする。
 * 別のセッションを開くときは新しく作る（シークは先頭・等倍・1 ページ目から）。
 */
@Stable
class ReplaySessionState(val sessionDir: File) {
    /** シーク位置 0.0〜1.0 */
    var position by mutableFloatStateOf(0f)

    /** 下ペインのページ 0〜3（数値・衛星リスト・上空図・高度） */
    var page by mutableIntStateOf(0)

    /** 軌跡ペインのズーム倍率・パン位置 */
    val viewport = TrajectoryViewport()

    /** 読み込み済みの記録（戻ってきたときに読み直さない）。loaded が false の間は未読み込み */
    var data by mutableStateOf<SessionData?>(null)
    var loaded by mutableStateOf(false)

    /** 表示用の測位点（基準気圧を解決済み）。data.track と同じ並び・同じ件数 */
    var trackPoints by mutableStateOf<List<TrackPoint>>(emptyList())

    /** このセッションの meta.json のうるう秒。null ならアプリ全体の設定値を使う */
    var leapSeconds by mutableStateOf<Int?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplayScreen(state: ReplaySessionState, onBack: () -> Unit) {
    val sessionDir = state.sessionDir
    val data = state.data
    val loading = !state.loaded
    var position by state::position
    val trackPoints = state.trackPoints
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMetaEditor by remember { mutableStateOf(false) }
    // 編集画面から戻るたびに増やし、基準気圧を解決し直す
    var metaRevision by remember { mutableIntStateOf(0) }
    val currentSession by GnssStateHolder.currentSessionDir.collectAsState()
    val isRecordingThis = currentSession?.name == sessionDir.name

    // 読み込みはIOスレッドで。読み込み済みなら読み直さない（他のタブから戻ったとき）。
    // ただし記録中のセッションは点が増えていくので、開くたびに読み直す（シーク位置などは保つ）
    LaunchedEffect(state) {
        if (state.loaded && !isRecordingThis) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { SessionReader.read(sessionDir) }
        val meta = loadMeta(sessionDir)
        state.leapSeconds = meta?.leapSeconds
        // 表示用の点も読み込み時にまとめて作る（再生位置を動かすたびに区間を探さない）
        state.trackPoints = loaded?.let { buildTrackPoints(meta, it.track) } ?: emptyList()
        state.data = loaded
        state.loaded = true
    }

    // meta.json を編集したら基準気圧だけ解決し直す（記録本体は読み直さない・再生位置も保つ）
    LaunchedEffect(metaRevision) {
        if (metaRevision == 0) return@LaunchedEffect
        val track = data?.track ?: return@LaunchedEffect
        val meta = loadMeta(sessionDir)
        state.leapSeconds = meta?.leapSeconds
        state.trackPoints = buildTrackPoints(meta, track)
    }

    // 記録情報（meta.json）の編集画面。戻ると再生画面へ（読み込み済みの記録と再生位置は保持）
    if (showMetaEditor) {
        MetaEditScreen(
            sessionDir = sessionDir,
            onBack = {
                showMetaEditor = false
                metaRevision++
            }
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        // ヘッダ
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(sessionDir.name, Modifier.weight(1f), fontSize = 12.sp)
            // 記録中のセッションは停止時に meta.json が書き出され、編集が上書きされるので開かせない
            IconButton(
                onClick = { showMetaEditor = true },
                enabled = !isRecordingThis,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.replay_edit_meta),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = { showDeleteConfirm = true },
                enabled = !isRecordingThis,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = if (isRecordingThis) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        HorizontalDivider()

        val session = data
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            session == null || session.track.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text(stringResource(R.string.replay_load_failed)) }

            else -> {
                val track = session.track
                val index = ((track.size - 1) * position).toInt().coerceIn(0, track.size - 1)
                val record = track[index]

                // 選択時点の衛星を最近傍ジョインで取得（±1秒）
                val satEpoch = session.findNearestSatEpoch(record.elapsedRealtimeNs)

                val snapshot = ViewSnapshot(
                    latitude = record.latitude,
                    longitude = record.longitude,
                    altitude = record.altitude,
                    accuracy = record.accuracy,
                    verticalAccuracy = record.verticalAccuracy,
                    speed = record.speed,
                    bearing = record.bearing,
                    bearingAccuracy = record.bearingAccuracy,
                    satellites = satEpoch?.satellites ?: emptyList(),
                    dop = record.dop,
                    pressureHpa = record.pressureHpa,
                    basePressureHpa = trackPoints.getOrNull(index)?.basePressureHpa
                        ?: BarometerReader.STANDARD_PRESSURE_HPA,
                    trackPoints = trackPoints,
                    leapSeconds = state.leapSeconds,
                    timeMs = record.epochMs,
                    markerIndex = index,
                    sessionStartMs = track.firstOrNull()?.epochMs ?: 0L,
                    sessionEndMs = track.lastOrNull()?.epochMs ?: 0L
                )

                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        SplitScreen(
                            top = {
                                TrajectoryPane(
                                    snapshot = snapshot,
                                    onSelectIndex = { index ->
                                        position = if (track.size > 1) {
                                            index.toFloat() / (track.size - 1)
                                        } else 0f
                                    },
                                    viewport = state.viewport
                                )
                            },
                            bottom = {
                                BottomPager(
                                    snapshot,
                                    initialPage = state.page,
                                    onPageChanged = { state.page = it }
                                ) { index ->
                                    position = if (track.size > 1) {
                                        index.toFloat() / (track.size - 1)
                                    } else 0f
                                }
                            }
                        )
                    }

                    // シークバー
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StepButton(
                                icon = Icons.Filled.SkipPrevious,
                                contentDescription = stringResource(R.string.replay_previous),
                                enabled = index > 0,
                                onStep = {
                                    val step = 1f / (track.size - 1).coerceAtLeast(1)
                                    position = (position - step).coerceIn(0f, 1f)
                                }
                            )

                            Slider(
                                value = position,
                                onValueChange = { position = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 32.dp),
                                thumb = {
                                    Box(
                                        Modifier
                                            .size(width = 16.dp, height = 32.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary,
                                                RoundedCornerShape(6.dp)
                                            )
                                    )
                                }
                            )

                            StepButton(
                                icon = Icons.Filled.SkipNext,
                                contentDescription = stringResource(R.string.replay_next),
                                enabled = index < track.size - 1,
                                onStep = {
                                    val step = 1f / (track.size - 1).coerceAtLeast(1)
                                    position = (position + step).coerceIn(0f, 1f)
                                }
                            )
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatTime(record.epochMs), fontSize = 11.sp)
                            Text("${index + 1} / ${track.size}", fontSize = 11.sp)
                            Text(
                                if (satEpoch == null) stringResource(R.string.replay_sats_none)
                                else stringResource(R.string.replay_sats, satEpoch.satellites.size),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.replay_delete_title)) },
            text = {
                Column {
                    Text(formatSessionLabel(sessionDir.name), fontSize = 14.sp)
                    Text(
                        stringResource(R.string.undo_warning),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    sessionDir.deleteRecursively()
                    showDeleteConfirm = false
                    onBack()
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(epochMs))

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onStep: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed, enabled) {
        if (isPressed && enabled) {
            onStep()
            delay(400)
            var interval = 150L
            while (true) {
                onStep()
                delay(interval)
                if (interval > 40L) interval -= 15L
            }
        }
    }

    OutlinedIconButton(
        onClick = { },
        enabled = enabled,
        modifier = Modifier.size(36.dp),
        interactionSource = interactionSource
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
    }
}

/** meta.json を読む（IO スレッド）。無い・壊れていれば null */
private suspend fun loadMeta(sessionDir: File): SessionMeta? =
    withContext(Dispatchers.IO) { SessionReader.readMeta(sessionDir) }

/**
 * CSV の記録を表示用の測位点に変換し、各点の基準気圧を meta.json から解決する。
 * 全点の解決は IO スレッドでまとめて行う。
 */
private suspend fun buildTrackPoints(meta: SessionMeta?, track: List<TrackRecord>): List<TrackPoint> =
    withContext(Dispatchers.IO) {
        val resolver = BasePressureResolver(meta)
        track.map {
            TrackPoint(
                latitude = it.latitude,
                longitude = it.longitude,
                altitude = it.altitude,
                timeMs = it.epochMs,
                gapBefore = it.gapBefore,
                pressureHpa = it.pressureHpa,
                basePressureHpa = resolver.at(it.epochMs)
            )
        }
    }

/** session_20260720_143000 → 2026-07-20 14:30:00 */
private fun formatSessionLabel(name: String): String {
    val raw = name.removePrefix("session_")
    return runCatching {
        val parsed = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(raw)
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(parsed!!)
    }.getOrDefault(name)
}
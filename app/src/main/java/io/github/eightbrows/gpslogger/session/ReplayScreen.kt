package io.github.eightbrows.gpslogger.session

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import io.github.eightbrows.gpslogger.state.GnssStateHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplayScreen(sessionDir: File, onBack: () -> Unit) {
    var data by remember { mutableStateOf<SessionData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var position by remember { mutableFloatStateOf(0f) }  // 0.0〜1.0
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val currentSession by GnssStateHolder.currentSessionDir.collectAsState()
    val isRecordingThis = currentSession?.name == sessionDir.name

    // 読み込みはIOスレッドで
    LaunchedEffect(sessionDir) {
        loading = true
        data = withContext(Dispatchers.IO) { SessionReader.read(sessionDir) }
        position = 0f
        loading = false
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
            }
            Text(sessionDir.name, Modifier.weight(1f), fontSize = 12.sp)
            IconButton(
                onClick = { showDeleteConfirm = true },
                enabled = !isRecordingThis,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "削除",
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
            ) { Text("記録を読み込めませんでした") }

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
                    satellites = satEpoch?.satellites ?: emptyList(),
                    dop = record.dop,
                    trackPoints = track.map {
                        io.github.eightbrows.gpslogger.state.TrackPoint(
                            latitude = it.latitude,
                            longitude = it.longitude,
                            altitude = it.altitude,
                            timeMs = it.epochMs
                        )
                    },
                    timeMs = record.epochMs,
                    markerIndex = index,
                    sessionStartMs = track.firstOrNull()?.epochMs ?: 0L,
                    sessionEndMs = track.lastOrNull()?.epochMs ?: 0L
                )

                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        SplitScreen(
                            top = { TrajectoryPane(snapshot) },
                            bottom = {
                                BottomPager(snapshot) { index ->
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
                                contentDescription = "前へ",
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
                                contentDescription = "次へ",
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
                                if (satEpoch == null) "衛星: 該当なし"
                                else "衛星: ${satEpoch.satellites.size}",
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
            title = { Text("記録を削除") },
            text = {
                Column {
                    Text(formatSessionLabel(sessionDir.name), fontSize = 14.sp)
                    Text(
                        "この操作は取り消せません。",
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
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("キャンセル") }
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

/** session_20260720_143000 → 2026-07-20 14:30:00 */
private fun formatSessionLabel(name: String): String {
    val raw = name.removePrefix("session_")
    return runCatching {
        val parsed = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(raw)
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(parsed!!)
    }.getOrDefault(name)
}
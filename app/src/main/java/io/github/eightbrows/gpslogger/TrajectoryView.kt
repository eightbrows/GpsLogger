package io.github.eightbrows.gpslogger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import io.github.eightbrows.gpslogger.state.ViewSnapshot

@Composable
fun TrajectoryPane(
    snapshot: ViewSnapshot,
    modifier: Modifier = Modifier
) {
    val points = snapshot.trackPoints
    val lineColor = MaterialTheme.colorScheme.primary
    val currentColor = MaterialTheme.colorScheme.error
    val startColor = MaterialTheme.colorScheme.outline

    // ズーム倍率と、追従解除中の平行移動量（px）
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    // 追従ON = 常に全体を自動フィット
    var following by remember { mutableStateOf(true) }

    Box(modifier.fillMaxSize()) {
        if (points.size < 2) {
            // 軌跡が無い場合: 現在地だけを中央に表示
            if (snapshot.latitude != null && snapshot.longitude != null) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        currentColor,
                        6.dp.toPx(),
                        androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                    )
                }
                Text(
                    "測位中（記録なし）",
                    Modifier.align(Alignment.TopStart).padding(8.dp),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                Text(
                    "測位待ち…",
                    Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            // 触った時点で追従解除
                            following = false
                            zoom = (zoom * gestureZoom).coerceIn(0.5f, 50f)
                            panX += pan.x
                            panY += pan.y
                        }
                    }
            ) {
                // バウンディングボックス
                var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
                var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
                points.forEach { (lat, lon) ->
                    if (lat < minLat) minLat = lat
                    if (lat > maxLat) maxLat = lat
                    if (lon < minLon) minLon = lon
                    if (lon > maxLon) maxLon = lon
                }

                val centerLat = (minLat + maxLat) / 2.0
                val lonScale = cos(centerLat * PI / 180.0)
                val spanLat = max(maxLat - minLat, 1e-7)
                val spanLon = max((maxLon - minLon) * lonScale, 1e-7)

                // 全体フィットの基準スケール
                val baseScale = minOf(size.width / spanLon, size.height / spanLat).toFloat()
                val scale = baseScale * zoom

                val drawW = (spanLon * scale).toFloat()
                val drawH = (spanLat * scale).toFloat()
                val offsetX = (size.width - drawW) / 2f + panX
                val offsetY = (size.height - drawH) / 2f + panY

                fun toScreen(lat: Double, lon: Double): Offset {
                    val x = offsetX + ((lon - minLon) * lonScale * scale).toFloat()
                    val y = offsetY + drawH - ((lat - minLat) * scale).toFloat()
                    return Offset(x, y)
                }

                // 縮尺連動の間引き（ズームすると自然に点が増える）
                val minPixelGap = 2.dp.toPx()
                val screenPoints = ArrayList<Offset>(points.size)
                var last: Offset? = null
                points.forEach { (lat, lon) ->
                    val p = toScreen(lat, lon)
                    val prev = last
                    if (prev == null ||
                        abs(p.x - prev.x) >= minPixelGap || abs(p.y - prev.y) >= minPixelGap
                    ) {
                        screenPoints.add(p)
                        last = p
                    }
                }
                val lastRaw = toScreen(points.last().first, points.last().second)
                if (screenPoints.lastOrNull() != lastRaw) screenPoints.add(lastRaw)

                if (screenPoints.size >= 2) {
                    val path = Path().apply {
                        moveTo(screenPoints[0].x, screenPoints[0].y)
                        for (i in 1 until screenPoints.size) {
                            lineTo(screenPoints[i].x, screenPoints[i].y)
                        }
                    }
                    drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
                }

                drawCircle(startColor, 4.dp.toPx(), screenPoints.first())

                // 選択位置があればそこ、なければ末尾（現在地）
                val markerIdx = snapshot.markerIndex
                val markerPos = if (markerIdx != null && markerIdx in points.indices) {
                    toScreen(points[markerIdx].first, points[markerIdx].second)
                } else {
                    screenPoints.last()
                }
                drawCircle(currentColor, 5.dp.toPx(), markerPos)
            }

            Text(
                "点数 ${points.size}  ×%.1f".format(zoom),
                Modifier.align(Alignment.TopStart).padding(8.dp),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.outline
            )

            // 兼用追従ボタン: ON=塗り / OFF=輪郭（タップで現在地へ戻り追従再開）
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
            ) {
                if (following) {
                    FilledIconButton(
                        onClick = { /* 追従中は何もしない */ },
                        colors = IconButtonDefaults.filledIconButtonColors()
                    ) {
                        Icon(Icons.Filled.LocationOn, contentDescription = "追従中")
                    }
                } else {
                    OutlinedIconButton(onClick = {
                        following = true
                        zoom = 1f
                        panX = 0f
                        panY = 0f
                    }) {
                        Icon(Icons.Filled.LocationOn, contentDescription = "現在地へ戻る")
                    }
                }
            }
        }
    }
}
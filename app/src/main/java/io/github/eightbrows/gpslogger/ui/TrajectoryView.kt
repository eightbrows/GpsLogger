package io.github.eightbrows.gpslogger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
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
import io.github.eightbrows.gpslogger.state.ViewSnapshot
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import io.github.eightbrows.gpslogger.settings.CoordFormat
import io.github.eightbrows.gpslogger.settings.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Delete
import io.github.eightbrows.gpslogger.state.GnssStateHolder

private const val MIN_ZOOM = 0.1f

/** 描画時に算出した追従位置を、ジェスチャ処理へ渡すための入れ物（状態ではない） */
private class TrajViewState {
    var followTx = 0f
    var followTy = 0f
    var maxZoom = 100f
    // タップ位置の逆変換に使う
    var ox = 0f
    var oy = 0f
    var scale = 0f
    var lonScale = 1.0
    var minLon = 0.0
    var maxLat = 0.0
}

@Composable
fun TrajectoryPane(
    snapshot: ViewSnapshot,
    modifier: Modifier = Modifier,
    onClearTrack: (() -> Unit)? = null,
    onSelectIndex: ((Int) -> Unit)? = null
) {
    val points = snapshot.trackPoints

    val recordingColor by Settings.recordingColor.collectAsState()
    val previewColor by Settings.previewColor.collectAsState()
    val lineColor = Color(if (snapshot.isRecording) recordingColor else previewColor)
    val currentColor = MaterialTheme.colorScheme.error
    val startColor = MaterialTheme.colorScheme.outlineVariant

    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val labelColor = MaterialTheme.colorScheme.outline

    val coordFormat by Settings.coordFormat.collectAsState()

    var zoom by remember { mutableFloatStateOf(1f) }
    // 平行移動量（追従OFF時の唯一の基準）
    var tx by remember { mutableFloatStateOf(0f) }
    var ty by remember { mutableFloatStateOf(0f) }
    var following by remember { mutableStateOf(true) }
    val view = remember { TrajViewState() }

    // 画面中心をアンカーにしたズーム（ボタン用）
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    fun applyZoom(factor: Float) {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, view.maxZoom)
        val k = newZoom / zoom
        if (!following && canvasSize != androidx.compose.ui.geometry.Size.Zero) {
            val cx = canvasSize.width / 2f
            val cy = canvasSize.height / 2f
            tx = cx - (cx - tx) * k
            ty = cy - (cy - ty) * k
        }
        zoom = newZoom
    }

    Box(modifier.fillMaxSize()) {
        if (points.size < 2) {
            if (snapshot.latitude != null && snapshot.longitude != null) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(currentColor, 6.dp.toPx(), Offset(size.width / 2f, size.height / 2f))
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
                    .pointerInput(points, onSelectIndex) {
                        if (onSelectIndex != null) {
                            detectTapGestures { tap ->
                                if (view.scale <= 0f) return@detectTapGestures
                                val lon = view.minLon + (tap.x - view.ox) / (view.lonScale * view.scale)
                                val lat = view.maxLat - (tap.y - view.oy) / view.scale

                                var best = 0
                                var bestD = Double.MAX_VALUE
                                points.forEachIndexed { i, p ->
                                    val dx = (p.longitude - lon) * view.lonScale
                                    val dy = p.latitude - lat
                                    val d = dx * dx + dy * dy
                                    if (d < bestD) { bestD = d; best = i }
                                }
                                onSelectIndex(best)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                            // ズーム: 指の中心を固定して拡大縮小
                            if (gestureZoom != 1f) {
                                val newZoom = (zoom * gestureZoom).coerceIn(MIN_ZOOM, view.maxZoom)
                                val k = newZoom / zoom
                                if (!following) {
                                    tx = centroid.x - (centroid.x - tx) * k
                                    ty = centroid.y - (centroid.y - ty) * k
                                }
                                zoom = newZoom
                            }
                            // 移動: 追従を解除し、その瞬間の見た目を引き継ぐ
                            if (abs(pan.x) > 0.5f || abs(pan.y) > 0.5f) {
                                if (following) {
                                    tx = view.followTx
                                    ty = view.followTy
                                    following = false
                                }
                                tx += pan.x
                                ty += pan.y
                            }
                        }
                    }
            ) {
                canvasSize = size

                // 外接矩形
                var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
                var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
                points.forEach { p ->
                    if (p.latitude < minLat) minLat = p.latitude
                    if (p.latitude > maxLat) maxLat = p.latitude
                    if (p.longitude < minLon) minLon = p.longitude
                    if (p.longitude > maxLon) maxLon = p.longitude
                }

                val centerLat = (minLat + maxLat) / 2.0
                val lonScale = cos(centerLat * PI / 180.0)
                // 表示範囲の下限を約1mにする（静止時の過剰な拡大を防ぐ）
                val minSpanDeg = 1.0 / 111_320.0   // 緯度1度 ≒ 111,320 m
                val spanLat = max(maxLat - minLat, minSpanDeg)
                val spanLon = max((maxLon - minLon) * lonScale, minSpanDeg)

                val baseScale = minOf(size.width / spanLon, size.height / spanLat).toFloat()
                // スケールバーが約1cmになる倍率を上限とする
                // スケールバー長 ≒ 画面幅の1/4 なので、画面幅 4cm 相当が限界
                val limitSpanMeters = 0.04
                val limitSpanDeg = limitSpanMeters / 111_320.0
                val maxScaleForLimit = (size.width / limitSpanDeg).toFloat()
                view.maxZoom = (maxScaleForLimit / baseScale).coerceAtLeast(1f)
                val scale = baseScale * zoom

                // 注目点（ライブ=現在地 / 再生=マーカー）を画面中央に置くための平行移動量
                val focus = snapshot.markerIndex?.let { points.getOrNull(it) } ?: points.last()
                val fwx = ((focus.longitude - minLon) * lonScale * scale).toFloat()
                val fwy = ((maxLat - focus.latitude) * scale).toFloat()
                view.followTx = size.width / 2f - fwx
                view.followTy = size.height / 2f - fwy

                val ox = if (following) view.followTx else tx
                val oy = if (following) view.followTy else ty

                fun toScreen(lat: Double, lon: Double): Offset = Offset(
                    ox + ((lon - minLon) * lonScale * scale).toFloat(),
                    oy + ((maxLat - lat) * scale).toFloat()
                )

                // タップ位置の逆変換用に保存
                view.ox = ox
                view.oy = oy
                view.scale = scale
                view.lonScale = lonScale
                view.minLon = minLon
                view.maxLat = maxLat

                // 画面座標 → 緯度経度（グリッドの範囲計算に使う）
                fun screenToLat(y: Float): Double = maxLat - (y - oy) / scale
                fun screenToLon(x: Float): Double = minLon + (x - ox) / (lonScale * scale)

                // 背景グリッド（軌跡より先に描く）
                MapGrid.drawGrid(
                    scope = this,
                    gridColor = gridColor,
                    labelColor = labelColor,
                    scale = scale,
                    lonScale = lonScale,
                    centerLat = centerLat,
                    toScreen = ::toScreen,
                    screenToLat = ::screenToLat,
                    screenToLon = ::screenToLon,
                    labelTextSizePx = 9.dp.toPx(),
                    dms = (coordFormat == CoordFormat.DMS)
                )

                // 縮尺連動の間引き
                val minPixelGap = 2.dp.toPx()
                val screenPoints = ArrayList<Offset>(points.size)
                var last: Offset? = null
                points.forEach { pt ->
                    val p = toScreen(pt.latitude, pt.longitude)
                    val prev = last
                    if (prev == null ||
                        abs(p.x - prev.x) >= minPixelGap || abs(p.y - prev.y) >= minPixelGap
                    ) {
                        screenPoints.add(p)
                        last = p
                    }
                }
                val lastRaw = toScreen(points.last().latitude, points.last().longitude)
                if (screenPoints.lastOrNull() != lastRaw) screenPoints.add(lastRaw)

                if (screenPoints.size >= 2) {
                    val path = Path().apply {
                        moveTo(screenPoints[0].x, screenPoints[0].y)
                        for (i in 1 until screenPoints.size) {
                            lineTo(screenPoints[i].x, screenPoints[i].y)
                        }
                    }
                    drawPath(
                        path,
                        lineColor,
                        style = Stroke(width = if (snapshot.isRecording) 2.5.dp.toPx() else 1.5.dp.toPx())
                    )
                }

                // 選択点に十字線（再生・レビュー時のみ）
                if (snapshot.markerIndex != null) {
                    val markerPos = toScreen(focus.latitude, focus.longitude)
                    val lineW = 1.dp.toPx()

                    drawLine(
                        currentColor.copy(alpha = 0.6f),
                        Offset(markerPos.x, 0f),
                        Offset(markerPos.x, size.height),
                        lineW
                    )
                    drawLine(
                        currentColor.copy(alpha = 0.6f),
                        Offset(0f, markerPos.y),
                        Offset(size.width, markerPos.y),
                        lineW
                    )
                }

                drawCircle(startColor, 4.dp.toPx(), screenPoints.first())
                drawCircle(currentColor, 5.dp.toPx(), toScreen(focus.latitude, focus.longitude))

                MapGrid.drawScaleBar(
                    scope = this,
                    barColor = labelColor,
                    scale = scale,
                    labelTextSizePx = 9.dp.toPx(),
                    topMarginPx = 14.dp.toPx()   // ← ここを調整
                )
            }

            Text(
                "点数 ${points.size}  ×%.1f".format(zoom),
                Modifier.align(Alignment.TopCenter).padding(vertical = 4.dp),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.outline
            )

            // ズーム操作＋追従ボタン
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SmallZoomButton("＋") { applyZoom(2f) }
                SmallZoomButton("－") { applyZoom(0.5f) }
                SmallZoomButton("1x") { applyZoom(1f / zoom) }

                // 記録していないときだけ軌跡クリア
                if (onClearTrack != null) {
                    OutlinedIconButton(
                        onClick = onClearTrack,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "軌跡をクリア",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (following) {
                    FilledIconButton(
                        onClick = { },
                        colors = IconButtonDefaults.filledIconButtonColors()
                    ) {
                        Icon(Icons.Filled.LocationOn, contentDescription = "追従中")
                    }
                } else {
                    OutlinedIconButton(onClick = { following = true }) {
                        Icon(Icons.Filled.LocationOn, contentDescription = "現在地へ戻る")
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallZoomButton(label: String, onClick: () -> Unit) {
    OutlinedIconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp)
    ) {
        Text(label, fontSize = 13.sp)
    }
}

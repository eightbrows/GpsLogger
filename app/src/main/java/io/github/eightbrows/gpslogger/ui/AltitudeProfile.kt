package io.github.eightbrows.gpslogger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eightbrows.gpslogger.state.ViewSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import io.github.eightbrows.gpslogger.state.BarometerReader


@Composable
fun AltitudePage(
    snapshot: ViewSnapshot,
    onSelectIndex: ((Int) -> Unit)? = null
) {
    val points = snapshot.trackPoints
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.outline
    val markerColor = MaterialTheme.colorScheme.error
    val baroColor = MaterialTheme.colorScheme.tertiary

    // 気圧高度（m）。生値から都度計算し、気圧が無い点は null。points と同じ並び
    val baroAlts: List<Double?> = remember(points) {
        points.map { p -> p.pressureHpa?.let { BarometerReader.altitudeM(it).toDouble() } }
    }
    val hasBaro = baroAlts.any { it != null }

    // 横軸のズームと移動（0.0〜1.0 の表示範囲）
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableFloatStateOf(0f) }  // 表示範囲の左端（0〜1-1/zoom）

    fun clampOffset() {
        val maxOffset = (1f - 1f / zoom).coerceAtLeast(0f)
        offset = offset.coerceIn(0f, maxOffset)
    }

    fun applyZoom(factor: Float) {
        val center = offset + 0.5f / zoom          // 現在の中心
        zoom = (zoom * factor).coerceIn(1f, 100f)
        offset = center - 0.5f / zoom              // 中心を保つ
        clampOffset()
    }

    Box(Modifier.fillMaxSize()) {
        if (points.size < 2) {
            Text(
                "データがありません",
                Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.outline
            )
            return@Box
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .padding(start = 40.dp, end = 12.dp, top = 12.dp, bottom = 34.dp)
                .pointerInput(zoom) {
                    if (zoom > 1f) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            offset -= dragAmount / size.width / zoom
                            clampOffset()
                        }
                    }
                }
                .pointerInput(points, zoom, offset) {
                    if (onSelectIndex != null) {
                        detectTapGestures { tap ->
                            // タップX座標から時刻を逆算し、最も近い点を選ぶ
                            val t0All = points.first().timeMs
                            val t1All = points.last().timeMs
                            val dtAll = max((t1All - t0All).toDouble(), 1.0)
                            val viewStart = t0All + (dtAll * offset).toLong()
                            val viewEnd = t0All + (dtAll * (offset + 1f / zoom)).toLong()
                            val dtView = max((viewEnd - viewStart).toDouble(), 1.0)

                            val tappedMs = viewStart + (tap.x / size.width * dtView).toLong()
                            var best = 0
                            var bestDiff = Long.MAX_VALUE
                            points.forEachIndexed { i, p ->
                                val d = kotlin.math.abs(p.timeMs - tappedMs)
                                if (d < bestDiff) { bestDiff = d; best = i }
                            }
                            onSelectIndex(best)
                        }
                    }
                }
        ) {
            // 時刻の全体範囲
            val t0All = points.first().timeMs
            val t1All = points.last().timeMs
            val dtAll = max((t1All - t0All).toDouble(), 1.0)

            // 表示範囲
            val viewStart = t0All + (dtAll * offset).toLong()
            val viewEnd = t0All + (dtAll * (offset + 1f / zoom)).toLong()
            val dtView = max((viewEnd - viewStart).toDouble(), 1.0)

            // 表示範囲内の点だけで高度レンジを決める（GPS・気圧の両方を含めて1つの縦軸にする）
            val useAll = points.count { it.timeMs in viewStart..viewEnd } < 2

            var minAlt = Double.MAX_VALUE
            var maxAlt = -Double.MAX_VALUE
            fun include(alt: Double) {
                if (alt < minAlt) minAlt = alt
                if (alt > maxAlt) maxAlt = alt
            }
            points.forEachIndexed { i, p ->
                if (useAll || p.timeMs in viewStart..viewEnd) {
                    include(p.altitude)
                    baroAlts[i]?.let { include(it) }
                }
            }
            val span = max(maxAlt - minAlt, 1.0)
            val pad = span * 0.05
            val lo = minAlt - pad
            val hi = maxAlt + pad

            fun toX(timeMs: Long): Float =
                ((timeMs - viewStart) / dtView * size.width).toFloat()

            fun toY(alt: Double): Float =
                ((hi - alt) / (hi - lo) * size.height).toFloat()

            val strokeW = 1.dp.toPx()
            val paint = android.graphics.Paint().apply {
                color = labelColor.toArgb()
                textSize = 9.dp.toPx()
                isAntiAlias = true
            }

            // 横グリッド（高度）
            val labelPaint = android.graphics.Paint(paint).apply {
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            listOf(0.0, 0.5, 1.0).forEach { frac ->
                val alt = lo + (hi - lo) * frac
                val y = toY(alt)
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeW)
                drawContext.canvas.nativeCanvas.drawText(
                    "%.0f".format(alt), -4f, y + paint.textSize / 3f, labelPaint
                )
            }

            // 表示範囲の点と、その前後1点（画面端まで線を繋ぐため）
            fun nearView(i: Int): Boolean {
                val prevIn = i > 0 && points[i - 1].timeMs in viewStart..viewEnd
                val inRange = points[i].timeMs in viewStart..viewEnd
                val nextIn = i < points.size - 1 && points[i + 1].timeMs in viewStart..viewEnd
                return inRange || prevIn || nextIn
            }

            // 気圧高度の折れ線。気圧が無い点で線を切り、前後を直線で結ばない。
            // GPS の線を上に重ねるため先に描く
            if (hasBaro) {
                val baroPath = Path()
                var penDown = false
                points.forEachIndexed { i, p ->
                    val b = baroAlts[i]
                    if (b == null || !nearView(i)) {
                        penDown = false
                        return@forEachIndexed
                    }
                    val x = toX(p.timeMs)
                    val y = toY(b)
                    if (!penDown) { baroPath.moveTo(x, y); penDown = true } else baroPath.lineTo(x, y)
                }
                drawPath(baroPath, baroColor, style = Stroke(width = 1.5.dp.toPx()))
            }

            // GPS高度の折れ線（表示範囲の前後1点を含めて線を繋ぐ）
            val path = Path()
            var started = false
            points.forEachIndexed { i, p ->
                if (nearView(i)) {
                    val x = toX(p.timeMs)
                    val y = toY(p.altitude)
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
            }
            if (started) drawPath(path, lineColor, style = Stroke(width = 1.5.dp.toPx()))

            // 時刻ラベル（表示範囲の両端）
            drawContext.canvas.nativeCanvas.drawText(
                timeFormat.format(Date(viewStart)), 0f, size.height + paint.textSize + 4f, paint
            )
            val endPaint = android.graphics.Paint(paint).apply {
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            drawContext.canvas.nativeCanvas.drawText(
                timeFormat.format(Date(viewEnd)), size.width, size.height + paint.textSize + 4f, endPaint
            )

            // 選択位置（再生時はmarkerIndex、ライブ時は末尾）
            val idx = snapshot.markerIndex ?: (points.size - 1)
            if (idx in points.indices) {
                val p = points[idx]
                if (p.timeMs in viewStart..viewEnd) {
                    val x = toX(p.timeMs)
                    drawLine(markerColor, Offset(x, 0f), Offset(x, size.height), strokeW * 1.5f)
                    drawCircle(markerColor, 3.dp.toPx(), Offset(x, toY(p.altitude)))

                    val selLabel = timeFormat.format(Date(p.timeMs))
                    val selPaint = android.graphics.Paint().apply {
                        color = markerColor.toArgb()
                        textSize = 9.dp.toPx()
                        isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val half = selPaint.measureText(selLabel) / 2f
                    val labelX = x.coerceIn(half, size.width - half)
                    drawContext.canvas.nativeCanvas.drawText(
                        selLabel, labelX,
                        size.height + selPaint.textSize * 2f + 8f,
                        selPaint
                    )
                }
            }
        }

        // 高度範囲と倍率
        val minA = points.minOf { it.altitude }
        val maxA = points.maxOf { it.altitude }
        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Text(
                "%.1f 〜 %.1f m  ×%.1f".format(minA, maxA, zoom),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.outline
            )
            // 凡例。気圧の線が無いときは従来どおり出さない
            if (hasBaro) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LegendSwatch(lineColor)
                    Text("GPS", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                    LegendSwatch(baroColor, Modifier.padding(start = 6.dp))
                    Text("気圧", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        // ズームボタン
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedIconButton(
                onClick = { applyZoom(2f) },
                modifier = Modifier.size(32.dp)
            ) { Text("＋", fontSize = 12.sp) }
            OutlinedIconButton(
                onClick = { applyZoom(0.5f) },
                modifier = Modifier.size(32.dp)
            ) { Text("－", fontSize = 12.sp) }
            OutlinedIconButton(
                onClick = { zoom = 1f; offset = 0f },
                modifier = Modifier.size(32.dp)
            ) { Text("1x", fontSize = 11.sp) }
        }
    }
}

/** 凡例の色見本（線を模した短い横棒） */
@Composable
private fun LegendSwatch(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(12.dp)
            .height(2.dp)
            .background(color)
    )
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
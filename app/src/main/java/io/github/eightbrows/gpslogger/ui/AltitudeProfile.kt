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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun AltitudePage(snapshot: ViewSnapshot) {
    val points = snapshot.trackPoints
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.outline
    val markerColor = MaterialTheme.colorScheme.error

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
                    // 拡大中のみ横ドラッグで移動（ページ切替との競合を避ける）
                    if (zoom > 1f) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            offset -= dragAmount / size.width / zoom
                            clampOffset()
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

            // 表示範囲内の点だけで高度レンジを決める
            val visible = points.filter { it.timeMs in viewStart..viewEnd }
            val target = if (visible.size >= 2) visible else points

            var minAlt = Double.MAX_VALUE
            var maxAlt = -Double.MAX_VALUE
            target.forEach {
                if (it.altitude < minAlt) minAlt = it.altitude
                if (it.altitude > maxAlt) maxAlt = it.altitude
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

            // 折れ線（表示範囲の前後1点を含めて線を繋ぐ）
            val path = Path()
            var started = false
            points.forEachIndexed { i, p ->
                val prevIn = i > 0 && points[i - 1].timeMs in viewStart..viewEnd
                val inRange = p.timeMs in viewStart..viewEnd
                val nextIn = i < points.size - 1 && points[i + 1].timeMs in viewStart..viewEnd
                if (inRange || prevIn || nextIn) {
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

            // 選択位置（表示範囲内のときだけ）
            val idx = snapshot.markerIndex
            if (idx != null && idx in points.indices) {
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
        Text(
            "%.1f 〜 %.1f m  ×%.1f".format(minA, maxA, zoom),
            Modifier.align(Alignment.TopStart).padding(8.dp),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.outline
        )

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

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
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

@Composable
fun AltitudePage(snapshot: ViewSnapshot) {
    val points = snapshot.trackPoints
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.outline
    val markerColor = MaterialTheme.colorScheme.error

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
                .padding(start = 40.dp, end = 12.dp, top = 12.dp, bottom = 22.dp)
        ) {
            // 高度の範囲（上下に5%の余白）
            var minAlt = Double.MAX_VALUE
            var maxAlt = -Double.MAX_VALUE
            points.forEach {
                if (it.altitude < minAlt) minAlt = it.altitude
                if (it.altitude > maxAlt) maxAlt = it.altitude
            }
            val span = max(maxAlt - minAlt, 1.0)
            val pad = span * 0.05
            val lo = minAlt - pad
            val hi = maxAlt + pad

            // 時刻の範囲
            val t0 = points.first().timeMs
            val t1 = points.last().timeMs
            val dt = max((t1 - t0).toDouble(), 1.0)

            fun toX(timeMs: Long): Float =
                ((timeMs - t0) / dt * size.width).toFloat()

            fun toY(alt: Double): Float =
                ((hi - alt) / (hi - lo) * size.height).toFloat()

            val strokeW = 1.dp.toPx()
            val paint = android.graphics.Paint().apply {
                color = labelColor.toArgb()
                textSize = 9.dp.toPx()
                isAntiAlias = true
            }

            // 横グリッド（高度）3本
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

            // 折れ線
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = toX(p.timeMs)
                val y = toY(p.altitude)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 1.5.dp.toPx()))

            // 選択位置の縦線
            val idx = snapshot.markerIndex
            if (idx != null && idx in points.indices) {
                val p = points[idx]
                val x = toX(p.timeMs)
                drawLine(markerColor, Offset(x, 0f), Offset(x, size.height), strokeW * 1.5f)
                drawCircle(markerColor, 3.dp.toPx(), Offset(x, toY(p.altitude)))
            }

            // 時刻ラベル（左端・右端）
            drawContext.canvas.nativeCanvas.drawText(
                timeFormat.format(Date(t0)), 0f, size.height + paint.textSize + 4f, paint
            )
            val endPaint = android.graphics.Paint(paint).apply {
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            drawContext.canvas.nativeCanvas.drawText(
                timeFormat.format(Date(t1)), size.width, size.height + paint.textSize + 4f, endPaint
            )
        }

        // 高度範囲の表示
        val minA = points.minOf { it.altitude }
        val maxA = points.maxOf { it.altitude }
        Text(
            "%.1f 〜 %.1f m".format(minA, maxA),
            Modifier.align(Alignment.TopEnd).padding(8.dp),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
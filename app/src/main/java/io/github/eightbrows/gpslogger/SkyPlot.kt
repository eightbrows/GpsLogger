package io.github.eightbrows.gpslogger.ui

import android.location.GnssStatus
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eightbrows.gpslogger.log.LogEvent
import io.github.eightbrows.gpslogger.state.GnssStateHolder
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** コンステレーション別の色 */
fun constellationColor(type: Int): Color = when (type) {
    GnssStatus.CONSTELLATION_GPS -> Color(0xFF2E7D32)      // 緑
    GnssStatus.CONSTELLATION_GLONASS -> Color(0xFFC62828)  // 赤
    GnssStatus.CONSTELLATION_GALILEO -> Color(0xFF1565C0)  // 青
    GnssStatus.CONSTELLATION_BEIDOU -> Color(0xFFEF6C00)   // 橙
    GnssStatus.CONSTELLATION_QZSS -> Color(0xFF6A1B9A)     // 紫
    GnssStatus.CONSTELLATION_SBAS -> Color(0xFF00838F)     // 青緑
    GnssStatus.CONSTELLATION_IRNSS -> Color(0xFF8D6E63)    // 茶
    else -> Color(0xFF757575)                               // 灰
}

@Composable
fun SkyPlotPage() {
    val snapshot by GnssStateHolder.snapshot.collectAsState()
    val sats = snapshot.satellites

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            SkyPlot(sats, Modifier.fillMaxSize().padding(8.dp))
        }
        Legend(sats)
    }
}

@Composable
private fun SkyPlot(sats: List<LogEvent.Sat>, modifier: Modifier = Modifier) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        // ラベル分の余白を確保
        val radius = min(size.width, size.height) / 2f - 12.dp.toPx()
        if (radius <= 0f) return@Canvas

        val strokeW = 1.dp.toPx()

        // 仰角リング: 0°(外周) / 30° / 60°、中心が90°
        listOf(0f, 30f, 60f).forEach { elev ->
            drawCircle(
                color = gridColor,
                radius = radius * (1f - elev / 90f),
                center = Offset(cx, cy),
                style = Stroke(width = strokeW)
            )
        }

        // 方位の十字線
        drawLine(gridColor, Offset(cx - radius, cy), Offset(cx + radius, cy), strokeW)
        drawLine(gridColor, Offset(cx, cy - radius), Offset(cx, cy + radius), strokeW)

        // 方位ラベル（N/E/S/W）
        val textPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = 11.dp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val off = 4.dp.toPx()
        drawContext.canvas.nativeCanvas.apply {
            drawText("N", cx, cy - radius - off, textPaint)
            drawText("S", cx, cy + radius + textPaint.textSize, textPaint)
            drawText("E", cx + radius + off + textPaint.textSize / 2f, cy + textPaint.textSize / 3f, textPaint)
            drawText("W", cx - radius - off - textPaint.textSize / 2f, cy + textPaint.textSize / 3f, textPaint)
        }

        // 衛星をプロット
        val svidPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = 9.dp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }

        sats.forEach { sat ->
            if (sat.elevationDeg < 0f) return@forEach

            val elev = sat.elevationDeg.coerceIn(0f, 90f)
            val azRad = Math.toRadians(sat.azimuthDeg.toDouble())
            val d = radius * (1f - elev / 90f)
            val x = cx + d * sin(azRad).toFloat()
            val y = cy - d * cos(azRad).toFloat()

            // C/N0 でドットサイズを変える（信号が強いほど大きい）
            val dotR = (3.dp.toPx() + (sat.cn0DbHz / 50f).coerceIn(0f, 1f) * 3.dp.toPx())
            val color = constellationColor(sat.constellation)

            if (sat.usedInFix) {
                drawCircle(color, dotR, Offset(x, y))                        // 塗り＝測位使用
            } else {
                drawCircle(color, dotR, Offset(x, y), style = Stroke(strokeW * 1.5f)) // 輪郭＝未使用
            }

            drawContext.canvas.nativeCanvas.drawText(
                sat.svid.toString(), x, y - dotR - 2.dp.toPx(), svidPaint
            )
        }
    }
}

@Composable
private fun Legend(sats: List<LogEvent.Sat>) {
    val present = sats.map { it.constellation }.distinct().sorted()
    if (present.isEmpty()) return

    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        present.forEach { type ->
            val used = sats.count { it.constellation == type && it.usedInFix }
            val total = sats.count { it.constellation == type }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(constellationColor(type), CircleShape)
                )
                Text(
                    " ${constellationName(type)} $used/$total",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
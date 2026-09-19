package io.github.eightbrows.gpslogger.ui

import io.github.eightbrows.gpslogger.R
import androidx.compose.ui.res.stringResource
import android.location.GnssStatus
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import io.github.eightbrows.gpslogger.state.ViewSnapshot
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import io.github.eightbrows.gpslogger.log.CONSTELLATION_IRNSS

/** コンステレーション別の色 */
fun constellationColor(type: Int): Color = when (type) {
    GnssStatus.CONSTELLATION_GPS -> Color(0xFF2E7D32)      // 緑
    GnssStatus.CONSTELLATION_GLONASS -> Color(0xFFC62828)  // 赤
    GnssStatus.CONSTELLATION_GALILEO -> Color(0xFF1565C0)  // 青
    GnssStatus.CONSTELLATION_BEIDOU -> Color(0xFFEF6C00)   // 橙
    GnssStatus.CONSTELLATION_QZSS -> Color(0xFF6A1B9A)     // 紫
    GnssStatus.CONSTELLATION_SBAS -> Color(0xFF00838F)     // 青緑
    CONSTELLATION_IRNSS -> Color(0xFF8D6E63)    // 茶
    else -> Color(0xFF757575)                               // 灰
}

@Composable
fun SkyPlotPage(snapshot: ViewSnapshot) {
    val sats = snapshot.satellites

    Row(
        Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SideStats(
            sats = sats,
            dop = snapshot.dop,
            modifier = Modifier
                .width(88.dp)
                .fillMaxHeight()
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            SkyPlot(sats, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SideStats(
    sats: List<LogEvent.Sat>,
    dop: io.github.eightbrows.gpslogger.calc.Dop?,
    modifier: Modifier = Modifier
) {
    val order = listOf(
        GnssStatus.CONSTELLATION_GPS,
        GnssStatus.CONSTELLATION_GLONASS,
        GnssStatus.CONSTELLATION_GALILEO,
        GnssStatus.CONSTELLATION_BEIDOU,
        GnssStatus.CONSTELLATION_QZSS,
        GnssStatus.CONSTELLATION_SBAS,
        CONSTELLATION_IRNSS,
        GnssStatus.CONSTELLATION_UNKNOWN
    )
    val present = order.filter { type -> sats.any { it.constellation == type } }

    // GPS + QZSS のみで再計算
    val gjSats = sats.filter {
        it.constellation == GnssStatus.CONSTELLATION_GPS ||
                it.constellation == GnssStatus.CONSTELLATION_QZSS
    }
    val gjDop = io.github.eightbrows.gpslogger.calc.DopCalculator.calculate(gjSats)

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        // 衛星数
        val used = sats.count { it.usedInFix }
        StatRow(stringResource(R.string.sat_used), "$used", emphasize = true)
        StatRow(stringResource(R.string.sat_visible), "${sats.size}", emphasize = true)

        HorizontalDivider(Modifier.padding(vertical = 3.dp))

        // 系統別
        present.forEach { type ->
            val u = sats.count { it.constellation == type && it.usedInFix }
            val t = sats.count { it.constellation == type }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(constellationColor(type), CircleShape)
                )
                Text(
                    " ${rinexCode(type)}",
                    Modifier.weight(1f),
                    fontSize = 11.sp,
                    lineHeight = 12.sp
                )
                Text("$u/$t", fontSize = 11.sp, lineHeight = 12.sp)
            }
        }
        if (present.isEmpty()) {
            Text(stringResource(R.string.sky_no_satellites), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }

        HorizontalDivider(Modifier.padding(vertical = 3.dp))

        // 全体DOP
        Text(
            "DOP",
            fontSize = 10.sp,
            lineHeight = 11.sp,
            color = MaterialTheme.colorScheme.primary
        )
        DopRow("P", gjDop?.pdop)
        DopRow("H", gjDop?.hdop)
        DopRow("V", gjDop?.vdop)
        DopRow("G", gjDop?.gdop)
        DopRow("T", gjDop?.tdop)

        HorizontalDivider(Modifier.padding(vertical = 3.dp))

        // GPS + QZSS 限定DOP
        Text(
            "DOP (G+J)",
            fontSize = 10.sp,
            lineHeight = 11.sp,
            color = MaterialTheme.colorScheme.primary
        )
        DopRow("P", gjDop?.pdop)
        DopRow("H", gjDop?.hdop)
        DopRow("V", gjDop?.vdop)
        DopRow("G", gjDop?.gdop)
        DopRow("T", gjDop?.tdop)
    }
}

@Composable
private fun StatRow(label: String, value: String, emphasize: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.weight(1f),
            fontSize = 11.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            fontSize = if (emphasize) 12.sp else 11.sp,
            lineHeight = 12.sp
        )
    }
}

@Composable
private fun DopRow(label: String, value: Double?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.weight(1f),
            fontSize = 11.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (value != null) "%.2f".format(value) else "—",
            fontSize = 11.sp,
            lineHeight = 12.sp,
            color = when {
                value == null -> MaterialTheme.colorScheme.outline
                value <= 2.0 -> MaterialTheme.colorScheme.primary
                value <= 5.0 -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.error
            }
        )
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

/** RINEXのシステム記号 */
internal fun rinexCode(type: Int): String = when (type) {
    GnssStatus.CONSTELLATION_GPS -> "G"
    GnssStatus.CONSTELLATION_GLONASS -> "R"
    GnssStatus.CONSTELLATION_GALILEO -> "E"
    GnssStatus.CONSTELLATION_BEIDOU -> "C"
    GnssStatus.CONSTELLATION_QZSS -> "J"
    GnssStatus.CONSTELLATION_SBAS -> "S"
    CONSTELLATION_IRNSS -> "I"
    else -> "?"
}
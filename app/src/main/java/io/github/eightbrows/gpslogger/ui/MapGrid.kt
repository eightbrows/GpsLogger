package io.github.eightbrows.gpslogger.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.floor
import androidx.compose.ui.unit.dp

/** 緯度経度グリッドとスケールバーの描画 */
object MapGrid {

    /** 表示範囲に応じたグリッド間隔（度）の候補 */
    private val STEPS = doubleArrayOf(
        10.0, 5.0, 2.0, 1.0, 0.5, 0.2, 0.1,
        0.05, 0.02, 0.01, 0.005, 0.002, 0.001,
        0.0005, 0.0002, 0.0001
    )

    /** 画面上の間隔が目標px前後になるステップを選ぶ */
    private fun chooseStep(degPerPx: Double, targetPx: Double): Double {
        val target = degPerPx * targetPx
        return STEPS.minByOrNull { kotlin.math.abs(it - target) } ?: 0.01
    }

    /** 度数の表示。DMS指定時は簡易的な度分秒に */
    private fun formatDeg(value: Double, step: Double, dms: Boolean): String {
        if (!dms) {
            return when {
                step >= 1.0 -> "%.0f".format(value)
                step >= 0.1 -> "%.1f".format(value)
                step >= 0.01 -> "%.2f".format(value)
                step >= 0.001 -> "%.3f".format(value)
                else -> "%.4f".format(value)
            }
        }
        // 度分秒（グリッドラベルなので簡潔に）
        val a = kotlin.math.abs(value)
        val deg = floor(a).toInt()
        val minFull = (a - deg) * 60.0
        val min = floor(minFull).toInt()
        val sec = (minFull - min) * 60.0
        val sign = if (value < 0) "-" else ""
        return when {
            step >= 1.0 -> "$sign$deg°"
            step >= 1.0 / 60 -> "$sign$deg°%02d'".format(min)
            else -> "$sign$deg°%02d'%04.1f\"".format(min, sec)
        }
    }

    /**
     * グリッドを描く。
     * toScreen: 緯度経度→画面座標の変換（呼び出し側と同じもの）
     * scale: 緯度1度あたりのピクセル数
     */
    fun drawGrid(
        scope: DrawScope,
        gridColor: Color,
        labelColor: Color,
        scale: Float,
        lonScale: Double,
        centerLat: Double,
        toScreen: (Double, Double) -> Offset,
        screenToLat: (Float) -> Double,
        screenToLon: (Float) -> Double,
        labelTextSizePx: Float,
        dms: Boolean
    ) = with(scope) {
        // 画面に映っている緯度経度の範囲
        val topLat = screenToLat(0f)
        val bottomLat = screenToLat(size.height)
        val leftLon = screenToLon(0f)
        val rightLon = screenToLon(size.width)

        val latDegPerPx = (topLat - bottomLat) / size.height
        val lonDegPerPx = (rightLon - leftLon) / size.width

        val latStep = chooseStep(latDegPerPx, 80.0)
        val lonStep = chooseStep(lonDegPerPx, 80.0)

        val paint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = labelTextSizePx
            isAntiAlias = true
        }
        val strokeW = 0.5.dp.toPx()

        // 緯線（水平線）
        var lat = floor(bottomLat / latStep) * latStep
        while (lat <= topLat) {
            val y = toScreen(lat, leftLon).y
            if (y >= 0f && y <= size.height) {
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeW)
                drawContext.canvas.nativeCanvas.drawText(
                    formatDeg(lat, latStep, dms), 4f, y - 4f, paint
                )
            }
            lat += latStep
        }

        // 経線（垂直線）
        var lon = floor(leftLon / lonStep) * lonStep
        while (lon <= rightLon) {
            val x = toScreen(bottomLat, lon).x
            if (x >= 0f && x <= size.width) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeW)
                drawContext.canvas.nativeCanvas.drawText(
                    formatDeg(lon, lonStep, dms), x + 4f, size.height - 4f, paint
                )
            }
            lon += lonStep
        }
    }

    /** スケールバーを右上に描く */
    fun drawScaleBar(
        scope: DrawScope,
        barColor: Color,
        scale: Float,
        labelTextSizePx: Float,
        topMarginPx: Float
    ) = with(scope) {
        val metersPerPx = 111_320.0 / scale
        if (metersPerPx <= 0.0 || metersPerPx.isNaN() || metersPerPx.isInfinite()) return@with

        val targetPx = size.width * 0.25
        val roughMeters = metersPerPx * targetPx
        if (roughMeters <= 0.0 || roughMeters.isNaN() || roughMeters.isInfinite()) return@with

        val exp = floor(kotlin.math.log10(roughMeters))
        val base = Math.pow(10.0, exp)
        var niceMeters = when {
            roughMeters / base >= 5 -> 5 * base
            roughMeters / base >= 2 -> 2 * base
            else -> base
        }

        // 画面幅を超える場合は段階的に縮める
        var barPx = (niceMeters / metersPerPx).toFloat()
        var guard = 0
        while (barPx > size.width && guard < 20) {
            niceMeters /= 2.0
            barPx = (niceMeters / metersPerPx).toFloat()
            guard++
        }
        if (barPx <= 0f || barPx.isNaN() || barPx.isInfinite()) return@with

        val label = when {
            niceMeters >= 1000 -> "%.0f km".format(niceMeters / 1000)
            niceMeters >= 1 -> "%.0f m".format(niceMeters)
            else -> "%.2f m".format(niceMeters)
        }

        val paint = android.graphics.Paint().apply {
            color = barColor.toArgb()
            textSize = labelTextSizePx
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
        }

        // 右端から左へ伸ばす
        val x1 = size.width - 8f
        val x0 = x1 - barPx
        val y = topMarginPx
        val strokeW = 2.dp.toPx()
        val tick = 4.dp.toPx()

        drawLine(barColor, Offset(x0, y), Offset(x1, y), strokeW)
        drawLine(barColor, Offset(x0, y - tick), Offset(x0, y + tick), strokeW)
        drawLine(barColor, Offset(x1, y - tick), Offset(x1, y + tick), strokeW)

        drawContext.canvas.nativeCanvas.drawText(label, x1, y - tick - 3f, paint)
    }
}
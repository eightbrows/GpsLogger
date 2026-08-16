package io.github.eightbrows.gpslogger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import io.github.eightbrows.gpslogger.settings.Settings

@Composable
fun SplitScreen(
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val handleHeight = 20.dp
        val minPane = 100.dp   // 最小ペイン高さ（実機で調整）

        val availablePx = with(density) { (maxHeight - handleHeight).toPx() }
        val minPanePx = with(density) { minPane.toPx() }

        // 分割比率（初期50:50）。rememberSaveableで回転・再生成に耐える
        val savedRatio by Settings.splitRatio.collectAsState()
        var ratio by remember { mutableFloatStateOf(savedRatio) }

        val topHeightPx = availablePx * ratio
        val topHeight = with(density) { topHeightPx.toDp() }

        val dragState = rememberDraggableState { delta ->
            val newTopPx = (topHeightPx + delta)
                .coerceIn(minPanePx, availablePx - minPanePx)
            ratio = newTopPx / availablePx
            Settings.setSplitRatio(ratio)
        }

        Column(Modifier.fillMaxSize()) {
            // 上ペイン（軌跡）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(topHeight)
            ) { top() }

            // グラブハンドル（ここだけが縦ドラッグを拾う）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(handleHeight)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            RoundedCornerShape(2.dp)
                        )
                )
            }

            // 下ペイン（3ページ）
            Box(Modifier.fillMaxSize()) { bottom() }
        }
    }
}
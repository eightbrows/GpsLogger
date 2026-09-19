package io.github.eightbrows.gpslogger.ui

import android.location.GnssStatus
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.eightbrows.gpslogger.R
import io.github.eightbrows.gpslogger.log.CONSTELLATION_IRNSS
import io.github.eightbrows.gpslogger.log.LogEvent
import io.github.eightbrows.gpslogger.state.ViewSnapshot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数値ページの種別1・種別2の行で、値（色の点＋系統記号＋使用数/可視数、強調時は枠付き）が
 * 他の行と同じ値の列から始まり、ラベルの列に食い込まないことを、画面幅と文字の大きさを変えて確かめる。
 */
@RunWith(AndroidJUnit4::class)
class ConstellationRowLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    private fun sat(type: Int, svid: Int, used: Boolean) = LogEvent.Sat(
        constellation = type, svid = svid, cn0DbHz = 40f, basebandCn0DbHz = 0f,
        elevationDeg = 45f, azimuthDeg = 90f, carrierFrequencyHz = 0f,
        usedInFix = used, hasAlmanac = true, hasEphemeris = true
    )

    /** 見える系統が多く、数も 2 桁になる厳しめの状態 */
    private val sats = buildList {
        repeat(12) { add(sat(GnssStatus.CONSTELLATION_GPS, it + 1, it < 10)) }
        repeat(11) { add(sat(GnssStatus.CONSTELLATION_GALILEO, it + 1, it < 9)) }
        repeat(4) { add(sat(GnssStatus.CONSTELLATION_QZSS, 193 + it, it < 3)) }
        repeat(3) { add(sat(GnssStatus.CONSTELLATION_SBAS, 120 + it, false)) }
        repeat(8) { add(sat(GnssStatus.CONSTELLATION_GLONASS, it + 1, it < 6)) }
        repeat(14) { add(sat(GnssStatus.CONSTELLATION_BEIDOU, it + 1, it < 11)) }
        repeat(2) { add(sat(CONSTELLATION_IRNSS, it + 1, false)) }
        add(sat(GnssStatus.CONSTELLATION_UNKNOWN, 1, false))
    }

    /**
     * 系統の文字の左端から、枠を含む部品の左端までの幅。
     * 強調あり: 内側の余白 4dp（枠線はこの中に描かれる）+ 色の点 6dp。強調なし（不明の系統）: 色の点 6dp。
     */
    private val leadEmphasized = 10.dp
    private val leadPlain = 6.dp

    @Test
    fun constellationValuesAlignWithValueColumn() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val rows = listOf(
            ctx.getString(R.string.num_group1) to listOf("G10/12", "E9/11", "J3/4", "S0/3"),
            ctx.getString(R.string.num_group2) to listOf("R6/8", "C11/14", "I0/2", "?0/1")
        )
        // 値の列の開始位置の基準：枠の無い普通の行（衛星数）の値
        val satCountText = ctx.getString(
            R.string.sat_used_visible, sats.count { it.usedInFix }, sats.size
        )

        var width by mutableStateOf(360.dp)
        var fontScale by mutableStateOf(1f)
        rule.setContent {
            val density = LocalDensity.current.density
            // 端末の「文字サイズ」設定を変えた状態を再現する
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                Box(Modifier.width(width).height(2000.dp)) {
                    BottomPager(ViewSnapshot(satellites = sats))
                }
            }
        }

        val problems = mutableListOf<String>()
        val widths: List<Dp> = listOf(240.dp, 280.dp, 320.dp, 360.dp)
        for (scale in listOf(1f, 1.3f, 1.5f)) for (w in widths) {
            rule.runOnIdle { width = w; fontScale = scale }
            rule.waitForIdle()

            rule.onAllNodesWithText(satCountText)[0].performScrollTo()
            val columnStart = rule.onAllNodesWithText(satCountText)[0].getBoundsInRoot().left

            for ((label, chips) in rows) {
                // 行を画面内に出してから測る（文字が大きいとページが縦に長くなるため）
                rule.onAllNodesWithText(label)[0].performScrollTo()
                rule.waitForIdle()
                val labelRight = rule.onAllNodesWithText(label)[0].getBoundsInRoot().right
                for (chip in chips) {
                    val node = rule.onAllNodes(hasText(chip, substring = true), useUnmergedTree = true)[0]
                    // 折り返して下の行に回った系統も、画面内に出してから測る
                    node.performScrollTo()
                    rule.waitForIdle()
                    val b = node.getBoundsInRoot()
                    val lead = if (chip.startsWith("?")) leadPlain else leadEmphasized
                    val chipLeft = b.left - lead
                    val line = "scale=$scale width=$w label='$label' labelRight=$labelRight " +
                        "columnStart=$columnStart chip='$chip' chipLeft=$chipLeft right=${b.right}"
                    Log.i(TAG, line)
                    if (b.right <= b.left) { problems += "not visible: $line"; continue }
                    if (chipLeft < columnStart - 0.5.dp) problems += "left of value column: $line"
                    if (chipLeft < labelRight - 0.5.dp) problems += "overlaps label: $line"
                    if (b.right > w + 0.5.dp) problems += "beyond screen: $line"
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private companion object {
        const val TAG = "ConstellationRowLayout"
    }
}

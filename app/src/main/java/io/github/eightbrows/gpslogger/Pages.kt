package io.github.eightbrows.gpslogger.ui

import android.location.GnssStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eightbrows.gpslogger.log.LogEvent
import io.github.eightbrows.gpslogger.state.GnssStateHolder

@Composable
fun BottomPager() {
    val pagerState = rememberPagerState(pageCount = { 3 })

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (page) {
                0 -> NumericPage()
                1 -> SatListPage()
                2 -> SkyPlotPage()
            }
        }

        // ページインジケータ
        Row(
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            val labels = listOf("数値", "衛星リスト", "上空図")
            labels.forEachIndexed { index, label ->
                Row(
                    Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(
                                if (pagerState.currentPage == index)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape
                            )
                    )
                    Text(
                        text = " $label",
                        fontSize = 11.sp,
                        color = if (pagerState.currentPage == index)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun NumericPage() {
    val snapshot by GnssStateHolder.snapshot.collectAsState()
    val trackPoints by GnssStateHolder.trackPoints.collectAsState()
    val loc = snapshot.location

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (loc != null) {
            NumRow("緯度", "%.7f".format(loc.latitude))
            NumRow("経度", "%.7f".format(loc.longitude))
            NumRow("楕円体高", "%.1f m".format(loc.altitude))
            NumRow("水平精度", "%.1f m".format(loc.accuracy))
            NumRow("垂直精度", "%.1f m".format(loc.verticalAccuracyMeters))
            NumRow("速度", "%.2f m/s".format(loc.speed))
            NumRow("方位", "%.1f °".format(loc.bearing))
            NumRow("記録点数", "${trackPoints.size}")
        } else {
            Text("測位待ち…")
        }
        NumRow("衛星", "使用 ${snapshot.satsUsed} / 可視 ${snapshot.satsInView}")
    }
}

@Composable
private fun NumRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
        Text(value, fontSize = 13.sp)
    }
}

@Composable
private fun SatListPage() {
    val snapshot by GnssStateHolder.snapshot.collectAsState()
    val sats = snapshot.satellites.sortedWith(
        compareBy({ it.constellation }, { it.svid })
    )

    Column(Modifier.fillMaxSize()) {
        // ヘッダ
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("系統", Modifier.weight(1.4f), fontSize = 11.sp)
            Text("SV", Modifier.weight(0.7f), fontSize = 11.sp)
            Text("C/N0", Modifier.weight(1f), fontSize = 11.sp, textAlign = TextAlign.End)
            Text("仰角", Modifier.weight(1f), fontSize = 11.sp, textAlign = TextAlign.End)
            Text("方位", Modifier.weight(1f), fontSize = 11.sp, textAlign = TextAlign.End)
            Text("使用", Modifier.weight(0.7f), fontSize = 11.sp, textAlign = TextAlign.End)
        }
        HorizontalDivider()

        // ペイン内スクロール
        LazyColumn(Modifier.fillMaxSize()) {
            items(sats) { sat ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                ) {
                    Text(constellationName(sat.constellation), Modifier.weight(1.4f), fontSize = 12.sp)
                    Text("${sat.svid}", Modifier.weight(0.7f), fontSize = 12.sp)
                    Text("%.0f".format(sat.cn0DbHz), Modifier.weight(1f), fontSize = 12.sp, textAlign = TextAlign.End)
                    Text("%.0f".format(sat.elevationDeg), Modifier.weight(1f), fontSize = 12.sp, textAlign = TextAlign.End)
                    Text("%.0f".format(sat.azimuthDeg), Modifier.weight(1f), fontSize = 12.sp, textAlign = TextAlign.End)
                    Text(if (sat.usedInFix) "●" else "", Modifier.weight(0.7f), fontSize = 12.sp, textAlign = TextAlign.End)
                }
            }
        }
    }
}

internal fun constellationName(type: Int): String = when (type) {
    GnssStatus.CONSTELLATION_GPS -> "GPS"
    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
    GnssStatus.CONSTELLATION_GALILEO -> "GALILEO"
    GnssStatus.CONSTELLATION_BEIDOU -> "BEIDOU"
    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
    GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
    else -> "不明"
}
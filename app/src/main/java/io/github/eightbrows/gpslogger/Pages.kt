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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eightbrows.gpslogger.state.ViewSnapshot
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.eightbrows.gpslogger.settings.CoordFormatter
import io.github.eightbrows.gpslogger.settings.Settings

@Composable
fun BottomPager(snapshot: ViewSnapshot) {
    val pagerState = rememberPagerState(pageCount = { 3 })

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) { page ->
            when (page) {
                0 -> NumericPage(snapshot)
                1 -> SatListPage(snapshot)
                2 -> SkyPlotPage(snapshot)
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
private fun NumericPage(snapshot: ViewSnapshot) {
    val coordFormat by Settings.coordFormat.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (snapshot.latitude != null && snapshot.longitude != null) {
            NumRow("緯度", CoordFormatter.latitude(snapshot.latitude, coordFormat))
            NumRow("経度", CoordFormatter.longitude(snapshot.longitude, coordFormat))
            NumRow("楕円体高", "%.1f m".format(snapshot.altitude))
            NumRow("水平精度", "%.1f m".format(snapshot.accuracy))
            NumRow("速度", "%.2f m/s".format(snapshot.speed))
            NumRow("方位", "%.1f °".format(snapshot.bearing))
            NumRow("記録点数", "${snapshot.trackPoints.size}")
        } else {
            Text("測位待ち…")
        }
        NumRow("衛星", "使用 ${snapshot.satsUsed} / 可視 ${snapshot.satsInView}")

        val dop = snapshot.dop
        if (dop != null) {
            NumRow("PDOP / HDOP", "%.2f / %.2f".format(dop.pdop, dop.hdop))
            NumRow("VDOP / TDOP", "%.2f / %.2f".format(dop.vdop, dop.tdop))
            NumRow("GDOP", "%.2f".format(dop.gdop))
        } else {
            NumRow("DOP", "—")
        }
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
private fun SatListPage(snapshot: ViewSnapshot) {
    val sats = snapshot.satellites.sortedWith(compareBy({ it.constellation }, { it.svid }))

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
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(Modifier.weight(1.4f), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(constellationColor(sat.constellation), CircleShape)
                        )
                        Text(" " + constellationName(sat.constellation), fontSize = 12.sp)
                    }
                    Text("${sat.svid}", Modifier.weight(0.7f), fontSize = 12.sp)
                    Text(
                        "%.0f".format(sat.cn0DbHz),
                        Modifier.weight(1f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.End,
                        color = when {
                            sat.cn0DbHz >= 40f -> MaterialTheme.colorScheme.primary
                            sat.cn0DbHz >= 25f -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
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
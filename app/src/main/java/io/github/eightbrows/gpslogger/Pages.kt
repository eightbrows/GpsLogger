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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.eightbrows.gpslogger.calc.GpsTime

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
    val leapSeconds by Settings.leapSeconds.collectAsState()

    val hasTime = snapshot.timeMs > 0
    val hasPos = snapshot.latitude != null && snapshot.longitude != null
    val gps = if (hasTime) GpsTime.fromUtcMillis(snapshot.timeMs, leapSeconds) else null
    val dop = snapshot.dop

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // 時刻
        NumRow("UTC", if (hasTime) formatUtc(snapshot.timeMs) else DASH)
        NumRow("Local", if (hasTime) formatLocal(snapshot.timeMs) else DASH)
        NumRow("WN / TOW", if (gps != null) "${gps.week} / %.1f".format(gps.tow) else "$DASH / $DASH")

        // 位置
        NumRow("緯度", if (hasPos) CoordFormatter.latitudeBoth(snapshot.latitude!!, coordFormat) else DASH)
        NumRow("経度", if (hasPos) CoordFormatter.longitudeBoth(snapshot.longitude!!, coordFormat) else DASH)
        NumRow("楕円体高", if (hasPos) "%.1f m".format(snapshot.altitude) else DASH)
        NumRow("水平精度", if (hasPos) "%.1f m".format(snapshot.accuracy) else DASH)
        NumRow("速度", if (hasPos) "%.2f m/s".format(snapshot.speed) else DASH)
        NumRow("方位", if (hasPos) "%.1f °".format(snapshot.bearing) else DASH)
        NumRow("記録点数", "${snapshot.trackPoints.size}")

        // 衛星
        NumRow("衛星", "使用 ${snapshot.satsUsed} / 可視 ${snapshot.satsInView}")

        // DOP
        NumRow(
            "DOP(P/V/H)",
            if (dop != null) "%.2f / %.2f / %.2f".format(dop.pdop, dop.vdop, dop.hdop)
            else "$DASH / $DASH / $DASH"
        )
        NumRow(
            "DOP(G/T)",
            if (dop != null) "%.2f / %.2f".format(dop.gdop, dop.tdop)
            else "$DASH / $DASH"
        )
    }
}

private const val DASH = "—"

private val utcFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
    timeZone = java.util.TimeZone.getTimeZone("UTC")
}
private val localFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)

private fun formatUtc(ms: Long): String = utcFormat.format(java.util.Date(ms))
private fun formatLocal(ms: Long): String = localFormat.format(java.util.Date(ms))

@Composable
private fun NumRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(0.8f), fontSize = 13.sp, lineHeight = 14.sp)
        Text(value, Modifier.weight(2.2f), fontSize = 13.sp, lineHeight = 14.sp)
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
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(Modifier.weight(1.4f), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(constellationColor(sat.constellation), CircleShape)
                        )
                        Text(" " + constellationName(sat.constellation), fontSize = 12.sp, lineHeight = 12.sp)
                    }
                    Text("${sat.svid}", Modifier.weight(0.7f), fontSize = 12.sp, lineHeight = 12.sp)
                    Text(
                        "%.0f".format(sat.cn0DbHz),
                        Modifier.weight(1f),
                        fontSize = 12.sp,
                        lineHeight = 12.sp,
                        textAlign = TextAlign.End,
                        color = when {
                            sat.cn0DbHz >= 40f -> MaterialTheme.colorScheme.primary
                            sat.cn0DbHz >= 25f -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                    Text("%.0f".format(sat.elevationDeg), Modifier.weight(1f), fontSize = 12.sp, lineHeight = 12.sp, textAlign = TextAlign.End)
                    Text("%.0f".format(sat.azimuthDeg), Modifier.weight(1f), fontSize = 12.sp, lineHeight = 12.sp, textAlign = TextAlign.End)
                    Text(if (sat.usedInFix) "●" else "", Modifier.weight(0.7f), fontSize = 12.sp, lineHeight = 12.sp, textAlign = TextAlign.End)
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
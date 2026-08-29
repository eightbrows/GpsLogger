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
import androidx.compose.ui.graphics.Color
import io.github.eightbrows.gpslogger.log.CONSTELLATION_IRNSS
import io.github.eightbrows.gpslogger.log.LogEvent

@Composable
fun BottomPager(
    snapshot: ViewSnapshot,
    onSelectIndex: ((Int) -> Unit)? = null
) {
    val pageCount = 4
    val startPage = Int.MAX_VALUE / 2
    val pagerState = rememberPagerState(
        initialPage = startPage - (startPage % pageCount),
        pageCount = { Int.MAX_VALUE }
    )

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) { page ->
            when (page % pageCount) {
                0 -> NumericPage(snapshot)
                1 -> SatListPage(snapshot)
                2 -> SkyPlotPage(snapshot)
                3 -> AltitudePage(snapshot, onSelectIndex)
            }
        }

        // ページインジケータ
        Row(
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            val labels = listOf("数値", "衛星リスト", "上空図", "高度")
            val current = pagerState.currentPage % pageCount
            labels.forEachIndexed { index, label ->
                Row(
                    Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(
                                if (current == index)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape
                            )
                    )
                    Text(
                        text = " $label",
                        fontSize = 11.sp,
                        color = if (current == index)
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

        // 記録情報
        val startMs = snapshot.sessionStartMs
        val endMs = snapshot.sessionEndMs
        NumRow("記録開始", if (startMs > 0) formatLocal(startMs) else DASH)
        NumRow("記録終了", if (endMs > 0) formatLocal(endMs) else DASH)
        NumRow(
            "記録時間",
            "${formatDuration(startMs, endMs, snapshot.timeMs)}  (${snapshot.trackPoints.size}点)"
        )

        // 位置
        NumRow("緯度", if (hasPos) CoordFormatter.latitudeBoth(snapshot.latitude!!, coordFormat) else DASH)
        NumRow("経度", if (hasPos) CoordFormatter.longitudeBoth(snapshot.longitude!!, coordFormat) else DASH)
        NumRow("楕円体高", if (hasPos) formatAltitude(snapshot.altitude) else DASH)
        NumRow(
            "精度",
            if (hasPos) "H%.1f m / V%.1f m".format(snapshot.accuracy, snapshot.verticalAccuracy) else DASH
        )
        NumRow("速度", if (hasPos) formatSpeed(snapshot.speed) else DASH)
        NumRow("方位", if (hasPos) "%.1f °".format(snapshot.bearing) else DASH)

        // 衛星
        NumRow("衛星数", "使用 ${snapshot.satsUsed} / 可視 ${snapshot.satsInView}")
        ConstellationRow("衛星種別1", snapshot.satellites, GROUP_WEST)
        ConstellationRow("衛星種別2", snapshot.satellites, GROUP_OTHER)

        // DOP
        NumRow(
            "DOP",
            if (dop != null)
                "P%.2f / H%.2f / V%.2f / G%.2f / T%.2f".format(
                    dop.pdop, dop.hdop, dop.vdop, dop.gdop, dop.tdop
                )
            else DASH
        )
    }
}

@Composable
private fun ConstellationRow(
    label: String,
    sats: List<LogEvent.Sat>,
    order: List<Int>
) {
    val present = order.filter { type -> sats.any { it.constellation == type } }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.8f), fontSize = 13.sp, lineHeight = 14.sp)
        Row(Modifier.weight(2.2f), verticalAlignment = Alignment.CenterVertically) {
            if (present.isEmpty()) {
                Text(DASH, fontSize = 13.sp, lineHeight = 14.sp)
            } else {
                present.forEach { type ->
                    val used = sats.count { it.constellation == type && it.usedInFix }
                    val total = sats.count { it.constellation == type }
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(constellationColor(type), CircleShape)
                    )
                    Text(
                        " ${rinexCode(type)}$used/$total ",
                        fontSize = 13.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

private const val DASH = "—"

private val utcFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
    timeZone = java.util.TimeZone.getTimeZone("UTC")
}
private val localFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)

private fun formatUtc(ms: Long): String = utcFormat.format(java.util.Date(ms))
private fun formatLocal(ms: Long): String = localFormat.format(java.util.Date(ms))

/** 42.3 → "42.3 m / 138.8 ft" */
private fun formatAltitude(meters: Double): String {
    val feet = meters * 3.28084
    return "%.1f m / %.1f ft".format(meters, feet)
}

/** 1.25 → "1.25 m/s / 4.5 km/h / 2.4 kt" */
private fun formatSpeed(mps: Float): String {
    val kmh = mps * 3.6f
    val kt = mps / 0.514444f
    return "%.2f m/s / %.1f km/h / %.1f kt".format(mps, kmh, kt)
}

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
        // サマリー
        val used = sats.count { it.usedInFix }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 1.dp)
        ) {
            Text(
                "使用 $used / 可視 ${sats.size}",
                fontSize = 12.sp,
                lineHeight = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // ヘッダ
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 1.dp)
        ) {
            Text("系統", Modifier.weight(1.3f), fontSize = 11.sp, lineHeight = 12.sp, textAlign = TextAlign.End)
            Text("SV", Modifier.weight(0.6f), fontSize = 11.sp, lineHeight = 12.sp, textAlign = TextAlign.End)
            Row(Modifier.weight(2.0f)) {
                Text("使用", Modifier.weight(1f), fontSize = 11.sp, lineHeight = 12.sp, textAlign = TextAlign.End)
                Text("方位", Modifier.weight(1.2f), fontSize = 11.sp, lineHeight = 12.sp, textAlign = TextAlign.End)
                Text("仰角", Modifier.weight(1.1f), fontSize = 11.sp, lineHeight = 12.sp, textAlign = TextAlign.End)
                Text("C/N0", Modifier.weight(1.2f), fontSize = 11.sp, lineHeight = 12.sp, textAlign = TextAlign.End)
            }
        }

        // ペイン内スクロール
        LazyColumn(Modifier.fillMaxSize()) {
            items(sats) { sat ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (sat.usedInFix)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier.weight(1.3f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(constellationName(sat.constellation), fontSize = 12.sp, lineHeight = 12.sp)
                        Box(
                            Modifier
                                .padding(start = 3.dp)
                                .size(6.dp)
                                .background(constellationColor(sat.constellation), CircleShape)
                        )
                    }
                    Text(
                        "${sat.svid}",
                        Modifier.weight(0.6f),
                        fontSize = 12.sp, lineHeight = 12.sp,
                        textAlign = TextAlign.End
                    )

                    Row(Modifier.weight(2.0f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (sat.usedInFix) "●" else "",
                            Modifier.weight(1f),
                            fontSize = 12.sp, lineHeight = 12.sp, textAlign = TextAlign.End
                        )
                        Text(
                            "%.0f".format(sat.azimuthDeg),
                            Modifier.weight(1.2f),
                            fontSize = 12.sp, lineHeight = 12.sp, textAlign = TextAlign.End
                        )
                        Text(
                            "%.0f".format(sat.elevationDeg),
                            Modifier.weight(1.1f),
                            fontSize = 12.sp, lineHeight = 12.sp, textAlign = TextAlign.End
                        )
                        Text(
                            "%.0f".format(sat.cn0DbHz),
                            Modifier.weight(1.2f),
                            fontSize = 12.sp, lineHeight = 12.sp, textAlign = TextAlign.End,
                            color = when {
                                sat.cn0DbHz >= 40f -> MaterialTheme.colorScheme.primary
                                sat.cn0DbHz >= 25f -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                    }
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
    CONSTELLATION_IRNSS -> "IRNSS"
    else -> "不明"
}

/** 記録時間。終了時刻があればその差、なければ現在時刻との差 */
private fun formatDuration(startMs: Long, endMs: Long, nowMs: Long): String {
    if (startMs <= 0) return DASH
    val end = if (endMs > 0) endMs else nowMs
    if (end <= startMs) return DASH
    val sec = (end - startMs) / 1000
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

/** 系統別の使用/可視をRINEX記号で並べる */
private fun constellationBreakdown(sats: List<LogEvent.Sat>): String {
    if (sats.isEmpty()) return DASH
    val order = listOf(
        GnssStatus.CONSTELLATION_GPS,
        GnssStatus.CONSTELLATION_GLONASS,
        GnssStatus.CONSTELLATION_GALILEO,
        GnssStatus.CONSTELLATION_BEIDOU,
        GnssStatus.CONSTELLATION_QZSS,
        GnssStatus.CONSTELLATION_SBAS,
        CONSTELLATION_IRNSS
    )
    return order.mapNotNull { type ->
        val total = sats.count { it.constellation == type }
        if (total == 0) return@mapNotNull null
        val used = sats.count { it.constellation == type && it.usedInFix }
        "${rinexCode(type)}$used/$total"
    }.joinToString(" ")
}

/** 衛星種別1: GPS・Galileo・QZSS・SBAS */
private val GROUP_WEST = listOf(
    GnssStatus.CONSTELLATION_GPS,
    GnssStatus.CONSTELLATION_GALILEO,
    GnssStatus.CONSTELLATION_QZSS,
    GnssStatus.CONSTELLATION_SBAS
)

/** 衛星種別2: GLONASS・BeiDou・NavIC・不明 */
private val GROUP_OTHER = listOf(
    GnssStatus.CONSTELLATION_GLONASS,
    GnssStatus.CONSTELLATION_BEIDOU,
    CONSTELLATION_IRNSS,
    GnssStatus.CONSTELLATION_UNKNOWN
)
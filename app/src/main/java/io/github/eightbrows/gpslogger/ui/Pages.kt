package io.github.eightbrows.gpslogger.ui

import io.github.eightbrows.gpslogger.settings.DopMetric
import io.github.eightbrows.gpslogger.settings.ConstellationCode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.border
import io.github.eightbrows.gpslogger.settings.CoordFormat
import io.github.eightbrows.gpslogger.settings.SpeedUnit
import io.github.eightbrows.gpslogger.settings.AltitudeUnit
import io.github.eightbrows.gpslogger.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eightbrows.gpslogger.state.ViewSnapshot
import io.github.eightbrows.gpslogger.state.BarometerReader
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

/**
 * 下ペインのページ（数値・衛星リスト・上空図・高度）。
 * initialPage（0〜3）から始め、ページが変わるたびに onPageChanged へ 0〜3 で知らせる
 * （画面を離れて戻ったときに同じページから再開するため）。
 */
@Composable
fun BottomPager(
    snapshot: ViewSnapshot,
    initialPage: Int = 0,
    onPageChanged: ((Int) -> Unit)? = null,
    onSelectIndex: ((Int) -> Unit)? = null
) {
    val pageCount = 4
    val startPage = Int.MAX_VALUE / 2
    val pagerState = rememberPagerState(
        initialPage = startPage - (startPage % pageCount) + initialPage.mod(pageCount),
        pageCount = { Int.MAX_VALUE }
    )

    val currentOnPageChanged by rememberUpdatedState(onPageChanged)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage % pageCount }
            .collect { currentOnPageChanged?.invoke(it) }
    }

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
                .padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            val current = pagerState.currentPage % pageCount
            repeat(pageCount) { index ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(5.dp)
                        .background(
                            if (current == index) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape
                        )
                )
            }
        }
    }
}

@Composable
private fun NumericPage(snapshot: ViewSnapshot) {
    val coordFormat by Settings.coordFormat.collectAsState()
    val altitudeEmphasis by Settings.altitudeEmphasis.collectAsState()
    val speedEmphasis by Settings.speedEmphasis.collectAsState()
    val constellationEmphasis by Settings.constellationEmphasis.collectAsState()
    val dopEmphasis by Settings.dopEmphasis.collectAsState()
    val appLeapSeconds by Settings.leapSeconds.collectAsState()
    // 再生中はそのセッションの meta.json の値、記録中や未設定ならアプリ全体の設定値
    val leapSeconds = snapshot.leapSeconds ?: appLeapSeconds

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
        // --- 時刻 ---
        NumRow("UTC", if (hasTime) formatUtc(snapshot.timeMs) else DASH)
        NumRow("Local", if (hasTime) formatLocal(snapshot.timeMs) else DASH)
        NumRow("WN / TOW", if (gps != null) "${gps.week} / %.1f".format(gps.tow) else "$DASH / $DASH")

        NumDivider()

        // --- 記録 ---
        val startMs = snapshot.sessionStartMs
        val endMs = snapshot.sessionEndMs
        NumRow(
            stringResource(R.string.num_session),
            if (startMs > 0) "${formatTimeOnly(startMs)} → ${if (endMs > 0) formatTimeOnly(endMs) else DASH}"
            else DASH
        )
        NumRow(
            stringResource(R.string.num_duration),
            stringResource(R.string.num_duration_value, formatDuration(startMs, endMs, snapshot.timeMs), snapshot.trackPoints.size)
        )

        NumDivider()

        // --- 位置 ---
        NumUnitsRow(
            stringResource(R.string.num_latitude),
            if (hasPos) formatCoordinate(
                "%.7f".format(snapshot.latitude),
                CoordFormatter.latitudeDms(snapshot.latitude!!),
                coordFormat
            ) else null
        )
        NumUnitsRow(
            stringResource(R.string.num_longitude),
            if (hasPos) formatCoordinate(
                "%.7f".format(snapshot.longitude),
                CoordFormatter.longitudeDms(snapshot.longitude!!),
                coordFormat
            ) else null
        )
        NumUnitsRow(
            stringResource(R.string.num_alt_gps),
            if (hasPos) formatAltitude(snapshot.altitude, altitudeEmphasis) else null
        )
        NumUnitsRow(
            stringResource(R.string.num_alt_baro),
            snapshot.pressureHpa?.let {
                formatAltitude(
                    BarometerReader.altitudeM(it, snapshot.basePressureHpa).toDouble(),
                    altitudeEmphasis
                )
            } ?: null
        )
        NumRow(
            stringResource(R.string.num_accuracy),
            if (hasPos) "H %.1f m / V %.1f m".format(snapshot.accuracy, snapshot.verticalAccuracy) else DASH
        )
        NumUnitsRow(
            stringResource(R.string.num_speed),
            if (hasPos) formatSpeed(snapshot.speed, speedEmphasis) else null
        )
        NumRow(
            stringResource(R.string.num_bearing),
            if (hasPos) stringResource(R.string.num_bearing_value, snapshot.bearing, snapshot.bearingAccuracy)
            else DASH
        )

        NumDivider()

        // --- 衛星・DOP ---
        NumRow(stringResource(R.string.num_satellites), stringResource(R.string.sat_used_visible, snapshot.satsUsed, snapshot.satsInView))
        ConstellationRow(stringResource(R.string.num_group1), snapshot.satellites, GROUP_WEST, constellationEmphasis)
        ConstellationRow(stringResource(R.string.num_group2), snapshot.satellites, GROUP_OTHER, constellationEmphasis)
        NumUnitsRow(
            "DOP1",
            dop?.let {
                listOf(
                    dopPart(DopMetric.P, it.pdop, dopEmphasis),
                    dopPart(DopMetric.H, it.hdop, dopEmphasis),
                    dopPart(DopMetric.V, it.vdop, dopEmphasis)
                )
            }
        )
        NumUnitsRow(
            "DOP2",
            dop?.let {
                listOf(
                    dopPart(DopMetric.G, it.gdop, dopEmphasis),
                    dopPart(DopMetric.T, it.tdop, dopEmphasis)
                )
            }
        )
    }
}

@Composable
private fun NumDivider() {
    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 2.dp))
}

@Composable
private fun ConstellationRow(
    label: String,
    sats: List<LogEvent.Sat>,
    order: List<Int>,
    emphasis: Set<ConstellationCode>
) {
    val present = order.filter { type -> sats.any { it.constellation == type } }
    if (present.isEmpty()) {
        NumRow(label, DASH)
        return
    }

    // ラベルと値の幅の比率は他の行と共通（NumRowFrame）にし、値の列の開始位置をそろえる。
    // 値の上下に枠の分の余白があるので、ラベルも同じだけ下げて文字の高さをそろえる
    NumRowFrame(label, labelTopPadding = UNIT_BORDER) {
        ConstellationChips(present, sats, emphasis)
    }
}

/**
 * 系統ごとの「●G8/11」を並べる。強調する系統だけ角丸の枠で囲む（色の点ごと）。
 * 1行に入り切らないときは系統の単位で折り返す。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConstellationChips(
    present: List<Int>,
    sats: List<LogEvent.Sat>,
    emphasis: Set<ConstellationCode>
) {
    FlowRow(
        Modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(CONSTELLATION_GAP)
    ) {
        present.forEach { type ->
            val used = sats.count { it.constellation == type && it.usedInFix }
            val total = sats.count { it.constellation == type }
            // 系統記号が 7 系統に無いもの（不明）は強調の対象外
            val emphasized = constellationCodeOf(type)?.let { it in emphasis } == true
            val modifier = if (emphasized) {
                Modifier
                    .border(UNIT_BORDER, MaterialTheme.colorScheme.outline, UNIT_SHAPE)
                    .padding(horizontal = CONSTELLATION_PADDING_H, vertical = UNIT_BORDER)
            } else {
                Modifier.padding(vertical = UNIT_BORDER)
            }
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(constellationColor(type), CircleShape)
                )
                Text(
                    " ${rinexCode(type)}$used/$total",
                    fontSize = 13.sp,
                    lineHeight = 13.sp
                )
            }
        }
    }
}

/** GnssStatus の系統を、強調設定の系統記号に対応させる。7 系統以外は null */
private fun constellationCodeOf(type: Int): ConstellationCode? =
    ConstellationCode.entries.firstOrNull { it.name == rinexCode(type) }

/**
 * 衛星種別の枠の内側の左右の余白と、系統どうしの間隔。
 * 1 系統あたりの幅を抑え、4 系統見えていても 1 行に収まりやすくするため、単位の枠より少し詰める。
 */
private val CONSTELLATION_PADDING_H = 4.dp
private val CONSTELLATION_GAP = 6.dp

/** "P 1.2" の形の DOP の1項目。選んだ指標だけ枠で囲む */
private fun dopPart(metric: DopMetric, value: Double, emphasis: Set<DopMetric>): UnitPart =
    UnitPart("${metric.name} %.1f".format(value), metric in emphasis)

private const val DASH = "—"

private val utcFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
    timeZone = java.util.TimeZone.getTimeZone("UTC")
}
private val localFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
private val timeOnlyFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

private fun formatUtc(ms: Long): String = utcFormat.format(java.util.Date(ms)) + " Z"

private fun formatLocal(ms: Long): String {
    val offsetMs = java.util.TimeZone.getDefault().getOffset(ms)
    val totalMinutes = offsetMs / 60000
    val sign = if (totalMinutes >= 0) "+" else "-"
    val hours = kotlin.math.abs(totalMinutes) / 60
    val minutes = kotlin.math.abs(totalMinutes) % 60
    val offsetStr = "%s%02d:%02d".format(sign, hours, minutes)
    return localFormat.format(java.util.Date(ms)) + " " + offsetStr
}
private fun formatTimeOnly(ms: Long): String = timeOnlyFormat.format(java.util.Date(ms))

/** 単位を併記する値の1要素。emphasized なら枠で囲む */
private data class UnitPart(val text: String, val emphasized: Boolean)

/** 42.3 → "42.3 m / 138.8 ft"。設定で選んだ単位だけ枠で囲む */
private fun formatAltitude(meters: Double, emphasis: Set<AltitudeUnit>): List<UnitPart> =
    listOf(
        UnitPart("%.1f ${AltitudeUnit.M.symbol}".format(meters), AltitudeUnit.M in emphasis),
        UnitPart("%.1f ${AltitudeUnit.FT.symbol}".format(meters * 3.28084), AltitudeUnit.FT in emphasis)
    )

/** 1.25 → "1.25 m/s / 4.5 km/h / 2.4 kt"。設定で選んだ単位だけ枠で囲む */
private fun formatSpeed(mps: Float, emphasis: Set<SpeedUnit>): List<UnitPart> =
    listOf(
        UnitPart("%.2f ${SpeedUnit.MPS.symbol}".format(mps), SpeedUnit.MPS in emphasis),
        UnitPart("%.1f ${SpeedUnit.KMH.symbol}".format(mps * 3.6f), SpeedUnit.KMH in emphasis),
        UnitPart("%.1f ${SpeedUnit.KT.symbol}".format(mps / 0.514444f), SpeedUnit.KT in emphasis)
    )

/**
 * 座標は「座標表示形式」の設定に従い、選ばれている表記を先に枠付きで、
 * もう一方を後ろに枠なしで並べる。
 */
private fun formatCoordinate(decimal: String, dms: String, format: CoordFormat): List<UnitPart> =
    when (format) {
        CoordFormat.DECIMAL -> listOf(UnitPart(decimal, true), UnitPart(dms, false))
        CoordFormat.DMS -> listOf(UnitPart(dms, true), UnitPart(decimal, false))
    }

/**
 * 強調の枠の太さ。枠の線が文字に重ならないよう上下にこの分だけ余白を取り、
 * 枠のない部分とラベルにも同じ余白を入れて、文字の高さをそろえる。
 */
private val UNIT_BORDER = 1.dp

/** 強調の枠の形と、枠の内側の左右の余白 */
private val UNIT_SHAPE = RoundedCornerShape(4.dp)
private val UNIT_PADDING_H = 6.dp

/** 文字列1つの行（既存の表示のまま） */
@Composable
private fun NumRow(label: String, value: String) = NumRowFrame(label) {
    Text(value, fontSize = 13.sp, lineHeight = 13.sp)
}

/**
 * 単位を併記する行。parts が null なら「—」。
 * 強調する単位だけ角丸の枠で囲み、" / " でつなぐ。文字の色・太さ・大きさはすべて同じ。
 * 1行に入り切らないときは単位ごとに折り返す（" / " は前の単位と一緒に置き、行頭に来ないようにする）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NumUnitsRow(label: String, parts: List<UnitPart>?) {
    if (parts == null) {
        NumRow(label, DASH)
        return
    }
    NumRowFrame(label, labelTopPadding = UNIT_BORDER) {
        // 読み上げでは1行の値としてまとめて読む
        FlowRow(Modifier.semantics(mergeDescendants = true) {}) {
            parts.forEachIndexed { i, part ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UnitPartText(part)
                    if (i < parts.lastIndex) Text(" / ", fontSize = 13.sp, lineHeight = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun UnitPartText(part: UnitPart) {
    val modifier = if (part.emphasized) {
        Modifier
            .border(UNIT_BORDER, MaterialTheme.colorScheme.outline, UNIT_SHAPE)
            .padding(horizontal = UNIT_PADDING_H, vertical = UNIT_BORDER)
    } else {
        Modifier.padding(vertical = UNIT_BORDER)
    }
    Text(part.text, modifier, fontSize = 13.sp, lineHeight = 13.sp)
}

/** ラベルと値を横に並べる、数値ページの1行の枠組み */
@Composable
private fun NumRowFrame(
    label: String,
    labelTopPadding: Dp = 0.dp,
    value: @Composable () -> Unit
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            Modifier
                .weight(0.9f)
                .padding(top = labelTopPadding),
            fontSize = 13.sp,
            lineHeight = 13.sp
        )
        Box(Modifier.weight(2.1f)) { value() }
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
                stringResource(R.string.sat_used_visible, used, sats.size),
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
            Text(stringResource(R.string.satlist_system), Modifier.weight(1.3f), fontSize = 11.sp, lineHeight = 12.sp, textAlign = TextAlign.End)
            Text("SV", Modifier.weight(0.6f), fontSize = 11.sp, lineHeight = 12.sp, textAlign = TextAlign.End)
            Row(Modifier.weight(2.0f)) {
                Text(stringResource(R.string.sat_used), Modifier.weight(1f), fontSize = 11.sp, lineHeight = 12.sp, textAlign = TextAlign.End)
                Text(stringResource(R.string.satlist_azimuth), Modifier.weight(1.2f), fontSize = 11.sp, lineHeight = 12.sp, textAlign = TextAlign.End)
                Text(stringResource(R.string.satlist_elevation), Modifier.weight(1.1f), fontSize = 11.sp, lineHeight = 12.sp, textAlign = TextAlign.End)
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
    else -> "UNKNOWN"
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
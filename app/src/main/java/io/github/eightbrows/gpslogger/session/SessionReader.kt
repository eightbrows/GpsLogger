package io.github.eightbrows.gpslogger.session

import android.util.Log
import io.github.eightbrows.gpslogger.calc.Dop
import io.github.eightbrows.gpslogger.log.LogEvent
import java.io.File
import io.github.eightbrows.gpslogger.log.CONSTELLATION_IRNSS
import io.github.eightbrows.gpslogger.calc.Geo

/** track.csv の1行（再生用に必要な項目のみ） */
data class TrackRecord(
    val epochMs: Long,
    val elapsedRealtimeNs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val verticalAccuracy: Float,
    val speed: Float,
    val bearing: Float,
    val bearingAccuracy: Float,
    val dop: Dop?,
    /** 一時停止からの再開直後の点か */
    val gapBefore: Boolean,
    /** 記録時の設定間隔（秒）。読めなければ0 */
    val intervalSec: Int,
    /** 気圧センサーの生値（hPa）。空欄なら null */
    val pressureHpa: Float?
)

/** sats.csv の1エポック分 */
data class SatEpoch(
    val epochId: Long,
    val epochMs: Long,
    val elapsedRealtimeNs: Long,
    val satellites: List<LogEvent.Sat>
)

/** 1セッション分の読み込み済みデータ */
class SessionData(
    val track: List<TrackRecord>,
    val satEpochs: List<SatEpoch>,
    val skippedTrackLines: Int = 0,
    val skippedSatLines: Int = 0
) {
    /**
     * 指定した elapsedRealtimeNs に最も近い衛星エポックを返す。
     * 許容差（既定±1秒）を超える場合は null。
     */
    fun findNearestSatEpoch(
        targetNs: Long,
        toleranceNs: Long = 1_000_000_000L
    ): SatEpoch? {
        if (satEpochs.isEmpty()) return null

        // 二分探索で挿入位置を求める
        var lo = 0
        var hi = satEpochs.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (satEpochs[mid].elapsedRealtimeNs < targetNs) lo = mid + 1 else hi = mid
        }

        // 前後の候補から近い方を選ぶ
        val candidates = listOfNotNull(
            satEpochs.getOrNull(lo - 1),
            satEpochs.getOrNull(lo)
        )
        val nearest = candidates.minByOrNull {
            kotlin.math.abs(it.elapsedRealtimeNs - targetNs)
        } ?: return null

        return if (kotlin.math.abs(nearest.elapsedRealtimeNs - targetNs) <= toleranceNs) {
            nearest
        } else null
    }
}

object SessionReader {

    /** セッションフォルダの一覧（新しい順） */
    fun listSessions(baseDir: File): List<File> =
        baseDir.listFiles { f -> f.isDirectory && f.name.startsWith("session_") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    /** セッションを読み込む。track.csv が無ければ null。 */
    fun read(sessionDir: File): SessionData? {
        val trackFile = File(sessionDir, "track.csv")
        if (!trackFile.exists()) return null

        val (track, trackSkipped) = readTrack(trackFile)
        val satEpochs = readSats(File(sessionDir, "sats.csv"))
        Log.d(TAG, "read ${sessionDir.name}: track=${track.size} (skipped=$trackSkipped) satEpochs=${satEpochs.size}")

        return SessionData(track, satEpochs, trackSkipped)
    }

    /**
     * meta.json を読む。ファイルが無い（既存セッション）・読めない・JSONとして不正なら null。
     * 例外は投げない。
     */
    fun readMeta(sessionDir: File): SessionMeta? {
        val file = File(sessionDir, SessionMeta.FILE_NAME)
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (e: Exception) {
            Log.w(TAG, "cannot read ${file.path}", e)
            return null
        }
        return SessionMeta.parse(text).also {
            if (it == null) Log.w(TAG, "invalid meta.json: ${sessionDir.name}")
        }
    }

    /**
     * track.csv の移動距離（m）。区間ごとに積算して合計する。
     * gap_before の点の手前（一時停止をまたぐ部分）は含めない。ファイルが無ければ 0
     */
    fun trackDistanceM(sessionDir: File): Double {
        val file = File(sessionDir, "track.csv")
        if (!file.exists()) return 0.0
        val (track, _) = readTrack(file)
        return Geo.pathLengthM(track.map { it.latitude to it.longitude }) { track[it].gapBefore }
    }

    /** track.csv の測位点だけを読む（衛星は読まない）。track.csv が無ければ null */
    fun readTrackRecords(sessionDir: File): List<TrackRecord>? {
        val file = File(sessionDir, "track.csv")
        if (!file.exists()) return null
        return readTrack(file).first
    }

    private fun readTrack(file: File): Pair<List<TrackRecord>, Int> =
        file.bufferedReader().useLines { parseTrack(it) }

    /**
     * track.csv の行（1 行目はヘッダー）を読む。戻り値は（読めた点, 読み飛ばした行数）。
     *
     * 列は番号ではなくヘッダーの列名で引くので、旧形式も読める。
     *  - v1（19 列）: …, tdop, is_mock
     *  - v2（22 列）: + gap_before, interval_sec, pressure_hpa
     * 無い列は既定値（gap_before=false, interval_sec=0, pressure_hpa=null など）。
     * 必須は epoch_ms・latitude・longitude で、これが読めない行は読み飛ばして数える。空行は数えない。
     */
    fun parseTrack(lines: Sequence<String>): Pair<List<TrackRecord>, Int> {
        val result = ArrayList<TrackRecord>()
        var skipped = 0
        val iter = lines.iterator()
        if (!iter.hasNext()) return result to 0
        val cols = TrackColumns.fromHeader(iter.next()) ?: TrackColumns.COMMON

        for (line in iter) {
            if (line.isBlank()) continue
            val c = line.split(',')
            fun s(name: String): String? = cols[name]?.let { c.getOrNull(it) }?.trim()

            val record = try {
                TrackRecord(
                    epochMs = s("epoch_ms")!!.toLong(),
                    elapsedRealtimeNs = s("elapsed_realtime_ns")?.toLongOrNull() ?: 0L,
                    latitude = s("latitude")!!.toDouble(),
                    longitude = s("longitude")!!.toDouble(),
                    altitude = s("altitude_ellipsoid_m")?.toDoubleOrNull() ?: 0.0,
                    accuracy = s("horizontal_acc_m")?.toFloatOrNull() ?: 0f,
                    verticalAccuracy = s("vertical_acc_m")?.toFloatOrNull() ?: 0f,
                    speed = s("speed_mps")?.toFloatOrNull() ?: 0f,
                    bearing = s("bearing_deg")?.toFloatOrNull() ?: 0f,
                    bearingAccuracy = s("bearing_acc_deg")?.toFloatOrNull() ?: 0f,
                    dop = parseDop(::s),
                    gapBefore = s("gap_before")?.toBoolean() ?: false,
                    intervalSec = s("interval_sec")?.toIntOrNull() ?: 0,
                    pressureHpa = s("pressure_hpa")?.toFloatOrNull()
                )
            } catch (e: Exception) {
                null
            }
            if (record == null) skipped++ else result += record
        }
        return result to skipped
    }

    /** gdop,pdop,hdop,vdop,tdop。どれか欠けていれば null */
    private fun parseDop(get: (String) -> String?): Dop? {
        val g = get("gdop")?.toDoubleOrNull() ?: return null
        val p = get("pdop")?.toDoubleOrNull() ?: return null
        val h = get("hdop")?.toDoubleOrNull() ?: return null
        val v = get("vdop")?.toDoubleOrNull() ?: return null
        val t = get("tdop")?.toDoubleOrNull() ?: return null
        return Dop(g, p, h, v, t)
    }

    /** track.csv の列名 → 列番号 */
    private class TrackColumns(private val index: Map<String, Int>) {
        operator fun get(name: String): Int? = index[name]

        companion object {
            private val REQUIRED = listOf("epoch_ms", "latitude", "longitude")

            /** 全形式で共通の先頭 19 列の並び。ヘッダーが読めないときに使う */
            val COMMON = of(
                "utc_iso8601", "epoch_ms", "elapsed_realtime_ns", "provider", "latitude", "longitude",
                "altitude_ellipsoid_m", "horizontal_acc_m", "vertical_acc_m", "speed_mps", "speed_acc_mps",
                "bearing_deg", "bearing_acc_deg", "gdop", "pdop", "hdop", "vdop", "tdop", "is_mock"
            )

            private fun of(vararg names: String) =
                TrackColumns(names.withIndex().associate { (i, n) -> n to i })

            /** ヘッダー行から作る。必須の列が無ければ null */
            fun fromHeader(header: String): TrackColumns? {
                val names = header.removePrefix("\uFEFF").split(',').map { it.trim() }
                val map = names.withIndex().associate { (i, n) -> n to i }
                return if (REQUIRED.all { it in map }) TrackColumns(map) else null
            }
        }
    }

    private fun readSats(file: File): List<SatEpoch> {
        if (!file.exists()) return emptyList()

        // epoch_id ごとにまとめる（ファイルは時系列順なので順次まとめる）
        val result = ArrayList<SatEpoch>()
        var currentId = -1L
        var currentMs = 0L
        var currentNs = 0L
        var buffer = ArrayList<LogEvent.Sat>()

        fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                result.add(SatEpoch(currentId, currentMs, currentNs, buffer))
                buffer = ArrayList()
            }
        }

        file.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val c = line.split(',')
                if (c.size < 14) return@forEach
                runCatching {
                    val epochId = c[3].toLong()
                    if (epochId != currentId) {
                        flushBuffer()
                        currentId = epochId
                        currentMs = c[1].toLong()
                        currentNs = c[2].toLong()
                    }
                    buffer.add(
                        LogEvent.Sat(
                            constellation = constellationTypeOf(c[4]),
                            svid = c[5].toInt(),
                            cn0DbHz = c[6].toFloatOrNull() ?: 0f,
                            basebandCn0DbHz = c[7].toFloatOrNull() ?: 0f,
                            elevationDeg = c[8].toFloatOrNull() ?: 0f,
                            azimuthDeg = c[9].toFloatOrNull() ?: 0f,
                            carrierFrequencyHz = c[10].toFloatOrNull() ?: 0f,
                            usedInFix = c[11].toBoolean(),
                            hasAlmanac = c[12].toBoolean(),
                            hasEphemeris = c[13].trim().toBoolean()
                        )
                    )
                }
            }
        }
        flushBuffer()
        return result
    }

    /** CSVに書いた名称から定数へ戻す */
    private fun constellationTypeOf(name: String): Int = when (name) {
        "GPS" -> android.location.GnssStatus.CONSTELLATION_GPS
        "GLONASS" -> android.location.GnssStatus.CONSTELLATION_GLONASS
        "GALILEO" -> android.location.GnssStatus.CONSTELLATION_GALILEO
        "BEIDOU" -> android.location.GnssStatus.CONSTELLATION_BEIDOU
        "QZSS" -> android.location.GnssStatus.CONSTELLATION_QZSS
        "SBAS" -> android.location.GnssStatus.CONSTELLATION_SBAS
        "IRNSS" -> CONSTELLATION_IRNSS
        else -> android.location.GnssStatus.CONSTELLATION_UNKNOWN
    }

    private const val TAG = "SessionReader"
}
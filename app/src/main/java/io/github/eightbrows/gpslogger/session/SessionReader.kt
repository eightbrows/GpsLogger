package io.github.eightbrows.gpslogger.session

import android.util.Log
import io.github.eightbrows.gpslogger.calc.Dop
import io.github.eightbrows.gpslogger.log.LogEvent
import java.io.File
import io.github.eightbrows.gpslogger.log.CONSTELLATION_IRNSS

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

    private fun readTrack(file: File): Pair<List<TrackRecord>, Int> {
        val result = ArrayList<TrackRecord>()
        var skipped = 0
        file.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val c = line.split(',')
                if (c.size < TRACK_COLUMNS) {
                    if (line.isNotBlank()) skipped++
                    return@forEach
                }
                runCatching {
                    result.add(
                        TrackRecord(
                            epochMs = c[1].toLong(),
                            elapsedRealtimeNs = c[2].toLong(),
                            latitude = c[4].toDouble(),
                            longitude = c[5].toDouble(),
                            altitude = c[6].toDoubleOrNull() ?: 0.0,
                            accuracy = c[7].toFloatOrNull() ?: 0f,
                            verticalAccuracy = c[8].toFloatOrNull() ?: 0f,
                            speed = c[9].toFloatOrNull() ?: 0f,
                            bearing = c[11].toFloatOrNull() ?: 0f,
                            bearingAccuracy = c[12].toFloatOrNull() ?: 0f,
                            dop = parseDop(c),
                            gapBefore = c[19].trim().toBoolean(),
                            intervalSec = c[20].trim().toIntOrNull() ?: 0,
                            pressureHpa = c[21].trim().toFloatOrNull()
                        )
                    )
                }.onFailure { skipped++ }
            }
        }
        return result to skipped
    }

    /** gdop,pdop,hdop,vdop,tdop は 13〜17列目。空欄なら null。 */
    private fun parseDop(c: List<String>): Dop? {
        val g = c.getOrNull(13)?.toDoubleOrNull() ?: return null
        val p = c.getOrNull(14)?.toDoubleOrNull() ?: return null
        val h = c.getOrNull(15)?.toDoubleOrNull() ?: return null
        val v = c.getOrNull(16)?.toDoubleOrNull() ?: return null
        val t = c.getOrNull(17)?.toDoubleOrNull() ?: return null
        return Dop(g, p, h, v, t)
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

    /** track.csv v2 の列数。これ未満の行は読み飛ばす（v1データは読めない） */
    private const val TRACK_COLUMNS = 22

    private const val TAG = "SessionReader"
}
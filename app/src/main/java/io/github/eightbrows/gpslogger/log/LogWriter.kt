package io.github.eightbrows.gpslogger.log

import android.location.GnssStatus
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LogWriter(
    private val sessionDir: File,
    private val onError: ((String) -> Unit)? = null
) {

    private val queue = LinkedBlockingQueue<LogEvent>()
    private var thread: Thread? = null
    @Volatile private var running = false

    private val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    fun start() {
        if (running) return
        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            Log.e(TAG, "failed to create session dir")
            onError?.invoke("保存先フォルダを作成できませんでした")
            return
        }
        running = true
        thread = Thread { runLoop() }.also { it.start() }
        Log.d(TAG, "writer started: ${sessionDir.absolutePath}")
    }

    fun submit(event: LogEvent) {
        if (running) queue.offer(event)
    }

    fun stop() {
        running = false
        val t = thread
        thread = null
        if (t != null) {
            t.join(5000)
            if (t.isAlive) {
                Log.w(TAG, "writer thread did not stop in time; data may be lost")
            }
        }
        Log.d(TAG, "writer stopped")
    }

    private fun runLoop() {
        val trackFile = File(sessionDir, "track.csv")
        val satsFile = File(sessionDir, "sats.csv")

        val trackWriter = BufferedWriter(FileWriter(trackFile, true), BUFFER_SIZE)
        val satsWriter = BufferedWriter(FileWriter(satsFile, true), BUFFER_SIZE)

        try {
            if (trackFile.length() == 0L) {
                trackWriter.write(TRACK_HEADER); trackWriter.newLine()
            }
            if (satsFile.length() == 0L) {
                satsWriter.write(SATS_HEADER); satsWriter.newLine()
            }

            var lastFlush = System.currentTimeMillis()

            while (running || queue.isNotEmpty()) {
                val event = queue.poll(1, TimeUnit.SECONDS)
                when (event) {
                    is LogEvent.Fix -> writeFix(trackWriter, event)
                    is LogEvent.Sats -> writeSats(satsWriter, event)
                    null -> { /* タイムアウト。flush判定へ */ }
                }

                val now = System.currentTimeMillis()
                if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                    trackWriter.flush()
                    satsWriter.flush()
                    lastFlush = now
                    Log.d(TAG, "flushed")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "writer error", e)
            onError?.invoke("記録の書き込みに失敗しました: ${e.message}")
        } finally {
            runCatching { trackWriter.flush(); trackWriter.close() }
                .onFailure { onError?.invoke("記録の保存に失敗しました") }
            runCatching { satsWriter.flush(); satsWriter.close() }
                .onFailure { onError?.invoke("記録の保存に失敗しました") }
            Log.d(TAG, "writer closed (final flush done)")
        }
    }

    private fun writeFix(w: BufferedWriter, e: LogEvent.Fix) {
        val loc = e.location
        val sb = StringBuilder()
        sb.append(utcFormat.format(Date(loc.time))).append(',')
        sb.append(loc.time).append(',')
        sb.append(loc.elapsedRealtimeNanos).append(',')
        sb.append(loc.provider ?: "").append(',')
        sb.append(loc.latitude).append(',')
        sb.append(loc.longitude).append(',')
        sb.append(loc.altitude).append(',')
        sb.append(loc.accuracy).append(',')
        sb.append(loc.verticalAccuracyMeters).append(',')
        sb.append(loc.speed).append(',')
        sb.append(loc.speedAccuracyMetersPerSecond).append(',')
        sb.append(loc.bearing).append(',')
        sb.append(loc.bearingAccuracyDegrees).append(',')

        val d = e.dop
        if (d != null) {
            sb.append("%.2f".format(d.gdop)).append(',')
            sb.append("%.2f".format(d.pdop)).append(',')
            sb.append("%.2f".format(d.hdop)).append(',')
            sb.append("%.2f".format(d.vdop)).append(',')
            sb.append("%.2f".format(d.tdop)).append(',')
        } else {
            sb.append(",,,,,")  // 測位不成立・衛星不足時は空欄
        }
        val isMock = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            loc.isMock
        } else {
            @Suppress("DEPRECATION")
            loc.isFromMockProvider
        }
        sb.append(isMock)
        w.write(sb.toString())
        w.newLine()
    }

    private fun writeSats(w: BufferedWriter, e: LogEvent.Sats) {
        val utc = utcFormat.format(Date(e.epochMs))
        for (s in e.satellites) {
            val sb = StringBuilder()
            sb.append(utc).append(',')
            sb.append(e.epochMs).append(',')
            sb.append(e.elapsedRealtimeNs).append(',')
            sb.append(e.epochId).append(',')
            sb.append(constellationName(s.constellation)).append(',')
            sb.append(s.svid).append(',')
            sb.append(s.cn0DbHz).append(',')
            sb.append(s.basebandCn0DbHz).append(',')
            sb.append(s.elevationDeg).append(',')
            sb.append(s.azimuthDeg).append(',')
            sb.append(s.carrierFrequencyHz).append(',')
            sb.append(s.usedInFix).append(',')
            sb.append(s.hasAlmanac).append(',')
            sb.append(s.hasEphemeris)
            w.write(sb.toString())
            w.newLine()
        }
    }

    private fun constellationName(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_GALILEO -> "GALILEO"
        GnssStatus.CONSTELLATION_BEIDOU -> "BEIDOU"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
        else -> "UNKNOWN"
    }

    companion object {
        private const val TAG = "LogWriter"
        private const val BUFFER_SIZE = 128 * 1024      // 128KB
        private const val FLUSH_INTERVAL_MS = 30_000L   // 30秒

        private const val TRACK_HEADER =
            "utc_iso8601,epoch_ms,elapsed_realtime_ns,provider,latitude,longitude," +
                    "altitude_ellipsoid_m,horizontal_acc_m,vertical_acc_m,speed_mps,speed_acc_mps," +
                    "bearing_deg,bearing_acc_deg,gdop,pdop,hdop,vdop,tdop,is_mock"

        private const val SATS_HEADER =
            "utc_iso8601,epoch_ms,elapsed_realtime_ns,epoch_id,constellation,svid," +
                    "cn0_dbhz,baseband_cn0_dbhz,elevation_deg,azimuth_deg,carrier_frequency_hz," +
                    "used_in_fix,has_almanac,has_ephemeris"
    }
}
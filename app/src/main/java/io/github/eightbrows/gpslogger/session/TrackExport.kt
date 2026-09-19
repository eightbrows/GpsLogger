package io.github.eightbrows.gpslogger.session

import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 書き出し形式 */
enum class TrackFormat(val label: String, val fileName: String) {
    GPX("GPX", "track.gpx"),
    KMZ("KMZ", "track.kmz")
}

/** 複数セッションを書き出した結果 */
data class ExportSummary(val format: TrackFormat, val succeeded: Int, val failed: Int)

/**
 * ZIP エクスポート前の自動生成の結果。
 * created は新しく作ったファイルの数、failed は作れなかったファイルがあるセッションの数
 */
data class EnsureSummary(val created: Int, val failed: Int)

/**
 * track.csv の測位点を GPX 1.1 / KML（KMZ）に変換する。
 * 出力するのは緯度・経度・高度・時刻だけで、アプリ独自の情報は含めない。
 * 一時停止で空いた区間（gap_before の点の手前）で区切る。
 *
 * 高度は「楕円体高 − ジオイド高（geoidHeightM）」で標高に換算して出す。
 * geoidHeightM が 0 なら楕円体高のまま。
 */
object TrackExport {

    /** KMZ の中の KML のファイル名（KMZ の慣例） */
    private const val KML_ENTRY = "doc.kml"

    /**
     * 一時停止で区間に分ける。gapBefore の点から新しい区間を始める。
     * 先頭の点の gapBefore は無視する（空の区間は作らない）。
     */
    fun segments(track: List<TrackRecord>): List<List<TrackRecord>> {
        val out = ArrayList<MutableList<TrackRecord>>()
        for (p in track) {
            if (out.isEmpty() || (p.gapBefore && out.last().isNotEmpty())) out.add(ArrayList())
            out.last().add(p)
        }
        return out
    }

    /** 出力する高度（m）。楕円体高からジオイド高を引く。高度が不明（NaN など）なら null */
    fun outputAltitude(p: TrackRecord, geoidHeightM: Double): Double? =
        (p.altitude - geoidHeightM).takeIf { it.isFinite() }

    // ================= GPX =================

    /** GPX 1.1。区間ごとに <trkseg> を分けた 1 本の <trk> */
    fun toGpx(name: String, track: List<TrackRecord>, geoidHeightM: Double = 0.0): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append(
            """<gpx version="1.1" creator="GpsLogger" """ +
                """xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """ +
                """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 """ +
                """http://www.topografix.com/GPX/1/1/gpx.xsd">"""
        ).append('\n')
        append("  <metadata>\n")
        append("    <name>").append(xml(name)).append("</name>\n")
        track.firstOrNull()?.let { append("    <time>").append(time(it.epochMs)).append("</time>\n") }
        append("  </metadata>\n")
        append("  <trk>\n")
        append("    <name>").append(xml(name)).append("</name>\n")
        for (seg in segments(track)) {
            append("    <trkseg>\n")
            for (p in seg) {
                append("      <trkpt lat=\"").append(coord(p.latitude))
                    .append("\" lon=\"").append(coord(p.longitude)).append("\">")
                outputAltitude(p, geoidHeightM)?.let { append("<ele>").append(ele(it)).append("</ele>") }
                append("<time>").append(time(p.epochMs)).append("</time>")
                append("</trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n")
        append("</gpx>\n")
    }

    // ================= KML =================

    /**
     * KML 2.2 ＋ Google の拡張 gx:Track（Google Earth 向け）。
     * 区間ごとに Placemark を分け、それぞれの gx:Track に点ごとの時刻（<when>）と
     * 座標（<gx:coord>「経度 緯度 高度」）を持たせる。1 点だけの区間も 1 点の gx:Track にする。
     * <when> を全点ぶん並べてから <gx:coord> を同じ順で並べる（KML リファレンスの例と同じ並び）。
     * 高度が不明な点は、<when> と数が合わなくならないよう高度 0 として出す。
     */
    fun toKml(name: String, track: List<TrackRecord>, geoidHeightM: Double = 0.0): String = buildString {
        val segs = segments(track)
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">""")
            .append('\n')
        append("  <Document>\n")
        append("    <name>").append(xml(name)).append("</name>\n")
        // 線の色（aabbggrr）。アプリの記録中の既定色（#E91E63）に合わせる
        append("    <Style id=\"track\"><LineStyle><color>ff631ee9</color><width>3</width></LineStyle></Style>\n")
        segs.forEachIndexed { i, seg ->
            val title = if (segs.size == 1) name else "$name (${i + 1}/${segs.size})"
            append("    <Placemark>\n")
            append("      <name>").append(xml(title)).append("</name>\n")
            append("      <styleUrl>#track</styleUrl>\n")
            append("      <gx:Track>\n")
            append("        <altitudeMode>absolute</altitudeMode>\n")
            for (p in seg) append("        <when>").append(time(p.epochMs)).append("</when>\n")
            for (p in seg) {
                append("        <gx:coord>").append(coord(p.longitude)).append(' ').append(coord(p.latitude))
                    .append(' ').append(ele(outputAltitude(p, geoidHeightM) ?: 0.0)).append("</gx:coord>\n")
            }
            append("      </gx:Track>\n")
            append("    </Placemark>\n")
        }
        append("  </Document>\n")
        append("</kml>\n")
    }

    // ================= ファイルへの書き出し =================

    /** セッションフォルダに track.gpx を書く（既存は上書き） */
    fun writeGpx(sessionDir: File, name: String, track: List<TrackRecord>, geoidHeightM: Double = 0.0): File {
        val target = File(sessionDir, TrackFormat.GPX.fileName)
        writeAtomically(target) { it.write(toGpx(name, track, geoidHeightM).toByteArray(Charsets.UTF_8)) }
        return target
    }

    /** セッションフォルダに track.kmz（doc.kml を ZIP にしたもの）を書く（既存は上書き） */
    fun writeKmz(sessionDir: File, name: String, track: List<TrackRecord>, geoidHeightM: Double = 0.0): File {
        val target = File(sessionDir, TrackFormat.KMZ.fileName)
        writeAtomically(target) { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(KML_ENTRY))
                zip.write(toKml(name, track, geoidHeightM).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return target
    }

    /**
     * 選んだセッションをまとめて書き出す。track.csv が無い・読めない・点が 0 のものは失敗として数える。
     * ファイルを読み書きするので、メインスレッドから呼ばないこと。
     */
    fun exportSessions(dirs: List<File>, format: TrackFormat, geoidHeightM: Double = 0.0): ExportSummary {
        var ok = 0
        var failed = 0
        for (dir in dirs) {
            val track = try {
                SessionReader.readTrackRecords(dir)
            } catch (e: Exception) {
                null
            }
            if (track.isNullOrEmpty()) {
                failed++
                continue
            }
            try {
                when (format) {
                    TrackFormat.GPX -> writeGpx(dir, dir.name, track, geoidHeightM)
                    TrackFormat.KMZ -> writeKmz(dir, dir.name, track, geoidHeightM)
                }
                ok++
            } catch (e: Exception) {
                failed++
            }
        }
        return ExportSummary(format, ok, failed)
    }

    /**
     * ZIP エクスポートの前処理。track.gpx / track.kmz のうち、まだ無い方だけを作る。
     * 既にあるファイルは上書きしない（作り直しは 3 点メニューからの手動書き出しで行う）。
     * 失敗したセッションがあっても、残りのセッションは続けて処理する。
     * ファイルを読み書きするので、メインスレッドから呼ばないこと。
     */
    fun ensureExports(dirs: List<File>, geoidHeightM: Double = 0.0): EnsureSummary {
        var created = 0
        var failed = 0
        for (dir in dirs) {
            val missing = TrackFormat.entries.filter { !File(dir, it.fileName).exists() }
            if (missing.isEmpty()) continue
            // track.csv は無いファイルがあるときだけ、1 セッションにつき 1 回読む
            val track = try {
                SessionReader.readTrackRecords(dir)
            } catch (e: Exception) {
                null
            }
            if (track.isNullOrEmpty()) {
                failed++
                continue
            }
            var ok = true
            for (format in missing) {
                try {
                    when (format) {
                        TrackFormat.GPX -> writeGpx(dir, dir.name, track, geoidHeightM)
                        TrackFormat.KMZ -> writeKmz(dir, dir.name, track, geoidHeightM)
                    }
                    created++
                } catch (e: Exception) {
                    ok = false
                }
            }
            if (!ok) failed++
        }
        return EnsureSummary(created, failed)
    }

    /** 途中で落ちても壊れたファイルが残らないよう、一時ファイルに書いてから置き換える */
    private fun writeAtomically(target: File, write: (OutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.outputStream().buffered().use(write)
            if (!tmp.renameTo(target)) {
                // 置き換えに失敗する環境向け。上書きしてから一時ファイルを消す
                tmp.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        } finally {
            tmp.delete()
        }
    }

    // ================= 書式（端末の言語設定に左右されないよう Locale.ROOT で固定） =================

    private fun coord(v: Double): String = String.format(Locale.ROOT, "%.8f", v)
    private fun ele(v: Double): String = String.format(Locale.ROOT, "%.2f", v)
    private fun time(epochMs: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))

    private fun xml(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }
}

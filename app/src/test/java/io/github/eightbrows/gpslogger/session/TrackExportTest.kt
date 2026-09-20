package io.github.eightbrows.gpslogger.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class TrackExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var savedLocale: Locale

    @Before
    fun useCommaDecimalLocale() {
        // 小数点がカンマの言語でも、座標は "." で出ること
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    private fun pt(i: Int, lat: Double, lon: Double, alt: Double = 40.0 + i, gap: Boolean = false) =
        TrackRecord(
            epochMs = 1_789_431_600_000L + i * 1000L,
            elapsedRealtimeNs = 0L,
            latitude = lat, longitude = lon, altitude = alt,
            accuracy = 3f, verticalAccuracy = 5f, speed = 1f, bearing = 0f, bearingAccuracy = 0f,
            dop = null, gapBefore = gap, intervalSec = 1, pressureHpa = null
        )

    /** 2 回の一時停止で 3 区間（3 点・2 点・1 点） */
    private val track = listOf(
        pt(0, 35.6812360, 139.7671250),
        pt(1, 35.6813, 139.7672),
        pt(2, 35.6814, 139.7673),
        pt(3, 35.7000, 139.8000, gap = true),
        pt(4, 35.7001, 139.8001),
        pt(5, 35.7500, 139.9000, gap = true)
    )

    private fun parseXml(text: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(text.byteInputStream(Charsets.UTF_8))

    private fun Document.all(ns: String, tag: String): List<Element> {
        val list = getElementsByTagNameNS(ns, tag)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private val gpxNs = "http://www.topografix.com/GPX/1/1"
    private val kmlNs = "http://www.opengis.net/kml/2.2"

    // ===== 区間分割 =====

    @Test
    fun segments_splitAtGapPoints() {
        assertEquals(listOf(3, 2, 1), TrackExport.segments(track).map { it.size })
        assertEquals(track[3], TrackExport.segments(track)[1].first())
    }

    @Test
    fun segments_withoutGaps_isOneSegment() {
        val plain = track.map { it.copy(gapBefore = false) }
        assertEquals(listOf(6), TrackExport.segments(plain).map { it.size })
    }

    @Test
    fun segments_gapOnFirstPoint_doesNotCreateEmptySegment() {
        val t = listOf(pt(0, 1.0, 2.0, gap = true), pt(1, 1.1, 2.1))
        assertEquals(listOf(2), TrackExport.segments(t).map { it.size })
        assertEquals(emptyList<List<TrackRecord>>(), TrackExport.segments(emptyList()))
    }

    // ===== GPX =====

    @Test
    fun gpx_isValidGpx11WithOneTrackAndSegmentsPerPause() {
        val doc = parseXml(TrackExport.toGpx("session_20260915_092000", track))
        val root = doc.documentElement
        assertEquals("gpx", root.localName)
        assertEquals(gpxNs, root.namespaceURI)
        assertEquals("1.1", root.getAttribute("version"))
        assertEquals(1, doc.all(gpxNs, "trk").size)
        val segs = doc.all(gpxNs, "trkseg")
        assertEquals(listOf(3, 2, 1), segs.map { it.getElementsByTagNameNS(gpxNs, "trkpt").length })
    }

    @Test
    fun gpx_writesCoordinatesElevationAndTime() {
        val doc = parseXml(TrackExport.toGpx("s", track))
        val first = doc.all(gpxNs, "trkpt").first()
        assertEquals("35.68123600", first.getAttribute("lat"))
        assertEquals("139.76712500", first.getAttribute("lon"))
        assertEquals("40.00", first.getElementsByTagNameNS(gpxNs, "ele").item(0).textContent)
        assertEquals("2026-09-15T00:20:00Z", first.getElementsByTagNameNS(gpxNs, "time").item(0).textContent)
        val last = doc.all(gpxNs, "trkpt").last()
        assertEquals("35.75000000", last.getAttribute("lat"))
        assertEquals("2026-09-15T00:20:05Z", last.getElementsByTagNameNS(gpxNs, "time").item(0).textContent)
    }

    @Test
    fun gpx_containsNoAppSpecificExtensions() {
        val text = TrackExport.toGpx("s", track)
        assertFalse(text.contains("<extensions"))
        assertFalse(text.contains("dop", ignoreCase = true))
    }

    @Test
    fun gpx_escapesName() {
        val doc = parseXml(TrackExport.toGpx("a & <b>", track))
        assertEquals("a & <b>", doc.all(gpxNs, "name").first().textContent)
    }

    // ===== KML =====

    private val gxNs = "http://www.google.com/kml/ext/2.2"

    private fun Element.texts(ns: String, tag: String): List<String> {
        val list = getElementsByTagNameNS(ns, tag)
        return (0 until list.length).map { list.item(it).textContent }
    }

    @Test
    fun kml_declaresGxNamespace_andHasOneGxTrackPerSegment() {
        val text = TrackExport.toKml("s", track)
        assertTrue(text.contains("""xmlns:gx="http://www.google.com/kml/ext/2.2""""))
        val doc = parseXml(text)
        assertEquals(3, doc.all(kmlNs, "Placemark").size)
        val tracks = doc.all(gxNs, "Track")
        // 1 点だけの区間も 1 点の gx:Track にする（LineString・Point・TimeSpan は使わない）
        assertEquals(listOf(3, 2, 1), tracks.map { it.texts(gxNs, "coord").size })
        assertEquals(0, doc.all(kmlNs, "LineString").size)
        assertEquals(0, doc.all(kmlNs, "Point").size)
        assertEquals(0, doc.all(kmlNs, "TimeSpan").size)
        assertEquals(listOf("s (1/3)", "s (2/3)", "s (3/3)"),
            doc.all(kmlNs, "Placemark").map { it.getElementsByTagNameNS(kmlNs, "name").item(0).textContent })
    }

    @Test
    fun kml_gxTrackHasWhenAndCoordPerPoint() {
        val doc = parseXml(TrackExport.toKml("s", track))
        val first = doc.all(gxNs, "Track").first()
        // <when> は KML 名前空間、<gx:coord> は gx 名前空間。数は点の数と同じ
        assertEquals(
            listOf("2026-09-15T00:20:00Z", "2026-09-15T00:20:01Z", "2026-09-15T00:20:02Z"),
            first.texts(kmlNs, "when")
        )
        // 経度 緯度 高度（スペース区切り）
        assertEquals(
            listOf(
                "139.76712500 35.68123600 40.00",
                "139.76720000 35.68130000 41.00",
                "139.76730000 35.68140000 42.00"
            ),
            first.texts(gxNs, "coord")
        )
        val second = doc.all(gxNs, "Track")[1]
        assertEquals(listOf("2026-09-15T00:20:03Z", "2026-09-15T00:20:04Z"), second.texts(kmlNs, "when"))
    }

    @Test
    fun kml_gxTrackUsesAbsoluteAltitude() {
        val doc = parseXml(TrackExport.toKml("s", track))
        assertEquals(
            listOf("absolute", "absolute", "absolute"),
            doc.all(gxNs, "Track").map { it.getElementsByTagNameNS(kmlNs, "altitudeMode").item(0).textContent }
        )
    }

    // ===== ジオイド高（標高換算） =====

    @Test
    fun geoidHeight_isSubtractedInGpx() {
        val doc = parseXml(TrackExport.toGpx("s", track, geoidOffsetM = 36.0))
        val eles = doc.all(gpxNs, "ele").map { it.textContent }
        // 楕円体高 40〜45 m − 36 m
        assertEquals(listOf("4.00", "5.00", "6.00", "7.00", "8.00", "9.00"), eles)
    }

    @Test
    fun geoidHeight_isSubtractedInKml() {
        val doc = parseXml(TrackExport.toKml("s", track, geoidOffsetM = 36.5))
        val coords = doc.all(gxNs, "coord").map { it.textContent }
        assertEquals("139.76712500 35.68123600 3.50", coords.first())
        assertEquals("139.90000000 35.75000000 8.50", coords.last())
    }

    @Test
    fun geoidHeight_zeroMeansNoCorrection_andNegativeAddsHeight() {
        assertEquals(TrackExport.toGpx("s", track), TrackExport.toGpx("s", track, geoidOffsetM = 0.0))
        assertEquals(40.0, TrackExport.outputAltitude(track[0], 0.0)!!, 0.0)
        // ジオイド高が負の地域（例: インド洋）では楕円体高より高くなる
        assertEquals(50.0, TrackExport.outputAltitude(track[0], -10.0)!!, 0.0)
        // 高さが海抜より低くなっても負の値のまま出す
        assertEquals(-60.0, TrackExport.outputAltitude(track[0], 100.0)!!, 1e-9)
        assertEquals(null, TrackExport.outputAltitude(track[0].copy(altitude = Double.NaN), 36.0))
    }

    @Test
    fun geoidHeight_isAppliedByExportSessions() {
        val header = "utc_iso8601,epoch_ms,elapsed_realtime_ns,provider,latitude,longitude," +
            "altitude_ellipsoid_m,horizontal_acc_m,vertical_acc_m,speed_mps,speed_acc_mps," +
            "bearing_deg,bearing_acc_deg,gdop,pdop,hdop,vdop,tdop,is_mock"
        val dir = File(tmp.root, "session_20260815_090000").apply { mkdirs() }
        File(dir, "track.csv").writeText("$header\nt,1000,0,gps,35.1,139.1,50.25,3,5,0,0,0,0,,,,,,false")
        TrackExport.exportSessions(listOf(dir), TrackFormat.GPX, fallbackGeoidM = 36.0)
        val doc = parseXml(File(dir, "track.gpx").readText())
        assertEquals("14.25", doc.all(gpxNs, "ele").single().textContent)
    }

    @Test
    fun kml_singleSegmentUsesPlainName() {
        val doc = parseXml(TrackExport.toKml("s", track.take(3)))
        assertEquals(1, doc.all(kmlNs, "Placemark").size)
        assertEquals("s", doc.all(kmlNs, "Placemark").first()
            .getElementsByTagNameNS(kmlNs, "name").item(0).textContent)
    }

    // ===== ファイル =====

    @Test
    fun writeGpx_overwritesAndLeavesNoTempFile() {
        val dir = tmp.newFolder()
        File(dir, "track.gpx").writeText("old")
        TrackExport.writeGpx(dir, "s", track)
        val text = File(dir, "track.gpx").readText()
        assertTrue(text.startsWith("<?xml"))
        assertEquals(TrackExport.toGpx("s", track), text)
        assertFalse(File(dir, "track.gpx.tmp").exists())
    }

    @Test
    fun writeKmz_isZipContainingDocKml() {
        val dir = tmp.newFolder()
        File(dir, "track.kmz").writeText("old")
        TrackExport.writeKmz(dir, "s", track)
        ZipFile(File(dir, "track.kmz")).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            assertEquals(listOf("doc.kml"), entries)
            val kml = zip.getInputStream(zip.getEntry("doc.kml")).readBytes().toString(Charsets.UTF_8)
            assertEquals(TrackExport.toKml("s", track), kml)
        }
        assertFalse(File(dir, "track.kmz.tmp").exists())
    }

    @Test
    fun exportSessions_countsSuccessAndFailure_perSessionFolder() {
        val header = "utc_iso8601,epoch_ms,elapsed_realtime_ns,provider,latitude,longitude," +
            "altitude_ellipsoid_m,horizontal_acc_m,vertical_acc_m,speed_mps,speed_acc_mps," +
            "bearing_deg,bearing_acc_deg,gdop,pdop,hdop,vdop,tdop,is_mock"
        fun row(i: Int) = "t,${1000 + i},0,gps,35.$i,139.$i,10.0,3,5,0,0,0,0,,,,,,false"

        val v1 = File(tmp.root, "session_20260815_090000").apply { mkdirs() }
        File(v1, "track.csv").writeText(listOf(header, row(1), row(2)).joinToString("\n"))
        val v2 = File(tmp.root, "session_20260915_090000").apply { mkdirs() }
        File(v2, "track.csv").writeText(
            listOf("$header,gap_before,interval_sec,pressure_hpa", row(1) + ",false,1,", row(2) + ",true,1,")
                .joinToString("\n")
        )
        val empty = File(tmp.root, "session_20260915_100000").apply { mkdirs() }
        File(empty, "track.csv").writeText(header)
        val missing = File(tmp.root, "session_20260915_110000").apply { mkdirs() }

        val summary = TrackExport.exportSessions(listOf(v1, v2, empty, missing), TrackFormat.GPX)
        assertEquals(ExportSummary(TrackFormat.GPX, succeeded = 2, failed = 2), summary)
        assertTrue(File(v1, "track.gpx").exists())
        assertTrue(File(v2, "track.gpx").exists())
        assertFalse(File(empty, "track.gpx").exists())
        assertFalse(File(missing, "track.gpx").exists())

        // 一時停止を挟んだ v2 のセッションは 2 区間になる
        val doc = parseXml(File(v2, "track.gpx").readText())
        assertEquals(2, doc.all(gpxNs, "trkseg").size)
        // セッション名がファイル内の名前になる
        assertEquals("session_20260915_090000", doc.all(gpxNs, "name").first().textContent)

        val kmz = TrackExport.exportSessions(listOf(v1), TrackFormat.KMZ)
        assertEquals(1, kmz.succeeded)
        assertTrue(File(v1, "track.kmz").exists())
    }

    @Test
    fun exportSessions_usesGeoidFromMetaJson_perSegment() {
        val dir = File(tmp.root, "session_20260915_092000").apply { mkdirs() }
        val header = "utc_iso8601,epoch_ms,elapsed_realtime_ns,provider,latitude,longitude," +
            "altitude_ellipsoid_m,horizontal_acc_m,vertical_acc_m,speed_mps,speed_acc_mps," +
            "bearing_deg,bearing_acc_deg,gdop,pdop,hdop,vdop,tdop,is_mock,gap_before,interval_sec,pressure_hpa"
        File(dir, "track.csv").writeText(
            (listOf(header) + track.map { p ->
                "t,${p.epochMs},0,gps,${p.latitude},${p.longitude},${p.altitude}," +
                    "3,5,0,0,0,0,,,,,,false,${p.gapBefore},1,"
            }).joinToString("\n")
        )
        // 最初の区間だけ 30 m、残りはセッション全体の 40 m を使う
        SessionMeta(
            geoidOffsetM = 40.0,
            segments = listOf(
                Segment(
                    start = SessionMeta.timeOf(track[0].epochMs),
                    end = SessionMeta.timeOf(track[2].epochMs),
                    points = 3,
                    geoidOffsetM = 30.0
                ),
                Segment(
                    start = SessionMeta.timeOf(track[3].epochMs),
                    end = SessionMeta.timeOf(track[5].epochMs),
                    points = 3
                )
            )
        ).writeTo(dir)

        // アプリ全体の設定（36 m）ではなく meta.json の値を使う
        TrackExport.exportSessions(listOf(dir), TrackFormat.GPX, fallbackGeoidM = 36.0)
        val doc = parseXml(File(dir, "track.gpx").readText())
        assertEquals(
            listOf("10.00", "11.00", "12.00", "3.00", "4.00", "5.00"),
            doc.all(gpxNs, "ele").map { it.textContent }
        )
    }

    // ===== ZIP エクスポート前の自動生成 =====

    private val v1Header = "utc_iso8601,epoch_ms,elapsed_realtime_ns,provider,latitude,longitude," +
        "altitude_ellipsoid_m,horizontal_acc_m,vertical_acc_m,speed_mps,speed_acc_mps," +
        "bearing_deg,bearing_acc_deg,gdop,pdop,hdop,vdop,tdop,is_mock"

    private fun sessionWithTrack(name: String): File =
        File(tmp.root, name).apply {
            mkdirs()
            File(this, "track.csv").writeText(
                "$v1Header\nt,1000,0,gps,35.1,139.1,50.0,3,5,0,0,0,0,,,,,,false"
            )
        }

    @Test
    fun ensureExports_createsBothWhenMissing_withGeoidHeight() {
        val dir = sessionWithTrack("session_20260915_090000")
        val summary = TrackExport.ensureExports(listOf(dir), fallbackGeoidM = 36.0)
        assertEquals(EnsureSummary(created = 2, failed = 0), summary)
        val gpx = parseXml(File(dir, "track.gpx").readText())
        assertEquals("14.00", gpx.all(gpxNs, "ele").single().textContent)
        ZipFile(File(dir, "track.kmz")).use { zip ->
            val kml = zip.getInputStream(zip.getEntry("doc.kml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(kml.contains("<gx:coord>139.10000000 35.10000000 14.00</gx:coord>"))
        }
        assertFalse(File(dir, "track.gpx.tmp").exists())
        assertFalse(File(dir, "track.kmz.tmp").exists())
    }

    @Test
    fun ensureExports_keepsExistingFile_andCreatesOnlyTheMissingOne() {
        val dir = sessionWithTrack("session_20260915_090000")
        File(dir, "track.gpx").writeText("manual")
        val summary = TrackExport.ensureExports(listOf(dir), fallbackGeoidM = 36.0)
        assertEquals(EnsureSummary(created = 1, failed = 0), summary)
        // 既にある GPX は上書きしない
        assertEquals("manual", File(dir, "track.gpx").readText())
        assertTrue(File(dir, "track.kmz").exists())

        // 両方あれば何もしない（track.csv も読まない）
        File(dir, "track.csv").delete()
        assertEquals(EnsureSummary(created = 0, failed = 0), TrackExport.ensureExports(listOf(dir)))
    }

    @Test
    fun ensureExports_continuesPastFailedSessions() {
        val noCsv = File(tmp.root, "session_20260915_080000").apply { mkdirs() }
        val empty = File(tmp.root, "session_20260915_083000").apply { mkdirs() }
        File(empty, "track.csv").writeText(v1Header)
        val good = sessionWithTrack("session_20260915_090000")

        val summary = TrackExport.ensureExports(listOf(noCsv, empty, good))
        assertEquals(EnsureSummary(created = 2, failed = 2), summary)
        assertTrue(File(good, "track.gpx").exists())
        assertTrue(File(good, "track.kmz").exists())
        assertFalse(File(noCsv, "track.gpx").exists())
        assertFalse(File(empty, "track.kmz").exists())
    }
}

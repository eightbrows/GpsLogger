package io.github.eightbrows.gpslogger.session

import io.github.eightbrows.gpslogger.calc.StepSensorSpec
import io.github.eightbrows.gpslogger.calc.Steps
import io.github.eightbrows.gpslogger.calc.StepsStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** meta.json の steps、steps.csv の有無とセッションの読み込み、インポートのパターン */
class StepsSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val detectorSpec = StepSensorSpec(
        name = "Step Detector", vendor = "Vendor A", version = 3, wakeUp = false,
        reportingMode = 2, minDelayUs = 0, maxDelayUs = 0,
        fifoReservedEventCount = 100, fifoMaxEventCount = 3000
    )

    private val recorded = StepsMeta(
        enabled = true,
        permissionGranted = true,
        status = StepsStatus.RECORDED.jsonName,
        requestedMaxReportLatencyUs = 0,
        detector = StepSensorMeta.of(detectorSpec, used = true, events = 812, timebaseAnomalies = 1),
        counter = StepSensorMeta.of(null, used = false, events = 0, timebaseAnomalies = 0)
    )

    // ---- meta.json の steps ----

    @Test
    fun steps_roundTrip() {
        val meta = SessionMeta(steps = recorded)
        assertEquals(meta, SessionMeta.parse(meta.toJson().toString(2)))
        assertTrue(recorded.recorded)
    }

    @Test
    fun steps_jsonLayout() {
        val json = SessionMeta(steps = recorded).toJson().getJSONObject("steps")
        assertEquals("recorded", json.getString("status"))
        assertEquals(0, json.getInt("requestedMaxReportLatencyUs"))
        val d = json.getJSONObject("detector")
        assertTrue(d.getBoolean("present"))
        assertTrue(d.getBoolean("used"))
        assertEquals("Step Detector", d.getString("name"))
        assertFalse(d.getBoolean("wakeUp"))
        assertEquals(3000, d.getInt("fifoMaxEventCount"))
        assertEquals(812, d.getLong("events"))
        assertEquals(1, d.getLong("timebaseAnomalies"))
        val c = json.getJSONObject("counter")
        assertFalse(c.getBoolean("present"))
        assertTrue(c.isNull("name"))
    }

    @Test
    fun oldMetaWithoutSteps_readsAsNotRecorded() {
        val meta = SessionMeta.parse("""{"pointCount": 10, "compassEnabled": true}""")!!
        assertNull(meta.steps)
        // 書き戻しても null のまま
        assertNull(SessionMeta.parse(meta.toJson().toString())!!.steps)
    }

    @Test
    fun brokenSteps_fallBackPerField() {
        assertNull(SessionMeta.parse("""{"steps": "yes"}""")!!.steps)
        val steps = SessionMeta.parse(
            """{"steps": {"enabled": "on", "status": 3, "detector": [1], "counter": {"present": true, "events": "x"}}}"""
        )!!.steps!!
        assertFalse(steps.enabled)
        assertEquals(StepsStatus.DISABLED.jsonName, steps.status)
        assertEquals(StepSensorMeta(), steps.detector)
        assertTrue(steps.counter.present)
        assertEquals(0L, steps.counter.events)
    }

    @Test
    fun noPermissionAndNoSensor_areWrittenWithReason() {
        val noPermission = StepsMeta(enabled = true, permissionGranted = false, status = StepsStatus.NO_PERMISSION.jsonName)
        val json = noPermission.toJson()
        assertEquals("noPermission", json.getString("status"))
        assertFalse(json.getBoolean("permissionGranted"))
        assertFalse(noPermission.recorded)

        val noSensor = StepsMeta(enabled = true, permissionGranted = true, status = StepsStatus.NO_SENSOR.jsonName)
        assertEquals("noSensor", noSensor.toJson().getString("status"))
        assertFalse(noSensor.toJson().getJSONObject("detector").getBoolean("present"))
    }

    @Test
    fun flatten_showsStepsRows() {
        val values = MetaPaths.flatten(SessionMeta(steps = recorded)).associate { it.path to it.value }
        assertEquals("recorded", values["steps.status"])
        assertEquals("Step Detector", values["steps.detector.name"])
        assertEquals("812", values["steps.detector.events"])
        assertEquals("false", values["steps.counter.present"])
        assertNull(values["steps.counter.name"])
        // 歩数の項目は編集できない
        assertTrue(MetaPaths.flatten(SessionMeta(steps = recorded))
            .filter { it.path.startsWith("steps") }.none { it.editable })
        // 古い meta.json では 1 行（値なし）
        val old = MetaPaths.flatten(SessionMeta()).filter { it.path.startsWith("steps") }
        assertEquals(listOf(MetaEntry("steps", null, false)), old)
    }

    // ---- steps.csv の有無とセッションの読み込み ----

    private val trackHeader =
        "utc_iso8601,epoch_ms,elapsed_realtime_ns,provider,latitude,longitude," +
            "altitude_ellipsoid_m,horizontal_acc_m,vertical_acc_m,speed_mps,speed_acc_mps," +
            "bearing_deg,bearing_acc_deg,gdop,pdop,hdop,vdop,tdop,is_mock," +
            "gap_before,interval_sec,pressure_hpa"

    private fun session(name: String, withSteps: Boolean): File {
        val dir = File(tmp.root, name).apply { mkdirs() }
        val rows = (0 until 3).joinToString("\n") { i ->
            "2026-11-22T00:00:0$i.000Z,${1_795_000_000_000L + i * 1000},${(5 + i) * 1_000_000_000L},gps," +
                "34.6${i},136.4${i},50.0,3.0,5.0,1.2,0.1,90.0,2.0,,,,,,false,false,1,"
        }
        File(dir, "track.csv").writeText("$trackHeader\n$rows\n")
        SessionMeta(pointCount = 3, steps = if (withSteps) recorded else null).writeTo(dir)
        if (withSteps) {
            File(dir, Steps.FILE_NAME).writeText(
                Steps.HEADER + "\n" +
                    "detector,5100000000,5150000000,1795000000050,\n" +
                    "counter,5100000000,5150000000,1795000000050,1201\n"
            )
        }
        return dir
    }

    @Test
    fun sessionsReadTheSame_withOrWithoutStepsCsv() {
        val with = session("session_20261122_100000", withSteps = true)
        val without = session("session_20261122_090000", withSteps = false)

        assertEquals(listOf(with, without), SessionReader.listSessions(tmp.root))
        for (dir in listOf(with, without)) {
            val track = SessionReader.readTrackRecords(dir)
            assertNotNull(track)
            assertEquals(3, track!!.size)
            assertTrue(SessionReader.trackDistanceM(dir) > 0.0)
            assertNotNull(SessionReader.readMeta(dir))
        }
        assertNotNull(SessionReader.readMeta(with)!!.steps)
        assertNull(SessionReader.readMeta(without)!!.steps)
    }

    // ---- インポートで受け付けるファイル名 ----

    @Test
    fun importPattern_acceptsStepsCsvAndExistingFiles() {
        for (file in listOf("track.csv", "sats.csv", "steps.csv", "meta.json", "track.gpx", "track.kmz")) {
            val m = SessionZip.ENTRY_PATTERN.matchEntire("session_20261122_101500/$file")
            assertNotNull(file, m)
            assertEquals("session_20261122_101500", m!!.groupValues[1])
        }
    }

    @Test
    fun importPattern_rejectsOtherNamesAndPaths() {
        listOf(
            "session_20261122_101500/steps.csv.bak",
            "session_20261122_101500/Steps.csv",
            "session_20261122_101500/steps_csv",
            "session_20261122_101500/sub/steps.csv",
            "session_20261122_101500/../steps.csv",
            "../session_20261122_101500/steps.csv",
            "/session_20261122_101500/steps.csv",
            "session_2026112_101500/steps.csv",
            "steps.csv",
            "session_20261122_101500/"
        ).forEach { assertNull(it, SessionZip.ENTRY_PATTERN.matchEntire(it)) }
    }
}

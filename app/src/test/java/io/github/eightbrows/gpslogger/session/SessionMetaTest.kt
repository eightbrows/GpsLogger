package io.github.eightbrows.gpslogger.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class SessionMetaTest {

    private val jst = ZoneOffset.ofHours(9)

    private fun t(text: String): OffsetDateTime = OffsetDateTime.parse(text)

    private val sample = SessionMeta(
        comment = "朝の散歩 \"テスト\"\n2行目",
        tags = listOf("walk", "晴れ"),
        basePressureHpa = 1008.6,
        start = t("2026-09-15T09:20:00+09:00"),
        end = t("2026-09-15T15:30:00+09:00"),
        pointCount = 15600,
        distanceM = 8420.5,
        segments = listOf(
            Segment(
                start = t("2026-09-15T09:20:00+09:00"),
                end = t("2026-09-15T11:20:00+09:00"),
                points = 7200,
                basePressureHpa = null
            ),
            Segment(
                start = t("2026-09-15T12:00:00+09:00"),
                end = t("2026-09-15T15:30:00+09:00"),
                points = 8400,
                basePressureHpa = 1011.2
            )
        ),
        useWakeLock = true,
        deviceModel = "Pixel 8",
        osVersion = "16",
        appVersion = "20260913-R01"
    )

    @Test
    fun roundTrip_restoresAllFields() {
        val text = sample.toJson().toString(2)
        val restored = SessionMeta.fromJson(JSONObject(text))
        assertEquals(sample, restored)
    }

    @Test
    fun roundTrip_integralDoubleStaysDouble() {
        // 8420.0 のような値は JSON 上で整数になるが、読み戻しても Double として一致すること
        val meta = sample.copy(distanceM = 8420.0, basePressureHpa = 1013.0)
        assertEquals(meta, SessionMeta.parse(meta.toJson().toString()))
    }

    @Test
    fun toJson_writesIsoOffsetTimeAndNullSegmentPressure() {
        val json = sample.toJson()
        assertEquals("2026-09-15T09:20:00+09:00", json.getString("start"))
        val seg0 = json.getJSONArray("segments").getJSONObject(0)
        assertTrue(seg0.isNull("basePressureHpa"))
        assertEquals(1, json.getInt("schemaVersion"))
    }

    @Test
    fun toJson_nonFiniteNumbersBecomeNullAndReadBackAsDefault() {
        val meta = sample.copy(distanceM = Double.NaN)
        val json = meta.toJson()
        assertTrue(json.isNull("distanceM"))
        assertEquals(0.0, SessionMeta.fromJson(json).distanceM, 0.0)
    }

    @Test
    fun fromJson_emptyObject_givesDefaults() {
        assertEquals(SessionMeta(), SessionMeta.fromJson(JSONObject()))
    }

    @Test
    fun fromJson_brokenFields_fallBackPerField() {
        val text = """
            {
              "schemaVersion": "one",
              "comment": 42,
              "tags": ["ok", 3, null, "also-ok"],
              "basePressureHpa": "1013.25",
              "start": "2026-09-15 09:20",
              "end": "2026-09-15T15:30:00+09:00",
              "pointCount": null,
              "distanceM": true,
              "segments": [1, "x", {"start": "bad", "points": "many", "basePressureHpa": "hi"}],
              "useWakeLock": "yes",
              "deviceModel": null,
              "osVersion": 16,
              "appVersion": "R01"
            }
        """.trimIndent()

        val meta = SessionMeta.parse(text)!!
        val d = SessionMeta()
        assertEquals(d.schemaVersion, meta.schemaVersion)
        assertEquals(d.comment, meta.comment)
        assertEquals(listOf("ok", "also-ok"), meta.tags)
        assertEquals(d.basePressureHpa, meta.basePressureHpa, 0.0)
        assertNull(meta.start)
        // 壊れていない項目はそのまま読める
        assertEquals(t("2026-09-15T15:30:00+09:00"), meta.end)
        assertEquals(d.pointCount, meta.pointCount)
        assertEquals(d.distanceM, meta.distanceM, 0.0)
        assertEquals(listOf(Segment(start = null, end = null, points = 0, basePressureHpa = null)), meta.segments)
        assertEquals(d.useWakeLock, meta.useWakeLock)
        assertEquals(d.deviceModel, meta.deviceModel)
        assertEquals(d.osVersion, meta.osVersion)
        assertEquals("R01", meta.appVersion)
    }

    @Test
    fun fromJson_wrongContainerTypes_giveDefaults() {
        val meta = SessionMeta.parse("""{"tags": "walk", "segments": {"points": 1}}""")!!
        assertEquals(emptyList<String>(), meta.tags)
        assertEquals(emptyList<Segment>(), meta.segments)
    }

    @Test
    fun parse_invalidJson_returnsNull() {
        assertNull(SessionMeta.parse(""))
        assertNull(SessionMeta.parse("{ broken"))
        assertNull(SessionMeta.parse("[1, 2, 3]"))
        assertNull(SessionMeta.parse("null"))
    }

    @Test
    fun timeOf_usesZoneOffsetAndTruncatesToSeconds() {
        // 2026-09-15T00:20:00.789Z
        val ms = 1_789_431_600_789L
        val time = SessionMeta.timeOf(ms, ZoneId.of("Asia/Tokyo"))
        assertEquals(jst, time.offset)
        assertEquals(0, time.nano)
        assertEquals("2026-09-15T09:20:00+09:00", SessionMeta(start = time).toJson().getString("start"))
    }

    // ===== 平坦化（MetaPaths.flatten） =====

    @Test
    fun flatten_pathsFollowMetaJsonOrder() {
        val expected = listOf(
            "schemaVersion", "comment", "tags", "basePressureHpa", "start", "end",
            "pointCount", "distanceM",
            "segments[0].start", "segments[0].end", "segments[0].points", "segments[0].basePressureHpa",
            "segments[1].start", "segments[1].end", "segments[1].points", "segments[1].basePressureHpa",
            "useWakeLock", "deviceModel", "osVersion", "appVersion"
        )
        assertEquals(expected, MetaPaths.flatten(sample).map { it.path })
    }

    @Test
    fun flatten_formatsValues() {
        val values = MetaPaths.flatten(sample).associate { it.path to it.value }
        assertEquals("walk, 晴れ", values["tags"])
        assertEquals("1008.6", values["basePressureHpa"])
        assertEquals("2026-09-15T09:20:00+09:00", values["start"])
        assertEquals("15600", values["pointCount"])
        assertEquals("8420.5", values["distanceM"])
        assertNull(values["segments[0].basePressureHpa"])
        assertEquals("1011.2", values["segments[1].basePressureHpa"])
        assertEquals("7200", values["segments[0].points"])
        assertEquals("true", values["useWakeLock"])
    }

    @Test
    fun flatten_nullTimesAndIntegralNumbers() {
        val values = MetaPaths.flatten(SessionMeta(basePressureHpa = 1013.0, distanceM = 12_000_000.0))
            .associate { it.path to it.value }
        assertNull(values["start"])
        assertNull(values["end"])
        // 末尾の .0 は付けず、指数表記にもしない
        assertEquals("1013", values["basePressureHpa"])
        assertEquals("12000000", values["distanceM"])
    }

    @Test
    fun flatten_marksOnlyEditableEntries() {
        val editable = MetaPaths.flatten(sample).filter { it.editable }.map { it.path }
        assertEquals(
            listOf(
                "comment", "tags", "basePressureHpa",
                "segments[0].basePressureHpa", "segments[1].basePressureHpa"
            ),
            editable
        )
    }

    // ===== 編集可否（ワイルドカード） =====

    @Test
    fun isEditable_segmentIndexIsWildcard() {
        for (i in listOf(0, 1, 9, 10, 123, 99_999)) {
            assertTrue("index $i", MetaPaths.isEditable("segments[$i].basePressureHpa"))
            assertFalse("index $i", MetaPaths.isEditable("segments[$i].start"))
            assertFalse("index $i", MetaPaths.isEditable("segments[$i].points"))
        }
        assertEquals("segments[*].basePressureHpa", MetaPaths.pattern("segments[42].basePressureHpa"))
    }

    @Test
    fun isEditable_rejectsMalformedPaths() {
        listOf(
            "segments[].basePressureHpa",
            "segments[a].basePressureHpa",
            "segments[-1].basePressureHpa",
            "segments.basePressureHpa",
            "xsegments[0].basePressureHpa",
            "segments[0].basePressureHpaX",
            "start", "end", "pointCount", "distanceM", "schemaVersion",
            "useWakeLock", "deviceModel", "osVersion", "appVersion", ""
        ).forEach { assertFalse(it, MetaPaths.isEditable(it)) }
    }

    @Test
    fun editableCount_scalesWithSegmentCount() {
        for (n in listOf(0, 1, 2, 50)) {
            val meta = SessionMeta(segments = List(n) { Segment(start = null, end = null) })
            val editable = MetaPaths.flatten(meta).count { it.editable }
            assertEquals("segments=$n", 3 + n, editable)
        }
    }

    // ===== 書き戻し（MetaPaths.apply） =====

    private fun ok(result: MetaEditResult): SessionMeta {
        assertTrue("expected success: $result", result is MetaEditResult.Success)
        return (result as MetaEditResult.Success).meta
    }

    private fun assertRejected(meta: SessionMeta, path: String, input: String) {
        val result = MetaPaths.apply(meta, path, input)
        assertTrue("\"$input\" at $path should be rejected", result is MetaEditResult.Failure)
    }

    @Test
    fun apply_comment_keepsTextAsIs() {
        val edited = ok(MetaPaths.apply(sample, "comment", "  京都旅行 1日目\n晴れ  "))
        assertEquals("  京都旅行 1日目\n晴れ  ", edited.comment)
        assertEquals(sample.copy(comment = edited.comment), edited)
    }

    @Test
    fun apply_tags_splitsTrimsAndDropsEmpty() {
        assertEquals(
            listOf("旅行", "お気に入り"),
            ok(MetaPaths.apply(sample, "tags", "  旅行 , , お気に入り ,")).tags
        )
        assertEquals(emptyList<String>(), ok(MetaPaths.apply(sample, "tags", " , ")).tags)
        assertEquals(emptyList<String>(), ok(MetaPaths.apply(sample, "tags", "")).tags)
    }

    @Test
    fun apply_basePressure_acceptsDecimal() {
        assertEquals(1008.5, ok(MetaPaths.apply(sample, "basePressureHpa", " 1008.5 ")).basePressureHpa, 0.0)
        assertEquals(1013.0, ok(MetaPaths.apply(sample, "basePressureHpa", "1013")).basePressureHpa, 0.0)
    }

    @Test
    fun apply_basePressure_rejectsEmptyAndInvalid() {
        listOf("", "   ", "abc", "NaN", "Infinity", "-1013", "0", "0.0", "1e3", "1013.25hPa", "1013f", "0x10", "1,013")
            .forEach { assertRejected(sample, "basePressureHpa", it) }
    }

    @Test
    fun apply_segmentPressure_emptyMeansUnset() {
        val edited = ok(MetaPaths.apply(sample, "segments[1].basePressureHpa", "  "))
        assertEquals(null, edited.segments[1].basePressureHpa)
        // 他の区間・他の項目は変わらない
        assertEquals(sample.segments[0], edited.segments[0])
        assertEquals(sample.copy(segments = edited.segments), edited)
    }

    @Test
    fun apply_segmentPressure_setsValue() {
        val edited = ok(MetaPaths.apply(sample, "segments[0].basePressureHpa", "1009.75"))
        assertEquals(1009.75, edited.segments[0].basePressureHpa!!, 0.0)
        assertEquals(sample.segments[1], edited.segments[1])
        assertEquals(sample.segments[0].copy(basePressureHpa = 1009.75), edited.segments[0])
    }

    @Test
    fun apply_segmentPressure_rejectsInvalidAndOutOfRange() {
        listOf("abc", "NaN", "-5", "0", "1e3").forEach {
            assertRejected(sample, "segments[0].basePressureHpa", it)
        }
        assertRejected(sample, "segments[2].basePressureHpa", "1000")
        assertRejected(sample, "segments[99999].basePressureHpa", "")
    }

    @Test
    fun apply_readOnlyPaths_areRejected() {
        MetaPaths.flatten(sample).filterNot { it.editable }.forEach {
            assertRejected(sample, it.path, it.value ?: "")
        }
    }

    @Test
    fun editText_emptyForUnsetSegment() {
        assertEquals("", MetaPaths.editText(sample, "segments[0].basePressureHpa"))
        assertEquals("1011.2", MetaPaths.editText(sample, "segments[1].basePressureHpa"))
        assertEquals("walk, 晴れ", MetaPaths.editText(sample, "tags"))
        assertEquals("1008.6", MetaPaths.editText(sample, "basePressureHpa"))
    }

    @Test
    fun applyingInitialText_leavesMetaUnchanged() {
        // ダイアログを開いてそのまま保存しても、構造も値も元のまま
        MetaPaths.flatten(sample).filter { it.editable }.forEach { entry ->
            val text = MetaPaths.editText(sample, entry.path)
            assertEquals(entry.path, sample, ok(MetaPaths.apply(sample, entry.path, text)))
        }
    }

    @Test
    fun editedMeta_survivesJsonRoundTrip() {
        var meta = sample
        meta = ok(MetaPaths.apply(meta, "comment", "京都旅行 1日目"))
        meta = ok(MetaPaths.apply(meta, "tags", "旅行, お気に入り"))
        meta = ok(MetaPaths.apply(meta, "basePressureHpa", "1010.4"))
        meta = ok(MetaPaths.apply(meta, "segments[0].basePressureHpa", "1012"))
        meta = ok(MetaPaths.apply(meta, "segments[1].basePressureHpa", ""))

        val restored = SessionMeta.parse(meta.toJson().toString(2))!!
        assertEquals(meta, restored)
        assertEquals(2, restored.segments.size)
        assertEquals(1012.0, restored.segments[0].basePressureHpa!!, 0.0)
        assertEquals(null, restored.segments[1].basePressureHpa)
        // 平坦化の結果も一致（画面に同じ内容が出る）
        assertEquals(MetaPaths.flatten(meta), MetaPaths.flatten(restored))
    }

    @Test
    fun writeTo_createsFileWhenMissing() {
        val dir = java.nio.file.Files.createTempDirectory("meta").toFile()
        try {
            val meta = ok(MetaPaths.apply(SessionMeta(), "comment", "新規"))
            meta.writeTo(dir)
            val file = java.io.File(dir, SessionMeta.FILE_NAME)
            assertTrue(file.exists())
            assertFalse(java.io.File(dir, SessionMeta.FILE_NAME + ".tmp").exists())
            assertEquals(meta, SessionMeta.parse(file.readText()))
        } finally {
            dir.deleteRecursively()
        }
    }
}

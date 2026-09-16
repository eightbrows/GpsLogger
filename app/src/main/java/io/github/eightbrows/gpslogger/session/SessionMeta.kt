package io.github.eightbrows.gpslogger.session

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 記録区間。一時停止で閉じ、再開で次の区間が始まる。
 * 一時停止を挟まなければセッション全体で1区間。
 */
data class Segment(
    val start: OffsetDateTime?,
    val end: OffsetDateTime?,
    /** この区間で記録した測位点の数 */
    val points: Int = 0,
    /** 区間ごとの基準気圧（hPa）。null ならセッションの値を使う */
    val basePressureHpa: Double? = null
)

/**
 * セッションフォルダ直下の meta.json の内容。
 * 時刻はすべてオフセット付き ISO8601 で読み書きする。
 */
data class SessionMeta(
    val schemaVersion: Int = SCHEMA_VERSION,
    val comment: String = "",
    val tags: List<String> = emptyList(),
    /** 気圧高度の基準気圧（hPa） */
    val basePressureHpa: Double = DEFAULT_BASE_PRESSURE_HPA,
    val start: OffsetDateTime? = null,
    val end: OffsetDateTime? = null,
    val pointCount: Int = 0,
    val distanceM: Double = 0.0,
    val segments: List<Segment> = emptyList(),
    val useWakeLock: Boolean = false,
    val deviceModel: String = "",
    val osVersion: String = "",
    val appVersion: String = ""
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("comment", comment)
        put("tags", JSONArray(tags))
        put("basePressureHpa", number(basePressureHpa))
        put("start", time(start))
        put("end", time(end))
        put("pointCount", pointCount)
        put("distanceM", number(distanceM))
        put("segments", JSONArray().apply {
            segments.forEach { s ->
                put(JSONObject().apply {
                    put("start", time(s.start))
                    put("end", time(s.end))
                    put("points", s.points)
                    put("basePressureHpa", s.basePressureHpa?.let { number(it) } ?: JSONObject.NULL)
                })
            }
        })
        put("useWakeLock", useWakeLock)
        put("deviceModel", deviceModel)
        put("osVersion", osVersion)
        put("appVersion", appVersion)
    }

    /**
     * セッションフォルダへ書き出す。
     * 途中で落ちても壊れたファイルが残らないよう、一時ファイルに書いてから置き換える。
     */
    fun writeTo(sessionDir: File) {
        val target = File(sessionDir, FILE_NAME)
        val tmp = File(sessionDir, "$FILE_NAME.tmp")
        tmp.writeText(toJson().toString(2))
        if (!tmp.renameTo(target)) {
            // 置き換えに失敗する環境向け。上書きしてから一時ファイルを消す
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "meta.json"
        const val SCHEMA_VERSION = 1
        const val DEFAULT_BASE_PRESSURE_HPA = 1013.25

        private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        /**
         * JSON から組み立てる。項目ごとに型や値が不正なら既定値に置き換え、
         * 一部が壊れていても全体は読めるようにする。
         */
        fun fromJson(json: JSONObject): SessionMeta {
            val d = SessionMeta()
            return SessionMeta(
                schemaVersion = json.intOr("schemaVersion", d.schemaVersion),
                comment = json.stringOr("comment", d.comment),
                tags = json.optJSONArray("tags")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.opt(it) as? String }
                } ?: d.tags,
                basePressureHpa = json.doubleOrNull("basePressureHpa") ?: d.basePressureHpa,
                start = json.timeOrNull("start"),
                end = json.timeOrNull("end"),
                pointCount = json.intOr("pointCount", d.pointCount),
                distanceM = json.doubleOrNull("distanceM") ?: d.distanceM,
                segments = json.optJSONArray("segments")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        (arr.opt(i) as? JSONObject)?.let { s ->
                            Segment(
                                start = s.timeOrNull("start"),
                                end = s.timeOrNull("end"),
                                points = s.intOr("points", 0),
                                basePressureHpa = s.doubleOrNull("basePressureHpa")
                            )
                        }
                    }
                } ?: d.segments,
                useWakeLock = json.opt("useWakeLock") as? Boolean ?: d.useWakeLock,
                deviceModel = json.stringOr("deviceModel", d.deviceModel),
                osVersion = json.stringOr("osVersion", d.osVersion),
                appVersion = json.stringOr("appVersion", d.appVersion)
            )
        }

        /** 文字列から読む。JSON として不正、またはオブジェクトでなければ null */
        fun parse(text: String): SessionMeta? =
            try {
                fromJson(JSONObject(text))
            } catch (e: JSONException) {
                null
            }

        /** エポックミリ秒を端末のタイムゾーンのオフセット付き時刻へ（秒未満は切り捨て） */
        fun timeOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): OffsetDateTime =
            Instant.ofEpochMilli(epochMs).atZone(zone).toOffsetDateTime()
                .truncatedTo(ChronoUnit.SECONDS)

        private fun time(t: OffsetDateTime?): Any = t?.format(FORMATTER) ?: JSONObject.NULL

        /** JSON は NaN / 無限大を表せないので null として書く */
        private fun number(v: Double): Any = if (v.isFinite()) v else JSONObject.NULL

        // --- 読み込み用。null・型違い・範囲外はすべて既定値扱い ---

        private fun JSONObject.stringOr(key: String, def: String): String =
            opt(key) as? String ?: def

        private fun JSONObject.intOr(key: String, def: Int): Int =
            (opt(key) as? Number)?.toInt() ?: def

        private fun JSONObject.doubleOrNull(key: String): Double? =
            (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }

        private fun JSONObject.timeOrNull(key: String): OffsetDateTime? =
            (opt(key) as? String)?.let {
                try {
                    OffsetDateTime.parse(it, FORMATTER)
                } catch (e: Exception) {
                    null
                }
            }
    }
}

package io.github.eightbrows.gpslogger.session

import java.math.BigDecimal
import kotlin.math.abs
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** 平坦化した meta.json の1行 */
data class MetaEntry(
    /** "comment" や "segments[0].basePressureHpa" のようなパス */
    val path: String,
    /** 表示用の値。null は未設定 */
    val value: String?,
    val editable: Boolean
)

/** 編集内容を SessionMeta に書き戻した結果 */
sealed interface MetaEditResult {
    data class Success(val meta: SessionMeta) : MetaEditResult
    /** 失敗の理由。文言は画面側で表示言語に合わせて決める */
    data class Failure(val reason: Reason, val segmentIndex: Int? = null) : MetaEditResult

    enum class Reason {
        NOT_EDITABLE, PRESSURE_REQUIRED, INVALID_PRESSURE, INVALID_GEOID, INVALID_LEAP_SECONDS,
        SEGMENT_NOT_FOUND
    }
}

/**
 * SessionMeta を「パス → 値」の平坦なリストとして扱う。
 * 表示・編集可否の判定・値の書き戻しをここに集め、画面側はパスだけを扱う。
 */
object MetaPaths {

    private val INDEX = Regex("""\[\d+]""")
    private val SEGMENT_FIELD = Regex("""^segments\[(\d+)]\.(basePressureHpa|geoidOffsetM)$""")

    /** 気圧の入力として受け付ける形（符号・指数・16進などは受け付けない） */
    private val DECIMAL = Regex("""^\d+(\.\d+)?$""")

    /** ジオイド高は負の値もある（ジオイドが楕円体より下にある地域） */
    private val SIGNED_DECIMAL = Regex("""^-?\d+(\.\d+)?$""")

    /** うるう秒は整数 */
    private val INTEGER = Regex("""^\d+$""")

    /** 編集できるパス。区間の番号は [*] で表し、区間の数に依存しない */
    private val EDITABLE = setOf(
        "comment",
        "tags",
        "basePressureHpa",
        "geoidOffsetM",
        "leapSeconds",
        "segments[*].basePressureHpa",
        "segments[*].geoidOffsetM"
    )

    /** パスの番号部分をワイルドカードに置き換える。"segments[12].end" → "segments[*].end" */
    fun pattern(path: String): String = path.replace(INDEX, "[*]")

    fun isEditable(path: String): Boolean = pattern(path) in EDITABLE

    /** meta.json の構造どおりの順序で平坦化する */
    fun flatten(meta: SessionMeta): List<MetaEntry> {
        val rows = ArrayList<Pair<String, String?>>()
        rows += "schemaVersion" to meta.schemaVersion.toString()
        rows += "comment" to meta.comment
        rows += "tags" to joinTags(meta.tags)
        rows += "basePressureHpa" to number(meta.basePressureHpa)
        rows += "geoidOffsetM" to meta.geoidOffsetM?.let(::number)
        rows += "leapSeconds" to meta.leapSeconds?.toString()
        rows += "start" to time(meta.start)
        rows += "end" to time(meta.end)
        rows += "pointCount" to meta.pointCount.toString()
        rows += "distanceM" to number(meta.distanceM)
        meta.segments.forEachIndexed { i, s ->
            val p = "segments[$i]"
            rows += "$p.start" to time(s.start)
            rows += "$p.end" to time(s.end)
            rows += "$p.points" to s.points.toString()
            rows += "$p.basePressureHpa" to s.basePressureHpa?.let(::number)
            rows += "$p.geoidOffsetM" to s.geoidOffsetM?.let(::number)
        }
        rows += "useWakeLock" to meta.useWakeLock.toString()
        rows += "deviceModel" to meta.deviceModel
        rows += "osVersion" to meta.osVersion
        rows += "appVersion" to meta.appVersion
        return rows.map { (path, value) -> MetaEntry(path, value, isEditable(path)) }
    }

    /** 入力欄の初期値。未設定の値は空欄にする */
    fun editText(meta: SessionMeta, path: String): String = when (path) {
        "comment" -> meta.comment
        "tags" -> joinTags(meta.tags)
        "basePressureHpa" -> number(meta.basePressureHpa)
        "geoidOffsetM" -> meta.geoidOffsetM?.let(::number) ?: ""
        "leapSeconds" -> meta.leapSeconds?.toString() ?: ""
        else -> {
            val segment = segmentIndex(path)?.let { meta.segments.getOrNull(it) }
            val value = if (isGeoid(path)) segment?.geoidOffsetM else segment?.basePressureHpa
            value?.let(::number) ?: ""
        }
    }

    /**
     * 入力値をパスの位置へ書き戻した新しい SessionMeta を返す。
     * 不正な入力や編集できないパスは Failure（元の値は変えない）。
     */
    fun apply(meta: SessionMeta, path: String, input: String): MetaEditResult {
        if (!isEditable(path)) return MetaEditResult.Failure(MetaEditResult.Reason.NOT_EDITABLE)

        return when (path) {
            "comment" -> MetaEditResult.Success(meta.copy(comment = input))

            "tags" -> MetaEditResult.Success(meta.copy(tags = parseTags(input)))

            "basePressureHpa" -> {
                val text = input.trim()
                if (text.isEmpty()) {
                    return MetaEditResult.Failure(MetaEditResult.Reason.PRESSURE_REQUIRED)
                }
                val value = parsePressure(text)
                    ?: return MetaEditResult.Failure(MetaEditResult.Reason.INVALID_PRESSURE)
                MetaEditResult.Success(meta.copy(basePressureHpa = value))
            }

            // 空欄は「未設定」（アプリ全体の設定値を使う）
            "geoidOffsetM" -> {
                val text = input.trim()
                val value = if (text.isEmpty()) null else {
                    parseGeoid(text) ?: return MetaEditResult.Failure(MetaEditResult.Reason.INVALID_GEOID)
                }
                MetaEditResult.Success(meta.copy(geoidOffsetM = value))
            }

            // 空欄は「未設定」（アプリ全体の設定値を使う）
            "leapSeconds" -> {
                val text = input.trim()
                val value = if (text.isEmpty()) null else {
                    parseLeapSeconds(text)
                        ?: return MetaEditResult.Failure(MetaEditResult.Reason.INVALID_LEAP_SECONDS)
                }
                MetaEditResult.Success(meta.copy(leapSeconds = value))
            }

            else -> {
                val index = segmentIndex(path)
                    ?: return MetaEditResult.Failure(MetaEditResult.Reason.NOT_EDITABLE)
                if (index !in meta.segments.indices) {
                    return MetaEditResult.Failure(MetaEditResult.Reason.SEGMENT_NOT_FOUND, index)
                }
                val text = input.trim()
                val geoid = isGeoid(path)
                // 空欄は「未設定」（セッション全体の値を使う）
                val value = if (text.isEmpty()) null else {
                    val parsed = if (geoid) parseGeoid(text) else parsePressure(text)
                    parsed ?: return MetaEditResult.Failure(
                        if (geoid) MetaEditResult.Reason.INVALID_GEOID
                        else MetaEditResult.Reason.INVALID_PRESSURE
                    )
                }
                val segments = meta.segments.toMutableList()
                segments[index] =
                    if (geoid) segments[index].copy(geoidOffsetM = value)
                    else segments[index].copy(basePressureHpa = value)
                MetaEditResult.Success(meta.copy(segments = segments))
            }
        }
    }

    /** カンマで分割し、前後の空白を除いて、空の要素を捨てる */
    fun parseTags(input: String): List<String> =
        input.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun joinTags(tags: List<String>): String = tags.joinToString(", ")

    /** "segments[2].geoidOffsetM" のようなパスが指す区間。区間のパスでなければ null */
    fun segmentOf(meta: SessionMeta, path: String): Segment? =
        segmentIndex(path)?.let { meta.segments.getOrNull(it) }

    private fun segmentIndex(path: String): Int? =
        SEGMENT_FIELD.matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()

    /** ジオイド高の項目（セッション全体・区間別のどちらも）かどうか */
    private fun isGeoid(path: String): Boolean = path.endsWith("geoidOffsetM")

    private fun parsePressure(text: String): Double? =
        if (DECIMAL.matches(text)) text.toDouble().takeIf { it.isFinite() && it > 0.0 } else null

    private fun parseLeapSeconds(text: String): Int? =
        if (INTEGER.matches(text)) {
            text.toIntOrNull()
                ?.takeIf { it in SessionMeta.MIN_LEAP_SECONDS..SessionMeta.MAX_LEAP_SECONDS }
        } else null

    /** ジオイド高は 0 も負の値も受け付ける。地球上のジオイド高の範囲を超える値は受け付けない */
    private fun parseGeoid(text: String): Double? =
        if (SIGNED_DECIMAL.matches(text)) {
            text.toDouble().takeIf { it.isFinite() && abs(it) <= SessionMeta.GEOID_LIMIT_M }
        } else null

    /** 1013.25 → "1013.25"、1013.0 → "1013"。指数表記にはしない */
    private fun number(v: Double): String =
        if (v.isFinite()) BigDecimal.valueOf(v).stripTrailingZeros().toPlainString() else v.toString()

    private fun time(t: OffsetDateTime?): String? =
        t?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

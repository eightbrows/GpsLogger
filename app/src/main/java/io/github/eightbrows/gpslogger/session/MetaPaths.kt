package io.github.eightbrows.gpslogger.session

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** 平坦化した meta.json の1行 */
data class MetaEntry(
    /** "comment" や "segments[0].basePressureHpa" のようなパス */
    val path: String,
    /** 表示用の値 */
    val value: String,
    val editable: Boolean
)

/** 編集内容を SessionMeta に書き戻した結果 */
sealed interface MetaEditResult {
    data class Success(val meta: SessionMeta) : MetaEditResult
    data class Failure(val message: String) : MetaEditResult
}

/**
 * SessionMeta を「パス → 値」の平坦なリストとして扱う。
 * 表示・編集可否の判定・値の書き戻しをここに集め、画面側はパスだけを扱う。
 */
object MetaPaths {

    /** null の値の表示 */
    const val UNSET = "未設定"

    private val INDEX = Regex("""\[\d+]""")
    private val SEGMENT_BASE_PRESSURE = Regex("""^segments\[(\d+)]\.basePressureHpa$""")

    /** 気圧の入力として受け付ける形（符号・指数・16進などは受け付けない） */
    private val DECIMAL = Regex("""^\d+(\.\d+)?$""")

    /** 編集できるパス。区間の番号は [*] で表し、区間の数に依存しない */
    private val EDITABLE = setOf(
        "comment",
        "tags",
        "basePressureHpa",
        "segments[*].basePressureHpa"
    )

    private const val INVALID_PRESSURE = "気圧は正の数値で入力してください（例: 1013.25）"

    /** パスの番号部分をワイルドカードに置き換える。"segments[12].end" → "segments[*].end" */
    fun pattern(path: String): String = path.replace(INDEX, "[*]")

    fun isEditable(path: String): Boolean = pattern(path) in EDITABLE

    /** meta.json の構造どおりの順序で平坦化する */
    fun flatten(meta: SessionMeta): List<MetaEntry> {
        val rows = ArrayList<Pair<String, String>>()
        rows += "schemaVersion" to meta.schemaVersion.toString()
        rows += "comment" to meta.comment
        rows += "tags" to joinTags(meta.tags)
        rows += "basePressureHpa" to number(meta.basePressureHpa)
        rows += "start" to time(meta.start)
        rows += "end" to time(meta.end)
        rows += "pointCount" to meta.pointCount.toString()
        rows += "distanceM" to number(meta.distanceM)
        meta.segments.forEachIndexed { i, s ->
            val p = "segments[$i]"
            rows += "$p.start" to time(s.start)
            rows += "$p.end" to time(s.end)
            rows += "$p.points" to s.points.toString()
            rows += "$p.basePressureHpa" to (s.basePressureHpa?.let(::number) ?: UNSET)
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
        else -> segmentIndex(path)
            ?.let { meta.segments.getOrNull(it)?.basePressureHpa }
            ?.let(::number)
            ?: ""
    }

    /**
     * 入力値をパスの位置へ書き戻した新しい SessionMeta を返す。
     * 不正な入力や編集できないパスは Failure（元の値は変えない）。
     */
    fun apply(meta: SessionMeta, path: String, input: String): MetaEditResult {
        if (!isEditable(path)) return MetaEditResult.Failure("この項目は編集できません")

        return when (path) {
            "comment" -> MetaEditResult.Success(meta.copy(comment = input))

            "tags" -> MetaEditResult.Success(meta.copy(tags = parseTags(input)))

            "basePressureHpa" -> {
                val text = input.trim()
                if (text.isEmpty()) {
                    return MetaEditResult.Failure("基準気圧は空欄にできません")
                }
                val value = parsePressure(text)
                    ?: return MetaEditResult.Failure(INVALID_PRESSURE)
                MetaEditResult.Success(meta.copy(basePressureHpa = value))
            }

            else -> {
                val index = segmentIndex(path)
                    ?: return MetaEditResult.Failure("この項目は編集できません")
                if (index !in meta.segments.indices) {
                    return MetaEditResult.Failure("区間 $index が見つかりません")
                }
                val text = input.trim()
                // 空欄は「未設定」（セッション全体の基準気圧を使う）
                val value = if (text.isEmpty()) {
                    null
                } else {
                    parsePressure(text) ?: return MetaEditResult.Failure(INVALID_PRESSURE)
                }
                val segments = meta.segments.toMutableList()
                segments[index] = segments[index].copy(basePressureHpa = value)
                MetaEditResult.Success(meta.copy(segments = segments))
            }
        }
    }

    /** カンマで分割し、前後の空白を除いて、空の要素を捨てる */
    fun parseTags(input: String): List<String> =
        input.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun joinTags(tags: List<String>): String = tags.joinToString(", ")

    private fun segmentIndex(path: String): Int? =
        SEGMENT_BASE_PRESSURE.matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()

    private fun parsePressure(text: String): Double? =
        if (DECIMAL.matches(text)) text.toDouble().takeIf { it.isFinite() && it > 0.0 } else null

    /** 1013.25 → "1013.25"、1013.0 → "1013"。指数表記にはしない */
    private fun number(v: Double): String =
        if (v.isFinite()) BigDecimal.valueOf(v).stripTrailingZeros().toPlainString() else v.toString()

    private fun time(t: OffsetDateTime?): String =
        t?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) ?: UNSET
}

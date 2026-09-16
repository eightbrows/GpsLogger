package io.github.eightbrows.gpslogger.session

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 履歴一覧用に、各セッションの meta.json のタグを覚えておく。
 *
 * 一覧画面は再生画面へ移るたびに破棄されるので、画面の外（プロセスの間）で保持し、
 * 戻ってくるたびに全件を読み直さずに済むようにする。
 * meta.json の更新日時とサイズが前回と同じなら、ファイルは読まずに前回の結果を使う。
 */
object SessionTagCache {

    private class Entry(val modified: Long, val length: Long, val tags: List<String>)

    /** キーはセッションフォルダの絶対パス */
    private val entries = ConcurrentHashMap<String, Entry>()

    /** 読み込み済みの分だけを返す（ファイルには触らない）。キーはセッション名 */
    fun cached(): Map<String, List<String>> =
        entries.entries.associate { File(it.key).name to it.value.tags }

    /**
     * 指定したセッションのタグを返す（キーはセッション名）。
     * 変わったものだけ読み直し、一覧に無くなったセッションは忘れる。
     * ファイルを読むので、メインスレッドから呼ばないこと。
     */
    fun load(dirs: List<File>): Map<String, List<String>> {
        val keep = HashSet<String>(dirs.size)
        val result = LinkedHashMap<String, List<String>>(dirs.size)
        for (dir in dirs) {
            val key = dir.absolutePath
            keep += key
            val file = File(dir, SessionMeta.FILE_NAME)
            // ファイルが無ければどちらも 0 になる
            val modified = file.lastModified()
            val length = file.length()
            val hit = entries[key]
            val tags = if (hit != null && hit.modified == modified && hit.length == length) {
                hit.tags
            } else {
                readTags(file).also { entries[key] = Entry(modified, length, it) }
            }
            result[dir.name] = tags
        }
        entries.keys.retainAll(keep)
        return result
    }

    /** meta.json を書き換えたときに呼ぶ。次の load() で必ず読み直す */
    fun invalidate(dir: File) {
        entries.remove(dir.absolutePath)
    }

    /** 無い・読めない・JSON として不正なら空。空白だけのタグと重複は除く */
    private fun readTags(file: File): List<String> {
        if (!file.isFile) return emptyList()
        val meta = try {
            SessionMeta.parse(file.readText())
        } catch (e: Exception) {
            null
        }
        return meta?.tags.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}

/** タグと、そのタグが付いたセッションの数 */
data class TagCount(val tag: String, val count: Int)

/** タグによる絞り込み（AND 条件） */
object TagFilter {

    /** 選択したタグをすべて含むか。何も選んでいなければ常に true */
    fun matches(tags: List<String>, required: Set<String>): Boolean =
        required.isEmpty() || tags.containsAll(required)

    /** 選択したタグをすべて含むセッション名だけを、元の並びのまま返す */
    fun filter(
        names: List<String>,
        tagsByName: Map<String, List<String>>,
        required: Set<String>
    ): List<String> =
        if (required.isEmpty()) names
        else names.filter { matches(tagsByName[it].orEmpty(), required) }

    /**
     * 指定したセッションに付いているタグを集める。
     * 付いている件数の多い順、同数なら名前順。
     */
    fun collect(tagsByName: Map<String, List<String>>, names: Collection<String>): List<TagCount> {
        val counts = HashMap<String, Int>()
        for (name in names) {
            tagsByName[name].orEmpty().toSet().forEach { tag ->
                counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return counts.map { (tag, count) -> TagCount(tag, count) }
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.tag })
    }
}

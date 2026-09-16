package io.github.eightbrows.gpslogger.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionTagsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun forgetEverything() {
        // シングルトンなので、他のテストに持ち越さない
        SessionTagCache.load(emptyList())
    }

    // ===== TagFilter =====

    private val tagsByName = mapOf(
        "s1" to listOf("旅行", "お気に入り"),
        "s2" to listOf("旅行"),
        "s3" to listOf("通勤", "お気に入り", "旅行"),
        "s4" to emptyList()
    )
    private val names = listOf("s1", "s2", "s3", "s4", "s5") // s5 は meta.json なし

    @Test
    fun matches_emptySelectionMatchesEverything() {
        assertTrue(TagFilter.matches(emptyList(), emptySet()))
        assertTrue(TagFilter.matches(listOf("旅行"), emptySet()))
    }

    @Test
    fun matches_requiresAllSelectedTags() {
        val selected = setOf("旅行", "お気に入り")
        assertTrue(TagFilter.matches(listOf("旅行", "お気に入り"), selected))
        assertTrue(TagFilter.matches(listOf("通勤", "お気に入り", "旅行"), selected))
        // どれか1つだけでは不可（OR ではない）
        assertFalse(TagFilter.matches(listOf("旅行"), selected))
        assertFalse(TagFilter.matches(listOf("お気に入り"), selected))
        assertFalse(TagFilter.matches(emptyList(), selected))
    }

    @Test
    fun filter_keepsOriginalOrder() {
        assertEquals(names, TagFilter.filter(names, tagsByName, emptySet()))
        assertEquals(listOf("s1", "s2", "s3"), TagFilter.filter(names, tagsByName, setOf("旅行")))
        assertEquals(listOf("s1", "s3"), TagFilter.filter(names, tagsByName, setOf("旅行", "お気に入り")))
        assertEquals(listOf("s3"), TagFilter.filter(names, tagsByName, setOf("旅行", "通勤")))
        assertEquals(emptyList<String>(), TagFilter.filter(names, tagsByName, setOf("通勤", "存在しない")))
    }

    @Test
    fun collect_countsSessionsAndSortsByCountThenName() {
        assertEquals(
            listOf(TagCount("旅行", 3), TagCount("お気に入り", 2), TagCount("通勤", 1)),
            TagFilter.collect(tagsByName, names)
        )
    }

    @Test
    fun collect_onlyLooksAtGivenSessions_andCountsDuplicatesOnce() {
        val map = tagsByName + ("dup" to listOf("b", "b", "a"))
        assertEquals(
            listOf(TagCount("a", 1), TagCount("b", 1)),
            TagFilter.collect(map, listOf("dup"))
        )
        assertEquals(emptyList<TagCount>(), TagFilter.collect(tagsByName, listOf("s4", "s5")))
    }

    // ===== SessionTagCache =====

    private fun session(name: String, tags: List<String>?): File {
        val dir = File(tmp.root, name).apply { mkdirs() }
        if (tags != null) SessionMeta(tags = tags).writeTo(dir)
        return dir
    }

    private fun meta(dir: File) = File(dir, SessionMeta.FILE_NAME)

    @Test
    fun load_readsTagsAndHandlesMissingOrBrokenMeta() {
        val a = session("session_20260915_090000", listOf("旅行", "お気に入り"))
        val b = session("session_20260915_100000", null)
        val c = session("session_20260915_110000", null).also { meta(it).writeText("{ broken") }
        val d = session("session_20260915_120000", listOf(" 旅行 ", "", "旅行", "  "))

        val tags = SessionTagCache.load(listOf(a, b, c, d))
        assertEquals(listOf("旅行", "お気に入り"), tags[a.name])
        assertEquals(emptyList<String>(), tags[b.name])
        assertEquals(emptyList<String>(), tags[c.name])
        // 前後の空白・空・重複は除く
        assertEquals(listOf("旅行"), tags[d.name])
        assertEquals(listOf(a.name, b.name, c.name, d.name), tags.keys.toList())
    }

    @Test
    fun load_reusesResultWhenFileIsUnchanged() {
        val dir = session("session_20260915_090000", listOf("aaaa"))
        val stamp = 1_700_000_000_000L
        meta(dir).setLastModified(stamp)
        assertEquals(listOf("aaaa"), SessionTagCache.load(listOf(dir))[dir.name])

        // 同じ長さの内容に書き換え、更新日時も戻す → 読み直さない（キャッシュが使われている証拠）
        SessionMeta(tags = listOf("bbbb")).writeTo(dir)
        meta(dir).setLastModified(stamp)
        assertEquals(listOf("aaaa"), SessionTagCache.load(listOf(dir))[dir.name])

        // 明示的に無効化すれば読み直す
        SessionTagCache.invalidate(dir)
        assertEquals(listOf("bbbb"), SessionTagCache.load(listOf(dir))[dir.name])
    }

    @Test
    fun load_rereadsWhenFileChanges() {
        val dir = session("session_20260915_090000", listOf("旅行"))
        meta(dir).setLastModified(1_700_000_000_000L)
        assertEquals(listOf("旅行"), SessionTagCache.load(listOf(dir))[dir.name])

        SessionMeta(tags = listOf("旅行", "お気に入り")).writeTo(dir)
        meta(dir).setLastModified(1_700_000_005_000L)
        assertEquals(listOf("旅行", "お気に入り"), SessionTagCache.load(listOf(dir))[dir.name])
    }

    @Test
    fun load_picksUpMetaCreatedLater() {
        val dir = session("session_20260915_090000", null)
        assertEquals(emptyList<String>(), SessionTagCache.load(listOf(dir))[dir.name])

        // 記録停止時の書き出しや、編集画面での新規作成
        SessionMeta(tags = listOf("新規")).writeTo(dir)
        assertEquals(listOf("新規"), SessionTagCache.load(listOf(dir))[dir.name])
    }

    @Test
    fun load_forgetsSessionsNoLongerListed() {
        val a = session("session_20260915_090000", listOf("x"))
        val b = session("session_20260915_100000", listOf("y"))
        SessionTagCache.load(listOf(a, b))
        assertEquals(setOf(a.name, b.name), SessionTagCache.cached().keys)

        b.deleteRecursively()
        SessionTagCache.load(listOf(a))
        assertEquals(mapOf(a.name to listOf("x")), SessionTagCache.cached())
    }

    @Test
    fun cached_doesNotTouchFiles() {
        val dir = session("session_20260915_090000", listOf("x"))
        assertEquals(emptyMap<String, List<String>>(), SessionTagCache.cached())
        SessionTagCache.load(listOf(dir))
        dir.deleteRecursively()
        // 削除後でも、次の load() までは前回の結果を返すだけ
        assertEquals(mapOf(dir.name to listOf("x")), SessionTagCache.cached())
    }
}

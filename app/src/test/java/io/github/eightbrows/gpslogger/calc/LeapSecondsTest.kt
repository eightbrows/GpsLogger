package io.github.eightbrows.gpslogger.calc

import io.github.eightbrows.gpslogger.session.Segment
import io.github.eightbrows.gpslogger.session.SessionMeta
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

/** セッションのうるう秒 → アプリ全体の設定、の順で決まることを確認する */
class LeapSecondsTest {

    private val appSetting = 18

    private fun meta(sec: Int?) = SessionMeta(
        leapSeconds = sec,
        segments = listOf(
            Segment(
                OffsetDateTime.parse("2026-09-20T09:00:00+09:00"),
                OffsetDateTime.parse("2026-09-20T09:00:10+09:00"),
                11
            )
        )
    )

    @Test
    fun noMeta_usesAppSetting() {
        assertEquals(18, resolveLeapSeconds(null, appSetting))
        assertEquals(19, resolveLeapSeconds(null, 19))
    }

    @Test
    fun metaWithoutLeapSeconds_usesAppSetting() {
        // この項目より前に記録したセッション
        assertEquals(18, resolveLeapSeconds(meta(null), appSetting))
    }

    @Test
    fun sessionValue_winsOverAppSetting() {
        assertEquals(17, resolveLeapSeconds(meta(17), appSetting))
        assertEquals(0, resolveLeapSeconds(meta(0), appSetting))
        assertEquals(99, resolveLeapSeconds(meta(99), appSetting))
    }

    @Test
    fun outOfRangeSessionValue_fallsBackToAppSetting() {
        assertEquals(18, resolveLeapSeconds(meta(-1), appSetting))
        assertEquals(18, resolveLeapSeconds(meta(100), appSetting))
    }

    @Test
    fun valueIsIndependentOfSegments() {
        // うるう秒は区間別に持たないので、時刻によらず同じ値になる
        val meta = meta(17)
        assertEquals(17, resolveLeapSeconds(meta, appSetting))
        assertEquals(17, resolveLeapSeconds(meta.copy(segments = emptyList()), appSetting))
    }
}

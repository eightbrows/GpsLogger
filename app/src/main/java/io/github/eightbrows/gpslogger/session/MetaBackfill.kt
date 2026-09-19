package io.github.eightbrows.gpslogger.session

import io.github.eightbrows.gpslogger.calc.Geo
import java.io.File
import java.io.FileNotFoundException
import java.time.ZoneId

/**
 * meta.json が無いセッション（v1 形式のころの記録など）に、track.csv から meta.json を後付けで作る。
 * 記録時の情報（端末・一時停止・WakeLock など）は分からないので、分からない項目は既定値にする。
 */
object MetaBackfill {

    /** 記録時の情報が分からない項目に入れる値 */
    const val UNKNOWN = "Unknown"

    /**
     * 測位点から meta を組み立てる。
     * - start / end: 最初・最後の点の epoch_ms（端末のタイムゾーン、秒未満は切り捨て）
     * - distanceM: 一時停止で区切らず、全点を 1 本の線として結んだ距離
     * - segments: 全体で 1 区間
     * 点が 0 なら start / end は null、segments は空にする。
     */
    fun build(track: List<TrackRecord>, zone: ZoneId = ZoneId.systemDefault()): SessionMeta {
        val start = track.firstOrNull()?.let { SessionMeta.timeOf(it.epochMs, zone) }
        val end = track.lastOrNull()?.let { SessionMeta.timeOf(it.epochMs, zone) }
        return SessionMeta(
            comment = "",
            tags = emptyList(),
            basePressureHpa = SessionMeta.DEFAULT_BASE_PRESSURE_HPA,
            start = start,
            end = end,
            pointCount = track.size,
            distanceM = Geo.pathLengthM(track.map { it.latitude to it.longitude }),
            segments = if (track.isEmpty()) emptyList()
            else listOf(Segment(start = start, end = end, points = track.size, basePressureHpa = null)),
            useWakeLock = false,
            deviceModel = UNKNOWN,
            osVersion = UNKNOWN,
            appVersion = UNKNOWN
        )
    }

    /**
     * track.csv を読み直して meta.json を作り、書き込んだ内容を返す。既存の meta.json は置き換える。
     * track.csv が無ければ FileNotFoundException。ファイルを読み書きするので、メインスレッドから呼ばないこと。
     */
    fun create(sessionDir: File, zone: ZoneId = ZoneId.systemDefault()): SessionMeta {
        val track = SessionReader.readTrackRecords(sessionDir)
            ?: throw FileNotFoundException(File(sessionDir, "track.csv").path)
        val meta = build(track, zone)
        meta.writeTo(sessionDir)
        // 履歴一覧のタグ表示を読み直させる
        SessionTagCache.invalidate(sessionDir)
        return meta
    }
}

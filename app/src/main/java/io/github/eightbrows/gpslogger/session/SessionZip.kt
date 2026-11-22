package io.github.eightbrows.gpslogger.session

/** ZIP の入出力でセッションのファイルを扱うときの決まり */
object SessionZip {

    /**
     * インポートで受け付けるエントリ名（これ以外は全て拒否）。
     * "セッション名/ファイル名" の 1 階層だけで、ファイル名は記録・書き出しで作るものに限る
     */
    val ENTRY_PATTERN =
        Regex(
            "^(session_\\d{8}_\\d{6})/" +
                "(track\\.csv|sats\\.csv|steps\\.csv|meta\\.json|track\\.gpx|track\\.kmz)$"
        )
}

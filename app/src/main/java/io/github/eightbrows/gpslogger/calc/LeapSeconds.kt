package io.github.eightbrows.gpslogger.calc

import io.github.eightbrows.gpslogger.session.SessionMeta

/**
 * Z-count の算出に使ううるう秒（GPS − UTC、秒）を決める。
 *
 * 記録中は変わらない値なので、基準気圧やジオイド高のような区間別の上書きは持たない。
 * セッションに設定があればそれを、無ければ fallbackSec（アプリ全体の設定値）を使う。
 * 範囲外の値は壊れているとみなし、未設定と同じに扱う。
 */
fun resolveLeapSeconds(meta: SessionMeta?, fallbackSec: Int): Int =
    meta?.leapSeconds?.takeIf { it in SessionMeta.MIN_LEAP_SECONDS..SessionMeta.MAX_LEAP_SECONDS }
        ?: fallbackSec

package io.github.eightbrows.gpslogger.calc

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 緯度経度の距離計算（球面近似） */
object Geo {

    /** 地球の平均半径（m） */
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** 2点間の大円距離（m）。Haversine 式 */
    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        // 丸め誤差で 1 をわずかに超えると asin が NaN になるので抑える
        return 2 * EARTH_RADIUS_M * asin(sqrt(min(1.0, a)))
    }

    /** 点列を順に結んだ距離の合計（m）。点が2つ未満なら 0 */
    fun pathLengthM(points: List<Pair<Double, Double>>): Double =
        pathLengthM(points) { false }

    /**
     * 点列を区間ごとに結んだ距離の合計（m）。
     * gapBefore(i) が true の点 i は新しい区間の先頭とみなし、直前の点とは結ばない
     * （一時停止中の移動を距離に含めないため）。先頭の点の判定は結果に影響しない。
     */
    fun pathLengthM(points: List<Pair<Double, Double>>, gapBefore: (Int) -> Boolean): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            if (gapBefore(i)) continue
            val (lat1, lon1) = points[i - 1]
            val (lat2, lon2) = points[i]
            total += haversineM(lat1, lon1, lat2, lon2)
        }
        return total
    }
}

package io.github.eightbrows.gpslogger.calc

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {

    @Test
    fun oneDegreeOfLatitude_isAbout111km() {
        // 半径 6371008.8 m の球で 1 度 = 111195.08 m
        assertEquals(111_195.08, Geo.haversineM(0.0, 0.0, 1.0, 0.0), 0.05)
    }

    @Test
    fun oneDegreeOfLongitude_shrinksWithLatitude() {
        val atEquator = Geo.haversineM(0.0, 139.0, 0.0, 140.0)
        val at60 = Geo.haversineM(60.0, 139.0, 60.0, 140.0)
        assertEquals(atEquator / 2, at60, 5.0)
    }

    @Test
    fun tokyoToOsaka_isAbout400km() {
        // 東京駅 → 大阪駅。球面近似で約 403 km
        val d = Geo.haversineM(35.681236, 139.767125, 34.702485, 135.495951)
        assertEquals(403_000.0, d, 2_000.0)
    }

    @Test
    fun samePoint_isZero() {
        assertEquals(0.0, Geo.haversineM(35.0, 139.0, 35.0, 139.0), 0.0)
    }

    @Test
    fun pathLength_sumsSegments() {
        val points = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        assertEquals(2 * 111_195.08, Geo.pathLengthM(points), 0.1)
    }

    @Test
    fun pathLength_fewerThanTwoPoints_isZero() {
        assertEquals(0.0, Geo.pathLengthM(emptyList()), 0.0)
        assertEquals(0.0, Geo.pathLengthM(listOf(35.0 to 139.0)), 0.0)
    }

    // --- 区間分割（一時停止をまたぐ移動を除外） ---

    private val oneDegreeM = 111_195.08

    @Test
    fun segmented_skipsOnlyTheLegIntoAGapPoint() {
        // 0→1 度を歩き、一時停止中に 5 度へ移動、再開して 5→6 度を歩く
        val points = listOf(0.0 to 0.0, 1.0 to 0.0, 5.0 to 0.0, 6.0 to 0.0)
        val gaps = setOf(2)
        assertEquals(2 * oneDegreeM, Geo.pathLengthM(points) { it in gaps }, 0.1)
        // 区切らなければ 6 度分になる（除外が効いていることの対照）
        assertEquals(6 * oneDegreeM, Geo.pathLengthM(points), 0.1)
    }

    @Test
    fun segmented_withoutGaps_equalsPlainSumExactly() {
        val points = listOf(
            35.681236 to 139.767125,
            35.689487 to 139.691706,
            35.658034 to 139.701636,
            35.710063 to 139.810700
        )
        var expected = 0.0
        for (i in 1 until points.size) {
            expected += Geo.haversineM(
                points[i - 1].first, points[i - 1].second,
                points[i].first, points[i].second
            )
        }
        // 区間が1つなら足し算の順序も同じなので、誤差なしで一致する
        assertEquals(expected, Geo.pathLengthM(points) { false }, 0.0)
        assertEquals(expected, Geo.pathLengthM(points), 0.0)
    }

    @Test
    fun segmented_gapOnFirstPoint_hasNoEffect() {
        val points = listOf(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        assertEquals(Geo.pathLengthM(points), Geo.pathLengthM(points) { it == 0 }, 0.0)
    }

    @Test
    fun segmented_consecutiveGaps_leaveIsolatedPointOut() {
        // 1 と 2 がどちらも再開直後 → 0→1 と 1→2 を除外し、2→3 だけ数える
        val points = listOf(0.0 to 0.0, 1.0 to 0.0, 3.0 to 0.0, 4.0 to 0.0)
        assertEquals(oneDegreeM, Geo.pathLengthM(points) { it == 1 || it == 2 }, 0.1)
    }

    @Test
    fun segmented_everyPointIsGap_isZero() {
        val points = listOf(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        assertEquals(0.0, Geo.pathLengthM(points) { true }, 0.0)
    }
}

package io.github.eightbrows.gpslogger.calc

import io.github.eightbrows.gpslogger.log.LogEvent
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Dop(
    val gdop: Double,
    val pdop: Double,
    val hdop: Double,
    val vdop: Double,
    val tdop: Double
)

object DopCalculator {

    /**
     * 使用衛星の az/el から DOP を計算する。
     * 衛星4個未満、または幾何が特異な場合は null。
     */
    fun calculate(satellites: List<LogEvent.Sat>): Dop? {
        val used = satellites.filter { it.usedInFix }
        if (used.size < 4) return null

        // 観測行列 H (n×4): 各行 = [east, north, up, 1]
        val h = Array(used.size) { i ->
            val sat = used[i]
            val elRad = Math.toRadians(sat.elevationDeg.toDouble())
            val azRad = Math.toRadians(sat.azimuthDeg.toDouble())
            val cosEl = cos(elRad)
            doubleArrayOf(
                cosEl * sin(azRad),   // 東西
                cosEl * cos(azRad),   // 南北
                sin(elRad),           // 上下
                1.0                   // 時刻
            )
        }

        // HtH = Hᵀ * H (4×4)
        val hth = Array(4) { DoubleArray(4) }
        for (row in h) {
            for (i in 0 until 4) {
                for (j in 0 until 4) {
                    hth[i][j] += row[i] * row[j]
                }
            }
        }

        val q = invert4x4(hth) ?: return null

        val qxx = q[0][0]
        val qyy = q[1][1]
        val qzz = q[2][2]
        val qtt = q[3][3]

        // 対角成分が負になるのは数値的に破綻しているケース
        if (qxx < 0 || qyy < 0 || qzz < 0 || qtt < 0) return null

        return Dop(
            gdop = sqrt(qxx + qyy + qzz + qtt),
            pdop = sqrt(qxx + qyy + qzz),
            hdop = sqrt(qxx + qyy),
            vdop = sqrt(qzz),
            tdop = sqrt(qtt)
        )
    }

    /** ガウス・ジョルダン法で4×4を逆行列化。特異なら null。 */
    private fun invert4x4(m: Array<DoubleArray>): Array<DoubleArray>? {
        val n = 4
        // [m | I] の拡大行列
        val a = Array(n) { i ->
            DoubleArray(2 * n).also { row ->
                for (j in 0 until n) row[j] = m[i][j]
                row[n + i] = 1.0
            }
        }

        for (col in 0 until n) {
            // 部分ピボット選択（絶対値最大の行を選ぶ）
            var pivotRow = col
            for (r in col + 1 until n) {
                if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[pivotRow][col])) pivotRow = r
            }
            if (kotlin.math.abs(a[pivotRow][col]) < 1e-12) return null  // 特異行列

            val tmp = a[col]; a[col] = a[pivotRow]; a[pivotRow] = tmp

            // ピボットを1に正規化
            val pivot = a[col][col]
            for (j in 0 until 2 * n) a[col][j] /= pivot

            // 他の行から消去
            for (r in 0 until n) {
                if (r == col) continue
                val factor = a[r][col]
                if (factor == 0.0) continue
                for (j in 0 until 2 * n) a[r][j] -= factor * a[col][j]
            }
        }

        return Array(n) { i -> DoubleArray(n) { j -> a[i][j + n] } }
    }
}
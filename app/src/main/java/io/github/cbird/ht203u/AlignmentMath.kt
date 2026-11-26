package io.github.cbird.ht203u

import kotlin.math.abs

/** A projective fit of four matching image/passthrough points, in normalized panel coordinates. */
object AlignmentMath {
    val identity = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    fun map(h: List<Float>, x: Float, y: Float): Pair<Float, Float> {
        val d = h[6] * x + h[7] * y + h[8]
        require(abs(d) > 1e-6f)
        return (h[0] * x + h[1] * y + h[2]) / d to (h[3] * x + h[4] * y + h[5]) / d
    }

    fun fit(source: List<Pair<Float, Float>>, target: List<Pair<Float, Float>>): List<Float>? {
        if (source.size != 4 || target.size != 4) return null
        val a = Array(8) { DoubleArray(9) }
        for (i in 0..3) {
            val (x, y) = source[i]; val (u, v) = target[i]
            a[i * 2] = doubleArrayOf(x.toDouble(), y.toDouble(), 1.0, 0.0, 0.0, 0.0,
                (-u*x).toDouble(), (-u*y).toDouble(), u.toDouble())
            a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x.toDouble(), y.toDouble(), 1.0,
                (-v*x).toDouble(), (-v*y).toDouble(), v.toDouble())
        }
        for (column in 0..7) {
            val pivot = (column..7).maxBy { abs(a[it][column]) }
            if (abs(a[pivot][column]) < 1e-8) return null
            val tmp = a[column]; a[column] = a[pivot]; a[pivot] = tmp
            val scale = a[column][column]
            for (k in column..8) a[column][k] /= scale
            for (row in 0..7) if (row != column) {
                val factor = a[row][column]
                for (k in column..8) a[row][k] -= factor * a[column][k]
            }
        }
        val result = (0..7).map { a[it][8].toFloat() } + 1f
        if (result.any { !it.isFinite() }) return null
        // Reject a fold or a pole through the image, rather than saving a broken overlay.
        val corners = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
        val divisors = corners.map { result[6] * it.first + result[7] * it.second + 1f }
        if (divisors.any { it <= 0.05f }) return null
        return result
    }
}

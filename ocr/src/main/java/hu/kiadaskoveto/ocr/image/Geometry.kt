package hu.kiadaskoveto.ocr.image

import kotlin.math.hypot
import kotlin.math.max

data class PointF2(val x: Float, val y: Float)

/**
 * A quadrilateral in image coordinates, corners in order:
 * top-left, top-right, bottom-right, bottom-left. Used for receipt cropping.
 */
data class Quad(val tl: PointF2, val tr: PointF2, val br: PointF2, val bl: PointF2) {
    val points: List<PointF2> get() = listOf(tl, tr, br, bl)

    /** Output size of a perspective-corrected crop. */
    fun targetSize(): Pair<Int, Int> {
        val w = max(dist(tl, tr), dist(bl, br))
        val h = max(dist(tl, bl), dist(tr, br))
        return w.toInt().coerceAtLeast(1) to h.toInt().coerceAtLeast(1)
    }

    fun scaled(sx: Float, sy: Float) = Quad(
        PointF2(tl.x * sx, tl.y * sy), PointF2(tr.x * sx, tr.y * sy),
        PointF2(br.x * sx, br.y * sy), PointF2(bl.x * sx, bl.y * sy),
    )

    fun isFullFrame(width: Int, height: Int, tolerance: Float = 2f): Boolean =
        tl.x <= tolerance && tl.y <= tolerance && tr.x >= width - tolerance && br.y >= height - tolerance

    /** Normalized (0..1) representation for persisting a crop independent of image size. */
    fun normalized(width: Int, height: Int) = scaled(1f / width, 1f / height)

    companion object {
        fun full(width: Int, height: Int) = Quad(
            PointF2(0f, 0f), PointF2(width.toFloat(), 0f),
            PointF2(width.toFloat(), height.toFloat()), PointF2(0f, height.toFloat()),
        )
        private fun dist(a: PointF2, b: PointF2) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
    }
}

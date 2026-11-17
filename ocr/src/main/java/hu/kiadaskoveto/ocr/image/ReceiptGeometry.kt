package hu.kiadaskoveto.ocr.image

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.tan

/** Finds the receipt paper in a photo: the largest bright region on a darker background. */
object ReceiptBoundsDetector {

    /**
     * Returns the receipt corners in [image] coordinates, or null when no distinct
     * receipt region was found (e.g. the receipt fills the frame or the background is light).
     */
    fun detect(image: GrayImage): Quad? {
        val scale = if (image.width > 320) 320.0 / image.width else 1.0
        val small = if (scale < 1.0) image.scaledToWidth(320) else image
        val w = small.width; val h = small.height
        val threshold = small.otsuThreshold()
        val bright = BooleanArray(w * h) { small.data[it].toInt() and 0xff > threshold }

        // Largest connected bright component (4-connectivity, iterative BFS).
        val label = IntArray(w * h)
        var bestLabel = 0; var bestSize = 0; var current = 0
        val queue = IntArray(w * h)
        for (start in 0 until w * h) {
            if (!bright[start] || label[start] != 0) continue
            current++
            var head = 0; var tail = 0
            queue[tail++] = start; label[start] = current
            var size = 0
            while (head < tail) {
                val p = queue[head++]; size++
                val x = p % w; val y = p / w
                if (x > 0) { val q = p - 1; if (bright[q] && label[q] == 0) { label[q] = current; queue[tail++] = q } }
                if (x < w - 1) { val q = p + 1; if (bright[q] && label[q] == 0) { label[q] = current; queue[tail++] = q } }
                if (y > 0) { val q = p - w; if (bright[q] && label[q] == 0) { label[q] = current; queue[tail++] = q } }
                if (y < h - 1) { val q = p + w; if (bright[q] && label[q] == 0) { label[q] = current; queue[tail++] = q } }
            }
            if (size > bestSize) { bestSize = size; bestLabel = current }
        }
        val area = w * h
        if (bestSize < area * 0.12 || bestSize > area * 0.97) return null

        var tlS = Int.MAX_VALUE; var brS = Int.MIN_VALUE; var trD = Int.MIN_VALUE; var blD = Int.MAX_VALUE
        var tl = 0; var br = 0; var tr = 0; var bl = 0
        for (p in 0 until area) {
            if (label[p] != bestLabel) continue
            val x = p % w; val y = p / w
            val s = x + y; val d = x - y
            if (s < tlS) { tlS = s; tl = p }
            if (s > brS) { brS = s; br = p }
            if (d > trD) { trD = d; tr = p }
            if (d < blD) { blD = d; bl = p }
        }
        fun pt(p: Int) = PointF2(((p % w) / scale).toFloat(), ((p / w) / scale).toFloat())
        val quad = Quad(pt(tl), pt(tr), pt(br), pt(bl))
        val (qw, qh) = quad.targetSize()
        if (qw < image.width * 0.15 || qh < image.height * 0.15) return null
        return quad
    }
}

/** Estimates small text skew and 90° orientation from projection profiles. */
object SkewEstimator {

    /** Returns the skew angle in degrees (positive = text rises to the right) within ±[maxDegrees]. */
    fun estimateSkew(binary: GrayImage, maxDegrees: Double = 8.0, step: Double = 0.5): Double {
        val small = if (binary.width > 600) binary.scaledToWidth(600) else binary
        val dark = ArrayList<Long>()
        for (y in 0 until small.height) for (x in 0 until small.width) if (small[x, y] < 128) dark += (x.toLong() shl 32) or y.toLong()
        if (dark.size < 50) return 0.0
        var bestAngle = 0.0; var bestScore = -1.0
        var a = -maxDegrees
        while (a <= maxDegrees + 1e-9) {
            val score = profileScore(dark, small.height, small.width, a)
            if (score > bestScore) { bestScore = score; bestAngle = a }
            a += step
        }
        return bestAngle
    }

    private fun profileScore(dark: List<Long>, height: Int, width: Int, degrees: Double): Double {
        val t = tan(degrees * PI / 180)
        val offset = (width * kotlin.math.abs(t)).toInt() + 2
        val bins = IntArray(height + 2 * offset)
        for (p in dark) {
            val x = (p shr 32).toInt(); val y = (p and 0xffffffffL).toInt()
            val row = (y + x * t).toInt() + offset
            if (row in bins.indices) bins[row]++
        }
        var score = 0.0
        for (i in 1 until bins.size) { val d = (bins[i] - bins[i - 1]).toDouble(); score += d * d }
        return score
    }

    /**
     * Decides whether the text lines run horizontally (0) or vertically (needs 90° rotation).
     * Returns the number of clockwise quarter turns (0 or 1) that make lines horizontal.
     */
    fun quarterTurnsForHorizontalText(binary: GrayImage): Int {
        val small = if (max(binary.width, binary.height) > 500) {
            val s = 500.0 / max(binary.width, binary.height)
            binary.resized(max(1, (binary.width * s).toInt()), max(1, (binary.height * s).toInt()))
        } else binary
        fun rowScore(img: GrayImage): Double {
            val rows = IntArray(img.height)
            for (y in 0 until img.height) for (x in 0 until img.width) if (img[x, y] < 128) rows[y]++
            var s = 0.0
            for (i in 1 until rows.size) { val d = (rows[i] - rows[i - 1]).toDouble(); s += d * d }
            return s / max(1, img.width)
        }
        val horizontal = rowScore(small)
        val vertical = rowScore(small.rotated90(1))
        return if (vertical > horizontal * 1.6) 1 else 0
    }
}

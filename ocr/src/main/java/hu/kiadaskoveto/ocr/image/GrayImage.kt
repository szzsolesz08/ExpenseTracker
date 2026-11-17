package hu.kiadaskoveto.ocr.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 8-bit grayscale image (0 = black, 255 = white) with the pure-Kotlin image operations used
 * for receipt preprocessing. No Android dependencies, so every algorithm is unit-testable on the JVM.
 */
class GrayImage(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height)) {

    init { require(data.size == width * height) { "data size mismatch" } }

    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xff
    operator fun set(x: Int, y: Int, v: Int) { data[y * width + x] = v.coerceIn(0, 255).toByte() }

    fun copy(): GrayImage = GrayImage(width, height, data.copyOf())

    fun histogram(): IntArray {
        val h = IntArray(256)
        for (b in data) h[b.toInt() and 0xff]++
        return h
    }

    fun meanLuminance(): Double {
        var sum = 0L
        for (b in data) sum += b.toInt() and 0xff
        return sum.toDouble() / data.size
    }

    /** Linear contrast stretch between the [lowPct] and [highPct] percentiles. */
    fun contrastStretched(lowPct: Double = 0.01, highPct: Double = 0.99): GrayImage {
        val h = histogram()
        val n = data.size.toLong()
        var acc = 0L
        var lo = 0
        while (lo < 255 && acc + h[lo] < n * lowPct) { acc += h[lo]; lo++ }
        acc = 0L
        var hi = 255
        while (hi > 0 && acc + h[hi] < n * (1 - highPct)) { acc += h[hi]; hi-- }
        if (hi - lo < 16) return copy()
        val lut = IntArray(256) { v -> ((v - lo) * 255.0 / (hi - lo)).roundToInt().coerceIn(0, 255) }
        val out = ByteArray(data.size)
        for (i in data.indices) out[i] = lut[data[i].toInt() and 0xff].toByte()
        return GrayImage(width, height, out)
    }

    /** 3×3 box blur (separable) for light noise reduction before thresholding. */
    fun boxBlur3(): GrayImage {
        val tmp = IntArray(data.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val a = this[max(0, x - 1), y]; val b = this[x, y]; val c = this[min(width - 1, x + 1), y]
                tmp[row + x] = a + b + c
            }
        }
        val out = ByteArray(data.size)
        for (y in 0 until height) for (x in 0 until width) {
            val a = tmp[max(0, y - 1) * width + x]; val b = tmp[y * width + x]; val c = tmp[min(height - 1, y + 1) * width + x]
            out[y * width + x] = ((a + b + c) / 9).toByte()
        }
        return GrayImage(width, height, out)
    }

    /**
     * Bradley–Roth adaptive threshold using an integral image: a pixel becomes black when it is
     * [t] darker than the mean of its neighbourhood. Robust to shadows and uneven lighting.
     */
    fun adaptiveThreshold(windowFraction: Double = 1.0 / 16, t: Double = 0.15): GrayImage {
        val integral = IntArray((width + 1) * (height + 1))
        val w1 = width + 1
        for (y in 0 until height) {
            var rowSum = 0
            for (x in 0 until width) {
                rowSum += this[x, y]
                integral[(y + 1) * w1 + x + 1] = integral[y * w1 + x + 1] + rowSum
            }
        }
        val half = max(4, (width * windowFraction).toInt() / 2)
        val out = ByteArray(data.size)
        for (y in 0 until height) {
            val y0 = max(0, y - half); val y1 = min(height, y + half + 1)
            for (x in 0 until width) {
                val x0 = max(0, x - half); val x1 = min(width, x + half + 1)
                val count = (x1 - x0) * (y1 - y0)
                val sum = integral[y1 * w1 + x1] - integral[y0 * w1 + x1] - integral[y1 * w1 + x0] + integral[y0 * w1 + x0]
                val v = this[x, y]
                out[y * width + x] = if (v.toLong() * count < sum * (1.0 - t)) 0 else 255.toByte()
            }
        }
        return GrayImage(width, height, out)
    }

    /** Removes isolated black specks (≤ [maxNeighbours] black neighbours) from a binary image. */
    fun despeckled(maxNeighbours: Int = 1): GrayImage {
        val out = data.copyOf()
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            if (this[x, y] != 0) continue
            var n = 0
            for (dy in -1..1) for (dx in -1..1) if ((dx != 0 || dy != 0) && this[x + dx, y + dy] == 0) n++
            if (n <= maxNeighbours) out[y * width + x] = 255.toByte()
        }
        return GrayImage(width, height, out)
    }

    /**
     * Whitens a frame of [fraction] of the width along every edge. After cropping, receipt text
     * never touches the border, but the paper edge leaves dashed residue that slows OCR.
     */
    fun withClearedBorder(fraction: Double = 0.015): GrayImage {
        val m = max(2, (width * fraction).toInt())
        val out = data.copyOf()
        val white = 255.toByte()
        for (y in 0 until height) for (x in 0 until width) {
            if (x < m || y < m || x >= width - m || y >= height - m) out[y * width + x] = white
        }
        return GrayImage(width, height, out)
    }

    /** Variance of the Laplacian — a standard sharpness measure. Low values indicate blur. */
    fun laplacianVariance(): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0; var sumSq = 0.0; var n = 0
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val l = (this[x - 1, y] + this[x + 1, y] + this[x, y - 1] + this[x, y + 1] - 4 * this[x, y]).toDouble()
            sum += l; sumSq += l * l; n++
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** Otsu's global threshold. */
    fun otsuThreshold(): Int {
        val h = histogram()
        val total = data.size.toDouble()
        var sumAll = 0.0
        for (i in 0..255) sumAll += i * h[i].toDouble()
        var sumB = 0.0; var wB = 0.0; var best = 0.0; var threshold = 127
        for (i in 0..255) {
            wB += h[i]; if (wB == 0.0) continue
            val wF = total - wB; if (wF == 0.0) break
            sumB += i * h[i].toDouble()
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = i }
        }
        return threshold
    }

    /** Bilinear resize. */
    fun resized(newWidth: Int, newHeight: Int): GrayImage {
        if (newWidth == width && newHeight == height) return copy()
        val out = ByteArray(newWidth * newHeight)
        val sx = width.toDouble() / newWidth; val sy = height.toDouble() / newHeight
        for (y in 0 until newHeight) {
            val fy = ((y + 0.5) * sy - 0.5).coerceIn(0.0, (height - 1).toDouble())
            val y0 = fy.toInt(); val y1 = min(height - 1, y0 + 1); val dy = fy - y0
            for (x in 0 until newWidth) {
                val fx = ((x + 0.5) * sx - 0.5).coerceIn(0.0, (width - 1).toDouble())
                val x0 = fx.toInt(); val x1 = min(width - 1, x0 + 1); val dx = fx - x0
                val top = this[x0, y0] * (1 - dx) + this[x1, y0] * dx
                val bottom = this[x0, y1] * (1 - dx) + this[x1, y1] * dx
                out[y * newWidth + x] = (top * (1 - dy) + bottom * dy).roundToInt().toByte()
            }
        }
        return GrayImage(newWidth, newHeight, out)
    }

    fun scaledToWidth(targetWidth: Int): GrayImage =
        resized(targetWidth, max(1, (height.toLong() * targetWidth / width).toInt()))

    /** Rotation by multiples of 90° clockwise. */
    fun rotated90(times: Int): GrayImage {
        return when (((times % 4) + 4) % 4) {
            0 -> copy()
            1 -> GrayImage(height, width).also { o -> for (y in 0 until height) for (x in 0 until width) o[height - 1 - y, x] = this[x, y] }
            2 -> GrayImage(width, height).also { o -> for (y in 0 until height) for (x in 0 until width) o[width - 1 - x, height - 1 - y] = this[x, y] }
            else -> GrayImage(height, width).also { o -> for (y in 0 until height) for (x in 0 until width) o[y, width - 1 - x] = this[x, y] }
        }
    }

    /**
     * 64-bit difference hash: robust fingerprint of the page that survives re-encoding and
     * small exposure changes. Used for duplicate detection.
     */
    fun differenceHash(): Long {
        val small = resized(9, 8)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (small[x, y] > small[x + 1, y]) hash = hash or (1L shl bit)
            bit++
        }
        return hash
    }

    /** Fraction of black pixels in a binary image. */
    fun inkRatio(): Double = data.count { (it.toInt() and 0xff) < 128 }.toDouble() / data.size

    companion object {
        fun fromArgb(pixels: IntArray, width: Int, height: Int): GrayImage {
            val out = ByteArray(width * height)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
                out[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }
            return GrayImage(width, height, out)
        }
    }

    fun toArgb(): IntArray = IntArray(data.size) { i ->
        val v = data[i].toInt() and 0xff
        (0xff shl 24) or (v shl 16) or (v shl 8) or v
    }

    /** Mean absolute difference to [other] (same size) — used in tests. */
    fun meanAbsDiff(other: GrayImage): Double {
        require(other.width == width && other.height == height)
        var s = 0L
        for (i in data.indices) s += abs((data[i].toInt() and 0xff) - (other.data[i].toInt() and 0xff))
        return s.toDouble() / data.size
    }
}

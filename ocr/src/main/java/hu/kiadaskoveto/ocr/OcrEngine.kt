package hu.kiadaskoveto.ocr

import android.graphics.Bitmap
import hu.kiadaskoveto.core.text.Fuzzy
import hu.kiadaskoveto.core.text.HungarianText

/** One recognized text line. [confidence] in 0..1. */
data class OcrTextLine(val text: String, val confidence: Double, val top: Int = 0, val left: Int = 0)

data class OcrPage(val lines: List<OcrTextLine>, val meanConfidence: Double, val engine: String, val millis: Long)

class OcrCancelledException : Exception("Az OCR feldolgozás megszakítva.")

/** Local text recognition engine. Implementations must never contact the network. */
interface OcrEngine {
    val name: String

    /** True when all required model files are available on the device. */
    fun isAvailable(): Boolean

    /**
     * Recognizes [bitmap]. [onProgress] receives 0..100. Throws [OcrCancelledException]
     * when [cancel] was called during recognition.
     */
    suspend fun recognize(bitmap: Bitmap, onProgress: (Int) -> Unit = {}): OcrPage

    fun cancel()
}

/**
 * Joins the OCR lines of several photos of one long receipt. When consecutive photos overlap,
 * the repeated lines at the seam are removed (fuzzy match tolerates OCR differences).
 */
object PageMerger {

    fun merge(pages: List<List<OcrTextLine>>, maxOverlap: Int = 8): List<OcrTextLine> {
        if (pages.isEmpty()) return emptyList()
        val out = ArrayList(pages.first())
        for (page in pages.drop(1)) {
            val overlap = findOverlap(out, page, maxOverlap)
            out += page.drop(overlap)
        }
        return out
    }

    /** Number of leading lines of [next] that repeat the trailing lines of [previous]. */
    fun findOverlap(previous: List<OcrTextLine>, next: List<OcrTextLine>, maxOverlap: Int): Int {
        val limit = minOf(maxOverlap, previous.size, next.size)
        for (k in limit downTo 1) {
            val tail = previous.takeLast(k)
            val head = next.take(k)
            val matches = tail.zip(head).count { (a, b) -> similar(a.text, b.text) }
            if (matches == k || (k >= 3 && matches >= k - 1)) return k
        }
        return 0
    }

    private fun similar(a: String, b: String): Boolean {
        val ka = HungarianText.matchKey(a); val kb = HungarianText.matchKey(b)
        if (ka.length < 3 || kb.length < 3) return ka == kb
        return Fuzzy.similarity(ka, kb) >= 0.85
    }
}

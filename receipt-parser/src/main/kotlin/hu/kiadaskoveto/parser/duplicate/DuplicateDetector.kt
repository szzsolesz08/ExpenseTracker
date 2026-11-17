package hu.kiadaskoveto.parser.duplicate

import hu.kiadaskoveto.core.hash.Hashing
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.text.HungarianText
import java.time.Duration
import java.time.LocalDateTime

/** What we know about a receipt for duplicate detection. */
data class ReceiptFingerprint(
    val receiptId: Long? = null,
    /** SHA-256 of each original file. */
    val documentHashes: Set<String> = emptySet(),
    /** 64-bit difference hashes of the page images (robust to re-encoding). */
    val imageHashes: List<Long> = emptyList(),
    val storeKey: String? = null,
    val purchasedAt: LocalDateTime? = null,
    val hasTime: Boolean = false,
    val total: Money? = null,
    val receiptNumber: String? = null,
    val apCode: String? = null,
    /** Hash of the sorted item lines (raw text + amount). */
    val itemSignature: String? = null,
)

enum class DuplicateReason { SAME_DOCUMENT, SAME_IMAGE, SAME_RECEIPT_NUMBER, SAME_CONTENT, SAME_METADATA }

data class DuplicateMatch(val receiptId: Long?, val reason: DuplicateReason, val score: Double)

/**
 * Detects re-imports of the same receipt. Uses, in decreasing order of certainty:
 * identical file hash, near-identical page image, cash-register identifiers (AP code + receipt number),
 * identical item list, and finally store + date/time + total.
 * The caller shows a warning; the user may always override it.
 */
object DuplicateDetector {

    fun itemSignature(lines: List<Pair<String, Money>>): String? {
        if (lines.isEmpty()) return null
        val canonical = lines.map { (name, amount) -> HungarianText.matchKey(name) + "|" + amount.minor }.sorted().joinToString("\n")
        return Hashing.sha256Hex(canonical)
    }

    fun find(candidate: ReceiptFingerprint, existing: List<ReceiptFingerprint>): DuplicateMatch? =
        existing.mapNotNull { compare(candidate, it) }.maxByOrNull { it.score }

    fun compare(a: ReceiptFingerprint, b: ReceiptFingerprint): DuplicateMatch? {
        if (a.receiptId != null && a.receiptId == b.receiptId) return null
        if (a.documentHashes.isNotEmpty() && a.documentHashes.any { it in b.documentHashes }) {
            return DuplicateMatch(b.receiptId, DuplicateReason.SAME_DOCUMENT, 1.0)
        }
        if (a.imageHashes.isNotEmpty() && b.imageHashes.isNotEmpty()) {
            val close = a.imageHashes.any { x -> b.imageHashes.any { y -> Hashing.hammingDistance(x, y) <= 4 } }
            if (close) return DuplicateMatch(b.receiptId, DuplicateReason.SAME_IMAGE, 0.95)
        }
        if (!a.apCode.isNullOrBlank() && a.apCode == b.apCode && !a.receiptNumber.isNullOrBlank() &&
            normalizeNumber(a.receiptNumber) == normalizeNumber(b.receiptNumber)) {
            return DuplicateMatch(b.receiptId, DuplicateReason.SAME_RECEIPT_NUMBER, 0.97)
        }
        val sameTotal = a.total != null && a.total == b.total
        if (a.itemSignature != null && a.itemSignature == b.itemSignature && sameTotal) {
            val sameDay = a.purchasedAt == null || b.purchasedAt == null || a.purchasedAt.toLocalDate() == b.purchasedAt.toLocalDate()
            if (sameDay) return DuplicateMatch(b.receiptId, DuplicateReason.SAME_CONTENT, 0.9)
        }
        if (sameTotal && a.purchasedAt != null && b.purchasedAt != null &&
            (a.storeKey == null || b.storeKey == null || a.storeKey == b.storeKey)) {
            if (a.purchasedAt.toLocalDate() != b.purchasedAt.toLocalDate()) return null
            return if (a.hasTime && b.hasTime) {
                val minutes = kotlin.math.abs(Duration.between(a.purchasedAt, b.purchasedAt).toMinutes())
                if (minutes <= 2) DuplicateMatch(b.receiptId, DuplicateReason.SAME_METADATA, 0.85) else null
            } else DuplicateMatch(b.receiptId, DuplicateReason.SAME_METADATA, 0.6)
        }
        return null
    }

    /** `0123/00456` and `123/456` are the same receipt number: compare digit groups without leading zeros. */
    private fun normalizeNumber(s: String?): String =
        s.orEmpty().split(Regex("\\D+")).filter { it.isNotEmpty() }.joinToString("/") { it.trimStart('0').ifEmpty { "0" } }
}

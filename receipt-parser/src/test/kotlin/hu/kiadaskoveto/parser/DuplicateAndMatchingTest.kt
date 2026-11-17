package hu.kiadaskoveto.parser

import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.parser.duplicate.DuplicateDetector
import hu.kiadaskoveto.parser.duplicate.DuplicateReason
import hu.kiadaskoveto.parser.duplicate.ReceiptFingerprint
import hu.kiadaskoveto.parser.matching.MatchStrength
import hu.kiadaskoveto.parser.matching.ProductCandidate
import hu.kiadaskoveto.parser.matching.ProductMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DuplicateAndMatchingTest {

    private val t = LocalDateTime.of(2024, 3, 15, 14, 32)

    @Test fun `same file is a certain duplicate`() {
        val m = DuplicateDetector.compare(
            ReceiptFingerprint(documentHashes = setOf("abc")),
            ReceiptFingerprint(receiptId = 7, documentHashes = setOf("abc", "def")),
        )!!
        assertEquals(DuplicateReason.SAME_DOCUMENT, m.reason)
        assertEquals(7L, m.receiptId)
    }

    @Test fun `re-photographed receipt is detected by perceptual hash`() {
        val m = DuplicateDetector.compare(
            ReceiptFingerprint(imageHashes = listOf(0b1011_0110L)),
            ReceiptFingerprint(receiptId = 1, imageHashes = listOf(0b1011_0111L)),
        )
        assertEquals(DuplicateReason.SAME_IMAGE, m?.reason)
    }

    @Test fun `cash register identifiers identify the receipt`() {
        val a = ReceiptFingerprint(apCode = "A12345678", receiptNumber = "0123/00456")
        val b = ReceiptFingerprint(receiptId = 2, apCode = "A12345678", receiptNumber = "123/456")
        assertEquals(DuplicateReason.SAME_RECEIPT_NUMBER, DuplicateDetector.compare(a, b)?.reason)
    }

    @Test fun `same store, time and total is a likely duplicate`() {
        val a = ReceiptFingerprint(storeKey = "spar", purchasedAt = t, hasTime = true, total = Money.ofForint(3369))
        val b = a.copy(receiptId = 3, purchasedAt = t.plusMinutes(1))
        assertEquals(DuplicateReason.SAME_METADATA, DuplicateDetector.compare(a, b)?.reason)
        // Same total an hour later is a different shopping trip
        assertNull(DuplicateDetector.compare(a, b.copy(purchasedAt = t.plusHours(1))))
        // Different store is not a duplicate
        assertNull(DuplicateDetector.compare(a, b.copy(storeKey = "lidl")))
    }

    @Test fun `identical item list on the same day is detected`() {
        val sig = DuplicateDetector.itemSignature(listOf("TEJ 1L" to Money.ofForint(329), "KENYER" to Money.ofForint(549)))
        val sig2 = DuplicateDetector.itemSignature(listOf("KENYER" to Money.ofForint(549), "tej 1l" to Money.ofForint(329)))
        assertEquals(sig, sig2)
        val a = ReceiptFingerprint(itemSignature = sig, total = Money.ofForint(878), purchasedAt = t)
        assertEquals(DuplicateReason.SAME_CONTENT, DuplicateDetector.compare(a, a.copy(receiptId = 9))?.reason)
    }

    @Test fun `the receipt itself is never its own duplicate`() {
        val a = ReceiptFingerprint(receiptId = 5, documentHashes = setOf("x"))
        assertNull(DuplicateDetector.compare(a, a))
    }

    private val milkSpar = ProductCandidate(1, "Tej ESL 2,8%", "Tej", null, null, mapOf("TEJ ESL 2,8% 1L" to "spar"))
    private val milkLidl = ProductCandidate(2, "Friss tej 2,8%", "Tej", "Pilos", null, mapOf("FRISS TEJ 2,8% 1L" to "lidl"))

    @Test fun `only confirmed aliases link automatically`() {
        assertEquals(1L, ProductMatcher.autoLink("TEJ ESL 2,8% 1L", "spar", listOf(milkSpar, milkLidl))?.productId)
        // Same text at another store: suggestion only
        assertNull(ProductMatcher.autoLink("TEJ ESL 2,8% 1L", "tesco", listOf(milkSpar)))
        assertEquals(MatchStrength.ALIAS_OTHER_STORE, ProductMatcher.match("TEJ ESL 2,8% 1L", "tesco", listOf(milkSpar)).first().strength)
    }

    @Test fun `similar names are suggested but never merged`() {
        val results = ProductMatcher.match("TEJ ESL 2,8% 1 L", "spar", listOf(milkSpar, milkLidl))
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.strength == MatchStrength.SIMILAR })
        assertNull(ProductMatcher.autoLink("FRISS TEJ 2,8%", "spar", listOf(milkSpar, milkLidl)))
    }
}

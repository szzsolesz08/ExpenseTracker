package hu.kiadaskoveto.parser

import hu.kiadaskoveto.core.money.MeasureUnit
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.money.Quantity
import hu.kiadaskoveto.parser.categorize.CategoryKeys
import hu.kiadaskoveto.parser.model.IssueCode
import hu.kiadaskoveto.parser.model.ItemKind
import hu.kiadaskoveto.parser.model.OcrInput
import hu.kiadaskoveto.parser.model.OcrLine
import hu.kiadaskoveto.parser.model.ParseStage
import hu.kiadaskoveto.parser.model.ParsedItem
import hu.kiadaskoveto.parser.model.ParsedReceipt
import hu.kiadaskoveto.parser.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** End-to-end parsing of Hungarian sample receipts (test fixtures only). */
class ReceiptFixturesTest {

    private val parser = ReceiptParser(today = { LocalDate.of(2024, 12, 31) })

    private fun parse(name: String): ParsedReceipt {
        val text = javaClass.getResourceAsStream("/receipts/$name")!!.bufferedReader(Charsets.UTF_8).readText()
        return parser.parse(OcrInput.fromText(text))
    }

    private fun ParsedReceipt.item(nameContains: String): ParsedItem =
        items.firstOrNull { it.rawName.contains(nameContains, ignoreCase = true) }
            ?: error("No item containing '$nameContains' in ${items.map { it.rawName }}")

    private fun ft(v: Long) = Money.ofForint(v)

    @Test fun `SPAR receipt with weighed products, deposit, multi-quantity and discount`() {
        val r = parse("spar.txt")
        assertEquals("spar", r.storeProfileId)
        assertEquals(LocalDateTime.of(2024, 3, 15, 14, 32), r.purchasedAt)
        assertTrue(r.dateHasTime)
        assertEquals(ft(3369), r.printedTotal)
        assertEquals(ft(3369), r.computedTotal)
        assertEquals(true, r.totalMatches)
        assertEquals(PaymentMethod.CARD, r.paymentMethod)
        assertEquals("A12345678", r.apCode)
        assertEquals("12345678-2-42", r.taxNumber)
        assertEquals(6, r.items.size)

        val milk = r.item("TEJ ESL")
        assertEquals(ft(329), milk.lineTotal)
        assertNull("quantity was not printed", milk.quantity)
        assertEquals("C00", milk.vatCode)
        assertEquals(CategoryKeys.DAIRY, milk.category.key)
        assertEquals("Tej", milk.interpretation.productType)

        val cheese = r.item("TRAP SAJT")
        assertEquals(Quantity(600), cheese.quantity)
        assertEquals(MeasureUnit.KILOGRAM, cheese.unit)
        assertEquals(ft(2990), cheese.unitPrice)
        assertEquals(ft(1794), cheese.lineTotal)
        assertEquals("Trappista sajt", cheese.interpretation.productType)
        assertEquals(CategoryKeys.DAIRY, cheese.category.key)

        val banana = r.item("BANAN")
        assertEquals(Quantity(846), banana.quantity)
        assertEquals(ft(422), banana.lineTotal)
        assertEquals(CategoryKeys.PRODUCE, banana.category.key)
        assertEquals("Banán", banana.interpretation.displayName)

        val cola = r.item("COCA COLA")
        assertEquals("Coca-Cola", cola.interpretation.brand)
        assertEquals(CategoryKeys.DRINKS, cola.category.key)
        assertEquals("1,5 l", cola.interpretation.packageSize?.format())

        val deposit = r.items.single { it.kind == ItemKind.DEPOSIT }
        assertEquals(ft(50), deposit.lineTotal)

        val kifli = r.item("KIFLI")
        assertEquals(Quantity.ofWhole(5), kifli.quantity)
        assertEquals(ft(45), kifli.unitPrice)
        assertEquals("line total from the quantity line", ft(225), kifli.lineTotal)
        assertEquals(ft(-100), kifli.discount)
        assertEquals(ft(125), kifli.finalPrice)
        assertEquals(CategoryKeys.BAKERY, kifli.category.key)
    }

    @Test fun `Lidl receipt with OCR mistake, repeated items, subtotal and receipt-level coupon`() {
        val r = parse("lidl.txt")
        assertEquals("lidl", r.storeProfileId)
        assertEquals(ft(2584), r.printedTotal)
        assertEquals(true, r.totalMatches)
        assertEquals(LocalDate.of(2024, 4, 2), r.purchasedAt?.toLocalDate())

        val milk = r.item("tej 1,5%")
        assertEquals(Quantity.ofWhole(2), milk.quantity)
        assertEquals(ft(259), milk.unitPrice)
        assertEquals(ft(518), milk.lineTotal)
        assertEquals(CategoryKeys.DAIRY, milk.category.key)

        val bread = r.item("kenyér")
        assertEquals(ft(-40), bread.discount)
        assertEquals(ft(359), bread.finalPrice)

        val yoghurts = r.items.filter { it.rawName.contains("Joghurt") }
        assertEquals("repeated products are kept as separate lines", 2, yoghurts.size)
        yoghurts.forEach { assertEquals(CategoryKeys.DAIRY, it.category.key) }

        val paprika = r.item("Paprika")
        assertEquals(Quantity(562), paprika.quantity)
        assertEquals(ft(561), paprika.lineTotal)
        assertEquals(CategoryKeys.PRODUCE, paprika.category.key)

        val coupon = r.items.single { it.kind == ItemKind.DISCOUNT }
        assertEquals(ft(-200), coupon.lineTotal)
        assertEquals(1, r.items.count { it.kind == ItemKind.DEPOSIT })
        assertEquals(CategoryKeys.DRINKS, r.item("ásványvíz").category.key)
    }

    @Test fun `ALDI receipt with comma decimals, name-then-quantity layout, cash rounding`() {
        val r = parse("aldi.txt")
        assertEquals("aldi", r.storeProfileId)
        assertEquals(ft(5313), r.printedTotal)
        assertEquals(ft(5315), r.payableTotal)
        assertEquals(ft(2), r.rounding)
        assertEquals(ft(5313), r.computedTotal)
        assertEquals(true, r.totalMatches)
        assertEquals(PaymentMethod.CASH, r.paymentMethod)
        assertEquals(LocalDateTime.of(2024, 5, 15, 9, 12), r.purchasedAt)

        val sourCream = r.item("tejföl")
        assertEquals(Quantity.ofWhole(2), sourCream.quantity)
        assertEquals(ft(369), sourCream.unitPrice)
        assertEquals(ft(738), sourCream.lineTotal)
        assertEquals(CategoryKeys.DAIRY, sourCream.category.key)

        assertEquals(CategoryKeys.MEAT, r.item("Csirkemell").category.key)
        assertEquals(CategoryKeys.DAIRY, r.item("Tojás").category.key)
        assertEquals("10 db", r.item("Tojás").interpretation.packageSize?.format())
        assertEquals(CategoryKeys.ALCOHOL, r.item("sör").category.key)
        val dish = r.item("Mosogatószer")
        assertEquals(CategoryKeys.CLEANING, dish.category.key)
        assertEquals(ft(-100), dish.discount)
    }

    @Test fun `Tesco receipt with VAT column, repaired amount and unit price on item line`() {
        val r = parse("tesco.txt")
        assertEquals("tesco", r.storeProfileId)
        assertEquals(ft(6103), r.printedTotal)
        assertEquals(true, r.totalMatches)
        assertEquals(LocalDateTime.of(2024, 6, 1, 10, 45), r.purchasedAt)

        val ham = r.item("SONKA")
        assertEquals("SONKA line keeps its VAT letter separate", "C", ham.vatCode)
        assertFalse(ham.rawName.endsWith(" C"))
        assertEquals(ft(-200), ham.discount)

        val choc = r.item("MILKA")
        assertEquals(ft(459), choc.lineTotal)
        assertTrue(choc.issues.any { it.code == IssueCode.AMOUNT_REPAIRED })
        assertTrue("repaired amounts always need review", choc.needsReview)
        assertEquals(CategoryKeys.SWEETS, choc.category.key)
        assertEquals("Milka", choc.interpretation.brand)

        assertEquals(ft(1799), r.item("WC PAPIR").lineTotal)
        assertEquals(CategoryKeys.HOUSEHOLD, r.item("WC PAPIR").category.key)
        assertEquals(CategoryKeys.CLEANING, r.item("OBLITO").category.key)
        assertEquals(CategoryKeys.PET, r.item("PEDIGREE").category.key)

        val beer = r.item("HEINEKEN")
        assertEquals(ft(798), beer.lineTotal)
        assertEquals(ft(399), beer.unitPrice)
        assertEquals(Quantity.ofWhole(2), beer.quantity)
        assertEquals(CategoryKeys.ALCOHOL, beer.category.key)
    }

    @Test fun `generic shop receipt with unknown product`() {
        val r = parse("generic.txt")
        assertNull(r.storeProfileId)
        assertEquals("Minta Élelmiszer Kft.", r.storeName)
        assertEquals(ft(2155), r.printedTotal)
        assertEquals(true, r.totalMatches)
        assertEquals(PaymentMethod.CASH, r.paymentMethod)
        assertEquals(5, r.items.size)
        assertEquals(LocalDate.of(2024, 2, 29), r.purchasedAt?.toLocalDate())
        val unknown = r.item("Ismeretlen")
        assertEquals(CategoryKeys.UNCATEGORIZED, unknown.category.key)
        assertTrue(unknown.needsReview)
        assertTrue(r.issues.any { it.code == IssueCode.STORE_UNKNOWN })
        assertEquals(Quantity(945), r.item("Alma").quantity)
    }

    @Test fun `PENNY receipt with OCR noise in total keyword and date`() {
        val r = parse("penny_ocr_noise.txt")
        assertEquals("penny", r.storeProfileId)
        assertEquals(ft(5635), r.printedTotal)
        assertEquals(true, r.totalMatches)
        assertEquals(LocalDateTime.of(2024, 7, 20, 17, 40), r.purchasedAt)
        assertEquals(CategoryKeys.MEAT, r.item("CSIRKEMELLF").category.key)
        assertEquals(2, r.items.count { it.rawName.startsWith("JOGH") })
        r.items.filter { it.rawName.startsWith("JOGH") }.forEach {
            assertEquals(CategoryKeys.DAIRY, it.category.key)
            assertEquals("Joghurt", it.interpretation.productType)
        }
        assertEquals(CategoryKeys.CLEANING, r.item("MOSOPOR").category.key)
        assertEquals(CategoryKeys.PRODUCE, r.item("PARADICS").category.key)
    }

    @Test fun `damaged store name is still recognized`() {
        val r = parse("spar_damaged_header.txt")
        assertEquals("spar", r.storeProfileId)
        assertEquals(true, r.totalMatches)
        assertEquals(PaymentMethod.CASH, r.paymentMethod)
    }

    @Test fun `INTERSPAR is not confused with SPAR`() {
        assertEquals("interspar", parse("interspar.txt").storeProfileId)
    }

    @Test fun `total mismatch is reported, not hidden`() {
        val text = """
            SPAR MAGYARORSZAG KFT
            TEJ 1L        C00   329
            KENYER        C00   549
            ÖSSZESEN:          1 000
            2024.01.10 10:00
        """.trimIndent()
        val r = parser.parse(OcrInput.fromText(text))
        assertEquals(false, r.totalMatches)
        assertEquals(ft(878), r.computedTotal)
        assertEquals(ft(1000), r.effectiveTotal)
        assertTrue(r.issues.any { it.code == IssueCode.TOTAL_MISMATCH })
        assertTrue(r.needsReview)
    }

    @Test fun `missing date and total are reported, never invented`() {
        val r = parser.parse(OcrInput.fromText("Valami bolt\nKENYER 549\nTEJ 329"))
        assertNull(r.purchasedAt)
        assertNull(r.printedTotal)
        assertEquals(ft(878), r.computedTotal)
        assertTrue(r.issues.any { it.code == IssueCode.DATE_MISSING })
        assertTrue(r.issues.any { it.code == IssueCode.TOTAL_MISSING })
    }

    @Test fun `future dates are flagged as implausible`() {
        val r = parser.parse(OcrInput.fromText("BOLT\nKENYER 549\nÖSSZESEN 549\n2031.01.01 10:00"))
        assertNotNull(r.purchasedAt)
        assertTrue(r.issues.any { it.code == IssueCode.DATE_IMPLAUSIBLE })
    }

    @Test fun `all ten stages are reported in order`() {
        val stages = ArrayList<ParseStage>()
        parser.parse(OcrInput.fromText("BOLT\nKENYER 549\nÖSSZESEN 549"), onStage = { stages += it })
        assertEquals(ParseStage.entries.toList(), stages)
    }

    /** Verbatim Tesseract output from the emulator for a photographed SPAR receipt. */
    @Test fun `real on-device OCR output of a SPAR photo`() {
        val r = parse("spar_real_ocr.txt")
        assertEquals("spar", r.storeProfileId)
        assertEquals(ft(3369), r.printedTotal)
        assertEquals(LocalDateTime.of(2024, 3, 15, 14, 32), r.purchasedAt)
        assertEquals("A12345678", r.apCode)
        assertEquals(6, r.items.size)
        // OCR junk and mangled VAT codes do not leak into names
        assertEquals("TRAP SAJT KG", r.item("TRAP").rawName)
        assertEquals("C", r.item("TRAP").vatCode?.take(1))
        assertEquals("KIFLI VAJAS 606G", r.item("KIFLI").rawName)
        // "%" read as "X" is restored
        assertTrue(r.item("TEJ").rawName.contains("2,8%"))
        assertEquals(CategoryKeys.DAIRY, r.item("TEJ").category.key)
        // The misread discount (-190 instead of -100) is not hidden: the total mismatch is reported.
        assertEquals(ft(-190), r.item("KIFLI").discount)
        assertEquals(false, r.totalMatches)
        assertTrue(r.issues.any { it.code == IssueCode.TOTAL_MISMATCH && it.detail?.startsWith("90") == true })
    }

    @Test fun `original OCR lines are preserved on every item`() {
        val r = parse("spar.txt")
        val cheese = r.item("TRAP SAJT")
        assertTrue(cheese.originalText.contains("TRAP SAJT KG"))
        assertTrue(cheese.originalText.contains("0,600 kg x 2.990 Ft/kg"))
        assertTrue(r.item("KIFLI").originalText.contains("SPAR PLUSZ KEDVEZMÉNY"))
    }

    @Test fun `OCR noise from a photo without a receipt is not turned into a store name`() {
        // Real Tesseract output of an emulator camera photo of a room (no receipt in frame).
        val noise = listOf("ed", "eze)", "----a  —")
        val r = parser.parse(OcrInput(noise.map { OcrLine(it, 0.36) }))
        assertNull("noise must not become a store name", r.storeName)
        assertTrue(r.items.isEmpty())
        assertNull(r.printedTotal)
        assertNull(r.purchasedAt)
    }

    @Test fun `unknown shop name is kept when the header line is a confident real word`() {
        val r = parser.parse(OcrInput(listOf("Kovács Pékség", "Kenyér 1kg        649", "ÖSSZESEN:        649").map { OcrLine(it, 0.9) }))
        assertEquals("Kovács Pékség", r.storeName)
    }

    @Test fun `year misread by OCR is repaired and flagged for review`() {
        // Real on-device OCR read "2024.03.15" as "2924.03.15".
        val r = parser.parse(OcrInput(listOf("SPAR", "TEJ 1L        329", "ÖSSZESEN:        329", "2924.03.15 14:32").map { OcrLine(it, 0.9) }))
        assertEquals(LocalDateTime.of(2024, 3, 15, 14, 32), r.purchasedAt)
        assertTrue(r.issues.any { it.code == IssueCode.DATE_REPAIRED })
        val dmy = parser.parse(OcrInput(listOf("SPAR", "TEJ 1L        329", "15.03.2824").map { OcrLine(it, 0.9) }))
        assertEquals(LocalDate.of(2024, 3, 15), dmy.purchasedAt?.toLocalDate())
        // A correctly printed date never carries the repair flag.
        assertTrue(parse("spar.txt").issues.none { it.code == IssueCode.DATE_REPAIRED })
    }

    @Test fun `VAT code read with 8 instead of 0 stays out of the product name`() {
        // Real on-device OCR of the SPAR photo fixture: "C00" came out as "co8" / "Ce8".
        val r = parser.parse(OcrInput(listOf(
            "SPAR MAGYARORSZÁG KERESKEDELMI KFT.",
            "TEJ ESL 2,8% 1. co8              329",
            "TRAP SAJT KG Ce8               1.794",
            "0,600 kg x 2.990 Ft/kg",
            "ÖSSZESEN:                      2.123",
        ).map { OcrLine(it, 0.85) }))
        assertEquals(2, r.items.size)
        assertTrue(r.items.none { it.interpretation.displayName.contains("co8", ignoreCase = true) || it.rawName.contains("ce8", ignoreCase = true) })
        assertEquals("C00", r.items.first().vatCode)
    }

    @Test fun `real OCR of a Tesco PDF keeps the quantity line and reports the misread amount`() {
        // Tesseract output of an e-receipt PDF: "x" read as "Xx", "459" read as "4599".
        val r = parse("tesco_pdf_real_ocr.txt")
        val beer = r.item("HEINEKEN")
        assertEquals(ft(798), beer.lineTotal)
        assertEquals(ft(399), beer.unitPrice)
        assertTrue("no item made of the quantity line", r.items.none { it.rawName.startsWith("2 X", ignoreCase = true) })
        assertEquals(ft(1799), r.item("WC PAPIR").lineTotal)
        assertEquals(ft(6103), r.printedTotal)
        // The OCR misread is not silently fixed: the difference is reported for review.
        assertEquals(false, r.totalMatches)
        assertTrue(r.issues.any { it.code == IssueCode.TOTAL_MISMATCH })
        assertEquals(ft(10243), r.computedTotal)
    }
}

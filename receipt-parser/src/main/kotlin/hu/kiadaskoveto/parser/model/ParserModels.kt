package hu.kiadaskoveto.parser.model

import hu.kiadaskoveto.core.money.MeasureUnit
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.money.PackageSize
import hu.kiadaskoveto.core.money.Quantity
import java.time.LocalDateTime

/** One line of OCR output. [confidence] is in [0, 1]. */
data class OcrLine(val text: String, val confidence: Double = 1.0, val page: Int = 0)

/** Stage 1 output: raw OCR text, line by line, in reading order. */
data class OcrInput(val lines: List<OcrLine>) {
    val fullText: String get() = lines.joinToString("\n") { it.text }

    companion object {
        /** Builds input from plain text (tests, digital receipts). Confidence is 1.0. */
        fun fromText(text: String): OcrInput =
            OcrInput(text.lines().filter { it.isNotBlank() }.map { OcrLine(it, 1.0) })
    }
}

/** Pipeline stages, reported to the UI as progress. */
enum class ParseStage(val number: Int) {
    RAW_TEXT(1), STRUCTURE(2), STORE(3), DATE(4), PRODUCT_LINES(5),
    QUANTITIES(6), DISCOUNTS(7), TOTAL_CHECK(8), CATEGORIES(9), CONFIDENCE(10)
}

enum class LineType {
    HEADER, ITEM, NAME_ONLY, QUANTITY, DISCOUNT, DEPOSIT, DEPOSIT_RETURN,
    SUBTOTAL, TOTAL, ROUNDING, PAYMENT, CHANGE, VAT, FOOTER, SEPARATOR, UNKNOWN
}

enum class ItemKind { PRODUCT, DISCOUNT, DEPOSIT, DEPOSIT_RETURN }

enum class PaymentMethod { CARD, CASH, SZEP_CARD, VOUCHER, MIXED }

enum class CategorySource { PRODUCT_MAPPING, USER_MAPPING, USER_RULE, DICTIONARY, FUZZY_DICTIONARY, LOCAL_AI, FALLBACK }

data class CategoryGuess(
    val key: String,
    val confidence: Double,
    val source: CategorySource,
    val matchedKeyword: String? = null,
)

/** Interpretation of an abbreviated merchant description. Never presented as certain unless confirmed. */
data class ProductInterpretation(
    val displayName: String,
    val productType: String?,
    val brand: String?,
    val variant: String?,
    val packageSize: PackageSize?,
    /** 0..1 — how much of the description was explained by the dictionary. */
    val certainty: Double,
    /** True when the product is sold by weight (kg price line or KG token). */
    val soldByWeight: Boolean = false,
)

enum class IssueCode {
    LOW_OCR_CONFIDENCE,
    AMOUNT_REPAIRED,
    QUANTITY_PRICE_MISMATCH,
    DISCOUNT_SIGN_ASSUMED,
    NAME_UNCERTAIN,
    CATEGORY_UNCERTAIN,
    STORE_UNKNOWN,
    DATE_MISSING,
    DATE_IMPLAUSIBLE,
    DATE_REPAIRED,
    TOTAL_MISSING,
    TOTAL_MISMATCH,
    NO_ITEMS,
    UNPARSED_LINES,
}

data class ParseIssue(val code: IssueCode, val detail: String? = null)

data class ParsedItem(
    /** All OCR lines that produced this item, verbatim, joined with `\n`. */
    val originalText: String,
    /** Merchant description as printed (whitespace normalized). */
    val rawName: String,
    val kind: ItemKind,
    /** Printed quantity; null when not printed (one unit implied, but not stored as a fact). */
    val quantity: Quantity?,
    val unit: MeasureUnit?,
    /** Unit price only when it was printed on the receipt. */
    val unitPrice: Money?,
    /** Line total exactly as printed. */
    val lineTotal: Money,
    /** Discounts attached to this line (zero or negative). */
    val discount: Money = Money.ZERO,
    val discountTexts: List<String> = emptyList(),
    val vatCode: String? = null,
    val interpretation: ProductInterpretation,
    val category: CategoryGuess,
    val ocrConfidence: Double,
    val confidence: Double = 0.0,
    val issues: List<ParseIssue> = emptyList(),
) {
    val finalPrice: Money get() = lineTotal + discount
    val needsReview: Boolean
        get() = confidence < REVIEW_THRESHOLD || issues.any { it.code in BLOCKING_ISSUES }

    companion object {
        const val REVIEW_THRESHOLD = 0.6
        val BLOCKING_ISSUES = setOf(
            IssueCode.QUANTITY_PRICE_MISMATCH,
            IssueCode.AMOUNT_REPAIRED,
            IssueCode.DISCOUNT_SIGN_ASSUMED,
            IssueCode.CATEGORY_UNCERTAIN,
            IssueCode.LOW_OCR_CONFIDENCE,
        )
    }
}

data class VatLine(val code: String?, val ratePercent: String?, val amount: Money?, val originalText: String)

data class ClassifiedLine(val index: Int, val line: OcrLine, val type: LineType)

data class ParsedReceipt(
    val storeProfileId: String?,
    val storeName: String?,
    val storeConfidence: Double,
    val companyName: String?,
    val address: String?,
    val taxNumber: String?,
    val purchasedAt: LocalDateTime?,
    val dateHasTime: Boolean,
    val items: List<ParsedItem>,
    val printedTotal: Money?,
    val payableTotal: Money?,
    val rounding: Money?,
    val computedTotal: Money,
    val totalMatches: Boolean?,
    val paymentMethod: PaymentMethod?,
    val receiptNumber: String?,
    val apCode: String?,
    val vatLines: List<VatLine>,
    val unparsedLines: List<OcrLine>,
    val classifiedLines: List<ClassifiedLine>,
    val confidence: Double,
    val issues: List<ParseIssue>,
    val rawText: String,
) {
    /** The total that should be recorded: the printed total when available, otherwise the computed sum. */
    val effectiveTotal: Money get() = printedTotal ?: payableTotal ?: computedTotal
    val needsReview: Boolean get() = confidence < 0.75 || items.any { it.needsReview } || totalMatches != true
}

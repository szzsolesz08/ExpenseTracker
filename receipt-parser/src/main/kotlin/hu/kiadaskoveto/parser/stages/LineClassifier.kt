package hu.kiadaskoveto.parser.stages

import hu.kiadaskoveto.core.text.Fuzzy
import hu.kiadaskoveto.parser.config.Keywords
import hu.kiadaskoveto.parser.config.ParserConfig
import hu.kiadaskoveto.parser.config.StoreProfile
import hu.kiadaskoveto.parser.model.LineType

/**
 * Stage 2: receipt structure detection. Splits the receipt into header, item
 * section and footer, and labels each line. Works on accent-folded match keys.
 */
class LineClassifier(private val config: ParserConfig, private val profile: StoreProfile) {

    private val kw = config.keywords
    private val discountWords = kw.discount + profile.discountKeywords
    private val strongDiscountWords = discountWords - setOf("AKCIO", "BONUSZ", "SZTORNO")
    private val depositWords = kw.deposit + profile.depositKeywords
    private val totalWords = kw.total
    private val payableWords = kw.payable
    private val streetSuffixes = kw.streetSuffixes.toSet()

    private val separator = Regex("^[-=_*.#~+ ]{3,}$")
    private val taxNumber = Regex("\\b\\d{8}-\\d-\\d{2}\\b")
    private val postalCity = Regex("^(?:H-?)?\\d{4}\\s*,?\\s*[A-Z]")
    private val phone = Regex("(?:TEL|TELEFON)[: ]|\\+36|\\b06[ -]?\\d")
    private val dateOnly = Regex("^\\d{2,4}[.\\-/ ]\\s?\\d{1,2}[.\\-/ ]\\s?\\d{1,4}\\.?(?:\\s+\\d{1,2}[:.]\\d{2}(?:[:.]\\d{2})?)?$")
    private val vatSummary = Regex("^[A-E](?:00)?\\s+\\d{1,2}(?:[.,]\\d{1,2})?\\s?%")
    private val totalHeads = (totalWords + payableWords).map { it.split(' ').first() }.filter { it.length >= 6 }.toSet()

    private enum class Phase { HEADER, ITEMS, FOOTER }

    fun classify(lines: List<PreparedLine>): List<LineType> {
        var phase = Phase.HEADER
        val result = ArrayList<LineType>(lines.size)
        lines.forEachIndexed { i, line ->
            val type = when (phase) {
                Phase.HEADER -> {
                    val asItem = itemSectionType(line)
                    if (startsItems(line, asItem)) {
                        phase = Phase.ITEMS
                        // "TEJ 1L" + "2 x 329  658": the name line above the first quantity line is an item name.
                        if (asItem == LineType.QUANTITY && i > 0 && result[i - 1] == LineType.HEADER &&
                            lines[i - 1].hasLetters && lines[i - 1].trailing == null && !looksLikeHeader(lines[i - 1])) {
                            result[i - 1] = LineType.NAME_ONLY
                        }
                        asItem
                    } else headerType(line)
                }
                Phase.ITEMS -> itemSectionType(line)
                Phase.FOOTER -> footerType(line)
            }
            if (type == LineType.TOTAL || type == LineType.PAYMENT || type == LineType.CHANGE) phase = Phase.FOOTER
            result += type
        }
        return result
    }

    private fun startsItems(line: PreparedLine, asItem: LineType): Boolean {
        if (looksLikeHeader(line)) return false
        return when (asItem) {
            LineType.ITEM, LineType.QUANTITY, LineType.DEPOSIT, LineType.TOTAL, LineType.SUBTOTAL -> true
            else -> false
        }
    }

    private fun looksLikeHeader(line: PreparedLine): Boolean {
        val key = line.key
        if (Keywords.containsAny(key, kw.header)) return true
        if (taxNumber.containsMatchIn(line.text)) return true
        if (postalCity.containsMatchIn(key)) return true
        if (phone.containsMatchIn(key)) return true
        // Store name lines are header, but own-brand products ("TESCO FINEST SONKA 899 C") are items.
        if (profile.identifierRegexes.any { it.containsMatchIn(key) } &&
            (line.trailing == null || (line.index < 4 && line.trailing.vatCode == null))) return true
        if (dateOnly.matches(line.text)) return true
        val tokens = key.split(' ')
        // Street address: "VACI UT 12", "IRINYI J. U. 6."
        tokens.forEachIndexed { i, t ->
            if (t.trimEnd('.') in streetSuffixes && i + 1 < tokens.size && tokens[i + 1].trimEnd('.').firstOrNull()?.isDigit() == true) return true
        }
        return false
    }

    private fun headerType(line: PreparedLine): LineType = when {
        separator.matches(line.text) -> LineType.SEPARATOR
        else -> LineType.HEADER
    }

    private fun isTotalLine(key: String): Boolean {
        if (Keywords.startsWithAny(key, totalWords) || Keywords.startsWithAny(key, payableWords)) return true
        val first = key.substringBefore(' ')
        return first.length >= 6 && totalHeads.any { Fuzzy.similarity(first, it) >= 0.8 }
    }

    private fun itemSectionType(line: PreparedLine): LineType {
        val key = line.key
        val amount = line.trailing
        return when {
            line.text.isBlank() || separator.matches(line.text) -> LineType.SEPARATOR
            amount != null && isTotalLine(key) && !Keywords.containsAny(key, kw.subtotal) -> LineType.TOTAL
            Keywords.startsWithAny(key, kw.subtotal) -> LineType.SUBTOTAL
            amount != null && Keywords.startsWithAny(key, kw.rounding) -> LineType.ROUNDING
            amount != null && Keywords.startsWithAny(key, kw.payment.keys) && !Keywords.containsAny(key, discountWords) -> LineType.PAYMENT
            line.quantity != null -> LineType.QUANTITY
            amount != null && Keywords.containsAny(key, kw.depositReturn) -> LineType.DEPOSIT_RETURN
            amount != null && Keywords.containsAny(key, depositWords) -> LineType.DEPOSIT
            amount != null && amount.amount.isNegative -> LineType.DISCOUNT
            amount != null && Keywords.containsAny(key, strongDiscountWords) -> LineType.DISCOUNT
            profile.ignoreKeywords.isNotEmpty() && Keywords.containsAny(key, profile.ignoreKeywords) -> LineType.FOOTER
            amount != null && amount.prefix.any { it.isLetter() } -> LineType.ITEM
            Keywords.startsWithAny(key, kw.footer) -> LineType.FOOTER
            line.hasLetters && line.text.count { it.isLetter() } >= 2 -> LineType.NAME_ONLY
            else -> LineType.UNKNOWN
        }
    }

    private fun footerType(line: PreparedLine): LineType {
        val key = line.key
        val amount = line.trailing
        return when {
            separator.matches(line.text) -> LineType.SEPARATOR
            amount != null && isTotalLine(key) -> LineType.TOTAL
            amount != null && Keywords.startsWithAny(key, kw.rounding) -> LineType.ROUNDING
            Keywords.startsWithAny(key, kw.change) -> LineType.CHANGE
            Keywords.startsWithAny(key, kw.payment.keys) -> LineType.PAYMENT
            vatSummary.containsMatchIn(key) || Keywords.startsWithAny(key, kw.vat) -> LineType.VAT
            else -> LineType.FOOTER
        }
    }
}

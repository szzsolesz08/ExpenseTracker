package hu.kiadaskoveto.parser.stages

import hu.kiadaskoveto.core.money.AmountParser
import hu.kiadaskoveto.core.money.MeasureUnit
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.money.PriceMath
import hu.kiadaskoveto.core.money.Quantity
import hu.kiadaskoveto.core.text.HungarianText
import hu.kiadaskoveto.parser.model.OcrLine

/** Amount printed at the end of a line, with the text before it. */
data class TrailingAmount(
    val amount: Money,
    val vatCode: String?,
    val prefix: String,
    val repaired: Boolean,
)

/** A parsed quantity line such as `2 x 329` or `0,846 kg x 499 Ft/kg`. */
data class QuantityInfo(
    val quantity: Quantity,
    val unit: MeasureUnit?,
    val unitPrice: Money?,
    val priceUnit: MeasureUnit?,
    val total: Money?,
    val vatCode: String?,
) {
    val isConsistent: Boolean?
        get() = if (unitPrice != null && total != null) PriceMath.isConsistent(quantity, unitPrice, total) else null
}

/** Stage 1 → 2 bridge: a cleaned OCR line with pre-computed features. */
data class PreparedLine(
    val index: Int,
    val ocr: OcrLine,
    val text: String,
    val key: String,
    val trailing: TrailingAmount?,
    val quantity: QuantityInfo?,
) {
    val hasLetters: Boolean get() = key.any { it.isLetter() }
}

object LinePreparer {

    private val JUNK_EDGES = charArrayOf('|', '_', '~', '\'', '"', '`', '·', '•', '»', '«')
    // VAT class letter, optionally with "00"; tolerant to OCR reading 0 as o/O/Q/D/e/8 ("c00", "co0", "Ceo", "Co8").
    private val VAT_TOKEN = Regex("^\\*?([A-Ea-e])([0OoQDe8]{2})?\\*?$")
    private val LEADING_JUNK = Regex("^(?:[^\\p{L}\\p{N}\\s]+\\s+)+")
    private val PERCENT_AS_X = Regex("(?<=\\d[.,]\\d{1,2})[Xx×](?=\\s|$)")
    private val GLUED_VAT = Regex("^(-?\\d[\\d.,]*-?)([A-Ea-e])$")
    private val AMOUNT_TOKEN = Regex("^-?\\d+(?:[.,]\\d+)*-?$")
    // A multiplication sign as OCR prints it: "x", "X", "*", "×", or doubled ("Xx").
    internal val MULTIPLY = Regex("^[Xx*×]{1,2}$")

    fun prepare(lines: List<OcrLine>): List<PreparedLine> {
        val texts = lines.map { clean(it.text) }
        // Some registers print the VAT letter in its own column before the price ("SONKA  C  899").
        // A single letter there is only treated as VAT when most priced lines of this receipt share it,
        // so names such as "VITAMIN C 299" keep their letter on other receipts.
        val priced = texts.mapNotNull { trailingAmount(it) }.filter { it.prefix.isNotEmpty() }
        val withLetter = priced.count { SHORT_VAT_TAIL.containsMatchIn(it.prefix) }
        val vatColumn = priced.size >= 2 && withLetter * 2 >= priced.size
        return lines.mapIndexed { i, line ->
            val text = texts[i]
            PreparedLine(
                index = i,
                ocr = line,
                text = text,
                key = HungarianText.matchKey(text),
                trailing = trailingAmount(text, shortVatBeforeAmount = vatColumn),
                quantity = QuantityParser.parse(text),
            )
        }
    }

    private val SHORT_VAT_TAIL = Regex("\\s[A-E]$")

    fun isVatToken(token: String): Boolean = VAT_TOKEN.matches(token)

    /**
     * OCR clean-up that never changes amounts: strips edge junk and leading punctuation-only
     * tokens (". —", "])"), and restores "%" read as "X" after a decimal ("2,8X" → "2,8%").
     */
    fun clean(raw: String): String {
        var t = HungarianText.collapseSpaces(raw.trim().trim(*JUNK_EDGES).trim())
        t = LEADING_JUNK.replace(t, "")
        t = PERCENT_AS_X.replace(t, "%")
        return t
    }

    private fun normalizeVat(token: String): String {
        val m = VAT_TOKEN.matchEntire(token) ?: return token
        return m.groupValues[1].uppercase() + if (m.groupValues[2].isNotEmpty()) "00" else ""
    }

    /** Extracts the amount at the end of a line, tolerant to VAT codes, `Ft` and OCR digit confusions. */
    fun trailingAmount(text: String, shortVatBeforeAmount: Boolean = false): TrailingAmount? {
        val tokens = text.split(' ').filter { it.isNotEmpty() }.toMutableList()
        if (tokens.isEmpty()) return null
        if (tokens.size == 1) {
            // A line holding only an amount (label printed on the previous line).
            val only = tokens[0].removeSuffix("Ft").removeSuffix("ft")
            if (!AMOUNT_TOKEN.matches(only)) return null
            val amount = AmountParser.parseMoney(only) ?: return null
            return TrailingAmount(amount, null, "", false)
        }
        var vat: String? = null
        var repaired = false

        fun popVat(requireLongForm: Boolean) {
            val last = tokens.lastOrNull() ?: return
            val m = VAT_TOKEN.matchEntire(last) ?: return
            if (requireLongForm && m.groupValues[2].isEmpty()) return
            if (tokens.size >= 2 && vat == null) { vat = normalizeVat(last); tokens.removeAt(tokens.lastIndex) }
        }
        popVat(requireLongForm = false)
        if (tokens.lastOrNull()?.equals("Ft", ignoreCase = true) == true) tokens.removeAt(tokens.lastIndex)
        if (tokens.isEmpty()) return null

        var last = tokens.last()
        GLUED_VAT.matchEntire(last)?.let { m ->
            if (vat == null) { vat = m.groupValues[2].uppercase(); last = m.groupValues[1] }
        }
        if (last.endsWith("Ft", ignoreCase = true) && last.length > 2) last = last.dropLast(2)

        if (!AMOUNT_TOKEN.matches(last)) {
            val fixed = HungarianText.repairNumericToken(last)
            if (fixed != last && AMOUNT_TOKEN.matches(fixed) && last.any { it.isDigit() }) { last = fixed; repaired = true } else return null
        }
        tokens[tokens.lastIndex] = last

        // Thousands separated by a space: "1 299" (only when a name remains before it).
        var amountText = last
        var consumed = 1
        if (tokens.size >= 3) {
            val prev = tokens[tokens.size - 2]
            val before = tokens[tokens.size - 3]
            if (prev.length in 1..3 && prev.all { it.isDigit() } && Regex("^\\d{3}(?:,\\d{2})?-?$").matches(last) &&
                before.any { it.isLetter() } && !MULTIPLY.matches(before)) {
                amountText = prev + last
                consumed = 2
            }
        }
        val amount = AmountParser.parseMoney(amountText) ?: return null
        repeat(consumed) { tokens.removeAt(tokens.lastIndex) }
        // VAT code printed before the amount ("TEJ 1L C00 329") — only the unambiguous long form.
        popVat(requireLongForm = !shortVatBeforeAmount)
        val prefix = tokens.joinToString(" ").trim()
        return TrailingAmount(amount, vat, prefix, repaired)
    }
}

object QuantityParser {

    private val HEAD = Regex("^(\\d+(?:[.,]\\d{1,3})?)\\s*(DB|DRB|KG|G|GR|L|CS|CSOMAG|PCS)?\\.?\\s*[X*×][Xx]?\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val NO_X = Regex("^(\\d+(?:[.,]\\d{1,3})?)\\s*(KG|G|L)\\s+(.+?/\\s*(?:KG|L|G).*)$", RegexOption.IGNORE_CASE)
    private val ALONE = Regex("^(\\d+(?:[.,]\\d{1,3})?)\\s*(DB|KG|G|L)\\.?$", RegexOption.IGNORE_CASE)
    private val PRICE_UNIT = Regex("/\\s*(KG|DB|L|G|CS)\\b", RegexOption.IGNORE_CASE)
    private val NUMERIC = Regex("^-?\\d+(?:[.,]\\d+)?-?$")

    fun parse(text: String): QuantityInfo? {
        val t = text.trim().replace('×', 'x')
        ALONE.matchEntire(t)?.let { m ->
            val q = AmountParser.parseQuantity(m.groupValues[1]) ?: return null
            return QuantityInfo(q, MeasureUnit.parse(m.groupValues[2]), null, null, null, null)
        }
        val m = HEAD.matchEntire(t) ?: NO_X.matchEntire(t) ?: return null
        val qty = AmountParser.parseQuantity(m.groupValues[1]) ?: return null
        val unit = MeasureUnit.parse(m.groupValues[2].ifEmpty { null })
        var rest = m.groupValues[3]
        val priceUnit = PRICE_UNIT.find(rest)?.groupValues?.get(1)?.let { MeasureUnit.parse(it) }
        rest = PRICE_UNIT.replace(rest, " ")
            .replace(Regex("\\bFT\\b", RegexOption.IGNORE_CASE), " ")
            .replace("=", " ")
        val tokens = rest.split(' ').filter { it.isNotBlank() }.toMutableList()
        var vat: String? = null
        if (tokens.isNotEmpty() && LinePreparer.isVatToken(tokens.last())) {
            vat = tokens.removeAt(tokens.lastIndex).take(1).uppercase()
        }
        if (tokens.isEmpty() || tokens.size > 4) return null
        val fixed = tokens.map { tok -> if (NUMERIC.matches(tok)) tok else HungarianText.repairNumericToken(tok) }
        if (!fixed.all { NUMERIC.matches(it) }) return null

        val effectiveUnit = unit ?: priceUnit?.takeIf { it == MeasureUnit.KILOGRAM || it == MeasureUnit.LITER }
        val candidates = segmentations(fixed)
        if (candidates.isEmpty()) return null
        val best = candidates.firstOrNull { (price, total) -> total != null && PriceMath.isConsistent(qty, price, total) }
            ?: candidates.firstOrNull { it.second == null }
            ?: candidates.first()
        return QuantityInfo(qty, effectiveUnit, best.first, priceUnit, best.second, vat)
    }

    /** All ways to read the numeric tokens as (unit price) or (unit price, total), allowing "1 299" grouping. */
    private fun segmentations(tokens: List<String>): List<Pair<Money, Money?>> {
        fun groups(from: Int): List<Pair<Money, Int>> {
            val out = ArrayList<Pair<Money, Int>>()
            if (from < tokens.size) AmountParser.parseMoney(tokens[from])?.let { out += it to from + 1 }
            if (from + 1 < tokens.size && tokens[from].length in 1..3 && tokens[from].all { it.isDigit() } &&
                Regex("^\\d{3}(?:,\\d{2})?$").matches(tokens[from + 1])) {
                AmountParser.parseMoney(tokens[from] + tokens[from + 1])?.let { out += it to from + 2 }
            }
            return out
        }
        val result = ArrayList<Pair<Money, Money?>>()
        for ((price, next) in groups(0)) {
            if (next == tokens.size) result += price to null
            for ((total, end) in groups(next)) if (end == tokens.size) result += price to total
        }
        return result
    }
}

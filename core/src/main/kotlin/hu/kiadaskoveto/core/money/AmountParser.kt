package hu.kiadaskoveto.core.money

import java.math.BigDecimal

/**
 * Parses amounts printed on Hungarian receipts. Handles the formats that appear in
 * practice: `1299`, `1 299`, `1.299`, `1.299,00`, `1,299.00`, `-150`, `150-`, `12,5`, `0,456`.
 *
 * Hungarian receipts use the comma as decimal separator and space or dot as
 * thousands separator. Money in HUF never has more than two decimals, so a
 * separator followed by exactly three digits is a thousands separator for money,
 * but a decimal separator for quantities (`0,456 kg`).
 */
object AmountParser {

    private val SPACES = Regex("[\\s\\u00A0\\u202F]")

    /** Parses a money amount. Returns null if the text is not a well-formed amount. */
    fun parseMoney(raw: String): Money? =
        parseDecimal(raw, threeDigitGroupIsDecimal = false)?.let { Money.fromBigDecimal(it) }

    /** Parses a positive quantity (up to three decimals). */
    fun parseQuantity(raw: String): Quantity? =
        parseDecimal(raw, threeDigitGroupIsDecimal = true)?.takeIf { it.signum() > 0 }?.let { Quantity.of(it) }

    fun parseDecimal(raw: String, threeDigitGroupIsDecimal: Boolean): BigDecimal? {
        var s = raw.trim()
            .replace("Ft", "", ignoreCase = true)
            .replace("HUF", "", ignoreCase = true)
            .trim()
        if (s.isEmpty()) return null
        var negative = false
        if (s.first() in "-−–") { negative = true; s = s.substring(1) }
        if (s.endsWith("-")) { negative = true; s = s.dropLast(1) }
        s = SPACES.replace(s, "")
        if (s.isEmpty() || !s.first().isDigit() || !s.last().isDigit()) return null
        if (!s.all { it.isDigit() || it == '.' || it == ',' }) return null

        val dots = s.count { it == '.' }
        val commas = s.count { it == ',' }
        val normalized: String = when {
            dots == 0 && commas == 0 -> s
            dots > 0 && commas > 0 -> {
                val decimalSep = if (s.lastIndexOf(',') > s.lastIndexOf('.')) ',' else '.'
                val groupSep = if (decimalSep == ',') '.' else ','
                if (s.count { it == decimalSep } != 1) return null
                val (intPart, frac) = s.split(decimalSep)
                if (!isGrouped(intPart, groupSep) || frac.length !in 1..3) return null
                intPart.replace(groupSep.toString(), "") + "." + frac
            }
            else -> {
                val sep = if (dots > 0) '.' else ','
                val count = if (dots > 0) dots else commas
                if (count > 1) {
                    if (!isGrouped(s, sep)) return null
                    s.replace(sep.toString(), "")
                } else {
                    val (intPart, frac) = s.split(sep)
                    when (frac.length) {
                        1, 2 -> "$intPart.$frac"
                        3 -> if (threeDigitGroupIsDecimal || intPart == "0") "$intPart.$frac"
                             else if (intPart.length in 1..3) intPart + frac
                             else return null
                        else -> return null
                    }
                }
            }
        }
        return try {
            val value = BigDecimal(normalized)
            if (negative) value.negate() else value
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun isGrouped(s: String, sep: Char): Boolean {
        val parts = s.split(sep)
        if (parts.first().isEmpty() || parts.first().length > 3) return false
        return parts.drop(1).all { it.length == 3 }
    }
}

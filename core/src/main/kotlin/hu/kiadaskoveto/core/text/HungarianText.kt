package hu.kiadaskoveto.core.text

import java.text.Normalizer
import java.util.Locale

/** Text helpers tuned for Hungarian receipts. */
object HungarianText {

    val HU: Locale = Locale.forLanguageTag("hu-HU")

    private val MULTI_SPACE = Regex("\\s+")
    private val COMBINING = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^A-Z0-9%,.]+")

    /** Removes accents: `ŐSZIBARACK` → `OSZIBARACK`, `túró` → `turo`. */
    fun foldAccents(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return COMBINING.replace(decomposed, "")
            // Characters OCR engines sometimes emit for Hungarian double acute letters
            .replace('õ', 'o').replace('Õ', 'O').replace('û', 'u').replace('Û', 'U')
    }

    fun collapseSpaces(text: String): String = MULTI_SPACE.replace(text.trim(), " ")

    /**
     * Canonical key used for matching receipt descriptions: upper case, accents removed,
     * punctuation (except `%`, `,` and `.` inside numbers) turned into single spaces.
     */
    fun matchKey(text: String): String {
        val upper = foldAccents(text).uppercase(Locale.ROOT)
        return collapseSpaces(NON_ALNUM.replace(upper, " ")).trim(' ', '.', ',')
    }

    /** Splits a match key into tokens. */
    fun tokens(text: String): List<String> =
        matchKey(text).split(' ').map { it.trimEnd('.', ',') }.filter { it.isNotBlank() }

    /** `TEJ ESL 2,8%` → `Tej esl 2,8%` (first letter upper, rest lower, Hungarian locale). */
    fun sentenceCase(text: String): String {
        val lower = collapseSpaces(text).lowercase(HU)
        return lower.replaceFirstChar { it.titlecase(HU) }
    }

    /**
     * Fixes typical OCR confusions in a token that is expected to be numeric:
     * `O`→`0`, `l`/`I`/`|`→`1`, `S`→`5`, `B`→`8`, `Z`→`2`, `G`→`6`.
     * Only applied to tokens that are mostly digits already, so words are not damaged.
     */
    fun repairNumericToken(token: String): String {
        if (token.isEmpty()) return token
        val digitish = token.count { it.isDigit() || it in ".,-" }
        if (digitish.toDouble() / token.length < 0.6) return token
        return buildString(token.length) {
            for (c in token) append(
                when (c) {
                    'O', 'o', 'D', 'Q' -> '0'
                    'l', 'I', '|', 'i', '!' -> '1'
                    'S', 's' -> '5'
                    'B' -> '8'
                    'Z', 'z' -> '2'
                    'G' -> '6'
                    else -> c
                }
            )
        }
    }
}

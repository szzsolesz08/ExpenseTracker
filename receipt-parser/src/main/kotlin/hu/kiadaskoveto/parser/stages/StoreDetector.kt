package hu.kiadaskoveto.parser.stages

import hu.kiadaskoveto.core.text.Fuzzy
import hu.kiadaskoveto.core.text.HungarianText
import hu.kiadaskoveto.parser.config.ParserConfig
import hu.kiadaskoveto.parser.config.StoreProfile

data class StoreDetection(
    val profile: StoreProfile,
    val confidence: Double,
    val storeName: String?,
    val companyName: String?,
    val address: String?,
    val taxNumber: String?,
)

/** Stage 3: store identification from configurable identifier patterns, OCR tolerant. */
class StoreDetector(private val config: ParserConfig) {

    private val taxNumber = Regex("\\b(\\d{8}-\\d-\\d{2})\\b")
    private val company = Regex("\\b(KFT|ZRT|NYRT|BT|KFT\\.|ZRT\\.)\\b")
    private val postal = Regex("^(?:H-?)?\\d{4}\\s*,?\\s*[A-Z]")
    private val streetSuffixes = config.keywords.streetSuffixes.toSet()

    fun detect(lines: List<PreparedLine>): StoreDetection {
        val head = lines.take(14)
        val tail = lines.takeLast(10)
        val headKeys = head.map { repairAlpha(it.key) }
        val tailKeys = tail.map { repairAlpha(it.key) }

        var best: StoreProfile? = null
        var bestConfidence = 0.0
        for (profile in config.stores.sortedByDescending { it.priority }) {
            fun hit(keys: List<String>) = keys.any { k ->
                profile.identifierRegexes.any { it.containsMatchIn(k) } && profile.excludeRegexes.none { it.containsMatchIn(k) }
            }
            val confidence = when {
                hit(headKeys) -> 0.95
                hit(tailKeys) -> 0.8
                else -> 0.0
            }
            if (confidence > bestConfidence) { best = profile; bestConfidence = confidence }
        }

        if (best == null) {
            // Fuzzy fallback on chain names for heavily damaged headers ("SPAP", "TESC0").
            val names = config.stores.associateBy { HungarianText.matchKey(it.chain) }
            for (key in headKeys) for (token in key.split(' ').filter { it.length >= 4 }) {
                val match = names.keys.map { it to Fuzzy.similarity(token, it) }.maxByOrNull { it.second } ?: continue
                if (match.second >= 0.75 && match.second * 0.65 > bestConfidence) {
                    best = names[match.first]; bestConfidence = match.second * 0.65
                }
            }
        }

        val companyLine = head.firstOrNull { company.containsMatchIn(it.key) }?.text
        val tax = lines.firstNotNullOfOrNull { taxNumber.find(it.text)?.groupValues?.get(1) }
        val address = head.firstOrNull { isAddress(it.key) }?.text
        val profile = best ?: StoreProfile.GENERIC
        val storeName = when {
            best != null -> best.displayName
            else -> head.firstOrNull { plausibleName(it) }?.text ?: companyLine
        }
        return StoreDetection(
            profile = profile,
            confidence = if (best != null) bestConfidence else if (storeName != null) 0.35 else 0.0,
            storeName = storeName,
            companyName = companyLine,
            address = address,
            taxNumber = tax,
        )
    }

    /**
     * A header line we may show as the shop name when no store profile matched. OCR noise from a
     * photo without a receipt ("eze)", "|;:.") must not become a store name, so the line needs a
     * real word, mostly letters, and reasonable OCR confidence.
     */
    private fun plausibleName(line: PreparedLine): Boolean {
        if (!line.hasLetters || line.trailing != null || isAddress(line.key) || taxNumber.containsMatchIn(line.text)) return false
        if (line.ocr.confidence < 0.5) return false
        val compact = line.text.filterNot { it.isWhitespace() }
        val letters = compact.count { it.isLetter() }
        val longestWord = line.text.split(' ').maxOf { word -> word.count { it.isLetter() } }
        return compact.length >= 3 && letters >= compact.length * 0.6 && longestWord >= 4
    }

    private fun isAddress(key: String): Boolean {
        if (postal.containsMatchIn(key)) return true
        val tokens = key.split(' ')
        return tokens.indices.any { i -> tokens[i].trimEnd('.') in streetSuffixes && i + 1 < tokens.size && tokens[i + 1].firstOrNull()?.isDigit() == true }
    }

    /** Maps digits that OCR produced inside words back to letters: `L1DL` → `LIDL`, `TESC0` → `TESCO`. */
    private fun repairAlpha(key: String): String = key.split(' ').joinToString(" ") { token ->
        val letters = token.count { it.isLetter() }
        if (letters == 0 || letters.toDouble() / token.length < 0.5) token
        else token.map { c ->
            when (c) { '0' -> 'O'; '1' -> 'I'; '5' -> 'S'; '8' -> 'B'; '2' -> 'Z'; '6' -> 'G'; else -> c }
        }.joinToString("")
    }
}

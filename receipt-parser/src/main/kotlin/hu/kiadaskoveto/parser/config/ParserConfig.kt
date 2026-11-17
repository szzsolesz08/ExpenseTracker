package hu.kiadaskoveto.parser.config

import hu.kiadaskoveto.core.text.HungarianText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class QuantityPosition { BEFORE, AFTER, EITHER }

/**
 * A store-specific parsing profile. All behaviour is driven by patterns in
 * `parser_config.json`, so supporting a new chain does not require code changes.
 * Patterns are matched against accent-folded, upper-case text.
 */
@Serializable
data class StoreProfile(
    val id: String,
    val displayName: String,
    val chain: String = displayName,
    val priority: Int = 0,
    val identifiers: List<String>,
    val excludeIdentifiers: List<String> = emptyList(),
    val discountKeywords: List<String> = emptyList(),
    val depositKeywords: List<String> = emptyList(),
    val ignoreKeywords: List<String> = emptyList(),
    val quantityPosition: QuantityPosition = QuantityPosition.EITHER,
) {
    val identifierRegexes: List<Regex> by lazy { identifiers.map { Regex(it) } }
    val excludeRegexes: List<Regex> by lazy { excludeIdentifiers.map { Regex(it) } }

    companion object {
        val GENERIC = StoreProfile(id = "generic", displayName = "Ismeretlen bolt", identifiers = emptyList())
    }
}

@Serializable
data class KeywordConfig(
    val total: List<String>,
    val payable: List<String>,
    val subtotal: List<String>,
    val rounding: List<String>,
    val change: List<String>,
    val payment: Map<String, String>,
    val vat: List<String>,
    val discount: List<String>,
    val deposit: List<String>,
    val depositReturn: List<String>,
    val footer: List<String>,
    val header: List<String>,
    val streetSuffixes: List<String>,
    val dropTokens: List<String>,
)

@Serializable
data class ParserConfig(
    val version: Int,
    val keywords: KeywordConfig,
    val stores: List<StoreProfile>,
) {
    fun profile(id: String?): StoreProfile = stores.firstOrNull { it.id == id } ?: StoreProfile.GENERIC

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): ParserConfig = json.decodeFromString(serializer(), text)

        /** Loads the bundled configuration from the module's resources. */
        fun default(): ParserConfig = DEFAULT

        private val DEFAULT: ParserConfig by lazy {
            val stream = ParserConfig::class.java.getResourceAsStream("/hu/kiadaskoveto/parser/parser_config.json")
                ?: error("parser_config.json missing from resources")
            fromJson(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    }
}

/** Keyword phrase matching on token boundaries of a match key. */
internal object Keywords {
    fun containsPhrase(matchKey: String, phrase: String): Boolean {
        if (phrase.isEmpty()) return false
        val p = HungarianText.matchKey(phrase)
        var idx = matchKey.indexOf(p)
        while (idx >= 0) {
            val beforeOk = idx == 0 || matchKey[idx - 1] == ' '
            val end = idx + p.length
            val afterOk = end == matchKey.length || !matchKey[end].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            idx = matchKey.indexOf(p, idx + 1)
        }
        return false
    }

    fun startsWithPhrase(matchKey: String, phrase: String): Boolean {
        val p = HungarianText.matchKey(phrase)
        return matchKey.startsWith(p) && (matchKey.length == p.length || !matchKey[p.length].isLetterOrDigit())
    }

    fun containsAny(matchKey: String, phrases: Collection<String>): Boolean = phrases.any { containsPhrase(matchKey, it) }

    fun startsWithAny(matchKey: String, phrases: Collection<String>): Boolean = phrases.any { startsWithPhrase(matchKey, it) }
}

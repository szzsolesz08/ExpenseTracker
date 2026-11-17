package hu.kiadaskoveto.parser.categorize

import hu.kiadaskoveto.core.text.Fuzzy
import hu.kiadaskoveto.core.text.HungarianText
import hu.kiadaskoveto.parser.dictionary.MatchType
import hu.kiadaskoveto.parser.dictionary.ProductDictionary
import hu.kiadaskoveto.parser.model.CategoryGuess
import hu.kiadaskoveto.parser.model.CategorySource

/** A category the user confirmed for an exact receipt description (optionally store-specific). */
data class UserCategoryMapping(val matchKey: String, val storeKey: String?, val categoryKey: String)

enum class RuleMatchType { CONTAINS, STARTS_WITH, EXACT, REGEX }

/** A user-defined automatic categorization rule. */
data class UserCategoryRule(
    val pattern: String,
    val matchType: RuleMatchType,
    val categoryKey: String,
    val storeKey: String? = null,
    val priority: Int = 0,
) {
    private val key = HungarianText.matchKey(pattern)
    private val regex: Regex? by lazy {
        if (matchType == RuleMatchType.REGEX) runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() else null
    }

    fun matches(rawName: String, matchKey: String): Boolean = when (matchType) {
        RuleMatchType.CONTAINS -> key.isNotEmpty() && matchKey.contains(key)
        RuleMatchType.STARTS_WITH -> key.isNotEmpty() && matchKey.startsWith(key)
        RuleMatchType.EXACT -> matchKey == key
        RuleMatchType.REGEX -> regex?.containsMatchIn(rawName) == true || regex?.containsMatchIn(matchKey) == true
    }
}

/** Snapshot of user knowledge used during categorization. */
data class CategorizationContext(
    val mappings: List<UserCategoryMapping> = emptyList(),
    val rules: List<UserCategoryRule> = emptyList(),
) {
    internal val mappingIndex: Map<String, List<UserCategoryMapping>> by lazy { mappings.groupBy { it.matchKey } }

    companion object { val EMPTY = CategorizationContext() }
}

/**
 * Deterministic category assignment. Order of evidence:
 * 1. confirmed user mapping for this exact description (store-specific first),
 * 2. user rules,
 * 3. near-identical confirmed description (OCR variation),
 * 4. local dictionary (keywords, abbreviations, brands, fuzzy),
 * 5. "Nem besorolt" fallback.
 */
class ProductCategorizer(private val dictionary: ProductDictionary = ProductDictionary.default()) {

    fun categorize(rawName: String, storeKey: String?, context: CategorizationContext = CategorizationContext.EMPTY): CategoryGuess {
        val key = HungarianText.matchKey(rawName)

        context.mappingIndex[key]?.let { list ->
            val m = list.firstOrNull { it.storeKey != null && it.storeKey == storeKey } ?: list.firstOrNull { it.storeKey == null } ?: list.first()
            return CategoryGuess(m.categoryKey, if (m.storeKey == storeKey || m.storeKey == null) 1.0 else 0.95, CategorySource.USER_MAPPING, m.matchKey)
        }

        context.rules
            .filter { it.storeKey == null || it.storeKey == storeKey }
            .sortedByDescending { it.priority }
            .firstOrNull { it.matches(rawName, key) }
            ?.let { return CategoryGuess(it.categoryKey, 0.95, CategorySource.USER_RULE, it.pattern) }

        if (key.length >= 6) {
            val near = context.mappings
                .map { it to Fuzzy.similarity(it.matchKey, key) }
                .filter { it.second >= 0.9 }
                .maxByOrNull { it.second }
            if (near != null) return CategoryGuess(near.first.categoryKey, 0.85 * near.second, CategorySource.USER_MAPPING, near.first.matchKey)
        }

        return dictionaryGuess(key)
    }

    fun dictionaryGuess(matchKey: String): CategoryGuess {
        val tokens = HungarianText.tokens(matchKey)
        val matches = dictionary.match(tokens)
        if (matches.isEmpty()) return CategoryGuess(CategoryKeys.UNCATEGORIZED, 0.0, CategorySource.FALLBACK)

        val byCategory = HashMap<String, Pair<Double, String>>()
        for (m in matches) {
            val cat = m.entry?.category ?: m.brand?.category ?: continue
            val current = byCategory[cat]
            if (current == null || m.score > current.first) byCategory[cat] = m.score to m.key
        }
        val ranked = byCategory.entries.sortedByDescending { it.value.first }
        if (ranked.isEmpty()) return CategoryGuess(CategoryKeys.UNCATEGORIZED, 0.0, CategorySource.FALLBACK)
        val best = ranked[0]
        val second = ranked.getOrNull(1)?.value?.first ?: 0.0
        var confidence = best.value.first.coerceAtMost(0.95)
        val margin = best.value.first - second
        if (second > 0 && margin < 0.15) confidence -= (0.15 - margin)
        val bestMatch = matches.first { (it.entry?.category ?: it.brand?.category) == best.key }
        val source = if (bestMatch.matchType == MatchType.FUZZY) CategorySource.FUZZY_DICTIONARY else CategorySource.DICTIONARY
        confidence = confidence.coerceIn(0.0, 1.0)
        return if (confidence < 0.3) CategoryGuess(CategoryKeys.UNCATEGORIZED, confidence, CategorySource.FALLBACK, best.value.second)
        else CategoryGuess(best.key, confidence, source, best.value.second)
    }
}

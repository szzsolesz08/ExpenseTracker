package hu.kiadaskoveto.parser.matching

import hu.kiadaskoveto.core.money.PackageSize
import hu.kiadaskoveto.core.text.Fuzzy
import hu.kiadaskoveto.core.text.HungarianText

/** A known product identity with the merchant descriptions confirmed for it. */
data class ProductCandidate(
    val productId: Long,
    val name: String,
    val productType: String?,
    val brand: String?,
    val packageSize: PackageSize?,
    /** Alias match keys mapped to the store key they were confirmed at (null = any store). */
    val aliases: Map<String, String?>,
)

enum class MatchStrength {
    /** Same description already confirmed for this store (or for all stores) — safe to link automatically. */
    CONFIRMED_ALIAS,
    /** Same description confirmed at a different store — suggest only. */
    ALIAS_OTHER_STORE,
    /** Similar description — suggest only, never merge automatically. */
    SIMILAR,
}

data class ProductMatchResult(val candidate: ProductCandidate, val strength: MatchStrength, val score: Double)

/**
 * Links receipt lines to product identities.
 *
 * Identical or similar names are never assumed to be the same product: only an alias the
 * user confirmed (for this store, or explicitly for all stores) links automatically.
 * Everything else is offered as a suggestion.
 */
object ProductMatcher {

    fun match(rawName: String, storeKey: String?, candidates: List<ProductCandidate>, limit: Int = 5): List<ProductMatchResult> {
        val key = HungarianText.matchKey(rawName)
        if (key.isBlank()) return emptyList()
        val results = ArrayList<ProductMatchResult>()
        for (c in candidates) {
            if (c.aliases.containsKey(key)) {
                val aliasStore = c.aliases[key]
                val strength = if (aliasStore == null || aliasStore == storeKey) MatchStrength.CONFIRMED_ALIAS else MatchStrength.ALIAS_OTHER_STORE
                results += ProductMatchResult(c, strength, if (strength == MatchStrength.CONFIRMED_ALIAS) 1.0 else 0.85)
                continue
            }
            val best = (c.aliases.keys + HungarianText.matchKey(c.name)).maxOfOrNull { alias ->
                maxOf(Fuzzy.tokenSetSimilarity(alias, key), Fuzzy.jaroWinkler(alias, key) * 0.95)
            } ?: 0.0
            if (best >= 0.78) results += ProductMatchResult(c, MatchStrength.SIMILAR, best * 0.8)
        }
        return results.sortedByDescending { it.score }.take(limit)
    }

    /** The product to link automatically, if any. */
    fun autoLink(rawName: String, storeKey: String?, candidates: List<ProductCandidate>): ProductCandidate? =
        match(rawName, storeKey, candidates, limit = 1).firstOrNull { it.strength == MatchStrength.CONFIRMED_ALIAS }?.candidate
}

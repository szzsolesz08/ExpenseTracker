package hu.kiadaskoveto.parser.dictionary

import hu.kiadaskoveto.core.text.Fuzzy
import hu.kiadaskoveto.core.text.HungarianText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DictionaryEntry(
    val keys: List<String>,
    val type: String? = null,
    val category: String,
    val priority: Double = 1.0,
    /** Modifier entries (e.g. "FAGY") steer the category but do not name the product. */
    val modifier: Boolean = false,
    /** Only exact token matches count (for very short keys such as "SO"). */
    val exactOnly: Boolean = false,
)

@Serializable
data class BrandEntry(
    val name: String,
    val keys: List<String>,
    val type: String? = null,
    val category: String? = null,
)

@Serializable
data class ProductDictionaryData(
    val version: Int,
    val abbreviations: Map<String, String>,
    val entries: List<DictionaryEntry>,
    val brands: List<BrandEntry>,
)

enum class MatchType(val quality: Double) {
    EXACT(1.0), MULTI_TOKEN(1.0), ABBREVIATION(0.85), COMPOUND_PREFIX(0.8), COMPOUND_CONTAINS(0.7), FUZZY(0.75)
}

data class KeywordMatch(
    val key: String,
    val tokenIndices: Set<Int>,
    val matchType: MatchType,
    val similarity: Double,
    val entry: DictionaryEntry? = null,
    val brand: BrandEntry? = null,
) {
    /** Relevance score: quality × key specificity × entry priority. */
    val score: Double
        get() {
            val chars = key.count { it != ' ' }
            val length = (0.55 + 0.05 * chars).coerceAtMost(1.0) + if (key.contains(' ')) 0.1 else 0.0
            val priority = entry?.priority ?: BRAND_WEIGHT
            return matchType.quality * similarity * length * priority
        }

    companion object { const val BRAND_WEIGHT = 0.9 }
}

/**
 * Local product knowledge: keywords, abbreviations and brands for Hungarian receipts.
 * Loaded from `product_dictionary.json`; extendable without code changes.
 */
class ProductDictionary(val data: ProductDictionaryData) {

    private class Key(val text: String, val tokens: List<String>, val entry: DictionaryEntry?, val brand: BrandEntry?)

    private val entryKeys: List<Key> = data.entries.flatMap { e ->
        e.keys.map { k -> HungarianText.matchKey(k).let { mk -> Key(mk, mk.split(' '), e, null) } }
    }
    private val brandKeys: List<Key> = data.brands.flatMap { b ->
        b.keys.map { k -> HungarianText.matchKey(k).let { mk -> Key(mk, mk.split(' '), null, b) } }
    }
    val abbreviations: Map<String, String> = data.abbreviations.mapKeys { HungarianText.matchKey(it.key) }

    /**
     * Finds all keyword matches for the given match-key tokens, best first.
     * Known receipt abbreviations are expanded first (`JOGH` → `JOGHURT`); such tokens are
     * then only matched exactly, so a complete word like `NATUR` cannot prefix-match `NATURAQUA`.
     */
    fun match(tokens: List<String>): List<KeywordMatch> {
        val known = BooleanArray(tokens.size)
        val expandedFlags = BooleanArray(tokens.size)
        val expanded = tokens.mapIndexed { i, t ->
            val exp = abbreviations[t] ?: return@mapIndexed t
            known[i] = true
            val key = HungarianText.matchKey(exp)
            if (key.isNotEmpty() && !key.contains(' ') && key != t) { expandedFlags[i] = true; key } else t
        }
        val ctx = MatchInput(tokens, expanded, known, expandedFlags)
        val result = ArrayList<KeywordMatch>()
        for (key in entryKeys) matchKey(key, ctx)?.let { result += it }
        for (key in brandKeys) matchKey(key, ctx, brand = true)?.let { result += it }
        return result.sortedByDescending { it.score }
    }

    private class MatchInput(val original: List<String>, val expanded: List<String>, val known: BooleanArray, val wasExpanded: BooleanArray)

    private fun matchKey(key: Key, input: MatchInput, brand: Boolean = false): KeywordMatch? {
        val direct = matchKeyOn(key, input.original, input.known, brand)
        if (input.wasExpanded.none { it }) return direct
        val viaExpansion = matchKeyOn(key, input.expanded, input.known, brand)
            ?.let { it.copy(similarity = it.similarity * 0.95) }
        return listOfNotNull(direct, viaExpansion).maxByOrNull { it.score }
    }

    private fun matchKeyOn(key: Key, tokens: List<String>, known: BooleanArray, brand: Boolean): KeywordMatch? {
        if (key.tokens.size > 1) {
            // Consecutive multi-token phrase, each token exact or near-exact for long tokens.
            outer@ for (start in 0..tokens.size - key.tokens.size) {
                var sim = 1.0
                for (i in key.tokens.indices) {
                    val t = tokens[start + i]
                    val k = key.tokens[i]
                    if (t == k) continue
                    if (!known[start + i] && k.length >= 5 && t.length >= 4 && k.startsWith(t)) { sim = minOf(sim, 0.9); continue }
                    val s = if (k.length >= 5) Fuzzy.similarity(t, k) else 0.0
                    if (s >= 0.8) { sim = minOf(sim, s); continue }
                    continue@outer
                }
                return KeywordMatch(key.text, (start until start + key.tokens.size).toSet(), MatchType.MULTI_TOKEN, sim, key.entry, key.brand)
            }
            return null
        }
        val k = key.text
        val exactOnly = key.entry?.exactOnly == true
        var best: KeywordMatch? = null
        tokens.forEachIndexed { idx, t ->
            val candidate: KeywordMatch? = when {
                t == k -> KeywordMatch(k, setOf(idx), MatchType.EXACT, 1.0, key.entry, key.brand)
                // Known abbreviations/words and tokens with digits are only matched exactly.
                exactOnly || known[idx] || t.any { it.isDigit() } -> null
                !brand && t.length >= 4 && k.length > t.length && k.startsWith(t) ->
                    KeywordMatch(k, setOf(idx), MatchType.ABBREVIATION, t.length.toDouble() / k.length * 0.5 + 0.5, key.entry, null)
                !brand && k.length >= 4 && t.length > k.length && t.startsWith(k) ->
                    KeywordMatch(k, setOf(idx), MatchType.COMPOUND_PREFIX, 1.0, key.entry, null)
                !brand && k.length >= 5 && t.length > k.length && t.contains(k) ->
                    KeywordMatch(k, setOf(idx), MatchType.COMPOUND_CONTAINS, 1.0, key.entry, null)
                k.length >= 5 && t.length >= 5 -> {
                    val s = Fuzzy.similarity(t, k)
                    if (s >= 0.8) KeywordMatch(k, setOf(idx), MatchType.FUZZY, s, key.entry, key.brand) else null
                }
                else -> null
            }
            if (candidate != null && candidate.score > (best?.score ?: -1.0)) best = candidate
        }
        return best
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): ProductDictionary = ProductDictionary(json.decodeFromString(ProductDictionaryData.serializer(), text))

        fun default(): ProductDictionary = DEFAULT

        private val DEFAULT: ProductDictionary by lazy {
            val stream = ProductDictionary::class.java.getResourceAsStream("/hu/kiadaskoveto/parser/product_dictionary.json")
                ?: error("product_dictionary.json missing from resources")
            fromJson(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    }
}

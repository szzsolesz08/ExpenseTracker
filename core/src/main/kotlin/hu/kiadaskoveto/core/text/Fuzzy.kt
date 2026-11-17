package hu.kiadaskoveto.core.text

import kotlin.math.max
import kotlin.math.min

/** String similarity measures used for OCR-tolerant matching. All return values in [0, 1]. */
object Fuzzy {

    /** Optimal string alignment distance (Damerau–Levenshtein with adjacent transpositions). */
    fun damerauLevenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = min(v, d[i - 2][j - 2] + 1)
                }
                d[i][j] = v
            }
        }
        return d[a.length][b.length]
    }

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val dist = damerauLevenshtein(a, b)
        return 1.0 - dist.toDouble() / max(a.length, b.length)
    }

    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val matchDistance = max(0, max(a.length, b.length) / 2 - 1)
        val aMatches = BooleanArray(a.length)
        val bMatches = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val start = max(0, i - matchDistance)
            val end = min(i + matchDistance + 1, b.length)
            for (j in start until end) {
                if (bMatches[j] || a[i] != b[j]) continue
                aMatches[i] = true; bMatches[j] = true; matches++; break
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatches[i]) continue
            while (!bMatches[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0
        var prefix = 0
        while (prefix < min(4, min(a.length, b.length)) && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    /** Token-set similarity: order-independent comparison of word sets, tolerant to small typos. */
    fun tokenSetSimilarity(a: String, b: String): Double {
        val ta = HungarianText.tokens(a).toSet()
        val tb = HungarianText.tokens(b).toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        var score = 0.0
        for (x in ta) score += tb.maxOf { y -> similarity(x, y) }
        for (y in tb) score += ta.maxOf { x -> similarity(x, y) }
        return score / (ta.size + tb.size)
    }

    /** Best similarity of [token] against any of [candidates]; returns pair of candidate and score. */
    fun bestMatch(token: String, candidates: Collection<String>): Pair<String, Double>? =
        candidates.map { it to similarity(token, it) }.maxByOrNull { it.second }
}

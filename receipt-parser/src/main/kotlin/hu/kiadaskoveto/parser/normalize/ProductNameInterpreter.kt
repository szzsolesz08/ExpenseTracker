package hu.kiadaskoveto.parser.normalize

import hu.kiadaskoveto.core.money.MeasureUnit
import hu.kiadaskoveto.core.money.PackageSize
import hu.kiadaskoveto.core.text.HungarianText
import hu.kiadaskoveto.parser.dictionary.KeywordMatch
import hu.kiadaskoveto.parser.dictionary.ProductDictionary
import hu.kiadaskoveto.parser.model.ProductInterpretation
import java.math.BigDecimal

/**
 * Turns an abbreviated merchant description into a readable interpretation while
 * keeping product type, brand, variant and package size separate.
 *
 * `TEJ ESL 2,8% 1L` → type "Tej", variant "ESL 2,8%", size 1 l.
 * The result carries a [ProductInterpretation.certainty] so the UI never presents a guess as fact.
 */
class ProductNameInterpreter(
    private val dictionary: ProductDictionary = ProductDictionary.default(),
    private val dropTokens: Set<String> = setOf("LOOSE", "LEDIG", "LEDIGES", "AKCIO", "AKCIOS", "UJ", "NEW", "PROMO"),
) {
    private val sizeToken = Regex("^(\\d+(?:[.,]\\d+)?)(KG|G|GR|DKG|L|LTR|DL|CL|ML|DB|CS|M)$")
    private val multiPackToken = Regex("^(\\d+)X(\\d+(?:[.,]\\d+)?)(KG|G|GR|DKG|L|DL|CL|ML|DB)$")
    private val multiplierToken = Regex("^(\\d+)X$")
    private val numberToken = Regex("^\\d+(?:[.,]\\d+)?$")
    private val percentToken = Regex("^\\d+(?:[.,]\\d+)?%$")
    private val unitOnly = setOf("KG", "DB", "L", "G", "CS")

    fun interpret(rawName: String): ProductInterpretation {
        val tokens = HungarianText.tokens(rawName)
        if (tokens.isEmpty()) return ProductInterpretation(rawName.trim(), null, null, null, null, 0.0)
        // Original words (with accents) aligned to the match tokens, used for unexplained words.
        val originalWords = HungarianText.collapseSpaces(rawName).split(' ')
            .map { w -> w.trim('.', ',', ';', ':', '(', ')', '-', '–', '—') }
            .filter { w -> HungarianText.matchKey(w).isNotEmpty() }
            .takeIf { words -> words.size == tokens.size && words.indices.all { HungarianText.matchKey(it.let(words::get)).trimEnd('.', ',') == tokens[it] } }

        val consumed = BooleanArray(tokens.size)
        var size: PackageSize? = null
        var soldByWeight = false
        var multipackText: String? = null
        val percents = mutableListOf<String>()

        // --- Package size, multipacks, percentages, drop words -------------------------
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val multi = multiPackToken.matchEntire(t)
            val single = sizeToken.matchEntire(t)
            when {
                multi != null -> {
                    val count = BigDecimal(multi.groupValues[1])
                    val each = parseSize(multi.groupValues[2], multi.groupValues[3])
                    if (each != null) {
                        size = PackageSize(each.amount.multiply(count), each.unit)
                        multipackText = "${multi.groupValues[1]}×${each.format()}"
                    }
                    consumed[i] = true
                }
                multiplierToken.matches(t) && i + 1 < tokens.size && sizeToken.matches(tokens[i + 1]) -> {
                    val count = BigDecimal(multiplierToken.matchEntire(t)!!.groupValues[1])
                    val m = sizeToken.matchEntire(tokens[i + 1])!!
                    val each = parseSize(m.groupValues[1], m.groupValues[2])
                    if (each != null) {
                        size = PackageSize(each.amount.multiply(count), each.unit)
                        multipackText = "${count}×${each.format()}"
                    }
                    consumed[i] = true; consumed[i + 1] = true; i++
                }
                single != null -> {
                    parseSize(single.groupValues[1], single.groupValues[2])?.let { if (size == null) size = it }
                    consumed[i] = true
                }
                numberToken.matches(t) && i + 1 < tokens.size && MeasureUnit.parse(tokens[i + 1]) != null &&
                    tokens[i + 1] != "M" -> {
                    parseSize(t, tokens[i + 1])?.let { if (size == null) size = it }
                    consumed[i] = true; consumed[i + 1] = true; i++
                }
                percentToken.matches(t) -> { percents += t; consumed[i] = true }
                t in unitOnly -> { if (t == "KG") soldByWeight = true; consumed[i] = true }
                t in dropTokens -> consumed[i] = true
            }
            i++
        }

        // --- Dictionary: product type and brand -----------------------------------------
        val matches = dictionary.match(tokens.mapIndexed { idx, t -> if (consumed[idx]) "\u0000" else t })
        val typeMatch: KeywordMatch? = matches.firstOrNull { it.entry != null && !it.entry.modifier && it.entry.type != null }
        val brandMatch: KeywordMatch? = matches.firstOrNull { it.brand != null && (typeMatch == null || it.tokenIndices.intersect(typeMatch.tokenIndices).isEmpty()) }
        val modifierMatches = matches.filter { it.entry?.modifier == true }

        val explained = consumed.copyOf()
        var abbreviationUsed = false
        typeMatch?.tokenIndices?.forEach { explained[it] = true }
        brandMatch?.tokenIndices?.forEach { explained[it] = true }

        // --- Leftover tokens become the variant -----------------------------------------
        val variantParts = mutableListOf<String>()
        modifierMatches.forEach { m -> m.tokenIndices.forEach { idx -> if (!explained[idx]) { explained[idx] = true; variantParts += "fagyasztott".takeIf { m.entry?.category == "frozen" } ?: tokens[idx].lowercase(HungarianText.HU) } } }
        tokens.forEachIndexed { idx, t ->
            if (explained[idx]) return@forEachIndexed
            val expansion = dictionary.abbreviations[t]
            if (expansion != null) {
                abbreviationUsed = abbreviationUsed || expansion.length > t.length
                explained[idx] = true
                if (expansion.isNotEmpty()) variantParts += expansion
            } else {
                val original = originalWords?.get(idx)
                variantParts += when {
                    t.length <= 3 && t.all { it.isLetter() } -> t
                    original != null -> original.lowercase(HungarianText.HU)
                    else -> t.lowercase(HungarianText.HU)
                }
            }
        }
        variantParts += percents
        multipackText?.let { variantParts += it }

        val type = typeMatch?.entry?.type ?: brandMatch?.brand?.type
        val brand = brandMatch?.brand?.name
        val variant = variantParts.distinct().joinToString(" ").ifBlank { null }

        val display = buildString {
            when {
                brand != null && typeMatch?.entry?.type != null -> append(brand).append(' ').append(typeMatch.entry.type!!.lowercase(HungarianText.HU))
                brand != null -> append(brand)
                type != null -> append(type)
                else -> append(HungarianText.sentenceCase(variantParts.joinToString(" ").ifBlank { rawName }))
            }
            if ((brand != null || type != null) && variant != null) append(' ').append(variant)
        }.trim()

        val explainedCount = explained.count { it }
        var certainty = explainedCount.toDouble() / tokens.size
        if (type == null && brand == null) certainty = minOf(certainty, 0.4)
        if (abbreviationUsed) certainty *= 0.9
        typeMatch?.let { certainty *= (0.75 + 0.25 * it.matchType.quality * it.similarity) }

        return ProductInterpretation(
            displayName = display,
            productType = type,
            brand = brand,
            variant = variant,
            packageSize = size,
            certainty = certainty.coerceIn(0.0, 1.0),
            soldByWeight = soldByWeight,
        )
    }

    private fun parseSize(amount: String, unitToken: String): PackageSize? {
        val unit = MeasureUnit.parse(if (unitToken == "LTR") "l" else unitToken) ?: return null
        val value = amount.replace(',', '.').toBigDecimalOrNull() ?: return null
        if (value.signum() <= 0) return null
        return PackageSize(value, unit)
    }
}

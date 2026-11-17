package hu.kiadaskoveto.parser

import hu.kiadaskoveto.core.money.MeasureUnit
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.money.sumMoney
import hu.kiadaskoveto.parser.categorize.CategorizationContext
import hu.kiadaskoveto.parser.categorize.CategoryKeys
import hu.kiadaskoveto.parser.categorize.ProductCategorizer
import hu.kiadaskoveto.parser.config.Keywords
import hu.kiadaskoveto.parser.config.ParserConfig
import hu.kiadaskoveto.parser.dictionary.ProductDictionary
import hu.kiadaskoveto.parser.model.CategoryGuess
import hu.kiadaskoveto.parser.model.CategorySource
import hu.kiadaskoveto.parser.model.ClassifiedLine
import hu.kiadaskoveto.parser.model.IssueCode
import hu.kiadaskoveto.parser.model.ItemKind
import hu.kiadaskoveto.parser.model.LineType
import hu.kiadaskoveto.parser.model.OcrInput
import hu.kiadaskoveto.parser.model.ParseIssue
import hu.kiadaskoveto.parser.model.ParseStage
import hu.kiadaskoveto.parser.model.ParsedItem
import hu.kiadaskoveto.parser.model.ParsedReceipt
import hu.kiadaskoveto.parser.model.PaymentMethod
import hu.kiadaskoveto.parser.model.ProductInterpretation
import hu.kiadaskoveto.parser.model.VatLine
import hu.kiadaskoveto.parser.normalize.ProductNameInterpreter
import hu.kiadaskoveto.parser.stages.DateExtractor
import hu.kiadaskoveto.parser.stages.ItemAssembler
import hu.kiadaskoveto.parser.stages.LineClassifier
import hu.kiadaskoveto.parser.stages.LinePreparer
import hu.kiadaskoveto.parser.stages.PreparedLine
import hu.kiadaskoveto.parser.stages.RawItem
import hu.kiadaskoveto.parser.stages.StoreDetector
import java.time.LocalDate

/**
 * Multi-stage receipt interpretation. OCR only extracts text; this class turns that
 * text into structured purchase data. It is deterministic and has no I/O.
 *
 * Stages: 1 raw text → 2 structure → 3 store → 4 date → 5 product lines →
 * 6 quantities & prices → 7 discounts → 8 total verification → 9 categories → 10 confidence.
 */
class ReceiptParser(
    private val config: ParserConfig = ParserConfig.default(),
    dictionary: ProductDictionary = ProductDictionary.default(),
    today: () -> LocalDate = { LocalDate.now() },
) {
    private val storeDetector = StoreDetector(config)
    private val dateExtractor = DateExtractor(today)
    private val interpreter = ProductNameInterpreter(dictionary, config.keywords.dropTokens.toSet())
    private val categorizer = ProductCategorizer(dictionary)

    private val apCode = Regex("\\bA\\s?P\\s*[:.]?\\s*([A-Z]\\d{8})\\b")
    private val apAlone = Regex("^([A-Z]\\d{8})$")
    private val receiptNo = Regex("(?:NYUGTASZAM|NYUGTA SZAM|NYUGTA SORSZAM|SORSZAM|BIZONYLATSZAM|NYUGTA SZ)\\s*[:.]?\\s*([0-9][0-9/ .\\-]{2,20})")
    private val vatLine = Regex("^([A-E])(?:00)?\\s+(\\d{1,2}(?:[.,]\\d{1,2})?)\\s?%")

    /**
     * @param storeKeyResolver maps the detected store (profile id, printed name) to the caller's store key,
     *   which is used to look up store-specific user corrections. Defaults to the profile id.
     * @param onStage progress callback, invoked once per stage.
     */
    fun parse(
        input: OcrInput,
        context: CategorizationContext = CategorizationContext.EMPTY,
        storeKeyResolver: ((profileId: String?, storeName: String?) -> String?)? = null,
        onStage: (ParseStage) -> Unit = {},
    ): ParsedReceipt {
        onStage(ParseStage.RAW_TEXT)
        val lines = LinePreparer.prepare(input.lines.filter { it.text.isNotBlank() })

        onStage(ParseStage.STRUCTURE)
        val store = storeDetector.detect(lines)
        val types = LineClassifier(config, store.profile).classify(lines)

        onStage(ParseStage.STORE)
        val issues = ArrayList<ParseIssue>()
        if (store.confidence < 0.5) issues += ParseIssue(IssueCode.STORE_UNKNOWN)

        onStage(ParseStage.DATE)
        val date = dateExtractor.extract(lines)
        if (date == null) issues += ParseIssue(IssueCode.DATE_MISSING)
        else if (date.implausible) issues += ParseIssue(IssueCode.DATE_IMPLAUSIBLE, date.dateTime.toLocalDate().toString())
        else if (date.repaired) issues += ParseIssue(IssueCode.DATE_REPAIRED, date.dateTime.toLocalDate().toString())

        onStage(ParseStage.PRODUCT_LINES)
        val assembly = ItemAssembler(store.profile).assemble(lines, types)
        onStage(ParseStage.QUANTITIES)
        onStage(ParseStage.DISCOUNTS)

        onStage(ParseStage.TOTAL_CHECK)
        val totals = totals(lines, types)
        val computed = assembly.items.map { it.lineTotal + it.discount }.sumMoney()
        val reference = totals.printed ?: totals.payable
        val totalMatches: Boolean? = when {
            reference == null -> null
            totals.printed != null && computed == totals.printed -> true
            totals.payable != null && (computed == totals.payable || computed + (totals.rounding ?: Money.ZERO) == totals.payable) -> true
            else -> false
        }
        if (reference == null) issues += ParseIssue(IssueCode.TOTAL_MISSING)
        else if (totalMatches == false) issues += ParseIssue(IssueCode.TOTAL_MISMATCH, (reference - computed).format())

        onStage(ParseStage.CATEGORIES)
        val profileId = store.profile.id.takeIf { it != "generic" }
        val storeKey = if (storeKeyResolver != null) storeKeyResolver(profileId, store.storeName) else profileId
        val items = assembly.items.map { buildItem(it, storeKey, context) }

        onStage(ParseStage.CONFIDENCE)
        if (items.isEmpty()) issues += ParseIssue(IssueCode.NO_ITEMS)
        if (assembly.unparsed.isNotEmpty()) issues += ParseIssue(IssueCode.UNPARSED_LINES, assembly.unparsed.size.toString())
        val meanItem = if (items.isEmpty()) 0.0 else items.map { it.confidence }.average()
        val totalScore = when (totalMatches) { true -> 1.0; null -> 0.4; false -> 0.15 }
        val confidence = if (items.isEmpty()) 0.1 else
            (0.3 * totalScore + 0.15 * store.confidence + 0.15 * (if (date != null && !date.implausible) 1.0 else 0.0) + 0.4 * meanItem)
                .coerceIn(0.0, 1.0)

        return ParsedReceipt(
            storeProfileId = store.profile.id.takeIf { it != "generic" },
            storeName = store.storeName,
            storeConfidence = store.confidence,
            companyName = store.companyName,
            address = store.address,
            taxNumber = store.taxNumber,
            purchasedAt = date?.dateTime,
            dateHasTime = date?.hasTime ?: false,
            items = items,
            printedTotal = totals.printed,
            payableTotal = totals.payable,
            rounding = totals.rounding,
            computedTotal = computed,
            totalMatches = totalMatches,
            paymentMethod = paymentMethod(lines, types),
            receiptNumber = lines.firstNotNullOfOrNull { receiptNo.find(it.key)?.groupValues?.get(1)?.trim() },
            apCode = lines.firstNotNullOfOrNull { apCode.find(it.key)?.groupValues?.get(1) }
                ?: lines.takeLast(12).firstNotNullOfOrNull { apAlone.find(it.key)?.groupValues?.get(1) },
            vatLines = lines.indices.filter { types[it] == LineType.VAT }.map { i ->
                val m = vatLine.find(lines[i].key)
                VatLine(m?.groupValues?.get(1), m?.groupValues?.get(2), lines[i].trailing?.amount, lines[i].text)
            },
            unparsedLines = assembly.unparsed,
            classifiedLines = lines.mapIndexed { i, l -> ClassifiedLine(i, l.ocr, types[i]) },
            confidence = confidence,
            issues = issues,
            rawText = input.fullText,
        )
    }

    /** Categorizes and scores an assembled item. Also used when the user adds or edits a line. */
    fun interpretLine(rawName: String, storeKey: String?, context: CategorizationContext): Pair<ProductInterpretation, CategoryGuess> =
        interpreter.interpret(rawName) to categorizer.categorize(rawName, storeKey, context)

    private fun buildItem(raw: RawItem, storeKey: String?, context: CategorizationContext): ParsedItem {
        val ocrConfidence = raw.sources.map { it.ocr.confidence }.average().takeIf { !it.isNaN() } ?: 1.0
        val issues = raw.issues.toMutableList()
        val interpretation = interpreter.interpret(raw.name)
        val category = when (raw.kind) {
            ItemKind.PRODUCT -> categorizer.categorize(raw.name, storeKey, context)
            ItemKind.DEPOSIT, ItemKind.DEPOSIT_RETURN -> CategoryGuess(CategoryKeys.OTHER, 0.95, CategorySource.DICTIONARY, "VISSZAVALTASI DIJ")
            ItemKind.DISCOUNT -> CategoryGuess(CategoryKeys.OTHER, 0.9, CategorySource.DICTIONARY, "KEDVEZMENY")
        }
        if (ocrConfidence < 0.6) issues += ParseIssue(IssueCode.LOW_OCR_CONFIDENCE, "%.0f%%".format(ocrConfidence * 100))
        if (raw.kind == ItemKind.PRODUCT && interpretation.certainty < 0.5) issues += ParseIssue(IssueCode.NAME_UNCERTAIN)
        if (raw.kind == ItemKind.PRODUCT && category.confidence < 0.55) issues += ParseIssue(IssueCode.CATEGORY_UNCERTAIN)

        val structure = when {
            issues.any { it.code == IssueCode.QUANTITY_PRICE_MISMATCH } -> 0.5
            issues.any { it.code == IssueCode.AMOUNT_REPAIRED } -> 0.6
            issues.any { it.code == IssueCode.DISCOUNT_SIGN_ASSUMED } -> 0.7
            else -> 1.0
        }
        val confidence = (0.35 * ocrConfidence + 0.25 * structure + 0.25 * category.confidence + 0.15 * interpretation.certainty)
            .coerceIn(0.0, 1.0)

        val q = raw.quantity
        val unit = q?.unit ?: q?.priceUnit?.takeIf { it == MeasureUnit.KILOGRAM } ?: if (interpretation.soldByWeight) MeasureUnit.KILOGRAM else null
        return ParsedItem(
            originalText = raw.sources.joinToString("\n") { it.ocr.text.trim() },
            rawName = raw.name,
            kind = raw.kind,
            quantity = q?.quantity,
            unit = unit ?: if (q != null) MeasureUnit.PIECE else null,
            unitPrice = q?.unitPrice,
            lineTotal = raw.lineTotal,
            discount = raw.discount,
            discountTexts = raw.discountTexts.toList(),
            vatCode = raw.vatCode,
            interpretation = interpretation,
            category = category,
            ocrConfidence = ocrConfidence,
            confidence = confidence,
            issues = issues,
        )
    }

    private data class Totals(val printed: Money?, val payable: Money?, val rounding: Money?)

    private fun totals(lines: List<PreparedLine>, types: List<LineType>): Totals {
        var printed: Money? = null
        var payable: Money? = null
        var rounding: Money? = null
        lines.forEachIndexed { i, line ->
            val amount = line.trailing?.amount ?: return@forEachIndexed
            when (types[i]) {
                LineType.TOTAL -> {
                    if (Keywords.startsWithAny(line.key, config.keywords.payable)) { if (payable == null) payable = amount }
                    else if (printed == null) printed = amount
                }
                LineType.ROUNDING -> if (rounding == null) rounding = amount
                else -> Unit
            }
        }
        if (rounding == null && printed != null && payable != null && printed != payable) {
            val diff = payable - printed
            if (diff.abs <= Money.ofForint(2)) rounding = diff
        }
        return Totals(printed, payable, rounding)
    }

    private fun paymentMethod(lines: List<PreparedLine>, types: List<LineType>): PaymentMethod? {
        val found = LinkedHashSet<PaymentMethod>()
        lines.forEachIndexed { i, line ->
            if (types[i] != LineType.PAYMENT && types[i] != LineType.FOOTER && types[i] != LineType.CHANGE) return@forEachIndexed
            config.keywords.payment.entries
                .sortedByDescending { it.key.length }
                .firstOrNull { Keywords.containsPhrase(line.key, it.key) }
                ?.let { found += PaymentMethod.valueOf(it.value) }
        }
        return when {
            found.isEmpty() -> null
            found.size == 1 -> found.first()
            else -> PaymentMethod.MIXED
        }
    }
}

package hu.kiadaskoveto.parser.stages

import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.money.PriceMath
import hu.kiadaskoveto.parser.config.QuantityPosition
import hu.kiadaskoveto.parser.config.StoreProfile
import hu.kiadaskoveto.parser.model.IssueCode
import hu.kiadaskoveto.parser.model.ItemKind
import hu.kiadaskoveto.parser.model.LineType
import hu.kiadaskoveto.parser.model.OcrLine
import hu.kiadaskoveto.parser.model.ParseIssue

/** Item assembled from one or more receipt lines, before interpretation and categorization. */
class RawItem(
    val kind: ItemKind,
    var name: String,
    var lineTotal: Money,
    var vatCode: String?,
    val sources: MutableList<PreparedLine>,
) {
    var quantity: QuantityInfo? = null
    var discount: Money = Money.ZERO
    val discountTexts = mutableListOf<String>()
    val issues = mutableListOf<ParseIssue>()
}

data class AssemblyResult(val items: List<RawItem>, val unparsed: List<OcrLine>)

/**
 * Stages 5–7: product line detection, quantity/price association and discount detection.
 *
 * Rules:
 * - printed values are kept as printed; prices are never derived from guessed unit prices,
 * - a quantity line is attached to the neighbouring item whose printed total it agrees with,
 * - a discount directly after a product belongs to that product, otherwise it is receipt-level,
 * - lines that cannot be placed are returned as unparsed so the user can review them.
 */
class ItemAssembler(private val profile: StoreProfile) {

    private class State {
        val items = ArrayList<RawItem>()
        val unparsed = ArrayList<OcrLine>()
        val pendingName = ArrayList<PreparedLine>()
        var productBeforePending = false
        var pendingQty: PreparedLine? = null
        var lastProduct: RawItem? = null
        var lastWasProduct = false

        fun takePendingName(): List<PreparedLine> = pendingName.toList().also { pendingName.clear() }

        fun flushPendingName() {
            pendingName.forEach { unparsed += it.ocr }
            pendingName.clear()
        }

        fun addProduct(item: RawItem) {
            items += item; lastProduct = item; lastWasProduct = true
        }
    }

    fun assemble(lines: List<PreparedLine>, types: List<LineType>): AssemblyResult {
        val firstTotal = types.indexOfFirst { it == LineType.TOTAL || it == LineType.PAYMENT }
        val inItems = lines.indices.filter { idx ->
            types[idx] !in setOf(LineType.HEADER, LineType.SEPARATOR) && (firstTotal < 0 || idx < firstTotal)
        }
        val s = State()

        fun nextItemLine(after: Int): PreparedLine? {
            val pos = inItems.indexOf(after)
            for (j in pos + 1 until inItems.size) {
                val t = types[inItems[j]]
                if (t == LineType.ITEM) return lines[inItems[j]]
                if (t != LineType.SEPARATOR) return null
            }
            return null
        }

        for (idx in inItems) {
            val line = lines[idx]
            when (types[idx]) {
                LineType.NAME_ONLY -> {
                    if (s.pendingName.isEmpty()) s.productBeforePending = s.lastWasProduct
                    s.pendingName += line
                    s.lastWasProduct = false
                }
                LineType.QUANTITY -> handleQuantity(s, line, nextItemLine(idx))
                LineType.ITEM -> {
                    val t = line.trailing!!
                    val names = s.takePendingName()
                    val name = (names.map { it.text } + t.prefix).joinToString(" ").trim()
                    val item = RawItem(ItemKind.PRODUCT, name, t.amount, t.vatCode, (names + line).toMutableList())
                    if (t.repaired) item.issues += ParseIssue(IssueCode.AMOUNT_REPAIRED, line.text)
                    s.pendingQty?.let { attachQuantity(item, it) }
                    s.pendingQty = null
                    s.addProduct(item)
                }
                LineType.DISCOUNT -> {
                    val t = line.trailing!!
                    var amount = t.amount
                    val assumed = amount.isPositive
                    if (assumed) amount = -amount
                    val labelOnPreviousLine = s.pendingName.isNotEmpty()
                    val belongsToProduct = s.lastProduct != null && (s.lastWasProduct || (labelOnPreviousLine && s.productBeforePending))
                    val names = s.takePendingName()
                    val label = (names.map { it.text } + t.prefix).joinToString(" ").trim()
                    if (belongsToProduct) {
                        val target = s.lastProduct!!
                        target.discount += amount
                        target.discountTexts += label.ifBlank { line.text }
                        target.sources += names + line
                        if (assumed) target.issues += ParseIssue(IssueCode.DISCOUNT_SIGN_ASSUMED, line.text)
                        s.lastWasProduct = true
                    } else {
                        val item = RawItem(ItemKind.DISCOUNT, label.ifBlank { "Kedvezmény" }, amount, t.vatCode, (names + line).toMutableList())
                        if (assumed) item.issues += ParseIssue(IssueCode.DISCOUNT_SIGN_ASSUMED, line.text)
                        s.items += item
                        s.lastWasProduct = false
                    }
                }
                LineType.DEPOSIT, LineType.DEPOSIT_RETURN -> {
                    val t = line.trailing!!
                    val names = s.takePendingName()
                    val label = (names.map { it.text } + t.prefix).joinToString(" ").trim()
                    val isReturn = types[idx] == LineType.DEPOSIT_RETURN
                    val amount = if (isReturn && t.amount.isPositive) -t.amount else t.amount
                    s.items += RawItem(if (isReturn) ItemKind.DEPOSIT_RETURN else ItemKind.DEPOSIT, label, amount, t.vatCode, (names + line).toMutableList())
                    s.lastWasProduct = false
                }
                LineType.SUBTOTAL -> { s.lastWasProduct = false; s.flushPendingName() }
                LineType.UNKNOWN -> {
                    val t = line.trailing
                    if (t != null && t.prefix.isEmpty() && s.pendingName.isNotEmpty() && t.amount.isPositive) {
                        // Name on one line, price alone on the next.
                        val names = s.takePendingName()
                        val item = RawItem(ItemKind.PRODUCT, names.joinToString(" ") { it.text }, t.amount, null, (names + line).toMutableList())
                        s.pendingQty?.let { attachQuantity(item, it) }
                        s.pendingQty = null
                        s.addProduct(item)
                    } else {
                        if (line.text.count { it.isLetterOrDigit() } >= 3) s.unparsed += line.ocr
                        s.lastWasProduct = false
                    }
                }
                else -> s.lastWasProduct = false
            }
        }
        s.flushPendingName()
        s.pendingQty?.let { s.unparsed += it.ocr }
        return AssemblyResult(s.items, s.unparsed)
    }

    private fun handleQuantity(s: State, line: PreparedLine, next: PreparedLine?) {
        val q = line.quantity!!
        val prev = s.lastProduct
        val prevOpen = prev != null && s.lastWasProduct && prev.quantity == null
        val agreesWithPrev = prevOpen && (
            (q.total != null && (q.total == prev!!.lineTotal || q.unitPrice == prev.lineTotal)) ||
                (q.total == null && q.unitPrice != null && PriceMath.isConsistent(q.quantity, q.unitPrice, prev!!.lineTotal)) ||
                q.unitPrice == null)
        val agreesWithNext = next?.trailing != null && q.unitPrice != null &&
            PriceMath.isConsistent(q.quantity, q.unitPrice, next.trailing.amount)

        when {
            s.pendingName.isNotEmpty() && q.total != null -> {
                // "NAME" line followed by "2 x 329   658" → one item.
                val names = s.takePendingName()
                val item = RawItem(ItemKind.PRODUCT, names.joinToString(" ") { it.text }, q.total, q.vatCode, (names + line).toMutableList())
                item.quantity = q
                q.isConsistent?.let { if (!it) item.issues += ParseIssue(IssueCode.QUANTITY_PRICE_MISMATCH, line.text) }
                s.addProduct(item)
            }
            agreesWithPrev && (profile.quantityPosition != QuantityPosition.BEFORE || !agreesWithNext) -> {
                attachQuantity(prev!!, line); s.lastWasProduct = true
            }
            agreesWithNext || profile.quantityPosition == QuantityPosition.BEFORE -> s.pendingQty = line
            prevOpen -> {
                // Adjacent but inconsistent: attach and flag, keeping the printed values.
                attachQuantity(prev!!, line); s.lastWasProduct = true
            }
            else -> s.pendingQty = line
        }
    }

    private fun attachQuantity(item: RawItem, qLine: PreparedLine) {
        val q = qLine.quantity!!
        item.sources += qLine
        if (q.total != null && q.unitPrice != null && q.unitPrice == item.lineTotal && q.total != item.lineTotal &&
            PriceMath.isConsistent(q.quantity, q.unitPrice, q.total)) {
            // The item line printed the unit price and the quantity line printed the line total.
            item.lineTotal = q.total
        }
        item.quantity = q
        val consistent = q.unitPrice == null || PriceMath.isConsistent(q.quantity, q.unitPrice, item.lineTotal)
        if (!consistent) item.issues += ParseIssue(IssueCode.QUANTITY_PRICE_MISMATCH,
            "${q.quantity.format()} × ${q.unitPrice?.format()} ≠ ${item.lineTotal.format()}")
        if (item.vatCode == null) item.vatCode = q.vatCode
    }
}

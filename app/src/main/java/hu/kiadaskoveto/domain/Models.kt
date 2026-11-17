package hu.kiadaskoveto.domain

import hu.kiadaskoveto.core.money.MeasureUnit
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.money.Quantity
import java.time.LocalDateTime

data class Category(
    val id: Long,
    val key: String,
    val name: String,
    val parentId: Long?,
    val colorArgb: Long,
    val iconKey: String,
    val sortOrder: Int,
    val isSystem: Boolean,
) {
    val isUncategorized: Boolean get() = key == "uncategorized"
}

enum class ReceiptStatus { DRAFT, CONFIRMED, FAILED }

enum class ItemKind(val label: String) {
    PRODUCT("Termék"), DISCOUNT("Kedvezmény"), DEPOSIT("Visszaváltási díj"), DEPOSIT_RETURN("Visszaváltás");
}

data class ReceiptItem(
    val id: Long,
    val receiptId: Long,
    val position: Int,
    val kind: ItemKind,
    val originalText: String,
    val rawName: String,
    val displayName: String,
    val productId: Long?,
    val categoryId: Long?,
    val quantity: Quantity?,
    val unit: MeasureUnit?,
    val unitPrice: Money?,
    val lineTotal: Money,
    val discount: Money,
    val discountText: String?,
    val vatCode: String?,
    val confidence: Double,
    val needsReview: Boolean,
    val reviewNote: String?,
    val userEdited: Boolean,
) {
    val finalPrice: Money get() = lineTotal + discount
}

data class Receipt(
    val id: Long,
    val storeId: Long?,
    val storeName: String?,
    val purchasedAt: LocalDateTime,
    val hasTime: Boolean,
    val dateKnown: Boolean,
    val total: Money,
    val printedTotal: Money?,
    val payableTotal: Money?,
    val computedTotal: Money,
    val rounding: Money?,
    val paymentMethod: String?,
    val receiptNumber: String?,
    val apCode: String?,
    val address: String?,
    val taxNumber: String?,
    val status: ReceiptStatus,
    val confidence: Double,
    val ocrText: String?,
    val unparsedText: String?,
    val notes: String?,
    val duplicateOverride: Boolean,
    val failureReason: String?,
) {
    /** Difference between the printed total and the sum of lines (null when no total was printed). */
    val totalDifference: Money?
        get() = when {
            printedTotal != null -> printedTotal - computedTotal
            payableTotal != null -> payableTotal - (rounding ?: Money.ZERO) - computedTotal
            else -> null
        }
}

data class ReceiptDocument(val id: Long, val filePath: String, val mimeType: String, val pageIndex: Int, val selectedPages: String?)

data class Store(val id: Long, val name: String, val chainKey: String?, val address: String?)

fun paymentLabel(method: String?): String? = when (method) {
    "CARD" -> "Bankkártya"
    "CASH" -> "Készpénz"
    "SZEP_CARD" -> "SZÉP-kártya"
    "VOUCHER" -> "Utalvány"
    "MIXED" -> "Vegyes fizetés"
    else -> null
}

fun unitLabel(unit: MeasureUnit?): String = unit?.symbol ?: ""

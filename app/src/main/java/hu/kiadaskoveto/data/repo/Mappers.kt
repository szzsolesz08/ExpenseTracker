package hu.kiadaskoveto.data.repo

import hu.kiadaskoveto.core.money.MeasureUnit
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.money.PackageSize
import hu.kiadaskoveto.core.money.Quantity
import hu.kiadaskoveto.data.WallClock
import hu.kiadaskoveto.database.entity.CategoryEntity
import hu.kiadaskoveto.database.entity.ReceiptDocumentEntity
import hu.kiadaskoveto.database.entity.ReceiptEntity
import hu.kiadaskoveto.database.entity.ReceiptItemEntity
import hu.kiadaskoveto.database.entity.StoreEntity
import hu.kiadaskoveto.domain.Category
import hu.kiadaskoveto.domain.ItemKind
import hu.kiadaskoveto.domain.Receipt
import hu.kiadaskoveto.domain.ReceiptDocument
import hu.kiadaskoveto.domain.ReceiptItem
import hu.kiadaskoveto.domain.ReceiptStatus
import hu.kiadaskoveto.domain.Store
import java.math.BigDecimal

fun CategoryEntity.toDomain() = Category(id, key, name, parentId, colorArgb, iconKey, sortOrder, isSystem)

fun StoreEntity.toDomain() = Store(id, name, chainKey, address)

fun ReceiptDocumentEntity.toDomain() = ReceiptDocument(id, filePath, mimeType, pageIndex, selectedPages)

fun ReceiptEntity.toDomain(storeName: String?) = Receipt(
    id = id,
    storeId = storeId,
    storeName = storeName ?: storeNameRaw,
    purchasedAt = WallClock.fromStored(purchasedAt),
    hasTime = purchasedHasTime,
    dateKnown = dateKnown,
    total = Money(totalMinor),
    printedTotal = printedTotalMinor?.let(::Money),
    payableTotal = payableTotalMinor?.let(::Money),
    computedTotal = Money(computedTotalMinor),
    rounding = roundingMinor?.let(::Money),
    paymentMethod = paymentMethod,
    receiptNumber = receiptNumber,
    apCode = apCode,
    address = address,
    taxNumber = taxNumber,
    status = runCatching { ReceiptStatus.valueOf(status) }.getOrDefault(ReceiptStatus.DRAFT),
    confidence = confidence,
    ocrText = ocrText,
    unparsedText = unparsedText,
    notes = notes,
    duplicateOverride = duplicateOverride,
    failureReason = failureReason,
)

fun ReceiptItemEntity.toDomain() = ReceiptItem(
    id = id,
    receiptId = receiptId,
    position = position,
    kind = runCatching { ItemKind.valueOf(kind) }.getOrDefault(ItemKind.PRODUCT),
    originalText = originalText,
    rawName = rawName,
    displayName = displayName,
    productId = productId,
    categoryId = categoryId,
    quantity = quantityMilli?.let(::Quantity),
    unit = unit?.let { runCatching { MeasureUnit.valueOf(it) }.getOrNull() },
    unitPrice = unitPriceMinor?.let(::Money),
    lineTotal = Money(lineTotalMinor),
    discount = Money(discountMinor),
    discountText = discountText,
    vatCode = vatCode,
    confidence = confidence,
    needsReview = needsReview,
    reviewNote = reviewNote,
    userEdited = userEdited,
)

fun packageSizeOf(amount: String?, unit: String?): PackageSize? {
    val a = amount?.toBigDecimalOrNull() ?: return null
    val u = unit?.let { runCatching { MeasureUnit.valueOf(it) }.getOrNull() } ?: return null
    return PackageSize(a, u)
}

fun PackageSize.amountString(): String = amount.stripTrailingZeros().toPlainString()

fun BigDecimal.toMoney(): Money = Money.fromBigDecimal(this)

package hu.kiadaskoveto.database.model

import androidx.room.Embedded
import hu.kiadaskoveto.database.entity.ProductEntity

/** Row of the receipt list. */
data class ReceiptSummary(
    val id: Long,
    val storeId: Long?,
    val storeName: String?,
    val purchasedAt: Long,
    val purchasedHasTime: Boolean,
    val dateKnown: Boolean,
    val totalMinor: Long,
    val status: String,
    val confidence: Double,
    val itemCount: Int,
    val reviewCount: Int,
    val thumbnailPath: String?,
)

data class PeriodSummary(val total: Long, val count: Int)

data class PeriodTotal(val period: String, val total: Long, val count: Int)

data class CategoryTotal(val categoryId: Long?, val total: Long, val itemCount: Int)

data class CategoryPeriodTotal(val period: String, val categoryId: Long?, val total: Long)

data class StoreTotal(val storeId: Long?, val storeName: String, val total: Long, val visits: Int)

data class ProductStat(
    val productId: Long?,
    val name: String,
    val categoryId: Long?,
    val purchaseCount: Int,
    val receiptCount: Int,
    val total: Long,
    val maxPrice: Long,
    val lastPurchasedAt: Long,
)

data class PricePoint(
    val itemId: Long,
    val receiptId: Long,
    val productId: Long?,
    val purchasedAt: Long,
    val storeId: Long?,
    val storeName: String?,
    val quantityMilli: Long?,
    val unit: String?,
    val unitPriceMinor: Long?,
    val lineTotalMinor: Long,
    val discountMinor: Long,
    val packageSizeAmount: String?,
    val packageSizeUnit: String?,
)

data class ProductWithStats(
    @Embedded val product: ProductEntity,
    val purchaseCount: Int,
    val totalSpent: Long,
    val aliasCount: Int,
)

data class StoreWithStats(
    val id: Long,
    val name: String,
    val chainKey: String?,
    val address: String?,
    val receiptCount: Int,
    val total: Long,
)

data class FingerprintRow(
    val receiptId: Long,
    val storeId: Long?,
    val purchasedAt: Long,
    val purchasedHasTime: Boolean,
    val dateKnown: Boolean,
    val totalMinor: Long,
    val receiptNumber: String?,
    val apCode: String?,
    val itemSignature: String?,
)

data class DocumentHashRow(val receiptId: Long, val sha256: String, val imageHash: Long?)

data class ItemWithReceipt(
    val itemId: Long,
    val receiptId: Long,
    val purchasedAt: Long,
    val storeName: String?,
    val displayName: String,
    val rawName: String,
    val quantityMilli: Long?,
    val unit: String?,
    val unitPriceMinor: Long?,
    val lineTotalMinor: Long,
    val discountMinor: Long,
)

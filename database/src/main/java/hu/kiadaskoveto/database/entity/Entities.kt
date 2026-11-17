package hu.kiadaskoveto.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * Storage conventions
 * -------------------
 * - Money: Long minor units (fillér). Sums in SQL are therefore exact.
 * - Quantities: Long thousandths (0,456 kg → 456).
 * - Purchase time: the wall-clock time printed on the receipt, encoded as epoch millis
 *   *as if it were UTC*. SQL `strftime(..., 'unixepoch')` then yields the printed local date,
 *   independent of the device time zone or DST changes.
 * - Enums are stored by name (TEXT) so that reordering enum constants is harmless.
 */

@Serializable
@Entity(
    tableName = "categories",
    indices = [Index(value = ["key"], unique = true), Index("parentId")],
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.CASCADE)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable key: built-in categories use fixed keys (`dairy`), user categories `user-<uuid>`. */
    val key: String,
    val name: String,
    val parentId: Long? = null,
    val colorArgb: Long,
    val iconKey: String,
    val sortOrder: Int,
    val isSystem: Boolean,
    val createdAt: Long,
)

@Serializable
@Entity(tableName = "stores", indices = [Index(value = ["normalizedName"], unique = true), Index("chainKey")])
data class StoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    /** Parser store profile id (`spar`, `lidl` …) when known. */
    val chainKey: String? = null,
    val address: String? = null,
    val taxNumber: String? = null,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "receipts",
    indices = [Index("storeId"), Index("purchasedAt"), Index("status"), Index("apCode")],
    foreignKeys = [ForeignKey(entity = StoreEntity::class, parentColumns = ["id"], childColumns = ["storeId"], onDelete = ForeignKey.SET_NULL)],
)
data class ReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val storeId: Long? = null,
    val storeNameRaw: String? = null,
    val purchasedAt: Long,
    val purchasedHasTime: Boolean = false,
    /** True when the date was read from the receipt or entered by the user (not the import date). */
    val dateKnown: Boolean = true,
    /** The amount counted as spent: printed total, or payable, or the sum of items. */
    val totalMinor: Long,
    val printedTotalMinor: Long? = null,
    val payableTotalMinor: Long? = null,
    val computedTotalMinor: Long,
    val roundingMinor: Long? = null,
    val paymentMethod: String? = null,
    val receiptNumber: String? = null,
    val apCode: String? = null,
    val taxNumber: String? = null,
    val address: String? = null,
    /** DRAFT (imported, awaiting review), CONFIRMED, FAILED (processing failed, originals kept). */
    val status: String,
    val confidence: Double = 0.0,
    val ocrText: String? = null,
    /** OCR lines the parser could not place (shown in review so nothing is silently dropped). */
    val unparsedText: String? = null,
    val notes: String? = null,
    val itemSignature: String? = null,
    /** Accent-folded upper-case store name + item names, for accent-insensitive search. */
    val searchKey: String = "",
    val duplicateOverride: Boolean = false,
    val failureReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "receipt_items",
    indices = [Index("receiptId"), Index("productId"), Index("categoryId")],
    foreignKeys = [
        ForeignKey(entity = ReceiptEntity::class, parentColumns = ["id"], childColumns = ["receiptId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class ReceiptItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val position: Int,
    /** PRODUCT, DISCOUNT, DEPOSIT, DEPOSIT_RETURN */
    val kind: String,
    /** OCR lines that produced this item, verbatim. Empty for manually added items. */
    val originalText: String,
    /** Merchant description as printed. */
    val rawName: String,
    /** Interpreted / user-edited name. */
    val displayName: String,
    val productId: Long? = null,
    val categoryId: Long? = null,
    val quantityMilli: Long? = null,
    val unit: String? = null,
    val unitPriceMinor: Long? = null,
    val lineTotalMinor: Long,
    val discountMinor: Long = 0,
    val discountText: String? = null,
    val vatCode: String? = null,
    val packageSizeAmount: String? = null,
    val packageSizeUnit: String? = null,
    val confidence: Double = 1.0,
    val needsReview: Boolean = false,
    val reviewNote: String? = null,
    val userEdited: Boolean = false,
    val categorySource: String? = null,
)

@Serializable
@Entity(
    tableName = "receipt_documents",
    indices = [Index("receiptId"), Index("sha256")],
    foreignKeys = [ForeignKey(entity = ReceiptEntity::class, parentColumns = ["id"], childColumns = ["receiptId"], onDelete = ForeignKey.CASCADE)],
)
data class ReceiptDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    /** Path relative to the app's private files directory. */
    val filePath: String,
    val mimeType: String,
    val pageIndex: Int,
    /** SHA-256 of the original file bytes. */
    val sha256: String,
    /** 64-bit difference hash of the page image, for re-photographed duplicates. */
    val imageHash: Long? = null,
    val sizeBytes: Long,
    /** For PDFs: which pages were processed (comma separated, 0-based). */
    val selectedPages: String? = null,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "products",
    indices = [Index("normalizedName"), Index("barcode"), Index("categoryId")],
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL)],
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val brand: String? = null,
    val productType: String? = null,
    val variant: String? = null,
    val categoryId: Long? = null,
    val packageSizeAmount: String? = null,
    val packageSizeUnit: String? = null,
    /** Unit the product is sold in (db, kg, l). */
    val unit: String? = null,
    val barcode: String? = null,
    val firstPurchasedAt: Long? = null,
    val lastPurchasedAt: Long? = null,
    val notes: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A merchant description confirmed to mean a product. storeScope 0 = any store. */
@Serializable
@Entity(
    tableName = "product_aliases",
    indices = [Index(value = ["aliasKey", "storeScope"], unique = true), Index("productId")],
    foreignKeys = [ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.CASCADE)],
)
data class ProductAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val aliasKey: String,
    val originalText: String,
    val storeScope: Long,
    val userConfirmed: Boolean,
    val createdAt: Long,
)

/** A category correction remembered from the user. storeScope 0 = any store. */
@Serializable
@Entity(
    tableName = "category_mappings",
    indices = [Index(value = ["matchKey", "storeScope"], unique = true), Index("categoryId")],
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)],
)
data class CategoryMappingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchKey: String,
    val storeScope: Long,
    val categoryId: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "category_rules",
    indices = [Index("categoryId")],
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)],
)
data class CategoryRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    /** CONTAINS, STARTS_WITH, EXACT, REGEX */
    val matchType: String,
    val categoryId: Long,
    val storeId: Long? = null,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "budgets",
    indices = [Index("categoryId"), Index("storeId")],
    foreignKeys = [
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = StoreEntity::class, parentColumns = ["id"], childColumns = ["storeId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** TOTAL, CATEGORY, STORE */
    val scope: String,
    val categoryId: Long? = null,
    val storeId: Long? = null,
    /** MONTHLY, WEEKLY */
    val period: String,
    val limitMinor: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Records which budget thresholds were already notified in a period, so each warning fires once. */
@Serializable
@Entity(
    tableName = "budget_alerts",
    indices = [Index(value = ["budgetId", "periodKey", "thresholdPercent"], unique = true)],
    foreignKeys = [ForeignKey(entity = BudgetEntity::class, parentColumns = ["id"], childColumns = ["budgetId"], onDelete = ForeignKey.CASCADE)],
)
data class BudgetAlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val budgetId: Long,
    val periodKey: String,
    @ColumnInfo(name = "thresholdPercent") val thresholdPercent: Int,
    val notifiedAt: Long,
)

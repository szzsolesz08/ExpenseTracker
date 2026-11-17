package hu.kiadaskoveto.database.dao

import androidx.room.Dao
import androidx.room.Query
import hu.kiadaskoveto.database.model.CategoryPeriodTotal
import hu.kiadaskoveto.database.model.CategoryTotal
import hu.kiadaskoveto.database.model.PeriodSummary
import hu.kiadaskoveto.database.model.PeriodTotal
import hu.kiadaskoveto.database.model.PricePoint
import hu.kiadaskoveto.database.model.ProductStat
import hu.kiadaskoveto.database.model.StoreTotal
import kotlinx.coroutines.flow.Flow

/**
 * Aggregations over confirmed receipts only. Time bounds are half-open [from, to) in the
 * wall-clock-as-UTC encoding described in Entities.kt. All sums are exact integer fillér.
 */
@Dao
interface AnalyticsDao {

    @Query("SELECT COALESCE(SUM(totalMinor), 0) AS total, COUNT(*) AS count FROM receipts WHERE status = 'CONFIRMED' AND purchasedAt >= :from AND purchasedAt < :to")
    fun observePeriodSummary(from: Long, to: Long): Flow<PeriodSummary>

    @Query("SELECT COALESCE(SUM(totalMinor), 0) AS total, COUNT(*) AS count FROM receipts WHERE status = 'CONFIRMED' AND purchasedAt >= :from AND purchasedAt < :to")
    suspend fun periodSummary(from: Long, to: Long): PeriodSummary

    @Query(
        """
        SELECT strftime('%Y-%m', purchasedAt / 1000, 'unixepoch') AS period, SUM(totalMinor) AS total, COUNT(*) AS count
        FROM receipts WHERE status = 'CONFIRMED' AND purchasedAt >= :from AND purchasedAt < :to
        GROUP BY period ORDER BY period
        """
    )
    fun observeMonthlyTotals(from: Long, to: Long): Flow<List<PeriodTotal>>

    @Query(
        """
        SELECT strftime('%Y-%m-%d', purchasedAt / 1000, 'unixepoch') AS period, SUM(totalMinor) AS total, COUNT(*) AS count
        FROM receipts WHERE status = 'CONFIRMED' AND purchasedAt >= :from AND purchasedAt < :to
        GROUP BY period ORDER BY period
        """
    )
    fun observeDailyTotals(from: Long, to: Long): Flow<List<PeriodTotal>>

    @Query(
        """
        SELECT i.categoryId AS categoryId, SUM(i.lineTotalMinor + i.discountMinor) AS total, COUNT(*) AS itemCount
        FROM receipt_items i JOIN receipts r ON r.id = i.receiptId
        WHERE r.status = 'CONFIRMED' AND r.purchasedAt >= :from AND r.purchasedAt < :to AND i.kind != 'DISCOUNT'
        GROUP BY i.categoryId ORDER BY total DESC
        """
    )
    fun observeCategoryTotals(from: Long, to: Long): Flow<List<CategoryTotal>>

    @Query(
        """
        SELECT i.categoryId AS categoryId, SUM(i.lineTotalMinor + i.discountMinor) AS total, COUNT(*) AS itemCount
        FROM receipt_items i JOIN receipts r ON r.id = i.receiptId
        WHERE r.status = 'CONFIRMED' AND r.purchasedAt >= :from AND r.purchasedAt < :to AND i.kind != 'DISCOUNT'
        GROUP BY i.categoryId
        """
    )
    suspend fun categoryTotals(from: Long, to: Long): List<CategoryTotal>

    /** Receipt-level discounts (coupons not tied to a product) — shown separately from categories. */
    @Query(
        """
        SELECT COALESCE(SUM(i.lineTotalMinor + i.discountMinor), 0)
        FROM receipt_items i JOIN receipts r ON r.id = i.receiptId
        WHERE r.status = 'CONFIRMED' AND r.purchasedAt >= :from AND r.purchasedAt < :to AND i.kind = 'DISCOUNT'
        """
    )
    fun observeReceiptLevelDiscounts(from: Long, to: Long): Flow<Long>

    /** Total savings: item-level discounts plus receipt-level discounts (a negative number). */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN i.kind = 'DISCOUNT' THEN i.lineTotalMinor + i.discountMinor ELSE i.discountMinor END), 0)
        FROM receipt_items i JOIN receipts r ON r.id = i.receiptId
        WHERE r.status = 'CONFIRMED' AND r.purchasedAt >= :from AND r.purchasedAt < :to
        """
    )
    fun observeSavings(from: Long, to: Long): Flow<Long>

    @Query(
        """
        SELECT strftime('%Y-%m', r.purchasedAt / 1000, 'unixepoch') AS period, i.categoryId AS categoryId,
               SUM(i.lineTotalMinor + i.discountMinor) AS total
        FROM receipt_items i JOIN receipts r ON r.id = i.receiptId
        WHERE r.status = 'CONFIRMED' AND r.purchasedAt >= :from AND r.purchasedAt < :to AND i.kind != 'DISCOUNT'
        GROUP BY period, i.categoryId ORDER BY period
        """
    )
    fun observeCategoryMonthly(from: Long, to: Long): Flow<List<CategoryPeriodTotal>>

    @Query(
        """
        SELECT r.storeId AS storeId, COALESCE(s.name, r.storeNameRaw, 'Ismeretlen bolt') AS storeName,
               SUM(r.totalMinor) AS total, COUNT(*) AS visits
        FROM receipts r LEFT JOIN stores s ON s.id = r.storeId
        WHERE r.status = 'CONFIRMED' AND r.purchasedAt >= :from AND r.purchasedAt < :to
        GROUP BY r.storeId, storeName ORDER BY total DESC
        """
    )
    fun observeStoreTotals(from: Long, to: Long): Flow<List<StoreTotal>>

    @Query(
        """
        SELECT r.storeId AS storeId, COALESCE(s.name, r.storeNameRaw, 'Ismeretlen bolt') AS storeName,
               SUM(r.totalMinor) AS total, COUNT(*) AS visits
        FROM receipts r LEFT JOIN stores s ON s.id = r.storeId
        WHERE r.status = 'CONFIRMED' AND r.purchasedAt >= :from AND r.purchasedAt < :to
        GROUP BY r.storeId, storeName
        """
    )
    suspend fun storeTotals(from: Long, to: Long): List<StoreTotal>

    @Query(
        """
        SELECT i.productId AS productId, COALESCE(p.name, i.displayName) AS name, COALESCE(p.categoryId, MAX(i.categoryId)) AS categoryId,
               COUNT(*) AS purchaseCount, COUNT(DISTINCT r.id) AS receiptCount,
               SUM(i.lineTotalMinor + i.discountMinor) AS total, MAX(i.lineTotalMinor + i.discountMinor) AS maxPrice,
               MAX(r.purchasedAt) AS lastPurchasedAt
        FROM receipt_items i JOIN receipts r ON r.id = i.receiptId LEFT JOIN products p ON p.id = i.productId
        WHERE r.status = 'CONFIRMED' AND i.kind = 'PRODUCT' AND r.purchasedAt >= :from AND r.purchasedAt < :to
        GROUP BY COALESCE('p' || i.productId, 'n' || i.displayName)
        ORDER BY CASE :orderBy WHEN 'TOTAL' THEN SUM(i.lineTotalMinor + i.discountMinor)
                               WHEN 'MAX_PRICE' THEN MAX(i.lineTotalMinor + i.discountMinor)
                               ELSE COUNT(*) END DESC,
                 total DESC
        LIMIT :limit
        """
    )
    fun observeProductStats(from: Long, to: Long, orderBy: String, limit: Int): Flow<List<ProductStat>>

    @Query(
        """
        SELECT i.id AS itemId, r.id AS receiptId, i.productId AS productId, r.purchasedAt, r.storeId,
               COALESCE(s.name, r.storeNameRaw) AS storeName, i.quantityMilli, i.unit, i.unitPriceMinor,
               i.lineTotalMinor, i.discountMinor, i.packageSizeAmount, i.packageSizeUnit
        FROM receipt_items i JOIN receipts r ON r.id = i.receiptId LEFT JOIN stores s ON s.id = r.storeId
        WHERE i.productId = :productId AND r.status = 'CONFIRMED' AND i.kind = 'PRODUCT'
        ORDER BY r.purchasedAt, i.id
        """
    )
    fun observePriceHistory(productId: Long): Flow<List<PricePoint>>

    @Query(
        """
        SELECT i.id AS itemId, r.id AS receiptId, i.productId AS productId, r.purchasedAt, r.storeId,
               COALESCE(s.name, r.storeNameRaw) AS storeName, i.quantityMilli, i.unit, i.unitPriceMinor,
               i.lineTotalMinor, i.discountMinor, i.packageSizeAmount, i.packageSizeUnit
        FROM receipt_items i JOIN receipts r ON r.id = i.receiptId LEFT JOIN stores s ON s.id = r.storeId
        WHERE i.productId IS NOT NULL AND r.status = 'CONFIRMED' AND i.kind = 'PRODUCT'
        ORDER BY i.productId, r.purchasedAt, i.id
        """
    )
    fun observeAllPricePoints(): Flow<List<PricePoint>>

    @Query("SELECT MIN(purchasedAt) FROM receipts WHERE status = 'CONFIRMED'")
    fun observeFirstPurchase(): Flow<Long?>
}

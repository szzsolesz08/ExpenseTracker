package hu.kiadaskoveto.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import hu.kiadaskoveto.database.entity.BudgetAlertEntity
import hu.kiadaskoveto.database.entity.BudgetEntity
import hu.kiadaskoveto.database.entity.CategoryEntity
import hu.kiadaskoveto.database.entity.CategoryMappingEntity
import hu.kiadaskoveto.database.entity.CategoryRuleEntity
import hu.kiadaskoveto.database.entity.ProductAliasEntity
import hu.kiadaskoveto.database.entity.ProductEntity
import hu.kiadaskoveto.database.entity.StoreEntity
import hu.kiadaskoveto.database.model.ItemWithReceipt
import hu.kiadaskoveto.database.model.ProductWithStats
import hu.kiadaskoveto.database.model.StoreWithStats
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Insert suspend fun insert(category: CategoryEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAllIgnore(categories: List<CategoryEntity>): List<Long>
    @Update suspend fun update(category: CategoryEntity)
    @Query("DELETE FROM categories WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM categories ORDER BY sortOrder, name") fun observeAll(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories ORDER BY sortOrder, name") suspend fun all(): List<CategoryEntity>
    @Query("SELECT * FROM categories WHERE id = :id") suspend fun byId(id: Long): CategoryEntity?
    @Query("SELECT * FROM categories WHERE `key` = :key") suspend fun byKey(key: String): CategoryEntity?
    @Query("SELECT COUNT(*) FROM categories") suspend fun count(): Int
    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM categories") suspend fun nextSortOrder(): Int
    @Query("SELECT id FROM categories WHERE parentId = :id") suspend fun childIds(id: Long): List<Long>
    @Query("UPDATE receipt_items SET categoryId = :target WHERE categoryId IN (:sources)")
    suspend fun moveItems(sources: List<Long>, target: Long)
    @Query("UPDATE products SET categoryId = :target WHERE categoryId IN (:sources)")
    suspend fun moveProducts(sources: List<Long>, target: Long)
}

@Dao
interface StoreDao {
    @Insert suspend fun insert(store: StoreEntity): Long
    @Update suspend fun update(store: StoreEntity)
    @Query("DELETE FROM stores WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM stores WHERE normalizedName = :normalizedName LIMIT 1") suspend fun byNormalizedName(normalizedName: String): StoreEntity?
    @Query("SELECT * FROM stores WHERE id = :id") suspend fun byId(id: Long): StoreEntity?
    @Query("SELECT * FROM stores ORDER BY name COLLATE NOCASE") fun observeAll(): Flow<List<StoreEntity>>
    @Query("SELECT * FROM stores ORDER BY name COLLATE NOCASE") suspend fun all(): List<StoreEntity>
    @Query(
        """
        SELECT s.id, s.name, s.chainKey, s.address,
               (SELECT COUNT(*) FROM receipts r WHERE r.storeId = s.id AND r.status = 'CONFIRMED') AS receiptCount,
               (SELECT COALESCE(SUM(r.totalMinor), 0) FROM receipts r WHERE r.storeId = s.id AND r.status = 'CONFIRMED') AS total
        FROM stores s ORDER BY s.name COLLATE NOCASE
        """
    )
    fun observeWithStats(): Flow<List<StoreWithStats>>
    @Query("UPDATE receipts SET storeId = :target WHERE storeId = :source") suspend fun moveReceipts(source: Long, target: Long)
}

@Dao
interface ProductDao {
    @Insert suspend fun insert(product: ProductEntity): Long
    @Update suspend fun update(product: ProductEntity)
    @Query("DELETE FROM products WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM products WHERE id = :id") suspend fun byId(id: Long): ProductEntity?
    @Query("SELECT * FROM products WHERE id = :id") fun observe(id: Long): Flow<ProductEntity?>
    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1") suspend fun byBarcode(barcode: String): ProductEntity?
    @Query("SELECT * FROM products") suspend fun all(): List<ProductEntity>

    @Query(
        """
        SELECT p.*,
               (SELECT COUNT(*) FROM receipt_items i JOIN receipts r ON r.id = i.receiptId
                 WHERE i.productId = p.id AND r.status = 'CONFIRMED') AS purchaseCount,
               (SELECT COALESCE(SUM(i.lineTotalMinor + i.discountMinor), 0) FROM receipt_items i JOIN receipts r ON r.id = i.receiptId
                 WHERE i.productId = p.id AND r.status = 'CONFIRMED') AS totalSpent,
               (SELECT COUNT(*) FROM product_aliases a WHERE a.productId = p.id) AS aliasCount
        FROM products p ORDER BY p.name COLLATE NOCASE
        """
    )
    fun observeWithStats(): Flow<List<ProductWithStats>>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAlias(alias: ProductAliasEntity): Long
    @Upsert suspend fun upsertAlias(alias: ProductAliasEntity)
    @Query("DELETE FROM product_aliases WHERE id = :id") suspend fun deleteAlias(id: Long)
    @Query("SELECT * FROM product_aliases") suspend fun allAliases(): List<ProductAliasEntity>
    @Query("SELECT * FROM product_aliases WHERE productId = :productId ORDER BY originalText")
    fun observeAliases(productId: Long): Flow<List<ProductAliasEntity>>
    @Query("SELECT * FROM product_aliases WHERE productId = :productId") suspend fun aliasesOf(productId: Long): List<ProductAliasEntity>
    @Query("SELECT * FROM product_aliases WHERE aliasKey = :aliasKey AND storeScope = :storeScope LIMIT 1")
    suspend fun alias(aliasKey: String, storeScope: Long): ProductAliasEntity?

    @Query("UPDATE receipt_items SET productId = :target WHERE productId = :source") suspend fun moveItems(source: Long, target: Long)
    @Query("UPDATE receipt_items SET productId = :target WHERE productId = :source AND rawName = :rawName")
    suspend fun moveItemsWithRawName(source: Long, target: Long, rawName: String)

    @Query(
        """
        UPDATE products SET
          firstPurchasedAt = (SELECT MIN(r.purchasedAt) FROM receipt_items i JOIN receipts r ON r.id = i.receiptId WHERE i.productId = products.id AND r.status = 'CONFIRMED'),
          lastPurchasedAt  = (SELECT MAX(r.purchasedAt) FROM receipt_items i JOIN receipts r ON r.id = i.receiptId WHERE i.productId = products.id AND r.status = 'CONFIRMED')
        WHERE id = :productId
        """
    )
    suspend fun refreshPurchaseDates(productId: Long)

    @Query(
        """
        SELECT i.id AS itemId, r.id AS receiptId, r.purchasedAt, COALESCE(s.name, r.storeNameRaw) AS storeName,
               i.displayName, i.rawName, i.quantityMilli, i.unit, i.unitPriceMinor, i.lineTotalMinor, i.discountMinor
        FROM receipt_items i JOIN receipts r ON r.id = i.receiptId LEFT JOIN stores s ON s.id = r.storeId
        WHERE i.productId = :productId ORDER BY r.purchasedAt DESC
        """
    )
    fun observePurchases(productId: Long): Flow<List<ItemWithReceipt>>

    /**
     * Auto-created products that no receipt line references any more (cleanup after deletes).
     * Products the user curated (edited, or given a barcode) are kept; their aliases cascade with them.
     */
    @Query("DELETE FROM products WHERE id NOT IN (SELECT DISTINCT productId FROM receipt_items WHERE productId IS NOT NULL) AND barcode IS NULL AND updatedAt = createdAt")
    suspend fun deleteOrphans(): Int
}

@Dao
interface RuleDao {
    @Upsert suspend fun upsertMapping(mapping: CategoryMappingEntity)
    @Query("SELECT * FROM category_mappings WHERE matchKey = :matchKey AND storeScope = :storeScope LIMIT 1")
    suspend fun mapping(matchKey: String, storeScope: Long): CategoryMappingEntity?
    @Query("SELECT * FROM category_mappings") suspend fun allMappings(): List<CategoryMappingEntity>
    @Query("SELECT * FROM category_mappings ORDER BY updatedAt DESC") fun observeMappings(): Flow<List<CategoryMappingEntity>>
    @Query("DELETE FROM category_mappings WHERE id = :id") suspend fun deleteMapping(id: Long)

    @Insert suspend fun insertRule(rule: CategoryRuleEntity): Long
    @Update suspend fun updateRule(rule: CategoryRuleEntity)
    @Query("DELETE FROM category_rules WHERE id = :id") suspend fun deleteRule(id: Long)
    @Query("SELECT * FROM category_rules ORDER BY priority DESC, createdAt") suspend fun allRules(): List<CategoryRuleEntity>
    @Query("SELECT * FROM category_rules ORDER BY priority DESC, createdAt") fun observeRules(): Flow<List<CategoryRuleEntity>>
}

@Dao
interface BudgetDao {
    @Insert suspend fun insert(budget: BudgetEntity): Long
    @Update suspend fun update(budget: BudgetEntity)
    @Query("DELETE FROM budgets WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM budgets ORDER BY CASE scope WHEN 'TOTAL' THEN 0 WHEN 'CATEGORY' THEN 1 ELSE 2 END, id")
    fun observeAll(): Flow<List<BudgetEntity>>
    @Query("SELECT * FROM budgets") suspend fun all(): List<BudgetEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAlert(alert: BudgetAlertEntity): Long
    @Query("SELECT COUNT(*) FROM budget_alerts WHERE budgetId = :budgetId AND periodKey = :periodKey AND thresholdPercent = :threshold")
    suspend fun alertCount(budgetId: Long, periodKey: String, threshold: Int): Int
}

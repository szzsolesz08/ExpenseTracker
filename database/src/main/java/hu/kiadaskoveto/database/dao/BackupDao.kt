package hu.kiadaskoveto.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import hu.kiadaskoveto.database.entity.BudgetAlertEntity
import hu.kiadaskoveto.database.entity.BudgetEntity
import hu.kiadaskoveto.database.entity.CategoryEntity
import hu.kiadaskoveto.database.entity.CategoryMappingEntity
import hu.kiadaskoveto.database.entity.CategoryRuleEntity
import hu.kiadaskoveto.database.entity.ProductAliasEntity
import hu.kiadaskoveto.database.entity.ProductEntity
import hu.kiadaskoveto.database.entity.ReceiptDocumentEntity
import hu.kiadaskoveto.database.entity.ReceiptEntity
import hu.kiadaskoveto.database.entity.ReceiptItemEntity
import hu.kiadaskoveto.database.entity.StoreEntity

/** Whole-table access used by backup/export and restore. Restores keep the original ids. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM categories ORDER BY CASE WHEN parentId IS NULL THEN 0 ELSE 1 END, id") suspend fun categories(): List<CategoryEntity>
    @Query("SELECT * FROM stores ORDER BY id") suspend fun stores(): List<StoreEntity>
    @Query("SELECT * FROM products ORDER BY id") suspend fun products(): List<ProductEntity>
    @Query("SELECT * FROM receipts ORDER BY id") suspend fun receipts(): List<ReceiptEntity>
    @Query("SELECT * FROM receipt_items ORDER BY receiptId, position, id") suspend fun items(): List<ReceiptItemEntity>
    @Query("SELECT * FROM receipt_documents ORDER BY id") suspend fun documents(): List<ReceiptDocumentEntity>
    @Query("SELECT * FROM product_aliases ORDER BY id") suspend fun aliases(): List<ProductAliasEntity>
    @Query("SELECT * FROM category_mappings ORDER BY id") suspend fun mappings(): List<CategoryMappingEntity>
    @Query("SELECT * FROM category_rules ORDER BY id") suspend fun rules(): List<CategoryRuleEntity>
    @Query("SELECT * FROM budgets ORDER BY id") suspend fun budgets(): List<BudgetEntity>
    @Query("SELECT * FROM budget_alerts ORDER BY id") suspend fun budgetAlerts(): List<BudgetAlertEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCategories(rows: List<CategoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putStores(rows: List<StoreEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProducts(rows: List<ProductEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putReceipts(rows: List<ReceiptEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putItems(rows: List<ReceiptItemEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDocuments(rows: List<ReceiptDocumentEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAliases(rows: List<ProductAliasEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMappings(rows: List<CategoryMappingEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRules(rows: List<CategoryRuleEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBudgets(rows: List<BudgetEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBudgetAlerts(rows: List<BudgetAlertEntity>)
}

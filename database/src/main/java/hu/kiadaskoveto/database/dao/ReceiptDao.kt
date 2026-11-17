package hu.kiadaskoveto.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import hu.kiadaskoveto.database.entity.ReceiptDocumentEntity
import hu.kiadaskoveto.database.entity.ReceiptEntity
import hu.kiadaskoveto.database.entity.ReceiptItemEntity
import hu.kiadaskoveto.database.model.DocumentHashRow
import hu.kiadaskoveto.database.model.FingerprintRow
import hu.kiadaskoveto.database.model.ReceiptSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {

    @Insert suspend fun insertReceipt(receipt: ReceiptEntity): Long
    @Update suspend fun updateReceipt(receipt: ReceiptEntity)
    @Query("DELETE FROM receipts WHERE id = :id") suspend fun deleteReceipt(id: Long)

    @Insert suspend fun insertItems(items: List<ReceiptItemEntity>): List<Long>
    @Insert suspend fun insertItem(item: ReceiptItemEntity): Long
    @Update suspend fun updateItem(item: ReceiptItemEntity)
    @Query("DELETE FROM receipt_items WHERE id = :id") suspend fun deleteItem(id: Long)
    @Query("DELETE FROM receipt_items WHERE receiptId = :receiptId") suspend fun deleteItemsOf(receiptId: Long)

    @Insert suspend fun insertDocument(document: ReceiptDocumentEntity): Long
    @Query("SELECT * FROM receipt_documents WHERE receiptId = :receiptId ORDER BY pageIndex")
    suspend fun documents(receiptId: Long): List<ReceiptDocumentEntity>
    @Query("SELECT * FROM receipt_documents WHERE receiptId = :receiptId ORDER BY pageIndex")
    fun observeDocuments(receiptId: Long): Flow<List<ReceiptDocumentEntity>>
    @Query("UPDATE receipt_documents SET imageHash = :hash WHERE receiptId = :receiptId AND pageIndex = :pageIndex")
    suspend fun setDocumentImageHash(receiptId: Long, pageIndex: Int, hash: Long)

    @Query("SELECT filePath FROM receipt_documents")
    suspend fun allDocumentPaths(): List<String>

    @Query("SELECT * FROM receipts WHERE id = :id") suspend fun receipt(id: Long): ReceiptEntity?
    @Query("SELECT * FROM receipts WHERE id = :id") fun observeReceipt(id: Long): Flow<ReceiptEntity?>
    @Query("SELECT * FROM receipt_items WHERE receiptId = :receiptId ORDER BY position, id")
    suspend fun items(receiptId: Long): List<ReceiptItemEntity>
    @Query("SELECT * FROM receipt_items WHERE receiptId = :receiptId ORDER BY position, id")
    fun observeItems(receiptId: Long): Flow<List<ReceiptItemEntity>>
    @Query("SELECT * FROM receipt_items WHERE id = :id") suspend fun item(id: Long): ReceiptItemEntity?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM receipt_items WHERE receiptId = :receiptId")
    suspend fun nextPosition(receiptId: Long): Int

    @Query(
        """
        SELECT r.id, r.storeId, COALESCE(s.name, r.storeNameRaw) AS storeName, r.purchasedAt, r.purchasedHasTime,
               r.dateKnown, r.totalMinor, r.status, r.confidence,
               (SELECT COUNT(*) FROM receipt_items i WHERE i.receiptId = r.id AND i.kind = 'PRODUCT') AS itemCount,
               (SELECT COUNT(*) FROM receipt_items i WHERE i.receiptId = r.id AND i.needsReview = 1) AS reviewCount,
               (SELECT d.filePath FROM receipt_documents d WHERE d.receiptId = r.id ORDER BY d.pageIndex LIMIT 1) AS thumbnailPath
        FROM receipts r LEFT JOIN stores s ON s.id = r.storeId
        WHERE (:query = '' OR r.searchKey LIKE '%' || :query || '%')
          AND (:storeId IS NULL OR r.storeId = :storeId)
          AND r.purchasedAt >= :from AND r.purchasedAt < :to
          AND r.totalMinor >= :minTotal AND r.totalMinor <= :maxTotal
          AND (:includeDrafts = 1 OR r.status = 'CONFIRMED')
        ORDER BY CASE WHEN r.status = 'CONFIRMED' THEN 1 ELSE 0 END, r.purchasedAt DESC, r.id DESC
        """
    )
    fun search(
        query: String,
        storeId: Long?,
        from: Long,
        to: Long,
        minTotal: Long,
        maxTotal: Long,
        includeDrafts: Boolean,
    ): Flow<List<ReceiptSummary>>

    @Query(
        """
        SELECT r.id, r.storeId, COALESCE(s.name, r.storeNameRaw) AS storeName, r.purchasedAt, r.purchasedHasTime,
               r.dateKnown, r.totalMinor, r.status, r.confidence,
               (SELECT COUNT(*) FROM receipt_items i WHERE i.receiptId = r.id AND i.kind = 'PRODUCT') AS itemCount,
               (SELECT COUNT(*) FROM receipt_items i WHERE i.receiptId = r.id AND i.needsReview = 1) AS reviewCount,
               (SELECT d.filePath FROM receipt_documents d WHERE d.receiptId = r.id ORDER BY d.pageIndex LIMIT 1) AS thumbnailPath
        FROM receipts r LEFT JOIN stores s ON s.id = r.storeId
        WHERE r.status = 'CONFIRMED'
        ORDER BY r.purchasedAt DESC, r.id DESC LIMIT :limit
        """
    )
    fun observeRecent(limit: Int): Flow<List<ReceiptSummary>>

    @Query("SELECT COUNT(*) FROM receipts WHERE status != 'CONFIRMED'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM receipts") suspend fun count(): Int

    @Query(
        """
        SELECT r.id AS receiptId, r.storeId, r.purchasedAt, r.purchasedHasTime, r.dateKnown, r.totalMinor,
               r.receiptNumber, r.apCode, r.itemSignature
        FROM receipts r WHERE r.status != 'FAILED'
        """
    )
    suspend fun fingerprintRows(): List<FingerprintRow>

    @Query("SELECT receiptId, sha256, imageHash FROM receipt_documents")
    suspend fun documentHashes(): List<DocumentHashRow>

    /** Confirmed receipts whose stored item sum disagrees with their items (consistency check). */
    @Query(
        """
        SELECT r.id FROM receipts r
        WHERE r.status = 'CONFIRMED' AND r.computedTotalMinor !=
              (SELECT COALESCE(SUM(i.lineTotalMinor + i.discountMinor), 0) FROM receipt_items i WHERE i.receiptId = r.id)
        """
    )
    suspend fun receiptsWithStaleItemSum(): List<Long>

    @Query("UPDATE receipts SET computedTotalMinor = (SELECT COALESCE(SUM(i.lineTotalMinor + i.discountMinor), 0) FROM receipt_items i WHERE i.receiptId = receipts.id) WHERE id = :id")
    suspend fun recomputeItemSum(id: Long)

    @Transaction
    suspend fun replaceItems(receiptId: Long, items: List<ReceiptItemEntity>) {
        deleteItemsOf(receiptId)
        insertItems(items)
    }

    @Query("UPDATE receipts SET storeId = :storeId, updatedAt = :now WHERE id = :receiptId")
    suspend fun setStore(receiptId: Long, storeId: Long?, now: Long)

    @Query("UPDATE receipt_items SET categoryId = :categoryId WHERE id = :itemId")
    suspend fun setItemCategory(itemId: Long, categoryId: Long?)
}

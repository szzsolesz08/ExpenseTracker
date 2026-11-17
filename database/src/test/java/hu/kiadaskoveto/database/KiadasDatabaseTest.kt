package hu.kiadaskoveto.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hu.kiadaskoveto.database.entity.CategoryEntity
import hu.kiadaskoveto.database.entity.ProductAliasEntity
import hu.kiadaskoveto.database.entity.ProductEntity
import hu.kiadaskoveto.database.entity.ReceiptDocumentEntity
import hu.kiadaskoveto.database.entity.ReceiptEntity
import hu.kiadaskoveto.database.entity.ReceiptItemEntity
import hu.kiadaskoveto.database.entity.StoreEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class KiadasDatabaseTest {

    private lateinit var db: KiadasDatabase

    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).toEpochSecond(ZoneOffset.UTC) * 1000

    @Before fun setUp() { db = KiadasDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun tearDown() { db.close() }

    private suspend fun seed(): Triple<Long, Long, Long> {
        val dairy = db.categoryDao().insert(CategoryEntity(key = "dairy", name = "Tejtermékek", colorArgb = 0xFF0000, iconKey = "dairy", sortOrder = 1, isSystem = true, createdAt = 0))
        val produce = db.categoryDao().insert(CategoryEntity(key = "produce", name = "Zöldség és gyümölcs", colorArgb = 0x00FF00, iconKey = "produce", sortOrder = 0, isSystem = true, createdAt = 0))
        val store = db.storeDao().insert(StoreEntity(name = "SPAR", normalizedName = "SPAR", chainKey = "spar", createdAt = 0))
        return Triple(dairy, produce, store)
    }

    private fun receipt(storeId: Long?, at: Long, total: Long, status: String = "CONFIRMED", search: String = "") =
        ReceiptEntity(storeId = storeId, purchasedAt = at, totalMinor = total, computedTotalMinor = total, status = status,
            searchKey = search, createdAt = 0, updatedAt = 0)

    private fun item(receiptId: Long, pos: Int, name: String, total: Long, categoryId: Long?, discount: Long = 0, kind: String = "PRODUCT", productId: Long? = null) =
        ReceiptItemEntity(receiptId = receiptId, position = pos, kind = kind, originalText = name, rawName = name, displayName = name,
            categoryId = categoryId, lineTotalMinor = total, discountMinor = discount, productId = productId)

    @Test fun deletingReceiptCascadesToItemsAndDocuments() = runTest {
        val (dairy, _, store) = seed()
        val r = db.receiptDao().insertReceipt(receipt(store, at(2024, 3, 15), 32900))
        db.receiptDao().insertItems(listOf(item(r, 0, "TEJ", 32900, dairy)))
        db.receiptDao().insertDocument(ReceiptDocumentEntity(receiptId = r, filePath = "receipts/x.jpg", mimeType = "image/jpeg", pageIndex = 0, sha256 = "abc", sizeBytes = 10, createdAt = 0))
        db.receiptDao().deleteReceipt(r)
        assertTrue(db.receiptDao().items(r).isEmpty())
        assertTrue(db.receiptDao().documents(r).isEmpty())
    }

    @Test fun deletingStoreKeepsReceipts() = runTest {
        val (_, _, store) = seed()
        val r = db.receiptDao().insertReceipt(receipt(store, at(2024, 3, 15), 100))
        db.storeDao().delete(store)
        assertNull(db.receiptDao().receipt(r)!!.storeId)
    }

    @Test fun monthlyTotalsUseThePrintedWallClockDate() = runTest {
        val (_, _, store) = seed()
        // 23:30 on the last day of March must count for March regardless of device time zone.
        db.receiptDao().insertReceipt(receipt(store, LocalDateTime.of(2024, 3, 31, 23, 30).toEpochSecond(ZoneOffset.UTC) * 1000, 100_000))
        db.receiptDao().insertReceipt(receipt(store, at(2024, 4, 1, 8), 50_000))
        db.receiptDao().insertReceipt(receipt(store, at(2024, 4, 2), 99_999, status = "DRAFT"))
        val months = db.analyticsDao().observeMonthlyTotals(at(2024, 1, 1), at(2025, 1, 1)).first()
        assertEquals(listOf("2024-03" to 100_000L, "2024-04" to 50_000L), months.map { it.period to it.total })
        val april = db.analyticsDao().periodSummary(at(2024, 4, 1, 0), at(2024, 5, 1, 0))
        assertEquals("drafts are not counted", 1, april.count)
    }

    @Test fun categoryTotalsAreExactAndExcludeReceiptLevelDiscounts() = runTest {
        val (dairy, produce, store) = seed()
        val r = db.receiptDao().insertReceipt(receipt(store, at(2024, 3, 15), 75_100))
        db.receiptDao().insertItems(listOf(
            item(r, 0, "TEJ", 32_900, dairy, discount = -10_000),
            item(r, 1, "SAJT", 59_900, dairy),
            item(r, 2, "BANAN", 12_300, produce),
            item(r, 3, "KUPON", -20_000, null, kind = "DISCOUNT"),
        ))
        val totals = db.analyticsDao().observeCategoryTotals(at(2024, 3, 1), at(2024, 4, 1)).first().associate { it.categoryId to it.total }
        assertEquals(82_800L, totals[dairy])
        assertEquals(12_300L, totals[produce])
        assertEquals(-20_000L, db.analyticsDao().observeReceiptLevelDiscounts(at(2024, 3, 1), at(2024, 4, 1)).first())
        assertEquals(-30_000L, db.analyticsDao().observeSavings(at(2024, 3, 1), at(2024, 4, 1)).first())
        assertTrue(db.receiptDao().receiptsWithStaleItemSum().isEmpty())
    }

    @Test fun priceHistoryAndPurchaseDates() = runTest {
        val (dairy, _, store) = seed()
        val p = db.productDao().insert(ProductEntity(name = "Tej", normalizedName = "TEJ", categoryId = dairy, createdAt = 0, updatedAt = 0))
        val r1 = db.receiptDao().insertReceipt(receipt(store, at(2024, 1, 5), 29_900))
        val r2 = db.receiptDao().insertReceipt(receipt(store, at(2024, 2, 5), 32_900))
        db.receiptDao().insertItems(listOf(item(r1, 0, "TEJ", 29_900, dairy, productId = p), item(r2, 0, "TEJ", 32_900, dairy, productId = p)))
        db.productDao().refreshPurchaseDates(p)
        val product = db.productDao().byId(p)!!
        assertEquals(at(2024, 1, 5), product.firstPurchasedAt)
        assertEquals(at(2024, 2, 5), product.lastPurchasedAt)
        val history = db.analyticsDao().observePriceHistory(p).first()
        assertEquals(listOf(29_900L, 32_900L), history.map { it.lineTotalMinor })
        val stats = db.productDao().observeWithStats().first().single()
        assertEquals(2, stats.purchaseCount)
        assertEquals(62_800L, stats.totalSpent)
    }

    @Test fun aliasesAreUniquePerStoreScope() = runTest {
        val p = db.productDao().insert(ProductEntity(name = "Tej", normalizedName = "TEJ", createdAt = 0, updatedAt = 0))
        assertTrue(db.productDao().insertAlias(ProductAliasEntity(productId = p, aliasKey = "TEJ 1L", originalText = "TEJ 1L", storeScope = 1, userConfirmed = true, createdAt = 0)) > 0)
        assertEquals(-1L, db.productDao().insertAlias(ProductAliasEntity(productId = p, aliasKey = "TEJ 1L", originalText = "TEJ 1L", storeScope = 1, userConfirmed = true, createdAt = 0)))
        assertTrue(db.productDao().insertAlias(ProductAliasEntity(productId = p, aliasKey = "TEJ 1L", originalText = "TEJ 1L", storeScope = 0, userConfirmed = true, createdAt = 0)) > 0)
    }

    @Test fun searchIsAccentInsensitiveViaSearchKey() = runTest {
        val (_, _, store) = seed()
        db.receiptDao().insertReceipt(receipt(store, at(2024, 3, 15), 100, search = "SPAR TURO RUDI"))
        db.receiptDao().insertReceipt(receipt(store, at(2024, 3, 16), 200, search = "SPAR KENYER"))
        val found = db.receiptDao().search("TURO", null, 0, Long.MAX_VALUE, 0, Long.MAX_VALUE, includeDrafts = true).first()
        assertEquals(1, found.size)
        val byAmount = db.receiptDao().search("", store, 0, Long.MAX_VALUE, 150, Long.MAX_VALUE, includeDrafts = true).first()
        assertEquals(200L, byAmount.single().totalMinor)
    }
}

package hu.kiadaskoveto.data.repo

import androidx.room.withTransaction
import hu.kiadaskoveto.core.money.MeasureUnit
import hu.kiadaskoveto.core.text.HungarianText
import hu.kiadaskoveto.database.KiadasDatabase
import hu.kiadaskoveto.database.entity.ProductAliasEntity
import hu.kiadaskoveto.database.entity.ProductEntity
import hu.kiadaskoveto.database.entity.ReceiptItemEntity
import hu.kiadaskoveto.parser.ReceiptParser
import hu.kiadaskoveto.parser.categorize.CategorizationContext
import hu.kiadaskoveto.parser.matching.ProductCandidate
import hu.kiadaskoveto.parser.matching.ProductMatchResult
import hu.kiadaskoveto.parser.matching.ProductMatcher

/**
 * Product identities. Identical or similar descriptions are never merged automatically: a line
 * links to a product only through an alias the user confirmed (by saving a receipt or by editing).
 */
class ProductRepository(private val db: KiadasDatabase, private val parser: ReceiptParser) {

    private val dao = db.productDao()
    private val analytics = db.analyticsDao()

    val productsWithStats = dao.observeWithStats()
    fun observe(id: Long) = dao.observe(id)
    fun observeAliases(id: Long) = dao.observeAliases(id)
    fun observePurchases(id: Long) = dao.observePurchases(id)
    fun observePriceHistory(id: Long) = analytics.observePriceHistory(id)

    suspend fun byId(id: Long): ProductEntity? = dao.byId(id)
    suspend fun byBarcode(code: String): ProductEntity? = dao.byBarcode(code)

    suspend fun candidates(): List<ProductCandidate> {
        val aliases = dao.allAliases().groupBy { it.productId }
        return dao.all().map { p ->
            ProductCandidate(
                productId = p.id, name = p.name, productType = p.productType, brand = p.brand,
                packageSize = packageSizeOf(p.packageSizeAmount, p.packageSizeUnit),
                aliases = aliases[p.id].orEmpty().associate { it.aliasKey to it.storeScope.takeIf { s -> s != 0L }?.toString() },
            )
        }
    }

    /** Suggestions for the review screen (never applied automatically unless confirmed alias). */
    suspend fun suggestions(rawName: String, storeId: Long?): List<ProductMatchResult> =
        ProductMatcher.match(rawName, storeId?.toString(), candidates())

    /** Product confirmed for this exact description at this store (or for any store), if any. */
    suspend fun confirmedProductFor(rawName: String, storeId: Long?): ProductEntity? {
        val key = HungarianText.matchKey(rawName)
        val alias = dao.alias(key, storeId ?: 0L) ?: dao.alias(key, 0L) ?: return null
        return dao.byId(alias.productId)
    }

    /** Links a confirmed receipt line to its product, creating a new product identity if needed. */
    suspend fun linkOrCreate(item: ReceiptItemEntity, storeId: Long?): Long {
        item.productId?.let { id ->
            if (dao.byId(id) != null) { ensureAlias(id, item.rawName, storeId); return id }
        }
        confirmedProductFor(item.rawName, storeId)?.let { return it.id }
        val (interp, _) = parser.interpretLine(item.rawName, storeId?.toString(), CategorizationContext.EMPTY)
        val now = System.currentTimeMillis()
        val name = item.displayName.ifBlank { interp.displayName }
        val weighed = item.unit == MeasureUnit.KILOGRAM.name || interp.soldByWeight
        val id = dao.insert(
            ProductEntity(
                name = name, normalizedName = HungarianText.matchKey(name), brand = interp.brand, productType = interp.productType,
                variant = interp.variant, categoryId = item.categoryId,
                packageSizeAmount = item.packageSizeAmount ?: interp.packageSize?.amountString(),
                packageSizeUnit = item.packageSizeUnit ?: interp.packageSize?.unit?.name,
                unit = if (weighed) MeasureUnit.KILOGRAM.name else MeasureUnit.PIECE.name,
                createdAt = now, updatedAt = now,
            )
        )
        ensureAlias(id, item.rawName, storeId)
        return id
    }

    suspend fun ensureAlias(productId: Long, rawText: String, storeId: Long?) {
        val key = HungarianText.matchKey(rawText)
        if (key.isBlank()) return
        val scope = storeId ?: 0L
        val existing = dao.alias(key, scope)
        if (existing == null) {
            dao.insertAlias(ProductAliasEntity(productId = productId, aliasKey = key, originalText = rawText.trim(), storeScope = scope, userConfirmed = true, createdAt = System.currentTimeMillis()))
        } else if (existing.productId != productId) {
            // The user re-assigned this description: the latest confirmation wins.
            dao.upsertAlias(existing.copy(productId = productId))
        }
    }

    suspend fun update(product: ProductEntity) {
        require(product.name.isNotBlank()) { "A termék neve nem lehet üres." }
        dao.update(product.copy(normalizedName = HungarianText.matchKey(product.name), updatedAt = System.currentTimeMillis()))
    }

    suspend fun setBarcode(productId: Long, barcode: String?) {
        val p = dao.byId(productId) ?: return
        if (barcode != null) dao.byBarcode(barcode)?.let { other ->
            if (other.id != productId) dao.update(other.copy(barcode = null, updatedAt = System.currentTimeMillis()))
        }
        dao.update(p.copy(barcode = barcode, updatedAt = System.currentTimeMillis()))
    }

    suspend fun addAlias(productId: Long, text: String, storeId: Long?) = ensureAlias(productId, text, storeId)
    suspend fun deleteAlias(id: Long) = dao.deleteAlias(id)

    /** Merges [sourceId] into [targetId]: purchases and aliases move, the source is deleted. */
    suspend fun merge(sourceId: Long, targetId: Long) {
        require(sourceId != targetId)
        db.withTransaction {
            dao.moveItems(sourceId, targetId)
            dao.aliasesOf(sourceId).forEach { a ->
                dao.deleteAlias(a.id)
                dao.insertAlias(a.copy(id = 0, productId = targetId))
            }
            val source = dao.byId(sourceId)
            val target = dao.byId(targetId)
            if (source != null && target != null && target.barcode == null && source.barcode != null) {
                dao.update(target.copy(barcode = source.barcode, updatedAt = System.currentTimeMillis()))
            }
            dao.delete(sourceId)
            dao.refreshPurchaseDates(targetId)
        }
    }

    /** Separates one alias (and the purchases printed with that text) into a new product. */
    suspend fun splitAlias(aliasId: Long, aliasRawName: String, fromProductId: Long): Long = db.withTransaction {
        val source = dao.byId(fromProductId) ?: error("A termék nem található.")
        val alias = dao.aliasesOf(fromProductId).firstOrNull { it.id == aliasId } ?: error("Az álnév nem található.")
        val now = System.currentTimeMillis()
        val newId = dao.insert(source.copy(id = 0, name = aliasRawName.trim(), normalizedName = HungarianText.matchKey(aliasRawName), barcode = null, createdAt = now, updatedAt = now))
        dao.upsertAlias(alias.copy(productId = newId))
        dao.moveItemsWithRawName(fromProductId, newId, alias.originalText)
        dao.refreshPurchaseDates(fromProductId)
        dao.refreshPurchaseDates(newId)
        newId
    }

    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun refreshPurchaseDates(id: Long) = dao.refreshPurchaseDates(id)
}

package hu.kiadaskoveto.data.repo

import androidx.room.withTransaction
import hu.kiadaskoveto.core.money.MeasureUnit
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.money.Quantity
import hu.kiadaskoveto.core.text.HungarianText
import hu.kiadaskoveto.data.WallClock
import hu.kiadaskoveto.data.files.DocumentStorage
import hu.kiadaskoveto.database.KiadasDatabase
import hu.kiadaskoveto.database.entity.ReceiptDocumentEntity
import hu.kiadaskoveto.database.entity.ReceiptEntity
import hu.kiadaskoveto.database.entity.ReceiptItemEntity
import hu.kiadaskoveto.database.model.ReceiptSummary
import hu.kiadaskoveto.domain.ItemKind
import hu.kiadaskoveto.domain.Receipt
import hu.kiadaskoveto.domain.ReceiptDocument
import hu.kiadaskoveto.domain.ReceiptItem
import hu.kiadaskoveto.domain.ReceiptStatus
import hu.kiadaskoveto.parser.duplicate.DuplicateDetector
import hu.kiadaskoveto.parser.duplicate.DuplicateMatch
import hu.kiadaskoveto.parser.duplicate.DuplicateReason
import hu.kiadaskoveto.parser.duplicate.ReceiptFingerprint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDateTime

data class ReceiptDetail(
    val receipt: Receipt,
    val items: List<ReceiptItem>,
    val documents: List<ReceiptDocument>,
)

data class ReceiptFilter(
    val query: String = "",
    val storeId: Long? = null,
    val from: Long = Long.MIN_VALUE / 4,
    val to: Long = Long.MAX_VALUE / 4,
    val minTotal: Long = Long.MIN_VALUE / 4,
    val maxTotal: Long = Long.MAX_VALUE / 4,
    val includeDrafts: Boolean = true,
)

/** New line data as entered by the user or produced by the parser. */
data class ItemInput(
    val kind: ItemKind = ItemKind.PRODUCT,
    val rawName: String,
    val displayName: String,
    val categoryId: Long?,
    val quantity: Quantity?,
    val unit: MeasureUnit?,
    val unitPrice: Money?,
    val lineTotal: Money,
    val discount: Money = Money.ZERO,
    val discountText: String? = null,
    val vatCode: String? = null,
    val originalText: String = "",
    val confidence: Double = 1.0,
    val needsReview: Boolean = false,
    val reviewNote: String? = null,
    val productId: Long? = null,
    val packageSizeAmount: String? = null,
    val packageSizeUnit: String? = null,
    val categorySource: String? = null,
)

data class DuplicateInfo(val match: DuplicateMatch, val other: ReceiptEntity?, val otherStoreName: String?) {
    val reasonText: String
        get() = when (match.reason) {
            DuplicateReason.SAME_DOCUMENT -> "Ugyanezt a fájlt már importáltad."
            DuplicateReason.SAME_IMAGE -> "Egy korábbi blokk képe szinte azonos ezzel."
            DuplicateReason.SAME_RECEIPT_NUMBER -> "Ugyanez a nyugtaszám és pénztárgép-azonosító már szerepel."
            DuplicateReason.SAME_CONTENT -> "Ugyanaznap ugyanezekkel a tételekkel és végösszeggel már van blokk."
            DuplicateReason.SAME_METADATA -> "Ugyanabban a boltban, ugyanakkor, ugyanekkora összegű blokk már szerepel."
        }
}

class ReceiptRepository(
    private val db: KiadasDatabase,
    private val storage: DocumentStorage,
    private val stores: StoreRepository,
    private val learning: LearningRepository,
    private val products: ProductRepository,
) {
    private val dao = db.receiptDao()

    fun search(filter: ReceiptFilter): Flow<List<ReceiptSummary>> = dao.search(
        HungarianText.matchKey(filter.query), filter.storeId, filter.from, filter.to, filter.minTotal, filter.maxTotal, filter.includeDrafts,
    )

    fun recent(limit: Int) = dao.observeRecent(limit)
    val pendingCount = dao.observePendingCount()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDetail(id: Long): Flow<ReceiptDetail?> =
        dao.observeReceipt(id).flatMapLatest { r ->
            if (r == null) flowOf(null)
            else combine(dao.observeItems(id), dao.observeDocuments(id)) { items, docs ->
                val storeName = r.storeId?.let { stores.byId(it)?.name }
                ReceiptDetail(r.toDomain(storeName), items.map { it.toDomain() }, docs.map { it.toDomain() })
            }
        }

    suspend fun detail(id: Long): ReceiptDetail? {
        val r = dao.receipt(id) ?: return null
        val storeName = r.storeId?.let { stores.byId(it)?.name }
        return ReceiptDetail(r.toDomain(storeName), dao.items(id).map { it.toDomain() }, dao.documents(id).map { it.toDomain() })
    }

    suspend fun documentEntities(id: Long) = dao.documents(id)

    data class NewDocument(val filePath: String, val mimeType: String, val pageIndex: Int, val sha256: String, val imageHash: Long?, val sizeBytes: Long, val selectedPages: String?)

    data class NewReceipt(
        val storeId: Long?,
        val storeNameRaw: String?,
        val purchasedAt: LocalDateTime,
        val hasTime: Boolean,
        val dateKnown: Boolean,
        val printedTotal: Money?,
        val payableTotal: Money?,
        val rounding: Money?,
        val paymentMethod: String?,
        val receiptNumber: String?,
        val apCode: String?,
        val taxNumber: String?,
        val address: String?,
        val confidence: Double,
        val ocrText: String?,
        val status: ReceiptStatus,
        val failureReason: String? = null,
        val unparsedText: String? = null,
    )

    /** Saves a draft (or failed) receipt together with its original documents. Returns the id. */
    suspend fun saveDraft(header: NewReceipt, items: List<ItemInput>, documents: List<NewDocument>): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val computed = items.sumOf { (it.lineTotal + it.discount).minor }
        val id = dao.insertReceipt(
            ReceiptEntity(
                storeId = header.storeId, storeNameRaw = header.storeNameRaw,
                purchasedAt = WallClock.toStored(header.purchasedAt), purchasedHasTime = header.hasTime, dateKnown = header.dateKnown,
                totalMinor = (header.printedTotal ?: header.payableTotal)?.minor ?: computed,
                printedTotalMinor = header.printedTotal?.minor, payableTotalMinor = header.payableTotal?.minor,
                computedTotalMinor = computed, roundingMinor = header.rounding?.minor,
                paymentMethod = header.paymentMethod, receiptNumber = header.receiptNumber, apCode = header.apCode,
                taxNumber = header.taxNumber, address = header.address, status = header.status.name,
                confidence = header.confidence, ocrText = header.ocrText, failureReason = header.failureReason, unparsedText = header.unparsedText,
                createdAt = now, updatedAt = now,
            )
        )
        dao.insertItems(items.mapIndexed { i, it -> it.toEntity(id, i) })
        documents.forEach { d ->
            dao.insertDocument(ReceiptDocumentEntity(receiptId = id, filePath = d.filePath, mimeType = d.mimeType, pageIndex = d.pageIndex,
                sha256 = d.sha256, imageHash = d.imageHash, sizeBytes = d.sizeBytes, selectedPages = d.selectedPages, createdAt = now))
        }
        refreshDerived(id)
        id
    }

    /** Fills a placeholder receipt (created before OCR) with the parsed result. */
    suspend fun fillDraft(id: Long, header: NewReceipt, items: List<ItemInput>) = db.withTransaction {
        val r = dao.receipt(id) ?: return@withTransaction
        val computed = items.sumOf { (it.lineTotal + it.discount).minor }
        dao.updateReceipt(
            r.copy(
                storeId = header.storeId, storeNameRaw = header.storeNameRaw,
                purchasedAt = WallClock.toStored(header.purchasedAt), purchasedHasTime = header.hasTime, dateKnown = header.dateKnown,
                totalMinor = (header.printedTotal ?: header.payableTotal)?.minor ?: computed,
                printedTotalMinor = header.printedTotal?.minor, payableTotalMinor = header.payableTotal?.minor,
                computedTotalMinor = computed, roundingMinor = header.rounding?.minor,
                paymentMethod = header.paymentMethod, receiptNumber = header.receiptNumber, apCode = header.apCode,
                taxNumber = header.taxNumber, address = header.address, status = header.status.name,
                confidence = header.confidence, ocrText = header.ocrText, failureReason = header.failureReason,
                unparsedText = header.unparsedText, updatedAt = System.currentTimeMillis(),
            )
        )
        dao.replaceItems(id, items.mapIndexed { i, it -> it.toEntity(id, i) })
        refreshDerived(id)
    }

    suspend fun setDocumentImageHash(receiptId: Long, pageIndex: Int, hash: Long) = dao.setDocumentImageHash(receiptId, pageIndex, hash)

    suspend fun markFailed(id: Long, reason: String, ocrText: String?) {
        val r = dao.receipt(id) ?: return
        dao.updateReceipt(r.copy(status = ReceiptStatus.FAILED.name, failureReason = reason, ocrText = ocrText ?: r.ocrText, updatedAt = System.currentTimeMillis()))
    }

    /** Creates an empty receipt for manual entry. */
    suspend fun createManual(storeName: String?, date: LocalDateTime): Long {
        val storeId = stores.resolve(storeName, null, null, null)
        return saveDraft(
            NewReceipt(storeId, storeName, date, hasTime = false, dateKnown = true, printedTotal = null, payableTotal = null, rounding = null,
                paymentMethod = null, receiptNumber = null, apCode = null, taxNumber = null, address = null, confidence = 1.0, ocrText = null,
                status = ReceiptStatus.DRAFT),
            emptyList(), emptyList(),
        )
    }

    suspend fun updateHeader(
        id: Long,
        storeName: String?,
        purchasedAt: LocalDateTime,
        hasTime: Boolean,
        printedTotal: Money?,
        paymentMethod: String?,
        notes: String?,
    ) {
        val r = dao.receipt(id) ?: return
        val storeId = stores.resolve(storeName, null, r.address, r.taxNumber)
        dao.updateReceipt(
            r.copy(
                storeId = storeId, storeNameRaw = storeName ?: r.storeNameRaw,
                purchasedAt = WallClock.toStored(purchasedAt), purchasedHasTime = hasTime, dateKnown = true,
                printedTotalMinor = printedTotal?.minor, paymentMethod = paymentMethod, notes = notes,
                updatedAt = System.currentTimeMillis(),
            )
        )
        refreshDerived(id)
    }

    suspend fun addItem(receiptId: Long, input: ItemInput): Long {
        val id = dao.insertItem(input.copy(needsReview = false).toEntity(receiptId, dao.nextPosition(receiptId)).copy(userEdited = true))
        refreshDerived(receiptId)
        return id
    }

    /** Saves a user edit of a line. A changed category is remembered for future receipts. */
    suspend fun updateItem(itemId: Long, input: ItemInput) {
        val old = dao.item(itemId) ?: return
        val receipt = dao.receipt(old.receiptId) ?: return
        val updated = input.toEntity(old.receiptId, old.position).copy(
            id = old.id, userEdited = true, needsReview = false, reviewNote = null,
            originalText = old.originalText, confidence = old.confidence,
            categorySource = if (input.categoryId != old.categoryId) "USER" else old.categorySource,
        )
        dao.updateItem(updated)
        if (input.categoryId != null && input.categoryId != old.categoryId && input.kind == ItemKind.PRODUCT) {
            learning.rememberCategory(old.rawName, receipt.storeId, input.categoryId)
            updated.productId?.let { pid -> products.byId(pid)?.let { p -> products.update(p.copy(categoryId = input.categoryId)) } }
        }
        refreshDerived(old.receiptId)
    }

    suspend fun setItemCategory(itemId: Long, categoryId: Long) {
        val old = dao.item(itemId) ?: return
        val receipt = dao.receipt(old.receiptId) ?: return
        // Choosing a category resolves only the category issue; price issues stay open.
        val remaining = old.reviewNote?.split("; ")?.filterNot { it.startsWith("Kategória") }.orEmpty()
        dao.updateItem(old.copy(categoryId = categoryId, categorySource = "USER", userEdited = true,
            needsReview = old.needsReview && remaining.isNotEmpty(), reviewNote = remaining.joinToString("; ").ifEmpty { null }))
        learning.rememberCategory(old.rawName, receipt.storeId, categoryId)
        old.productId?.let { pid -> products.byId(pid)?.let { p -> products.update(p.copy(categoryId = categoryId)) } }
    }

    suspend fun markItemReviewed(itemId: Long) {
        val old = dao.item(itemId) ?: return
        dao.updateItem(old.copy(needsReview = false, reviewNote = null, userEdited = true))
    }

    suspend fun deleteItem(itemId: Long) {
        val old = dao.item(itemId) ?: return
        dao.deleteItem(itemId)
        refreshDerived(old.receiptId)
        old.productId?.let { products.refreshPurchaseDates(it) }
    }

    /** Confirms a draft: links products, remembers aliases and refreshes derived data. */
    suspend fun confirm(id: Long) = db.withTransaction {
        val r = dao.receipt(id) ?: return@withTransaction
        dao.items(id).forEach { item ->
            if (item.kind != ItemKind.PRODUCT.name || item.rawName.isBlank()) return@forEach
            val productId = products.linkOrCreate(item, r.storeId)
            if (productId != item.productId) dao.updateItem(item.copy(productId = productId))
        }
        dao.updateReceipt(r.copy(status = ReceiptStatus.CONFIRMED.name, updatedAt = System.currentTimeMillis()))
        refreshDerived(id)
        dao.items(id).mapNotNull { it.productId }.distinct().forEach { products.refreshPurchaseDates(it) }
    }

    suspend fun overrideDuplicate(id: Long) {
        val r = dao.receipt(id) ?: return
        dao.updateReceipt(r.copy(duplicateOverride = true, updatedAt = System.currentTimeMillis()))
    }

    /** Deletes the receipt, its lines and its original files. */
    suspend fun delete(id: Long) {
        val docs = dao.documents(id)
        val productIds = dao.items(id).mapNotNull { it.productId }.distinct()
        dao.deleteReceipt(id)
        docs.forEach { storage.deleteFolderOf(it.filePath) }
        productIds.forEach { products.refreshPurchaseDates(it) }
        db.productDao().deleteOrphans()
    }

    suspend fun findDuplicate(id: Long): DuplicateInfo? {
        val r = dao.receipt(id) ?: return null
        if (r.duplicateOverride) return null
        val docRows = dao.documentHashes().groupBy { it.receiptId }
        fun fp(receiptId: Long, storeId: Long?, at: Long, hasTime: Boolean, dateKnown: Boolean, total: Long, number: String?, ap: String?, sig: String?) =
            ReceiptFingerprint(
                receiptId = receiptId,
                documentHashes = docRows[receiptId].orEmpty().map { it.sha256 }.toSet(),
                imageHashes = docRows[receiptId].orEmpty().mapNotNull { it.imageHash },
                storeKey = storeId?.toString(),
                purchasedAt = if (dateKnown) WallClock.fromStored(at) else null,
                hasTime = hasTime, total = Money(total), receiptNumber = number, apCode = ap, itemSignature = sig,
            )
        val candidate = fp(r.id, r.storeId, r.purchasedAt, r.purchasedHasTime, r.dateKnown, r.totalMinor, r.receiptNumber, r.apCode, r.itemSignature)
        val others = dao.fingerprintRows().filter { it.receiptId != id }.map {
            fp(it.receiptId, it.storeId, it.purchasedAt, it.purchasedHasTime, it.dateKnown, it.totalMinor, it.receiptNumber, it.apCode, it.itemSignature)
        }
        val match = DuplicateDetector.find(candidate, others) ?: return null
        val other = match.receiptId?.let { dao.receipt(it) }
        return DuplicateInfo(match, other, other?.storeId?.let { stores.byId(it)?.name } ?: other?.storeNameRaw)
    }

    /** Recomputes the line sum, effective total, search key and item signature. */
    suspend fun refreshDerived(id: Long) {
        val r = dao.receipt(id) ?: return
        val items = dao.items(id)
        val computed = items.sumOf { it.lineTotalMinor + it.discountMinor }
        val total = r.printedTotalMinor ?: r.payableTotalMinor ?: computed
        val storeName = r.storeId?.let { stores.byId(it)?.name } ?: r.storeNameRaw.orEmpty()
        val search = HungarianText.matchKey(storeName + " " + items.joinToString(" ") { it.displayName + " " + it.rawName })
        val sig = DuplicateDetector.itemSignature(items.map { it.rawName to Money(it.lineTotalMinor) })
        dao.updateReceipt(r.copy(computedTotalMinor = computed, totalMinor = total, searchKey = search, itemSignature = sig))
    }

    private fun ItemInput.toEntity(receiptId: Long, position: Int) = ReceiptItemEntity(
        receiptId = receiptId, position = position, kind = kind.name, originalText = originalText,
        rawName = rawName.trim(), displayName = displayName.trim().ifBlank { rawName.trim() },
        productId = productId, categoryId = categoryId, quantityMilli = quantity?.milli, unit = unit?.name,
        unitPriceMinor = unitPrice?.minor, lineTotalMinor = lineTotal.minor, discountMinor = discount.minor,
        discountText = discountText, vatCode = vatCode, packageSizeAmount = packageSizeAmount, packageSizeUnit = packageSizeUnit,
        confidence = confidence, needsReview = needsReview, reviewNote = reviewNote, categorySource = categorySource,
    )
}

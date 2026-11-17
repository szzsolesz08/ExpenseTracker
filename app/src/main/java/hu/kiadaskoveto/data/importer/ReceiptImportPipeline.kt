package hu.kiadaskoveto.data.importer

import android.content.Context
import hu.kiadaskoveto.core.text.HungarianText
import hu.kiadaskoveto.data.WallClock
import hu.kiadaskoveto.data.files.DocumentStorage
import hu.kiadaskoveto.data.repo.CategoryRepository
import hu.kiadaskoveto.data.repo.ItemInput
import hu.kiadaskoveto.data.repo.LearningRepository
import hu.kiadaskoveto.data.repo.ProductRepository
import hu.kiadaskoveto.data.repo.ReceiptRepository
import hu.kiadaskoveto.data.repo.StoreRepository
import hu.kiadaskoveto.data.repo.amountString
import hu.kiadaskoveto.data.settings.SettingsRepository
import hu.kiadaskoveto.domain.ItemKind
import hu.kiadaskoveto.domain.ReceiptStatus
import hu.kiadaskoveto.localai.CategoryOption
import hu.kiadaskoveto.localai.LocalLanguageModel
import hu.kiadaskoveto.ocr.ImageQualityIssue
import hu.kiadaskoveto.ocr.ImagePreprocessor
import hu.kiadaskoveto.ocr.OcrCancelledException
import hu.kiadaskoveto.ocr.OcrEngine
import hu.kiadaskoveto.ocr.OcrTextLine
import hu.kiadaskoveto.ocr.PageMerger
import hu.kiadaskoveto.ocr.PreprocessOptions
import hu.kiadaskoveto.ocr.image.BitmapOps
import hu.kiadaskoveto.parser.ReceiptParser
import hu.kiadaskoveto.parser.categorize.CategoryKeys
import hu.kiadaskoveto.parser.model.CategorySource
import hu.kiadaskoveto.parser.model.IssueCode
import hu.kiadaskoveto.parser.model.OcrInput
import hu.kiadaskoveto.parser.model.OcrLine
import hu.kiadaskoveto.parser.model.ParseStage
import hu.kiadaskoveto.parser.model.ParsedItem
import hu.kiadaskoveto.parser.model.ParsedReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** The eight steps shown to the user while a receipt is processed. */
enum class ImportStep(val label: String) {
    IMPORTED("Dokumentum importálva"),
    PREPROCESS("Kép előfeldolgozása"),
    OCR("Szövegfelismerés (OCR)"),
    INTERPRET("Szöveg értelmezése"),
    EXTRACT("Termékek kinyerése"),
    CATEGORIZE("Kategóriák hozzárendelése"),
    REVIEW("Ellenőrzés és jóváhagyás"),
    SAVE("Mentés a helyi adatbázisba"),
}

data class ImportProgress(val step: ImportStep, val fraction: Float, val detail: String? = null)

sealed interface ImportOutcome {
    /** A draft receipt awaiting review. */
    data class Draft(val receiptId: Long, val qualityIssues: Set<ImageQualityIssue>) : ImportOutcome
    /** Processing failed; the originals were preserved in a FAILED receipt. */
    data class Failed(val receiptId: Long?, val message: String) : ImportOutcome
    data class Cancelled(val receiptId: Long?) : ImportOutcome
}

fun IssueCode.hungarian(): String = when (this) {
    IssueCode.LOW_OCR_CONFIDENCE -> "Bizonytalan szövegfelismerés"
    IssueCode.AMOUNT_REPAIRED -> "Az összeg javított olvasat, ellenőrizd"
    IssueCode.QUANTITY_PRICE_MISMATCH -> "Mennyiség × egységár nem egyezik a sorárral"
    IssueCode.DISCOUNT_SIGN_ASSUMED -> "A kedvezmény előjele feltételezett"
    IssueCode.NAME_UNCERTAIN -> "Terméknév értelmezése bizonytalan"
    IssueCode.CATEGORY_UNCERTAIN -> "Kategória bizonytalan"
    IssueCode.STORE_UNKNOWN -> "A bolt nem azonosítható"
    IssueCode.DATE_MISSING -> "Nem található vásárlási dátum"
    IssueCode.DATE_IMPLAUSIBLE -> "A dátum valószínűtlen"
    IssueCode.DATE_REPAIRED -> "A dátum évszámát javítottuk (olvasási hiba), ellenőrizd"
    IssueCode.TOTAL_MISSING -> "Nem található végösszeg"
    IssueCode.TOTAL_MISMATCH -> "A tételek összege eltér a végösszegtől"
    IssueCode.NO_ITEMS -> "Nem találtunk tételsorokat"
    IssueCode.UNPARSED_LINES -> "Néhány sort nem sikerült értelmezni"
}

fun ImageQualityIssue.hungarian(): String = when (this) {
    ImageQualityIssue.BLURRY -> "A kép homályosnak tűnik"
    ImageQualityIssue.TOO_DARK -> "A kép túl sötét"
    ImageQualityIssue.OVEREXPOSED -> "A kép túlexponált"
    ImageQualityIssue.LOW_RESOLUTION -> "Alacsony felbontású kép"
    ImageQualityIssue.LOW_CONTRAST -> "Alacsony kontraszt"
}

/**
 * Orchestrates import → preprocessing → OCR → parsing → categorization → draft.
 * Originals are moved into private storage first, so they survive any later failure.
 */
class ReceiptImportPipeline(
    private val context: Context,
    private val storage: DocumentStorage,
    private val ocr: OcrEngine,
    private val parser: ReceiptParser,
    private val receipts: ReceiptRepository,
    private val stores: StoreRepository,
    private val categories: CategoryRepository,
    private val learning: LearningRepository,
    private val products: ProductRepository,
    private val settings: SettingsRepository,
    private val localModel: () -> LocalLanguageModel,
) {

    fun cancel() = ocr.cancel()

    suspend fun run(session: ImportSession, onProgress: (ImportProgress) -> Unit): ImportOutcome = withContext(Dispatchers.Default) {
        val sources = session.sources.value
        val pages = session.pages.value
        if (pages.isEmpty()) return@withContext ImportOutcome.Failed(null, "Nincs feldolgozható oldal.")

        // 1. Preserve the originals.
        onProgress(ImportProgress(ImportStep.IMPORTED, 0f))
        val folder = storage.newFolder()
        val stored = try {
            sources.mapIndexed { i, src ->
                val f = storage.adoptFile(src.tempFile, folder, "original_${i + 1}", src.mimeType)
                src to f
            }
        } catch (e: IOException) {
            android.util.Log.e(TAG, "Storing originals failed", e)
            return@withContext ImportOutcome.Failed(null, "Az eredeti fájl mentése nem sikerült. Ellenőrizd a szabad tárhelyet, és próbáld újra.")
        }
        // Image pages may be the originals themselves, which were just moved into private storage.
        val movedTo: Map<java.io.File, java.io.File> = stored.associate { (src, f) -> src.tempFile.absoluteFile to f.file(context) }
        fun inputOf(page: ImportPage): java.io.File = movedTo[page.imageFile.absoluteFile] ?: page.imageFile
        onProgress(ImportProgress(ImportStep.IMPORTED, 1f, "${pages.size} oldal"))

        val qualityIssues = HashSet<ImageQualityIssue>()
        val imageHashes = HashMap<String, Long>()
        val pageLines = ArrayList<List<OcrTextLine>>()
        var ocrConfidenceSum = 0.0
        val s = settings.current()

        // A placeholder record is written together with the originals, so even if the app is killed
        // during OCR the receipt stays visible (as failed) and can be completed by hand.
        val placeholderId: Long = try {
            receipts.saveDraft(
                ReceiptRepository.NewReceipt(null, null, WallClock.now(), false, false, null, null, null, null, null, null, null, null,
                    0.0, null, ReceiptStatus.FAILED, "A feldolgozás megszakadt (az alkalmazás bezárult). Az eredeti fájl megmaradt; rögzítheted kézzel."),
                emptyList(), buildDocs(stored, emptyMap()),
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Placeholder save failed", e)
            return@withContext ImportOutcome.Failed(null, "Az adatbázisba írás nem sikerült. Próbáld újra.")
        }
        val sourceIndex = stored.mapIndexed { i, (src, _) -> src.id to i }.toMap()

        suspend fun saveFailed(reason: String): Long? = runCatching {
            withContext(kotlinx.coroutines.NonCancellable) {
                receipts.markFailed(placeholderId, reason, pageLines.flatten().joinToString("\n") { it.text }.ifBlank { null })
                placeholderId
            }
        }.getOrNull()

        try {
            // 2–3. Preprocess and recognise each page.
            pages.forEachIndexed { index, page ->
                ensureActive()
                onProgress(ImportProgress(ImportStep.PREPROCESS, index.toFloat() / pages.size, "${index + 1}. oldal"))
                val tPre = System.currentTimeMillis()
                val bitmap = BitmapOps.decode(inputOf(page), maxLongSide = 3200)
                val pre = ImagePreprocessor.process(
                    bitmap, PreprocessOptions(quarterTurns = page.quarterTurns, normalizedCrop = page.crop, autoCrop = s.autoCrop && page.crop == null),
                )
                qualityIssues += pre.issues
                imageHashes[page.sourceId + "#" + page.pageIndexInSource] = pre.imageHash
                if (page.pageIndexInSource == 0 || pages.count { it.sourceId == page.sourceId } == 1) {
                    sourceIndex[page.sourceId]?.let { receipts.setDocumentImageHash(placeholderId, it, pre.imageHash) }
                }
                BitmapOps.saveJpeg(pre.enhancedBitmap, storage.resolve("$folder/processed_${index + 1}.jpg"), 85)
                if (hu.kiadaskoveto.BuildConfig.DEBUG) BitmapOps.savePng(pre.ocrBitmap, java.io.File(context.cacheDir, "debug_ocr_${index + 1}.png"))
                bitmap.recycle()
                android.util.Log.i(TAG, "page ${index + 1}: preprocess=${System.currentTimeMillis() - tPre} ms")

                ensureActive()
                onProgress(ImportProgress(ImportStep.OCR, index.toFloat() / pages.size, "${index + 1}/${pages.size}. oldal"))
                // At most one progress update per second: every update redraws the screen, and drawing competes
                // with recognition for the CPU (measured on a software-rendered emulator: ~130 ms per frame).
                var lastProgressAt = 0L
                var result = ocr.recognize(pre.ocrBitmap) { pct ->
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastProgressAt >= PROGRESS_INTERVAL_MS || pct >= 100) {
                        lastProgressAt = now
                        onProgress(ImportProgress(ImportStep.OCR, (index + pct / 100f) / pages.size, "${index + 1}/${pages.size}. oldal · $pct%"))
                    }
                }
                // Upside-down photos: retry rotated by 180° when confidence is poor and the user did not rotate.
                if (result.meanConfidence < 0.45 && page.quarterTurns == 0) {
                    val flipped = BitmapOps.rotate(pre.ocrBitmap, 180f)
                    val second = ocr.recognize(flipped) {}
                    if (second.meanConfidence > result.meanConfidence + 0.1) result = second
                    flipped.recycle()
                }
                android.util.Log.i(TAG, "page ${index + 1}: ocr=${result.millis} ms, size=${pre.ocrBitmap.width}x${pre.ocrBitmap.height}, " +
                    "conf=${"%.2f".format(result.meanConfidence)}, skew=${"%.1f".format(pre.skewDegrees)}")
                pre.ocrBitmap.recycle(); pre.enhancedBitmap.recycle()
                pageLines += result.lines
                ocrConfidenceSum += result.meanConfidence
            }
            onProgress(ImportProgress(ImportStep.OCR, 1f))

            val merged = PageMerger.merge(pageLines)
            if (merged.isEmpty()) {
                val id = saveFailed("Nem sikerült szöveget felismerni a képen. Az eredeti fájl megmaradt; rögzítheted kézzel vagy újrapróbálhatod jobb fényviszonyok mellett.")
                return@withContext ImportOutcome.Failed(id, "Nem sikerült szöveget felismerni. Az eredeti képet megőriztük.")
            }

            // 4–6. Interpret, extract, categorize.
            val context = learning.context()
            val categoryByKey = categories.all().associateBy { it.key }
            // Known stores, so store-specific corrections apply during categorization (no I/O inside the parser).
            val knownStores = stores.all().associateBy { HungarianText.matchKey(it.name) }
            val parsed = parser.parse(
                OcrInput(merged.map { OcrLine(it.text, it.confidence) }),
                context,
                storeKeyResolver = { _, name -> name?.let { knownStores[HungarianText.matchKey(it)]?.id?.toString() } },
                onStage = { stage ->
                    val step = when (stage) {
                        ParseStage.RAW_TEXT, ParseStage.STRUCTURE, ParseStage.STORE, ParseStage.DATE -> ImportStep.INTERPRET
                        ParseStage.PRODUCT_LINES, ParseStage.QUANTITIES, ParseStage.DISCOUNTS, ParseStage.TOTAL_CHECK -> ImportStep.EXTRACT
                        ParseStage.CATEGORIES, ParseStage.CONFIDENCE -> ImportStep.CATEGORIZE
                    }
                    onProgress(ImportProgress(step, stage.number / 10f, "${stage.number}/10. lépés"))
                },
            )
            if (!parsed.hasReceiptContent()) {
                val reason = "A képen nem találtunk blokkra utaló szöveget (bolt, dátum, tételek vagy végösszeg). Az eredeti képet megőriztük; " +
                    "rögzítheted kézzel, vagy próbáld újra élesebb, jól megvilágított fényképpel."
                // The failure belongs to text interpretation, not to the later stages the parser already reported.
                onProgress(ImportProgress(ImportStep.INTERPRET, 1f))
                val id = saveFailed(reason)
                return@withContext ImportOutcome.Failed(id, reason)
            }
            val storeId = stores.resolve(parsed.storeName, parsed.storeProfileId, parsed.address, parsed.taxNumber)

            val model = if (s.localAiEnabled) localModel() else null
            val options = categoryByKey.values.filter { it.parentId == null }.map { CategoryOption(it.key, it.name) }
            val items = parsed.items.map { item ->
                toInput(item, storeId, categoryByKey.mapValues { it.value.id }, model, options)
            }

            // 7. Save as draft for review.
            onProgress(ImportProgress(ImportStep.REVIEW, 0.5f))
            val receiptId = placeholderId
            receipts.fillDraft(
                placeholderId,
                ReceiptRepository.NewReceipt(
                    storeId = storeId, storeNameRaw = parsed.storeName,
                    purchasedAt = parsed.purchasedAt ?: WallClock.now(), hasTime = parsed.dateHasTime, dateKnown = parsed.purchasedAt != null,
                    printedTotal = parsed.printedTotal, payableTotal = parsed.payableTotal, rounding = parsed.rounding,
                    paymentMethod = parsed.paymentMethod?.name, receiptNumber = parsed.receiptNumber, apCode = parsed.apCode,
                    taxNumber = parsed.taxNumber, address = parsed.address,
                    confidence = parsed.confidence * (ocrConfidenceSum / pages.size).coerceIn(0.5, 1.0),
                    ocrText = parsed.rawText, status = ReceiptStatus.DRAFT,
                    unparsedText = parsed.unparsedLines.joinToString("\n") { it.text }.ifBlank { null },
                ),
                items,
            )
            onProgress(ImportProgress(ImportStep.REVIEW, 1f))
            ImportOutcome.Draft(receiptId, qualityIssues)
        } catch (e: OcrCancelledException) {
            ImportOutcome.Cancelled(saveFailed("A feldolgozást megszakítottad. Az eredeti fájl megmaradt."))
        } catch (e: CancellationException) {
            saveFailed("A feldolgozást megszakítottad. Az eredeti fájl megmaradt.")
            throw e
        } catch (e: OutOfMemoryError) {
            val id = saveFailed("Nem volt elég memória a kép feldolgozásához.")
            ImportOutcome.Failed(id, "Nem volt elég memória a kép feldolgozásához. Próbáld kisebb képpel vagy kevesebb oldallal.")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Receipt processing failed", e)
            val id = saveFailed("A feldolgozás hibára futott (${e.javaClass.simpleName}). Az eredeti fájl megmaradt; rögzítheted kézzel.")
            ImportOutcome.Failed(id, "A feldolgozás nem sikerült. Az eredeti fájlt megőriztük; rögzítheted kézzel, vagy próbáld újra egy élesebb képpel.")
        } finally {
            // Temporary renders (PDF pages) are no longer needed; originals already live in private storage.
            pages.forEach { p -> if (p.imageFile.exists() && movedTo[p.imageFile.absoluteFile] == null) p.imageFile.delete() }
            session.reset(keepFiles = true)
        }
    }

    private companion object {
        const val TAG = "ImportPipeline"
        const val PROGRESS_INTERVAL_MS = 1000L
    }

    private fun buildDocs(stored: List<Pair<SourceDocument, hu.kiadaskoveto.data.files.StoredFile>>, hashes: Map<String, Long>) =
        stored.mapIndexed { i, (src, f) ->
            ReceiptRepository.NewDocument(
                filePath = f.relativePath, mimeType = f.mimeType, pageIndex = i, sha256 = f.sha256,
                imageHash = hashes[src.id + "#0"] ?: hashes.entries.firstOrNull { it.key.startsWith(src.id) }?.value,
                sizeBytes = f.sizeBytes, selectedPages = src.selectedPages?.joinToString(","),
            )
        }

    private suspend fun toInput(
        item: ParsedItem,
        storeId: Long?,
        categoryIds: Map<String, Long>,
        model: LocalLanguageModel?,
        options: List<CategoryOption>,
    ): ItemInput {
        val kind = ItemKind.valueOf(item.kind.name)
        var categoryKey = item.category.key
        var categorySource = item.category.source.name
        var productId: Long? = null
        var resolvedByProduct = false
        if (kind == ItemKind.PRODUCT) {
            products.confirmedProductFor(item.rawName, storeId)?.let { p ->
                productId = p.id
                p.categoryId?.let { cid -> categoryIds.entries.firstOrNull { it.value == cid }?.key }?.let {
                    categoryKey = it; categorySource = CategorySource.PRODUCT_MAPPING.name; resolvedByProduct = true
                }
            }
            if (!resolvedByProduct && item.category.confidence < 0.55 && model != null && model.isReady) {
                model.suggest(item.rawName, options)?.takeIf { it.confidence >= 0.6 && it.categoryKey in categoryIds }?.let {
                    categoryKey = it.categoryKey!!; categorySource = CategorySource.LOCAL_AI.name
                }
            }
        }
        val issues = item.issues
            .filterNot { resolvedByProduct && (it.code == IssueCode.CATEGORY_UNCERTAIN || it.code == IssueCode.NAME_UNCERTAIN) }
        val blocking = issues.filter { it.code in hu.kiadaskoveto.parser.model.ParsedItem.BLOCKING_ISSUES }
        val needsReview = blocking.isNotEmpty() || (!resolvedByProduct && item.confidence < hu.kiadaskoveto.parser.model.ParsedItem.REVIEW_THRESHOLD)
        val note = issues.joinToString("; ") { it.code.hungarian() + (it.detail?.let { d -> " ($d)" } ?: "") }.ifBlank { null }
        val size = item.interpretation.packageSize
        return ItemInput(
            kind = kind,
            rawName = item.rawName,
            displayName = if (kind == ItemKind.PRODUCT) item.interpretation.displayName else item.rawName.ifBlank { kind.label },
            categoryId = categoryIds[categoryKey] ?: categoryIds[CategoryKeys.UNCATEGORIZED],
            quantity = item.quantity,
            unit = item.unit,
            unitPrice = item.unitPrice,
            lineTotal = item.lineTotal,
            discount = item.discount,
            discountText = item.discountTexts.joinToString("; ").ifBlank { null },
            vatCode = item.vatCode,
            originalText = item.originalText,
            confidence = if (resolvedByProduct) maxOf(item.confidence, 0.9) else item.confidence,
            needsReview = needsReview,
            reviewNote = note,
            productId = productId,
            packageSizeAmount = size?.amountString(),
            packageSizeUnit = size?.unit?.name,
            categorySource = categorySource,
        )
    }
}

/**
 * True when the recognized text contains anything that identifies a receipt. A photo of something
 * else yields OCR noise only; that is reported as "no receipt found" rather than saved as an empty draft.
 */
internal fun ParsedReceipt.hasReceiptContent(): Boolean =
    items.isNotEmpty() || printedTotal != null || payableTotal != null || purchasedAt != null || storeConfidence >= 0.5

package hu.kiadaskoveto

import android.content.Context
import android.net.Uri
import hu.kiadaskoveto.data.backup.BackupManager
import hu.kiadaskoveto.data.backup.PdfReportWriter
import hu.kiadaskoveto.data.files.DocumentStorage
import hu.kiadaskoveto.data.importer.ImportSession
import hu.kiadaskoveto.data.importer.ReceiptImportPipeline
import hu.kiadaskoveto.data.repo.AnalyticsRepository
import hu.kiadaskoveto.data.repo.BudgetRepository
import hu.kiadaskoveto.data.repo.CategoryRepository
import hu.kiadaskoveto.data.repo.LearningRepository
import hu.kiadaskoveto.data.repo.ProductRepository
import hu.kiadaskoveto.data.repo.ReceiptRepository
import hu.kiadaskoveto.data.repo.StoreRepository
import hu.kiadaskoveto.data.settings.SettingsRepository
import hu.kiadaskoveto.data.work.Notifier
import hu.kiadaskoveto.database.KiadasDatabase
import hu.kiadaskoveto.localai.LocalModelManager
import hu.kiadaskoveto.ocr.tesseract.TesseractOcrEngine
import hu.kiadaskoveto.parser.ReceiptParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Something shared into the app from another app (image(s) or a PDF). */
data class SharedInput(val uris: List<Uri>, val mimeType: String)

/**
 * Manual dependency container. The graph is small and a DI framework would add build
 * complexity without real value here.
 */
class AppContainer(val context: Context, private val databaseOverride: KiadasDatabase? = null) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(context)
    val database: KiadasDatabase by lazy { databaseOverride ?: KiadasDatabase.build(context) }
    val storage = DocumentStorage(context)
    val parser: ReceiptParser by lazy { ReceiptParser() }

    val categories by lazy { CategoryRepository(database) }
    val stores by lazy { StoreRepository(database) }
    val learning by lazy { LearningRepository(database) }
    val products by lazy { ProductRepository(database, parser) }
    val receipts by lazy { ReceiptRepository(database, storage, stores, learning, products) }
    val analytics by lazy { AnalyticsRepository(database) }
    val budgets by lazy { BudgetRepository(database) { settings.current() } }
    val backup by lazy { BackupManager(context, database, storage) }
    val pdfReport by lazy { PdfReportWriter(database) }
    val notifier = Notifier(context)
    val localModels by lazy { LocalModelManager(context) }

    @Volatile private var ocrLanguages: String = "hun"
    val ocrEngine: TesseractOcrEngine by lazy { TesseractOcrEngine(context) { ocrLanguages } }

    val importSession = ImportSession()
    val importHelper by lazy { hu.kiadaskoveto.data.importer.ImportHelper(context, storage, importSession) }
    val pipeline by lazy {
        ReceiptImportPipeline(context, storage, ocrEngine, parser, receipts, stores, categories, learning, products, settings) { localModels.createModel() }
    }

    /** Pending share intent, consumed by the navigation host. */

    fun start() {
        appScope.launch {
            categories.ensureDefaults()
            settings.settings.collect { s ->
                if (s.ocrLanguages != ocrLanguages) { ocrLanguages = s.ocrLanguages; ocrEngine.release() }
            }
        }
    }

    /** Integrity and consistency checks with automatic repair of derived values. */
    suspend fun runConsistencyCheck(): List<String> = withContext(Dispatchers.IO) {
        val report = ArrayList<String>()
        val db = database.openHelper.writableDatabase
        db.query("PRAGMA integrity_check").use { c -> if (c.moveToFirst()) report += "SQLite integritás: " + if (c.getString(0) == "ok") "rendben" else c.getString(0) }
        db.query("PRAGMA foreign_key_check").use { c -> report += if (c.count == 0) "Kapcsolatok: rendben" else "Kapcsolati hibák: ${c.count}" }
        val stale = database.receiptDao().receiptsWithStaleItemSum()
        stale.forEach { receipts.refreshDerived(it) }
        report += if (stale.isEmpty()) "Blokkösszegek: rendben" else "Blokkösszegek újraszámolva: ${stale.size} blokk"
        val referenced = database.receiptDao().allDocumentPaths().toSet()
        val missing = referenced.count { !File(context.filesDir, it).exists() }
        report += if (missing == 0) "Eredeti fájlok: mind megvannak" else "Hiányzó eredeti fájl: $missing"
        val orphans = storage.orphans(referenced)
        report += if (orphans.isEmpty()) "Árva fájlok: nincs" else "Árva fájlok (nem tartoznak blokkhoz): ${orphans.size}"
        report
    }

    /** Erases every receipt, product, budget, setting and file. Irreversible. */
    suspend fun deleteAllData() = withContext(Dispatchers.IO) {
        ocrEngine.release()
        database.clearAllTables()
        storage.deleteAll()
        backup.privateBackupDir().deleteRecursively()
        localModels.delete()
        importSession.reset()
        settings.clear()
        categories.ensureDefaults()
    }
}

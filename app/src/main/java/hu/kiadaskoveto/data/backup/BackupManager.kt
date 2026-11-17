package hu.kiadaskoveto.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import hu.kiadaskoveto.BuildConfig
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.data.WallClock
import hu.kiadaskoveto.data.files.DocumentStorage
import hu.kiadaskoveto.database.KiadasDatabase
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupData(
    val format: Int = BackupManager.FORMAT,
    val app: String = "KiadásKövető",
    val appVersion: String,
    val schemaVersion: Int,
    val createdAt: Long,
    val categories: List<CategoryEntity>,
    val stores: List<StoreEntity>,
    val products: List<ProductEntity>,
    val receipts: List<ReceiptEntity>,
    val items: List<ReceiptItemEntity>,
    val documents: List<ReceiptDocumentEntity>,
    val aliases: List<ProductAliasEntity>,
    val mappings: List<CategoryMappingEntity>,
    val rules: List<CategoryRuleEntity>,
    val budgets: List<BudgetEntity>,
    val budgetAlerts: List<BudgetAlertEntity>,
)

data class BackupSummary(val receipts: Int, val items: Int, val files: Int, val bytes: Long)

/**
 * Local backup and restore. A backup is a ZIP with `data.json` (all tables) and the original
 * receipt files. Nothing is uploaded anywhere; the user chooses where the file goes.
 */
class BackupManager(
    private val context: Context,
    private val db: KiadasDatabase,
    private val storage: DocumentStorage,
) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun snapshot(): BackupData = withContext(Dispatchers.IO) {
        val b = db.backupDao()
        BackupData(
            appVersion = BuildConfig.VERSION_NAME, schemaVersion = KiadasDatabase.VERSION, createdAt = System.currentTimeMillis(),
            categories = b.categories(), stores = b.stores(), products = b.products(), receipts = b.receipts(), items = b.items(),
            documents = b.documents(), aliases = b.aliases(), mappings = b.mappings(), rules = b.rules(), budgets = b.budgets(),
            budgetAlerts = b.budgetAlerts(),
        )
    }

    /** Writes a full backup ZIP to [out]. */
    suspend fun writeBackup(out: OutputStream): BackupSummary = withContext(Dispatchers.IO) {
        val data = snapshot()
        var files = 0
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(DATA_ENTRY))
            zip.write(json.encodeToString(BackupData.serializer(), data).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (doc in data.documents) {
                val f = storage.resolve(doc.filePath)
                if (!f.isFile) continue
                zip.putNextEntry(ZipEntry("files/" + doc.filePath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                files++
            }
        }
        BackupSummary(data.receipts.size, data.items.size, files, 0)
    }

    suspend fun backupTo(uri: Uri): BackupSummary =
        context.contentResolver.openOutputStream(uri, "w")?.use { writeBackup(it) } ?: throw IOException("A célfájl nem írható.")

    /** Reads a backup and returns what it contains, without changing anything. */
    suspend fun inspect(uri: Uri): BackupData = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { readData(it) } ?: throw IOException("A fájl nem olvasható.")
    }

    /**
     * Replaces all data with the backup. The current data is first saved to a private safety
     * backup so a failed restore can be undone.
     */
    suspend fun restore(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val safety = File(privateBackupDir(), "elozo-allapot-${System.currentTimeMillis()}.zip")
        safety.outputStream().use { writeBackup(it) }

        val data = context.contentResolver.openInputStream(uri)?.use { readData(it) } ?: throw IOException("A fájl nem olvasható.")
        if (data.schemaVersion > KiadasDatabase.VERSION) throw IOException("A mentés egy újabb alkalmazásverzióból származik.")
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var files = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory || !e.name.startsWith("files/")) continue
                    val rel = e.name.removePrefix("files/")
                    // Zip-slip protection: only plain receipts/... paths are accepted.
                    if (!rel.startsWith("receipts/") || rel.contains("..") || rel.contains('\\')) continue
                    val target = File(staging, rel)
                    if (!target.canonicalPath.startsWith(staging.canonicalPath)) continue
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    files++
                }
            }
        }
        // clearAllTables() vacuums and must run outside a transaction; the safety backup above covers a failure in between.
        db.clearAllTables()
        db.withTransaction {
            val b = db.backupDao()
            b.putCategories(data.categories); b.putStores(data.stores); b.putProducts(data.products)
            b.putReceipts(data.receipts); b.putItems(data.items); b.putDocuments(data.documents)
            b.putAliases(data.aliases); b.putMappings(data.mappings); b.putRules(data.rules)
            b.putBudgets(data.budgets); b.putBudgetAlerts(data.budgetAlerts)
        }
        File(context.filesDir, "receipts").deleteRecursively()
        File(staging, "receipts").takeIf { it.exists() }?.copyRecursively(File(context.filesDir, "receipts"), overwrite = true)
        staging.deleteRecursively()
        BackupSummary(data.receipts.size, data.items.size, files, 0)
    }

    private fun readData(input: InputStream): BackupData {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == DATA_ENTRY) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    val data = runCatching { json.decodeFromString(BackupData.serializer(), text) }
                        .getOrElse { throw IOException("A mentés adatai sérültek vagy nem értelmezhetők.") }
                    if (data.format > FORMAT) throw IOException("Ismeretlen mentésformátum (${data.format}).")
                    return data
                }
            }
        }
        throw IOException("Ez nem KiadásKövető mentés (hiányzik a data.json).")
    }

    fun privateBackupDir(): File = File(context.filesDir, "backups").apply { mkdirs() }

    /** Automatic backup: into the chosen folder (tree URI) or the private backup folder; keeps the last 5 private copies. */
    suspend fun autoBackup(folderUri: String?): String = withContext(Dispatchers.IO) {
        val name = "kiadaskoveto-mentes-${java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.zip"
        if (folderUri != null) {
            val tree = Uri.parse(folderUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(context.contentResolver, parent, "application/zip", name)
                ?: throw IOException("A mentési mappába nem lehet írni.")
            context.contentResolver.openOutputStream(doc)?.use { writeBackup(it) }
            "Mentve a kiválasztott mappába: $name"
        } else {
            File(privateBackupDir(), name).outputStream().use { writeBackup(it) }
            privateBackupDir().listFiles { f -> f.name.startsWith("kiadaskoveto-mentes-") }?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
            "Mentve az alkalmazás belső tárhelyére: $name"
        }
    }

    /** JSON export of all data (no images), for use in other tools. */
    suspend fun exportJson(out: OutputStream) = withContext(Dispatchers.IO) {
        out.bufferedWriter(Charsets.UTF_8).use { it.write(prettyJson.encodeToString(BackupData.serializer(), snapshot())) }
    }

    /** CSV export of confirmed receipt lines (semicolon separated, decimal comma, UTF-8 with BOM for Excel). */
    suspend fun exportCsv(out: OutputStream) = withContext(Dispatchers.IO) {
        val data = snapshot()
        val receipts = data.receipts.filter { it.status == "CONFIRMED" }.associateBy { it.id }
        val stores = data.stores.associateBy { it.id }
        val categories = data.categories.associateBy { it.id }
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        fun csv(s: String?): String { val v = s.orEmpty(); return if (v.any { it == ';' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v }
        fun num(m: Long?): String = m?.let { Money(it).format(withCurrency = false).replace(" ", "") }.orEmpty()
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("﻿")
            w.write("Blokk azonosító;Dátum;Bolt;Tétel;Eredeti szöveg;Típus;Kategória;Mennyiség;Egység;Egységár;Sorár;Kedvezmény;Fizetett;ÁFA-kód\n")
            for (i in data.items.filter { it.receiptId in receipts }) {
                val r = receipts.getValue(i.receiptId)
                w.write(listOf(
                    r.id.toString(), WallClock.fromStored(r.purchasedAt).format(fmt), csv(r.storeId?.let { stores[it]?.name } ?: r.storeNameRaw),
                    csv(i.displayName), csv(i.rawName), i.kind, csv(i.categoryId?.let { categories[it]?.name }),
                    i.quantityMilli?.let { hu.kiadaskoveto.core.money.Quantity(it).format() }.orEmpty(), i.unit.orEmpty(),
                    num(i.unitPriceMinor), num(i.lineTotalMinor), num(i.discountMinor), num(i.lineTotalMinor + i.discountMinor), i.vatCode.orEmpty(),
                ).joinToString(";"))
                w.write("\n")
            }
        }
    }

    companion object {
        const val FORMAT = 1
        const val DATA_ENTRY = "data.json"
    }
}

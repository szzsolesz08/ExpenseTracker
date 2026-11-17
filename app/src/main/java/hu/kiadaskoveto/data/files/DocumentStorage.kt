package hu.kiadaskoveto.data.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import hu.kiadaskoveto.core.hash.Hashing
import java.io.File
import java.io.IOException
import java.util.UUID

/** A stored original document. Paths are relative to the app's private files directory. */
data class StoredFile(val relativePath: String, val mimeType: String, val sha256: String, val sizeBytes: Long) {
    fun file(context: Context): File = File(context.filesDir, relativePath)
}

/**
 * Keeps original receipt documents in private app storage (not visible to other apps, not
 * backed up to the cloud). Originals are never modified; processing works on copies.
 */
class DocumentStorage(private val context: Context) {

    private val root: File get() = File(context.filesDir, "receipts").apply { mkdirs() }
    private val incoming: File get() = File(context.cacheDir, "incoming").apply { mkdirs() }

    fun newFolder(): String = "receipts/" + UUID.randomUUID().toString()

    fun resolve(relativePath: String): File = File(context.filesDir, relativePath)

    /** Copies a content URI into [folder] and fingerprints it. */
    @Throws(IOException::class)
    fun importUri(uri: Uri, folder: String, baseName: String, fallbackMime: String): StoredFile {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: fallbackMime
        val ext = extension(mime, displayName(uri))
        val rel = "$folder/$baseName.$ext"
        val target = resolve(rel).apply { parentFile?.mkdirs() }
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it, 64 * 1024) } }
            ?: throw IOException("A fájl nem nyitható meg.")
        if (target.length() == 0L) { target.delete(); throw IOException("A kiválasztott fájl üres.") }
        return StoredFile(rel, mime, target.inputStream().use { Hashing.sha256Hex(it) }, target.length())
    }

    /** Moves a temporary file (e.g. a camera capture) into [folder]. */
    @Throws(IOException::class)
    fun adoptFile(source: File, folder: String, baseName: String, mime: String): StoredFile {
        val ext = extension(mime, source.name)
        val rel = "$folder/$baseName.$ext"
        val target = resolve(rel).apply { parentFile?.mkdirs() }
        if (!source.renameTo(target)) { source.copyTo(target, overwrite = true); source.delete() }
        return StoredFile(rel, mime, target.inputStream().use { Hashing.sha256Hex(it) }, target.length())
    }

    /** A new temporary file for camera captures / rendered pages. */
    fun newTempFile(suffix: String): File = File(incoming, UUID.randomUUID().toString() + suffix)

    fun clearTemp() { incoming.listFiles()?.forEach { it.deleteRecursively() } }

    fun deleteFolderOf(relativePath: String) {
        val f = resolve(relativePath).parentFile ?: return
        if (f.canonicalPath.startsWith(root.canonicalPath) && f != root) f.deleteRecursively()
    }

    fun deleteAll() { root.deleteRecursively(); clearTemp() }

    fun totalSizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Files on disk that no database row references (consistency check). */
    fun orphans(referenced: Set<String>): List<File> =
        root.walkTopDown().filter { it.isFile }.filter { f ->
            val rel = f.relativeTo(context.filesDir).invariantSeparatorsPath
            rel !in referenced && !rel.contains("/processed_") && !rel.endsWith("thumb.jpg")
        }.toList()

    fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun extension(mime: String, name: String?): String {
        name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in setOf("jpg", "jpeg", "png", "webp", "pdf") }?.let { return it }
        return when (mime) {
            "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "application/pdf" -> "pdf"
            else -> "bin"
        }
    }

    companion object {
        val SUPPORTED_IMAGE_MIME = setOf("image/jpeg", "image/jpg", "image/png", "image/webp")
    }
}

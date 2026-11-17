package hu.kiadaskoveto.data.importer

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import hu.kiadaskoveto.data.files.DocumentStorage
import hu.kiadaskoveto.ocr.image.BitmapOps
import hu.kiadaskoveto.ocr.pdf.PdfPageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class ImportException(message: String) : IOException(message)

/** Brings picked, shared or captured files into the import session, validating them first. */
class ImportHelper(private val context: Context, private val storage: DocumentStorage, private val session: ImportSession) {

    private val maxBytes = 60L * 1024 * 1024

    private fun mimeOf(uri: Uri, fallback: String): String = context.contentResolver.getType(uri) ?: fallback

    /** Adds image files (JPG, PNG, WebP) — all pages of one receipt. */
    suspend fun addImages(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var added = 0
        val errors = ArrayList<String>()
        for (uri in uris) {
            try {
                val mime = mimeOf(uri, "image/jpeg").lowercase()
                if (mime !in DocumentStorage.SUPPORTED_IMAGE_MIME) throw ImportException("Nem támogatott képformátum ($mime). Használj JPG, PNG vagy WebP fájlt.")
                val temp = copy(uri, if (mime == "image/png") ".png" else if (mime == "image/webp") ".webp" else ".jpg")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temp.absolutePath, bounds)
                if (bounds.outWidth <= 0) { temp.delete(); throw ImportException("A kép sérült vagy nem olvasható.") }
                val source = SourceDocument(tempFile = temp, mimeType = if (mime == "image/jpg") "image/jpeg" else mime, displayName = storage.displayName(uri))
                session.addSource(source, listOf(ImportPage(sourceId = source.id, imageFile = temp)))
                added++
            } catch (e: IOException) {
                errors += e.message ?: "Ismeretlen hiba"
            } catch (e: SecurityException) {
                errors += "Nincs jogosultság a fájl olvasásához."
            }
        }
        if (added == 0 && errors.isNotEmpty()) throw ImportException(errors.first())
        added
    }

    /** Copies a PDF to temp storage and reports its page count. */
    suspend fun preparePdf(uri: Uri): Pair<File, Int> = withContext(Dispatchers.IO) {
        val mime = mimeOf(uri, "application/pdf")
        if (mime != "application/pdf") throw ImportException("A kiválasztott fájl nem PDF.")
        val temp = copy(uri, ".pdf")
        try {
            PdfPageRenderer.open(temp).use { temp to it.pageCount }
        } catch (e: IOException) {
            temp.delete(); throw ImportException(e.message ?: "A PDF nem nyitható meg.")
        }
    }

    /** Renders the selected PDF pages (300 dpi) and adds them to the session. */
    suspend fun addPdfPages(pdf: File, displayName: String?, pages: List<Int>) = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) throw ImportException("Válassz ki legalább egy oldalt.")
        val source = SourceDocument(tempFile = pdf, mimeType = "application/pdf", displayName = displayName, selectedPages = pages.sorted())
        val imported = PdfPageRenderer.open(pdf).use { renderer ->
            pages.sorted().map { index ->
                val bmp = renderer.render(index, dpi = 300)
                val out = storage.newTempFile("_p${index + 1}.png")
                BitmapOps.savePng(bmp, out)
                bmp.recycle()
                ImportPage(sourceId = source.id, imageFile = out, pageIndexInSource = index)
            }
        }
        session.addSource(source, imported)
    }

    /** Adds a camera capture (already a JPEG file in temp storage). */
    fun addCapture(file: File) {
        val source = SourceDocument(tempFile = file, mimeType = "image/jpeg", displayName = file.name)
        session.addSource(source, listOf(ImportPage(sourceId = source.id, imageFile = file)))
    }

    fun displayName(uri: Uri): String? = storage.displayName(uri)

    private fun copy(uri: Uri, suffix: String): File {
        val temp = storage.newTempFile(suffix)
        val written = context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { out ->
                var total = 0L
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw ImportException("A fájl túl nagy (legfeljebb 60 MB).")
                    out.write(buf, 0, n)
                }
                total
            }
        } ?: throw ImportException("A fájl nem nyitható meg.")
        if (written == 0L) { temp.delete(); throw ImportException("A kiválasztott fájl üres.") }
        return temp
    }
}

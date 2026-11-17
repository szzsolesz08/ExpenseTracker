package hu.kiadaskoveto.data.importer

import hu.kiadaskoveto.ocr.image.Quad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

/** An original document the user brought in (photo, image file or PDF), held in temp storage until processing. */
data class SourceDocument(
    val id: String = UUID.randomUUID().toString(),
    val tempFile: File,
    val mimeType: String,
    val displayName: String?,
    /** For PDFs: the 0-based pages selected for processing. */
    val selectedPages: List<Int>? = null,
)

/** One page image to process, with the user's rotation and crop. */
data class ImportPage(
    val id: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val imageFile: File,
    val pageIndexInSource: Int = 0,
    val quarterTurns: Int = 0,
    /** Normalized crop (0..1) of the rotated image; null = automatic detection. */
    val crop: Quad? = null,
)

/**
 * In-memory state of the receipt being imported, shared by the scanner, page review and
 * processing screens. Files live in the app cache until the pipeline moves them to private storage.
 */
class ImportSession {
    private val _sources = MutableStateFlow<List<SourceDocument>>(emptyList())
    private val _pages = MutableStateFlow<List<ImportPage>>(emptyList())
    val sources: StateFlow<List<SourceDocument>> = _sources
    val pages: StateFlow<List<ImportPage>> = _pages

    fun addSource(source: SourceDocument, pages: List<ImportPage>) {
        _sources.update { it + source }
        _pages.update { it + pages }
    }

    fun updatePage(id: String, transform: (ImportPage) -> ImportPage) {
        _pages.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    fun removePage(id: String) {
        val page = _pages.value.firstOrNull { it.id == id } ?: return
        _pages.update { list -> list.filterNot { it.id == id } }
        if (_pages.value.none { it.sourceId == page.sourceId }) {
            _sources.value.firstOrNull { it.id == page.sourceId }?.tempFile?.delete()
            _sources.update { list -> list.filterNot { it.id == page.sourceId } }
        }
        if (page.imageFile.exists() && _sources.value.none { it.tempFile == page.imageFile }) page.imageFile.delete()
    }

    fun movePage(id: String, delta: Int) {
        _pages.update { list ->
            val i = list.indexOfFirst { it.id == id }
            val j = i + delta
            if (i < 0 || j !in list.indices) list else list.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
        }
    }

    val isEmpty: Boolean get() = _pages.value.isEmpty()

    /** Clears the session; temp files are deleted unless [keepFiles]. */
    fun reset(keepFiles: Boolean = false) {
        if (!keepFiles) {
            _pages.value.forEach { it.imageFile.delete() }
            _sources.value.forEach { it.tempFile.delete() }
        }
        _pages.value = emptyList()
        _sources.value = emptyList()
    }
}

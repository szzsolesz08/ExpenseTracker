package hu.kiadaskoveto.ocr.tesseract

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import hu.kiadaskoveto.ocr.OcrCancelledException
import hu.kiadaskoveto.ocr.OcrEngine
import hu.kiadaskoveto.ocr.OcrPage
import hu.kiadaskoveto.ocr.OcrTextLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs the bundled Tesseract language models (assets/tessdata) into private storage.
 * Nothing is downloaded: the models ship inside the APK.
 */
class TessDataInstaller(private val context: Context) {

    val dataDir: File get() = File(context.filesDir, "ocr")
    private val tessDir: File get() = File(dataDir, "tessdata")

    fun bundledLanguages(): List<String> =
        (context.assets.list("tessdata") ?: emptyArray()).filter { it.endsWith(".traineddata") }.map { it.removeSuffix(".traineddata") }

    fun isInstalled(language: String): Boolean {
        val f = File(tessDir, "$language.traineddata")
        if (!f.exists()) return false
        val expected = runCatching { context.assets.openFd("tessdata/$language.traineddata").use { it.length } }.getOrNull()
        return expected == null || expected == f.length()
    }

    @Throws(IOException::class)
    fun install(languages: Collection<String>) {
        tessDir.mkdirs()
        for (lang in languages) {
            if (isInstalled(lang)) continue
            val tmp = File(tessDir, "$lang.traineddata.tmp")
            context.assets.open("tessdata/$lang.traineddata").use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
            val target = File(tessDir, "$lang.traineddata")
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        }
    }
}

/**
 * Tesseract 5 (LSTM) OCR via Tesseract4Android, with bundled Hungarian (`hun`, tessdata_best)
 * and English (`eng`, tessdata_fast) models. Runs fully offline on the CPU.
 */
class TesseractOcrEngine(
    private val context: Context,
    private val languages: () -> String = { "hun" },
) : OcrEngine {

    override val name: String = "Tesseract 5 (LSTM)"

    companion object {
        /**
         * OpenMP worker threads for LSTM recognition (read by the OpenMP runtime at its first parallel region).
         * Tesseract's LSTM parallelizes over at most 4 gates, so more threads only oversubscribe the CPU.
         *
         * Measured on a 4-core x86_64 emulator (photo-like SPAR / Lidl fixtures): 4 threads 4.7 / 6.0 s and
         * 1 thread 7.7 / 6.7 s without UI; through the app 19.7 / 17.0 s vs 25.9 / 19.4 s (cold debug process).
         * OMP_WAIT_POLICY=PASSIVE made recognition ~15× slower, so OpenMP keeps its default active waiting.
         * Anything that keeps the CPU busy during OCR (an indeterminate spinner on a software-rendered display
         * took it to 160–250 s) hurts badly, so the processing screen only redraws when progress changes.
         */
        val ompThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        init {
            runCatching { android.system.Os.setenv("OMP_NUM_THREADS", ompThreads.toString(), true) }
        }
    }

    private val installer = TessDataInstaller(context)
    private val mutex = Mutex()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var api: TessBaseAPI? = null
    @Volatile private var initializedLanguages: String? = null
    @Volatile private var progressListener: ((Int) -> Unit)? = null

    override fun isAvailable(): Boolean = languages().split('+').all { it in installer.bundledLanguages() }

    override suspend fun recognize(bitmap: Bitmap, onProgress: (Int) -> Unit): OcrPage = withContext(Dispatchers.Default) {
        mutex.withLock {
            cancelled.set(false)
            val start = System.currentTimeMillis()
            val tess = ensureApi()
            progressListener = onProgress
            try {
                tess.setImage(bitmap)
                // getHOCRText drives recognition with progress callbacks and supports stop().
                val hocr = tess.getHOCRText(0)
                if (cancelled.get() || hocr == null) throw OcrCancelledException()
                val lines = ArrayList<OcrTextLine>()
                val it = tess.resultIterator
                if (it != null) {
                    try {
                        it.begin()
                        do {
                            val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim().orEmpty()
                            if (text.isNotEmpty()) {
                                val conf = (it.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE) / 100.0).coerceIn(0.0, 1.0)
                                val rect = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                                lines += OcrTextLine(text, conf, rect?.top ?: 0, rect?.left ?: 0)
                            }
                        } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                    } finally {
                        it.delete()
                    }
                }
                val mean = (tess.meanConfidence() / 100.0).coerceIn(0.0, 1.0)
                onProgress(100)
                OcrPage(lines, mean, name, System.currentTimeMillis() - start)
            } finally {
                progressListener = null
                tess.clear()
            }
        }
    }

    override fun cancel() {
        cancelled.set(true)
        api?.stop()
    }

    /** Releases native memory (call when the app goes to background or OCR settings change). */
    fun release() {
        api?.recycle()
        api = null
        initializedLanguages = null
    }

    private fun ensureApi(): TessBaseAPI {
        val langs = languages()
        val existing = api
        if (existing != null && initializedLanguages == langs) return existing
        existing?.recycle()
        installer.install(langs.split('+'))
        val tess = TessBaseAPI { values -> progressListener?.invoke(values.percent) }
        val ok = tess.init(installer.dataDir.absolutePath, langs, TessBaseAPI.OEM_LSTM_ONLY)
        if (!ok) {
            tess.recycle()
            throw IOException("Az OCR nyelvi modell nem tölthető be ($langs).")
        }
        tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
        tess.setVariable("preserve_interword_spaces", "1")
        tess.setVariable("user_defined_dpi", "300")
        // Receipts are dark text on light paper; skipping the inverted-line pass saves ~30 %.
        tess.setVariable("tessedit_do_invert", "0")
        api = tess
        initializedLanguages = langs
        return tess
    }
}

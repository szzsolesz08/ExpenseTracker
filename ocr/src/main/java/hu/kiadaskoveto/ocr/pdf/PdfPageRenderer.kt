package hu.kiadaskoveto.ocr.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.min

/**
 * Renders PDF pages to bitmaps with Android's built-in [PdfRenderer] — fully on-device.
 * Password-protected or corrupted PDFs raise an [IOException] with a Hungarian message.
 */
class PdfPageRenderer private constructor(private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) : Closeable {

    val pageCount: Int get() = renderer.pageCount

    /** Renders [pageIndex] at [dpi] (receipts OCR well at 300 dpi), capped at [maxLongSide] pixels. */
    @Synchronized
    fun render(pageIndex: Int, dpi: Int = 300, maxLongSide: Int = 4200): Bitmap {
        renderer.openPage(pageIndex).use { page ->
            var scale = dpi / 72f
            val longSide = maxOf(page.width, page.height) * scale
            if (longSide > maxLongSide) scale *= maxLongSide / longSide
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            return bmp
        }
    }

    fun thumbnail(pageIndex: Int, width: Int = 360): Bitmap {
        renderer.openPage(pageIndex).use { page ->
            val scale = width.toFloat() / page.width
            val h = min((page.height * scale).toInt(), width * 4).coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.WHITE)
            page.render(bmp, null, android.graphics.Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }

    companion object {
        @Throws(IOException::class)
        fun open(file: File): PdfPageRenderer {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: SecurityException) {
                fd.close(); throw IOException("A PDF jelszóval védett, nem nyitható meg.", e)
            } catch (e: IOException) {
                fd.close(); throw IOException("A PDF sérült vagy nem támogatott formátumú.", e)
            }
            if (renderer.pageCount == 0) {
                renderer.close(); fd.close()
                throw IOException("A PDF nem tartalmaz oldalt.")
            }
            return PdfPageRenderer(fd, renderer)
        }
    }
}

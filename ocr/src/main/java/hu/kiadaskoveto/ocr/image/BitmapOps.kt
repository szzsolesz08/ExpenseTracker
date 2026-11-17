package hu.kiadaskoveto.ocr.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.max

/** Android bitmap helpers: safe decoding, EXIF orientation, rotation and perspective correction. */
object BitmapOps {

    /** Decodes an image so that its long side is at most [maxLongSide], applying EXIF orientation. */
    @Throws(IOException::class)
    fun decode(resolver: ContentResolver, uri: Uri, maxLongSide: Int = 3000): Bitmap =
        decode({ resolver.openInputStream(uri) ?: throw IOException("Nem olvasható fájl: $uri") }, maxLongSide)

    @Throws(IOException::class)
    fun decode(file: File, maxLongSide: Int = 3000): Bitmap = decode({ file.inputStream() }, maxLongSide)

    @Throws(IOException::class)
    fun decode(open: () -> InputStream, maxLongSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("A fájl nem érvényes kép.")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLongSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = open().use { BitmapFactory.decodeStream(it, null, opts) } ?: throw IOException("A kép nem dekódolható.")
        val orientation = runCatching { open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val oriented = applyExif(decoded, orientation)
        return scaleDownIfNeeded(oriented, maxLongSide)
    }

    private fun applyExif(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { if (it != src) src.recycle() }
    }

    fun scaleDownIfNeeded(src: Bitmap, maxLongSide: Int): Bitmap {
        val long = max(src.width, src.height)
        if (long <= maxLongSide) return src
        val s = maxLongSide.toFloat() / long
        return Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
            .also { if (it != src) src.recycle() }
    }

    /** Rotates by any angle (degrees, clockwise), filling the new corners with white. */
    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return src
        val m = Matrix().apply { postRotate(degrees) }
        if (degrees % 90f == 0f) return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val rect = android.graphics.RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        m.mapRect(rect)
        val out = Bitmap.createBitmap(rect.width().toInt().coerceAtLeast(1), rect.height().toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        m.postTranslate(-rect.left, -rect.top)
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Perspective-corrects the [quad] region of [src] into an upright rectangle. */
    fun warpPerspective(src: Bitmap, quad: Quad): Bitmap {
        val (w, h) = quad.targetSize()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val srcPts = quad.points.flatMap { listOf(it.x, it.y) }.toFloatArray()
        val dstPts = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) return src
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    fun toGray(src: Bitmap): GrayImage {
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        return GrayImage.fromArgb(pixels, src.width, src.height)
    }

    fun fromGray(img: GrayImage): Bitmap =
        Bitmap.createBitmap(img.toArgb(), img.width, img.height, Bitmap.Config.ARGB_8888)

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 90) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    fun savePng(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

package hu.kiadaskoveto.ocr

import android.graphics.Bitmap
import hu.kiadaskoveto.ocr.image.BitmapOps
import hu.kiadaskoveto.ocr.image.GrayImage
import hu.kiadaskoveto.ocr.image.Quad
import hu.kiadaskoveto.ocr.image.ReceiptBoundsDetector
import hu.kiadaskoveto.ocr.image.SkewEstimator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class ImageQualityIssue { BLURRY, TOO_DARK, OVEREXPOSED, LOW_RESOLUTION, LOW_CONTRAST }

data class PreprocessOptions(
    /** User-chosen rotation in quarter turns (clockwise), applied first. */
    val quarterTurns: Int = 0,
    /** User-adjusted crop in normalized 0..1 coordinates of the rotated image; null = automatic. */
    val normalizedCrop: Quad? = null,
    val autoCrop: Boolean = true,
    val autoRotate: Boolean = true,
    val deskew: Boolean = true,
    /** Output width for OCR: ~1280 px gives 25–35 px letters on an 80 mm receipt, Tesseract's sweet spot. */
    val targetWidth: Int = 1280,
)

data class PreprocessResult(
    /** Binarized, deskewed image fed to OCR. */
    val ocrBitmap: Bitmap,
    /** Grayscale enhanced image (shown in the preview). */
    val enhancedBitmap: Bitmap,
    val appliedQuarterTurns: Int,
    val skewDegrees: Double,
    val crop: Quad?,
    val issues: Set<ImageQualityIssue>,
    val sharpness: Double,
    val meanLuminance: Double,
    val imageHash: Long,
)

/**
 * Receipt image preprocessing: orientation → crop & perspective → grayscale → contrast →
 * noise reduction → deskew → resize → adaptive binarization. The original file is never modified.
 */
object ImagePreprocessor {

    /** Suggested crop for the crop editor (normalized coordinates), or null if none was found. */
    fun suggestCrop(bitmap: Bitmap): Quad? {
        val gray = BitmapOps.toGray(BitmapOps.scaleDownIfNeeded(bitmap.copy(Bitmap.Config.ARGB_8888, false), 800))
        val quad = ReceiptBoundsDetector.detect(gray) ?: return null
        return quad.normalized(gray.width, gray.height)
    }

    fun process(source: Bitmap, options: PreprocessOptions = PreprocessOptions()): PreprocessResult {
        var bmp = if (options.quarterTurns % 4 != 0) BitmapOps.rotate(source, 90f * options.quarterTurns) else source

        // Crop: user quad, otherwise automatic receipt detection.
        var crop: Quad? = options.normalizedCrop?.scaled(bmp.width.toFloat(), bmp.height.toFloat())
        if (crop == null && options.autoCrop) {
            crop = ReceiptBoundsDetector.detect(BitmapOps.toGray(bmp))
        }
        if (crop != null && !crop.isFullFrame(bmp.width, bmp.height)) bmp = BitmapOps.warpPerspective(bmp, crop)

        var gray = BitmapOps.toGray(bmp)
        val issues = HashSet<ImageQualityIssue>()
        val probe = if (gray.width > 900) gray.scaledToWidth(900) else gray
        val sharpness = probe.laplacianVariance()
        val luminance = probe.meanLuminance()
        if (sharpness < 45) issues += ImageQualityIssue.BLURRY
        if (luminance < 70) issues += ImageQualityIssue.TOO_DARK
        if (luminance > 245) issues += ImageQualityIssue.OVEREXPOSED
        if (min(gray.width, gray.height) < 500) issues += ImageQualityIssue.LOW_RESOLUTION
        val hist = probe.histogram()
        val spread = run {
            var lo = 0; var acc = 0; val n = probe.width * probe.height
            while (lo < 255 && acc < n * 0.05) { acc += hist[lo]; lo++ }
            var hi = 255; acc = 0
            while (hi > 0 && acc < n * 0.05) { acc += hist[hi]; hi-- }
            hi - lo
        }
        if (spread < 60) issues += ImageQualityIssue.LOW_CONTRAST

        // Normalize size: enough pixels per character for Tesseract without wasting memory.
        val target = options.targetWidth
        if (gray.width > target * 1.15 || gray.width < target * 0.6) {
            val w = if (gray.width < target * 0.6) min(target, gray.width * 2) else target
            gray = gray.scaledToWidth(w)
        }

        gray = gray.contrastStretched().boxBlur3()
        var binary = gray.adaptiveThreshold().despeckled().let { if (crop != null) it.withClearedBorder() else it }

        var turns = options.quarterTurns
        if (options.autoRotate && options.quarterTurns == 0) {
            val q = SkewEstimator.quarterTurnsForHorizontalText(binary)
            if (q != 0) {
                turns = q
                gray = gray.rotated90(q)
                binary = binary.rotated90(q)
            }
        }

        var skew = 0.0
        var enhanced = BitmapOps.fromGray(gray)
        var ocr = BitmapOps.fromGray(binary)
        if (options.deskew) {
            skew = SkewEstimator.estimateSkew(binary)
            if (abs(skew) >= 0.5) {
                enhanced = BitmapOps.rotate(enhanced, skew.toFloat())
                ocr = BitmapOps.rotate(ocr, skew.toFloat())
            }
        }
        return PreprocessResult(
            ocrBitmap = ocr,
            enhancedBitmap = enhanced,
            appliedQuarterTurns = turns,
            skewDegrees = skew,
            crop = crop,
            issues = issues,
            sharpness = sharpness,
            meanLuminance = luminance,
            imageHash = gray.differenceHash(),
        )
    }

    /** Perceptual hash of an image for duplicate detection (independent of preprocessing). */
    fun imageHash(bitmap: Bitmap): Long {
        val scaled = BitmapOps.scaleDownIfNeeded(bitmap.copy(Bitmap.Config.ARGB_8888, false), max(64, 256))
        return BitmapOps.toGray(scaled).differenceHash()
    }
}

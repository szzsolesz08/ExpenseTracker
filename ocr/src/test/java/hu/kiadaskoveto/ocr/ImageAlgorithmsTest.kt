package hu.kiadaskoveto.ocr

import hu.kiadaskoveto.core.hash.Hashing
import hu.kiadaskoveto.ocr.barcode.BarcodeDecoder
import hu.kiadaskoveto.ocr.image.GrayImage
import hu.kiadaskoveto.ocr.image.ReceiptBoundsDetector
import hu.kiadaskoveto.ocr.image.SkewEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tan

/** Pure JVM tests for the preprocessing algorithms (no Android framework needed). */
class ImageAlgorithmsTest {

    private fun filled(w: Int, h: Int, v: Int) = GrayImage(w, h).also { img -> for (i in img.data.indices) img.data[i] = v.toByte() }

    /** White page with dark horizontal "text lines", optionally sheared by [degrees]. */
    private fun textPage(w: Int = 400, h: Int = 600, degrees: Double = 0.0): GrayImage {
        val img = filled(w, h, 240)
        val t = tan(Math.toRadians(degrees))
        for (line in 0 until 20) {
            val baseY = 40 + line * 26
            for (x in 30 until w - 30) {
                if ((x / 7) % 3 == 2) continue // word gaps
                val y0 = (baseY - x * t).toInt()
                for (dy in 0 until 8) { val y = y0 + dy; if (y in 0 until h) img[x, y] = 20 }
            }
        }
        return img
    }

    @Test fun adaptiveThresholdSurvivesShadow() {
        val img = textPage()
        // Strong shadow over the left half
        for (y in 0 until img.height) for (x in 0 until img.width / 2) img[x, y] = (img[x, y] * 0.45).toInt()
        val bin = img.adaptiveThreshold()
        // Paper in the shadow stays white, text stays black
        assertEquals(255, bin[10, 5])
        assertEquals(0, bin[45, 42])
        assertEquals(0, bin[300, 42])
        // Global Otsu would turn much of the shadowed paper black; adaptive keeps ink ratio reasonable
        assertTrue(bin.inkRatio() < 0.35)
    }

    @Test fun contrastStretchExpandsRange() {
        val img = GrayImage(100, 1)
        for (x in 0 until 100) img[x, 0] = 100 + x / 2
        val s = img.contrastStretched()
        assertTrue(s[0, 0] < 10)
        assertTrue(s[99, 0] > 245)
    }

    @Test fun despeckleRemovesIsolatedDots() {
        val img = filled(20, 20, 255)
        img[10, 10] = 0
        for (x in 2..8) img[x, 3] = 0
        val d = img.despeckled()
        assertEquals(255, d[10, 10])
        assertEquals(0, d[5, 3])
    }

    @Test fun skewEstimateFindsRotationSign() {
        val straight = SkewEstimator.estimateSkew(textPage().adaptiveThreshold())
        assertTrue("straight page skew $straight", abs(straight) <= 0.5)
        val rising = SkewEstimator.estimateSkew(textPage(degrees = 4.0).adaptiveThreshold())
        assertTrue("rising text gives positive skew, was $rising", rising in 3.0..5.0)
        val falling = SkewEstimator.estimateSkew(textPage(degrees = -3.0).adaptiveThreshold())
        assertTrue("falling text gives negative skew, was $falling", falling in -4.0..-2.0)
    }

    @Test fun detectsSidewaysText() {
        val page = textPage().adaptiveThreshold()
        assertEquals(0, SkewEstimator.quarterTurnsForHorizontalText(page))
        assertEquals(1, SkewEstimator.quarterTurnsForHorizontalText(page.rotated90(1)))
    }

    @Test fun findsReceiptOnDarkBackground() {
        val photo = filled(600, 800, 40)
        for (y in 100 until 700) for (x in 200 until 400) photo[x, y] = 235
        val quad = ReceiptBoundsDetector.detect(photo)
        assertNotNull(quad)
        quad!!
        assertTrue(abs(quad.tl.x - 200) < 6 && abs(quad.tl.y - 100) < 6)
        assertTrue(abs(quad.br.x - 400) < 6 && abs(quad.br.y - 700) < 6)
        // Receipt filling the frame → no crop suggestion
        assertNull(ReceiptBoundsDetector.detect(filled(300, 300, 230)))
    }

    @Test fun blurLowersLaplacianVariance() {
        val sharp = textPage()
        var blurred = sharp
        repeat(4) { blurred = blurred.boxBlur3() }
        assertTrue(sharp.laplacianVariance() > blurred.laplacianVariance() * 3)
    }

    @Test fun differenceHashIsStableUnderSmallChanges() {
        val a = textPage()
        val b = a.copy().also { for (i in it.data.indices) it.data[i] = ((it.data[i].toInt() and 0xff) * 0.9).toInt().toByte() }
        val c = textPage(degrees = 10.0)
        assertTrue(Hashing.hammingDistance(a.differenceHash(), b.differenceHash()) <= 4)
        assertTrue(Hashing.hammingDistance(a.differenceHash(), c.differenceHash()) > 4)
    }

    @Test fun rotationRoundTrip() {
        val a = textPage(100, 60)
        assertEquals(0.0, a.rotated90(1).rotated90(3).meanAbsDiff(a), 0.0)
        assertEquals(0.0, a.rotated90(2).rotated90(2).meanAbsDiff(a), 0.0)
    }

    @Test fun mergesOverlappingPhotosOfOneReceipt() {
        fun l(vararg s: String) = s.map { OcrTextLine(it, 0.9) }
        val first = l("SPAR", "TEJ 329", "KENYER 549", "SAJT 1299")
        val second = l("KENYER 549", "SAJT 1.299", "ALMA 378", "OSSZESEN 2555")
        val merged = PageMerger.merge(listOf(first, second))
        assertEquals(listOf("SPAR", "TEJ 329", "KENYER 549", "SAJT 1299", "ALMA 378", "OSSZESEN 2555"), merged.map { it.text })
        // No overlap → simple concatenation
        assertEquals(8, PageMerger.merge(listOf(first, l("A 1", "B 2", "C 3", "D 4"))).size)
    }

    @Test fun gtinCheckDigits() {
        assertTrue(BarcodeDecoder.isValidGtin("5998200300015".let { fixCheck(it) }))
        assertTrue(BarcodeDecoder.isValidGtin("96385074"))
        assertFalse(BarcodeDecoder.isValidGtin("5998200300011".let { breakCheck(fixCheck(it)) }))
        assertFalse(BarcodeDecoder.isValidGtin("12345"))
    }

    private fun fixCheck(code: String): String {
        val body = code.dropLast(1).map { it - '0' }.reversed()
        val sum = body.mapIndexed { i, d -> if (i % 2 == 0) d * 3 else d }.sum()
        return code.dropLast(1) + ((10 - sum % 10) % 10)
    }
    private fun breakCheck(code: String) = code.dropLast(1) + ((code.last() - '0' + 1) % 10)
}

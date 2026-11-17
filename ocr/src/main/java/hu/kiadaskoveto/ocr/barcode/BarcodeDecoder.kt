package hu.kiadaskoveto.ocr.barcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

enum class BarcodeType(val label: String, val isProductCode: Boolean) {
    EAN_13("EAN-13", true), EAN_8("EAN-8", true), UPC_A("UPC-A", true), CODE_128("Code 128", false), QR_CODE("QR", false)
}

data class BarcodeResult(val text: String, val type: BarcodeType)

/**
 * Offline barcode decoding with ZXing (Apache-2.0). No lookup service is used: a product
 * barcode only identifies a product the user already has in the local database.
 * Note: the barcode at the bottom of a receipt is a transaction identifier, not a product list.
 */
class BarcodeDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.CODE_128, BarcodeFormat.QR_CODE,
                ),
                DecodeHintType.TRY_HARDER to true,
            )
        )
    }

    /** Decodes from a camera Y (luminance) plane. */
    fun decodeLuminance(yPlane: ByteArray, rowStride: Int, width: Int, height: Int): BarcodeResult? {
        val source = PlanarYUVLuminanceSource(yPlane, rowStride, height, 0, 0, width, height, false)
        return decode(BinaryBitmap(HybridBinarizer(source))) ?: decode(BinaryBitmap(HybridBinarizer(source.invert())))
    }

    /** Decodes from ARGB pixels (e.g. a still image). */
    fun decodeArgb(pixels: IntArray, width: Int, height: Int): BarcodeResult? {
        val source = RGBLuminanceSource(width, height, pixels)
        return decode(BinaryBitmap(HybridBinarizer(source)))
    }

    private fun decode(bitmap: BinaryBitmap): BarcodeResult? = try {
        val r = reader.decodeWithState(bitmap)
        val type = when (r.barcodeFormat) {
            BarcodeFormat.EAN_13 -> BarcodeType.EAN_13
            BarcodeFormat.EAN_8 -> BarcodeType.EAN_8
            BarcodeFormat.UPC_A -> BarcodeType.UPC_A
            BarcodeFormat.CODE_128 -> BarcodeType.CODE_128
            BarcodeFormat.QR_CODE -> BarcodeType.QR_CODE
            else -> null
        }
        type?.let { BarcodeResult(r.text, it) }
    } catch (_: NotFoundException) {
        null
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }

    companion object {
        /** Validates the GS1 check digit of EAN-8, UPC-A (12) and EAN-13 codes. */
        fun isValidGtin(code: String): Boolean {
            if (code.length !in setOf(8, 12, 13) || !code.all { it.isDigit() }) return false
            val digits = code.map { it - '0' }
            val body = digits.dropLast(1).reversed()
            val sum = body.mapIndexed { i, d -> if (i % 2 == 0) d * 3 else d }.sum()
            return (10 - sum % 10) % 10 == digits.last()
        }
    }
}

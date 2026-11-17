package hu.kiadaskoveto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.data.importer.ImportOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * End-to-end import on the device: image/PDF → preprocessing → bundled Tesseract OCR (offline) →
 * parsing → draft receipt in the local database. The receipt images are generated in the test (fixtures only).
 */
@RunWith(AndroidJUnit4::class)
class OcrImportWorkflowTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val c get() = (context.applicationContext as KiadasApp).container

    private val spar = """
        SPAR MAGYARORSZÁG KERESKEDELMI KFT.
        2000 PÉLDAVÁROS, MINTA U. 2.
        ADÓSZÁM: 12345678-2-42
        NYUGTA
        TEJ ESL 2,8% 1L            C00      329
        TRAP SAJT KG               C00    1.794
        0,600 kg x 2.990 Ft/kg
        BANAN LOOSE                C00      422
        0,846 kg x 499 Ft/kg
        COCA COLA 1,5L             C00      649
        VISSZAVÁLTÁSI DÍJ          E00       50
        ÖSSZESEN:                        3.244
        BANKKÁRTYA:                      3.244
        2024.03.15 14:32
        AP A12345678
    """.trimIndent()

    @Before fun clean() { c.importSession.reset() }

    private fun renderReceipt(text: String): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 30f; typeface = Typeface.MONOSPACE }
        val lines = text.lines()
        val width = (lines.maxOf { paint.measureText(it) } + 120).toInt()
        val lineH = 44
        val bmp = Bitmap.createBitmap(width, lines.size * lineH + 140, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(250, 250, 246))
        lines.forEachIndexed { i, l -> canvas.drawText(l, 60f, 90f + i * lineH, paint) }
        return bmp
    }

    private fun assertSparDraft(outcome: ImportOutcome) = runBlocking {
        val draft = outcome as? ImportOutcome.Draft ?: fail("Expected a draft, got $outcome").let { return@runBlocking }
        val d = c.receipts.detail(draft.receiptId)!!
        try {
            assertEquals("SPAR", d.receipt.storeName)
            assertEquals(LocalDate.of(2024, 3, 15), d.receipt.purchasedAt.toLocalDate())
            assertEquals(Money.ofForint(3244), d.receipt.printedTotal)
            assertTrue("at least 5 lines recognized, got ${d.items.map { it.rawName }}", d.items.size >= 5)
            assertTrue(d.documents.isNotEmpty())
            assertTrue("original file preserved", d.documents.all { c.storage.resolve(it.filePath).exists() })
        } finally {
            c.receipts.delete(draft.receiptId)
        }
    }

    @Test fun photographedReceiptIsRecognizedOffline() {
        val file = File(context.cacheDir, "incoming/test_receipt.jpg").apply { parentFile?.mkdirs() }
        file.outputStream().use { renderReceipt(spar).compress(Bitmap.CompressFormat.JPEG, 92, it) }
        c.importHelper.addCapture(file)
        val outcome = runBlocking { c.pipeline.run(c.importSession) {} }
        assertSparDraft(outcome)
    }

    @Test fun pdfReceiptIsRenderedAndRecognized() {
        val pdfFile = File(context.cacheDir, "test_receipt.pdf")
        val doc = PdfDocument()
        val lines = spar.lines()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(340, 40 + lines.size * 16, 1).create())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 11f; typeface = Typeface.MONOSPACE }
        lines.forEachIndexed { i, l -> page.canvas.drawText(l, 14f, 28f + i * 16, paint) }
        doc.finishPage(page)
        pdfFile.outputStream().use { doc.writeTo(it) }
        doc.close()
        val (temp, pages) = runBlocking { c.importHelper.preparePdf(Uri.fromFile(pdfFile)) }
        assertEquals(1, pages)
        runBlocking { c.importHelper.addPdfPages(temp, "test_receipt.pdf", listOf(0)) }
        val outcome = runBlocking { c.pipeline.run(c.importSession) {} }
        assertSparDraft(outcome)
    }

    /**
     * Photo-like fixtures (receipt on a dark table, rotated, uneven light) go through automatic edge
     * detection, perspective correction, binarization and OCR. Timing is logged for comparison.
     */
    @Test fun photographedSampleReceiptsReconcile() {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val expectations = listOf("spar_photo.jpg" to 3369L, "lidl_photo.png" to 2584L)
        for ((asset, total) in expectations) {
            c.importSession.reset()
            val file = File(context.cacheDir, "incoming/$asset").apply { parentFile?.mkdirs() }
            testAssets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
            c.importHelper.addCapture(file)
            val t0 = System.currentTimeMillis()
            val outcome = runBlocking { c.pipeline.run(c.importSession) {} }
            android.util.Log.i("OcrBenchmark", "$asset processed in ${System.currentTimeMillis() - t0} ms")
            val draft = outcome as? ImportOutcome.Draft ?: fail("Expected a draft for $asset, got $outcome").let { return }
            val d = runBlocking { c.receipts.detail(draft.receiptId)!! }
            try {
                assertEquals("$asset printed total", Money.ofForint(total), d.receipt.printedTotal)
                assertTrue("$asset store recognized", d.receipt.storeId != null)
                assertTrue("$asset lines recognized: ${d.items.size}", d.items.size >= 6)
            } finally {
                runBlocking { c.receipts.delete(draft.receiptId) }
            }
        }
    }

    @Test fun corruptedFileIsRejectedWithHungarianMessage() {
        val bad = File(context.cacheDir, "broken.pdf").apply { writeText("not a pdf") }
        try {
            runBlocking { c.importHelper.preparePdf(Uri.fromFile(bad)) }
            fail("A corrupted PDF must be rejected")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("PDF"))
        }
    }
}

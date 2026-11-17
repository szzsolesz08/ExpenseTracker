package hu.kiadaskoveto.data.backup

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.money.PriceMath
import hu.kiadaskoveto.data.Period
import hu.kiadaskoveto.data.WallClock
import hu.kiadaskoveto.database.KiadasDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Monthly spending report as a PDF, drawn locally with Android's PdfDocument. */
class PdfReportWriter(private val db: KiadasDatabase) {

    private val hu = Locale.forLanguageTag("hu-HU")
    private val pageW = 595 // A4 in points
    private val pageH = 842
    private val margin = 48f

    suspend fun write(month: YearMonth, out: OutputStream) = withContext(Dispatchers.IO) {
        val period = Period.month(month)
        val analytics = db.analyticsDao()
        val summary = analytics.periodSummary(period.from, period.to)
        val prev = YearMonth.from(month).minusMonths(1).let { analytics.periodSummary(Period.month(it).from, Period.month(it).to) }
        val categories = db.categoryDao().all().associateBy { it.id }
        val catTotals = analytics.categoryTotals(period.from, period.to).sortedByDescending { it.total }
        val storeTotals = analytics.storeTotals(period.from, period.to).sortedByDescending { it.total }
        val topProducts = analytics.observeProductStats(period.from, period.to, "TOTAL", 10).first()
        val receipts = db.receiptDao().search("", null, period.from, period.to, Long.MIN_VALUE / 4, Long.MAX_VALUE / 4, false).first()

        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        fun canvas(): android.graphics.Canvas = checkNotNull(page).canvas
        var y = 0f
        val title = Paint().apply { color = Color.rgb(23, 29, 26); textSize = 20f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
        val h2 = Paint().apply { color = Color.rgb(14, 107, 80); textSize = 13f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
        val body = Paint().apply { color = Color.rgb(23, 29, 26); textSize = 10f; isAntiAlias = true }
        val muted = Paint().apply { color = Color.rgb(91, 99, 94); textSize = 9f; isAntiAlias = true }
        val right = Paint(body).apply { textAlign = Paint.Align.RIGHT }
        val bar = Paint().apply { color = Color.rgb(14, 122, 90) }
        val rule = Paint().apply { color = Color.rgb(220, 229, 223); strokeWidth = 0.7f }

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            y = margin
            canvas().drawText("KiadásKövető · ${month.format(DateTimeFormatter.ofPattern("yyyy. LLLL", hu))}", margin, pageH - 24f, muted)
            canvas().drawText("$pageNo. oldal", pageW - margin, pageH - 24f, Paint(muted).apply { textAlign = Paint.Align.RIGHT })
        }
        fun ensure(space: Float) { if (page == null || y + space > pageH - 60) newPage() }
        fun line(left: String, rightText: String, paint: Paint = body) {
            ensure(16f)
            val c = canvas()
            c.drawText(left.take(70), margin, y, paint)
            c.drawText(rightText, pageW - margin, y, right)
            y += 15f
        }

        newPage()
        val c0 = canvas()
        c0.drawText("Havi költési jelentés", margin, y + 6, title); y += 26
        c0.drawText(month.format(DateTimeFormatter.ofPattern("yyyy. LLLL", hu)).replaceFirstChar { it.titlecase(hu) }, margin, y, h2); y += 22
        line("Összes költés", Money(summary.total).format())
        line("Blokkok száma", summary.count.toString())
        line("Átlagos kosárérték", PriceMath.average(Money(summary.total), summary.count).roundedToForint().format())
        val change = PriceMath.percentChange(Money(prev.total), Money(summary.total))
        line("Változás az előző hónaphoz képest", change?.let { (if (it.signum() > 0) "+" else "") + it.toPlainString().replace('.', ',') + "%" } ?: "nincs előző havi adat")
        y += 10

        fun section(name: String) { ensure(40f); y += 8; canvas().drawText(name, margin, y, h2); y += 8; canvas().drawLine(margin, y, pageW - margin, y, rule); y += 14 }

        section("Kategóriák")
        if (catTotals.isEmpty()) line("Nincs adat ebben a hónapban.", "")
        val max = catTotals.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1
        catTotals.forEach { ct ->
            ensure(16f)
            val name = ct.categoryId?.let { categories[it]?.name } ?: "Nem besorolt"
            val share = PriceMath.percentOf(Money(ct.total), Money(catTotals.sumOf { it.total }))
            val w = ((pageW - 2 * margin - 260) * (ct.total.toDouble() / max)).toFloat().coerceAtLeast(1f)
            canvas().drawRect(margin + 170, y - 8, margin + 170 + w, y - 1, bar)
            line(name, Money(ct.total).format() + "  (" + share.toPlainString().replace('.', ',') + "%)")
        }

        section("Boltok")
        storeTotals.forEach { st -> line("${st.storeName} · ${st.visits} vásárlás", Money(st.total).format()) }

        section("Legnagyobb tételek (termékek)")
        topProducts.forEach { p -> line("${p.name} · ${p.purchaseCount}×", Money(p.total).format()) }

        section("Blokkok")
        val df = DateTimeFormatter.ofPattern("yyyy.MM.dd.")
        receipts.forEach { r -> line("${WallClock.fromStored(r.purchasedAt).format(df)}  ${r.storeName ?: "Ismeretlen bolt"}", Money(r.totalMinor).format()) }

        ensure(40f); y += 16
        canvas().drawText("A jelentés kizárólag a készüléken tárolt, jóváhagyott blokkok adataiból készült.", margin, y, muted)
        page?.let { doc.finishPage(it) }
        out.use { doc.writeTo(it) }
        doc.close()
    }
}

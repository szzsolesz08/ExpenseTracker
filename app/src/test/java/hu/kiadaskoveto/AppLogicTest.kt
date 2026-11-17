package hu.kiadaskoveto

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hu.kiadaskoveto.core.money.MeasureUnit
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.money.Quantity
import hu.kiadaskoveto.data.Period
import hu.kiadaskoveto.data.WallClock
import hu.kiadaskoveto.data.repo.ItemInput
import hu.kiadaskoveto.data.repo.PriceNormalizer
import hu.kiadaskoveto.data.repo.ReceiptRepository
import hu.kiadaskoveto.data.settings.AppSettings
import hu.kiadaskoveto.database.KiadasDatabase
import hu.kiadaskoveto.database.entity.BudgetEntity
import hu.kiadaskoveto.database.model.PricePoint
import hu.kiadaskoveto.domain.ItemKind
import hu.kiadaskoveto.domain.ReceiptStatus
import hu.kiadaskoveto.feature.dashboard.DashboardViewModel
import hu.kiadaskoveto.feature.receipts.DatePreset
import hu.kiadaskoveto.feature.receipts.ReceiptsFilterState
import hu.kiadaskoveto.parser.duplicate.DuplicateReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import hu.kiadaskoveto.parser.ReceiptParser
import hu.kiadaskoveto.parser.model.OcrInput
import hu.kiadaskoveto.parser.model.OcrLine
import hu.kiadaskoveto.data.importer.hasReceiptContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppLogicTest {

    private lateinit var db: KiadasDatabase
    private lateinit var c: AppContainer

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = KiadasDatabase.inMemory(ApplicationProvider.getApplicationContext())
        c = AppContainer(ApplicationProvider.getApplicationContext(), db)
        runBlocking { c.categories.ensureDefaults() }
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    private fun item(name: String, total: Long, categoryKey: String? = null, qtyMilli: Long? = null, unit: MeasureUnit? = null, unitPrice: Long? = null, discount: Long = 0) =
        runBlocking {
            ItemInput(rawName = name, displayName = name, categoryId = categoryKey?.let { c.categories.byKey(it)?.id },
                quantity = qtyMilli?.let(::Quantity), unit = unit, unitPrice = unitPrice?.let { Money.ofForint(it) },
                lineTotal = Money.ofForint(total), discount = Money.ofForint(discount), originalText = name)
        }

    private suspend fun receipt(store: String?, at: LocalDateTime, items: List<ItemInput>, printed: Long? = null, confirm: Boolean = true): Long {
        val storeId = c.stores.resolve(store, store?.lowercase(), null, null)
        val id = c.receipts.saveDraft(
            ReceiptRepository.NewReceipt(storeId, store, at, true, true, printed?.let { Money.ofForint(it) }, null, null, "CARD", null, null, null, null,
                0.9, null, ReceiptStatus.DRAFT),
            items, emptyList(),
        )
        if (confirm) c.receipts.confirm(id)
        return id
    }

    @Test fun confirmingLinksProductsAndRemembersAliasesPerStore() = runBlocking {
        val now = LocalDateTime.now()
        val first = receipt("SPAR", now.minusDays(2), listOf(item("TEJ ESL 2,8% 1L", 329, "dairy"), item("KENYER FEHER", 549, "bakery")))
        val milkProduct = c.receipts.detail(first)!!.items.first { it.rawName.startsWith("TEJ") }.productId
        assertNotNull(milkProduct)
        // Same description at the same store links automatically
        assertEquals(milkProduct, c.products.confirmedProductFor("TEJ ESL 2,8% 1L", c.stores.resolve("SPAR", null, null, null))?.id)
        // ...but not at another store: never assume identical names mean the same product
        assertNull(c.products.confirmedProductFor("TEJ ESL 2,8% 1L", c.stores.resolve("Lidl", null, null, null)))
        val second = receipt("SPAR", now.minusDays(1), listOf(item("TEJ ESL 2,8% 1L", 349, "dairy")))
        assertEquals(milkProduct, c.receipts.detail(second)!!.items.single().productId)
        // Price history now has two points on the same basis
        val history = c.products.observePriceHistory(milkProduct!!).first()
        assertEquals(listOf(32_900L, 34_900L), history.map { it.lineTotalMinor })
    }

    @Test fun categoryCorrectionIsRememberedForTheNextReceipt() = runBlocking {
        val id = receipt("SPAR", LocalDateTime.now(), listOf(item("TITOKZATOS ARU", 990, "uncategorized")), confirm = false)
        val line = c.receipts.detail(id)!!.items.single()
        val household = c.categories.byKey("household")!!.id
        c.receipts.setItemCategory(line.id, household)
        val ctx = c.learning.context()
        val storeKey = c.stores.resolve("SPAR", null, null, null).toString()
        val guess = c.parser.interpretLine("TITOKZATOS ARU", storeKey, ctx).second
        assertEquals("household", guess.key)
    }

    @Test fun duplicateReceiptIsDetectedAndCanBeOverridden() = runBlocking {
        val at = LocalDateTime.of(2024, 3, 15, 14, 32)
        receipt("SPAR", at, listOf(item("TEJ", 329), item("KENYER", 549)), printed = 878)
        val again = receipt("SPAR", at, listOf(item("TEJ", 329), item("KENYER", 549)), printed = 878, confirm = false)
        val dup = c.receipts.findDuplicate(again)
        assertNotNull(dup)
        assertTrue(dup!!.match.reason in setOf(DuplicateReason.SAME_CONTENT, DuplicateReason.SAME_METADATA))
        c.receipts.overrideDuplicate(again)
        assertNull(c.receipts.findDuplicate(again))
    }

    @Test fun itemEditsKeepPrintedTotalAndRecomputeLineSum() = runBlocking {
        val id = receipt("SPAR", LocalDateTime.now(), listOf(item("KIFLI", 225, discount = -190)), printed = 135, confirm = false)
        var r = c.receipts.detail(id)!!.receipt
        assertEquals(Money.ofForint(35), r.computedTotal)
        assertEquals(Money.ofForint(100), r.totalDifference)
        val line = c.receipts.detail(id)!!.items.single()
        c.receipts.updateItem(line.id, ItemInput(rawName = line.rawName, displayName = "Kifli", categoryId = line.categoryId, quantity = null, unit = null,
            unitPrice = null, lineTotal = Money.ofForint(225), discount = Money.ofForint(-90)))
        r = c.receipts.detail(id)!!.receipt
        assertEquals(Money.ofForint(135), r.computedTotal)
        assertEquals(Money.ZERO, r.totalDifference)
        assertEquals(Money.ofForint(135), r.total)
    }

    @Test fun budgetAlertsFireOncePerThreshold() = runBlocking {
        val budgets = hu.kiadaskoveto.data.repo.BudgetRepository(db) { AppSettings(budgetThresholds = setOf(50, 75, 90, 100)) }
        budgets.save(BudgetEntity(scope = "TOTAL", period = "MONTHLY", limitMinor = 300_000, createdAt = 0, updatedAt = 0))
        receipt("SPAR", LocalDateTime.now(), listOf(item("BEVASARLAS", 2_400)))
        val first = budgets.newAlerts()
        assertEquals(1, first.size)
        assertEquals(75, first.single().threshold)
        assertTrue("the same thresholds never fire twice in a period", budgets.newAlerts().isEmpty())
        receipt("SPAR", LocalDateTime.now(), listOf(item("BEVASARLAS 2", 800)))
        val over = budgets.newAlerts()
        assertEquals(100, over.single().threshold)
        assertEquals(Money.ofForint(-200), budgets.progress().single().remaining)
    }

    @Test fun dashboardViewModelShowsExactMonthFigures() = runBlocking {
        val month = YearMonth.now()
        receipt("SPAR", month.atDay(1).atTime(10, 0), listOf(item("TEJ", 300, "dairy"), item("ALMA", 450, "produce")))
        receipt("Lidl", month.atDay(1).atTime(18, 0), listOf(item("KENYER", 550, "bakery")))
        receipt("Lidl", month.minusMonths(1).atDay(5).atTime(9, 0), listOf(item("KENYER", 1_000, "bakery")))
        val vm = DashboardViewModel(c)
        val s = withTimeout(10_000) { vm.state.first { it.loaded && it.count == 2 } }
        assertEquals(Money.ofForint(1_300), s.total)
        assertEquals(Money.ofForint(650), s.average)
        assertEquals(BigDecimal("30.0"), s.change)
        assertEquals(listOf("bakery", "produce", "dairy"), s.categories.map { it.first?.key })
        assertEquals(2, s.recent.size.coerceAtMost(2))
    }

    @Test fun deletingAReceiptRemovesItsLinesAndOrphanProducts() = runBlocking {
        val id = receipt("SPAR", LocalDateTime.now(), listOf(item("EGYEDI TERMEK", 100)))
        assertEquals(1, c.products.productsWithStats.first().size)
        c.receipts.delete(id)
        assertNull(c.receipts.detail(id))
        assertTrue(c.products.productsWithStats.first().isEmpty())
    }

    @Test fun csvAndJsonExportsContainConfirmedData() = runBlocking {
        receipt("SPAR", LocalDateTime.of(2024, 3, 15, 14, 32), listOf(item("TEJ ESL 2,8% 1L", 329, "dairy")))
        receipt("Draft bolt", LocalDateTime.now(), listOf(item("NEM SZAMIT", 999)), confirm = false)
        val csv = ByteArrayOutputStream().also { c.backup.exportCsv(it) }.toString(Charsets.UTF_8)
        assertTrue(csv.startsWith("﻿Blokk azonosító;Dátum;Bolt"))
        assertTrue(csv.contains("2024-03-15 14:32;SPAR;TEJ ESL 2,8% 1L"))
        assertTrue("drafts are not exported as spending", !csv.contains("NEM SZAMIT"))
        val json = ByteArrayOutputStream().also { c.backup.exportJson(it) }.toString(Charsets.UTF_8)
        assertTrue(json.contains("\"receipts\""))
        assertTrue(json.contains("TEJ ESL 2,8% 1L"))
    }

    @Test fun placeholderWrittenBeforeOcrIsFilledOrMarkedFailed() = runBlocking {
        val placeholder = c.receipts.saveDraft(
            ReceiptRepository.NewReceipt(null, null, LocalDateTime.now(), false, false, null, null, null, null, null, null, null, null,
                0.0, null, ReceiptStatus.FAILED, "A feldolgozás megszakadt"),
            emptyList(), emptyList(),
        )
        // Visible in the list even before OCR finishes (survives a process kill)
        assertEquals(ReceiptStatus.FAILED, c.receipts.detail(placeholder)!!.receipt.status)
        c.receipts.fillDraft(placeholder,
            ReceiptRepository.NewReceipt(null, "SPAR", LocalDateTime.of(2024, 3, 15, 14, 32), true, true, Money.ofForint(878), null, null, "CARD",
                null, null, null, null, 0.9, "TEJ 329\nKENYER 549", ReceiptStatus.DRAFT),
            listOf(item("TEJ", 329), item("KENYER", 549)))
        val d = c.receipts.detail(placeholder)!!
        assertEquals(ReceiptStatus.DRAFT, d.receipt.status)
        assertEquals(2, d.items.size)
        assertEquals(Money.ZERO, d.receipt.totalDifference)
        c.receipts.markFailed(placeholder, "OCR hiba", null)
        assertEquals("OCR hiba", c.receipts.detail(placeholder)!!.receipt.failureReason)
    }

    @Test fun periodsUseWallClockDates() {
        val t = LocalDateTime.of(2024, 3, 31, 23, 59)
        assertEquals(t, WallClock.fromStored(WallClock.toStored(t)))
        val march = Period.month(YearMonth.of(2024, 3))
        assertTrue(WallClock.toStored(t) in march.from until march.to)
    }
}

class PureLogicTest {

    private fun point(id: Long, at: LocalDateTime, total: Long, qty: Long? = null, unit: String? = null, unitPrice: Long? = null, size: String? = null, sizeUnit: String? = null) =
        PricePoint(id, id, 1, WallClock.toStored(at), 1, "SPAR", qty, unit, unitPrice, total, 0, size, sizeUnit)

    @Test fun weighedProductsCompareOnPricePerKg() {
        val a = point(1, LocalDateTime.of(2024, 1, 1, 10, 0), 179_400, 600, "KILOGRAM", 299_000)
        val b = point(2, LocalDateTime.of(2024, 2, 1, 10, 0), 166_400, 520, "KILOGRAM", 320_000)
        val change = PriceNormalizer.changes(listOf(a, b), mapOf(1L to "Trappista")).single()
        assertEquals(Money.ofForint(2_990), change.previous.price)
        assertEquals(Money.ofForint(3_200), change.current.price)
        assertEquals(BigDecimal("7.0"), change.percent)
    }

    @Test fun differentPackageSizesAreNeverComparedDirectly() {
        val small = point(1, LocalDateTime.of(2024, 1, 1, 10, 0), 32_900, size = "1", sizeUnit = "LITER")
        val big = point(2, LocalDateTime.of(2024, 2, 1, 10, 0), 59_900, size = "2", sizeUnit = "LITER")
        assertTrue(PriceNormalizer.changes(listOf(small, big), emptyMap()).isEmpty())
        val sameSize = point(3, LocalDateTime.of(2024, 3, 1, 10, 0), 34_900, size = "1000", sizeUnit = "MILLILITER")
        val change = PriceNormalizer.changes(listOf(small, big, sameSize), emptyMap()).single()
        assertEquals(Money.ofForint(329), change.previous.price)
        assertEquals(Money.ofForint(349), change.current.price)
    }

    @Test fun multiQuantityLinesCompareOnPricePerPiece() {
        val a = point(1, LocalDateTime.of(2024, 1, 1, 10, 0), 22_500, qty = 5_000, unit = "PIECE")
        val b = point(2, LocalDateTime.of(2024, 2, 1, 10, 0), 5_000, qty = 1_000, unit = "PIECE")
        val change = PriceNormalizer.changes(listOf(a, b), emptyMap()).single()
        assertEquals(Money.ofForint(45), change.previous.price)
        assertEquals(Money.ofForint(50), change.current.price)
    }

    @Test fun receiptFilterTranslatesPresetsAndAmounts() {
        val f = ReceiptsFilterState(query = "  túró ", preset = DatePreset.THIS_MONTH, minText = "1 000", maxText = "5.000").toFilter()
        assertEquals("túró", f.query)
        assertEquals(100_000L, f.minTotal)
        assertEquals(500_000L, f.maxTotal)
        assertEquals(Period.month(YearMonth.now()).from, f.from)
        assertTrue(ReceiptsFilterState().toFilter().let { it.from < 0 && it.to > 0 })
        assertTrue(!ReceiptsFilterState().isFiltered)
        assertEquals(ItemKind.PRODUCT, ItemKind.valueOf("PRODUCT"))
    }

    @Test fun photoWithoutReceiptIsReportedInsteadOfSavedAsEmptyDraft() {
        val parser = ReceiptParser()
        val noise = parser.parse(OcrInput(listOf("ed", "eze)", "----a  —").map { OcrLine(it, 0.36) }))
        assertFalse(noise.hasReceiptContent())
        val receipt = parser.parse(OcrInput(listOf("SPAR", "TEJ 1L        329", "ÖSSZESEN:        329").map { OcrLine(it, 0.9) }))
        assertTrue(receipt.hasReceiptContent())
    }
}

package hu.kiadaskoveto.data.repo

import hu.kiadaskoveto.core.money.MeasureUnit
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.core.money.PackageSize
import hu.kiadaskoveto.core.money.PriceMath
import hu.kiadaskoveto.core.money.Quantity
import hu.kiadaskoveto.data.Period
import hu.kiadaskoveto.data.WallClock
import hu.kiadaskoveto.database.KiadasDatabase
import hu.kiadaskoveto.database.model.PricePoint
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime

/** Basis on which two purchases of a product can be compared. */
data class PriceBasis(val label: String, val key: String)

data class ComparablePrice(val point: PricePoint, val price: Money, val basis: PriceBasis) {
    val date: LocalDateTime get() = WallClock.fromStored(point.purchasedAt)
}

data class PriceChange(
    val productId: Long,
    val productName: String,
    val previous: ComparablePrice,
    val current: ComparablePrice,
) {
    val difference: Money get() = current.price - previous.price
    val percent: BigDecimal? get() = PriceMath.percentChange(previous.price, current.price)
}

/**
 * Price normalization. Purchases are only compared when they share a basis:
 * - weighed goods: printed unit price per kg / l,
 * - counted goods with a printed quantity: price per piece,
 * - otherwise the line price, only between purchases of the same known package size.
 * Different package sizes are never compared directly.
 */
object PriceNormalizer {

    fun comparable(p: PricePoint): ComparablePrice? {
        val unit = p.unit?.let { runCatching { MeasureUnit.valueOf(it) }.getOrNull() }
        val qty = p.quantityMilli?.let(::Quantity)
        val size = packageSizeOf(p.packageSizeAmount, p.packageSizeUnit)
        val unitPriceMinor = p.unitPriceMinor
        // Discounts are excluded: shelf price development is what is compared.
        return when {
            unit != null && unit.baseUnit in setOf(MeasureUnit.BaseUnit.KILOGRAM, MeasureUnit.BaseUnit.LITER) && unitPriceMinor != null -> {
                val perBase = Money(unitPriceMinor).toBigDecimal().divide(unit.toBase, MathContext.DECIMAL64)
                ComparablePrice(p, Money.fromBigDecimal(perBase), PriceBasis("Ft/${unit.baseUnit.symbol}", "base:${unit.baseUnit}"))
            }
            qty != null && qty.isPositive && (unit == null || unit.baseUnit == MeasureUnit.BaseUnit.PIECE) -> {
                val each = Money(p.lineTotalMinor).toBigDecimal().divide(qty.toBigDecimal(), 2, RoundingMode.HALF_UP)
                ComparablePrice(p, Money.fromBigDecimal(each), PriceBasis(sizeLabel(size, "Ft/db"), "piece:${size?.let { sizeKey(it) } ?: "?"}"))
            }
            qty == null && size != null -> ComparablePrice(p, Money(p.lineTotalMinor), PriceBasis(sizeLabel(size, "Ft/db"), "piece:${sizeKey(size)}"))
            qty == null -> ComparablePrice(p, Money(p.lineTotalMinor), PriceBasis("Ft/db", "piece:?"))
            else -> null
        }
    }

    private fun sizeKey(s: PackageSize) = s.baseAmount.stripTrailingZeros().toPlainString() + s.unit.baseUnit.name
    private fun sizeLabel(s: PackageSize?, fallback: String) = if (s == null) fallback else "Ft / ${s.format()}"

    /** Latest vs previous comparable price per product. */
    fun changes(points: List<PricePoint>, names: Map<Long, String>): List<PriceChange> =
        points.filter { it.productId != null }.groupBy { it.productId!! }.mapNotNull { (pid, list) ->
            val comparable = list.mapNotNull { comparable(it) }.sortedBy { it.point.purchasedAt }
            val latest = comparable.lastOrNull() ?: return@mapNotNull null
            // Same product identity (user-confirmed) and same basis; unknown sizes only compare within
            // one product identity, which was linked through an identical printed description.
            val previous = comparable.dropLast(1).lastOrNull { it.basis.key == latest.basis.key } ?: return@mapNotNull null
            PriceChange(pid, names[pid] ?: "Termék #$pid", previous, latest)
        }
}

class AnalyticsRepository(private val db: KiadasDatabase) {
    private val dao = db.analyticsDao()

    fun periodSummary(p: Period) = dao.observePeriodSummary(p.from, p.to)
    fun monthlyTotals(p: Period) = dao.observeMonthlyTotals(p.from, p.to)
    fun dailyTotals(p: Period) = dao.observeDailyTotals(p.from, p.to)
    fun categoryTotals(p: Period) = dao.observeCategoryTotals(p.from, p.to)
    fun categoryMonthly(p: Period) = dao.observeCategoryMonthly(p.from, p.to)
    fun receiptLevelDiscounts(p: Period) = dao.observeReceiptLevelDiscounts(p.from, p.to)
    fun savings(p: Period) = dao.observeSavings(p.from, p.to)
    fun storeTotals(p: Period) = dao.observeStoreTotals(p.from, p.to)
    fun productStats(p: Period, orderBy: String, limit: Int) = dao.observeProductStats(p.from, p.to, orderBy, limit)
    val allPricePoints = dao.observeAllPricePoints()
    val firstPurchase = dao.observeFirstPurchase()

    /** Days to average over: elapsed days for the running month, full length otherwise. */
    fun daysForAverage(monthStart: LocalDate, today: LocalDate = LocalDate.now()): Int {
        val end = monthStart.plusMonths(1)
        return when {
            today.isBefore(monthStart) -> 0
            today.isBefore(end) -> today.dayOfMonth
            else -> monthStart.lengthOfMonth()
        }
    }
}

package hu.kiadaskoveto.core.money

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Deterministic price calculations. Every function either returns an exact result
 * or `null` when the inputs do not allow a reliable answer — it never guesses.
 */
object PriceMath {

    /** Unit price = line total / quantity, only when quantity is known and positive. */
    fun unitPrice(lineTotal: Money, quantity: Quantity?): Money? {
        if (quantity == null || !quantity.isPositive) return null
        return Money.fromBigDecimal(lineTotal.toBigDecimal().divide(quantity.toBigDecimal(), MathContext.DECIMAL64))
    }

    /** True when quantity × unitPrice equals lineTotal within [toleranceMinor] fillér (rounding on receipts). */
    fun isConsistent(quantity: Quantity, unitPrice: Money, lineTotal: Money, toleranceMinor: Long = 100): Boolean {
        val expected = unitPrice * quantity
        return kotlin.math.abs(expected.minor - lineTotal.minor) <= toleranceMinor
    }

    /**
     * Price per base unit (Ft/kg, Ft/l, Ft/db) for a purchase of [packages] packages of [size].
     * Returns null when the size is unknown — callers must not compare such prices.
     */
    fun pricePerBaseUnit(lineTotal: Money, packages: Quantity, size: PackageSize?): BigDecimal? {
        if (size == null || size.unit == MeasureUnit.UNKNOWN || !packages.isPositive) return null
        val totalBase = size.baseAmount.multiply(packages.toBigDecimal())
        if (totalBase.signum() <= 0) return null
        return lineTotal.toBigDecimal().divide(totalBase, 2, RoundingMode.HALF_UP)
    }

    /** Percentage change from [previous] to [current], rounded to one decimal; null if previous is zero. */
    fun percentChange(previous: Money, current: Money): BigDecimal? {
        if (previous.isZero) return null
        return current.toBigDecimal().subtract(previous.toBigDecimal())
            .multiply(BigDecimal(100))
            .divide(previous.toBigDecimal().abs(), 1, RoundingMode.HALF_UP)
    }

    /** Share of [part] in [whole] as a percentage with one decimal; zero when whole is zero. */
    fun percentOf(part: Money, whole: Money): BigDecimal {
        if (whole.isZero) return BigDecimal.ZERO.setScale(1)
        return part.toBigDecimal().multiply(BigDecimal(100))
            .divide(whole.toBigDecimal(), 1, RoundingMode.HALF_UP)
    }

    /** Average of [total] over [count] items, rounded to whole fillér. */
    fun average(total: Money, count: Int): Money {
        if (count <= 0) return Money.ZERO
        return Money.fromBigDecimal(total.toBigDecimal().divide(BigDecimal(count), 2, RoundingMode.HALF_UP))
    }

    /** Hungarian cash rounding (5 Ft steps) as applied to cash payments since 2008. */
    fun cashRounded(total: Money): Money {
        val forint = total.toBigDecimal().setScale(0, RoundingMode.HALF_UP).toLong()
        val remainder = Math.floorMod(forint, 5L)
        val rounded = if (remainder <= 2) forint - remainder else forint + (5 - remainder)
        return Money.ofForint(rounded)
    }
}

package hu.kiadaskoveto.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Exact monetary amount in HUF, stored as minor units (fillér, 1/100 Ft).
 *
 * All financial arithmetic in the application goes through this type or through
 * [BigDecimal]; floating point is never used for money.
 */
@JvmInline
value class Money(val minor: Long) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(Math.addExact(minor, other.minor))
    operator fun minus(other: Money): Money = Money(Math.subtractExact(minor, other.minor))
    operator fun unaryMinus(): Money = Money(Math.negateExact(minor))
    operator fun times(factor: Int): Money = Money(Math.multiplyExact(minor, factor.toLong()))
    override fun compareTo(other: Money): Int = minor.compareTo(other.minor)

    /** Multiplies by an exact quantity, rounding half-up to the nearest fillér. */
    operator fun times(quantity: Quantity): Money =
        fromBigDecimal(toBigDecimal().multiply(quantity.toBigDecimal()))

    val isZero: Boolean get() = minor == 0L
    val isNegative: Boolean get() = minor < 0L
    val isPositive: Boolean get() = minor > 0L
    val abs: Money get() = if (minor < 0) Money(-minor) else this

    /** Whole forints, truncated toward zero. */
    val forints: Long get() = minor / 100

    fun toBigDecimal(): BigDecimal = BigDecimal.valueOf(minor, 2)

    /** Rounded to whole forints (half up) for display of derived figures such as averages. */
    fun roundedToForint(): Money = Money(Math.floorDiv(minor + 50, 100L) * 100)

    /** Hungarian formatting, e.g. `1 299 Ft`, `-150 Ft`, `12,50 Ft`. Uses a narrow no-break space as group separator. */
    fun format(withCurrency: Boolean = true): String {
        val negative = minor < 0
        val absMinor = kotlin.math.abs(minor)
        val whole = absMinor / 100
        val frac = (absMinor % 100).toInt()
        val grouped = groupThousands(whole)
        val body = if (frac == 0) grouped else grouped + "," + frac.toString().padStart(2, '0')
        val sign = if (negative) "-" else ""
        return if (withCurrency) "$sign$body Ft" else "$sign$body"
    }

    override fun toString(): String = format()

    companion object {
        val ZERO = Money(0)

        fun ofForint(forint: Long): Money = Money(Math.multiplyExact(forint, 100L))

        fun fromBigDecimal(value: BigDecimal): Money =
            Money(value.setScale(2, RoundingMode.HALF_UP).unscaledValue().longValueExact())

        fun sum(values: Iterable<Money>): Money = values.fold(ZERO) { acc, m -> acc + m }

        internal fun groupThousands(value: Long): String {
            val s = value.toString()
            if (s.length <= 3) return s
            val sb = StringBuilder()
            val firstGroup = s.length % 3
            if (firstGroup > 0) sb.append(s, 0, firstGroup)
            var i = firstGroup
            while (i < s.length) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s, i, i + 3)
                i += 3
            }
            return sb.toString()
        }
    }
}

fun Iterable<Money>.sumMoney(): Money = Money.sum(this)

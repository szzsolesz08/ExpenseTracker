package hu.kiadaskoveto.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/** Unit of measure as printed on receipts or product packages. */
enum class MeasureUnit(val symbol: String, val baseUnit: BaseUnit, val toBase: BigDecimal) {
    PIECE("db", BaseUnit.PIECE, BigDecimal.ONE),
    PACK("csomag", BaseUnit.PIECE, BigDecimal.ONE),
    KILOGRAM("kg", BaseUnit.KILOGRAM, BigDecimal.ONE),
    DEKAGRAM("dkg", BaseUnit.KILOGRAM, BigDecimal("0.01")),
    GRAM("g", BaseUnit.KILOGRAM, BigDecimal("0.001")),
    LITER("l", BaseUnit.LITER, BigDecimal.ONE),
    DECILITER("dl", BaseUnit.LITER, BigDecimal("0.1")),
    CENTILITER("cl", BaseUnit.LITER, BigDecimal("0.01")),
    MILLILITER("ml", BaseUnit.LITER, BigDecimal("0.001")),
    METER("m", BaseUnit.METER, BigDecimal.ONE),
    UNKNOWN("", BaseUnit.UNKNOWN, BigDecimal.ONE);

    enum class BaseUnit(val symbol: String) { PIECE("db"), KILOGRAM("kg"), LITER("l"), METER("m"), UNKNOWN("") }

    companion object {
        /** Parses a unit token as found on Hungarian receipts (case-insensitive, OCR tolerant). */
        fun parse(token: String?): MeasureUnit? {
            if (token == null) return null
            return when (token.trim().trimEnd('.').lowercase()) {
                "db", "darab", "drb", "d b", "pcs", "pc" -> PIECE
                "cs", "csom", "csomag", "csg", "pack" -> PACK
                "kg", "k g", "kilo" -> KILOGRAM
                "dkg" -> DEKAGRAM
                "g", "gr", "gramm" -> GRAM
                "l", "liter", "ltr", "lit" -> LITER
                "dl" -> DECILITER
                "cl" -> CENTILITER
                "ml" -> MILLILITER
                "m", "méter", "meter" -> METER
                else -> null
            }
        }
    }
}

/**
 * Exact quantity with three decimals (e.g. 0,456 kg), stored as thousandths.
 */
@JvmInline
value class Quantity(val milli: Long) : Comparable<Quantity> {
    fun toBigDecimal(): BigDecimal = BigDecimal.valueOf(milli, 3)
    override fun compareTo(other: Quantity): Int = milli.compareTo(other.milli)
    val isWhole: Boolean get() = milli % 1000 == 0L
    val isPositive: Boolean get() = milli > 0

    /** Hungarian formatting: `2`, `0,456`, `1,5`. */
    fun format(): String {
        val bd = toBigDecimal().stripTrailingZeros()
        val plain = if (bd.scale() < 0) bd.setScale(0).toPlainString() else bd.toPlainString()
        return plain.replace('.', ',')
    }

    override fun toString(): String = format()

    companion object {
        val ONE = Quantity(1000)
        fun of(value: BigDecimal): Quantity =
            Quantity(value.setScale(3, RoundingMode.HALF_UP).unscaledValue().longValueExact())
        fun ofWhole(value: Long): Quantity = Quantity(Math.multiplyExact(value, 1000L))
    }
}

/** A package size such as 1,5 l or 250 g. */
data class PackageSize(val amount: BigDecimal, val unit: MeasureUnit) {
    /** Amount expressed in the base unit (kg, l, db, m). */
    val baseAmount: BigDecimal get() = amount.multiply(unit.toBase)

    fun format(): String {
        val bd = amount.stripTrailingZeros()
        val plain = if (bd.scale() < 0) bd.setScale(0).toPlainString() else bd.toPlainString()
        return plain.replace('.', ',') + " " + unit.symbol
    }

    /** True when both sizes measure the same physical dimension (mass, volume, count). */
    fun isComparableWith(other: PackageSize): Boolean =
        unit.baseUnit != MeasureUnit.BaseUnit.UNKNOWN && unit.baseUnit == other.unit.baseUnit
}

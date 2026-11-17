package hu.kiadaskoveto.ui.format

import hu.kiadaskoveto.core.money.Money
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object Fmt {
    val HU: Locale = Locale.forLanguageTag("hu-HU")
    private val fullDate = DateTimeFormatter.ofPattern("yyyy. MMMM d.", HU)
    private val shortDate = DateTimeFormatter.ofPattern("MMM d.", HU)
    private val numericDate = DateTimeFormatter.ofPattern("yyyy.MM.dd.", HU)
    private val time = DateTimeFormatter.ofPattern("HH:mm", HU)

    fun money(m: Money): String = m.format()
    fun money(minor: Long): String = Money(minor).format()

    /** Compact amounts for axes and tight spaces: 950, 12e, 1,2M. */
    fun compact(minor: Long): String {
        val ft = Money(minor).forints
        val abs = kotlin.math.abs(ft)
        return when {
            abs >= 1_000_000 -> (BigDecimal(ft).divide(BigDecimal(1_000_000)).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')) + "M"
            abs >= 10_000 -> (ft / 1000).toString() + "e"
            abs >= 1000 -> BigDecimal(ft).divide(BigDecimal(1000)).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',') + "e"
            else -> ft.toString()
        }
    }

    fun percent(p: BigDecimal): String = p.stripTrailingZeros().toPlainString().replace('.', ',') + "%"

    fun signedPercent(p: BigDecimal): String = (if (p.signum() > 0) "+" else if (p.signum() < 0) "−" else "") + percent(p.abs())

    fun date(d: LocalDate): String = d.format(fullDate)
    fun dateShort(d: LocalDate): String = d.format(shortDate)
    fun dateNumeric(d: LocalDate): String = d.format(numericDate)
    fun dateTime(t: LocalDateTime, withTime: Boolean): String = date(t.toLocalDate()) + if (withTime) ", " + t.format(time) else ""

    fun monthName(ym: YearMonth): String = ym.month.getDisplayName(TextStyle.FULL_STANDALONE, HU)
    /** "Március" in the current year, "2024. március" otherwise (Hungarian month names are lower case mid-sentence). */
    fun monthTitle(ym: YearMonth): String =
        if (ym.year == YearMonth.now().year) monthName(ym).replaceFirstChar { it.titlecase(HU) } else "${ym.year}. ${monthName(ym)}"
    fun monthShort(ym: YearMonth): String = ym.month.getDisplayName(TextStyle.SHORT_STANDALONE, HU).trimEnd('.')

    fun weekday(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.FULL, HU)

    /** "ma", "tegnap", or the date. */
    fun relativeDay(d: LocalDate, today: LocalDate = LocalDate.now()): String = when (d) {
        today -> "Ma"
        today.minusDays(1) -> "Tegnap"
        else -> if (d.year == today.year) d.format(DateTimeFormatter.ofPattern("MMMM d.", HU)).replaceFirstChar { it.titlecase(HU) } else date(d)
    }

    fun bytes(n: Long): String = when {
        n >= 1L shl 30 -> String.format(HU, "%.1f GB", n / (1L shl 30).toDouble())
        n >= 1L shl 20 -> String.format(HU, "%.1f MB", n / (1L shl 20).toDouble())
        n >= 1L shl 10 -> String.format(HU, "%.0f kB", n / (1L shl 10).toDouble())
        else -> "$n bájt"
    }

    fun count(n: Int, noun: String): String = "$n $noun"
}

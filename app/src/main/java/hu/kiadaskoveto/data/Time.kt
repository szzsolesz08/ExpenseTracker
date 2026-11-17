package hu.kiadaskoveto.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * Purchase times are the wall-clock time printed on the receipt. They are stored as epoch millis
 * "as if UTC", so SQL date grouping returns the printed date regardless of the device time zone.
 */
object WallClock {
    fun toStored(t: LocalDateTime): Long = t.toEpochSecond(ZoneOffset.UTC) * 1000 + t.nano / 1_000_000
    fun fromStored(millis: Long): LocalDateTime = LocalDateTime.ofEpochSecond(Math.floorDiv(millis, 1000), (Math.floorMod(millis, 1000) * 1_000_000).toInt(), ZoneOffset.UTC)
    fun startOf(date: LocalDate): Long = toStored(date.atStartOfDay())
    fun now(): LocalDateTime = LocalDateTime.now()
    fun today(): LocalDate = LocalDate.now()
}

/** Half-open time range [from, to) in stored encoding. */
data class Period(val from: Long, val to: Long) {
    companion object {
        fun month(ym: YearMonth) = Period(WallClock.startOf(ym.atDay(1)), WallClock.startOf(ym.plusMonths(1).atDay(1)))
        fun months(from: YearMonth, toInclusive: YearMonth) = Period(WallClock.startOf(from.atDay(1)), WallClock.startOf(toInclusive.plusMonths(1).atDay(1)))
        fun week(containing: LocalDate): Period {
            val monday = containing.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return Period(WallClock.startOf(monday), WallClock.startOf(monday.plusDays(7)))
        }
        fun days(from: LocalDate, toInclusive: LocalDate) = Period(WallClock.startOf(from), WallClock.startOf(toInclusive.plusDays(1)))
        val ALL = Period(Long.MIN_VALUE / 4, Long.MAX_VALUE / 4)
    }
}

fun LocalDate.atNoon(): LocalDateTime = atTime(LocalTime.NOON)

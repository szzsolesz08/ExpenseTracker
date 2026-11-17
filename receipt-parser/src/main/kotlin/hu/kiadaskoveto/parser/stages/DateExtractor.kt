package hu.kiadaskoveto.parser.stages

import hu.kiadaskoveto.core.text.HungarianText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class DateDetection(
    val dateTime: LocalDateTime,
    val hasTime: Boolean,
    val lineIndex: Int,
    val implausible: Boolean,
    /** The year was an OCR misread repaired by rule (`2924` → `2024`); the user must confirm it. */
    val repaired: Boolean = false,
)

/** Stage 4: purchase date extraction. Validates every candidate; never fabricates a date. */
class DateExtractor(private val today: () -> LocalDate = { LocalDate.now() }) {

    private val ymd = Regex("(20\\d{2})\\s?[.\\-/]\\s?(\\d{1,2})\\s?[.\\-/]\\s?(\\d{1,2})\\.?(?:[\\s,]+(\\d{1,2})[:.](\\d{2})(?:[:.](\\d{2}))?)?")
    private val dmy = Regex("(?<!\\d)(\\d{1,2})\\s?[.\\-/]\\s?(\\d{1,2})\\s?[.\\-/]\\s?(20\\d{2})\\.?(?:[\\s,]+(\\d{1,2})[:.](\\d{2})(?:[:.](\\d{2}))?)?")
    private val monthName = Regex("(20\\d{2})\\.?\\s*(JAN|FEBR?|MARC?|APR|MAJ|JUN|JUL|AUG|SZEPT?|OKT|NOV|DEC)[A-Z]*\\.?\\s*(\\d{1,2})\\.?(?:\\s+(\\d{1,2})[:.](\\d{2}))?")
    private val timeOnly = Regex("(?<![\\d.])(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?!\\d)")
    private val months = mapOf("JAN" to 1, "FEB" to 2, "FEBR" to 2, "MAR" to 3, "MARC" to 3, "APR" to 4, "MAJ" to 5, "JUN" to 6,
        "JUL" to 7, "AUG" to 8, "SZEP" to 9, "SZEPT" to 9, "OKT" to 10, "NOV" to 11, "DEC" to 12)

    // Receipt years are 20xx, so a second digit other than 0 is an OCR misread (0 read as 9, 8, 6...).
    private val misreadYear = Regex(
        """(?<!\d)2[1-9](\d{2})(?=\s?[.\-/]\s?\d{1,2}\s?[.\-/]\s?\d{1,2})""" +
            """|(?<=\d{1,2}\s?[.\-/]\s?\d{1,2}\s?[.\-/]\s?)2[1-9](\d{2})(?!\d)"""
    )

    fun extract(lines: List<PreparedLine>): DateDetection? {
        val candidates = ArrayList<DateDetection>()
        lines.forEach { line ->
            val original = repairDigits(line.text)
            val before = candidates.size
            scan(line, original, repaired = false, candidates)
            if (candidates.size == before) {
                val fixed = misreadYear.replace(original) { m -> "20" + (m.groupValues[1].ifEmpty { m.groupValues[2] }) }
                if (fixed != original) {
                    val found = ArrayList<DateDetection>()
                    scan(line, fixed, repaired = true, found)
                    // A repair is only kept when it yields a believable purchase date.
                    candidates += found.filter { !it.implausible && it.dateTime.year >= today().year - 20 }
                }
            }
        }
        return pick(lines, candidates)
    }

    private fun scan(line: PreparedLine, text: String, repaired: Boolean, candidates: MutableList<DateDetection>) {
            fun add(y: Int, mo: Int, d: Int, h: String?, mi: String?, s: String?) {
                val date = runCatching { LocalDate.of(y, mo, d) }.getOrNull() ?: return
                val time = if (!h.isNullOrEmpty() && !mi.isNullOrEmpty())
                    runCatching { LocalTime.of(h.toInt(), mi.toInt(), s?.takeIf { it.isNotEmpty() }?.toInt() ?: 0) }.getOrNull() else null
                val now = today()
                val implausible = date.isAfter(now.plusDays(1)) || date.year < 2000
                val sameLineTime = time ?: timeOnly.find(text.substringAfterLast(d.toString()))?.let { tm ->
                    runCatching { LocalTime.of(tm.groupValues[1].toInt(), tm.groupValues[2].toInt()) }.getOrNull()
                }
                candidates += DateDetection(LocalDateTime.of(date, sameLineTime ?: LocalTime.NOON), sameLineTime != null, line.index, implausible, repaired)
            }
            ymd.findAll(text).forEach { m -> add(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4], m.groupValues[5], m.groupValues[6]) }
            if (candidates.none { it.lineIndex == line.index }) {
                dmy.findAll(text).forEach { m -> add(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt(), m.groupValues[4], m.groupValues[5], m.groupValues[6]) }
            }
            if (candidates.none { it.lineIndex == line.index }) {
                monthName.findAll(HungarianText.matchKey(text)).forEach { m ->
                    val mo = months[m.groupValues[2]] ?: return@forEach
                    add(m.groupValues[1].toInt(), mo, m.groupValues[3].toInt(), m.groupValues[4], m.groupValues[5], null)
                }
            }
    }

    private fun pick(lines: List<PreparedLine>, candidates: List<DateDetection>): DateDetection? {
        if (candidates.isEmpty()) return null

        // A date without time on its own line is often followed by the time on the next line.
        val enriched = candidates.map { c ->
            if (c.hasTime) c else {
                val next = lines.getOrNull(c.lineIndex + 1)?.text?.let { timeOnly.find(repairDigits(it)) }
                val t = next?.let { runCatching { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull() }
                if (t != null) c.copy(dateTime = LocalDateTime.of(c.dateTime.toLocalDate(), t), hasTime = true) else c
            }
        }
        val plausible = enriched.filter { !it.implausible }.ifEmpty { enriched }
        val byDate = plausible.groupBy { it.dateTime.toLocalDate() }
        val bestDate = byDate.maxWith(compareBy<Map.Entry<LocalDate, List<DateDetection>>> { it.value.size }.thenBy { e -> e.value.count { it.hasTime } }).key
        return byDate.getValue(bestDate).sortedWith(compareByDescending<DateDetection> { it.hasTime }.thenBy { it.lineIndex }).first()
    }

    /** OCR fixes inside date-like tokens only: `2O24.O3.l5` → `2024.03.15`. */
    private fun repairDigits(text: String): String = text.split(' ').joinToString(" ") { token ->
        if (token.count { it in ".:/-" } >= 1 && token.count { it.isDigit() } >= 3) HungarianText.repairNumericToken(token) else token
    }
}

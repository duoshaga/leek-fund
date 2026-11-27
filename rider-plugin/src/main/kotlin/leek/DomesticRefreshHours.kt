package leek

import java.time.LocalDate
import java.util.Locale

/** Beijing-time windows. Ends are exclusive, and a smaller end time continues into the next day. */
class DomesticRefreshHours private constructor(private val windows: List<Window>) {
    private data class Window(val start: Int, val end: Int) {
        val crossesMidnight = end < start
        val night = crossesMidnight || start >= 2100
    }

    val text = windows.joinToString(", ") { formatTime(it.start) + "-" + formatTime(it.end) }

    internal fun includesDay(hhmm: Int) = windows.any { !it.night && hhmm in it.start..<it.end }

    internal fun openingEvening(date: LocalDate, hhmm: Int): LocalDate? {
        for (window in windows) {
            if (!window.night) continue
            if (hhmm >= window.start && (window.crossesMidnight || hhmm < window.end)) return date
            if (window.crossesMidnight && hhmm < window.end) return date.minusDays(1)
        }
        return null
    }

    companion object {
        private val separators = Regex("[,，、;；\\n]")
        private val timeRange = Regex("""\s*([0-9]{1,2}):([0-5][0-9])\s*[-–~～]\s*([0-9]{1,2}):([0-5][0-9])\s*""")
        private fun formatTime(hhmm: Int) = "%02d:%02d".format(Locale.ROOT, hhmm / 100, hhmm % 100)

        fun parse(input: String): DomesticRefreshHours? {
            val windows = input.trim().replace('：', ':').split(separators).map { part ->
                val match = timeRange.matchEntire(part) ?: return null
                val (startHour, startMinute, endHour, endMinute) = match.destructured
                val start = startHour.toInt() * 100 + startMinute.toInt()
                val end = endHour.toInt() * 100 + endMinute.toInt()
                if (start >= 2400 || end > 2400 || start == end) return null
                Window(start, end)
            }
            // Split at midnight to catch overlaps on either side of an overnight window.
            val spans = windows.flatMap {
                if (it.crossesMidnight) listOf(it.start..<2400, 0..<it.end) else listOf(it.start..<it.end)
            }.filterNot { it.isEmpty() }.sortedBy { it.first }
            if (spans.zipWithNext().any { (previous, next) -> previous.last >= next.first }) return null
            return DomesticRefreshHours(windows)
        }
    }
}

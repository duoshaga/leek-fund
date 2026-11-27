package leek

import java.time.LocalDateTime
import kotlin.math.pow
import kotlin.math.roundToInt

/** A null right edge follows the latest bar. A dated edge stays put across rolling history updates. */
internal class ChartViewport {
    private var zoomCount: Int? = null
    private var rightEdge: LocalDateTime? = null
    private var wheelRemainder = 0.0

    fun reset() {
        zoomCount = null
        rightEdge = null
        wheelRemainder = 0.0
    }

    fun window(bars: List<ChartBar>, fit: Int): IntRange {
        if (bars.isEmpty()) return IntRange.EMPTY
        val count = (zoomCount ?: fit).coerceIn(1, bars.size)
        val end = indexAtOrBefore(bars, rightEdge).coerceAtLeast(count - 1)
        return end - count + 1..end
    }

    fun zoom(bars: List<ChartBar>, fit: Int, fraction: Double, turns: Double) {
        if (bars.isEmpty() || turns == 0.0) return
        val accumulated = (wheelRemainder + turns).coerceIn(-30.0, 30.0)
        val notches = accumulated.toInt()
        wheelRemainder = accumulated - notches
        if (notches == 0) return
        val old = window(bars, fit)
        val count = old.last - old.first + 1
        val next = (count * 1.25.pow(notches)).roundToInt().coerceIn(minOf(5, bars.size), bars.size)
        if (next == count) return
        val at = fraction.coerceIn(0.0, 1.0)
        val anchor = old.first + (at * count).toInt().coerceAtMost(count - 1)
        val start = anchor - (at * next).toInt().coerceAtMost(next - 1)
        zoomCount = next
        setEnd(bars, next, start + next - 1)
    }

    fun pan(bars: List<ChartBar>, fit: Int, rightAtPress: LocalDateTime, draggedBars: Int) {
        if (bars.isEmpty()) return
        wheelRemainder = 0.0
        val count = (zoomCount ?: fit).coerceIn(1, bars.size)
        setEnd(bars, count, indexAtOrBefore(bars, rightAtPress) - draggedBars)
    }

    private fun setEnd(bars: List<ChartBar>, count: Int, end: Int) {
        val index = end.coerceIn(count - 1, bars.lastIndex)
        rightEdge = if (index == bars.lastIndex) null else bars[index].time
    }

    private fun indexAtOrBefore(bars: List<ChartBar>, time: LocalDateTime?): Int {
        if (time == null) return bars.lastIndex
        val index = bars.binarySearchBy(time, selector = ChartBar::time)
        return if (index >= 0) index else (-index - 2).coerceAtLeast(0)
    }
}

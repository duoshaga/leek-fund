package leek

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ChartViewportTest {
    private val bars = (0..109).map { i ->
        ChartBar(LocalDateTime.of(2026, 1, 1, 9, 0).plusMinutes(i * 5L), 1.0, 2.0, 0.5, 1.5, 15.0)
    }

    @Test fun wheelKeepsThePointedCandleUnderThePointerAndZoomsInBothDirections() {
        val view = ChartViewport()
        val sample = bars.take(100)
        assertEquals(60..99, view.window(sample, 40))
        view.zoom(sample, 40, 0.5, -1.0)
        assertEquals(64..95, view.window(sample, 40))
        view.zoom(sample, 40, 0.5, 1.0)
        assertEquals(60..99, view.window(sample, 40))
        view.zoom(sample, 40, 0.99, 100.0)
        assertEquals(0..99, view.window(sample, 40))
        view.zoom(sample, 40, 0.0, -100.0)
        assertEquals(0..4, view.window(sample, 40))
    }

    @Test fun dragUsesThePressedPositionAndStopsAtBothEnds() {
        val view = ChartViewport()
        val sample = bars.take(100)
        val atPress = sample.last().time
        view.pan(sample, 25, atPress, 10)
        assertEquals(65..89, view.window(sample, 25))
        view.pan(sample, 25, atPress, 1000)
        assertEquals(0..24, view.window(sample, 25))
        view.pan(sample, 25, atPress, 1)
        assertEquals(74..98, view.window(sample, 25))
        view.pan(sample, 25, atPress, -100)
        assertEquals(75..99, view.window(sample, 25))
    }

    @Test fun highResolutionWheelEventsAccumulateToOneNotch() {
        val regular = ChartViewport()
        regular.zoom(bars, 30, 0.4, -1.0)
        val smooth = ChartViewport()
        repeat(32) { smooth.zoom(bars, 30, 0.4, -1.0 / 32) }
        assertEquals(regular.window(bars, 30), smooth.window(bars, 30))
    }

    @Test fun pannedHistoryStaysOnTheSameDatesWhenNewBarsArriveOrOldBarsExpire() {
        val view = ChartViewport()
        val old = bars.take(100)
        view.pan(old, 20, old.last().time, 25)
        val times = view.window(old, 20).map { old[it].time }
        val rolling = bars.drop(10)
        assertEquals(times, view.window(rolling, 20).map { rolling[it].time })
        view.reset()
        assertEquals(90..109, view.window(bars, 20))
    }

    @Test fun emptyAndShortHistoriesStayInBounds() {
        val view = ChartViewport()
        assertTrue(view.window(emptyList(), 50).isEmpty())
        view.zoom(emptyList(), 50, 0.5, -1.0)
        val single = bars.take(1)
        view.zoom(single, 50, 1.0, -3.0)
        view.pan(single, 50, single[0].time, 300)
        assertEquals(0..0, view.window(single, 50))
    }
}

package leek

import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Path2D
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JPanel
import kotlin.math.abs
import kotlin.math.roundToInt

internal class ChartPlot : JPanel() {
    private data class PlotArea(val left: Int, val right: Int, val top: Int, val bottom: Int,
                                val first: Int, val last: Int, val fit: Int) {
        val step = (right - left).toDouble() / (last - first + 1)
        fun x(index: Int) = (left + (index - first + 0.5) * step).roundToInt()
        fun index(x: Int, y: Int): Int? =
            if (x in left until right && y in top..bottom) first + ((x - left) / step).toInt() else null
    }
    private data class Drag(val x: Int, val rightAtPress: LocalDateTime, val step: Double)
    private data class PriceScale(val low: Double, val high: Double, val top: Int, val bottom: Int) {
        fun y(price: Double) = (bottom - (price - low) / (high - low) * (bottom - top)).roundToInt()
        fun price(y: Int) = low + (bottom - y).toDouble() / (bottom - top) * (high - low)
    }

    private val readout = ChartReadout()
    private val viewport = ChartViewport()
    private var drag: Drag? = null
    private var period = ChartPeriod.DAY
    private var series: ChartSeries? = null
    private var averages = MovingAverages.defaults
    private var lines = emptyMap<Int, DoubleArray>()
    private var area: PlotArea? = null
    private var hover: Point? = null
    private var quote: Quote? = null

    init {
        name = "leek.chartPlot"
        getAccessibleContext().accessibleName = "价格与成交量图"
        preferredSize = JBUI.size(260, 220)
        val pointer = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) { setHover(e.point.takeIf { area?.index(e.x, e.y) != null }) }
            override fun mouseExited(e: MouseEvent) { setHover(null) }
            override fun mouseWheelMoved(e: MouseWheelEvent) {
                val a = area ?: return
                if (drag != null || a.index(e.x, e.y) == null) return
                viewport.zoom(series!!.bars, a.fit, (e.x - a.left).toDouble() / (a.right - a.left), e.preciseWheelRotation)
                hover = e.point
                e.consume()
                repaint()
            }
            override fun mousePressed(e: MouseEvent) {
                val a = area ?: return
                if (e.button != MouseEvent.BUTTON1 || a.index(e.x, e.y) == null) return
                drag = Drag(e.x, series!!.bars[a.last].time, a.step)
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                setHover(null)
            }
            override fun mouseDragged(e: MouseEvent) {
                val start = drag ?: return
                if (e.modifiersEx and MouseEvent.BUTTON1_DOWN_MASK == 0) { endDrag(); return }
                val a = area ?: return
                viewport.pan(series!!.bars, a.fit, start.rightAtPress, ((e.x - start.x) / start.step).roundToInt())
                repaint()
            }
            override fun mouseReleased(e: MouseEvent) { if (e.button == MouseEvent.BUTTON1) endDrag() }
        }
        addMouseListener(pointer)
        addMouseMotionListener(pointer)
        addMouseWheelListener(pointer)
    }

    private fun endDrag() {
        drag = null
        cursor = null
    }

    fun showSeries(value: ChartSeries?, period: ChartPeriod, resetView: Boolean = false) {
        if (!resetView && series == value && this.period == period) return
        if (resetView || value == null) {
            viewport.reset()
            endDrag()
            hover = null
        }
        series = value
        this.period = period
        // Mouse handlers must never index a replacement series using the previous frame's window.
        area = null
        updateLines()
    }

    fun setQuote(value: Quote?) {
        if (quote == value) return
        quote = value
        repaint()
    }

    fun setAverages(periods: List<Int>) {
        averages = periods
        updateLines()
    }

    private fun updateLines() {
        val bars = series?.bars.orEmpty()
        lines = MovingAverages.lines(bars, averages)
        readout.setScalePrecision(bars)
        getAccessibleContext().accessibleDescription = if (bars.isEmpty()) null else
            "${period.label} · ${bars.size} 根 · " + details(bars.lastIndex).joinToString("  ")
        repaint()
    }

    private fun setHover(point: Point?) {
        if (hover != point) { hover = point; repaint() }
    }

    private fun details(index: Int): List<String> {
        val data = series ?: return emptyList()
        val bar = data.bars.getOrNull(index) ?: return emptyList()
        return buildList {
            add(if (period.intraday) bar.time.format(FULL_MINUTE) else stamp(bar))
            for (field in readout.fields(data, index, period)) add("${field.label} ${field.value}")
            if (bar.inconsistentRange) add("源开高低收不一致，菱形标记收盘价")
            for ((n, values) in lines) add("MA$n  ${format(values[index])}")
        }
    }

    private fun format(value: Double?) = readout.price(value)
    private fun stamp(bar: ChartBar) = if (period.intraday) bar.time.format(MINUTE) else bar.time.toLocalDate().toString()
    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        area = null
        val data = series?.takeIf { it.bars.isNotEmpty() } ?: return
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = JBUI.Fonts.smallFont()
            drawChart(g, data)
        } finally { g.dispose() }
    }

    private fun drawChart(g: Graphics2D, data: ChartSeries) {
        val fm = g.fontMetrics
        val pad = JBUI.scale(6)
        val left = pad
        val right = width - minOf(JBUI.scale(65), width / 3)
        if (right <= left) return
        val bars = data.bars
        val fit = ((right - left) / JBUI.scale(5)).coerceIn(1, 100)
        val range = viewport.window(bars, fit)
        val first = range.first
        val last = range.last
        val visible = bars.subList(first, last + 1)
        val pointer = hover
        val legendIndex = pointer?.takeIf { it.x in left until right }?.let {
            first + ((it.x - left).toLong() * visible.size / (right - left)).toInt()
        } ?: bars.lastIndex
        val top = legend(g, data, pad, legendIndex, visible.any(ChartBar::inconsistentRange)) + JBUI.scale(4)
        val bottom = height - fm.height - JBUI.scale(3)
        if (bottom - top < fm.height * 3) return
        val a = PlotArea(left, right, top, bottom, first, last, fit)
        area = a
        var low = visible.minOf { if (it.inconsistentRange) it.close else it.low ?: it.close }
        var high = visible.maxOf { if (it.inconsistentRange) it.close else it.high ?: it.close }
        for (values in lines.values) for (i in range) {
            if (values[i].isFinite()) { low = minOf(low, values[i]); high = maxOf(high, values[i]) }
        }
        val market = data.kind == SeriesKind.CANDLES
        val spot = quote?.takeIf { market && it.kind == QuoteKind.MARKET && it.price != null &&
            it.sourceTime.take(10).replace('/', '-') >= bars.last().time.toLocalDate().toString() }
        val latest = spot?.price?.toDouble()?.takeIf(Double::isFinite) ?: bars.last().close
        val latestLabel = when {
            !market -> data.kind.label
            spot == null -> "最新收盘"
            spot.bidFallback -> "买一"
            else -> "最新价"
        }
        if (last == bars.lastIndex) { low = minOf(low, latest); high = maxOf(high, latest) }
        val margin = if (high == low) maxOf(abs(high) * 0.01, 0.0001) else (high - low) * 0.04
        low -= margin
        high += margin
        val priceBottom = if (market) top + ((bottom - top) * 0.67).roundToInt() else bottom - fm.height
        val scale = PriceScale(low, high, top, priceBottom)
        fun y(price: Double) = scale.y(price)
        val intervals = ((priceBottom - top) / (fm.height * 3)).coerceIn(2, 6)
        for (tick in 0..intervals) {
            val level = high - (high - low) * tick / intervals
            val y = y(level)
            g.color = GRID
            g.drawLine(left, y, right, y)
            g.color = foreground
            g.drawString(readout.axis(level), right + JBUI.scale(3), (y + fm.ascent / 2).coerceIn(top + fm.ascent, priceBottom))
        }

        val body = (a.step * 0.67).roundToInt().coerceIn(1, JBUI.scale(48))
        if (market) {
            for (i in range) {
                val bar = bars[i]
                val x = a.x(i)
                val closeY = y(bar.close)
                g.color = candleColor(bar)
                if (bar.inconsistentRange) {
                    val r = JBUI.scale(3)
                    g.drawPolygon(intArrayOf(x, x + r, x, x - r), intArrayOf(closeY - r, closeY, closeY + r, closeY), 4)
                    continue
                }
                val openY = y(bar.open!!)
                g.drawLine(x, y(bar.high!!), x, y(bar.low!!))
                g.fillRect(x - body / 2, minOf(openY, closeY), body, maxOf(1, abs(closeY - openY)))
            }
            drawVolume(g, data, a, priceBottom, body)
        } else {
            drawLine(g, a, last, NAV_COLOR) { y(bars[it].close).toDouble() }
            g.color = foreground
            g.drawString("场外基金无场内成交量", left, bottom)
        }
        for ((colorIndex, values) in lines.values.withIndex()) {
            drawLine(g, a, last, MA_COLORS[colorIndex]) { i ->
                if (values[i].isFinite()) y(values[i]).toDouble() else Double.NaN
            }
        }
        drawInspection(g, data, a, scale, latest, latestLabel)
    }

    private fun drawInspection(g: Graphics2D, data: ChartSeries, a: PlotArea, scale: PriceScale, latest: Double, latestLabel: String) {
        val bars = data.bars
        val pointer = hover
        val index = pointer?.let { a.index(it.x, it.y) }
        val onPrice = index != null && pointer.y in a.top..scale.bottom
        val latestY = scale.y(latest.coerceIn(scale.low, scale.high))
        val arrow = when { latest > scale.high -> "↑"; latest < scale.low -> "↓"; else -> "" }
        val tagHeight = g.fontMetrics.height + JBUI.scale(2)
        val cursorTagY = if (onPrice) tagCenter(pointer.y, tagHeight) else null
        var latestTagY = tagCenter(latestY, tagHeight)
        if (cursorTagY != null && abs(latestTagY - cursorTagY) < tagHeight) {
            latestTagY = if (cursorTagY + tagHeight <= scale.bottom && (latestTagY >= cursorTagY || cursorTagY - tagHeight < a.top))
                cursorTagY + tagHeight else cursorTagY - tagHeight
        }
        g.color = LAST_PRICE
        g.drawLine(a.right - JBUI.scale(5), latestY, a.right, latestTagY)
        axisTag(g, a.right, latestTagY, arrow + format(latest), LAST_PRICE)
        drawTimeAxis(g, a, bars, index)
        val active = index ?: bars.lastIndex
        var cursorField: ChartField? = null
        if (index != null) {
            val volumeTop = scale.bottom + g.fontMetrics.height + JBUI.scale(2)
            cursorField = when {
                onPrice -> ChartField("指针价", readout.axis(scale.price(pointer.y)))
                data.kind == SeriesKind.CANDLES && pointer.y in volumeTop..a.bottom && a.bottom > volumeTop ->
                    bars.subList(a.first, a.last + 1).mapNotNull { it.volume }.maxOrNull()?.let { max ->
                        ChartField("指针量", format(Math.rint((a.bottom - pointer.y).toDouble() / (a.bottom - volumeTop) * max)))
                    }
                else -> null
            }
            drawCrosshair(g, a, pointer, index, cursorField)
            val fields = readout.fields(data, index, period)
            val date = if (period.intraday) bars[index].time.format(FULL_MINUTE) else stamp(bars[index])
            drawCard(g, a, index, date, listOfNotNull(cursorField) + fields)
        }
        val description = "${period.label} · ${bars.size} 根 · " + details(active).joinToString("  ") +
            "  $latestLabel ${format(latest)}" + (cursorField?.let { "  ${it.label} ${it.value}" } ?: "")
        if (accessibleContext.accessibleDescription != description) accessibleContext.accessibleDescription = description
    }

    private fun legend(g: Graphics2D, data: ChartSeries, pad: Int, selected: Int, hasRangeIssue: Boolean): Int {
        val fm = g.fontMetrics
        var x = pad
        var y = fm.ascent + JBUI.scale(3)
        fun item(text: String, color: Color) {
            val w = fm.stringWidth(text)
            if (x > pad && x + w > width - pad) { x = pad; y += fm.height }
            g.color = color
            g.drawString(text, x, y)
            x += w + JBUI.scale(9)
        }
        if (data.kind != SeriesKind.CANDLES) item(data.kind.label, NAV_COLOR)
        for ((index, period) in averages.withIndex()) {
            val value = lines.getValue(period)[selected]
            item("MA$period: ${readout.average(value)}", if (value.isFinite()) MA_COLORS[index] else JBColor.GRAY)
        }
        if (hasRangeIssue) item("◇ 收盘（源价异常）", JBColor.GRAY)
        return if (x == pad) pad else y + fm.descent
    }

    private fun tagCenter(y: Int, tagHeight: Int) =
        y.coerceIn(tagHeight / 2, height - (tagHeight + 1) / 2)

    private fun axisTag(g: Graphics2D, left: Int, center: Int, text: String, color: Color) {
        val fm = g.fontMetrics
        val h = fm.height + JBUI.scale(2)
        val top = tagCenter(center, h) - h / 2
        g.color = color
        g.fillRect(left, top, width - left, h)
        g.color = Color.WHITE
        g.drawString(text, left + maxOf(1, (width - left - fm.stringWidth(text)) / 2), top + (h - fm.height) / 2 + fm.ascent)
    }

    private fun drawCrosshair(g: Graphics2D, a: PlotArea, point: Point, index: Int, field: ChartField?) {
        val x = a.x(index)
        val stroke = g.stroke
        val dash = JBUIScale.scale(3f)
        g.stroke = BasicStroke(JBUIScale.scale(1f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(dash, dash), 0f)
        g.color = CROSSHAIR
        g.drawLine(x, a.top, x, a.bottom)
        if (field != null) g.drawLine(a.left, point.y, a.right, point.y)
        g.stroke = stroke
        if (field != null) axisTag(g, a.right, point.y, field.value, CURSOR_TAG)
    }

    private fun drawTimeAxis(g: Graphics2D, a: PlotArea, bars: List<ChartBar>, index: Int?) {
        val fm = g.fontMetrics
        val start = stamp(bars[a.first])
        val end = stamp(bars[a.last])
        val time = index?.let { stamp(bars[it]) }.orEmpty()
        val w = minOf(fm.stringWidth(time) + JBUI.scale(8), a.right - a.left)
        val left = index?.let { (a.x(it) - w / 2).coerceIn(a.left, a.right - w) }
        val endX = a.right - fm.stringWidth(end)
        val startEnd = a.left + fm.stringWidth(start) + JBUI.scale(3)
        g.color = foreground
        if (left == null || left + w < endX) g.drawString(end, endX, height - fm.descent)
        if (a.first != a.last && startEnd < endX && (left == null || startEnd < left)) g.drawString(start, a.left, height - fm.descent)
        if (left == null) return
        g.color = CURSOR_TAG
        g.fillRect(left, height - fm.height, w, fm.height)
        g.color = Color.WHITE
        g.drawString(time, left + JBUI.scale(4), height - fm.descent)
    }

    private fun drawCard(g: Graphics2D, a: PlotArea, index: Int, date: String, rows: List<ChartField>) {
        val fm = g.fontMetrics
        val pad = JBUI.scale(5)
        val x = a.x(index)
        val dockLeft = x >= (a.left + a.right) / 2
        val available = (if (dockLeft) x - a.left else a.right - x) - pad
        val line = fm.height
        val capacity = ((a.bottom - a.top - 2 * pad) / line - 1).coerceAtLeast(1)
        val labelWidth = rows.maxOf { fm.stringWidth(it.label) }
        val valueWidth = rows.maxOf { fm.stringWidth(it.value) }
        val columnWidth = labelWidth + pad * 2 + valueWidth
        val columns = if (rows.size > capacity && available >= columnWidth * 2 + pad * 3) 2 else 1
        val w = minOf(available, maxOf(fm.stringWidth(date), columns * columnWidth + (columns - 1) * pad) + 2 * pad)
        if (w <= pad * 2) return
        val count = minOf(capacity, (rows.size + columns - 1) / columns)
        val h = (count + 1) * line + 2 * pad
        val left = if (dockLeft) a.left else a.right - w
        val top = a.top
        val card = g.create(left, top, w, h) as Graphics2D
        try {
            card.color = CARD
            card.fillRect(0, 0, w, h)
            card.color = GRID
            card.drawRect(0, 0, w - 1, h - 1)
            card.color = foreground
            card.drawString(date, pad, pad + fm.ascent)
            val cell = (w - 2 * pad - (columns - 1) * pad) / columns
            for ((i, field) in rows.take(count * columns).withIndex()) {
                val start = pad + (i / count) * (cell + pad)
                val baseline = pad + (i % count + 1) * line + fm.ascent
                val valueX = start + cell - fm.stringWidth(field.value)
                val labels = card.create() as Graphics2D
                try {
                    labels.clipRect(start, baseline - fm.ascent, maxOf(0, valueX - start - 2), line)
                    labels.color = foreground
                    labels.drawString(field.label, start, baseline)
                } finally { labels.dispose() }
                card.color = when {
                    field.direction > 0 -> UP
                    field.direction < 0 -> DOWN
                    else -> foreground
                }
                card.drawString(field.value, maxOf(start, valueX), baseline)
            }
        } finally { card.dispose() }
    }

    private fun drawVolume(g: Graphics2D, data: ChartSeries, a: PlotArea, priceBottom: Int, body: Int) {
        val fm = g.fontMetrics
        val labelY = priceBottom + fm.height
        val top = labelY + JBUI.scale(2)
        val max = data.bars.subList(a.first, a.last + 1).maxOf { it.volume ?: -1.0 }
        g.color = foreground
        val label = when {
            max < 0 -> "行情源暂无成交量"
            data.volumeUnit.isEmpty() -> "源成交量"
            else -> "成交量（${data.volumeUnit}）"
        }
        g.drawString(label, a.left, labelY)
        g.color = GRID
        g.drawLine(a.left, a.bottom, a.right, a.bottom)
        if (max < 0 || a.bottom <= top) return
        val upper = when {
            max >= 1e8 -> format(max / 1e8) + "亿"
            max >= 1e4 -> format(max / 1e4) + "万"
            else -> format(max)
        }
        g.color = foreground
        g.drawString(upper, a.right + JBUI.scale(3), minOf(a.bottom, top + fm.ascent))
        if (max == 0.0) return
        for (i in a.first..a.last) {
            val bar = data.bars[i]
            val volume = bar.volume?.takeIf { it > 0 } ?: continue
            val h = ((volume / max) * (a.bottom - top)).roundToInt().coerceAtLeast(1)
            g.color = candleColor(bar)
            g.fillRect(a.x(i) - body / 2, a.bottom - h, body, h)
        }
    }

    private fun drawLine(g: Graphics2D, a: PlotArea, last: Int, color: Color, y: (Int) -> Double) {
        val path = Path2D.Double()
        var points = 0
        var lastY = 0.0
        for (i in a.first..last) {
            val py = y(i)
            if (!py.isFinite()) continue
            val px = a.x(i).toDouble()
            if (points == 0) path.moveTo(px, py) else path.lineTo(px, py)
            points++
            lastY = py
        }
        g.color = color
        g.stroke = BasicStroke(JBUIScale.scale(1.25f))
        g.draw(path)
        if (points == 1) g.fillOval(a.x(last) - 1, lastY.roundToInt() - 1, 3, 3)
    }

    private fun candleColor(bar: ChartBar) = when {
        bar.inconsistentRange -> JBColor.GRAY
        bar.close > bar.open!! -> UP
        bar.close < bar.open -> DOWN
        else -> JBColor.GRAY
    }

    private companion object {
        val MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        val FULL_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val GRID = JBColor(0xD8DCE0, 0x494D52)
        val CARD = JBColor(0xF5F7FA, 0x272A30)
        val CROSSHAIR = JBColor(0x586577, 0xB3C1D1)
        val CURSOR_TAG = JBColor(0x335F88, 0x345E87)
        val LAST_PRICE = JBColor(0x007970, 0x007970)
        val UP = JBColor(0xBD4138, 0xEF7973)
        val DOWN = JBColor(0x24754E, 0x68BB8E)
        val NAV_COLOR = JBColor(0x176481, 0x9BD6EB)
        val MA_COLORS = listOf(JBColor(0x876412, 0xE8C66B), JBColor(0x8552A2, 0xC79BE8),
            JBColor(0x286EB5, 0x76B3EF), JBColor(0xAC5528, 0xF7A16C), JBColor(0x137775, 0x65CBBD),
            JBColor(0xA64576, 0xEC93BB), JBColor(0x626C2C, 0xB5C97A), JBColor(0x5E598F, 0xA9A7EC))
    }
}

package leek

import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Job
import java.awt.BorderLayout
import java.awt.Font
import java.awt.event.HierarchyEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.Timer

/** All registrations and ticks run on the EDT. Several Rider windows can show the same symbol. */
internal class ChartPolling(private val refreshQuotes: (Collection<Symbol>) -> Unit) {
    private val visible = mutableMapOf<() -> Unit, Symbol>()
    private val timer = Timer(2000) {
        refreshQuotes(visible.values.toSet())
        visible.keys.toList().forEach { refreshHistory -> refreshHistory() }
    }

    fun select(refreshHistory: () -> Unit, symbol: Symbol?) {
        if (visible[refreshHistory] == symbol) return
        visible.remove(refreshHistory)
        if (symbol != null) {
            val firstChart = symbol !in visible.values
            visible[refreshHistory] = symbol
            if (firstChart) refreshQuotes(listOf(symbol))
            if (visible.size == 1) timer.restart() else timer.start()
        } else if (visible.isEmpty()) timer.stop()
    }
}

internal class ChartPanel(private val service: LeekService) : JPanel(BorderLayout()), Disposable {
    private data class Key(val symbol: Symbol, val period: ChartPeriod)

    private val title = JBLabel().apply { name = "leek.chartTitle"; font = font.deriveFont(Font.BOLD) }
    private val interval = ComboBox<ChartPeriod>().apply {
        name = "leek.chartPeriod"
        accessibleContext.accessibleName = "K 线周期"
        toolTipText = "切换周期"
    }
    private val plot = ChartPlot()
    private val status = JBLabel().apply { name = "leek.chartStatus"; border = JBUI.Borders.empty(1, 6) }
    private var currentRow: WatchRow? = null
    private var key: Key? = null
    private var marketPeriod = ChartPeriod.DAY
    private var fundPeriod = ChartPeriod.DAY
    private var choices = emptyList<ChartPeriod>()
    private var updatingInterval = false
    private var maPeriods = MovingAverages.defaults
    private var requestId = 0L
    private var pending: Job? = null
    private var disposed = false
    private val refreshHistory = { request() }
    private val cache = object : LinkedHashMap<Key, ChartSeries>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ChartSeries>?) = size > 12
    }

    init {
        name = "leek.chart"
        isVisible = false
        minimumSize = JBUI.size(110, 115)
        val close = JButton("关闭 K 线").apply {
            name = "leek.chartClose"
            addActionListener { select(null, maPeriods) }
        }
        val ma = JButton("MA…").apply {
            name = "leek.chartMA"
            toolTipText = "设置均线数量和周期"
            accessibleContext.accessibleName = toolTipText
            addActionListener { editAverages() }
        }
        val controls = JPanel(HorizontalLayout(3)).apply { add(close); add(interval); add(ma) }
        val header = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(4, 6, 1, 4)
            add(title, BorderLayout.CENTER)
            add(controls, BorderLayout.EAST)
        }
        val top = JPanel(BorderLayout()).apply { add(header, BorderLayout.NORTH); add(status, BorderLayout.SOUTH) }
        add(top, BorderLayout.NORTH)
        add(plot, BorderLayout.CENTER)
        interval.addActionListener {
            if (!updatingInterval) {
                val row = currentRow ?: return@addActionListener
                val period = interval.selectedItem as? ChartPeriod ?: return@addActionListener
                if (row.symbol.disclosureOnly) fundPeriod = period else marketPeriod = period
                select(row, maPeriods)
            }
        }
        addHierarchyListener {
            if (!disposed && (it.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L) {
                syncPolling()
                if (isShowing) request() else cancelRequest()
            }
        }
    }

    fun select(row: WatchRow?, averages: List<Int>) {
        currentRow = row
        if (maPeriods != averages) {
            maPeriods = averages
            plot.setAverages(averages)
        }
        if (row == null) {
            if (key != null) {
                cancelRequest()
                key = null
                plot.showSeries(null, ChartPeriod.DAY)
            }
            isVisible = false
            syncPolling()
            return
        }
        val code = row.symbol.code
        title.text = QuoteText.name(row) ?: code
        title.toolTipText = listOfNotNull(QuoteText.name(row), code, "滚轮缩放，按住鼠标左键左右拖动").joinToString("  ")
        val available = ChartPeriod.forSymbol(row.symbol)
        val period = if (row.symbol.disclosureOnly) fundPeriod else marketPeriod
        if (choices != available) {
            updatingInterval = true
            choices = available
            interval.model = DefaultComboBoxModel(choices.toTypedArray())
            interval.selectedItem = period
            updatingInterval = false
        }
        val next = Key(row.symbol, period)
        val changed = key != next
        if (changed) {
            cancelRequest()
            key = next
            present(cache[next], period, resetView = true)
        }
        plot.setQuote(row.quote)
        isVisible = true
        syncPolling()
        if (changed) request()
    }

    private fun syncPolling() {
        service.chartPolling.select(refreshHistory, key?.symbol?.takeIf { isShowing && !disposed })
    }

    fun refresh() = request(force = true)

    private fun request(force: Boolean = false) {
        val selected = key ?: return
        if (disposed || !isShowing || pending != null) return
        if (force || status.isVisible) {
            status.text = if (cache[selected]?.bars?.isNotEmpty() == true) "正在更新…" else "正在加载…"
            status.isVisible = true
        }
        val id = ++requestId
        pending = service.loadChart(selected.symbol, selected.period) { result ->
            if (disposed || id != requestId) return@loadChart
            pending = null
            result.fold(onSuccess = { series ->
                cache[selected] = series
                present(series, selected.period)
            }, onFailure = {
                status.text = if (cache[selected]?.bars?.isNotEmpty() == true) "更新失败，保留上次图表" else "图表加载失败，将自动重试"
                status.isVisible = true
            })
        }
    }

    private fun present(series: ChartSeries?, period: ChartPeriod, resetView: Boolean = false) {
        plot.showSeries(series, period, resetView)
        status.text = when {
            series == null -> "正在加载…"
            series.bars.isEmpty() -> "该周期暂无历史数据"
            else -> ""
        }
        status.isVisible = status.text.isNotEmpty()
    }

    private fun editAverages() {
        val validator = object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? =
                if (MovingAverages.parse(inputString) == null) "请输入 1–250 的不同整数，最多 8 条" else null
        }
        val value = Messages.showInputDialog(this, "输入每条 MA 的周期，用逗号分隔。留空可隐藏全部均线。",
            "均线设置", null, maPeriods.joinToString(", "), validator) ?: return
        service.setMaPeriods(value)
    }

    private fun cancelRequest() {
        ++requestId
        if (pending != null) {
            pending?.cancel()
            pending = null
        }
    }

    override fun dispose() {
        disposed = true
        syncPolling()
        cancelRequest()
        cache.clear()
    }
}

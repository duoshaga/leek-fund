package leek

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

internal data class ChartField(val label: String, val value: String, val direction: Int = 0)

internal class ChartReadout {
    private val symbols = DecimalFormatSymbols(Locale.ROOT)
    private val numbers = DecimalFormat("0.####", symbols)
    private val averages = DecimalFormat("0.00##", symbols)
    private val percentages = DecimalFormat("0.00", symbols)
    private val axisNumbers = DecimalFormat("0.####", symbols)

    fun price(value: Double?): String = formatted(value, numbers)
    fun average(value: Double?): String = formatted(value, averages)
    fun axis(value: Double): String = axisNumbers.format(value)

    fun setScalePrecision(bars: List<ChartBar>) {
        axisNumbers.maximumFractionDigits = bars.maxOfOrNull { bar ->
            listOfNotNull(bar.open, bar.high, bar.low, bar.close).maxOf {
                BigDecimal.valueOf(it).stripTrailingZeros().scale().coerceIn(0, 4)
            }
        } ?: 4
        averages.maximumFractionDigits = maxOf(2, axisNumbers.maximumFractionDigits)
    }

    private fun formatted(value: Double?, format: DecimalFormat) =
        if (value == null || !value.isFinite()) "—" else format.format(value)

    private fun signed(value: Double?, percent: Boolean = false): String {
        if (value == null || !value.isFinite()) return "—"
        return (if (value > 0) "+" else "") + (if (percent) percentages.format(value) + "%" else price(value))
    }

    fun fields(data: ChartSeries, index: Int, period: ChartPeriod): List<ChartField> {
        val bar = data.bars[index]
        if (data.kind != SeriesKind.CANDLES) return listOf(ChartField(data.kind.label, price(bar.close)))
        val previous = data.bars.getOrNull(index - 1)
        val change = previous?.let { bar.close - it.close }
        val gain = rate(change, previous?.close)
        val spread = if (bar.inconsistentRange || bar.high == null || bar.low == null) null else bar.high - bar.low
        return buildList {
            fun quote(label: String, value: Double?) {
                add(ChartField(label, price(value), if (value != null && previous != null) value.compareTo(previous.close) else 0))
            }
            quote("开盘", bar.open)
            quote("最高", bar.high)
            quote("最低", bar.low)
            quote("收盘", bar.close)
            add(ChartField("成交量", bar.volume?.let { price(it) + data.volumeUnit } ?: "—"))
            add(ChartField("涨跌", signed(change), change?.compareTo(0.0) ?: 0))
            add(ChartField("涨幅", signed(gain, true), gain?.compareTo(0.0) ?: 0))
            add(ChartField("振幅", rate(spread, previous?.close)?.let { percentages.format(it) + "%" } ?: "—"))
            if (bar.openInterest != null || bar.settlement != null) {
                quote("结算", bar.settlement)
                add(ChartField("持仓", price(bar.openInterest)))
                val delta = previous?.openInterest?.let { earlier -> bar.openInterest?.minus(earlier) }
                val label = when (period) { ChartPeriod.DAY -> "日增仓"; ChartPeriod.WEEK -> "周增仓"; else -> "较前柱增仓" }
                add(ChartField(label, signed(delta), delta?.compareTo(0.0) ?: 0))
                add(ChartField("增仓幅", signed(rate(delta, previous?.openInterest), true)))
            }
            add(ChartField("成交额", price(bar.turnover)))
        }
    }

    private fun rate(delta: Double?, base: Double?): Double? =
        if (delta != null && base != null && base != 0.0) 100.0 * delta / kotlin.math.abs(base) else null
}

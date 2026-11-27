package leek

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

internal object QuoteText {
    private const val DASH = "—"

    fun percentage(number: BigDecimal?): String {
        val value = number?.setScale(2, RoundingMode.HALF_UP) ?: return DASH
        return (if (value.signum() > 0) "+" else "") + value.toPlainString() + "%"
    }

    private fun short(value: BigDecimal?) = value?.stripTrailingZeros()?.toPlainString() ?: DASH

    private fun quantity(value: BigDecimal?): String {
        if (value == null) return DASH
        val places = when {
            value.abs() >= BigDecimal("100000000") -> 8
            value.abs() >= BigDecimal("10000") -> 4
            else -> return short(value)
        }
        return short(value.movePointLeft(places).setScale(2, RoundingMode.HALF_UP)) + if (places == 8) "亿" else "万"
    }

    fun price(row: WatchRow): String {
        val quote = row.quote
        val price = if (row.symbol.group == Group.FUND) quote?.price?.toPlainString() ?: DASH else short(quote?.price)
        val kind = when {
            row.symbol.group == Group.FUND -> "净值 "
            quote?.bidFallback == true -> "买一 "
            else -> ""
        }
        return kind + price
    }

    fun market(row: WatchRow) = price(row) + "   " + percentage(row.quote?.percent)

    fun timestamp(row: WatchRow): String {
        val label = if (row.symbol.group == Group.FUND) "净值日期" else "报价时间"
        return "$label  " + row.quote?.sourceTime.orEmpty().ifBlank { DASH }
    }

    fun availability(row: WatchRow): String? = when (row.state) {
        QuoteState.NEW -> "等待报价"
        QuoteState.CACHED -> "上次报价"
        QuoteState.FAILED -> if (row.quote == null) "更新失败" else "更新失败 · 保留上次报价"
        QuoteState.EMPTY -> "暂无报价"
        QuoteState.CURRENT -> null
    }

    fun status(row: WatchRow?) = if (row == null) "韭菜盒子" else buildString {
        append(row.symbol.code).append("   ").append(market(row))
        availability(row)?.let { append("  ·  ").append(it) }
    }

    fun refreshNote(row: WatchRow, compact: Boolean): String? {
        row.refreshHours?.let { return if (compact) "自定义时段" else "自定义刷新时段（北京时间）  ${it.text}" }
        if (row.symbol.group == Group.DOMESTIC && row.marketHours == null && MarketSessions.builtInHours(row.symbol) == null) {
            if (row.hoursLookupFailed) return if (compact) "时段待重试" else "暂未查到交易时段，插件会自动重试。也可点击时钟手填时段。"
            return if (compact) "查询时段中" else "正在查询该品种的交易时段。"
        }
        val reason = MarketSessions.manualOnlyReason(row.symbol, Instant.now(), row.marketHours)
        if (reason != null) return if (compact) "仅手动刷新" else reason
        return if (compact) null else row.marketHours?.let { "自动刷新时段（北京时间，东方财富期货）  ${it.text}" }
    }

    fun detail(row: WatchRow): String = buildList {
        val name = row.quote?.name?.takeUnless { it.isBlank() || it == row.symbol.code } ?: row.displayName
        add(listOfNotNull(name, row.symbol.code).joinToString("  "))
        add(if (row.symbol.group == Group.FUND) "已公布单位净值  " + market(row).removePrefix("净值 ") else market(row))
        add(timestamp(row))
        if (row.symbol.code == "hf_OIL" || row.symbol.code == "hf_CHA50CFD") add("行情源  新浪 CFD 指示报价")
        row.quote?.let {
            if (it.price != null && it.basis != null) {
                val change = it.price - it.basis
                add("涨跌  " + (if (change.signum() > 0) "+" else "") + short(change))
            }
            it.day?.let { day ->
                add("最高  " + short(day.high) + "     最低  " + short(day.low))
                add("今开  " + short(day.open))
                val unit = when (row.symbol.group) { Group.STOCK -> "股"; Group.DOMESTIC -> "手"; else -> "" }
                val amountUnit = if (row.symbol.code.startsWith("hk")) "（报价币种）" else "元"
                val volumeLabel = if (row.symbol.group == Group.OVERSEAS) "源成交量  " else "成交量  "
                add(volumeLabel + quantity(day.volume) + (if (day.volume != null) unit else "") +
                    "     成交额  " + quantity(day.turnover) + (if (day.turnover != null) amountUnit else ""))
            }
            if (it.basisLabel.isNotBlank()) add("涨跌口径  " + it.basisLabel + (it.basis?.let { n -> "  " + n.toPlainString() } ?: ""))
            if (it.bidFallback) add("源成交价  " + (it.rawTrade?.toPlainString() ?: DASH))
        }
        availability(row)?.let(::add)
        refreshNote(row, compact = false)?.let(::add)
    }.joinToString("\n")

    fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    fun tooltip(row: WatchRow) = "<html>" + html(detail(row)).replace("\n", "<br>") + "</html>"
}

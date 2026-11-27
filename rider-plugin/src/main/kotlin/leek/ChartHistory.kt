package leek

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

internal val Symbol.disclosureOnly: Boolean get() = group == Group.FUND && etfMarketCode == null

internal enum class ChartPeriod(val label: String, val scale: Int) {
    M5("5 分钟", 5), M15("15 分钟", 15), M30("30 分钟", 30), M60("60 分钟", 60),
    M120("120 分钟", 120), DAY("日 K", 101), WEEK("周 K", 102);

    val intraday get() = this != DAY && this != WEEK
    override fun toString() = label

    companion object {
        fun forSymbol(symbol: Symbol): List<ChartPeriod> =
            if (symbol.disclosureOnly) listOf(DAY, WEEK) else entries
    }
}

internal enum class SeriesKind(val label: String) { CANDLES("K 线"), NAV("单位净值"), INCOME("每万份收益") }

internal data class ChartBar(val time: LocalDateTime, val open: Double?, val high: Double?, val low: Double?,
                             val close: Double, val volume: Double?, val turnover: Double? = null,
                             val openInterest: Double? = null, val settlement: Double? = null) {
    val inconsistentRange: Boolean get() = open != null && low != null && high != null &&
        (low > minOf(open, close) || high < maxOf(open, close))
}
internal data class ChartSeries(val kind: SeriesKind, val bars: List<ChartBar>, val volumeUnit: String = "手")

internal object MovingAverages {
    val defaults = listOf(5, 10, 20, 60)
    const val MAX_LINES = 8
    const val MAX_PERIOD = 250

    fun parse(text: String): List<Int>? {
        if (text.isBlank()) return emptyList()
        val values = text.trim().split(Regex("[,，、]")).map {
            it.trim().toIntOrNull()?.takeIf { n -> n in 1..MAX_PERIOD } ?: return null
        }
        return values.takeIf { it.size <= MAX_LINES && it.distinct().size == it.size }
    }

    fun lines(bars: List<ChartBar>, periods: List<Int>): Map<Int, DoubleArray> = periods.associateWith { period ->
        var sum = 0.0
        DoubleArray(bars.size) { i ->
            sum += bars[i].close
            if (i >= period) sum -= bars[i - period].close
            if (i + 1 >= period) sum / period else Double.NaN
        }
    }
}

internal class HistorySource(private val request: (URI, String) -> String = { uri, referer ->
    // Some futures endpoints return the whole contract history even for a short chart.
    downloadQuote(uri, referer, Charsets.UTF_8, maximumBytes = 4 * 1024 * 1024)
}) {
    fun fetch(symbol: Symbol, period: ChartPeriod): ChartSeries {
        require(period in ChartPeriod.forSymbol(symbol))
        return when (symbol.group) {
            Group.STOCK -> exchange(symbol.code, period)
            Group.FUND -> symbol.etfMarketCode?.let { exchange(it, period) } ?: fund(symbol.code, period)
            Group.DOMESTIC, Group.OVERSEAS -> futures(symbol, period)
        }
    }

    private fun get(url: String, referer: String) = JsonParser.parseString(request(URI.create(url), referer))

    private fun exchange(code: String, period: ChartPeriod): ChartSeries {
        val market = when (code.take(2)) { "sh" -> 1; "hk" -> 116; else -> 0 }
        val number = code.drop(2)
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=$market.$number" +
            "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57" +
            "&klt=${period.scale}&fqt=0&end=20500101&lmt=$HISTORY_SIZE"
        val root = get(url, "https://quote.eastmoney.com/").asJsonObject
        val unit = if (market == 116) "股" else "手"
        val data = root.get("data")
        if (data?.isJsonNull == true && root.get("rc")?.asInt in listOf(0, 100)) {
            return ChartSeries(SeriesKind.CANDLES, emptyList(), unit)
        }
        require(root.get("rc")?.asInt == 0 && data?.asJsonObject?.text("code") == number)
        val rows = requireNotNull(data.asJsonObject.getAsJsonArray("klines"))
        val bars = normalize(rows) { row ->
            val f = row.asString.split(',')
            if (f.size < 5) null else candle(f[0], f[1], f[3], f[4], f[2], f.getOrNull(5))
                ?.copy(turnover = nonnegative(f.getOrNull(6)))
        }
        return ChartSeries(SeriesKind.CANDLES, bars.takeLast(HISTORY_SIZE), unit)
    }

    private fun futures(symbol: Symbol, period: ChartPeriod): ChartSeries {
        val code = symbol.code.substringAfter('_')
        val overseas = symbol.group == Group.OVERSEAS
        val service = when {
            overseas && period.intraday -> "https://gu.sina.cn/ft/api/json.php/GlobalService.getMink"
            overseas -> "${SINA_FUTURES}GlobalFuturesService.getGlobalFuturesDailyKLine"
            period.intraday -> "${SINA_FUTURES}InnerFuturesNewService.getFewMinLine"
            else -> "${SINA_FUTURES}InnerFuturesNewService.getDailyKLine"
        }
        val query = if (period.intraday) "symbol=$code&type=${period.scale}" else "symbol=$code"
        val root = get("$service?$query", "https://finance.sina.com.cn/")
        val rows = if (root.isJsonNull) JsonArray() else root.asJsonArray
        val longKeys = overseas && !period.intraday
        val bars = normalize(rows) { element ->
            val row = element.asJsonObject
            if (longKeys) candle(row.text("date"), row.text("open"), row.text("high"), row.text("low"), row.text("close"), row.text("volume"))
            else candle(row.text("d"), row.text("o"), row.text("h"), row.text("l"), row.text("c"), row.text("v"))
                ?.let { if (overseas) it else it.copy(openInterest = nonnegative(row.text("p")), settlement = number(row.text("s"))) }
        }
        return ChartSeries(SeriesKind.CANDLES, recent(bars, period), if (overseas) "" else "手")
    }

    private fun fund(code: String, period: ChartPeriod): ChartSeries {
        val referer = "https://fundf10.eastmoney.com/jjjz_$code.html"
        // The bulk history uses DWJZ for both unit NAV and income per 10,000 units.
        val metadata = get("https://api.fund.eastmoney.com/f10/lsjz?fundCode=$code&pageIndex=1&pageSize=1", referer).asJsonObject
        require(metadata.get("ErrCode")?.asInt == 0)
        val info = requireNotNull(metadata.getAsJsonObject("Data"))
        val kind = when (info.text("SYType")) {
            null, "" -> SeriesKind.NAV
            "每万份收益" -> SeriesKind.INCOME
            else -> error("Unsupported fund disclosure")
        }
        val count = if (period == ChartPeriod.WEEK) HISTORY_SIZE * 7 else HISTORY_SIZE
        val url = "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNHisNetList?FCODE=$code&pageIndex=1&pageSize=$count" +
            "&deviceid=leekbox&plat=Android&product=EFund&version=6.3.8"
        val body = get(url, "https://fund.eastmoney.com/").asJsonObject
        require(body.get("ErrCode")?.asInt == 0)
        val bars = normalize(body.getAsJsonArray("Datas")) { element ->
            val row = element.asJsonObject
            val time = timestamp(row.text("FSRQ")) ?: return@normalize null
            val value = number(row.text("DWJZ")) ?: return@normalize null
            ChartBar(time, null, null, null, value, null)
        }
        return ChartSeries(kind, recent(bars, period), "")
    }

    private fun candle(date: String?, open: String?, high: String?, low: String?, close: String?, volume: String?): ChartBar? {
        val time = timestamp(date) ?: return null
        val o = number(open) ?: return null
        val h = number(high) ?: return null
        val l = number(low) ?: return null
        val c = number(close) ?: return null
        return ChartBar(time, o, h, l, c, nonnegative(volume))
    }

    private fun timestamp(raw: String?): LocalDateTime? {
        val text = raw?.trim() ?: return null
        return try {
            if (text.length == 10) LocalDate.parse(text).atStartOfDay() else LocalDateTime.parse(text.replace(' ', 'T'))
        } catch (_: DateTimeParseException) { null }
    }

    private fun number(raw: String?) = raw?.toDoubleOrNull()?.takeIf(Double::isFinite)
    private fun nonnegative(raw: String?) = number(raw)?.takeIf { it >= 0 }
    private fun JsonObject.text(key: String) = get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString

    private fun normalize(rows: JsonArray, parse: (JsonElement) -> ChartBar?): List<ChartBar> {
        val byTime = sortedMapOf<LocalDateTime, ChartBar>()
        for (row in rows) parse(row)?.let { byTime[it.time] = it }
        require(rows.isEmpty || byTime.isNotEmpty()) { "No valid dated bars in response" }
        return byTime.values.toList()
    }

    private fun recent(bars: List<ChartBar>, period: ChartPeriod): List<ChartBar> {
        if (period != ChartPeriod.WEEK) return bars.takeLast(HISTORY_SIZE)
        // Daily futures bars already carry their exchange trading dates, including night sessions.
        val weeks = bars.groupBy { it.time.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        return weeks.values.toList().takeLast(HISTORY_SIZE).map { week ->
            val last = week.last()
            if (last.open == null) last else last.copy(open = week.first().open, high = week.maxOf { it.high!! },
                low = week.minOf { it.low!! }, volume = if (week.all { it.volume != null }) week.sumOf { it.volume!! } else null)
        }
    }

    private companion object {
        const val HISTORY_SIZE = 400
        const val SINA_FUTURES = "https://stock2.finance.sina.com.cn/futures/api/json.php/"
    }
}

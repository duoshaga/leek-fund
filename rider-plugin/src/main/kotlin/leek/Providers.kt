package leek

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MarketQuoteSource internal constructor(
    private val request: (URI, String, Charset) -> String,
) : QuoteSource {
    constructor() : this(::downloadQuote)

    private val fundNames = ConcurrentHashMap<String, String>()

    override fun fetch(symbols: List<Symbol>): Map<String, Result<Quote?>> {
        val (hongKong, sina) = symbols.distinctBy(Symbol::code).partition { it.code.startsWith("hk") }
        val batches =
            sina.chunked(50).map { batch -> { fromSina(batch) } } +
                hongKong.chunked(50).map { batch -> { fromTencent(batch) } }
        val quotes = fetchBatches(batches)
        val now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
        val today = now.toLocalDate().toString()
        val disclosures = sina.mapNotNull { symbol ->
            if (symbol.group != Group.FUND || symbol.etfMarketCode != null) return@mapNotNull null
            val result = quotes.getValue(symbol.code)
            // A transport or protocol failure must leave the last good quote eligible for retention.
            if (result.isFailure) return@mapNotNull null
            val estimate = result.getOrNull()
            val estimateDate = estimate?.sourceTime?.substringBefore(' ')
            if (estimateDate == today && now.hour < 18) return@mapNotNull null
            {
                val published = runCatching { fromEastmoney(symbol) }
                val latest = if (estimate != null && published.getOrNull()?.sourceTime.orEmpty() < estimateDate.orEmpty()) {
                    Result.success(estimate)
                } else published
                mapOf(symbol.code to latest)
            }
        }
        return quotes + fetchBatches(disclosures)
    }

    private fun fetchBatches(batches: List<() -> Map<String, Result<Quote?>>>): Map<String, Result<Quote?>> {
        if (batches.isEmpty()) return emptyMap()

        val executor = Executors.newFixedThreadPool(minOf(4, batches.size))
        try {
            val futures = batches.map { batch -> executor.submit<Map<String, Result<Quote?>>> { batch() } }
            return buildMap {
                futures.forEach { putAll(it.get()) }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun sinaCode(symbol: Symbol) = if (symbol.group == Group.FUND) {
        symbol.etfMarketCode ?: "fu_${symbol.code}"
    } else symbol.code

    private fun fromSina(symbols: List<Symbol>): Map<String, Result<Quote?>> = parseBatch(symbols, {
        val codes = symbols.joinToString(",", transform = ::sinaCode)
        val body = request(URI.create("https://hq.sinajs.cn/list=$codes"), "https://finance.sina.com.cn/", GB18030)
        val rows = SINA_ROW.findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        require(rows.isNotEmpty()) { "Unexpected Sina quote response" }
        rows
    }) { symbol, rows ->
        val row = requireNotNull(rows[sinaCode(symbol)]) { "Sina omitted ${sinaCode(symbol)}" }
        if (row.isBlank()) null else parseSinaQuote(symbol, row.split(','))
    }

    private fun parseSinaQuote(symbol: Symbol, fields: List<String>): Quote? = when (symbol.group) {
        Group.STOCK -> cashMarketQuote(symbol, fields)
        Group.FUND -> if (symbol.etfMarketCode != null) cashMarketQuote(symbol, fields) else fundEstimate(symbol, fields)
        Group.DOMESTIC -> {
            val product = symbol.code.substring(3).takeWhile(Char::isLetter)
            if (product in INDEX_AND_BOND_FUTURES) {
                require(fields.size >= 38) { "Incomplete Sina financial futures quote for ${symbol.code}" }
                val price = decimal(fields[3])
                marketQuote(symbol, fields.getOrNull(49).orEmpty(), price, sourceTime(fields[36], fields[37]), "昨收",
                    decimal(fields[13]), day = dayStats(fields[0], fields[1], fields[2], fields[4]))
            } else {
                require(fields.size >= 18) { "Incomplete Sina commodity futures quote for ${symbol.code}" }
                val time = fields[1].trim().replace(COMPACT_TIME, "$1:$2:$3")
                marketQuote(symbol, fields[0], decimal(fields[8]), sourceTime(fields[17], time), "昨结算价",
                    decimal(fields[10]), day = dayStats(fields[2], fields[3], fields[4], fields[14]))
            }
        }
        Group.OVERSEAS -> {
            require(fields.size >= 14) { "Incomplete Sina overseas futures quote for ${symbol.code}" }
            val trade = decimal(fields[0])
            val bid = decimal(fields[2])
            val ask = decimal(fields[3])
            val useBid = bid != null && (trade == null || (ask != null && bid <= ask && (trade < bid || trade > ask)))
            marketQuote(symbol, fields[13], if (useBid) bid else trade, sourceTime(fields[12], fields[6]), "昨结算价",
                decimal(fields[7]), trade, useBid, dayStats(fields[8], fields[4], fields[5], fields.getOrNull(14)))
        }
    }

    private fun cashMarketQuote(symbol: Symbol, fields: List<String>): Quote {
        require(fields.size >= 32) { "Incomplete Sina exchange quote for ${symbol.code}" }
        val trade = decimal(fields[3])
        val bid = decimal(fields[6])
        return marketQuote(symbol, fields[0], trade ?: bid, sourceTime(fields[30], fields[31]), "昨收",
            decimal(fields[2]), trade, trade == null && bid != null, dayStats(fields[1], fields[4], fields[5], fields[8], fields[9]))
    }

    private fun fundEstimate(symbol: Symbol, fields: List<String>): Quote? {
        require(fields.size >= 8) { "Incomplete Sina fund estimate for ${symbol.code}" }
        val price = decimal(fields[2])?.takeIf { it.signum() > 0 } ?: return null
        val date = fields[7].trim()
        val time = fields[1].trim()
        if (date.isEmpty() || time.isEmpty()) return null
        LocalDate.parse(date)
        LocalTime.parse(time)
        return Quote(symbol.code, fields[0].ifBlank { symbol.code }, price, fields[6].trim().toBigDecimalOrNull(),
            sourceTime(date, time), "上次公布净值", decimal(fields[3]), null, false, kind = QuoteKind.ESTIMATE)
    }

    private fun fromTencent(symbols: List<Symbol>): Map<String, Result<Quote?>> = parseBatch(symbols, {
        val codes = symbols.joinToString(",") { "r_${it.code}" }
        val data = responseObject(request(URI.create("https://qt.gtimg.cn/q=?q=$codes&fmt=json"), "https://gu.qq.com/", GB18030))
        require(data.keySet().any { it.startsWith("r_hk") || it == "pv_none_match" }) { "Unexpected Tencent quote response" }
        data
    }) { symbol, data ->
        val fields = data.getAsJsonArray("r_${symbol.code}")
        if (fields == null || fields.isEmpty) null else {
            require(fields.size() >= 31) { "Incomplete Tencent quote for ${symbol.code}" }
            val price = decimal(fields[3].asString)
            fun field(index: Int): String? =
                if (index < fields.size()) fields[index].takeIf { it.isJsonPrimitive }?.asString else null
            marketQuote(symbol, fields[1].asString, price, fields[30].asString, "昨收", decimal(fields[4].asString),
                day = dayStats(field(5), field(33), field(34), field(36), field(37)))
        }
    }

    private fun fromEastmoney(symbol: Symbol): Quote? {
        val code = symbol.code
        val url = URI.create("https://api.fund.eastmoney.com/f10/lsjz?fundCode=$code&pageIndex=1&pageSize=1")
        val data = responseObject(request(url, "https://fundf10.eastmoney.com/jjjz_$code.html", Charsets.UTF_8))
        require(data.get("ErrCode")?.asInt == 0) { "Eastmoney NAV request failed for $code" }
        val info = data.getAsJsonObject("Data")
        val rows = requireNotNull(info?.getAsJsonArray("LSJZList")) {
            "Unexpected Eastmoney NAV response for $code"
        }
        if (rows.isEmpty) return null
        val row = rows[0].asJsonObject
        val date = requireNotNull(row.text("FSRQ")?.takeIf(String::isNotBlank)) { "Eastmoney NAV missing date for $code" }
        val kind = when (info.text("SYType")) {
            null, "" -> QuoteKind.NAV
            "每万份收益" -> QuoteKind.INCOME
            else -> return null
        }
        val nav = if (kind == QuoteKind.INCOME) row.text("DWJZ")?.toBigDecimalOrNull() else decimal(row.text("DWJZ"))
        if (nav == null) return null
        val percent = row.text("JZZZL")?.trim()?.removeSuffix("%")?.toBigDecimalOrNull()
        val name = fundNames[code] ?: runCatching { fundName(code) }.getOrNull()?.also { fundNames[code] = it }
        val hasNav = kind == QuoteKind.NAV
        return Quote(code, name ?: code, nav, percent.takeIf { hasNav }, date, if (hasNav) "披露日涨跌幅" else "",
            null, null, false, kind = kind)
    }

    private fun fundName(code: String): String? {
        val url = URI.create("https://fundsuggest.eastmoney.com/FundSearch/api/FundSearchAPI.ashx?m=1&key=$code")
        val data = responseObject(request(url, "https://fund.eastmoney.com/", Charsets.UTF_8))
        if (data.get("ErrCode")?.asInt != 0) return null
        return data.getAsJsonArray("Datas")?.asSequence()?.map { it.asJsonObject }
            ?.firstOrNull { row -> row.get("FundBaseInfo")?.isJsonObject == true && row.getAsJsonObject("FundBaseInfo").text("FCODE") == code }
            ?.text("NAME")?.takeIf(String::isNotBlank)
    }

    private fun <T> parseBatch(
        symbols: List<Symbol>,
        fetch: () -> T,
        parse: (Symbol, T) -> Quote?,
    ): Map<String, Result<Quote?>> {
        val batch = runCatching(fetch)
        return symbols.associate { symbol -> symbol.code to batch.mapCatching { parse(symbol, it) } }
    }

    private fun marketQuote(
        symbol: Symbol,
        name: String,
        price: BigDecimal?,
        time: String,
        basisLabel: String,
        basis: BigDecimal?,
        rawTrade: BigDecimal? = price,
        bidFallback: Boolean = false,
        day: DayStats,
    ): Quote {
        val percent = if (price == null || basis == null) null else {
            (price - basis).movePointRight(2).divide(basis, 2, RoundingMode.HALF_UP)
        }
        return Quote(symbol.code, name.trim().ifEmpty { symbol.code }, price, percent, time, basisLabel, basis, rawTrade, bidFallback, day)
    }

    private fun dayStats(open: String?, high: String?, low: String?, volume: String?, turnover: String? = null) =
        DayStats(decimal(open), decimal(high), decimal(low), quantity(volume), quantity(turnover))

    private fun quantity(value: String?): BigDecimal? =
        value?.trim()?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }

    private fun decimal(value: String?): BigDecimal? =
        value?.trim()?.toBigDecimalOrNull()?.takeUnless { it.signum() == 0 }

    private fun sourceTime(date: String, time: String): String = listOf(date.trim(), time.trim()).filter(String::isNotEmpty).joinToString(" ")

    private fun responseObject(body: String): JsonObject = JsonParser.parseString(body).asJsonObject

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private companion object {
        val GB18030: Charset = Charset.forName("GB18030")
        val SINA_ROW = Regex("""var\s+hq_str_([a-zA-Z0-9_]+)\s*=\s*"([^"\r\n]*)"\s*;""")
        val COMPACT_TIME = Regex("^([0-9]{2})([0-9]{2})([0-9]{2})$")
        val INDEX_AND_BOND_FUTURES = setOf("IC", "IF", "IH", "IM", "T", "TF", "TL", "TS")
    }
}

internal fun downloadQuote(
    uri: URI,
    referer: String,
    charset: Charset,
    connectTimeoutMillis: Int = 5000,
    readTimeoutMillis: Int = 7000,
    maximumBytes: Int = 512 * 1024,
): String {
    val connection = uri.toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.setRequestProperty("Referer", referer)
        val status = connection.responseCode
        if (status !in 200..299) throw IOException("${uri.host} returned HTTP $status")
        return connection.inputStream.use { stream ->
            val bytes = stream.readNBytes(maximumBytes + 1)
            if (bytes.size > maximumBytes) throw IOException("${uri.host} returned an oversized quote response")
            String(bytes, charset)
        }
    } finally {
        connection.disconnect()
    }
}

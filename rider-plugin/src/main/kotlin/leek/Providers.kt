package leek

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MarketQuoteSource internal constructor(
    private val request: (URI, String, Charset) -> String,
) : QuoteSource {
    constructor() : this(::downloadQuote)

    private val fundNames = ConcurrentHashMap<String, String>()

    override fun fetch(symbols: List<Symbol>): Map<String, Result<Quote?>> {
        val (funds, markets) = symbols.distinctBy(Symbol::code).partition { it.group == Group.FUND }
        val (hongKong, sina) = markets.partition { it.code.startsWith("hk") }
        val batches =
            sina.chunked(50).map { batch -> { fromSina(batch) } } +
                hongKong.chunked(50).map { batch -> { fromTencent(batch) } } +
                funds.map { fund -> { mapOf(fund.code to runCatching { fromEastmoney(fund) }) } }
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

    private fun fromSina(symbols: List<Symbol>): Map<String, Result<Quote?>> = parseBatch(symbols, {
        val codes = symbols.joinToString(",") { it.code }
        val body = request(URI.create("https://hq.sinajs.cn/list=$codes"), "https://finance.sina.com.cn/", GB18030)
        val rows = SINA_ROW.findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        require(rows.isNotEmpty()) { "Unexpected Sina quote response" }
        rows
    }) { symbol, rows ->
        val row = requireNotNull(rows[symbol.code]) { "Sina omitted ${symbol.code}" }
        if (row.isBlank()) null else parseSinaQuote(symbol, row.split(','))
    }

    private fun parseSinaQuote(symbol: Symbol, fields: List<String>): Quote = when (symbol.group) {
        Group.STOCK -> {
            require(fields.size >= 32) { "Incomplete Sina stock quote for ${symbol.code}" }
            val trade = decimal(fields[3])
            val basis = decimal(fields[2])
            val bid = decimal(fields[6])
            val price = trade ?: bid
            marketQuote(symbol, fields[0], price, sourceTime(fields[30], fields[31]), "昨收", basis, trade,
                trade == null && bid != null, dayStats(fields[1], fields[4], fields[5], fields[8], fields[9]))
        }
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
        Group.FUND -> error("Unexpected Sina fund quote for ${symbol.code}")
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
        val rows = requireNotNull(data.getAsJsonObject("Data")?.getAsJsonArray("LSJZList")) {
            "Unexpected Eastmoney NAV response for $code"
        }
        if (rows.isEmpty) return null
        val row = rows[0].asJsonObject
        val date = requireNotNull(row.text("FSRQ")?.takeIf(String::isNotBlank)) { "Eastmoney NAV missing date for $code" }
        val nav = decimal(row.text("DWJZ"))
        val percent = row.text("JZZZL")?.trim()?.removeSuffix("%")?.toBigDecimalOrNull()
        val name = fundNames[code] ?: runCatching { fundName(code) }.getOrNull()?.also { fundNames[code] = it }
        return Quote(code, name ?: code, nav, percent, date, "披露日涨跌幅", null, null, false)
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
            val maximum = 512 * 1024
            val bytes = stream.readNBytes(maximum + 1)
            if (bytes.size > maximum) throw IOException("${uri.host} returned an oversized quote response")
            String(bytes, charset)
        }
    } finally {
        connection.disconnect()
    }
}

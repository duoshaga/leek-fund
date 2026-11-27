package leek

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SymbolHit(val symbol: Symbol, val name: String)

class SymbolSearch internal constructor(private val request: (URI, String) -> String) {
    constructor() : this({ uri, referer ->
        val charset = if (uri.host == "suggest3.sinajs.cn") GB18030 else Charsets.UTF_8
        downloadQuote(uri, referer, charset, 2000, 3000)
    })

    fun local(query: String): List<SymbolHit> {
        val keyword = normalize(query)
        if (keyword.isEmpty() || keyword.length > MAX_QUERY_LENGTH) return emptyList()
        val localMarkets = SAMPLES.filter { (hit, aliases) ->
            hit.symbol.code.contains(keyword, ignoreCase = true) || hit.name.contains(keyword, ignoreCase = true) ||
                aliases.any { it.contains(keyword, ignoreCase = true) }
        }.map { it.first }
        return ranked(keyword, localMarkets + FuturesDirectory.search(keyword))
    }

    /** Call on a background thread. Local results survive a slow or unavailable provider. */
    fun search(query: String): List<SymbolHit> {
        val keyword = normalize(query)
        val immediate = local(keyword)
        if (keyword.isEmpty() || keyword.length > MAX_QUERY_LENGTH) return immediate

        val market = MARKET_PREFIX.matchEntire(keyword)?.groupValues?.get(1)
        val futureGroup = when {
            keyword.startsWith("nf_") -> Group.DOMESTIC
            keyword.startsWith("hf_") -> Group.OVERSEAS
            else -> null
        }
        val requests = buildList<Callable<List<SymbolHit>>> {
            if (futureGroup == null) {
                val stockWord = if (market == null) keyword else keyword.drop(2).trimStart()
                if (market == null) add(Callable { eastmoney(keyword) })
                add(Callable { tencent(stockWord, market) })
            }
            if (market == null) {
                val futureWord = if (futureGroup == null) keyword else keyword.drop(3).trimStart()
                if (futureWord.isNotEmpty()) add(Callable { sina(futureWord, futureGroup) })
            }
        }
        if (requests.isEmpty()) return immediate

        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val responses = executor.invokeAll(requests, 4, TimeUnit.SECONDS)
            val suggestions = responses.filterNot { it.isCancelled }.flatMap { response ->
                runCatching { response.get() }.getOrDefault(emptyList())
            }
            return ranked(keyword, immediate + suggestions)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun sina(keyword: String, group: Group?): List<SymbolHit> {
        val key = encoded(keyword).replace("+", "%20")
        val url = URI.create("https://suggest3.sinajs.cn/suggest/type=85,86,88&key=$key")
        val body = request(url, "https://finance.sina.com.cn/")
        val rows = SINA_ROWS.find(body)?.groupValues?.get(1) ?: return emptyList()
        val codeFragment = FUTURES_CODE_FRAGMENT.matches(keyword)
        return rows.split(';').mapNotNull { row ->
            val fields = row.split(',')
            val kind = fields.getOrNull(1)
            val code = fields.getOrNull(3)?.trim().orEmpty()
            val name = fields.getOrNull(4)?.trim().orEmpty()
            val prefix = when (kind) {
                "85", "88" -> "nf_"
                "86" -> "hf_"
                else -> return@mapNotNull null
            }
            if (name.isEmpty() || name.contains("现货") || (codeFragment && !code.contains(keyword, ignoreCase = true))) return@mapNotNull null
            val symbol = Symbol.parse(prefix + code)?.takeIf { group == null || it.group == group }
            symbol?.let { SymbolHit(it, name) }
        }
    }

    private fun tencent(keyword: String, market: String?): List<SymbolHit> {
        val url = URI.create("https://proxy.finance.qq.com/ifzqgtimg/appstock/smartbox/search/get?q=${encoded(keyword)}")
        val data = JsonParser.parseString(request(url, "https://gu.qq.com/")).asJsonObject
        if (data.get("code")?.text() != "0") return emptyList()
        val rows = data.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.get("stock")
        return rows?.takeIf { it.isJsonArray }?.asJsonArray?.asList().orEmpty().mapNotNull { row ->
            if (!row.isJsonArray) return@mapNotNull null
            val fields = row.asJsonArray.asList()
            val exchange = fields.getOrNull(0).text() ?: return@mapNotNull null
            val code = fields.getOrNull(1).text() ?: return@mapNotNull null
            val name = fields.getOrNull(2).text() ?: return@mapNotNull null
            val kind = fields.getOrNull(4).text().orEmpty()
            if (market != null && exchange != market) return@mapNotNull null
            if (keyword.all(Char::isDigit) && !code.contains(keyword)) return@mapNotNull null

            val symbol = when {
                kind == "ETF" && exchange in setOf("sh", "sz") -> Symbol.parse(exchange + code)
                exchange == "jj" && (kind.startsWith("KJ") || kind == "LOF" || kind == "ETF") -> Symbol.parse(code)?.takeIf { it.group == Group.FUND }
                exchange in STOCK_MARKETS && (kind == "ZS" || kind == "GP" || kind == "GP-A" || kind.startsWith("GP-A-")) ->
                    Symbol.parse(exchange + code)
                else -> null
            }
            symbol?.let { SymbolHit(it, name) }
        }
    }

    private fun eastmoney(keyword: String): List<SymbolHit> {
        val url = URI.create("https://fundsuggest.eastmoney.com/FundSearch/api/FundSearchAPI.ashx?m=1&key=${encoded(keyword)}")
        val data = JsonParser.parseString(request(url, "https://fund.eastmoney.com/")).asJsonObject
        if (data.get("ErrCode").text() != "0") return emptyList()
        return data.get("Datas")?.takeIf { it.isJsonArray }?.asJsonArray?.asList().orEmpty().mapNotNull { row ->
            if (!row.isJsonObject) return@mapNotNull null
            val result = row.asJsonObject
            val code = result.get("CODE").text() ?: return@mapNotNull null
            val name = result.get("NAME").text() ?: return@mapNotNull null
            if (keyword.all(Char::isDigit) && !code.contains(keyword)) return@mapNotNull null
            val fund = result.get("FundBaseInfo")?.takeIf { it.isJsonObject }?.asJsonObject
            if (result.get("CATEGORY").text() != "700" || fund?.get("FCODE").text() != code) return@mapNotNull null
            val symbol = Symbol.parse(code)?.takeIf { it.group == Group.FUND }
            symbol?.let { SymbolHit(it, name) }
        }
    }

    private fun ranked(keyword: String, hits: List<SymbolHit>): List<SymbolHit> = hits
        .distinctBy { it.symbol.code }
        .sortedBy { rank(keyword, it) }
        .take(MAX_RESULTS)

    private fun rank(keyword: String, hit: SymbolHit): Int {
        val code = normalize(hit.symbol.code)
        val name = normalize(hit.name)
        val unqualifiedCode = when (hit.symbol.group) {
            Group.STOCK -> code.drop(2)
            Group.DOMESTIC, Group.OVERSEAS -> code.drop(3)
            Group.FUND -> code
        }
        return when {
            code == keyword || unqualifiedCode == keyword || name == keyword -> 0
            code.startsWith(keyword) || unqualifiedCode.startsWith(keyword) || name.startsWith(keyword) -> 1
            code.contains(keyword) || name.contains(keyword) -> 2
            hit.symbol.group == Group.FUND -> 4
            else -> 3
        }
    }

    private fun JsonElement?.text(): String? = this?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotEmpty)

    private companion object {
        const val MAX_QUERY_LENGTH = 80
        const val MAX_RESULTS = 40
        val STOCK_MARKETS = setOf("sh", "sz", "bj", "hk")
        val MARKET_PREFIX = Regex("(sh|sz|bj|hk) *[0-9]{1,6}")
        val FUTURES_CODE_FRAGMENT = Regex("[a-z0-9]+")
        val SINA_ROWS = Regex("""var\s+suggestvalue\s*=\s*"([^"\r\n]*)"\s*;""")
        val GB18030: Charset = Charset.forName("GB18030")

        val SAMPLES = listOf(
            SymbolHit(Symbol("sh000001", Group.STOCK), "上证指数") to listOf("szzs", "shangzhengzhishu"),
            SymbolHit(Symbol("sz399001", Group.STOCK), "深证成指") to listOf("szcz", "shenzhengchengzhi"),
            SymbolHit(Symbol("sz000001", Group.STOCK), "平安银行") to listOf("payh", "pinganyinhang"),
            SymbolHit(Symbol("sh600000", Group.STOCK), "浦发银行") to listOf("pfyh", "pufayinhang"),
            SymbolHit(Symbol("hk00700", Group.STOCK), "腾讯控股") to listOf("txkg", "tengxunkonggu", "tencent"),
            SymbolHit(Symbol("000001", Group.FUND), "华夏成长混合") to listOf("hxczhh", "huaxiachengzhanghunhe"),
            SymbolHit(Symbol("161725", Group.FUND), "招商中证白酒指数(LOF)A") to listOf("白酒基金", "zszzbjzsa", "zhaoshangzhongzhengbaijiuzhishu"),
        )

        fun normalize(query: String): String = Normalizer.normalize(query, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)

        fun encoded(query: String): String = URLEncoder.encode(query, Charsets.UTF_8)
    }
}

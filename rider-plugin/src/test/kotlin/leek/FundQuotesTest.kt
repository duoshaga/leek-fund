package leek

import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FundQuotesTest {
    private val now = Instant.parse("2026-11-27T05:31:15Z")
    private val nav = """{"ErrCode":0,"Data":{"LSJZList":[{"FSRQ":"2026-11-26","DWJZ":"0.7745","JZZZL":"-1.40"}]}}"""
    private val emptyNames = """{"ErrCode":0,"Datas":[]}"""
    private val etfRow = """var hq_str_sh588200="科创芯片ETF嘉实,0.778,0.774,0.888,0.891,0.778,0.887,0.888,4996484980,4128823842.000,3035500,0.887,1906600,0.886,3871500,0.885,4592600,0.884,2049700,0.883,1469800,0.888,4454500,0.889,4110776,0.890,4014300,0.891,2493000,0.892,2026-11-27,13:31:13,00,";"""

    @Test fun listedEtfsUseTheExchangeTradeAndPreviousCloseInTheFundGroup() {
        val tickers = listOf("sh588200", "sz159915", "sh520580", "sh551500", "sh565000", "sh530680", "sz158001")
        val source = MarketQuoteSource { uri, _, _ -> when (uri.host) {
            "hq.sinajs.cn" -> uri.path.substringAfter("list=").split(',').joinToString("\n") { code ->
                require(code in tickers) { code }
                etfRow.replace("sh588200", code)
            }
            "api.fund.eastmoney.com" -> nav
            else -> emptyNames
        } }
        val codes = tickers.map { it.drop(2) }
        val quotes = source.fetch(codes.map { Symbol.parse(it)!! })
        for (code in codes) {
            val quote = quotes.getValue(code).getOrThrow()!!
            val symbol = Symbol.parse(code)!!
            val row = WatchRow(symbol, quote, QuoteState.CURRENT, now)
            assertEquals("0.888", quote.price.toString(), code)
            assertEquals("0.774", quote.basis.toString())
            assertEquals("14.73", quote.percent.toString())
            assertEquals("2026-11-27 13:31:13", quote.sourceTime)
            assertEquals(WatchGroup.FUND, symbol.watchGroup)
            assertEquals("现价 0.888", QuoteText.price(row))
            assertTrue(QuoteText.timestamp(row).contains("报价时间"))
            assertTrue(QuoteText.detail(row).contains("昨收"))
            assertEquals(QuoteText.market(row), QuoteText.market(SavedItem.from(row).toRow()!!))
        }
    }

    @Test fun otherFundsIncludingEtfFeedersAndLofsShowTheSourceEstimateAndTime() {
        val codes = listOf("000001", "007300", "161725", "519674")
        val source = MarketQuoteSource { uri, _, _ -> when (uri.host) {
            "hq.sinajs.cn" -> uri.path.substringAfter("list=").split(',').joinToString("\n") { code ->
                require(code.startsWith("fu_") && code.drop(3) in codes)
                """var hq_str_${code}="基金,13:28:00,1.0099,0.9000,3.4730,0,12.2133,2026-11-27,1.0260,14.0035";"""
            }
            "api.fund.eastmoney.com" -> nav
            else -> emptyNames
        } }
        val quotes = source.fetch(codes.map { Symbol.parse(it)!! })
        for (code in codes) {
            val quote = quotes.getValue(code).getOrThrow()!!
            val row = WatchRow(Symbol.parse(code)!!, quote, QuoteState.CURRENT, now)
            assertEquals("估值 1.0099", QuoteText.price(row), code)
            assertEquals("12.2133", quote.percent.toString())
            assertEquals("0.9000", quote.basis.toString())
            assertNull(quote.rawTrade)
            assertNull(quote.day)
            assertTrue(QuoteText.detail(row).contains("估值时间  2026-11-27 13:28:00"))
            assertTrue(QuoteText.status(SavedItem.from(row).toRow()).contains("估值 1.0099   +12.21%"))
        }
    }

    @Test fun partialEstimateFieldsKeepZeroDistinctFromMissingAndProtocolFailuresDistinctFromNoData() {
        val source = MarketQuoteSource { uri, _, _ -> when (uri.host) {
            "hq.sinajs.cn" -> """
                var hq_str_fu_007300="联接A,13:28:00,1.0000,1.0000,1.0000,,0.0000,2026-11-27,,";
                var hq_str_fu_000001="成长,13:28:00,1.0000,,1.0000,,,2026-11-27,,";
                var hq_str_sh588200="";
            """.trimIndent()
            else -> error("unexpected request $uri")
        } }
        val result = source.fetch(listOf("007300", "000001", "161725", "588200").map { Symbol.parse(it)!! })
        assertEquals("0.00%", QuoteText.percentage(result.getValue("007300").getOrThrow()!!.percent))
        assertNull(result.getValue("000001").getOrThrow()!!.percent)
        assertTrue(result.getValue("161725").isFailure)
        assertNull(result.getValue("588200").getOrThrow())
    }

    @Test fun laterOfficialDisclosureSupersedesAnOldEstimate() {
        val source = MarketQuoteSource { uri, _, _ -> when (uri.host) {
            "hq.sinajs.cn" -> """var hq_str_fu_007300="联接A,15:00:00,0.7690,0.7600,0.7600,,1.18,2020-01-02,,";"""
            "api.fund.eastmoney.com" -> nav
            else -> emptyNames
        } }
        val symbol = Symbol.parse("007300")!!
        val quote = source.fetch(listOf(symbol)).getValue(symbol.code).getOrThrow()
        val row = WatchRow(symbol, quote, QuoteState.CURRENT, now)
        assertEquals("净值 0.7745", QuoteText.price(row))
        assertEquals("净值日期  2026-11-26", QuoteText.timestamp(row))
    }

    @Test fun aMissingEstimateDoesNotReplaceTodaysCachedEstimateWithYesterdaysNav() {
        var estimate = "联接A,13:28:00,1.0099,0.9000,3.4730,0,12.2133,2026-11-27,,"
        val source = MarketQuoteSource { uri, _, _ -> when (uri.host) {
            "hq.sinajs.cn" -> """var hq_str_fu_007300="$estimate";"""
            "api.fund.eastmoney.com" -> nav
            else -> emptyNames
        } }
        val symbol = Symbol.parse("007300")!!
        val book = WatchBook(MarketSessions::interval)
        book.add(symbol)
        val first = book.reserve(now).single()
        book.complete(first, source.fetch(listOf(symbol)).getValue(symbol.code), now)
        estimate = ""
        book.requestAll()
        val later = now.plusSeconds(60)
        book.complete(book.reserve(later).single(), source.fetch(listOf(symbol)).getValue(symbol.code), later)
        val row = book.view().rows.single()
        assertEquals("估值 1.0099", QuoteText.price(row))
        assertEquals("2026-11-27 13:28:00", row.quote?.sourceTime)
        assertEquals(QuoteState.CACHED, row.state)
        assertEquals(now, row.received)
    }

    @Test fun unavailableEstimatesUseLabelledDisclosureAndMoneyFundIncomeRetainsItsUnit() {
        val source = MarketQuoteSource { uri, _, _ -> when (uri.host) {
            "hq.sinajs.cn" -> """var hq_str_fu_007300="";var hq_str_fu_000198="";"""
            "api.fund.eastmoney.com" -> if ("000198" in uri.query) {
                """{"ErrCode":0,"Data":{"SYType":"每万份收益","LSJZList":[{"FSRQ":"2026-11-26","DWJZ":"0.2159","JZZZL":"0.00"}]}}"""
            } else nav
            else -> emptyNames
        } }
        val symbols = listOf("007300", "000198").map { Symbol.parse(it)!! }
        val quotes = source.fetch(symbols)
        val rows = symbols.map { WatchRow(it, quotes.getValue(it.code).getOrThrow(), QuoteState.CURRENT, now) }
        assertEquals("净值 0.7745", QuoteText.price(rows[0]))
        assertTrue(QuoteText.detail(rows[0]).contains("暂无估值"))
        assertTrue(QuoteText.timestamp(rows[0]).contains("净值日期  2026-11-26"))
        assertEquals("每万份收益 0.2159", QuoteText.price(rows[1]))
        assertNull(rows[1].quote?.percent)
    }

    @Test fun marketQualifiedSearchAndOldPinsResolveToTheSameSixDigitEtf() {
        val etf = Symbol.parse("588200")!!
        val search = SymbolSearch { uri, _ -> if (uri.host == "proxy.finance.qq.com") {
            """{"code":0,"data":{"stock":[["sh","588200","科创芯片ETF嘉实","","ETF"]]}}"""
        } else emptyNames }
        assertEquals(listOf(SymbolHit(etf, "科创芯片ETF嘉实")), search.search("sh588200"))
        assertEquals(etf, Symbol.parse("SH588200"))
        val old = SavedWatchlist().apply {
            pinnedCode = "sh588200"
            items.add(SavedItem().apply {
                code = "588200"; name = "科创芯片ETF嘉实"; price = "0.7745"; time = "2026-11-26"
                basisLabel = "披露日涨跌幅"
            })
            items.add(SavedItem().apply {
                code = "sh588200"; name = "科创芯片ETF嘉实"; price = "0.888"; time = "2026-11-27 13:31:13"
                basisLabel = "昨收"
            })
        }
        val book = WatchBook(MarketSessions::interval)
        book.restore(old)
        assertEquals(listOf(etf), book.view().rows.map { it.symbol })
        assertEquals("现价 0.888", QuoteText.price(book.view().pinned!!))
        old.items.removeAt(1)
        old.pinnedCode = "588200"
        book.restore(old)
        assertFalse(QuoteText.price(book.view().pinned!!).contains("0.7745"))
        assertEquals(etf, book.reserve(Instant.parse("2026-11-28T05:00:00Z")).single().symbol)
    }

    @Test fun reopeningAfterCloseFetchesTheEtfClosingPriceAndKeepsTheSelectedFundName() {
        val symbol = Symbol.parse("159915")!!
        val quote = Quote("159915", "创业板", "2.414".toBigDecimal(), null, "2026-11-27 13:31:15",
            "昨收", "2.217".toBigDecimal(), "2.414".toBigDecimal(), false)
        val row = WatchRow(symbol, quote, QuoteState.CURRENT, now, displayName = "创业板ETF易方达")
        val book = WatchBook(MarketSessions::interval)
        book.restore(SavedWatchlist().apply { items.add(SavedItem.from(row)) })
        val closed = Instant.parse("2026-11-27T09:00:00Z")
        assertTrue(QuoteText.detail(book.view().rows.single()).contains("创业板ETF易方达"))
        val request = book.reserve(closed).single()
        book.complete(request, Result.success(quote), closed)
        assertTrue(book.reserve(closed.plusSeconds(60)).isEmpty())
    }

    @Test fun exchangeAndEstimatedFundsRefreshWithinTheMainlandTradingSession() {
        val etf = Symbol.parse("588200")!!
        val fund = Symbol.parse("007300")!!
        val book = WatchBook(MarketSessions::interval)
        for (symbol in listOf(etf, fund)) book.add(symbol)
        book.reserve(now).forEach { book.complete(it, Result.success(null), now) }
        assertEquals(listOf(etf), book.reserve(now.plusSeconds(25)).map { it.symbol })
        assertEquals(listOf(fund), book.reserve(now.plusSeconds(60)).map { it.symbol })
        val lunch = Instant.parse("2026-11-27T04:00:00Z")
        val weekend = Instant.parse("2026-11-28T05:00:00Z")
        for (closed in listOf(lunch, weekend)) {
            assertNull(MarketSessions.interval(etf, closed))
            assertTrue(MarketSessions.interval(fund, closed)!! >= Duration.ofHours(1))
        }
        assertEquals(Duration.ofSeconds(25), MarketSessions.interval(etf, Instant.parse("2026-11-27T07:00:20Z")))
        assertNull(MarketSessions.interval(etf, Instant.parse("2026-11-27T07:01:00Z")))
    }
}

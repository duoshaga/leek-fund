package leek

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class ChartHistoryTest {
    private fun fetch(code: String, period: ChartPeriod, reply: (URI) -> String): ChartSeries =
        HistorySource { uri, _ -> reply(uri) }.fetch(Symbol.parse(code)!!, period)

    @Test fun exchangeHistoryRequestsTradedAmountAlongsideVolume() {
        val series = fetch("sh600000", ChartPeriod.M60) { uri ->
            assertTrue(uri.query.substringAfter("fields2=").substringBefore('&').split(',').contains("f57"))
            """{"rc":0,"data":{"code":"600000","klines":["2026-11-27 10:30,10,12,14,9,1234,1357900"]}}"""
        }
        assertEquals(1357900.0, series.bars.single().turnover)
    }

    @Test fun exchangeSymbolsUseTheirOwnMarketAndNativePeriodIncludingTwoHours() {
        val instruments = mapOf("sh000001" to "1.000001", "sz000001" to "0.000001",
            "bj920017" to "0.920017", "hk00700" to "116.00700", "588200" to "1.588200", "159915" to "0.159915")
        val periods = listOf(ChartPeriod.M5 to 5, ChartPeriod.M15 to 15, ChartPeriod.M30 to 30,
            ChartPeriod.M60 to 60, ChartPeriod.M120 to 120, ChartPeriod.DAY to 101, ChartPeriod.WEEK to 102)
        for ((code, secid) in instruments) for ((period, interval) in periods) {
            val time = if (interval < 100 || interval == 120) "2026-11-27 11:30" else "2026-11-27"
            val series = fetch(code, period) { uri ->
                assertTrue("secid=$secid" in uri.query.split('&'))
                assertTrue("klt=$interval" in uri.query.split('&'))
                """{"rc":0,"data":{"code":"${secid.substringAfter('.')}","klines":["$time,10,12,14,9,1234"]}}"""
            }
            assertEquals(ChartBar(LocalDateTime.parse(time.replace(' ', 'T').let { if ('T' in it) it else "${it}T00:00" }),
                10.0, 14.0, 9.0, 12.0, 1234.0), series.bars.single())
            assertEquals(if (code.startsWith("hk")) "股" else "手", series.volumeUnit)
        }
    }

    @Test fun minuteFuturesKeepTheExactContractAndTwoHourNightSession() {
        for ((code, host) in mapOf("nf_AO2702" to "stock2.finance.sina.com.cn", "nf_IF0" to "stock2.finance.sina.com.cn",
            "hf_CHA50CFD" to "gu.sina.cn")) {
            val series = fetch(code, ChartPeriod.M120) { uri ->
                assertEquals(host, uri.host)
                assertTrue("symbol=${code.substringAfter('_')}" in uri.query.split('&'))
                assertTrue("type=120" in uri.query.split('&'))
                """[{"d":"2026-11-27 01:00:00","o":"10","h":"13","l":"9","c":"11","v":"0","p":"874"}]"""
            }
            val interest = if (code.startsWith("nf_")) 874.0 else null
            assertEquals(ChartBar(LocalDateTime.parse("2026-11-27T01:00"), 10.0, 13.0, 9.0, 11.0, 0.0,
                openInterest = interest), series.bars.single())
        }
    }

    @Test fun weeklyFuturesUseTradingDatesAndDoNotCountADuplicateOrInventMissingVolume() {
        val series = fetch("hf_OIL", ChartPeriod.WEEK) {
            assertTrue("getGlobalFuturesDailyKLine" in it.path)
            """[
              {"date":"2026-01-05","open":12,"high":15,"low":10,"close":14,"volume":"--"},
              {"date":"2025-12-29","open":10,"high":13,"low":9,"close":12,"volume":4},
              {"date":"2026-01-02","open":12,"high":16,"low":7,"close":14,"volume":3},
              {"date":"2026-01-02","open":12,"high":16,"low":7,"close":15,"volume":6}
            ]"""
        }
        assertEquals(listOf(
            ChartBar(LocalDateTime.parse("2026-01-02T00:00"), 10.0, 16.0, 7.0, 15.0, 10.0),
            ChartBar(LocalDateTime.parse("2026-01-05T00:00"), 12.0, 15.0, 10.0, 14.0, null)), series.bars)
    }

    @Test fun fundChartsUseDatedDisclosuresAndRestrictIntradayTradingPeriodsToListedFunds() {
        for ((label, kind) in listOf("" to SeriesKind.NAV, "每万份收益" to SeriesKind.INCOME)) {
            val series = fetch("000001", ChartPeriod.WEEK) { uri ->
                if (uri.host == "api.fund.eastmoney.com") """{"ErrCode":0,"Data":{"SYType":"$label"}}"""
                else """{"ErrCode":0,"Datas":[{"FSRQ":"2026-11-27","DWJZ":"0.92"},{"FSRQ":"2026-11-26","DWJZ":"0.90"},{"FSRQ":"2026-11-20","DWJZ":"0.87"}]}"""
            }
            assertEquals(kind, series.kind)
            assertEquals(listOf(0.87, 0.92), series.bars.map { it.close })
            assertTrue(series.bars.all { it.volume == null && it.open == null })
        }
        assertEquals(listOf(ChartPeriod.DAY, ChartPeriod.WEEK), ChartPeriod.forSymbol(Symbol.parse("000001")!!))
        assertEquals(7, ChartPeriod.forSymbol(Symbol.parse("588200")!!).size)
    }

    @Test fun malformedHistoryDoesNotMasqueradeAsAnEmptyOrZeroPriceSeries() {
        assertThrows(Exception::class.java) { fetch("nf_IF0", ChartPeriod.M5) { """{"__ERROR":3}""" } }
        assertTrue(fetch("nf_IF0", ChartPeriod.DAY) { "[]" }.bars.isEmpty())
        assertThrows(Exception::class.java) { fetch("sh000001", ChartPeriod.DAY) {
            """{"rc":0,"data":{"code":"600000","klines":["2026-11-27,1,2,3,1,0"]}}"""
        } }
    }

    @Test fun contradictorySourceRangePreservesTheReportedCloseAndVolumeAndFlagsTheCandle() {
        val bars = fetch("hf_OIL", ChartPeriod.DAY) {
            """[{"date":"2026-11-26","open":92,"high":94,"low":90,"close":93,"volume":40},
                {"date":"2026-11-27","open":91.70,"high":93.17,"low":91.84,"close":92.4,"volume":57}]"""
        }.bars
        assertFalse(bars.first().inconsistentRange)
        assertTrue(bars.last().inconsistentRange)
        assertEquals(91.70, bars.last().open)
        assertEquals(91.84, bars.last().low)
        assertEquals(57.0, bars.last().volume)
        assertEquals(92.7, MovingAverages.lines(bars, listOf(2)).getValue(2).last(), 1e-9)
    }

    @Test fun inspectionUsesOnlyThePointedBarsFieldsAndThePreviousBarsCloseAndInterest() {
        val series = fetch("nf_AO2702", ChartPeriod.DAY) {
            """[{"d":"2026-11-25","o":10,"h":11,"l":9,"c":10,"p":100},
                {"d":"2026-11-26","o":10,"h":12,"l":9,"c":11,"v":250,"p":110,"s":10.5},
                {"d":"2026-11-27","o":11,"h":15,"l":11,"c":15,"p":120}]"""
        }
        val readout = ChartReadout()
        val values = readout.fields(series, 1, ChartPeriod.DAY).associate { it.label to it.value }
        assertEquals(mapOf("开盘" to "10", "最高" to "12", "最低" to "9", "收盘" to "11", "成交量" to "250手",
            "涨跌" to "+1", "涨幅" to "+10.00%", "振幅" to "30.00%", "持仓" to "110", "结算" to "10.5",
            "日增仓" to "+10", "增仓幅" to "+10.00%", "成交额" to "—"), values)
        val first = readout.fields(series, 0, ChartPeriod.DAY).associate { it.label to it.value }
        assertEquals("—", first["涨幅"])
        assertEquals("—", first["成交量"])
        val week = fetch("nf_AO2702", ChartPeriod.WEEK) {
            """[{"d":"2026-11-25","o":10,"h":11,"l":9,"c":10,"p":100,"s":9},
                {"d":"2026-11-26","o":10,"h":12,"l":9,"c":11,"p":110,"s":10.5}]"""
        }.bars.single()
        assertEquals(110.0, week.openInterest)
        assertEquals(10.5, week.settlement)
    }

    @Test fun windowsShowingTheSameSymbolShareQuotesAndReleaseTheirOwnHistorySubscription() {
        val symbol = Symbol.parse("nf_AO2702")!!
        val quotes = LinkedBlockingQueue<List<Symbol>>()
        val history = LinkedBlockingQueue<String>()
        val polling = ChartPolling { quotes.add(it.toList()) }
        val first: () -> Unit = { history.add("first") }
        val second: () -> Unit = { history.add("second") }
        try {
            SwingUtilities.invokeAndWait { polling.select(first, symbol); polling.select(second, symbol) }
            assertEquals(listOf(symbol), quotes.remove())
            assertEquals(setOf("first", "second"), List(2) { history.poll(5, TimeUnit.SECONDS) }.toSet())
            assertEquals(listOf(symbol), quotes.remove())
            assertTrue(quotes.isEmpty())
            SwingUtilities.invokeAndWait { polling.select(first, null) }
            assertEquals("second", history.poll(5, TimeUnit.SECONDS))
            assertEquals(listOf(symbol), quotes.remove())
        } finally {
            SwingUtilities.invokeAndWait { polling.select(first, null); polling.select(second, null) }
        }
    }

    @Test fun movingAveragesUseCompleteWindowsOfClosesInTheSelectedPeriod() {
        val history = listOf(2.0, 4.0, 9.0, 5.0).mapIndexed { i, close ->
            ChartBar(LocalDateTime.of(2026,11,27,10,i * 5), 1.0, 12.0, 1.0, close, 200.0)
        }
        val values = MovingAverages.lines(history, listOf(1, 3, 5))
        assertArrayEquals(doubleArrayOf(2.0, 4.0, 9.0, 5.0), values.getValue(1))
        assertArrayEquals(doubleArrayOf(Double.NaN, Double.NaN, 5.0, 6.0), values.getValue(3))
        assertTrue(values.getValue(5).all(Double::isNaN))
    }

    @Test fun maSettingsSurviveRestoreIncludingTurningOffAllLinesAndLoadingOlderSettings() {
        val book = WatchBook { _, _ -> Duration.ZERO }
        book.restore(SavedWatchlist())
        assertEquals(listOf(5, 10, 20, 60), book.view().maPeriods)
        assertTrue(book.setMaPeriods("3， 8, 120"))
        for (invalid in listOf("5,5", "0", "251", "-2", "5,abc", "1,2,3,4,5,6,7,8,9")) assertFalse(book.setMaPeriods(invalid))
        val restored = WatchBook { _, _ -> Duration.ZERO }
        restored.restore(book.save())
        assertEquals(listOf(3, 8, 120), restored.view().maPeriods)
        assertTrue(book.setMaPeriods(""))
        restored.restore(book.save())
        assertTrue(restored.view().maPeriods.isEmpty())
    }
}

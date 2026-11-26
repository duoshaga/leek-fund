package leek

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class WatchBookTest {
    private val time = Instant.parse("2026-11-26T07:35:00Z")
    private val code = "nf_RB2701"
    private val other = "nf_RB0"
    private val book = WatchBook { _, _ -> Duration.ofSeconds(20) }
    private val quote = Quote(code, "螺纹钢2701", BigDecimal("3201"), BigDecimal("1.01"),
        "2026-11-26 14:59:57", "昨结算", BigDecimal("3169"), BigDecimal("3201"), false)

    @Test fun movingWithinAMarketPreservesOtherSlotsPendingQuotesAndPinAcrossReload() {
        val initial = listOf(other, "hk00700", code, "sh000001", "nf_FG2705")
        initial.forEach { book.add(Symbol.parse(it)!!) }
        book.pin(code)
        val outstanding = book.reserve(time).single { it.symbol.code == code }
        assertTrue(book.moveSymbol(code, other, after = false))
        assertTrue(book.moveSymbol(other, "nf_FG2705", after = true))
        assertFalse(book.moveSymbol(code, "nf_FG2705", after = false))
        assertFalse(book.moveSymbol("sh000001", "hk00700", after = true))
        assertFalse(book.moveSymbol("removed", code, after = true))
        assertFalse(book.moveSymbol(code, "removed", after = true))
        book.complete(outstanding, Result.success(quote), time)
        val restored = WatchBook { _, _ -> null }
        restored.restore(book.save())
        assertEquals(listOf(code, "hk00700", "nf_FG2705", "sh000001", other), restored.view().rows.map { it.symbol.code })
        assertEquals(quote, restored.view().pinned?.quote)
    }

    @Test fun oldWatchlistsGetFiveOrderedGroupsAndMovedEmptyGroupsSurviveReload() {
        val default = listOf(WatchGroup.MAINLAND, WatchGroup.HONG_KONG, WatchGroup.FUND, WatchGroup.DOMESTIC, WatchGroup.OVERSEAS)
        val old = SavedWatchlist().apply { items.add(SavedItem().apply { code = "hk00700" }) }
        book.restore(old)
        assertEquals(default, book.view().groupOrder)
        assertEquals(WatchGroup.HONG_KONG, book.view().rows.single().symbol.watchGroup)
        assertTrue(book.moveGroup(WatchGroup.OVERSEAS, WatchGroup.MAINLAND, after = false))
        assertTrue(book.moveGroup(WatchGroup.HONG_KONG, WatchGroup.DOMESTIC, after = true))
        assertFalse(book.moveGroup(WatchGroup.HONG_KONG, WatchGroup.DOMESTIC, after = true))
        val saved = book.save()
        saved.groupOrder.addAll(listOf("UNKNOWN", "OVERSEAS"))
        val restored = WatchBook { _, _ -> null }
        restored.restore(saved)
        assertEquals(listOf(WatchGroup.OVERSEAS, WatchGroup.MAINLAND, WatchGroup.FUND, WatchGroup.DOMESTIC,
            WatchGroup.HONG_KONG), restored.view().groupOrder)
    }

    @Test fun overlappingRequestsAndLateRemovalDoNotOverwriteReplacement() {
        assertTrue(book.add(Symbol.parse(code)!!))
        assertFalse(book.add(Symbol.parse(code)!!))
        val previous = book.reserve(time).single()
        book.requestAll()
        assertTrue(book.reserve(time).isEmpty())
        book.remove(code)
        book.add(Symbol.parse(code)!!)
        val replacement = book.reserve(time).single()
        book.complete(previous, Result.success(quote), time)
        assertNull(book.view().rows.single().quote)
        book.complete(replacement, Result.success(quote), time)
        assertEquals(quote, book.view().rows.single().quote)
        assertTrue(book.reserve(time.plusSeconds(19)).isEmpty())
        assertEquals(code, book.reserve(time.plusSeconds(20)).single().symbol.code)
    }

    @Test fun transportFailureRetainsPriceAndTimeAndSuccessfulEmptyClearsThem() {
        book.add(Symbol.parse(code)!!)
        book.complete(book.reserve(time).single(), Result.success(quote), time)
        val later = time.plusSeconds(20)
        book.complete(book.reserve(later).single(), Result.failure(java.io.IOException("offline")), later)
        val stale = book.view().rows.single()
        assertEquals(quote, stale.quote)
        assertEquals(QuoteState.FAILED, stale.state)
        assertTrue(QuoteText.detail(stale).contains(quote.sourceTime))
        val recovery = later.plusSeconds(20)
        book.complete(book.reserve(recovery).single(), Result.success(quote), recovery)
        assertEquals(QuoteState.CURRENT, book.view().rows.single().state)
        book.requestAll()
        book.complete(book.reserve(recovery).single(), Result.success(null), recovery)
        val empty = book.view().rows.single()
        assertEquals(QuoteState.EMPTY, empty.state)
        assertNull(empty.quote)
        assertTrue(QuoteText.market(empty).contains("—"))
    }

    @Test fun watchlistCacheAndExactPinnedMonthSurviveReload() {
        book.add(Symbol.parse(other)!!)
        book.add(Symbol.parse(code)!!)
        assertTrue(book.pin(code))
        val tickets = book.reserve(time)
        book.complete(tickets.last(), Result.success(quote), time)
        val restored = WatchBook { _, _ -> null }
        restored.restore(book.save())
        assertEquals(listOf(other, code), restored.view().rows.map { it.symbol.code })
        assertEquals(quote, restored.view().pinned?.quote)
        assertEquals(QuoteState.CACHED, restored.view().pinned?.state)
        assertTrue(restored.reserve(time).isEmpty())
        restored.requestAll()
        assertEquals(2, restored.reserve(time).size)
        restored.pin(other)
        assertEquals(other, restored.view().pinned?.symbol?.code)
        restored.remove(other)
        assertNull(restored.view().pinned)
        assertFalse(restored.pin("invalid"))
    }

    @Test fun bidFallbackAndMissingPercentAreConsistentOnBothSurfaces() {
        val symbol = Symbol.parse("hf_CHA50CFD")!!
        val live = Quote(symbol.code, "A50", BigDecimal("12524.000"), BigDecimal("0.5540"),
            "2026-11-26 12:21:38", "昨结算", BigDecimal("12455.000"), BigDecimal("12530.130"), true)
        book.add(symbol)
        book.pin(symbol.code)
        book.complete(book.reserve(time).single(), Result.success(live), time)
        val row = book.view().pinned!!
        for (text in listOf(QuoteText.market(row), QuoteText.status(row))) {
            assertTrue(text.contains("买一"))
            assertTrue(text.contains("12524"))
            assertTrue(text.contains("+0.55%"))
        }
        assertTrue(QuoteText.detail(row).contains("12530.130"))
        assertTrue(QuoteText.detail(row).contains("12455.000"))
        assertTrue(QuoteText.market(row.copy(quote = live.copy(percent = null))).contains("—"))
    }

    @Test fun oxideContractHoverRetainsSessionStatsAcrossRestart() {
        val symbol = Symbol.parse("nf_AO2702")!!
        val day = DayStats("2556".toBigDecimal(), "2588".toBigDecimal(), "2553".toBigDecimal(), "34742".toBigDecimal(), null)
        val oxide = Quote(symbol.code, "氧化铝2702", "2560.000".toBigDecimal(), "0.08".toBigDecimal(),
            "2026-11-26 11:30:00", "昨结算价", "2558".toBigDecimal(), "2560".toBigDecimal(), false, day)
        book.add(symbol)
        book.complete(book.reserve(time).single(), Result.success(oxide), time)
        val fresh = WatchBook { _, _ -> null }
        fresh.restore(book.save())
        val row = fresh.view().rows.single()
        assertEquals(oxide, row.quote)
        assertEquals("2560", QuoteText.price(row))
        val detail = QuoteText.detail(row)
        listOf("2588", "2553", "2556", "2558", "3.47万", "成交额", "2026-11-26 11:30:00").forEach { assertTrue(it in detail, it) }
    }

    @Test fun selectedSearchNameSurvivesFirstOfflineFetchAndRestart() {
        val symbol = Symbol.parse("nf_AO2702")!!
        book.add(symbol, "氧化铝2702")
        book.complete(book.reserve(time).single(), Result.failure(java.io.IOException("offline")), time)
        assertTrue(QuoteText.detail(book.view().rows.single()).contains("氧化铝2702"))
        val restored = WatchBook { _, _ -> null }
        restored.restore(book.save())
        assertEquals("氧化铝2702", restored.view().rows.single().displayName)
    }

    @Test fun exchangeClockReplayPollsOnlyGoldAfterRebarClosesAndKeepsItsTradingDay() {
        val watch = WatchBook(MarketSessions::interval)
        val rb = Symbol.parse("nf_RB0")!!
        val au = Symbol.parse("nf_AU0")!!
        watch.add(rb)
        watch.add(au)
        fun poll(local: String, vararg expected: String) {
            val now = java.time.OffsetDateTime.parse(local + "+08:00").toInstant()
            val batch = watch.reserve(now)
            assertEquals(expected.toList(), batch.map { it.symbol.code }, local)
            batch.forEach {
                assertEquals(java.time.LocalDate.of(2026, 11, 27), MarketSessions.tradingDay(it.symbol, now))
                watch.complete(it, Result.success(null), now)
            }
        }
        poll("2026-11-26T22:58:50", rb.code, au.code)
        poll("2026-11-26T22:59:50", rb.code, au.code)
        poll("2026-11-26T23:00:20", au.code)
        poll("2026-11-26T23:59:50", au.code)
        poll("2026-11-27T00:01:00", au.code)
        poll("2026-11-27T02:30:00")
    }
}

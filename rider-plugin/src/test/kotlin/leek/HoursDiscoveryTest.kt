package leek

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

class HoursDiscoveryTest {
    private val now = Instant.parse("2026-11-26T02:00:00Z")
    private fun at(local: String) = OffsetDateTime.parse(local + ":00+08:00").toInstant()

    @Test fun newlyAddedFuturesShowAPendingLookupWithoutManualInput() {
        val watch = WatchBook(MarketSessions::interval)
        for (code in listOf("nf_LC2705", "nf_AG2612", "nf_T2703")) {
            watch.add(Symbol.parse(code)!!)
        }
        assertEquals(List(3) { "查询时段中" }, watch.view().rows.map { QuoteText.refreshNote(it, compact = true) })
    }

    @Test fun onlineDirectoryAndContractMetadataDetermineHoursForAnyProduct() {
        val calls = mutableListOf<String>()
        val payloads = mapOf(
            "gnweb" to """[{"mktid":"225","mktshort":"GFEX"},{"mktid":"113","mktshort":"SHFE"},{"mktid":"220","mktshort":"CFFEX"}]""",
            "225" to """[{"vcode":"lc"},{"vcode":"new"}]""",
            "113" to """[{"vcode":"ag"}]""",
            "220" to """[{"vcode":"T"}]""",
            "225_lc_info" to """{"vcode":"lc","jysj":"上午9:00-11:30,下午13:30-15:00以及交易所规定的其他时间"}""",
            "225_new_info" to """{"vcode":"NEW","jysj":"上午9:00-11:30,下午1:30-3:00,下午21:00-23:00(夜盘)"}""",
            "113_ag_info" to """{"vcode":"AG","jysj":"上午9:00-11:30,下午13:30-15:00,下午21:00-次日2:30(夜盘)"}""",
            "220_t_info" to """{"vcode":"T","jysj":"上午9:30-11:30,下午13:00-15:15"}""",
        )
        val source = DomesticHoursSource { uri ->
            assertEquals("futsse-static.eastmoney.com", uri.host)
            val key = uri.query.substringAfter("msgid=")
            calls.add(key)
            payloads.getValue(key)
        }
        val day = "09:00-10:15, 10:30-11:30, 13:30-15:00"
        assertEquals(day, source.fetch("LC", now)?.text)
        assertEquals("$day, 21:00-23:00", source.fetch("NEW", now)?.text)
        assertEquals("$day, 21:00-02:30", source.fetch("AG", now)?.text)
        assertEquals("09:30-11:30, 13:00-15:15", source.fetch("T", now)?.text)
        assertNull(source.fetch("UNKNOWN", now))
        assertEquals(1, calls.count { it == "gnweb" })
        assertEquals(1, calls.count { it == "225" })
    }

    @Test fun discoveredHoursDriveRestoredAndNewContractsAndManualHoursTakePrecedence() {
        val watch = WatchBook(MarketSessions::interval)
        watch.restore(SavedWatchlist().apply { items.add(SavedItem().apply { code = "nf_LC2705" }) })
        watch.add(Symbol.parse("nf_LC0")!!)
        val lookup = watch.reserveHours(now)!!
        assertEquals("LC", lookup.product)
        assertNull(watch.reserveHours(now))
        val hours = DomesticRefreshHours.parse("09:00-10:15, 10:30-11:30, 13:30-15:00")!!
        watch.completeHours(lookup, hours, now)
        val restored = WatchBook(MarketSessions::interval)
        restored.restore(watch.save())
        assertNull(restored.reserveHours(now.plusSeconds(60)))
        fun poll(time: String, vararg codes: String) {
            val instant = at(time)
            val batch = restored.reserve(instant)
            assertEquals(codes.toList(), batch.map { it.symbol.code }, time)
            batch.forEach { restored.complete(it, Result.success(null), instant) }
        }
        poll("2026-11-26T10:14", "nf_LC2705", "nf_LC0")
        poll("2026-11-26T10:15")
        poll("2026-11-26T10:30", "nf_LC2705", "nf_LC0")
        assertTrue(restored.setRefreshHours("nf_LC0", "21:00-01:00"))
        poll("2026-11-26T21:00", "nf_LC0")
        assertTrue(restored.setRefreshHours("nf_LC0", ""))
        poll("2026-11-26T21:01")
        poll("2026-11-27T13:30", "nf_LC2705", "nf_LC0")
        poll("2026-11-28T13:30")
        val stale = restored.reserveHours(now.plus(Duration.ofDays(2)))!!
        restored.completeHours(stale, null, now.plus(Duration.ofDays(2)))
        assertTrue(restored.view().rows.all { it.marketHours?.text == hours.text })
        assertNull(restored.reserveHours(now.plus(Duration.ofDays(2)).plusSeconds(5)))
        assertNotNull(restored.reserveHours(now.plus(Duration.ofDays(2)).plusSeconds(301)))
    }

    @Test fun aDifferentProductOrAPartiallyReadableScheduleDoesNotEnablePolling() {
        var metadata = """{"vcode":"SI","jysj":"上午9:00-11:30"}"""
        val source = DomesticHoursSource { uri ->
            when (uri.query.substringAfter("msgid=")) {
                "gnweb" -> """[{"mktid":"225","mktshort":"GFEX"}]"""
                "225" -> """[{"vcode":"lc"}]"""
                "225_lc_info" -> metadata
                else -> error("Unexpected request $uri")
            }
        }
        assertNull(source.fetch("LC", now))
        metadata = """{"vcode":"lc","jysj":"上午9:00-11:30,下午13:30-?"}"""
        assertNull(source.fetch("LC", now))
        metadata = """{"vcode":"lc","jysj":"以交易所公告为准"}"""
        assertNull(source.fetch("LC", now))
    }

    @Test fun failedLookupsRetryWithoutAQuoteRefreshAndLateResultsCannotReplaceANewWatch() {
        val watch = WatchBook(MarketSessions::interval)
        val symbol = Symbol.parse("nf_LC2705")!!
        watch.add(symbol)
        val removed = watch.reserveHours(now)!!
        watch.remove(symbol.code)
        watch.add(symbol)
        val current = watch.reserveHours(now)!!
        watch.completeHours(removed, DomesticRefreshHours.parse("21:00-23:00"), now)
        assertNull(watch.view().rows.single().marketHours)
        watch.completeHours(current, null, now)
        assertEquals("时段待重试", QuoteText.refreshNote(watch.view().rows.single(), compact = true))
        assertNull(watch.reserveHours(now.plusSeconds(299)))
        assertNotNull(watch.reserveHours(now.plusSeconds(300)))
    }
}

package leek

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

class SessionTest {
    private fun at(chinaTime: String) = OffsetDateTime.parse(chinaTime + ":00+08:00").toInstant()

    private val rb = Symbol("nf_RB2701", Group.DOMESTIC)
    private val au = Symbol("nf_AU0", Group.DOMESTIC)
    private val index = Symbol("nf_IF0", Group.DOMESTIC)
    private val a = Symbol("sh600000", Group.STOCK)
    private val hongKong = Symbol("hk00700", Group.STOCK)
    private val oil = Symbol("hf_OIL", Group.OVERSEAS)
    private val a50 = Symbol("hf_CHA50CFD", Group.OVERSEAS)

    private fun day(sym: Symbol, time: String, expected: String?) {
        val instant = at(time)
        assertEquals(expected?.let(LocalDate::parse), MarketSessions.tradingDay(sym, instant), "${sym.code} at $time")
        assertEquals(expected != null, MarketSessions.interval(sym, instant) != null, "poll ${sym.code} at $time")
    }

    @Test fun shfeNightTradingDaySurvivesMidnightAndWeekend() {
        day(rb, "2026-11-26T22:59", "2026-11-27")
        day(rb, "2026-11-26T23:00", null)
        day(au, "2026-11-26T23:59", "2026-11-27")
        day(au, "2026-11-27T00:01", "2026-11-27")
        day(au, "2026-11-27T02:30", null)
        day(au, "2026-11-27T23:59", "2026-11-30")
        day(au, "2026-11-28T00:01", "2026-11-30")
        day(au, "2026-11-30T00:01", null)
    }

    @Test fun mainlandBreaksAndHolidayEves() {
        day(rb, "2026-11-26T10:15", null)
        day(rb, "2026-11-26T10:30", "2026-11-26")
        day(index, "2026-11-26T13:05", "2026-11-26")
        day(index, "2026-11-26T21:30", null)
        day(au, "2026-02-13T21:00", null)
        day(au, "2026-02-14T00:01", null)
        day(au, "2026-02-24T00:01", null)
        day(rb, "2026-09-30T21:00", null)
        day(a, "2026-10-10T10:00", null)
        day(a, "2026-11-26T09:20", "2026-11-26")
        day(a, "2026-11-26T11:30", null)
        day(a, "2026-11-26T15:30", null)
    }

    @Test fun aluminaClosesAfterRebarAndBeforeGold() {
        for (code in listOf("nf_AO0", "nf_AO2702")) {
            val ao = Symbol(code, Group.DOMESTIC)
            assertNull(MarketSessions.manualOnlyReason(ao, at("2026-11-26T13:30")))
            day(ao, "2026-11-26T13:30", "2026-11-26")
            day(ao, "2026-11-26T23:59", "2026-11-27")
            day(ao, "2026-11-27T00:01", "2026-11-27")
            day(ao, "2026-11-27T00:59", "2026-11-27")
            day(ao, "2026-11-27T01:00", null)
            day(ao, "2026-11-28T00:59", "2026-11-30")
            day(ao, "2026-09-30T23:59", null)
        }
        day(rb, "2026-11-26T23:59", null)
        day(au, "2026-11-27T01:00", "2026-11-27")
    }

    @Test fun hongKongCashSharesUseLocalLunchHalfDayAndHolidays() {
        day(hongKong, "2026-02-16T12:05", "2026-02-16")
        day(hongKong, "2026-02-16T13:00", null)
        day(hongKong, "2026-11-26T12:05", null)
        day(hongKong, "2026-11-26T16:08", "2026-11-26")
        day(hongKong, "2026-12-25T10:00", null)
        day(hongKong, "2027-02-05T13:05", null)
        day(hongKong, "2027-02-08T10:00", null)
    }

    @Test fun mainlandAfterHoursUsesEffectiveDateAndBoard() {
        day(a, "2026-07-03T15:10", null)
        day(a, "2026-07-06T15:10", "2026-07-06")
        day(a, "2026-11-26T15:02", null)
        day(a, "2026-11-26T15:29", "2026-11-26")
        day(a, "2026-11-26T15:30", null)
        day(Symbol("sz300750", Group.STOCK), "2026-07-03T15:10", "2026-07-03")
        day(Symbol("bj920002", Group.STOCK), "2026-11-26T10:30", "2026-11-26")
        day(Symbol("bj920002", Group.STOCK), "2026-11-26T15:10", null)
    }

    @Test fun sourceA50ReopensAtSeventeenAndTradesOnMainlandHolidays() {
        day(a50, "2026-10-01T10:30", "2026-10-01")
        day(a50, "2026-11-26T16:34", "2026-11-26")
        day(a50, "2026-11-26T16:45", null)
        day(a50, "2026-11-26T17:00", "2026-11-27")
        day(a50, "2026-11-27T00:01", "2026-11-27")
        day(a50, "2026-11-28T05:14", "2026-11-30")
        day(a50, "2026-11-28T05:15", null)
        day(a50, "2026-01-01T10:00", null)
    }

    @Test fun brentDstAndSundayOpeningUseUsEasternClock() {
        day(oil, "2026-11-23T06:59", null)
        day(oil, "2026-11-23T07:00", "2026-11-23")
        day(oil, "2026-11-24T08:59", null)
        day(oil, "2026-11-24T09:00", "2026-11-24")
        day(oil, "2026-10-26T06:00", "2026-10-26")
        day(oil, "2026-10-27T07:59", null)
        day(oil, "2026-10-27T08:00", "2026-10-27")
    }

    @Test fun iceHolidayCloseOverridesRegularSourceHours() {
        day(oil, "2026-11-26T15:30", "2026-11-26")
        day(oil, "2026-11-27T02:29", "2026-11-26")
        day(oil, "2026-11-27T02:30", null)
        day(oil, "2026-11-28T03:59", "2026-11-27")
        day(oil, "2026-11-28T04:00", null)
        day(oil, "2026-12-25T03:00", null)
        day(oil, "2026-12-25T20:00", null)
        day(oil, "2026-04-03T15:00", null)
    }

    @Test fun disclosureCadenceAndUnverifiedContracts() {
        val now = at("2026-11-26T20:00")
        val fund = MarketSessions.interval(Symbol("000001", Group.FUND), now)
        assertNotNull(fund)
        assertTrue(fund!! >= Duration.ofMinutes(30))
        assertTrue(MarketSessions.interval(a50, now)!! <= Duration.ofSeconds(30))
        day(Symbol("nf_XY0", Group.DOMESTIC), "2026-11-26T10:00", null)
        day(Symbol("hf_UNKNOWN", Group.OVERSEAS), "2026-11-26T10:00", null)
        day(rb, "2030-01-07T10:00", null)
        day(oil, "2030-01-07T10:00", null)
    }

    @Test fun brentYearEndFollowsTheOutrightContractInAppendixTwo() {
        day(oil, "2027-12-24T20:59", "2027-12-24")
        day(oil, "2027-12-24T21:00", null)
        day(oil, "2027-12-27T07:00", "2027-12-27")
        day(oil, "2027-12-28T06:59", "2027-12-27")
        day(oil, "2027-12-28T09:00", "2027-12-28")
        day(oil, "2028-01-01T03:59", "2027-12-31")
        day(oil, "2028-01-01T04:00", null)
        day(oil, "2028-01-03T07:00", "2028-01-03")
        day(oil, "2028-01-04T09:00", null)
    }

    @Test fun manualOnlyReasonDistinguishesClosedFromMissingCalendar() {
        fun reason(s: Symbol, time: String) = MarketSessions.manualOnlyReason(s, at(time))
        assertNull(reason(a, "2026-11-26T20:00"))
        assertNull(reason(rb, "2026-09-30T21:00"))
        assertNull(reason(a50, "2026-11-28T14:00"))
        assertNull(reason(oil, "2027-12-24T21:01"))
        assertNull(reason(oil, "2028-01-01T03:59"))
        assertNull(reason(Symbol("000001", Group.FUND), "2030-01-07T10:00"))
        assertNotNull(reason(Symbol("nf_XY0", Group.DOMESTIC), "2026-11-26T10:00"))
        assertNotNull(reason(Symbol("hf_UNKNOWN", Group.OVERSEAS), "2026-11-26T10:00"))
        assertNotNull(reason(rb, "2026-12-31T21:00"))
        assertNotNull(reason(a50, "2026-12-31T17:00"))
        assertNotNull(reason(a, "2027-01-04T10:00"))
        assertNotNull(reason(oil, "2028-01-04T09:00"))
    }
}

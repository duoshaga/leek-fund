package leek

import java.time.DayOfWeek.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Exchange sessions and vendor quote windows. References and calendar horizons are in acceptance/calendar. */
object MarketSessions {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val hongKong = ZoneId.of("Asia/Hong_Kong")
    private val singapore = ZoneId.of("Asia/Singapore")
    private val newYork = ZoneId.of("America/New_York")
    private val london = ZoneId.of("Europe/London")
    private val quoteInterval = Duration.ofSeconds(25)
    private val estimateInterval = Duration.ofMinutes(1)
    private val navEveningInterval = Duration.ofHours(1)
    private val navDaytimeInterval = Duration.ofHours(6)
    private val mainlandDates = LocalDate.of(2026, 1, 1)..LocalDate.of(2026, 12, 31)
    private val hkDates = LocalDate.of(2026, 1, 1)..LocalDate.of(2027, 12, 31)
    private val sgxDates = LocalDate.of(2026, 1, 1)..LocalDate.of(2026, 12, 31)
    private val iceDates = LocalDate.of(2026, 1, 1)..LocalDate.of(2028, 1, 3)

    private fun dates(year: Int, monthDays: String) =
        monthDays.split(" ").mapTo(mutableSetOf()) { LocalDate.parse("$year-$it") }

    // Weekday closures only. A compensating working Saturday or Sunday is still an exchange holiday.
    private val mainlandHolidays = dates(2026,
        "01-01 01-02 02-16 02-17 02-18 02-19 02-20 02-23 04-06 05-01 05-04 05-05 06-19 09-25 10-01 10-02 10-05 10-06 10-07")
    private val hkHolidays = dates(2026,
        "01-01 02-17 02-18 02-19 04-03 04-06 04-07 05-01 05-25 06-19 07-01 10-01 10-19 12-25") +
        dates(2027, "01-01 02-08 02-09 03-26 03-29 04-05 05-13 06-09 07-01 09-16 10-01 10-08 12-27")
    private val hkHalfDays = dates(2026, "02-16 12-24 12-31") + dates(2027, "02-05 12-24 12-31")
    private val extendedAfterHours = LocalDate.of(2026, 7, 6)

    private val iceHolidays = dates(2026, "01-01 04-03 12-25") + dates(2027, "01-01 03-26")
    private val iceEarly1830 = dates(2026, "05-25 06-19 07-03 09-07 11-26") +
        dates(2027, "05-31 06-18 07-05 09-06 11-25")
    private val iceEarly2000 = dates(2026, "11-27 12-31") + dates(2027, "11-26 12-31")
    private val iceEarly1900 = LocalDate.of(2026, 12, 24)
    private val iceEarly1300 = LocalDate.of(2027, 12, 24)
    private fun commodityHours(nightClose: String) = requireNotNull(
        DomesticRefreshHours.parse("09:00-10:15, 10:30-11:30, 13:30-15:00, 21:00-$nightClose"))
    private val until2300 = commodityHours("23:00")
    private val domesticHours = mapOf(
        "IF" to requireNotNull(DomesticRefreshHours.parse("09:30-11:30, 13:00-15:00")),
        "RB" to until2300, "FG" to until2300, "AO" to commodityHours("01:00"), "AU" to commodityHours("02:30"),
    )

    private fun mainlandCode(code: String) = code.startsWith("sh") || code.startsWith("sz") || code.startsWith("bj")
    internal fun builtInHours(symbol: Symbol) = domesticHours[symbol.domesticProduct]
    private fun iceTradeDate(eastern: ZonedDateTime) =
        eastern.toLocalDate().plusDays(if (eastern.hour >= 18) 1 else 0)

    /** For unlisted quotes this is the disclosure-check date. The feed supplies the actual valuation date. */
    fun tradingDay(symbol: Symbol, instant: Instant): LocalDate? {
        if (symbol.group == Group.OVERSEAS && symbol.code == "hf_OIL") return brentDay(instant)
        val zone = when {
            symbol.group == Group.OVERSEAS -> singapore
            symbol.group == Group.STOCK && symbol.code.startsWith("hk") -> hongKong
            else -> shanghai
        }
        val local = instant.atZone(zone)
        val date = local.toLocalDate()
        val hhmm = local.hour * 100 + local.minute
        return when (symbol.group) {
            Group.FUND -> if (symbol.etfMarketCode != null) mainlandStockDay(symbol.etfMarketCode, date, hhmm) else date
            Group.STOCK -> when {
                symbol.code.startsWith("hk") -> hkDay(date, hhmm)
                mainlandCode(symbol.code) -> mainlandStockDay(symbol.code, date, hhmm)
                else -> null
            }
            Group.DOMESTIC -> builtInHours(symbol)?.let { domesticDay(it, date, hhmm, ::mainlandDay) }
            Group.OVERSEAS -> if (symbol.code == "hf_CHA50CFD") a50Day(date, hhmm) else null
        }
    }

    /** Null also covers a *scheduled* holiday or break; these symbols resume automatically when they reopen. */
    @JvmOverloads
    fun manualOnlyReason(symbol: Symbol, instant: Instant, marketHours: DomesticRefreshHours? = null): String? {
        val unknown = "该品种的交易时段尚未核实，可手动刷新"
        fun checkDate(date: LocalDate, covered: ClosedRange<LocalDate>, market: String) =
            if (date in covered) null else "${market}交易日历已核实至 ${covered.endInclusive}，当前日期可手动刷新"

        val local = instant.atZone(shanghai)
        val date = local.toLocalDate()
        return when (symbol.group) {
            Group.FUND -> if (symbol.etfMarketCode != null) checkDate(date, mainlandDates, "内地") else null
            Group.STOCK -> when {
                symbol.code.startsWith("hk") -> checkDate(instant.atZone(hongKong).toLocalDate(), hkDates, "港股")
                mainlandCode(symbol.code) -> checkDate(date, mainlandDates, "内地")
                else -> unknown
            }
            Group.DOMESTIC -> {
                val hours = marketHours ?: builtInHours(symbol) ?: return unknown
                val evening = hours.openingEvening(date, local.hour * 100 + local.minute)
                val sessionDate = if (evening != null && mainlandDay(evening)) nextWeekday(evening) else date
                checkDate(sessionDate, mainlandDates, "内地期货")?.plus("。可点击时钟设置自动刷新时段。")
            }
            Group.OVERSEAS -> when (symbol.code) {
                "hf_OIL" -> checkDate(iceTradeDate(instant.atZone(newYork)), iceDates, "ICE")
                "hf_CHA50CFD" -> {
                    val sg = instant.atZone(singapore)
                    val sgDate = sg.toLocalDate()
                    val sessionDate = if (sg.hour >= 17 && weekday(sgDate)) nextWeekday(sgDate) else sgDate
                    checkDate(sessionDate, sgxDates, "SGX")
                }
                else -> unknown
            }
        }
    }

    fun interval(symbol: Symbol, instant: Instant): Duration? {
        if (symbol.group == Group.FUND) {
            val local = instant.atZone(shanghai)
            val hhmm = local.hour * 100 + local.minute
            val openDay = mainlandDay(local.toLocalDate())
            if (symbol.etfMarketCode == null) {
                if (openDay && mainlandCashHours(hhmm)) return estimateInterval
                return if (local.hour >= 18) navEveningInterval else navDaytimeInterval
            }
            // Allow the final exchange print to reach the feed at each session close.
            if (openDay && (hhmm == 1130 || hhmm == 1500)) return quoteInterval
        }
        return tradingDay(symbol, instant)?.let { quoteInterval }
    }

    fun interval(row: WatchRow, instant: Instant): Duration? {
        val hours = row.refreshHours ?: row.marketHours ?: return interval(row.symbol, instant)
        val local = instant.atZone(shanghai)
        val isTradingDate = if (row.refreshHours != null) ::mainlandWeekday else ::mainlandDay
        return domesticDay(hours, local.toLocalDate(), local.hour * 100 + local.minute, isTradingDate)?.let { quoteInterval }
    }

    private fun weekday(date: LocalDate) = date.dayOfWeek != SATURDAY && date.dayOfWeek != SUNDAY
    private fun nextWeekday(date: LocalDate) = date.plusDays(when (date.dayOfWeek) {
        FRIDAY -> 3L
        SATURDAY -> 2L
        else -> 1L
    })

    private fun mainlandWeekday(date: LocalDate) = weekday(date) && date !in mainlandHolidays
    private fun mainlandDay(date: LocalDate) = date in mainlandDates && mainlandWeekday(date)
    private fun mainlandCashHours(hhmm: Int) = hhmm in 930..<1130 || hhmm in 1300..<1500

    private fun mainlandStockDay(code: String, date: LocalDate, hhmm: Int): LocalDate? {
        if (!mainlandDay(date)) return null
        if (hhmm in 915..<925 || mainlandCashHours(hhmm)) return date
        if (hhmm !in 1505..<1530) return null
        val starOrChiNext = code.startsWith("sh688") || code.startsWith("sh689") || code.startsWith("sz3")
        val mainBoard = code.startsWith("sh6") || code.startsWith("sz0")
        return date.takeIf { starOrChiNext || (mainBoard && date >= extendedAfterHours) }
    }

    private fun hkDay(date: LocalDate, hhmm: Int): LocalDate? {
        if (date !in hkDates || !weekday(date) || date in hkHolidays) return null
        if (date in hkHalfDays) return date.takeIf { hhmm in 900..<1210 }
        return date.takeIf { hhmm in 900..<1200 || hhmm in 1300..<1610 }
    }

    private fun domesticDay(hours: DomesticRefreshHours, date: LocalDate, hhmm: Int, isTradingDate: (LocalDate) -> Boolean): LocalDate? {
        if (hours.includesDay(hhmm)) return date.takeIf(isTradingDate)
        val evening = hours.openingEvening(date, hhmm) ?: return null
        // Friday night belongs to Monday. No night session precedes a weekday holiday.
        return nextWeekday(evening).takeIf { isTradingDate(evening) && isTradingDate(it) }
    }

    private fun a50Day(date: LocalDate, hhmm: Int): LocalDate? {
        fun openDate(d: LocalDate) = d in sgxDates && weekday(d) && d.dayOfYear != 1
        if (hhmm in 900..<1635) return date.takeIf(::openDate)
        // Sina's source window starts at 17:00 even though SGX's T+1 session starts at 16:45.
        val evening = when {
            hhmm >= 1700 -> date
            hhmm < 515 -> date.minusDays(1)
            else -> return null
        }
        return nextWeekday(evening).takeIf { openDate(evening) && openDate(it) }
    }

    private fun brentDay(instant: Instant): LocalDate? {
        // ICE aligns its maintenance window with New York, including UK/US DST mismatch weeks.
        val eastern = instant.atZone(newYork)
        val tradeDate = iceTradeDate(eastern)
        if (tradeDate !in iceDates || !weekday(tradeDate) || tradeDate in iceHolidays) return null
        val opensAt = if (tradeDate.dayOfWeek == MONDAY) LocalTime.of(18, 0) else LocalTime.of(20, 0)
        val start = tradeDate.minusDays(1).atTime(opensAt).atZone(newYork).toInstant()
        val specialClose = when (tradeDate) {
            in iceEarly1830 -> LocalTime.of(18, 30)
            in iceEarly2000 -> LocalTime.of(20, 0)
            iceEarly1900 -> LocalTime.of(19, 0)
            iceEarly1300 -> LocalTime.of(13, 0)
            else -> null
        }
        val end = specialClose?.let { tradeDate.atTime(it).atZone(london) }
            ?: tradeDate.atTime(18, 0).atZone(newYork)
        return tradeDate.takeIf { !instant.isBefore(start) && instant.isBefore(end.toInstant()) }
    }
}

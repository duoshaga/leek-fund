package leek

import java.time.Duration
import java.time.Instant

enum class QuoteState { NEW, CURRENT, CACHED, FAILED, EMPTY }

enum class WatchGroup(val title: String) {
    MAINLAND("A 股"), HONG_KONG("港股"), FUND("基金"), DOMESTIC("国内期货"), OVERSEAS("海外期货")
}

internal val Symbol.watchGroup: WatchGroup get() = when (group) {
    Group.STOCK -> if (code.startsWith("hk")) WatchGroup.HONG_KONG else WatchGroup.MAINLAND
    Group.FUND -> WatchGroup.FUND
    Group.DOMESTIC -> WatchGroup.DOMESTIC
    Group.OVERSEAS -> WatchGroup.OVERSEAS
}

data class WatchRow(val symbol: Symbol, val quote: Quote?, val state: QuoteState, val received: Instant?,
                    val displayName: String? = null, val refreshHours: DomesticRefreshHours? = null,
                    val marketHours: DomesticRefreshHours? = null, val hoursLookupFailed: Boolean = false)
data class WatchView(val rows: List<WatchRow>, val pin: String?, val groupOrder: List<WatchGroup>) {
    val pinned: WatchRow? get() = rows.firstOrNull { it.symbol.code == pin }
}

internal data class Request(val symbol: Symbol, val id: Long)
internal data class HoursRequest(val product: String, val id: Long)

/** A reservation belongs to a particular list entry. Removing and re-adding the same code
 * while its HTTP request is in flight cannot deliver that old response to the new entry. */
internal class WatchBook(private val interval: (WatchRow, Instant) -> Duration?) {
    private class Entry(var row: WatchRow) {
        var requested = false
        var active: Long? = null
        var lastAttempt: Instant? = null
    }

    private var entries = linkedMapOf<String, Entry>()
    private class ProductSchedule {
        var hours: DomesticRefreshHours? = null
        var fetchedAt: Instant? = null
        var lastAttempt: Instant? = null
        var failed = false
        var active: Long? = null
    }
    private val schedules = linkedMapOf<String, ProductSchedule>()
    private val groupOrder = WatchGroup.entries.toMutableList()
    private var pin: String? = null
    private var sequence = 0L

    private fun withSchedule(row: WatchRow): WatchRow {
        val schedule = schedules[row.symbol.domesticProduct] ?: return row
        if (schedule.hours == null && !schedule.failed) return row
        return row.copy(marketHours = schedule.hours, hoursLookupFailed = schedule.failed)
    }

    @Synchronized fun view() = WatchView(entries.values.map { withSchedule(it.row) }, pin, groupOrder.toList())

    @Synchronized fun add(symbol: Symbol, displayName: String? = null): Boolean {
        if (symbol.code in entries) return false
        entries[symbol.code] = Entry(WatchRow(symbol, null, QuoteState.NEW, null, displayName)).apply { requested = true }
        symbol.domesticProduct?.let { schedules.getOrPut(it, ::ProductSchedule) }
        return true
    }

    @Synchronized fun remove(code: String) {
        val product = entries.remove(code)?.row?.symbol?.domesticProduct
        if (product != null && entries.values.none { it.row.symbol.domesticProduct == product }) schedules.remove(product)
        if (pin == code) pin = null
    }

    @Synchronized fun setRefreshHours(code: String, text: String): Boolean {
        val entry = entries[code] ?: return false
        if (entry.row.symbol.group != Group.DOMESTIC) return false
        val hours = if (text.isBlank()) null else DomesticRefreshHours.parse(text) ?: return false
        entry.row = entry.row.copy(refreshHours = hours)
        return true
    }

    @Synchronized fun moveGroup(group: WatchGroup, anchor: WatchGroup, after: Boolean) =
        groupOrder.moveRelativeTo(group, anchor, after)

    @Synchronized fun moveSymbol(code: String, anchorCode: String, after: Boolean): Boolean {
        val source = entries[code] ?: return false
        val anchor = entries[anchorCode] ?: return false
        val group = source.row.symbol.watchGroup
        val siblings = entries.values.filter { it.row.symbol.watchGroup == group }.toMutableList()
        if (!siblings.moveRelativeTo(source, anchor, after)) return false
        // Only replace this group's slots. The Entry objects retain their in-flight quote requests.
        val reordered = siblings.iterator()
        entries = entries.values.map { if (it.row.symbol.watchGroup == group) reordered.next() else it }
            .associateByTo(linkedMapOf()) { it.row.symbol.code }
        return true
    }

    @Synchronized fun pin(code: String?): Boolean {
        if (code != null && code !in entries) return false
        pin = code
        return true
    }

    @Synchronized fun requestAll() {
        entries.values.filter { it.active == null }.forEach { it.requested = true }
        schedules.values.filter { it.failed }.forEach { it.lastAttempt = null }
    }

    @Synchronized fun reserveHours(now: Instant): HoursRequest? {
        for ((product, schedule) in schedules) {
            if (schedule.active != null) continue
            val last = schedule.lastAttempt
            val ttl = if (schedule.failed) HOURS_RETRY else HOURS_TTL
            if (last != null && now >= last && Duration.between(last, now) < ttl) continue
            val request = HoursRequest(product, ++sequence)
            schedule.active = request.id
            return request
        }
        return null
    }

    @Synchronized fun completeHours(request: HoursRequest, hours: DomesticRefreshHours?, now: Instant) {
        val schedule = schedules[request.product] ?: return
        if (schedule.active != request.id) return
        schedule.active = null
        schedule.lastAttempt = now
        schedule.failed = hours == null
        if (hours != null) {
            schedule.hours = hours
            schedule.fetchedAt = now
        }
    }

    @Synchronized fun reserve(now: Instant): List<Request> = entries.values.mapNotNull { entry ->
        if (entry.active != null) return@mapNotNull null
        val symbol = entry.row.symbol
        if (!entry.requested) {
            val cadence = interval(withSchedule(entry.row), now) ?: return@mapNotNull null
            val last = entry.lastAttempt
            if (last != null && now >= last && now < last.plus(cadence)) return@mapNotNull null
        }
        entry.requested = false
        entry.active = ++sequence
        Request(symbol, sequence)
    }

    @Synchronized fun complete(request: Request, result: Result<Quote?>, now: Instant) {
        val entry = entries[request.symbol.code] ?: return
        if (entry.active != request.id) return
        entry.active = null
        entry.lastAttempt = now
        entry.row = result.fold(
            onSuccess = { quote ->
                val state = if (quote == null) QuoteState.EMPTY else QuoteState.CURRENT
                if (quote != null && quote.isOlderFundValueThan(entry.row.quote)) {
                    entry.row.copy(state = QuoteState.CACHED)
                } else entry.row.copy(quote = quote, state = state, received = now)
            },
            onFailure = { entry.row.copy(state = QuoteState.FAILED) }
        )
    }

    @Synchronized fun save() = SavedWatchlist().also { saved ->
        saved.pinnedCode = pin
        saved.groupOrder = groupOrder.map { it.name }.toMutableList()
        saved.items = entries.values.map { SavedItem.from(it.row) }.toMutableList()
        saved.marketSchedules = schedules.mapNotNull { (product, schedule) ->
            val hours = schedule.hours ?: return@mapNotNull null
            SavedMarketSchedule().also {
                it.product = product
                it.hours = hours.text
                it.fetchedAt = schedule.fetchedAt.toString()
            }
        }.toMutableList()
    }

    @Synchronized fun restore(saved: SavedWatchlist) {
        entries.clear()
        schedules.clear()
        groupOrder.clear()
        val known = WatchGroup.entries.associateBy { it.name }
        groupOrder.addAll((saved.groupOrder.mapNotNull(known::get) + WatchGroup.entries).distinct())
        for (item in saved.items) {
            val row = item.toRow() ?: continue
            val existing = entries[row.symbol.code]
            if (existing == null || (existing.row.quote == null && row.quote != null)) {
                entries[row.symbol.code] = Entry(row).apply {
                    // Pick up a closing trade even if Rider reopens during lunch or after hours.
                    requested = row.symbol.group == Group.FUND
                }
            }
            row.symbol.domesticProduct?.let { schedules.getOrPut(it, ::ProductSchedule) }
        }
        for (item in saved.marketSchedules) {
            val schedule = schedules[item.product] ?: continue
            val hours = DomesticRefreshHours.parse(item.hours) ?: continue
            val at = runCatching { Instant.parse(item.fetchedAt) }.getOrNull() ?: continue
            schedule.hours = hours
            schedule.fetchedAt = at
            schedule.lastAttempt = at
        }
        pin = saved.pinnedCode?.let { Symbol.parse(it)?.code }?.takeIf { it in entries }
    }

    private companion object {
        val HOURS_RETRY = Duration.ofMinutes(5)
        val HOURS_TTL = Duration.ofDays(1)
    }
}

private fun Quote.isOlderFundValueThan(previous: Quote?): Boolean {
    if (kind == QuoteKind.MARKET || previous == null || previous.kind == QuoteKind.MARKET) return false
    val date = sourceTime.substringBefore(' ')
    val previousDate = previous.sourceTime.substringBefore(' ')
    if (date != previousDate) return date < previousDate
    if (kind != previous.kind) return kind == QuoteKind.ESTIMATE
    return sourceTime < previous.sourceTime
}

private fun <T> MutableList<T>.moveRelativeTo(item: T, anchor: T, after: Boolean): Boolean {
    val from = indexOf(item)
    val target = indexOf(anchor)
    if (from < 0 || target < 0) return false
    val insert = if (after) target + 1 else target
    if (insert == from || insert == from + 1) return false
    removeAt(from)
    add(if (insert > from) insert - 1 else insert, item)
    return true
}

// Plain beans are the on-disk contract for IntelliJ's XML serializer.
class SavedWatchlist {
    var items: MutableList<SavedItem> = mutableListOf()
    var pinnedCode: String? = null
    var groupOrder: MutableList<String> = mutableListOf()
    var marketSchedules: MutableList<SavedMarketSchedule> = mutableListOf()
}

class SavedMarketSchedule {
    var product = ""
    var hours = ""
    var fetchedAt = ""
}

class SavedItem {
    var code = ""
    var displayName: String? = null
    var refreshHours: String? = null
    var name: String? = null
    var price: String? = null
    var percent: String? = null
    var time = ""
    var basisLabel = ""
    var basis: String? = null
    var trade: String? = null
    var bid = false
    var quoteKind: QuoteKind? = null
    var receivedAt: String? = null
    var dayOpen: String? = null
    var dayHigh: String? = null
    var dayLow: String? = null
    var volume: String? = null
    var turnover: String? = null

    fun toRow(): WatchRow? {
        val symbol = Symbol.parse(code) ?: return null
        // Before 1.0.7 bare fund codes cached NAV, while qualified codes cached exchange prices.
        val kind = quoteKind ?: if (symbol.group == Group.FUND && code == symbol.code) QuoteKind.NAV else QuoteKind.MARKET
        val obsoleteNav = symbol.etfMarketCode != null && kind == QuoteKind.NAV
        val quote = name?.takeUnless { obsoleteNav }?.let {
            val day = if (listOf(dayOpen, dayHigh, dayLow, volume, turnover).all { value -> value == null }) null
                else DayStats(dayOpen?.toBigDecimalOrNull(), dayHigh?.toBigDecimalOrNull(), dayLow?.toBigDecimalOrNull(),
                    volume?.toBigDecimalOrNull(), turnover?.toBigDecimalOrNull())
            Quote(symbol.code, it, price?.toBigDecimalOrNull(), percent?.toBigDecimalOrNull(),
                time, basisLabel, basis?.toBigDecimalOrNull(), trade?.toBigDecimalOrNull(), bid, day, kind)
        }
        val received = receivedAt?.takeUnless { obsoleteNav }?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val state = when {
            quote != null -> QuoteState.CACHED
            received != null -> QuoteState.EMPTY
            else -> QuoteState.NEW
        }
        val hours = if (symbol.group == Group.DOMESTIC) refreshHours?.let(DomesticRefreshHours::parse) else null
        return WatchRow(symbol, quote, state, received, displayName ?: name, hours)
    }

    companion object {
        fun from(row: WatchRow) = SavedItem().apply {
            code = row.symbol.code
            displayName = row.displayName
            refreshHours = row.refreshHours?.text
            receivedAt = row.received?.toString()
            row.quote?.let {
                name = it.name
                price = it.price?.toPlainString()
                percent = it.percent?.toPlainString()
                time = it.sourceTime
                basisLabel = it.basisLabel
                basis = it.basis?.toPlainString()
                trade = it.rawTrade?.toPlainString()
                bid = it.bidFallback
                quoteKind = it.kind
                dayOpen = it.day?.open?.toPlainString()
                dayHigh = it.day?.high?.toPlainString()
                dayLow = it.day?.low?.toPlainString()
                volume = it.day?.volume?.toPlainString()
                turnover = it.day?.turnover?.toPlainString()
            }
        }
    }
}

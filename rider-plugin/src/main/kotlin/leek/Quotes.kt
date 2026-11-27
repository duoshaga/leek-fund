package leek

import java.math.BigDecimal
import java.util.Locale

enum class Group { STOCK, FUND, DOMESTIC, OVERSEAS }

data class Symbol(val code: String, val group: Group) {
    internal val domesticProduct: String? get() =
        if (group == Group.DOMESTIC) code.removePrefix("nf_").takeWhile { it in 'A'..'Z' } else null

    internal val etfMarketCode: String? = if (group == Group.FUND) etfMarket(code)?.plus(code) else null

    companion object {
        private val mainland = Regex("(?:sh|sz|bj)[0-9]{6}")
        private val hongKong = Regex("hk[0-9]{5}")
        private val fund = Regex("[0-9]{6}")
        private val domestic = Regex("nf_[a-z]{1,5}[0-9]{1,4}")
        private val overseas = Regex("hf_[a-z0-9]{1,24}")

        // SSE Securities Code Allocation Guide (2026 revision 3), SZSE ETF ranges 158/159.
        // In particular, 519 is an open-end fund range and 16 includes LOFs.
        private fun etfMarket(code: String): String? = when (code.toIntOrNull()) {
            in 158000..159999 -> if (code == "159900") null else "sz" // redemption cash substitute
            in 510000..513999, in 515000..518999, in 520500..520999, in 526000..526999,
            in 530000..530999, in 551000..551999, in 560000..563999, in 565000..567999,
            in 581000..581999, in 587000..589999 -> "sh"
            else -> null
        }

        fun parse(input: String): Symbol? {
            val code = input.trim().lowercase(Locale.ROOT)
            return when {
                mainland.matches(code) -> {
                    val number = code.drop(2)
                    if (etfMarket(number) == code.take(2)) Symbol(number, Group.FUND) else Symbol(code, Group.STOCK)
                }
                hongKong.matches(code) -> Symbol(code, Group.STOCK)
                fund.matches(code) -> Symbol(code, Group.FUND)
                domestic.matches(code) -> Symbol("nf_" + code.substring(3).uppercase(Locale.ROOT), Group.DOMESTIC)
                overseas.matches(code) -> Symbol("hf_" + code.substring(3).uppercase(Locale.ROOT), Group.OVERSEAS)
                else -> null
            }
        }
    }
}

data class DayStats(
    val open: BigDecimal? = null,
    val high: BigDecimal? = null,
    val low: BigDecimal? = null,
    val volume: BigDecimal? = null,
    val turnover: BigDecimal? = null,
)

enum class QuoteKind(val priceLabel: String, val timeLabel: String) {
    MARKET("", "报价时间"), ESTIMATE("估值", "估值时间"), NAV("净值", "净值日期"), INCOME("每万份收益", "收益日期")
}

data class Quote(
    val code: String,
    val name: String,
    val price: BigDecimal?,
    val percent: BigDecimal?,
    val sourceTime: String,
    val basisLabel: String,
    val basis: BigDecimal?,
    val rawTrade: BigDecimal?,
    val bidFallback: Boolean,
    val day: DayStats? = null,
    val kind: QuoteKind = QuoteKind.MARKET,
)

interface QuoteSource {
    /** A successful null is an explicit empty response. A failed result lets callers retain their last quote. */
    fun fetch(symbols: List<Symbol>): Map<String, Result<Quote?>>
}

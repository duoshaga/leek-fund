package leek

import java.math.BigDecimal
import java.util.Locale

enum class Group { STOCK, FUND, DOMESTIC, OVERSEAS }

data class Symbol(val code: String, val group: Group) {
    internal val domesticProduct: String? get() =
        if (group == Group.DOMESTIC) code.removePrefix("nf_").takeWhile { it in 'A'..'Z' } else null

    companion object {
        private val mainland = Regex("(?:sh|sz|bj)[0-9]{6}")
        private val hongKong = Regex("hk[0-9]{5}")
        private val fund = Regex("[0-9]{6}")
        private val domestic = Regex("nf_[a-z]{1,5}[0-9]{1,4}")
        private val overseas = Regex("hf_[a-z0-9]{1,24}")

        fun parse(input: String): Symbol? {
            val code = input.trim().lowercase(Locale.ROOT)
            return when {
                mainland.matches(code) || hongKong.matches(code) -> Symbol(code, Group.STOCK)
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
)

interface QuoteSource {
    /** A successful null is an explicit empty response. A failed result lets callers retain their last quote. */
    fun fetch(symbols: List<Symbol>): Map<String, Result<Quote?>>
}

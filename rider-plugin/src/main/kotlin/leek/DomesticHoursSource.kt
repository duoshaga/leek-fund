package leek

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Only the hours worker calls this source. Exchange and product identifiers come from the live directory. */
internal class DomesticHoursSource(private val read: (URI) -> String = { uri ->
    downloadQuote(uri, "https://quote.eastmoney.com/", Charsets.UTF_8, 3000, 5000)
}) {
    private data class Catalog(val rows: JsonArray, val checked: Instant)
    private val catalogs = mutableMapOf<String, Catalog>()

    fun fetch(product: String, now: Instant): DomesticRefreshHours? {
        for (item in catalog("gnweb", now)) {
            val venue = item.asJsonObject
            val id = venue.get("mktid")?.asString ?: continue
            if (!id.matches(MARKET_ID)) continue
            val listed = catalog(id, now).any { it.asJsonObject.get("vcode")?.asString.equals(product, ignoreCase = true) }
            if (!listed) continue
            val info = load(id + "_" + product.lowercase(Locale.ROOT) + "_info").asJsonObject
            if (!info.get("vcode")?.asString.equals(product, ignoreCase = true)) return null
            val text = info.get("jysj")?.asString ?: return null
            val exchange = venue.get("mktshort")?.asString ?: return null
            return parseHours(text, exchange)
        }
        return null
    }

    private fun load(key: String) = JsonParser.parseString(read(URI.create("https://futsse-static.eastmoney.com/redis?msgid=" + key)))

    private fun catalog(key: String, now: Instant): JsonArray {
        val previous = catalogs[key]
        if (previous != null && now >= previous.checked && Duration.between(previous.checked, now) < CATALOG_TTL) return previous.rows
        val rows = try {
            load(key).asJsonArray.also { require(!it.isEmpty && it.all { row -> row.isJsonObject }) }
        } catch (_: Exception) {
            previous?.rows ?: JsonArray()
        }
        catalogs[key] = Catalog(rows, now)
        return rows
    }

    companion object {
        private val CATALOG_TTL = Duration.ofMinutes(5)
        private val MARKET_ID = Regex("[0-9]{1,6}")
        private val range = Regex("""(上午|下午|晚上|凌晨|次日)?\s*(\d{1,2}:\d{2})\s*[-–—~～至]\s*(上午|下午|晚上|凌晨|次日)?\s*(\d{1,2}:\d{2})""")

        private fun clock(text: String, period: String): String {
            val hour = text.substringBefore(':').toInt()
            val adjusted = if (period in listOf("下午", "晚上") && hour in 1..11) hour + 12 else hour
            return adjusted.toString().padStart(2, '0') + ":" + text.substringAfter(':')
        }

        internal fun parseHours(description: String, exchange: String): DomesticRefreshHours? {
            val text = description.replace('：', ':')
            if (range.replace(text, "").any(Char::isDigit)) return null
            val windows = range.findAll(text).flatMap { match ->
                val (period, first, endPeriod, last) = match.destructured
                val start = clock(first, period)
                val end = clock(last, endPeriod.ifEmpty { period })
                // Commodity specifications often print the entire morning, including the 10:15 recess.
                if (exchange != "CFFEX" && start == "09:00" && end == "11:30") listOf("09:00-10:15", "10:30-11:30")
                else listOf("$start-$end")
            }
            return DomesticRefreshHours.parse(windows.joinToString(", "))
        }
    }
}

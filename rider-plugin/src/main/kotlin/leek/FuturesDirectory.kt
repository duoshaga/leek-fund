package leek

/** Local search remains available when suggestions or quote servers are offline. */
object FuturesDirectory {
    private val listings: List<SymbolHit> = buildList {
        val input = requireNotNull(FuturesDirectory::class.java.getResourceAsStream("/leek/domestic-futures.txt"))
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith('#')) continue
                val (product, name, listedMonths) = line.split('|')
                for (month in listedMonths.split(' ')) {
                    val code = "nf_$product$month"
                    val display = if (month == "0") "${name}连续" else name + month
                    add(SymbolHit(Symbol(code, Group.DOMESTIC), display))
                }
            }
        }
        add(SymbolHit(Symbol("hf_OIL", Group.OVERSEAS), "布伦特原油"))
        add(SymbolHit(Symbol("hf_CHA50CFD", Group.OVERSEAS), "富时中国A50指数期货"))
    }

    fun search(query: String): List<SymbolHit> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val hits = listings.filter { it.symbol.code.contains(term, ignoreCase = true) || it.name.contains(term, ignoreCase = true) }
        return hits.sortedWith(compareBy<SymbolHit>(
            { !(it.symbol.code.equals(term, true) || it.symbol.code.substringAfter('_').equals(term, true) || it.name.equals(term, true)) },
            { it.symbol.group == Group.DOMESTIC && it.symbol.code.takeLast(4).all(Char::isDigit) },
            { !(it.symbol.code.substringAfter('_').startsWith(term, true) || it.name.startsWith(term, true)) },
            { it.symbol.code },
        ))
    }
}

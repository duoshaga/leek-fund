package leek

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchTest {
    @Test
    fun `numeric search preserves separately named index stock and fund candidates`() {
        val search = SymbolSearch { uri, _ ->
            when (uri.host) {
                "proxy.finance.qq.com" -> """{"code":0,"data":{"stock":[["sz","000001","平安银行","","GP-A"],["sh","000001","上证指数","","ZS"],["jj","000001","华夏成长混合","","KJ"]]}}"""
                "fundsuggest.eastmoney.com" -> """{"ErrCode":0,"Datas":[{"CODE":"000001","NAME":"华夏成长混合","CATEGORY":700,"FundBaseInfo":{"FCODE":"000001"}},{"CODE":"000001","NAME":"平安银行","CATEGORY":150,"FundBaseInfo":null}]}"""
                else -> error("Unexpected host ${uri.host}")
            }
        }

        val result = search.search("０００００１")
        assertEquals(3, result.size)
        assertEquals(
            mapOf("sh000001" to "上证指数", "sz000001" to "平安银行", "000001" to "华夏成长混合"),
            result.associate { it.symbol.code to it.name },
        )
    }

    @Test
    fun `mainland benchmarks remain available in the picker when suggestions are offline`() {
        val search = SymbolSearch { _, _ -> throw IOException("offline") }
        val shanghai = SymbolHit(Symbol("sh000001", Group.STOCK), "上证指数")
        val shenzhen = SymbolHit(Symbol("sz399001", Group.STOCK), "深证成指")

        assertEquals(listOf(shanghai), search.local("SH000001"))
        assertEquals(listOf(shanghai), search.search("上证指数"))
        assertEquals(listOf(shenzhen), search.search("深证成指"))
        assertEquals(listOf("sh000001", "sz000001", "000001"), search.local("000001").map { it.symbol.code })
    }

    @Test
    fun `online index search accepts exchange indexes by name and qualified code`() {
        val search = SymbolSearch { uri, _ ->
            require(uri.host == "proxy.finance.qq.com" && uri.query in setOf("q=沪深300", "q=000300"))
            """{"code":0,"data":{"stock":[["sh","000300","沪深300","","ZS"],["sz","399300","沪深300","","ZS"],["us","CHAU.AM","2倍做多沪深300ETF-Direxion","","GP-ETF"],["sh","510300","沪深300ETF华泰柏瑞","","ETF"]]}}"""
        }
        val shanghai = SymbolHit(Symbol("sh000300", Group.STOCK), "沪深300")
        val shenzhen = SymbolHit(Symbol("sz399300", Group.STOCK), "沪深300")

        val resultsByName = search.search("沪深300")
        assertEquals(listOf(shanghai, shenzhen), resultsByName.filter { it.symbol.group == Group.STOCK })
        assertEquals(listOf(shanghai), search.search("sh000300"))
    }

    @Test
    fun `market-prefixed fragments send only digits to Tencent and retain that market`() {
        val search = SymbolSearch { uri, _ ->
            require(uri.host == "proxy.finance.qq.com")
            require(uri.query == "q=007")
            """{"code":0,"data":{"stock":[["hk","00700","腾讯控股","","GP"],["sh","600007","中国国贸","","GP-A"],["hk","00723","信保环球控股","","GP"],["hk","00724","供应商列出的债券","","ZQ"]]}}"""
        }

        val codes = search.search(" hk007 ").map { it.symbol.code }
        assertEquals(listOf("hk00700", "hk00723"), codes)
    }

    @Test
    fun `fund names and stock pinyin can return remotely found instruments`() {
        val search = SymbolSearch { uri, _ ->
            when (uri.host) {
                "proxy.finance.qq.com" -> if (uri.query == "q=zxgj") {
                    """{"code":0,"data":{"stock":[["hk","00981","中芯国际","","GP"],["sh","688981","中芯国际","","GP-A-KCB"],["us","SMICY","中芯国际","","GP"]]}}"""
                } else """{"code":0,"data":{}}"""
                "fundsuggest.eastmoney.com" -> if (uri.rawQuery.contains("%E6%8B%9B%E5%95%86")) {
                    """{"ErrCode":0,"Datas":[{"CODE":"012414","NAME":"招商中证白酒指数(LOF)C","CATEGORY":700,"FundBaseInfo":{"FCODE":"012414"}},{"CODE":"000847","NAME":"腾讯济安","CATEGORY":600,"FundBaseInfo":null}]}"""
                } else """{"ErrCode":0,"Datas":[]}"""
                else -> error("Unexpected host ${uri.host}")
            }
        }

        val chips = search.search("zxgj").map { it.symbol.code }.toSet()
        assertEquals(setOf("hk00981", "sh688981"), chips)
        val funds = search.search("招商中证白酒").map { it.symbol.code }
        assertTrue("012414" in funds)
        assertFalse("000847" in funds)
    }

    @Test
    fun `a failed or missing feed preserves known local results and the other provider`() {
        val requests = AtomicInteger()
        val search = SymbolSearch { uri, _ ->
            requests.incrementAndGet()
            if (uri.host == "proxy.finance.qq.com") throw IOException("offline")
            """{"ErrCode":0,"Datas":[{"CODE":"013389","NAME":"华夏成长先锋一年持有混合A","CATEGORY":700,"FundBaseInfo":{"FCODE":"013389"}}]}"""
        }

        assertEquals("000001", search.local("华夏成长").single().symbol.code)
        assertEquals(0, requests.get())
        assertEquals(setOf("000001", "013389"), search.search("华夏成长").map { it.symbol.code }.toSet())
        assertTrue(search.local("999998").isEmpty())
    }

    @Test
    fun `Sina futures search supplements the catalogue and respects an explicit market prefix`() {
        val search = SymbolSearch { uri, _ ->
            if (uri.host == "suggest3.sinajs.cn") {
                require(uri.rawPath.endsWith("key=si"))
                """var suggestvalue="si,86,si,si,纽约白银,,纽约白银,99,1,,,,;si2701,86,si2701,si2701,纽约白银2701,,纽约白银2701,99,1,,,,;SI0,85,si0,si0,工业硅连续,,工业硅连续,99,1,,,,;xau,86,xau,xau,伦敦金（现货黄金）,,伦敦金,99,1,,,,;nq,86,nq,nq,纳斯达克指数期货,,纳斯达克指数期货,99,1,,,,";"""
            } else {
                throw IOException("unavailable")
            }
        }

        val overseas = search.search("HF_si")
        assertEquals(listOf("hf_SI", "hf_SI2701"), overseas.map { it.symbol.code })
        assertEquals("纽约白银2701", overseas.last().name)
        assertTrue(search.search("si").any { it.symbol.code == "nf_SI0" })
    }
}

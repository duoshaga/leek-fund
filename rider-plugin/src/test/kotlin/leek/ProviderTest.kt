package leek

import java.io.IOException
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderTest {
    @Test
    fun `normalize codes without collapsing distinct futures contracts`() {
        assertEquals(Symbol("sh600000", Group.STOCK), Symbol.parse(" SH600000 "))
        assertEquals(Symbol("hk00700", Group.STOCK), Symbol.parse("HK00700"))
        assertEquals(Symbol("000001", Group.FUND), Symbol.parse("000001"))
        assertEquals(Symbol("nf_RB0", Group.DOMESTIC), Symbol.parse("nf_rb0"))
        assertEquals(Symbol("nf_RB2701", Group.DOMESTIC), Symbol.parse("nf_rb2701"))
        assertEquals(Symbol("nf_AU2706", Group.DOMESTIC), Symbol.parse("nf_au2706"))
        assertEquals(Symbol("hf_CHA50CFD", Group.OVERSEAS), Symbol.parse("HF_cha50cfd"))
        assertNull(Symbol.parse("hf_OIL,nf_RB0"))
        assertNull(Symbol.parse("60000"))
    }

    @Test
    fun `read stock prices and original times from the respective feeds`() {
        val source = MarketQuoteSource { uri, _, _ ->
            when (uri.host) {
                "hq.sinajs.cn" -> """var hq_str_sh600000="浦发银行,10.680,10.670,10.640,10.690,10.530,10.640,10.650,29298496,311414105.000,40400,10.640,52800,10.630,16800,10.620,82800,10.610,119700,10.600,10600,10.650,25800,10.660,67600,10.670,138300,10.680,205425,10.690,2026-11-26,11:30:00,00,";"""
                "qt.gtimg.cn" -> """{"r_hk00700":["100","腾讯控股","00700","344.000","337.400","344.800","14538137.0","0","0","344.000","0","0","0","0","0","0","0","0","0","344.000","0","0","0","0","0","0","0","0","0","14538137.0","2026/11/26 11:59:59","6.600","1.96","348.800","337.800","344.000","14538137.0","4989047112.790"]}"""
                else -> error("Unexpected host $uri")
            }
        }

        val quotes = source.fetch(symbols("sh600000", "hk00700"))
        val a = quotes.getValue("sh600000").getOrThrow()!!
        val hk = quotes.getValue("hk00700").getOrThrow()!!
        assertDecimal("10.640", a.price)
        assertDecimal("-0.28", a.percent)
        assertEquals("2026-11-26 11:30:00", a.sourceTime)
        assertDay(a, "10.680", "10.690", "10.530", "29298496", "311414105.000")
        assertDecimal("344.000", hk.price)
        assertDecimal("1.96", hk.percent)
        assertEquals("2026/11/26 11:59:59", hk.sourceTime)
        assertDay(hk, "344.800", "348.800", "337.800", "14538137.0", "4989047112.790")
    }

    @Test
    fun `use index previous close and commodity previous settlement from the same batch`() {
        val source = MarketQuoteSource { _, _, _ ->
            """
            var hq_str_nf_IF0="3858.000,3888.400,3852.800,3855.800,40471,156505398.400,160063.000,0.000,0.000,4230.800,3461.600,0.000,0.000,3824.000,3846.200,173655.000,3855.400,1,0.000,0,0.000,0,0.000,0,0.000,0,3856.000,2,0.000,0,0.000,0,0.000,0,0.000,0,2026-11-26,11:30:00,400,1,,,,,,,,,3867.100,沪深300指数期货连续";
            var hq_str_nf_RB0="螺纹钢连续,113000,3065.000,3071.000,3051.000,0.000,3065.000,3066.000,3065.000,0.000,3047.000,12,141,1079943.000,465137,沪,螺纹钢,2026-11-26,1,,,,,,,,,3062.348,3064.000,65,3063.000,110,3062.000,316,3061.000,616,3067.000,275,3068.000,417,3069.000,902,3070.000,1820";
            var hq_str_nf_RB2701="螺纹钢2701,000103,3065.000,3071.000,3051.000,0.000,3065.000,3066.000,3065.000,0.000,3047.000,12,141,1079943.000,465137,沪,螺纹钢,2026-11-27,1,,,,,,,,,3062.348,3064.000,65,3063.000,110,3062.000,316,3061.000,616,3067.000,275,3068.000,417,3069.000,902,3070.000,1820";
            """.trimIndent()
        }

        val quotes = source.fetch(symbols("nf_IF0", "nf_RB0", "nf_RB2701"))
        val index = quotes.getValue("nf_IF0").getOrThrow()!!
        assertEquals("沪深300指数期货连续", index.name)
        assertEquals("昨收", index.basisLabel)
        assertDecimal("3824.000", index.basis)
        assertDecimal("0.83", index.percent)
        assertDay(index, "3858.000", "3888.400", "3852.800", "40471", null)
        val continuous = quotes.getValue("nf_RB0").getOrThrow()!!
        assertEquals("昨结算价", continuous.basisLabel)
        assertDecimal("3047.000", continuous.basis)
        assertDecimal("0.59", continuous.percent)
        val month = quotes.getValue("nf_RB2701").getOrThrow()!!
        assertEquals("nf_RB2701", month.code)
        assertEquals("2026-11-27 00:01:03", month.sourceTime)
        assertDay(month, "3065.000", "3071.000", "3051.000", "465137", null)
    }

    @Test
    fun `show buy one for an out of spread trade or missing trade and retain its raw value`() {
        val source = MarketQuoteSource { _, _, _ ->
            """
            var hq_str_hf_OIL="91.094,,91.080,91.100,91.240,90.490,12:20:56,90.640,90.690,0,2,7,2026-11-26,布伦特原油,13317";
            var hq_str_hf_CHA50CFD="12530.130,,12524.000,12525.000,12582.000,12336.000,12:21:38,12455.000,12459.000,796595,47,9,2026-11-26,富时中国A50期货,310098";
            var hq_str_hf_SI=",,90.000,,91.240,90.490,12:20:56,0.000,90.690,0,2,7,2026-11-26,纽约白银";
            var hq_str_hf_XAU=",,,,91.240,90.490,12:20:56,90.640,90.690,0,2,7,2026-11-26,伦敦金";
            var hq_str_hf_CL="91.094,,92.000,,91.240,90.490,12:20:56,90.640,90.690,0,2,7,2026-11-26,纽约原油";
            """.trimIndent()
        }

        val quotes = source.fetch(symbols("hf_OIL", "hf_CHA50CFD", "hf_SI", "hf_XAU", "hf_CL"))
        val brent = quotes.getValue("hf_OIL").getOrThrow()!!
        assertFalse(brent.bidFallback)
        assertDecimal("91.094", brent.price)
        assertDecimal("0.50", brent.percent)
        assertDay(brent, "90.690", "91.240", "90.490", "13317", null)
        val a50 = quotes.getValue("hf_CHA50CFD").getOrThrow()!!
        assertTrue(a50.bidFallback)
        assertDecimal("12524.000", a50.price)
        assertDecimal("12530.130", a50.rawTrade)
        assertDecimal("0.55", a50.percent)
        assertEquals("昨结算价", a50.basisLabel)
        assertEquals("2026-11-26 12:21:38", a50.sourceTime)
        assertDay(a50, "12459.000", "12582.000", "12336.000", "310098", null)
        val silver = quotes.getValue("hf_SI").getOrThrow()!!
        assertTrue(silver.bidFallback)
        assertDecimal("90.000", silver.price)
        assertNull(silver.rawTrade)
        assertNull(silver.percent)
        val gold = quotes.getValue("hf_XAU").getOrThrow()!!
        assertFalse(gold.bidFallback)
        assertNull(gold.price)
        assertNull(gold.percent)
        assertDay(gold, "90.690", "91.240", "90.490", null, null)
        val wti = quotes.getValue("hf_CL").getOrThrow()!!
        assertFalse(wti.bidFallback)
        assertDecimal("91.094", wti.price)
    }

    @Test
    fun `keep published unit NAV and only the provider supplied percentage`() {
        val source = MarketQuoteSource { uri, _, _ ->
            when {
                uri.host == "hq.sinajs.cn" -> """var hq_str_fu_000001="";var hq_str_fu_161725="";"""
                uri.host == "fundsuggest.eastmoney.com" -> """{"ErrCode":0,"Datas":[{"CODE":"000001","NAME":"平安银行","FundBaseInfo":null},{"CODE":"000001","NAME":"华夏成长混合","FundBaseInfo":{"FCODE":"000001"}}]}"""
                uri.query.startsWith("fundCode=000001&") -> """{"ErrCode":0,"Data":{"LSJZList":[{"FSRQ":"2026-11-25","DWJZ":"0.9110","LJJZ":"3.4840","JZZZL":"-5.89"}]}}"""
                else -> """{"ErrCode":0,"Data":{"LSJZList":[{"DWJZ":"0.5721","LJJZ":"2.2882","FSRQ":"2026-11-25","JZZZL":""}]}}"""
            }
        }

        val quotes = source.fetch(symbols("000001", "161725"))
        val fund = quotes.getValue("000001").getOrThrow()!!
        assertEquals("华夏成长混合", fund.name)
        assertDecimal("0.9110", fund.price)
        assertDecimal("-5.89", fund.percent)
        assertEquals("2026-11-25", fund.sourceTime)
        assertEquals("0.9110", fund.price!!.toPlainString())
        assertNull(fund.day)
        val missingPercent = quotes.getValue("161725").getOrThrow()!!
        assertDecimal("0.5721", missingPercent.price)
        assertNull(missingPercent.percent)
    }

    @Test
    fun `an explicit empty carries no quote and one provider failure leaves other results available`() {
        val source = MarketQuoteSource { uri, _, _ ->
            when (uri.host) {
                "hq.sinajs.cn" -> """var hq_str_nf_RB9999="";var hq_str_fu_999999="";"""
                "qt.gtimg.cn" -> throw IOException("offline")
                else -> """{"ErrCode":0,"Data":{"LSJZList":[]},"TotalCount":0}"""
            }
        }

        val quotes = source.fetch(symbols("nf_RB9999", "hk00700", "999999"))
        assertNull(quotes.getValue("nf_RB9999").getOrThrow())
        assertNull(quotes.getValue("999999").getOrThrow())
        assertTrue(quotes.getValue("hk00700").isFailure)
    }

    @Test
    fun `a protocol error keeps previous data eligible instead of masquerading as empty`() {
        val source = MarketQuoteSource { _, _, _ -> "<html>页面未找到</html>" }
        val quotes = source.fetch(symbols("nf_RB0", "hk00700", "000001"))
        assertTrue(quotes.values.all { it.isFailure })
    }

    @Test
    fun `Tencent matches its records by code and recognizes the explicit no-match sentinel`() {
        val source = MarketQuoteSource { uri, _, _ ->
            if (uri.query.contains(",")) {
                """{"r_hk00700":["100","腾讯控股","00700","344.000","337.400","344.800","14538137.0","0","0","344.000","0","0","0","0","0","0","0","0","0","344.000","0","0","0","0","0","0","0","0","0","14538137.0","2026/11/26 11:59:59","6.600","1.96"]}"""
            } else {
                """{"pv_none_match":["1"]}"""
            }
        }

        val mixed = source.fetch(symbols("hk99999", "hk00700"))
        assertNull(mixed.getValue("hk99999").getOrThrow())
        assertDecimal("344.000", mixed.getValue("hk00700").getOrThrow()?.price)
        assertNull(source.fetch(symbols("hk99999")).getValue("hk99999").getOrThrow())
    }

    private fun symbols(vararg codes: String) = codes.map { Symbol.parse(it)!! }

    @Test
    fun `commodity details preserve genuine zero volume and distinguish it from missing volume`() {
        val response = """var hq_str_nf_AO2702="氧化铝2702,113000,2556.000,2588.000,2553.000,0.000,2558.000,2561.000,2560.000,0.000,2558.000,23,11,153828.000,34742,沪,氧化铝,2026-11-26";"""
        fun capture(raw: String): Quote =
            MarketQuoteSource { _, _, _ -> raw }.fetch(symbols("nf_AO2702")).getValue("nf_AO2702").getOrThrow()!!

        assertDay(capture(response), "2556.000", "2588.000", "2553.000", "34742", null)
        assertDay(capture(response.replace(",34742,", ",0,")), "2556.000", "2588.000", "2553.000", "0", null)
        assertDay(capture(response.replace(",34742,", ",,")), "2556.000", "2588.000", "2553.000", null, null)
    }

    private fun assertDay(quote: Quote, open: String, high: String, low: String, volume: String?, turnover: String?) {
        val day = requireNotNull(quote.day)
        assertDecimal(open, day.open)
        assertDecimal(high, day.high)
        assertDecimal(low, day.low)
        assertEquals(volume?.toBigDecimal()?.stripTrailingZeros(), day.volume?.stripTrailingZeros())
        assertEquals(turnover?.toBigDecimal()?.stripTrailingZeros(), day.turnover?.stripTrailingZeros())
    }

    private fun assertDecimal(expected: String, actual: BigDecimal?) {
        assertEquals(BigDecimal(expected).stripTrailingZeros(), actual?.stripTrailingZeros())
    }
}

package leek

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FuturesDirectoryTest {
    @Test fun aluminaCanBeFoundByCodeChineseNameOrCompleteContract() {
        val partialCode = FuturesDirectory.search(" aO ")
        val chinese = FuturesDirectory.search("氧化铝")
        assertEquals("nf_AO0", partialCode.first().symbol.code)
        assertEquals("氧化铝连续", chinese.first().name)
        val month = chinese.single { it.symbol.code == "nf_AO2701" }
        assertEquals("氧化铝2701", month.name)
        assertTrue(month in partialCode)
        assertEquals(month, FuturesDirectory.search("NF_AO2701").first())
        assertEquals(month, FuturesDirectory.search("O2701").first())
        assertTrue(FuturesDirectory.search("AO9999").isEmpty())
    }

    @Test fun rankedMatchesKeepDistinctContractsAndSearchProviderCodes() {
        val steel = FuturesDirectory.search("螺纹")
        assertEquals("nf_RB0", steel.first().symbol.code)
        assertTrue(steel.any { it.symbol.code == "nf_RB2701" })
        assertEquals("nf_IF0", FuturesDirectory.search("沪深300").first().symbol.code)
        assertEquals("hf_OIL", FuturesDirectory.search("布伦特").first().symbol.code)
        assertEquals("hf_CHA50CFD", FuturesDirectory.search("a50").first().symbol.code)
        assertTrue(FuturesDirectory.search("   ").isEmpty())
        val aluminium = FuturesDirectory.search("铝").map { it.symbol.code }
        assertEquals(aluminium.distinct(), aluminium)
        assertTrue(aluminium.indexOf("nf_AO0") < aluminium.indexOf("nf_AO2701"))
    }
}

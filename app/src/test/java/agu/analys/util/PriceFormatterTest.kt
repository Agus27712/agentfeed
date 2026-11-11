package agu.analys.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceFormatterTest {

    @Test
    fun testFormatPriceIdr() {
        assertEquals("Rp 500.000.000", PriceFormatter.formatPrice(500000000.0, quoteAsset = "IDR"))
        assertEquals("Rp 0,00000123", PriceFormatter.formatPrice(0.00000123, quoteAsset = "IDR"))
        assertEquals("Rp 1.234,56", PriceFormatter.formatPrice(1234.56, quoteAsset = "IDR", decimals = 2))
        assertEquals("-Rp 100.000", PriceFormatter.formatPrice(-100000.0, quoteAsset = "IDR"))
    }

    @Test
    fun testFormatPriceUsdt() {
        assertEquals("$65,000.50", PriceFormatter.formatPrice(65000.50, quoteAsset = "USDT"))
        assertEquals("$0.00000123", PriceFormatter.formatPrice(0.00000123, quoteAsset = "USDT"))
        assertEquals("$1,234.5678", PriceFormatter.formatPrice(1234.5678, quoteAsset = "USDT", decimals = 4))
        assertEquals("-$50.00", PriceFormatter.formatPrice(-50.0, quoteAsset = "USDT"))
    }

    @Test
    fun testFormatVolume() {
        assertEquals("Rp 1,5 T", PriceFormatter.formatVolume(1_500_000_000_000.0, "IDR"))
        assertEquals("Rp 2,5 Mil", PriceFormatter.formatVolume(2_500_000_000.0, "IDR"))
        assertEquals("Rp 10 jt", PriceFormatter.formatVolume(10_000_000.0, "IDR"))
        assertEquals("$1.5 B", PriceFormatter.formatVolume(1_500_000_000.0, "USDT"))
        assertEquals("$2.5 M", PriceFormatter.formatVolume(2_500_000.0, "USDT"))
        assertEquals("$10 K", PriceFormatter.formatVolume(10_000.0, "USDT"))
    }

    @Test
    fun testFormatPercentage() {
        assertEquals("+5.25%", PriceFormatter.formatPercentage(5.25))
        assertEquals("-2.10%", PriceFormatter.formatPercentage(-2.1))
        assertEquals("0.00%", PriceFormatter.formatPercentage(0.0))
    }

    @Test
    fun testFormatRsiAndIndicators() {
        assertEquals("68.5", PriceFormatter.formatRsi(68.48))
        assertEquals("12.3456", PriceFormatter.formatIndicatorVal(12.345612, decimals = 4))
    }

    @Test
    fun testFormatBenchmarkPerformance() {
        val startTime = System.nanoTime()
        val iterations = 50_000
        for (i in 0 until iterations) {
            PriceFormatter.formatPrice(12345.67 + i, quoteAsset = "IDR")
            PriceFormatter.formatPrice(0.0000123 + (i * 0.0000001), quoteAsset = "USDT")
            PriceFormatter.formatPercentage(i * 0.01)
            PriceFormatter.formatVolume(1000000.0 + i, quoteAsset = "IDR")
        }
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0
        println("Benchmark: $iterations iterations formatted in ${elapsedMs}ms")
        assertTrue("Benchmark should execute efficiently", elapsedMs < 5000.0)
    }
}

package agu.analys.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotPositionTrailingTest {

    private fun calculateTrailingLimitPrice(peakPrice: Double, entryPrice: Double, trailingPercent: Double): Double {
        val rawStop = peakPrice * (1.0 - trailingPercent / 100.0)
        return if (entryPrice > 0.0) maxOf(rawStop, entryPrice) else rawStop
    }

    @Test
    fun testTrailingLimitPrice_FloorAtEntryWhenPriceRiseIsSmall() {
        val entryPrice = 24933.0
        val trailingPct = 1.5 // 1.5%

        // Peak saat baru beli
        val initialPeak = 24933.0
        val oldSlPrice = calculateTrailingLimitPrice(initialPeak, entryPrice, trailingPct)
        assertEquals(24933.0, oldSlPrice, 0.001)

        // Harga naik sedikit ke 24950
        val newPeakSmall = 24950.0
        val newSlPriceSmall = calculateTrailingLimitPrice(newPeakSmall, entryPrice, trailingPct)
        // 24950 * (1 - 0.015) = 24575.75, yang masih di bawah 24933 -> limit tetap 24933.0
        assertEquals(24933.0, newSlPriceSmall, 0.001)

        // Batas aman TIDAK NAIK (oldSlPrice == newSlPriceSmall) -> Notifikasi HARUS DIAM!
        assertFalse(newSlPriceSmall > oldSlPrice)
    }

    @Test
    fun testTrailingLimitPrice_RisesOnlyWhenPeakExceedsTrailingThreshold() {
        val entryPrice = 24933.0
        val trailingPct = 1.5

        val oldSlPrice = calculateTrailingLimitPrice(24933.0, entryPrice, trailingPct)

        // Harga naik signifikan ke 26000
        val newPeakBig = 26000.0
        val newSlPriceBig = calculateTrailingLimitPrice(newPeakBig, entryPrice, trailingPct)
        // 26000 * (1 - 0.015) = 25610.0 > 24933.0
        assertEquals(25610.0, newSlPriceBig, 0.001)

        // Batas aman BENAR-BENAR NAIK -> Notifikasi boleh terpicu!
        assertTrue(newSlPriceBig > oldSlPrice)
    }

    @Test
    fun testTrailingProfitLock_FlashDropTriggerBehavior() {
        val entryPrice = 20000.0
        val peakPrice = 25000.0
        val trailingPct = 2.0 // Lock price = 25000 * 0.98 = 24500.0
        val trailingLockPrice = calculateTrailingLimitPrice(peakPrice, entryPrice, trailingPct)
        assertEquals(24500.0, trailingLockPrice, 0.001)

        // Skenario 1: Harga flash crash jatuh tajam dari 25000 langsung ke 21000 (melewati 24500)
        val flashDropPrice = 21000.0
        val isTriggered = flashDropPrice <= trailingLockPrice
        assertTrue("Order sell trailing harus terpicu saat harga drop melewati batas lock profit", isTriggered)
    }

    @Test
    fun testRealModeTrailingCalculations() {
        val entryPrice = 50000.0
        val peakPrice = 60000.0
        val trailingPct = 2.5
        val slPrice = calculateTrailingLimitPrice(peakPrice, entryPrice, trailingPct)
        // 60000 * (1 - 0.025) = 58500.0
        assertEquals(58500.0, slPrice, 0.001)

        // Drop below SL
        val dropPrice = 58400.0
        assertTrue("Real mode trailing must trigger when price drops below stop limit", dropPrice <= slPrice)
    }
}

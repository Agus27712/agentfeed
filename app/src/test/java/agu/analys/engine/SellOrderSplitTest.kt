package agu.analys.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SellOrderSplitTest {

    @Test
    fun testTwoTpOrderSplitDustFree() {
        val totalQuantity = 0.12345678
        val tp1Percent = 50.0
        val tp2Percent = 50.0

        val p1 = (tp1Percent / 100.0).coerceIn(0.01, 0.99)
        val qty1 = ((totalQuantity * p1) * 100_000_000.0).toLong() / 100_000_000.0
        val qty2 = totalQuantity - qty1

        assertEquals(0.06172839, qty1, 0.000000001)
        assertEquals(0.06172839, qty2, 0.000000001)
        assertEquals(totalQuantity, qty1 + qty2, 0.0000000000001)
    }

    @Test
    fun testUnevenPercentSplitDustFree() {
        val totalQuantity = 1.35791357
        val tp1Percent = 60.0

        val p1 = (tp1Percent / 100.0).coerceIn(0.01, 0.99)
        val qty1 = ((totalQuantity * p1) * 100_000_000.0).toLong() / 100_000_000.0
        val qty2 = totalQuantity - qty1

        assertEquals(0.81474814, qty1, 0.000000001)
        assertEquals(0.54316543, qty2, 0.000000001)
        assertEquals(totalQuantity, qty1 + qty2, 0.0000000000001)
    }

    @Test
    fun testZeroDustOnOddQuantities() {
        val oddQuantities = listOf(
            0.00000003,
            0.00000007,
            0.99999999,
            123.45678901,
            7.00000001
        )

        for (qty in oddQuantities) {
            val p1 = 0.50
            val qty1 = ((qty * p1) * 100_000_000.0).toLong() / 100_000_000.0
            val qty2 = qty - qty1

            assertTrue(qty1 >= 0.0)
            assertTrue(qty2 >= 0.0)
            assertEquals("Quantity mismatch for $qty", qty, qty1 + qty2, 0.00000000000001)
        }
    }
}

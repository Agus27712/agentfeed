package agu.analys.engine.scalping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes ringkasan performa paper trade (P2).
 *
 * Fokus: memastikan dua keterbatasan yang dicatat di `AGENT_BRIDGE.md` benar-benar tertutup —
 * TIMEOUT tidak dibuang diam-diam, dan breakeven (r = 0) tidak lagi dihitung sebagai LOSS.
 */
class PaperTradeStatsTest {

    private fun decision(r: Double, reason: PaperExitReason) = PaperExitDecision(
        result = if (r > 0.0) PaperExitResult.WIN else PaperExitResult.LOSS,
        r = r,
        exitIndex = 1,
        ambiguous = false,
        reason = reason
    )

    private fun timeout() = PaperExitDecision(
        PaperExitResult.UNRESOLVED, 0.0, null, false, PaperExitReason.TIMEOUT
    )

    @Test
    fun `daftar kosong menghasilkan statistik kosong`() {
        val s = PaperTradeStats.compute(emptyList())
        assertEquals(PaperTradeStats.EMPTY, s)
        assertTrue(!s.hasData)
        assertEquals(0.0, s.winRate, 0.0)
    }

    @Test
    fun `timeout dilaporkan terpisah bukan dibuang diam-diam`() {
        val s = PaperTradeStats.compute(
            listOf(
                decision(1.5, PaperExitReason.TP2),
                decision(-1.9, PaperExitReason.SL),
                timeout(),
                timeout()
            )
        )
        assertEquals(4, s.totalTrades)
        assertEquals(2, s.resolvedTrades)
        assertEquals(2, s.timeouts)
        assertEquals(2, s.byExitReason[PaperExitReason.TIMEOUT])
        // R hanya dihitung dari trade resolved
        assertEquals(-0.4, s.totalR, 0.0001)
        assertEquals(-0.2, s.avgR, 0.0001)
    }

    @Test
    fun `exit breakeven diklasifikasi BREAKEVEN bukan LOSS`() {
        val s = PaperTradeStats.compute(
            listOf(
                decision(0.0, PaperExitReason.BE_SL),
                decision(0.0, PaperExitReason.TRAIL_SL),
                decision(1.0, PaperExitReason.TP2)
            )
        )
        // dua trade keluar tepat di r = 0 (BE_SL dan TRAIL_SL) -> BREAKEVEN, bukan LOSS
        assertEquals(2, s.breakevens)
        assertEquals(0, s.losses)
        assertEquals(1, s.wins)
        assertEquals(3, s.resolvedTrades)
        // win rate = 1 menang / 3 resolved, bukan 1/1
        assertEquals(33.3333, s.winRate, 0.001)
        assertNull("tidak ada trade rugi -> profit factor tidak terdefinisi", s.profitFactor)
    }

    @Test
    fun `profit factor dan rata-rata R dihitung dari trade resolved`() {
        val s = PaperTradeStats.compute(
            listOf(
                decision(1.5, PaperExitReason.TP2),
                decision(0.5, PaperExitReason.EARLY_PROFIT),
                decision(-1.0, PaperExitReason.SL),
                decision(-1.0, PaperExitReason.SL),
                timeout()
            )
        )
        assertEquals(4, s.resolvedTrades)
        assertEquals(2, s.wins)
        assertEquals(2, s.losses)
        // gross win 2.0 / gross loss 2.0
        assertEquals(1.0, s.profitFactor!!, 0.0001)
        assertEquals(1.0, s.avgWinR, 0.0001)
        assertEquals(-1.0, s.avgLossR, 0.0001)
        assertEquals(50.0, s.winRate, 0.0001)
    }

    @Test
    fun `outcome per trade memakai ambang breakeven`() {
        assertEquals(PaperTradeOutcome.WIN, PaperTradeOutcome.of(decision(0.5, PaperExitReason.TP2)))
        assertEquals(PaperTradeOutcome.LOSS, PaperTradeOutcome.of(decision(-0.5, PaperExitReason.SL)))
        assertEquals(PaperTradeOutcome.BREAKEVEN, PaperTradeOutcome.of(decision(0.0, PaperExitReason.BE_SL)))
        assertEquals(PaperTradeOutcome.UNRESOLVED, PaperTradeOutcome.of(timeout()))
    }
}

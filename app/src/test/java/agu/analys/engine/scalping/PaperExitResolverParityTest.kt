package agu.analys.engine.scalping

import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes paritas [PaperExitResolver] terhadap `resolve_trade()` di `AUDIT-2026-11-08.md`.
 *
 * Angka harapan di bawah **dihasilkan langsung** dari implementasi Python pada
 * `AUDIT-2026-11-08.md` (bukan dihitung ulang manual), sehingga kegagalan tes berarti
 * perilaku Kotlin menyimpang dari replay offline.
 *
 * Fee: Tokocrypto all-in USDT 0,4044% per sisi; slippage 0,08%.
 */
class PaperExitResolverParityTest {

    private val buyFee = 0.4044
    private val sellFee = 0.4044
    private val slippage = 0.08
    private val entry = 100.0
    private val stopLoss = 99.0
    private val tp1 = 101.6
    private val tp2 = 102.5

    private fun b(i: Long, o: Double, h: Double, l: Double, c: Double) =
        CandleBar(timestamp = i * 60_000L, open = o, high = h, low = l, close = c, volume = 100.0)

    /**
     * Skenario bawah ini bergerak di kisaran +1…2,5% kotor dan angka harapannya dihasilkan
     * sebelum **S2 (lantai profit)** ada, jadi lantai dimatikan eksplisit.
     * Perilaku lantai sendiri diuji di [ScalpStrategyFixTest] dan pada
     * [paritas lantaiProfit menahanEarlyProfit] di bawah.
     */
    private fun resolve(
        bars: List<CandleBar>,
        mode: ScalpExitMode,
        maxHold: Int = 0,
        minProfitFrictionMultiple: Double = 0.0
    ) = PaperExitResolver.resolve(
        bars = bars,
        entryIndex = 0,
        entry = entry,
        stopLoss = stopLoss,
        tp1 = tp1,
        tp2 = tp2,
        buyFeePct = buyFee,
        sellFeePct = sellFee,
        slippagePct = slippage,
        params = ScalpExitParams(
            mode = mode,
            maxHoldBarsM1 = maxHold,
            minProfitFrictionMultiple = minProfitFrictionMultiple
        )
    )

    // ---------- skenario: tp2_hit ----------
    private fun barsTp2Hit(): List<CandleBar> = listOf(
        b(0L, 100.0, 100.0, 100.0, 100.0),
        b(1L, 100.0, 100.2, 99.9, 100.1),
        b(2L, 100.1, 102.6, 100.0, 102.4),
        b(3L, 102.4, 102.4, 102.4, 102.4),
        b(4L, 102.4, 102.4, 102.4, 102.4),
        b(5L, 102.4, 102.4, 102.4, 102.4),
        b(6L, 102.4, 102.4, 102.4, 102.4),
        b(7L, 102.4, 102.4, 102.4, 102.4),
        b(8L, 102.4, 102.4, 102.4, 102.4),
        b(9L, 102.4, 102.4, 102.4, 102.4),
        b(10L, 102.4, 102.4, 102.4, 102.4),
        b(11L, 102.4, 102.4, 102.4, 102.4),
        b(12L, 102.4, 102.4, 102.4, 102.4),
        b(13L, 102.4, 102.4, 102.4, 102.4),
        b(14L, 102.4, 102.4, 102.4, 102.4),
        b(15L, 102.4, 102.4, 102.4, 102.4),
        b(16L, 102.4, 102.4, 102.4, 102.4),
        b(17L, 102.4, 102.4, 102.4, 102.4),
        b(18L, 102.4, 102.4, 102.4, 102.4),
        b(19L, 102.4, 102.4, 102.4, 102.4),
        b(20L, 102.4, 102.4, 102.4, 102.4),
        b(21L, 102.4, 102.4, 102.4, 102.4),
        b(22L, 102.4, 102.4, 102.4, 102.4),
    )

    @Test
    fun `paritas tp2_hit_classic`() {
        val d = resolve(barsTp2Hit(), ScalpExitMode.CLASSIC, maxHold = 0)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(1.5118, d.r, 0.0001)
        assertEquals(PaperExitReason.TP2, d.reason)
        assertEquals(2, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas tp2_hit_tp1_be`() {
        val d = resolve(barsTp2Hit(), ScalpExitMode.TP1_BE, maxHold = 0)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(1.5118, d.r, 0.0001)
        assertEquals(PaperExitReason.TP2, d.reason)
        assertEquals(2, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas tp2_hit_early`() {
        val d = resolve(barsTp2Hit(), ScalpExitMode.EARLY, maxHold = 0)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(1.5118, d.r, 0.0001)
        assertEquals(PaperExitReason.TP2, d.reason)
        assertEquals(2, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas tp2_hit_trail`() {
        val d = resolve(barsTp2Hit(), ScalpExitMode.TRAIL, maxHold = 0)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(1.5118, d.r, 0.0001)
        assertEquals(PaperExitReason.TP2, d.reason)
        assertEquals(2, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas tp2_hit_full`() {
        val d = resolve(barsTp2Hit(), ScalpExitMode.FULL, maxHold = 90)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(1.5118, d.r, 0.0001)
        assertEquals(PaperExitReason.TP2, d.reason)
        assertEquals(2, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    // ---------- skenario: sl_hit ----------
    private fun barsSlHit(): List<CandleBar> = listOf(
        b(0L, 100.0, 100.0, 100.0, 100.0),
        b(1L, 100.0, 100.1, 98.9, 99.0),
        b(2L, 99.0, 99.0, 99.0, 99.0),
        b(3L, 99.0, 99.0, 99.0, 99.0),
        b(4L, 99.0, 99.0, 99.0, 99.0),
        b(5L, 99.0, 99.0, 99.0, 99.0),
        b(6L, 99.0, 99.0, 99.0, 99.0),
        b(7L, 99.0, 99.0, 99.0, 99.0),
        b(8L, 99.0, 99.0, 99.0, 99.0),
        b(9L, 99.0, 99.0, 99.0, 99.0),
        b(10L, 99.0, 99.0, 99.0, 99.0),
        b(11L, 99.0, 99.0, 99.0, 99.0),
        b(12L, 99.0, 99.0, 99.0, 99.0),
        b(13L, 99.0, 99.0, 99.0, 99.0),
        b(14L, 99.0, 99.0, 99.0, 99.0),
        b(15L, 99.0, 99.0, 99.0, 99.0),
        b(16L, 99.0, 99.0, 99.0, 99.0),
        b(17L, 99.0, 99.0, 99.0, 99.0),
        b(18L, 99.0, 99.0, 99.0, 99.0),
        b(19L, 99.0, 99.0, 99.0, 99.0),
        b(20L, 99.0, 99.0, 99.0, 99.0),
        b(21L, 99.0, 99.0, 99.0, 99.0),
    )

    @Test
    fun `paritas sl_hit_classic`() {
        val d = resolve(barsSlHit(), ScalpExitMode.CLASSIC, maxHold = 0)
        assertEquals(PaperExitResult.LOSS, d.result)
        assertEquals(-1.9545, d.r, 0.0001)
        assertEquals(PaperExitReason.SL, d.reason)
        assertEquals(1, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas sl_hit_tp1_be`() {
        val d = resolve(barsSlHit(), ScalpExitMode.TP1_BE, maxHold = 0)
        assertEquals(PaperExitResult.LOSS, d.result)
        assertEquals(-1.9545, d.r, 0.0001)
        assertEquals(PaperExitReason.SL, d.reason)
        assertEquals(1, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas sl_hit_early`() {
        val d = resolve(barsSlHit(), ScalpExitMode.EARLY, maxHold = 0)
        assertEquals(PaperExitResult.LOSS, d.result)
        assertEquals(-1.9545, d.r, 0.0001)
        assertEquals(PaperExitReason.SL, d.reason)
        assertEquals(1, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas sl_hit_trail`() {
        val d = resolve(barsSlHit(), ScalpExitMode.TRAIL, maxHold = 0)
        assertEquals(PaperExitResult.LOSS, d.result)
        assertEquals(-1.9545, d.r, 0.0001)
        assertEquals(PaperExitReason.SL, d.reason)
        assertEquals(1, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas sl_hit_full`() {
        val d = resolve(barsSlHit(), ScalpExitMode.FULL, maxHold = 90)
        assertEquals(PaperExitResult.LOSS, d.result)
        assertEquals(-1.9545, d.r, 0.0001)
        assertEquals(PaperExitReason.SL, d.reason)
        assertEquals(1, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    // ---------- skenario: tp1_then_be ----------
    private fun barsTp1ThenBe(): List<CandleBar> = listOf(
        b(0L, 100.0, 100.0, 100.0, 100.0),
        b(1L, 100.0, 101.7, 100.0, 101.6),
        b(2L, 101.6, 101.7, 100.5, 100.6),
        b(3L, 100.6, 100.6, 100.6, 100.6),
        b(4L, 100.6, 100.6, 100.6, 100.6),
        b(5L, 100.6, 100.6, 100.6, 100.6),
        b(6L, 100.6, 100.6, 100.6, 100.6),
        b(7L, 100.6, 100.6, 100.6, 100.6),
        b(8L, 100.6, 100.6, 100.6, 100.6),
        b(9L, 100.6, 100.6, 100.6, 100.6),
        b(10L, 100.6, 100.6, 100.6, 100.6),
        b(11L, 100.6, 100.6, 100.6, 100.6),
        b(12L, 100.6, 100.6, 100.6, 100.6),
        b(13L, 100.6, 100.6, 100.6, 100.6),
        b(14L, 100.6, 100.6, 100.6, 100.6),
        b(15L, 100.6, 100.6, 100.6, 100.6),
        b(16L, 100.6, 100.6, 100.6, 100.6),
        b(17L, 100.6, 100.6, 100.6, 100.6),
        b(18L, 100.6, 100.6, 100.6, 100.6),
        b(19L, 100.6, 100.6, 100.6, 100.6),
        b(20L, 100.6, 100.6, 100.6, 100.6),
        b(21L, 100.6, 100.6, 100.6, 100.6),
        b(22L, 100.6, 100.6, 100.6, 100.6),
    )

    @Test
    fun `paritas tp1_then_be_classic`() {
        val d = resolve(barsTp1ThenBe(), ScalpExitMode.CLASSIC, maxHold = 0)
        assertEquals(PaperExitResult.UNRESOLVED, d.result)
        assertEquals(0.0, d.r, 0.0001)
        assertEquals(PaperExitReason.TIMEOUT, d.reason)
        assertEquals(null as Int?, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas tp1_then_be_tp1_be`() {
        val d = resolve(barsTp1ThenBe(), ScalpExitMode.TP1_BE, maxHold = 0)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(0.3102, d.r, 0.0001)
        assertEquals(PaperExitReason.BE_SL, d.reason)
        assertEquals(2, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas tp1_then_be_early`() {
        val d = resolve(barsTp1ThenBe(), ScalpExitMode.EARLY, maxHold = 0)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(0.6204, d.r, 0.0001)
        assertEquals(PaperExitReason.EARLY_PROFIT, d.reason)
        assertEquals(1, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas tp1_then_be_trail`() {
        val d = resolve(barsTp1ThenBe(), ScalpExitMode.TRAIL, maxHold = 0)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(0.367, d.r, 0.0001)
        assertEquals(PaperExitReason.TRAIL_SL, d.reason)
        assertEquals(2, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas tp1_then_be_full`() {
        val d = resolve(barsTp1ThenBe(), ScalpExitMode.FULL, maxHold = 90)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(0.6204, d.r, 0.0001)
        assertEquals(PaperExitReason.EARLY_PROFIT, d.reason)
        assertEquals(1, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    // ---------- skenario: trail_hit ----------
    private fun barsTrailHit(): List<CandleBar> = listOf(
        b(0L, 100.0, 100.0, 100.0, 100.0),
        b(1L, 100.0, 102.0, 100.0, 101.9),
        b(2L, 101.9, 102.0, 101.5, 101.6),
        b(3L, 101.6, 101.6, 101.6, 101.6),
        b(4L, 101.6, 101.6, 101.6, 101.6),
        b(5L, 101.6, 101.6, 101.6, 101.6),
        b(6L, 101.6, 101.6, 101.6, 101.6),
        b(7L, 101.6, 101.6, 101.6, 101.6),
        b(8L, 101.6, 101.6, 101.6, 101.6),
        b(9L, 101.6, 101.6, 101.6, 101.6),
        b(10L, 101.6, 101.6, 101.6, 101.6),
        b(11L, 101.6, 101.6, 101.6, 101.6),
        b(12L, 101.6, 101.6, 101.6, 101.6),
        b(13L, 101.6, 101.6, 101.6, 101.6),
        b(14L, 101.6, 101.6, 101.6, 101.6),
        b(15L, 101.6, 101.6, 101.6, 101.6),
        b(16L, 101.6, 101.6, 101.6, 101.6),
        b(17L, 101.6, 101.6, 101.6, 101.6),
        b(18L, 101.6, 101.6, 101.6, 101.6),
        b(19L, 101.6, 101.6, 101.6, 101.6),
        b(20L, 101.6, 101.6, 101.6, 101.6),
        b(21L, 101.6, 101.6, 101.6, 101.6),
        b(22L, 101.6, 101.6, 101.6, 101.6),
    )

    @Test
    fun `paritas trail_hit_classic`() {
        val d = resolve(barsTrailHit(), ScalpExitMode.CLASSIC, maxHold = 0)
        assertEquals(PaperExitResult.UNRESOLVED, d.result)
        assertEquals(0.0, d.r, 0.0001)
        assertEquals(PaperExitReason.TIMEOUT, d.reason)
        assertEquals(null as Int?, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas trail_hit_tp1_be`() {
        val d = resolve(barsTrailHit(), ScalpExitMode.TP1_BE, maxHold = 0)
        assertEquals(PaperExitResult.UNRESOLVED, d.result)
        assertEquals(0.0, d.r, 0.0001)
        assertEquals(PaperExitReason.TIMEOUT, d.reason)
        assertEquals(null as Int?, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas trail_hit_early`() {
        val d = resolve(barsTrailHit(), ScalpExitMode.EARLY, maxHold = 0)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(0.9176, d.r, 0.0001)
        assertEquals(PaperExitReason.EARLY_PROFIT, d.reason)
        assertEquals(1, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas trail_hit_trail`() {
        val d = resolve(barsTrailHit(), ScalpExitMode.TRAIL, maxHold = 0)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(0.663, d.r, 0.0001)
        assertEquals(PaperExitReason.TRAIL_SL, d.reason)
        assertEquals(2, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas trail_hit_full`() {
        val d = resolve(barsTrailHit(), ScalpExitMode.FULL, maxHold = 90)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(0.769, d.r, 0.0001)
        assertEquals(PaperExitReason.EARLY_PROFIT, d.reason)
        assertEquals(1, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    // ---------- skenario: early_hit ----------
    private fun barsEarlyHit(): List<CandleBar> = listOf(
        b(0L, 100.0, 100.0, 100.0, 100.0),
        b(1L, 100.0, 100.4, 99.9, 100.3),
        b(2L, 100.0, 100.4, 99.9, 100.3),
        b(3L, 100.0, 100.4, 99.9, 100.3),
        b(4L, 100.3, 101.0, 100.2, 100.9),
        b(5L, 100.3, 101.0, 100.2, 100.9),
        b(6L, 100.3, 101.0, 100.2, 100.9),
        b(7L, 100.9, 101.8, 100.8, 101.45),
        b(8L, 101.45, 101.45, 101.45, 101.45),
        b(9L, 101.45, 101.45, 101.45, 101.45),
        b(10L, 101.45, 101.45, 101.45, 101.45),
        b(11L, 101.45, 101.45, 101.45, 101.45),
        b(12L, 101.45, 101.45, 101.45, 101.45),
        b(13L, 101.45, 101.45, 101.45, 101.45),
        b(14L, 101.45, 101.45, 101.45, 101.45),
        b(15L, 101.45, 101.45, 101.45, 101.45),
        b(16L, 101.45, 101.45, 101.45, 101.45),
        b(17L, 101.45, 101.45, 101.45, 101.45),
        b(18L, 101.45, 101.45, 101.45, 101.45),
        b(19L, 101.45, 101.45, 101.45, 101.45),
        b(20L, 101.45, 101.45, 101.45, 101.45),
        b(21L, 101.45, 101.45, 101.45, 101.45),
        b(22L, 101.45, 101.45, 101.45, 101.45),
        b(23L, 101.45, 101.45, 101.45, 101.45),
        b(24L, 101.45, 101.45, 101.45, 101.45),
        b(25L, 101.45, 101.45, 101.45, 101.45),
        b(26L, 101.45, 101.45, 101.45, 101.45),
        b(27L, 101.45, 101.45, 101.45, 101.45),
    )

    @Test
    fun `paritas early_hit_classic`() {
        val d = resolve(barsEarlyHit(), ScalpExitMode.CLASSIC, maxHold = 0)
        assertEquals(PaperExitResult.UNRESOLVED, d.result)
        assertEquals(0.0, d.r, 0.0001)
        assertEquals(PaperExitReason.TIMEOUT, d.reason)
        assertEquals(null as Int?, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas early_hit_tp1_be`() {
        val d = resolve(barsEarlyHit(), ScalpExitMode.TP1_BE, maxHold = 0)
        assertEquals(PaperExitResult.UNRESOLVED, d.result)
        assertEquals(0.0, d.r, 0.0001)
        assertEquals(PaperExitReason.TIMEOUT, d.reason)
        assertEquals(null as Int?, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas early_hit_early`() {
        val d = resolve(barsEarlyHit(), ScalpExitMode.EARLY, maxHold = 0)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(0.4719, d.r, 0.0001)
        assertEquals(PaperExitReason.EARLY_PROFIT, d.reason)
        assertEquals(7, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas early_hit_trail`() {
        val d = resolve(barsEarlyHit(), ScalpExitMode.TRAIL, maxHold = 0)
        assertEquals(PaperExitResult.UNRESOLVED, d.result)
        assertEquals(0.0, d.r, 0.0001)
        assertEquals(PaperExitReason.TIMEOUT, d.reason)
        assertEquals(null as Int?, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas early_hit_full`() {
        val d = resolve(barsEarlyHit(), ScalpExitMode.FULL, maxHold = 90)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(0.5462, d.r, 0.0001)
        assertEquals(PaperExitReason.EARLY_PROFIT, d.reason)
        assertEquals(7, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    // ---------- skenario: timeout ----------
    private fun barsTimeout(): List<CandleBar> = listOf(
        b(0L, 100.0, 100.0, 100.0, 100.0),
        b(1L, 100.05, 100.05, 100.05, 100.05),
        b(2L, 100.05, 100.05, 100.05, 100.05),
        b(3L, 100.05, 100.05, 100.05, 100.05),
        b(4L, 100.05, 100.05, 100.05, 100.05),
        b(5L, 100.05, 100.05, 100.05, 100.05),
        b(6L, 100.05, 100.05, 100.05, 100.05),
        b(7L, 100.05, 100.05, 100.05, 100.05),
        b(8L, 100.05, 100.05, 100.05, 100.05),
        b(9L, 100.05, 100.05, 100.05, 100.05),
        b(10L, 100.05, 100.05, 100.05, 100.05),
        b(11L, 100.05, 100.05, 100.05, 100.05),
        b(12L, 100.05, 100.05, 100.05, 100.05),
        b(13L, 100.05, 100.05, 100.05, 100.05),
        b(14L, 100.05, 100.05, 100.05, 100.05),
        b(15L, 100.05, 100.05, 100.05, 100.05),
        b(16L, 100.05, 100.05, 100.05, 100.05),
        b(17L, 100.05, 100.05, 100.05, 100.05),
        b(18L, 100.05, 100.05, 100.05, 100.05),
        b(19L, 100.05, 100.05, 100.05, 100.05),
        b(20L, 100.05, 100.05, 100.05, 100.05),
        b(21L, 100.05, 100.05, 100.05, 100.05),
        b(22L, 100.05, 100.05, 100.05, 100.05),
        b(23L, 100.05, 100.05, 100.05, 100.05),
        b(24L, 100.05, 100.05, 100.05, 100.05),
        b(25L, 100.05, 100.05, 100.05, 100.05),
        b(26L, 100.05, 100.05, 100.05, 100.05),
        b(27L, 100.05, 100.05, 100.05, 100.05),
        b(28L, 100.05, 100.05, 100.05, 100.05),
        b(29L, 100.05, 100.05, 100.05, 100.05),
        b(30L, 100.05, 100.05, 100.05, 100.05),
        b(31L, 100.05, 100.05, 100.05, 100.05),
        b(32L, 100.05, 100.05, 100.05, 100.05),
        b(33L, 100.05, 100.05, 100.05, 100.05),
        b(34L, 100.05, 100.05, 100.05, 100.05),
        b(35L, 100.05, 100.05, 100.05, 100.05),
        b(36L, 100.05, 100.05, 100.05, 100.05),
        b(37L, 100.05, 100.05, 100.05, 100.05),
        b(38L, 100.05, 100.05, 100.05, 100.05),
        b(39L, 100.05, 100.05, 100.05, 100.05),
        b(40L, 100.05, 100.05, 100.05, 100.05),
        b(41L, 100.05, 100.05, 100.05, 100.05),
        b(42L, 100.05, 100.05, 100.05, 100.05),
        b(43L, 100.05, 100.05, 100.05, 100.05),
        b(44L, 100.05, 100.05, 100.05, 100.05),
        b(45L, 100.05, 100.05, 100.05, 100.05),
        b(46L, 100.05, 100.05, 100.05, 100.05),
        b(47L, 100.05, 100.05, 100.05, 100.05),
        b(48L, 100.05, 100.05, 100.05, 100.05),
        b(49L, 100.05, 100.05, 100.05, 100.05),
        b(50L, 100.05, 100.05, 100.05, 100.05),
        b(51L, 100.05, 100.05, 100.05, 100.05),
        b(52L, 100.05, 100.05, 100.05, 100.05),
        b(53L, 100.05, 100.05, 100.05, 100.05),
        b(54L, 100.05, 100.05, 100.05, 100.05),
        b(55L, 100.05, 100.05, 100.05, 100.05),
        b(56L, 100.05, 100.05, 100.05, 100.05),
        b(57L, 100.05, 100.05, 100.05, 100.05),
        b(58L, 100.05, 100.05, 100.05, 100.05),
        b(59L, 100.05, 100.05, 100.05, 100.05),
        b(60L, 100.05, 100.05, 100.05, 100.05),
    )

    @Test
    fun `paritas timeout_classic`() {
        val d = resolve(barsTimeout(), ScalpExitMode.CLASSIC, maxHold = 0)
        assertEquals(PaperExitResult.UNRESOLVED, d.result)
        assertEquals(0.0, d.r, 0.0001)
        assertEquals(PaperExitReason.TIMEOUT, d.reason)
        assertEquals(null as Int?, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas timeout_tp1_be`() {
        val d = resolve(barsTimeout(), ScalpExitMode.TP1_BE, maxHold = 0)
        assertEquals(PaperExitResult.UNRESOLVED, d.result)
        assertEquals(0.0, d.r, 0.0001)
        assertEquals(PaperExitReason.TIMEOUT, d.reason)
        assertEquals(null as Int?, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas timeout_early`() {
        val d = resolve(barsTimeout(), ScalpExitMode.EARLY, maxHold = 0)
        assertEquals(PaperExitResult.UNRESOLVED, d.result)
        assertEquals(0.0, d.r, 0.0001)
        assertEquals(PaperExitReason.TIMEOUT, d.reason)
        assertEquals(null as Int?, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas timeout_trail`() {
        val d = resolve(barsTimeout(), ScalpExitMode.TRAIL, maxHold = 0)
        assertEquals(PaperExitResult.UNRESOLVED, d.result)
        assertEquals(0.0, d.r, 0.0001)
        assertEquals(PaperExitReason.TIMEOUT, d.reason)
        assertEquals(null as Int?, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    @Test
    fun `paritas timeout_full`() {
        val d = resolve(barsTimeout(), ScalpExitMode.FULL, maxHold = 90)
        assertEquals(PaperExitResult.UNRESOLVED, d.result)
        assertEquals(0.0, d.r, 0.0001)
        assertEquals(PaperExitReason.TIMEOUT, d.reason)
        assertEquals(null as Int?, d.exitIndex)
        assertEquals(false, d.ambiguous)
    }

    // ---------- skenario: ambiguous ----------
    private fun barsAmbiguous(): List<CandleBar> = listOf(
        b(0L, 100.0, 100.0, 100.0, 100.0),
        b(1L, 100.0, 102.6, 98.9, 100.5),
        b(2L, 100.5, 100.5, 100.5, 100.5),
        b(3L, 100.5, 100.5, 100.5, 100.5),
        b(4L, 100.5, 100.5, 100.5, 100.5),
        b(5L, 100.5, 100.5, 100.5, 100.5),
        b(6L, 100.5, 100.5, 100.5, 100.5),
        b(7L, 100.5, 100.5, 100.5, 100.5),
        b(8L, 100.5, 100.5, 100.5, 100.5),
        b(9L, 100.5, 100.5, 100.5, 100.5),
        b(10L, 100.5, 100.5, 100.5, 100.5),
        b(11L, 100.5, 100.5, 100.5, 100.5),
        b(12L, 100.5, 100.5, 100.5, 100.5),
        b(13L, 100.5, 100.5, 100.5, 100.5),
        b(14L, 100.5, 100.5, 100.5, 100.5),
        b(15L, 100.5, 100.5, 100.5, 100.5),
        b(16L, 100.5, 100.5, 100.5, 100.5),
        b(17L, 100.5, 100.5, 100.5, 100.5),
        b(18L, 100.5, 100.5, 100.5, 100.5),
        b(19L, 100.5, 100.5, 100.5, 100.5),
        b(20L, 100.5, 100.5, 100.5, 100.5),
        b(21L, 100.5, 100.5, 100.5, 100.5),
    )

    @Test
    fun `paritas ambiguous_classic`() {
        val d = resolve(barsAmbiguous(), ScalpExitMode.CLASSIC, maxHold = 0)
        assertEquals(PaperExitResult.LOSS, d.result)
        assertEquals(-1.9545, d.r, 0.0001)
        assertEquals(PaperExitReason.SL, d.reason)
        assertEquals(1, d.exitIndex)
        assertEquals(true, d.ambiguous)
    }

    @Test
    fun `paritas ambiguous_tp1_be`() {
        val d = resolve(barsAmbiguous(), ScalpExitMode.TP1_BE, maxHold = 0)
        assertEquals(PaperExitResult.LOSS, d.result)
        assertEquals(-1.9545, d.r, 0.0001)
        assertEquals(PaperExitReason.SL, d.reason)
        assertEquals(1, d.exitIndex)
        assertEquals(true, d.ambiguous)
    }

    @Test
    fun `paritas ambiguous_early`() {
        val d = resolve(barsAmbiguous(), ScalpExitMode.EARLY, maxHold = 0)
        assertEquals(PaperExitResult.LOSS, d.result)
        assertEquals(-1.9545, d.r, 0.0001)
        assertEquals(PaperExitReason.SL, d.reason)
        assertEquals(1, d.exitIndex)
        assertEquals(true, d.ambiguous)
    }

    @Test
    fun `paritas ambiguous_trail`() {
        val d = resolve(barsAmbiguous(), ScalpExitMode.TRAIL, maxHold = 0)
        assertEquals(PaperExitResult.LOSS, d.result)
        assertEquals(-1.9545, d.r, 0.0001)
        assertEquals(PaperExitReason.SL, d.reason)
        assertEquals(1, d.exitIndex)
        assertEquals(true, d.ambiguous)
    }

    @Test
    fun `paritas ambiguous_full`() {
        val d = resolve(barsAmbiguous(), ScalpExitMode.FULL, maxHold = 90)
        assertEquals(PaperExitResult.LOSS, d.result)
        assertEquals(-1.9545, d.r, 0.0001)
        assertEquals(PaperExitReason.SL, d.reason)
        assertEquals(1, d.exitIndex)
        assertEquals(true, d.ambiguous)
    }

    // ---------- paritas indikator (ema_last / rsi_wilder) ----------

    private val indicatorCloses = listOf(100.0, 100.16359734839807, 100.30918490153486, 100.42073549240395, 100.48596895068165, 100.49770397887588, 100.45464871341284, 100.36154294086916, 100.2286363133179, 100.07056000402993, 99.90471601856225, 99.74936147570583, 99.62159875234603, 99.53549274936462, 99.50052254145103, 99.52053786266843, 99.59333530421621, 99.71090087912785, 99.86029225090054, 100.02506350494109, 100.18707561528561, 100.3284932993594, 100.4337484348094, 100.49125389349318, 100.49467912331168, 100.44364705404735, 100.34377560755654, 100.20605924262088, 100.04565861777738, 99.88023161343484, 99.72798944455532, 99.60569185888839, 99.52680212158094, 99.50000489672465, 99.52825018649226, 99.6084285768817, 99.73171354099978, 99.88453311704941, 100.05006399277329, 100.21008351841333)

    @Test
    fun `paritas ema_last dan rsi_wilder`() {
        assertEquals(99.89462647523222, PaperExitResolver.emaLast(indicatorCloses, 9), 1e-9)
        assertEquals(99.91463595190966, PaperExitResolver.emaLast(indicatorCloses, 21), 1e-9)
        assertEquals(59.797722490528415, PaperExitResolver.rsiWilder(indicatorCloses, 14), 1e-9)
    }

    @Test
    fun `netProfitPct dan breakevenPrice paritas`() {
        assertEquals(0.026228946980833, PaperExitResolver.netProfitPct(entry, 101.0, buyFee, sellFee, slippage), 1e-9)
        assertEquals(100.97351571009973, PaperExitResolver.breakevenPrice(entry, buyFee, sellFee, slippage), 1e-9)
    }

    @Test
    fun `SL kena biaya all-in sehingga lebih buruk dari -1R`() {
        val d = resolve(barsSlHit(), ScalpExitMode.CLASSIC)
        assertTrue("SL harus lebih buruk dari -1R karena fee all-in", d.r < -1.0)
    }

    @Test
    fun `mode classic tidak memakai partial atau trailing`() {
        val d = resolve(barsTp1ThenBe(), ScalpExitMode.CLASSIC)
        assertEquals(PaperExitReason.TIMEOUT, d.reason)
        assertFalse(d.isResolved)
        assertNull(d.exitIndex)
    }

    // ---------- S2: lantai profit (min_profit_friction_multiple) ----------

    /**
     * Deret [barsTrailHit] menyentuh +2,0% kotor, yaitu **di atas** lantai 2× friksi
     * (1,9376%), sehingga trailing tetap boleh di-arm — lantai tidak mematikan exit,
     * hanya memastikan profitnya layak dibawa pulang.
     *
     * Angka harapan dihasilkan dari `resolve_trade()` Python dengan
     * `min_profit_friction_multiple = 2.0`.
     */
    @Test
    fun `paritas lantaiProfit trailingTetapArmKarenaGrossDiAtasLantai`() {
        val d = resolve(barsTrailHit(), ScalpExitMode.TRAIL, minProfitFrictionMultiple = 2.0)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(0.663, d.r, 0.0001)
        assertEquals(2, d.exitIndex)
        assertEquals(PaperExitReason.TRAIL_SL, d.reason)
    }

    /**
     * Mode FULL pada deret yang sama: tanpa lantai (lihat `paritas trail_hit_full`) early
     * profit mengambil +1,9% kotor di bar 1 — dengan lantai aktif early diblokir dan posisi
     * ditutup trailing di bar 2 dengan R lebih kecil namun **profit kotor ≥ 2× friksi**.
     */
    @Test
    fun `paritas lantaiProfit menahanEarlyProfit`() {
        val d = resolve(barsTrailHit(), ScalpExitMode.FULL, minProfitFrictionMultiple = 2.0)
        assertEquals(PaperExitResult.WIN, d.result)
        assertEquals(0.6417, d.r, 0.0001)
        assertEquals(2, d.exitIndex)
        assertEquals(PaperExitReason.TRAIL_SL, d.reason)
    }
}

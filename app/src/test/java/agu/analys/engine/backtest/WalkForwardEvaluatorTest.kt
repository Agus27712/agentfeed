package agu.analys.engine.backtest

import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes WalkForwardEvaluator: anti skor palsu (sampel tipis = data kurang),
 * tanpa fallback PF=1.0, dan pesan jujur.
 */
class WalkForwardEvaluatorTest {

    private val zeroFee = TradingFeeConfig(0.0, 0.0, 0.0, 0.0)

    /** Satu episode: 25 bar flat (reset SMA20) + trigger + exit WIN/LOSS deterministik. */
    private fun episode(startT: Long, win: Boolean): List<CandleBar> {
        val bars = mutableListOf<CandleBar>()
        for (i in 0 until 25) {
            bars += CandleBar(startT + i * 60_000L, 100.0, 102.5, 97.5, 100.0, 1000.0)
        }
        val t = startT + 25 * 60_000L
        bars += CandleBar(t, 100.0, 101.0, 99.5, 105.0, 1000.0) // trigger cross
        // ATR ≈ 5 → SL floor 103.425, TP ≈ 116. netRr ≈ 4.2 ≥ 1.2 → trade terbuka.
        val e = t + 60_000L
        bars += if (win) {
            CandleBar(e, 105.0, 120.0, 104.0, 118.0, 1000.0) // TP kena
        } else {
            CandleBar(e, 105.0, 106.0, 100.0, 101.0, 1000.0) // SL kena
        }
        return bars
    }

    private fun manyEpisodes(n: Int): List<CandleBar> {
        val out = mutableListOf<CandleBar>()
        var t = 0L
        for (i in 0 until n) {
            val eps = episode(t, win = i % 2 == 0)
            out += eps
            t = eps.last().timestamp + 60_000L
        }
        return out
    }

    @Test
    fun tinySample_dataKurang_bukanSkor50() {
        val r = WalkForwardEvaluator.validate((0 until 10).map {
            CandleBar(it * 60_000L, 100.0, 101.0, 99.0, 100.0, 1000.0)
        })
        assertFalse(r.isDataSufficient)
        assertEquals(-1, r.overallScore)
    }

    @Test
    fun emptySamples_dataKurang_bukan70Cukup() {
        // Flat 200 bar → 0 trade di kedua sampel. Sebelum fix: skor 70 ("Cukup").
        val flat = (0 until 200).map {
            CandleBar(it * 60_000L, 100.0, 100.0, 100.0, 100.0, 1000.0)
        }
        val r = WalkForwardEvaluator.validate(flat, zeroFee)
        assertFalse(r.isDataSufficient)
        assertEquals(-1, r.overallScore)
        assertTrue(r.summaryMessage.contains("Data kurang"))
    }

    @Test
    fun sufficientSamples_honestHighScore() {
        // 100 episode selang-seling WIN/LOSS → IS ±60 / OOS ±40 trade.
        val r = WalkForwardEvaluator.validate(manyEpisodes(100), zeroFee)
        assertTrue(r.isDataSufficient)
        assertTrue(r.overallScore in 10..100)
        assertTrue(r.summaryMessage.isNotBlank())
        assertFalse(r.isOverfitted)
    }

    @Test
    fun allLossSamples_noFakeScore() {
        // 70 episode LOSS semua → PF 0 di kedua sampel → data kurang, bukan skor.
        val out = mutableListOf<CandleBar>()
        var t = 0L
        for (i in 0 until 70) {
            val eps = episode(t, win = false)
            out += eps
            t = eps.last().timestamp + 60_000L
        }
        val r = WalkForwardEvaluator.validate(out, zeroFee)
        assertFalse(r.isDataSufficient)
        assertEquals(-1, r.overallScore)
    }
}

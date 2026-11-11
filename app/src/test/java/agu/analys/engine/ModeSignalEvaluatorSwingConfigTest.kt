package agu.analys.engine

import agu.analys.config.StrategyMode
import agu.analys.engine.swing.SwingConfig
import agu.analys.engine.swing.SwingRuntimeConfig
import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji sambungan pintu (seam) slider SWING: `ModeSignalInput.swingConfig` →
 * `ModeSignalEvaluator.evaluateSwing`. Dua config berbeda WAJIB memproduksi hasil berbeda pada
 * input yang sama; tanpa config (bawaan holder), hasilnya sama dengan config DEFAULT.
 *
 * Pola `ModeSignalEvaluatorIntradayConfigTest` — uji sambungan perubahan reasoning, bukan
 * pindai sumber.
 */
class ModeSignalEvaluatorSwingConfigTest {

    /** Fixture sama dengan `SwingEvaluatorGoldenTest.fixtureA` (net R:R 1:6,63, conf 36, HOLD). */
    private fun fixtureA(): List<CandleBar> {
        val baseTime = 1_700_000_000_000L
        val history = mutableListOf<CandleBar>()
        var p = 800.0
        for (i in 0 until 150) {
            val open = p
            val close = p + 1.6
            history.add(CandleBar(baseTime + i * 3_600_000L, open, close + 0.5, open - 0.5, close, 2500.0))
            p = close
        }
        val peak = p
        val corrections = listOf(peak - 8.0, peak - 16.0, peak - 23.0, peak - 28.0)
        for ((idx, target) in corrections.withIndex()) {
            val open = p
            history.add(CandleBar(baseTime + (150 + idx) * 3_600_000L, open, open + 1.0, target - 1.0, target, 2000.0))
            p = target
        }
        val lastOpen = p
        val lastClose = p + 6.0
        history.add(CandleBar(baseTime + 154 * 3_600_000L, lastOpen, lastClose + 0.5, lastOpen - 2.0, lastClose, 12_000.0))
        return history
    }

    private val harga = 1018.0000000000023
    private val baseInput = { cfg: SwingConfig ->
        ModeSignalInput(
            mode = StrategyMode.SWING,
            symbol = "TESTUSDT",
            price = harga,
            nowMs = 1_700_010_000_000L,
            h1Candles = fixtureA(),
            swingConfig = cfg
        )
    }

    @Test
    fun `dua config berbeda memproduksi output berbeda pada input yang sama`() {
        val bawaan = ModeSignalEvaluator.evaluate(baseInput(SwingConfig.DEFAULT))
        val ambangNaik = ModeSignalEvaluator.evaluate(baseInput(SwingConfig.DEFAULT.copy(overboughtRsi = 40.0)))

        assertTrue(bawaan != null && ambangNaik != null)
        val r1 = bawaan!!.signal
        val r2 = ambangNaik!!.signal

        // Input sama, config beda: keputusan wajib beda — RSI 48,24 dianggap jenuh beli pada
        // config yang menurunkan ambangnya ke 40 (confidence 36 → 0, hasil probe 2026-11-03).
        assertNotEquals(r1.confidence, r2.confidence)
    }

    @Test
    fun `tanpa config eksplisit = bawaan holder`() {
        val simpan = SwingRuntimeConfig.current
        try {
            SwingRuntimeConfig.current = SwingConfig.DEFAULT
            val tanpaConfig = ModeSignalEvaluator.evaluate(
                ModeSignalInput(
                    mode = StrategyMode.SWING,
                    symbol = "TESTUSDT",
                    price = harga,
                    nowMs = 1_700_010_000_000L,
                    h1Candles = fixtureA()
                )
            )
            val denganDefault = ModeSignalEvaluator.evaluate(baseInput(SwingConfig.DEFAULT))

            assertEquals(
                "Bawaan input wajib membaca holder SwingRuntimeConfig.current",
                denganDefault!!.signal.reasoning,
                tanpaConfig!!.signal.reasoning
            )
            assertEquals(denganDefault.signal.confidence, tanpaConfig.signal.confidence)
        } finally {
            SwingRuntimeConfig.current = simpan
        }
    }
}

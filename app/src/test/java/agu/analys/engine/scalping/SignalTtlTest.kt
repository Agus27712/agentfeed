package agu.analys.engine.scalping

import agu.analys.model.AISignalState
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 — batas TTL harus tepat di `maxBars`, dan `EXPIRED` tidak boleh langsung di-arm ulang
 * oleh setup yang sama. Kontrol negatif ada di `SignalLifecycleManagerTest`.
 */
class SignalTtlTest {

    @Test
    fun evaluate_usesWallClockBarsNotTickCount() {
        val t0 = 1_000_000L
        assertEquals(0L, SignalTtl.evaluate(t0, t0, 5).elapsedBars)
        assertEquals(4L, SignalTtl.evaluate(t0, t0 + 4 * SignalTtl.M1_BAR_MS, 5).elapsedBars)
        assertFalse(SignalTtl.evaluate(t0, t0 + 4 * SignalTtl.M1_BAR_MS + 59_999L, 5).expired)
        assertTrue("5 candle penuh = EXPIRED", SignalTtl.evaluate(t0, t0 + 5 * SignalTtl.M1_BAR_MS, 5).expired)
        assertEquals(0L, SignalTtl.evaluate(t0, t0 + 9 * SignalTtl.M1_BAR_MS, 5).remainingBars)
    }

    @Test
    fun evaluate_handlesClockGoingBackwardsAndZeroLimit() {
        val t0 = 5_000_000L
        val backwards = SignalTtl.evaluate(t0, t0 - 10_000L, 5)
        assertEquals(0L, backwards.elapsedBars)
        assertFalse(backwards.expired)

        val zero = SignalTtl.evaluate(t0, t0 + 60_000L, 0)
        assertEquals(1, zero.maxBars)
        assertTrue("limit 0 diperlakukan 1 candle", zero.expired)
    }

    @Test
    fun setupKey_doesNotChangeWhenOnlyPriceLevelsMove() {
        val base = signal("TREND_PULLBACK", "LONG")
        val movedLevels = base.copy(entryPrice = 101.0, stopLoss = 91.0, targetPrice1 = 111.0)
        assertEquals(SignalTtl.setupKey(base), SignalTtl.setupKey(movedLevels))
    }

    @Test
    fun setupKey_changesWhenSetupOrDirectionChanges() {
        val base = signal("TREND_PULLBACK", "LONG")
        assertNotEquals(SignalTtl.setupKey(base), SignalTtl.setupKey(base.copy(scalpingSetup = "BREAKOUT")))
        assertNotEquals(SignalTtl.setupKey(base), SignalTtl.setupKey(base.copy(scalpingDirection = "SHORT")))
    }

    @Test
    fun hasSetup_rejectsEmptyAndNone() {
        assertFalse(SignalTtl.hasSetup(signal("NONE", "WAIT")))
        assertFalse(SignalTtl.hasSetup(signal("", "WAIT")))
        assertTrue(SignalTtl.hasSetup(signal("BREAKOUT", "LONG")))
    }

    private fun signal(setup: String, direction: String) = AISignalState(
        action = SignalAction.BUY,
        confidence = 80,
        entryPrice = 100.0,
        stopLoss = 90.0,
        targetPrice1 = 110.0,
        scalpingStage = ScalpingStage.EARLY_ENTRY,
        scalpingSetup = setup,
        scalpingDirection = direction
    )
}

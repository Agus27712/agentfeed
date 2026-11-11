package agu.analys.engine.scalping

import agu.analys.config.StrategyMode
import agu.analys.model.AISignalState
import agu.analys.model.LifecycleState
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SignalLifecycleManagerTest {

    private val symbol = "BTCIDR"

    @Before
    fun setUp() {
        SignalLifecycleManager.reset(symbol)
    }

    @Test
    fun testNonScalping_SwingTransitionDetectionAndDeduplication() {
        SignalLifecycleManager.reset(symbol, StrategyMode.SWING)

        // 1st tick: HOLD signal -> state remains IDLE, no notification
        val signalHold = AISignalState(
            action = SignalAction.HOLD,
            confidence = 20,
            entryPrice = 100_000.0,
            stopLoss = 95_000.0,
            targetPrice1 = 110_000.0
        )
        val t1 = SignalLifecycleManager.process(symbol, 100_000.0, signalHold, StrategyMode.SWING)
        assertEquals(LifecycleState.IDLE, t1.state)
        assertFalse(t1.transition?.hasTriggeringTransition == true)

        // 2nd tick: Qualified BUY with moderate confidence (55%) -> Transition to DETECTED (edge trigger)
        val signalDetected = AISignalState(
            action = SignalAction.BUY,
            confidence = 55,
            entryPrice = 101_000.0,
            stopLoss = 96_000.0,
            targetPrice1 = 112_000.0
        )
        val t2 = SignalLifecycleManager.process(symbol, 101_000.0, signalDetected, StrategyMode.SWING)
        assertEquals(LifecycleState.DETECTED, t2.state)
        assertTrue(t2.transition?.isNewDetected == true)
        assertTrue(t2.transition?.hasTriggeringTransition == true)

        // 3rd tick: Same DETECTED state -> No duplicate spam notification
        val t3 = SignalLifecycleManager.process(symbol, 101_200.0, signalDetected.copy(confidence = 58), StrategyMode.SWING)
        assertEquals(LifecycleState.DETECTED, t3.state)
        assertFalse(t3.transition?.isNewDetected == true)
        assertFalse(t3.transition?.hasTriggeringTransition == true)

        // 4th tick: Upgraded to CONFIRMING (68%)
        val signalConfirming = signalDetected.copy(confidence = 68)
        val t4 = SignalLifecycleManager.process(symbol, 102_000.0, signalConfirming, StrategyMode.SWING)
        assertEquals(LifecycleState.CONFIRMING, t4.state)
        assertFalse(t4.transition?.hasTriggeringTransition == true)

        // 5th tick: Upgraded to READY (80%) -> Transition to READY (edge trigger)
        val signalReady = signalDetected.copy(confidence = 80)
        val t5 = SignalLifecycleManager.process(symbol, 102_500.0, signalReady, StrategyMode.SWING)
        assertEquals(LifecycleState.READY, t5.state)
        assertTrue(t5.transition?.isNewReady == true)
        assertTrue(t5.transition?.hasTriggeringTransition == true)

        // 6th tick: Repeated READY tick -> No duplicate notification
        val t6 = SignalLifecycleManager.process(symbol, 102_600.0, signalReady.copy(confidence = 82), StrategyMode.SWING)
        assertEquals(LifecycleState.READY, t6.state)
        assertFalse(t6.transition?.isNewReady == true)
        assertFalse(t6.transition?.hasTriggeringTransition == true)

        // 7th tick: Invalidation (action switches to HOLD) -> State becomes INVALIDATED
        val t7 = SignalLifecycleManager.process(symbol, 102_000.0, signalHold, StrategyMode.SWING)
        assertEquals(LifecycleState.INVALIDATED, t7.state)
        assertFalse(t7.transition?.hasTriggeringTransition == true)

        // 8th tick: Recovering directly to strong BUY (85%) from INVALIDATED -> Edge trigger READY
        val t8 = SignalLifecycleManager.process(symbol, 103_000.0, signalReady.copy(confidence = 85), StrategyMode.SWING)
        assertEquals(LifecycleState.READY, t8.state)
        assertTrue(t8.transition?.isNewReady == true)
        assertTrue(t8.transition?.hasTriggeringTransition == true)
    }

    @Test
    fun testModeIsolation_SameSymbolDifferentModes() {
        // Mode SWING reaches READY
        val swingSignal = AISignalState(action = SignalAction.BUY, confidence = 80)
        val swingTracked = SignalLifecycleManager.process(symbol, 50_000.0, swingSignal, StrategyMode.SWING)
        assertEquals(LifecycleState.READY, swingTracked.state)

        // Mode INTRADAY with neutral signal remains IDLE
        val intradaySignal = AISignalState(action = SignalAction.HOLD, confidence = 20)
        val intradayTracked = SignalLifecycleManager.process(symbol, 50_000.0, intradaySignal, StrategyMode.OFFICE_DAILY)
        assertEquals(LifecycleState.IDLE, intradayTracked.state)

        // Verify SWING state was not overwritten by INTRADAY
        val recheckSwing = SignalLifecycleManager.process(symbol, 50_100.0, swingSignal, StrategyMode.SWING)
        assertEquals(LifecycleState.READY, recheckSwing.state)
    }

    @Test
    fun testScalping_BackwardsCompatibility() {
        SignalLifecycleManager.reset(symbol, StrategyMode.SCALPING)

        // Scalping uses scalpingStage — dan sejak perbaikan P1 wajib disertai pola yang valid
        // (`scalpingSetup != "NONE"`), karena tanpa pola tidak ada stage yang boleh menaikkan
        // siklus hidup.
        val scalpingEarly = AISignalState(
            action = SignalAction.BUY,
            scalpingStage = ScalpingStage.EARLY_ENTRY,
            scalpingSetup = "LIQUIDITY_SWEEP",
            confidence = 60
        )
        val t1 = SignalLifecycleManager.process(symbol, 10_000.0, scalpingEarly, StrategyMode.SCALPING)
        assertEquals(LifecycleState.DETECTED, t1.state)
        assertTrue(t1.transition?.hasTriggeringTransition == true)

        val scalpingStrong = AISignalState(
            action = SignalAction.BUY,
            scalpingStage = ScalpingStage.STRONG_ENTRY,
            scalpingSetup = "BREAKOUT",
            confidence = 90
        )
        val t2 = SignalLifecycleManager.process(symbol, 10_100.0, scalpingStrong, StrategyMode.SCALPING)
        assertEquals(LifecycleState.READY, t2.state)
        assertTrue(t2.transition?.hasTriggeringTransition == true)
    }

    /**
     * Invarian P1: tanpa pola scalping valid, siklus hidup **tidak boleh** naik ke
     * DETECTED / CONFIRMING / READY — apa pun stage yang dikirim engine.
     *
     * Ini reproduksi persis keluhan pengguna: radar berkata "TERDETEKSI" sementara kartu setup
     * berkata "NONE", karena `EARLY_ENTRY` dipromosikan hanya berdasar tekanan orderbook.
     */
    @Test
    fun testScalping_NoSetupNeverPromoted() {
        SignalLifecycleManager.reset(symbol, StrategyMode.SCALPING)

        val noSetup = AISignalState(
            action = SignalAction.HOLD,
            scalpingStage = ScalpingStage.EARLY_ENTRY,
            scalpingSetup = "NONE",
            confidence = 49
        )

        // 1. Persis kasus yang dilaporkan: EARLY_ENTRY + setup NONE -> tetap IDLE.
        val t1 = SignalLifecycleManager.process(symbol, 10_000.0, noSetup, StrategyMode.SCALPING)
        assertEquals(LifecycleState.IDLE, t1.state)
        assertFalse(t1.transition?.isNewDetected == true)

        // 2. Setup kosong (default `""`) juga tidak boleh lolos.
        val t2 = SignalLifecycleManager.process(symbol, 10_010.0, noSetup.copy(scalpingSetup = ""), StrategyMode.SCALPING)
        assertEquals(LifecycleState.IDLE, t2.state)

        // 3. WAIT_PULLBACK tanpa setup ditahan juga.
        val t3 = SignalLifecycleManager.process(
            symbol, 10_020.0, noSetup.copy(scalpingStage = ScalpingStage.WAIT_PULLBACK), StrategyMode.SCALPING
        )
        assertEquals(LifecycleState.IDLE, t3.state)

        // 4. ENTRY & STRONG_ENTRY tanpa setup pun tidak boleh menaikkan status (jaring pengaman).
        val t4 = SignalLifecycleManager.process(
            symbol, 10_030.0, noSetup.copy(scalpingStage = ScalpingStage.ENTRY), StrategyMode.SCALPING
        )
        assertEquals(LifecycleState.IDLE, t4.state)

        val t5 = SignalLifecycleManager.process(
            symbol, 10_040.0, noSetup.copy(scalpingStage = ScalpingStage.STRONG_ENTRY), StrategyMode.SCALPING
        )
        assertEquals(LifecycleState.IDLE, t5.state)

        // 5. Begitu pola valid muncul, DETECTED menjadi sah kembali.
        val t6 = SignalLifecycleManager.process(
            symbol, 10_050.0, noSetup.copy(scalpingSetup = "LIQUIDITY_SWEEP"), StrategyMode.SCALPING
        )
        assertEquals(LifecycleState.DETECTED, t6.state)
        assertTrue(t6.transition?.isNewDetected == true)
    }

    /**
     * Jalur invalidasi **tidak** boleh ikut terpengaruh normalisasi P1: kondisi berbahaya
     * (`HOLD`) harus tetap membatalkan sinyal yang sudah DETECTED, walaupun pola tidak terbaca.
     */
    @Test
    fun testScalping_DangerousHoldStillInvalidatesEvenWithoutSetup() {
        SignalLifecycleManager.reset(symbol, StrategyMode.SCALPING)

        val detected = AISignalState(
            action = SignalAction.BUY,
            scalpingStage = ScalpingStage.EARLY_ENTRY,
            scalpingSetup = "LIQUIDITY_SWEEP",
            confidence = 60
        )
        val t1 = SignalLifecycleManager.process(symbol, 10_000.0, detected, StrategyMode.SCALPING)
        assertEquals(LifecycleState.DETECTED, t1.state)

        // Kondisi berbahaya + pola hilang -> tetap INVALIDATED (bukan berhenti di DETECTED).
        val dangerous = detected.copy(scalpingStage = ScalpingStage.HOLD, scalpingSetup = "NONE")
        val t2 = SignalLifecycleManager.process(symbol, 10_050.0, dangerous, StrategyMode.SCALPING)
        assertEquals(LifecycleState.INVALIDATED, t2.state)
    }

    @Test
    fun testPriceDropBelowStopLoss_InvalidatesState() {
        val signal = AISignalState(
            action = SignalAction.BUY,
            confidence = 80,
            entryPrice = 100.0,
            stopLoss = 90.0,
            targetPrice1 = 120.0
        )
        val t1 = SignalLifecycleManager.process(symbol, 100.0, signal, StrategyMode.SWING)
        assertEquals(LifecycleState.READY, t1.state)

        // Price drops below stop loss (89.0 <= 90.0)
        val t2 = SignalLifecycleManager.process(symbol, 89.0, signal, StrategyMode.SWING)
        assertEquals(LifecycleState.INVALIDATED, t2.state)
        assertFalse(t2.transition?.hasTriggeringTransition == true)
    }

    /**
     * P0 — TTL 5 candle M1. Sebelum perbaikan ini sinyal berumur 5 menit hanya berkedip
     * `EXPIRED` satu tick lalu langsung naik lagi ke DETECTED pada tick yang sama.
     */
    @Test
    fun testScalpingTtl_ExpiresAtConfiguredBarsAndBlocksSameSetup() {
        SignalLifecycleManager.reset(symbol, StrategyMode.SCALPING)
        val cfg = ScalpingRuntimeConfig(ttlBarsM1 = 5)
        val t0 = 10_000_000L
        val setup = AISignalState(
            action = SignalAction.BUY,
            confidence = 80,
            entryPrice = 100.0,
            stopLoss = 90.0,
            targetPrice1 = 120.0,
            scalpingStage = ScalpingStage.EARLY_ENTRY,
            scalpingSetup = "TREND_PULLBACK",
            scalpingDirection = "LONG"
        )

        val detected = SignalLifecycleManager.process(
            symbol, 100.0, setup, StrategyMode.SCALPING, nowMs = t0, config = cfg
        )
        assertEquals(LifecycleState.DETECTED, detected.state)

        // 4 menit: masih hidup.
        val fourMinutes = SignalLifecycleManager.process(
            symbol, 100.0, setup, StrategyMode.SCALPING,
            nowMs = t0 + 4 * SignalTtl.M1_BAR_MS, config = cfg
        )
        assertEquals(LifecycleState.DETECTED, fourMinutes.state)

        // 5 menit tepat: EXPIRED.
        val expired = SignalLifecycleManager.process(
            symbol, 100.0, setup, StrategyMode.SCALPING,
            nowMs = t0 + 5 * SignalTtl.M1_BAR_MS, config = cfg
        )
        assertEquals(LifecycleState.EXPIRED, expired.state)

        // Tick berikutnya dengan setup sama tidak boleh re-arm, walau stage naik ke STRONG_ENTRY.
        val reArmAttempt = SignalLifecycleManager.process(
            symbol, 100.0, setup.copy(scalpingStage = ScalpingStage.STRONG_ENTRY),
            StrategyMode.SCALPING, nowMs = t0 + 5 * SignalTtl.M1_BAR_MS + 1, config = cfg
        )
        assertEquals("Setup sama setelah EXPIRED harus tetap EXPIRED", LifecycleState.EXPIRED, reArmAttempt.state)

        // Pola berbeda = episode baru, boleh masuk.
        val newSetup = setup.copy(scalpingStage = ScalpingStage.STRONG_ENTRY, scalpingSetup = "BREAKOUT")
        val fresh = SignalLifecycleManager.process(
            symbol, 100.0, newSetup, StrategyMode.SCALPING,
            nowMs = t0 + 5 * SignalTtl.M1_BAR_MS + 2, config = cfg
        )
        assertEquals(LifecycleState.READY, fresh.state)
    }

    /**
     * Setup yang sempat hilang (`NONE`) melepas kunci anti re-arm. Ini mencegah aturan
     * terminal berubah menjadi blokir permanen untuk pola yang sama di jam berikutnya.
     */
    @Test
    fun testScalpingTtl_KunciDilepasSaatSetupHilang() {
        SignalLifecycleManager.reset(symbol, StrategyMode.SCALPING)
        val cfg = ScalpingRuntimeConfig(ttlBarsM1 = 5)
        val t0 = 20_000_000L
        val setup = AISignalState(
            action = SignalAction.BUY,
            confidence = 80,
            entryPrice = 100.0,
            stopLoss = 90.0,
            targetPrice1 = 120.0,
            scalpingStage = ScalpingStage.EARLY_ENTRY,
            scalpingSetup = "BREAKOUT",
            scalpingDirection = "LONG"
        )
        SignalLifecycleManager.process(symbol, 100.0, setup, StrategyMode.SCALPING, nowMs = t0, config = cfg)
        val expired = SignalLifecycleManager.process(
            symbol, 100.0, setup, StrategyMode.SCALPING,
            nowMs = t0 + 5 * SignalTtl.M1_BAR_MS, config = cfg
        )
        assertEquals(LifecycleState.EXPIRED, expired.state)

        // Setup hilang selama satu tick.
        val none = setup.copy(scalpingStage = ScalpingStage.WATCH, scalpingSetup = "NONE", action = SignalAction.HOLD)
        SignalLifecycleManager.process(
            symbol, 100.0, none, StrategyMode.SCALPING,
            nowMs = t0 + 5 * SignalTtl.M1_BAR_MS + 1, config = cfg
        )

        // Setup yang sama muncul lagi -> episode baru.
        val fresh = SignalLifecycleManager.process(
            symbol, 100.0, setup.copy(scalpingStage = ScalpingStage.STRONG_ENTRY),
            StrategyMode.SCALPING, nowMs = t0 + 5 * SignalTtl.M1_BAR_MS + 2, config = cfg
        )
        assertEquals(LifecycleState.READY, fresh.state)
    }
}

package agu.analys.engine.scalping

import agu.analys.model.AISignalState
import agu.analys.model.LifecycleState
import agu.analys.model.ScalpingMtfSnapshot
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji model kesiapan scalping (ScalpReadiness).
 *
 * Invarian yang dijaga di sini adalah **perbaikan bug** yang dilaporkan pengguna:
 * radar menampilkan "TERDETEKSI" sementara setup menampilkan "NONE". Penyebabnya ada di
 * `ScalpingMtfEvaluator.kt:246` (`early` tidak memeriksa setup) dan
 * `SignalLifecycleManager.kt:112` (`EARLY_ENTRY -> DETECTED`), sehingga satu kelas
 * ketidaksinkronan muncul di tiga permukaan UI.
 */
class ScalpReadinessTest {

    // ── Helper ──────────────────────────────────────────────────────────────

    /** MTF dengan semua checkpoint lolos — kasus paling "menggoda" untuk salah lapor. */
    private fun mtfAllOk() = ScalpingMtfSnapshot(
        biasOk = true,
        biasDetail = "Ruang naik terbuka.",
        setupOk = true,
        setupDetail = "Orderbook Bid/Ask 1.4x.",
        triggerOk = true,
        triggerDetail = "Setup & VWAP 1M terkonfirmasi.",
        entryPriceOk = true,
        entryPriceDetail = "Net RR 1:2.1 (Valid), skor 82.",
        statusTitle = "BUY READY",
        waitingFor = "Eksekusi"
    )

    private fun signal(
        setup: String,
        action: SignalAction = SignalAction.BUY,
        stage: ScalpingStage = ScalpingStage.ENTRY,
        lifecycle: LifecycleState = LifecycleState.DETECTED,
        mtf: ScalpingMtfSnapshot = mtfAllOk()
    ) = AISignalState(
        action = action,
        scalpingStage = stage,
        lifecycleState = lifecycle,
        scalpingSetup = setup,
        mtf = mtf
    )

    // ── Invarian inti ───────────────────────────────────────────────────────

    @Test
    fun setupNone_neverReportsReadyOrConfirming() {
        // Matriks penuh: 6 lifecycle x 6 stage, semua dengan setup NONE dan MTF "semua lolos".
        for (lifecycle in LifecycleState.entries) {
            for (stage in ScalpingStage.entries) {
                val snap = ScalpReadiness.resolve(
                    signal(setup = ScalpReadiness.SETUP_NONE, stage = stage, lifecycle = lifecycle)
                )
                assertFalse(
                    "lifecycle=$lifecycle stage=$stage tidak boleh READY",
                    snap.state == ScalpReadinessState.READY
                )
                assertFalse(
                    "lifecycle=$lifecycle stage=$stage tidak boleh CONFIRMING",
                    snap.state == ScalpReadinessState.CONFIRMING
                )
                assertFalse(
                    "lifecycle=$lifecycle stage=$stage tidak boleh mengizinkan auto-buy",
                    snap.canAutoBuy
                )
                assertFalse(snap.setupPresent)
            }
        }
    }

    @Test
    fun setupNone_evenWhenLifecycleIsDetected_reportsWatching() {
        // Kasus persis yang dilaporkan: radar bilang "TERDETEKSI", setup bilang "NONE".
        val snap = ScalpReadiness.resolve(
            signal(
                setup = ScalpReadiness.SETUP_NONE,
                stage = ScalpingStage.EARLY_ENTRY,
                lifecycle = LifecycleState.DETECTED
            )
        )
        assertEquals(ScalpReadinessState.WATCHING, snap.state)
        assertEquals(ScalpReadiness.BLOCKER_NO_SETUP, snap.blocker)
        assertEquals("Belum ada setup", snap.setupLabel)
        assertTrue(snap.headline.contains("belum terbentuk"))
    }

    @Test
    fun blankSetup_treatedAsNone() {
        val snap = ScalpReadiness.resolve(signal(setup = "   "))
        assertEquals(ScalpReadinessState.WATCHING, snap.state)
        assertFalse(snap.setupPresent)
    }

    @Test
    fun setupPresent_andEntryPriceOk_andBuy_reportsReady() {
        val snap = ScalpReadiness.resolve(signal(setup = "BREAKOUT_RETEST"))
        assertEquals(ScalpReadinessState.READY, snap.state)
        assertNull(snap.blocker)
        assertTrue(snap.setupPresent)
        assertEquals("BREAKOUT_RETEST", snap.setupLabel)
        assertTrue(snap.canAutoBuy)
    }

    @Test
    fun setupPresent_andTriggerOk_reportsConfirming() {
        // step3Ok terpenuhi (setup + trigger) tetapi step4Ok belum -> engine mengirim HOLD.
        val snap = ScalpReadiness.resolve(
            signal(
                setup = "LIQUIDITY_SWEEP",
                action = SignalAction.HOLD,
                mtf = mtfAllOk().copy(entryPriceOk = false)
            )
        )
        assertEquals(ScalpReadinessState.CONFIRMING, snap.state)
        assertTrue(snap.setupPresent)
        assertFalse(snap.canAutoBuy)
    }

    @Test
    fun actionNotBuy_isNeverReady() {
        // Penjaga terhadap input tak konsisten: MTF bilang lolos, tetapi engine tidak membeli.
        val snap = ScalpReadiness.resolve(
            signal(setup = "BREAKOUT", action = SignalAction.HOLD)
        )
        assertFalse(snap.state == ScalpReadinessState.READY)
        assertFalse(snap.canAutoBuy)
        assertEquals(ScalpReadinessState.CONFIRMING, snap.state)
    }

    @Test
    fun stageBelowMinStage_blocksAutoBuy() {
        val snap = ScalpReadiness.resolve(
            signal(setup = "BREAKOUT", stage = ScalpingStage.EARLY_ENTRY),
            minStage = ScalpingStage.ENTRY
        )
        assertFalse(snap.canAutoBuy)

        val loose = ScalpReadiness.resolve(
            signal(setup = "BREAKOUT", stage = ScalpingStage.EARLY_ENTRY),
            minStage = ScalpingStage.EARLY_ENTRY
        )
        assertTrue(loose.canAutoBuy)
    }

    @Test
    fun extremeVolatility_reportsBlocked_evenWithSetup() {
        val snap = ScalpReadiness.resolve(
            signal(
                setup = "BREAKOUT",
                mtf = mtfAllOk().copy(extremeVolatility = true)
            )
        )
        assertEquals(ScalpReadinessState.BLOCKED, snap.state)
        assertEquals(ScalpReadiness.BLOCKER_EXTREME_VOL, snap.blocker)
    }

    @Test
    fun hasPaperPosition_reportsHolding() {
        val snap = ScalpReadiness.resolve(
            signal(setup = ScalpReadiness.SETUP_NONE),
            hasPaperPosition = true
        )
        assertEquals(ScalpReadinessState.HOLDING, snap.state)
        assertNull(snap.blocker)
    }

    @Test
    fun noSetup_usesWaitingForAsBlocker() {
        val snap = ScalpReadiness.resolve(
            signal(
                setup = "TREND_PULLBACK",
                action = SignalAction.HOLD,
                mtf = mtfAllOk().copy(
                    triggerOk = false,
                    entryPriceOk = false,
                    waitingFor = "Menunggu trigger VWAP"
                )
            )
        )
        assertEquals(ScalpReadinessState.WATCHING, snap.state)
        assertEquals("Menunggu trigger VWAP", snap.blocker)
    }

    @Test
    fun confirming_blamesNetRrGate_notTrigger() {
        // Saat setup + trigger sudah valid, penghalang yang benar adalah gate R:R / skor,
        // bukan "menunggu trigger".
        val snap = ScalpReadiness.resolve(
            signal(
                setup = "BREAKOUT_RETEST",
                action = SignalAction.HOLD,
                mtf = mtfAllOk().copy(
                    entryPriceOk = false,
                    entryPriceDetail = "Menunggu Net RR optimal (Min 1:2.0)."
                )
            )
        )
        assertEquals(ScalpReadinessState.CONFIRMING, snap.state)
        assertEquals("Menunggu Net RR optimal (Min 1:2.0).", snap.blocker)
    }

    // ── Checklist ───────────────────────────────────────────────────────────

    @Test
    fun checkpoints_areFiveAndOrdered() {
        val cps = ScalpReadiness.checkpoints(signal(setup = "BREAKOUT"))
        assertEquals(5, cps.size)
        assertEquals(listOf(1, 2, 3, 4, 5), cps.map { it.index })
        assertEquals(
            listOf(
                "Bias & Ruang Naik",
                "Setup Scalping",
                "Tekanan Orderbook",
                "Trigger VWAP / VSA",
                "Net R:R & Skor"
            ),
            cps.map { it.label }
        )
    }

    @Test
    fun checkpoints_secondRowReflectsSetupPresence() {
        val withoutSetup = ScalpReadiness.checkpoints(signal(setup = ScalpReadiness.SETUP_NONE))
        assertFalse(withoutSetup[1].ok)
        assertTrue(withoutSetup[1].detail.contains("Belum ada setup"))

        val withSetup = ScalpReadiness.checkpoints(signal(setup = "LIQUIDITY_SWEEP"))
        assertTrue(withSetup[1].ok)
        assertTrue(withSetup[1].detail.contains("LIQUIDITY_SWEEP"))
    }

    @Test
    fun checkpoints_thirdRowIsOrderbook_notSetup() {
        // Baris 3 harus mengikuti tekanan orderbook (setupOk), terpisah dari baris 2.
        val orderbookOnly = ScalpReadiness.checkpoints(
            signal(
                setup = ScalpReadiness.SETUP_NONE,
                mtf = mtfAllOk().copy(triggerOk = false, entryPriceOk = false)
            )
        )
        assertFalse(orderbookOnly[1].ok)   // pola belum ada
        assertTrue(orderbookOnly[2].ok)    // orderbook sudah mendukung
    }

    @Test
    fun hasSetup_acceptsOnlyRealSetupNames() {
        assertFalse(ScalpReadiness.hasSetup(signal(setup = "NONE")))
        assertFalse(ScalpReadiness.hasSetup(signal(setup = "none")))
        assertFalse(ScalpReadiness.hasSetup(signal(setup = "")))
        assertFalse(ScalpReadiness.hasSetup(signal(setup = "  ")))
        assertTrue(ScalpReadiness.hasSetup(signal(setup = "BREAKOUT")))
        assertTrue(ScalpReadiness.hasSetup(signal(setup = "TREND_PULLBACK")))
    }
}

package agu.analys.engine.scalping

import agu.analys.engine.TestData
import agu.analys.model.CandleBar
import agu.analys.model.MarketRegime
import agu.analys.model.RegimeSnapshot
import agu.analys.model.ScalpSetupType
import agu.analys.model.SignalAction
import agu.analys.model.StructureBias
import agu.analys.model.StructureSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Penguji perbaikan strategi **S1–S4 & S8–S9** (tindak lanjut audit GNS/USDT & AMP/USDT).
 *
 * Setiap uji memakai angka dari kasus nyata supaya regresi mudah ditelusuri:
 *  - AMP/USDT: RVOL 0,20x lolos BREAKOUT, change24h +28,09%, ATR 2,03%.
 *  - GNS/USDT: ATR 3,31%, trailing 0,15% tersapu wick, BE 0,35% di bawah noise.
 */
class ScalpStrategyFixTest {

    private fun bar(i: Int, o: Double, h: Double, l: Double, c: Double): CandleBar =
        CandleBar(i * 60_000L, o, h, l, c, 1000.0)

    // ── S1 — RVOL wajib untuk BREAKOUT ───────────────────────────────────────────────────

    private val s1Config: ScalpingRuntimeConfig = ScalpingRuntimeConfig.current.copy(
        rvolBreakoutMin = 1.2,
        buyPressureBreakout = 1.05
    )

    private fun breakoutRegime() = RegimeSnapshot(
        regime = MarketRegime.BREAKOUT,
        adx = 30.0,
        chop = 40.0,
        atrPct = 1.5,
        emaAlignment = "mixed"
    )

    /** Struktur datar: tidak ada sweep, BOS, maupun resistance — hanya jalur BREAKOUT yang hidup. */
    private fun neutralStructure() = StructureSnapshot(
        bias = StructureBias.NEUTRAL,
        pattern = "RANGING"
    )

    /** M1 melemah: EMA9 di bawah EMA21, jadi `momentumBullish` = false. */
    private fun weakeningM1(price: Double): List<CandleBar> =
        TestData.generateCandles(20, price * 1.05, -0.002)

    /**
     * Regresi AMP/USDT — breakout dengan volume mengering (RVOL 0,20x vs gate 1,2x) **dulu
     * tetap lolos** karena pada regime BREAKOUT cukup "1 dari 3 konfirmasi" (arus beli saja).
     * Breakout tanpa lonjakan volume bukan breakout — itu harga bergerak di pasar sepi.
     */
    @Test
    fun s1_breakoutDitolak_bilaRvolDiBawahGate_walauRegimeBreakout() {
        val res = ScalpSetupDetector.detect(
            ScalpSetupDetector.Input(
                price = 100.0,
                structure = neutralStructure(),
                regime = breakoutRegime(),
                rvol = 0.20, // AMP/USDT: partisipasi mengering di puncak
                buyPressure = 1.60, // arus beli kuat → dulu cukup untuk lolos
                m1Candles = weakeningM1(100.0),
                config = s1Config
            )
        )
        assertNotEquals(
            "Breakout tanpa partisipasi volume tidak boleh lolos (regresi AMP RVOL 0,20x)",
            ScalpSetupType.BREAKOUT,
            res.setup
        )
    }

    /** Sebaliknya: RVOL di atas gate tetap menghasilkan BREAKOUT. */
    @Test
    fun s1_breakoutLolos_bilaRvolDiAtasGate() {
        val res = ScalpSetupDetector.detect(
            ScalpSetupDetector.Input(
                price = 100.0,
                structure = neutralStructure(),
                regime = breakoutRegime(),
                rvol = 1.45, // GNS/USDT setelah perbaikan basis volume
                buyPressure = 1.60,
                m1Candles = weakeningM1(100.0),
                config = s1Config
            )
        )
        assertEquals(ScalpSetupType.BREAKOUT, res.setup)
    }

    // ── S2 — lantai profit tutup (≥ 2× friksi) ───────────────────────────────────────────

    /**
     * Kasus AMP: posisi ditutup di +1,50% kotor → 54% gerakan dimakan bursa.
     * Dengan lantai 2× friksi (≈1,94% kotor untuk Tokocrypto USDT taker), exit profit
     * sukarela maupun peng-arm-an BE/trailing di +1,5% tidak boleh terjadi.
     */
    @Test
    fun s2_earlyProfitDanBe_diblokirDiBawahLantaiFriksi() {
        val fee = 0.4044
        val slip = 0.08
        val bars = listOf(bar(0, 100.0, 100.2, 99.8, 100.1)) +
            (1..20).map { i -> bar(i, 101.5, 101.6, 101.4, 101.5) }

        fun run(multiple: Double): PaperExitDecision = PaperExitResolver.resolve(
            bars = bars,
            entryIndex = 0,
            entry = 100.0,
            stopLoss = 96.0,
            tp1 = 104.0,
            tp2 = 110.0,
            buyFeePct = fee,
            sellFeePct = fee,
            slippagePct = slip,
            params = ScalpExitParams(
                mode = ScalpExitMode.FULL,
                minEarlyProfitNetPct = 0.30,
                breakevenTriggerNetPct = 0.35,
                trailPct = 0.15,
                maxHoldBarsM1 = 0,
                minProfitFrictionMultiple = multiple
            ),
            lookaheadBars = 120
        )

        // Lantai nonaktif (perilaku lama): profit tipis langsung dikunci.
        assertEquals(PaperExitReason.EARLY_PROFIT, run(0.0).reason)
        // Lantai aktif: +1,5% kotor (≈0,53% net) belum layak dibawa pulang.
        assertEquals(PaperExitReason.TIMEOUT, run(2.0).reason)
    }

    @Test
    fun s2_lantaiFriksi_dihitungDariBiayaRoundTrip() {
        val params = ScalpExitParams(minProfitFrictionMultiple = 2.0)
        // (0,4044 + 0,4044 + 2×0,08) × 2 = 1,9376% kotor
        assertEquals(1.9376, params.minProfitFloorGrossPct(0.4044, 0.4044, 0.08), 1e-9)
        assertEquals(0.0, params.copy(minProfitFrictionMultiple = 0.0)
            .minProfitFloorGrossPct(0.4044, 0.4044, 0.08), 1e-9)
    }

    // ── S3 — SL keras eksplisit & bisa dikonfigurasi ─────────────────────────────────────

    /** Batas keras SL mengikuti `maxRiskAtr`; memperkecilnya memperpendek jarak SL. */
    @Test
    fun s3_batasKerasSl_mengikutiMaxRiskAtr() {
        fun riskPct(maxRiskAtr: Double): Double {
            val out = ScalpingRiskEngine.evaluate(
                ScalpingRiskEngine.Input(
                    price = 100.0,
                    setup = ScalpSetupType.BREAKOUT,
                    // Support jauh → SL struktur harus dipangkas oleh batas keras.
                    structure = StructureSnapshot(support = 80.0),
                    regime = RegimeSnapshot(regime = MarketRegime.BREAKOUT, atrPct = 2.0),
                    minFrictionMultiple = 0.0,
                    minRiskPct = 0.5,
                    maxRiskAtr = maxRiskAtr
                )
            )
            val lv = out.levels!!
            return (lv.entryZone.high - lv.stopLoss) / lv.entryZone.high * 100.0
        }

        val wide = riskPct(2.0)
        val tight = riskPct(1.0)
        assertTrue("SL 1×ATR wajib lebih rapat dari 2×ATR (tight=$tight, wide=$wide)", tight < wide)
        // 2 × ATR 2,0% → ≈4% dari entry (± tolerasi pembulatan ATR).
        assertTrue("SL 2×ATR harus ≈4%, bukan mengikuti support 80 (got $wide)", wide in 3.5..4.5)
    }

    // ── S4 — konstanta ATR_PCT_* benar-benar dipakai ─────────────────────────────────────

    @Test
    fun s4_bandAtrScalp_dipakaiBukanSekadarDeklarasi() {
        assertTrue(ScalpingConfig.isAtrInScalpSweetBand(1.0))
        assertFalse(ScalpingConfig.isAtrInScalpSweetBand(0.1)) // terlalu tenang
        assertFalse(ScalpingConfig.isAtrInScalpSweetBand(2.03)) // AMP: terlalu liar untuk stop rapat
        assertFalse(ScalpingConfig.isAtrInScalpSweetBand(3.31)) // GNS
        assertEquals("terlalu liar", ScalpingConfig.atrBandLabel(2.03))
        assertEquals("terlalu tenang", ScalpingConfig.atrBandLabel(0.1))
        assertEquals("ideal", ScalpingConfig.atrBandLabel(1.0))
    }

    // ── S8 — BE & trailing proporsional ATR ──────────────────────────────────────────────

    /**
     * Kasus GNS: `trail_pct = 0,15%` ≈ 1/22 ATR (ATR 3,31%) — tersapu satu wick normal,
     * sehingga engine keluar +0,86% padahal harga lanjut ke +7,09%.
     * Dengan `trail_atr_fraction = 0,6` jaraknya 1,99% dan wick 0,4% tidak menyentuh SL.
     */
    @Test
    fun s8_jarakTrailing_proporsionalAtr() {
        val params = ScalpExitParams(
            mode = ScalpExitMode.TRAIL,
            trailPct = 0.15,
            trailAtrFraction = 0.6,
            beTriggerAtrFraction = 0.5,
            breakevenTriggerNetPct = 0.35,
            minProfitFrictionMultiple = 0.0,
            minEarlyProfitNetPct = 5.0 // nonaktifkan early agar yang diuji murni trailing
        )
        // Tanpa ATR → jatuh ke nilai persen lama.
        assertEquals(0.15, params.effectiveTrailDistancePct(0.0), 1e-9)
        assertEquals(0.35, params.effectiveBreakevenTriggerNetPct(0.0), 1e-9)
        // Dengan ATR 3,31% (GNS) → 0,6 × 3,31 = 1,986% dan BE di 1,655% net.
        assertEquals(1.986, params.effectiveTrailDistancePct(3.31), 1e-3)
        assertEquals(1.655, params.effectiveBreakevenTriggerNetPct(3.31), 1e-3)

        val bars = listOf(
            bar(0, 100.0, 100.5, 99.5, 100.2), // entry
            bar(1, 100.2, 103.0, 100.0, 102.8), // spike ke +3%
            bar(2, 102.8, 102.9, 102.5, 102.6) // wick turun 0,4% dari peak
        ) + (3..25).map { i -> bar(i, 102.6, 102.8, 102.4, 102.6) }

        fun run(atrPct: Double) = PaperExitResolver.resolve(
            bars = bars,
            entryIndex = 0,
            entry = 100.0,
            stopLoss = 97.0,
            tp1 = 104.0,
            tp2 = 110.0,
            buyFeePct = 0.4044,
            sellFeePct = 0.4044,
            slippagePct = 0.08,
            params = params,
            lookaheadBars = 120,
            atrPct = atrPct
        )

        assertEquals(PaperExitReason.TRAIL_SL, run(0.0).reason) // 0,15% → tersapu wick
        assertEquals(PaperExitReason.TIMEOUT, run(3.31).reason) // 1,99% → posisi selamat
    }

    // ── S9 — gerbang extended move ───────────────────────────────────────────────────────

    /**
     * Kasus AMP: masuk saat koin sudah +28,09% dalam 24 jam — mengejar ekor kenaikan,
     * bukan breakout segar. Gerbang menahan eksekusi dengan alasan yang tercatat.
     */
    @Test
    fun s9_gerbangExtendedMove_menahanSinyal() {
        val h1 = TestData.generateCandles(100, 900.0, 0.001)
        val m15 = TestData.generateCandles(100, 950.0, 0.0005)
        val m1 = TestData.generateCandles(100, 980.0, 0.0002).toMutableList()
        val lastClose = m1.last().close
        val t = m1.last().timestamp
        m1.add(CandleBar(t + 60_000, lastClose, lastClose + 1.0, lastClose - 2.0, lastClose - 1.0, 1500.0))
        m1.add(CandleBar(t + 120_000, lastClose - 1.0, lastClose + 1.0, lastClose - 2.0, lastClose - 0.5, 1600.0))
        m1.add(CandleBar(t + 180_000, lastClose - 0.5, lastClose + 4.0, lastClose - 1.0, lastClose + 3.0, 4000.0))
        val price = m1.last().close
        val cfg = ScalpingRuntimeConfig.current.copy(maxChange24hPct = 25.0)

        val base = ScalpingMtfEvaluator.evaluate(price, h1, m15, m1, config = cfg)
        assertNotEquals("STEP0_EXTENDED_MOVE", base?.audit?.rejectionReason)

        val extended = ScalpingMtfEvaluator.evaluate(
            price, h1, m15, m1, config = cfg, change24h = 28.09
        )!!
        assertEquals("STEP0_EXTENDED_MOVE", extended.audit.rejectionReason)
        assertEquals(SignalAction.HOLD, extended.signal.action)
        assertTrue(
            "Alasan harus menyebut kenaikan 24 jam: ${extended.scalping.reasoning}",
            extended.scalping.reasoning.any { it.contains("24 jam") }
        )

        // `0` = gerbang nonaktif (mis. jalur replay yang tidak punya data 24 jam).
        val gateOff = ScalpingMtfEvaluator.evaluate(
            price, h1, m15, m1, config = cfg.copy(maxChange24hPct = 0.0), change24h = 28.09
        )!!
        assertNotEquals("STEP0_EXTENDED_MOVE", gateOff.audit.rejectionReason)
    }

    // ── Konfigurasi baru: wajib ikut ekspor/impor JSON ───────────────────────────────────

    @Test
    fun konfigurasiBaru_roundTripJson() {
        val cfg = ScalpingRuntimeConfig.current.copy(maxRiskAtr = 1.5, maxChange24hPct = 20.0)
        val back = ScalpingRuntimeConfig.fromJson(cfg.toJson())
        assertEquals(1.5, back.maxRiskAtr, 1e-9)
        assertEquals(20.0, back.maxChange24hPct, 1e-9)

        val p = ScalpExitParams(
            mode = ScalpExitMode.FULL,
            minProfitFrictionMultiple = 2.5,
            beTriggerAtrFraction = 0.5,
            trailAtrFraction = 0.6
        )
        val pb = ScalpExitParams.fromJson(p.toJson())
        assertEquals(ScalpExitMode.FULL, pb.mode)
        assertEquals(2.5, pb.minProfitFrictionMultiple, 1e-9)
        assertEquals(0.5, pb.beTriggerAtrFraction, 1e-9)
        assertEquals(0.6, pb.trailAtrFraction, 1e-9)
    }
}

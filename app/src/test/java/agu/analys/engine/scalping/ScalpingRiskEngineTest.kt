package agu.analys.engine.scalping

import agu.analys.config.FeeCalculator
import agu.analys.config.TradingFeeConfig
import agu.analys.model.MarketRegime
import agu.analys.model.RegimeSnapshot
import agu.analys.model.ScalpSetupType
import agu.analys.model.StructureBias
import agu.analys.model.StructureSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpingRiskEngineTest {

    private val tokoFees = TradingFeeConfig(0.10, 0.10, 0.10, 0.10)
    private val highFees = TradingFeeConfig(0.11, 0.21, 0.32, 0.42)

    private fun regime(atrPct: Double = 1.0) = RegimeSnapshot(
        regime = MarketRegime.TRENDING_UP,
        adx = 28.0,
        chop = 35.0,
        atrPct = atrPct
    )

    private fun structure(price: Double) = StructureSnapshot(
        bias = StructureBias.BULLISH,
        support = price * 0.985,
        lastSwingLow = price * 0.985
    )

    private fun input(
        price: Double = 1_000_000.0,
        setup: ScalpSetupType = ScalpSetupType.BREAKOUT_RETEST,
        fees: TradingFeeConfig = tokoFees,
        atrPct: Double = 1.0,
        structure: StructureSnapshot = structure(price)
    ) = ScalpingRiskEngine.Input(
        price = price,
        setup = setup,
        structure = structure,
        regime = regime(atrPct),
        fees = fees
    )

    @Test
    fun entryZone_lowNotAboveHigh() {
        val r = ScalpingRiskEngine.evaluate(input())
        val z = r.levels!!.entryZone
        assertTrue(z.low <= z.high)
        assertTrue(z.low > 0.0)
        assertTrue(z.confirmationTrigger.isNotBlank())
    }

    @Test
    fun levelsOrder_slBelowZone_tp1AboveEntry_tp2AboveTp1() {
        val l = ScalpingRiskEngine.evaluate(input()).levels!!
        assertTrue(l.stopLoss < l.entryZone.low)
        assertTrue(l.takeProfit1 > l.entryZone.high)
        assertTrue(l.takeProfit2 > l.takeProfit1)
        assertEquals(l.stopLoss, l.entryZone.invalidation, 0.0)
    }

    @Test
    fun netRr_matchesFeeCalculator() {
        val l = ScalpingRiskEngine.evaluate(input()).levels!!
        val expected = FeeCalculator.roundTrip(
            entry = l.entryZone.high,
            stopLoss = l.stopLoss,
            takeProfit = l.takeProfit2,
            fees = tokoFees,
            useMaker = false,
            slippagePct = ScalpingRiskEngine.DEFAULT_SLIPPAGE_PCT
        )
        assertEquals(expected.netRr, l.netRr, 1e-9)
        assertTrue(l.netRr > 0.0)
    }

    @Test
    fun defaultMinNetRr_is115() {
        assertEquals(1.15, ScalpingRiskEngine.DEFAULT_MIN_NET_RR, 0.0)
    }

    @Test
    fun tokocryptoFees_validPlan_whenNetRrPasses() {
        val r = ScalpingRiskEngine.evaluate(input())
        assertTrue("netRr=${r.levels!!.netRr}", r.valid)
        assertTrue(r.levels!!.netRr >= 1.15)
    }

    @Test
    fun highFees_pushTargetFurther_butStillValid() {
        val toko = ScalpingRiskEngine.evaluate(input(fees = tokoFees)).levels!!
        val high = ScalpingRiskEngine.evaluate(input(fees = highFees)).levels!!
        // Fee lebih mahal → TP2 lebih jauh agar Net R:R tetap tercapai
        assertTrue(high.rewardPct > toko.rewardPct)
        assertTrue(high.netRr >= 1.15)
    }

    @Test
    fun extremeFees_widenSlToFrictionFloor_planStaysConsistent() {
        // Sebelum 2026-10-12 engine menolak ("target terlalu jauh") saat fee ekstrem.
        // Sekarang engine justru melebarkan SL ke lantai friksi, lalu target tetap konsisten.
        val extreme = TradingFeeConfig(0.5, 0.5, 0.5, 0.5)
        val r = ScalpingRiskEngine.evaluate(input(fees = extreme, atrPct = 0.2))
        assertNotNull(r.levels)
        // friksi = 0.5+0.5+2*0.08 = 1.16% ; lantai 6x = 6.96%
        assertTrue("riskPct=${r.levels!!.riskPct}", r.levels!!.riskPct >= 6.96 - 0.05)
        assertTrue(r.levels!!.netRr >= ScalpingRiskEngine.DEFAULT_MIN_NET_RR)
        assertTrue(r.valid)
        // Engine harus jujur memberi tahu ini bukan scalp cepat.
        assertTrue(r.reasons.any { it.contains("friksi") })
    }

    @Test
    fun riskFloor_minRiskPct_applied() {
        val l = ScalpingRiskEngine.evaluate(input(atrPct = 0.1)).levels!!
        assertTrue(l.riskPct >= 0.5 - 1e-9)
    }

    @Test
    fun frictionFloor_raisesRiskAboveAbsoluteAndAtrFloor() {
        // Fee riil Tokocrypto USDT: 0.4044%/sisi -> friksi 0.9688%, lantai 6x = 5.81%
        val real = TradingFeeConfig(0.4044, 0.4044, 0.4044, 0.4044)
        val l = ScalpingRiskEngine.evaluate(input(fees = real, atrPct = 0.3)).levels!!
        assertTrue("riskPct=${l.riskPct}", l.riskPct >= 5.81 - 0.05)
    }

    @Test
    fun frictionFloor_zero_revertsToLegacyBehaviour() {
        val real = TradingFeeConfig(0.4044, 0.4044, 0.4044, 0.4044)
        val r = ScalpingRiskEngine.evaluate(
            ScalpingRiskEngine.Input(
                price = 1_000_000.0,
                setup = ScalpSetupType.BREAKOUT_RETEST,
                structure = structure(1_000_000.0),
                regime = regime(0.3),
                fees = real,
                minFrictionMultiple = 0.0
            )
        )
        // Tanpa lantai friksi, risk kembali ke orde < 1% (perilaku lama yang terbukti -EV).
        assertTrue("riskPct=${r.levels!!.riskPct}", r.levels!!.riskPct < 1.0)
    }

    @Test
    fun resistanceClose_capsTp1_andInvalid() {
        val price = 1_000_000.0
        val s = structure(price).copy(resistance = price * 1.004)
        val r = ScalpingRiskEngine.evaluate(input(price = price, structure = s))
        assertTrue(r.levels!!.takeProfit1 < price * 1.004)
        assertFalse(r.valid)
        assertTrue(r.reasons.any { it.contains("Resistance dekat") })
    }

    @Test
    fun idrAndUsdtScale_samePercentages() {
        val idr = ScalpingRiskEngine.evaluate(input(price = 1_000_000_000.0)).levels!!
        val usdt = ScalpingRiskEngine.evaluate(input(price = 0.5)).levels!!
        assertEquals(idr.riskPct, usdt.riskPct, 1e-6)
        assertEquals(idr.rewardPct, usdt.rewardPct, 1e-6)
        assertEquals(idr.netRr, usdt.netRr, 1e-6)
    }

    @Test
    fun slDistance_clampedToAtrBounds_whenFrictionFloorDisabled() {
        val price = 1_000_000.0
        fun noFloor(atrPct: Double, st: StructureSnapshot) = ScalpingRiskEngine.evaluate(
            ScalpingRiskEngine.Input(
                price = price,
                setup = ScalpSetupType.BREAKOUT_RETEST,
                structure = st,
                regime = regime(atrPct),
                fees = tokoFees,
                minFrictionMultiple = 0.0
            )
        )
        // Support sangat jauh -> SL dibatasi 2x ATR (saat lantai friksi dimatikan)
        val far = structure(price).copy(support = price * 0.80, lastSwingLow = price * 0.80)
        val l = noFloor(1.0, far).levels!!
        val atr = price * 0.01
        assertTrue(l.entryZone.high - l.stopLoss <= 2.0 * atr + 1e-6)

        // Tanpa struktur -> pakai ATR default
        val l2 = noFloor(1.0, StructureSnapshot()).levels!!
        assertTrue(l2.entryZone.high - l2.stopLoss >= 0.8 * atr - 1e-6)
    }

    @Test
    fun slDistance_mayExceedAtrCap_whenFrictionFloorDominates() {
        // Dengan lantai friksi aktif, SL boleh lebih lebar dari 2x ATR — itu memang tujuannya:
        // risiko harus menutup friksi, bukan mengikuti volatilitas semata.
        val real = TradingFeeConfig(0.4044, 0.4044, 0.4044, 0.4044)
        val l = ScalpingRiskEngine.evaluate(input(fees = real, atrPct = 0.3)).levels!!
        val atr = l.entryZone.high * 0.003
        assertTrue(l.entryZone.high - l.stopLoss > 2.0 * atr)
    }

    @Test
    fun missingAtr_usesFallback_noCrash() {
        val r = ScalpingRiskEngine.evaluate(input(atrPct = 0.0))
        assertNotNull(r.levels)
        assertTrue(r.reasons.any { it.contains("ATR belum tersedia") })
    }

    @Test
    fun invalidPriceOrNoSetup_returnsNullLevels() {
        val a = ScalpingRiskEngine.evaluate(input(price = 0.0))
        assertNull(a.levels)
        assertFalse(a.valid)

        val b = ScalpingRiskEngine.evaluate(input(setup = ScalpSetupType.NONE))
        assertNull(b.levels)
        assertFalse(b.valid)
    }

    @Test
    fun feeNote_notEmpty() {
        val l = ScalpingRiskEngine.evaluate(input()).levels!!
        assertTrue(l.feeSlippageNote.contains("Fee"))
        assertTrue(l.riskPct > 0.0 && l.rewardPct > 0.0)
    }
}

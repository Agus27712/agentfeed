package agu.analys.engine.scalping

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtfAlignmentPolicyTest {

    private fun matrix(
        h1: MtfConfluenceMatrix.TfBias,
        m15: MtfConfluenceMatrix.TfBias,
        m5: MtfConfluenceMatrix.TfBias,
        m1: MtfConfluenceMatrix.TfBias
    ) = MtfConfluenceMatrix.fromLegs(
        listOf(
            MtfConfluenceMatrix.TfLeg("1H", h1),
            MtfConfluenceMatrix.TfLeg("15M", m15),
            MtfConfluenceMatrix.TfLeg("5M", m5),
            MtfConfluenceMatrix.TfLeg("1M", m1)
        )
    )

    @Test
    fun threeOfFourBullish_allowsLongEvenWhenLowerTfPullsBack() {
        val decision = MtfAlignmentPolicy.evaluate(
            matrix(
                h1 = MtfConfluenceMatrix.TfBias.BULLISH,
                m15 = MtfConfluenceMatrix.TfBias.BULLISH,
                m5 = MtfConfluenceMatrix.TfBias.NEUTRAL,
                m1 = MtfConfluenceMatrix.TfBias.BULLISH
            ),
            ScalpingRuntimeConfig(mtfMinBullish = 3)
        )
        assertTrue(decision.reason, decision.allowsLong)
    }

    @Test
    fun highTimeframeBearish_blocksEvenWhenCountWouldAllow() {
        val decision = MtfAlignmentPolicy.evaluate(
            matrix(
                h1 = MtfConfluenceMatrix.TfBias.BULLISH,
                m15 = MtfConfluenceMatrix.TfBias.BEARISH,
                m5 = MtfConfluenceMatrix.TfBias.BULLISH,
                m1 = MtfConfluenceMatrix.TfBias.BULLISH
            ),
            ScalpingRuntimeConfig(mtfMinBullish = 3)
        )
        assertFalse("H1/M15 bearish harus memblokir LONG", decision.allowsLong)
        assertTrue(decision.highTfBearish)
    }

    @Test
    fun onlyTwoBullish_isRejected() {
        val decision = MtfAlignmentPolicy.evaluate(
            matrix(
                h1 = MtfConfluenceMatrix.TfBias.BULLISH,
                m15 = MtfConfluenceMatrix.TfBias.BULLISH,
                m5 = MtfConfluenceMatrix.TfBias.NEUTRAL,
                m1 = MtfConfluenceMatrix.TfBias.NEUTRAL
            ),
            ScalpingRuntimeConfig(mtfMinBullish = 3)
        )
        assertFalse(decision.allowsLong)
    }

    @Test
    fun missingTf_failsClosedInsteadOfCountingAvailableOnly() {
        val decision = MtfAlignmentPolicy.evaluate(
            matrix(
                h1 = MtfConfluenceMatrix.TfBias.BULLISH,
                m15 = MtfConfluenceMatrix.TfBias.BULLISH,
                m5 = MtfConfluenceMatrix.TfBias.UNKNOWN,
                m1 = MtfConfluenceMatrix.TfBias.BULLISH
            ),
            ScalpingRuntimeConfig(mtfMinBullish = 3)
        )
        assertFalse("3/4 tidak boleh dihitung dari 3 TF yang tersedia", decision.allowsLong)
        assertTrue(decision.reason.contains("belum lengkap"))
    }

    @Test
    fun extraDailyLegsCannotMaskMissingM5() {
        // Matriks penuh bisa memuat 1D/4H. TF tambahan itu tidak boleh membuat knownCount
        // terlihat >= 4 ketika M5 (salah satu dari empat TF p0) tidak punya data.
        val full = MtfConfluenceMatrix.fromLegs(
            listOf(
                MtfConfluenceMatrix.TfLeg("1D", MtfConfluenceMatrix.TfBias.BULLISH),
                MtfConfluenceMatrix.TfLeg("4H", MtfConfluenceMatrix.TfBias.BULLISH),
                MtfConfluenceMatrix.TfLeg("1H", MtfConfluenceMatrix.TfBias.BULLISH),
                MtfConfluenceMatrix.TfLeg("15M", MtfConfluenceMatrix.TfBias.BULLISH),
                MtfConfluenceMatrix.TfLeg("1M", MtfConfluenceMatrix.TfBias.BULLISH)
            )
        )
        val decision = MtfAlignmentPolicy.evaluate(
            full, ScalpingRuntimeConfig(mtfMinBullish = 3)
        )
        assertFalse("M5 hilang harus fail-closed walau 1D/4H tersedia", decision.allowsLong)
        assertTrue(decision.reason.contains("belum lengkap"))
    }

    @Test
    fun disabledGate_allowsEvenWithoutSupport() {
        val decision = MtfAlignmentPolicy.evaluate(
            matrix(
                h1 = MtfConfluenceMatrix.TfBias.BEARISH,
                m15 = MtfConfluenceMatrix.TfBias.BEARISH,
                m5 = MtfConfluenceMatrix.TfBias.BEARISH,
                m1 = MtfConfluenceMatrix.TfBias.BEARISH
            ),
            ScalpingRuntimeConfig(mtfMinBullish = 0)
        )
        assertTrue(decision.allowsLong)
    }
}

package agu.analys.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalLogSetupStatsTest {

    private fun log(
        setup: String,
        status: String,
        pnl: Double? = null,
        category: String = "STRONG",
        score: Int = 80
    ) = SignalLogEntity(
        symbol = "BTCUSDT",
        action = "BUY",
        confidence = 70,
        entryPrice = 100.0,
        outcomeStatus = status,
        realizedPnlPct = pnl,
        scalpingSetup = setup,
        scalpingScore = score,
        scalpingScoreCategory = category
    )

    @Test
    fun emptyLogs_noStats() {
        assertTrue(SignalLogRepository.calculateSetupStats(emptyList()).isEmpty())
    }

    @Test
    fun legacyLogs_withoutSetup_areSkipped() {
        val logs = listOf(log("", "HIT_TP1", 1.0, category = ""), log("", "HIT_SL", -1.0, category = ""))
        assertTrue(SignalLogRepository.calculateSetupStats(logs).isEmpty())
        assertTrue(SignalLogRepository.calculateScoreCategoryStats(logs).isEmpty())
        assertEquals(2, SignalLogRepository.calculateReliabilitySummary(logs).legacySignalCount)
    }

    @Test
    fun groupsBySetup_winRateAndAvgReturn() {
        val logs = listOf(
            log("BREAKOUT", "HIT_TP1", 2.0),
            log("BREAKOUT", "HIT_TP2", 4.0),
            log("BREAKOUT", "HIT_SL", -1.0),
            log("BREAKOUT", "HIT_SL", -1.0),
            log("BREAKOUT", "TRACKING"),
            log("LIQUIDITY_SWEEP", "HIT_TP1", 3.0)
        )
        val stats = SignalLogRepository.calculateSetupStats(logs)
        // urutan: Sweep dulu, lalu Breakout
        assertEquals(listOf("LIQUIDITY_SWEEP", "BREAKOUT"), stats.map { it.key })

        val b = stats.first { it.key == "BREAKOUT" }
        assertEquals(5, b.totalSignals)
        assertEquals(1, b.trackingCount)
        assertEquals(4, b.resolvedCount)
        assertEquals(2, b.winCount)
        assertEquals(2, b.lossCount)
        assertEquals(50.0, b.winRatePct, 0.001)
        assertEquals(1.0, b.avgReturnPct, 0.001)   // (2+4-1-1)/4
        assertEquals(3.0, b.profitFactor, 0.001)   // 6 / 2
        assertTrue(b.isSmallSample)
    }

    @Test
    fun trackingOnly_noWinRate() {
        val stats = SignalLogRepository.calculateSetupStats(listOf(log("TREND_PULLBACK", "TRACKING")))
        val s = stats.single()
        assertEquals(0, s.resolvedCount)
        assertEquals(0.0, s.winRatePct, 0.0)
        assertEquals(1, s.trackingCount)
    }

    @Test
    fun expiredWithPositivePnl_countsAsWin_negativeAsLoss() {
        val logs = listOf(log("BREAKOUT", "EXPIRED", 0.5), log("BREAKOUT", "EXPIRED", -0.5))
        val s = SignalLogRepository.calculateSetupStats(logs).single()
        assertEquals(1, s.winCount)
        assertEquals(1, s.lossCount)
    }

    @Test
    fun hitSlWithPositivePnl_isNotCountedAsWin() {
        val s = SignalLogRepository.calculateSetupStats(listOf(log("BREAKOUT", "HIT_SL", 0.2))).single()
        assertEquals(0, s.winCount)
        assertEquals(1, s.lossCount)
    }

    @Test
    fun scoreCategory_orderedStrongToWeak() {
        val logs = listOf(
            log("BREAKOUT", "HIT_SL", -1.0, category = "WEAK"),
            log("BREAKOUT", "HIT_TP1", 2.0, category = "VERY_STRONG"),
            log("BREAKOUT", "HIT_TP1", 2.0, category = "WATCH")
        )
        val keys = SignalLogRepository.calculateScoreCategoryStats(logs).map { it.key }
        assertEquals(listOf("VERY_STRONG", "WATCH", "WEAK"), keys)
    }

    @Test
    fun summary_includesSetupStats() {
        val logs = listOf(log("BREAKOUT", "HIT_TP1", 2.0), log("", "HIT_SL", -1.0, category = ""))
        val sum = SignalLogRepository.calculateReliabilitySummary(logs)
        assertEquals(1, sum.setupStats.size)
        assertEquals(1, sum.legacySignalCount)
    }
}

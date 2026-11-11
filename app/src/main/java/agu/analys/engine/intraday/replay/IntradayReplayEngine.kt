package agu.analys.engine.intraday.replay

import agu.analys.config.FeeSchedule
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.intraday.IntradayConfig
import agu.analys.engine.intraday.IntradayEvaluator
import agu.analys.engine.intraday.IntradayExitConfig
import agu.analys.engine.intraday.IntradayExitDecision
import agu.analys.engine.intraday.IntradayExitEngine
import agu.analys.engine.intraday.IntradayExitPosition
import agu.analys.engine.indicators.IndicatorMath
import agu.analys.engine.regime.MacroAnomalyDetector
import agu.analys.model.CandleBar
import agu.analys.model.SignalAction
import kotlin.math.abs

enum class IntradayTradeOutcome {
    VALID_ENTRY_TP,
    VALID_ENTRY_CLOSE_MALAM,
    FALSE_SIGNAL_SL,
    FALSE_SIGNAL_CLOSE_MALAM,
    MISSED_OPPORTUNITY,
    AVOIDED_LOSS,
    /** Exit time-stop intraday (paket E) — peristiwa tersendiri, bukan "tidak tuntas". */
    TIME_STOP_EXIT,
    /** Stop yang sudah dinaikkan (impas/trailing) tersentuh — arahnya bisa untung. */
    TRAIL_STOP_HIT,
    UNRESOLVED,
    AMBIGUOUS
}

data class IntradayTradeRecord(
    val entryIndex: Int,
    val entryTime: Long,
    val entryPrice: Double,
    val targetPrice1: Double,
    val targetPrice2: Double,
    val stopLoss: Double,
    val exitIndex: Int,
    val exitTime: Long,
    val exitPrice: Double,
    val exitReason: String,
    val grossPnlPct: Double,
    val netPnlPct: Double,
    val barsHeld: Int,
    val outcome: IntradayTradeOutcome
)

data class IntradayReplayFrame(
    val index: Int,
    val candle: CandleBar,
    val timestamp: Long,
    val phase: IntradayEvaluator.IntradayPhase,
    val action: SignalAction,
    val confidence: Int,
    val setupScore: Int,
    val isQualified: Boolean,
    val isMomentumCandidate: Boolean,
    val rejectionReason: String? = null,
    val outcome: IntradayTradeOutcome? = null
)

data class IntradayBottleneckReport(
    val totalMomentumCandidates: Int,
    val step1Rejections: Int,
    val step2Rejections: Int,
    val step3Rejections: Int,
    val step4Rejections: Int,
    val sessionGateRejections: Int,
    val flashDumpTraumaBlocks: Int,
    val pumpAndDumpTrapBlocks: Int,
    val missedAfterStep1: Int,
    val missedAfterStep2: Int,
    val missedAfterStep3: Int,
    val missedAfterStep4: Int,
    val missedAfterSessionGate: Int,
    val primaryBottleneckStep: String,
    val primaryBottleneckDescription: String
)

data class IntradayReplayReport(
    val symbol: String,
    val totalEvaluations: Int,
    val totalSignalsTriggered: Int,
    val validEntries: Int,
    val falseSignals: Int,
    val missedOpportunities: Int,
    val avoidedLosses: Int,
    /**
     * Trade yang tidak menyentuh SL/TP/sesi **sementara data masih ada** setelah jendela
     * lookahead (`LOOKAHEAD_EXPIRY`). Tetap dihitung kalah (aturan A5), tetapi artinya
     * "tidak tuntas di dalam jendela", bukan "data habis".
     */
    val unresolvedOutcomes: Int,
    /**
     * Trade yang ditutup karena **dataset habis** (`END_OF_DATA`) — jendela lookahead terpotong
     * di bar terakhir. Dipisahkan dari [unresolvedOutcomes] supaya laporan tidak mencampur
     * "trade belum tuntas" dengan "data berakhir" (paket D butir 2).
     *
     * Keduanya tetap dihitung **kalah**; pemisahan ini hanya untuk keterbacaan laporan.
     */
    val endOfDataOutcomes: Int,
    val ambiguousOutcomes: Int,
    /** Exit karena time-stop intraday (paket E). Nol pada mode `SESSION_CLOSE`. */
    val timeStopOutcomes: Int,
    val totalNetPnlPct: Double,
    val avgNetPnlPct: Double,
    val winRatePct: Double,
    val profitFactor: Double,
    val maxDrawdownPct: Double,
    val bottleneck: IntradayBottleneckReport,
    val trades: List<IntradayTradeRecord>,
    val phaseDistribution: Map<IntradayEvaluator.IntradayPhase, Int>,
    val frames: List<IntradayReplayFrame>
) {
    val totalResolvedTrades: Int
        get() = validEntries + falseSignals

    /**
     * Semua trade yang tidak punya exit peristiwa (SL/TP/sesi): jendela lookahead habis
     * ([unresolvedOutcomes]) atau dataset habis ([endOfDataOutcomes]).
     */
    val unresolvedTotal: Int
        get() = unresolvedOutcomes + endOfDataOutcomes

    val captureRatePct: Double
        get() {
            val viable = validEntries + missedOpportunities
            return if (viable > 0) validEntries.toDouble() / viable * 100.0 else 0.0
        }
}

object IntradayReplayEngine {

    /**
     * Net P&L satu trade dalam persen — **satu rumus dengan aplikasi** ([FeeSchedule.netProfitPct],
     * taker dua sisi + slippage dua sisi, multiplikatif).
     *
     * Sebelumnya replay memakai bentuk aditif `gross − (buyTaker + sellTaker)`: tanpa slippage dan
     * tanpa bunga-berbunga. Untuk tarif lama hasilnya 0,63% padahal impas net sebenarnya 0,794%,
     * jadi setiap trade tampak lebih untung daripada kenyataan.
     */
    fun netPnlPct(
        entry: Double,
        exit: Double,
        feeConfig: TradingFeeConfig,
        slippagePct: Double = FeeSchedule.DEFAULT_SLIPPAGE_PCT
    ): Double = FeeSchedule.netProfitPct(
        entry = entry,
        price = exit,
        buyFeePct = feeConfig.buyTakerPct,
        sellFeePct = feeConfig.sellTakerPct,
        slippagePct = slippagePct
    )

    /**
     * Satu predikat menang/kalah untuk replay: **net**, bukan gross.
     *
     * Dipakai untuk exit sesi Close Malam, yang dulu dinilai `gross >= 0` sehingga exit +0,2%
     * dihitung menang padahal setelah biaya hasilnya ≈−0,6%.
     */
    fun isWin(netPnlPct: Double): Boolean = netPnlPct >= 0.0

    /** Klasifikasi exit sesi Close Malam dari net, bukan dari gross. */
    fun classifyCloseMalam(netPnlPct: Double): IntradayTradeOutcome =
        if (isWin(netPnlPct)) IntradayTradeOutcome.VALID_ENTRY_CLOSE_MALAM
        else IntradayTradeOutcome.FALSE_SIGNAL_CLOSE_MALAM

    /**
     * Jarak antar-bar (ms) diambil dari **median** selisih timestamp, bukan konstanta: replay
     * dipakai untuk H1 maupun H4. Nilai ini menambah timestamp **buka** bar menjadi waktu
     * **tutup**-nya, yaitu saat harga close benar-benar diketahui.
     */
    private fun inferBarDurationMs(candles: List<CandleBar>): Long {
        if (candles.size < 2) return 0L
        val diffs = candles.zipWithNext { a, b -> b.timestamp - a.timestamp }
            .filter { it > 0L }
            .sorted()
        return if (diffs.isEmpty()) 0L else diffs[diffs.size / 2]
    }

    fun replay(
        symbol: String = "BTCIDR",
        candles: List<CandleBar>,
        dailyCandles: List<CandleBar> = emptyList(),
        forwardLookaheadBars: Int = 24,
        enforceSessionClose: Boolean = true,
        /**
         * Tarif bursa **wajib** dioper pemanggil.
         *
         * Sengaja tidak ada nilai bawaan: `TradingFeeConfig()` tanpa argumen jatuh ke preset
         * legacy (0,11/0,21/0,32/0,42) yang bukan tarif bursa aktif, sehingga
         * hasil replay tidak sebanding dengan angka di aplikasi.
         */
        feeConfig: TradingFeeConfig,
        slippagePct: Double = FeeSchedule.DEFAULT_SLIPPAGE_PCT,
        config: IntradayConfig = IntradayConfig.DEFAULT,
        /**
         * Aturan keluar intraday (paket E). Bawaan [IntradayExitConfig.DEFAULT] = perilaku lama
         * (SL/TP statis + SELL paksa sesi Close Malam), sehingga seluruh angka paket A–D tetap
         * dapat direproduksi. Mode lain hanya dipakai alat ukur sampai ada keputusan owner.
         */
        exitConfig: IntradayExitConfig = IntradayExitConfig.DEFAULT
    ): IntradayReplayReport {
        val sortedCandles = candles.sortedBy { it.timestamp }
        val sortedDaily = dailyCandles.sortedBy { it.timestamp }

        if (sortedCandles.size < 25) {
            return emptyReport(symbol)
        }

        val barDurationMs = inferBarDurationMs(sortedCandles)

        val frames = mutableListOf<IntradayReplayFrame>()
        val trades = mutableListOf<IntradayTradeRecord>()
        val phaseCounts = mutableMapOf<IntradayEvaluator.IntradayPhase, Int>()

        var step1Rejections = 0
        var step2Rejections = 0
        var step3Rejections = 0
        var step4Rejections = 0
        var sessionGateRejections = 0
        var flashDumpTraumaBlocks = 0
        var pumpAndDumpTrapBlocks = 0

        var missedAfterStep1 = 0
        var missedAfterStep2 = 0
        var missedAfterStep3 = 0
        var missedAfterStep4 = 0
        var missedAfterSessionGate = 0

        var missedOpportunities = 0
        var avoidedLosses = 0
        var unresolvedOutcomes = 0
        var endOfDataOutcomes = 0
        var ambiguousOutcomes = 0
        var timeStopOutcomes = 0

        var totalMomentumCandidates = 0

        val startIndex = 20
        val endIndex = sortedCandles.size - 1

        var currentHoldingUntilIndex = -1

        for (i in startIndex..endIndex) {
            val currentCandle = sortedCandles[i]
            // Bar dievaluasi pada waktu **tutup**-nya: saat itulah harga close diketahui.
            val evalTime = currentCandle.timestamp + barDurationMs
            val window = sortedCandles.subList(maxOf(0, i - 300), i + 1)

            val phase = IntradayEvaluator.getCurrentIntradayPhase(evalTime)
            phaseCounts[phase] = (phaseCounts[phase] ?: 0) + 1

            val dailySlice = sortedDaily.filter { it.timestamp <= evalTime }.takeLast(100)
            val anomalyResult = if (dailySlice.size >= 14) {
                MacroAnomalyDetector.evaluate(dailySlice, currentCandle.close)
            } else null

            val evalResult = IntradayEvaluator.evaluate(
                globalContext = agu.analys.engine.global.GlobalMarketContext(),
                price = currentCandle.close,
                history = window,
                fees = feeConfig,
                macroAnomalyResult = anomalyResult,
                evaluationTimestamp = evalTime,
                symbol = symbol,
                config = config
            )

            val signal = evalResult.signal
            val mtf = signal.mtf

            // Momentum candidate definition (for counterfactual bottleneck analysis)
            val lookback = maxOf(0, i - 14)
            val priorSlice = sortedCandles.subList(lookback, i)
            val priorAvgVol = if (priorSlice.isNotEmpty()) priorSlice.map { it.volume }.average() else currentCandle.volume
            val isVolumeSurge = currentCandle.volume >= priorAvgVol * 1.25
            val isGreenBreakout = currentCandle.close > currentCandle.open && (currentCandle.close >= priorSlice.maxOfOrNull { it.high } ?: currentCandle.close)
            val isMomentumCandidate = isGreenBreakout || (isVolumeSurge && currentCandle.close > currentCandle.open)

            // Calculate hypothetical TP/SL for counterfactual check if candidate rejected
            val effTp = signal.targetPrice1.takeIf { it > currentCandle.close } ?: (currentCandle.close * 1.03)
            val effSl = signal.stopLoss.takeIf { it > 0.0 && it < currentCandle.close } ?: (currentCandle.close * 0.98)

            // Forward simulation for this candidate
            val maxLookahead = minOf(sortedCandles.size - 1, i + forwardLookaheadBars)
            var futureRalliedWithoutSl = false
            var futureHitSl = false

            if (i < maxLookahead) {
                for (f in (i + 1)..maxLookahead) {
                    val fb = sortedCandles[f]
                    if (fb.high >= effTp) {
                        futureRalliedWithoutSl = true
                        break
                    }
                    if (fb.low <= effSl) {
                        futureHitSl = true
                        break
                    }
                }
            }

            var rejectionReason: String? = null
            if (signal.action != SignalAction.BUY) {
                rejectionReason = when {
                    signal.reasoning.any { it.contains("Fake Pump Trap", true) || it.contains("Upper Wick", true) } -> {
                        pumpAndDumpTrapBlocks++
                        "PUMP_DUMP_TRAP"
                    }
                    signal.reasoning.any { it.contains("Anti Flash Dump", true) || it.contains("Pernah flash dump", true) } -> {
                        flashDumpTraumaBlocks++
                        "ANTI_FLASH_DUMP"
                    }
                    !mtf.biasOk -> "STEP_1_TREND_DANGER"
                    !mtf.setupOk -> "STEP_2_SUPPORT_OR_HIGH_EXTENSION"
                    !mtf.triggerOk -> "STEP_3_RSI_OR_MACD"
                    !mtf.entryPriceOk -> "STEP_4_RR_OR_SCORE"
                    !phase.isOpenWindow -> "SESSION_GATE_${phase.name}"
                    else -> "NOT_QUALIFIED"
                }
            }

            if (isMomentumCandidate) {
                totalMomentumCandidates++
                when {
                    !mtf.biasOk -> {
                        step1Rejections++
                        if (futureRalliedWithoutSl) missedAfterStep1++
                    }
                    !mtf.setupOk -> {
                        step2Rejections++
                        if (futureRalliedWithoutSl) missedAfterStep2++
                    }
                    !mtf.triggerOk -> {
                        step3Rejections++
                        if (futureRalliedWithoutSl) missedAfterStep3++
                    }
                    !mtf.entryPriceOk -> {
                        step4Rejections++
                        if (futureRalliedWithoutSl) missedAfterStep4++
                    }
                    !phase.isOpenWindow -> {
                        sessionGateRejections++
                        if (futureRalliedWithoutSl) missedAfterSessionGate++
                    }
                }
            }

            var tradeOutcome: IntradayTradeOutcome? = null

            if (signal.action == SignalAction.BUY && i >= currentHoldingUntilIndex) {
                // Execute BUY simulation. Sejak paket E, seluruh jalur keluar melewati
                // `IntradayExitEngine` — satu definisi untuk replay dan (nanti) app. Mode bawaan
                // `SESSION_CLOSE` mereproduksi perilaku lama persis (SL/TP statis + sesi).
                var exitIndex = i
                var exitPrice = currentCandle.close
                var exitReason = "UNRESOLVED"

                val tradeTp1 = signal.targetPrice1
                val tradeTp2 = signal.targetPrice2
                val tradeSl = signal.stopLoss

                // ATR entry: dasar jarak trailing. Jendela sama dengan yang dipakai evaluator.
                val atrEntry = IndicatorMath.atr(window, 14)
                    .takeIf { it.isFinite() && it > 0.0 }
                    ?: (currentCandle.close * config.atrFloorPct / 100.0)
                val posisiKeluar = IntradayExitPosition(
                    entryPrice = currentCandle.close,
                    initialStopLoss = tradeSl,
                    takeProfit1 = tradeTp1,
                    takeProfit2 = tradeTp2,
                    breakevenPrice = IntradayExitEngine.breakevenPrice(
                        entry = currentCandle.close,
                        fees = feeConfig,
                        useMaker = config.useMakerFee,
                        slippagePct = slippagePct
                    ),
                    atr = atrEntry
                )
                var stateKeluar = IntradayExitEngine.initialState(posisiKeluar)

                for (f in (i + 1)..maxLookahead) {
                    val fb = sortedCandles[f]
                    // Fase keluar dihitung pada waktu **tutup** bar yang sama dengan evalTime.
                    val fbPhase = IntradayEvaluator.getCurrentIntradayPhase(fb.timestamp + barDurationMs)
                    val keputusan = IntradayExitEngine.evaluate(
                        bar = fb,
                        position = posisiKeluar,
                        state = stateKeluar,
                        config = exitConfig,
                        sessionClose = enforceSessionClose &&
                            fbPhase == IntradayEvaluator.IntradayPhase.CLOSE_MALAM
                    )
                    when (keputusan) {
                        is IntradayExitDecision.Exit -> {
                            exitIndex = f
                            exitPrice = keputusan.price
                            exitReason = keputusan.reason
                            break
                        }
                        is IntradayExitDecision.Hold -> stateKeluar = keputusan.state
                    }
                }

                if (exitReason == "UNRESOLVED") {
                    exitIndex = maxLookahead
                    exitPrice = sortedCandles[maxLookahead].close
                    // Paket D butir 2: dua hal berbeda tidak boleh memakai satu label.
                    // - END_OF_DATA: jendela lookahead terpotong karena dataset habis.
                    // - LOOKAHEAD_EXPIRY: data masih ada, trade hanya belum tuntas.
                    exitReason = if (maxLookahead == sortedCandles.size - 1) "END_OF_DATA" else "LOOKAHEAD_EXPIRY"
                }

                currentHoldingUntilIndex = exitIndex

                val grossPnlPct = ((exitPrice - currentCandle.close) / currentCandle.close) * 100.0
                val netPnlPct = netPnlPct(
                    entry = currentCandle.close,
                    exit = exitPrice,
                    feeConfig = feeConfig,
                    slippagePct = slippagePct
                )

                // Satu klasifikasi peristiwa per trade. Menang/kalah TIDAK diambil dari sini —
                // lihat `isWin` di ringkasan: dasarnya net, bukan gross.
                val outcome = when (exitReason) {
                    "AMBIGUOUS_SAME_BAR" -> {
                        ambiguousOutcomes++
                        IntradayTradeOutcome.AMBIGUOUS
                    }
                    "TP1_HIT", "TP2_HIT" -> IntradayTradeOutcome.VALID_ENTRY_TP
                    "STOP_LOSS_HIT" -> IntradayTradeOutcome.FALSE_SIGNAL_SL
                    "TRAIL_STOP_HIT" -> IntradayTradeOutcome.TRAIL_STOP_HIT
                    "CLOSE_MALAM_SESSION_EXIT" -> classifyCloseMalam(netPnlPct)
                    "TIME_STOP" -> {
                        timeStopOutcomes++
                        IntradayTradeOutcome.TIME_STOP_EXIT
                    }
                    else -> {
                        if (exitReason == "END_OF_DATA") endOfDataOutcomes++ else unresolvedOutcomes++
                        IntradayTradeOutcome.UNRESOLVED
                    }
                }

                trades += IntradayTradeRecord(
                    entryIndex = i,
                    entryTime = currentCandle.timestamp,
                    entryPrice = currentCandle.close,
                    targetPrice1 = tradeTp1,
                    targetPrice2 = tradeTp2,
                    stopLoss = tradeSl,
                    exitIndex = exitIndex,
                    exitTime = sortedCandles[exitIndex].timestamp,
                    exitPrice = exitPrice,
                    exitReason = exitReason,
                    grossPnlPct = grossPnlPct,
                    netPnlPct = netPnlPct,
                    barsHeld = exitIndex - i,
                    outcome = outcome
                )
            } else if (signal.action != SignalAction.BUY && isMomentumCandidate) {
                if (futureRalliedWithoutSl) {
                    missedOpportunities++
                    tradeOutcome = IntradayTradeOutcome.MISSED_OPPORTUNITY
                } else if (futureHitSl) {
                    avoidedLosses++
                    tradeOutcome = IntradayTradeOutcome.AVOIDED_LOSS
                }
            }

            frames += IntradayReplayFrame(
                index = i,
                candle = currentCandle,
                timestamp = evalTime,
                phase = phase,
                action = signal.action,
                confidence = signal.confidence,
                setupScore = evalResult.setupScore,
                isQualified = evalResult.isQualified,
                isMomentumCandidate = isMomentumCandidate,
                rejectionReason = rejectionReason,
                outcome = tradeOutcome
            )
        }

        // Bottleneck identification
        val maxRejections = maxOf(step1Rejections, step2Rejections, step3Rejections, step4Rejections, sessionGateRejections)
        val primaryStep = when {
            maxRejections == 0 -> "NONE"
            step1Rejections == maxRejections -> "STEP_1_MACRO_TREND"
            step2Rejections == maxRejections -> "STEP_2_SUPPORT_AND_HIGH"
            step3Rejections == maxRejections -> "STEP_3_RSI_MACD"
            step4Rejections == maxRejections -> "STEP_4_RR_SCORE"
            else -> "SESSION_GATE"
        }

        val primaryDesc = when (primaryStep) {
            "STEP_1_MACRO_TREND" -> "STEP 1 (Macro Trend & Anti Flash Dump) menolak terbanyak: $step1Rejections kandidat."
            "STEP_2_SUPPORT_AND_HIGH" -> "STEP 2 (Support & Near High Filter) menolak terbanyak: $step2Rejections kandidat."
            "STEP_3_RSI_MACD" -> "STEP 3 (RSI & MACD Momentum) menolak terbanyak: $step3Rejections kandidat."
            "STEP_4_RR_SCORE" -> "STEP 4 (Risk-Reward & Setup Score) menolak terbanyak: $step4Rejections kandidat."
            "SESSION_GATE" -> "SESSION GATE (Rest Malam & Hold Sore) menolak terbanyak: $sessionGateRejections kandidat."
            else -> "Tidak ada bottleneck dominan."
        }

        val bottleneck = IntradayBottleneckReport(
            totalMomentumCandidates = totalMomentumCandidates,
            step1Rejections = step1Rejections,
            step2Rejections = step2Rejections,
            step3Rejections = step3Rejections,
            step4Rejections = step4Rejections,
            sessionGateRejections = sessionGateRejections,
            flashDumpTraumaBlocks = flashDumpTraumaBlocks,
            pumpAndDumpTrapBlocks = pumpAndDumpTrapBlocks,
            missedAfterStep1 = missedAfterStep1,
            missedAfterStep2 = missedAfterStep2,
            missedAfterStep3 = missedAfterStep3,
            missedAfterStep4 = missedAfterStep4,
            missedAfterSessionGate = missedAfterSessionGate,
            primaryBottleneckStep = primaryStep,
            primaryBottleneckDescription = primaryDesc
        )

        // ── AKUNTANSI MENANG/KALAH: NET, DAN SETIAP TRADE DIHITUNG TEPAT SEKALI ──────────
        // Dulu `validEntries`/`falseSignals` dinaikkan di dalam loop: TP dan SL dihitung dari
        // peristiwa, exit Close Malam dari **gross**, dan trade `AMBIGUOUS` tidak masuk hitungan
        // sama sekali. Akibatnya `validEntries + falseSignals != trades.size` — trade ada di
        // daftar tetapi tidak pernah masuk win rate. Sekarang predikatnya tunggal (`isWin` atas
        // net) dan partisinya lengkap: kalah = sisanya, termasuk `UNRESOLVED` dan `AMBIGUOUS`.
        val validEntries = trades.count { isWin(it.netPnlPct) }
        val falseSignals = trades.size - validEntries

        val winRatePct = if (trades.isNotEmpty()) {
            (validEntries.toDouble() / trades.size) * 100.0
        } else 0.0

        val totalNetPnlPct = trades.sumOf { it.netPnlPct }
        val avgNetPnlPct = if (trades.isNotEmpty()) totalNetPnlPct / trades.size else 0.0

        // Profit factor juga net: gross membuat exit sesi yang hanya menutup biaya terlihat untung.
        val netWins = trades.filter { it.netPnlPct > 0.0 }.sumOf { it.netPnlPct }
        val netLosses = trades.filter { it.netPnlPct < 0.0 }.sumOf { abs(it.netPnlPct) }
        val profitFactor = when {
            netLosses > 0.0 -> netWins / netLosses
            netWins > 0.0 -> Double.POSITIVE_INFINITY
            else -> 0.0
        }

        // Max drawdown calculation on equity curve
        var runningEquity = 100.0
        var peakEquity = 100.0
        var maxDd = 0.0
        for (t in trades) {
            runningEquity *= (1.0 + (t.netPnlPct / 100.0))
            if (runningEquity > peakEquity) {
                peakEquity = runningEquity
            }
            val dd = if (peakEquity > 0.0) ((peakEquity - runningEquity) / peakEquity) * 100.0 else 0.0
            if (dd > maxDd) {
                maxDd = dd
            }
        }

        return IntradayReplayReport(
            symbol = symbol,
            totalEvaluations = frames.size,
            totalSignalsTriggered = frames.count { it.action == SignalAction.BUY },
            validEntries = validEntries,
            falseSignals = falseSignals,
            missedOpportunities = missedOpportunities,
            avoidedLosses = avoidedLosses,
            unresolvedOutcomes = unresolvedOutcomes,
            endOfDataOutcomes = endOfDataOutcomes,
            ambiguousOutcomes = ambiguousOutcomes,
            timeStopOutcomes = timeStopOutcomes,
            totalNetPnlPct = totalNetPnlPct,
            avgNetPnlPct = avgNetPnlPct,
            winRatePct = winRatePct,
            profitFactor = profitFactor,
            maxDrawdownPct = maxDd,
            bottleneck = bottleneck,
            trades = trades,
            phaseDistribution = phaseCounts,
            frames = frames
        )
    }

    private fun emptyReport(symbol: String) = IntradayReplayReport(
        symbol = symbol,
        totalEvaluations = 0,
        totalSignalsTriggered = 0,
        validEntries = 0,
        falseSignals = 0,
        missedOpportunities = 0,
        avoidedLosses = 0,
        unresolvedOutcomes = 0,
        endOfDataOutcomes = 0,
        ambiguousOutcomes = 0,
        timeStopOutcomes = 0,
        totalNetPnlPct = 0.0,
        avgNetPnlPct = 0.0,
        winRatePct = 0.0,
        profitFactor = 0.0,
        maxDrawdownPct = 0.0,
        bottleneck = IntradayBottleneckReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "NO_DATA", "Data tidak mencukupi"),
        trades = emptyList(),
        phaseDistribution = emptyMap(),
        frames = emptyList()
    )
}

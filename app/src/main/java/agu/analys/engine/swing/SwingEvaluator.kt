package agu.analys.engine.swing

import agu.analys.config.FeeCalculator
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.engine.indicators.CandlePatternDetector
import agu.analys.engine.indicators.IndicatorMath
import agu.analys.engine.regime.MarketRegimeDetector
import agu.analys.model.AISignalState
import agu.analys.model.CandleBar
import agu.analys.model.MtfLegStatus
import agu.analys.model.ScalpingMtfSnapshot
import agu.analys.model.ScalpingPath
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import agu.analys.model.TechnicalIndicators
import agu.analys.model.TrendSentiment
import agu.analys.util.PriceFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Swing Evaluator — polished dengan 4 setup klasik di level penting:
 * 1. Rejection
 * 2. Breakout / Breakdown
 * 3. Retest
 * 4. Reclaim / Failed Breakout
 *
 * Prinsip: Sabar tunggu salah satu dari 4 setup muncul di Support/Resistance.
 * Tidak pakai istilah complicated. Fokus pada struktur + konfirmasi candle + volume.
 */
data class SwingEvalResult(val signal: AISignalState, val indicators: TechnicalIndicators)

private enum class SwingSetup {
    NONE,
    REJECTION,
    BREAKOUT,
    RETEST,
    RECLAIM_FAILED
}

object SwingEvaluator {

    fun evaluate(
        price: Double,
        history: List<CandleBar>,
        fees: TradingFeeConfig = TradingFeeConfig(),
        config: SwingConfig = SwingConfig.DEFAULT
    ): SwingEvalResult = evaluate(agu.analys.engine.global.GlobalMarketContext(), price, history, fees, null, "", config)

    fun evaluate(
        globalContext: agu.analys.engine.global.GlobalMarketContext = agu.analys.engine.global.GlobalMarketContext(),
        price: Double, 
        history: List<CandleBar>, 
        fees: TradingFeeConfig = TradingFeeConfig(),
        macroAnomalyResult: agu.analys.engine.regime.MacroAnomalyResult? = null,
        symbol: String = "",
        config: SwingConfig = SwingConfig.DEFAULT
    ): SwingEvalResult {
        if (price <= 0.0) {
            return SwingEvalResult(AISignalState(), TechnicalIndicators())
        }

        val quoteAsset = if (symbol.isNotBlank()) agu.analys.util.PriceFormatter.extractQuote(symbol) else "IDR"

        val minCandles = 20
        if (history.size < minCandles) {
            val mtfSnapshot = ScalpingMtfSnapshot(
                biasOk = false,
                biasDirection = "neutral",
                biasStatus = MtfLegStatus.WAITING,
                biasDetail = "Mengumpulkan data candle untuk analisis tren makro (tersedia ${history.size}/$minCandles)...",
                setupOk = false,
                setupStatus = MtfLegStatus.WAITING,
                setupDetail = "Menunggu riwayat candle untuk memetakan level Support/Resistance.",
                triggerOk = false,
                triggerStatus = MtfLegStatus.WAITING,
                triggerDetail = "Menunggu data volume dan momentum.",
                entryPriceOk = false,
                entryPriceStatus = MtfLegStatus.WAITING,
                entryPriceDetail = "Menunggu riwayat candle pasar untuk memetakan level entry swing.",
                path = ScalpingPath.PULLBACK,
                statusTitle = "MENGUMPULKAN DATA",
                waitingFor = "Menunggu sinkronisasi data candle",
                entryCondition = "Memuat riwayat candle pasar untuk kalkulasi Swing."
            )

            return SwingEvalResult(
                AISignalState(
                    action = SignalAction.HOLD,
                    confidence = 0,
                    sentiment = TrendSentiment.NEUTRAL_CONSOLIDATION,
                    entryPrice = price,
                    targetPrice1 = 0.0,
                    targetPrice2 = 0.0,
                    stopLoss = 0.0,
                    riskRewardRatio = "--",
                    reasoning = listOf(
                        "Data candle sedang disinkronkan (${history.size}/$minCandles candle).",
                        "Menunggu riwayat candle pasar untuk kalkulasi Swing."
                    ),
                    timestamp = System.currentTimeMillis(),
                    scalpingStage = ScalpingStage.HOLD,
                    mtf = mtfSnapshot
                ),
                TechnicalIndicators()
            )
        }

        // ── Indicators ──────────────────────────────────────────────────────
        val closes = DoubleArray(history.size) { history[it].close }
        val highs = history.map { it.high }
        val rsi = IndicatorMath.rsi(history, min(14, history.size - 1))
        val ema20 = IndicatorMath.ema(closes, min(20, closes.size))
        val ema50 = IndicatorMath.ema(closes, min(50, closes.size))
        val macdResult = IndicatorMath.macdSeries(closes, 12, 26, 9)
        val macd = macdResult.lastMacd
        val macdSignal = macdResult.lastSignal
        val macdHist = macd - macdSignal
        val bb = IndicatorMath.bollinger(closes, min(20, closes.size))
        val atr = IndicatorMath.atr(history, min(14, history.size - 1))
        val pattern = CandlePatternDetector.detect(history)
        val regime = MarketRegimeDetector.detect(price, ema20, ema50, macdHist, rsi, atr, bb.first, bb.second)
        val structure = MarketStructureAnalyzer.analyze(history)
        val micro = MarketStructureAnalyzer.analyzeMicro(history)
        val momentumBase = closes[closes.lastIndex - min(10, closes.size - 1)]
        val momentum = if (momentumBase > 0.0) (price - momentumBase) / momentumBase else 0.0
        val ema200 = if (closes.size >= 200) IndicatorMath.ema(closes, 200) else Double.NaN
        val indicators = TechnicalIndicators(rsi, macd, macdSignal, macdHist, ema20, ema50, ema200, bb.second, bb.first, atr, momentum)

        val last = history.last()
        val prev = history[history.lastIndex - 1]
        val avgVolume = history.takeLast(8).dropLast(1).map { it.volume }.average().coerceAtLeast(1.0)
        val volumeRatio = last.volume / avgVolume
        val strongVolume = volumeRatio >= config.strongVolumeRatio
        val body = abs(last.close - last.open)
        val range = (last.high - last.low).coerceAtLeast(1e-9)
        val bodyRatio = body / range
        val isBullCandle = last.close > last.open
        val isBearCandle = last.close < last.open
        val lowerWick = min(last.open, last.close) - last.low
        val upperWick = last.high - max(last.open, last.close)

        // ── Level penting (Support / Resistance) ────────────────────────────
        val effectiveAtr = if (atr.isFinite() && atr > 0.0) atr else (price * 0.03)
        val supportLevel = structure.support?.takeIf { it > 0.0 && it < price * 1.02 }
            ?: structure.lastSwingLow?.takeIf { it > 0.0 && it < price }
            ?: (price - effectiveAtr * 1.6)
        val resistanceLevel = structure.resistance?.takeIf { it > price * 0.98 }
            ?: structure.lastSwingHigh?.takeIf { it > price }
            ?: (price + effectiveAtr * 2.0)

        val nearSupport = abs(price - supportLevel) / price <= config.nearLevelPct / 100.0 || last.low <= supportLevel * 1.008
        val nearResistance = abs(price - resistanceLevel) / price <= config.nearLevelPct / 100.0 || last.high >= resistanceLevel * 0.992
        val brokeAboveResistance = last.close > resistanceLevel && prev.close <= resistanceLevel * 1.002
        val brokeBelowSupport = last.close < supportLevel && prev.close >= supportLevel * 0.998

        // ── Deteksi 4 Setup Klasik ──────────────────────────────────────────
        var detectedSetup = SwingSetup.NONE
        var setupScore = 0.0
        val reasons = mutableListOf<String>()
        reasons += "Kondisi Pasar: $regime."
        reasons += "Level penting → Support ${PriceFormatter.formatPrice(supportLevel, quoteAsset = quoteAsset)} | Resistance ${PriceFormatter.formatPrice(resistanceLevel, quoteAsset = quoteAsset)}."

        // 1. REJECTION
        // Candle besar yang pantul dari level, minim wick ke arah pantulan, volume bagus
        val rejectionAtSupport = nearSupport && isBullCandle && bodyRatio >= config.rejectionBodyRatioMin &&
            lowerWick <= body * 0.55 && (strongVolume || lowerWick > body * 0.3)
        val rejectionAtResistance = nearResistance && isBearCandle && bodyRatio >= config.rejectionBodyRatioMin &&
            upperWick <= body * 0.55 && (strongVolume || upperWick > body * 0.3)

        // Micro sweep juga dihitung sebagai rejection kuat
        val sweepRejectionUp = micro.hasBullishSweep && nearSupport
        val sweepRejectionDown = micro.hasBearishSweep && nearResistance

        if (rejectionAtSupport || sweepRejectionUp) {
            detectedSetup = SwingSetup.REJECTION
            setupScore = if (strongVolume || sweepRejectionUp) 28.0 else 20.0
            reasons += "REJECTION di Support: candle pantul naik dari ${PriceFormatter.formatPrice(supportLevel, quoteAsset = quoteAsset)}${if (strongVolume) " + volume ${fmt(volumeRatio)}×" else ""}."
        } else if (rejectionAtResistance || sweepRejectionDown) {
            detectedSetup = SwingSetup.REJECTION
            setupScore = if (strongVolume || sweepRejectionDown) 26.0 else 18.0
            reasons += "REJECTION di Resistance: candle pantul turun dari ${PriceFormatter.formatPrice(resistanceLevel, quoteAsset = quoteAsset)}${if (strongVolume) " + volume ${fmt(volumeRatio)}×" else ""}."
        }

        // 2. BREAKOUT / BREAKDOWN
        // Candle nutup solid di luar level + volume
        val solidBreakout = brokeAboveResistance && isBullCandle && bodyRatio >= config.breakoutBodyRatioMin && last.close >= resistanceLevel * 1.001
        val solidBreakdown = brokeBelowSupport && isBearCandle && bodyRatio >= config.breakoutBodyRatioMin && last.close <= supportLevel * 0.999
        val bosBreakout = micro.hasBullishBOS && price > (structure.lastSwingHigh ?: resistanceLevel) * 0.998
        val bosBreakdown = micro.hasBearishBOS && price < (structure.lastSwingLow ?: supportLevel) * 1.002

        if (detectedSetup == SwingSetup.NONE) {
            if (solidBreakout || bosBreakout) {
                detectedSetup = SwingSetup.BREAKOUT
                // FIX: skor breakout instan diturunkan; prefer retest (win-rate lebih tinggi)
                setupScore = if (strongVolume) 24.0 else 16.0
                reasons += "BREAKOUT: harga tembus Resistance ${PriceFormatter.formatPrice(resistanceLevel, quoteAsset = quoteAsset)} dengan candle solid${if (strongVolume) " + volume ${fmt(volumeRatio)}×" else ""} (lebih aman tunggu retest)."
            } else if (solidBreakdown || bosBreakdown) {
                detectedSetup = SwingSetup.BREAKOUT
                setupScore = if (strongVolume) 22.0 else 14.0
                reasons += "BREAKDOWN: harga tembus Support ${PriceFormatter.formatPrice(supportLevel, quoteAsset = quoteAsset)} dengan candle solid${if (strongVolume) " + volume ${fmt(volumeRatio)}×" else ""}."
            }
        }

        // 3. RETEST
        // Setelah breakout, harga balik ngetes level yang baru ditembus lalu ditolak lagi
        // Deteksi sederhana: harga dekat level yang sudah pernah di-break, + rejection candle
        val recentHighs = history.takeLast(12).map { it.high }
        val recentLows = history.takeLast(12).map { it.low }
        val hadBreakAbove = recentHighs.any { it > resistanceLevel * 1.005 } && price < resistanceLevel * 1.015
        val hadBreakBelow = recentLows.any { it < supportLevel * 0.995 } && price > supportLevel * 0.985

        if (detectedSetup == SwingSetup.NONE) {
            if (hadBreakAbove && nearResistance && (rejectionAtResistance || (isBearCandle && bodyRatio >= RETEST_BODY_RATIO_MIN))) {
                // Ini sebenarnya failed retest / distribution — treat as rejection setelah break
                detectedSetup = SwingSetup.RETEST
                setupScore = 18.0
                reasons += "RETEST Resistance gagal: level ${PriceFormatter.formatPrice(resistanceLevel, quoteAsset = quoteAsset)} ditolak lagi setelah pernah tembus."
            } else if (hadBreakBelow && nearSupport && (rejectionAtSupport || (isBullCandle && bodyRatio >= RETEST_BODY_RATIO_MIN))) {
                detectedSetup = SwingSetup.RETEST
                setupScore = 28.0
                reasons += "RETEST Support: harga balik ngetes ${PriceFormatter.formatPrice(supportLevel, quoteAsset = quoteAsset)} setelah breakdown lalu ditolak (support hold)."
            } else if (price > resistanceLevel * 0.995 && price < resistanceLevel * 1.025 && isBullCandle && strongVolume) {
                // Retest dari atas (resistance become support)
                detectedSetup = SwingSetup.RETEST
                setupScore = 30.0
                reasons += "RETEST: Resistance lama jadi Support. Harga ditolak naik dari area ${PriceFormatter.formatPrice(resistanceLevel, quoteAsset = quoteAsset)}."
            }
        }

        // 4. RECLAIM / FAILED BREAKOUT
        // Harga sempat tembus level lalu balik lagi ke sisi semula → sinyal kuat berlawanan
        val failedBreakout = last.high > resistanceLevel * 1.003 && last.close < resistanceLevel * 0.998 && isBearCandle
        val failedBreakdown = last.low < supportLevel * 0.997 && last.close > supportLevel * 1.002 && isBullCandle
        val reclaimFromBelow = micro.hasBullishSweep || (failedBreakdown && nearSupport)
        val reclaimFromAbove = micro.hasBearishSweep || (failedBreakout && nearResistance)

        if (detectedSetup == SwingSetup.NONE || setupScore < 20.0) {
            if (reclaimFromBelow || failedBreakdown) {
                detectedSetup = SwingSetup.RECLAIM_FAILED
                setupScore = max(setupScore, if (strongVolume) 28.0 else 22.0)
                reasons += "RECLAIM / FAILED BREAKDOWN: harga sempat breakdown Support lalu balik naik. Sinyal bullish kuat."
            } else if (reclaimFromAbove || failedBreakout) {
                detectedSetup = SwingSetup.RECLAIM_FAILED
                setupScore = max(setupScore, if (strongVolume) 26.0 else 20.0)
                reasons += "FAILED BREAKOUT: harga sempat tembus Resistance lalu balik turun. Sinyal bearish kuat."
            }
        }

        // ── Scoring tambahan (konteks) ──────────────────────────────────────
        var buy = setupScore
        var sell = 0.0

        // RSI
        when {
            rsi < 32 -> { buy += 14; reasons += "RSI ${fmt(rsi)} jenuh jual (peluang rebound swing)." }
            rsi in 32.0..48.0 -> { buy += 10; reasons += "RSI ${fmt(rsi)} di zona akumulasi sehat." }
            rsi in 48.0..65.0 -> { buy += 8; reasons += "RSI ${fmt(rsi)} momentum bullish terjaga." }
            rsi > config.overboughtRsi -> { sell += 18; reasons += "RSI ${fmt(rsi)} jenuh beli (waspada koreksi)." }
            else -> reasons += "RSI ${fmt(rsi)} netral."
        }

        // EMA alignment
        val emaBullish = ema20.isFinite() && ema50.isFinite() && ema20 > ema50 && price >= ema50 * 0.985
        val isReclaimEma = price > ema20 && price > ema50 && macdHist > 0
        val isBearishTrend = ema20.isFinite() && ema50.isFinite() && ema20 < ema50 && price < ema20

        when {
            emaBullish -> { buy += 16; reasons += "EMA20 > EMA50, harga di atas EMA (uptrend selaras)." }
            isReclaimEma -> { buy += 14; reasons += "Harga reclaim EMA20 & EMA50 dengan momentum positif." }
            isBearishTrend -> { sell += 18; reasons += "EMA20 < EMA50, harga di bawah EMA20 (downtrend makro)." }
            price > ema50 -> { buy += 6; reasons += "Harga bertahan di atas EMA50." }
            else -> reasons += "EMA berkonsolidasi, menunggu arah tegas."
        }

        // MACD
        when {
            macdHist > 0 -> { buy += 10; reasons += "MACD histogram positif (momentum beli)." }
            macdHist < 0 -> { sell += 10; reasons += "MACD histogram negatif (tekanan jual)." }
        }

        // Volume konfirmasi
        if (strongVolume) {
            if (isBullCandle) { buy += 10; reasons += "Volume beli ${fmt(volumeRatio)}× di atas rata-rata." }
            else { sell += 10; reasons += "Volume jual ${fmt(volumeRatio)}× di atas rata-rata." }
        }

        // Struktur market
        val isBullishStructure = structure.trend.contains("Bull", true)
        val isBearishStructure = structure.trend.contains("Bear", true)
        if (structure.dataEnough) {
            when {
                isBullishStructure -> { buy += 12; reasons += "Struktur market bullish (Higher-High & Higher-Low)." }
                isBearishStructure -> { sell += 12; reasons += "Struktur market bearish (Lower-Low)." }
                else -> reasons += "Struktur market range / transisi."
            }
        }

        // Pattern candle
        pattern?.let {
            if (it.contains("Bullish", true) || it.contains("Hammer", true) || it.contains("Morning", true)) {
                buy += 8; reasons += "Pola Candlestick: $it."
            } else if (it.contains("Bearish", true) || it.contains("Shooting", true) || it.contains("Evening", true)) {
                sell += 8; reasons += "Pola Candlestick: $it."
            }
        }

        // ── Target & SL ─────────────────────────────────────────────────────
        val calculatedSl = when (detectedSetup) {
            SwingSetup.REJECTION, SwingSetup.RETEST, SwingSetup.RECLAIM_FAILED -> {
                // SL di bawah low candle rejection / di luar support
                // Konstanta SL cabang ini = knob config: buffer ATR, jarak ATR maksimum, serta
                // lantai/atas jarak dalam persen. Cabang BREAKOUT dan tanpa-setup memakai nilai
                // internalnya sendiri (lihat KDoc `SwingConfig`) supaya default tidak bergeser.
                maxOf(
                    min(last.low, supportLevel) - effectiveAtr * config.slBufferAtrRejection,
                    price - effectiveAtr * config.slAtrMaxRejection,
                    price * (100.0 - config.slMaxDistancePct) / 100.0
                ).coerceAtMost(price * (100.0 - config.slMinDistancePct) / 100.0)
            }
            SwingSetup.BREAKOUT -> {
                // SL sedikit di bawah candle breakout / level yang ditembus
                maxOf(
                    min(last.low, resistanceLevel) - effectiveAtr * 0.15,
                    price - effectiveAtr * 1.2,
                    price * 0.94
                ).coerceAtMost(price * 0.988)
            }
            else -> {
                maxOf(
                    supportLevel - effectiveAtr * 0.25,
                    price - effectiveAtr * 1.5,
                    price * 0.93
                ).coerceAtMost(price * 0.985)
            }
        }

        val calculatedTp1 = maxOf(
            resistanceLevel,
            price + effectiveAtr * config.tp1AtrMult,
            price * 1.07
        )
        val calculatedTp2 = maxOf(
            calculatedTp1 * 1.08,
            price + effectiveAtr * config.tp2AtrMult,
            price * 1.16
        )

        // Gerbang R:R memakai angka MENTAH dari FeeCalculator, bukan angka yang sudah dibawahkan
        // ke lantai tampilan. Dengan itu slider `minNetRr` di bawah 1,4 tetap ditegakkan pada
        // keputusan; lantai hanya milik teks tampilan, persis seperti perilaku lama.
        val feeResult = FeeCalculator.roundTrip(
            entry = price,
            stopLoss = calculatedSl,
            takeProfit = calculatedTp2,
            fees = fees,
            slippagePct = config.slippagePct
        )
        val netRrRaw = feeResult.netRr
        val netRr = netRrRaw.coerceAtLeast(RR_DISPLAY_FLOOR)
        val rrString = "1:${fmt(netRr)}"

        // ── Recent high / extension filter (FIX: dead-code —
        // `price >= calculatedTp1 * 0.995` mustahil true karena calculatedTp1 selalu >= price*1.07.
        // Diganti guard nyata: jarak ke recent high + extension dari rata-rata ATR, mencegah
        // "beli di pucuk" seperti kasus XRP di Intraday.) ─────────────────────────
        val lookbackHigh = min(15, highs.size - 1).coerceAtLeast(1)
        val recentHigh = highs.dropLast(1).takeLast(lookbackHigh).maxOrNull() ?: price
        val distToHighPct = if (recentHigh > 0.0) (recentHigh - price) / recentHigh else 1.0
        val mean20 = closes.takeLast(min(20, closes.size)).average()
        val atrExtension = if (effectiveAtr > 0.0) (price - mean20) / effectiveAtr else 0.0
        val tooCloseToHigh = distToHighPct >= 0.0 && distToHighPct <= config.maxDistToHighPct / 100.0 &&
            detectedSetup != SwingSetup.BREAKOUT
        val isOverExtended = atrExtension > config.maxAtrExtension

        // --- 1. DANGER & INVALIDATION CHECKS (DI ATAS) ---
        val isOverbought = rsi >= config.overboughtRsi
        val isNearHighDanger = tooCloseToHigh || isOverExtended
        val isResistanceRejection = (detectedSetup == SwingSetup.REJECTION && rejectionAtResistance) ||
            (detectedSetup == SwingSetup.RECLAIM_FAILED && (reclaimFromAbove || failedBreakout))
        val isSolidBreakdown = isBearishTrend || solidBreakdown || bosBreakdown ||
            (detectedSetup == SwingSetup.BREAKOUT && (solidBreakdown || bosBreakdown))
        val isHeavySelling = sell >= HEAVY_SELL_SCORE && sell > buy * config.minBuySellRatio

        val isTechnicalDistribution = isOverbought || isNearHighDanger || isResistanceRejection || isSolidBreakdown || isHeavySelling
        if (isNearHighDanger) {
            reasons.add(0, "⚠️ Tertahan: Harga terlalu dekat recent high (${PriceFormatter.formatPrice(recentHigh, quoteAsset = quoteAsset)}) / overextended. Hindari beli di pucuk.")
        }

        // --- 2. WATERFALL CHECKPOINTS ---
        val step1Ok = !isTechnicalDistribution &&
            (emaBullish || isReclaimEma || detectedSetup == SwingSetup.RECLAIM_FAILED || detectedSetup == SwingSetup.BREAKOUT) &&
            !isBearishTrend

        val step2Ok = step1Ok && detectedSetup != SwingSetup.NONE && !tooCloseToHigh &&
            (nearSupport || nearResistance || brokeAboveResistance || brokeBelowSupport || micro.hasBullishBOS || micro.hasBullishSweep)

        val confluence = agu.analys.engine.confluence.ConfluenceEvaluator.evaluate(
            price = price,
            macroCandles = history,
            microCandles = history,
            strategyMode = agu.analys.config.StrategyMode.SWING,
            fees = fees,
            quoteAsset = quoteAsset,
            // Ambang R:R checkpoint 6 mengikuti slider SWING (bawaan 2,0 = perilaku lama) dan
            // slippage mengikuti config SWING (bawaan 0,08 = bawaan lama). `useMaker` dan geometri
            // internal checkpoint 6 TIDAK diubah supaya default tetap identik dengan engine lama —
            // perubahan asumsi biaya diagnostik bukan bagian paket ini.
            netRrThreshold = config.minNetRr,
            slippagePct = config.slippagePct
        )

        val isRsiBullish = rsi in 35.0..68.0 || (rsi in 28.0..38.0 && macdHist >= 0)
        val step3Ok = step2Ok && (isRsiBullish || macdHist >= 0 || strongVolume) && buy > sell

        // R:R mentah (bukan angka berlantai tampilan) dibandingkan ambang config agar slider
        // di bawah lantai tampilan (1,4) tetap berpengaruh pada keputusan.
        val isRrValid = netRrRaw >= config.minNetRr
        val step4Ok = step3Ok && isRrValid && buy >= MIN_CHECKPOINT_SCORE && !isOverExtended

        val completedSteps = confluence.completedCount

        // ── Keputusan akhir ────────────────────────────────
        val isQualifiedBuy = step4Ok && isRrValid && buy >= config.minEntryScore &&
            buy > sell * config.minBuySellRatio && !isNearHighDanger &&
            detectedSetup in listOf(SwingSetup.REJECTION, SwingSetup.BREAKOUT, SwingSetup.RETEST, SwingSetup.RECLAIM_FAILED) &&
            (detectedSetup != SwingSetup.REJECTION || !rejectionAtResistance) &&
            confluence.completedCount >= config.minConfluenceCheckpoints

        var finalAction = when {
            globalContext.isVetoActive -> SignalAction.HOLD
            isQualifiedBuy && !isTechnicalDistribution -> SignalAction.BUY
            else -> SignalAction.HOLD
        }

        if (!isRrValid && step3Ok) {
            reasons.add(0, "⚠️ Risk/Reward: Net R:R 1:${fmt(netRr)} < 1:${fmt(config.minNetRr)}. Ruang menuju resistance terlalu sempit — setup di-skip.")
        }

        if (isTechnicalDistribution) {
            when {
                isOverbought -> reasons.add(0, "⚠️ Tertahan: Harga koin sedang terlalu tinggi (Jenuh Beli/Overbought).")
                isSolidBreakdown -> reasons.add(0, "⚠️ Tertahan: Harga koin sedang turun menembus support (Breakdown).")
                isResistanceRejection -> reasons.add(0, "⚠️ Tertahan: Terjadi penolakan harga di Resistance (Rejection).")
                isHeavySelling -> reasons.add(0, "⚠️ Tertahan: Tekanan jual dominan di area ini.")
                else -> reasons.add(0, "⚠️ Tertahan: Sinyal distribusi / koreksi teknikal terdeteksi.")
            }
        } else if (isQualifiedBuy) {
            reasons.add(0, "✅ Setup ${detectedSetup.name.replace('_', ' ')} valid — entry swing siap.")
        }

        val finalScoreRaw = when {
            isTechnicalDistribution -> 0
            isQualifiedBuy -> (80 + min(15, (buy * 0.18).toInt())).coerceIn(80, 95)
            step3Ok -> 65
            step2Ok -> 50
            step1Ok -> 36
            else -> 24
        }
        
        // --- SOFT Penalties ---
        val regimeMultiplier = when {
            regime.contains("Tinggi") -> 0.7 // High Volatility penalty
            else -> 1.0
        }
        val anomalyPenalty = macroAnomalyResult?.confidencePenalty ?: 0
        val finalScore = maxOf(0, (finalScoreRaw * regimeMultiplier).toInt() + anomalyPenalty)


        val step1Detail = when {
            isOverbought -> "Tertahan: Harga koin sedang terlalu tinggi (Jenuh Beli/Overbought)."
            isSolidBreakdown -> "Tertahan: Harga koin sedang turun menembus support (Breakdown)."
            isResistanceRejection -> "Tertahan: Terjadi penolakan harga di resistance."
            step1Ok && detectedSetup == SwingSetup.BREAKOUT -> "Bias bullish via Breakout level penting."
            step1Ok && detectedSetup == SwingSetup.RECLAIM_FAILED -> "Bias bullish via Reclaim / Failed Breakdown."
            step1Ok -> "Tren makro selaras positif (EMA20 > EMA50 di ${PriceFormatter.formatPrice(ema20, quoteAsset = quoteAsset)})."
            isBearishTrend -> "Tren makro downtrend (EMA20 < EMA50). Menunggu Reclaim EMA20."
            else -> "Memantau keselarasan tren EMA (harga menguji area ${PriceFormatter.formatPrice(ema20, quoteAsset = quoteAsset)})."
        }

        val step2Detail = when {
            !step1Ok -> "Menunggu Checkpoint 1 lolos."
            detectedSetup == SwingSetup.REJECTION -> "Setup REJECTION terdeteksi di level penting."
            detectedSetup == SwingSetup.BREAKOUT -> "Setup BREAKOUT terdeteksi — momentum kuat."
            detectedSetup == SwingSetup.RETEST -> "Setup RETEST terdeteksi — level dihormati lagi."
            detectedSetup == SwingSetup.RECLAIM_FAILED -> "Setup RECLAIM / FAILED BREAK terdeteksi — sinyal kuat."
            else -> "Belum ada Rejection / Breakout / Retest / Reclaim yang valid di Support/Resistance."
        }

        val step3Detail = when {
            !step2Ok -> "Menunggu Checkpoint 2 lolos."
            step3Ok -> "Momentum & volume mendukung (RSI ${fmt(rsi)}, Vol ${fmt(volumeRatio)}×)."
            macdHist < 0 -> "MACD masih negatif (${fmt(macdHist)}). Menunggu penguatan momentum."
            else -> "Menunggu dorongan volume & penguatan RSI (${fmt(rsi)})."
        }

        val step4Detail = when {
            !step3Ok -> "Menunggu Checkpoint 3 lolos."
            step4Ok -> "Zona entry ideal · Net R:R $rrString · Setup: ${detectedSetup.name.replace('_', ' ')}"
            else -> "Menunggu R:R optimal (Min 1:1.4) atau skor setup lebih tinggi."
        }

        val path = when (detectedSetup) {
            SwingSetup.BREAKOUT -> ScalpingPath.MOMENTUM_CONTINUATION
            SwingSetup.RETEST, SwingSetup.REJECTION, SwingSetup.RECLAIM_FAILED -> ScalpingPath.PULLBACK
            else -> if (isBullishStructure) ScalpingPath.MOMENTUM_CONTINUATION else ScalpingPath.PULLBACK
        }

        val statusTitle = when {
            isOverbought -> "SWING OVERBOUGHT (HOLD)"
            isNearHighDanger -> "SWING DEKAT HIGH / OVEREXTENDED (HOLD)"
            isSolidBreakdown -> "SWING BREAKDOWN (HOLD)"
            isTechnicalDistribution -> "SWING PULLBACK / RESISTANCE"
            confluence.isAllPassed -> "SWING ENTRY READY (6/6)"
            confluence.completedCount > 0 -> "SWING ANALYZING (${confluence.completedCount}/6)"
            else -> "SWING ANALYZING (0/6)"
        }

        val mtfSnapshot = ScalpingMtfSnapshot(
            biasOk = step1Ok,
            biasDirection = if (step1Ok) "bullish" else if (isBearishTrend) "bearish" else "neutral",
            biasStatus = if (step1Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            biasDetail = step1Detail,

            setupOk = step2Ok,
            setupStatus = if (step2Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            setupDetail = step2Detail,

            triggerOk = step3Ok,
            triggerStatus = if (step3Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            triggerDetail = step3Detail,

            entryPriceOk = step4Ok,
            entryPriceStatus = if (step4Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            entryPriceDetail = step4Detail,

            path = path,
            statusTitle = statusTitle,
            waitingFor = when {
                isOverbought -> "Menunggu koreksi / reset RSI (Overbought)"
                isNearHighDanger -> "Menunggu pullback dari zona high (jangan entry di pucuk)"
                isSolidBreakdown -> "Menunggu pembentukan lantai support baru"
                isTechnicalDistribution -> "Menunggu lantai support baru (jangan entry)"
                confluence.isAllPassed -> "Siap eksekusi Swing Buy (6/6 lolos)"
                else -> confluence.summaryReason
            },
            entryCondition = when (detectedSetup) {
                SwingSetup.REJECTION -> "Rejection di Support"
                SwingSetup.BREAKOUT -> "Breakout level penting"
                SwingSetup.RETEST -> "Retest level setelah break"
                SwingSetup.RECLAIM_FAILED -> "Reclaim / Failed Break"
                else -> "Menunggu 6 Konfluensi di Key Level"
            },
            checkpoints = confluence.checkpoints,
            completedCount = confluence.completedCount
        )

        return SwingEvalResult(
            AISignalState(
                action = finalAction,
                confidence = finalScore,
                sentiment = when (finalAction) {
                    SignalAction.BUY -> when (detectedSetup) {
                        SwingSetup.BREAKOUT -> TrendSentiment.STRONG_BULLISH_CONTINUATION
                        SwingSetup.RECLAIM_FAILED, SwingSetup.REJECTION -> TrendSentiment.BULLISH_REVERSAL
                        else -> TrendSentiment.STRONG_BULLISH_CONTINUATION
                    }
                    SignalAction.SELL -> TrendSentiment.BEARISH_DISTRIBUTION
                    SignalAction.HOLD -> if (isTechnicalDistribution) TrendSentiment.BEARISH_DISTRIBUTION else if (completedSteps >= 2) TrendSentiment.ACCUMULATION_SQUEEZE else TrendSentiment.NEUTRAL_CONSOLIDATION
                },
                entryPrice = price,
                targetPrice1 = calculatedTp1,
                targetPrice2 = calculatedTp2,
                stopLoss = calculatedSl,
                riskRewardRatio = rrString,
                reasoning = reasons.take(8),
                timestamp = System.currentTimeMillis(),
                patternDetected = pattern,
                scalpingStage = when {
                    completedSteps == 4 -> ScalpingStage.ENTRY
                    completedSteps >= 3 -> ScalpingStage.EARLY_ENTRY
                    completedSteps >= 2 -> ScalpingStage.WAIT_PULLBACK
                    else -> ScalpingStage.HOLD
                },
                mtf = mtfSnapshot,
                regimeDetected = regime
            ),
            indicators
        )
    }

    private fun fmt(v: Double) = agu.analys.util.PriceFormatter.fmt(v)
    private fun fmtPrice(v: Double) = agu.analys.util.PriceFormatter.fmtPriceInt(v)

    // ── Konstanta internal yang BELUM jadi slider (lihat KDoc `SwingConfig`) ──────────
    /** Lantai tampilan R:R dulu `netRr.coerceAtLeast(1.4)`; hanya untuk teks, bukan gerbang. */
    private const val RR_DISPLAY_FLOOR = 1.4

    /** Ambang `buy` checkpoint 4 (dulu `buy >= 38.0`). */
    private const val MIN_CHECKPOINT_SCORE = 38.0

    /** Ambang skor jual "berat" (dulu `sell >= 42.0`). */
    private const val HEAVY_SELL_SCORE = 42.0

    /** Rasio badan candle minimum deteksi RETEST lama (`bodyRatio >= 0.35`). */
    private const val RETEST_BODY_RATIO_MIN = 0.35
}
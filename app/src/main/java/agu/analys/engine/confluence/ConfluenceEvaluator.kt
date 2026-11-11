package agu.analys.engine.confluence

import agu.analys.config.FeeCalculator
import agu.analys.config.FeeSchedule
import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.engine.indicators.CandlePatternDetector
import agu.analys.engine.indicators.IndicatorMath
import agu.analys.model.CandleBar
import agu.analys.model.ConfluenceCheckpoint
import agu.analys.model.MtfLegStatus
import agu.analys.model.OrderBookItem
import agu.analys.model.ScalpingMtfSnapshot
import agu.analys.model.ScalpingPath
import agu.analys.util.PriceFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ConfluenceResult(
    val checkpoints: List<ConfluenceCheckpoint>,
    val completedCount: Int,
    val isAllPassed: Boolean,
    val recommendedStopLoss: Double,
    val recommendedTp1: Double,
    val recommendedTp2: Double,
    val netRiskReward: Double,
    val mtfSnapshot: ScalpingMtfSnapshot,
    val summaryReason: String
)

/**
 * Standard 6-Checkpoint Confluence Evaluator untuk Spot Trading (High-Probability Setup).
 * Mengimplementasikan 6 filter konfluensi wajib:
 * 1. Keselarasan Multi-Timeframe (Market Structure)
 * 2. Reaksi di Area of Value (Key Levels)
 * 3. Konfirmasi Volume (Validasi Institusi)
 * 4. Trigger Price Action (Sinyal Masuk)
 * 5. Filter Momentum (Divergence & Oscillators)
 * 6. Uji Risk-to-Reward Ratio (Net R:R >= ambang; bawaan 1:2, mode lain bisa mengirim ambang
 *    dan geometrinya sendiri — paket C intraday)
 */
object ConfluenceEvaluator {

    fun evaluate(
        price: Double,
        macroCandles: List<CandleBar>,
        microCandles: List<CandleBar>,
        strategyMode: StrategyMode = StrategyMode.SWING,
        orderBookBids: List<OrderBookItem> = emptyList(),
        orderBookAsks: List<OrderBookItem> = emptyList(),
        fees: TradingFeeConfig = TradingFeeConfig(),
        quoteAsset: String = "IDR",
        /**
         * Jenis order kaki beli **dan** jual untuk uji R:R.
         *
         * Bawaan `true` mempertahankan perilaku lama pemanggil SWING. Pemanggil yang mengeksekusi
         * order market (mode INTRADAY) wajib mengirim `false`: menilai R:R dengan tarif maker
         * membuat biaya tampak lebih murah 0,2 pp pada pair IDR daripada yang benar-benar dibayar.
         */
        useMaker: Boolean = true,
        slippagePct: Double = FeeSchedule.DEFAULT_SLIPPAGE_PCT,
        /**
         * Ambang net R:R checkpoint 6. Bawaan 2,0 = perilaku lama SWING.
         *
         * Paket C: pemanggil INTRADAY mengirim `IntradayConfig.minNetRrTp1` — dulu ambang ini
         * hardcoded 2,0 sehingga mode intraday punya **dua** definisi R:R dalam satu sinyal
         * (ambang evaluators 1,5 dan ambang konfluensi 2,0 yang tidak tercatat mana pun).
         */
        netRrThreshold: Double = 2.0,
        /**
         * Geometri SL/TP dari pemicu (paket C). Bila diisi, checkpoint 6 memakai SL/TP itu —
         * bukan menghitung geometrinya sendiri — supaya angka R:R konfluensi = angka R:R sinyal.
         * Bawaan `null` mempertahankan geometri lama pemanggil SWING.
         */
        overrideStopLoss: Double? = null,
        overrideTp1: Double? = null,
        /**
         * Net R:R yang **sudah dihitung** pemicu (paket C). Bila diisi, checkpoint 6 memakai angka
         * ini untuk gate **dan** tampilan, sehingga tidak ada definisi kedua.
         *
         * Kenapa perlu: `FeeCalculator.roundTrip` memipihkan R:R negatif ke 0, sedangkan geometri
         * intraday sengaja membiarkannya negatif ("TP1 tidak menutup biaya"). Tanpa override,
         * satu sinyal punya dua angka R:R yang berbeda pada kasus itu. Bawaan `null` mempertahankan
         * perilaku lama pemanggil SWING.
         */
        overrideNetRr: Double? = null
    ): ConfluenceResult {
        if (price <= 0.0 || microCandles.size < 5) {
            val emptyList = (1..6).map { idx ->
                ConfluenceCheckpoint(
                    number = idx,
                    code = when (idx) {
                        1 -> "MTF"
                        2 -> "AOV"
                        3 -> "VOL"
                        4 -> "TRG"
                        5 -> "MOM"
                        else -> "RR"
                    },
                    label = when (idx) {
                        1 -> "Struktur MTF"
                        2 -> "Area of Value"
                        3 -> "Volume Validasi"
                        4 -> "Price Action"
                        5 -> "Momentum / Div"
                        else -> "Risk / Reward"
                    },
                    isOk = false,
                    status = MtfLegStatus.WAITING,
                    metricValue = "--",
                    detail = "Memuat data pasar..."
                )
            }
            return ConfluenceResult(
                checkpoints = emptyList,
                completedCount = 0,
                isAllPassed = false,
                recommendedStopLoss = price * 0.97,
                recommendedTp1 = price * 1.06,
                recommendedTp2 = price * 1.12,
                netRiskReward = 0.0,
                mtfSnapshot = ScalpingMtfSnapshot(statusTitle = "MEMUAT DATA", waitingFor = "Sinkronisasi candle pasar"),
                summaryReason = "Menunggu data candle pasar"
            )
        }

        val microCloses = DoubleArray(microCandles.size) { microCandles[it].close }
        val microEma20 = IndicatorMath.ema(microCloses, min(20, microCloses.size))
        val microEma50 = IndicatorMath.ema(microCloses, min(50, microCloses.size))
        val microAtr = IndicatorMath.atr(microCandles, min(14, microCandles.size - 1))
        val effectiveAtr = if (microAtr.isFinite() && microAtr > 0.0) microAtr else (price * 0.025)

        val microStructure = MarketStructureAnalyzer.analyze(microCandles)
        val microMicro = MarketStructureAnalyzer.analyzeMicro(microCandles)

        // ═════════════════════════════════════════════════════════════════════
        // CHECKPOINT 1: Keselarasan Multi-Timeframe (Market Structure)
        // Makro menentukan tren utama; Mikro mencari momentum koreksi selesai.
        // ═════════════════════════════════════════════════════════════════════
        val hasMacro = macroCandles.size >= 10
        val macroCloses = if (hasMacro) DoubleArray(macroCandles.size) { macroCandles[it].close } else microCloses
        val macroEma20 = if (hasMacro) IndicatorMath.ema(macroCloses, min(20, macroCloses.size)) else microEma20
        val macroEma50 = if (hasMacro) IndicatorMath.ema(macroCloses, min(50, macroCloses.size)) else microEma50
        val macroStructure = if (hasMacro) MarketStructureAnalyzer.analyze(macroCandles) else microStructure

        val isMacroBullish = (macroEma20.isFinite() && macroEma50.isFinite() && macroEma20 >= macroEma50 * 0.995) ||
                macroStructure.trend.contains("Bull", ignoreCase = true) ||
                (price >= macroEma50 * 0.985)

        val isMicroPullbackFinished = (price >= microEma20 * 0.99 || microMicro.hasBullishBOS || microMicro.hasBullishSweep)
        val isMtfAligned = isMacroBullish && isMicroPullbackFinished

        val mtfMetric = when {
            isMacroBullish && isMicroPullbackFinished -> "Uptrend Selaras"
            isMacroBullish -> "Makro Bull · Koreksi Mikro"
            else -> "Konsolidasi / Downtrend"
        }
        val mtfDetail = when {
            isMtfAligned -> "Tren makro bullish terkonfirmasi (EMA20 >= EMA50). Koreksi mikro selesai di support."
            isMacroBullish -> "Tren makro positif, namun timeframe mikro masih dalam fase koreksi/uji support."
            else -> "Tren makro belum selaras (EMA makro masih di bawah EMA50 / downtrend)."
        }
        val step1 = ConfluenceCheckpoint(
            number = 1,
            code = "MTF",
            label = "Struktur MTF",
            isOk = isMtfAligned,
            status = if (isMtfAligned) MtfLegStatus.OK else MtfLegStatus.WAITING,
            metricValue = mtfMetric,
            detail = mtfDetail
        )

        // ═════════════════════════════════════════════════════════════════════
        // CHECKPOINT 2: Reaksi di Area of Value (Key Levels)
        // Harga merespons Support, Retest, atau Reclaim level penting.
        // Tidak membeli di tengah-tengah (no man's land).
        // ═════════════════════════════════════════════════════════════════════
        val supportLevel = microStructure.support?.takeIf { it > 0.0 && it < price * 1.02 }
            ?: microStructure.lastSwingLow?.takeIf { it > 0.0 && it < price }
            ?: (price - effectiveAtr * 1.4)
        val resistanceLevel = microStructure.resistance?.takeIf { it > price * 0.98 }
            ?: microStructure.lastSwingHigh?.takeIf { it > price }
            ?: (price + effectiveAtr * 2.2)

        val distToSupportPct = abs(price - supportLevel) / price
        val distToResistancePct = abs(resistanceLevel - price) / price

        val isNearSupport = distToSupportPct <= 0.025 || (microCandles.last().low <= supportLevel * 1.01)
        val isReclaimSupport = microMicro.hasBullishSweep || (microCandles.last().low < supportLevel && microCandles.last().close > supportLevel)
        val isRetestKeyLevel = microMicro.hasBullishBOS && distToSupportPct <= 0.035

        val isAovValid = isNearSupport || isReclaimSupport || isRetestKeyLevel
        val aovMetric = when {
            isReclaimSupport -> "Reclaim Support ${PriceFormatter.formatPrice(supportLevel, quoteAsset = quoteAsset)}"
            isRetestKeyLevel -> "Retest Key Level ${PriceFormatter.formatPrice(supportLevel, quoteAsset = quoteAsset)}"
            isNearSupport -> "Support ${PriceFormatter.formatPrice(supportLevel, quoteAsset = quoteAsset)} (${String.format(Locale.US, "%.1f", distToSupportPct * 100)}%)"
            else -> "No Man's Land (+${String.format(Locale.US, "%.1f", distToSupportPct * 100)}%)"
        }
        val aovDetail = when {
            isReclaimSupport -> "False breakdown teratasi! Buyer merebut kembali level Support ${PriceFormatter.formatPrice(supportLevel, quoteAsset = quoteAsset)}."
            isRetestKeyLevel -> "Harga melakukan retest sehat pada resistance yang kini menjadi support baru."
            isNearSupport -> "Harga berada tepat di Area of Value Support ${PriceFormatter.formatPrice(supportLevel, quoteAsset = quoteAsset)} (tidak beli di pucuk)."
            else -> "Harga berada di 'no man's land' (terlalu jauh dari support ${PriceFormatter.formatPrice(supportLevel, quoteAsset = quoteAsset)}). Tunggu pullback."
        }
        val step2 = ConfluenceCheckpoint(
            number = 2,
            code = "AOV",
            label = "Area of Value",
            isOk = isAovValid,
            status = if (isAovValid) MtfLegStatus.OK else MtfLegStatus.WAITING,
            metricValue = aovMetric,
            detail = aovDetail
        )

        // ═════════════════════════════════════════════════════════════════════
        // CHECKPOINT 3: Konfirmasi Volume (Validasi Institusi)
        // Breakout butuh volume > rata-rata. Pullback butuh volume menyusut/kering.
        // Bid/Ask orderbook menunjukkan akumulasi buyer.
        // ═════════════════════════════════════════════════════════════════════
        val lastCandle = microCandles.last()
        val lastMicro = lastCandle
        val closedCandles = microCandles.filter { it.isClosed && it.volume > 0.0 }

        // Pilih candle representatif: jika candle berjalan (forming) masih 0 atau baru mulai, gunakan candle tertutup terakhir
        val candleForVol = when {
            lastMicro.volume > 0.0 && lastMicro.isClosed -> lastMicro
            lastMicro.volume > 0.0 && !lastMicro.isClosed -> lastMicro
            closedCandles.isNotEmpty() -> closedCandles.last()
            else -> microCandles.lastOrNull { it.volume > 0.0 } ?: lastMicro
        }

        val sampleCandles = microCandles.takeLast(min(30, microCandles.size))
        val activeCandles = sampleCandles.filter { it.volume > 0.0 }
        val avgMicroVolume = when {
            activeCandles.size >= 3 -> activeCandles.map { it.volume }.average()
            sampleCandles.isNotEmpty() -> sampleCandles.map { it.volume }.average().coerceAtLeast(1.0)
            else -> 1.0
        }

        // Fallback cerdas ke macroCandles (M15 / H1) jika volume microCandles 0 (umum terjadi pada timeframe M1 altcoin di bursa)
        val volRatio: Double
        val isUsingMacroFallback: Boolean
        if (candleForVol.volume <= 0.0 || avgMicroVolume <= 0.0) {
            val macroClosed = macroCandles.filter { it.isClosed && it.volume > 0.0 }
            val macroCandleForVol = when {
                macroCandles.last().volume > 0.0 -> macroCandles.last()
                macroClosed.isNotEmpty() -> macroClosed.last()
                else -> macroCandles.lastOrNull { it.volume > 0.0 } ?: macroCandles.last()
            }
            val macroSample = macroCandles.takeLast(min(20, macroCandles.size))
            val macroActive = macroSample.filter { it.volume > 0.0 }
            val macroAvgVol = if (macroActive.isNotEmpty()) macroActive.map { it.volume }.average() else 1.0
            volRatio = if (macroCandleForVol.volume > 0.0 && macroAvgVol > 0.0) macroCandleForVol.volume / macroAvgVol else 0.0
            isUsingMacroFallback = true
        } else {
            volRatio = candleForVol.volume / avgMicroVolume
            isUsingMacroFallback = false
        }

        val totalBids = orderBookBids.sumOf { it.amount }
        val totalAsks = orderBookAsks.sumOf { it.amount }
        val obTotal = totalBids + totalAsks
        val bidPct = if (obTotal > 0.0) (totalBids / obTotal) * 100.0 else 50.0

        val hasRealVolume = volRatio >= 0.05
        val isBullCandle = candleForVol.close >= candleForVol.open
        val isHealthyBreakoutVol = hasRealVolume && isBullCandle && volRatio >= 1.20
        val isHealthyPullbackVol = hasRealVolume && (
            (!isBullCandle && volRatio in 0.20..0.90) ||
            (isBullCandle && volRatio in 0.70..1.20)
        )
        val isOrderBookSupportive = bidPct >= 48.0 || orderBookBids.isEmpty()

        val isVolumeValid = hasRealVolume && (isHealthyBreakoutVol || isHealthyPullbackVol) && isOrderBookSupportive
        val volMetric = if (hasRealVolume) {
            "Vol ${String.format(Locale.US, "%.2f", volRatio)}× · Bid ${String.format(Locale.US, "%.0f", bidPct)}%"
        } else {
            "Vol 0.00× (Menunggu) · Bid ${String.format(Locale.US, "%.0f", bidPct)}%"
        }
        val volDetail = when {
            !hasRealVolume -> "Volume transaksi candle belum terbentuk / data volume candle 0. Menunggu aktivitas transaksi tercatat di orderbook & grafik."
            isHealthyBreakoutVol -> "Lonjakan volume institusi (${String.format(Locale.US, "%.1f", volRatio)}× MA) memvalidasi dorongan buyer."
            isHealthyPullbackVol -> "Volume menyusut sehat saat pengujian support (${String.format(Locale.US, "%.2f", volRatio)}× MA), bukan aksi dumping."
            bidPct < 48.0 -> "Seller mendominasi orderbook (Bid ${String.format(Locale.US, "%.0f", bidPct)}% vs Ask ${String.format(Locale.US, "%.0f", 100.0 - bidPct)}%). Menunggu buy pressure menguat."
            else -> "Aktivitas volume belum memenuhi konfirmasi (Vol ${String.format(Locale.US, "%.2f", volRatio)}× MA)."
        }
        val step3 = ConfluenceCheckpoint(
            number = 3,
            code = "VOL",
            label = "Volume Validasi",
            isOk = isVolumeValid,
            status = if (isVolumeValid) MtfLegStatus.OK else MtfLegStatus.WAITING,
            metricValue = volMetric,
            detail = volDetail
        )

        // ═════════════════════════════════════════════════════════════════════
        // CHECKPOINT 4: Trigger Price Action (Sinyal Masuk)
        // Konfirmasi pola candlestick (Hammer, Bullish Engulfing, Rejection Wick).
        // ═════════════════════════════════════════════════════════════════════
        val candlePattern = CandlePatternDetector.detect(microCandles)
        val body = abs(lastCandle.close - lastCandle.open)
        val lowerWick = min(lastCandle.open, lastCandle.close) - lastCandle.low
        val isRejectionWick = lowerWick >= body * 1.4 && lowerWick >= (lastCandle.high - lastCandle.low) * 0.40

        val isTriggerValid = candlePattern != null && (
            candlePattern.contains("Bullish", ignoreCase = true) ||
            candlePattern.contains("Hammer", ignoreCase = true) ||
            candlePattern.contains("Morning", ignoreCase = true) ||
            candlePattern.contains("Pin", ignoreCase = true)
        ) || isRejectionWick || microMicro.hasBullishSweep

        val patternName = when {
            candlePattern != null -> candlePattern
            isRejectionWick -> "Rejection Wick"
            microMicro.hasBullishSweep -> "Liquidity Sweep"
            else -> "Netral / Doji"
        }
        val triggerDetail = when {
            isTriggerValid -> "Konfirmasi Price Action: Pola $patternName terbentuk di area kunci. Buyer mengambil kendali."
            else -> "Belum ada pola candlestick pembalikan (Hammer/Engulfing) yang jelas di level support."
        }
        val step4 = ConfluenceCheckpoint(
            number = 4,
            code = "TRG",
            label = "Price Action",
            isOk = isTriggerValid,
            status = if (isTriggerValid) MtfLegStatus.OK else MtfLegStatus.WAITING,
            metricValue = patternName,
            detail = triggerDetail
        )

        // ═════════════════════════════════════════════════════════════════════
        // CHECKPOINT 5: Filter Momentum (Divergence & Oscillators)
        // RSI tidak overbought (>70) dan terhindar dari dead cat bounce.
        // Deteksi Bullish Divergence (Harga Lower Low, RSI Higher Low).
        // ═════════════════════════════════════════════════════════════════════
        val rsi = IndicatorMath.rsi(microCandles, min(14, microCandles.size - 1))
        val macdResult = IndicatorMath.macdSeries(microCloses, 12, 26, 9)
        val macdHist = macdResult.lastMacd - macdResult.lastSignal
        val divergence = IndicatorMath.detectDivergence(microCandles, 14, 25)

        val isNotOverbought = rsi <= 68.0
        val isMomentumPositive = (macdHist >= 0 || divergence.hasBullishDivergence || rsi in 32.0..62.0)
        val isMomentumValid = isNotOverbought && isMomentumPositive

        val momMetric = when {
            divergence.hasBullishDivergence -> "Bullish Div · RSI ${String.format(Locale.US, "%.0f", rsi)}"
            macdHist >= 0 -> "RSI ${String.format(Locale.US, "%.0f", rsi)} · MACD Positif"
            else -> "RSI ${String.format(Locale.US, "%.0f", rsi)} · Momentum Lemah"
        }
        val momDetail = when {
            divergence.hasBullishDivergence -> divergence.detail
            !isNotOverbought -> "RSI ${String.format(Locale.US, "%.1f", rsi)} terlampau tinggi (Jenuh Beli). Risiko koreksi tinggi."
            isMomentumPositive -> "Osilator di zona akumulasi ideal (RSI ${String.format(Locale.US, "%.1f", rsi)}, MACD menguat)."
            else -> "Momentum osilator masih lemah / dead cat bounce belum terkonfirmasi pulih."
        }
        val step5 = ConfluenceCheckpoint(
            number = 5,
            code = "MOM",
            label = "Momentum / Div",
            isOk = isMomentumValid,
            status = if (isMomentumValid) MtfLegStatus.OK else MtfLegStatus.WAITING,
            metricValue = momMetric,
            detail = momDetail
        )

        // ═════════════════════════════════════════════════════════════════════
        // CHECKPOINT 6: Uji Risk-to-Reward Ratio (Net R:R >= ambang; bawaan 1:2)
        // Stop Loss di bawah swing low / support terdekat.
        // TP1 memberi rasio minimal sesuai ambang setelah memperhitungkan fee bursa.
        // Pemanggil mode lain (INTRADAY, paket C) boleh mengirim geometri, ambang, **dan angka
        // R:R-nya sendiri** lewat `overrideStopLoss`/`overrideTp1`/`netRrThreshold`/`overrideNetRr`
        // — jangan menghitung ulang dan jangan memipihkan angka pemicu.
        // ═════════════════════════════════════════════════════════════════════
        val calculatedSl = overrideStopLoss ?: maxOf(
            min(lastCandle.low, supportLevel) - effectiveAtr * 0.25,
            price - effectiveAtr * 1.5,
            price * 0.94
        ).coerceAtMost(price * 0.985)

        // Hitung TP1 logis: mengarah ke Resistance atau minimal 2.0x SL
        val minTargetFor2R = price + (price - calculatedSl) * 2.15
        val calculatedTp1 = overrideTp1 ?: maxOf(resistanceLevel, minTargetFor2R)
        val calculatedTp2 = calculatedTp1 + (calculatedTp1 - price) * 0.65

        // Satu definisi R:R: angka dari pemicu dipakai apa adanya (tidak dipipihkan, tidak
        // dihitung ulang). Jatuh ke FeeCalculator hanya bila pemicu tidak mengirim angka.
        val netRr = overrideNetRr ?: FeeCalculator.roundTrip(
            entry = price,
            stopLoss = calculatedSl,
            takeProfit = calculatedTp1,
            fees = fees,
            useMaker = useMaker,
            slippagePct = slippagePct
        ).netRr
        val isRiskRewardValid = netRr >= netRrThreshold

        // Label wajib menyebut **TP1**: sejak paket B3 gate dinilai dari TP1, bukan TP2.
        // Angka "net" = setelah fee beli + fee jual + slippage dua sisi (FeeCalculator).
        val rrMetric = "Net R:R TP1 1:${String.format(Locale.US, "%.2f", netRr)}"
        val rrDetail = if (isRiskRewardValid) {
            "Rasio Risk:Reward istimewa (Net R:R TP1 1:${String.format(Locale.US, "%.2f", netRr)} >= 1:${String.format(Locale.US, "%.2f", netRrThreshold)}). Net = setelah fee beli + fee jual + slippage dua sisi. SL di ${PriceFormatter.formatPrice(calculatedSl, quoteAsset = quoteAsset)}, TP1 di ${PriceFormatter.formatPrice(calculatedTp1, quoteAsset = quoteAsset)}."
        } else {
            "Net R:R TP1 1:${String.format(Locale.US, "%.2f", netRr)} < 1:${String.format(Locale.US, "%.2f", netRrThreshold)}. Ruang menuju resistance terlalu sempit. Net = setelah fee beli + fee jual + slippage dua sisi. Tunggu harga mendekati support ${PriceFormatter.formatPrice(supportLevel, quoteAsset = quoteAsset)} untuk validasi R:R."
        }
        val step6 = ConfluenceCheckpoint(
            number = 6,
            code = "RR",
            label = "Risk / Reward",
            isOk = isRiskRewardValid,
            status = if (isRiskRewardValid) MtfLegStatus.OK else MtfLegStatus.WAITING,
            metricValue = rrMetric,
            detail = rrDetail
        )

        val checkpoints = listOf(step1, step2, step3, step4, step5, step6)
        val completedCount = checkpoints.count { it.isOk }
        val isAllPassed = completedCount == 6

        val path = if (isHealthyBreakoutVol) ScalpingPath.MOMENTUM_CONTINUATION else ScalpingPath.PULLBACK
        val statusTitle = when {
            isAllPassed -> "${strategyMode.label.uppercase(Locale.US)} SIAP ENTRI (6/6)"
            completedCount >= 4 -> "${strategyMode.label.uppercase(Locale.US)} MENGUJI ($completedCount/6)"
            else -> "${strategyMode.label.uppercase(Locale.US)} MEMANTAU ($completedCount/6)"
        }

        val waitingFor = checkpoints.firstOrNull { !it.isOk }?.let {
            "Menunggu: ${it.label} (${it.metricValue})"
        } ?: "Semua 6 konfluensi terpenuhi. Siap entry!"

        val mtfSnapshot = ScalpingMtfSnapshot(
            biasOk = step1.isOk,
            biasDirection = if (step1.isOk) "bullish" else "neutral",
            biasStatus = step1.status,
            biasDetail = step1.detail,

            setupOk = step2.isOk,
            setupStatus = step2.status,
            setupDetail = step2.detail,

            triggerOk = step4.isOk,
            triggerStatus = step4.status,
            triggerDetail = step4.detail,

            entryPriceOk = step6.isOk,
            entryPriceStatus = step6.status,
            entryPriceDetail = step6.detail,

            path = path,
            statusTitle = statusTitle,
            waitingFor = waitingFor,
            entryCondition = "6 Konfluensi: MTF · AoV · Vol · Trigger · Mom · Net R:R >= 1:" +
                String.format(Locale.US, "%.2f", netRrThreshold),
            checkpoints = checkpoints,
            completedCount = completedCount
        )

        return ConfluenceResult(
            checkpoints = checkpoints,
            completedCount = completedCount,
            isAllPassed = isAllPassed,
            recommendedStopLoss = calculatedSl,
            recommendedTp1 = calculatedTp1,
            recommendedTp2 = calculatedTp2,
            netRiskReward = netRr,
            mtfSnapshot = mtfSnapshot,
            summaryReason = waitingFor
        )
    }
}

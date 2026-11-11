package agu.analys.engine.intraday

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

data class IntradayEvalResult(
    val signal: AISignalState,
    val indicators: TechnicalIndicators,
    val isQualified: Boolean = false,
    val setupScore: Int = 0
)

/**
 * INTRADAY TRADING STRATEGY EVALUATOR (OPEN PAGI · CLOSE MALAM)
 *
 * Filosofi Intraday Disiplin Sesi & Anti Flash Dump:
 * - Siklus Harian (jam WIB, selang **setengah-terbuka** `[mulai, selesai)`):
 *   1. Open Pagi (06:00–11:30): jendela pembukaan posisi saat volume & likuiditas pagi terbentuk.
 *   2. Siang Akumulasi (11:30–15:30): boleh entry selektif bila momentum masih sehat.
 *   3. Sore Trailing (15:30–19:30): **hanya** mengawal posisi (trailing), dilarang entry baru.
 *   4. Close Malam (19:30–23:30): tutup posisi menjadi kas sebelum tengah malam.
 *   5. Istirahat (23:30–06:00): dilarang membuka posisi baru (jam rawan dump pasar global).
 *
 *   Konsekuensi selang setengah-terbuka: menit **11:30** milik Siang Akumulasi, **15:30** milik
 *   Sore Trailing, **19:30** milik Close Malam, dan **23:30** milik Istirahat. Label setiap fase
 *   karena itu cocok persis dengan kode — tidak ada menit yang dihitung dua kali. Label yang
 *   tampil di UI memakai menit **terakhir** fase (mis. `06:00–11:29`) supaya batasnya tidak
 *   terbaca sebagai selang tertutup.
 *
 * - Anti Flash Dump Protection:
 *   1. Memindai histori harga panjang (H4 100–200 candle / D1 100 candle).
 *   2. Deteksi trauma flash dump pada **12 candle terakhir** (= 48 jam bila candle H4) tanpa base
 *      akumulasi kokoh. Angka ini dari [IntradayConfig], bukan literal di badan fungsi.
 *   3. Deteksi fake pump rejection wick (upper wick panjang) pencegah jebakan pompa.
 *   4. Validasi baseline support jangka panjang (EMA100/EMA200).
 */
object IntradayEvaluator {

    // Batas sesi dalam menit sejak 00:00 WIB. Selangnya setengah-terbuka [mulai, selesai),
    // jadi 11:30 = HOLD_SIANG, 15:30 = HOLD_SORE, 19:30 = CLOSE_MALAM, 23:30 = REST_MALAM.
    private const val OPEN_PAGI_START = 6 * 60
    private const val HOLD_SIANG_START = 11 * 60 + 30
    private const val HOLD_SORE_START = 15 * 60 + 30
    private const val CLOSE_MALAM_START = 19 * 60 + 30
    private const val REST_MALAM_START = 23 * 60 + 30

    enum class IntradayPhase(
        val label: String,
        val isOpenWindow: Boolean,
        val isCloseWindow: Boolean,
        val isRestWindow: Boolean,
        val isTrailingWindow: Boolean = false
    ) {
        // Label memakai menit **terakhir** yang masih milik fase itu, supaya batas yang tampil
        // tidak bisa dibaca sebagai selang tertutup: 11:30 milik Siang, bukan Open Pagi.
        OPEN_PAGI("Sesi Open Pagi (06:00–11:29 WIB)", isOpenWindow = true, isCloseWindow = false, isRestWindow = false),
        HOLD_SIANG("Sesi Siang Akumulasi (11:30–15:29 WIB)", isOpenWindow = true, isCloseWindow = false, isRestWindow = false),
        HOLD_SORE("Sesi Sore Trailing (15:30–19:29 WIB)", isOpenWindow = false, isCloseWindow = false, isRestWindow = false, isTrailingWindow = true),
        CLOSE_MALAM("Sesi Close Malam (19:30–23:29 WIB)", isOpenWindow = false, isCloseWindow = true, isRestWindow = false),
        REST_MALAM("Sesi Istirahat (23:30–05:59 WIB)", isOpenWindow = false, isCloseWindow = false, isRestWindow = true)
    }

    fun getCurrentIntradayPhase(timestamp: Long = System.currentTimeMillis()): IntradayPhase {
        val wibCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta"))
        wibCal.timeInMillis = timestamp
        val hour = wibCal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = wibCal.get(java.util.Calendar.MINUTE)
        val timeMinutes = hour * 60 + minute

        return when {
            timeMinutes in OPEN_PAGI_START until HOLD_SIANG_START -> IntradayPhase.OPEN_PAGI
            timeMinutes in HOLD_SIANG_START until HOLD_SORE_START -> IntradayPhase.HOLD_SIANG
            timeMinutes in HOLD_SORE_START until CLOSE_MALAM_START -> IntradayPhase.HOLD_SORE
            timeMinutes in CLOSE_MALAM_START until REST_MALAM_START -> IntradayPhase.CLOSE_MALAM
            else -> IntradayPhase.REST_MALAM
        }
    }

    fun evaluate(
        price: Double,
        history: List<CandleBar>,
        fees: TradingFeeConfig = TradingFeeConfig(),
        evaluationTimestamp: Long? = null,
        symbol: String = "",
        config: IntradayConfig = IntradayConfig.DEFAULT
    ): IntradayEvalResult = evaluate(
        globalContext = agu.analys.engine.global.GlobalMarketContext(),
        price = price,
        history = history,
        fees = fees,
        macroAnomalyResult = null,
        evaluationTimestamp = evaluationTimestamp,
        symbol = symbol,
        config = config
    )

    fun evaluate(
        globalContext: agu.analys.engine.global.GlobalMarketContext = agu.analys.engine.global.GlobalMarketContext(),
        price: Double,
        history: List<CandleBar>,
        fees: TradingFeeConfig = TradingFeeConfig(),
        macroAnomalyResult: agu.analys.engine.regime.MacroAnomalyResult? = null,
        evaluationTimestamp: Long? = null,
        symbol: String = "",
        config: IntradayConfig = IntradayConfig.DEFAULT
    ): IntradayEvalResult {
        if (price <= 0.0) {
            return IntradayEvalResult(AISignalState(), TechnicalIndicators())
        }

        val quoteAsset = if (symbol.isNotBlank()) agu.analys.util.PriceFormatter.extractQuote(symbol) else "IDR"

        val evalTime = evaluationTimestamp ?: history.lastOrNull()?.timestamp ?: System.currentTimeMillis()

        val minCandles = 20
        if (history.size < minCandles) {
            val mtfSnapshot = ScalpingMtfSnapshot(
                biasOk = false,
                biasDirection = "neutral",
                biasStatus = MtfLegStatus.WAITING,
                biasDetail = "Mengumpulkan candle makro (${history.size}/$minCandles)...",
                setupOk = false,
                setupStatus = MtfLegStatus.WAITING,
                setupDetail = "Menunggu data H4/1D untuk validasi tren yang stabil.",
                triggerOk = false,
                triggerStatus = MtfLegStatus.WAITING,
                triggerDetail = "Menunggu konfirmasi area beli aman.",
                entryPriceOk = false,
                entryPriceStatus = MtfLegStatus.WAITING,
                entryPriceDetail = "Menunggu riwayat candle pasar untuk memetakan level entry Intraday.",
                path = ScalpingPath.PULLBACK,
                statusTitle = "MENGUMPULKAN DATA",
                waitingFor = "Menunggu sinkronisasi candle makro",
                entryCondition = "Memuat riwayat candle untuk setup Intraday."
            )

            return IntradayEvalResult(
                signal = AISignalState(
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
                        "Menunggu riwayat candle untuk setup Intraday."
                    ),
                    timestamp = evalTime,
                    scalpingStage = ScalpingStage.HOLD,
                    mtf = mtfSnapshot
                ),
                indicators = TechnicalIndicators(),
                isQualified = false,
                setupScore = 0
            )
        }

        val closes = DoubleArray(history.size) { history[it].close }
        val highs = history.map { it.high }
        val rsi = IndicatorMath.rsi(history, min(14, history.size - 1))
        val ema20 = IndicatorMath.ema(closes, min(20, closes.size))
        val ema50 = IndicatorMath.ema(closes, min(50, closes.size))
        val ema100 = if (closes.size >= 100) IndicatorMath.ema(closes, 100) else Double.NaN
        val ema200 = if (closes.size >= 200) IndicatorMath.ema(closes, 200) else Double.NaN
        val macdResult = IndicatorMath.macdSeries(closes, 12, 26, 9)
        val macd = macdResult.lastMacd
        val macdSignal = macdResult.lastSignal
        val macdHist = macd - macdSignal
        // Paket C: histogram MACD dinormalisasi ke % harga. Ambang lama `macdHist >= -0,002`
        // bersatuan harga (mis. −1.656.379 pada BTC/IDR) sehingga praktis berarti "histogram
        // tidak negatif" dan tidak berskala antar koin. Bawaan ambang 0,0 = setara perilaku lama
        // untuk koin berharga besar; alternatifnya diukur, bukan diubah diam-diam.
        val macdHistPct = if (price > 0.0) macdHist / price * 100.0 else 0.0
        val bb = IndicatorMath.bollinger(closes, min(20, closes.size))
        val atr = IndicatorMath.atr(history, min(14, history.size - 1))
        val pattern = CandlePatternDetector.detect(history)
        val regime = MarketRegimeDetector.detect(price, ema20, ema50, macdHist, rsi, atr, bb.first, bb.second)
        val structure = MarketStructureAnalyzer.analyze(history)
        val momentumBase = closes[closes.lastIndex - min(10, closes.size - 1)]
        val momentum = if (momentumBase > 0.0) (price - momentumBase) / momentumBase else 0.0
        val indicators = TechnicalIndicators(rsi, macd, macdSignal, macdHist, ema20, ema50, ema200, bb.second, bb.first, atr, momentum)

        val intradayPhase = getCurrentIntradayPhase(evalTime)
        var buyScore = 0.0
        var sellScore = 0.0
        val reasons = mutableListOf<String>()
        reasons += "Kondisi Pasar: $regime · ${intradayPhase.label}"

        // ── ANTI-FLASH DUMP DETECTOR (Menggunakan Histori Panjang) ──────────
        // 1. Pindai riwayat dump mendadak dalam N candle terakhir (default 18 candle).
        //    Ambang umur trauma juga dari config (default 12 candle = 48 jam bila candle H4).
        var flashDumpTrauma = false
        var flashDumpDetail = ""
        val lookbackDump = min(config.flashDumpScanCandles, history.size)
        val recentHistory = history.takeLast(lookbackDump)
        for (i in recentHistory.indices) {
            val bar = recentHistory[i]
            val dropFromOpen = if (bar.open > 0.0) (bar.open - bar.close) / bar.open else 0.0
            val candleRange = if (bar.high > 0.0) (bar.high - bar.low) / bar.high else 0.0
            if (dropFromOpen >= 0.075 || (candleRange >= 0.12 && bar.close < bar.open)) {
                val candlesAgo = recentHistory.size - 1 - i
                if (candlesAgo < config.flashDumpTraumaCandles) {
                    flashDumpTrauma = true
                    flashDumpDetail = "Pernah flash dump -${fmt(max(dropFromOpen, candleRange) * 100)}% ($candlesAgo candle lalu). Rawan dump susulan."
                    break
                }
            }
        }

        // 2. Deteksi Jebakan Pompa (Fake Pump / Long Upper Wick Rejection)
        var pumpAndDumpTrap = false
        val last3Candles = history.takeLast(min(4, history.size))
        for (bar in last3Candles) {
            val body = abs(bar.close - bar.open)
            val upperWick = bar.high - max(bar.open, bar.close)
            if (upperWick > 1.8 * max(body, bar.close * 0.008) && bar.high > price * 1.025) {
                pumpAndDumpTrap = true
                break
            }
        }

        // 3. Long-term Baseline Downtrend
        val isMacroDowntrend = (ema100.isFinite() && price < ema100 * 0.97) || (ema200.isFinite() && price < ema200 * 0.96)

        // ── Recent high / extension filter (cegah beli di pucuk) ────────
        val lookbackHigh = min(30, highs.size)
        val recentHigh = highs.takeLast(lookbackHigh).maxOrNull() ?: price
        val effectiveAtr = if (atr.isFinite() && atr > 0.0) atr else (price * 0.04)
        val distToHighPct = if (recentHigh > 0.0) (recentHigh - price) / recentHigh else 1.0
        val meanCount = min(20, closes.size)
        val mean20 = if (meanCount > 0) {
            var sum = 0.0
            for (i in (closes.size - meanCount) until closes.size) sum += closes[i]
            sum / meanCount
        } else price
        val atrExtension = if (effectiveAtr > 0.0) (price - mean20) / effectiveAtr else 0.0

        val tooCloseToHigh = distToHighPct < 0.012
        val isOverExtended = atrExtension > 1.8

        // 6. Level SL & TP Intraday (Dioptimalkan untuk Trade Harian: Open Pagi, Close Malam)
        val supportLevel = structure.support?.takeIf { it > 0.0 && it < price }
            ?: (price - effectiveAtr * config.noSupportAtrMult)

        // 1. Trend & Moving Average Alignment
        val isEstablishedUptrend = indicators.ema20.isFinite() && indicators.ema50.isFinite() && (ema20 > ema50) && (price >= ema50 * 0.985)
        val candle5Ago = if (closes.size >= 5) closes[closes.size - 5] else (closes.firstOrNull() ?: price)
        val isGoldenCross = ema20 > ema50 && candle5Ago <= ema50

        // Early Reversal Pagi / Reclaim Support:
        // Sesi pagi sering kali memantul dari support dan menembus kembali ke atas EMA20 (reclaim)
        // dengan momentum positif, meski lagging EMA20 belum sempat golden cross di atas EMA50.
        val isReclaimingEma20 = indicators.ema20.isFinite() && price >= ema20 * 0.995
        val isEarlyReversal = isReclaimingEma20 && macdHistPct >= config.macdHistMinPct && rsi in 35.0..65.0 && (price >= supportLevel * 0.98)
        val isTrendValidForIntraday = isEstablishedUptrend || isGoldenCross || isEarlyReversal

        val isDowntrend = indicators.ema20.isFinite() && indicators.ema50.isFinite() && (ema20 < ema50 && price < ema20 * 0.995 && !isEarlyReversal)

        when {
            isEstablishedUptrend -> { buyScore += 28; reasons += "Tren makro solid (EMA20 > EMA50, harga di atas support dinamis)." }
            isGoldenCross -> { buyScore += 24; reasons += "Baru terjadi Golden Cross EMA." }
            isEarlyReversal -> { buyScore += 24; reasons += "Early Reversal Pagi: Harga reclaim EMA20 (${PriceFormatter.formatPrice(ema20, quoteAsset = quoteAsset)}) dari support harian." }
            isDowntrend -> { sellScore += 24; reasons += "Tren makro bearish (EMA20 < EMA50 & harga di bawah EMA20)." }
            else -> reasons += "Tren berkonsolidasi, menunggu arah tren tegas."
        }

        // 2. RSI Sweet Spot (40-58 ideal untuk akumulasi Intraday)
        when {
            rsi in 40.0..58.0 -> { buyScore += 26; reasons += "RSI ${fmt(rsi)} di zona akumulasi ideal (Intraday)." }
            rsi in 30.0..40.0 && macdHist > 0 -> { buyScore += 18; reasons += "RSI oversold rebound dengan momentum positif." }
            rsi in 58.0..68.0 -> { buyScore += 8; reasons += "RSI ${fmt(rsi)} masih OK tapi mendekati zona tinggi." }
            rsi > 72.0 -> { sellScore += 28; reasons += "RSI ${fmt(rsi)} overbought (potensi koreksi)." }
            rsi < 30.0 -> { buyScore += 12; reasons += "RSI ${fmt(rsi)} jenuh jual (peluang rebound)." }
            else -> reasons += "RSI ${fmt(rsi)} netral."
        }

        // 3. MACD Momentum (histogram dinormalisasi ke % harga — paket C)
        if (macdHist > 0) {
            buyScore += 18
            reasons += "Histogram MACD positif (+${fmt(macdHistPct, 3)}% dari harga)."
        } else {
            sellScore += 14
            reasons += "Histogram MACD negatif (${fmt(macdHistPct, 3)}% dari harga)."
        }

        // 4. Struktur Higher High / Higher Low
        val isBullishStructure = structure.trend.contains("Bull", true)
        val isBearishStructure = structure.trend.contains("Bear", true) && !isEarlyReversal
        if (structure.dataEnough) {
            when {
                isBullishStructure -> { buyScore += 18; reasons += "Struktur chart: Higher-High & Higher-Low stabil." }
                isBearishStructure -> { sellScore += 16; reasons += "Struktur chart: Lower-Low (Risiko penurunan)." }
            }
        }

        // 5. Pola Candlestick
        pattern?.let {
            if (it.contains("Bullish", true) || it.contains("Hammer", true) || it.contains("Morning", true)) {
                buyScore += 12; reasons += "Pola Reversal: $it."
            } else if (it.contains("Bearish", true) || it.contains("Shooting", true) || it.contains("Evening", true)) {
                sellScore += 14; reasons += "Pola Pelemahan: $it."
            }
        }

        // ── Paket C: geometri SL/TP satu definisi (IntradayGeometry) ─────────
        // Dulu band persen tetap di evaluator ini (SL −1,5%…−2,8%, TP1 +2,8%…+5,0%) membuat
        // net R:R TP1 ≈0,74–0,87 — tidak pernah lolos ambang 1,5, jadi sinyal BUY selalu nol.
        // Sekarang TP1 diturunkan dari SL + ATR + ambang R:R, dibatasi resistance/band atas
        // supaya gate masih bisa gagal jujur ketika ruang tidak cukup.
        val resistanceHint = structure.resistance?.takeIf { it > price }
        val geometry = IntradayGeometry.resolve(
            price = price,
            supportLevel = supportLevel,
            resistanceLevel = resistanceHint,
            atr = effectiveAtr,
            fees = fees,
            config = config
        ) ?: return IntradayEvalResult(AISignalState(), TechnicalIndicators()) // fail-closed
        val calculatedSl = geometry.stopLoss
        val calculatedTp1 = geometry.takeProfit1
        val calculatedTp2 = geometry.takeProfit2

        // Net R:R dihitung dari **TP1** — target yang benar-benar dipakai untuk menutup posisi
        // pertama. Tanpa clamp (`coerceAtLeast(1.6)` yang lama membuat gate selalu lolos).
        val netRrTp1 = geometry.netRrTp1
        val rrString = "1:${fmt(netRrTp1)}"

        // ── DANGER & INVALIDATION ───────────────────────────────────────────
        val isRsiOverbought = rsi >= 72.0
        val isNearHighDanger = tooCloseToHigh || isOverExtended
        
        val isParabolicUnwind = macroAnomalyResult?.isParabolicUnwind == true
        if (isParabolicUnwind) {
            reasons.add(0, "🚨 DITOLAK (HARD): Terdeteksi Parabolic Unwind (Koin baru saja pump ekstrim dan sedang turun). Dilarang beli.")
        }

        if (flashDumpTrauma) {
            reasons.add(0, "🚨 DITOLAK (Anti Flash Dump): $flashDumpDetail")
        }

        if (pumpAndDumpTrap) {
            reasons.add(0, "🚨 DITOLAK (Fake Pump Trap): Terdeteksi Upper Wick panjang penolakan pucuk (Fake Pump Trap).")
        }

        if (isMacroDowntrend) {
            reasons.add(0, "⚠️ Macro Downtrend: Harga di bawah baseline EMA jangka panjang (Rawan dump).")
            sellScore += 20
        }
        
        // Distribusi / breakdown
        val isDistribution = sellScore >= 48.0 && sellScore > buyScore * 1.15
        val isBreakdown = (price < supportLevel * 0.965) || (isDowntrend && !isEarlyReversal)
        val isDangerous = isRsiOverbought || isNearHighDanger || isDistribution || isBreakdown || isParabolicUnwind || flashDumpTrauma || pumpAndDumpTrap || isMacroDowntrend

        if (isNearHighDanger) {
            reasons.add(0, "⚠️ Tertahan: Harga terlalu dekat recent high (${PriceFormatter.formatPrice(recentHigh, quoteAsset = quoteAsset)}) / overextended. Hindari beli di pucuk.")
        }

        // ── WATERFALL CHECKPOINTS ───────────────────────────────────────────
        val confluence = agu.analys.engine.confluence.ConfluenceEvaluator.evaluate(
            price = price,
            macroCandles = history,
            microCandles = history,
            strategyMode = agu.analys.config.StrategyMode.OFFICE_DAILY,
            fees = fees,
            quoteAsset = quoteAsset,
            useMaker = config.useMakerFee,
            slippagePct = config.slippagePct,
            // Paket C: satu geometri, satu ambang — checkpoint 6 memakai SL/TP dan ambang
            // intraday yang sama dengan sinyal, bukan geometri + gate 2.0-nya sendiri.
            netRrThreshold = config.minNetRrTp1,
            overrideStopLoss = calculatedSl,
            overrideTp1 = calculatedTp1,
            // Satu definisi R:R: checkpoint 6 memakai angka geometri apa adanya (bisa negatif).
            // Tanpa ini checkpoint menghitung ulang lewat `FeeCalculator.roundTrip` yang
            // memipihkan R:R negatif ke 0 — dua definisi untuk sinyal yang sama.
            overrideNetRr = netRrTp1
        )

        val step1Ok = !isDangerous && isTrendValidForIntraday && !flashDumpTrauma
        val step2Ok = step1Ok && (price >= supportLevel * 0.988) && !tooCloseToHigh && !pumpAndDumpTrap
        val step3Ok = step2Ok && (rsi in 35.0..65.0) && macdHistPct >= config.macdHistMinPct
        val step4Ok = step3Ok && netRrTp1 >= config.minNetRrTp1 && buyScore >= 50.0 && !isOverExtended

        val completedSteps = confluence.completedCount

        // ── Keputusan akhir Intraday Disiplin Sesi (Open Pagi, Close Malam) ──
        // Diperbolehkan BUY pada Sesi Open Pagi (06:00–11:30 WIB) atau Sesi Siang Akumulasi jika momentum kuat
        val isQualified = step4Ok && buyScore >= 52.0 && buyScore > sellScore * 1.15 && !isNearHighDanger && !flashDumpTrauma && !pumpAndDumpTrap && intradayPhase.isOpenWindow && confluence.completedCount >= 4
        
        var baseConfidence = if (isQualified) (buyScore).coerceAtMost(90.0).toInt() else (buyScore).coerceAtMost(60.0).toInt()
        val regimeMultiplier = when {
            regime.contains("SIDEWAYS") -> 0.7
            regime.contains("Tinggi") -> 0.6
            else -> 1.0
        }
        val confidence = if (isParabolicUnwind || flashDumpTrauma) 0 else (baseConfidence * regimeMultiplier).toInt()
        
        val finalAction = when {
            globalContext.isVetoActive -> SignalAction.HOLD
            isParabolicUnwind || flashDumpTrauma -> SignalAction.HOLD
            intradayPhase.isCloseWindow -> {
                reasons.add(0, "🌙 SESI CLOSE MALAM (19:30–23:29 WIB): Amankan profit harian & tutup posisi menjadi kas IDR sebelum tengah malam (Hindari overnight dump).")
                SignalAction.SELL
            }
            intradayPhase.isRestWindow -> {
                reasons.add(0, "💤 SESI ISTIRAHAT (23:30–05:59 WIB): Pasar ditutup untuk posisi baru. Menghindari flash dump dini hari.")
                SignalAction.HOLD
            }
            intradayPhase.isTrailingWindow -> {
                reasons.add(0, "🛡️ SESI SORE TRAILING (15:30–19:29 WIB): Pasang trailing stop untuk mengunci profit harian. Tidak membuka posisi baru.")
                SignalAction.HOLD
            }
            isQualified && !isDangerous -> {
                if (intradayPhase == IntradayPhase.OPEN_PAGI) {
                    reasons.add(0, "⚡ SESI OPEN PAGI (06:00–11:29 WIB): Momentum harian terkonfirmasi. Waktu prima eksekusi posisi.")
                } else {
                    reasons.add(0, "☀️ SESI SIANG (11:30–15:29 WIB): Akumulasi tren lanjutan sehat. Siapkan TP/trailing sebelum malam.")
                }
                SignalAction.BUY
            }
            else -> SignalAction.HOLD
        }

        if (isDangerous && !isNearHighDanger && !flashDumpTrauma && !pumpAndDumpTrap) {
            when {
                isRsiOverbought -> reasons.add(0, "⚠️ Tertahan: RSI jenuh beli (>= 72).")
                isBreakdown -> reasons.add(0, "⚠️ Tertahan: Harga breakdown / downtrend menembus support.")
                isDistribution -> reasons.add(0, "⚠️ Tertahan: Tekanan jual & distribusi tinggi terdeteksi.")
            }
        }

        val finalScoreRaw = when {
            isDangerous -> 20
            isQualified -> (82 + min(13, (buyScore * 0.12).toInt())).coerceIn(80, 95)
            step3Ok -> 62
            step2Ok -> 48
            step1Ok -> 32
            else -> 18
        }
        val finalScore = if (isParabolicUnwind || flashDumpTrauma) 0 else (finalScoreRaw * regimeMultiplier).toInt()

        val biasDetailText = when {
            flashDumpTrauma -> "Ditolak: Riwayat flash dump dalam ${config.flashDumpTraumaCandles} candle terakhir."
            pumpAndDumpTrap -> "Ditolak: Trap upper wick tajam."
            isRsiOverbought -> "Tertahan: RSI overbought."
            isNearHighDanger -> "Tertahan: Terlalu dekat recent high / overextended."
            isBreakdown -> "Tertahan: Breakdown / downtrend."
            isDistribution -> "Tertahan: Distribusi tinggi."
            isEstablishedUptrend -> "Tren makro selaras (EMA20 > EMA50)."
            isGoldenCross -> "Golden Cross EMA terkonfirmasi."
            isEarlyReversal -> "Reversal Pagi: Reclaim EMA20 & akumulasi support."
            step1Ok -> "Struktur tren intraday valid."
            else -> "Menunggu tren makro stabil."
        }

        val setupDetailText = when {
            !step1Ok -> "Menunggu Checkpoint 1 lolos."
            step2Ok -> "Support aman di ${PriceFormatter.formatPrice(supportLevel, quoteAsset = quoteAsset)} · Jarak ke high ${fmt(distToHighPct * 100)}%."
            else -> "Memantau lantai support / jarak ke high."
        }

        val triggerDetailText = when {
            !step2Ok -> "Menunggu Checkpoint 2 lolos."
            step3Ok -> "RSI (${fmt(rsi)}) & MACD akumulasi stabil."
            else -> "Menunggu momentum RSI & MACD stabil."
        }

        val entryPriceDetailText = when {
            !intradayPhase.isOpenWindow -> "Sesi entry ditutup (${intradayPhase.label})."
            !step3Ok -> "Menunggu Checkpoint 3 lolos."
            step4Ok -> "Zona Entry: ${PriceFormatter.formatPrice(price, quoteAsset = quoteAsset)} (Net R:R TP1 $rrString)."
            else -> "Menunggu R:R optimal (Min 1:${fmt(config.minNetRrTp1)}) & skor buy."
        }

        val mtfSnapshot = ScalpingMtfSnapshot(
            biasOk = step1Ok,
            biasDirection = if (step1Ok) "bullish" else "neutral",
            biasStatus = if (step1Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            biasDetail = biasDetailText,

            setupOk = step2Ok,
            setupStatus = if (step2Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            setupDetail = setupDetailText,

            triggerOk = step3Ok,
            triggerStatus = if (step3Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            triggerDetail = triggerDetailText,

            entryPriceOk = step4Ok,
            entryPriceStatus = if (step4Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            entryPriceDetail = entryPriceDetailText,

            path = if (isBullishStructure) ScalpingPath.MOMENTUM_CONTINUATION else ScalpingPath.PULLBACK,
            statusTitle = when {
                intradayPhase.isCloseWindow -> "CLOSE MALAM (EXIT)"
                intradayPhase.isRestWindow -> "ISTIRAHAT (NO ENTRY)"
                intradayPhase.isTrailingWindow -> "SORE TRAILING (HOLD)"
                flashDumpTrauma -> "FLASH DUMP TRAUMA (HOLD)"
                pumpAndDumpTrap -> "FAKE PUMP TRAP (HOLD)"
                isRsiOverbought -> "OVERBOUGHT (HOLD)"
                isNearHighDanger -> "DEKAT HIGH / OVEREXTENDED (HOLD)"
                isBreakdown -> "BREAKDOWN / DOWNTREND (HOLD)"
                isDistribution -> "DISTRIBUSI TINGGI (HOLD)"
                confluence.isAllPassed -> if (intradayPhase == IntradayPhase.OPEN_PAGI) "READY (OPEN PAGI 6/6)" else "READY (INTRADAY 6/6)"
                confluence.completedCount > 0 -> "ANALYZING (${confluence.completedCount}/6)"
                else -> "ANALYZING (0/6)"
            },
            waitingFor = when {
                intradayPhase.isCloseWindow -> "Sesi Close Malam: Tutup posisi harian menjadi kas sebelum tengah malam"
                intradayPhase.isRestWindow -> "Menunggu Sesi Open Pagi (06:00 WIB)"
                intradayPhase.isTrailingWindow -> "Kawal profit dengan trailing stop (15:30–19:29 WIB)"
                flashDumpTrauma -> "Menunggu kestabilan base konsolidasi pasca-dump"
                pumpAndDumpTrap -> "Menunggu pullback aman dari rejection upper wick"
                isRsiOverbought -> "Menunggu koreksi / reset RSI"
                isNearHighDanger -> "Menunggu pullback dari zona high"
                isBreakdown -> "Menunggu pembentukan support baru"
                isDistribution -> "Menunggu tekanan jual mereda"
                confluence.isAllPassed -> "Siap eksekusi (Open Pagi)"
                else -> confluence.summaryReason
            },
            entryCondition = "Intraday 6 Konfluensi · Anti Flash Dump · High R:R",
            checkpoints = confluence.checkpoints,
            completedCount = confluence.completedCount
        )

        return IntradayEvalResult(
            signal = AISignalState(
                action = finalAction,
                confidence = finalScore,
                sentiment = when (finalAction) {
                    SignalAction.BUY -> TrendSentiment.STRONG_BULLISH_CONTINUATION
                    SignalAction.SELL -> TrendSentiment.BEARISH_DISTRIBUTION
                    SignalAction.HOLD -> if (isDangerous) TrendSentiment.BEARISH_DISTRIBUTION else if (completedSteps >= 2) TrendSentiment.ACCUMULATION_SQUEEZE else TrendSentiment.NEUTRAL_CONSOLIDATION
                },
                entryPrice = price,
                targetPrice1 = calculatedTp1,
                targetPrice2 = calculatedTp2,
                stopLoss = calculatedSl,
                riskRewardRatio = rrString,
                reasoning = reasons.take(8),
                timestamp = evalTime,
                patternDetected = pattern,
                scalpingStage = if (completedSteps == 4 && intradayPhase.isOpenWindow) ScalpingStage.ENTRY else if (completedSteps >= 2) ScalpingStage.WAIT_PULLBACK else ScalpingStage.HOLD,
                mtf = mtfSnapshot
            ),
            indicators = indicators,
            isQualified = isQualified,
            setupScore = completedSteps
        )
    }

    private fun fmt(v: Double, decimals: Int = 2) = agu.analys.util.PriceFormatter.fmt(v, decimals)
    private fun fmtPrice(v: Double) = agu.analys.util.PriceFormatter.fmtPrice(v)
}

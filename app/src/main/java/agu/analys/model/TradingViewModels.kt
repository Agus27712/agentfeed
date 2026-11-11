package agu.analys.model

enum class SignalAction { BUY, SELL, HOLD }

enum class ScalpingStage(val displayName: String) {
    HOLD("TAHAN"),
    WATCH("WATCH"),
    WAIT_PULLBACK("TUNGGU PULLBACK"),
    EARLY_ENTRY("AWAL ENTRY"),
    ENTRY("ENTRY"),
    STRONG_ENTRY("ENTRY KUAT")
}

enum class LifecycleState(val displayName: String) {
    IDLE("IDLE"),
    DETECTED("TERDETEKSI"),
    CONFIRMING("KONFIRMASI"),
    READY("SIAP ENTRY"),
    TRIGGERED("TERPICU"),
    EXPIRED("KEDALUWARSA"),
    INVALIDATED("BATAL")
}

/** Status satu leg MTF untuk UI — diisi engine, bukan dihitung ulang di Compose. */
enum class MtfLegStatus {
    OK,
    PARTIAL,
    WAITING,
    FAIL,
    UNKNOWN
}

enum class ScalpingPath {
    NONE,
    PULLBACK,
    MOMENTUM_CONTINUATION,
    BOTH,
    ENTRY_READY
}

/**
 * 6 Checkpoint Konfluensi Standar Industri Trading Spot.
 */
data class ConfluenceCheckpoint(
    val number: Int,
    val code: String,              // "MTF", "AOV", "VOL", "TRG", "MOM", "RR"
    val label: String,             // "Struktur MTF", "Area of Value", "Volume Institusi", "Price Action", "Momentum / Div", "Risk/Reward"
    val isOk: Boolean = false,
    val status: MtfLegStatus = MtfLegStatus.WAITING,
    val metricValue: String = "",  // e.g. "EMA Uptrend", "Support Rp 1.450", "Vol 1.6× MA", "Hammer Candle", "RSI 44 Bull Div", "Net R:R TP1 1:2.4"
    val detail: String = ""
)

/**
 * Structured MTF snapshot dari Evaluator Strategi.
 * UI hanya menampilkan — tidak menghitung ulang threshold.
 */
data class ScalpingMtfSnapshot(
    val biasOk: Boolean = false,
    val biasDirection: String = "mixed", // bullish | bearish | mixed
    val biasStatus: MtfLegStatus = MtfLegStatus.UNKNOWN,
    val biasDetail: String = "",
    val setupOk: Boolean = false,
    val setupStatus: MtfLegStatus = MtfLegStatus.UNKNOWN,
    val setupDetail: String = "",
    val triggerOk: Boolean = false,
    val triggerStatus: MtfLegStatus = MtfLegStatus.UNKNOWN,
    val triggerDetail: String = "",
    val entryPriceOk: Boolean = false,
    val entryPriceStatus: MtfLegStatus = MtfLegStatus.UNKNOWN,
    val entryPriceDetail: String = "",
    val path: ScalpingPath = ScalpingPath.NONE,
    val statusTitle: String = "BELUM TERSEDIA",
    val waitingFor: String = "",
    val entryCondition: String = "",
    val extended: Boolean = false,
    val extremeVolatility: Boolean = false,
    val checkpoints: List<ConfluenceCheckpoint> = emptyList(),
    val completedCount: Int = 0
) {
    fun resolvedCheckpoints(): List<ConfluenceCheckpoint> {
        if (checkpoints.isNotEmpty()) return checkpoints
        return listOf(
            ConfluenceCheckpoint(
                number = 1,
                code = "MTF",
                label = "Struktur MTF",
                isOk = biasOk,
                status = biasStatus,
                metricValue = if (biasOk) "Uptrend Selaras" else "Konsolidasi",
                detail = biasDetail.ifEmpty { "Pemeriksaan keselarasan tren timeframe makro dan mikro." }
            ),
            ConfluenceCheckpoint(
                number = 2,
                code = "AOV",
                label = "Area of Value",
                isOk = setupOk,
                status = setupStatus,
                metricValue = if (setupOk) "Level Kunci Teruji" else "No Man's Land",
                detail = setupDetail.ifEmpty { "Harga harus merespons Support, Retest, atau Reclaim level penting." }
            ),
            ConfluenceCheckpoint(
                number = 3,
                code = "VOL",
                label = "Volume Validasi",
                isOk = setupOk && triggerOk,
                status = if (setupOk && triggerOk) MtfLegStatus.OK else MtfLegStatus.WAITING,
                metricValue = if (setupOk && triggerOk) "Volume Terkonfirmasi" else "Volume Standar",
                detail = "Volume transaksi mengonfirmasi validitas (breakout bervolume atau pullback kering)."
            ),
            ConfluenceCheckpoint(
                number = 4,
                code = "TRG",
                label = "Price Action",
                isOk = triggerOk,
                status = triggerStatus,
                metricValue = if (triggerOk) "Candle Trigger Siap" else "Menunggu Trigger",
                detail = triggerDetail.ifEmpty { "Konfirmasi pola candlestick pembalikan atau penerusan arah." }
            ),
            ConfluenceCheckpoint(
                number = 5,
                code = "MOM",
                label = "Momentum / Div",
                isOk = triggerOk,
                status = if (triggerOk) MtfLegStatus.OK else MtfLegStatus.WAITING,
                metricValue = if (triggerOk) "Momentum Positif" else "Menunggu RSI/MACD",
                detail = "Filter osilator sehat dan deteksi potensi Bullish Divergence."
            ),
            ConfluenceCheckpoint(
                number = 6,
                code = "RR",
                label = "Risk / Reward",
                isOk = entryPriceOk,
                status = entryPriceStatus,
                metricValue = if (entryPriceOk) "Net R:R TP1 >= 1:2.0" else "Menunggu Setup R:R",
                detail = entryPriceDetail.ifEmpty {
                    "Net Risk to Reward TP1 minimal 1:2.0 setelah fee beli + fee jual + slippage dua sisi."
                }
            )
        )
    }

    val resolvedCompletedCount: Int
        get() = if (completedCount > 0) completedCount else resolvedCheckpoints().count { it.isOk }
}

enum class TrendSentiment(val displayName: String) {
    STRONG_BULLISH_CONTINUATION("Kelanjutan Bullish Kuat"),
    BULLISH_REVERSAL("Pembalikan Arah Bullish"),
    ACCUMULATION_SQUEEZE("Tunggu Pullback"),
    NEUTRAL_CONSOLIDATION("Konsolidasi Netral"),
    BEARISH_DISTRIBUTION("Distribusi Bearish"),
    BEARISH_BREAKDOWN("Breakdown Bearish"),
    EXTREME_OVERSOLD("Pantulan Jenuh Jual (Oversold)")
}

enum class AppScreen { DASHBOARD, SIMULATION_TRADE, LANDSCAPE_CHART, SETTINGS, LEARNING, PORTFOLIO, SIGNAL_LOGS }

data class WorthCoinInfo(val pair: TradingPair, val worthScore: Int, val isWorthIt: Boolean, val recommendation: String, val potentialProfitPct: Double, val aiRationale: String)
data class CoinHoldingStatus(
    val isHolding: Boolean = false,
    val quantity: Double = 0.0,
    val entryPrice: Double = 0.0,
    val isReal: Boolean = false,
    val tp1Price: Double = 0.0,
    val tp2Price: Double = 0.0,
    val stopLossPrice: Double = 0.0,
    val isTrailingEnabled: Boolean = false,
    val isTrailingTriggered: Boolean = false
)
data class MarketTick(val symbol: String, val price: Double, val high24h: Double, val low24h: Double, val volume24h: Double, val change24h: Double, val timestamp: Long = System.currentTimeMillis())
data class CandleBar(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val isClosed: Boolean = true
)

data class IndonesiaCpiData(
    val period: String,
    val yoyPercent: Double = Double.NaN,
    val mtmPercent: Double? = null,
    val ytdPercent: Double? = null,
    val coreYoyPercent: Double? = null,
    val cpiIndex: Double? = null,
    val inflationTargetCenterPercent: Double = 2.5,
    val inflationTargetBandPercent: Double = 1.0,
    val source: String = "BPS WebAPI",
    val sourceUrl: String = "https://webapi.bps.go.id/documentation",
    val fetchedAt: Long = System.currentTimeMillis()
)

data class TechnicalIndicators(
    val rsi14: Double = Double.NaN,
    val macd: Double = Double.NaN,
    val macdSignal: Double = Double.NaN,
    val macdHist: Double = Double.NaN,
    val ema20: Double = Double.NaN,
    val ema50: Double = Double.NaN,
    val ema200: Double = Double.NaN,
    val bbUpper: Double = Double.NaN,
    val bbLower: Double = Double.NaN,
    val atr: Double = Double.NaN,
    val momentum: Double = Double.NaN,
    /**
     * Basis bar untuk seluruh indikator di atas (P0-2).
     * Tanpa ini, angka RSI/MACD/RVOL tidak bisa direproduksi oleh pihak ketiga —
     * tidak diketahui apakah dihitung dari bar yang sudah tutup atau bar yang masih berjalan.
     */
    val barTimestamp: Long = 0L,
    val barClosed: Boolean = true,
    /** Relative Volume yang sudah dinormalisasi ke basis bar penuh (bukan volume sebagian menit). */
    val rvol: Double = Double.NaN,
    /** VWAP M1 pada saat evaluasi; `0.0` berarti tidak tersedia. */
    val vwap: Double = Double.NaN
)

data class AISignalState(
    val action: SignalAction = SignalAction.HOLD,
    val confidence: Int = 0,
    val sentiment: TrendSentiment = TrendSentiment.NEUTRAL_CONSOLIDATION,
    val entryPrice: Double = 0.0,
    val targetPrice1: Double = 0.0,
    val targetPrice2: Double = 0.0,
    val stopLoss: Double = 0.0,
    val riskRewardRatio: String = "Belum tersedia",
    val probabilityScore: Double = 0.0,
    val patternDetected: String? = null,
    val reasoning: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val marketSymbol: String = "",
    val scalpingStage: ScalpingStage = ScalpingStage.HOLD,
    val lifecycleState: LifecycleState = LifecycleState.IDLE,
    /** Structured MTF — diisi evaluator scalping. Default kosong untuk swing/offline. */
    val mtf: ScalpingMtfSnapshot = ScalpingMtfSnapshot(),
    /** Info mode offline & backtest walk-forward validation */
    val isOfflineMode: Boolean = false,
    val offlineSnapshotTime: Long = 0L,
    val offlineReason: String = "",
    val backtestWinRatePct: Double = 0.0,
    val backtestScore: Int = 0,
    val walkForwardEfficiencyPct: Double = 0.0,
    val regimeDetected: String = "",
    /** Output pipeline scalping KriptoYoi (kosong untuk swing/intraday/offline). */
    val scalpingSetup: String = "",
    val scalpingScore: Int = 0,
    val scalpingScoreCategory: String = "",
    /** Rincian skor, mis. "Struktur 18/25 · MTF 10/15 · ...". */
    val scalpingScoreDetail: String = "",
    val scalpingRegime: String = "",
    val scalpingDirection: String = "",
    /** Zona entry dari ScalpingRiskEngine. [entryPrice] tetap harga saat sinyal. */
    val entryZoneLow: Double = 0.0,
    val entryZoneHigh: Double = 0.0
)

data class TradingPair(
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    val displayName: String,
    val initialPrice: Double = 0.0,
    val iconUrl: String = "",
    val altPair: String = "",
    val tokocryptoPair: String = ""
) {
    companion object {
// 1. Daftar pair populer Tokocrypto (parameter tokocryptoPair)
val POPULAR_TOKOCRYPTO_PAIRS = listOf(
    // ==================== USDT PAIRS ====================
    TradingPair("BTCUSDT", "BTC", "USDT", "Bitcoin / USDT", tokocryptoPair = "BTC_USDT"),
    TradingPair("ETHUSDT", "ETH", "USDT", "Ethereum / USDT", tokocryptoPair = "ETH_USDT"),
    TradingPair("BNBUSDT", "BNB", "USDT", "BNB / USDT", tokocryptoPair = "BNB_USDT"),
    TradingPair("SOLUSDT", "SOL", "USDT", "Solana / USDT", tokocryptoPair = "SOL_USDT"),
    TradingPair("XRPUSDT", "XRP", "USDT", "XRP / USDT", tokocryptoPair = "XRP_USDT"),
    TradingPair("DOGEUSDT", "DOGE", "USDT", "Dogecoin / USDT", tokocryptoPair = "DOGE_USDT"),
    TradingPair("PEPEUSDT", "PEPE", "USDT", "Pepe / USDT", tokocryptoPair = "PEPE_USDT"),
    TradingPair("SHIBUSDT", "SHIB", "USDT", "Shiba Inu / USDT", tokocryptoPair = "SHIB_USDT"),
    TradingPair("TONUSDT", "TON", "USDT", "Toncoin / USDT", tokocryptoPair = "TON_USDT"),
    TradingPair("ADAUSDT", "ADA", "USDT", "Cardano / USDT", tokocryptoPair = "ADA_USDT"),
    TradingPair("AVAXUSDT", "AVAX", "USDT", "Avalanche / USDT", tokocryptoPair = "AVAX_USDT"),
    TradingPair("NEARUSDT", "NEAR", "USDT", "NEAR / USDT", tokocryptoPair = "NEAR_USDT"),
    TradingPair("SUIUSDT", "SUI", "USDT", "Sui / USDT", tokocryptoPair = "SUI_USDT"),
    TradingPair("LINKUSDT", "LINK", "USDT", "Chainlink / USDT", tokocryptoPair = "LINK_USDT"),
    TradingPair("DOTUSDT", "DOT", "USDT", "Polkadot / USDT", tokocryptoPair = "DOT_USDT"),
    TradingPair("LTCUSDT", "LTC", "USDT", "Litecoin / USDT", tokocryptoPair = "LTC_USDT"),
    TradingPair("WLDUSDT", "WLD", "USDT", "Worldcoin / USDT", tokocryptoPair = "WLD_USDT"),
    TradingPair("ONDOUSDT", "ONDO", "USDT", "Ondo / USDT", tokocryptoPair = "ONDO_USDT"),

    // ==================== IDR PAIRS ====================
    TradingPair("BTCIDR", "BTC", "IDR", "Bitcoin / IDR", tokocryptoPair = "BTC_IDR"),
    TradingPair("ETHIDR", "ETH", "IDR", "Ethereum / IDR", tokocryptoPair = "ETH_IDR"),
    TradingPair("BNBIDR", "BNB", "IDR", "BNB / IDR", tokocryptoPair = "BNB_IDR"),
    TradingPair("SOLIDR", "SOL", "IDR", "Solana / IDR", tokocryptoPair = "SOL_IDR"),
    TradingPair("XRPIDR", "XRP", "IDR", "XRP / IDR", tokocryptoPair = "XRP_IDR"),
    TradingPair("DOGEIDR", "DOGE", "IDR", "Dogecoin / IDR", tokocryptoPair = "DOGE_IDR")
)

// 2. Daftar pair berkuotasi IDR (juga diperdagangkan di Tokocrypto)
val POPULAR_IDR_PAIRS = listOf(
    TradingPair("BTCIDR", "BTC", "IDR", "Bitcoin / IDR", altPair = "btc_idr"),
    TradingPair("ETHIDR", "ETH", "IDR", "Ethereum / IDR", altPair = "eth_idr"),
    TradingPair("SOLIDR", "SOL", "IDR", "Solana / IDR", altPair = "sol_idr"),
    TradingPair("BNBIDR", "BNB", "IDR", "BNB / IDR", altPair = "bnb_idr"),
    TradingPair("XRPIDR", "XRP", "IDR", "XRP / IDR", altPair = "xrp_idr"),
    TradingPair("DOGEIDR", "DOGE", "IDR", "Dogecoin / IDR", altPair = "doge_idr"),
    TradingPair("PEPEIDR", "PEPE", "IDR", "Pepe / IDR", altPair = "pepe_idr"),
    TradingPair("ADAIDR", "ADA", "IDR", "Cardano / IDR", altPair = "ada_idr"),
    TradingPair("AVAXIDR", "AVAX", "IDR", "Avalanche / IDR", altPair = "avax_idr"),
    TradingPair("SHIBIDR", "SHIB", "IDR", "Shiba Inu / IDR", altPair = "shib_idr"),
    TradingPair("NEARIDR", "NEAR", "IDR", "NEAR / IDR", altPair = "near_idr"),
    TradingPair("SUIIDR", "SUI", "IDR", "Sui / IDR", altPair = "sui_idr"),
    TradingPair("DOTIDR", "DOT", "IDR", "Polkadot / IDR", altPair = "dot_idr"),
    TradingPair("LTCIDR", "LTC", "IDR", "Litecoin / IDR", altPair = "ltc_idr"),
    TradingPair("LINKIDR", "LINK", "IDR", "Chainlink / IDR", altPair = "link_idr"),
    TradingPair("MYXIDR", "MYX", "IDR", "MYX Finance / IDR", altPair = "myx_idr")
)

        val POPULAR_PAIRS = POPULAR_TOKOCRYPTO_PAIRS

        fun popularPairsForSource(source: agu.analys.config.MarketDataSource? = null): List<TradingPair> {
            val dynamic = agu.analys.data.TokocryptoSymbolRepository.getAllTradingPairs()
            return if (dynamic.isNotEmpty()) dynamic else POPULAR_TOKOCRYPTO_PAIRS + POPULAR_IDR_PAIRS
        }

        fun fromCustomSymbol(
            raw: String,
            defaultQuote: String = "IDR",
            exchange: String? = null
        ): TradingPair {
            val cleaned = PairRules.normalizeBidr(raw)
            val (base, quote) = when {
                cleaned.endsWith("USDT") -> cleaned.removeSuffix("USDT") to "USDT"
                cleaned.endsWith("USDC") -> cleaned.removeSuffix("USDC") to "USDC"
                cleaned.endsWith("BUSD") -> cleaned.removeSuffix("BUSD") to "BUSD"
                cleaned.endsWith("BIDR") -> cleaned.removeSuffix("BIDR") to "BIDR"
                cleaned.endsWith("IDR") -> cleaned.removeSuffix("IDR") to "IDR"
                cleaned.endsWith("IDRT") -> cleaned.removeSuffix("IDRT") to "IDRT"
                cleaned.endsWith("USD") -> cleaned.removeSuffix("USD") to "USDT"
                else -> cleaned to defaultQuote
            }
            val finalBase = base.ifEmpty { "BTC" }
            val finalQuote = quote.ifEmpty { defaultQuote }
            val symbol = "$finalBase$finalQuote"

            // 1. Coba cari di Dynamic Tokocrypto Repository
            val dynamicInfo = agu.analys.data.TokocryptoSymbolRepository.getSymbolInfo(symbol)
                ?: agu.analys.data.TokocryptoSymbolRepository.getSymbolInfo("${finalBase}_$finalQuote")
            if (dynamicInfo != null) {
                return dynamicInfo.toTradingPair()
            }

            // 2. Cocokkan pair populer, termasuk pair IDR yang juga diperdagangkan Tokocrypto
            val allPopular = POPULAR_TOKOCRYPTO_PAIRS + POPULAR_IDR_PAIRS
            val known = allPopular.find { it.symbol.equals(symbol, ignoreCase = true) || (it.baseAsset.equals(finalBase, ignoreCase = true) && it.quoteAsset.equals(finalQuote, ignoreCase = true)) }
            if (known != null) return known
            return TradingPair(
                symbol = symbol,
                baseAsset = finalBase,
                quoteAsset = finalQuote,
                displayName = "$finalBase / $finalQuote",
                altPair = "${finalBase.lowercase()}_${finalQuote.lowercase()}",
                tokocryptoPair = "${finalBase.uppercase()}_${finalQuote.uppercase()}"
            )
        }
    }

    fun effectiveAltPair(): String = if (altPair.isNotBlank()) altPair else "${baseAsset.lowercase()}_idr"
    fun effectiveTokocryptoPair(): String {
        return if (tokocryptoPair.isNotBlank()) {
            tokocryptoPair.replace("_BIDR", "_IDR").replace("BIDR_", "IDR_")
        } else {
            val q = quoteAsset.uppercase().replace("BIDR", "IDR")
            "${baseAsset.uppercase()}_$q"
        }
    }
    fun effectiveCompactSymbol(): String = effectiveTokocryptoPair().replace("_", "").uppercase()
}

enum class Timeframe(val code: String, val label: String) {
    M1("1", "1m"), M5("5", "5m"), M15("15", "15m"), H1("60", "1h"), H4("240", "4h"), D1("D", "1d")
}

enum class ChartStyle(val label: String) { CANDLES("Candlesticks"), LINE("Line"), AREA("Area") }
data class OrderBookItem(val price: Double, val amount: Double, val total: Double, val isBid: Boolean)
data class TradeStreamItem(val id: String, val price: Double, val amount: Double, val timeFormatted: String, val isBuy: Boolean)

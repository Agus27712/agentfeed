package agu.analys.bridge

import kotlinx.serialization.Serializable

/**
 * Model data pasar yang diterbitkan server `nadi agentfeed` ke agent OMP (2026-11-08).
 *
 * Tujuan: agent **tidak perlu lagi** memanggil proxy harga pihak ketiga. Semua angka
 * di sini berasal dari `TokocryptoMarketService` / `TokocryptoSymbolRepository` /
 * `FeeSchedule` yang sama dengan yang dipakai chart aplikasi, sehingga keputusan
 * trading agent dibuat di atas data yang identik dengan yang dilihat pengguna.
 *
 * Aturan yang tidak boleh dilanggar:
 * - Field `available=false` + `error` berarti data gagal diambil. **Jangan** mengisi
 *   angka nol seolah itu harga/volume nyata; agen wajib membaca `available` dulu.
 * - `source` selalu menyebut asal data. Tidak ada angka hasil tebakan atau sintetis.
 * - Semua DTO murni `kotlinx.serialization`, tanpa kelas Android, supaya proyek
 *   smoke JVM di `tools/bridge-smoke` bisa meng-compile sumber produksi apa adanya.
 */

@Serializable
data class AgentTickerSnapshot(
    val symbol: String,
    val available: Boolean,
    val source: String = "TOKOCRYPTO_REST",
    val price: Double = 0.0,
    val high24h: Double = 0.0,
    val low24h: Double = 0.0,
    val volume24h: Double = 0.0,
    val change24h: Double = 0.0,
    val ts: Long = System.currentTimeMillis(),
    val error: String? = null
)

@Serializable
data class AgentCandle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    /** `false` = bar masih berjalan (REST memberi candle yang belum tutup). */
    val closed: Boolean = true
)

@Serializable
data class AgentKlinesSnapshot(
    val symbol: String,
    val interval: String,
    val limit: Int,
    val available: Boolean,
    val source: String = "TOKOCRYPTO_REST",
    val candles: List<AgentCandle> = emptyList(),
    val ts: Long = System.currentTimeMillis(),
    val error: String? = null
)

@Serializable
data class AgentDepthLevel(
    val price: Double,
    val amount: Double,
    val total: Double
)

@Serializable
data class AgentDepthSnapshot(
    val symbol: String,
    val available: Boolean,
    val bids: List<AgentDepthLevel> = emptyList(),
    val asks: List<AgentDepthLevel> = emptyList(),
    val bidVolume: Double = 0.0,
    val askVolume: Double = 0.0,
    /** Selisih harga ask terendah - bid tertinggi; 0 bila salah satu sisi kosong. */
    val spread: Double = 0.0,
    /** (bidVolume - askVolume) / (bidVolume + askVolume); 0 bila kedua sisi kosong. */
    val imbalance: Double = 0.0,
    val ts: Long = System.currentTimeMillis(),
    val error: String? = null
)

/**
 * Aturan eksekusi satu simbol. Agen **wajib** memakai `tickSize`/`stepSize`/
 * `minQty`/`minNotional` sebelum mengirim order, kalau tidak order akan ditolak bursa.
 * Nilai 0.0 berarti "tidak tersedia", bukan "tidak ada batas".
 */
@Serializable
data class AgentSymbolRules(
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    val symbolType: Int = 1,
    val spotTradingEnabled: Boolean = true,
    val tickSize: Double = 0.0,
    val minPrice: Double = 0.0,
    val maxPrice: Double = 0.0,
    val minQty: Double = 0.0,
    val maxQty: Double = 0.0,
    val stepSize: Double = 0.0,
    val minNotional: Double = 0.0,
    val basePrecision: Int = 8,
    val quotePrecision: Int = 8
)

@Serializable
data class AgentSymbolsSnapshot(
    val available: Boolean,
    val count: Int = 0,
    val quoteFilter: String? = null,
    val symbols: List<AgentSymbolRules> = emptyList(),
    val ts: Long = System.currentTimeMillis(),
    val error: String? = null
)

/** Tarif dalam **persen per sisi**, mirror langsung `FeeSchedule`. */
@Serializable
data class AgentFeeSnapshot(
    val quote: String,
    val buyMakerPct: Double,
    val buyTakerPct: Double,
    val sellMakerPct: Double,
    val sellTakerPct: Double,
    val slippagePctPerSide: Double,
    val roundTripPctMultiplicative: Double
)

@Serializable
data class AgentServerTime(
    val serverTime: Long,
    val deviceTime: Long,
    val offsetMs: Long,
    val source: String = "TOKOCRYPTO"
)

/** Manifest mesin: dipakai agent untuk discovery tanpa membaca dokumen Markdown. */
@Serializable
data class AgentCapabilities(
    val service: String = "agentfeed-bridge-v3",
    val apiVersion: String = "2026-11-08",
    val app: String = "nadi agentfeed",
    val exchange: String = "TOKOCRYPTO",
    val quoteAssets: List<String> = listOf("USDT", "IDR"),
    val intervals: List<String> = listOf("1m", "5m", "15m", "1h", "4h", "1d"),
    val minKlineLimit: Int = 10,
    val maxKlineLimit: Int = 1000,
    val maxDepthLimit: Int = 100,
    val readEndpoints: List<String> = emptyList(),
    val writeEndpoints: List<String> = emptyList(),
    val pushEndpoints: List<String> = emptyList(),
    val authHeader: String = "X-Agent-Token",
    val authScope: String = "Semua rute /api/v1/trading/* menuntut header ini (fail-closed).",
    val dataPolicy: String = "Angka bersumber dari REST resmi Tokocrypto; bila gagal, available=false + error. Tidak ada angka sintetis.",
    val serverTime: Long = System.currentTimeMillis()
)

@Serializable
data class AgentCheckpoint(
    val code: String,
    val label: String,
    val isOk: Boolean = false,
    val metricValue: String = "",
    val detail: String = ""
)

@Serializable
data class AgentScalpingAnalysis(
    val action: String = "HOLD",
    val stage: String = "HOLD",
    val confidence: Int = 0,
    val setup: String = "",
    val score: Int = 0,
    val scoreCategory: String = "",
    val regime: String = "",
    val entryPrice: Double = 0.0,
    val entryZoneLow: Double = 0.0,
    val entryZoneHigh: Double = 0.0,
    val tp1: Double = 0.0,
    val tp2: Double = 0.0,
    val sl: Double = 0.0,
    val riskReward: String = "",
    val checkpoints: List<AgentCheckpoint> = emptyList(),
    val reasoning: List<String> = emptyList()
)

@Serializable
data class AgentIntradayAnalysis(
    val action: String = "HOLD",
    val confidence: Int = 0,
    val setup: String = "",
    val session: String = "",
    val targetPrice: Double = 0.0,
    val stopLoss: Double = 0.0,
    val riskReward: String = "",
    val reasoning: List<String> = emptyList()
)

@Serializable
data class AgentSwingAnalysis(
    val action: String = "HOLD",
    val confidence: Int = 0,
    val trend: String = "",
    val regime: String = "",
    val support: Double = 0.0,
    val resistance: Double = 0.0,
    val targetPrice: Double = 0.0,
    val stopLoss: Double = 0.0,
    val reasoning: List<String> = emptyList()
)

@Serializable
data class AgentTechnicalIndicators(
    val rsi14: Double? = null,
    val macd: Double? = null,
    val macdSignal: Double? = null,
    val macdHist: Double? = null,
    val ema20: Double? = null,
    val ema50: Double? = null,
    val ema200: Double? = null,
    val bbUpper: Double? = null,
    val bbLower: Double? = null,
    val atr: Double? = null,
    val rvol: Double? = null,
    val vwap: Double? = null
)

@Serializable
data class AgentEngineAnalysis(
    val symbol: String,
    val available: Boolean,
    val source: String = "ENGINE_EVALUATOR",
    val price: Double = 0.0,
    val scalping: AgentScalpingAnalysis? = null,
    val intraday: AgentIntradayAnalysis? = null,
    val swing: AgentSwingAnalysis? = null,
    val indicators: AgentTechnicalIndicators? = null,
    val overallBias: String = "NEUTRAL",
    val ts: Long = System.currentTimeMillis(),
    val error: String? = null
)

/**
 * Satu panggilan untuk **seluruh** konteks yang dibutuhkan agent sebelum memutuskan
 * trading: harga, candle, kedalaman order book, aturan simbol, biaya, sinyal terakhir,
 * portofolio, analisis engine multi-mode (scalp, intraday, swing), dan status kesiapan order.
 */
@Serializable
data class AgentTradingContext(
    val symbol: String,
    val ts: Long = System.currentTimeMillis(),
    val exchange: String = "TOKOCRYPTO",
    val ticker: AgentTickerSnapshot? = null,
    val klines: AgentKlinesSnapshot? = null,
    val depth: AgentDepthSnapshot? = null,
    val rules: AgentSymbolRules? = null,
    val fees: AgentFeeSnapshot? = null,
    val latestSignal: AgentSignalOutput? = null,
    val portfolio: AgentPortfolioOutput? = null,
    val tradingStatus: AgentTradingStatusResponse? = null,
    val engineAnalysis: AgentEngineAnalysis? = null,
    val notes: List<String> = emptyList(),
    val type: String = "market_context"
)

@Serializable
data class AgentOpenOrder(
    val orderId: String,
    val clientOrderId: String? = null,
    val symbol: String,
    val side: String = "",
    val price: Double = 0.0,
    val origQty: Double = 0.0,
    val executedQty: Double = 0.0,
    val status: String = "UNKNOWN",
    val isSimulation: Boolean = false,
    val createdAt: Long = 0L
)

@Serializable
data class AgentOpenOrdersResponse(
    val success: Boolean,
    val message: String = "",
    val exchange: String = "TOKOCRYPTO",
    val isSimulation: Boolean = false,
    val symbolFilter: String? = null,
    val count: Int = 0,
    val orders: List<AgentOpenOrder> = emptyList(),
    val serverTime: Long = System.currentTimeMillis()
)

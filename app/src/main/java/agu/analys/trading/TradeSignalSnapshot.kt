package agu.analys.trading

import agu.analys.model.AISignalState
import agu.analys.model.MarketTick
import agu.analys.model.OrderBookItem
import agu.analys.model.TechnicalIndicators
import agu.analys.data.OrderBookDepthCache
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Snapshot komprehensif metrik teknikal dan sinyal saat eksekusi order (Beli / Jual).
 * Dipisahkan per-kategori agar mudah dibaca di UI dan diverifikasi oleh LLM (Gemini, Claude, Grok, ChatGPT).
 */
data class TradeSignalSnapshot(
    val strategyMode: String = "SCALPING",
    // 1. Kategori Order Book & Likuiditas
    val bidRatioPct: Double? = null,
    val askRatioPct: Double? = null,
    val orderBookPressure: Int? = null,
    val spreadPct: Double? = null,
    val volume24h: Double? = null,
    val priceChange24h: Double? = null,

    // 2. Kategori Momentum & Osilator
    val rsi14: Double? = null,
    val macd: Double? = null,
    val macdSignal: Double? = null,
    val macdHist: Double? = null,
    val momentum: Double? = null,

    // 2b. Basis & volume (P0-1/P0-2) — agar angka di atas dapat direproduksi dari candle bursa
    /** Relative Volume pada basis bar penuh (bukan volume sebagian menit). */
    val rvol: Double? = null,
    /** VWAP M1 saat evaluasi; `null` berarti tidak tersedia. */
    val vwap: Double? = null,
    /** Timestamp (open time) bar M1 yang dipakai menghitung indikator di atas. */
    val indicatorBarTime: Long? = null,
    /** `true` = indikator dihitung dari bar yang sudah tutup; `false` = bar masih berjalan. */
    val indicatorBarClosed: Boolean? = null,

    // 3. Kategori Tren & Rata-rata Bergerak
    val ema20: Double? = null,
    val ema50: Double? = null,
    val ema200: Double? = null,
    val trendStatus: String? = null,

    // 4. Kategori Volatilitas & Bollinger Bands
    val bbUpper: Double? = null,
    val bbMiddle: Double? = null,
    val bbLower: Double? = null,
    val bbWidthPct: Double? = null,
    val atr: Double? = null,

    // 5. Kategori Keputusan AI Evaluator & Market Regime
    val confidenceScore: Int? = null,
    val marketRegime: String? = null,
    val patternDetected: String? = null,
    val reasons: List<String> = emptyList(),

    // 6. Pipeline scalping KriptoYoi (setup, skor, regime engine, zona entry)
    val scalpingSetup: String? = null,
    val scalpingScore: Int? = null,
    val scalpingScoreCategory: String? = null,
    val scalpingScoreDetail: String? = null,
    val scalpingRegime: String? = null,
    val entryZoneLow: Double? = null,
    val entryZoneHigh: Double? = null,
    val scalpingThresholds: String? = null
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("strategyMode", strategyMode)

        // Category 1
        bidRatioPct?.let { obj.put("bidRatioPct", it) }
        askRatioPct?.let { obj.put("askRatioPct", it) }
        orderBookPressure?.let { obj.put("orderBookPressure", it) }
        spreadPct?.let { obj.put("spreadPct", it) }
        volume24h?.let { obj.put("volume24h", it) }
        priceChange24h?.let { obj.put("priceChange24h", it) }

        // Category 2
        rsi14?.let { obj.put("rsi14", it) }
        macd?.let { obj.put("macd", it) }
        macdSignal?.let { obj.put("macdSignal", it) }
        macdHist?.let { obj.put("macdHist", it) }
        momentum?.let { obj.put("momentum", it) }

        // Category 2b — basis & volume
        rvol?.let { obj.put("rvol", it) }
        vwap?.let { obj.put("vwap", it) }
        indicatorBarTime?.let { obj.put("indicatorBarTime", it) }
        indicatorBarClosed?.let { obj.put("indicatorBarClosed", it) }

        // Category 3
        ema20?.let { obj.put("ema20", it) }
        ema50?.let { obj.put("ema50", it) }
        ema200?.let { obj.put("ema200", it) }
        trendStatus?.let { obj.put("trendStatus", it) }

        // Category 4
        bbUpper?.let { obj.put("bbUpper", it) }
        bbMiddle?.let { obj.put("bbMiddle", it) }
        bbLower?.let { obj.put("bbLower", it) }
        bbWidthPct?.let { obj.put("bbWidthPct", it) }
        atr?.let { obj.put("atr", it) }

        // Category 5
        confidenceScore?.let { obj.put("confidenceScore", it) }
        marketRegime?.let { obj.put("marketRegime", it) }
        patternDetected?.let { obj.put("patternDetected", it) }

        // Category 6
        scalpingSetup?.let { obj.put("scalpingSetup", it) }
        scalpingScore?.let { obj.put("scalpingScore", it) }
        scalpingScoreCategory?.let { obj.put("scalpingScoreCategory", it) }
        scalpingScoreDetail?.let { obj.put("scalpingScoreDetail", it) }
        scalpingRegime?.let { obj.put("scalpingRegime", it) }
        entryZoneLow?.let { obj.put("entryZoneLow", it) }
        entryZoneHigh?.let { obj.put("entryZoneHigh", it) }
        scalpingThresholds?.let { obj.put("scalpingThresholds", it) }

        if (reasons.isNotEmpty()) {
            val arr = JSONArray()
            reasons.forEach { arr.put(it) }
            obj.put("reasons", arr)
        }

        return obj
    }

    companion object {
        fun fromJson(json: JSONObject?): TradeSignalSnapshot? {
            if (json == null) return null
            val reasonsList = mutableListOf<String>()
            val rArr = json.optJSONArray("reasons")
            if (rArr != null) {
                for (i in 0 until rArr.length()) {
                    reasonsList.add(rArr.optString(i))
                }
            }

            return TradeSignalSnapshot(
                strategyMode = json.optString("strategyMode", "SCALPING"),
                bidRatioPct = if (json.has("bidRatioPct")) json.optDouble("bidRatioPct") else null,
                askRatioPct = if (json.has("askRatioPct")) json.optDouble("askRatioPct") else null,
                orderBookPressure = if (json.has("orderBookPressure")) json.optInt("orderBookPressure") else null,
                spreadPct = if (json.has("spreadPct")) json.optDouble("spreadPct") else null,
                volume24h = if (json.has("volume24h")) json.optDouble("volume24h") else null,
                priceChange24h = if (json.has("priceChange24h")) json.optDouble("priceChange24h") else null,
                rsi14 = if (json.has("rsi14")) json.optDouble("rsi14") else null,
                macd = if (json.has("macd")) json.optDouble("macd") else null,
                macdSignal = if (json.has("macdSignal")) json.optDouble("macdSignal") else null,
                macdHist = if (json.has("macdHist")) json.optDouble("macdHist") else null,
                momentum = if (json.has("momentum")) json.optDouble("momentum") else null,
                rvol = if (json.has("rvol")) json.optDouble("rvol") else null,
                vwap = if (json.has("vwap")) json.optDouble("vwap") else null,
                indicatorBarTime = if (json.has("indicatorBarTime")) json.optLong("indicatorBarTime") else null,
                indicatorBarClosed = if (json.has("indicatorBarClosed")) json.optBoolean("indicatorBarClosed") else null,
                ema20 = if (json.has("ema20")) json.optDouble("ema20") else null,
                ema50 = if (json.has("ema50")) json.optDouble("ema50") else null,
                ema200 = if (json.has("ema200")) json.optDouble("ema200") else null,
                trendStatus = json.optString("trendStatus").takeIf { it.isNotBlank() },
                bbUpper = if (json.has("bbUpper")) json.optDouble("bbUpper") else null,
                bbMiddle = if (json.has("bbMiddle")) json.optDouble("bbMiddle") else null,
                bbLower = if (json.has("bbLower")) json.optDouble("bbLower") else null,
                bbWidthPct = if (json.has("bbWidthPct")) json.optDouble("bbWidthPct") else null,
                atr = if (json.has("atr")) json.optDouble("atr") else null,
                confidenceScore = if (json.has("confidenceScore")) json.optInt("confidenceScore") else null,
                marketRegime = json.optString("marketRegime").takeIf { it.isNotBlank() },
                patternDetected = json.optString("patternDetected").takeIf { it.isNotBlank() },
                reasons = reasonsList,
                scalpingSetup = json.optString("scalpingSetup").takeIf { it.isNotBlank() },
                scalpingScore = if (json.has("scalpingScore")) json.optInt("scalpingScore") else null,
                scalpingScoreCategory = json.optString("scalpingScoreCategory").takeIf { it.isNotBlank() },
                scalpingScoreDetail = json.optString("scalpingScoreDetail").takeIf { it.isNotBlank() },
                scalpingRegime = json.optString("scalpingRegime").takeIf { it.isNotBlank() },
                entryZoneLow = if (json.has("entryZoneLow")) json.optDouble("entryZoneLow") else null,
                entryZoneHigh = if (json.has("entryZoneHigh")) json.optDouble("entryZoneHigh") else null,
                scalpingThresholds = json.optString("scalpingThresholds").takeIf { it.isNotBlank() }
            )
        }

        fun fromJsonString(str: String?): TradeSignalSnapshot? {
            if (str.isNullOrBlank()) return null
            return try {
                fromJson(JSONObject(str))
            } catch (_: Exception) {
                null
            }
        }

        fun capture(
            symbol: String,
            strategyMode: String,
            tick: MarketTick?,
            indicators: TechnicalIndicators?,
            signal: AISignalState?,
            bids: List<OrderBookItem> = emptyList(),
            asks: List<OrderBookItem> = emptyList(),
            exchange: String = "TOKOCRYPTO"
        ): TradeSignalSnapshot {
            // N10 — sinyal hanya boleh menempel pada simbol yang benar-benar dinilai engine.
            // Bila engine terakhir menilai pair lain (user baru saja ganti pair), seluruh field
            // turunan sinyal dibuang: confidence/TP/SL/setup scalping pair lain tidak boleh
            // tercatat sebagai milik pair ini.
            val attributedSignal = signal?.takeIf {
                SignalSymbolAttribution.matches(symbol, it.marketSymbol)
            }
            // Calculate Order Book Ratio
            val obPair = OrderBookDepthCache.getOrderBook(symbol, exchange)
            val effectiveBids = if (bids.isNotEmpty()) bids else (obPair?.first ?: emptyList())
            val effectiveAsks = if (asks.isNotEmpty()) asks else (obPair?.second ?: emptyList())

            val totalBidVol = effectiveBids.sumOf { it.amount }
            val totalAskVol = effectiveAsks.sumOf { it.amount }
            val totalVol = totalBidVol + totalAskVol

            val bidRatio = if (totalVol > 0.0) (totalBidVol / totalVol) * 100.0 else null
            val askRatio = if (totalVol > 0.0) (totalAskVol / totalVol) * 100.0 else null
            val obPressure = OrderBookDepthCache.calculatePressure(symbol, exchange)

            val spread = if (effectiveBids.isNotEmpty() && effectiveAsks.isNotEmpty()) {
                val bestBid = effectiveBids.first().price
                val bestAsk = effectiveAsks.first().price
                if (bestBid > 0.0) ((bestAsk - bestBid) / bestBid) * 100.0 else null
            } else null

            // Trend status calculation
            val curPrice = tick?.price ?: 0.0
            val ema20 = indicators?.ema20?.takeIf { !it.isNaN() && it > 0.0 }
            val ema50 = indicators?.ema50?.takeIf { !it.isNaN() && it > 0.0 }
            val ema200 = indicators?.ema200?.takeIf { !it.isNaN() && it > 0.0 }

            val trend = when {
                ema20 != null && ema50 != null && curPrice > ema20 && ema20 > ema50 -> "BULLISH_UPTREND"
                ema20 != null && ema50 != null && curPrice < ema20 && ema20 < ema50 -> "BEARISH_DOWNTREND"
                ema20 != null && curPrice >= ema20 -> "BULLISH_PULLBACK"
                ema20 != null && curPrice < ema20 -> "BEARISH_REJECTION"
                else -> "SIDEWAYS_CONSOLIDATION"
            }

            // Bollinger Bandwidth calculation
            val bbUpper = indicators?.bbUpper?.takeIf { !it.isNaN() && it > 0.0 }
            val bbLower = indicators?.bbLower?.takeIf { !it.isNaN() && it > 0.0 }
            // A6 — bbMiddle PALSU jika fallback ke ema20. Saat Bollinger tidak tersedia,
            // field harus null (jujur "tidak diketahui"), bukan menampilkan EMA20 dengan
            // label "Bollinger Middle" — pembaca audit akan mengira itu nilai BB asli.
            val bbMiddle = if (bbUpper != null && bbLower != null) (bbUpper + bbLower) / 2.0 else null
            val bbWidthPct = if (bbUpper != null && bbLower != null && bbMiddle != null && bbMiddle > 0.0) {
                ((bbUpper - bbLower) / bbMiddle) * 100.0
            } else null

            return TradeSignalSnapshot(
                strategyMode = strategyMode,
                bidRatioPct = bidRatio,
                askRatioPct = askRatio,
                orderBookPressure = obPressure,
                spreadPct = spread,
                volume24h = tick?.volume24h,
                priceChange24h = tick?.change24h,
                rsi14 = indicators?.rsi14?.takeIf { !it.isNaN() },
                macd = indicators?.macd?.takeIf { !it.isNaN() },
                macdSignal = indicators?.macdSignal?.takeIf { !it.isNaN() },
                macdHist = indicators?.macdHist?.takeIf { !it.isNaN() },
                momentum = indicators?.momentum?.takeIf { !it.isNaN() },
                rvol = indicators?.rvol?.takeIf { !it.isNaN() },
                vwap = indicators?.vwap?.takeIf { !it.isNaN() && it > 0.0 },
                indicatorBarTime = indicators?.barTimestamp?.takeIf { it > 0L },
                indicatorBarClosed = indicators?.barClosed,
                ema20 = ema20,
                ema50 = ema50,
                ema200 = ema200,
                trendStatus = trend,
                bbUpper = bbUpper,
                bbMiddle = bbMiddle,
                bbLower = bbLower,
                bbWidthPct = bbWidthPct,
                atr = indicators?.atr?.takeIf { !it.isNaN() },
                confidenceScore = attributedSignal?.confidence?.takeIf { it > 0 },
                marketRegime = attributedSignal?.regimeDetected?.takeIf { it.isNotBlank() } ?: "NORMAL_LIQUIDITY",
                patternDetected = attributedSignal?.patternDetected?.takeIf { it.isNotBlank() },
                reasons = attributedSignal?.reasoning?.ifEmpty { listOf("Technical signal match strategy mode $strategyMode") } ?: emptyList(),
                scalpingSetup = attributedSignal?.scalpingSetup?.takeIf { it.isNotBlank() },
                scalpingScore = attributedSignal?.scalpingScore?.takeIf { attributedSignal.scalpingSetup.isNotBlank() },
                scalpingScoreCategory = attributedSignal?.scalpingScoreCategory?.takeIf { it.isNotBlank() },
                scalpingScoreDetail = attributedSignal?.scalpingScoreDetail?.takeIf { it.isNotBlank() },
                scalpingRegime = attributedSignal?.scalpingRegime?.takeIf { it.isNotBlank() },
                entryZoneLow = attributedSignal?.entryZoneLow?.takeIf { it > 0.0 },
                entryZoneHigh = attributedSignal?.entryZoneHigh?.takeIf { it > 0.0 },
                scalpingThresholds = agu.analys.engine.scalping.ScalpingRuntimeConfig.current.toSummaryString()
            )
        }
    }
}

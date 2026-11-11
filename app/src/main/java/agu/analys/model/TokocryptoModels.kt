package agu.analys.model

/**
 * Model data resmi untuk integrasi API Tokocrypto.
 * Mendukung pembedaan Symbol Type 1 (MBX) vs Type 3 (NextMe),
 * Dynamic Symbol Discovery, Filter Trading (Lot Size, Price Filter, Notional),
 * dan Execution Rules.
 */

data class TokocryptoPriceFilter(
    val minPrice: Double = 0.0,
    val maxPrice: Double = Double.MAX_VALUE,
    val tickSize: Double = 0.0
)

data class TokocryptoLotSizeFilter(
    val minQty: Double = 0.0,
    val maxQty: Double = Double.MAX_VALUE,
    val stepSize: Double = 0.0
)

data class TokocryptoMinNotionalFilter(
    val minNotional: Double = 0.0,
    val applyToMarket: Boolean = true,
    val avgPriceMins: Int = 5
)

data class TokocryptoExecutionRules(
    val symbol: String,
    val bidLimitMultUp: Double = 1.1,
    val bidLimitMultDown: Double = 0.9,
    val askLimitMultUp: Double = 1.1,
    val askLimitMultDown: Double = 0.9
)

data class TokocryptoSymbolInfo(
    val symbol: String,                          // e.g. "BTCIDR", "BTCUSDT"
    val baseAsset: String,                      // "BTC"
    val quoteAsset: String,                     // "IDR", "USDT"
    val symbolType: Int = 1,                    // 1 = MBX, 3 = NextMe
    val basePrecision: Int = 8,
    val quotePrecision: Int = 8,
    val spotTradingEnable: Boolean = true,
    val defaultSelfTradePreventionMode: String = "NONE",
    val priceFilter: TokocryptoPriceFilter? = null,
    val lotSizeFilter: TokocryptoLotSizeFilter? = null,
    val marketLotSizeFilter: TokocryptoLotSizeFilter? = null,
    val minNotionalFilter: TokocryptoMinNotionalFilter? = null,
    var executionRules: TokocryptoExecutionRules? = null
) {
    /**
     * Konversi ke model TradingPair aplikasi
     */
    fun toTradingPair(): TradingPair {
        val cleanQuote = if (quoteAsset.equals("BIDR", true)) "IDR" else quoteAsset.uppercase()
        val cleanBase = baseAsset.uppercase().replace("BIDR", "IDR")
        val cleanSymbol = symbol.uppercase().replace("BIDR", "IDR").replace("_", "")
        return TradingPair(
            symbol = cleanSymbol,
            baseAsset = cleanBase,
            quoteAsset = cleanQuote,
            displayName = "$cleanBase / $cleanQuote",
            altPair = "${cleanBase.lowercase()}_${cleanQuote.lowercase()}",
            tokocryptoPair = "${cleanBase}_$cleanQuote"
        )
    }

    /**
     * Membulatkan harga sesuai tickSize
     */
    fun formatPrice(price: Double): Double {
        val tick = priceFilter?.tickSize ?: return price
        if (tick <= 0.0) return price
        val steps = Math.round(price / tick)
        return steps * tick
    }

    /**
     * Membulatkan quantity sesuai stepSize
     */
    fun formatQuantity(qty: Double): Double {
        val step = lotSizeFilter?.stepSize ?: return qty
        if (step <= 0.0) return qty
        val steps = Math.floor(qty / step)
        return steps * step
    }
}

enum class TokocryptoOrderType(val code: Int, val apiName: String) {
    LIMIT(1, "LIMIT"),
    MARKET(2, "MARKET"),
    STOP_LOSS(3, "STOP_LOSS"),
    STOP_LOSS_LIMIT(4, "STOP_LOSS_LIMIT"),
    TAKE_PROFIT(5, "TAKE_PROFIT"),
    TAKE_PROFIT_LIMIT(6, "TAKE_PROFIT_LIMIT"),
    LIMIT_MAKER(7, "LIMIT_MAKER")
}

enum class TokocryptoOrderSide(val code: Int, val apiName: String) {
    BUY(0, "BUY"),
    SELL(1, "SELL")
}

data class TokocryptoOrderRequest(
    val symbol: String,
    val side: TokocryptoOrderSide,
    val type: TokocryptoOrderType,
    val quantity: Double? = null,
    val quoteOrderQty: Double? = null,
    val price: Double? = null,
    val stopPrice: Double? = null,
    val timeInForce: String? = "GTC",
    val clientId: String? = null,
    val icebergQty: Double? = null,
    val recvWindow: Long = 10000L
)

data class TokocryptoOrderResult(
    val success: Boolean,
    val orderId: String = "",
    val clientId: String = "",
    val symbol: String = "",
    val status: String = "",
    val executedQty: Double = 0.0,
    val cumulativeQuoteQty: Double = 0.0,
    val avgPrice: Double = 0.0,
    val rawMessage: String = "",
    val errorMessage: String? = null,
    val httpCode: Int = 0,
    val serverCode: Int? = null,
    val serverBody: String = "",
    val requestDebug: String = ""
)

data class TokocryptoAssetBalance(
    val asset: String,
    val free: Double,
    val locked: Double,
    val total: Double = free + locked
)

data class TokocryptoValidationResult(
    val isValid: Boolean,
    val adjustedPrice: Double,
    val adjustedQty: Double,
    val reason: String = ""
)

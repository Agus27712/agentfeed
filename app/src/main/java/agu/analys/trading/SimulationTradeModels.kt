package agu.analys.trading

enum class SimulationOrderType(val displayName: String) {
    LIMIT("Limit Order"),
    MARKET("Market Order"),
    STOP_LIMIT("Stop Limit Order")
}

enum class SimulationOrderSide(val displayName: String) {
    BUY("Beli"),
    SELL("Jual")
}

enum class SimulationOrderStatus {
    OPEN,
    FILLED,
    CANCELLED
}

data class SimulationOrder(
    val id: String,
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String = "IDR",
    val side: SimulationOrderSide,
    val type: SimulationOrderType,
    val limitPrice: Double,
    val stopPrice: Double = 0.0,
    val quantity: Double,
    val totalIdr: Double,
    val filledQuantity: Double = 0.0,
    val filledAvgPrice: Double = 0.0,
    val feeIdr: Double = 0.0,
    val status: SimulationOrderStatus = SimulationOrderStatus.OPEN,
    val isStopTriggered: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val filledAt: Long? = null,
    val strategyMode: String = "SCALPING",
    val entryPrice: Double? = null,
    val entryTimestamp: Long? = null,
    val isTrailingUsed: Boolean = false,
    val trailingPercent: Double? = null,
    val trailingPeakPrice: Double? = null,
    val trailingLockPrice: Double? = null,
    val signalSnapshot: TradeSignalSnapshot? = null
)

data class SimulationTradeHistoryItem(
    val id: String,
    val orderId: String,
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String = "IDR",
    val side: SimulationOrderSide,
    val type: SimulationOrderType,
    val executionPrice: Double,
    val quantity: Double,
    val totalIdr: Double,
    val feeIdr: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val pnlIdr: Double? = null,
    val pnlPercent: Double? = null,
    val isRealMirror: Boolean = false,
    val strategyMode: String = "SCALPING",
    val holdingDurationMs: Long? = null,
    val entryPrice: Double? = null,
    val entryTimestamp: Long? = null,
    val isTrailingUsed: Boolean = false,
    val trailingPercent: Double? = null,
    val trailingPeakPrice: Double? = null,
    val trailingLockPrice: Double? = null,
    val signalSnapshot: TradeSignalSnapshot? = null
)

data class SimulationWallet(
    val idrBalance: Double = 10_000_000.0,
    val lockedIdr: Double = 0.0,
    /** Saldo kas USDT (terpisah mutlak dari saldo Rupiah). */
    val usdtBalance: Double = 0.0,
    /** Saldo USDT yang terkunci di antrean open order pair USDT. */
    val lockedUsdt: Double = 0.0,
    val coinBalances: Map<String, Double> = emptyMap(),
    val lockedCoinBalances: Map<String, Double> = emptyMap(),
    val avgBuyPrices: Map<String, Double> = emptyMap(),
    /**
     * Mata uang kuotasi yang dipakai saat posisi koin ini dibuka.
     * Menentukan saldo kas mana (IDR/USDT) yang dipakai saat koin tersebut dijual
     * dan bagaimana nilai asetnya diformat ($ vs Rp).
     */
    val coinQuoteAssets: Map<String, String> = emptyMap()
) {
    fun getAvailableIdr(): Double = (idrBalance - lockedIdr).coerceAtLeast(0.0)

    fun getAvailableUsdt(): Double = (usdtBalance - lockedUsdt).coerceAtLeast(0.0)

    /** Saldo kas yang tersedia untuk kuotasi tertentu (IDR / USDT / USD). */
    fun getAvailableQuote(quoteAsset: String): Double =
        if (agu.analys.util.PriceFormatter.isUsdtQuote(quoteAsset)) getAvailableUsdt() else getAvailableIdr()

    /** Total saldo kas (termasuk yang terkunci) untuk kuotasi tertentu. */
    fun getTotalQuote(quoteAsset: String): Double =
        if (agu.analys.util.PriceFormatter.isUsdtQuote(quoteAsset)) usdtBalance else idrBalance

    /** Saldo kas yang terkunci untuk kuotasi tertentu. */
    fun getLockedQuote(quoteAsset: String): Double =
        if (agu.analys.util.PriceFormatter.isUsdtQuote(quoteAsset)) lockedUsdt else lockedIdr

    /** Saldo kas yang tersedia untuk pair/simbol (tanpa harus tahu quote-nya). */
    fun getAvailableQuoteForSymbol(symbol: String): Double = getAvailableQuote(quoteOf(symbol))

    fun quoteForCoin(baseAsset: String): String =
        coinQuoteAssets[baseAsset.uppercase()]?.uppercase() ?: "IDR"

    fun isUsdtPosition(baseAsset: String): Boolean =
        agu.analys.util.PriceFormatter.isUsdtQuote(quoteForCoin(baseAsset))

    fun getAvailableCoin(baseAsset: String): Double {
        val key = baseAsset.uppercase()
        val total = coinBalances[key] ?: 0.0
        val locked = lockedCoinBalances[key] ?: 0.0
        return (total - locked).coerceAtLeast(0.0)
    }

    fun getTotalCoin(baseAsset: String): Double {
        val key = baseAsset.uppercase()
        return coinBalances[key] ?: 0.0
    }

    companion object {
        /** Tebak kuotasi dari nama simbol, mis. `BTCUSDT` -> `USDT`, `btc_idr` -> `IDR`. */
        fun quoteOf(symbol: String): String {
            val s = symbol.trim().uppercase().replace("_", "").replace("/", "").replace("-", "")
            return when {
                s.endsWith("USDT") -> "USDT"
                s.endsWith("USDC") -> "USDC"
                s.endsWith("BUSD") -> "BUSD"
                s.endsWith("BIDR") -> "IDR"
                s.endsWith("IDR") -> "IDR"
                s.endsWith("USD") -> "USD"
                else -> "IDR"
            }
        }
    }
}

sealed class SimulationOrderResult {
    data class Success(val order: SimulationOrder, val message: String) : SimulationOrderResult()
    data class Error(val message: String) : SimulationOrderResult()
}
package agu.analys.trading

import agu.analys.util.PriceFormatter
import java.util.UUID

/**
 * Mesin eksekusi order simulasi.
 *
 * Semua nominal diproses dalam **mata uang kuotasi pair** (`quote`), bukan selalu Rupiah:
 * - pair ber-kuotasi IDR  -> saldo kas Rupiah (`idrBalance` / `lockedIdr`)
 * - pair ber-kuotasi USDT -> saldo kas USDT (`usdtBalance` / `lockedUsdt`)
 *
 * Nama field `totalIdr` / `feeIdr` / `pnlIdr` dipertahankan demi kompatibilitas penyimpanan Room/JSON lama,
 * tetapi nilainya selalu dalam `quoteAsset` milik order yang bersangkutan.
 */
object SimulationOrderEngine {
    data class ExecutionResult(
        val updatedWallet: SimulationWallet,
        val historyItem: SimulationTradeHistoryItem,
        val completedOrder: SimulationOrder
    )

    fun isUsdt(quote: String): Boolean = PriceFormatter.isUsdtQuote(quote)

    private fun withQuoteBalance(wallet: SimulationWallet, quote: String, newValue: Double): SimulationWallet =
        if (isUsdt(quote)) wallet.copy(usdtBalance = newValue) else wallet.copy(idrBalance = newValue)

    /**
     * Eksekusi beli pasar.
     *
     * `feeRate` **wajib** diberikan eksplisit (pecahan, mis. `FeeSchedule.buyRate(...)`).
     * Sebelumnya parameter ini punya nilai default preset lama, sehingga order Tokocrypto
     * USDT memakai biaya 0,21% padahal seharusnya 0,4044% — dan biaya yang dicek di awal
     * berbeda dari yang benar-benar dipotong.
     */
    fun executeMarketBuy(
        wallet: SimulationWallet,
        symbol: String,
        baseKey: String,
        quote: String,
        execPrice: Double,
        quantity: Double,
        feeRate: Double
    ): Result<ExecutionResult> {
        val totalQuote = quantity * execPrice
        val feeQuote = totalQuote * feeRate
        val requiredQuote = totalQuote + feeQuote

        val availableQuote = wallet.getAvailableQuote(quote)
        if (availableQuote < requiredQuote) {
            return Result.failure(
                IllegalArgumentException(
                    "Saldo $quote tidak cukup. Dibutuhkan ${formatMoney(requiredQuote, quote)}, saldo ${formatMoney(availableQuote, quote)}."
                )
            )
        }

        val newCoinBalances = wallet.coinBalances.toMutableMap()
        val currentCoin = newCoinBalances[baseKey] ?: 0.0
        val prevQuote = wallet.quoteForCoin(baseKey)
        val currentAvg = if (prevQuote.equals(quote, true)) (wallet.avgBuyPrices[baseKey] ?: 0.0) else 0.0
        val newTotalCoin = currentCoin + quantity
        val newAvgPrice = if (newTotalCoin > 0.0) {
            ((currentCoin * currentAvg) + totalQuote) / newTotalCoin
        } else execPrice

        newCoinBalances[baseKey] = newTotalCoin
        val newAvgMap = wallet.avgBuyPrices.toMutableMap().apply { put(baseKey, newAvgPrice) }
        val newQuoteMap = wallet.coinQuoteAssets.toMutableMap().apply { put(baseKey, quote.uppercase()) }

        val updatedWallet = withQuoteBalance(
            wallet.copy(
                coinBalances = newCoinBalances,
                avgBuyPrices = newAvgMap,
                coinQuoteAssets = newQuoteMap
            ),
            quote,
            (wallet.getTotalQuote(quote) - requiredQuote).coerceAtLeast(0.0)
        )

        val history = SimulationTradeHistoryItem(
            id = UUID.randomUUID().toString(),
            orderId = UUID.randomUUID().toString(),
            symbol = symbol,
            baseAsset = baseKey,
            quoteAsset = quote,
            side = SimulationOrderSide.BUY,
            type = SimulationOrderType.MARKET,
            executionPrice = execPrice,
            quantity = quantity,
            totalIdr = totalQuote,
            feeIdr = feeQuote,
            timestamp = System.currentTimeMillis()
        )

        val order = SimulationOrder(
            id = history.orderId,
            symbol = symbol,
            baseAsset = baseKey,
            quoteAsset = quote,
            side = SimulationOrderSide.BUY,
            type = SimulationOrderType.MARKET,
            limitPrice = execPrice,
            quantity = quantity,
            totalIdr = totalQuote,
            filledQuantity = quantity,
            filledAvgPrice = execPrice,
            feeIdr = feeQuote,
            status = SimulationOrderStatus.FILLED,
            filledAt = System.currentTimeMillis()
        )

        return Result.success(ExecutionResult(updatedWallet, history, order))
    }

    /**
     * Eksekusi jual pasar.
     *
     * `feeRate` (biaya jual) **wajib** eksplisit. `buyFeeRate` adalah biaya yang dulu dibayar
     * saat posisi ini dibeli — dipakai agar PnL bersih benar-benar memperhitungkan **kedua sisi**.
     * Sebelumnya biaya beli tidak pernah masuk harga pokok, sehingga PnL dilaporkan lebih
     * optimistis sebesar biaya beli.
     */
    fun executeMarketSell(
        wallet: SimulationWallet,
        symbol: String,
        baseKey: String,
        quote: String,
        execPrice: Double,
        quantity: Double,
        feeRate: Double,
        buyFeeRate: Double = feeRate
    ): Result<ExecutionResult> {
        val available = wallet.getAvailableCoin(baseKey)
        // Toleransi absolut + relatif agar trailing/full-close tidak menyisakan dust
        val qtyDiff = quantity - available
        val actualQty = when {
            quantity <= 0.0 || available <= 0.0 -> 0.0
            qtyDiff <= 0.0 -> quantity
            qtyDiff < 0.0001 || (available > 0.0 && qtyDiff / available < 1e-4) -> available
            else -> quantity
        }

        if (available < actualQty || actualQty <= 0.0) {
            return Result.failure(
                IllegalArgumentException("Saldo $baseKey tidak cukup. Tersedia: ${wallet.getAvailableCoin(baseKey)}")
            )
        }

        val totalQuote = actualQty * execPrice
        val feeQuote = totalQuote * feeRate
        val netQuote = (totalQuote - feeQuote).coerceAtLeast(0.0)
        val prevQuote = wallet.quoteForCoin(baseKey)
        val avgBuy = if (prevQuote.equals(quote, true)) (wallet.avgBuyPrices[baseKey] ?: execPrice) else execPrice
        // Harga pokok mencakup biaya beli yang dulu dibayar, supaya PnL bersih = hasil jual −
        // (modal + biaya beli) − biaya jual. Tanpa ini PnL terlalu optimistis sebesar biaya beli.
        val costBasis = actualQty * avgBuy * (1.0 + buyFeeRate)
        val pnlQuote = totalQuote - costBasis - feeQuote
        val pnlPercent = if (costBasis > 0.0) (pnlQuote / costBasis) * 100.0 else 0.0

        val newCoinBalances = wallet.coinBalances.toMutableMap()
        val newAvgMap = wallet.avgBuyPrices.toMutableMap()
        val newQuoteMap = wallet.coinQuoteAssets.toMutableMap()
        val remaining = (newCoinBalances[baseKey] ?: 0.0) - actualQty
        // Full close jika sisa dust (absolut atau relatif)
        val isDustRemaining = remaining <= 0.00000001 ||
            ((newCoinBalances[baseKey] ?: 0.0) > 0.0 && remaining / (newCoinBalances[baseKey] ?: 1.0) < 1e-6)
        if (isDustRemaining) {
            newCoinBalances.remove(baseKey)
            newAvgMap.remove(baseKey)
            newQuoteMap.remove(baseKey)
        } else {
            newCoinBalances[baseKey] = remaining
        }

        val updatedWallet = withQuoteBalance(
            wallet.copy(
                coinBalances = newCoinBalances,
                avgBuyPrices = newAvgMap,
                coinQuoteAssets = newQuoteMap
            ),
            quote,
            wallet.getTotalQuote(quote) + netQuote
        )

        val history = SimulationTradeHistoryItem(
            id = UUID.randomUUID().toString(),
            orderId = UUID.randomUUID().toString(),
            symbol = symbol,
            baseAsset = baseKey,
            quoteAsset = quote,
            side = SimulationOrderSide.SELL,
            type = SimulationOrderType.MARKET,
            executionPrice = execPrice,
            quantity = actualQty,
            totalIdr = totalQuote,
            feeIdr = feeQuote,
            timestamp = System.currentTimeMillis(),
            pnlIdr = pnlQuote,
            pnlPercent = pnlPercent
        )

        val order = SimulationOrder(
            id = history.orderId,
            symbol = symbol,
            baseAsset = baseKey,
            quoteAsset = quote,
            side = SimulationOrderSide.SELL,
            type = SimulationOrderType.MARKET,
            limitPrice = execPrice,
            quantity = actualQty,
            totalIdr = totalQuote,
            filledQuantity = actualQty,
            filledAvgPrice = execPrice,
            feeIdr = feeQuote,
            status = SimulationOrderStatus.FILLED,
            filledAt = System.currentTimeMillis()
        )

        return Result.success(ExecutionResult(updatedWallet, history, order))
    }

    fun formatMoney(value: Double, quoteAsset: String): String {
        return PriceFormatter.formatPrice(value, showSymbol = true, quoteAsset = quoteAsset)
    }
}

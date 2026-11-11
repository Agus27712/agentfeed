package agu.analys.trading

import android.content.Context
import org.json.JSONArray
import java.util.UUID

class SimulationTradeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("simulation_trade_prefs", Context.MODE_PRIVATE)

    /**
     * S5 — asumsi slippage **per sisi** (%) untuk order MARKET simulasi.
     *
     * Sebelumnya laporan audit memakai PnL **fee-only** sementara mesin exit memakai
     * **fee + 2× slippage** — beda 0,16 pp per trade di Tokocrypto USDT (AMP: +0,6889% di
     * laporan vs +0,5289% versi engine). Dua angka untuk satu trade membuat log tidak bisa
     * diaudit. Slippage kini diterapkan ke **harga isi efektif** order MARKET, sehingga saldo
     * kas, PnL yang dilaporkan, dan basis mesin exit memakai satu angka yang sama.
     *
     * Order LIMIT sengaja **tidak** dikenai slippage: limit tidak "slip", ia hanya bisa gagal terisi.
     *
     * `0.0` = tanpa slippage (perilaku lama; nilai default untuk unit test).
     */
    var slippagePct: Double = 0.0

    companion object {
        /**
         * Tarif biaya simulasi diambil dari `agu.analys.config.FeeSchedule` — satu sumber
         * kebenaran untuk seluruh aplikasi.
         *
         * Konstanta lama (0,1% / 0,3%) dihapus karena bertentangan dengan `FEE_TRUTH.md` dan
         * membuat biaya yang dicek di awal berbeda dari yang benar-benar dipotong.
         */
        private fun buyRate(exchange: String, quote: String, useMaker: Boolean = false): Double =
            agu.analys.config.FeeSchedule.buyRate(exchange, quote, useMaker)

        private fun sellRate(exchange: String, quote: String, useMaker: Boolean = false): Double =
            agu.analys.config.FeeSchedule.sellRate(exchange, quote, useMaker)
    }

    private fun getWalletKey(exchange: String): String = "${exchange.trim().lowercase()}_sim_wallet"
    private fun getOrdersKey(exchange: String): String = "${exchange.trim().lowercase()}_sim_open_orders"
    private fun getHistoryKey(exchange: String): String = "${exchange.trim().lowercase()}_sim_trade_history"

    @Synchronized
    fun getWallet(exchange: String = "TOKOCRYPTO"): SimulationWallet {
        val exKey = getWalletKey(exchange)
        val raw = prefs.getString(exKey, null) ?: prefs.getString("sim_wallet", null)
        return SimulationTradeJson.walletFromJson(raw)
    }

    @Synchronized
    fun saveWallet(wallet: SimulationWallet, exchange: String = "TOKOCRYPTO") {
        val json = SimulationTradeJson.walletToJson(wallet)
        val exKey = getWalletKey(exchange)
        prefs.edit().putString(exKey, json.toString()).apply()
    }

    @Synchronized
    fun topUpIdr(amount: Double, exchange: String = "TOKOCRYPTO") {
        val w = getWallet(exchange)
        val updated = w.copy(idrBalance = w.idrBalance + amount.coerceAtLeast(0.0))
        saveWallet(updated, exchange)
    }

    @Synchronized
    fun setBalance(amount: Double, exchange: String = "TOKOCRYPTO") {
        val w = getWallet(exchange)
        val updated = w.copy(idrBalance = amount.coerceAtLeast(0.0))
        saveWallet(updated, exchange)
    }

    @Synchronized
    fun topUpUsdt(amount: Double, exchange: String = "TOKOCRYPTO") {
        val w = getWallet(exchange)
        val updated = w.copy(usdtBalance = w.usdtBalance + amount.coerceAtLeast(0.0))
        saveWallet(updated, exchange)
    }

    @Synchronized
    fun resetWallet(initialIdr: Double = 10_000_000.0, exchange: String = "TOKOCRYPTO") {
        saveWallet(SimulationWallet(idrBalance = initialIdr, usdtBalance = 0.0), exchange)
        val ordersKey = getOrdersKey(exchange)
        val historyKey = getHistoryKey(exchange)
        prefs.edit().remove(ordersKey).remove(historyKey).apply()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // KONVERSI SALDO (IDR <-> USDT) BERBASIS RATE EXCHANGE REAL-TIME
    // ═══════════════════════════════════════════════════════════════════════

    data class ConversionResult(
        val success: Boolean,
        val message: String,
        val rate: Double = 0.0,
        val fromAmount: Double = 0.0,
        val toAmount: Double = 0.0
    )

    /**
     * Konversi saldo Rupiah -> USDT memakai rate USDT/IDR live dari exchange.
     * Rate tidak pernah di-hardcode: bila exchange belum memberi data, konversi ditolak.
     */
    @Synchronized
    fun convertIdrToUsdt(amountIdr: Double, exchange: String = "TOKOCRYPTO"): ConversionResult {
        val rate = agu.analys.util.ExchangeRateManager.currentRate()
        if (rate <= 0.0) {
            return ConversionResult(false, "Rate USDT/IDR belum tersedia dari exchange. Coba lagi sebentar.")
        }
        val amount = amountIdr.coerceAtLeast(0.0)
        if (amount <= 0.0) return ConversionResult(false, "Nominal konversi harus lebih besar dari 0.")

        val w = getWallet(exchange)
        if (w.getAvailableIdr() < amount) {
            return ConversionResult(
                false,
                "Saldo Rupiah tidak cukup. Tersedia: ${formatMoney(w.getAvailableIdr(), "IDR")}"
            )
        }
        val usdtReceived = amount / rate
        saveWallet(
            w.copy(
                idrBalance = (w.idrBalance - amount).coerceAtLeast(0.0),
                usdtBalance = w.usdtBalance + usdtReceived
            ),
            exchange
        )
        agu.analys.util.AppLogManager.trade(
            "SimConvert",
            "💱 [KONVERSI] Rp ${formatMoney(amount, "IDR")} -> ${formatMoney(usdtReceived, "USDT")} (rate ${sourceLabel()})"
        )
        return ConversionResult(
            true,
            "✅ ${formatMoney(amount, "IDR")} → ${formatMoney(usdtReceived, "USDT")}  •  kurs ${sourceLabel()}/USDT",
            rate,
            amount,
            usdtReceived
        )
    }

    /** Konversi saldo USDT -> Rupiah memakai rate USDT/IDR live dari exchange. */
    @Synchronized
    fun convertUsdtToIdr(amountUsdt: Double, exchange: String = "TOKOCRYPTO"): ConversionResult {
        val rate = agu.analys.util.ExchangeRateManager.currentRate()
        if (rate <= 0.0) {
            return ConversionResult(false, "Rate USDT/IDR belum tersedia dari exchange. Coba lagi sebentar.")
        }
        val amount = amountUsdt.coerceAtLeast(0.0)
        if (amount <= 0.0) return ConversionResult(false, "Nominal konversi harus lebih besar dari 0.")

        val w = getWallet(exchange)
        if (w.getAvailableUsdt() < amount) {
            return ConversionResult(
                false,
                "Saldo USDT tidak cukup. Tersedia: ${formatMoney(w.getAvailableUsdt(), "USDT")}"
            )
        }
        val idrReceived = amount * rate
        saveWallet(
            w.copy(
                usdtBalance = (w.usdtBalance - amount).coerceAtLeast(0.0),
                idrBalance = w.idrBalance + idrReceived
            ),
            exchange
        )
        agu.analys.util.AppLogManager.trade(
            "SimConvert",
            "💱 [KONVERSI] ${formatMoney(amount, "USDT")} -> Rp ${formatMoney(idrReceived, "IDR")} (rate ${sourceLabel()})"
        )
        return ConversionResult(
            true,
            "✅ ${formatMoney(amount, "USDT")} → ${formatMoney(idrReceived, "IDR")}  •  kurs ${sourceLabel()}/USDT",
            rate,
            amount,
            idrReceived
        )
    }

    private fun sourceLabel(): String {
        val r = agu.analys.util.ExchangeRateManager.currentRate()
        return if (r > 0.0) agu.analys.util.PriceFormatter.formatPrice(r, quoteAsset = "IDR") else "-"
    }

    /**
     * Pastikan saldo kuotasi [quote] mencukupi [required].
     * Untuk pair USDT: bila saldo USDT kurang, saldo Rupiah dikonversi otomatis
     * pada rate exchange live (sesuai permintaan: order pair USDT memakai saldo IDR
     * yang dikonversi ke USDT).
     */
    @Synchronized
    private fun ensureQuoteBalance(
        wallet: SimulationWallet,
        quote: String,
        required: Double,
        exchange: String
    ): Pair<SimulationWallet, String?> {
        val available = wallet.getAvailableQuote(quote)
        if (available >= required) return wallet to null

        if (!agu.analys.util.PriceFormatter.isUsdtQuote(quote)) {
            return wallet to "Saldo $quote tidak cukup. Tersedia: ${formatMoney(available, quote)}"
        }

        val rate = agu.analys.util.ExchangeRateManager.currentRate()
        if (rate <= 0.0) {
            return wallet to "Saldo $quote tidak cukup dan rate USDT/IDR belum tersedia. Tersedia: ${formatMoney(available, quote)}"
        }

        val shortfallUsdt = required - available
        val neededIdr = shortfallUsdt * rate
        val availableIdr = wallet.getAvailableIdr()
        if (availableIdr < neededIdr) {
            return wallet to (
                "Saldo tidak cukup. Dibutuhkan ${formatMoney(required, quote)} " +
                    "(≈ ${formatMoney(neededIdr, "IDR")} dari Rupiah). " +
                    "USDT: ${formatMoney(available, quote)} · IDR: ${formatMoney(availableIdr, "IDR")}"
                )
        }

        val converted = (availableIdr.coerceAtMost(neededIdr))
        val usdtReceived = converted / rate
        val updated = wallet.copy(
            idrBalance = (wallet.idrBalance - converted).coerceAtLeast(0.0),
            usdtBalance = wallet.usdtBalance + usdtReceived
        )
        saveWallet(updated, exchange)
        val note = "Auto-konversi ${formatMoney(converted, "IDR")} → ${formatMoney(usdtReceived, "USDT")} (rate Rp ${agu.analys.util.PriceFormatter.formatIdrNumber(rate)})"
        agu.analys.util.AppLogManager.trade("SimConvert", "💱 [AUTO KONVERSI ORDER] $note")
        return updated to note
    }

    @Synchronized
    fun getOpenOrders(exchange: String = "TOKOCRYPTO", symbolFilter: String? = null): List<SimulationOrder> {
        val exKey = getOrdersKey(exchange)
        val raw = prefs.getString(exKey, null) ?: prefs.getString("sim_open_orders", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<SimulationOrder>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val sym = obj.optString("symbol", "")
                if (symbolFilter != null && !sym.equals(symbolFilter, true)) continue
                list.add(SimulationTradeJson.orderFromJson(obj))
            }
            list.sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getOpenOrders(symbolFilter: String?): List<SimulationOrder> =
        getOpenOrders("TOKOCRYPTO", symbolFilter)

    @Synchronized
    fun getOpenOrders(): List<SimulationOrder> =
        getOpenOrders("TOKOCRYPTO", null)

    @Synchronized
    private fun saveOpenOrders(orders: List<SimulationOrder>, exchange: String = "TOKOCRYPTO") {
        val array = JSONArray()
        orders.filter { it.status == SimulationOrderStatus.OPEN }.forEach {
            array.put(SimulationTradeJson.orderToJson(it))
        }
        val exKey = getOrdersKey(exchange)
        prefs.edit().putString(exKey, array.toString()).apply()
    }

    @Synchronized
    fun getTradeHistory(exchange: String = "TOKOCRYPTO", symbolFilter: String? = null): List<SimulationTradeHistoryItem> {
        val exKey = getHistoryKey(exchange)
        val raw = prefs.getString(exKey, null) ?: prefs.getString("sim_trade_history", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<SimulationTradeHistoryItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val sym = obj.optString("symbol", "")
                if (symbolFilter != null && !sym.equals(symbolFilter, true)) continue
                list.add(SimulationTradeJson.historyFromJson(obj))
            }
            list.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getTradeHistory(symbolFilter: String?): List<SimulationTradeHistoryItem> =
        getTradeHistory("TOKOCRYPTO", symbolFilter)

    @Synchronized
    fun getTradeHistory(): List<SimulationTradeHistoryItem> =
        getTradeHistory("TOKOCRYPTO", null)

    @Synchronized
    private fun addTradeHistory(item: SimulationTradeHistoryItem, exchange: String = "TOKOCRYPTO") {
        val current = getTradeHistory(exchange).toMutableList()
        current.add(0, item)
        if (current.size > 100) {
            current.removeAt(current.lastIndex)
        }
        val array = JSONArray()
        current.forEach { array.put(SimulationTradeJson.historyToJson(it)) }
        val exKey = getHistoryKey(exchange)
        prefs.edit().putString(exKey, array.toString()).apply()
    }

    @Synchronized
    fun recordMirroredRealTrade(
        symbol: String,
        baseAsset: String,
        quoteAsset: String = "IDR",
        side: SimulationOrderSide,
        price: Double,
        quantity: Double,
        pnlIdr: Double? = null,
        pnlPercent: Double? = null,
        exchange: String = "TOKOCRYPTO"
    ) {
        val totalIdr = quantity * price
        val feeRate = sellRate(exchange, quoteAsset)
        val feeIdr = totalIdr * feeRate
        val item = SimulationTradeHistoryItem(
            id = UUID.randomUUID().toString(),
            orderId = "real-${System.currentTimeMillis()}",
            symbol = symbol,
            baseAsset = baseAsset.uppercase(),
            quoteAsset = quoteAsset,
            side = side,
            type = SimulationOrderType.MARKET,
            executionPrice = price,
            quantity = quantity,
            totalIdr = totalIdr,
            feeIdr = feeIdr,
            timestamp = System.currentTimeMillis(),
            pnlIdr = pnlIdr,
            pnlPercent = pnlPercent,
            isRealMirror = true
        )
        addTradeHistory(item, exchange)
    }

    @Synchronized
    private fun sameQuoteClass(a: String, b: String): Boolean =
        agu.analys.util.PriceFormatter.isUsdtQuote(a) == agu.analys.util.PriceFormatter.isUsdtQuote(b)

    /** Pesan error bila order memakai kuotasi berbeda dari posisi/order beli yang sudah ada; null bila aman. */
    private fun findQuoteConflict(
        wallet: SimulationWallet,
        baseKey: String,
        quote: String,
        side: SimulationOrderSide,
        exchange: String
    ): String? {
        val held = (wallet.coinBalances[baseKey] ?: 0.0) + (wallet.lockedCoinBalances[baseKey] ?: 0.0)
        if (held > 0.00000001) {
            val heldQuote = wallet.quoteForCoin(baseKey)
            if (!sameQuoteClass(heldQuote, quote)) {
                return if (side == SimulationOrderSide.BUY) {
                    "Posisi $baseKey sudah ada di kuotasi $heldQuote. Jual dulu sebelum beli lewat pair $quote."
                } else {
                    "Posisi $baseKey dibeli di kuotasi $heldQuote. Jual lewat pair $heldQuote, bukan $quote."
                }
            }
        }
        if (side == SimulationOrderSide.BUY) {
            val pending = getOpenOrders(exchange = exchange, symbolFilter = null).firstOrNull {
                it.side == SimulationOrderSide.BUY &&
                    it.status == SimulationOrderStatus.OPEN &&
                    it.baseAsset.equals(baseKey, ignoreCase = true) &&
                    !sameQuoteClass(it.quoteAsset, quote)
            }
            if (pending != null) {
                return "Masih ada order beli $baseKey terbuka di kuotasi ${pending.quoteAsset}. Batalkan dulu sebelum beli lewat pair $quote."
            }
        }
        return null
    }

    fun placeOrder(
        symbol: String,
        baseAsset: String,
        quoteAsset: String = "IDR",
        side: SimulationOrderSide,
        type: SimulationOrderType,
        price: Double,
        stopPrice: Double = 0.0,
        quantity: Double,
        currentMarketPrice: Double,
        exchange: String = "TOKOCRYPTO"
    ): SimulationOrderResult {
        if (quantity <= 0.0) return SimulationOrderResult.Error("Jumlah koin harus lebih besar dari 0.")
        var wallet = getWallet(exchange)
        val baseKey = baseAsset.uppercase()
        val quote = quoteAsset.ifBlank { "IDR" }
        val isUsdt = agu.analys.util.PriceFormatter.isUsdtQuote(quote)

        // Wallet menyimpan satu kuotasi per koin. Tolak order yang mencampur IDR dan USDT
        // pada koin yang sama agar rata-rata harga dan kas tidak rusak.
        findQuoteConflict(wallet, baseKey, quote, side, exchange)?.let {
            return SimulationOrderResult.Error(it)
        }

        when (type) {
            SimulationOrderType.MARKET -> {
                val rawExecPrice = if (currentMarketPrice > 0.0) currentMarketPrice else price
                if (rawExecPrice <= 0.0) return SimulationOrderResult.Error("Harga pasar realtime belum tersedia.")
                // S5 — slippage per sisi untuk order MARKET supaya PnL simulasi memakai basis
                // yang sama dengan mesin exit (fee + 2x slippage), bukan fee-only.
                val slipFrac = if (slippagePct > 0.0) slippagePct / 100.0 else 0.0
                val execPrice = if (slipFrac > 0.0) {
                    if (side == SimulationOrderSide.BUY) rawExecPrice * (1.0 + slipFrac)
                    else rawExecPrice * (1.0 - slipFrac)
                } else {
                    rawExecPrice
                }

                if (side == SimulationOrderSide.BUY) {
                    // Biaya pre-check WAJIB sama dengan biaya yang dipakai eksekusi,
                    // kalau tidak saldo bisa terpotong berbeda dari yang dicek.
                    val feeRate = buyRate(exchange, quote)
                    val required = quantity * execPrice * (1.0 + feeRate)
                    // Pair USDT: saldo Rupiah dikonversi otomatis bila saldo USDT kurang.
                    val (toppedWallet, convNote) = ensureQuoteBalance(wallet, quote, required, exchange)
                    wallet = toppedWallet

                    val result = SimulationOrderEngine.executeMarketBuy(
                        wallet, symbol, baseKey, quote, execPrice, quantity, feeRate
                    )
                    return result.fold(
                        onSuccess = { res ->
                            saveWallet(res.updatedWallet, exchange)
                            addTradeHistory(res.historyItem, exchange)
                            val sisa = res.updatedWallet.getTotalQuote(quote)
                            agu.analys.util.AppLogManager.trade(
                                "SimOrder",
                                "💼 [SIMULASI BUY] Berhasil beli $quantity $baseKey @ ${formatMoney(execPrice, quote)} | Total: ${formatMoney(quantity * execPrice, quote)} | Sisa Saldo $quote: ${formatMoney(sisa, quote)}"
                            )
                            val noteSuffix = if (convNote != null) "\n$convNote" else ""
                            SimulationOrderResult.Success(res.completedOrder, "Market Buy berhasil @ ${formatMoney(execPrice, quote)}!$noteSuffix")
                        },
                        onFailure = { err ->
                            agu.analys.util.AppLogManager.warn("SimOrder", "Gagal simulasi Buy $symbol: ${err.message}")
                            SimulationOrderResult.Error(err.message ?: "Gagal memproses Market Buy.")
                        }
                    )
                } else {
                    val result = SimulationOrderEngine.executeMarketSell(
                        wallet, symbol, baseKey, quote, execPrice, quantity,
                        feeRate = sellRate(exchange, quote),
                        buyFeeRate = buyRate(exchange, quote)
                    )
                    return result.fold(
                        onSuccess = { res ->
                            saveWallet(res.updatedWallet, exchange)
                            addTradeHistory(res.historyItem, exchange)
                            val pnlStr = if (res.historyItem.pnlIdr != null) {
                                val sign = if (res.historyItem.pnlIdr!! >= 0) "+" else ""
                                " | PnL: $sign${formatMoney(res.historyItem.pnlIdr!!, quote)} (${String.format(java.util.Locale.US, "%.2f", res.historyItem.pnlPercent ?: 0.0)}%)"
                            } else ""
                            agu.analys.util.AppLogManager.trade(
                                "SimOrder",
                                "💼 [SIMULASI SELL] Berhasil jual ${res.historyItem.quantity} $baseKey @ ${formatMoney(execPrice, quote)} | Hasil: ${formatMoney(res.historyItem.totalIdr, quote)}$pnlStr"
                            )
                            SimulationOrderResult.Success(res.completedOrder, "Market Sell berhasil @ ${formatMoney(execPrice, quote)}!")
                        },
                        onFailure = { err ->
                            agu.analys.util.AppLogManager.warn("SimOrder", "Gagal simulasi Sell $symbol: ${err.message}")
                            SimulationOrderResult.Error(err.message ?: "Gagal memproses Market Sell.")
                        }
                    )
                }
            }

            SimulationOrderType.LIMIT, SimulationOrderType.STOP_LIMIT -> {
                if (price <= 0.0) return SimulationOrderResult.Error("Harga Limit harus lebih besar dari 0.")
                val totalQuote = quantity * price
                // Limit order = maker, dan tarifnya berbeda untuk beli vs jual
                // (mis. Tokocrypto IDR: beli maker 0,1222% vs jual maker 0,3322%).
                val feeRate = if (side == SimulationOrderSide.BUY) {
                    buyRate(exchange, quote, useMaker = true)
                } else {
                    sellRate(exchange, quote, useMaker = true)
                }
                val feeQuote = totalQuote * feeRate

                if (side == SimulationOrderSide.BUY) {
                    val requiredQuote = totalQuote + feeQuote
                    val (toppedWallet, convNote) = ensureQuoteBalance(wallet, quote, requiredQuote, exchange)
                    wallet = toppedWallet
                    if (wallet.getAvailableQuote(quote) < requiredQuote) {
                        return SimulationOrderResult.Error(
                            "Saldo $quote tidak cukup untuk limit order. Tersedia: ${formatMoney(wallet.getAvailableQuote(quote), quote)}" +
                                (if (isUsdt) " (saldo IDR juga tidak mencukupi untuk auto-konversi)." else ".")
                        )
                    }

                    val updatedWallet = if (isUsdt) {
                        wallet.copy(lockedUsdt = wallet.lockedUsdt + requiredQuote)
                    } else {
                        wallet.copy(lockedIdr = wallet.lockedIdr + requiredQuote)
                    }
                    saveWallet(updatedWallet, exchange)

                    val order = SimulationOrder(
                        id = UUID.randomUUID().toString(),
                        symbol = symbol,
                        baseAsset = baseKey,
                        quoteAsset = quote,
                        side = side,
                        type = type,
                        limitPrice = price,
                        stopPrice = stopPrice,
                        quantity = quantity,
                        totalIdr = totalQuote,
                        feeIdr = feeQuote,
                        status = SimulationOrderStatus.OPEN,
                        isStopTriggered = type == SimulationOrderType.LIMIT
                    )

                    val openList = getOpenOrders(exchange).toMutableList()
                    openList.add(order)
                    saveOpenOrders(openList, exchange)

                    processPriceTick(symbol, currentMarketPrice, currentMarketPrice, currentMarketPrice, exchange)

                    val noteSuffix = if (convNote != null) "\n$convNote" else ""
                    return SimulationOrderResult.Success(order, "Order ${type.displayName} Beli dipasang @ ${formatMoney(price, quote)}.$noteSuffix")
                } else {
                    val availableCoin = wallet.getAvailableCoin(baseKey)
                    val actualQuantity = if (quantity > availableCoin && (quantity - availableCoin < 0.001 || (quantity - availableCoin) / availableCoin.coerceAtLeast(0.0001) < 0.001)) {
                        availableCoin
                    } else {
                        quantity
                    }

                    if (availableCoin < actualQuantity) {
                        return SimulationOrderResult.Error("Saldo koin $baseKey tidak cukup. Tersedia: $availableCoin")
                    }

                    val lockedMap = wallet.lockedCoinBalances.toMutableMap()
                    lockedMap[baseKey] = (lockedMap[baseKey] ?: 0.0) + actualQuantity
                    val updatedWallet = wallet.copy(lockedCoinBalances = lockedMap)
                    saveWallet(updatedWallet, exchange)

                    val order = SimulationOrder(
                        id = UUID.randomUUID().toString(),
                        symbol = symbol,
                        baseAsset = baseKey,
                        quoteAsset = quote,
                        side = side,
                        type = type,
                        limitPrice = price,
                        stopPrice = stopPrice,
                        quantity = actualQuantity,
                        totalIdr = totalQuote,
                        feeIdr = feeQuote,
                        status = SimulationOrderStatus.OPEN,
                        isStopTriggered = type == SimulationOrderType.LIMIT
                    )

                    val openList = getOpenOrders(exchange).toMutableList()
                    openList.add(order)
                    saveOpenOrders(openList, exchange)

                    processPriceTick(symbol, currentMarketPrice, currentMarketPrice, currentMarketPrice, exchange)

                    return SimulationOrderResult.Success(order, "Order ${type.displayName} Jual dipasang @ ${formatMoney(price, quote)}.")
                }
            }
        }
    }

    @Synchronized
    fun cancelOrder(orderId: String, exchange: String = "TOKOCRYPTO"): Boolean {
        val openOrders = getOpenOrders(exchange).toMutableList()
        val index = openOrders.indexOfFirst { it.id == orderId }
        if (index == -1) return false
        val order = openOrders.removeAt(index)

        val wallet = getWallet(exchange)
        val baseKey = order.baseAsset.uppercase()

        val updatedWallet = if (order.side == SimulationOrderSide.BUY) {
            val lockedTotal = order.totalIdr + order.feeIdr
            if (agu.analys.util.PriceFormatter.isUsdtQuote(order.quoteAsset)) {
                wallet.copy(lockedUsdt = (wallet.lockedUsdt - lockedTotal).coerceAtLeast(0.0))
            } else {
                wallet.copy(lockedIdr = (wallet.lockedIdr - lockedTotal).coerceAtLeast(0.0))
            }
        } else {
            val lockedMap = wallet.lockedCoinBalances.toMutableMap()
            val currentLocked = lockedMap[baseKey] ?: 0.0
            val remLocked = (currentLocked - order.quantity).coerceAtLeast(0.0)
            if (remLocked <= 0.00000001) lockedMap.remove(baseKey) else lockedMap[baseKey] = remLocked
            wallet.copy(lockedCoinBalances = lockedMap)
        }

        saveWallet(updatedWallet, exchange)
        saveOpenOrders(openOrders, exchange)
        return true
    }

    @Synchronized
    fun cancelAllOrders(symbolFilter: String? = null, exchange: String = "TOKOCRYPTO"): Int {
        val openOrders = getOpenOrders(exchange)
        val targets = if (symbolFilter != null) openOrders.filter { it.symbol.equals(symbolFilter, true) } else openOrders
        var count = 0
        targets.forEach {
            if (cancelOrder(it.id, exchange)) count++
        }
        return count
    }

    @Synchronized
    fun processPriceTick(
        symbol: String,
        currentPrice: Double,
        high24h: Double,
        low24h: Double,
        exchange: String = "TOKOCRYPTO"
    ): List<SimulationOrder> {
        if (currentPrice <= 0.0) return emptyList()
        val allOpen = getOpenOrders(exchange).toMutableList()
        val relevant = allOpen.filter { it.symbol.equals(symbol, true) }
        if (relevant.isEmpty()) return emptyList()

        val filledOrders = mutableListOf<SimulationOrder>()
        var wallet = getWallet(exchange)

        relevant.forEach { order ->
            var shouldFill = false
            var updatedOrder = order

            when (order.type) {
                SimulationOrderType.LIMIT -> {
                    if (order.side == SimulationOrderSide.BUY) {
                        if (currentPrice <= order.limitPrice) shouldFill = true
                    } else {
                        if (currentPrice >= order.limitPrice) shouldFill = true
                    }
                }
                SimulationOrderType.STOP_LIMIT -> {
                    if (order.side == SimulationOrderSide.BUY) {
                        val triggered = order.isStopTriggered || (currentPrice >= order.stopPrice && order.stopPrice > 0.0)
                        if (triggered) {
                            updatedOrder = order.copy(isStopTriggered = true)
                            if (currentPrice <= order.limitPrice) shouldFill = true
                        }
                    } else {
                        val triggered = order.isStopTriggered || (currentPrice <= order.stopPrice && order.stopPrice > 0.0)
                        if (triggered) {
                            updatedOrder = order.copy(isStopTriggered = true)
                            shouldFill = true
                        }
                    }
                }
                SimulationOrderType.MARKET -> shouldFill = true
            }

            if (shouldFill) {
                val baseKey = order.baseAsset.uppercase()
                val rawExecPrice = if (order.type == SimulationOrderType.STOP_LIMIT && order.side == SimulationOrderSide.SELL) {
                    currentPrice
                } else {
                    order.limitPrice
                }
                // S5 — order MARKET dikenai slippage supaya PnL simulasi = basis mesin exit
                // (fee + 2x slippage). Order LIMIT tidak, karena limit tidak "slip".
                val slipFrac = if (slippagePct > 0.0) slippagePct / 100.0 else 0.0
                val execPrice = if (slipFrac > 0.0 && order.type == SimulationOrderType.MARKET) {
                    if (order.side == SimulationOrderSide.BUY) rawExecPrice * (1.0 + slipFrac)
                    else rawExecPrice * (1.0 - slipFrac)
                } else {
                    rawExecPrice
                }
                val orderQuote = order.quoteAsset.ifBlank { "IDR" }
                val isUsdtOrder = agu.analys.util.PriceFormatter.isUsdtQuote(orderQuote)
                // Tarif per sisi — limit order = maker, dan tarif beli ≠ tarif jual.
                val feeRate = if (order.side == SimulationOrderSide.BUY) {
                    buyRate(exchange, orderQuote, useMaker = true)
                } else {
                    sellRate(exchange, orderQuote, useMaker = true)
                }
                val totalQuoteVal = order.quantity * execPrice
                val feeQuoteVal = totalQuoteVal * feeRate

                if (order.side == SimulationOrderSide.BUY) {
                    val lockedToRelease = order.totalIdr + order.feeIdr
                    val newLocked = (wallet.getLockedQuote(orderQuote) - lockedToRelease).coerceAtLeast(0.0)
                    val newBalance = (wallet.getTotalQuote(orderQuote) - (totalQuoteVal + feeQuoteVal)).coerceAtLeast(0.0)

                    val newCoinBalances = wallet.coinBalances.toMutableMap()
                    val currentCoin = newCoinBalances[baseKey] ?: 0.0
                    val prevQuote = wallet.quoteForCoin(baseKey)
                    val currentAvg = if (prevQuote.equals(orderQuote, true)) (wallet.avgBuyPrices[baseKey] ?: 0.0) else 0.0
                    val newTotalCoin = currentCoin + order.quantity
                    val newAvgPrice = if (newTotalCoin > 0.0) {
                        ((currentCoin * currentAvg) + totalQuoteVal) / newTotalCoin
                    } else execPrice

                    newCoinBalances[baseKey] = newTotalCoin
                    val newAvgMap = wallet.avgBuyPrices.toMutableMap().apply { put(baseKey, newAvgPrice) }
                    val newQuoteMap = wallet.coinQuoteAssets.toMutableMap().apply { put(baseKey, orderQuote.uppercase()) }

                    wallet = if (isUsdtOrder) {
                        wallet.copy(
                            usdtBalance = newBalance,
                            lockedUsdt = newLocked,
                            coinBalances = newCoinBalances,
                            avgBuyPrices = newAvgMap,
                            coinQuoteAssets = newQuoteMap
                        )
                    } else {
                        wallet.copy(
                            idrBalance = newBalance,
                            lockedIdr = newLocked,
                            coinBalances = newCoinBalances,
                            avgBuyPrices = newAvgMap,
                            coinQuoteAssets = newQuoteMap
                        )
                    }

                    val history = SimulationTradeHistoryItem(
                        id = UUID.randomUUID().toString(),
                        orderId = order.id,
                        symbol = order.symbol,
                        baseAsset = baseKey,
                        quoteAsset = orderQuote,
                        side = order.side,
                        type = order.type,
                        executionPrice = execPrice,
                        quantity = order.quantity,
                        totalIdr = totalQuoteVal,
                        feeIdr = feeQuoteVal,
                        timestamp = System.currentTimeMillis()
                    )
                    addTradeHistory(history, exchange)
                } else {
                    val lockedMap = wallet.lockedCoinBalances.toMutableMap()
                    val curLocked = lockedMap[baseKey] ?: 0.0
                    val remLocked = (curLocked - order.quantity).coerceAtLeast(0.0)
                    if (remLocked <= 0.00000001) lockedMap.remove(baseKey) else lockedMap[baseKey] = remLocked

                    val newCoinBalances = wallet.coinBalances.toMutableMap()
                    val newAvgMap = wallet.avgBuyPrices.toMutableMap()
                    val newQuoteMap = wallet.coinQuoteAssets.toMutableMap()
                    val curCoin = newCoinBalances[baseKey] ?: 0.0
                    val sellQty = when {
                        order.quantity <= 0.0 -> 0.0
                        order.quantity >= curCoin -> curCoin
                        (curCoin - order.quantity) <= 0.00000001 -> curCoin
                        (curCoin > 0.0 && (curCoin - order.quantity) / curCoin < 1e-6) -> curCoin
                        else -> order.quantity
                    }
                    val remCoin = (curCoin - sellQty).coerceAtLeast(0.0)
                    val isDustRemaining = remCoin <= 0.00000001 ||
                        (curCoin > 0.0 && remCoin / curCoin < 1e-6)
                    if (isDustRemaining) {
                        newCoinBalances.remove(baseKey)
                        newAvgMap.remove(baseKey)
                        newQuoteMap.remove(baseKey)
                    } else {
                        newCoinBalances[baseKey] = remCoin
                    }

                    val fillTotal = sellQty * execPrice
                    val fillFee = fillTotal * feeRate
                    val prevQuote = wallet.quoteForCoin(baseKey)
                    val avgBuy = if (prevQuote.equals(orderQuote, true)) (wallet.avgBuyPrices[baseKey] ?: execPrice) else execPrice
                    // Harga pokok termasuk biaya beli (maker) supaya PnL bersih menghitung KEDUA sisi biaya.
                    val costBasis = sellQty * avgBuy * (1.0 + buyRate(exchange, orderQuote, useMaker = true))
                    val netQuote = (fillTotal - fillFee).coerceAtLeast(0.0)
                    val pnlQuote = fillTotal - costBasis - fillFee
                    val pnlPercent = if (costBasis > 0.0) (pnlQuote / costBasis) * 100.0 else 0.0

                    wallet = if (isUsdtOrder) {
                        wallet.copy(
                            usdtBalance = wallet.usdtBalance + netQuote,
                            coinBalances = newCoinBalances,
                            avgBuyPrices = newAvgMap,
                            coinQuoteAssets = newQuoteMap,
                            lockedCoinBalances = lockedMap
                        )
                    } else {
                        wallet.copy(
                            idrBalance = wallet.idrBalance + netQuote,
                            coinBalances = newCoinBalances,
                            avgBuyPrices = newAvgMap,
                            coinQuoteAssets = newQuoteMap,
                            lockedCoinBalances = lockedMap
                        )
                    }

                    val history = SimulationTradeHistoryItem(
                        id = UUID.randomUUID().toString(),
                        orderId = order.id,
                        symbol = order.symbol,
                        baseAsset = baseKey,
                        quoteAsset = orderQuote,
                        side = order.side,
                        type = order.type,
                        executionPrice = execPrice,
                        quantity = sellQty,
                        totalIdr = fillTotal,
                        feeIdr = fillFee,
                        timestamp = System.currentTimeMillis(),
                        pnlIdr = pnlQuote,
                        pnlPercent = pnlPercent
                    )
                    addTradeHistory(history, exchange)
                }

                allOpen.remove(order)
                val completed = updatedOrder.copy(
                    status = SimulationOrderStatus.FILLED,
                    filledQuantity = order.quantity,
                    filledAvgPrice = execPrice,
                    feeIdr = feeQuoteVal,
                    filledAt = System.currentTimeMillis()
                )
                filledOrders.add(completed)
            } else if (updatedOrder != order) {
                val idx = allOpen.indexOf(order)
                if (idx != -1) allOpen[idx] = updatedOrder
            }
        }

        if (filledOrders.isNotEmpty()) {
            saveWallet(wallet, exchange)
            saveOpenOrders(allOpen, exchange)
        }

        return filledOrders
    }

    private fun formatMoney(value: Double, quoteAsset: String): String {
        return agu.analys.util.PriceFormatter.formatPrice(value, showSymbol = true, quoteAsset = quoteAsset)
    }
}
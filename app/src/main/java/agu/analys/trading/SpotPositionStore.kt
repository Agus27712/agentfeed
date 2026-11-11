package agu.analys.trading

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local spot-position state. A signal never means an order was executed.
 * The user explicitly confirms ownership after trading on Tokocrypto via the
 * manual position form. Default is NO_POSITION because the app cannot read balances.
 *
 * Ownership changes are timestamped so signal history can reconstruct
 * the position state that existed when each signal was emitted.
 */
enum class SpotPositionState {
    NO_POSITION,
    HOLDING
}

/**
 * Konfigurasi Tier / Smart Step Trailing Stop
 * Saat profit (peak vs entry) mencapai threshold minProfitPct, jarak trailing otomatis
 * mengencang ke trailingPercent.
 */
data class TrailingTier(
    val minProfitPct: Double,
    val trailingPercent: Double
)

data class SpotPosition(
    val symbol: String = "",
    val exchange: String = "TOKOCRYPTO",
    val state: SpotPositionState = SpotPositionState.NO_POSITION,
    val investedAmount: Double = 0.0,
    val entryPrice: Double = 0.0,
    val quantity: Double = 0.0,
    val openedAt: Long = 0L,
    val isReal: Boolean = false,
    val isTrailingEnabled: Boolean = false,
    val trailingPercent: Double = 0.0,
    val isTieredTrailingEnabled: Boolean = true,
    val tieredConfigJson: String = "",
    val activeTrailingPercent: Double = 0.0,
    val peakPrice: Double = 0.0,
    val trailingStopPrice: Double = 0.0,
    val stopLossPrice: Double = 0.0,
    val isTrailingTriggered: Boolean = false,
    val isAutoSellEnabled: Boolean = false,
    val tp1Price: Double = 0.0,
    val tp1Percent: Double = 50.0,
    val tp2Price: Double = 0.0,
    val tp2Percent: Double = 50.0,
    val isTp1Triggered: Boolean = false,
    val isTp2Triggered: Boolean = false,
    val lastTrailingOrderId: String? = null,
    val lastOrderUpdateTime: Long = 0L
) {
    val isHolding: Boolean get() = state == SpotPositionState.HOLDING
}

class SpotPositionStore(context: Context) {
    private val prefs: android.content.SharedPreferences = run {
        val appContext = context.applicationContext
        val legacyPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(appContext)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                appContext,
                "${PREFS_NAME}_encrypted",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            // Migrasi otomatis data legacy unencrypted bila prefs terenkripsi masih kosong
            if (encPrefs.all.isEmpty() && legacyPrefs.all.isNotEmpty()) {
                val editor = encPrefs.edit()
                legacyPrefs.all.forEach { (k, v) ->
                    when (v) {
                        is String -> editor.putString(k, v)
                        is Boolean -> editor.putBoolean(k, v)
                        is Int -> editor.putInt(k, v)
                        is Long -> editor.putLong(k, v)
                        is Float -> editor.putFloat(k, v)
                    }
                }
                editor.apply()
            }
            encPrefs
        } catch (_: Exception) {
            legacyPrefs
        }
    }

    companion object {
        private const val PREFS_NAME = "analysis_ui_spot_positions"
        private const val MAX_HISTORY_EVENTS = 50

        val DEFAULT_TIERS = listOf(
            TrailingTier(minProfitPct = 1.0, trailingPercent = 2.0),
            TrailingTier(minProfitPct = 3.0, trailingPercent = 1.5),
            TrailingTier(minProfitPct = 5.0, trailingPercent = 1.2),
            TrailingTier(minProfitPct = 10.0, trailingPercent = 1.0)
        )

        fun serializeTiers(tiers: List<TrailingTier>): String {
            val arr = JSONArray()
            for (t in tiers) {
                val obj = JSONObject()
                obj.put("profit", t.minProfitPct)
                obj.put("trailing", t.trailingPercent)
                arr.put(obj)
            }
            return arr.toString()
        }

        fun deserializeTiers(json: String?): List<TrailingTier> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                val list = mutableListOf<TrailingTier>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        TrailingTier(
                            minProfitPct = obj.optDouble("profit", 0.0),
                            trailingPercent = obj.optDouble("trailing", 0.0)
                        )
                    )
                }
                list.sortedBy { it.minProfitPct }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Menghitung persentase trailing efektif berdasarkan profit saat ini (Smart Step Trailing).
     * Jika profit membesar melebihi tier tertentu, trailing percent mengencang otomatis.
     */
    fun resolveEffectiveTrailingPercent(
        peakPrice: Double,
        entryPrice: Double,
        baseTrailingPercent: Double,
        isTieredEnabled: Boolean = true,
        customTiersJson: String? = null
    ): Double {
        val safeBase = baseTrailingPercent.coerceAtLeast(0.1)
        if (!isTieredEnabled || entryPrice <= 0.0 || peakPrice <= entryPrice) {
            return safeBase
        }
        val profitPct = ((peakPrice - entryPrice) / entryPrice) * 100.0
        val tiers = if (!customTiersJson.isNullOrBlank()) {
            deserializeTiers(customTiersJson).ifEmpty { DEFAULT_TIERS }
        } else {
            DEFAULT_TIERS
        }

        // Cari tier tertinggi yang telah dicapai oleh profit
        val matchedTier = tiers.filter { profitPct >= it.minProfitPct }
            .maxByOrNull { it.minProfitPct }

        return matchedTier?.trailingPercent?.coerceAtLeast(0.1) ?: safeBase
    }

    fun calculateTrailingLimitPrice(peakPrice: Double, entryPrice: Double, trailingPercent: Double): Double {
        val rawStop = peakPrice * (1.0 - trailingPercent / 100.0)
        return if (entryPrice > 0.0) maxOf(rawStop, entryPrice) else rawStop
    }

    private fun getSafeString(key: String): String? {
        return try {
            prefs.getString(key, null)
        } catch (_: ClassCastException) {
            try {
                prefs.all[key]?.toString()
            } catch (_: Exception) {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getSafeBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return try {
            prefs.getBoolean(key, defaultValue)
        } catch (_: ClassCastException) {
            try {
                prefs.all[key]?.toString()?.toBooleanStrictOrNull() ?: defaultValue
            } catch (_: Exception) {
                defaultValue
            }
        } catch (_: Exception) {
            defaultValue
        }
    }

    private fun getSafeLong(key: String, defaultValue: Long = 0L): Long {
        return try {
            prefs.getLong(key, defaultValue)
        } catch (_: ClassCastException) {
            try {
                prefs.all[key]?.toString()?.toLongOrNull() ?: defaultValue
            } catch (_: Exception) {
                defaultValue
            }
        } catch (_: Exception) {
            defaultValue
        }
    }

    fun buildKey(symbol: String, isReal: Boolean, exchange: String = "TOKOCRYPTO"): String {
        val norm = normalize(symbol)
        val ex = exchange.trim().lowercase()
        return "${ex}_${if (isReal) "real" else "sim"}_${norm}"
    }

    fun get(symbol: String, isReal: Boolean = false, exchange: String = "TOKOCRYPTO"): SpotPosition {
        val norm = normalize(symbol)
        val exKey = buildKey(symbol, isReal, exchange)
        val legacyKey = if (isReal) "real_${norm}" else "sim_${norm}"
        
        // Cek storage ber-prefix exchange (${ex}_sim_ / ${ex}_real_) terlebih dahulu
        val key = when {
            prefs.contains("${exKey}_state") -> exKey
            prefs.contains("${legacyKey}_state") -> legacyKey
            else -> {
                // Fallback backward compatibility ke legacy key tanpa prefix jika flag is_real cocok
                val legacyHasState = prefs.contains("${norm}_state")
                val legacyIsReal = getSafeBoolean("${norm}_is_real", false)
                if (legacyHasState && legacyIsReal == isReal) norm else exKey
            }
        }

        val state = getSafeString("${key}_state")
            ?.let { runCatching { SpotPositionState.valueOf(it) }.getOrDefault(SpotPositionState.NO_POSITION) }
            ?: SpotPositionState.NO_POSITION
        val entry = getSafeString("${key}_entry")?.toDoubleOrNull() ?: 0.0
        val peak = getSafeString("${key}_peak")?.toDoubleOrNull() ?: entry
        val trailingPct = getSafeString("${key}_trailing_pct")?.toDoubleOrNull() ?: 0.0
        val isTrailing = getSafeBoolean("${key}_trailing_enabled", false)
        val isTriggered = getSafeBoolean("${key}_trailing_triggered", false)
        val isTieredTrailing = getSafeBoolean("${key}_tiered_trailing_enabled", true)
        val tieredJson = getSafeString("${key}_tiered_config_json").orEmpty()
        val effectiveTrailingPct = if (isTrailing && peak > 0.0 && trailingPct > 0.0) {
            resolveEffectiveTrailingPercent(peak, entry, trailingPct, isTieredTrailing, tieredJson)
        } else {
            trailingPct
        }
        val trailingStop = if (isTrailing && peak > 0.0 && effectiveTrailingPct > 0.0) {
            calculateTrailingLimitPrice(peak, entry, effectiveTrailingPct)
        } else 0.0

        val lastTrailingOrderId = getSafeString("${key}_last_trailing_order_id")
        val lastOrderUpdateTime = getSafeLong("${key}_last_order_update_time", 0L)
        val stopLossPrice = getSafeString("${key}_stop_loss")?.toDoubleOrNull() ?: if (entry > 0.0) entry * 0.99 else 0.0

        return SpotPosition(
            symbol = norm,
            exchange = exchange.uppercase().trim(),
            state = state,
            investedAmount = getSafeString("${key}_invested")?.toDoubleOrNull() ?: 0.0,
            entryPrice = entry,
            quantity = getSafeString("${key}_quantity")?.toDoubleOrNull() ?: 0.0,
            openedAt = getSafeLong("${key}_opened_at", 0L),
            isReal = isReal,
            isTrailingEnabled = isTrailing,
            trailingPercent = trailingPct,
            isTieredTrailingEnabled = isTieredTrailing,
            tieredConfigJson = tieredJson,
            activeTrailingPercent = effectiveTrailingPct,
            peakPrice = peak,
            trailingStopPrice = trailingStop,
            stopLossPrice = stopLossPrice,
            isTrailingTriggered = isTriggered,
            isAutoSellEnabled = getSafeBoolean("${key}_auto_sell_enabled", false),
            tp1Price = getSafeString("${key}_tp1_price")?.toDoubleOrNull() ?: 0.0,
            tp1Percent = getSafeString("${key}_tp1_percent")?.toDoubleOrNull() ?: 50.0,
            tp2Price = getSafeString("${key}_tp2_price")?.toDoubleOrNull() ?: 0.0,
            tp2Percent = getSafeString("${key}_tp2_percent")?.toDoubleOrNull() ?: 50.0,
            isTp1Triggered = getSafeBoolean("${key}_tp1_triggered", false),
            isTp2Triggered = getSafeBoolean("${key}_tp2_triggered", false),
            lastTrailingOrderId = lastTrailingOrderId,
            lastOrderUpdateTime = lastOrderUpdateTime
        )
    }

    /** Reconstruct the position state at a historical timestamp. */
    fun getAt(symbol: String, timestamp: Long, isReal: Boolean = false, exchange: String = "TOKOCRYPTO"): SpotPosition {
        val key = buildKey(symbol, isReal, exchange)
        val history = readHistory(key)
        if (history.length() == 0) {
            val current = get(symbol, isReal, exchange)
            if (current.isHolding && current.openedAt > 0L && current.openedAt <= timestamp) return current
            return SpotPosition(symbol = normalize(symbol), exchange = exchange.uppercase().trim(), isReal = isReal)
        }

        var best: JSONObject? = null
        for (i in 0 until history.length()) {
            val event = history.optJSONObject(i) ?: continue
            val eventTime = event.optLong("timestamp", 0L)
            if (eventTime <= 0L || eventTime > timestamp) continue
            if (best == null || eventTime > best!!.optLong("timestamp", 0L)) best = event
        }

        if (best == null) return SpotPosition(symbol = normalize(symbol), exchange = exchange.uppercase().trim(), isReal = isReal)
        val state = best.optString("state", SpotPositionState.NO_POSITION.name)
            .let { runCatching { SpotPositionState.valueOf(it) }.getOrDefault(SpotPositionState.NO_POSITION) }
        return SpotPosition(
            symbol = normalize(symbol),
            exchange = exchange.uppercase().trim(),
            state = state,
            investedAmount = best.optDouble("investedAmount", 0.0),
            entryPrice = best.optDouble("entryPrice", 0.0),
            quantity = best.optDouble("quantity", 0.0),
            openedAt = best.optLong("openedAt", 0L),
            isReal = isReal
        )
    }

    fun setHolding(symbol: String, invested: Double, entry: Double, quantity: Double, isReal: Boolean = false, exchange: String = "TOKOCRYPTO") {
        val norm = normalize(symbol)
        val key = buildKey(symbol, isReal, exchange)
        val changedAt = System.currentTimeMillis()
        val current = get(symbol, isReal, exchange)
        val openedAt = if (current.isHolding && current.openedAt > 0L) current.openedAt else changedAt
        val peak = if (current.isHolding && current.peakPrice > 0.0) current.peakPrice.coerceAtLeast(entry) else entry
        val stopLossPrice = if (current.stopLossPrice > 0.0) current.stopLossPrice else if (entry > 0.0) entry * 0.99 else 0.0
        val position = SpotPosition(
            symbol = norm,
            exchange = exchange.uppercase().trim(),
            state = SpotPositionState.HOLDING,
            investedAmount = invested,
            entryPrice = entry,
            quantity = quantity,
            openedAt = openedAt,
            isReal = isReal,
            peakPrice = peak,
            stopLossPrice = stopLossPrice,
            isTrailingEnabled = current.isTrailingEnabled,
            trailingPercent = current.trailingPercent,
            isTieredTrailingEnabled = current.isTieredTrailingEnabled,
            tieredConfigJson = current.tieredConfigJson,
            activeTrailingPercent = current.activeTrailingPercent,
            isAutoSellEnabled = current.isAutoSellEnabled,
            tp1Price = current.tp1Price,
            tp1Percent = current.tp1Percent,
            tp2Price = current.tp2Price,
            tp2Percent = current.tp2Percent
        )
        prefs.edit()
            .putString("${key}_state", SpotPositionState.HOLDING.name)
            .putString("${key}_invested", invested.toString())
            .putString("${key}_entry", entry.toString())
            .putString("${key}_quantity", quantity.toString())
            .putLong("${key}_opened_at", openedAt)
            .putBoolean("${key}_is_real", isReal)
            .putString("${key}_peak", peak.toString())
            .putString("${key}_stop_loss", stopLossPrice.toString())
            .putString("${key}_history", appendHistoryEvent(key, position))
            .apply()
    }

    fun markBought(symbol: String, entryPrice: Double, invested: Double = 0.0, quantity: Double = 0.0, isReal: Boolean = false, exchange: String = "TOKOCRYPTO") {
        val finalInvested = if (invested > 0.0) invested else (if (quantity > 0.0 && entryPrice > 0.0) quantity * entryPrice else entryPrice)
        val finalQty = if (quantity > 0.0) quantity else (if (entryPrice > 0.0 && finalInvested > 0.0) finalInvested / entryPrice else (if (entryPrice > 0.0) 1.0 else 0.0))
        setHolding(symbol, invested = finalInvested, entry = entryPrice, quantity = finalQty, isReal = isReal, exchange = exchange)
    }

    fun markBought(symbol: String, invested: Double, entry: Double, isReal: Boolean = false, exchange: String = "TOKOCRYPTO") {
        val qty = if (entry > 0.0) invested / entry else 0.0
        setHolding(symbol, invested = invested, entry = entry, quantity = qty, isReal = isReal, exchange = exchange)
    }

    fun setManualEntryPrice(symbol: String, price: Double, amount: Double = 0.0, isReal: Boolean = false, exchange: String = "TOKOCRYPTO") {
        val qty = if (price > 0.0 && amount > 0.0) amount / price else (if (price > 0.0) 1.0 else 0.0)
        setHolding(symbol, invested = if (amount > 0.0) amount else price, entry = price, quantity = qty, isReal = isReal, exchange = exchange)
    }

    fun markSold(symbol: String, isReal: Boolean = false, exchange: String = "TOKOCRYPTO") {
        val norm = normalize(symbol)
        val key = buildKey(symbol, isReal, exchange)
        val current = get(symbol, isReal, exchange)
        if (!current.isHolding && current.entryPrice == 0.0 && current.investedAmount == 0.0 && current.quantity == 0.0) return
        val changedAt = System.currentTimeMillis()
        val position = SpotPosition(symbol = norm, state = SpotPositionState.NO_POSITION, openedAt = changedAt, isReal = isReal)
        val editor = prefs.edit()
            .putString("${key}_state", SpotPositionState.NO_POSITION.name)
            .remove("${key}_invested")
            .remove("${key}_entry")
            .remove("${key}_quantity")
            .remove("${key}_opened_at")
            .remove("${key}_is_real")
            .remove("${key}_peak")
            .remove("${key}_trailing_pct")
            .remove("${key}_tiered_trailing_enabled")
            .remove("${key}_tiered_config_json")
            .remove("${key}_trailing_enabled")
            .remove("${key}_trailing_triggered")
            .remove("${key}_auto_sell_enabled")
            .remove("${key}_tp1_price")
            .remove("${key}_tp1_percent")
            .remove("${key}_tp2_price")
            .remove("${key}_tp2_percent")
            .remove("${key}_stop_loss")
            .remove("${key}_stop_loss_price")
            .remove("${key}_tp1_triggered")
            .remove("${key}_tp2_triggered")
            .remove("${key}_sl_triggered")
            .remove("${key}_last_trailing_order_id")
            .remove("${key}_last_order_update_time")
            .remove("${key}_paper_engine")
            .remove("${key}_paper_fsm")
            .putString("${key}_history", appendHistoryEvent(key, position))

        // Bersihkan juga legacy key jika legacy mode is_real cocok
        val legacyKey = if (isReal) "real_${norm}" else "sim_${norm}"
        editor.remove("${legacyKey}_state")
            .remove("${legacyKey}_invested")
            .remove("${legacyKey}_entry")
            .remove("${legacyKey}_quantity")

        if (prefs.contains("${norm}_state") && getSafeBoolean("${norm}_is_real", false) == isReal) {
            editor.remove("${norm}_state")
                .remove("${norm}_invested")
                .remove("${norm}_entry")
                .remove("${norm}_quantity")
                .remove("${norm}_opened_at")
                .remove("${norm}_is_real")
                .remove("${norm}_peak")
                .remove("${norm}_trailing_pct")
                .remove("${norm}_tiered_trailing_enabled")
                .remove("${norm}_tiered_config_json")
                .remove("${norm}_trailing_enabled")
                .remove("${norm}_trailing_triggered")
                .remove("${norm}_auto_sell_enabled")
                .remove("${norm}_tp1_price")
                .remove("${norm}_tp1_percent")
                .remove("${norm}_tp2_price")
                .remove("${norm}_tp2_percent")
                .remove("${norm}_stop_loss")
                .remove("${norm}_stop_loss_price")
                .remove("${norm}_tp1_triggered")
                .remove("${norm}_tp2_triggered")
                .remove("${norm}_sl_triggered")
                .remove("${norm}_last_trailing_order_id")
                .remove("${norm}_last_order_update_time")
                .remove("${norm}_paper_engine")
                .remove("${norm}_paper_fsm")
        }

        // 2026-10-26 — catat waktu sell terakhir posisi paper supaya auto-buy tidak membuka
        // posisi ulang pair yang sama tanpa jeda (lihat PaperReentryCooldown). Hanya untuk
        // posisi simulasi; posisi uang riil tidak dicatat dan tidak dibatasi jeda.
        if (!isReal) editor.putLong(paperSoldKey(norm, exchange), changedAt)

        editor.apply()
    }

    /** Waktu sell terakhir posisi **paper** pair ini (0 = tidak pernah dijual / tidak ada catatan). */
    fun lastPaperSoldAt(symbol: String, exchange: String = "TOKOCRYPTO"): Long =
        prefs.getLong(paperSoldKey(normalize(symbol), exchange), 0L)

    private fun paperSoldKey(norm: String, exchange: String): String =
        "${exchange.trim().lowercase()}_sim_${norm}_last_sold_at"

    fun setTrailingOrderIdAndUpdateTime(symbol: String, orderId: String?, updateTime: Long, isReal: Boolean = false, exchange: String = "TOKOCRYPTO") {
        val key = buildKey(symbol, isReal, exchange)
        prefs.edit()
            .putString("${key}_last_trailing_order_id", orderId)
            .putLong("${key}_last_order_update_time", updateTime)
            .apply()
    }

    fun setTrailingStop(
        symbol: String,
        enabled: Boolean,
        trailingPercent: Double,
        referencePrice: Double = 0.0,
        isTieredEnabled: Boolean = true,
        customTiersJson: String? = null,
        isReal: Boolean = false,
        exchange: String = "TOKOCRYPTO"
    ) {
        val key = buildKey(symbol, isReal, exchange)
        if (!enabled) {
            prefs.edit()
                .putBoolean("${key}_trailing_enabled", false)
                .putBoolean("${key}_trailing_triggered", false)
                .remove("${key}_last_trailing_order_id")
                .remove("${key}_last_order_update_time")
                .apply()
            return
        }
        val current = get(symbol, isReal, exchange)
        val peak = when {
            referencePrice > 0.0 -> referencePrice
            current.entryPrice > 0.0 -> current.entryPrice
            else -> 0.0
        }
        val editor = prefs.edit()
            .putBoolean("${key}_trailing_enabled", true)
            .putString("${key}_trailing_pct", trailingPercent.coerceAtLeast(0.1).toString())
            .putBoolean("${key}_tiered_trailing_enabled", isTieredEnabled)
            .putString("${key}_peak", peak.toString())
            .putBoolean("${key}_trailing_triggered", false)
            .remove("${key}_last_trailing_order_id")
            .remove("${key}_last_order_update_time")

        if (customTiersJson != null) {
            editor.putString("${key}_tiered_config_json", customTiersJson)
        }
        editor.apply()
    }

    /**
     * Updates peak price and checks if trailing stop is triggered.
     * Returns Pair<SpotPosition, Boolean(justTriggered)>
     *
     * @param consumeTrigger **kepemilikan edge** (N2). Pemicu trailing bersifat *edge-once*:
     *   pemanggil pertama yang melihat harga melewati batas akan mengonsumsi edge-nya dan
     *   `justTriggered` hanya `true` sekali. Karena itu **hanya pemilik eksekusi** (yaitu
     *   `TradingViewModel.checkTrailingForMode`, satu-satunya yang memanggil `executeAutoSellOrder`)
     *   yang boleh memakai `true`. Pengamat lain — mis. loop 4 detik
     *   `TradingForegroundService` yang hanya butuh peak tetap naik — wajib mengirim
     *   `false`, supaya tidak mencuri edge dan membuat auto-sell tidak pernah terkirim.
     *
     *   Saat `false`, hanya Rule 1 (peak) yang dijalankan; mesin pemicu (hitung batas, filter
     *   2 tick, set flag `trailing_triggered`) tidak disentuh sama sekali dan hasilnya selalu
     *   `Pair(<posisi terbaru>, false)`.
     */
    fun updateTrailingPrice(
        symbol: String,
        currentPrice: Double,
        isReal: Boolean = false,
        exchange: String = "TOKOCRYPTO",
        consumeTrigger: Boolean = true
    ): Pair<SpotPosition, Boolean> {
        val current = get(symbol, isReal, exchange)
        if (!current.isHolding || !current.isTrailingEnabled || currentPrice <= 0.0) {
            return Pair(current, false)
        }

        val key = buildKey(symbol, isReal, exchange)
        var newPeak = current.peakPrice.coerceAtLeast(current.entryPrice)
        var justTriggered = false

        // Rule 1: Peak price hanya di-update saat harga naik (tidak pernah turun)
        if (currentPrice > newPeak) {
            newPeak = currentPrice
            prefs.edit().putString("${key}_peak", newPeak.toString()).apply()
        }

        // Pengamat non-pemilik berhenti di sini: peak sudah naik, edge tidak dikonsumsi (N2).
        if (!consumeTrigger) {
            return Pair(get(symbol, isReal, exchange), false)
        }

        // Rule 2: Resolusi dynamic Smart Step Trailing percent berdasarkan profit tertinggi yang dicapai
        val effectivePct = resolveEffectiveTrailingPercent(
            peakPrice = newPeak,
            entryPrice = current.entryPrice,
            baseTrailingPercent = current.trailingPercent,
            isTieredEnabled = current.isTieredTrailingEnabled,
            customTiersJson = current.tieredConfigJson
        )

        // Rule 3: Hitung batas jual trailing dari peak menggunakan effective percentage
        val rawTrailingStop = newPeak * (1.0 - effectivePct / 100.0)
        val trailingStop = calculateTrailingLimitPrice(newPeak, current.entryPrice, effectivePct)
        
        // Rule 4: Pure Profit-Lock (Anti Cut-Loss): Trailing HANYA AKTIF jika batas jual sudah di atas harga beli (in-profit)
        val isEligibleForTrailing = current.entryPrice > 0.0 && rawTrailingStop > current.entryPrice

        // Rule 5: Saat harga turun menyentuh trailing price dan sudah memenuhi syarat profit-lock -> trigger (Noise filter: minimal 2 ticks di bawah garis)
        if (isEligibleForTrailing && currentPrice <= trailingStop && !current.isTrailingTriggered) {
            val ticksBelow = prefs.getInt("${key}_trailing_ticks_below", 0) + 1
            if (ticksBelow >= 2) {
                justTriggered = true
                prefs.edit()
                    .putBoolean("${key}_trailing_triggered", true)
                    .putInt("${key}_trailing_ticks_below", 0)
                    .apply()
            } else {
                prefs.edit().putInt("${key}_trailing_ticks_below", ticksBelow).apply()
            }
        } else {
            // Reset noise filter jika harga memantul ke atas batas trailing
            prefs.edit().putInt("${key}_trailing_ticks_below", 0).apply()
        }

        val updated = get(symbol, isReal, exchange)
        return Pair(updated, justTriggered)
    }

    fun resetTrailingTrigger(symbol: String, isReal: Boolean = false, exchange: String = "TOKOCRYPTO") {
        val key = buildKey(symbol, isReal, exchange)
        prefs.edit().putBoolean("${key}_trailing_triggered", false).apply()
    }

    /**
     * @param stopLossPrice SL asli dari sinyal. Bila `> 0` ia menimpa SL yang ditulis
     *   [setHolding] — yang tanpa parameter ini selalu `entry * 0.99` (sintetis ≈1%).
     *   Nilai sintetis itu membuat jalur auto-sell lama **dan** rehidrasi FSM paper
     *   memakai SL yang salah (engine memakai lantai friksi, ≥ 5,8% untuk USDT Tokocrypto).
     *   `0.0` (default) mempertahankan perilaku lama.
     */
    fun setAutoSellParams(
        symbol: String,
        enabled: Boolean,
        tp1Price: Double,
        tp1Percent: Double,
        tp2Price: Double,
        tp2Percent: Double,
        isReal: Boolean = false,
        exchange: String = "TOKOCRYPTO",
        stopLossPrice: Double = 0.0
    ) {
        val key = buildKey(symbol, isReal, exchange)
        val editor = prefs.edit()
            .putBoolean("${key}_auto_sell_enabled", enabled)
            .putString("${key}_tp1_price", tp1Price.toString())
            .putString("${key}_tp1_percent", tp1Percent.toString())
            .putString("${key}_tp2_price", tp2Price.toString())
            .putString("${key}_tp2_percent", tp2Percent.toString())
            .putBoolean("${key}_tp1_triggered", false)
            .putBoolean("${key}_tp2_triggered", false)
        if (stopLossPrice > 0.0 && stopLossPrice.isFinite()) {
            editor.putString("${key}_stop_loss", stopLossPrice.toString())
        }
        editor.apply()
    }

    /**
     * Tandai apakah exit posisi ini dipegang engine paper (P2).
     *
     * Penanda ini **wajib** dipersistenkan: [agu.analys.viewmodel.PaperAutoTradeRuntime]
     * hanya hidup di memori, jadi setelah restart penanda inilah satu-satunya cara
     * membedakan posisi yang exit-nya dipegang engine paper dari posisi lain.
     */
    fun setPaperEngineOwned(
        symbol: String,
        isReal: Boolean = false,
        exchange: String = "TOKOCRYPTO",
        owned: Boolean
    ) {
        val key = buildKey(symbol, isReal, exchange)
        if (owned) {
            prefs.edit().putBoolean("${key}_paper_engine", true).apply()
        } else {
            prefs.edit().remove("${key}_paper_engine").apply()
        }
    }

    fun isPaperEngineOwned(symbol: String, isReal: Boolean = false, exchange: String = "TOKOCRYPTO"): Boolean =
        getSafeBoolean("${buildKey(symbol, isReal, exchange)}_paper_engine", false)

    /**
     * Simpan progres state machine exit paper (P4.4) sebagai **satu string JSON**.
     *
     * Store ini sengaja **tidak** mem-parsing isinya: skema JSON dimiliki
     * `agu.analys.engine.scalping.PaperExitFsmState`, sehingga paket `trading` tidak perlu
     * bergantung pada `engine`. Store hanya bertanggung jawab menyimpan dan mengembalikan apa adanya.
     *
     * Satu key (bukan satu key per field) dipilih supaya: (a) `markSold` hanya perlu satu
     * `.remove()` per blok, (b) tidak ada kemungkinan state separuh tertulis, dan (c) JSON bisa
     * membawa `entry`/`stopLoss` untuk memverifikasi bahwa state memang milik posisi ini.
     */
    fun setPaperFsmState(
        symbol: String,
        isReal: Boolean = false,
        exchange: String = "TOKOCRYPTO",
        stateJson: String
    ) {
        prefs.edit().putString("${buildKey(symbol, isReal, exchange)}_paper_fsm", stateJson).apply()
    }

    /** @return JSON mentah, atau `null` bila belum pernah disimpan. Parsing dilakukan pemanggil. */
    fun getPaperFsmState(symbol: String, isReal: Boolean = false, exchange: String = "TOKOCRYPTO"): String? =
        getSafeString("${buildKey(symbol, isReal, exchange)}_paper_fsm")

    /**
     * Hapus progres FSM tersimpan.
     *
     * Wajib dipanggil setiap kali kepemilikan exit dilepas tanpa lewat [markSold] — mis. saat
     * rehidrasi menyerahkan posisi ke jalur auto-sell lama, atau saat mesin dilepas karena posisinya
     * sudah tidak ada. Tanpa ini key akan tertinggal dan bisa terbaca oleh posisi berikutnya yang
     * memakai simbol sama.
     */
    fun clearPaperFsmState(symbol: String, isReal: Boolean = false, exchange: String = "TOKOCRYPTO") {
        prefs.edit().remove("${buildKey(symbol, isReal, exchange)}_paper_fsm").apply()
    }

    /**
     * Simbol (kanonik) yang exit-nya dipegang engine paper dan posisinya masih `HOLDING`.
     *
     * [getAllActiveTrailingSymbols] **tidak bisa** dipakai untuk ini: fungsi itu mensyaratkan
     * `trailing_enabled = true`, sedangkan posisi paper justru mematikan trailing saat engine
     * mengambil alih exit.
     */
    fun getPaperEngineSymbols(isReal: Boolean = false, exchange: String = "TOKOCRYPTO"): List<String> {
        val prefix = "${exchange.trim().lowercase()}_${if (isReal) "real" else "sim"}_"
        val out = mutableListOf<String>()
        for ((k, _) in prefs.all) {
            if (!k.endsWith("_paper_engine")) continue
            val rawPrefix = k.removeSuffix("_paper_engine")
            if (!rawPrefix.startsWith(prefix)) continue
            if (!getSafeBoolean(k, false)) continue
            if (prefs.getString("${rawPrefix}_state", null) != SpotPositionState.HOLDING.name) continue
            out.add(rawPrefix.removePrefix(prefix))
        }
        return out.sorted()
    }

    /**
     * Sama seperti [getPaperEngineSymbols], tetapi **lintas bursa** — mengembalikan pasangan
     * bursa + simbol beserta asal bursanya.
     *
     * P1 — rehidrasi setelah restart dulu hanya membaca bursa aktif
     * (`prefs.marketDataSource.name`), sehingga posisi paper di bursa lain **tidak pernah**
     * mendapat mesin exit: tanpa SL, TP, BE, trailing, maupun time-stop, selamanya. Kuncinya
     * sudah memuat bursa, jadi enumerasinya bisa dilakukan tanpa tahu bursa mana yang aktif.
     *
     * Kunci posisi berformat `"${exchange}_${real|sim}_${symbol}"` (lihat [buildKey]), dan
     * [normalize] sudah membuang `_ / - spasi` dari simbol — jadi bursa adalah segmen pertama,
     * jenis akun segmen kedua, dan sisanya simbol.
     */
    fun getPaperEnginePositions(isReal: Boolean = false): List<PaperEnginePosition> {
        val kind = if (isReal) "real" else "sim"
        val out = mutableListOf<PaperEnginePosition>()
        for ((k, _) in prefs.all) {
            if (!k.endsWith("_paper_engine")) continue
            val rawPrefix = k.removeSuffix("_paper_engine")
            val parts = rawPrefix.split("_")
            if (parts.size < 3) continue
            if (!parts[1].equals(kind, ignoreCase = true)) continue
            if (!getSafeBoolean(k, false)) continue
            if (prefs.getString("${rawPrefix}_state", null) != SpotPositionState.HOLDING.name) continue
            out.add(
                PaperEnginePosition(
                    exchange = parts[0].uppercase(),
                    symbol = parts.drop(2).joinToString("_").uppercase()
                )
            )
        }
        return out.sortedWith(compareBy({ it.exchange }, { it.symbol }))
    }

    fun markTp1Triggered(symbol: String, isReal: Boolean = false, exchange: String = "TOKOCRYPTO") {
        prefs.edit().putBoolean("${buildKey(symbol, isReal, exchange)}_tp1_triggered", true).apply()
    }

    fun markTp2Triggered(symbol: String, isReal: Boolean = false, exchange: String = "TOKOCRYPTO") {
        prefs.edit().putBoolean("${buildKey(symbol, isReal, exchange)}_tp2_triggered", true).apply()
    }

    /** Dipakai bila order jual TP gagal terkirim, supaya TP bisa dicoba lagi. */
    fun resetTp1Trigger(symbol: String, isReal: Boolean = false, exchange: String = "TOKOCRYPTO") {
        prefs.edit().putBoolean("${buildKey(symbol, isReal, exchange)}_tp1_triggered", false).apply()
    }

    fun resetTp2Trigger(symbol: String, isReal: Boolean = false, exchange: String = "TOKOCRYPTO") {
        prefs.edit().putBoolean("${buildKey(symbol, isReal, exchange)}_tp2_triggered", false).apply()
    }

    fun getAllActiveTrailingSymbols(isReal: Boolean? = null, exchange: String? = null): List<String> {
        val result = mutableSetOf<String>()
        val all = prefs.all
        val targetEx = exchange?.trim()?.lowercase()
        for ((k, _) in all) {
            if (!k.endsWith("_state")) continue
            val rawPrefix = k.removeSuffix("_state")
            val isKeyReal = when {
                rawPrefix.contains("_real_") || rawPrefix.startsWith("real_") -> true
                rawPrefix.contains("_sim_") || rawPrefix.startsWith("sim_") -> false
                else -> getSafeBoolean("${rawPrefix}_is_real", false)
            }
            if (isReal != null && isKeyReal != isReal) continue

            if (targetEx != null) {
                val keyMatchesExchange = rawPrefix.startsWith("${targetEx}_")
                if (!keyMatchesExchange && rawPrefix.startsWith("tokocrypto_")) {
                    continue
                }
            }

            val cleanSymbol = rawPrefix
                .removePrefix("tokocrypto_")
                .removePrefix("real_").removePrefix("sim_")
            val canonical = normalize(cleanSymbol)
            val stateStr = prefs.getString(k, null) ?: continue
            val isTrailing = getSafeBoolean("${rawPrefix}_trailing_enabled", false)
            if (stateStr == SpotPositionState.HOLDING.name && isTrailing) {
                result.add(canonical)
            }
        }
        return result.toList().sorted()
    }

    fun hasAnyHolding(isReal: Boolean? = null, exchange: String? = null): Boolean {
        val all = prefs.all
        val targetEx = exchange?.trim()?.lowercase()
        for ((k, _) in all) {
            if (!k.endsWith("_state")) continue
            val stateStr = prefs.getString(k, null) ?: continue
            if (stateStr == SpotPositionState.HOLDING.name) {
                val rawPrefix = k.removeSuffix("_state")
                val isKeyReal = when {
                    rawPrefix.contains("_real_") || rawPrefix.startsWith("real_") -> true
                    rawPrefix.contains("_sim_") || rawPrefix.startsWith("sim_") -> false
                    else -> getSafeBoolean("${rawPrefix}_is_real", false)
                }
                if (isReal != null && isKeyReal != isReal) continue

                if (targetEx != null) {
                    val keyMatchesExchange = rawPrefix.startsWith("${targetEx}_")
                    if (!keyMatchesExchange && rawPrefix.startsWith("tokocrypto_")) {
                        continue
                    }
                }

                val qty = getSafeString("${rawPrefix}_quantity")?.toDoubleOrNull()
                    ?: getSafeString("${rawPrefix}_qty")?.toDoubleOrNull()
                    ?: 0.0
                val isHoldingFlag = getSafeBoolean("${rawPrefix}_holding", false)
                if (isHoldingFlag || qty > 0.0) return true
            }
        }
        return false
    }

    fun getAllActivePositions(exchange: String? = null, isReal: Boolean? = null): List<SpotPosition> {
        val result = mutableListOf<SpotPosition>()
        val all = prefs.all
        val targetEx = exchange?.trim()?.lowercase()
        val processedSymbols = mutableSetOf<String>()

        for ((k, _) in all) {
            if (!k.endsWith("_state")) continue
            val stateStr = prefs.getString(k, null) ?: continue
            if (stateStr == SpotPositionState.HOLDING.name) {
                val rawPrefix = k.removeSuffix("_state")
                val isKeyReal = when {
                    rawPrefix.contains("_real_") || rawPrefix.startsWith("real_") -> true
                    rawPrefix.contains("_sim_") || rawPrefix.startsWith("sim_") -> false
                    else -> getSafeBoolean("${rawPrefix}_is_real", false)
                }
                if (isReal != null && isKeyReal != isReal) continue

                val exFromKey = when {
                    rawPrefix.startsWith("tokocrypto_") -> "TOKOCRYPTO"
                    else -> exchange?.uppercase() ?: "TOKOCRYPTO"
                }

                if (targetEx != null) {
                    val keyMatchesExchange = rawPrefix.startsWith("${targetEx}_")
                    if (!keyMatchesExchange && rawPrefix.startsWith("tokocrypto_")) {
                        continue
                    }
                }

                val cleanSymbol = rawPrefix
                    .removePrefix("tokocrypto_")
                    .removePrefix("real_").removePrefix("sim_")
                val canonical = normalize(cleanSymbol)
                val posKey = "${exFromKey}_${isKeyReal}_$canonical"
                if (!processedSymbols.contains(posKey)) {
                    processedSymbols.add(posKey)
                    val pos = get(canonical, isReal = isKeyReal, exchange = exFromKey)
                    if (pos.isHolding && pos.quantity > 0.0) {
                        result.add(pos)
                    }
                }
            }
        }
        return result
    }

    /**
     * Jumlah posisi **paper** aktif secara global (semua bursa & semua mata uang kuotasi).
     *
     * Dipakai gerbang batas posisi auto-buy (`PaperAutoTradeConfig.positionLimitReached`).
     * Memakai [getAllActivePositions] dengan `exchange = null` supaya lintas bursa, dan
     * `isReal = false` supaya posisi uang riil tidak ikut dihitung. Posisi yang dibeli
     * **manual** ikut terhitung — lihat catatan di `PaperAutoTradeConfig.maxConcurrentPositions`.
     */
    fun countActivePaperPositions(): Int = getAllActivePositions(isReal = false).size

    private fun readHistory(key: String): JSONArray {
        val raw = prefs.getString("${key}_history", null).orEmpty()
        return if (raw.isBlank()) JSONArray() else runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun appendHistoryEvent(key: String, position: SpotPosition): String {
        val history = readHistory(key)
        val event = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("state", position.state.name)
            .put("investedAmount", position.investedAmount)
            .put("entryPrice", position.entryPrice)
            .put("quantity", position.quantity)
            .put("openedAt", position.openedAt)
            .put("isReal", position.isReal)
        history.put(event)
        while (history.length() > MAX_HISTORY_EVENTS) history.remove(0)
        return history.toString()
    }

    fun normalize(symbol: String, defaultQuote: String = "IDR"): String {
        val s = symbol.trim().uppercase().replace("/", "").replace("-", "").replace(" ", "").replace("_", "")
        return when {
            s.endsWith("IDR") -> s
            s.endsWith("USDT") -> s
            s.endsWith("BIDR") -> s
            s.endsWith("USD") -> s
            s.endsWith("USDC") -> s
            s.endsWith("BUSD") -> s
            else -> "${s}${defaultQuote.uppercase()}"
        }
    }

    fun getAllStoredSymbols(isReal: Boolean? = null, exchange: String? = null): List<String> {
        val symbols = mutableSetOf<String>()
        val targetEx = exchange?.trim()?.lowercase()
        val defaultQuote = if (targetEx == "tokocrypto") "USDT" else "IDR"
        for ((k, v) in prefs.all) {
            if (k.endsWith("_state")) {
                val stateStr = v?.toString() ?: continue
                if (stateStr != SpotPositionState.HOLDING.name) continue

                val raw = k.removeSuffix("_state")
                val isKeyReal = when {
                    raw.contains("_real_") || raw.startsWith("real_") -> true
                    raw.contains("_sim_") || raw.startsWith("sim_") -> false
                    else -> getSafeBoolean("${raw}_is_real", false)
                }
                if (isReal != null && isKeyReal != isReal) continue

                if (targetEx != null) {
                    val keyMatchesExchange = raw.startsWith("${targetEx}_")
                    if (!keyMatchesExchange && raw.startsWith("tokocrypto_")) {
                        continue
                    }
                }

                val clean = raw
                    .removePrefix("tokocrypto_")
                    .removePrefix("real_").removePrefix("sim_")
                symbols.add(normalize(clean, defaultQuote))
            }
        }
        return symbols.toList().sorted()
    }

    fun getAllPositions(isReal: Boolean = false, exchange: String = "TOKOCRYPTO"): Map<String, SpotPosition> {
        return getAllStoredSymbols(isReal, exchange).associateWith { get(it, isReal, exchange) }
    }

    fun dumpRawPrefs(): Map<String, Any?> {
        return prefs.all
    }
}

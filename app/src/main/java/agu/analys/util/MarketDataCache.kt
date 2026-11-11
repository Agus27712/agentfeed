package agu.analys.util

import android.content.Context
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.PairPrecision
import agu.analys.model.Timeframe
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import org.json.JSONArray
import org.json.JSONObject

/** Persistent cache for last successful market data. Strictly partitioned by MarketDataSource. */
class MarketDataCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var lastDashboardWriteAt = 0L
    private val lastPairWriteAt = mutableMapOf<String, Long>()

    init { migrateLegacyPairsMetadata() }

    /**
     * Dulu metadata pair tersimpan tanpa penanda sumber (parameter source terlewat),
     * sehingga data lama bisa terbaca di partisi yang keliru. Kunci itu dibuang sekali
     * saat migrasi agar cache selalu bersih.
     */
    private fun migrateLegacyPairsMetadata() {
        if (prefs.getBoolean(KEY_META_MIGRATED_V2, false)) return
        prefs.edit()
            .remove(KEY_PAIRS_METADATA + "_" + agu.analys.config.MarketDataSource.TOKOCRYPTO.name.lowercase())
            .putBoolean(KEY_META_MIGRATED_V2, true)
            .apply()
    }

    fun clearAll() { prefs.edit().clear().apply(); lastDashboardWriteAt = 0L; lastPairWriteAt.clear() }

    fun clearCacheForSource(source: agu.analys.config.MarketDataSource) {
        val s = source.name.lowercase()
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(s) || it.contains("_$s") }.forEach {
            editor.remove(it)
        }
        editor.apply()
        lastDashboardWriteAt = 0L
        lastPairWriteAt.clear()
    }

    fun clearDashboardTicks(source: agu.analys.config.MarketDataSource) {
        val key = KEY_DASHBOARD_TICKS + "_" + source.name.lowercase()
        val keySavedAt = KEY_DASHBOARD_SAVED_AT + "_" + source.name.lowercase()
        prefs.edit().remove(key).remove(keySavedAt).apply()
        lastDashboardWriteAt = 0L
    }

    fun saveDashboardTicks(source: agu.analys.config.MarketDataSource, ticks: Map<String, MarketTick>) {
        if (ticks.isEmpty()) return
        val now = System.currentTimeMillis()
        val key = KEY_DASHBOARD_TICKS + "_" + source.name.lowercase()
        val keySavedAt = KEY_DASHBOARD_SAVED_AT + "_" + source.name.lowercase()
        if (now - lastDashboardWriteAt < DASHBOARD_WRITE_INTERVAL_MS) return
        val arr = JSONArray(); ticks.values.forEach { arr.put(tickToJson(it)) }
        prefs.edit().putString(key, arr.toString()).putLong(keySavedAt, now).apply(); lastDashboardWriteAt = now
    }

    fun loadDashboardTicks(source: agu.analys.config.MarketDataSource): Map<String, MarketTick> {
        val key = KEY_DASHBOARD_TICKS + "_" + source.name.lowercase()
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return try { val arr = JSONArray(raw); buildMap { for (i in 0 until arr.length()) jsonToTick(arr.getJSONObject(i))?.let { put(it.symbol, it) } } } catch (_: Exception) { emptyMap() }
    }

    fun saveWorthCoins(source: agu.analys.config.MarketDataSource, items: List<WorthCoinInfo>) {
        if (items.isEmpty()) return
        val key = KEY_WORTH_COINS + "_" + source.name.lowercase()
        val keySavedAt = KEY_WORTH_SAVED_AT + "_" + source.name.lowercase()
        val arr = JSONArray(); items.forEach { w -> arr.put(JSONObject().put("symbol", w.pair.symbol).put("score", w.worthScore).put("isWorth", w.isWorthIt).put("rec", w.recommendation).put("potential", w.potentialProfitPct).put("rationale", w.aiRationale)) }
        prefs.edit().putString(key, arr.toString()).putLong(keySavedAt, System.currentTimeMillis()).apply()
    }

    fun loadWorthCoins(source: agu.analys.config.MarketDataSource): List<WorthCoinInfo> {
        val key = KEY_WORTH_COINS + "_" + source.name.lowercase()
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try { val arr = JSONArray(raw); buildList { for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); val symbol = o.optString("symbol", ""); if (symbol.isNotBlank()) add(WorthCoinInfo(TradingPair.fromCustomSymbol(symbol), o.optInt("score", 0), o.optBoolean("isWorth", false), o.optString("rec", ""), o.optDouble("potential", 0.0), o.optString("rationale", ""))) } } } catch (_: Exception) { emptyList() }
    }

    fun savePairSnapshot(
        symbol: String,
        timeframe: Timeframe,
        tick: MarketTick?,
        candles: List<CandleBar>,
        source: agu.analys.config.MarketDataSource = agu.analys.config.MarketDataSource.TOKOCRYPTO
    ) {
        if (tick == null && candles.isEmpty()) return
        val normalized = symbol.uppercase()
        val timeframeKey = timeframeCacheKey(timeframe)
        val key = "${source.name.lowercase()}_pair_${normalized}_${timeframeKey}"
        val now = System.currentTimeMillis()
        if (now - (lastPairWriteAt[key] ?: 0L) < PAIR_WRITE_INTERVAL_MS) return
        val root = JSONObject()
        if (tick != null) root.put("tick", tickToJson(tick))
        val cArr = JSONArray()
        candles.takeLast(500).forEach { c ->
            cArr.put(JSONObject().put("t", c.timestamp).put("o", c.open).put("h", c.high).put("l", c.low).put("c", c.close).put("v", c.volume))
        }
        root.put("candles", cArr).put("savedAt", now).put("timeframe", timeframeKey).put("source", source.name)
        prefs.edit().putString(key, root.toString()).apply()
        lastPairWriteAt[key] = now
    }

    /**
     * Sintesis/Update candle terbaru secara real-time dari Ticker Tick.
     * Mencegah race condition & data lag tanpa menunggu refetch API 30 detik.
     */
    fun synthesizeLiveCandle(
        candles: List<CandleBar>,
        livePrice: Double,
        addedVolume: Double = 0.0,
        timeframe: Timeframe = Timeframe.M1
    ): List<CandleBar> {
        if (livePrice <= 0.0) return candles
        if (candles.isEmpty()) {
            val now = System.currentTimeMillis()
            return listOf(CandleBar(now, livePrice, livePrice, livePrice, livePrice, addedVolume))
        }

        val last = candles.last()
        val candleMs = CandleTimeUtil.timeframeDurationMs(timeframe)

        val now = System.currentTimeMillis()
        val isSameBar = (now - last.timestamp) < candleMs

        return if (isSameBar) {
            val updatedLast = last.copy(
                high = maxOf(last.high, livePrice),
                low = minOf(last.low, livePrice),
                close = livePrice,
                volume = last.volume + addedVolume
            )
            candles.dropLast(1) + updatedLast
        } else {
            val newBar = CandleBar(now, livePrice, livePrice, livePrice, livePrice, addedVolume)
            (candles + newBar).takeLast(500)
        }
    }

    fun savePairSnapshot(
        symbol: String,
        tick: MarketTick?,
        candles: List<CandleBar>,
        source: agu.analys.config.MarketDataSource = agu.analys.config.MarketDataSource.TOKOCRYPTO
    ) {
        if (candles.isNotEmpty()) savePairSnapshot(symbol, inferTimeframe(candles), tick, candles, source)
    }

    fun loadPairSnapshot(
        symbol: String,
        timeframe: Timeframe,
        source: agu.analys.config.MarketDataSource = agu.analys.config.MarketDataSource.TOKOCRYPTO
    ): Pair<MarketTick?, List<CandleBar>> {
        val timeframeKey = timeframeCacheKey(timeframe)
        val key = "${source.name.lowercase()}_pair_${symbol.uppercase()}_${timeframeKey}"
        val raw = prefs.getString(key, null) ?: return null to emptyList()
        val snapshot = parseSnapshot(raw) ?: return null to emptyList()
        if (!isFreshEnough(snapshot, timeframe)) return null to emptyList()
        return snapshot.tick to snapshot.candles
    }

    fun loadPairSnapshot(
        symbol: String,
        source: agu.analys.config.MarketDataSource = agu.analys.config.MarketDataSource.TOKOCRYPTO
    ): Pair<MarketTick?, List<CandleBar>> {
        val prefix = "${source.name.lowercase()}_pair_${symbol.uppercase()}_"
        val candidates = prefs.all.keys.filter { it.startsWith(prefix) }
            .mapNotNull { prefs.getString(it, null)?.let(::parseSnapshot) }
            .sortedByDescending { it.savedAt }
        val chosen = candidates.firstOrNull { isFreshEnough(it, null) } ?: return null to emptyList()
        return chosen.tick to chosen.candles
    }

    fun dashboardCacheAgeMs(source: agu.analys.config.MarketDataSource): Long {
        val at = prefs.getLong(KEY_DASHBOARD_SAVED_AT + "_" + source.name.lowercase(), 0L)
        return if (at <= 0) -1L else System.currentTimeMillis() - at
    }

    fun worthCacheAgeMs(source: agu.analys.config.MarketDataSource): Long {
        val at = prefs.getLong(KEY_WORTH_SAVED_AT + "_" + source.name.lowercase(), 0L)
        return if (at <= 0) -1L else System.currentTimeMillis() - at
    }

    private data class Snapshot(val tick: MarketTick?, val candles: List<CandleBar>, val savedAt: Long, val timeframeKey: String = "")
    private fun parseSnapshot(raw: String): Snapshot? = try { val root = JSONObject(raw); val tick = root.optJSONObject("tick")?.let(::jsonToTick); val cArr = root.optJSONArray("candles") ?: JSONArray(); val candles = buildList { for (i in 0 until cArr.length()) { val o = cArr.getJSONObject(i); val close = o.optDouble("c", 0.0); if (close > 0) add(CandleBar(o.optLong("t", 0L), o.optDouble("o", close), o.optDouble("h", close), o.optDouble("l", close), close, o.optDouble("v", 0.0))) } }; Snapshot(tick, candles, root.optLong("savedAt", 0L), root.optString("timeframe", "")) } catch (_: Exception) { null }
    private fun isFreshEnough(snapshot: Snapshot, requested: Timeframe?): Boolean { if (snapshot.savedAt <= 0L || (snapshot.tick == null && snapshot.candles.isEmpty())) return false; if (requested != null && snapshot.timeframeKey.isNotBlank() && snapshot.timeframeKey != timeframeCacheKey(requested)) return false; val age = System.currentTimeMillis() - snapshot.savedAt; val interval = candleIntervalMs(snapshot.candles); val maxAge = if (interval >= DAY_MS) 8L * DAY_MS else (interval * 6L).coerceIn(6L * 60L * 60L * 1000L, 48L * 60L * 60L * 1000L); return age <= maxAge }
    private fun timeframeCacheKey(timeframe: Timeframe): String = when (timeframe) { Timeframe.M15 -> "15m"; Timeframe.H1 -> "1h"; Timeframe.H4 -> "4h"; Timeframe.D1 -> "1d"; else -> timeframe.code.lowercase() }
    private fun inferTimeframe(candles: List<CandleBar>): Timeframe = when (val interval = candleIntervalMs(candles)) { in Long.MIN_VALUE..90_000L -> Timeframe.M1; in 90_001L..420_000L -> Timeframe.M5; in 420_001L..1_200_000L -> Timeframe.M15; in 1_200_001L..5_400_000L -> Timeframe.H1; in 5_400_001L..18_000_000L -> Timeframe.H4; else -> Timeframe.D1 }
    private fun candleIntervalMs(candles: List<CandleBar>): Long { if (candles.size < 2) return DAY_MS; val times = candles.takeLast(6).map { it.timestamp }.sorted(); val diffs = times.zipWithNext().map { it.second - it.first }.filter { it > 0 }; return diffs.sorted().getOrNull(diffs.size / 2) ?: DAY_MS }
    private fun tickToJson(t: MarketTick): JSONObject = JSONObject().put("symbol", t.symbol).put("price", t.price).put("high", t.high24h).put("low", t.low24h).put("vol", t.volume24h).put("change", if (t.change24h.isNaN()) JSONObject.NULL else t.change24h).put("ts", t.timestamp)
    private fun jsonToTick(o: JSONObject): MarketTick? { val price = o.optDouble("price", 0.0); if (price <= 0) return null; val raw = o.opt("change"); val change = when (raw) { null, JSONObject.NULL -> Double.NaN; is Number -> raw.toDouble(); else -> o.optDouble("change", Double.NaN) }; return MarketTick(o.optString("symbol", ""), price, o.optDouble("high", price), o.optDouble("low", price), o.optDouble("vol", 0.0), change, o.optLong("ts", System.currentTimeMillis())) }
    companion object {
        private const val KEY_PAIRS_METADATA = "pairs_metadata_json"
        private const val KEY_META_MIGRATED_V2 = "pairs_metadata_migrated_v2"
        private const val PREFS_NAME = "krypto_market_cache"
        private const val KEY_DASHBOARD_TICKS = "dashboard_ticks_json"
        private const val KEY_DASHBOARD_SAVED_AT = "dashboard_saved_at"
        private const val KEY_WORTH_COINS = "worth_coins_json"
        private const val KEY_WORTH_SAVED_AT = "worth_coins_saved_at"
        private const val DASHBOARD_WRITE_INTERVAL_MS = 15_000L
        private const val PAIR_WRITE_INTERVAL_MS = 15_000L
        private const val DAY_MS = 24L * 60L * 60L * 1000L

        /** Cache dashboard yang lebih tua dari ini tidak dipulihkan (harga basi menyesatkan). */
        const val MAX_DASHBOARD_CACHE_AGE_MS = 6L * 60L * 60L * 1000L
    }

    fun savePairsMetadata(
        metadata: List<PairPrecision>,
        source: agu.analys.config.MarketDataSource = agu.analys.config.MarketDataSource.TOKOCRYPTO
    ) {
        val arr = JSONArray()
        metadata.forEach { m ->
            val obj = JSONObject()
            obj.put("id", m.id)
            obj.put("symbol", m.symbol)
            obj.put("base", m.baseCurrency)
            obj.put("traded", m.tradedCurrency)
            obj.put("priceDec", m.priceDecimals)
            obj.put("qtyDec", m.quantityDecimals)
            arr.put(obj)
        }
        val key = KEY_PAIRS_METADATA + "_" + source.name.lowercase()
        prefs.edit().putString(key, arr.toString()).apply()
    }

    fun loadPairsMetadata(
        source: agu.analys.config.MarketDataSource = agu.analys.config.MarketDataSource.TOKOCRYPTO
    ): List<PairPrecision> {
        val key = KEY_PAIRS_METADATA + "_" + source.name.lowercase()
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<PairPrecision>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(PairPrecision(
                    id = o.optString("id", ""),
                    symbol = o.optString("symbol", ""),
                    baseCurrency = o.optString("base", ""),
                    tradedCurrency = o.optString("traded", ""),
                    priceDecimals = o.optInt("priceDec", 2),
                    quantityDecimals = o.optInt("qtyDec", 0)
                ))
            }
            list
        } catch(e: Exception) {
            emptyList()
        }
    }
}

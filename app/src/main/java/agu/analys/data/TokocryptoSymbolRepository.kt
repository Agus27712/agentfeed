package agu.analys.data

import agu.analys.model.*
import agu.analys.network.NetworkClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Repository Single Source of Truth (SSOT) untuk Dynamic Symbol Discovery Tokocrypto.
 * Mengambil daftar symbol, filter trading (LOT_SIZE, PRICE_FILTER, MIN_NOTIONAL),
 * dan precision secara dinamis dari API resmi:
 * GET https://www.tokocrypto.com/open/v1/common/symbols
 *
 * Mencegah hardcoding pasangan koin dan mendukung deteksi Symbol Type 1 vs Type 3.
 */
object TokocryptoSymbolRepository {
    private val client get() = NetworkClientProvider.marketClient

    private val symbolsMap = ConcurrentHashMap<String, TokocryptoSymbolInfo>()
    private val executionRulesMap = ConcurrentHashMap<String, TokocryptoExecutionRules>()

    private val _symbolsState = MutableStateFlow<List<TokocryptoSymbolInfo>>(emptyList())
    val symbolsState: StateFlow<List<TokocryptoSymbolInfo>> = _symbolsState.asStateFlow()

    private val syncMutex = Mutex()
    private val isInitialized = AtomicBoolean(false)
    private val lastSyncTime = AtomicLong(0L)
    private val lastAttemptTime = AtomicLong(0L)
    private const val SYNC_TTL_MS = 30 * 60 * 1000L // 30 menit refresh interval
    private const val FAILURE_RETRY_MS = 2 * 60 * 1000L // coba lagi cepat setelah gagal

    @Volatile
    private var lastAttemptFailed = false

    /**
     * Sumber daftar pair **hanya** endpoint resmi.
     *
     * Gateway `tokocrypto.site/api/v3/exchangeInfo` tetap sah untuk ticker/kline/depth (lihat
     * `TokocryptoMarketService`), tetapi **bukan** sumber daftar pair: isinya universe Binance
     * (3.729 simbol), termasuk pair yang tidak diperdagangkan Tokocrypto seperti `UVXYBUSDT`.
     */
    private const val OFFICIAL_SYMBOLS_URL = "https://www.tokocrypto.com/open/v1/common/symbols"
    private const val TOKOCRYPTO_EXECUTION_RULES_URL = "https://www.tokocrypto.site/api/v3/executionRules"

    /**
     * Tipe simbol resmi: 1 = MBX, 2 = Next, 3 = NextMe. Bila simbol tidak ada di peta,
     * [getSymbolType] mengembalikan nilai ini — **bukan** diam-diam menganggapnya Type 1
     * (temuan K15). Pemanggil yang membandingkan `== 3` otomatis memakai host Type 1 sebagai
     * perlakukan bawaan, tetapi minimal ada log peringatan sekali per simbol.
     */
    const val SYMBOL_TYPE_UNKNOWN = 0

    /** Simbol tak dikenal yang log peringatannya sudah dicetak (sekali per simbol). */
    private val warnedUnknownSymbolType = ConcurrentHashMap.newKeySet<String>()

    /** Snapshot resmi terakhir; dipakai untuk uji kewajaran pembaruan. */
    @Volatile
    private var lastOfficialSymbols: Set<String> = emptySet()

    init {
        populateDefaultSymbols()
    }

    private fun populateDefaultSymbols() {
        val defaults = listOf(
            Triple("BTC", "IDR", Pair(1.0, 0.00001)),
            Triple("ETH", "IDR", Pair(1.0, 0.0001)),
            Triple("SOL", "IDR", Pair(1.0, 0.001)),
            Triple("DOGE", "IDR", Pair(1.0, 1.0)),
            Triple("XRP", "IDR", Pair(1.0, 0.1)),
            Triple("SUI", "IDR", Pair(1.0, 0.1)),
            Triple("ADA", "IDR", Pair(1.0, 0.1)),
            Triple("BNB", "IDR", Pair(1.0, 0.001)),
            Triple("SHIB", "IDR", Pair(1.0, 1.0)),
            Triple("NEAR", "IDR", Pair(1.0, 0.01)),
            Triple("AVAX", "IDR", Pair(1.0, 0.01)),
            Triple("PEPE", "IDR", Pair(1.0, 1.0)),
            Triple("TRX", "IDR", Pair(1.0, 0.1)),
            Triple("LINK", "IDR", Pair(1.0, 0.01)),
            Triple("RENDER", "IDR", Pair(1.0, 0.01)),
            Triple("FET", "IDR", Pair(1.0, 0.01)),
            Triple("FLOKI", "IDR", Pair(1.0, 1.0)),
            Triple("BONK", "IDR", Pair(1.0, 1.0)),

            Triple("BTC", "USDT", Pair(0.01, 0.00001)),
            Triple("ETH", "USDT", Pair(0.01, 0.0001)),
            Triple("SOL", "USDT", Pair(0.01, 0.01)),
            Triple("DOGE", "USDT", Pair(0.0001, 0.1)),
            Triple("XRP", "USDT", Pair(0.0001, 0.1)),
            Triple("SUI", "USDT", Pair(0.0001, 0.1)),
            Triple("ADA", "USDT", Pair(0.0001, 0.1)),
            Triple("BNB", "USDT", Pair(0.01, 0.001)),
            Triple("SHIB", "USDT", Pair(0.00000001, 1.0)),
            Triple("PEPE", "USDT", Pair(0.00000001, 1.0))
        )

        val minNotionalIdr = 20_000.0
        val minNotionalUsdt = 1.0

        for ((base, quote, precisions) in defaults) {
            val tickSize = precisions.first
            val stepSize = precisions.second
            val minNotional = if (quote == "IDR") minNotionalIdr else minNotionalUsdt

            val info = TokocryptoSymbolInfo(
                symbol = "${base}_$quote",
                baseAsset = base,
                quoteAsset = quote,
                symbolType = 1,
                basePrecision = 8,
                quotePrecision = 8,
                spotTradingEnable = true,
                defaultSelfTradePreventionMode = "NONE",
                priceFilter = TokocryptoPriceFilter(
                    minPrice = tickSize,
                    maxPrice = 1_000_000_000_000.0,
                    tickSize = tickSize
                ),
                lotSizeFilter = TokocryptoLotSizeFilter(
                    minQty = stepSize,
                    maxQty = 1_000_000_000.0,
                    stepSize = stepSize
                ),
                marketLotSizeFilter = TokocryptoLotSizeFilter(
                    minQty = stepSize,
                    maxQty = 1_000_000_000.0,
                    stepSize = stepSize
                ),
                minNotionalFilter = TokocryptoMinNotionalFilter(
                    minNotional = minNotional,
                    applyToMarket = true,
                    avgPriceMins = 5
                )
            )

            val rawSymbol = "${base}$quote"
            val underscore = "${base}_$quote"
            symbolsMap[rawSymbol] = info
            symbolsMap[underscore] = info
        }
        _symbolsState.value = symbolsMap.values.toList()
    }

    fun isReady(): Boolean = symbolsMap.isNotEmpty()

    suspend fun ensureSymbolsLoaded(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val waitMs = if (lastAttemptFailed) FAILURE_RETRY_MS else SYNC_TTL_MS
        if (!force && isInitialized.get() && (now - lastAttemptTime.get() < waitMs)) {
            return@withContext true
        }

        syncMutex.withLock {
            val nowLocked = System.currentTimeMillis()
            val waitLocked = if (lastAttemptFailed) FAILURE_RETRY_MS else SYNC_TTL_MS
            if (!force && isInitialized.get() && (nowLocked - lastAttemptTime.get() < waitLocked)) {
                return@withLock true
            }

            lastAttemptTime.set(System.currentTimeMillis())
            // Gagal sync tidak menghapus snapshot: peta lama (atau metadata bawaan) dipertahankan.
            val success = fetchFromTokocrypto()
            lastAttemptFailed = !success
            if (!success) {
                Timber.w("Tokocrypto symbol discovery gagal; memakai snapshot terakhir/metadata bawaan.")
            } else {
                lastSyncTime.set(System.currentTimeMillis())
            }

            isInitialized.set(true)
            _symbolsState.value = symbolsMap.values.toList()

            // Fetch execution rules di background untuk price range guard
            fetchExecutionRulesQuietly()

            // Sukses jika symbolsMap tidak kosong (baik dari network maupun fallback defaults)
            symbolsMap.isNotEmpty()
        }
    }

    /** Usia snapshot resmi terakhir; `-1` bila belum pernah sukses. */
    fun lastSuccessfulSyncAgeMs(now: Long = System.currentTimeMillis()): Long {
        val last = lastSyncTime.get()
        return if (last <= 0L) -1L else now - last
    }

    private suspend fun fetchFromTokocrypto(): Boolean {
        try {
            val req = Request.Builder()
                .url(OFFICIAL_SYMBOLS_URL)
                .get()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) TokoClient/3.5")
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank()) return false

                val root = JSONObject(body)
                if (root.optInt("code", 0) != 0) {
                    Timber.w("Tokocrypto symbols menolak permintaan: code=${root.optInt("code", -1)}")
                    return false
                }

                val data = TokocryptoSymbolFeed.extractList(root)
                if (data == null || data.length() == 0) {
                    Timber.w("Tokocrypto symbols: bentuk respons tidak dikenal")
                    return false
                }

                val incoming = TokocryptoSymbolFeed.parse(data)
                val reason = TokocryptoSymbolFeed.rejectionReason(incoming, lastOfficialSymbols)
                if (reason != null) {
                    // Jangan pernah mengganti snapshot dengan daftar yang tidak wajar.
                    Timber.w("Tokocrypto symbols ditolak: $reason")
                    return false
                }

                val newMap = buildAliasMap(incoming)
                symbolsMap.clear()
                symbolsMap.putAll(newMap)
                lastOfficialSymbols = incoming.map { it.symbol }.toSet()
                Timber.i("TokocryptoSymbolRepository: ${incoming.size} simbol resmi dimuat")
                return true
            }
        } catch (e: Exception) {
            Timber.w("Gagal fetch Tokocrypto symbols resmi: ${e.message}")
        }
        return false
    }

    private fun buildAliasMap(symbols: List<TokocryptoSymbolInfo>): Map<String, TokocryptoSymbolInfo> {
        val newMap = mutableMapOf<String, TokocryptoSymbolInfo>()
        for (info in symbols) {
            val rawSymbol = info.symbol
            val noUnderscore = rawSymbol.replace("_", "").replace("-", "")
            val underscore = "${info.baseAsset}_${info.quoteAsset}"
            newMap[rawSymbol] = info
            newMap[noUnderscore] = info
            newMap[underscore] = info
        }
        return newMap
    }


    private suspend fun fetchExecutionRulesQuietly() {
        try {
            val req = Request.Builder()
                .url(TOKOCRYPTO_EXECUTION_RULES_URL)
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val root = JSONObject(body)
                    val rulesArray = root.optJSONArray("data") ?: root.optJSONArray("symbols")
                    if (rulesArray != null) {
                        for (i in 0 until rulesArray.length()) {
                            val r = rulesArray.optJSONObject(i) ?: continue
                            val sym = r.optString("symbol", "").uppercase()
                            val rule = TokocryptoExecutionRules(
                                symbol = sym,
                                bidLimitMultUp = r.optString("bidLimitMultUp", "1.1").toDoubleOrNull() ?: 1.1,
                                bidLimitMultDown = r.optString("bidLimitMultDown", "0.9").toDoubleOrNull() ?: 0.9,
                                askLimitMultUp = r.optString("askLimitMultUp", "1.1").toDoubleOrNull() ?: 1.1,
                                askLimitMultDown = r.optString("askLimitMultDown", "0.9").toDoubleOrNull() ?: 0.9
                            )
                            executionRulesMap[sym] = rule
                            symbolsMap[sym]?.executionRules = rule
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun getSymbolInfo(rawSymbol: String): TokocryptoSymbolInfo? {
        val clean = rawSymbol.trim().uppercase()
        val direct = symbolsMap[clean]
        if (direct != null) return direct

        val noUnderscore = clean.replace("_", "").replace("/", "").replace("-", "")
        val directNoUnder = symbolsMap[noUnderscore]
        if (directNoUnder != null) return directNoUnder

        return null
    }

    /**
     * Tipe simbol untuk routing host (1/2/3). Simbol yang tidak ada di peta mengembalikan
     * [SYMBOL_TYPE_UNKNOWN] dan **mencatat log sekali per simbol** (temuan K15): dulu nilai
     * bawaannya `1`, sehingga kesalahan konfigurasi mengarah ke host yang salah tanpa jejak.
     */
    fun getSymbolType(symbol: String): Int {
        val info = getSymbolInfo(symbol)
        if (info != null) return info.symbolType
        warnUnknownSymbolTypeOnce(symbol)
        return SYMBOL_TYPE_UNKNOWN
    }

    private fun warnUnknownSymbolTypeOnce(symbol: String) {
        val key = symbol.trim().uppercase()
        if (key.isEmpty()) return
        if (warnedUnknownSymbolType.add(key)) {
            Timber.w("getSymbolType: \"$key\" tak ada di peta simbol; dianggap tidak diketahui (0)")
        }
    }

    /**
     * True bila simbol dikenal metadata resmi (dipakai gate watchlist, temuan K13).
     *
     * Sengaja **tidak** menuntut status `spotTradingEnable`: watchlist adalah daftar pantau,
     * sehingga pair suspend tetap boleh dipantau. Gate tampilan dashboard (yang menuntut
     * status TRADING) ada di [isSpotTradingEnabled] / `DashboardRanking.symbolListed`.
     */
    fun hasSymbol(rawSymbol: String): Boolean = getSymbolInfo(rawSymbol) != null

    /**
     * Gate listing fail-closed untuk tampilan dashboard.
     * True hanya bila simbol dikenal DAN spot trading aktif (status TRADING).
     * Simbol tak dikenal (belum pernah sync sukses) dianggap tidak layak tampil.
     * Aplikasi diasumsikan selalu online dan jalur discovery symbol aman.
     */
    fun isSpotTradingEnabled(rawSymbol: String): Boolean =
        getSymbolInfo(rawSymbol)?.spotTradingEnable == true

    /** Helper pengujian: ubah flag listing satu entri tanpa menghapus metadata lain. */
    internal fun setSpotTradingForTest(rawSymbol: String, enabled: Boolean): Boolean {
        val key = rawSymbol.trim().uppercase()
        val existing = symbolsMap[key] ?: return false
        symbolsMap[key] = existing.copy(spotTradingEnable = enabled)
        return true
    }

    fun getAllTradingPairs(quoteFilter: String? = null): List<TradingPair> {
        val uniqueSymbols = symbolsMap.values
            .filter {
                it.spotTradingEnable &&
                    (it.quoteAsset == "IDR" || it.quoteAsset == "USDT")
            }
            .distinctBy { it.symbol }
        val filtered = if (quoteFilter.isNullOrBlank() || quoteFilter.equals("ALL", true)) {
            uniqueSymbols
        } else {
            val q = quoteFilter.uppercase()
            uniqueSymbols.filter { it.quoteAsset.equals(q, true) }
        }
        return filtered.map { it.toTradingPair() }
    }

    fun searchSymbols(query: String, quoteFilter: String? = null): List<TradingPair> {
        val q = query.trim().uppercase()
        val all = getAllTradingPairs(quoteFilter)
        if (q.isBlank()) return all
        return all.filter {
            it.symbol.contains(q) || it.baseAsset.contains(q) || it.displayName.uppercase().contains(q)
        }
    }

/**
 * Memvalidasi order spot Tokocrypto berdasarkan filter trading resmi:
 * - LOT_SIZE
 * - MARKET_LOT_SIZE
 * - PRICE_FILTER
 * - MIN_NOTIONAL / NOTIONAL
 * - PRICE_RANGE execution rules
 *
 * PENTING:
 * Untuk real trading, metadata symbol wajib tersedia.
 * Tidak boleh fail-open menggunakan quantity mentah.
 */
fun validateOrder(
    symbol: String,
    price: Double,
    quantity: Double,
    isMarket: Boolean = false,
    isBuy: Boolean = true,
    referenceMarketPrice: Double = 0.0
): TokocryptoValidationResult {

    val info = getSymbolInfo(symbol)

    // Real order tidak boleh lolos tanpa metadata exchange.
    if (info == null) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = price,
            adjustedQty = quantity,
            reason = "Metadata symbol Tokocrypto belum tersedia untuk $symbol."
        )
    }

    if (!info.spotTradingEnable) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = price,
            adjustedQty = quantity,
            reason = "Spot trading untuk $symbol sedang tidak tersedia di Tokocrypto."
        )
    }

    if (!price.isFinite() || price <= 0.0) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = price,
            adjustedQty = quantity,
            reason = "Harga order tidak valid."
        )
    }

    if (!quantity.isFinite() || quantity <= 0.0) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = price,
            adjustedQty = quantity,
            reason = "Quantity order tidak valid."
        )
    }

    // =========================================================
    // 1. PRICE FILTER
    // =========================================================
    var adjPrice = price

    if (!isMarket) {
        val pf = info.priceFilter

        if (pf == null) {
            return TokocryptoValidationResult(
                isValid = false,
                adjustedPrice = price,
                adjustedQty = quantity,
                reason = "PRICE_FILTER $symbol belum tersedia."
            )
        }

        if (adjPrice < pf.minPrice) {
            return TokocryptoValidationResult(
                isValid = false,
                adjustedPrice = adjPrice,
                adjustedQty = quantity,
                reason = "Harga di bawah minimum Tokocrypto: ${pf.minPrice}"
            )
        }

        if (adjPrice > pf.maxPrice) {
            return TokocryptoValidationResult(
                isValid = false,
                adjustedPrice = adjPrice,
                adjustedQty = quantity,
                reason = "Harga di atas maksimum Tokocrypto: ${pf.maxPrice}"
            )
        }

        adjPrice = info.formatPrice(adjPrice)

        // Pastikan harga hasil pembulatan masih berada di range.
        if (adjPrice < pf.minPrice || adjPrice > pf.maxPrice) {
            return TokocryptoValidationResult(
                isValid = false,
                adjustedPrice = adjPrice,
                adjustedQty = quantity,
                reason = "Harga setelah normalisasi PRICE_FILTER tidak valid."
            )
        }

        // =====================================================
        // PRICE RANGE / EXECUTION RULE
        // =====================================================
        val exec = info.executionRules

        if (exec != null && referenceMarketPrice > 0.0) {
            if (isBuy) {
                val maxBuy =
                    referenceMarketPrice * exec.bidLimitMultUp

                val minBuy =
                    referenceMarketPrice * exec.bidLimitMultDown

                if (adjPrice > maxBuy) {
                    return TokocryptoValidationResult(
                        isValid = false,
                        adjustedPrice = adjPrice,
                        adjustedQty = quantity,
                        reason =
                            "Harga BELI melebihi guard exchange " +
                            "(maksimum: $maxBuy)."
                    )
                }

                if (adjPrice < minBuy) {
                    return TokocryptoValidationResult(
                        isValid = false,
                        adjustedPrice = adjPrice,
                        adjustedQty = quantity,
                        reason =
                            "Harga BELI di bawah guard exchange " +
                            "(minimum: $minBuy)."
                    )
                }
            } else {
                val maxSell =
                    referenceMarketPrice * exec.askLimitMultUp

                val minSell =
                    referenceMarketPrice * exec.askLimitMultDown

                if (adjPrice > maxSell) {
                    return TokocryptoValidationResult(
                        isValid = false,
                        adjustedPrice = adjPrice,
                        adjustedQty = quantity,
                        reason =
                            "Harga JUAL melebihi guard exchange " +
                            "(maksimum: $maxSell)."
                    )
                }

                if (adjPrice < minSell) {
                    return TokocryptoValidationResult(
                        isValid = false,
                        adjustedPrice = adjPrice,
                        adjustedQty = quantity,
                        reason =
                            "Harga JUAL di bawah guard exchange " +
                            "(minimum: $minSell)."
                    )
                }
            }
        }
    }

    // =========================================================
    // 2. LOT SIZE / MARKET LOT SIZE
    // =========================================================
    val lot = if (isMarket) {
        info.marketLotSizeFilter ?: info.lotSizeFilter
    } else {
        info.lotSizeFilter
    }

    if (lot == null) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = quantity,
            reason =
                "Filter quantity Tokocrypto belum tersedia " +
                "untuk $symbol."
        )
    }

    if (lot.stepSize <= 0.0) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = quantity,
            reason =
                "stepSize quantity Tokocrypto tidak valid untuk $symbol."
        )
    }

    if (quantity < lot.minQty) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = quantity,
            reason =
                "Quantity $quantity di bawah minimum " +
                "${lot.minQty}."
        )
    }

    if (quantity > lot.maxQty) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = quantity,
            reason =
                "Quantity $quantity melebihi maksimum " +
                "${lot.maxQty}."
        )
    }

    /*
     * Normalisasi quantity memakai BigDecimal.
     *
     * Contoh:
     * quantity = 1.2330456226880395
     * stepSize = 0.0001
     *
     * hasil:
     * 1.2330
     */
    val rawQty = BigDecimal.valueOf(quantity)
    val step = BigDecimal.valueOf(lot.stepSize)

    val steps = rawQty
        .divide(step, 0, RoundingMode.FLOOR)

    val normalizedQty = steps
        .multiply(step)
        .stripTrailingZeros()

    val normalizedQtyDouble =
        normalizedQty.toDouble()

    // Setelah rounding ke bawah, cek lagi minimum.
    if (normalizedQtyDouble < lot.minQty) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = normalizedQtyDouble,
            reason =
                "Quantity setelah normalisasi ($normalizedQty) " +
                "menjadi di bawah minimum ${lot.minQty}."
        )
    }

    if (normalizedQtyDouble > lot.maxQty) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = normalizedQtyDouble,
            reason =
                "Quantity setelah normalisasi ($normalizedQty) " +
                "melebihi maksimum ${lot.maxQty}."
        )
    }

    // Pastikan benar-benar kelipatan stepSize.
    val remainder = normalizedQty.remainder(step)

    if (remainder.compareTo(BigDecimal.ZERO) != 0) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = normalizedQtyDouble,
            reason =
                "Quantity hasil normalisasi tidak sesuai stepSize " +
                "${lot.stepSize}."
        )
    }

    // =========================================================
    // 3. MIN NOTIONAL
    // =========================================================
    val effectivePrice =
        if (isMarket && referenceMarketPrice > 0.0) {
            referenceMarketPrice
        } else {
            adjPrice
        }

    val notional =
        effectivePrice * normalizedQtyDouble

    val minNotional =
        info.minNotionalFilter?.minNotional ?: 0.0

    if (minNotional > 0.0 && notional < minNotional) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = normalizedQtyDouble,
            reason =
                "Nilai order setelah normalisasi ($notional) " +
                "di bawah minimum notional $minNotional."
        )
    }

    return TokocryptoValidationResult(
        isValid = true,
        adjustedPrice = adjPrice,
        adjustedQty = normalizedQtyDouble,
        reason =
            "Order valid. Quantity dinormalisasi terhadap " +
            "stepSize=${lot.stepSize}."
    )
}
}

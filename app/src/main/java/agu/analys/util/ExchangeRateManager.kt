package agu.analys.util

import agu.analys.service.TokocryptoMarketService
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sumber tunggal (SSOT) untuk rate konversi USDT <-> IDR.
 *
 * Aturan (seluruh data kini dari Tokocrypto: pair USDT maupun IDR bersumber dari satu bursa yang sama):
 * - Satu-satunya sumber adalah **Tokocrypto**, pair USDT/IDR, TANPA fallback ke exchange/metode lain.
 * - Nilai yang dipakai adalah **harga tengah bid-ask** orderbook (bukan harga transaksi terakhir,
 *   yang bisa basi di pair sepi). Bid, ask, dan spread dicatat di log agar bisa dicocokkan dengan aplikasi Tokocrypto.
 * - Hanya satu penulis: [refresh] (loop 60 detik + tombol refresh manual). Tidak ada jalur lain yang menimpa.
 * - Bila pengambilan gagal, rate lama dipertahankan tetapi ditandai usang lewat [isStale].
 * - Tidak ada nilai yang di-hardcode. Tanpa data, [usdtIdrRate] bernilai `0.0` = "rate belum tersedia".
 */
object ExchangeRateManager {

    private const val PREFS_NAME = "exchange_rate_prefs"
    // Kunci baru: nilai lama (sumber berbeda / bisa basi) sengaja tidak dibaca lagi.
    private const val KEY_RATE = "usdt_idr_rate_tokocrypto_v2"
    private const val KEY_RATE_AT = "usdt_idr_rate_tokocrypto_v2_at"
    private const val REFRESH_INTERVAL_MS = 60_000L
    private const val MIN_RETRY_INTERVAL_MS = 15_000L

    /** Rate dianggap usang bila tidak berhasil diperbarui lebih lama dari ini. */
    const val STALE_AFTER_MS = 10L * 60L * 1000L

    /** Cache yang lebih tua dari ini tidak dipakai saat aplikasi dibuka. */
    private const val MAX_CACHE_AGE_MS = 6L * 60L * 60L * 1000L

    /** Pair USDT/IDR di Tokocrypto. */
    private const val TOKO_USDT_IDR = "USDTIDR"

    private val _usdtIdrRate = MutableStateFlow(0.0)
    val usdtIdrRate: StateFlow<Double> = _usdtIdrRate.asStateFlow()

    private val _lastUpdatedAt = MutableStateFlow(0L)
    val lastUpdatedAt: StateFlow<Long> = _lastUpdatedAt.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val fetchMutex = Mutex()
    private var appContext: Context? = null
    private var refreshJob: Job? = null
    @Volatile private var lastAttemptAt = 0L

    private data class Quote(val bid: Double, val ask: Double) {
        val mid: Double get() = (bid + ask) / 2.0
        val spreadPct: Double get() = if (mid > 0.0) (ask - bid) / mid * 100.0 else 0.0
    }

    /** Rate snapshot sinkron — dipakai oleh jalur non-suspend (order engine, formatter). */
    fun currentRate(): Double = _usdtIdrRate.value

    fun isRateAvailable(): Boolean = _usdtIdrRate.value > 0.0

    fun lastUpdateMillis(): Long = _lastUpdatedAt.value

    /** Umur rate dalam ms sejak berhasil diperbarui; `Long.MAX_VALUE` bila belum pernah. */
    fun ageMs(): Long {
        val at = _lastUpdatedAt.value
        return if (at <= 0L) Long.MAX_VALUE else (System.currentTimeMillis() - at).coerceAtLeast(0L)
    }

    /** True bila rate belum ada atau tidak berhasil diperbarui lebih dari [STALE_AFTER_MS]. */
    fun isStale(): Boolean = !isRateAvailable() || ageMs() > STALE_AFTER_MS

    // ═════════════════════════════════════════════════════════════════════
    // Konversi
    // ═════════════════════════════════════════════════════════════════════

    /** Konversi nominal USDT -> IDR. Mengembalikan null bila rate belum tersedia. */
    fun usdtToIdr(amountUsdt: Double): Double? {
        val rate = _usdtIdrRate.value
        if (rate <= 0.0) return null
        return amountUsdt * rate
    }

    /** Konversi nominal IDR -> USDT. Mengembalikan null bila rate belum tersedia. */
    fun idrToUsdt(amountIdr: Double): Double? {
        val rate = _usdtIdrRate.value
        if (rate <= 0.0) return null
        return amountIdr / rate
    }

    /** Konversi harga/value dari mata uang kuotasi pair ke Rupiah (IDR pair = nilai apa adanya). */
    fun toIdr(amount: Double, quoteAsset: String): Double? {
        if (!PriceFormatter.isUsdtQuote(quoteAsset)) return amount
        return usdtToIdr(amount)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Init & lifecycle
    // ═════════════════════════════════════════════════════════════════════

    fun init(context: Context) {
        appContext = context.applicationContext
        val (cachedRate, cachedAt) = readCachedRate()
        val age = System.currentTimeMillis() - cachedAt
        if (cachedRate > 0.0 && cachedAt > 0L && age in 0..MAX_CACHE_AGE_MS) {
            _usdtIdrRate.value = cachedRate
            _lastUpdatedAt.value = cachedAt
        }
    }

    /**
     * Menjalankan loop refresh rate periodik di scope yang diberikan.
     * Aman dipanggil berulang kali — job lama akan dibatalkan.
     */
    fun startAutoRefresh(scope: CoroutineScope) {
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Kegagalan satu putaran tidak boleh menghentikan loop.
                }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    // ═════════════════════════════════════════════════════════════════════
    // Fetch (satu-satunya penulis rate)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Ambil rate terbaru dari Tokocrypto. Mengembalikan rate saat ini (>0) atau 0.0 bila belum pernah berhasil.
     * @param force true = abaikan jeda anti-spam (dipakai tombol refresh manual).
     * Cek [isStale] untuk mengetahui apakah pengambilan terakhir berhasil.
     */
    suspend fun refresh(force: Boolean = false): Double = fetchMutex.withLock {
        val now = System.currentTimeMillis()
        // Hindari spam saat banyak pemanggil meminta refresh bersamaan.
        if (!force && now - lastAttemptAt < MIN_RETRY_INTERVAL_MS && _usdtIdrRate.value > 0.0) {
            return@withLock _usdtIdrRate.value
        }
        lastAttemptAt = now
        _isRefreshing.value = true
        try {
            val quote = fetchQuoteFromTokocrypto()
            if (quote != null) {
                applyRate(quote)
            } else {
                val ageMin = if (_lastUpdatedAt.value > 0L) ageMs() / 60_000L else -1L
                AppLogManager.warn(
                    "ExchangeRate",
                    "⚠️ Kurs USDT/IDR gagal diambil dari orderbook Tokocrypto. " +
                        "Kurs lama dipertahankan: ${_usdtIdrRate.value}" +
                        if (ageMin >= 0) " (umur $ageMin menit)" else " (belum pernah berhasil)"
                )
            }
        } finally {
            _isRefreshing.value = false
        }
        _usdtIdrRate.value
    }

    /** Best bid & best ask USDT/IDR dari orderbook Tokocrypto; null bila data tidak valid. */
    private suspend fun fetchQuoteFromTokocrypto(): Quote? {
        val (bids, asks) = TokocryptoMarketService.fetchOrderBook(TOKO_USDT_IDR, 5)
        val bestBid = bids.maxOfOrNull { it.price } ?: return null
        val bestAsk = asks.minOfOrNull { it.price } ?: return null
        if (bestBid <= 0.0 || bestAsk <= 0.0 || bestAsk < bestBid) return null
        if (!bestBid.isFinite() || !bestAsk.isFinite()) return null
        return Quote(bestBid, bestAsk)
    }

    private fun applyRate(quote: Quote) {
        val rate = quote.mid
        val previous = _usdtIdrRate.value
        _usdtIdrRate.value = rate
        _lastUpdatedAt.value = System.currentTimeMillis()
        writeCachedRate(rate, _lastUpdatedAt.value)
        if (previous <= 0.0 || kotlin.math.abs(previous - rate) / previous > 0.0005) {
            AppLogManager.market(
                "ExchangeRate",
                "💱 Kurs USDT/IDR (Tokocrypto, harga tengah): Rp ${PriceFormatter.formatIdrNumber(rate)} / USDT " +
                    "[bid ${PriceFormatter.formatIdrNumber(quote.bid)} · ask ${PriceFormatter.formatIdrNumber(quote.ask)} · " +
                    "spread ${String.format(java.util.Locale.US, "%.2f", quote.spreadPct)}%]"
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Cache persistensi (hasil fetch Tokocrypto, bukan konstanta)
    // ═════════════════════════════════════════════════════════════════════

    private fun readCachedRate(): Pair<Double, Long> {
        val ctx = appContext ?: return 0.0 to 0L
        return try {
            val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rate = p.getString(KEY_RATE, null)?.toDoubleOrNull() ?: 0.0
            rate to p.getLong(KEY_RATE_AT, 0L)
        } catch (_: Exception) {
            0.0 to 0L
        }
    }

    private fun writeCachedRate(rate: Double, at: Long) {
        val ctx = appContext ?: return
        try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RATE, rate.toString())
                .putLong(KEY_RATE_AT, at)
                .apply()
        } catch (_: Exception) { }
    }
}

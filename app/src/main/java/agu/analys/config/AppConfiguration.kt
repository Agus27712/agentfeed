package agu.analys.config

import kotlin.math.abs
import kotlin.math.max

enum class AiProvider(val label: String) { GEMINI("Gemini") }

enum class StrategyMode(val label: String, val badge: String, val shortDesc: String) {
    SCALPING("Scalping Agresif", "⚡ SCALPING", "Cepat (1M–15M) · Trigger mikro"),
    SWING("Swing Trade", "📈 SWING", "Jangka menengah (1H–1D)"),
    OFFICE_DAILY("Intraday", "⚡ INTRADAY", "Open Pagi · Close Malam · Anti Flash Dump");
}

/**
 * Biaya trading **all-in per sisi** (%): trading fee + pajak + ICEx (bila ada).
 *
 * Tokocrypto (efektif 18 Jun 2026, support resmi):
 * - Pair **USDT/crypto**: 0,4044% per sisi (maker = taker; termasuk PPh 0,21% + ICEx 0,0444%).
 * - Pair **IDR**: beli taker 0,2222% / maker 0,1222%; jual taker 0,4322% / maker 0,3322%
 *   (jual termasuk PPh 0,21% + ICEx 0,0222%; beli IDR pajak 0%).
 * - Diskon TKO 25% / VIP tidak di-default; user bisa override di Settings.
 *
 * Nilai bawaan di atas adalah angka legacy (warisan preset lama), bukan tarif bursa aktif.
 * Jalur runtime dilarang memakai konstruktor tanpa argumen: wajib memakai preset eksplisit
 * `tokocryptoUsdt()` / `tokocryptoIdr()`, `FeeSchedule.configFor`, atau
 * `AppPreferences.tradingFees` (lihat `app/AGENTS.md` dan `FEE_TRUTH.md`).
 */
data class TradingFeeConfig(
    val buyMakerPct: Double = 0.11,
    val buyTakerPct: Double = 0.21,
    val sellMakerPct: Double = 0.32,
    val sellTakerPct: Double = 0.42
) {
    companion object {
        /** All-in Tokocrypto pair USDT/crypto — regular, tanpa TKO. */
        fun tokocryptoUsdt(): TradingFeeConfig = TradingFeeConfig(
            buyMakerPct = 0.4044,
            buyTakerPct = 0.4044,
            sellMakerPct = 0.4044,
            sellTakerPct = 0.4044
        )

        /** All-in Tokocrypto pair IDR — regular, tanpa TKO. */
        fun tokocryptoIdr(): TradingFeeConfig = TradingFeeConfig(
            buyMakerPct = 0.1222,
            buyTakerPct = 0.2222,
            sellMakerPct = 0.3322,
            sellTakerPct = 0.4322
        )

        /**
         * Pilih preset Tokocrypto menurut quote asset simbol.
         * USDT/USDC/BUSD/USD → usdt; selain itu (IDR, dll.) → idr.
         */
        fun forTokocrypto(quoteAsset: String): TradingFeeConfig {
            val q = quoteAsset.trim().uppercase()
            return when (q) {
                "USDT", "USDC", "BUSD", "USD" -> tokocryptoUsdt()
                else -> tokocryptoIdr()
            }
        }
    }
}

enum class MarketDataSource(
    val label: String,
    val shortCode: String,
    val defaultQuoteAsset: String,
    val defaultFeeConfig: TradingFeeConfig,
    val description: String
) {
    TOKOCRYPTO(
        label = "Tokocrypto",
        shortCode = "IDR",
        defaultQuoteAsset = "IDR",
        // Default all-in USDT: mayoritas pair scalping + data replay v2/v3.
        // Pair IDR: panggil TradingFeeConfig.forTokocrypto("IDR") / tokocryptoIdr().
        defaultFeeConfig = TradingFeeConfig.tokocryptoUsdt(),
        description = "Data market Tokocrypto (Pair IDR/USDT) dengan real-time REST & WebSocket"
    )
}
enum class MarketDataTransport(val label: String) { REST("REST"), WEBSOCKET("WebSocket") }

object MarketDataConfiguration {
    val transports = listOf(MarketDataTransport.REST, MarketDataTransport.WEBSOCKET)
}

/**
 * **Sumber kebenaran tunggal** untuk tarif biaya, dinyatakan sebagai **pecahan**
 * (`0.004044` = 0,4044%). Angka mengikuti `FEE_TRUTH.md`.
 *
 * Sebelum ini setiap modul punya angkanya sendiri-sendiri: mesin simulasi memakai 0,1%,
 * pencatat riwayat memakai 0,3%, dan `AppPreferences` memakai satu preset lama untuk semua
 * bursa — sehingga biaya yang dicek di awal berbeda dari yang benar-benar dipotong.
 *
 * Semua modul **wajib** mengambil tarif dari sini, bukan menulis angka sendiri.
 */
object FeeSchedule {
    // --- Tokocrypto USDT/crypto: maker = taker, all-in 0,4044% per sisi ---
    const val TOKOCRYPTO_USDT_BUY_TAKER = 0.004044
    const val TOKOCRYPTO_USDT_SELL_TAKER = 0.004044

    // --- Tokocrypto IDR (beli bebas PPh; jual kena PPh 0,21% + ICEx) ---
    const val TOKOCRYPTO_IDR_BUY_TAKER = 0.002222
    const val TOKOCRYPTO_IDR_BUY_MAKER = 0.001222
    const val TOKOCRYPTO_IDR_SELL_TAKER = 0.004322
    const val TOKOCRYPTO_IDR_SELL_MAKER = 0.003322

    /** Estimasi slippage default per sisi (%), selaras `ScalpingConfig` & `scalp_config.json`. */
    const val DEFAULT_SLIPPAGE_PCT = 0.08

    fun isUsdtQuote(quoteAsset: String): Boolean =
        quoteAsset.trim().uppercase() in setOf("USDT", "USDC", "BUSD", "USD")

    /** Tarif biaya **beli** per sisi (pecahan) untuk bursa + kuotasi tertentu. */
    fun buyRate(exchange: String, quoteAsset: String, useMaker: Boolean = false): Double =
        if (isUsdtQuote(quoteAsset)) {
            TOKOCRYPTO_USDT_BUY_TAKER
        } else {
            if (useMaker) TOKOCRYPTO_IDR_BUY_MAKER else TOKOCRYPTO_IDR_BUY_TAKER
        }

    /** Tarif biaya **jual** per sisi (pecahan) untuk bursa + kuotasi tertentu. */
    fun sellRate(exchange: String, quoteAsset: String, useMaker: Boolean = false): Double =
        if (isUsdtQuote(quoteAsset)) {
            TOKOCRYPTO_USDT_SELL_TAKER
        } else {
            if (useMaker) TOKOCRYPTO_IDR_SELL_MAKER else TOKOCRYPTO_IDR_SELL_TAKER
        }

    /** Total biaya round-trip sebagai pecahan, termasuk slippage dua sisi. */
    fun roundTripRate(
        exchange: String,
        quoteAsset: String,
        useMaker: Boolean = false,
        slippagePct: Double = DEFAULT_SLIPPAGE_PCT
    ): Double = buyRate(exchange, quoteAsset, useMaker) +
        sellRate(exchange, quoteAsset, useMaker) +
        2.0 * slippagePct / 100.0

    /**
     * Round-trip dalam persen, cara **multiplikatif** (fee dibebankan berurutan,
     * bukan dijumlahkan) — dipakai saat butuh angka biaya riil, bukan perkiraan.
     */
    fun roundTripPctMultiplicative(
        exchange: String,
        quoteAsset: String,
        useMaker: Boolean = false,
        slippagePct: Double = DEFAULT_SLIPPAGE_PCT
    ): Double {
        val buy = buyRate(exchange, quoteAsset, useMaker) + slippagePct / 100.0
        val sell = sellRate(exchange, quoteAsset, useMaker) + slippagePct / 100.0
        return ((1.0 + buy) / (1.0 - sell) - 1.0) * 100.0
    }

    /** Konfigurasi berbentuk persen untuk mesin yang memakai [TradingFeeConfig]. */
    fun configFor(exchange: String, quoteAsset: String): TradingFeeConfig =
        TradingFeeConfig.forTokocrypto(quoteAsset)

    // ─────────────────────────────────────────────────────────────────────────
    // SATU-SATUNYA RUMUS P&L (2026-10-15)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Profit **net** dalam persen setelah fee beli + fee jual + slippage dua sisi.
     *
     * Ini satu-satunya rumus P&L yang boleh dipakai di seluruh aplikasi. Sebelumnya ada empat
     * rumus berbeda untuk hal yang sama, dengan friksi berbeda-beda untuk Tokocrypto IDR:
     *
     * | Tempat | Friksi |
     * |---|---|
     * | halaman portofolio | **0%** (bruto, tanpa fee apa pun) |
     * | radar jual & kartu posisi detail | 0,3322% (hanya fee jual maker) |
     * | FSM paper trade | 0,8186% (taker + slippage dua sisi) |
     *
     * Akibatnya angka yang sama tampil berbeda di tiga tempat dan terlihat seperti bug. Basis
     * yang dipilih adalah basis FSM karena paling jujur: auto-buy dan auto-exit adalah order
     * **market**, jadi taker + slippage memang benar-benar dibayar.
     *
     * Bentuk **multiplikatif** (fee dibebankan berurutan, bukan dijumlahkan), paritas dengan
     * `PaperExitResolver` dan `AUDIT-2026-11-08.md`.
     *
     * @param buyFeePct fee beli per sisi dalam persen — pakai **taker** untuk order market
     * @param sellFeePct fee jual per sisi dalam persen — pakai **taker** untuk order market
     * @param slippagePct asumsi slippage per sisi dalam persen
     */
    fun netProfitPct(
        entry: Double,
        price: Double,
        buyFeePct: Double,
        sellFeePct: Double,
        slippagePct: Double = DEFAULT_SLIPPAGE_PCT
    ): Double {
        if (entry <= 0.0 || price <= 0.0 || !entry.isFinite() || !price.isFinite()) return 0.0
        val buyCost = 1.0 + (buyFeePct + slippagePct) / 100.0
        val sellNet = max(0.0, 1.0 - (sellFeePct + slippagePct) / 100.0)
        return ((price / entry) * sellNet / buyCost - 1.0) * 100.0
    }

    /**
     * [netProfitPct] dengan tarif diselesaikan dari bursa + kuotasi.
     *
     * Default **taker** (`useMaker = false`) — sengaja, karena itulah basis yang disepakati.
     */
    fun netProfitPctFor(
        entry: Double,
        price: Double,
        exchange: String,
        quoteAsset: String,
        useMaker: Boolean = false,
        slippagePct: Double = DEFAULT_SLIPPAGE_PCT
    ): Double = netProfitPct(
        entry = entry,
        price = price,
        buyFeePct = buyRate(exchange, quoteAsset, useMaker) * 100.0,
        sellFeePct = sellRate(exchange, quoteAsset, useMaker) * 100.0,
        slippagePct = slippagePct
    )

    /**
     * [netProfitPctFor] untuk sinyal **berarah** — BUY maupun SELL.
     *
     * [netProfitPctFor] selalu memodelkan posisi **long** (beli di `entry`, jual di `price`).
     * Sinyal `SELL` adalah cerminnya: bertaruh harga turun, jadi ia **menjual di `entry`**
     * (kena fee jual + slippage saat masuk) lalu **membeli kembali di `price`** (kena fee beli +
     * slippage saat keluar). Menukar peran `entry` dan `price` di [netProfitPct] menghasilkan
     * persis model itu — bukan pendekatan, tapi identitas aljabar:
     *
     * ```
     * long  = price·(1 − sellF − slip) / (entry·(1 + buyF + slip)) − 1
     * short = entry·(1 − sellF − slip) / (price·(1 + buyF + slip)) − 1
     * ```
     *
     * Tanpa pembeda arah, sinyal SELL akan dihitung dengan friksi terbalik — friksi beli
     * dibebankan pada kaki yang seharusnya jual.
     */
    fun netProfitPctForDirected(
        entry: Double,
        price: Double,
        isBuy: Boolean,
        exchange: String,
        quoteAsset: String,
        useMaker: Boolean = false,
        slippagePct: Double = DEFAULT_SLIPPAGE_PCT
    ): Double = if (isBuy) {
        netProfitPctFor(entry, price, exchange, quoteAsset, useMaker, slippagePct)
    } else {
        netProfitPctFor(price, entry, exchange, quoteAsset, useMaker, slippagePct)
    }

    /**
     * Biaya perolehan yang **sudah** termasuk fee beli + slippage.
     *
     * Dipakai bersama [netSellValue] supaya jumlah rupiah yang ditampilkan konsisten dengan
     * [netProfitPct]: `(netSellValue / costBasisNet - 1) * 100` menghasilkan angka yang sama
     * persis, jadi tidak mungkin lagi ada selisih antara persen dan rupiah.
     */
    fun costBasisNet(
        quantity: Double,
        entry: Double,
        buyFeePct: Double,
        slippagePct: Double = DEFAULT_SLIPPAGE_PCT
    ): Double = quantity * entry * (1.0 + (buyFeePct + slippagePct) / 100.0)

    /** Nilai jual bersih setelah fee jual + slippage. Pasangan [costBasisNet]. */
    fun netSellValue(
        quantity: Double,
        price: Double,
        sellFeePct: Double,
        slippagePct: Double = DEFAULT_SLIPPAGE_PCT
    ): Double = quantity * price * max(0.0, 1.0 - (sellFeePct + slippagePct) / 100.0)

    /** Harga impas net (profit net = 0). Kebalikan dari [netProfitPct]. */
    fun breakevenPrice(
        entry: Double,
        buyFeePct: Double,
        sellFeePct: Double,
        slippagePct: Double = DEFAULT_SLIPPAGE_PCT
    ): Double {
        if (entry <= 0.0) return 0.0
        val buyCost = 1.0 + (buyFeePct + slippagePct) / 100.0
        val sellNet = max(1e-9, 1.0 - (sellFeePct + slippagePct) / 100.0)
        return entry * buyCost / sellNet
    }
}

object FeeCalculator {
    data class Result(
        val feePct: Double,
        val slippagePct: Double,
        val totalCostPct: Double,
        val netRewardPct: Double,
        val netRiskPct: Double,
        val netRr: Double
    )

    /** Round-trip fee is buy + sell plus estimated orderbook slippage with accurate multiplicative compounding. */
    fun roundTrip(
        entry: Double,
        stopLoss: Double,
        takeProfit: Double,
        fees: TradingFeeConfig,
        useMaker: Boolean = false,
        slippagePct: Double = 0.08
    ): Result {
        if (entry <= 0.0 || stopLoss <= 0.0 || takeProfit <= 0.0) return Result(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val buyFee = if (useMaker) fees.buyMakerPct else fees.buyTakerPct
        val sellFee = if (useMaker) fees.sellMakerPct else fees.sellTakerPct
        val feePct = buyFee + sellFee
        val totalCostPct = feePct + (2 * slippagePct) // Slippage saat buy & sell

        // Multiplicative Net Reward & Net Risk
        val buyCostFactor = 1.0 + (buyFee + slippagePct) / 100.0
        val sellNetFactor = (1.0 - (sellFee + slippagePct) / 100.0).coerceAtLeast(0.0)
        val netRewardPct = ((takeProfit / entry * sellNetFactor / buyCostFactor) - 1.0) * 100.0
        val netRiskPct = (1.0 - (stopLoss / entry * sellNetFactor / buyCostFactor)) * 100.0

        val rr = if (netRiskPct > 0.0) (netRewardPct / netRiskPct).coerceAtLeast(0.0) else 0.0
        return Result(feePct, slippagePct, totalCostPct, netRewardPct, netRiskPct, rr)
    }
}

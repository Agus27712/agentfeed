package agu.analys.model

import agu.analys.util.PriceFormatter

/**
 * Aturan kuotasi per bursa — **satu sumber kebenaran** untuk validasi & normalisasi simbol.
 *
 * Mengikuti `AGENTS.md`:
 * - **Tokocrypto**: USDT/USDC/BUSD/USD (kuotasi kripto) + IDR/IDRT (rupiah).
 * - **BIDR dieliminasi** (dipetakan ke IDR), kecuali pair koin yang memang bernama
 *   `…BIDR` seperti `BNBIDR` / `SHIBIDR` yang dipertahankan apa adanya.
 */
object PairRules {

    val USDT_FAMILY = setOf("USDT", "USDC", "BUSD", "USD")
    val IDR_FAMILY = setOf("IDR", "IDRT", "BIDR")

    /** Pair BIDR yang sengaja dipertahankan (koinnya memang bernama `…BIDR`). */
    val BIDR_KEEP = setOf("BNBIDR", "SHIBIDR")

    fun isUsdtFamily(quote: String): Boolean = quote.trim().uppercase() in USDT_FAMILY

    /** Kuotasi sah untuk sebuah bursa (lihat aturan di atas). */
    fun isQuoteAllowedForExchange(exchange: String?, quote: String): Boolean {
        val q = quote.trim().uppercase()
        return q in USDT_FAMILY || q == "IDR" || q == "IDRT" || q == "BIDR"
    }

    /**
     * Eliminasi kuotasi BIDR: `XXXBIDR` → `XXXIDR`, kecuali [BIDR_KEEP] (`BNBIDR`/`SHIBIDR`).
     * Simbol tanpa akhiran BIDR dikembalikan apa adanya (setelah normalisasi dasar).
     */
    fun normalizeBidr(symbol: String): String {
        val s = symbol.trim().uppercase().replace("/", "").replace("-", "").replace("_", "").replace(" ", "")
        if (!s.endsWith("BIDR")) return s
        if (s in BIDR_KEEP) return s
        return s.removeSuffix("BIDR") + "IDR"
    }

    /**
     * Bersihkan simbol agar sah untuk bursa: eliminasi BIDR lalu periksa kuotasinya.
     *
     * @return simbol hasil bersih, atau `null` bila kombinasi bursa+kuotasi mustahil.
     */
    fun sanitizeSymbolForExchange(symbol: String, exchange: String?): String? {
        val s = normalizeBidr(symbol)
        val finalQuote = PriceFormatter.extractQuote(s)
        return if (isQuoteAllowedForExchange(exchange, finalQuote)) s else null
    }
}

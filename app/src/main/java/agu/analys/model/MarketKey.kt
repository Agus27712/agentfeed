package agu.analys.model

import java.io.Serializable

/**
 * Model identitas pasar tunggal yang memuat Bursa (Exchange), Simbol (Symbol), dan Kuotasi (Quote).
 * Menghilangkan tebak-tebakan kuotasi dan bursa dari string simbol.
 */
data class MarketKey(
    val exchange: String, // Bursa: Tokocrypto
    val symbol: String,   // e.g. "BTCUSDT", "BTCIDR"
    val quote: String     // e.g. "USDT", "IDR", "USDC", "BIDR"
) : Serializable {

    val key: String
        get() = "${exchange.uppercase().trim()}_${symbol.uppercase().trim()}"

    val base: String
        get() = if (symbol.uppercase().endsWith(quote.uppercase())) {
            symbol.substring(0, symbol.length - quote.length)
        } else {
            symbol
        }

    fun formattedPair(): String = "$base/$quote"

    val displayExchange: String
        get() = when (exchange.uppercase().trim()) {
            "TOKOCRYPTO" -> "Tokocrypto"
            else -> exchange
        }

    val isUsdtQuote: Boolean
        get() = quote.equals("USDT", true) || quote.equals("USD", true) ||
                quote.equals("USDC", true) || quote.equals("BUSD", true)

    val isIdrQuote: Boolean
        get() = quote.equals("IDR", true) || quote.equals("BIDR", true) ||
                quote.equals("IDRT", true)

    val isTokocrypto: Boolean
        get() = exchange.equals("TOKOCRYPTO", true)

    /**
     * Menghasilkan notification ID unik per (exchange, symbol, isReal).
     * Mencegah tumpang tindih notifikasi antar bursa dan mode real/simulasi.
     */
    fun toNotificationId(isReal: Boolean, offset: Int = 0): Int {
        val exNorm = exchange.uppercase().trim()
        val symNorm = symbol.uppercase().trim()
        val realMultiplier = if (isReal) 1 else 2
        val hash = (exNorm.hashCode() * 31 + symNorm.hashCode()) xor (realMultiplier * 100003)
        return ((hash and 0x3FFFFFFF) + offset) and 0x7FFFFFFF
    }

    fun toStorageKey(isReal: Boolean): String {
        val exNorm = exchange.lowercase().trim()
        val symNorm = symbol.uppercase().trim()
        return "${exNorm}_${if (isReal) "real" else "sim"}_${symNorm}"
    }

    companion object {
        fun resolve(symbol: String, exchange: String = "TOKOCRYPTO", quote: String? = null): MarketKey =
            create(exchange, symbol, quote)

        fun create(exchange: String, symbol: String, quote: String? = null): MarketKey {
            val ex = exchange.uppercase().trim().ifBlank { "TOKOCRYPTO" }
            // Eliminasi kuotasi BIDR (XXXBIDR → XXXIDR), kecuali BNBIDR/SHIBIDR.
            val sym = PairRules.normalizeBidr(symbol)
            val qRaw = if (!quote.isNullOrBlank()) {
                quote.uppercase().trim()
            } else {
                resolveQuote(sym, ex)
            }
            return MarketKey(exchange = ex, symbol = sym, quote = qRaw)
        }

        fun fromPair(pair: TradingPair, exchange: String = "TOKOCRYPTO"): MarketKey {
            val ex = exchange.uppercase().trim().ifBlank { "TOKOCRYPTO" }
            val sym = PairRules.normalizeBidr(pair.symbol)
            val q = pair.quoteAsset.uppercase().trim().ifBlank { resolveQuote(sym, ex) }
            return MarketKey(exchange = ex, symbol = sym, quote = q)
        }

        fun resolveQuote(symbol: String, exchange: String): String {
            val s = symbol.uppercase().replace("/", "").replace("-", "").replace("_", "").trim()
            val tokoInfo = agu.analys.data.TokocryptoSymbolRepository.getSymbolInfo(s)
            if (tokoInfo != null) return tokoInfo.quoteAsset
            val tokoMatch = TradingPair.POPULAR_TOKOCRYPTO_PAIRS.find { it.symbol.equals(s, ignoreCase = true) }
            if (tokoMatch != null) return tokoMatch.quoteAsset
            val idrMatch = TradingPair.POPULAR_IDR_PAIRS.find { it.symbol.equals(s, ignoreCase = true) }
            if (idrMatch != null) return idrMatch.quoteAsset
            return when {
                s.endsWith("USDT") -> "USDT"
                s.endsWith("USDC") -> "USDC"
                s.endsWith("BUSD") -> "BUSD"
                // BIDR hanya dipertahankan untuk koin yang memang bernama …BIDR.
                s.endsWith("BIDR") -> if (s in PairRules.BIDR_KEEP) "BIDR" else "IDR"
                s.endsWith("IDR") -> "IDR"
                else -> "USDT"
            }
        }
    }
}

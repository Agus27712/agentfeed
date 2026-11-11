package agu.analys.data

import agu.analys.model.TokocryptoLotSizeFilter
import agu.analys.model.TokocryptoMinNotionalFilter
import agu.analys.model.TokocryptoPriceFilter
import agu.analys.model.TokocryptoSymbolInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parser murni untuk daftar simbol resmi Tokocrypto.
 *
 * Bentuk resmi (`GET /open/v1/common/symbols`, diukur 2026-10-17):
 * `{"code":0,"data":{"list":[{ "type":1, "symbol":"BTC_USDT", ... }]}}`
 *
 * Endpoint gateway (`tokocrypto.site/api/v3/exchangeInfo`) memakai bentuk
 * `{"symbols":[...]}` dan memuat **seluruh universe Binance** (3.729 simbol). Bentuk itu tetap
 * bisa dibaca untuk kompatibilitas, tetapi [isPlausible] menolaknya sebagai sumber daftar pair.
 */
object TokocryptoSymbolFeed {

    /** Jumlah simbol wajar untuk Tokocrypto (resmi: 864 pada 2026-10-17). */
    const val MIN_PLAUSIBLE_SYMBOLS = 100
    const val MAX_PLAUSIBLE_SYMBOLS = 1_500

    /** Minimal bagian snapshot lama yang harus masih ada agar pembaruan diterima. */
    private const val MIN_OVERLAP_RATIO = 0.90

    private val ALLOWED_QUOTES = setOf("IDR", "USDT")

    /** Mengambil array simbol dari ketiga bentuk respons yang dikenal. */
    fun extractList(root: JSONObject): JSONArray? {
        root.optJSONObject("data")?.optJSONArray("list")?.let { return it }
        root.optJSONArray("data")?.let { return it }
        root.optJSONArray("symbols")?.let { return it }
        return null
    }

    /**
     * Mengurai satu array simbol menjadi daftar unik (satu entri per `symbol`).
     * Hanya quote IDR/USDT yang diterima, sesuai batas aplikasi.
     */
    fun parse(dataArray: JSONArray): List<TokocryptoSymbolInfo> {
        val bySymbol = LinkedHashMap<String, TokocryptoSymbolInfo>()
        for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i) ?: continue
            val info = parseOne(item) ?: continue
            bySymbol.putIfAbsent(info.symbol, info)
        }
        return bySymbol.values.toList()
    }

    /**
     * Alasan penolakan, atau `null` bila daftar layak menggantikan snapshot.
     *
     * Aturan:
     *  - jumlah 100–1.500 (universe Binance 3.729 langsung tertolak);
     *  - minimal satu pair IDR;
     *  - bila sudah ada snapshot resmi (≥100 simbol), ≥90% di antaranya masih ada.
     */
    fun rejectionReason(
        incoming: List<TokocryptoSymbolInfo>,
        previousOfficialSymbols: Set<String>
    ): String? {
        if (incoming.size < MIN_PLAUSIBLE_SYMBOLS) {
            return "jumlah simbol terlalu sedikit (${incoming.size})"
        }
        if (incoming.size > MAX_PLAUSIBLE_SYMBOLS) {
            return "jumlah simbol terlalu banyak (${incoming.size})"
        }
        if (incoming.none { it.quoteAsset == "IDR" }) {
            return "tidak ada pair IDR"
        }
        if (previousOfficialSymbols.size >= MIN_PLAUSIBLE_SYMBOLS) {
            val overlap = incoming.count { it.symbol in previousOfficialSymbols }
            val ratio = overlap.toDouble() / previousOfficialSymbols.size
            if (ratio < MIN_OVERLAP_RATIO) {
                return "tumpang tindih dengan snapshot lama hanya ${"%.1f".format(ratio * 100)}%"
            }
        }
        return null
    }

    private fun parseOne(item: JSONObject): TokocryptoSymbolInfo? {
        val rawSymbol = item.optString("symbol", "").trim().uppercase()
        if (rawSymbol.isBlank()) return null

        var baseAsset = item.optString("baseAsset", "").trim().uppercase()
        var quoteAsset = item.optString("quoteAsset", "").trim().uppercase()

        if (baseAsset.isBlank() || quoteAsset.isBlank()) {
            val cleanSym = rawSymbol.replace("_", "").replace("-", "").replace("/", "")
            when {
                cleanSym.endsWith("IDR") -> {
                    baseAsset = cleanSym.removeSuffix("IDR")
                    quoteAsset = "IDR"
                }
                cleanSym.endsWith("USDT") -> {
                    baseAsset = cleanSym.removeSuffix("USDT")
                    quoteAsset = "USDT"
                }
            }
        }

        if (quoteAsset !in ALLOWED_QUOTES) return null

        val symbolType = item.optInt("type", item.optInt("symbolType", 1))
        val basePrecision = item.optInt("basePrecision", item.optInt("baseAssetPrecision", 8))
        val quotePrecision = item.optInt("quotePrecision", item.optInt("quoteAssetPrecision", 8))
        val spotTradingEnable = readSpotTradingEnable(item)
        val defaultStp = item.optString("defaultSelfTradePreventionMode", "NONE")

        var priceFilter: TokocryptoPriceFilter? = null
        var lotSizeFilter: TokocryptoLotSizeFilter? = null
        var marketLotSizeFilter: TokocryptoLotSizeFilter? = null
        var minNotionalFilter: TokocryptoMinNotionalFilter? = null

        val filtersArray = item.optJSONArray("filters")
        if (filtersArray != null) {
            for (f in 0 until filtersArray.length()) {
                val fObj = filtersArray.optJSONObject(f) ?: continue
                when (fObj.optString("filterType", "")) {
                    "PRICE_FILTER" -> {
                        priceFilter = TokocryptoPriceFilter(
                            minPrice = fObj.optString("minPrice", "0").toDoubleOrNull() ?: 0.0,
                            maxPrice = fObj.optString("maxPrice", "0").toDoubleOrNull() ?: Double.MAX_VALUE,
                            tickSize = fObj.optString("tickSize", "0").toDoubleOrNull() ?: 0.0
                        )
                    }
                    "LOT_SIZE" -> {
                        lotSizeFilter = TokocryptoLotSizeFilter(
                            minQty = fObj.optString("minQty", "0").toDoubleOrNull() ?: 0.0,
                            maxQty = fObj.optString("maxQty", "0").toDoubleOrNull() ?: Double.MAX_VALUE,
                            stepSize = fObj.optString("stepSize", "0").toDoubleOrNull() ?: 0.0
                        )
                    }
                    "MARKET_LOT_SIZE" -> {
                        marketLotSizeFilter = TokocryptoLotSizeFilter(
                            minQty = fObj.optString("minQty", "0").toDoubleOrNull() ?: 0.0,
                            maxQty = fObj.optString("maxQty", "0").toDoubleOrNull() ?: Double.MAX_VALUE,
                            stepSize = fObj.optString("stepSize", "0").toDoubleOrNull() ?: 0.0
                        )
                    }
                    "NOTIONAL", "MIN_NOTIONAL" -> {
                        minNotionalFilter = TokocryptoMinNotionalFilter(
                            minNotional = fObj.optString("minNotional", "0").toDoubleOrNull() ?: 0.0,
                            applyToMarket = fObj.optBoolean("applyToMarket", true),
                            avgPriceMins = fObj.optInt("avgPriceMins", 5)
                        )
                    }
                }
            }
        }

        return TokocryptoSymbolInfo(
            symbol = rawSymbol,
            baseAsset = baseAsset,
            quoteAsset = quoteAsset,
            symbolType = symbolType,
            basePrecision = basePrecision,
            quotePrecision = quotePrecision,
            spotTradingEnable = spotTradingEnable,
            defaultSelfTradePreventionMode = defaultStp,
            priceFilter = priceFilter,
            lotSizeFilter = lotSizeFilter,
            marketLotSizeFilter = marketLotSizeFilter,
            minNotionalFilter = minNotionalFilter
        )
    }

    /**
     * `spotTradingEnable` resmi bertipe **angka** (1/0), bukan boolean. Field yang tidak dikenal
     * diperlakukan **tidak aktif** (fail-closed), bukan aktif.
     */
    fun readSpotTradingEnable(item: JSONObject): Boolean {
        val status = item.optString("status", "")
        if (status.isNotBlank() && !status.equals("TRADING", ignoreCase = true)) return false
        if (!item.has("spotTradingEnable")) return status.equals("TRADING", ignoreCase = true)
        return truthy(item.opt("spotTradingEnable"))
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.trim().equals("1", true) || value.trim().equals("true", true)
        else -> false
    }
}

package agu.analys.util

import agu.analys.config.MarketDataSource
import agu.analys.data.TokocryptoSymbolRepository
import agu.analys.model.MarketTick
import agu.analys.service.TokocryptoMarketService

/**
 * Peringkat dashboard yang adil untuk pair IDR dan USDT.
 * Volume USDT dikonversi ke IDR, tidak ada threshold harga absolut untuk USDT.
 */
object DashboardRanking {
    /** Jumlah pair per halaman dashboard (load bertahap saat scroll). */
    const val PAGE_SIZE = 15

    /**
     * Kurs USDT/IDR hanya dari exchange (ExchangeRateManager). Tanpa kurs valid hasilnya 0,
     * artinya volume pair USDT belum bisa dibandingkan dengan pair IDR (bukan ditebak).
     */
    fun safeRate(rate: Double): Double = if (rate > 1000.0) rate else 0.0

    fun isUsdtQuote(symbol: String): Boolean =
        symbol.uppercase().replace("_", "").replace("/", "").endsWith("USDT")

    /** Volume 24 jam dalam IDR. Pair USDT dikali kurs, pair IDR apa adanya. */
    fun volumeInIdr(symbol: String, volume24h: Double, usdtIdrRate: Double): Double =
        if (isUsdtQuote(symbol)) volume24h * safeRate(usdtIdrRate) else volume24h

    /** Floor harga hanya untuk quote IDR (harga sangat kecil dalam rupiah = koin mati). */
    fun passesPriceFloor(tick: MarketTick): Boolean =
        tick.price > 0.0 && (isUsdtQuote(tick.symbol) || tick.price > 5.0)

    /** Urut volume 24 jam tertinggi (dinormalisasi ke IDR), satu entri per simbol. */
    fun rankByVolume(
        ticks: Collection<MarketTick>,
        usdtIdrRate: Double,
        accept: (MarketTick) -> Boolean = { true }
    ): List<MarketTick> = ticks
        .asSequence()
        .filter(accept)
        .distinctBy { it.symbol.uppercase().replace("_", "") }
        .sortedByDescending { volumeInIdr(it.symbol, it.volume24h, usdtIdrRate) }
        .toList()

    private val EXCLUDED_STABLE_FIAT_SYMBOLS = setOf(
        "USDTIDR", "USDCIDR", "USDCUSDT", "USDTUSDC", "BUSDIDR", "BUSDUSDT",
        "TUSDUSDT", "TUSDIDR", "FDUSDUSDT", "FDUSDIDR", "EURUSDT", "GBPUSDT", "AUDUSDT",
        "DAIIDR", "DAIUSDT", "IDRTIDR", "IDRTUSDT"
    )

    /**
     * Gate **listing** untuk tampilan dashboard (temuan K14).
     *
     * Digantungkan pada satu fungsi supaya semua cabang dashboard (daftar fokus, daftar
     * tick, fallback statis) memakai syarat yang sama, bukan salinan syarat per cabang:
     * - **Tokocrypto** — satu-satunya bursa yang didukung; hanya simbol berstatus TRADING di
     *   discovery resmi yang lolos. Simbol delisted, tak dikenal, maupun sampah dari watchlist
     *   lama gugur (fail-closed) sehingga tidak tampil sebagai kartu bernilai `-`.
     */
    fun symbolListed(source: MarketDataSource, symbol: String): Boolean =
        when (source) {
            MarketDataSource.TOKOCRYPTO -> TokocryptoSymbolRepository.isSpotTradingEnabled(symbol)
        }

    /** Pair layak tampil di dashboard untuk bursa aktif (bukan stablecoin/fiat). */
    fun isRankable(source: MarketDataSource, tick: MarketTick): Boolean {
        val sym = tick.symbol.uppercase().replace("_", "").replace("/", "").replace("-", "")
        if (tick.price <= 0.0) return false
        if (EXCLUDED_STABLE_FIAT_SYMBOLS.contains(sym)) return false
        if (sym.startsWith("USDT") && sym.endsWith("IDR")) return false
        if (sym.startsWith("USDC") || sym.startsWith("DAI") || sym.startsWith("BUSD") || sym.startsWith("TUSD") || sym.startsWith("FDUSD")) {
            if (sym.endsWith("IDR") || sym.endsWith("USDT")) return false
        }
        return if (source == MarketDataSource.TOKOCRYPTO) {
            TokocryptoMarketService.isIdrOrUsdtPair(sym) &&
                // Fail-closed: hanya simbol berstatus TRADING di discovery resmi yang boleh tampil.
                // Simbol delisted / tak dikenal (getSymbolInfo == null) langsung gugur di sini.
                TokocryptoSymbolRepository.isSpotTradingEnabled(sym) &&
                TokocryptoMarketService.isSafeTradableAsset(
                    price = tick.price,
                    volume24h = tick.volume24h,
                    high24h = tick.high24h,
                    low24h = tick.low24h,
                    isIdrPair = sym.endsWith("IDR")
                )
        } else {
            !sym.contains("USDC") && !sym.contains("DAI") &&
                TokocryptoMarketService.isSafeTradableAsset(
                    price = tick.price,
                    volume24h = tick.volume24h,
                    high24h = tick.high24h,
                    low24h = tick.low24h,
                    isIdrPair = sym.endsWith("IDR")
                )
        }
    }
}

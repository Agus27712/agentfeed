package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import agu.analys.config.MarketDataSource
import agu.analys.data.TokocryptoSymbolRepository
import agu.analys.util.AppPreferences
import agu.analys.util.MtfCacheManager
import agu.analys.util.WatchlistEntryGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WatchlistViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    private val _watchlist = MutableStateFlow<Set<String>>(emptySet())
    val watchlist: StateFlow<Set<String>> = _watchlist.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    var onWatchlistUpdated: (() -> Unit)? = null

    init {
        val defaultSym = getDefaultSymbol()
        val initialWatchlist = prefs.getWatchlist().ifEmpty {
            prefs.toggleWatchlist(defaultSym)
            setOf(defaultSym)
        }
        _watchlist.value = initialWatchlist
        _favorites.value = prefs.getFavorites()
        MtfCacheManager.updateQueues(initialWatchlist.toList(), emptyList(), prefs.marketDataSource.name)
    }

    fun getDefaultSymbol(source: MarketDataSource = prefs.marketDataSource): String {
        return if (source == MarketDataSource.TOKOCRYPTO) "BTCUSDT" else "BTCIDR"
    }

    fun reloadForDataSource(source: MarketDataSource) {
        val current = prefs.getWatchlist(source).ifEmpty {
            val def = getDefaultSymbol(source)
            setOf(def)
        }
        _watchlist.value = current
        _favorites.value = prefs.getFavorites(source)
        MtfCacheManager.updateQueues(current.toList(), emptyList(), source.name)
        onWatchlistUpdated?.invoke()
    }

    private fun normalize(symbol: String): String =
        symbol.uppercase().trim().replace("/", "").replace("_", "")

    /**
     * Keberadaan simbol di repository bursa aktif — dipakai [WatchlistEntryGate].
     * Fail-closed: simbol yang tidak bisa dibuktikan ada di bursa aktif ditolak.
     */
    private fun isKnownSymbol(symbol: String): Boolean =
        when (prefs.marketDataSource) {
            MarketDataSource.TOKOCRYPTO -> TokocryptoSymbolRepository.hasSymbol(symbol)
        }

    /**
     * True bila [symbol] boleh ditambahkan ke watchlist bursa aktif (temuan K13).
     *
     * Menghapus simbol tidak digate — membersihkan daftar harus selalu bisa.
     */
    fun canAddToWatchlist(symbol: String): Boolean =
        WatchlistEntryGate.checkAdd(symbol, prefs.marketDataSource, ::isKnownSymbol).accepted

    fun toggleWatchlist(symbol: String) {
        val upper = normalize(symbol)
        if (upper.isBlank()) return
        val alreadyWatched = prefs.getWatchlist().contains(upper)
        if (!alreadyWatched && !canAddToWatchlist(upper)) return
        prefs.toggleWatchlist(upper)
        val defaultSym = getDefaultSymbol()
        val current = prefs.getWatchlist().ifEmpty { setOf(defaultSym) }
        _watchlist.value = current
        MtfCacheManager.updateQueues(current.toList(), emptyList(), prefs.marketDataSource.name)
        onWatchlistUpdated?.invoke()
    }

    fun addToWatchlist(symbol: String) {
        val upper = normalize(symbol)
        if (upper.isBlank()) return
        if (!canAddToWatchlist(upper)) return
        val current = _watchlist.value.toMutableSet()
        current.add(upper)
        prefs.setWatchlist(current)
        _watchlist.value = current
        MtfCacheManager.updateQueues(current.toList(), emptyList(), prefs.marketDataSource.name)
        onWatchlistUpdated?.invoke()
    }

    fun removeFromWatchlist(symbol: String) {
        val upper = normalize(symbol)
        val current = _watchlist.value.toMutableSet()
        current.remove(upper)
        val defaultSym = getDefaultSymbol()
        val finalSet = if (current.isEmpty()) setOf(defaultSym) else current
        prefs.setWatchlist(finalSet)
        _watchlist.value = finalSet
        MtfCacheManager.updateQueues(finalSet.toList(), emptyList(), prefs.marketDataSource.name)
        onWatchlistUpdated?.invoke()
    }

    fun setCustomWatchlist(symbols: Collection<String>) {
        // K13: entri yang tidak ada di bursa aktif dibuang (fail-closed), bukan disimpan
        // sebagai simbol hantu. Preset sebagian besar sah; yang tidak ada di bursa cukup gugur.
        val upper = symbols
            .map { normalize(it) }
            .filter { it.isNotBlank() && canAddToWatchlist(it) }
            .toSet()
        val defaultSym = getDefaultSymbol()
        val finalSet = if (upper.isEmpty()) setOf(defaultSym) else upper
        prefs.setWatchlist(finalSet)
        _watchlist.value = finalSet
        MtfCacheManager.updateQueues(finalSet.toList(), emptyList(), prefs.marketDataSource.name)
        onWatchlistUpdated?.invoke()
    }

    fun applyWatchlistPreset(presetType: String, source: MarketDataSource = prefs.marketDataSource) {
        val quote = if (source == MarketDataSource.TOKOCRYPTO) "USDT" else "IDR"
        val pairs = when (presetType.lowercase()) {
            "top10", "top_10" -> listOf("BTC", "ETH", "SOL", "BNB", "XRP", "ADA", "DOGE", "AVAX", "SUI", "NEAR").map { "$it$quote" }
            "scalp", "scalping", "gems" -> listOf("PEPE", "DOGE", "SHIB", "SUI", "SOL", "FLOKI", "BONK").map { "$it$quote" }
            "ai", "web3" -> listOf("NEAR", "RENDER", "FET", "GRT", "ICP", "FIL").map { "$it$quote" }
            "layer1", "l1" -> listOf("BTC", "ETH", "SOL", "ADA", "AVAX", "DOT", "SUI", "ATOM").map { "$it$quote" }
            else -> listOf("BTC", "ETH", "SOL", "DOGE").map { "$it$quote" }
        }
        setCustomWatchlist(pairs)
    }

    fun toggleFavorite(symbol: String) {
        val upper = normalize(symbol)
        if (upper.isBlank()) return
        prefs.toggleFavorite(upper)
        _favorites.value = prefs.getFavorites()
        onWatchlistUpdated?.invoke()
    }

    fun isFavorite(symbol: String): Boolean {
        return _favorites.value.contains(normalize(symbol))
    }

    fun isWatched(symbol: String): Boolean {
        return _watchlist.value.contains(normalize(symbol))
    }
}

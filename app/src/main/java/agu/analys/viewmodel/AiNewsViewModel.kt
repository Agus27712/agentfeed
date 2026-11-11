package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import agu.analys.config.AiProvider
import agu.analys.model.AISignalState
import agu.analys.model.MarketTick
import agu.analys.model.NewsScreenerUiState
import agu.analys.model.TechnicalIndicators
import agu.analys.model.TradingPair
import agu.analys.service.GeminiAiService
import agu.analys.service.NewsAiScreenerService
import agu.analys.service.NewsRssFeedService
import agu.analys.util.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AiNewsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    private val _newsScreenerState = MutableStateFlow<NewsScreenerUiState>(NewsScreenerUiState.Idle)
    val newsScreenerState: StateFlow<NewsScreenerUiState> = _newsScreenerState.asStateFlow()

    private val _auditReportText = MutableStateFlow<String?>(null)
    val auditReportText: StateFlow<String?> = _auditReportText.asStateFlow()

    private val _isAuditLoading = MutableStateFlow(false)
    val isAuditLoading: StateFlow<Boolean> = _isAuditLoading.asStateFlow()

    private val _geminiSummaryText = MutableStateFlow<String?>(null)
    val geminiSummaryText: StateFlow<String?> = _geminiSummaryText.asStateFlow()

    private val _isGeminiLoading = MutableStateFlow(false)
    val isGeminiLoading: StateFlow<Boolean> = _isGeminiLoading.asStateFlow()

    fun fetchAiNewsScreening(
        geminiKey: String = "",
        forceRefresh: Boolean = false,
        currentLiveTicks: Map<String, MarketTick> = emptyMap()
    ) {
        viewModelScope.launch {
            _newsScreenerState.value = NewsScreenerUiState.Loading("Mengambil & menganalisa feed berita kripto terbaru...")
            try {
                val effectiveGemini = geminiKey.ifBlank { prefs.geminiApiKey }
                val provider = prefs.aiProvider

                val articles = NewsRssFeedService.fetchAggregatedNews(forceRefresh = forceRefresh)
                if (articles.isEmpty()) {
                    _newsScreenerState.value = NewsScreenerUiState.Error("Tidak ada headline berita yang berhasil dimuat dari feed RSS.")
                    return@launch
                }

                // 1. Dapatkan live ticks: dari parameter, cache, atau fetch summaries Tokocrypto
                var ticks = currentLiveTicks
                if (ticks.isEmpty()) {
                    val cached = agu.analys.util.MarketDataCache(getApplication()).loadDashboardTicks(agu.analys.config.MarketDataSource.TOKOCRYPTO)
                    if (cached.isNotEmpty()) {
                        ticks = cached
                    }
                }

                val dynamicBases = mutableSetOf<String>()
                dynamicBases.addAll(TradingPair.POPULAR_IDR_PAIRS.map { it.baseAsset.uppercase() })
                val metadata = agu.analys.util.MarketDataCache(getApplication()).loadPairsMetadata(agu.analys.config.MarketDataSource.TOKOCRYPTO)
                if (metadata.isNotEmpty()) {
                    dynamicBases.addAll(metadata.map { it.baseCurrency.uppercase() })
                }

                // Jika ticks masih kosong atau kurang lengkap, fetch ranking & summaries langsung dari Tokocrypto
                if (ticks.isEmpty() || ticks.size < 10) {
                    try {
                        val rankings = agu.analys.service.TokocryptoMarketService.fetchMarketRankings(limit = 100)
                        if (rankings.allTicks.isNotEmpty()) {
                            ticks = ticks + rankings.allTicks
                            dynamicBases.addAll(rankings.allTicks.values.map {
                                it.symbol.removeSuffix("IDR").removeSuffix("_idr").uppercase()
                            })
                        }
                    } catch (e: Exception) {
                        timber.log.Timber.w(e, "Gagal fetch live ticks untuk AI screener")
                    }
                } else {
                    dynamicBases.addAll(ticks.values.map {
                        it.symbol.removeSuffix("IDR").removeSuffix("_idr").uppercase()
                    })
                }

                val result = NewsAiScreenerService.screenCoinsFromNews(
                    articles = articles,
                    validBases = dynamicBases,
                    liveTicks = ticks,
                    preferredProvider = provider,
                    geminiApiKey = effectiveGemini
                )
                _newsScreenerState.value = NewsScreenerUiState.Success(result)
            } catch (e: Exception) {
                _newsScreenerState.value = NewsScreenerUiState.Error("Gagal screening berita: ${e.message}")
            }
        }
    }

    fun clearNewsScreenerState() {
        _newsScreenerState.value = NewsScreenerUiState.Idle
    }

    fun requestDeepAiAudit(
        tick: MarketTick,
        indicators: TechnicalIndicators,
        signal: AISignalState,
        geminiApiKey: String? = null
    ) {
        requestGeminiChartSummary(tick, indicators, signal, geminiApiKey)
    }

    fun clearAuditReport() {
        _auditReportText.value = null
    }

    fun requestGeminiChartSummary(
        tick: MarketTick,
        indicators: TechnicalIndicators,
        signal: AISignalState,
        geminiApiKey: String? = null
    ) {
        if (_isAuditLoading.value || _isGeminiLoading.value) return
        viewModelScope.launch {
            _isGeminiLoading.value = true
            _geminiSummaryText.value = null
            try {
                val effectiveKey = geminiApiKey?.ifBlank { null } ?: prefs.geminiApiKey
                _geminiSummaryText.value = GeminiAiService.generateChartSummary24h(
                    effectiveKey, tick, indicators, signal
                )
            } catch (e: Exception) {
                _geminiSummaryText.value = "Gagal analisa AI: ${e.message}"
            } finally {
                _isGeminiLoading.value = false
            }
        }
    }

    fun clearGeminiSummary() {
        _geminiSummaryText.value = null
    }

    fun summarizeCurrentMarketWithAi(symbol: String, price: Double, rsi: Double, ema20: Double, reasoning: List<String>) {
        viewModelScope.launch {
            _isGeminiLoading.value = true
            _geminiSummaryText.value = null
            try {
                val geminiKey = prefs.geminiApiKey

                val prompt = """
                    Analisa koin: $symbol
                    Harga saat ini: Rp ${String.format(java.util.Locale.US, "%,.0f", price)}
                    RSI M1: ${String.format(java.util.Locale.US, "%.2f", rsi)}
                    EMA20 M1: ${String.format(java.util.Locale.US, "%,.0f", ema20)}
                    Pemicu Masuk/Tahan: ${reasoning.joinToString("; ")}
                    
                    Tolong berikan rekomendasi ringkas (maksimal 3 kalimat) dengan gaya seorang trader ahli profesional: apakah aman beli sekarang, atau tunggu pullback. Tulis dalam bahasa Indonesia yang lugas dan tajam.
                """.trimIndent()

                val summary = GeminiAiService.generateSimpleText(geminiKey, prompt)
                _geminiSummaryText.value = summary
            } catch (e: Exception) {
                _geminiSummaryText.value = "Gagal analisa AI: ${e.message}"
            } finally {
                _isGeminiLoading.value = false
            }
        }
    }
}

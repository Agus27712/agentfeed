package agu.analys.model

data class NewsArticle(
    val title: String,
    val source: String,
    val link: String = "",
    val publishedAtMs: Long = System.currentTimeMillis()
) {
    fun getRelativeTime(): String {
        val diff = (System.currentTimeMillis() - publishedAtMs).coerceAtLeast(0L)
        val minutes = diff / (60 * 1000L)
        val hours = diff / (60 * 60 * 1000L)
        return when {
            minutes < 5 -> "Baru saja"
            minutes < 60 -> "$minutes mnt lalu"
            hours < 24 -> "$hours jam lalu"
            else -> "${hours / 24} hr lalu"
        }
    }

    fun isFresh(maxAgeHours: Int = 24): Boolean {
        val ageMs = System.currentTimeMillis() - publishedAtMs
        return ageMs <= maxAgeHours * 60 * 60 * 1000L
    }
}

data class ScreenerCoinPick(
    val baseSymbol: String, // e.g. "SOL", "SUI", "BTC"
    val pairSymbol: String, // e.g. "SOLIDR" or "SOL/IDR"
    val altPair: String, // e.g. "sol_idr"
    val sentimentGrade: String, // e.g. "Sangat Kuat", "Menengah"
    val sectorNarrative: String, // e.g. "Layer 1 / DeFi Ecosystem"
    val mainCatalyst: String, // Headline catalyst event
    val reasons: List<String>, // Bullet points of reasons for potential upside
    val validityGrade: String = "Tinggi", // "Tinggi" / "Sedang"
    val currentPrice: Double = 0.0,
    val change24h: Double = 0.0,
    val volume24h: Double = 0.0
)

data class NewsScreenerResult(
    val picks: List<ScreenerCoinPick>,
    val rawAnalysis: String,
    val articlesAnalyzed: List<NewsArticle>,
    val providerUsed: String,
    val modelUsed: String,
    val timestampMs: Long = System.currentTimeMillis()
)

sealed class NewsScreenerUiState {
    object Idle : NewsScreenerUiState()
    data class Loading(val stage: String) : NewsScreenerUiState()
    data class Success(val result: NewsScreenerResult) : NewsScreenerUiState()
    data class Error(val message: String) : NewsScreenerUiState()
}

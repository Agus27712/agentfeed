package agu.analys.domain.model

enum class DomainSignalAction {
    BUY,
    SELL,
    HOLD
}

/**
 * Model data sinyal kecerdasan buatan (AI Signal) tingkat domain.
 */
data class DomainAiSignal(
    val symbol: String,
    val action: DomainSignalAction,
    val confidence: Int, // Skor keyakinan 0-100
    val sentiment: String, // e.g., "BULLISH_REVERSAL", "BEARISH_REJECTION"
    val reasoning: List<String>, // Alasan dan indikator pemicu konfluensi
    val timestamp: Long
)

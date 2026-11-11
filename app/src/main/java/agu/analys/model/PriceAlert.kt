package agu.analys.model

enum class PriceAlertType(val label: String, val description: String) {
    PRICE_ABOVE("Harga Di Atas (Target/TP)", "Picu alert saat harga naik menyentuh atau melebihi"),
    PRICE_BELOW("Harga Di Bawah (Support/SL)", "Picu alert saat harga turun menyentuh atau di bawah"),
    RSI_OVERSOLD("RSI Oversold (<30)", "Picu alert saat indikator RSI menyentuh area jenuh jual"),
    RSI_OVERBOUGHT("RSI Overbought (>70)", "Picu alert saat indikator RSI menyentuh area jenuh beli"),
    SECOND_WAVE_RECLAIM("Second-Wave Reclaim", "Picu alert saat setup Second-Wave terkonfirmasi")
}

data class PriceAlert(
    val id: String = java.util.UUID.randomUUID().toString(),
    val symbol: String,
    val type: PriceAlertType,
    val targetPrice: Double = 0.0,
    val targetRsi: Double = 30.0,
    val note: String = "",
    val isEnabled: Boolean = true,
    val isTriggered: Boolean = false,
    val triggeredAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val exchange: String = "TOKOCRYPTO"
)

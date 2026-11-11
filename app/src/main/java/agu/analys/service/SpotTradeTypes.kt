package agu.analys.service

/**
 * Saldo spot hasil pembacaan akun bursa. Dipakai bersama oleh lapisan order riil
 * dan portofolio; tidak terikat pada bursa tertentu.
 */
data class SpotBalances(
    val total: Map<String, Double>,
    val free: Map<String, Double>,
    val locked: Map<String, Double>
)

/** Hasil pembuatan / pembacaan order yang terstruktur. */
data class SpotOrderResult(
    val success: Boolean,
    val message: String,
    val orderId: String = "",
    val clientOrderId: String = "",
    val executedQty: Double = 0.0,
    val origQty: Double = 0.0,
    val status: String = ""
)

package agu.analys.model

data class SignalAudit(
    val symbol: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val price: Double = 0.0,
    val step1: Boolean = false,
    val step2: Boolean = false,
    val step3: Boolean = false,
    val step4: Boolean = false,
    val buyPressure: Double = 1.0,
    val vwap: Double = 0.0,
    val rsi: Double = 0.0,
    val rr: Double = 0.0,
    val finalAction: String = "HOLD",
    val rejectionReason: String? = null,
    val isOrderBookEmpty: Boolean = false,
    val orderBookAgeMs: Long = 0L,
    /** False berarti replay/diagnostic tidak memiliki snapshot historical order book. */
    val orderBookDataAvailable: Boolean = false,
    /** Pipeline KriptoYoi (P1.4) — default aman agar backward compatible. */
    val score: Int = 0,
    val scoreCategory: String = "NO_TRADE",
    val setup: String = "NONE",
    val direction: String = "WAIT",
    val regime: String = ""
) {
    val step1Ok: Boolean get() = step1
    val step2Ok: Boolean get() = step2
    val step3Ok: Boolean get() = step3
    val step4Ok: Boolean get() = step4
}

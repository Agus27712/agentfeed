package agu.analys.engine.scalping

/**
 * Potret satu posisi paper trade yang sedang berjalan — dipakai UI untuk menggambar
 * indikator FSM (P2) tanpa menyentuh state machine secara langsung.
 *
 * Semua nilai di sini **read-only** dan dihitung dari [PaperExitStateMachine], sehingga
 * angka yang ditampilkan sama persis dengan yang dipakai engine untuk memutuskan exit.
 */
data class PaperExitSnapshot(
    val symbol: String,
    val exchange: String,
    val mode: ScalpExitMode,
    val phase: PaperExitPhase,
    val entry: Double,
    /** SL awal dari sinyal, tidak pernah berubah. */
    val initialStopLoss: Double,
    /** Level SL yang sedang aktif (bisa sudah naik ke BE / trailing). */
    val activeStopLoss: Double,
    /** Harga impas net setelah fee all-in. */
    val netBreakevenPrice: Double,
    val peak: Double,
    val tp1: Double,
    val tp2: Double,
    val barsHeld: Int,
    val currentPrice: Double,
    val netProfitPct: Double,
    val currentR: Double,
    val isPartialDone: Boolean,
    val isRiskValid: Boolean
) {
    /** Jarak harga sekarang ke SL aktif, dalam persen. Negatif berarti sudah menembus SL. */
    val distanceToStopPct: Double
        get() = if (currentPrice > 0.0) (currentPrice - activeStopLoss) / currentPrice * 100.0 else 0.0

    /** SL aktif sudah naik di atas SL awal? Berarti proteksi BE/trailing sudah bekerja. */
    val stopHasMoved: Boolean get() = activeStopLoss > initialStopLoss + 1e-12

    companion object {
        fun of(
            machine: PaperExitStateMachine,
            symbol: String,
            exchange: String,
            currentPrice: Double
        ): PaperExitSnapshot = PaperExitSnapshot(
            symbol = symbol,
            exchange = exchange,
            mode = machine.params.mode,
            phase = machine.phase,
            entry = machine.entry,
            initialStopLoss = machine.stopLoss,
            activeStopLoss = machine.activeStopLoss,
            netBreakevenPrice = machine.netBreakevenPrice,
            peak = machine.peak,
            tp1 = machine.tp1,
            tp2 = machine.tp2,
            barsHeld = machine.barsHeld,
            currentPrice = currentPrice,
            netProfitPct = if (currentPrice > 0.0) machine.netProfitPct(currentPrice) else 0.0,
            currentR = if (currentPrice > 0.0) machine.rAt(currentPrice) else 0.0,
            isPartialDone = machine.isPartialDone,
            isRiskValid = machine.isRiskValid
        )
    }
}

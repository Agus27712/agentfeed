package agu.analys.trading

import agu.analys.engine.scalping.PaperExitSnapshot

/**
 * Potret FSM exit paper **per posisi** — **P5**.
 *
 * ## Kenapa kelas ini ada
 *
 * Dulu `publishPaperExitSnapshot` menulis ke **satu** `MutableStateFlow` global:
 *
 * ```kotlin
 * // PaperAutoTradeCoordinator.kt — dievaluasi untuk SETIAP simbol pada loop polling
 * val machine = PaperAutoTradeRuntime.machineFor(exchange, symbol)
 * if (machine == null) {
 *     // Tidak ada posisi paper berjalan -> pastikan kartu FSM kosong.
 *     publishPaperExitSnapshot(null)   // ← menimpa snapshot SEMUA posisi
 *     return null
 * }
 * ```
 *
 * Sementara `evaluatePaperAutoExit` dipanggil untuk setiap simbol yang mendapat tick
 * (`TradingViewModelSync.kt`) **dan** untuk pair yang dipilih (`TradingViewModel.kt`). Jadi
 * setiap simbol non-paper yang mendapat tick menimpa flow itu dengan `null`.
 *
 * Dampaknya: pengguna membuka detail BTC sementara posisi paper-nya ada di AMP/USDT — kartu FSM
 * di layar simulasi berkedip hilang. Ini persis kebingungan "satu state untuk dua tampilan" yang
 * dulu membuat fitur §14/§15 dibuang.
 *
 * ## Aturan yang ditegakkan di sini
 *
 * Satu posisi = satu entri. Memperbarui (atau menghapus) snapshot satu posisi **tidak boleh**
 * menyentuh posisi lain. Kuncinya dibuat pemanggil dengan `PaperAutoTradeRuntime.key`, supaya
 * tidak ada dua definisi kunci posisi dalam satu aplikasi.
 */
object PaperExitSnapshots {

    /**
     * Terbitkan [snapshot] untuk satu posisi. `null` berarti **hapus entri posisi itu saja**.
     *
     * @return peta baru — peta lama tidak dimutasi, supaya aman dibaca dari Compose.
     */
    fun publish(
        current: Map<String, PaperExitSnapshot>,
        key: String,
        snapshot: PaperExitSnapshot?
    ): Map<String, PaperExitSnapshot> {
        if (snapshot == null) {
            if (!current.containsKey(key)) return current
            return current - key
        }
        return current + (key to snapshot)
    }

    /** Potret untuk satu posisi, atau `null` bila posisi itu tidak punya snapshot. */
    fun forPosition(
        current: Map<String, PaperExitSnapshot>,
        key: String
    ): PaperExitSnapshot? = current[key]
}

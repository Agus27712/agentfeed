package agu.analys.trading

/**
 * J10 (sisa) — parameter SL/TP untuk **mesin exit paper**, hanya boleh berasal dari sinyal
 * milik simbol itu sendiri.
 *
 * ## Kenapa kelas ini ada
 *
 * Batch 5a (`9767c88`) menutup J10 di **jurnal**: `TradeSignalSnapshot.capture` menggerbang
 * sinyal dengan `SignalSymbolAttribution.matches`, dan jalur beli memakai `attributedFields`.
 * Tetapi satu jalur masih membaca `engine.signalState.value` **mentah**: beli **paper manual**
 * meng-*arm* mesin exit dengan SL/TP pair lain (`TradingViewModelSync.kt:343-350`).
 *
 * Jendelanya sama dengan J4: user berganti pair, sedangkan `engine.signalState` masih berisi
 * hasil evaluasi pair **lama** sampai tick berikutnya. Bedanya dengan jurnal, di sini nilainya
 * dipakai untuk **keputusan**, bukan catatan:
 *
 * - `stopLoss` masih disaring `PaperStopResolver.resolve` (`0 < SL < entry`), jadi SL pair lain
 *   yang jauh di atas entry ditolak → jatuh ke lantai friksi. Tetapi SL yang kebetulan di bawah
 *   entry **dan** di bawah lantai friksi tetap dipakai — SL terlalu lebar untuk koin itu.
 * - `tp1`/`tp2` **tidak** disaring. Mesin memakai `bar.high >= tp1` (partial TP + geser SL ke BE)
 *   dan `tp2` (TP penuh). TP1 pair lain yang berada **di bawah** entry posisi ini bisa memicu
 *   **jual sebagian pada tick pertama**.
 *
 * ## Aturan
 *
 * - Simbol cocok → nilai diteruskan apa adanya (validasi sisi harga tetap milik
 *   `PaperStopResolver`, bukan di sini).
 * - Simbol tidak cocok / kosong → **semua nol**. Nol berarti "tidak ada TP/SL dari sinyal":
 *   mesin jatuh ke lantai friksi untuk SL dan tidak punya TP untuk dipicu. Itu perilaku yang
 *   benar; memakai TP pair lain dengan label pair ini tidak pernah benar.
 */
data class PaperArmParams(
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double
) {
    /** Tidak ada satu pun parameter yang bisa dipakai mesin. */
    val isEmpty: Boolean get() = stopLoss <= 0.0 && tp1 <= 0.0 && tp2 <= 0.0
}

object PaperArmSignal {

    /**
     * @param requestedSymbol simbol posisi yang akan dibuka
     * @param signalSymbol `engine.signalState.value.marketSymbol` — pemilik sinyal
     */
    fun resolve(
        requestedSymbol: String,
        signalSymbol: String,
        stopLoss: Double,
        tp1: Double,
        tp2: Double
    ): PaperArmParams =
        if (SignalSymbolAttribution.matches(requestedSymbol, signalSymbol)) {
            PaperArmParams(stopLoss = stopLoss, tp1 = tp1, tp2 = tp2)
        } else {
            PaperArmParams(stopLoss = 0.0, tp1 = 0.0, tp2 = 0.0)
        }
}

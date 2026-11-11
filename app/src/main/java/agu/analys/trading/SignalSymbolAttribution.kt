package agu.analys.trading

/**
 * Atribusi sinyal ke simbol — **satu-satunya tempat** yang memutuskan "sinyal ini milik pair
 * yang mana".
 *
 * ## Kenapa kelas ini ada (J4 & N10)
 *
 * `engine.signalState.value` berisi hasil evaluasi **pair terakhir yang dinilai engine**, dan
 * field-nya (`marketSymbol`) sudah benar — engine mengisinya di empat tempat
 * (`LearningTradingEngine.kt:251`, `:284`, `:328`, `:372`). Masalahnya ada di pemanggil:
 *
 * 1. **J4** — `TradingViewModel.listenToEngineSignals` **menimpa** field yang sudah benar:
 *    ```kotlin
 *    list.add(0, signal.copy(marketSymbol = selectedPair.value.symbol))   // ditimpa
 *    signalLogRepository.recordSignal(symbol = selectedPair.value.symbol, …)
 *    ```
 *    `selectPair()` mengganti pair lebih dulu, sedangkan `signalState` masih berisi hasil pair
 *    **lama** sampai tick berikutnya. Selama jendela itu, jurnal menyimpan entry/TP1/TP2/SL/
 *    confidence **BTC** dengan label **ETH**, dan `SignalHistoryPanel.kt:74` (filter
 *    `marketSymbol`) pun tidak bisa menyelamatkannya karena field-nya sudah ditimpa.
 *
 * 2. **N10** — jalur beli memakai `engine.signalState.value` **tanpa memeriksa** apakah sinyal
 *    itu milik simbol yang sedang dibeli (`TradeSignalSnapshot.capture:275`,
 *    `TradingViewModelSync.kt:253-282`). Bila engine terakhir menilai pair lain, confidence/TP/SL
 *    pair lain ikut tercatat sebagai milik pair ini — persis manifestasi J4 di jurnal trade.
 *
 * ## Aturan yang ditegakkan di sini
 *
 * - **Jurnal memakai simbol yang dievaluasi engine**, bukan pair yang sedang dibuka.
 * - **Nilai turunan sinyal hanya boleh dikaitkan dengan simbol yang sama.** Bila tidak cocok,
 *   angkanya **dikosongkan** (confidence 0, TP/SL 0.0, harga sinyal jatuh ke harga fill,
 *   waktu sinyal jatuh ke waktu order) — bukan dipakai dengan label pair lain.
 *   Mengosongkan lebih jujur daripada memalsukan: jurnal ini adalah dasar statistik
 *   reliability, dan angka pair lain yang menempel di pair ini membuat statistik itu bohong.
 */
object SignalSymbolAttribution {

    /**
     * Bentuk baku untuk perbandingan simbol: `btc_idr`, `BTC/IDR`, `BTC-IDR`, `BTC IDR`
     * semuanya menjadi `BTCIDR`.
     */
    fun normalize(symbol: String): String = symbol
        .trim()
        .uppercase()
        .replace("_", "")
        .replace("-", "")
        .replace("/", "")
        .replace(" ", "")

    /**
     * **N10** — true bila sinyal itu benar-benar hasil evaluasi [requestedSymbol].
     * Simbol kosong di salah satu sisi dianggap **tidak cocok**: "tidak tahu" bukan "cocok".
     */
    fun matches(requestedSymbol: String, signalSymbol: String): Boolean {
        val requested = normalize(requestedSymbol)
        val signal = normalize(signalSymbol)
        return requested.isNotEmpty() && requested == signal
    }

    /**
     * **J4** — simbol yang dicatat ke jurnal: simbol yang dievaluasi engine, jatuh ke pair yang
     * sedang dibuka **hanya** bila engine belum mengisi field-nya (sinyal lawas/offline).
     */
    fun journalSymbol(signalSymbol: String, selectedSymbol: String): String =
        signalSymbol.trim().takeIf { it.isNotEmpty() } ?: selectedSymbol.trim()

    /** Baris jurnal hanya boleh ditulis bila simbolnya benar-benar diketahui. */
    fun isRecordable(symbol: String): Boolean = normalize(symbol).isNotEmpty()

    /**
     * Harga entry untuk jurnal: harga sinyal engine, jatuh ke harga tick **simbol itu sendiri**
     * (bukan tick pair yang sedang dibuka, yang bisa saja pair lain).
     */
    fun entryPrice(signalEntryPrice: Double, tickPrice: Double?): Double =
        signalEntryPrice.takeIf { it > 0.0 } ?: tickPrice?.takeIf { it > 0.0 } ?: 0.0

    /**
     * **N10** — nilai turunan sinyal yang boleh dikaitkan dengan [requestedSymbol].
     *
     * Bila sinyal milik pair lain, semua angkanya diganti nilai netral (lihat KDoc kelas).
     */
    fun attributedFields(
        requestedSymbol: String,
        signalSymbol: String,
        fields: SignalDerivedFields,
        fallbackPrice: Double,
        fallbackTime: Long
    ): SignalDerivedFields {
        if (!matches(requestedSymbol, signalSymbol)) return fields.neutral(fallbackPrice, fallbackTime)
        return fields.copy(
            entryPrice = entryPrice(fields.entryPrice, fallbackPrice),
            signalTime = fields.signalTime.takeIf { it > 0L } ?: fallbackTime
        )
    }
}

/**
 * Nilai turunan sinyal yang menempel pada satu baris jurnal/trade.
 */
data class SignalDerivedFields(
    val confidence: Int,
    val entryPrice: Double,
    val targetPrice1: Double,
    val targetPrice2: Double,
    val stopLoss: Double,
    val signalTime: Long
) {
    /**
     * Versi netral: dipakai bila sinyal **bukan** milik simbol yang dicatat. Angka pair lain
     * tidak boleh menempel di baris pair ini, jadi dikosongkan — harga sinyal jatuh ke harga
     * transaksi nyata dan waktu sinyal jatuh ke waktu transaksi.
     */
    fun neutral(fallbackPrice: Double, fallbackTime: Long): SignalDerivedFields = copy(
        confidence = 0,
        entryPrice = fallbackPrice,
        targetPrice1 = 0.0,
        targetPrice2 = 0.0,
        stopLoss = 0.0,
        signalTime = fallbackTime
    )
}

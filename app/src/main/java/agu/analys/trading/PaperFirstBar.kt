package agu.analys.trading

/**
 * P8 — apakah tick ini boleh **menambah** `barsHeld`.
 *
 * ## Masalah yang dicegah
 *
 * Setelah restart, `PaperRehydrate.mergedBarsHeld` menghitung `barsHeld` dari jam dinding:
 *
 * ```kotlin
 * elapsedBars = (now - openedAt) / 60_000
 * barsHeld = max(stored, elapsedBars)
 * ```
 *
 * Lalu `PaperAutoTradeRuntime.put(..., markFirstTickAsNewBar = true)` menandai bucket menit
 * sebagai sentinel (`-1`), sehingga tick pertama sesudah rehidrasi dianggap **bar baru** dan
 * `PaperExitStateMachine.onBar` menaikkan `barsHeld` **sekali lagi** untuk menit yang sudah
 * dihitung `mergedBarsHeld`. Akibatnya time-stop keluar **1 bar (1 menit) lebih awal** daripada
 * yang seharusnya — app dan `AUDIT-2026-11-08.md` tidak lagi sebanding.
 *
 * ## Aturan
 *
 * Tick pertama sesudah rehidrasi tetap **dievaluasi sebagai bar baru** (`isNewBar = true`) supaya
 * time-stop langsung dicek — kalau tidak, posisi yang seharusnya sudah kehabisan waktu baru
 * ditutup pada batas menit berikutnya. Tetapi ia **tidak menambah** hitungan, karena menit itu
 * sudah masuk lewat [PaperRehydrate.mergedBarsHeld].
 *
 * Dengan kata lain: *evaluasi* dan *hitungan* dipisahkan.
 */
object PaperFirstBarResolver {

    /**
     * @param isNewBar hasil `markBarBucket` — `true` bila tick ini jatuh di menit yang berbeda
     *   dari tick sebelumnya (atau tick pertama setelah `put` dengan sentinel).
     * @param firstTickAfterRehydrate `true` hanya untuk tick pertama sesudah rehidrasi
     *   (bucket sebelumnya adalah sentinel `FIRST_TICK_BUCKET`).
     * @return `true` bila `barsHeld` boleh ditambah.
     */
    fun countsAsNewBar(isNewBar: Boolean, firstTickAfterRehydrate: Boolean): Boolean =
        isNewBar && !firstTickAfterRehydrate
}

package agu.analys.util

import agu.analys.engine.scalping.PaperTradeRecord

/**
 * Penyimpan jurnal paper trade (P2).
 *
 * Menyimpan hasil akhir tiap posisi simulasi ke `SharedPreferences` terenkripsi milik
 * [AppPreferences], supaya kartu statistik tetap akurat setelah app ditutup. Jumlah baris
 * dibatasi [PaperTradeRecord.MAX_RECORDS]; baris terlama dibuang lebih dulu.
 *
 * Ini murni data paper trade — tidak menyentuh saldo atau posisi uang riil.
 */
class PaperTradeJournalStore(private val prefs: AppPreferences) {

    fun load(): List<PaperTradeRecord> = PaperTradeRecord.listFromJson(prefs.paperTradeJournalJson)

    /**
     * Tambahkan satu baris dan simpan.
     *
     * @return daftar lengkap setelah penambahan (sudah dipangkas ke [PaperTradeRecord.MAX_RECORDS]).
     */
    fun append(record: PaperTradeRecord): List<PaperTradeRecord> {
        val combined = load() + record
        val trimmed =
            if (combined.size > PaperTradeRecord.MAX_RECORDS) combined.takeLast(PaperTradeRecord.MAX_RECORDS)
            else combined
        prefs.paperTradeJournalJson = PaperTradeRecord.listToJson(trimmed)
        return trimmed
    }

    fun clear() {
        prefs.paperTradeJournalJson = "[]"
    }
}

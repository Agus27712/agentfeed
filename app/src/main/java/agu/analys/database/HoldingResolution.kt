package agu.analys.database

/**
 * J5 — resolusi record HOLDING untuk jurnal trade **tanpa fallback lintas bursa**.
 *
 * Sebelum perbaikan, `recordSell` dan `processPriceTick` jatuh ke query **tanpa filter
 * exchange** bila query berversi exchange kosong:
 *
 * ```kotlin
 * dao.getActiveHoldingForSymbolAndExchange(symbol, isReal, exchange)
 *     ?: dao.getActiveHoldingForSymbol(symbol, isReal)   // ← SEMUA bursa
 * ```
 *
 * Satu simbol bisa tersimpan di beberapa partisi lama (mis. `BTCIDR` dengan kunci
 * exchange berbeda). Bila query memakai fallback tanpa exchange, akibatnya:
 *
 * 1. jual `BTCIDR` dapat menutup record HOLDING yang bukan miliknya, dengan
 *    `sellPrice`/`customPnlIdr` dari partisi lain;
 * 2. `processPriceTick`: harga dari satu partisi memperbarui `peakPriceDuringHold`/
 *    `troughPriceDuringHold`/`maxProfitPct` record partisi lain ⇒ statistik
 *    MFE/drawdown per posisi tercemar.
 *
 * Pola yang benar sudah ada di `SignalLogRepository.processPriceTick` — query berversi
 * exchange, tanpa fallback, dengan komentar eksplisit. Objek ini meniru pola itu.
 *
 * **Konvensi lambda:** parameter `exchange` bertipe `String`; string **kosong** berarti
 * "tanpa filter bursa". Versi ini **tidak pernah** memanggil dengan bursa kosong —
 * konvensi itu ada semata untuk kontrol negatif, yang mengemulasi perilaku lama dengan
 * memanggil lambda bursa-kosong.
 */
object HoldingResolution {

    /**
     * Record HOLDING yang akan ditutup oleh penjualan.
     *
     * @param tradeUuid bila ada, record dicari lewat uuid (unik global — tidak mungkin
     *   campur bursa); uuid **kosong** diperlakukan seperti tidak ada, bukan short-circuit
     *   ke query uuid yang pasti miss.
     * @return record milik bursa [exchange], atau `null` bila tidak ada. **Tidak ada
     *   fallback ke bursa lain** — bila kosong, biarkan kosong.
     */
    suspend fun resolveForSell(
        tradeUuid: String?,
        symbol: String,
        isReal: Boolean,
        exchange: String,
        byUuid: suspend (String) -> TradeHistoryRecordEntity?,
        bySymbolAndExchange: suspend (String, Boolean, String) -> TradeHistoryRecordEntity?
    ): TradeHistoryRecordEntity? {
        if (!tradeUuid.isNullOrBlank()) return byUuid(tradeUuid)
        return bySymbolAndExchange(symbol, isReal, exchange)
    }

    /**
     * Record HOLDING yang diperbarui tick harga. Hanya milik bursa [exchange] dengan simbol
     * yang sama (abaikan kapitalisasi). Daftar kosong berarti kosong — harga dari bursa lain
     * **tidak boleh** menyentuh record bursa ini.
     */
    suspend fun holdingsForPriceTick(
        symbol: String,
        exchange: String,
        byExchange: suspend (String) -> List<TradeHistoryRecordEntity>
    ): List<TradeHistoryRecordEntity> =
        byExchange(exchange).filter { it.symbol.equals(symbol, ignoreCase = true) }
}

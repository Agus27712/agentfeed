package agu.analys.ui.screens.portfolio

import agu.analys.model.TradingPair

enum class PortfolioTab(val title: String) {
    HOLDINGS("Koin Dimiliki"),
    OPEN_ORDERS("Antrean Order"),
    HISTORY("Riwayat Transaksi")
}

/**
 * Satu posisi koin di portofolio.
 *
 * Pemisahan `$` vs `Rp` ditegakkan lewat [quoteAsset]:
 * - [avgBuyPrice] & [currentPrice] **selalu dalam mata uang kuotasi posisi**
 *   (`IDR` → Rupiah, `USDT` → dollar). Jangan pernah mencampur keduanya.
 * - [totalValueIdr] & [pnlIdr] sudah dinormalisasi ke Rupiah supaya agregasi
 *   portofolio (total nilai, total PnL) konsisten.
 * - [usdtIdrRate] menyimpan kurs yang dipakai saat normalisasi; `0.0` bila posisi
 *   memang berkuotasi Rupiah, atau bila kurs belum tersedia (nilai IDR lalu `0.0`).
 */
data class HoldingItem(
    val baseAsset: String,
    val quantity: Double,
    val avgBuyPrice: Double,
    val currentPrice: Double,
    val totalValueIdr: Double,
    val pnlIdr: Double,
    val pnlPercent: Double,
    val tradingPair: TradingPair,
    val isRealMirror: Boolean = false,
    /** Mata uang kuotasi posisi: `IDR` atau `USDT` (menentukan prefix Rp / $). */
    val quoteAsset: String = "IDR",
    /** Kurs USDT→IDR yang dipakai untuk normalisasi; `0.0` = tidak diperlukan/belum ada. */
    val usdtIdrRate: Double = 0.0
) {
    /** `true` bila posisi ini dibeli di pair berkuotasi USDT (harga dalam `$`). */
    val isUsdtPosition: Boolean
        get() = quoteAsset.equals("USDT", true) ||
            quoteAsset.equals("USD", true) ||
            quoteAsset.equals("USDC", true) ||
            quoteAsset.equals("BUSD", true)

    /** Nilai total dalam mata uang kuotasi aslinya (bukan Rupiah). */
    val totalValueInQuote: Double
        get() = quantity * currentPrice

    /**
     * PnL dalam mata uang kuotasi posisi. Ini yang **wajib** ditampilkan untuk
     * posisi USDT agar tidak muncul angka `$` bercampur dengan `Rp`.
     */
    val pnlInQuote: Double
        get() = if (avgBuyPrice > 0.0) (currentPrice - avgBuyPrice) * quantity else 0.0
}
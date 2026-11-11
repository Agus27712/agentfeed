package agu.analys.database

import agu.analys.config.FeeSchedule
import kotlin.math.abs

/**
 * Ambang perpindahan harga entry yang memisahkan dua episode sinyal.
 *
 * Nilainya sengaja sama dengan ambang anti-spam di `SignalLogRepository.recordSignal`
 * (0,8%) supaya kedua aturan tidak saling bertabrakan: perpindahan di bawah ambang = masih
 * sinyal yang sama; di atas ambang = episode baru.
 */
internal const val ENTRY_SPLIT_TOLERANCE = 0.008

/**
 * Apakah harga entry sudah bergerak cukup jauh sehingga sinyal ini adalah **episode baru**.
 *
 * Akar J2: `recordSignal` menyimpan `entryPrice` dari sinyal **pertama** tetapi memperbarui
 * `targetPrice1`/`targetPrice2`/`stopLoss` dari sinyal **terbaru**. Bila harga sudah naik jauh,
 * `stopLoss` baru bisa berada **di atas** `entryPrice` lama, sehingga harga yang turun menyentuh
 * stop loss justru menghasilkan P&L **positif** — dan `HIT_SL` ber-P&L positif itu masuk keranjang
 * menang sekaligus kalah.
 *
 * Harga tidak valid (`<= 0` atau bukan bilangan hingga) tidak dianggap perpindahan material,
 * supaya pemanggil yang mengirim harga kosong tidak memecah pelacakan yang sedang berjalan.
 */
internal fun hasEntryDrifted(oldEntry: Double, newEntry: Double): Boolean {
    if (oldEntry <= 0.0 || newEntry <= 0.0) return false
    if (!oldEntry.isFinite() || !newEntry.isFinite()) return false
    return abs(newEntry - oldEntry) / oldEntry > ENTRY_SPLIT_TOLERANCE
}

/**
 * Kuotasi sebuah simbol log. Dipakai untuk memilih tarif fee bursa yang tepat —
 * Tokocrypto melayani pair IDR **dan** USDT.
 */
internal fun quoteAssetForSymbol(symbol: String): String {
    val clean = symbol.uppercase().replace("_", "").replace("/", "").trim()
    return when {
        clean.endsWith("USDT") -> "USDT"
        clean.endsWith("USDC") -> "USDC"
        clean.endsWith("BUSD") -> "BUSD"
        clean.endsWith("BIDR") -> "BIDR"
        clean.endsWith("IDR") -> "IDR"
        else -> "IDR"
    }
}

/**
 * P&L **net** sebuah log sinyal, memakai basis yang sama dengan seluruh aplikasi
 * ([FeeSchedule.netProfitPctForDirected] — taker + slippage dua sisi).
 *
 * Sebelum perbaikan J1 (2026-10-16) jurnal memakai **selisih harga mentah** tanpa fee apa pun,
 * lalu UI-nya melabeli angka itu "Net". Untuk Tokocrypto USDT, angka "+0,35%" yang tampil sebagai
 * "Net" sebenarnya masih **rugi** karena impas net-nya ≈ +0,82%.
 *
 * @param isBuy `true` untuk sinyal BUY (long), `false` untuk SELL (bertaruh harga turun)
 */
internal fun netSignalPnlPct(
    entryPrice: Double,
    exitPrice: Double,
    isBuy: Boolean,
    exchange: String,
    quoteAsset: String
): Double = FeeSchedule.netProfitPctForDirected(
    entry = entryPrice,
    price = exitPrice,
    isBuy = isBuy,
    exchange = exchange,
    quoteAsset = quoteAsset
)

/** [netSignalPnlPct] untuk sebuah log, memakai bursa + kuotasi milik log itu sendiri. */
internal fun netSignalPnlPct(log: SignalLogEntity, exitPrice: Double): Double = netSignalPnlPct(
    entryPrice = log.entryPrice,
    exitPrice = exitPrice,
    isBuy = log.action.equals("BUY", ignoreCase = true),
    exchange = log.exchange,
    quoteAsset = quoteAssetForSymbol(log.symbol)
)

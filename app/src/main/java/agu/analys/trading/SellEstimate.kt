package agu.analys.trading

import agu.analys.config.FeeSchedule
import java.util.Locale

/**
 * Estimasi **satu transaksi jual** pada radar jual — satu-satunya sumber angka kartu jual.
 *
 * ## Kenapa kelas ini ada (J7)
 *
 * Sebelum ini, `RadarSellSection` menghitung sendiri dengan rumus yang **berbeda** dari
 * [FeeSchedule] yang sudah disepakati di `AUDIT-2026-11-08.md` §24:
 *
 * ```kotlin
 * val sellFeeIdr   = grossSellValueIdr * (activeFeePct / 100.0)   // hanya fee jual
 * val costBasisIdr = activeSellQty * effectiveBuyPrice            // TANPA fee beli
 * val netProfitIdr = netReceivedSellIdr - costBasisIdr            // tanpa slippage
 * ```
 *
 * Artinya kartu yang paling sering dibaca pengguna sebelum menekan tombol jual memotong
 * **hanya fee jual**, lalu menulis "sudah bersih fee & pajak". Friksi yang benar-benar
 * dipotong hanya 0,4322% (Tokocrypto IDR taker) padahal FSM di halaman yang sama memakai
 * 0,8186%. Selisih 0,3864 pp itu cukup untuk membuat kartu mengklaim **UNTUNG** pada kenaikan
 * harga +0,50%, padahal net-nya **rugi −0,3160%**.
 *
 * ## Basis yang dipakai
 *
 * - **Fee jual** = tarif order type yang dipilih pengguna (maker/taker) — itu yang benar-benar
 *   akan dibayar pada kaki jual yang sedang dipricilkan.
 * - **Fee beli** = tarif **taker**. Order beli sudah terjadi dan jenisnya tidak diketahui di
 *   sini, jadi dipakai asumsi terburuk yang juga basis FSM (`netProfitPctFor` default taker).
 * - **Slippage** = dua sisi, mengikuti konvensi [FeeSchedule] (slippage ikut dibebankan walau
 *   maker — lihat `netProfitPctFor(..., useMaker = true)`).
 *
 * Konsekuensinya, pada mode taker angka kartu **sama persis** dengan FSM. Pada mode maker
 * friksinya 0,7174% (bukan 0,8186%) — perbedaan yang disengaja dan justru jujur, karena
 * pengguna memang memilih limit order untuk kaki jual.
 *
 * Invarian yang dijaga: `(netProceeds / costBasisNet − 1) × 100` == `netProfitPct`, dan
 * `netProfitPct` == [FeeSchedule.netProfitPct] untuk tarif yang sama.
 */
data class SellEstimate(
    val quantity: Double,
    val entryPrice: Double,
    val sellPrice: Double,
    val buyFeePct: Double,
    val sellFeePct: Double,
    val slippagePct: Double,
    /** Nilai jual bruto sebelum potongan apa pun. */
    val grossSellValue: Double,
    val sellFeeAmount: Double,
    val sellSlippageAmount: Double,
    val buyFeeAmount: Double,
    val buySlippageAmount: Double,
    /** Kas bersih yang masuk saldo: bruto dikurangi fee jual + slippage jual. */
    val netProceeds: Double,
    /** Biaya perolehan bersih: `qty × entry` **ditambah** fee beli + slippage beli. */
    val costBasisNet: Double,
    val netProfit: Double,
    val netProfitPct: Double
) {
    /** Modal pembelian diketahui (dari portofolio atau input manual pengguna). */
    val hasBasis: Boolean get() = costBasisNet > 0.0

    val isProfitable: Boolean get() = hasBasis && netProfit >= 0.0

    /** Seluruh friksi yang dipotong: beli + jual, termasuk slippage dua sisi. */
    val totalFrictionAmount: Double
        get() = buyFeeAmount + buySlippageAmount + sellFeeAmount + sellSlippageAmount

    /** Friksi sisi jual saja — dipakai saat modal beli belum diketahui. */
    val sellSideFrictionAmount: Double
        get() = sellFeeAmount + sellSlippageAmount

    /** Angka yang ditampilkan sebagai "Potongan Biaya, Pajak & Slippage". */
    val frictionAmount: Double
        get() = if (hasBasis) totalFrictionAmount else sellSideFrictionAmount
}

object SellEstimateResolver {

    fun resolve(
        quantity: Double,
        entryPrice: Double,
        sellPrice: Double,
        buyFeePct: Double,
        sellFeePct: Double,
        slippagePct: Double = FeeSchedule.DEFAULT_SLIPPAGE_PCT
    ): SellEstimate {
        val qty = quantity.positiveOrZero()
        val entry = entryPrice.positiveOrZero()
        val price = sellPrice.positiveOrZero()
        val buyFee = buyFeePct.positiveOrZero()
        val sellFee = sellFeePct.positiveOrZero()
        val slip = slippagePct.positiveOrZero()

        val grossSellValue = qty * price
        val netProceeds = FeeSchedule.netSellValue(qty, price, sellFee, slip)
        val costBasis = FeeSchedule.costBasisNet(qty, entry, buyFee, slip)

        // Tanpa modal yang diketahui, laba tidak boleh dikarang: nol, bukan `netProceeds`.
        val hasBasis = costBasis > 0.0
        val profit = if (hasBasis) netProceeds - costBasis else 0.0
        val profitPct = if (hasBasis) (netProceeds / costBasis - 1.0) * 100.0 else 0.0

        return SellEstimate(
            quantity = qty,
            entryPrice = entry,
            sellPrice = price,
            buyFeePct = buyFee,
            sellFeePct = sellFee,
            slippagePct = slip,
            grossSellValue = grossSellValue,
            sellFeeAmount = grossSellValue * sellFee / 100.0,
            sellSlippageAmount = grossSellValue * slip / 100.0,
            buyFeeAmount = qty * entry * buyFee / 100.0,
            buySlippageAmount = qty * entry * slip / 100.0,
            netProceeds = netProceeds,
            costBasisNet = costBasis,
            netProfit = profit,
            netProfitPct = profitPct
        )
    }
}

/**
 * Sub-label di bawah judul "Potongan Biaya, Pajak & Slippage".
 *
 * Menggantikan persen tunggal yang dulu menyesatkan (dulu hanya fee jual, mis. "(0,43%)",
 * padahal yang dipotong jauh lebih banyak). Komponennya disebut satu per satu supaya pengguna
 * bisa mencocokkan sendiri dengan [SellEstimate] — dan supaya klaim "sudah bersih" bisa
 * diverifikasi, bukan dipercaya.
 */
fun frictionBreakdownLabel(
    buyFeePct: Double,
    sellFeePct: Double,
    slippagePct: Double,
    hasBasis: Boolean,
    sellIsMaker: Boolean
): String {
    val jenisJual = if (sellIsMaker) "maker" else "taker"
    val kakiJual = "Jual $jenisJual ${pct(sellFeePct)}%"
    return if (hasBasis) {
        "Beli taker ${pct(buyFeePct)}% + $kakiJual + Slippage 2×${pct(slippagePct)}%"
    } else {
        "$kakiJual + Slippage ${pct(slippagePct)}% (biaya beli belum diketahui)"
    }
}

/**
 * Kalimat basis di kotak untung/rugi.
 *
 * Versi lama menulis "Hasil bersih melebihi modal (sudah bersih fee & pajak)" — klaim yang
 * tidak benar, karena angka di sebelahnya belum memotong fee beli maupun slippage.
 */
fun profitBasisNote(isProfitable: Boolean): String = if (isProfitable) {
    "Hasil bersih melebihi modal setelah biaya beli, biaya jual, pajak & slippage"
} else {
    "Hasil bersih di bawah modal setelah biaya beli, biaya jual, pajak & slippage"
}

private fun pct(value: Double): String = String.format(Locale.US, "%.2f", value)

private fun Double.positiveOrZero(): Double = if (isFinite() && this > 0.0) this else 0.0

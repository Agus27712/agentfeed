package agu.analys.trading

import agu.analys.config.FeeSchedule

/**
 * J8 — P&L satu penjualan **uang riil**, dalam rupiah dan persen, dari **satu rumus**.
 *
 * Sebelumnya jalur eksekusi trade real menulis rumusnya sendiri
 * (`TradingViewModelSync.kt:56-65`):
 *
 * ```kotlin
 * val netSell = effectivePrice * (1.0 - sellRate)
 * val cost    = before.entryPrice * (1.0 + buyRate)
 * customPnlPct = (netSell / cost - 1.0) * 100.0
 * ```
 *
 * Rumus itu **tidak memasukkan slippage**, sedangkan `FeeSchedule.netProfitPctFor` menambahkan
 * slippage dua sisi (default 0,08%/sisi). Akibatnya jurnal trade real berbeda ≈ **0,16 pp**
 * dari angka portofolio/radar jual untuk trade yang sama — satu trade, dua angka. Itu pelanggaran
 * aturan "SATU rumus P&L": satu-satunya pengecualian yang sah adalah `SimulationTradeStore` /
 * `SimulationOrderEngine` (mesin simulasi punya model isi sendiri).
 *
 * Angka rupiah dan persen dihitung dari pasangan `costBasisNet`/`netSellValue` supaya keduanya
 * **tidak mungkin** berbeda: `(netSell / cost - 1) * 100` identik dengan `netProfitPctFor`.
 */
data class RealTradePnl(
    val pnlIdr: Double,
    val pnlPct: Double
) {
    companion object {
        val ZERO = RealTradePnl(pnlIdr = 0.0, pnlPct = 0.0)
    }
}

object RealTradePnlResolver {

    /**
     * @param quantity jumlah koin yang **dijual** (bukan total posisi — jual sebagian hanya
     *   menghitung bagian yang keluar)
     * @param entry harga masuk posisi (harga rata-rata bila DCA)
     * @param exitPrice harga jual efektif
     * @param useMaker `false` = taker (basis default aplikasi)
     * @param slippagePct asumsi slippage per sisi, persen
     */
    fun resolve(
        quantity: Double,
        entry: Double,
        exitPrice: Double,
        exchange: String,
        quoteAsset: String,
        useMaker: Boolean = false,
        slippagePct: Double = FeeSchedule.DEFAULT_SLIPPAGE_PCT
    ): RealTradePnl {
        if (quantity <= 0.0 || entry <= 0.0 || exitPrice <= 0.0) return RealTradePnl.ZERO
        if (!quantity.isFinite() || !entry.isFinite() || !exitPrice.isFinite()) return RealTradePnl.ZERO

        val buyFeePct = FeeSchedule.buyRate(exchange, quoteAsset, useMaker) * 100.0
        val sellFeePct = FeeSchedule.sellRate(exchange, quoteAsset, useMaker) * 100.0

        val cost = FeeSchedule.costBasisNet(quantity, entry, buyFeePct, slippagePct)
        val netSell = FeeSchedule.netSellValue(quantity, exitPrice, sellFeePct, slippagePct)
        val pct = FeeSchedule.netProfitPctFor(
            entry = entry,
            price = exitPrice,
            exchange = exchange,
            quoteAsset = quoteAsset,
            useMaker = useMaker,
            slippagePct = slippagePct
        )
        return RealTradePnl(pnlIdr = netSell - cost, pnlPct = pct)
    }
}

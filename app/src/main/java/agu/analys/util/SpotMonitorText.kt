package agu.analys.util

import agu.analys.config.FeeSchedule

/**
 * Satu aset yang ditampilkan di notifikasi persisten "Spot Monitor"
 * (`service/TradingForegroundService`).
 *
 * **H3** — seluruh angka persen di sini **net** setelah fee beli + fee jual + slippage dua sisi,
 * memakai [FeeSchedule.netProfitPctFor] dengan bursa + kuotasi milik aset itu. Sebelum perbaikan
 * 2026-10-31, `HoldingItem` menghitung `(currentPrice − entryPrice) / entryPrice` (bruto), sehingga
 * untuk posisi yang sama notifikasi menampilkan angka lebih besar dan label "SIAP JUAL" menyala
 * sebelum impas net — berbeda dari `ActiveHoldingSection` di dashboard dan dari halaman detail.
 */
internal data class SpotMonitorHolding(
    val baseAsset: String,
    val exchange: String,
    val quoteAsset: String,
    val currentPrice: Double,
    val entryPrice: Double,
    /** Saldo yang sudah diformat pemanggil (butuh metadata bursa, jadi tidak murni). */
    val quantityLabel: String
) {
    val hasCostBasis: Boolean get() = entryPrice > 0.0 && currentPrice > 0.0

    /**
     * Persen untung **net** di harga terkini. Nol bila harga masuk tidak diketahui: tanpa biaya
     * perolehan tidak ada dasar untuk mengklaim untung, dan menampilkan 0 lebih jujur daripada
     * menebak.
     */
    val netProfitPct: Double
        get() = if (hasCostBasis) {
            FeeSchedule.netProfitPctFor(
                entry = entryPrice,
                price = currentPrice,
                exchange = exchange,
                quoteAsset = quoteAsset
            )
        } else 0.0

    /**
     * "Siap Jual" menuntut untung **net**. Ambangnya sama dengan `ActiveHoldingSection`
     * (`pnlPct >= 0.0`), jadi dashboard dan notifikasi tidak bisa lagi berbeda status untuk aset
     * yang sama.
     */
    val isProfit: Boolean get() = hasCostBasis && netProfitPct >= 0.0
}

/** Satu kartu aset di badan notifikasi persisten. Persen atau nominal di sini selalu net. */
internal fun buildSpotMonitorHoldingCard(item: SpotMonitorHolding): String {
    val currPriceStr = PriceFormatter.formatPrice(item.currentPrice, showSymbol = true, quoteAsset = item.quoteAsset)
    val exBadge = "[${item.exchange}]"

    return if (item.entryPrice > 0.0) {
        val entryPriceStr = PriceFormatter.formatPrice(item.entryPrice, showSymbol = true, quoteAsset = item.quoteAsset)
        val pctFormatted = PriceFormatter.formatPercentage(item.netProfitPct, includePlusSign = true)
        val statusTag = if (item.isProfit) "▲ $pctFormatted  [SIAP JUAL]" else "▼ $pctFormatted  [HOLD]"
        "• ${item.baseAsset} $exBadge $currPriceStr  $statusTag\n  Beli: $entryPriceStr • Saldo: ${item.quantityLabel}"
    } else {
        "• ${item.baseAsset} $exBadge $currPriceStr  [HOLD]\n  Saldo: ${item.quantityLabel}"
    }
}

/** Judul notifikasi persisten: jumlah aset yang untung net, total aset, atau keadaan kosong. */
internal fun buildSpotMonitorTitle(items: List<SpotMonitorHolding>): String {
    val profitCount = items.count { it.isProfit }
    return when {
        profitCount > 0 -> "⚡ $profitCount Aset Siap Profit • Spot Monitor"
        items.isNotEmpty() -> "📈 Spot Monitor • ${items.size} Aset Aktif"
        else -> "📈 Spot Monitor • Menunggu Posisi"
    }
}

/**
 * Teks ringkas notifikasi persisten. Bila ada aset untung net, daftarnya memakai persen **net**;
 * bila tidak ada, hanya harga terkini yang ditampilkan (tanpa klaim untung).
 */
internal fun buildSpotMonitorCollapsedText(items: List<SpotMonitorHolding>): String {
    val profitList = items.filter { it.isProfit }
    return when {
        profitList.isNotEmpty() -> "Siap Jual: " + profitList.joinToString(", ") {
            "${it.baseAsset} (${PriceFormatter.formatPercentage(it.netProfitPct, includePlusSign = true)})"
        }
        items.isNotEmpty() -> "Pantau: " + items.take(3).joinToString(", ") {
            "${it.baseAsset} ${PriceFormatter.formatPrice(it.currentPrice, showSymbol = false, quoteAsset = it.quoteAsset)}"
        }
        else -> "Belum ada aset spot yang dipantau"
    }
}

/** Badan notifikasi persisten: portofolio real lalu simulasi, dipisah bagian. */
internal fun buildSpotMonitorBody(
    realItems: List<SpotMonitorHolding>,
    simItems: List<SpotMonitorHolding>
): String = buildString {
    if (realItems.isEmpty() && simItems.isEmpty()) {
        append("Belum ada koin yang dimiliki saat ini.\nBeli atau tambahkan posisi untuk mulai memantau.")
    } else {
        if (realItems.isNotEmpty()) {
            val realProfit = realItems.count { it.isProfit }
            append("💼 PORTOFOLIO REAL")
            if (realProfit > 0) append(" ($realProfit Siap Jual)")
            append(":\n")
            realItems.forEachIndexed { index, item ->
                append(buildSpotMonitorHoldingCard(item))
                if (index < realItems.size - 1) append("\n\n")
            }
        }
        if (simItems.isNotEmpty()) {
            if (realItems.isNotEmpty()) append("\n\n")
            val simProfit = simItems.count { it.isProfit }
            append("🧪 PORTOFOLIO SIMULASI")
            if (simProfit > 0) append(" ($simProfit Siap Jual)")
            append(":\n")
            simItems.forEachIndexed { index, item ->
                append(buildSpotMonitorHoldingCard(item))
                if (index < simItems.size - 1) append("\n\n")
            }
        }
    }
}.trim()

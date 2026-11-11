package agu.analys.bridge

/**
 * Logika pendorong portofolio ke agen (Agent Bridge, 2026-11-06).
 *
 * Sebelum ini `AgentEventBus.portfolio` punya ruang tayang (replay 1) tetapi **tidak ada
 * produsen**: `publishPortfolioSnapshot` tidak pernah dipanggil, sehingga agen hanya bisa
 * membaca foto `/portfolio` dan harus menebak kapan isinya berubah. Kelas ini memisahkan
 * dua keputusan yang bisa diuji tanpa Android:
 *
 * 1. **Apa yang dianggap "berubah"** — [signature] mengabaikan `ts` dan urutan aset, supaya
 *    snapshot yang isinya sama tidak memicu push palsu setiap putaran.
 * 2. **Kapan dikirim** — [shouldEmit]: perubahan isi, atau heartbeat berkala agar agen tahu
 *    "masih hidup" walaupun saldo tidak bergerak.
 *
 * Sengaja **tidak** mendorong pada setiap tick harga: harga bergerak tiap detik, sedangkan
 * agen tidak butuh P&L detik demi detik. Interval evaluasi dan heartbeat diatur konstanta
 * di bawah; ubah di sini saja, bukan di pemanggil.
 */
object AgentPortfolioPusher {

    /** Jarak antar evaluasi snapshot (bukan jarak antar push — push hanya bila berubah). */
    const val EVAL_INTERVAL_MS = 15_000L

    /** Batas atas diamnya agen: minimal satu snapshot dikirim dalam jendela ini. */
    const val HEARTBEAT_MS = 5 * 60_000L

    /**
     * Cap isi snapshot **tanpa** `ts` dan **tanpa** urutan aset.
     * Dua snapshot yang cap-nya sama = tidak ada yang perlu dikabarkan.
     */
    fun signature(p: AgentPortfolioOutput): String = buildString {
        append(p.exchange).append('|')
        append(p.totalIdrValue).append('|')
        append(p.totalUsdtValue).append('|')
        p.assets.sortedBy { it.symbol }.forEach { a ->
            append(a.symbol).append(':')
            append(a.free).append(',')
            append(a.locked).append(',')
            append(a.avgBuyPrice).append(',')
            append(a.currentPrice).append(',')
            append(a.pnlPercent).append(',')
            append(a.pnlValue).append(';')
        }
    }

    /**
     * @param previousSignature cap snapshot yang terakhir **terkirim**; `null` = belum pernah.
     * @param nextSignature cap snapshot yang baru dibangun.
     * @param nowMs jam sekarang (ms).
     * @param lastEmitMs waktu kirim terakhir; `0` = belum pernah.
     */
    fun shouldEmit(
        previousSignature: String?,
        nextSignature: String,
        nowMs: Long,
        lastEmitMs: Long,
        heartbeatMs: Long = HEARTBEAT_MS
    ): Boolean {
        if (previousSignature == null) return true
        if (nextSignature != previousSignature) return true
        return nowMs - lastEmitMs >= heartbeatMs
    }
}

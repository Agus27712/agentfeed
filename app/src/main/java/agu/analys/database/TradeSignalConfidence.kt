package agu.analys.database

/**
 * J9 (N9) — skor keyakinan yang dicatat untuk sebuah trade.
 *
 * Sebelumnya `recordBuy` punya default `signalConfidence = snapshot?.confidenceScore ?: 75`:
 * bila snapshot tidak ada, jurnal menulis **75** — angka yang tidak pernah dihasilkan siapa pun.
 * Itu pola yang sama dengan pelanggaran B1 (mengarang data agar baris terlihat lengkap), dan
 * angka karangan di jurnal trade merusak audit trail: pembaca tidak bisa membedakan
 * "keyakinan 75" dari "tidak ada data".
 *
 * Aturan:
 * - nilai eksplisit dari pemanggil menang (termasuk `0`);
 * - bila tidak ada, pakai skor dari snapshot;
 * - bila keduanya tidak ada, **0** — artinya "tidak diketahui", bukan angka karangan.
 */
object TradeSignalConfidence {

    fun resolve(explicit: Int?, fromSnapshot: Int?): Int = explicit ?: fromSnapshot ?: 0
}

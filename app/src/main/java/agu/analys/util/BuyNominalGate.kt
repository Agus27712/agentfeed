package agu.analys.util

/**
 * Gerbang nominal beli (paper/real) di form beli — satu-satunya tempat aturan ini
 * ditegakkan. Keputusan owner 2026-10-26: **form beli paper TANPA nominal default** —
 * user mengisi sendiri; tombol hanya mengirim perintah beli bila nominal valid.
 *
 * Latar: dulu state UI memakai literal `50000.0` (Rupiah) yang dipakai apa adanya
 * sebagai nominal kuotasi pair — pada pair USDT itu berarti **$50.000** (pola sama
 * dengan bug nominal IDR lama yang dianggap kuotasi USDT; lihat `AUDIT-2026-11-08.md`). Nilai sekarang selalu
 * milik user; resolver ini hanya menilai nominal itu sebelum tombol diaktifkan.
 *
 * Aturan penilaian (urut, satu keputusan):
 * - `EMPTY`       — nominal kosong/tidak valid (≤ 0, NaN, ∞). Ini kondisi awal normal.
 * - `BELOW_MIN`   — di bawah minimum order pair (`PriceFormatter.minOrderNominal`).
 *                   Bila minimum tak diketahui (kurs USDT belum ada → 0.0), cek ini
 *                   dilewati — bukan mengganti dengan kurs tebakan.
 * - `OVER_BALANCE`— nominal melebihi saldo kuotasi yang diketahui. Saldo 0 dihitung
 *                   saldo yang diketahui (kosong) → fail-closed; engine order tetap
 *                   gerbang terakhir.
 * - `OK`          — boleh submit.
 *
 * Resolver ini murni (tanpa Compose/Android) supaya bisa diuji JVM. Pesan teks untuk
 * user dibentuk di UI, bukan di sini. Kontrol negatif: mengembalikan literal nominal
 * default di `WaitingEntryRadarCard` wajib membuat `PaperBuyNominalSourceTest` GAGAL.
 */
enum class BuyNominalStatus { EMPTY, BELOW_MIN, OVER_BALANCE, OK }

data class BuyNominalGate(val status: BuyNominalStatus) {
    val canSubmit: Boolean get() = status == BuyNominalStatus.OK
}

fun resolveBuyNominalGate(
    nominal: Double,
    minNominal: Double,
    availableQuote: Double
): BuyNominalGate = when {
    nominal.isNaN() || nominal.isInfinite() || nominal <= 0.0 ->
        BuyNominalGate(BuyNominalStatus.EMPTY)
    minNominal > 0.0 && nominal < minNominal ->
        BuyNominalGate(BuyNominalStatus.BELOW_MIN)
    nominal.isInfinite() || nominal > availableQuote ->
        BuyNominalGate(BuyNominalStatus.OVER_BALANCE)
    else -> BuyNominalGate(BuyNominalStatus.OK)
}

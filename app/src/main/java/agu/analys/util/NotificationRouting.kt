package agu.analys.util

/**
 * N13 — kategori notifikasi untuk tiap **jenis peristiwa**.
 *
 * Sebelum perbaikan 2026-10-17, semua laporan eksekusi jual memakai
 * `sendPriceAlertNotification`, yang berarti kategori **PRICE_ALERT** ("Trading & Price Alerts").
 * Akibatnya dua arah, keduanya merusak kontrol pengguna:
 *
 * 1. mematikan "Trailing Stop & Eksekusi" **tidak** mematikan notifikasi trailing;
 * 2. mematikan "Trading & Price Alerts" **mematikan** konfirmasi bahwa order jual uang riil
 *    berhasil atau gagal — informasi paling kritis dan paling jarang muncul.
 *
 * Aturan yang sekarang dipegang:
 *
 * | Peristiwa | Kategori | Sakelar di Pengaturan |
 * |---|---|---|
 * | kandidat BUY | [NotificationCategory.CANDIDATE_BUY] | "Notifikasi Pair Ready & Sinyal Buy" |
 * | exit darurat / stop loss | [NotificationCategory.EMERGENCY_EXIT] | "Notifikasi Exit Darurat & Stop Loss" |
 * | **hasil eksekusi jual & trailing** | [NotificationCategory.TRAILING_STOP] | "Notifikasi Trailing Stop & Eksekusi" |
 * | **alert harga yang dibuat pengguna** | [NotificationCategory.PRICE_ALERT] | "Trading & Price Alerts" |
 *
 * Pembagian penting: `PRICE_ALERT` hanya untuk **peringatan harga yang diminta pengguna sendiri**
 * (mis. "kirim notifikasi saat BTC menembus 1 M"). Laporan "order berhasil/gagal" **bukan** price
 * alert — ia hasil eksekusi, dan pengguna harus tetap menerimanya walau mematikan pengingat harga.
 *
 * Nama helper-nya sengaja dibedakan ([AlertNotificationHelper.sendUserPriceAlertNotification] vs
 * [AlertNotificationHelper.sendExecutionResultNotification]) supaya pemanggil baru tidak bisa
 * "salah kategori" tanpa sadar.
 */
object NotificationRouting {

    /** Kategori untuk laporan hasil eksekusi jual, trailing, dan batch sell. */
    val EXECUTION: NotificationCategory = NotificationCategory.TRAILING_STOP

    /** Kategori untuk pengingat harga yang dibuat pengguna sendiri. */
    val USER_PRICE_ALERT: NotificationCategory = NotificationCategory.PRICE_ALERT
}

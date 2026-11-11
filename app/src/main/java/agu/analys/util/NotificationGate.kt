package agu.analys.util

/**
 * Kategori notifikasi yang punya sakelar sendiri di Pengaturan.
 *
 * Label sengaja sama dengan judul sakelar di `SettingsCategoryDetailContent` supaya pembaca bisa
 * mencocokkan kategori kode dengan apa yang dilihat user.
 */
enum class NotificationCategory(val label: String) {
    CANDIDATE_BUY("Notifikasi Pair Ready & Sinyal Buy"),
    PRICE_ALERT("Trading & Price Alerts"),
    TRAILING_STOP("Notifikasi Trailing Stop & Eksekusi"),
    EMERGENCY_EXIT("Notifikasi Exit Darurat & Stop Loss")
}

/**
 * Potret sakelar notifikasi user.
 *
 * Semua default `true` supaya pengguna lama yang belum pernah menyentuh Pengaturan tidak
 * kehilangan notifikasi apa pun saat gerbang ini dipasang.
 */
data class NotificationSettings(
    val masterEnabled: Boolean = true,
    val candidateBuyEnabled: Boolean = true,
    val priceAlertsEnabled: Boolean = true,
    val trailingStopEnabled: Boolean = true,
    val emergencyExitEnabled: Boolean = true
)

/**
 * Gerbang tunggal: boleh mengirim notifikasi kategori [category] atau tidak.
 *
 * **Fungsi murni** supaya bisa diuji tanpa Android dan supaya keputusannya bisa dibaca di satu
 * tempat. Sebelum perbaikan 2026-10-16 keputusan ini tidak ada sama sekali: sakelar master hanya
 * dipatuhi di `TradingViewModelSync` / `CandidateScanWorker` / `TradingForegroundService`, dan
 * **keempat** sakelar kategori tidak dibaca oleh jalur pengiriman mana pun — Pengaturan
 * menawarkan empat sakelar yang tidak melakukan apa-apa.
 *
 * Aturan: sakelar master mati ⇒ **semua** kategori mati, karena di UI sakelar kategori memang
 * disembunyikan saat master mati (mereka sub-pengaturan, bukan pengaturan sejajar).
 *
 * Catatan penting: gerbang ini **hanya** memengaruhi pengiriman notifikasi. Eksekusi jual
 * otomatis (`executeAutoSellOrder`) tidak boleh ikut terblokir — mematikan notifikasi bukan
 * berarti mematikan perlindungan posisi.
 */
fun allowsNotification(settings: NotificationSettings, category: NotificationCategory): Boolean {
    if (!settings.masterEnabled) return false
    return when (category) {
        NotificationCategory.CANDIDATE_BUY -> settings.candidateBuyEnabled
        NotificationCategory.PRICE_ALERT -> settings.priceAlertsEnabled
        NotificationCategory.TRAILING_STOP -> settings.trailingStopEnabled
        NotificationCategory.EMERGENCY_EXIT -> settings.emergencyExitEnabled
    }
}

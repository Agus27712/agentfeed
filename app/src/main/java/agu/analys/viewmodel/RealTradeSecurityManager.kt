package agu.analys.viewmodel

import agu.analys.service.TokocryptoMarketService
import agu.analys.util.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class RealTradeSecurityManager(
    private val scope: CoroutineScope,
    private val prefs: AppPreferences
) {
    private val _isRealBuyEnabled = MutableStateFlow(false)
    val isRealBuyEnabled: StateFlow<Boolean> = _isRealBuyEnabled.asStateFlow()

    private val _publicIp = MutableStateFlow<String?>(null)
    val publicIp: StateFlow<String?> = _publicIp.asStateFlow()

    private val _isPinRequired = MutableStateFlow(prefs.hasSecurityPin())
    val isPinRequired: StateFlow<Boolean> = _isPinRequired.asStateFlow()

    private val _isPinUnlocked = MutableStateFlow(!prefs.hasSecurityPin())
    val isPinUnlocked: StateFlow<Boolean> = _isPinUnlocked.asStateFlow()

    private val _failedPinAttempts = MutableStateFlow(prefs.failedPinAttempts)
    val failedPinAttempts: StateFlow<Int> = _failedPinAttempts.asStateFlow()

    /** Kapan sesi PIN terakhir dibuka (ms). 0 = belum pernah. */
    private var pinUnlockedAtMs: Long = 0L

    companion object {
        /**
         * Sesi PIN kedaluwarsa otomatis setelah 10 menit.
         *
         * Sebelumnya sesi PIN **tidak pernah kedaluwarsa** — sekali dibuka, gerbangnya
         * terbuka selama proses aplikasi hidup. Kalau HP berpindah tangan atau aplikasi
         * dibiarkan terbuka, siapa pun bisa memasang order riil.
         */
        const val PIN_SESSION_TIMEOUT_MS = 10 * 60 * 1000L

        /** Jumlah percobaan PIN salah sebelum data keamanan dibersihkan (dijanjikan di UI). */
        const val MAX_PIN_ATTEMPTS = 5
    }

    /**
     * True bila sesi PIN masih berlaku. Bila sudah kedaluwarsa, sesi dikunci otomatis.
     * Dipakai sebagai gerbang sebelum setiap order riil.
     */
    fun isPinSessionValid(): Boolean {
        if (!prefs.hasSecurityPin()) return true
        if (!_isPinUnlocked.value) return false
        if (System.currentTimeMillis() - pinUnlockedAtMs > PIN_SESSION_TIMEOUT_MS) {
            Timber.i("Sesi PIN kedaluwarsa (>${PIN_SESSION_TIMEOUT_MS / 60000} menit) — dikunci otomatis")
            lockPin()
            return false
        }
        return true
    }

    /** Tokocrypto tidak memakai IP whitelist, jadi pemeriksaan IP publik tidak diperlukan. */
    fun checkPublicIp() {
        _publicIp.value = "Tidak diperlukan untuk Tokocrypto"
    }

    /**
     * Verifikasi PIN dengan pembatasan percobaan.
     *
     * Sebelumnya jumlah percobaan salah dicatat oleh `recordFailedPinAttempt()` yang
     * **tidak pernah dipanggil**, sehingga PIN 6 digit bisa dicoba tanpa batas dan janji
     * "5x salah auto-wipe" di UI tidak pernah terjadi.
     */
    fun verifyPin(pin: String): Boolean {
        if (prefs.isPinResetRequired) return false
        val ok = prefs.verifySecurityPin(pin)
        if (ok) {
            prefs.resetFailedPinAttempts()
            _failedPinAttempts.value = 0
            pinUnlockedAtMs = System.currentTimeMillis()
            _isPinUnlocked.value = true
            return true
        }
        val attempts = prefs.recordFailedPinAttempt()
        _failedPinAttempts.value = attempts
        Timber.w("PIN salah (percobaan ke-$attempts dari $MAX_PIN_ATTEMPTS)")
        if (attempts >= MAX_PIN_ATTEMPTS) {
            Timber.e("Batas percobaan PIN terlampaui — membersihkan kredensial & PIN")
            prefs.wipeAllRealSecurityData()
            prefs.isPinResetRequired = true
            _isPinUnlocked.value = false
            _isRealBuyEnabled.value = false
            prefs.isRealBuyModeEnabled = false
        }
        return false
    }

    fun lockPin() {
        _isRealBuyEnabled.value = false
        pinUnlockedAtMs = 0L
        _isPinUnlocked.value = !prefs.hasSecurityPin()
    }

    /** Berapa percobaan tersisa sebelum data keamanan dibersihkan. */
    fun remainingPinAttempts(): Int =
        (MAX_PIN_ATTEMPTS - _failedPinAttempts.value).coerceAtLeast(0)

    fun setRealBuyMode(enabled: Boolean, pin: String? = null): Boolean {
        _isPinRequired.value = prefs.hasSecurityPin()
        if (!enabled) {
            _isRealBuyEnabled.value = false
            prefs.isRealBuyModeEnabled = false
            return true
        }
        if (!prefs.hasSecurityPin()) {
            _isRealBuyEnabled.value = true
            prefs.isRealBuyModeEnabled = true
            _isPinUnlocked.value = true
            return true
        }
        if (pin != null && verifyPin(pin)) {
            _isRealBuyEnabled.value = true
            prefs.isRealBuyModeEnabled = true
            _isPinUnlocked.value = true
            return true
        }
        return false
    }
}

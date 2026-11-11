package agu.analys.util

// Stub yang HARUS sejalan dengan agu/analys/util/AppLogManager.kt asli:
// kesalahan di sini membuat smoke hijau/merah berbeda dari aplikasi (pola B4/B3).
object AppLogManager {
    fun info(tag: String, message: String) = println("$tag: $message")
    fun error(tag: String, message: String, t: Throwable? = null) {
        System.err.println("$tag: $message ${t?.message ?: ""}")
        t?.printStackTrace()
    }
}

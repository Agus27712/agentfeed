package agu.analys.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **K15** — tipe simbol Tokocrypto yang tidak dikenal harus jujur "tidak diketahui", bukan
 * diam-diam dianggap Type 1.
 *
 * Cacat lama: `getSymbolType` mengembalikan `1` untuk simbol yang tidak ada di peta. Kesalahan
 * konfigurasi (mis. format pair salah) mengarah ke host Type 1 tanpa satu pun jejak log.
 *
 * Perbaikan: konstanta [TokocryptoSymbolRepository.SYMBOL_TYPE_UNKNOWN] = 0 + log peringatan
 * sekali per simbol. Host tetap Type 1 (perilaku lama dipertahankan) karena semua pemanggil
 * membandingkan `== 3`, tetapi kini ada log.
 *
 * Kontrol negatif: kembalikan `return 1` (atau `?: 1`) di `getSymbolType` → uji ini GAGAL.
 */
class TokocryptoSymbolTypeUnknownTest {

    private fun berkas(relatif: String): String {
        val base = File(System.getProperty("user.dir"))
        val kandidat = listOf(
            File(base, "src/main/java/agu/analys/$relatif"),
            File(base, "app/src/main/java/agu/analys/$relatif")
        )
        val file = kandidat.firstOrNull { it.exists() }
            ?: error("$relatif tidak ditemukan dari user.dir=${base.absolutePath}")
        return file.readLines()
            .filterNot { baris ->
                val s = baris.trimStart()
                s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")
            }
            .joinToString("\n")
    }

    private val repoKode: String by lazy { berkas("data/TokocryptoSymbolRepository.kt") }
    private val wsKode: String by lazy { berkas("service/TokocryptoMarketWebSocket.kt") }

    private fun badanFungsi(kode: String, tandaTangan: String): String {
        val mulai = kode.indexOf(tandaTangan)
        require(mulai >= 0) { "tidak ditemukan: $tandaTangan" }
        val penanda = listOf("\n    private fun ", "\n    fun ", "\n    suspend fun ")
        val batas = penanda
            .map { kode.indexOf(it, mulai + tandaTangan.length) }
            .filter { it >= 0 }
            .minOrNull() ?: kode.length
        return kode.substring(mulai, batas)
    }

    @Test
    fun `tipe tak dikenal punya konstanta sendiri`() {
        assertTrue(
            "nilai tak diketahui harus punya nama, bukan angka 1 yang tertanam",
            repoKode.contains("SYMBOL_TYPE_UNKNOWN = 0")
        )
    }

    @Test
    fun `getSymbolType mengembalikan unknown saat simbol tak ada di peta`() {
        val badan = badanFungsi(repoKode, "fun getSymbolType(")
        assertTrue(badan.contains("return SYMBOL_TYPE_UNKNOWN"))
        assertFalse("K15: default `1` menyembunyikan kesalahan konfigurasi", badan.contains("?: 1"))
        assertFalse("jangan lagi mengembalikan 1 diam-diam", Regex("""return\s+1\b""").containsMatchIn(badan))
    }

    @Test
    fun `peringatan tak dikenal hanya sekali per simbol`() {
        assertTrue(repoKode.contains("warnUnknownSymbolTypeOnce("))
        assertTrue("wajib dedup agar log tidak dibanjiri", repoKode.contains("ConcurrentHashMap.newKeySet"))
    }

    @Test
    fun `host Type 1 tetap dipakai untuk tipe tak dikenal`() {
        val badan = badanFungsi(wsKode, "fun hostForSymbolType(")
        assertTrue(
            "perilaku lama (0 -> host Type 1) dipertahankan lewat cabang else",
            badan.contains("else ->")
        )
    }
}

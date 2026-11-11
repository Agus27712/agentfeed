package agu.analys.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **K10** — status "live" WebSocket memakai flag khusus bursa aktif.
 *
 * Cacat lama: satu flag `wsLive` di-set `false` oleh callback mana pun, sehingga socket yang
 * terputus menjatuhkan status live, `wsFresh` menjadi `false`, dan REST fallback diforce tanpa
 * perlu.
 *
 * Kontrol negatif: kembalikan satu flag `private var wsLive = false` dan panggilan
 * `wsLive = true/false` → uji "tidak ada flag live tunggal" GAGAL.
 */
class MarketDataLiveFlagTest {

    private val kode: String by lazy {
        val base = File(System.getProperty("user.dir"))
        val kandidat = listOf(
            File(base, "src/main/java/agu/analys/viewmodel/MarketDataCoordinator.kt"),
            File(base, "app/src/main/java/agu/analys/viewmodel/MarketDataCoordinator.kt")
        )
        val file = kandidat.firstOrNull { it.exists() }
            ?: error("MarketDataCoordinator.kt tidak ditemukan dari user.dir=${base.absolutePath}")
        file.readLines()
            .filterNot { baris ->
                val s = baris.trimStart()
                s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")
            }
            .joinToString("\n")
    }

    @Test
    fun `status live memakai flag Tokocrypto`() {
        assertTrue("butuh flag Tokocrypto", kode.contains("wsLiveToko"))
    }

    @Test
    fun `tidak ada flag live tunggal`() {
        assertFalse(
            "satu flag global membuat satu bursa mematikan status bursa lain",
            Regex("""\bvar wsLive\b""").containsMatchIn(kode)
        )
        assertFalse(kode.contains("wsLive = true"))
        assertFalse(kode.contains("wsLive = false"))
    }

    @Test
    fun `kelayakan ws memakai status bursa aktif`() {
        assertTrue("butuh penentu bursa aktif", kode.contains("activeWsLive()"))
        val idx = kode.indexOf("val wsFresh")
        assertTrue("wsFresh harus ada", idx >= 0)
        assertTrue(
            "wsFresh wajib memakai status bursa aktif, bukan status bursa lain",
            kode.substring(idx, idx + 160).contains("activeWsLive()")
        )
    }

    @Test
    fun `callback bursa menyentuh flag Tokocrypto`() {
        assertTrue("onConnected Tokocrypto menandai bursa Tokocrypto", kode.contains("wsLiveToko = true"))
        assertTrue(kode.contains("wsLiveToko = false"))
    }
}

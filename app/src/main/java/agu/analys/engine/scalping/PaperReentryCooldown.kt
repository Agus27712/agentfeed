package agu.analys.engine.scalping

/**
 * Jeda minimum antar siklus **auto-buy paper** untuk satu pair (fix temuan 2026-10-26).
 *
 * ## Latar belakang
 *
 * Mode scalping tidak punya jeda apa pun: begitu exit paper terpicu (`finalizePaperExit`
 * → `markSold`), posisi pair itu hilang dan `tryPaperAutoBuy` hanya mencegah posisi
 * dobel — bukan entri-ulang. Pada pair yang sedang naik kuat (contoh terukur: WINUSDT
 * 25/10/2026, harga memuncak +4,8% sekitar 21:45 WIB), evaluator menilai kondisi masih
 * trending, sinyal BUY selanjutnya tetap terkirim, dan auto-buy langsung membuka posisi
 * lagi — diputar pada baris 1 M1 setelah sell. Akibatnya friksi dua sisi terus dibayar
 * pada sinyal yang sama tanpa evaluasi baru (lihat `AUDIT-2026-11-08.md`).
 *
 * Resolver ini **murni dan teruji** — logika jeda hidup di sini, runtime hanya melapor.
 *
 * ## Aturan
 *
 * - `cooldownMinutes <= 0` → jeda nonaktif (perilaku lama).
 * - `lastSoldAtMs <= 0` → tidak ada catatan sell → boleh masuk.
 * - `nowMs - lastSoldAtMs < 0` (jam perangkat mundur) → diperlakukan **baru saja dijual**,
 *   supaya penyesuaian jam tidak menjelma jalur lolos melewati jeda.
 * - Jeda diberlakukan **per pair, per bursa** — catatan sell disimpan `SpotPositionStore`
 *   hanya untuk `isReal = false`, tidak menyentuh posisi uang riil.
 */
sealed class PaperReentryVerdict {
    /** Entri-ulang sampai diizinkan. */
    data class Allow(val lastSoldAtMs: Long) : PaperReentryVerdict()

    /** Ditahan; [remainingMs] jeda masih tersisa. */
    data class Wait(
        val remainingMs: Long,
        val lastSoldAtMs: Long,
        val cooldownMs: Long
    ) : PaperReentryVerdict()
}

object PaperReentryCooldown {

    /** Konversi menit ke milidetik, satu sumber untuk runtime dan uji. */
    fun cooldownMs(minutes: Int): Long = 60_000L * minutes

    /**
     * Putuskan apakah auto-buy boleh membuka posisi baru pada pair yang diberikan.
     *
     * @param lastSoldAtMs waktu sell terakhir posisi paper pair itu (0 = tidak ada catatan).
     * @param nowMs waktu sekarang.
     * @param cooldownMinutes jeda minimum dalam menit; `0` atau negatif menonaktifkan.
     */
    fun verify(lastSoldAtMs: Long, nowMs: Long, cooldownMinutes: Int): PaperReentryVerdict {
        val cooldownMs = cooldownMs(cooldownMinutes)
        if (cooldownMs <= 0L || lastSoldAtMs <= 0L) return PaperReentryVerdict.Allow(lastSoldAtMs)

        // Jam perangkat mundur tidak boleh menjelma jalur lolos: sisa dihitung tetap dari nol.
        val elapsedMs = (nowMs - lastSoldAtMs).coerceAtLeast(0L)
        return if (elapsedMs < cooldownMs) {
            PaperReentryVerdict.Wait(
                remainingMs = cooldownMs - elapsedMs,
                lastSoldAtMs = lastSoldAtMs,
                cooldownMs = cooldownMs
            )
        } else {
            PaperReentryVerdict.Allow(lastSoldAtMs)
        }
    }
}

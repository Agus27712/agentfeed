package agu.analys.engine.scalping

/**
 * Penentu bursa mana yang perlu direhidrasi (P1).
 *
 * ## Kenapa perlu dipisah
 *
 * Sebelum perbaikan P1, rehidrasi hanya membaca `prefs.marketDataSource.name` — bursa yang
 * **sedang aktif**. Akibatnya rantai ini terjadi begitu saja:
 *
 * ```
 * posisi paper di partisi lama (auto_sell_enabled = false, mesin hanya di memori)
 *   -> sumber data aktif berubah
 *   -> app di-kill, lalu dibuka kembali (bursa aktif = Tokocrypto)
 *   -> rehidrasi hanya membaca partisi tokocrypto_sim_*
 *   -> mesin partisi lama TIDAK PERNAH dibangun
 *   -> posisi itu tidak punya SL/TP/BE/trailing/time-stop, selamanya
 * ```
 *
 * Karena keputusannya kini murni, ia bisa diuji tanpa ViewModel maupun Android — padahal
 * pemanggilnya (`rehydratePaperAutoExit`) adalah ekstensi ViewModel yang tidak bisa diuji.
 */
object PaperRehydrateScope {

    /**
     * Urutan bursa yang perlu direhidrasi.
     *
     * Aturan:
     * 1. **semua** bursa yang punya posisi paper tersimpan ikut — bukan hanya bursa aktif;
     * 2. bursa aktif didahulukan, supaya posisi yang sedang dilihat pengguna pulih lebih dulu
     *    dan mesinnya sudah siap saat tick pertama masuk;
     * 3. sisanya diurutkan alfabetis agar hasilnya deterministik (bisa dibandingkan antar-run).
     *
     * @param storedExchanges bursa yang punya posisi paper tersimpan; boleh berisi duplikat,
     *   kosong, atau spasi berlebih
     * @param activeExchange bursa yang sedang dipakai; kosong berarti tidak ada preferensi
     * @return daftar bursa tanpa duplikat, huruf besar, tanpa spasi; kosong bila tidak ada
     *   posisi paper tersimpan sama sekali
     */
    fun decide(storedExchanges: Collection<String>, activeExchange: String): List<String> {
        val distinct = storedExchanges
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
        if (distinct.isEmpty()) return emptyList()

        val active = activeExchange.trim().uppercase()
        if (active.isEmpty() || active !in distinct) return distinct
        return listOf(active) + distinct.filter { it != active }
    }
}

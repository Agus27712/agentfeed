package agu.analys.engine.scalping

/**
 * ATR% bar M1 terhadap harga acuan (P4).
 *
 * Dipakai mesin exit paper untuk S8 — ambang breakeven dan jarak trailing yang proporsional
 * volatilitas, bukan persen tetap.
 *
 * Dulu perhitungan ini hidup sebagai ekstensi `private` di `PaperAutoTradeCoordinator`, sehingga
 * **hanya** jalur auto-buy yang bisa memakainya; jalur beli manual tidak dapat memanggilnya sama
 * sekali dan selalu mengirim `atrPct = 0.0`. Karena `0.0` berarti "ATR tidak diketahui", mesin
 * menerima `0.0` itu apa adanya dan menyimpannya ke disk — jadi kerusakannya **persisten**,
 * bukan pulih setelah restart.
 *
 * Karena itu rumusnya dipindah ke sini sebagai fungsi murni: satu rumus, bisa diuji tanpa
 * ViewModel, dan bisa dipakai semua pemanggil.
 */
object PaperAtr {

    /**
     * ATR sebagai persen dari [referencePrice].
     *
     * @param atr ATR absolut dalam satuan harga; `<= 0` atau tidak hingga berarti belum tersedia
     * @param referencePrice harga acuan (biasanya harga entry); `<= 0` berarti belum ada acuan
     * @return persen ATR, atau `0.0` bila salah satu masukan belum layak — `0.0` berarti
     *   "tidak diketahui", sehingga mesin jatuh ke ambang persen tetap
     */
    fun of(atr: Double, referencePrice: Double): Double {
        if (!referencePrice.isFinite() || referencePrice <= 0.0) return 0.0
        if (!atr.isFinite() || atr <= 0.0) return 0.0
        val pct = atr / referencePrice * 100.0
        return if (pct.isFinite() && pct > 0.0) pct else 0.0
    }
}

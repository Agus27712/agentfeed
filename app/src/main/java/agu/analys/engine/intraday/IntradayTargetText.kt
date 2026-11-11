package agu.analys.engine.intraday

/**
 * Teks kartu target mode intraday (paket F).
 *
 * Dipisah sebagai fungsi murni supaya bisa diuji tanpa Android/Robolectric — pola yang sama
 * dengan aturan "ekstrak pembentuk teks jadi fungsi murni" di `app/AGENTS.md`. Isi teksnya
 * mengikat tiga hal yang pernah hilang dari kartu intraday:
 *
 * 1. label R:R **wajib** menyebut `TP1` (sejak paket B3 gate dinilai dari TP1, bukan TP2);
 * 2. persen target adalah perubahan **harga (gross)**, sedangkan angka net dihitung setelah
 *    fee beli + fee jual + slippage dua sisi;
 * 3. angka historis belum punya sampel yang cukup — jangan dibaca sebagai win rate.
 */
object IntradayTargetText {

    /** Label baris net R:R di kartu target. Wajib menyebut TP1. */
    const val NET_RR_ROW_LABEL: String = "Net R:R TP1"

    /** Catatan basis angka di bawah baris target. */
    const val NET_RR_NOTE: String =
        "Persen target = perubahan harga (gross). Net dihitung setelah fee beli + fee jual + " +
            "slippage dua sisi; angka historis belum punya sampel yang cukup."
}

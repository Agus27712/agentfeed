package agu.analys.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Penjaga **drift angka** tarif biaya Tokocrypto yang dipakai server `nadi agentfeed`.
 *
 * Sumber kebenaran: `AppConfiguration.FeeSchedule` (pecahan) didokumentasikan di
 * `FEE_TRUTH.md` dan diterbitkan ke agen lewat `GET /api/v1/capabilities` serta
 * `GET /api/v1/market/context`. Agen OMP memakai angka ini untuk menghitung target
 * profit net; kalau konstanta bergeser tanpa pembaruan dokumen, keputusan agen ikut
 * bergeser. Karena itu uji ini sengaja **murni konstanta**, tanpa membaca berkas
 * eksternal: folder arsip riset dan mirror replay offline sudah dihapus 2026-11-08.
 *
 * Kontrol negatif: ubah satu konstanta (mis. `TOKOCRYPTO_IDR_SELL_TAKER` menjadi
 * 0.004222) maka uji [konstanta Tokocrypto sama dengan FEE_TRUTH] harus GAGAL.
 */
class FeeTruthConstantsTest {

    private fun pct(pecahan: Double) = pecahan * 100.0

    @Test
    fun `konstanta Tokocrypto sama dengan FEE_TRUTH`() {
        assertEquals(0.4044, pct(FeeSchedule.TOKOCRYPTO_USDT_BUY_TAKER), 1e-9)
        assertEquals(0.4044, pct(FeeSchedule.TOKOCRYPTO_USDT_SELL_TAKER), 1e-9)
        assertEquals(0.2222, pct(FeeSchedule.TOKOCRYPTO_IDR_BUY_TAKER), 1e-9)
        assertEquals(0.1222, pct(FeeSchedule.TOKOCRYPTO_IDR_BUY_MAKER), 1e-9)
        assertEquals(0.4322, pct(FeeSchedule.TOKOCRYPTO_IDR_SELL_TAKER), 1e-9)
        assertEquals(0.3322, pct(FeeSchedule.TOKOCRYPTO_IDR_SELL_MAKER), 1e-9)
    }

    @Test
    fun `slippage base case 0_08 persen per sisi`() {
        assertEquals(0.08, FeeSchedule.DEFAULT_SLIPPAGE_PCT, 1e-9)
    }

    @Test
    fun `total friksi aditif dua sisi`() {
        assertEquals(0.9688, 2 * 0.4044 + 2 * 0.08, 1e-9)
        assertEquals(0.8144, 0.2222 + 0.4322 + 2 * 0.08, 1e-9)
        assertEquals(0.6144, 0.1222 + 0.3322 + 2 * 0.08, 1e-9)
    }

    @Test
    fun `impas net multiplikatif bukan sama dengan friksi aditif`() {
        // 0,9688% = friksi aditif; 0,9735% = kenaikan harga yang benar-benar menutup
        // semua biaya. Dua angka ini berbeda fungsi dan KEDUANYA benar — jangan
        // saling menggantikan di payload yang dikirim ke agen.
        val impasUsdt = FeeSchedule.roundTripPctMultiplicative("TOKOCRYPTO", "USDT")
        assertEquals(0.9735, impasUsdt, 5e-4)

        val impasIdrTaker = FeeSchedule.roundTripPctMultiplicative("TOKOCRYPTO", "IDR")
        assertEquals(0.8186, impasIdrTaker, 5e-4)
    }

    @Test
    fun `configFor memilih tarif sesuai kuotasi pair`() {
        val idr = FeeSchedule.configFor("TOKOCRYPTO", "IDR")
        assertEquals(0.2222, idr.buyTakerPct, 1e-9)
        assertEquals(0.4322, idr.sellTakerPct, 1e-9)

        val usdt = FeeSchedule.configFor("TOKOCRYPTO", "USDT")
        assertEquals(0.4044, usdt.buyTakerPct, 1e-9)
        assertEquals(0.4044, usdt.sellTakerPct, 1e-9)
    }

    @Test
    fun `buyRate dan sellRate konsisten dengan konstanta`() {
        assertEquals(
            FeeSchedule.TOKOCRYPTO_USDT_BUY_TAKER,
            FeeSchedule.buyRate("TOKOCRYPTO", "USDT"), 1e-12
        )
        assertEquals(
            FeeSchedule.TOKOCRYPTO_IDR_SELL_MAKER,
            FeeSchedule.sellRate("TOKOCRYPTO", "IDR", useMaker = true), 1e-12
        )
    }
}

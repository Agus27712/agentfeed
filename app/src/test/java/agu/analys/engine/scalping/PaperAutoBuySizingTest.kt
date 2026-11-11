package agu.analys.engine.scalping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ukuran posisi auto-buy paper (P2).
 *
 * Kelas ini mengunci dua cacat yang membuat auto-buy **tidak pernah** tereksekusi:
 *
 * 1. `fixedQuoteAmount` dipakai apa adanya sebagai kas kuotasi, padahal nilainya Rupiah —
 *    sehingga Rp 100.000 menjadi $100.000 pada pair USDT.
 * 2. Batas anggaran memakai saldo mentah, padahal order MARKET memotong fee **dan** slippage
 *    di atas nilai posisi. Setiap nominal yang ≥ saldo jadi mustahil tereksekusi.
 */
class PaperAutoBuySizingTest {

    private companion object {
        /** Tarif Tokocrypto pair USDT — all-in per sisi. */
        const val TOKO_USDT_FEE = 0.004044

        /** Slippage default `ScalpingRuntimeConfig` (persen). */
        const val SLIP_PCT = 0.08

        const val RATE = 16_400.0

        fun cfg(
            allocation: PaperAutoBuyAllocation = PaperAutoBuyAllocation.FIXED_QUOTE,
            fixedIdrAmount: Double = 100_000.0,
            riskPctPerTrade: Double = 1.0
        ) = PaperAutoTradeConfig(
            allocation = allocation,
            fixedIdrAmount = fixedIdrAmount,
            riskPctPerTrade = riskPctPerTrade
        )
    }

    // ── Cacat lama, didokumentasikan supaya tidak kembali ────────────────────────────

    /**
     * Karakterisasi bug: dengan rumus lama, kebutuhan order **selalu** melebihi saldo
     * sebesar fee + slippage, sehingga order ditolak 100% setiap kali nominal ≥ saldo.
     *
     * Angka ini yang muncul di log pengguna: "Dibutuhkan $560.41, saldo $557.71".
     */
    @Test
    fun rumusLamaSelaluDitolak_saatNominalMelebihiSaldo() {
        val available = 557.71
        val oldBudget = minOf(100_000.0, available) // potongan ke saldo mentah
        val oldRequired = oldBudget * (1.0 + SLIP_PCT / 100.0) * (1.0 + TOKO_USDT_FEE)

        assertEquals(557.71, oldBudget, 0.0001)
        assertEquals(560.41, oldRequired, 0.01)
        assertTrue(
            "Kebutuhan lama harus melebihi saldo — inilah sebab auto-buy tidak pernah jalan",
            oldRequired > available
        )
    }

    // ── Cacat 1: nominal Rupiah wajib dikonversi untuk pair USDT ─────────────────────

    @Test
    fun pairUsdt_nominalRupiahDikonversi_bukanDipakaiApaAdanya() {
        val available = 557.71

        val sizing = cfg().plan(
            availableQuote = available,
            price = 1.0,
            stopLoss = 0.95,
            quoteIsUsdt = true,
            usdtIdrRate = RATE,
            feeRate = TOKO_USDT_FEE,
            slippagePct = SLIP_PCT
        )

        assertTrue("Harus boleh dibuka", sizing is AutoBuySizing.Ok)
        val ok = sizing as AutoBuySizing.Ok

        // Rp 100.000 / 16.400 = $6,0976 — bukan 100.000 USDT, dan bukan seluruh saldo.
        assertEquals(6.0976, ok.notionalQuote, 0.0001)
        assertTrue("Tidak boleh memakai seluruh saldo", ok.notionalQuote < available)

        val required = ok.notionalQuote * (1.0 + SLIP_PCT / 100.0) * (1.0 + TOKO_USDT_FEE)
        assertTrue("Kebutuhan harus muat di saldo", required <= available)
        assertEquals(6.1271, required, 0.001)
    }

    @Test
    fun pairUsdt_kursBelumTersedia_dilewati() {
        val sizing = cfg().plan(
            availableQuote = 557.71,
            price = 1.0,
            stopLoss = 0.95,
            quoteIsUsdt = true,
            usdtIdrRate = 0.0,
            feeRate = TOKO_USDT_FEE,
            slippagePct = SLIP_PCT
        )

        assertTrue("Rate 0 harus dilewati, bukan ditebak", sizing is AutoBuySizing.Skip)
        assertTrue(
            (sizing as AutoBuySizing.Skip).reason.contains("kurs USDT/IDR", ignoreCase = true)
        )
    }

    // ── Cacat 2: anggaran wajib menyisihkan fee + slippage ──────────────────────────

    @Test
    fun nominalMelebihiAnggaran_dilewati_bukanDipotong() {
        // Saldo 5 USDT < Rp 100.000 (≈ 6,0976 USDT).
        val sizing = cfg().plan(
            availableQuote = 5.0,
            price = 1.0,
            stopLoss = 0.95,
            quoteIsUsdt = true,
            usdtIdrRate = RATE,
            feeRate = TOKO_USDT_FEE,
            slippagePct = SLIP_PCT
        )

        assertTrue(
            "Nominal yang tidak muat harus dilewati, bukan diperkecil diam-diam",
            sizing is AutoBuySizing.Skip
        )
    }

    @Test
    fun saldoTepatSepertiNominalTetapGagal_karenaFeeDanSlippage() {
        // Saldo persis sebesar nilai posisi — tanpa menyisihkan biaya, order ini akan ditolak engine.
        val sizing = cfg().plan(
            availableQuote = 6.097560975609756,
            price = 1.0,
            stopLoss = 0.95,
            quoteIsUsdt = true,
            usdtIdrRate = RATE,
            feeRate = TOKO_USDT_FEE,
            slippagePct = SLIP_PCT
        )

        assertTrue("Harus dilewati karena fee+slippage tidak muat", sizing is AutoBuySizing.Skip)
    }

    @Test
    fun nominalTepatSamaDenganAnggaran_masihDiterima() {
        val notional = 100_000.0 / RATE
        val mult = (1.0 + SLIP_PCT / 100.0) * (1.0 + TOKO_USDT_FEE)

        val sizing = cfg().plan(
            availableQuote = notional * mult,
            price = 1.0,
            stopLoss = 0.95,
            quoteIsUsdt = true,
            usdtIdrRate = RATE,
            feeRate = TOKO_USDT_FEE,
            slippagePct = SLIP_PCT
        )

        assertTrue("Batas harus inklusif", sizing is AutoBuySizing.Ok)
        assertEquals(notional, (sizing as AutoBuySizing.Ok).notionalQuote, 1e-9)
    }

    // ── Pair ber-kuotasi IDR: nominal dipakai apa adanya ────────────────────────────

    @Test
    fun pairIdr_nominalDipakaiApaAdanya() {
        val sizing = cfg().plan(
            availableQuote = 1_000_000.0,
            price = 1_000.0,
            stopLoss = 900.0,
            quoteIsUsdt = false,
            usdtIdrRate = 0.0, // tidak relevan untuk pair IDR
            feeRate = 0.002222,
            slippagePct = SLIP_PCT
        )

        assertTrue(sizing is AutoBuySizing.Ok)
        val ok = sizing as AutoBuySizing.Ok
        assertEquals(100_000.0, ok.notionalQuote, 0.0001)
        assertEquals(100.0, ok.quantity, 0.0001)
    }

    @Test
    fun pairIdr_saldoKurang_dilewati() {
        val sizing = cfg().plan(
            availableQuote = 50_000.0,
            price = 1_000.0,
            stopLoss = 900.0,
            quoteIsUsdt = false,
            usdtIdrRate = 0.0,
            feeRate = 0.002222,
            slippagePct = SLIP_PCT
        )

        assertTrue(sizing is AutoBuySizing.Skip)
    }

    // ── Mode berbasis risiko: clamp daya beli juga harus sadar biaya ────────────────

    @Test
    fun riskBased_tidakMelebihiDayaBeli_setelahBiaya() {
        val available = 100.0
        val price = 1.0
        val mult = (1.0 + SLIP_PCT / 100.0) * (1.0 + TOKO_USDT_FEE)

        // SL sangat rapat -> ukuran dari risiko jauh melebihi daya beli, jadi harus dipotong.
        val sizing = cfg(allocation = PaperAutoBuyAllocation.RISK_BASED, riskPctPerTrade = 10.0).plan(
            availableQuote = available,
            price = price,
            stopLoss = 0.9999,
            quoteIsUsdt = false,
            usdtIdrRate = 0.0,
            feeRate = TOKO_USDT_FEE,
            slippagePct = SLIP_PCT
        )

        assertTrue(sizing is AutoBuySizing.Ok)
        val ok = sizing as AutoBuySizing.Ok
        val required = ok.quantity * price * mult
        assertTrue("Kas terpakai ($required) tidak boleh melebihi saldo ($available)", required <= available)
    }

    @Test
    fun riskBased_slTidakValid_dilewati() {
        val sizing = cfg(allocation = PaperAutoBuyAllocation.RISK_BASED).plan(
            availableQuote = 100.0,
            price = 1.0,
            stopLoss = 1.0, // SL == entry -> jarak risiko nol
            quoteIsUsdt = false,
            usdtIdrRate = 0.0,
            feeRate = TOKO_USDT_FEE,
            slippagePct = SLIP_PCT
        )

        assertTrue(sizing is AutoBuySizing.Skip)
    }

    // ── Masukan tidak valid ────────────────────────────────────────────────────────

    @Test
    fun hargaTidakValid_dilewati() {
        val sizing = cfg().plan(
            availableQuote = 557.71,
            price = 0.0,
            stopLoss = 0.0,
            quoteIsUsdt = true,
            usdtIdrRate = RATE,
            feeRate = TOKO_USDT_FEE,
            slippagePct = SLIP_PCT
        )

        assertTrue(sizing is AutoBuySizing.Skip)
    }

    @Test
    fun saldoKosong_dilewati() {
        val sizing = cfg().plan(
            availableQuote = 0.0,
            price = 1.0,
            stopLoss = 0.95,
            quoteIsUsdt = true,
            usdtIdrRate = RATE,
            feeRate = TOKO_USDT_FEE,
            slippagePct = SLIP_PCT
        )

        assertTrue(sizing is AutoBuySizing.Skip)
    }

    @Test
    fun defaultNominalTetapSeratusRibuRupiah() {
        assertEquals(100_000.0, PaperAutoTradeConfig.DEFAULT.fixedIdrAmount, 0.0)
    }
}

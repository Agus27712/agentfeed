package agu.analys.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Penjaga gerbang nominal beli (owner 2026-10-26): form beli paper TANPA nominal
 * default — user mengisi sendiri, dan tombol hanya mengirim perintah beli bila
 * nominal valid. Resolver murni `resolveBuyNominalGate` di `util/BuyNominalGate.kt`
 * satu-satunya tempat aturan ini; pesan teks dibentuk di UI.
 */
class BuyNominalGateTest {

    @Test
    fun `nominal kosong atau nol = EMPTY - kondisi awal normal tanpa default`() {
        assertEquals(BuyNominalStatus.EMPTY, resolveBuyNominalGate(0.0, 10_000.0, 10_000_000.0).status)
        assertEquals(BuyNominalStatus.EMPTY, resolveBuyNominalGate(-1.0, 10_000.0, 10_000_000.0).status)
        assertFalse(resolveBuyNominalGate(0.0, 10_000.0, 10_000_000.0).canSubmit)
    }

    @Test
    fun `nominal tidak valid (NaN, tak hingga) = EMPTY`() {
        assertEquals(BuyNominalStatus.EMPTY, resolveBuyNominalGate(Double.NaN, 10_000.0, 10_000_000.0).status)
        assertEquals(BuyNominalStatus.EMPTY, resolveBuyNominalGate(Double.POSITIVE_INFINITY, 10_000.0, 10_000_000.0).status)
    }

    @Test
    fun `di bawah minimum order pair = BELOW_MIN`() {
        // IDR: minimum Rp 10.000
        assertEquals(
            BuyNominalStatus.BELOW_MIN,
            resolveBuyNominalGate(5_000.0, 10_000.0, 10_000_000.0).status
        )
        // USDT: minimum ekuivalen, mis. 0,625 USDT pada kurs 16.000
        assertEquals(
            BuyNominalStatus.BELOW_MIN,
            resolveBuyNominalGate(0.5, 0.625, 1_000.0).status
        )
    }

    @Test
    fun `minimum tak diketahui (0) melewati cek minimum - jangan tebak kurs`() {
        // Kurs USDT/IDR belum tersedia → minNominal 0.0 → cek minimum dilewati,
        // bukan diganti kurs tebakan. Nominal valid tetap OK.
        assertEquals(BuyNominalStatus.OK, resolveBuyNominalGate(3.0, 0.0, 1_000.0).status)
    }

    @Test
    fun `nominal melebihi saldo kuotasi = OVER_BALANCE - saldo 0 dihitung kosong`() {
        assertEquals(
            BuyNominalStatus.OVER_BALANCE,
            resolveBuyNominalGate(50_000.0, 10_000.0, 10_000.0).status
        )
        // Saldo 0 = saldo yang diketahui (kosong) → nominal apa pun (>0) diblokir.
        assertEquals(BuyNominalStatus.OVER_BALANCE, resolveBuyNominalGate(10_000.0, 10_000.0, 0.0).status)
    }

    @Test
    fun `nominal valid, cukup minimum, muat di saldo = OK`() {
        val gate = resolveBuyNominalGate(50_000.0, 10_000.0, 10_000_000.0)
        assertEquals(BuyNominalStatus.OK, gate.status)
        assertTrue(gate.canSubmit)
    }

    /**
     * Regresi persis keluhan owner: nominal Rp 50.000 tidak boleh lolos gerbang
     * sebagai $50.000 pada pair USDT ber-saldo $0. Gerbang wajib menolak.
     */
    @Test
    fun `kasus owner - 50000 pada pair USDT ber-saldo kosong TIDAK boleh submit`() {
        val gate = resolveBuyNominalGate(50_000.0, 0.625, 0.0)
        assertEquals(BuyNominalStatus.OVER_BALANCE, gate.status)
        assertFalse(gate.canSubmit)
    }
}

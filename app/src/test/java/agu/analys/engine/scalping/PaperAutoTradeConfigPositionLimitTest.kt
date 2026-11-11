package agu.analys.engine.scalping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji gerbang **batas jumlah posisi paper bersamaan** (global).
 *
 * Gerbang ini mencegah auto-buy menumpuk posisi sampai saldo kas habis — kas yang sudah
 * terpotong posisi yang masih hold tetap "terpakai", jadi tanpa batas ini order berikutnya
 * ditolak karena saldo kurang, bukan karena sinyalnya buruk.
 *
 * Aritmetika batas sengaja ditaruh di fungsi murni supaya bisa dikunci di sini, terpisah dari
 * Android. `0` berarti **tanpa batas**.
 */
class PaperAutoTradeConfigPositionLimitTest {

    @Test
    fun `default maks posisi adalah tiga`() {
        assertEquals(3, PaperAutoTradeConfig.DEFAULT.maxConcurrentPositions)
    }

    @Test
    fun `batas belum terlampaui bila jumlah di bawah batas`() {
        val cfg = PaperAutoTradeConfig(maxConcurrentPositions = 3)
        assertFalse(cfg.positionLimitReached(2))
    }

    @Test
    fun `batas tercapai bila jumlah sama dengan batas`() {
        val cfg = PaperAutoTradeConfig(maxConcurrentPositions = 3)
        assertTrue(cfg.positionLimitReached(3))
    }

    @Test
    fun `batas terlampaui bila jumlah melebihi batas`() {
        val cfg = PaperAutoTradeConfig(maxConcurrentPositions = 3)
        assertTrue(cfg.positionLimitReached(4))
    }

    @Test
    fun `nol berarti tanpa batas`() {
        val cfg = PaperAutoTradeConfig(maxConcurrentPositions = 0)
        assertFalse(cfg.positionLimitReached(999))
    }

    @Test
    fun `batas satu langsung tercapai pada posisi pertama`() {
        val cfg = PaperAutoTradeConfig(maxConcurrentPositions = 1)
        assertTrue(cfg.positionLimitReached(1))
    }
}

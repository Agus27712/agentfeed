package agu.analys.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J10 (sisa) — parameter arm mesin exit paper hanya boleh berasal dari sinyal milik simbol itu.
 *
 * Jalur yang dijaga: beli **paper manual** (`TradingViewModelSync.kt:343-350`). Sebelum
 * perbaikan, jalur itu membaca `engine.signalState.value` mentah; TP1 pair lain yang berada di
 * bawah entry bisa memicu jual sebagian pada tick pertama.
 */
class PaperArmSignalTest {

    @Test
    fun `simbol sama meneruskan SL dan TP apa adanya`() {
        val p = PaperArmSignal.resolve(
            requestedSymbol = "BTCIDR",
            signalSymbol = "BTCIDR",
            stopLoss = 990_000_000.0,
            tp1 = 1_010_000_000.0,
            tp2 = 1_020_000_000.0
        )
        assertEquals(990_000_000.0, p.stopLoss, 0.0)
        assertEquals(1_010_000_000.0, p.tp1, 0.0)
        assertEquals(1_020_000_000.0, p.tp2, 0.0)
        assertFalse(p.isEmpty)
    }

    @Test
    fun `simbol beda mengosongkan ketiganya`() {
        // inti J10 sisa: TP1 milik ETH tidak boleh meng-arm posisi BTC
        val p = PaperArmSignal.resolve(
            requestedSymbol = "BTCIDR",
            signalSymbol = "ETHIDR",
            stopLoss = 20_000_000.0,
            tp1 = 20_500_000.0,
            tp2 = 21_000_000.0
        )
        assertEquals(0.0, p.stopLoss, 0.0)
        assertEquals(0.0, p.tp1, 0.0)
        assertEquals(0.0, p.tp2, 0.0)
        assertTrue(p.isEmpty)
    }

    @Test
    fun `simbol sinyal kosong dianggap tidak cocok`() {
        // "tidak tahu" bukan "milik kita"
        val p = PaperArmSignal.resolve(
            requestedSymbol = "BTCIDR",
            signalSymbol = "",
            stopLoss = 990_000_000.0,
            tp1 = 1_010_000_000.0,
            tp2 = 1_020_000_000.0
        )
        assertTrue(p.isEmpty)
    }

    @Test
    fun `simbol yang diminta kosong dianggap tidak cocok`() {
        val p = PaperArmSignal.resolve(
            requestedSymbol = " ",
            signalSymbol = "BTCIDR",
            stopLoss = 990_000_000.0,
            tp1 = 1_010_000_000.0,
            tp2 = 1_020_000_000.0
        )
        assertTrue(p.isEmpty)
    }

    @Test
    fun `format simbol berbeda tetap dianggap milik sendiri`() {
        val p = PaperArmSignal.resolve(
            requestedSymbol = "btc_idr",
            signalSymbol = "BTC/IDR",
            stopLoss = 990_000_000.0,
            tp1 = 1_010_000_000.0,
            tp2 = 1_020_000_000.0
        )
        assertEquals(990_000_000.0, p.stopLoss, 0.0)
        assertEquals(1_010_000_000.0, p.tp1, 0.0)
    }

    @Test
    fun `simbol sama dengan nilai nol tetap nol`() {
        // tidak mengarang nilai saat sinyal tidak membawa TP/SL
        val p = PaperArmSignal.resolve(
            requestedSymbol = "BTCIDR",
            signalSymbol = "BTCIDR",
            stopLoss = 0.0,
            tp1 = 0.0,
            tp2 = 0.0
        )
        assertTrue(p.isEmpty)
    }

    @Test
    fun `SL negatif tidak divalidasi di sini`() {
        // validasi sisi harga milik PaperStopResolver; resolver ini hanya soal atribusi
        val p = PaperArmSignal.resolve(
            requestedSymbol = "BTCIDR",
            signalSymbol = "BTCIDR",
            stopLoss = -1.0,
            tp1 = 1_010_000_000.0,
            tp2 = 0.0
        )
        assertEquals(-1.0, p.stopLoss, 0.0)
        assertFalse(p.isEmpty)
    }

    @Test
    fun `bursa tidak ikut menentukan atribusi`() {
        // simbol sama di bursa berbeda tetap simbol yang sama; bursa ditangani pemanggil
        val p = PaperArmSignal.resolve(
            requestedSymbol = "BTCIDR",
            signalSymbol = "BTCIDR",
            stopLoss = 1.0,
            tp1 = 2.0,
            tp2 = 3.0
        )
        assertEquals(1.0, p.stopLoss, 0.0)
    }
}

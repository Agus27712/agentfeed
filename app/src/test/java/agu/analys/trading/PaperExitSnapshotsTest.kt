package agu.analys.trading

import agu.analys.engine.scalping.PaperExitPhase
import agu.analys.engine.scalping.PaperExitSnapshot
import agu.analys.engine.scalping.ScalpExitMode
import agu.analys.viewmodel.PaperAutoTradeRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji potret FSM per posisi — **P5**.
 *
 * Inti temuan: satu `StateFlow` global ditimpa `null` oleh **setiap** simbol yang tidak punya
 * mesin, padahal `evaluatePaperAutoExit` dipanggil untuk semua simbol yang mendapat tick. Uji
 * yang paling penting ada di bawah: mengevaluasi simbol tanpa mesin **tidak boleh** menghapus
 * snapshot simbol lain.
 */
class PaperExitSnapshotsTest {

    private fun snapshot(symbol: String, exchange: String, price: Double = 100.0) = PaperExitSnapshot(
        symbol = symbol,
        exchange = exchange,
        mode = ScalpExitMode.CLASSIC,
        phase = PaperExitPhase.HOLDING,
        entry = price,
        initialStopLoss = price * 0.99,
        activeStopLoss = price * 0.99,
        netBreakevenPrice = price * 1.01,
        peak = price,
        tp1 = price * 1.02,
        tp2 = price * 1.04,
        barsHeld = 1,
        currentPrice = price,
        netProfitPct = 0.0,
        currentR = 0.0,
        isPartialDone = false,
        isRiskValid = true
    )

    private val keyBtc = PaperAutoTradeRuntime.key("TOKOCRYPTO", "BTCIDR")
    private val keyAmp = PaperAutoTradeRuntime.key("TOKOCRYPTO", "AMPUSDT")
    private val snapBtc = snapshot("BTCIDR", "TOKOCRYPTO")
    private val snapAmp = snapshot("AMPUSDT", "TOKOCRYPTO", price = 0.02)

    // ── kunci posisi ─────────────────────────────────────────────────────────

    @Test
    fun `kunci posisi memakai bentuk baku runtime`() {
        assertEquals("TOKOCRYPTO|BTCIDR", keyBtc)
        assertEquals("TOKOCRYPTO|BTCIDR", PaperAutoTradeRuntime.key("tokocrypto", "btc_idr"))
    }

    // ── publish ──────────────────────────────────────────────────────────────

    @Test
    fun `publish - snapshot tersimpan di bawah kunci posisinya`() {
        val out = PaperExitSnapshots.publish(emptyMap(), keyBtc, snapBtc)
        assertEquals(1, out.size)
        assertEquals(snapBtc, PaperExitSnapshots.forPosition(out, keyBtc))
    }

    @Test
    fun `publish - simbol tanpa mesin TIDAK menghapus snapshot simbol lain`() {
        // P5: inilah yang dulu terjadi — tick pair lain menimpa satu flow global dengan null.
        var current = PaperExitSnapshots.publish(emptyMap(), keyBtc, snapBtc)
        current = PaperExitSnapshots.publish(current, keyAmp, snapAmp)
        current = PaperExitSnapshots.publish(current, keyAmp, null)   // AMP tidak punya mesin lagi

        assertEquals(snapBtc, PaperExitSnapshots.forPosition(current, keyBtc))
        assertNull(PaperExitSnapshots.forPosition(current, keyAmp))
    }

    @Test
    fun `publish - memperbarui satu posisi tidak menyentuh posisi lain`() {
        var current = PaperExitSnapshots.publish(emptyMap(), keyBtc, snapBtc)
        current = PaperExitSnapshots.publish(current, keyAmp, snapAmp)
        val updatedBtc = snapBtc.copy(currentPrice = 105.0)
        current = PaperExitSnapshots.publish(current, keyBtc, updatedBtc)

        assertEquals(updatedBtc, PaperExitSnapshots.forPosition(current, keyBtc))
        assertEquals(snapAmp, PaperExitSnapshots.forPosition(current, keyAmp))
    }

    @Test
    fun `publish - null pada kunci yang tidak ada mengembalikan peta yang sama`() {
        val current = PaperExitSnapshots.publish(emptyMap(), keyBtc, snapBtc)
        val out = PaperExitSnapshots.publish(current, keyAmp, null)
        assertSame(current, out)
    }

    @Test
    fun `publish - kunci sama ditimpa, tidak digandakan`() {
        var current = PaperExitSnapshots.publish(emptyMap(), keyBtc, snapBtc)
        current = PaperExitSnapshots.publish(current, keyBtc, snapBtc.copy(currentPrice = 99.0))
        assertEquals(1, current.size)
        assertEquals(99.0, PaperExitSnapshots.forPosition(current, keyBtc)!!.currentPrice, 1e-9)
    }

    // ── forPosition ──────────────────────────────────────────────────────────

    @Test
    fun `forPosition - null bila posisi itu belum pernah diterbitkan`() {
        assertNull(PaperExitSnapshots.forPosition(emptyMap(), keyBtc))
        val current = PaperExitSnapshots.publish(emptyMap(), keyBtc, snapBtc)
        assertNull(PaperExitSnapshots.forPosition(current, keyAmp))
    }

    @Test
    fun `forPosition - peta yang sudah dikosongkan tidak menyisakan posisi`() {
        var current = PaperExitSnapshots.publish(emptyMap(), keyBtc, snapBtc)
        current = PaperExitSnapshots.publish(current, keyBtc, null)
        assertTrue(current.isEmpty())
        assertFalse(current.containsKey(keyBtc))
    }
}

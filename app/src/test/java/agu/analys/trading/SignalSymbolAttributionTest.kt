package agu.analys.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji atribusi sinyal ke simbol — **J4** & **N10**.
 *
 * Fokus uji: angka turunan sinyal (confidence, TP, SL, harga & waktu sinyal) **tidak boleh**
 * menempel pada baris jurnal/trade milik simbol lain, dan jurnal harus memakai simbol yang
 * benar-benar dievaluasi engine — bukan pair yang sedang dibuka.
 */
class SignalSymbolAttributionTest {

    // ── normalize ─────────────────────────────────────────────────────────────

    @Test
    fun `normalize - underscore dihapus`() {
        assertEquals("BTCIDR", SignalSymbolAttribution.normalize("btc_idr"))
    }

    @Test
    fun `normalize - slash dan strip dihapus`() {
        assertEquals("BTCIDR", SignalSymbolAttribution.normalize("BTC/IDR"))
        assertEquals("BTCIDR", SignalSymbolAttribution.normalize("BTC-IDR"))
    }

    @Test
    fun `normalize - spasi di pinggir dan di tengah dibuang`() {
        assertEquals("BTCIDR", SignalSymbolAttribution.normalize("  btc idr  "))
    }

    // ── matches (N10) ────────────────────────────────────────────────────────

    @Test
    fun `matches - format berbeda tapi simbol sama dianggap cocok`() {
        assertTrue(SignalSymbolAttribution.matches("BTCIDR", "btc_idr"))
        assertTrue(SignalSymbolAttribution.matches("btc_idr", "BTC/IDR"))
    }

    @Test
    fun `matches - simbol beda tidak cocok`() {
        assertFalse(SignalSymbolAttribution.matches("BTCIDR", "ETHIDR"))
    }

    @Test
    fun `matches - simbol sinyal kosong tidak pernah cocok`() {
        // "tidak tahu" bukan "cocok" — sinyal tanpa marketSymbol tidak boleh dikaitkan.
        assertFalse(SignalSymbolAttribution.matches("BTCIDR", ""))
        assertFalse(SignalSymbolAttribution.matches("BTCIDR", "   "))
    }

    @Test
    fun `matches - simbol yang diminta kosong tidak pernah cocok`() {
        assertFalse(SignalSymbolAttribution.matches("", "BTCIDR"))
    }

    // ── journalSymbol (J4) ───────────────────────────────────────────────────

    @Test
    fun `journalSymbol - memakai simbol engine walau pair yang dibuka sudah berganti`() {
        // J4: user pindah ke ETH, tetapi sinyal yang datang masih hasil evaluasi BTC.
        assertEquals(
            "BTCIDR",
            SignalSymbolAttribution.journalSymbol(signalSymbol = "BTCIDR", selectedSymbol = "ETHIDR")
        )
    }

    @Test
    fun `journalSymbol - jatuh ke pair yang dibuka hanya bila engine tidak mengisi`() {
        assertEquals(
            "ETHIDR",
            SignalSymbolAttribution.journalSymbol(signalSymbol = "", selectedSymbol = "ETHIDR")
        )
        assertEquals(
            "ETHIDR",
            SignalSymbolAttribution.journalSymbol(signalSymbol = "  ", selectedSymbol = "ETHIDR")
        )
    }

    // ── isRecordable ─────────────────────────────────────────────────────────

    @Test
    fun `isRecordable - simbol kosong tidak boleh ditulis ke jurnal`() {
        assertFalse(SignalSymbolAttribution.isRecordable(""))
        assertFalse(SignalSymbolAttribution.isRecordable("   "))
        assertTrue(SignalSymbolAttribution.isRecordable("BTCIDR"))
    }

    // ── entryPrice ───────────────────────────────────────────────────────────

    @Test
    fun `entryPrice - harga sinyal dipakai bila ada`() {
        assertEquals(100.0, SignalSymbolAttribution.entryPrice(100.0, 90.0), 1e-9)
    }

    @Test
    fun `entryPrice - jatuh ke tick simbol itu sendiri bila sinyal tidak punya harga`() {
        assertEquals(90.0, SignalSymbolAttribution.entryPrice(0.0, 90.0), 1e-9)
        assertEquals(90.0, SignalSymbolAttribution.entryPrice(-1.0, 90.0), 1e-9)
    }

    @Test
    fun `entryPrice - nol bila tidak ada harga sama sekali`() {
        assertEquals(0.0, SignalSymbolAttribution.entryPrice(0.0, null), 1e-9)
        assertEquals(0.0, SignalSymbolAttribution.entryPrice(0.0, 0.0), 1e-9)
    }

    // ── attributedFields (N10) ───────────────────────────────────────────────

    private val btcFields = SignalDerivedFields(
        confidence = 72,
        entryPrice = 1_000_000.0,
        targetPrice1 = 1_030_000.0,
        targetPrice2 = 1_060_000.0,
        stopLoss = 970_000.0,
        signalTime = 1_700_000_000_000L
    )

    @Test
    fun `attributedFields - simbol sama, semua nilai turunan sinyal dipertahankan`() {
        val out = SignalSymbolAttribution.attributedFields(
            requestedSymbol = "BTCIDR", signalSymbol = "btc_idr",
            fields = btcFields, fallbackPrice = 1_010_000.0, fallbackTime = 1_700_000_500_000L
        )
        assertEquals(72, out.confidence)
        assertEquals(1_000_000.0, out.entryPrice, 1e-9)
        assertEquals(1_030_000.0, out.targetPrice1, 1e-9)
        assertEquals(1_060_000.0, out.targetPrice2, 1e-9)
        assertEquals(970_000.0, out.stopLoss, 1e-9)
        assertEquals(1_700_000_000_000L, out.signalTime)
    }

    @Test
    fun `attributedFields - simbol beda, confidence TIDAK boleh menempel`() {
        // N10: engine terakhir menilai ETH, jadi 72 milik ETH — bukan milik BTC yang dibeli.
        val out = SignalSymbolAttribution.attributedFields(
            requestedSymbol = "BTCIDR", signalSymbol = "ETHIDR",
            fields = btcFields, fallbackPrice = 1_010_000.0, fallbackTime = 1_700_000_500_000L
        )
        assertEquals(0, out.confidence)
    }

    @Test
    fun `attributedFields - simbol beda, TP dan SL dikosongkan`() {
        val out = SignalSymbolAttribution.attributedFields(
            requestedSymbol = "BTCIDR", signalSymbol = "ETHIDR",
            fields = btcFields, fallbackPrice = 1_010_000.0, fallbackTime = 1_700_000_500_000L
        )
        assertEquals(0.0, out.targetPrice1, 1e-9)
        assertEquals(0.0, out.targetPrice2, 1e-9)
        assertEquals(0.0, out.stopLoss, 1e-9)
    }

    @Test
    fun `attributedFields - simbol beda, harga dan waktu jatuh ke transaksi nyata`() {
        val out = SignalSymbolAttribution.attributedFields(
            requestedSymbol = "BTCIDR", signalSymbol = "ETHIDR",
            fields = btcFields, fallbackPrice = 1_010_000.0, fallbackTime = 1_700_000_500_000L
        )
        assertEquals(1_010_000.0, out.entryPrice, 1e-9)
        assertEquals(1_700_000_500_000L, out.signalTime)
    }

    @Test
    fun `attributedFields - simbol sama tapi harga sinyal nol, jatuh ke harga transaksi`() {
        val out = SignalSymbolAttribution.attributedFields(
            requestedSymbol = "BTCIDR", signalSymbol = "BTCIDR",
            fields = btcFields.copy(entryPrice = 0.0),
            fallbackPrice = 1_010_000.0, fallbackTime = 1_700_000_500_000L
        )
        assertEquals(1_010_000.0, out.entryPrice, 1e-9)
        assertEquals(72, out.confidence)
    }

    @Test
    fun `attributedFields - simbol sama tapi waktu sinyal nol, jatuh ke waktu transaksi`() {
        val out = SignalSymbolAttribution.attributedFields(
            requestedSymbol = "BTCIDR", signalSymbol = "BTCIDR",
            fields = btcFields.copy(signalTime = 0L),
            fallbackPrice = 1_010_000.0, fallbackTime = 1_700_000_500_000L
        )
        assertEquals(1_700_000_500_000L, out.signalTime)
    }

    @Test
    fun `attributedFields - sinyal tanpa marketSymbol diperlakukan sebagai bukan milik simbol ini`() {
        val out = SignalSymbolAttribution.attributedFields(
            requestedSymbol = "BTCIDR", signalSymbol = "",
            fields = btcFields, fallbackPrice = 1_010_000.0, fallbackTime = 1_700_000_500_000L
        )
        assertEquals(0, out.confidence)
        assertEquals(0.0, out.stopLoss, 1e-9)
    }
}

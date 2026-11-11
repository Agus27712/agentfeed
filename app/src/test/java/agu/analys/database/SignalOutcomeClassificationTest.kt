package agu.analys.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J1 + J2 — jurnal sinyal: satu predikat menang/kalah, dan satu basis P&L.
 *
 * Kasus patologis di sini **bukan** hipotetis: ia adalah buah dari `recordSignal` yang dulu
 * menyimpan `entryPrice` sinyal pertama tetapi memperbarui `stopLoss` dari sinyal terbaru.
 * Harga naik jauh → `stopLoss` baru berada di ATAS entry lama → harga turun menyentuh SL
 * justru menghasilkan P&L **positif** pada log berstatus `HIT_SL`.
 */
class SignalOutcomeClassificationTest {

    private fun log(
        status: String,
        pnl: Double?,
        confidence: Int = 70,
        symbol: String = "BTCUSDT",
        action: String = "BUY",
        entry: Double = 100.0
    ) = SignalLogEntity(
        symbol = symbol,
        action = action,
        confidence = confidence,
        entryPrice = entry,
        outcomeStatus = status,
        realizedPnlPct = pnl
    )

    // ── J2 — predikat eksklusif ────────────────────────────────────────────────

    @Test
    fun `HIT_SL dengan PnL positif tetap dihitung KALAH, bukan menang`() {
        // Skenario warisan: entry 100 (sinyal pertama), SL dinaikkan ke 190 (sinyal kedua),
        // harga turun ke 190 -> rawPnlPct = +90%.
        assertEquals(SignalOutcome.LOSS, classifySignalOutcome("HIT_SL", 90.0))
        assertEquals(SignalOutcome.WIN, classifySignalOutcome("HIT_TP1", -1.0))
        assertEquals(SignalOutcome.WIN, classifySignalOutcome("HIT_TP2", 0.0))
        assertEquals(SignalOutcome.LOSS, classifySignalOutcome("MANUAL_LOSS", 5.0))
    }

    @Test
    fun `status tidak dikenal mengikuti tanda PnL`() {
        assertEquals(SignalOutcome.WIN, classifySignalOutcome("EXPIRED", 0.5))
        assertEquals(SignalOutcome.LOSS, classifySignalOutcome("EXPIRED", -0.5))
        assertEquals(SignalOutcome.NEUTRAL, classifySignalOutcome("EXPIRED", 0.0))
        assertEquals(SignalOutcome.NEUTRAL, classifySignalOutcome("INVALIDATED", null))
    }

    @Test
    fun `satu log HIT_SL positif tidak lagi dihitung menang SEKALIGUS kalah`() {
        val summary = SignalLogRepository.calculateReliabilitySummary(
            listOf(log("HIT_SL", 90.0))
        )

        assertEquals("resolved", 1, summary.completedLogs)
        assertEquals("HIT_SL ber-PnL positif harus KALAH", 0, summary.winCount)
        assertEquals(1, summary.lossCount)
        assertEquals(
            "winCount + lossCount tidak boleh melebihi jumlah resolved",
            summary.completedLogs,
            summary.winCount + summary.lossCount
        )
        assertEquals("win-rate tidak boleh > 100%", 0.0, summary.overallWinRatePct, 0.001)
    }

    @Test
    fun `win-rate tetap di bawah 100 pada sampel campuran`() {
        val summary = SignalLogRepository.calculateReliabilitySummary(
            listOf(
                log("HIT_SL", 90.0),   // warisan: kalah walau P&L positif
                log("HIT_TP1", 2.0),
                log("EXPIRED", 0.3),
                log("EXPIRED", -0.4),
                log("TRACKING", null)
            )
        )

        assertEquals(5, summary.totalLogs)
        assertEquals(4, summary.completedLogs)
        assertEquals(1, summary.trackingCount)
        assertEquals(2, summary.winCount)   // HIT_TP1 + EXPIRED positif
        assertEquals(2, summary.lossCount)  // HIT_SL + EXPIRED negatif
        assertEquals(50.0, summary.overallWinRatePct, 0.001)
        assertTrue("win-rate wajib <= 100%", summary.overallWinRatePct <= 100.0)
    }

    @Test
    fun `avgLoss tidak lagi tercemar PnL positif`() {
        val summary = SignalLogRepository.calculateReliabilitySummary(
            listOf(log("HIT_SL", 90.0), log("HIT_SL", -1.5))
        )

        assertEquals(2, summary.lossCount)
        // Rata-rata kerugian harus negatif; dulu angkanya bisa ikut naik karena +90 dihitung rugi.
        assertTrue("avgLossPct harus negatif, dapat ${summary.avgLossPct}", summary.avgLossPct < 0.0)
        assertEquals(-1.5, summary.avgLossPct, 0.001)
        assertEquals(
            "P&L positif pada baris HIT_SL tidak boleh muncul sebagai kemenangan terbesar",
            0.0,
            summary.maxSingleWinPct,
            0.0
        )
        assertEquals(-1.5, summary.maxSingleLossPct, 0.001)
    }

    // ── J2 — akar masalah: pemisahan episode ──────────────────────────────────

    @Test
    fun `perpindahan entry di atas ambang dianggap episode baru`() {
        assertFalse("0,79% masih episode sama", hasEntryDrifted(100.0, 100.79))
        assertFalse("entry identik", hasEntryDrifted(100.0, 100.0))
        assertTrue("0,81% sudah episode baru", hasEntryDrifted(100.0, 100.81))
        assertTrue("turun jauh juga episode baru", hasEntryDrifted(200.0, 100.0))
    }

    @Test
    fun `harga tidak valid tidak memecah pelacakan yang sedang berjalan`() {
        assertFalse(hasEntryDrifted(100.0, 0.0))
        assertFalse(hasEntryDrifted(0.0, 100.0))
        assertFalse(hasEntryDrifted(100.0, Double.NaN))
        assertFalse(hasEntryDrifted(Double.POSITIVE_INFINITY, 100.0))
    }

    // ── J1 — basis net ────────────────────────────────────────────────────────

    @Test
    fun `kenaikan 0,5 persen di Tokocrypto USDT sebenarnya RUGI`() {
        // Impas net Tokocrypto USDT = 0,9735%. Kenaikan 0,5% belum menutup friksi.
        val net = netSignalPnlPct(
            entryPrice = 100.0,
            exitPrice = 100.5,
            isBuy = true,
            exchange = "TOKOCRYPTO",
            quoteAsset = "USDT"
        )

        assertTrue("kenaikan +0,5% harus net negatif, dapat $net", net < 0.0)
        assertEquals(-0.468950, net, 0.00001)
    }

    @Test
    fun `kenaikan 1 persen hanya menyisakan 0,026 persen net di Tokocrypto USDT`() {
        val net = netSignalPnlPct(100.0, 101.0, isBuy = true, exchange = "TOKOCRYPTO", quoteAsset = "USDT")
        assertEquals(0.026229, net, 0.00001)
    }

    @Test
    fun `basis net memakai kuotasi yang benar`() {
        val tokocryptoIdr = netSignalPnlPct(1_000_000.0, 1_010_000.0, true, "TOKOCRYPTO", "IDR")
        assertEquals(0.179934, tokocryptoIdr, 0.00001)
    }

    @Test
    fun `sinyal SELL memakai friksi sisi yang tercermin`() {
        // SELL = bertaruh harga turun. Menukar peran entry/harga di netProfitPct menghasilkan
        // model short yang benar: jual di entry (fee jual), beli kembali di harga (fee beli).
        val net = netSignalPnlPct(100.0, 99.5, isBuy = false, exchange = "TOKOCRYPTO", quoteAsset = "USDT")
        assertEquals(-0.466462, net, 0.00001)

        val mirror = netSignalPnlPct(99.5, 100.0, isBuy = true, exchange = "TOKOCRYPTO", quoteAsset = "USDT")
        assertEquals("cermin BUY<->SELL harus identik", mirror, net, 1e-12)
    }

    @Test
    fun `harga tidak valid menghasilkan nol, bukan NaN`() {
        assertEquals(0.0, netSignalPnlPct(0.0, 100.0, true, "TOKOCRYPTO", "USDT"), 0.0)
        assertEquals(0.0, netSignalPnlPct(100.0, 0.0, true, "TOKOCRYPTO", "USDT"), 0.0)
    }

    @Test
    fun `kuotasi simbol dikenali dengan benar`() {
        assertEquals("USDT", quoteAssetForSymbol("BTCUSDT"))
        assertEquals("USDT", quoteAssetForSymbol("eth_usdt"))
        assertEquals("IDR", quoteAssetForSymbol("BTCIDR"))
        assertEquals("IDR", quoteAssetForSymbol("btc/idr"))
        assertEquals("IDR", quoteAssetForSymbol("KOINANEHMACAM"))
    }
}

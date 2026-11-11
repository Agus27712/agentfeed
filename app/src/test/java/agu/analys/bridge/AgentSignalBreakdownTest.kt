package agu.analys.bridge

import agu.analys.model.ConfluenceCheckpoint
import agu.analys.model.MtfLegStatus
import agu.analys.model.ScalpingMtfSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji [AgentSignalBreakdown.build] — perbaikan temuan 2026-11-06: `TechnicalBreakdown`
 * dulu selalu `rsi/macd/emaTrend = null`, `volumeSpike = false` walaupun sinyal membawa
 * snapshot MTF yang memuat arah tren, status konfluensi, dan setup. Builder ini mengisi
 * dari data nyata, dan `rsi`/`macd` **sengaja tetap null** (data memang tidak ada —
 * uji ini menjaga agar tidak ada yang "mengisinya" dengan angka karangan).
 */
class AgentSignalBreakdownTest {

    private fun mtfNyata() = ScalpingMtfSnapshot(
        biasOk = true,
        biasDirection = "bullish",
        setupOk = true,
        triggerOk = true,
        statusTitle = "SIAP ENTRY",
        waitingFor = "",
        checkpoints = listOf(
            ConfluenceCheckpoint(1, "MTF", "Struktur MTF", isOk = true, status = MtfLegStatus.OK),
            ConfluenceCheckpoint(2, "VOL", "Volume Validasi", isOk = true, status = MtfLegStatus.OK),
            ConfluenceCheckpoint(3, "TRG", "Price Action", isOk = true, status = MtfLegStatus.OK)
        ),
        completedCount = 3
    )

    @Test
    fun `snapshot MTF nyata mengisi arah tren status dan volume terkonfirmasi`() {
        val t = AgentSignalBreakdown.build(
            orderbookPressure = "BUY_DOMINANT",
            mtf = mtfNyata(),
            scalpingSetup = "Setup A",
            scalpingRegime = "Regime X"
        )
        // rsi/macd memang tidak ada di snapshot — wajib null, bukan angka karangan.
        assertNull(t.rsi)
        assertNull(t.macd)
        assertEquals("BULLISH", t.emaTrend)
        assertEquals("BULLISH", t.mtfDirection)
        assertEquals("SIAP ENTRY", t.mtfStatusTitle)
        assertEquals(3, t.mtfCheckpointsOk)
        assertTrue(t.volumeSpike)
        assertEquals("BUY_DOMINANT", t.orderbookPressure)
        assertEquals("Setup A", t.scalpingSetup)
        assertEquals("Regime X", t.scalpingRegime)
    }

    @Test
    fun `tanpa MTF tetap mengisi setup dan regime yang diberikan`() {
        val t = AgentSignalBreakdown.build(
            orderbookPressure = "NETRAL",
            mtf = null,
            scalpingSetup = "Setup B",
            scalpingRegime = "Regime Y"
        )
        assertNull(t.rsi)
        assertNull(t.macd)
        assertNull(t.emaTrend)
        assertNull(t.mtfDirection)
        assertNull(t.mtfStatusTitle)
        assertNull(t.mtfCheckpointsOk)
        assertFalse(t.volumeSpike)
        assertEquals("Setup B", t.scalpingSetup)
        assertEquals("Regime Y", t.scalpingRegime)
    }

    @Test
    fun `snapshot default MTF diperlakukan sebagai tidak tersedia`() {
        val t = AgentSignalBreakdown.build(orderbookPressure = null, mtf = ScalpingMtfSnapshot())
        assertNull(t.emaTrend)
        assertNull(t.mtfDirection)
        assertNull(t.mtfStatusTitle)
        assertFalse(t.volumeSpike)
    }

    @Test
    fun `volume tidak terkonfirmasi ketika checkpoint VOL menunggu`() {
        val mtf = ScalpingMtfSnapshot(
            biasDirection = "bearish",
            statusTitle = "",
            checkpoints = listOf(
                ConfluenceCheckpoint(2, "VOL", "Volume Validasi", isOk = false, status = MtfLegStatus.WAITING)
            ),
            completedCount = 1
        )
        val t = AgentSignalBreakdown.build(orderbookPressure = null, mtf = mtf)
        assertEquals("BEARISH", t.emaTrend)
        assertFalse(t.volumeSpike)
        // statusTitle kosong atau "BELUM TERSEDIA" tidak boleh diteruskan ke agen
        assertNull(t.mtfStatusTitle)
    }
}

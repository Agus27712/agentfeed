package agu.analys.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji keputusan pendorong portofolio (2026-11-06).
 *
 * Yang dijaga: push hanya terjadi saat isi berubah atau heartbeat lewat — bukan setiap
 * putaran evaluasi. Kalau `ts` ikut dihitung, cap selalu berbeda dan agen dibanjiri
 * snapshot identik; itu kesalahan yang paling mudah terjadi saat menambah field.
 */
class AgentPortfolioPusherTest {

    private fun snapshot(
        ts: Long = 1_000L,
        exchange: String = "TOKOCRYPTO",
        totalUsdt: Double = 10.0,
        assets: List<AgentAsset> = listOf(
            AgentAsset("BTCUSDT", free = 0.5, locked = 0.0, avgBuyPrice = 100.0, currentPrice = 110.0, pnlPercent = 10.0, pnlValue = 5.0)
        )
    ) = AgentPortfolioOutput(
        ts = ts,
        exchange = exchange,
        totalIdrValue = totalUsdt * 16_000.0,
        totalUsdtValue = totalUsdt,
        assets = assets
    )

    @Test
    fun `cap tidak berubah hanya karena ts atau urutan aset berbeda`() {
        val a = snapshot(ts = 1_000L)
        val b = snapshot(
            ts = 9_999_999L,
            assets = listOf(
                AgentAsset("ETHUSDT", 1.0, 0.0, 10.0, 11.0, 10.0, 1.0),
                AgentAsset("BTCUSDT", 0.5, 0.0, 100.0, 110.0, 10.0, 5.0)
            )
        )
        val c = snapshot(
            ts = 5_000L,
            assets = listOf(
                AgentAsset("BTCUSDT", 0.5, 0.0, 100.0, 110.0, 10.0, 5.0),
                AgentAsset("ETHUSDT", 1.0, 0.0, 10.0, 11.0, 10.0, 1.0)
            )
        )
        assertEquals("urutan aset tidak boleh mengubah cap", AgentPortfolioPusher.signature(b), AgentPortfolioPusher.signature(c))
        assertNotEquals("aset berbeda harus mengubah cap", AgentPortfolioPusher.signature(a), AgentPortfolioPusher.signature(b))
    }

    @Test
    fun `perubahan isi mendorong push walau heartbeat belum lewat`() {
        val sebelum = AgentPortfolioPusher.signature(snapshot(totalUsdt = 10.0))
        val sesudah = AgentPortfolioPusher.signature(snapshot(totalUsdt = 12.5))
        assertTrue(sesudah != sebelum)
        assertTrue(
            AgentPortfolioPusher.shouldEmit(sebelum, sesudah, nowMs = 20_000L, lastEmitMs = 19_000L)
        )
    }

    @Test
    fun `isi sama tidak mendorong push sampai heartbeat`() {
        val sig = AgentPortfolioPusher.signature(snapshot())
        // belum pernah kirim
        assertTrue(AgentPortfolioPusher.shouldEmit(null, sig, nowMs = 0L, lastEmitMs = 0L))
        // 1 detik setelah kirim, isi sama
        assertFalse(
            AgentPortfolioPusher.shouldEmit(sig, sig, nowMs = 1_000L, lastEmitMs = 0L, heartbeatMs = 300_000L)
        )
        // tepat sebelum heartbeat
        assertFalse(
            AgentPortfolioPusher.shouldEmit(sig, sig, nowMs = 299_999L, lastEmitMs = 0L, heartbeatMs = 300_000L)
        )
        // sudah lewat heartbeat
        assertTrue(
            AgentPortfolioPusher.shouldEmit(sig, sig, nowMs = 300_000L, lastEmitMs = 0L, heartbeatMs = 300_000L)
        )
    }

    @Test
    fun `aset yang nilainya berubah mengubah cap`() {
        val a = AgentPortfolioPusher.signature(snapshot())
        val b = AgentPortfolioPusher.signature(
            snapshot(
                assets = listOf(
                    AgentAsset("BTCUSDT", 0.5, 0.0, 100.0, 111.0, 11.0, 5.5)
                )
            )
        )
        assertNotEquals(a, b)
    }
}

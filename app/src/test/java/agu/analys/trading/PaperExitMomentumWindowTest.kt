package agu.analys.trading

import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji satu sumber jendela momentum mesin exit paper — **P6**.
 *
 * Inti temuan: pemanggil foreground mengirim `recentCandles.takeLast(15)`, pemanggil latar
 * mengirim kosong. Karena `EARLY_PROFIT` hanya mungkin bila jendela tidak kosong, trade yang sama
 * bisa tercatat `EARLY_PROFIT` atau `TP2` hanya karena pair-nya kebetulan dibuka. Sekarang
 * jendelanya datang dari cache M1 untuk **kedua** pemanggil.
 */
class PaperExitMomentumWindowTest {

    private val now = 1_700_000_000_000L

    private fun bars(count: Int, lastTimestampMs: Long, startClose: Double = 100.0): List<CandleBar> {
        val step = 60_000L
        return (0 until count).map { i ->
            val ts = lastTimestampMs - (count - 1 - i) * step
            CandleBar(
                timestamp = ts,
                open = startClose + i,
                high = startClose + i + 0.5,
                low = startClose + i - 0.5,
                close = startClose + i,
                volume = 1000.0
            )
        }
    }

    // ── toMillis ─────────────────────────────────────────────────────────────

    @Test
    fun `toMillis - stempel detik dikonversi ke milidetik`() {
        assertEquals(1_700_000_000_000L, PaperExitMomentumWindow.toMillis(1_700_000_000L))
    }

    @Test
    fun `toMillis - stempel milidetik dibiarkan apa adanya`() {
        assertEquals(1_700_000_000_000L, PaperExitMomentumWindow.toMillis(1_700_000_000_000L))
    }

    // ── isFresh ──────────────────────────────────────────────────────────────

    @Test
    fun `isFresh - cache M1 segar layak dipakai`() {
        assertTrue(PaperExitMomentumWindow.isFresh(bars(30, now), now))
    }

    @Test
    fun `isFresh - kurang dari 20 bar tidak layak`() {
        // Syarat ini sama dengan `MtfCacheManager.isCacheValid`, bukan angka baru.
        assertFalse(PaperExitMomentumWindow.isFresh(bars(19, now), now))
        assertTrue(PaperExitMomentumWindow.isFresh(bars(20, now), now))
    }

    @Test
    fun `isFresh - kosong dan null tidak layak`() {
        assertFalse(PaperExitMomentumWindow.isFresh(emptyList(), now))
        assertFalse(PaperExitMomentumWindow.isFresh(null, now))
    }

    @Test
    fun `isFresh - lebih tua dari 180 detik tidak layak`() {
        assertFalse(PaperExitMomentumWindow.isFresh(bars(30, now - 180_001L), now))
        assertTrue(PaperExitMomentumWindow.isFresh(bars(30, now - 180_000L), now))
    }

    @Test
    fun `isFresh - stempel detik dihitung dengan benar`() {
        val lastInSeconds = (now - 60_000L) / 1000L
        assertTrue(PaperExitMomentumWindow.isFresh(bars(30, lastInSeconds), now))
    }

    // ── resolve ──────────────────────────────────────────────────────────────

    @Test
    fun `resolve - jendela diambil dari cache yang segar`() {
        val candles = bars(30, now)
        val out = PaperExitMomentumWindow.resolve(candles, now)
        assertEquals(PaperExitMomentumWindow.MAX_BARS, out.size)
        assertEquals(candles.takeLast(15), out)
    }

    @Test
    fun `resolve - cache basi menghasilkan jendela kosong`() {
        // Kosong berarti engine menahan diri dari early-profit — perlakuan yang SAMA untuk
        // kedua pemanggil. Inilah inti P6: bukan "jendela selalu ada", melainkan "sama".
        assertTrue(PaperExitMomentumWindow.resolve(bars(30, now - 300_000L), now).isEmpty())
    }

    @Test
    fun `resolve - cache kurang bar menghasilkan jendela kosong`() {
        assertTrue(PaperExitMomentumWindow.resolve(bars(12, now), now).isEmpty())
    }

    @Test
    fun `resolve - null diperlakukan seperti kosong`() {
        assertTrue(PaperExitMomentumWindow.resolve(null, now).isEmpty())
    }

    @Test
    fun `resolve - ukuran jendela tidak pernah lebih dari 15`() {
        assertEquals(15, PaperExitMomentumWindow.resolve(bars(200, now), now).size)
    }
}

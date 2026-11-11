package agu.analys.trading

import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji high intrabar M1 untuk mesin exit paper — **P6 bagian kedua**.
 *
 * Inti temuan: bar sintetis dari tick membuat `peak` tak pernah melihat high M1, sehingga
 * trailing SL jalur latar lebih longgar daripada replay. Resolver ini memberi high efektif
 * dengan syarat kesegaran cache yang sama dengan jendela momentum, dan hanya menghitung candle
 * yang dibuka **setelah** posisi dibuka.
 *
 * Kontrol negatif (mutasi yang harus menggagalkan uji):
 * - filter `>` diubah menjadi `>=` → uji "candle tepat saat posisi dibuka diabaikan" gagal;
 * - hasil `max` dikembalikan `currentPrice` saja → uji "high intrabar dipakai" gagal;
 * - cek `isFresh` dihapus → uji "cache basi" gagal.
 */
class PaperIntrabarPeakResolverTest {

    private val now = 1_700_000_000_000L

    /** 30 bar M1 berjarak satu menit; bar terakhir berakhir [lastTimestampMs]. */
    private fun bars(
        count: Int = 30,
        lastTimestampMs: Long = now - 60_000L
    ): List<CandleBar> {
        val step = 60_000L
        return (0 until count).map { i ->
            val ts = lastTimestampMs - (count - 1 - i) * step
            CandleBar(
                timestamp = ts,
                open = 100.0,
                high = 100.5,
                low = 99.0,
                close = 100.0,
                volume = 1000.0
            )
        }
    }

    @Test
    fun `high intrabar M1 dipakai bila cache segar`() {
        val candles = bars().toMutableList()
        candles[candles.lastIndex] = candles.last().copy(high = 110.0)
        assertEquals(
            110.0,
            PaperIntrabarPeakResolver.effectiveHigh(candles, openedAtMs = now - 300_000L, nowMs = now, currentPrice = 105.0),
            1e-9
        )
    }

    @Test
    fun `hasil tidak pernah di bawah currentPrice`() {
        val candles = bars()
        assertEquals(
            120.0,
            PaperIntrabarPeakResolver.effectiveHigh(candles, openedAtMs = now - 300_000L, nowMs = now, currentPrice = 120.0),
            1e-9
        )
    }

    @Test
    fun `cache basi kembali ke currentPrice`() {
        // High 130 di bar terakhir harus diabaikan karena cache sudah basi.
        val candles = bars(lastTimestampMs = now - 180_001L).toMutableList()
        candles[candles.lastIndex] = candles.last().copy(high = 130.0)
        assertEquals(
            105.0,
            PaperIntrabarPeakResolver.effectiveHigh(candles, openedAtMs = now - 300_000L, nowMs = now, currentPrice = 105.0),
            1e-9
        )
    }

    @Test
    fun `kurang dari 20 bar tidak layak dipakai`() {
        // High 130 juga harus diabaikan karena jumlah bar kurang dari syarat kesegaran.
        val candles = bars(count = 19).toMutableList()
        candles[candles.lastIndex] = candles.last().copy(high = 130.0)
        assertEquals(
            105.0,
            PaperIntrabarPeakResolver.effectiveHigh(candles, openedAtMs = now - 300_000L, nowMs = now, currentPrice = 105.0),
            1e-9
        )
    }

    @Test
    fun `candle null atau kosong kembali ke currentPrice`() {
        assertEquals(
            101.0,
            PaperIntrabarPeakResolver.effectiveHigh(null, openedAtMs = now - 300_000L, nowMs = now, currentPrice = 101.0),
            1e-9
        )
        assertEquals(
            101.0,
            PaperIntrabarPeakResolver.effectiveHigh(emptyList(), openedAtMs = now - 300_000L, nowMs = now, currentPrice = 101.0),
            1e-9
        )
    }

    @Test
    fun `candle sebelum posisi dibuka diabaikan`() {
        val openedAt = now - 120_000L
        val candles = bars().toMutableList()
        // Bar jauh sebelum entry: high 200 tidak boleh dihitung.
        candles[candles.lastIndex - 5] = candles[candles.lastIndex - 5].copy(
            timestamp = now - 180_000L,
            high = 200.0
        )
        candles[candles.lastIndex] = candles.last().copy(high = 108.0)
        assertEquals(
            108.0,
            PaperIntrabarPeakResolver.effectiveHigh(candles, openedAtMs = openedAt, nowMs = now, currentPrice = 105.0),
            1e-9
        )
    }

    @Test
    fun `candle tepat saat posisi dibuka diabaikan`() {
        val openedAt = now - 60_000L
        val candles = bars().toMutableList()
        // Bar entry: stempel == openedAt, high 200 (spike sebelum entry tidak boleh dihitung).
        candles[candles.lastIndex] = candles.last().copy(timestamp = openedAt, high = 200.0)
        // Bar setelah entry: high 108 (spike setelah entry wajib dihitung).
        candles.add(CandleBar(timestamp = now, open = 100.0, high = 108.0, low = 99.0, close = 100.0, volume = 1000.0))
        assertEquals(
            108.0,
            PaperIntrabarPeakResolver.effectiveHigh(candles, openedAtMs = openedAt, nowMs = now, currentPrice = 105.0),
            1e-9
        )
    }

    @Test
    fun `openedAt tidak diketahui tidak memakai candle sama sekali`() {
        val candles = bars().toMutableList()
        candles[candles.lastIndex] = candles.last().copy(high = 130.0)
        assertEquals(
            105.0,
            PaperIntrabarPeakResolver.effectiveHigh(candles, openedAtMs = 0L, nowMs = now, currentPrice = 105.0),
            1e-9
        )
    }

    @Test
    fun `currentPrice tidak valid dikembalikan apa adanya`() {
        val candles = bars()
        assertTrue(
            PaperIntrabarPeakResolver
                .effectiveHigh(candles, openedAtMs = now - 300_000L, nowMs = now, currentPrice = Double.NaN)
                .isNaN()
        )
        assertEquals(
            0.0,
            PaperIntrabarPeakResolver.effectiveHigh(candles, openedAtMs = now - 300_000L, nowMs = now, currentPrice = 0.0),
            1e-9
        )
    }

    @Test
    fun `high non-finite diabaikan`() {
        val candles = bars().toMutableList()
        candles[candles.lastIndex] = candles.last().copy(high = Double.NaN)
        candles[candles.lastIndex - 1] = candles[candles.lastIndex - 1].copy(high = 107.0)
        assertEquals(
            107.0,
            PaperIntrabarPeakResolver.effectiveHigh(candles, openedAtMs = now - 300_000L, nowMs = now, currentPrice = 105.0),
            1e-9
        )
    }

    @Test
    fun `stempel detik ikut dihitung`() {
        val lastInSeconds = (now - 30_000L) / 1000L
        val step = 60L
        val candles = (0 until 30).map { i ->
            val ts = lastInSeconds - (29 - i) * step
            CandleBar(timestamp = ts, open = 100.0, high = 100.5, low = 99.0, close = 100.0, volume = 1000.0)
        }.toMutableList()
        candles[candles.lastIndex] = candles.last().copy(high = 109.0)
        assertEquals(
            109.0,
            PaperIntrabarPeakResolver.effectiveHigh(candles, openedAtMs = now - 300_000L, nowMs = now, currentPrice = 105.0),
            1e-9
        )
    }
}

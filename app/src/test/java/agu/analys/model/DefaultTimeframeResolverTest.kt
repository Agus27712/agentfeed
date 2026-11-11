package agu.analys.model

import agu.analys.config.StrategyMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Penjaga default timeframe chart per mode strategi (2026-10-26).
 *
 * Latar: `MarketViewModel` membawa bawaan global `Timeframe.H4` dan
 * `setStrategyMode` tidak pernah menyentuhnya, jadi pindah swing → scalping
 * membiarkan chart tetap di 4 jam — jauh di luar horizon scalping (time-stop
 * exit 90 menit, MFE diukur 7 jam). Keputusannya: default chart mengikuti mode,
 * lewat resolver murni `resolveDefaultTimeframe` di `model/PositionContext.kt`.
 *
 * Pemetaan mengikuti peran timeframe yang sudah dideklarasikan tiap mode:
 * - SCALPING → M15 (kartu settings: "Bias: 1H · Setup: 15M · Trigger: 1M")
 * - INTRADAY → H1 (open pagi · close malam)
 * - SWING → D1 (jangka menengah 1H–1D)
 *
 * Uji ini menggigit: kalau pemetaan berubah tanpa keputusan baru, ia GAGAL.
 */
class DefaultTimeframeResolverTest {

    @Test
    fun `scalping default M15 - bukan H4 warisan global`() {
        assertEquals(Timeframe.M15, resolveDefaultTimeframe(StrategyMode.SCALPING))
    }

    @Test
    fun `intraday default H1`() {
        assertEquals(Timeframe.H1, resolveDefaultTimeframe(StrategyMode.OFFICE_DAILY))
    }

    @Test
    fun `swing default D1`() {
        assertEquals(Timeframe.D1, resolveDefaultTimeframe(StrategyMode.SWING))
    }

    /**
     * Kontrol eksplisit bahwa default scalping TIDAK lagi H4 — regresi persis
     * dari keluhan owner 2026-10-26 ("pindah ke scalping tapi chart 4H").
     */
    @Test
    fun `scalping TIDAK memakai default H4 lama`() {
        val tf = resolveDefaultTimeframe(StrategyMode.SCALPING)
        assertEquals(
            "Default chart scalping harus M15, bukan H4 bawaan MarketViewModel",
            Timeframe.M15,
            tf
        )
        assertEquals("H4 bukan default mode mana pun", false, listOf(
            resolveDefaultTimeframe(StrategyMode.SCALPING),
            resolveDefaultTimeframe(StrategyMode.OFFICE_DAILY),
            resolveDefaultTimeframe(StrategyMode.SWING)
        ).contains(Timeframe.H4))
    }
}

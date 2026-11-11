package agu.analys.engine.swing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji holder runtime SWING (`SwingRuntimeConfig`) — pola `IntradayRuntimeConfigTest`.
 *
 * Yang dijaga:
 * 1. Round-trip JSON mempertahankan SELURUH 18 knob (kunci snake_case) — export/import UI dan
 *    persistensi prefs bergantung pada keduanya.
 * 2. Preset STRICT/LOOSE benar-benar berbeda dari DEFAULT (frekuensi, bukan identitas).
 * 3. Holder `current`/`currentFlow` sinkron; bawaannya `SwingConfig.DEFAULT`.
 */
class SwingRuntimeConfigTest {

    @Test
    fun `round-trip JSON mempertahankan seluruh knob`() {
        val nilai = SwingConfig.DEFAULT.copy(
            minNetRr = 2.25, minEntryScore = 47.0, minConfluenceCheckpoints = 5,
            minBuySellRatio = 1.22, overboughtRsi = 70.0, maxAtrExtension = 2.0,
            strongVolumeRatio = 1.3, nearLevelPct = 1.5, rejectionBodyRatioMin = 0.5,
            breakoutBodyRatioMin = 0.45, maxDistToHighPct = 1.0,
            slBufferAtrRejection = 0.3, slAtrMaxRejection = 1.5,
            slMaxDistancePct = 8.0, slMinDistancePct = 1.2,
            tp1AtrMult = 2.2, tp2AtrMult = 3.6, slippagePct = 0.15
        )
        val dibaca = SwingRuntimeConfig.fromJsonString(SwingRuntimeConfig.toJsonString(nilai))
        assertNotEquals(null, dibaca)
        dibaca!!
        assertEquals(nilai, dibaca)
        assertEquals(nilai.hashCode(), dibaca.hashCode())
    }

    @Test
    fun `JSON rusak atau kosong menghasilkan null bukan bawaan diam-diam`() {
        assertNull(SwingRuntimeConfig.fromJsonString("bukan json"))
        assertNull(SwingRuntimeConfig.fromJsonString(""))
        // JSON parsial → field yang hilang jatuh ke bawaan, bukan exception.
        val parsial = SwingRuntimeConfig.fromJsonString("""{"min_net_rr": 2.75}""")
        assertNotEquals(null, parsial)
        parsial!!
        assertEquals(2.75, parsial.minNetRr, 0.0)
        assertEquals(SwingConfig.DEFAULT.minEntryScore, parsial.minEntryScore, 0.0)
    }

    @Test
    fun `kunci JSON snake_case lengkap untuk 18 knob`() {
        val teks = SwingRuntimeConfig.toJsonString(SwingConfig.DEFAULT)
        listOf(
            "min_net_rr", "min_entry_score", "min_confluence_checkpoints",
            "min_buy_sell_ratio", "overbought_rsi", "max_atr_extension",
            "strong_volume_ratio", "near_level_pct", "rejection_body_ratio_min",
            "breakout_body_ratio_min", "max_dist_to_high_pct",
            "sl_buffer_atr_rejection", "sl_atr_max_rejection",
            "sl_max_distance_pct", "sl_min_distance_pct",
            "tp1_atr_mult", "tp2_atr_mult", "slippage_pct"
        ).forEach { kunci ->
            assertTrue("Kunci '$kunci' hilang dari JSON", teks.contains("\"$kunci\""))
        }
    }

    @Test
    fun `preset STRICT dan LOOSE berbeda dari DEFAULT`() {
        assertNotEquals(SwingConfig.DEFAULT, SwingRuntimeConfig.STRICT)
        assertNotEquals(SwingConfig.DEFAULT, SwingRuntimeConfig.LOOSE)
        assertNotEquals(SwingRuntimeConfig.STRICT, SwingRuntimeConfig.LOOSE)
        // Arah preset: STRICT lebih selektif, LOOSE lebih longgar pada knob inti.
        assertTrue(SwingRuntimeConfig.STRICT.minNetRr > SwingConfig.DEFAULT.minNetRr)
        assertTrue(SwingRuntimeConfig.LOOSE.minNetRr < SwingConfig.DEFAULT.minNetRr)
        assertTrue(SwingRuntimeConfig.STRICT.minEntryScore > SwingConfig.DEFAULT.minEntryScore)
        assertTrue(SwingRuntimeConfig.LOOSE.minEntryScore < SwingConfig.DEFAULT.minEntryScore)
    }

    @Test
    fun `holder current dan currentFlow sinkron secara dua arah`() {
        val simpan = SwingRuntimeConfig.current
        try {
            val target = SwingRuntimeConfig.LOOSE
            SwingRuntimeConfig.current = target
            assertSame(target, SwingRuntimeConfig.current)
            assertSame(target, SwingRuntimeConfig.currentFlow.value)

            SwingRuntimeConfig.current = SwingConfig.DEFAULT
            assertSame(SwingConfig.DEFAULT, SwingRuntimeConfig.currentFlow.value)
        } finally {
            SwingRuntimeConfig.current = simpan
        }
        assertFalse(SwingRuntimeConfig.currentFlow.value != SwingRuntimeConfig.current)
    }

    @Test
    fun `ringkasan sebaris memuat angka config pilihan`() {
        val ringkas = SwingRuntimeConfig.toSummaryString(SwingRuntimeConfig.STRICT)
        assertTrue(ringkas.contains("2.5"))
        assertTrue(ringkas.contains("50.0"))
    }
}

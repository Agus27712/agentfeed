package agu.analys.engine.swing

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Penjaga sumber (source-scan) paket slider SWING — uji dua arah:
 *
 * 1. **Keluar dari evaluator:** setiap field [SwingConfig] muncul sebagai `config.<field>` di
 *    `SwingEvaluator.kt`. Tanpa ini, knob ada di config tetapi engine bisa diam-diam tetap
 *    memakai literal.
 * 2. **Masuk dari UI:** setiap field muncul sebagai `config.copy(<field> = …)` di
 *    `SwingSensitivitySettings.kt`. Tanpa ini, slider ada di layar tetapi tidak bisa mengubah
 *    config (knob dekorasi).
 *
 * Pola sama dengan `ModeSliderFrameSourceTest`; komentar dibuang agar KDoc yang menyebut nama
 * knob tidak memicu uji.
 */
class SwingConfigEnforcementTest {

    private fun berkas(relatif: String): File {
        val kandidat = listOf(
            File(System.getProperty("user.dir"), "src/main/java/$relatif"),
            File(System.getProperty("user.dir"), "app/src/main/java/$relatif")
        )
        return kandidat.firstOrNull { it.exists() }
            ?: error("$relatif tidak ditemukan dari user.dir=${System.getProperty("user.dir")}")
    }

    private fun kode(relatif: String): String =
        // Buang komentar /* */ dan // supaya teks penjelas tidak memicu/menyelamatkan uji.
        berkas(relatif).readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")

    /** 18 knob inti — kontrak paket slider SWING (AUDIT-2026-11-08.md ditulis agen lain). */
    private val fieldSwingConfig = listOf(
        "minNetRr", "minEntryScore", "minConfluenceCheckpoints", "minBuySellRatio",
        "overboughtRsi", "maxAtrExtension", "strongVolumeRatio", "nearLevelPct",
        "rejectionBodyRatioMin", "breakoutBodyRatioMin", "maxDistToHighPct",
        "slBufferAtrRejection", "slAtrMaxRejection", "slMaxDistancePct", "slMinDistancePct",
        "tp1AtrMult", "tp2AtrMult", "slippagePct"
    )

    @Test
    fun `setiap knob dibaca SwingEvaluator`() {
        val evaluator = kode("agu/analys/engine/swing/SwingEvaluator.kt")
        fieldSwingConfig.forEach { field ->
            assertTrue(
                "Field '$field' dari SwingConfig tidak dibaca SwingEvaluator sebagai " +
                    "'config.$field' — knob tanpa efek dilarang",
                evaluator.contains("config.$field")
            )
        }
    }

    @Test
    fun `setiap knob punya slider di SwingSensitivitySettings`() {
        val ui = kode("agu/analys/ui/components/settings/SwingSensitivitySettings.kt")
        fieldSwingConfig.forEach { field ->
            assertTrue(
                "Slider '$field' tidak menulis config.copy($field = …) di SwingSensitivitySettings —" +
                    " slider dekorasi dilarang",
                ui.contains("config.copy($field = it")
            )
        }
    }

    @Test
    fun `SwingConfig mendeklarasikan seluruh knob`() {
        val config = kode("agu/analys/engine/swing/SwingConfig.kt")
        fieldSwingConfig.forEach { field ->
            assertTrue(
                "Field '$field' tidak dideklarasikan di SwingConfig.kt",
                config.contains("val $field")
            )
        }
    }

    @Test
    fun `seam ModeSignalEvaluator meneruskan swingConfig`() {
        val seam = kode("agu/analys/engine/ModeSignalEvaluator.kt")
        assertTrue(
            "ModeSignalInput wajib punya field swingConfig (satu pintu slider)",
            seam.contains("val swingConfig")
        )
        assertTrue(
            "evaluateSwing wajib meneruskan config = input.swingConfig ke SwingEvaluator",
            seam.contains("config = input.swingConfig")
        )
    }
}

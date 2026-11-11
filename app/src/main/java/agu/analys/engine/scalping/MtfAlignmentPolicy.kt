package agu.analys.engine.scalping

/**
 * P0 — gerbang MTF 4 timeframe (H1, M15, M5, M1).
 *
 * p0 menyimpulkan `actionable_bias` dari hitungan bullish/bearish. Keputusan owner sedikit
 * lebih ketat untuk LONG: **H1 dan M15 tidak boleh bearish**, dan minimal
 * [ScalpingRuntimeConfig.mtfMinBullish] dari 4 TF harus bullish. M5/M1 boleh pullback —
 * itu justru entry yang dicari — asalkan hitungan total tetap cukup.
 *
 * Semua ambang dibaca dari runtime config supaya replay dan app memakai angka yang sama.
 */
object MtfAlignmentPolicy {

    data class Decision(
        val allowsLong: Boolean,
        val reason: String,
        val bullishCount: Int,
        val knownCount: Int,
        val highTfBearish: Boolean
    )

    fun evaluate(matrix: MtfConfluenceMatrix.Matrix, config: ScalpingRuntimeConfig): Decision {
        // Hitung hanya empat TF p0. Jangan memakai matrix.bullishCount/knownCount mentah:
        // matriks penuh juga memuat 1D/4H, sehingga TF tambahan itu bisa menutupi M5 yang
        // hilang dan membuat gerbang lolos tanpa data 4 TF yang lengkap.
        val h1 = matrix.biasOf("1H")
        val m15 = matrix.biasOf("15M")
        val m5 = matrix.biasOf("5M")
        val m1 = matrix.biasOf("1M")
        val legs = listOf(h1, m15, m5, m1)
        val knownCount = legs.count { it != MtfConfluenceMatrix.TfBias.UNKNOWN }
        val bullishCount = legs.count { it == MtfConfluenceMatrix.TfBias.BULLISH }

        val minBullish = config.mtfMinBullish
        if (minBullish <= 0) {
            return Decision(
                allowsLong = true,
                reason = "Gerbang MTF nonaktif (mtf_min_bullish=0).",
                bullishCount = bullishCount,
                knownCount = knownCount,
                highTfBearish = false
            )
        }

        // Fail-closed: 3/4 bullish tidak boleh dihitung dari tiga TF yang tersedia saja.
        // Tanpa M5, matriks bukan lagi 4 TF p0 dan label "3/4" akan menyesatkan.
        if (knownCount < 4) {
            return Decision(
                allowsLong = false,
                reason = "Data MTF belum lengkap ($knownCount/4 TF) — menunggu M5/M1.",
                bullishCount = bullishCount,
                knownCount = knownCount,
                highTfBearish = false
            )
        }

        val highTfBearish = config.mtfBlockHighTfBearish &&
            (h1 == MtfConfluenceMatrix.TfBias.BEARISH || m15 == MtfConfluenceMatrix.TfBias.BEARISH)

        if (highTfBearish) {
            val which = when {
                h1 == MtfConfluenceMatrix.TfBias.BEARISH && m15 == MtfConfluenceMatrix.TfBias.BEARISH -> "H1 & M15"
                h1 == MtfConfluenceMatrix.TfBias.BEARISH -> "H1"
                else -> "M15"
            }
            return Decision(
                allowsLong = false,
                reason = "MTF menolak LONG: $which bearish (${matrix.alignmentLabel}).",
                bullishCount = bullishCount,
                knownCount = knownCount,
                highTfBearish = true
            )
        }

        if (bullishCount < minBullish) {
            return Decision(
                allowsLong = false,
                reason = "MTF ${matrix.alignmentLabel} — butuh minimal $minBullish/4 bullish " +
                    "(M5/M1 boleh pullback).",
                bullishCount = bullishCount,
                knownCount = knownCount,
                highTfBearish = false
            )
        }

        return Decision(
            allowsLong = true,
            reason = "MTF ${matrix.alignmentLabel} — mendukung LONG.",
            bullishCount = bullishCount,
            knownCount = knownCount,
            highTfBearish = false
        )
    }
}

package agu.analys.util

/** Label tampilan (Bahasa Indonesia) untuk output pipeline scalping. */
object ScalpingLabels {
    fun setup(name: String): String = when (name.uppercase()) {
        "BREAKOUT" -> "Breakout"
        "BREAKOUT_RETEST" -> "Breakout + Retest"
        "LIQUIDITY_SWEEP" -> "Liquidity Sweep"
        "TREND_PULLBACK" -> "Trend Pullback"
        "NONE", "" -> "Belum ada setup"
        else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun scoreCategory(name: String): String = when (name.uppercase()) {
        "VERY_STRONG" -> "Sangat Kuat (90+)"
        "STRONG" -> "Kuat (75-89)"
        "WATCH" -> "Pantau (60-74)"
        "WEAK" -> "Lemah (40-59)"
        "NO_TRADE" -> "Jangan Trade (<40)"
        else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

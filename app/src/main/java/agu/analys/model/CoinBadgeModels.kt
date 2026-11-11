package agu.analys.model

import agu.analys.config.StrategyMode

/**
 * Enum tipe badge koin yang didukung oleh sistem agentfeed.
 */
enum class BadgeType(
    val label: String,
    val defaultPriority: Int
) {
    INTRADAY("INTRADAY", 1),
    SECONDWAVE("2ND WAVE", 2),
    SWING("SWING", 3),
    SCALPING("SCALPING", 4),
    TRENCHING("TRENCH", 5),
    READY("SIAP ENTRY", 6),
    HOT("HOT", 7),
    PUMP("BREAKOUT", 8),
    VOL24("HIGH VOL", 9),
    DUMP("PULLBACK", 10)
}

/**
 * Representasi badge yang terasosiasi dengan sebuah koin / pair.
 */
data class CoinBadge(
    val type: BadgeType,
    val label: String = type.label,
    val priority: Int = type.defaultPriority,
    val description: String = ""
)

typealias StrategyBadge = CoinBadge

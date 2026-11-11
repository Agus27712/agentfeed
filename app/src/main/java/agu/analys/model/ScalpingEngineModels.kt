package agu.analys.model

/**
 * Model engine scalping KriptoYoi — exchange-agnostic (pair IDR & USDT/IDR Tokocrypto).
 * Semua level harga relatif; threshold pakai persen/ratio, bukan absolute IDR.
 */

enum class MarketRegime {
    TRENDING_UP,
    TRENDING_DOWN,
    RANGING,
    BREAKOUT,
    HIGH_VOLATILITY,
    LOW_VOLATILITY
}

data class RegimeSnapshot(
    val regime: MarketRegime,
    val adx: Double = 0.0,
    val chop: Double = 0.0,
    val atrPct: Double = 0.0,
    val emaAlignment: String = "mixed", // bullish | bearish | mixed
    val explanation: String = ""
)

enum class StructureBias {
    BULLISH,
    BEARISH,
    NEUTRAL
}

/**
 * Snapshot struktur untuk pipeline scalping (mapping dari MarketStructureAnalyzer).
 */
data class StructureSnapshot(
    val bias: StructureBias = StructureBias.NEUTRAL,
    val pattern: String = "RANGING", // HH_HL | LH_LL | RANGING | CHOCH | BOS
    val bos: Boolean = false,
    val choch: Boolean = false,
    val strength: Int = 0, // 0–100
    val lastSwingHigh: Double? = null,
    val lastSwingLow: Double? = null,
    val support: Double? = null,
    val resistance: Double? = null,
    val liquiditySweepDetected: Boolean = false,
    val isBreakoutRetestValid: Boolean = false,
    val explanation: String = ""
)

enum class ScalpSetupType {
    NONE,
    BREAKOUT,
    BREAKOUT_RETEST,
    LIQUIDITY_SWEEP,
    TREND_PULLBACK
}

enum class SignalDirection {
    LONG,
    WAIT,
    SHORT
}

data class ScoreBreakdown(
    val structure: Int = 0,   // max 25
    val mtf: Int = 0,         // max 15
    val priceAction: Int = 0, // max 20
    val volume: Int = 0,      // max 15
    val momentum: Int = 0,    // max 10
    val orderFlow: Int = 0,   // max 10
    val volatility: Int = 0,  // max 5
    val reasons: List<String> = emptyList()
) {
    val total: Int
        get() = (structure + mtf + priceAction + volume + momentum + orderFlow + volatility).coerceIn(0, 100)

    val category: String
        get() = when {
            total >= 90 -> "VERY_STRONG"
            total >= 75 -> "STRONG"
            total >= 60 -> "WATCH"
            total >= 40 -> "WEAK"
            else -> "NO_TRADE"
        }
}

data class EntryZone(
    val low: Double,
    val high: Double,
    val confirmationTrigger: String = "",
    val invalidation: Double = 0.0
) {
    init {
        require(low <= high || (low == 0.0 && high == 0.0)) {
            "EntryZone.low must be <= high"
        }
    }
}

data class RiskLevels(
    val entryZone: EntryZone,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    val riskPct: Double = 0.0,
    val rewardPct: Double = 0.0,
    val netRr: Double = 0.0,
    val feeSlippageNote: String = ""
)

/**
 * Output pipeline scalping penuh (sebelum mapping ke AISignalState).
 */
data class ScalpingSignal(
    val direction: SignalDirection = SignalDirection.WAIT,
    val setup: ScalpSetupType = ScalpSetupType.NONE,
    val score: ScoreBreakdown = ScoreBreakdown(),
    val regime: RegimeSnapshot = RegimeSnapshot(MarketRegime.RANGING),
    val structure: StructureSnapshot = StructureSnapshot(),
    val risk: RiskLevels? = null,
    val mtfAlignment: String = "0/0",
    val rvol: Double = 1.0,
    val buyPressure: Double = 1.0,
    val orderImbalance: Double = 0.0,
    val reasoning: List<String> = emptyList(),
    val historicalEdgeStub: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

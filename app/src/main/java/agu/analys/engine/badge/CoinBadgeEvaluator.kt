package agu.analys.engine.badge

import agu.analys.config.StrategyMode
import agu.analys.engine.intraday.IntradayScreener
import agu.analys.model.BadgeType
import agu.analys.model.CoinBadge
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair

object CoinBadgeEvaluator {

    /**
     * Evaluasi badge per koin. Maksimal 1 badge per pair dipilih sesuai dengan
     * mode trading yang paling cocok di pair tersebut.
     */
    fun evaluateBadges(
        pair: TradingPair,
        tick: MarketTick?,
        activeStrategy: StrategyMode = StrategyMode.SCALPING,
        isSetupReady: Boolean = false,
        maxBadges: Int = 1
    ): List<CoinBadge> {
        if (tick == null || tick.price <= 0.0) return emptyList()

        val change = if (tick.change24h.isFinite()) tick.change24h else 0.0
        val volume = tick.volume24h
        val isUsdt = pair.quoteAsset.equals("USDT", true) || pair.quoteAsset.equals("USD", true)

        val volThresholdHigh = if (isUsdt) 5_000_000.0 else 25_000_000_000.0
        val volThresholdMed = if (isUsdt) 1_000_000.0 else 5_000_000_000.0
        val volThresholdMin = if (isUsdt) 200_000.0 else 1_000_000_000.0

        // Evaluasi kelayakan untuk masing-masing strategi trading:
        val intradayScore = IntradayScreener.evaluateFast(tick)
        val isIntradayQualified = intradayScore.isQualified

        val isSwingCandidate = volume >= volThresholdMin && change in -3.0..8.0
        val isScalpingCandidate =
            volume >= volThresholdMin && (change >= 1.5 || change <= -1.5 || volume >= volThresholdMed)

        // Pilih 1 badge mode strategi yang paling cocok untuk pair ini:
        val chosenBadge: CoinBadge? = when {
            // 1. Jika mode aktif pengguna cocok dengan kondisi pair ini, prioritaskan mode aktif
            activeStrategy == StrategyMode.SCALPING && isScalpingCandidate ->
                CoinBadge(BadgeType.SCALPING, priority = 0, description = "Momentum Scalping aktif")
            activeStrategy == StrategyMode.SWING && isSwingCandidate ->
                CoinBadge(BadgeType.SWING, priority = 0, description = "Setup Swing terdeteksi")
            activeStrategy == StrategyMode.OFFICE_DAILY && isIntradayQualified ->
                CoinBadge(BadgeType.INTRADAY, priority = 0, description = intradayScore.summary)

            // 2. Jika mode aktif tidak cocok, pilih mode strategi dengan setup terbaik
            isIntradayQualified && intradayScore.score >= 6 ->
                CoinBadge(BadgeType.INTRADAY, priority = 1, description = intradayScore.summary)
            isSwingCandidate && change in 0.0..6.0 ->
                CoinBadge(BadgeType.SWING, priority = 2, description = "Setup Swing terdeteksi")
            isScalpingCandidate ->
                CoinBadge(BadgeType.SCALPING, priority = 3, description = "Momentum Scalping aktif")

            // 3. Konfirmasi siap entry
            isSetupReady ->
                CoinBadge(BadgeType.READY, priority = 4, description = "Siap Entry")

            // 4. Sinyal pasar teknikal profesional (bukan spekulatif pump/dump)
            volume >= volThresholdMed && change >= 3.5 ->
                CoinBadge(BadgeType.PUMP, label = "BREAKOUT", priority = 5, description = "+${String.format(java.util.Locale.US, "%.1f", change)}% 24h")
            volume >= volThresholdMed && change <= -3.5 ->
                CoinBadge(BadgeType.DUMP, label = "PULLBACK", priority = 6, description = "${String.format(java.util.Locale.US, "%.1f", change)}% 24h")
            volume >= volThresholdHigh ->
                CoinBadge(BadgeType.VOL24, label = "HIGH VOL", priority = 7, description = "Volume 24H sangat tinggi")
            change >= 5.0 ->
                CoinBadge(BadgeType.PUMP, label = "MOMENTUM", priority = 8, description = "+${String.format(java.util.Locale.US, "%.1f", change)}% 24h")
            else -> null
        }

        return if (chosenBadge != null) {
            listOf(chosenBadge).take(maxBadges.coerceAtLeast(1))
        } else {
            emptyList()
        }
    }
}

package agu.analys.engine.scalping

import agu.analys.model.OrderBookItem
import java.util.Locale

enum class EntryExecutionType(val label: String) {
    MARKET_TAKER("Eksekusi (Taker)"),
    LIMIT_MAKER("Antri Limit (Maker)"),
    SPREAD_GUARD_VETO("Spread Guard Aktif (Tahan)"),
    NO_DEPTH("Order Book Kosong")
}

data class SpreadAnalysis(
    val bestBid: Double = 0.0,
    val bestAsk: Double = 0.0,
    val spreadIdr: Double = 0.0,
    val spreadPct: Double = 0.0,
    val tolerancePct: Double = 0.40,
    val maxGuardPct: Double = 1.20,
    val executionType: EntryExecutionType = EntryExecutionType.NO_DEPTH,
    val recommendedEntryPrice: Double = 0.0,
    val isSpreadGuardActive: Boolean = false,
    val statusText: String = "",
    val advice: String = "",
    val makerSavingsFeePct: Double = 0.30
)

/**
 * Snapshot order flow ringkas (P2) — buy pressure + imbalance formal.
 *
 * Imbalance = (bidVol - askVol) / (bidVol + askVol), range -1.0 .. +1.0.
 * Positif = tekanan beli di buku; negatif = tekanan jual.
 */
data class OrderFlowSnapshot(
    val buyPressure: Double = 1.0,
    val orderImbalance: Double = 0.0,
    val bidVolume: Double = 0.0,
    val askVolume: Double = 0.0,
    val levelsUsed: Int = 0,
    val hasDepth: Boolean = false
)

object OrderBookAnalyzer {
    fun calculateBuyPressure(bids: List<OrderBookItem>, asks: List<OrderBookItem>, levels: Int = 10): Double {
        val topBids = bids.take(levels).sumOf { it.amount }
        val topAsks = asks.take(levels).sumOf { it.amount }

        // An entirely empty order book contains no directional information.
        // Keep it neutral instead of treating it as bullish because topAsks == 0.
        if (topBids <= 0.0 && topAsks <= 0.0) return 1.0
        if (topAsks <= 0.0) return 1.5
        if (topBids <= 0.0) return 0.5
        return topBids / topAsks
    }

    /**
     * Order book imbalance formal (spek KriptoYoi):
     * `(Bid Volume - Ask Volume) / (Bid Volume + Ask Volume)`
     *
     * @return nilai di [-1.0, 1.0]; 0.0 jika depth kosong.
     */
    fun calculateOrderImbalance(
        bids: List<OrderBookItem>,
        asks: List<OrderBookItem>,
        levels: Int = 10
    ): Double {
        val bidVol = bids.take(levels).sumOf { it.amount.coerceAtLeast(0.0) }
        val askVol = asks.take(levels).sumOf { it.amount.coerceAtLeast(0.0) }
        val total = bidVol + askVol
        if (total <= 0.0) return 0.0
        return ((bidVol - askVol) / total).coerceIn(-1.0, 1.0)
    }

    /**
     * Gabungan buy pressure + imbalance untuk scoring / pipeline.
     */
    fun analyzeOrderFlow(
        bids: List<OrderBookItem>,
        asks: List<OrderBookItem>,
        levels: Int = 10
    ): OrderFlowSnapshot {
        val bidVol = bids.take(levels).sumOf { it.amount.coerceAtLeast(0.0) }
        val askVol = asks.take(levels).sumOf { it.amount.coerceAtLeast(0.0) }
        val hasDepth = bidVol > 0.0 || askVol > 0.0
        return OrderFlowSnapshot(
            buyPressure = calculateBuyPressure(bids, asks, levels),
            orderImbalance = calculateOrderImbalance(bids, asks, levels),
            bidVolume = bidVol,
            askVolume = askVol,
            levelsUsed = levels,
            hasDepth = hasDepth
        )
    }

    /**
     * Menganalisis spread order book untuk rekomendasi harga entri presisi
     * dengan toleransi spread dan proteksi Spread Guard anti-slippage.
     */
    fun analyzeSpread(
        bids: List<OrderBookItem>,
        asks: List<OrderBookItem>,
        currentPrice: Double = 0.0,
        tolerancePct: Double = ScalpingConfig.SPREAD_TOLERANCE_PCT,
        maxGuardPct: Double = ScalpingConfig.SPREAD_GUARD_MAX_PCT
    ): SpreadAnalysis {
        val topBid = bids.firstOrNull()?.price ?: 0.0
        val topAsk = asks.firstOrNull()?.price ?: 0.0

        if (topBid <= 0.0 || topAsk <= 0.0) {
            val fallbackPrice = if (currentPrice > 0.0) currentPrice else topBid.coerceAtLeast(topAsk)
            return SpreadAnalysis(
                bestBid = topBid,
                bestAsk = topAsk,
                spreadIdr = 0.0,
                spreadPct = 0.0,
                tolerancePct = tolerancePct,
                maxGuardPct = maxGuardPct,
                executionType = EntryExecutionType.NO_DEPTH,
                recommendedEntryPrice = fallbackPrice,
                isSpreadGuardActive = false,
                statusText = "Order Book Tidak Tersedia",
                advice = "Gunakan harga pasar terakhir dengan hati-hati."
            )
        }

        val spreadIdr = (topAsk - topBid).coerceAtLeast(0.0)
        val spreadPct = if (topBid > 0) (spreadIdr / topBid) * 100.0 else 0.0
        val isGuardActive = spreadPct > maxGuardPct

        val executionType = when {
            isGuardActive -> EntryExecutionType.SPREAD_GUARD_VETO
            spreadPct <= tolerancePct -> EntryExecutionType.MARKET_TAKER
            else -> EntryExecutionType.LIMIT_MAKER
        }

        val recommendedEntryPrice = when (executionType) {
            EntryExecutionType.MARKET_TAKER -> topAsk
            EntryExecutionType.LIMIT_MAKER -> topBid
            EntryExecutionType.SPREAD_GUARD_VETO -> topBid
            EntryExecutionType.NO_DEPTH -> currentPrice
        }

        val statusText = when (executionType) {
            EntryExecutionType.MARKET_TAKER -> "Spread Tipis (${String.format(Locale.US, "%.2f", spreadPct)}%)"
            EntryExecutionType.LIMIT_MAKER -> "Spread Sedang (${String.format(Locale.US, "%.2f", spreadPct)}%)"
            EntryExecutionType.SPREAD_GUARD_VETO -> "Spread Terlalu Lebar (${String.format(Locale.US, "%.2f", spreadPct)}%)"
            EntryExecutionType.NO_DEPTH -> "Order Book Kosong"
        }

        val advice = when (executionType) {
            EntryExecutionType.MARKET_TAKER ->
                "Eksekusi instan (Taker @ Rp ${formatPrice(topAsk)}). Slippage rendah tapi perhatikan Fee."
            EntryExecutionType.LIMIT_MAKER ->
                "Disarankan antri Limit Maker @ Rp ${formatPrice(topBid)} di Best Bid. " +
                    "Pada pair IDR tarif maker menghemat ~0,1% per sisi; pada pair USDT tarif maker = taker. " +
                    "Menghindari gap spread Rp ${formatPrice(spreadIdr)}."
            EntryExecutionType.SPREAD_GUARD_VETO ->
                "🛡️ SPREAD GUARD AKTIF: Spread ${String.format(Locale.US, "%.2f", spreadPct)}% melebihi toleransi maksimal (${String.format(Locale.US, "%.2f", maxGuardPct)}%). Dilarang Eksekusi Taker! Wajib pasang Limit Beli @ Rp ${formatPrice(topBid)}."
            EntryExecutionType.NO_DEPTH -> "Order book belum sinkron."
        }

        return SpreadAnalysis(
            bestBid = topBid,
            bestAsk = topAsk,
            spreadIdr = spreadIdr,
            spreadPct = spreadPct,
            tolerancePct = tolerancePct,
            maxGuardPct = maxGuardPct,
            executionType = executionType,
            recommendedEntryPrice = recommendedEntryPrice,
            isSpreadGuardActive = isGuardActive,
            statusText = statusText,
            advice = advice
        )
    }

    private fun formatPrice(p: Double): String = agu.analys.util.PriceFormatter.formatPrice(p, showSymbol = false)
}

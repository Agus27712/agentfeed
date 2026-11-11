package agu.analys.model

import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.trading.SpotPosition
import agu.analys.util.PriceFormatter
import java.util.Locale

/**
 * Workflow trading eksplisit berdasarkan konteks kepemilikan aset (Position Awareness).
 * NO POSITION -> BUY WORKFLOW
 * HAS POSITION -> HOLD / SELL WORKFLOW
 */
enum class TradingWorkflow {
    BUY,
    HOLD_SELL
}

/**
 * Status semantik untuk setiap checkpoint trading (baik pada BUY maupun SELL).
 */
enum class CheckpointStatus {
    LOCKED,
    MONITORING,
    ACTIVE,
    COMPLETED,
    WARNING,
    READY
}

/**
 * Single canonical source of truth untuk konteks posisi koin aktif.
 * Berlaku identik baik untuk transaksi real maupun simulasi tanpa data dummy.
 */
data class PositionContext(
    val hasPosition: Boolean = false,
    val symbol: String = "",
    val entryPrice: Double? = null,
    val quantity: Double? = null,
    val costBasis: Double? = null,
    val currentPrice: Double? = null,
    val grossValue: Double? = null,
    val netValue: Double? = null,
    val floatingProfitPct: Double? = null,
    val floatingProfitNet: Double? = null,
    val stopLoss: Double? = null,
    val tp1: Double? = null,
    val tp2: Double? = null,
    val trailingActive: Boolean = false,
    val isTrailingTriggered: Boolean = false,
    val isReal: Boolean = false,
    val peakPrice: Double? = null,
    val riskSnapshot: agu.analys.model.SellRiskSnapshot? = null
) {
    companion object {
        fun create(
            symbol: String,
            spotPosition: SpotPosition?,
            holdingStatus: CoinHoldingStatus?,
            currentPrice: Double,
            fees: TradingFeeConfig,
            currentModeIsReal: Boolean? = null
        ): PositionContext {
            val targetIsReal = currentModeIsReal ?: holdingStatus?.isReal ?: spotPosition?.isReal ?: false
            val isSpotHolding = spotPosition != null && spotPosition.isHolding && spotPosition.isReal == targetIsReal && spotPosition.quantity > 0.00000001
            val isHoldingStatusHolding = holdingStatus != null && holdingStatus.isHolding && holdingStatus.isReal == targetIsReal && holdingStatus.quantity > 0.00000001

            if (!isSpotHolding && !isHoldingStatusHolding) {
                return PositionContext(
                    hasPosition = false,
                    symbol = symbol,
                    currentPrice = if (currentPrice > 0.0 && currentPrice.isFinite()) currentPrice else null,
                    isReal = targetIsReal
                )
            }

            val qty = if (isSpotHolding) {
                spotPosition!!.quantity
            } else {
                holdingStatus?.quantity ?: 0.0
            }

            val entry = if (isSpotHolding) {
                if (spotPosition!!.entryPrice > 0.0) spotPosition.entryPrice else holdingStatus?.entryPrice
            } else {
                if ((holdingStatus?.entryPrice ?: 0.0) > 0.0) holdingStatus?.entryPrice else null
            }

            val tp1 = if (isSpotHolding && spotPosition!!.tp1Price > 0.0) {
                spotPosition.tp1Price
            } else if ((holdingStatus?.tp1Price ?: 0.0) > 0.0) {
                holdingStatus?.tp1Price
            } else null

            val tp2 = if (isSpotHolding && spotPosition!!.tp2Price > 0.0) {
                spotPosition.tp2Price
            } else if ((holdingStatus?.tp2Price ?: 0.0) > 0.0) {
                holdingStatus?.tp2Price
            } else null

            val sl = if (isSpotHolding && spotPosition!!.stopLossPrice > 0.0) {
                spotPosition.stopLossPrice
            } else if ((holdingStatus?.stopLossPrice ?: 0.0) > 0.0) {
                holdingStatus?.stopLossPrice
            } else if (entry != null && entry > 0.0) {
                entry * 0.99
            } else null

            val peak = if (isSpotHolding && spotPosition!!.peakPrice > 0.0) spotPosition.peakPrice else null
            val trailingActive = if (isSpotHolding) spotPosition!!.isTrailingEnabled else (holdingStatus?.isTrailingEnabled == true)
            val isTrailingTrig = if (isSpotHolding) spotPosition!!.isTrailingTriggered else (holdingStatus?.isTrailingTriggered == true)
            val isReal = targetIsReal

            val validPrice = if (currentPrice > 0.0 && currentPrice.isFinite()) currentPrice else null
            // Basis biaya tunggal (2026-10-15): **taker + slippage dua sisi**, sama persis dengan
            // FSM paper trade. Sebelumnya kartu ini hanya memotong fee jual maker dan memakai
            // `investedAmount` mentah sebagai biaya perolehan, sehingga untuk posisi yang sama
            // angkanya lebih optimistis daripada FSM — selisihnya terbaca sebagai bug.
            // Lantai biaya memakai tarif taker yang benar untuk bursa + kuotasi ini,
            // bukan angka tetap 0,21% dari preset lama.
            val resolvedExchange = spotPosition?.exchange?.takeIf { it.isNotBlank() } ?: "TOKOCRYPTO"
            val quoteAsset = PriceFormatter.extractQuote(symbol)
            val buyFloor = agu.analys.config.FeeSchedule.buyRate(resolvedExchange, quoteAsset)
            val sellFloor = agu.analys.config.FeeSchedule.sellRate(resolvedExchange, quoteAsset)
            val buyFeeRate = if (targetIsReal) (fees.buyTakerPct / 100.0).coerceAtLeast(buyFloor) else (fees.buyTakerPct / 100.0).coerceAtLeast(0.0)
            val sellFeeRate = if (targetIsReal) (fees.sellTakerPct / 100.0).coerceAtLeast(sellFloor) else (fees.sellTakerPct / 100.0).coerceAtLeast(0.0)
            val slippagePct = agu.analys.config.FeeSchedule.DEFAULT_SLIPPAGE_PCT

            // Harga masuk: dari posisi bila ada, atau diturunkan dari biaya perolehan tersimpan
            // supaya basis biaya tetap seragam meski `entryPrice` tidak tersimpan.
            val effectiveEntry = when {
                entry != null && entry > 0.0 -> entry
                isSpotHolding && qty > 0.0 && spotPosition!!.investedAmount > 0.0 ->
                    spotPosition.investedAmount / qty
                else -> null
            }

            val cost = if (effectiveEntry != null) {
                agu.analys.config.FeeSchedule.costBasisNet(
                    quantity = qty,
                    entry = effectiveEntry,
                    buyFeePct = buyFeeRate * 100.0,
                    slippagePct = slippagePct
                )
            } else null
            val gross = if (validPrice != null) qty * validPrice else null
            val netVal = if (validPrice != null) {
                agu.analys.config.FeeSchedule.netSellValue(
                    quantity = qty,
                    price = validPrice,
                    sellFeePct = sellFeeRate * 100.0,
                    slippagePct = slippagePct
                )
            } else null
            val netPnl = if (cost != null && netVal != null) netVal - cost else null
            val pnlPct = if (cost != null && cost > 0.0 && netPnl != null) (netPnl / cost) * 100.0 else null

            return PositionContext(
                hasPosition = true,
                symbol = symbol,
                entryPrice = entry,
                quantity = qty,
                costBasis = cost,
                currentPrice = validPrice,
                grossValue = gross,
                netValue = netVal,
                floatingProfitPct = pnlPct,
                floatingProfitNet = netPnl,
                stopLoss = sl,
                tp1 = tp1,
                tp2 = tp2,
                trailingActive = trailingActive,
                isTrailingTriggered = isTrailingTrig,
                isReal = isReal,
                peakPrice = peak
            )
        }
    }
}

/**
 * Resolver workflow trading:
 * NO POSITION -> BUY
 * HAS POSITION -> HOLD / SELL
 */
fun resolveWorkflow(positionContext: PositionContext): TradingWorkflow {
    return if (positionContext.hasPosition) {
        TradingWorkflow.HOLD_SELL
    } else {
        TradingWorkflow.BUY
    }
}

/**
 * Mode yang ditampilkan halaman detail koin.
 *
 * Halaman detail **tidak lagi** punya tab Beli/Jual manual: mode ditentukan sepenuhnya oleh
 * status kepemilikan aset, supaya tidak ada lagi kartu beli dan jual yang tampil bersamaan.
 */
enum class DetailTradeMode {
    BUY,
    SELL
}

/**
 * Resolver mode halaman detail — satu-satunya tempat aturan ini ditegakkan.
 *
 * - **sedang hold** koin → [DetailTradeMode.SELL]
 * - **tidak hold** → [DetailTradeMode.BUY]
 *
 * Tambah posisi (DCA) tetap bisa dari mode SELL lewat jalur terpisah, jadi mode ini tidak
 * mematikan kemampuan beli lagi — hanya menentukan tampilan utamanya.
 */
fun resolveDetailTradeMode(isHolding: Boolean): DetailTradeMode =
    if (isHolding) DetailTradeMode.SELL else DetailTradeMode.BUY

/**
 * Panel scalping yang boleh tampil di halaman detail koin.
 *
 * Sebelumnya halaman detail merender `ScalpDeskCard` + `PaperTradeDeskCard` dengan syarat
 * `isScalping` saja, **tanpa melihat status kepemilikan koin**. Akibatnya frame yang sama
 * muncul entah pengguna sedang memegang koin atau tidak, sehingga mode beli dan mode jual
 * terlihat identik — padahal keduanya butuh informasi yang berbeda:
 *
 * - **tidak hold** → yang dibutuhkan adalah keputusan masuk (`SCALPING_DESK`);
 * - **sedang hold** → yang dibutuhkan adalah progres keluar (`EXIT_FSM`).
 *
 * `NONE` dipisah dari kedua status itu supaya kasus "scalping nonaktif" bisa diuji eksplisit
 * dan tidak diam-diam ikut menampilkan FSM.
 */
enum class DetailScalpPanel {
    /** Scalping tidak aktif — tidak ada panel scalping yang relevan. */
    NONE,

    /** Tidak memegang koin: tampilkan meja kerja entry. */
    SCALPING_DESK,

    /** Sedang memegang koin: tampilkan progres FSM exit, meja kerja entry disembunyikan. */
    EXIT_FSM
}

/**
 * Resolver panel scalping halaman detail — satu-satunya tempat aturan ini ditegakkan.
 *
 * - scalping nonaktif → [DetailScalpPanel.NONE] (apa pun status kepemilikan)
 * - scalping aktif + sedang hold → [DetailScalpPanel.EXIT_FSM]
 * - scalping aktif + tidak hold → [DetailScalpPanel.SCALPING_DESK]
 */
fun resolveDetailScalpPanel(isScalping: Boolean, isHolding: Boolean): DetailScalpPanel = when {
    !isScalping -> DetailScalpPanel.NONE
    isHolding -> DetailScalpPanel.EXIT_FSM
    else -> DetailScalpPanel.SCALPING_DESK
}

/**
 * Resolver timeframe chart default per mode strategi — satu-satunya tempat aturan ini
 * ditegakkan. Jangan hardcode pemetaan ini di call site UI atau ViewModel lain.
 *
 * Latar (2026-10-26): `MarketViewModel` membawa bawaan global `Timeframe.H4` dan
 * `setStrategyMode` tidak pernah menyentuhnya, jadi pindah swing → scalping
 * membiarkan chart tetap di H4 — timeframe yang jauh di luar horizon scalping
 * (time-stop exit 90 menit, MFE diukur 7 jam). Pemetaannya mengikuti peran
 * timeframe yang sudah dideklarasikan tiap mode:
 *
 * - **SCALPING → M15**: kartu settings mendeklarasikan "Bias: 1H · Setup: 15M ·
 *   Trigger: 1M"; M15 adalah level setup yang masih terbaca manusia. User bebas
 *   mengganti chip setelahnya — resolver ini hanya menentukan default saat
 *   perpindahan mode.
 * - **INTRADAY (OFFICE_DAILY) → H1**: deskripsi mode "Open Pagi · Close Malam",
 *   horizon sehari tapi bukan skala menit.
 * - **SWING → D1**: deskripsi mode "Jangka menengah (1H–1D)".
 *
 * `when` sengaja TANPA cabang `else`: mode baru yang ditambahkan ke
 * [StrategyMode] akan membuat kompilasi gagal sampai pemetaannya diputuskan
 * secara sadar (fail-closed, pola sama dengan resolver lain di berkas ini).
 */
fun resolveDefaultTimeframe(mode: StrategyMode): Timeframe = when (mode) {
    StrategyMode.SCALPING -> Timeframe.M15
    StrategyMode.OFFICE_DAILY -> Timeframe.H1
    StrategyMode.SWING -> Timeframe.D1
}

/**
 * Item checkpoint terstruktur untuk visualisasi workflow.
 */
data class TradingCheckpointItem(
    val number: Int,
    val tabLabel: String,
    val title: String,
    val status: CheckpointStatus,
    val isOk: Boolean,
    val detail: String
)

/**
 * Evaluator deterministik untuk 4 Checkpoint SELL terpisah:
 * 1. Position Health
 * 2. Risk Protection
 * 3. Profit Target
 * 4. Sell Decision
 */
object SellCheckpointEvaluator {
    fun evaluate(
        context: PositionContext,
        sellSignal: SellSignalState,
        quoteAsset: String = "IDR"
    ): List<TradingCheckpointItem> {
        if (!context.hasPosition) return emptyList()

        val entry = context.entryPrice
        val current = context.currentPrice ?: 0.0
        val pnlPct = context.floatingProfitPct
        val cost = context.costBasis
        val sl = context.stopLoss
        val tp1 = context.tp1

        // 1. Position Health
        val healthStatus = if (entry != null && entry > 0.0 && context.quantity != null && context.quantity > 0.0) {
            CheckpointStatus.COMPLETED
        } else {
            CheckpointStatus.ACTIVE
        }
        val pnlFormatted = if (pnlPct != null) PriceFormatter.formatPercentage(pnlPct, includePlusSign = true) else "N/A"
        val healthDetail = if (entry != null && entry > 0.0 && cost != null) {
            "Entri: ${PriceFormatter.formatPrice(entry, quoteAsset = quoteAsset)} · Modal: ${PriceFormatter.formatPrice(cost, quoteAsset = quoteAsset)} (PnL $pnlFormatted)"
        } else {
            "Kuantitas: ${context.quantity ?: 0.0} · Entri riil belum tercatat"
        }
        val item1 = TradingCheckpointItem(
            number = 1,
            tabLabel = "1. Posisi",
            title = "1. Position Health · Posisi Aktif",
            status = healthStatus,
            isOk = healthStatus == CheckpointStatus.COMPLETED,
            detail = healthDetail
        )

        // 2. Risk Protection (Stop Loss, Rapid Drop, & Trailing Stop)
        val isSlHit = (sl != null && sl > 0.0 && current <= sl) || sellSignal.state == SellLifecycleState.STOP_LOSS_HIT
        val isRapidDropHit = sellSignal.state == SellLifecycleState.RAPID_DROP_EXIT
        val isTrailingHit = context.isTrailingTriggered || sellSignal.state == SellLifecycleState.TRAILING_TRIGGERED
        val riskStatus = when {
            isSlHit || isRapidDropHit || isTrailingHit -> CheckpointStatus.WARNING
            context.trailingActive -> CheckpointStatus.COMPLETED
            sl != null && sl > 0.0 -> CheckpointStatus.ACTIVE
            else -> CheckpointStatus.MONITORING
        }
        val riskDetail = when {
            isSlHit -> "Stop Loss Tersentuh di ${PriceFormatter.formatPrice(sl ?: 0.0, quoteAsset = quoteAsset)}! Risiko terpicu."
            isRapidDropHit -> "Rapid Drop Terdeteksi! ${sellSignal.reason}. Amankan modal segera."
            isTrailingHit -> "Trailing Stop Terpicu! Amankan posisi Anda."
            context.trailingActive -> "Trailing Stop Aktif melindungi keuntungan posisi."
            sl != null && sl > 0.0 -> "Batas Stop Loss terjaga di ${PriceFormatter.formatPrice(sl, quoteAsset = quoteAsset)}."
            else -> "Risiko terpantau normal. Siap cut loss bila perlu."
        }
        val item2 = TradingCheckpointItem(
            number = 2,
            tabLabel = "2. Proteksi",
            title = "2. Risk Protection · Batas Risiko & SL",
            status = riskStatus,
            isOk = riskStatus == CheckpointStatus.COMPLETED || riskStatus == CheckpointStatus.ACTIVE,
            detail = riskDetail
        )

        // 3. Profit Target
        val isTpReached = (tp1 != null && tp1 > 0.0 && current >= tp1) ||
                (sellSignal.state == SellLifecycleState.READY_TO_SELL && (pnlPct ?: 0.0) > 0.0)
        val isApproaching = (tp1 != null && tp1 > 0.0 && current >= tp1 * 0.98 && current < tp1) ||
                sellSignal.state == SellLifecycleState.APPROACHING_TARGET
        val targetStatus = when {
            isTpReached -> CheckpointStatus.COMPLETED
            isApproaching -> CheckpointStatus.READY
            (pnlPct ?: 0.0) > 0.0 -> CheckpointStatus.ACTIVE
            else -> CheckpointStatus.MONITORING
        }
        val targetDetail = when {
            isTpReached -> "Target TP1 ${PriceFormatter.formatPrice(tp1 ?: 0.0, quoteAsset = quoteAsset)} Tercapai ($pnlFormatted)!"
            isApproaching -> "Mendekati target TP1 di ${PriceFormatter.formatPrice(tp1 ?: 0.0, quoteAsset = quoteAsset)}."
            (pnlPct ?: 0.0) > 0.0 -> "Floating Profit $pnlFormatted. Bergerak menuju target."
            tp1 != null && tp1 > 0.0 -> "Target TP1 di ${PriceFormatter.formatPrice(tp1, quoteAsset = quoteAsset)}. Memantau pergerakan."
            else -> "Memantau momentum target profit..."
        }
        val item3 = TradingCheckpointItem(
            number = 3,
            tabLabel = "3. Target",
            title = "3. Profit Target · Evaluasi TP",
            status = targetStatus,
            isOk = targetStatus == CheckpointStatus.COMPLETED || targetStatus == CheckpointStatus.READY,
            detail = targetDetail
        )

        // 4. Sell Decision
        val decisionStatus = when (sellSignal.state) {
            SellLifecycleState.READY_TO_SELL -> CheckpointStatus.READY
            SellLifecycleState.STOP_LOSS_HIT -> CheckpointStatus.WARNING
            SellLifecycleState.RAPID_DROP_EXIT -> CheckpointStatus.WARNING
            SellLifecycleState.TRAILING_TRIGGERED -> CheckpointStatus.READY
            SellLifecycleState.APPROACHING_TARGET -> CheckpointStatus.ACTIVE
            SellLifecycleState.MONITORING -> CheckpointStatus.MONITORING
            SellLifecycleState.NOT_HOLDING -> CheckpointStatus.LOCKED
        }
        val decisionDetail = when (sellSignal.state) {
            SellLifecycleState.READY_TO_SELL -> "SIAP JUAL: ${sellSignal.reason} ($pnlFormatted)"
            SellLifecycleState.STOP_LOSS_HIT -> "CUT LOSS: ${sellSignal.reason}"
            SellLifecycleState.RAPID_DROP_EXIT -> "RAPID DROP EXIT: ${sellSignal.reason}"
            SellLifecycleState.TRAILING_TRIGGERED -> "TRAILING EXIT: ${sellSignal.reason}"
            SellLifecycleState.APPROACHING_TARGET -> "MENDEKATI EXIT: Siapkan rencana jual."
            SellLifecycleState.MONITORING -> "MEMANTAU: Pertahankan posisi sesuai rencana trading."
            SellLifecycleState.NOT_HOLDING -> "Tidak ada posisi aktif."
        }
        val item4 = TradingCheckpointItem(
            number = 4,
            tabLabel = "4. Keputusan",
            title = "4. Sell Decision · Rekomendasi Eksekusi",
            status = decisionStatus,
            isOk = decisionStatus == CheckpointStatus.READY || decisionStatus == CheckpointStatus.WARNING,
            detail = decisionDetail
        )

        return listOf(item1, item2, item3, item4)
    }
}

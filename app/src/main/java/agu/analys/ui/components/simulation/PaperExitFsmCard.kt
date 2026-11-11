package agu.analys.ui.components.simulation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.scalping.PaperAutoTradeConfig
import agu.analys.engine.scalping.PaperExitPhase
import agu.analys.engine.scalping.PaperExitSnapshot
import agu.analys.engine.scalping.ScalpExitMode
import agu.analys.engine.scalping.ScalpExitParams
import agu.analys.ui.animation.AnimatedMetricText
import agu.analys.ui.animation.AnimatedPercentageBadge
import agu.analys.ui.animation.AppAnimations
import agu.analys.ui.animation.LocalPriceAnimationMode
import agu.analys.ui.animation.PriceAnimationMode
import agu.analys.ui.animation.livePulse
import agu.analys.ui.animation.rememberLivePulseAlpha
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Indikator FSM exit paper trade (P2).
 *
 * Menampilkan posisi simulasi yang sedang berjalan beserta fase state machine-nya, level SL
 * aktif (yang bisa sudah naik ke BE / trailing), serta R net berjalan. Semua angka berasal
 * dari [PaperExitSnapshot] — mesin yang sama dengan yang dipakai engine untuk memutuskan exit,
 * jadi tidak ada dua sumber kebenaran.
 *
 * Kartu ini **hanya untuk paper trade**. Tidak ada tombol apa pun yang menyentuh uang riil.
 */
@Composable
fun PaperExitFsmCard(
    snapshot: PaperExitSnapshot?,
    autoTradeConfig: PaperAutoTradeConfig,
    exitParams: ScalpExitParams,
    quoteAsset: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showInfo by remember { mutableStateOf(false) }

    // ── Kosmetik: mode animasi pengguna & denyut indikator hidup ──────────────
    // `PriceAnimationMode.STATIC` adalah katup hemat baterai yang sudah ada di aplikasi;
    // kartu ini menghormatinya alih-alih menambah sakelar sendiri.
    val animationMode = LocalPriceAnimationMode.current
    val animationsEnabled = animationMode != PriceAnimationMode.STATIC
    val pulseAlpha = rememberLivePulseAlpha()

    // ── Deteksi SL bergeser (BE / trailing naik) ──────────────────────────────
    // Saat SL aktif naik, baris "SL aktif" berkedip sebentar. Tanpa ini, pergeseran
    // proteksi lewat begitu saja di antara dua pembaruan harga — padahal itulah momen
    // paling penting untuk disadari pengguna.
    var previousStopLoss by remember(snapshot?.symbol) {
        mutableStateOf(snapshot?.activeStopLoss ?: 0.0)
    }
    var stopLossFlash by remember(snapshot?.symbol) { mutableStateOf(false) }

    LaunchedEffect(snapshot?.activeStopLoss) {
        val current = snapshot?.activeStopLoss ?: 0.0
        if (current > previousStopLoss + 1e-12) {
            if (animationsEnabled) {
                stopLossFlash = true
                delay(AppAnimations.NORMAL_MS.toLong())
                stopLossFlash = false
            }
        }
        previousStopLoss = current
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ── Header ────────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = TvCyan,
                    modifier = Modifier
                        .size(15.dp)
                        .then(
                            // Denyut hanya saat ada posisi yang benar-benar dilacak.
                            if (animationsEnabled && snapshot != null) {
                                Modifier.livePulse(pulseAlpha)
                            } else {
                                Modifier
                            }
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "FSM EXIT PAPER TRADE",
                    color = TvTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                ModeChip(exitParams.mode)
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Penjelasan FSM exit",
                    tint = TvTextSecondary,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { showInfo = !showInfo }
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Tahapan auto-exit posisi SIMULASI. Engine ini hanya menutup posisi paper — " +
                    "tidak pernah mengeksekusi order uang riil.",
                color = TvTextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )

            if (showInfo) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TvSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "Urutan pemeriksaan tiap tick: SL aktif → TP2 → TP1 partial → arm BE → " +
                                "trailing dari peak → early profit. Hitungan bar M1 dan time-stop hanya " +
                                "maju sekali per menit. Satuan R memakai fee all-in + slippage, sama " +
                                "dengan AUDIT-2026-11-08.md.",
                            color = TvTextSecondary,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (snapshot == null) {
                EmptyFsmState(autoTradeConfig = autoTradeConfig, onOpenSettings = onOpenSettings)
            } else {
                FsmPhaseStepper(
                    snapshot = snapshot,
                    animationsEnabled = animationsEnabled,
                    pulseAlpha = pulseAlpha
                )
                Spacer(Modifier.height(10.dp))
                FsmLevelLadder(
                    snapshot = snapshot,
                    quoteAsset = quoteAsset,
                    stopLossFlash = stopLossFlash,
                    animationsEnabled = animationsEnabled
                )
                Spacer(Modifier.height(10.dp))
                FsmPositionBar(snapshot = snapshot, animationsEnabled = animationsEnabled)
                Spacer(Modifier.height(10.dp))
                FsmMetricsRow(
                    snapshot = snapshot,
                    animationsEnabled = animationsEnabled,
                    pulseAlpha = pulseAlpha
                )
            }
        }
    }
}

@Composable
private fun ModeChip(mode: ScalpExitMode) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = TvCyan.copy(alpha = 0.18f),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvCyan.copy(alpha = 0.5f))
    ) {
        Text(
            mode.label.uppercase(Locale.US),
            color = TvCyan,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun EmptyFsmState(
    autoTradeConfig: PaperAutoTradeConfig,
    onOpenSettings: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            if (autoTradeConfig.autoBuyEnabled) {
                "Tidak ada posisi paper terbuka. Auto-buy menunggu sinyal scalp yang lolos ambang " +
                    "stage minimal."
            } else {
                "Auto-buy paper masih nonaktif, jadi belum ada posisi yang dilacak FSM ini."
            },
            color = TvTextSecondary,
            fontSize = 10.sp,
            lineHeight = 14.sp
        )
        if (!autoTradeConfig.autoBuyEnabled) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(TvCyan.copy(alpha = 0.12f))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = TvCyan,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Buka Pengaturan → mode SCALPING",
                    color = TvCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** Rangkaian fase FSM; fase yang sudah dilalui ditandai, transisinya dianimasikan. */
@Composable
private fun FsmPhaseStepper(
    snapshot: PaperExitSnapshot,
    animationsEnabled: Boolean,
    pulseAlpha: Float
) {
    val phases = buildList {
        add(PaperExitPhase.HOLDING)
        if (snapshot.mode.usesTp1Partial) add(PaperExitPhase.TP1_PARTIAL)
        if (snapshot.mode.usesBreakeven) add(PaperExitPhase.BREAKEVEN)
        if (snapshot.mode.usesTrailing) add(PaperExitPhase.TRAILING)
    }

    val reached = buildSet {
        add(PaperExitPhase.HOLDING)
        if (snapshot.isPartialDone) add(PaperExitPhase.TP1_PARTIAL)
        if (snapshot.phase == PaperExitPhase.BREAKEVEN || snapshot.phase == PaperExitPhase.TRAILING) {
            add(PaperExitPhase.BREAKEVEN)
        }
        if (snapshot.phase == PaperExitPhase.TRAILING) add(PaperExitPhase.TRAILING)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            phases.forEachIndexed { index, phase ->
                FsmPhaseDot(
                    phase = phase,
                    isActive = snapshot.phase == phase,
                    isReached = phase in reached,
                    animationsEnabled = animationsEnabled,
                    pulseAlpha = pulseAlpha
                )
                if (index != phases.lastIndex) {
                    val connectorReached = phases[index + 1] in reached
                    val connectorColor by animateColorAsState(
                        targetValue = if (connectorReached) TvGreen.copy(alpha = 0.5f) else TvBorder,
                        animationSpec = tween(AppAnimations.NORMAL_MS),
                        label = "fsm_connector"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(connectorColor)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            snapshot.phase.label,
            color = TvCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Satu titik fase: ukuran, warna, dan denyutnya dianimasikan. */
@Composable
private fun FsmPhaseDot(
    phase: PaperExitPhase,
    isActive: Boolean,
    isReached: Boolean,
    animationsEnabled: Boolean,
    pulseAlpha: Float
) {
    val targetColor = when {
        isActive -> TvCyan
        isReached -> TvGreen
        else -> TvTextMuted
    }
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(AppAnimations.NORMAL_MS),
        label = "fsm_phase_color"
    )
    val dotSize by animateFloatAsState(
        targetValue = if (isActive) 12f else 9f,
        animationSpec = tween(AppAnimations.NORMAL_MS),
        label = "fsm_phase_size"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp)
    ) {
        Box(
            modifier = Modifier
                .size(dotSize.dp)
                .clip(CircleShape)
                .background(color)
                .then(
                    if (isActive) {
                        Modifier.border(2.dp, TvCyan.copy(alpha = 0.35f), CircleShape)
                    } else {
                        Modifier
                    }
                )
                .then(
                    // Titik fase aktif berdenyut supaya posisi FSM sekarang langsung terbaca.
                    if (animationsEnabled && isActive) Modifier.livePulse(pulseAlpha) else Modifier
                )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            phase.shortLabel(),
            color = color,
            fontSize = 8.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 10.sp
        )
    }
}

private fun PaperExitPhase.shortLabel(): String = when (this) {
    PaperExitPhase.HOLDING -> "HOLDING"
    PaperExitPhase.TP1_PARTIAL -> "TP1 50%"
    PaperExitPhase.BREAKEVEN -> "BE"
    PaperExitPhase.TRAILING -> "TRAIL"
    PaperExitPhase.CLOSED -> "CLOSED"
}

/** Tangga level: entry, SL awal, SL aktif, BE net, peak, TP1, TP2. */
@Composable
private fun FsmLevelLadder(
    snapshot: PaperExitSnapshot,
    quoteAsset: String,
    stopLossFlash: Boolean,
    animationsEnabled: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        FsmLevelRow(
            "Entry", snapshot.entry, quoteAsset, TvTextSecondary,
            "SL awal", snapshot.initialStopLoss, TvRed,
            animationsEnabled = animationsEnabled
        )
        // Baris SL aktif berkedip saat proteksi bergeser (BE / trailing naik).
        FsmLevelRow(
            "SL aktif", snapshot.activeStopLoss, quoteAsset,
            if (snapshot.stopHasMoved) TvGreen else TvRed,
            "BE net", snapshot.netBreakevenPrice, TvAmber,
            animationsEnabled = animationsEnabled,
            highlighted = stopLossFlash
        )
        FsmLevelRow(
            "Peak", snapshot.peak, quoteAsset, TvTextSecondary,
            "Harga kini", snapshot.currentPrice, TvTextPrimary,
            animationsEnabled = animationsEnabled
        )
        FsmLevelRow(
            "TP1", snapshot.tp1, quoteAsset, TvTextSecondary,
            "TP2", snapshot.tp2, TvGreen,
            animationsEnabled = animationsEnabled
        )
    }
}

@Composable
private fun FsmLevelRow(
    leftLabel: String,
    leftValue: Double,
    quoteAsset: String,
    leftColor: Color,
    rightLabel: String,
    rightValue: Double,
    rightColor: Color,
    animationsEnabled: Boolean = true,
    highlighted: Boolean = false
) {
    val background by animateColorAsState(
        targetValue = if (highlighted) TvGreen.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = tween(AppAnimations.NORMAL_MS),
        label = "fsm_sl_flash_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (highlighted) TvGreen.copy(alpha = 0.7f) else Color.Transparent,
        animationSpec = tween(AppAnimations.NORMAL_MS),
        label = "fsm_sl_flash_border"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(leftLabel, color = TvTextMuted, fontSize = 9.sp, modifier = Modifier.width(56.dp))
        FsmValueText(
            value = PriceFormatter.formatPrice(leftValue, quoteAsset = quoteAsset),
            color = leftColor,
            fontSize = 10.sp,
            animationsEnabled = animationsEnabled,
            modifier = Modifier.weight(1f)
        )
        Text(rightLabel, color = TvTextMuted, fontSize = 9.sp, modifier = Modifier.width(56.dp))
        FsmValueText(
            value = PriceFormatter.formatPrice(rightValue, quoteAsset = quoteAsset),
            color = rightColor,
            fontSize = 10.sp,
            animationsEnabled = animationsEnabled,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Nilai yang beranimasi saat berubah, atau teks biasa bila pengguna memilih
 * [PriceAnimationMode.STATIC]. Satu tempat saja supaya seluruh kartu konsisten.
 */
@Composable
private fun FsmValueText(
    value: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    if (animationsEnabled) {
        AnimatedMetricText(
            value = value,
            color = color,
            fontSize = fontSize,
            modifier = modifier
        )
    } else {
        Text(
            text = value,
            color = color,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            modifier = modifier
        )
    }
}

/** Bar posisi: SL awal di kiri, TP2 di kanan, penanda harga sekarang. */
@Composable
private fun FsmPositionBar(snapshot: PaperExitSnapshot, animationsEnabled: Boolean) {
    val lo = snapshot.initialStopLoss
    val hi = snapshot.tp2
    val span = hi - lo
    val targetFraction = if (span > 0.0) {
        ((snapshot.currentPrice - lo) / span).coerceIn(0.0, 1.0).toFloat()
    } else {
        0.5f
    }
    // Penanda harga meluncur ke posisi baru alih-alih melompat, supaya arah pergerakan terbaca.
    val fraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(if (animationsEnabled) AppAnimations.SLOW_MS else 0),
        label = "fsm_price_marker"
    )
    val hasBreakevenInRange = span > 0.0 && snapshot.netBreakevenPrice in lo..hi
    val beFraction by animateFloatAsState(
        targetValue = if (hasBreakevenInRange) {
            ((snapshot.netBreakevenPrice - lo) / span).toFloat()
        } else {
            0f
        },
        animationSpec = tween(if (animationsEnabled) AppAnimations.SLOW_MS else 0),
        label = "fsm_be_marker"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(TvRed.copy(alpha = 0.55f), TvAmber.copy(alpha = 0.55f), TvGreen.copy(alpha = 0.55f))
                    )
                )
        ) {
            // Garis BE net, bila masih di dalam rentang.
            if (hasBreakevenInRange) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(beFraction)
                        .fillMaxHeight()
                        .padding(end = 1.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(TvTextPrimary.copy(alpha = 0.7f))
                    )
                }
            }
            // Penanda harga sekarang.
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(TvTextPrimary)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("SL awal", color = TvTextMuted, fontSize = 8.sp)
            Text("garis putih = BE net", color = TvTextMuted, fontSize = 8.sp)
            Text("TP2", color = TvTextMuted, fontSize = 8.sp)
        }
    }
}

/** Metrik berjalan: net %, R, jumlah bar, jarak ke SL — masing-masing dalam frame sendiri. */
@Composable
private fun FsmMetricsRow(
    snapshot: PaperExitSnapshot,
    animationsEnabled: Boolean,
    pulseAlpha: Float
) {
    val rColor = if (snapshot.currentR >= 0.0) TvGreen else TvRed
    val distanceColor = if (snapshot.distanceToStopPct >= 0.0) TvTextSecondary else TvRed
    val badgeMode = if (animationsEnabled) LocalPriceAnimationMode.current else PriceAnimationMode.STATIC

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FsmMetricTile(label = "Net", modifier = Modifier.weight(1f)) {
                AnimatedPercentageBadge(
                    percentage = snapshot.netProfitPct,
                    fontSize = 11.sp,
                    animationMode = badgeMode
                )
            }
            FsmMetricTile(label = "R berjalan", modifier = Modifier.weight(1f)) {
                FsmValueText(
                    value = String.format(Locale.US, "%+.2fR", snapshot.currentR),
                    color = rColor,
                    fontSize = 12.sp,
                    animationsEnabled = animationsEnabled
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FsmMetricTile(label = "Bar M1", modifier = Modifier.weight(1f)) {
                FsmValueText(
                    value = "${snapshot.barsHeld}",
                    color = TvTextPrimary,
                    fontSize = 12.sp,
                    animationsEnabled = animationsEnabled
                )
            }
            FsmMetricTile(
                label = "Jarak SL",
                modifier = Modifier.weight(1f),
                trailing = {
                    // Petir berdenyut menandai SL sudah digeser (BE / trailing aktif).
                    if (snapshot.stopHasMoved) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "SL sudah digeser",
                            tint = TvGreen,
                            modifier = Modifier
                                .size(13.dp)
                                .then(if (animationsEnabled) Modifier.livePulse(pulseAlpha) else Modifier)
                        )
                    }
                }
            ) {
                FsmValueText(
                    value = String.format(Locale.US, "%.2f%%", snapshot.distanceToStopPct),
                    color = distanceColor,
                    fontSize = 12.sp,
                    animationsEnabled = animationsEnabled
                )
            }
        }
    }
}

/** Satu metrik dalam frame sendiri: label kecil di atas, nilai besar di bawah. */
@Composable
private fun FsmMetricTile(
    label: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    value: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = TvSurface,
        border = BorderStroke(1.dp, TvBorder),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = TvTextMuted, fontSize = 8.sp)
                if (trailing != null) {
                    Spacer(Modifier.weight(1f))
                    trailing()
                }
            }
            Spacer(Modifier.height(2.dp))
            value()
        }
    }
}

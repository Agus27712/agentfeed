package agu.analys.engine.scalping.replay

import agu.analys.config.TradingFeeConfig
import agu.analys.engine.scalping.ScalpingMtfEvaluator
import agu.analys.engine.scalping.ScalpingRiskEngine
import agu.analys.model.CandleBar
import agu.analys.model.SignalAction
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Analisa replay kausal pada candle NYATA (tanpa jaringan, bisa diuji offline).
 *
 * Metodologi:
 * - Tiap bar M1 yang sudah closed dievaluasi hanya dengan data sampai bar itu
 *   (M15/H1 hanya candle yang sudah closed pada waktu tsb).
 * - Entry di OPEN bar berikutnya (bukan di close bar sinyal).
 * - SL/TP1/TP2 dari sinyal; 50% keluar di TP1, 50% di TP2. SL dicek lebih dulu
 *   dalam satu bar (pesimistis). TP2 hanya dihitung di bar SETELAH TP1.
 * - Time stop setelah [lookaheadBars] bar: keluar di close.
 * - Fee + slippage mengikuti rumus FeeCalculator (multiplikatif).
 * - Satu posisi per pair (tanpa overlap).
 * - Orderbook historis tidak tersedia -> Step 2 di-bypass (hasil PROVISIONAL).
 * - Baseline: entry di bar acak/berkala dengan geometri SL/TP rata-rata yang sama.
 */
class RealDataReplayAnalyzer(
    private val fees: TradingFeeConfig,
    private val slippagePct: Double = ScalpingRiskEngine.DEFAULT_SLIPPAGE_PCT,
    private val lookaheadBars: Int = 30,
    private val m1Window: Int = 120,
    private val htfWindow: Int = 60
) {

    data class Trade(
        val symbol: String,
        val entryTime: Long,
        val entry: Double,
        val stopLoss: Double,
        val tp1: Double,
        val tp2: Double,
        val score: Int,
        val setup: String,
        val netPct: Double,
        val rMultiple: Double,
        val exitReason: String,
        val barsHeld: Int
    ) {
        val slPct get() = (entry - stopLoss) / entry * 100.0
        val tp1Pct get() = (tp1 - entry) / entry * 100.0
        val tp2Pct get() = (tp2 - entry) / entry * 100.0
    }

    data class PairResult(
        val symbol: String,
        val barsEvaluated: Int,
        val buySignals: Int,
        val trades: List<Trade>,
        val rejections: Map<String, Int>,
        val firstTime: Long,
        val lastTime: Long
    )

    data class Stats(
        val n: Int,
        val winRatePct: Double,
        val avgNetPct: Double,
        val ci95Pct: Double,
        val avgR: Double,
        val profitFactor: Double,
        val maxConsecutiveLosses: Int,
        val sumNetPct: Double
    )

    // ------------------------------------------------------------------
    // Replay satu pair
    // ------------------------------------------------------------------
    fun analyzePair(symbol: String, m1: List<CandleBar>, m15: List<CandleBar>, h1: List<CandleBar>): PairResult {
        val rejections = linkedMapOf<String, Int>()
        val trades = mutableListOf<Trade>()
        var evaluated = 0
        var buys = 0
        if (m1.size < 40) {
            return PairResult(symbol, 0, 0, emptyList(), mapOf("DATA_M1_KURANG" to 1), 0L, 0L)
        }

        var p15 = 0
        var p1h = 0
        var busyUntil = -1
        var i = 30
        while (i < m1.size - 1) {
            val closeTime = m1[i].timestamp + 60_000L
            while (p15 < m15.size && m15[p15].timestamp + 15 * 60_000L <= closeTime) p15++
            while (p1h < h1.size && h1[p1h].timestamp + 60 * 60_000L <= closeTime) p1h++

            val m1Slice = m1.subList(max(0, i - m1Window + 1), i + 1)
            val m15Slice = m15.subList(max(0, p15 - htfWindow), p15)
            val h1Slice = h1.subList(max(0, p1h - htfWindow), p1h)

            val result = ScalpingMtfEvaluator.evaluate(
                price = m1[i].close,
                h1Candles = h1Slice,
                m15Candles = m15Slice,
                m1Candles = m1Slice,
                fees = fees,
                symbol = symbol,
                diagnosticIgnoreOrderBookWhenUnavailable = true
            )

            if (result == null) {
                rejections.merge("DATA_KURANG", 1, Int::plus)
                i++
                continue
            }
            evaluated++
            val isBuy = result.signal.action == SignalAction.BUY

            if (!isBuy) {
                rejections.merge(result.audit.rejectionReason ?: "WAIT_LAINNYA", 1, Int::plus)
                i++
                continue
            }
            buys++
            if (i <= busyUntil) {
                rejections.merge("SUDAH_DALAM_POSISI", 1, Int::plus)
                i++
                continue
            }

            val entry = m1[i + 1].open
            val sl = result.signal.stopLoss
            val tp1 = result.signal.targetPrice1
            val tp2 = result.signal.targetPrice2
            if (!(sl > 0.0 && sl < entry && entry < tp1 && tp1 < tp2)) {
                rejections.merge("LEVEL_TIDAK_VALID_SAAT_ENTRY", 1, Int::plus)
                i++
                continue
            }

            val sim = simulate(m1, i + 1, entry, sl, tp1, tp2)
            val riskNet = netPct(entry, sl)
            val r = if (riskNet < 0.0) sim.netPct / -riskNet else 0.0
            trades += Trade(
                symbol = symbol,
                entryTime = m1[i + 1].timestamp,
                entry = entry, stopLoss = sl, tp1 = tp1, tp2 = tp2,
                score = result.audit.score,
                setup = result.audit.setup,
                netPct = sim.netPct,
                rMultiple = r,
                exitReason = sim.reason,
                barsHeld = sim.exitIndex - (i + 1) + 1
            )
            busyUntil = sim.exitIndex
            i = max(i + 1, sim.exitIndex)
        }
        return PairResult(symbol, evaluated, buys, trades, rejections, m1.first().timestamp, m1.last().timestamp)
    }

    // ------------------------------------------------------------------
    // Baseline: geometri SL/TP rata-rata yang sama, entry berkala di semua bar
    // ------------------------------------------------------------------
    fun baseline(allM1: Map<String, List<CandleBar>>, geometry: List<Trade>, stride: Int = 5): List<Double> {
        if (geometry.isEmpty()) return emptyList()
        val slPct = geometry.map { it.slPct }.average()
        val tp1Pct = geometry.map { it.tp1Pct }.average()
        val tp2Pct = geometry.map { it.tp2Pct }.average()
        val out = mutableListOf<Double>()
        for ((_, m1) in allM1) {
            var k = 30
            while (k < m1.size - 1) {
                val entry = m1[k + 1].open
                val sim = simulate(
                    m1, k + 1, entry,
                    entry * (1.0 - slPct / 100.0),
                    entry * (1.0 + tp1Pct / 100.0),
                    entry * (1.0 + tp2Pct / 100.0)
                )
                out += sim.netPct
                k += stride
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Simulasi trade
    // ------------------------------------------------------------------
    data class Sim(val netPct: Double, val exitIndex: Int, val reason: String)

    fun simulate(m1: List<CandleBar>, startIdx: Int, entry: Double, sl: Double, tp1: Double, tp2: Double): Sim {
        var remaining = 1.0
        var pnl = 0.0
        var tp1Bar = -1
        val last = min(m1.size - 1, startIdx + lookaheadBars - 1)
        for (j in startIdx..last) {
            val c = m1[j]
            if (c.low <= sl) {
                pnl += remaining * netPct(entry, sl)
                return Sim(pnl, j, if (tp1Bar >= 0) "SL_SETELAH_TP1" else "SL")
            }
            if (tp1Bar < 0 && c.high >= tp1) {
                pnl += 0.5 * netPct(entry, tp1)
                remaining = 0.5
                tp1Bar = j
            } else if (tp1Bar in 0 until j && c.high >= tp2) {
                pnl += remaining * netPct(entry, tp2)
                return Sim(pnl, j, "TP2")
            }
        }
        pnl += remaining * netPct(entry, m1[last].close)
        return Sim(pnl, last, if (tp1Bar >= 0) "TIME_STOP_SETELAH_TP1" else "TIME_STOP")
    }

    /** Return bersih (%) untuk keluar di [exit], termasuk fee + slippage (rumus FeeCalculator). */
    private fun netPct(entry: Double, exit: Double): Double {
        val buyCost = 1.0 + (fees.buyTakerPct + slippagePct) / 100.0
        val sellNet = (1.0 - (fees.sellTakerPct + slippagePct) / 100.0).coerceAtLeast(0.0)
        return ((exit / entry) * sellNet / buyCost - 1.0) * 100.0
    }

    // ------------------------------------------------------------------
    // Statistik
    // ------------------------------------------------------------------
    fun stats(trades: List<Trade>): Stats = statsOf(trades.map { it.netPct }, trades.map { it.rMultiple })

    fun statsOf(net: List<Double>, r: List<Double> = emptyList()): Stats {
        val n = net.size
        if (n == 0) return Stats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0)
        val avg = net.average()
        val variance = if (n > 1) net.sumOf { (it - avg) * (it - avg) } / (n - 1) else 0.0
        val ci = if (n > 1) 1.96 * sqrt(variance) / sqrt(n.toDouble()) else 0.0
        val wins = net.filter { it > 0.0 }
        val losses = net.filter { it <= 0.0 }
        val pf = if (losses.isEmpty()) Double.POSITIVE_INFINITY else wins.sum() / -losses.sum()
        var run = 0
        var maxRun = 0
        for (x in net) {
            if (x <= 0.0) { run++; maxRun = max(maxRun, run) } else run = 0
        }
        return Stats(
            n = n,
            winRatePct = wins.size * 100.0 / n,
            avgNetPct = avg,
            ci95Pct = ci,
            avgR = if (r.isNotEmpty()) r.average() else 0.0,
            profitFactor = pf,
            maxConsecutiveLosses = maxRun,
            sumNetPct = net.sum()
        )
    }

    // ------------------------------------------------------------------
    // Laporan markdown
    // ------------------------------------------------------------------
    fun buildReport(
        title: String,
        params: Map<String, String>,
        pairs: List<PairResult>,
        baselineNet: List<Double>,
        fetchNotes: List<String>
    ): String {
        val all = pairs.flatMap { it.trades }
        val s = stats(all)
        val b = statsOf(baselineNet)
        val sb = StringBuilder()
        sb.appendLine("# $title")
        sb.appendLine()
        sb.appendLine("Dibuat: ${Instant.now()} (UTC)")
        sb.appendLine()
        sb.appendLine("## Parameter")
        params.forEach { (k, v) -> sb.appendLine("- $k: $v") }
        sb.appendLine()
        sb.appendLine("## Catatan metodologi (WAJIB dibaca)")
        sb.appendLine("- Candle M1/M15/H1 NYATA dari Tokocrypto; hanya candle CLOSED; evaluasi kausal (tanpa lihat masa depan).")
        sb.appendLine("- Orderbook historis TIDAK tersedia → Step 2 di-bypass, skor order flow = 0. Hasil **PROVISIONAL**.")
        sb.appendLine("- Entry di OPEN bar berikutnya; fee + slippage ikut dihitung; SL dicek lebih dulu dalam satu bar.")
        sb.appendLine("- Periode data pendek (beberapa hari) = kemungkinan satu rezim pasar saja. Ini BUKAN bukti edge jangka panjang.")
        sb.appendLine()

        sb.appendLine("## Per pair")
        sb.appendLine("| Pair | Periode (UTC) | Bar dievaluasi | Sinyal BUY | Trade | Win% | Rata2 net% |")
        sb.appendLine("|---|---|---:|---:|---:|---:|---:|")
        for (p in pairs) {
            val ps = stats(p.trades)
            val period = if (p.firstTime > 0) "${fmtTime(p.firstTime)} → ${fmtTime(p.lastTime)}" else "-"
            sb.appendLine("| ${p.symbol} | $period | ${p.barsEvaluated} | ${p.buySignals} | ${ps.n} | ${f1(ps.winRatePct)} | ${f3(ps.avgNetPct)} |")
        }
        sb.appendLine()

        sb.appendLine("## Gabungan (semua pair)")
        sb.appendLine("- Jumlah trade: **${s.n}**")
        if (s.n > 0) {
            sb.appendLine("- Win rate (net > 0): **${f1(s.winRatePct)}%**")
            sb.appendLine("- Rata-rata net per trade: **${f3(s.avgNetPct)}%** (±${f3(s.ci95Pct)}% CI95)")
            sb.appendLine("- Rata-rata R: **${f2(s.avgR)}** · Profit factor: **${if (s.profitFactor.isInfinite()) "∞" else f2(s.profitFactor)}**")
            sb.appendLine("- Rugi beruntun maks: **${s.maxConsecutiveLosses}** · Total net: **${f2(s.sumNetPct)}%**")
            val reasons = all.groupingBy { it.exitReason }.eachCount().entries.sortedByDescending { it.value }
            sb.appendLine("- Alasan keluar: " + reasons.joinToString(", ") { "${it.key}=${it.value}" })
            val setups = all.groupingBy { it.setup }.eachCount().entries.sortedByDescending { it.value }
            sb.appendLine("- Setup (count): " + setups.joinToString(", ") { "${it.key}=${it.value}" })
        }
        sb.appendLine()

        // ---- Per-setup breakdown (utama untuk tuning) ----
        appendPerSetupSection(sb, all)

        sb.appendLine("## Baseline (entry berkala di semua bar, geometri SL/TP rata-rata yang sama)")
        if (b.n > 0) {
            sb.appendLine("- Sampel baseline: ${b.n} · Win rate: ${f1(b.winRatePct)}% · Rata-rata net: **${f3(b.avgNetPct)}%** (±${f3(b.ci95Pct)}%)")
        } else {
            sb.appendLine("- Tidak ada baseline (tidak ada trade untuk dijadikan geometri).")
        }
        sb.appendLine()

        sb.appendLine("## Kesimpulan otomatis")
        sb.appendLine(verdict(s, b))
        if (all.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine(perSetupVerdict(all))
        }
        sb.appendLine()

        sb.appendLine("## Penolakan sinyal (semua pair)")
        val rej = linkedMapOf<String, Int>()
        pairs.forEach { p -> p.rejections.forEach { (k, v) -> rej.merge(k, v, Int::plus) } }
        rej.entries.sortedByDescending { it.value }.take(12).forEach { sb.appendLine("- ${it.key}: ${it.value}") }
        sb.appendLine()

        if (all.isNotEmpty()) {
            sb.appendLine("## 15 trade terakhir")
            sb.appendLine("| Waktu (UTC) | Pair | Setup | Skor | SL% | TP1% | Net% | Keluar |")
            sb.appendLine("|---|---|---|---:|---:|---:|---:|---|")
            all.sortedBy { it.entryTime }.takeLast(15).forEach {
                sb.appendLine("| ${fmtTime(it.entryTime)} | ${it.symbol} | ${it.setup} | ${it.score} | ${f2(it.slPct)} | ${f2(it.tp1Pct)} | ${f3(it.netPct)} | ${it.exitReason} |")
            }
            sb.appendLine()
        }

        if (fetchNotes.isNotEmpty()) {
            sb.appendLine("## Catatan pengambilan data")
            fetchNotes.forEach { sb.appendLine("- $it") }
        }
        return sb.toString()
    }

    /**
     * Tabel statistik per [Trade.setup] + mix exit + note sampel kecil.
     */
    private fun appendPerSetupSection(sb: StringBuilder, all: List<Trade>) {
        sb.appendLine("## Per setup (breakdown)")
        if (all.isEmpty()) {
            sb.appendLine("- Tidak ada trade.")
            sb.appendLine()
            return
        }

        val total = all.size.toDouble()
        val bySetup = all.groupBy { it.setup.ifBlank { "UNKNOWN" } }
            .entries
            .sortedByDescending { it.value.size }

        sb.appendLine("Statistik dihitung dari field `Trade.setup` / skor / exitReason. " +
            "n < 30 = indikasi saja, jangan dipakai untuk klaim edge.")
        sb.appendLine()
        sb.appendLine("| Setup | n | % total | Win% | Avg net% | CI95 | Avg R | PF | Sum net% | Avg skor | Avg bar | % TIME* | % SL* | % TP* |")
        sb.appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")

        for ((setup, trades) in bySetup) {
            val st = stats(trades)
            val pct = trades.size * 100.0 / total
            val avgScore = trades.map { it.score }.average()
            val avgBars = trades.map { it.barsHeld }.average()
            val exits = trades.groupingBy { it.exitReason }.eachCount()
            val timePct = exitShare(exits, trades.size) { it.startsWith("TIME_STOP") }
            val slPct = exitShare(exits, trades.size) { it.startsWith("SL") }
            val tpPct = exitShare(exits, trades.size) { it == "TP2" || it.contains("TP1") }
            val pfStr = when {
                st.profitFactor.isInfinite() -> "∞"
                st.n == 0 -> "-"
                else -> f2(st.profitFactor)
            }
            val noteN = if (st.n < 30) "†" else ""
            sb.appendLine(
                "| $setup$noteN | ${st.n} | ${f1(pct)} | ${f1(st.winRatePct)} | ${f3(st.avgNetPct)} | " +
                    "±${f3(st.ci95Pct)} | ${f2(st.avgR)} | $pfStr | ${f2(st.sumNetPct)} | " +
                    "${f1(avgScore)} | ${f1(avgBars)} | ${f1(timePct)} | ${f1(slPct)} | ${f1(tpPct)} |"
            )
        }
        sb.appendLine()
        sb.appendLine("† = n < 30 (sampel kecil). % TIME* = TIME_STOP + TIME_STOP_SETELAH_TP1. " +
            "% SL* = SL + SL_SETELAH_TP1. % TP* = TP2 + exit yang menyentuh TP1 (termasuk time/SL setelah TP1).")
        sb.appendLine()

        sb.appendLine("### Exit mix per setup")
        for ((setup, trades) in bySetup) {
            val exits = trades.groupingBy { it.exitReason }.eachCount()
                .entries.sortedByDescending { it.value }
            val detail = exits.joinToString(", ") { "${it.key}=${it.value}" }
            sb.appendLine("- **$setup** (n=${trades.size}): $detail")
        }
        sb.appendLine()

        sb.appendLine("### Geometri rata-rata per setup (sebelum fee di path simulasi)")
        sb.appendLine("| Setup | Avg SL% | Avg TP1% | Avg TP2% |")
        sb.appendLine("|---|---:|---:|---:|")
        for ((setup, trades) in bySetup) {
            sb.appendLine(
                "| $setup | ${f2(trades.map { it.slPct }.average())} | " +
                    "${f2(trades.map { it.tp1Pct }.average())} | " +
                    "${f2(trades.map { it.tp2Pct }.average())} |"
            )
        }
        sb.appendLine()
    }

    private fun exitShare(exits: Map<String, Int>, n: Int, pred: (String) -> Boolean): Double {
        if (n <= 0) return 0.0
        val c = exits.entries.filter { pred(it.key) }.sumOf { it.value }
        return c * 100.0 / n
    }

    /** Ringkas setup mana yang mendominasi hasil jelek/baik (teks, bukan klaim edge). */
    private fun perSetupVerdict(all: List<Trade>): String {
        val bySetup = all.groupBy { it.setup.ifBlank { "UNKNOWN" } }
        if (bySetup.size <= 1) {
            val only = bySetup.keys.firstOrNull() ?: "UNKNOWN"
            return "Hampir semua trade bertipe **$only** — hasil aggregate ≈ kualitas setup ini saja."
        }
        val ranked = bySetup.map { (name, trades) -> name to stats(trades) }
            .sortedBy { it.second.avgNetPct }
        val worst = ranked.first()
        val best = ranked.last()
        val dominant = bySetup.maxByOrNull { it.value.size }!!
        val domShare = dominant.value.size * 100.0 / all.size
        return buildString {
            append("Setup dominan: **${dominant.key}** (${f1(domShare)}% trade). ")
            append("Avg net terendah: **${worst.first}** (${f3(worst.second.avgNetPct)}%, n=${worst.second.n}). ")
            append("Avg net tertinggi: **${best.first}** (${f3(best.second.avgNetPct)}%, n=${best.second.n}). ")
            if (dominant.value.size >= all.size * 0.5) {
                append("Tuning sebaiknya fokus ke setup dominan dulu.")
            } else {
                append("Tidak ada setup >50% — bandingkan baris tabel per setup sebelum mematikan salah satu.")
            }
        }
    }

    fun verdict(s: Stats, b: Stats): String {
        if (s.n == 0) {
            return "Tidak ada sinyal LONG pada periode ini. Engine sangat selektif atau setup belum muncul — belum bisa dinilai."
        }
        if (s.n < 30) {
            return "Sampel hanya ${s.n} trade (< 30). **Belum bisa disimpulkan.** Perpanjang periode / tambah pair."
        }
        val positive = s.avgNetPct - s.ci95Pct > 0.0
        val diff = s.avgNetPct - b.avgNetPct
        val diffCi = sqrt(s.ci95Pct * s.ci95Pct + b.ci95Pct * b.ci95Pct)
        return when {
            positive && diff > diffCi ->
                "Ada indikasi hasil positif setelah fee DAN lebih baik dari baseline secara berarti. Tetap PROVISIONAL: perlu data lebih panjang, rezim pasar berbeda, dan orderbook nyata."
            positive ->
                "Hasil positif setelah fee, tetapi **tidak berbeda nyata dari baseline** — kemungkinan besar karena arah pasar, bukan keunggulan engine."
            diff > 0.0 ->
                "Rata-rata sedikit di atas baseline, tetapi CI95 mencakup 0. **Belum ada bukti edge yang meyakinkan.**"
            else ->
                "**Belum ada bukti edge**: hasil tidak lebih baik dari baseline entry berkala dengan geometri yang sama."
        }
    }

    private fun fmtTime(ms: Long) = Instant.ofEpochMilli(ms).toString().replace("T", " ").removeSuffix("Z").take(16)
    private fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
    private fun f3(v: Double) = String.format(java.util.Locale.US, "%.3f", v)
}

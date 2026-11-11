package agu.analys.engine.intraday

import agu.analys.config.FeeSchedule
import agu.analys.config.TradingFeeConfig

/**
 * Hasil resolusi geometri SL/TP mode intraday — **satu definisi geometri** untuk seluruh sinyal
 * intraday (dipakai `IntradayEvaluator` dan diteruskan ke checkpoint 6 `ConfluenceEvaluator`).
 *
 * Sebelum paket C, geometri hidup di dua tempat dengan angka berbeda (`IntradayEvaluator` memakai
 * band persen SL −1,5%…−2,8% / TP1 +2,8%…+5,0%, sedangkan checkpoint 6 `ConfluenceEvaluator`
 * menghitung SL/TP sendiri dengan gate `netRr >= 2.0` yang hardcoded) sehingga dua definisi
 * R:R hidup bersamaan dalam satu sinyal. Paket C menyatukannya di sini.
 */
data class IntradayGeometryResult(
    /** ATR intraday setelah clamp band volatilitas, dalam % harga. */
    val intradayAtrPct: Double,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    /** Net R:R yang dihitung dari [takeProfit1] final (setelah batas atas), multiplikatif. */
    val netRrTp1: Double,
    val netRiskPct: Double,
    val netRewardPct: Double,
    /** Kenaikan harga yang benar-benar menutup biaya (impas net), %. */
    val impasNetPct: Double,
    /** true bila batas atas (resistance / band maksimum) memotong TP1 hasil R — gate gagal jujur. */
    val tp1TerikatBatas: Boolean
)

/**
 * Geometri SL/TP intraday — turunan dari **SL + ATR + gate R:R**, bukan band persen tetap.
 *
 * Alasan (paket C, keputusan owner 28 Okt 2026): dengan ambang `minNetRrTp1 = 1,5` dan biaya
 * all-in 0,79–0,97%, TP1 band lama (+2,8%–5,0%) **tidak pernah** memenuhi gate (net R:R ≈0,74–0,87),
 * dan angka yang dibutuhkan berubah mengikuti SL yang benar-benar dipakai (SL −1,5% butuh TP1
 * ≈+4,2% IDR; SL −2,8% butuh ≈+6,2%). Karena itu TP1 diturunkan dari SL, bukan band tetap:
 *
 * 1. **ATR** di-clamp ke band volatilitas harian sehat (`atrFloorPct`…`atrCapPct`) — sama seperti
 *    perilaku lama, tetapi angkanya sekarang dari config.
 * 2. **SL** dari support dikurangi `slAtrMult × ATR`, dijaga dalam band
 *    `[minSlDistancePct, maxSlPct]` dengan `minSlDistancePct = max(minSlPct,
 *    minSlFrictionMultiple × impas net)` — stop tidak boleh lebih rapat dari beberapa kali biaya,
 *    kalau tidak stop-out terjadi di dalam band biaya. Bila tidak ada support, SL memakai
 *    `(noSupportAtrMult + slAtrMult) × ATR` yang **sudah di-clamp** — tidak ada lagi literal 1,2
 *    dan tidak ada dua ukuran ATR dalam satu fungsi.
 * 3. **TP1 = max(TP1-ATR, TP1-dari-R)**, dibatasi batas atas `min(resistance, price × (1+maxTp1Pct))`.
 *    TP1-dari-R adalah harga yang membuat net R:R tepat `minNetRrTp1` (rumus yang sama dengan
 *    `FeeCalculator.roundTrip`, multiplikatif). Batas atas sengaja ada supaya gate **masih bisa
 *    gagal secara jujur** ketika ruang menuju resistance tidak cukup — tanpa itu geometri ini
 *    membuat gate tautologis (selalu lolos).
 * 4. **Gate** tetap dihitung dari TP1 final tanpa clamp (`minNetRrTp1` tidak pernah di-clamp).
 *
 * **Fail-closed:** harga tidak valid (`<= 0`, `NaN`, `∞`) mengembalikan `null`, bukan melempar
 * `require` yang membuat proses crash. Pemanggil wajib memperlakukan `null` sebagai "tidak ada
 * geometri → jangan bersinyal".
 */
object IntradayGeometry {

    fun resolve(
        price: Double,
        supportLevel: Double?,
        resistanceLevel: Double?,
        atr: Double,
        fees: TradingFeeConfig,
        config: IntradayConfig
    ): IntradayGeometryResult? {
        // Fail-closed, bukan crash: `require` di jalur evaluasi per-tick berarti satu harga rusak
        // dari feed bursa bisa menjatuhkan proses. Tidak ada geometri = tidak ada sinyal.
        if (!price.isFinite() || price <= 0.0) return null

        val effectiveAtr = if (atr.isFinite() && atr > 0.0) atr else price * 0.04
        val atrPct = (effectiveAtr / price * 100.0).coerceIn(config.atrFloorPct, config.atrCapPct)
        val intradayAtr = price * atrPct / 100.0

        val buyFeePct = if (config.useMakerFee) fees.buyMakerPct else fees.buyTakerPct
        val sellFeePct = if (config.useMakerFee) fees.sellMakerPct else fees.sellTakerPct
        val buyCostFactor = 1.0 + (buyFeePct + config.slippagePct) / 100.0
        val sellNetFactor = (1.0 - (sellFeePct + config.slippagePct) / 100.0).coerceAtLeast(0.0)

        // Impas net (kenaikan harga yang benar-benar menutup biaya) — satu rumus dengan FeeSchedule.
        val impasNetPct =
            (FeeSchedule.breakevenPrice(price, buyFeePct, sellFeePct, config.slippagePct) / price - 1.0) * 100.0

        // ── SL ──────────────────────────────────────────────────────────────
        val slDariSupport = if (supportLevel != null && supportLevel > 0.0 && supportLevel < price) {
            supportLevel - intradayAtr * config.slAtrMult
        } else {
            // Tanpa support: jarak dari config, memakai ATR yang sama (sudah di-clamp).
            price - intradayAtr * (config.noSupportAtrMult + config.slAtrMult)
        }
        val minSlDistancePct = maxOf(
            config.minSlPct,
            config.minSlFrictionMultiple * impasNetPct
        )
        val maxSlDistancePct = maxOf(config.maxSlPct, minSlDistancePct)
        val sl = minOf(
            maxOf(slDariSupport, price * (1.0 - maxSlDistancePct / 100.0)),
            price * (1.0 - minSlDistancePct / 100.0)
        )

        // ── TP1 ─────────────────────────────────────────────────────────────
        val netRiskPct = (1.0 - (sl / price) * sellNetFactor / buyCostFactor) * 100.0
        val netRewardDibutuhkanPct = config.minNetRrTp1 * netRiskPct
        val tp1DariR = price * (1.0 + netRewardDibutuhkanPct / 100.0) * buyCostFactor / sellNetFactor
        val tp1DariAtr = price + intradayAtr * config.tp1AtrMult
        val batasAtasTp1 = listOfNotNull(
            resistanceLevel?.takeIf { it > price },
            price * (1.0 + config.maxTp1Pct / 100.0)
        ).minOrNull() ?: price * (1.0 + config.maxTp1Pct / 100.0)
        val tp1SebelumBatas = maxOf(tp1DariAtr, tp1DariR)
        val tp1 = minOf(tp1SebelumBatas, batasAtasTp1)
        val tp1TerikatBatas = batasAtasTp1 < tp1SebelumBatas

        // ── TP2 ─────────────────────────────────────────────────────────────
        var tp2 = maxOf(
            tp1 + intradayAtr * config.tp2AtrMult,
            price * (1.0 + config.minTp2Pct / 100.0)
        ).coerceAtMost(price * (1.0 + config.maxTp2Pct / 100.0))
        // Invarian wajib replay: TP2 selalu di atas TP1 (exit TP2 dipilih bila high ≥ TP2).
        tp2 = maxOf(tp2, tp1 * 1.005)

        // ── Gate dari TP1 final, tanpa clamp ────────────────────────────────
        // TANPA coerce ke 0: TP1 yang terpotong batas sampai DI BAWAH impas net memberi
        // netReward negatif → net R:R negatif → gate gagal untuk ambang >= 0. Pipihkan nilai
        // negatif ini ke 0 berarti ambang 0,0 ("minimal impas net") lolos pada TP1 yang
        // bahkan tidak menutup biaya — terukur memicu avg net −0,8…−1,0%/trade di 7 pair.
        val netRewardPct = ((tp1 / price) * sellNetFactor / buyCostFactor - 1.0) * 100.0
        val netRr = if (netRiskPct > 0.0) netRewardPct / netRiskPct else 0.0

        return IntradayGeometryResult(
            intradayAtrPct = atrPct,
            stopLoss = sl,
            takeProfit1 = tp1,
            takeProfit2 = tp2,
            netRrTp1 = netRr,
            netRiskPct = netRiskPct,
            netRewardPct = netRewardPct,
            impasNetPct = impasNetPct,
            tp1TerikatBatas = tp1TerikatBatas
        )
    }
}

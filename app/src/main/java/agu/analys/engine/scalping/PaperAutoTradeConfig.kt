package agu.analys.engine.scalping

import agu.analys.model.ScalpingStage

/**
 * Cara menentukan ukuran posisi simulasi saat auto-buy paper (P2).
 */
enum class PaperAutoBuyAllocation(val key: String, val label: String, val description: String) {
    FIXED_QUOTE(
        key = "fixed_quote",
        label = "Nominal tetap",
        description = "Setiap posisi memakai nominal Rupiah yang sama, dikonversi ke kuotasi pair"
    ),
    RISK_BASED(
        key = "risk_based",
        label = "Berbasis risiko",
        description = "Ukuran posisi dihitung dari jarak entry ke SL dan % risiko per trade"
    );

    companion object {
        fun fromKey(key: String?): PaperAutoBuyAllocation =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: FIXED_QUOTE
    }
}

/**
 * Hasil perhitungan ukuran posisi auto-buy paper.
 *
 * Sengaja bukan `Double` telanjang. Sebelumnya ukuran yang tidak layak **dipotong diam-diam**
 * ke saldo yang tersedia, sehingga pengguna tidak pernah tahu posisinya bukan yang ia minta —
 * dan ketika potongan itu masih tidak muat, kegagalannya hanya terlihat sebagai pesan saldo
 * dari order engine, bukan sebagai masalah setelan.
 */
sealed interface AutoBuySizing {
    /**
     * Posisi boleh dibuka.
     *
     * @param quantity kuantitas base asset.
     * @param notionalQuote nilai posisi **sebelum** fee & slippage (`quantity * price`).
     */
    data class Ok(val quantity: Double, val notionalQuote: Double) : AutoBuySizing

    /** Posisi tidak dibuka; [reason] dipakai apa adanya di log pengguna. */
    data class Skip(val reason: String) : AutoBuySizing
}

/**
 * Pengaturan auto-buy & auto-exit **paper trade** (P2).
 *
 * Ini murni simulasi: tidak pernah memicu order uang riil. Larangan di `AUDIT-2026-11-08.md`
 * dan `AGENTS.md` tetap berlaku — real auto-execute dari sinyal scalp tidak diaktifkan
 * sebelum gate edge terpenuhi.
 */
data class PaperAutoTradeConfig(
    /** Bila aktif, posisi simulasi dibuka otomatis saat sinyal scalp terkonfirmasi. */
    val autoBuyEnabled: Boolean = false,
    /** Bila aktif, exit otomatis dijalankan memakai [ScalpingRuntimeConfig.exit]. */
    val autoExitEnabled: Boolean = true,
    val allocation: PaperAutoBuyAllocation = PaperAutoBuyAllocation.FIXED_QUOTE,
    /**
     * Nominal per posisi dalam **Rupiah** (mode nominal tetap).
     *
     * Rupiah, **bukan** kas kuotasi pair. Untuk pair ber-kuotasi USDT nilainya dikonversi
     * memakai kurs USDT/IDR live di [plan]. Sebelumnya nilai ini dipakai apa adanya sebagai
     * kas kuotasi, sehingga Rp 100.000 menjadi **$100.000** pada pair USDT.
     */
    val fixedIdrAmount: Double = 100_000.0,
    /** Risiko per trade dalam % dari saldo kas kuotasi (mode berbasis risiko). */
    val riskPctPerTrade: Double = 1.0,
    /** Stage minimum sinyal yang boleh memicu auto-buy. */
    val minStage: ScalpingStage = ScalpingStage.ENTRY,
    /**
     * Batas jumlah posisi paper yang boleh terbuka bersamaan — **global**, lintas bursa dan
     * lintas mata uang kuotasi.
     *
     * `0` berarti tanpa batas. Hitungannya memakai **semua** posisi paper (termasuk yang dibeli
     * manual), karena tujuannya mencegah auto-buy menumpuk posisi sampai saldo kas habis —
     * kas yang sudah terpotong posisi yang masih hold tetap "terpakai".
     */
    val maxConcurrentPositions: Int = 3,
    /**
     * Jeda minimum (menit) sebelum auto-buy **paper** boleh membuka posisi baru lagi pada
     * pair yang sama setelah sell (2026-10-26). Sebelum perbaikan ini tidak ada jeda: begitu
     * exit terpicu, sinyal BUY berikut langsung memicu order beli ulang — pada pair yang
     * sedang trending naik itu berarti dua friksi round-trip dibayar beruntun hanya karena
     * masih ada sinyal (contoh terukur: WINUSDT 25/10/2026, entri ulang 60 detik setelah
     * sell). `0` menonaktifkan jeda. Angka tersimpan di config, bukan literal di runtime.
     */
    val reentryCooldownMinutes: Int = 15
) {
    /** Apakah stage sinyal memenuhi ambang minimum auto-buy. */
    fun stageQualifies(stage: ScalpingStage): Boolean = stage.ordinal >= minStage.ordinal

    /**
     * Putuskan apakah jeda entri-ulang sudah habis untuk sebuah pair.
     *
     * Delegasi ke [PaperReentryCooldown.verify] supaya logikanya satu sumber (murni teruji);
     * fungsi ini hanya menempelkan config pada gerbang auto-buy.
     */
    fun reentryAllowed(lastSoldAtMs: Long, nowMs: Long): Boolean =
        PaperReentryCooldown.verify(lastSoldAtMs, nowMs, reentryCooldownMinutes) is PaperReentryVerdict.Allow

    /** Sisa jeda entri-ulang dalam menit, untuk log yang bisa dibaca pengguna. */
    fun reentryWaitMinutesRemaining(lastSoldAtMs: Long, nowMs: Long): Long {
        val verdict = PaperReentryCooldown.verify(lastSoldAtMs, nowMs, reentryCooldownMinutes)
        return if (verdict is PaperReentryVerdict.Wait) {
            (verdict.remainingMs / 60_000L).coerceAtLeast(1L)
        } else {
            0L
        }
    }

    /**
     * Apakah batas posisi bersamaan sudah tercapai.
     *
     * @param currentCount jumlah posisi paper aktif saat ini
     *   (lihat `SpotPositionStore.countActivePaperPositions`).
     *
     * `maxConcurrentPositions <= 0` berarti tanpa batas, sehingga selalu `false`.
     */
    fun positionLimitReached(currentCount: Int): Boolean =
        maxConcurrentPositions > 0 && currentCount >= maxConcurrentPositions

    /**
     * Hitung ukuran posisi simulasi untuk satu sinyal auto-buy.
     *
     * ## Aturan
     *
     * 1. **Anggaran sadar biaya.** Order MARKET membayar slippage lalu fee di atas nilai posisi,
     *    jadi kas yang benar-benar terpotong = `notional * (1 + slippage) * (1 + fee)`.
     *    Perbandingan di sini memakai rumus yang **sama persis** dengan yang dipakai order engine
     *    (`SimulationTradeStore.placeOrder`), supaya tidak ada lagi kasus "biaya yang dicek
     *    berbeda dari biaya yang dipotong". Tanpa ini, setiap nominal yang ≥ saldo **selalu**
     *    ditolak, karena kebutuhan selalu melebihi saldo sebesar fee + slippage.
     * 2. **Nominal Rupiah.** Mode [PaperAutoBuyAllocation.FIXED_QUOTE] memakai [fixedIdrAmount]
     *    sebagai Rupiah; pada pair USDT nilainya dibagi [usdtIdrRate].
     * 3. **Tidak memotong diam-diam.** Bila nominal (setelah konversi) tidak muat, hasilnya
     *    [AutoBuySizing.Skip] dengan alasan yang bisa dibaca — bukan posisi yang diperkecil
     *    tanpa sepengetahuan pengguna.
     *
     * @param availableQuote saldo kas kuotasi yang tersedia.
     * @param price harga eksekusi.
     * @param stopLoss harga SL (dipakai mode berbasis risiko).
     * @param quoteIsUsdt `true` bila pair ber-kuotasi USDT/USDC (butuh konversi dari Rupiah).
     * @param usdtIdrRate kurs USDT/IDR live; `0` berarti belum tersedia.
     * @param feeRate tarif biaya beli sebagai **pecahan** (mis. `0.004044` = 0,4044%).
     * @param slippagePct asumsi slippage per sisi dalam **persen** (mis. `0.08`).
     */
    fun plan(
        availableQuote: Double,
        price: Double,
        stopLoss: Double,
        quoteIsUsdt: Boolean,
        usdtIdrRate: Double,
        feeRate: Double,
        slippagePct: Double
    ): AutoBuySizing {
        if (!price.isFinite() || price <= 0.0) {
            return AutoBuySizing.Skip("harga eksekusi belum valid")
        }
        if (!availableQuote.isFinite() || availableQuote <= 0.0) {
            return AutoBuySizing.Skip("saldo kas kuotasi kosong")
        }

        val slipFrac = if (slippagePct.isFinite() && slippagePct > 0.0) slippagePct / 100.0 else 0.0
        val feeFrac = if (feeRate.isFinite() && feeRate > 0.0) feeRate else 0.0
        val costMultiplier = (1.0 + slipFrac) * (1.0 + feeFrac)

        return when (allocation) {
            PaperAutoBuyAllocation.FIXED_QUOTE -> {
                val notional = if (quoteIsUsdt) {
                    if (!usdtIdrRate.isFinite() || usdtIdrRate <= 0.0) {
                        return AutoBuySizing.Skip(
                            "kurs USDT/IDR belum tersedia — nominal Rupiah tidak bisa dikonversi"
                        )
                    }
                    fixedIdrAmount / usdtIdrRate
                } else {
                    fixedIdrAmount
                }
                if (!notional.isFinite() || notional <= 0.0) {
                    return AutoBuySizing.Skip("nominal per posisi belum valid")
                }

                // Rumus identik dengan yang dipakai order engine saat memotong kas.
                val required = notional * costMultiplier
                if (required > availableQuote) {
                    return AutoBuySizing.Skip(
                        "nominal ${formatAmount(notional)} + fee & slippage butuh " +
                            "${formatAmount(required)}, saldo hanya ${formatAmount(availableQuote)}"
                    )
                }
                AutoBuySizing.Ok(quantity = notional / price, notionalQuote = notional)
            }

            PaperAutoBuyAllocation.RISK_BASED -> {
                val riskPerUnit = price - stopLoss
                if (!riskPerUnit.isFinite() || riskPerUnit <= 0.0) {
                    return AutoBuySizing.Skip("SL tidak valid — ukuran berbasis risiko tidak bisa dihitung")
                }
                val riskBudget = availableQuote * (riskPctPerTrade / 100.0)
                // Membatasi ke daya beli memang semantik mode ini, tetapi batasnya juga harus
                // sadar biaya. Epsilon relatif menjaga galat pembulatan float tidak membuat
                // order melewati saldo dan ditolak engine.
                val maxQty = (availableQuote / (price * costMultiplier)) * (1.0 - 1e-9)
                val qty = (riskBudget / riskPerUnit).coerceAtMost(maxQty)
                if (!qty.isFinite() || qty <= 0.0) {
                    return AutoBuySizing.Skip("kuantitas hasil hitungan risiko nol")
                }
                AutoBuySizing.Ok(quantity = qty, notionalQuote = qty * price)
            }
        }
    }

    companion object {
        val DEFAULT = PaperAutoTradeConfig()
    }
}

/**
 * Angka ringkas untuk pesan log.
 *
 * Nilai besar (mis. Rupiah) ditampilkan tanpa desimal, nilai kecil (mis. USDT) dengan 2 desimal.
 */
private fun formatAmount(v: Double): String =
    if (kotlin.math.abs(v) >= 1000.0) {
        String.format(java.util.Locale.US, "%,.0f", v)
    } else {
        String.format(java.util.Locale.US, "%.2f", v)
    }

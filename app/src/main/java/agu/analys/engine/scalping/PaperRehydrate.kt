package agu.analys.engine.scalping

/**
 * Keputusan rehidrasi untuk **satu** posisi paper setelah app restart.
 *
 * Lihat [PaperRehydrate.decide] untuk aturan yang dipakai.
 */
sealed interface PaperRehydrateAction {

    /** Bangun ulang state machine exit dari parameter yang tersimpan. */
    data class Restore(
        val machine: PaperExitStateMachine,
        /**
         * `true` bila progres FSM (peak, SL aktif, status BE/trailing/partial, jumlah bar) berhasil
         * dipulihkan dari disk. `false` berarti mesin dibangun dari nol — baik karena belum ada state
         * tersimpan (posisi lama), maupun karena state tersimpan ditolak validasi.
         */
        val progressRestored: Boolean = false,
        /** Alasan progres dibuang, bila ada. `null` bila progres dipulihkan atau memang belum ada. */
        val warning: String? = null
    ) : PaperRehydrateAction

    /**
     * Serahkan kembali kepemilikan exit ke jalur auto-sell lama
     * (`checkTrailingForMode` → TP1/TP2/SL dari `positionStore`).
     */
    data class HandBackToLegacy(val reason: String) : PaperRehydrateAction

    /** Lewati posisi ini — tidak ada yang bisa dipulihkan dengan aman. */
    data class Skip(val reason: String) : PaperRehydrateAction
}

/**
 * Aturan rehidrasi state machine exit paper (P2, diperluas P4.4).
 *
 * ## Kenapa ini ada
 *
 * [PaperAutoTradeRuntime] menyimpan state machine **hanya di memori**
 * (`ConcurrentHashMap`), sedangkan posisinya persisten di `SharedPreferences`
 * (`SpotPositionStore`). Saat engine P2 mengambil alih exit, jalur auto-sell lama
 * sengaja dimatikan (`auto_sell_enabled = false`, juga persisten). Jadi setelah app
 * di-kill:
 *
 * ```
 * machines (memori)     -> kosong        -> engine paper tidak jalan
 * auto_sell_enabled     -> false         -> jalur lama juga tidak jalan
 * posisi di prefs       -> masih HOLDING -> posisi MENGGANTUNG
 * ```
 *
 * ## Kenapa tidak cukup membaca `positionStore` saja
 *
 * `SpotPositionStore.setHolding` menulis SL sintetis `entry * 0.99` (≈1%) karena
 * `markBought` tidak menerima SL. `setAutoSellParams` versi lama juga tidak menulis SL.
 * Kalau rehidrasi memakai nilai itu apa adanya, mesin akan dibangun dengan SL 1%
 * padahal SL asli engine minimal sebesar lantai friksi (≥ 5,8% untuk USDT Tokocrypto)
 * — artinya mesin akan **memalsukan stop-out** dan mengotori jurnal.
 *
 * Karena itu [decide] menolak SL yang tidak masuk akal ([HandBackToLegacy]) daripada
 * memegang posisi dengan risiko yang salah.
 *
 * ## Progres FSM (P4.4)
 *
 * Sebelum P4.4, `barsHeld`, `isBeArmed`, `isTrailArmed`, `isPartialDone`, `partialR`, `peak`,
 * `activeStopLoss`, dan `atrPct` **reset** setiap restart: SL kembali ke titik awal, trailing yang
 * sudah terkunci lepas, ambang BE/trailing S8 jatuh ke nilai persen tetap, dan time-stop menghitung
 * dari nol. Sekarang progres itu dibaca dari [PaperExitFsmState] tersimpan dan diterapkan lewat
 * [PaperExitStateMachine.restore].
 *
 * State yang tidak lolos validasi **tidak** membuat posisi diserahkan ke jalur lama — mesin tetap
 * dibangun dengan `entry`/`stopLoss` asli yang benar, hanya progresnya yang dibuang
 * (`progressRestored = false` + `warning`). Itu lebih baik daripada memakai jalur lama yang
 * semantik SL/BE-nya berbeda, sehingga hasilnya tidak sebanding dengan replay.
 *
 * `atrPct` diterapkan saat **membangun** mesin, bukan lewat `restore`, karena ia parameter
 * konstruktor dan ambang turunannya dihitung sekali saat mesin lahir.
 */
object PaperRehydrate {

    const val REASON_NO_ENTRY = "Harga entry tidak valid"
    const val REASON_AUTO_EXIT_OFF = "Auto-exit paper dimatikan"
    const val REASON_INVALID_SL = "SL tersimpan tidak valid (harus 0 < SL < entry)"

    /**
     * Tentukan tindakan untuk satu posisi paper yang tersimpan.
     *
     * @param entry harga masuk posisi (dari `positionStore`)
     * @param stopLoss SL tersimpan; ditulis oleh `setAutoSellParams(stopLossPrice = ...)`
     * @param tp1 target profit 1 (0 = belum ada)
     * @param tp2 target profit 2 (0 = belum ada)
     * @param autoExitEnabled apakah auto-exit paper masih aktif di Pengaturan
     * @param buyFeePct biaya beli taker (%) untuk pair ini
     * @param sellFeePct biaya jual taker (%) untuk pair ini
     * @param slippagePct asumsi slippage (%) dari runtime config
     * @param params parameter exit aktif ([ScalpingRuntimeConfig.exit])
     * @param storedFsm progres FSM tersimpan; `null` bila posisi ini belum pernah menyimpannya
     * @param openedAt waktu buka posisi asli (ms epoch); dipakai menghitung `barsHeld` dari jam
     *   dinding. `0` berarti tidak diketahui — `barsHeld` lalu memakai nilai tersimpan apa adanya.
     * @param now waktu sekarang (ms epoch); disuntik agar bisa diuji.
     */
    fun decide(
        entry: Double,
        stopLoss: Double,
        tp1: Double,
        tp2: Double,
        autoExitEnabled: Boolean,
        buyFeePct: Double,
        sellFeePct: Double,
        slippagePct: Double,
        params: ScalpExitParams,
        storedFsm: PaperExitFsmState? = null,
        openedAt: Long = 0L,
        now: Long = System.currentTimeMillis()
    ): PaperRehydrateAction {
        if (entry <= 0.0 || !entry.isFinite()) {
            return PaperRehydrateAction.Skip(REASON_NO_ENTRY)
        }
        if (!autoExitEnabled) {
            return PaperRehydrateAction.HandBackToLegacy(REASON_AUTO_EXIT_OFF)
        }
        if (stopLoss <= 0.0 || !stopLoss.isFinite() || stopLoss >= entry) {
            return PaperRehydrateAction.HandBackToLegacy(REASON_INVALID_SL)
        }

        // `atrPct` diterapkan di sini, bukan lewat restore(): ambang BE/trailing S8 dihitung sekali
        // saat mesin lahir, jadi menundanya sampai setelah konstruksi tidak akan berpengaruh.
        val machine = PaperExitStateMachine(
            entry = entry,
            stopLoss = stopLoss,
            tp1 = if (tp1 > 0.0) tp1 else entry,
            tp2 = if (tp2 > 0.0) tp2 else entry * 1.02,
            buyFeePct = buyFeePct,
            sellFeePct = sellFeePct,
            slippagePct = slippagePct,
            params = params,
            atrPct = storedFsm?.atrPct ?: 0.0
        )

        if (storedFsm == null) {
            // Posisi lama (dibeli sebelum P4.4) tidak punya state tersimpan. Bukan kesalahan.
            return PaperRehydrateAction.Restore(machine)
        }

        val effective = storedFsm.copy(barsHeld = mergedBarsHeld(storedFsm.barsHeld, openedAt, now))
        val rejection = machine.restoreRejection(effective)
        if (rejection != null) {
            return PaperRehydrateAction.Restore(machine, progressRestored = false, warning = rejection)
        }
        machine.restore(effective)
        return PaperRehydrateAction.Restore(machine, progressRestored = true)
    }

    /**
     * Gabungkan jumlah bar tersimpan dengan waktu berjalan sejak posisi dibuka.
     *
     * Replay offline (`AUDIT-2026-11-08.md`) menghitung bar M1 **terus-menerus**, tanpa peduli app
     * hidup atau mati. Supaya statistik app sebanding, jam tahan ikut berjalan selama app tertutup.
     *
     * Konsekuensi yang disengaja: bila app mati lebih lama daripada `maxHoldBarsM1`, posisi langsung
     * kena time-stop pada evaluasi berikutnya. Ini benar — memang sebanyak itu bar M1 sudah lewat.
     *
     * @param stored nilai `barsHeld` terakhir yang sempat ditulis.
     * @param openedAt waktu buka posisi; `0` atau di masa depan berarti tidak diketahui, dan nilai
     *   tersimpan dipakai apa adanya supaya tidak menghasilkan jumlah bar raksasa.
     */
    fun mergedBarsHeld(stored: Int, openedAt: Long, now: Long): Int {
        if (openedAt <= 0L || openedAt > now) return maxOf(stored, 0)
        val elapsedBars = ((now - openedAt) / 60_000L).toInt()
        return maxOf(stored, elapsedBars, 0)
    }
}

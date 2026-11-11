package agu.analys.trading

import agu.analys.engine.scalping.PaperStopResolver
import agu.analys.engine.scalping.PaperStopSource

/** Asal SL hasil serah-terima posisi paper ke jalur auto-sell lama (P9). */
enum class PaperHandBackSource {
    /** SL aktif mesin sudah mengunci profit (≥ harga impas net). */
    PROFIT,

    /** SL aktif mesin di atas entry tetapi belum menutup biaya (BE). */
    BREAKEVEN,

    /** SL aktif mesin masih di bawah entry — dipakai apa adanya. */
    ACTIVE_STOP,

    /** SL tersimpan yang lolos validasi lantai friksi. */
    SIGNAL,

    /** SL tersimpan tidak valid — dipakai lantai friksi. */
    FRICTION_FLOOR,

    /** Tidak ada SL yang aman dipakai; auto-sell sengaja dibiarkan mati. */
    NONE
}

/** Hasil [PaperHandBackResolver.resolveStopLoss]. */
data class ResolvedHandBackStop(val price: Double, val source: PaperHandBackSource) {
    /** `true` bila harga ini boleh dipakai untuk menyalakan jalur auto-sell lama. */
    val usable: Boolean get() = source != PaperHandBackSource.NONE
}

/**
 * **P9** — pemilih SL saat mesin exit paper menyerahkan posisinya ke jalur auto-sell lama.
 *
 * ## Kenapa perlu resolver terpisah
 *
 * Rehidrasi setelah restart memakai [PaperStopResolver] apa adanya: SL harus `0 < SL < entry`,
 * sehingga SL mesin yang sudah naik ke BE/trailing **ditolak** dan jatuh ke lantai friksi.
 * Contoh: entry 100, SL awal 94, peak 101, trail 3% ⇒ `activeStopLoss` = 97,97. Serah-terima
 * naif akan melempar SL ke 94 — stop yang sudah dikunci justru **melebar mundur** dan posisi
 * kehilangan 3,97 poin proteksi.
 *
 * Aturan di sini:
 * - SL aktif mesin ≥ entry → **dipertahankan** (label [PaperHandBackSource.PROFIT] bila juga
 *   ≥ harga impas net, selain itu [PaperHandBackSource.BREAKEVEN]). Ini pengecualian sadar
 *   terhadap invarian lama `0 < SL < entry`: jalur lama menjual saat `currentPrice <=
 *   stopLossPrice`, jadi stop di atas entry aman dan justru menjual pada harga ≥ entry.
 * - SL aktif < entry dan SL tersimpan sama-sama kandidat → ambil yang **lebih tinggi**
 *   (lebih dekat entry): stop yang sudah dikunci tidak boleh melebar, dan yang lebih lebar
 *   tidak boleh menyempit tiba-tiba di detik serah-terima.
 * - Semua kandidat tidak valid → [PaperHandBackSource.NONE] — posisi tanpa stop lebih baik
 *   daripada penjualan pasar berlabel `STOP_LOSS` yang salah (P3).
 *
 * Fungsi ini murni — tanpa Android — supaya bisa diuji langsung.
 */
object PaperHandBackResolver {

    /**
     * @param entryPrice harga masuk posisi; tidak valid ⇒ [PaperHandBackSource.NONE]
     * @param storedSl SL tersimpan di [agu.analys.trading.SpotPositionStore]
     * @param activeSl SL aktif mesin (`PaperExitStateMachine.activeStopLoss`)
     * @param bePx harga impas net mesin (`PaperExitStateMachine.netBreakevenPrice`); hanya
     *   dipakai untuk memberi label profit vs breakeven
     * @param floorPct lantai friksi dari `PaperStopResolver.frictionFloorPct`; `0.0` = mati
     */
    fun resolveStopLoss(
        entryPrice: Double,
        storedSl: Double,
        activeSl: Double,
        bePx: Double,
        floorPct: Double
    ): ResolvedHandBackStop {
        if (!entryPrice.isFinite() || entryPrice <= 0.0) {
            return ResolvedHandBackStop(0.0, PaperHandBackSource.NONE)
        }

        // SL aktif sudah di atas entry (BE / profit terkunci) → pertahankan apa adanya.
        if (activeSl.isFinite() && activeSl > 0.0 && activeSl >= entryPrice) {
            val source = if (bePx.isFinite() && bePx > 0.0 && activeSl >= bePx) {
                PaperHandBackSource.PROFIT
            } else {
                PaperHandBackSource.BREAKEVEN
            }
            return ResolvedHandBackStop(activeSl, source)
        }

        val stored = PaperStopResolver.resolve(signalSl = storedSl, entry = entryPrice, floorPct = floorPct)
        val storedCandidate = if (stored.usable) stored.price else null
        val activeCandidate = if (activeSl.isFinite() && activeSl > 0.0 && activeSl < entryPrice) activeSl else null

        return when {
            storedCandidate == null && activeCandidate == null ->
                ResolvedHandBackStop(0.0, PaperHandBackSource.NONE)

            activeCandidate != null && (storedCandidate == null || activeCandidate > storedCandidate) ->
                finish(entryPrice, activeCandidate, PaperHandBackSource.ACTIVE_STOP)

            else -> {
                val source = when (stored.source) {
                    PaperStopSource.SIGNAL -> PaperHandBackSource.SIGNAL
                    PaperStopSource.FRICTION_FLOOR -> PaperHandBackSource.FRICTION_FLOOR
                    PaperStopSource.NONE -> PaperHandBackSource.NONE
                }
                finish(entryPrice, storedCandidate ?: 0.0, source)
            }
        }
    }

    /** Jaminan akhir: hanya `0 < price < entry` yang boleh menyalakan jalur lama. */
    private fun finish(entry: Double, price: Double, source: PaperHandBackSource): ResolvedHandBackStop =
        if (source != PaperHandBackSource.NONE && price.isFinite() && price > 0.0 && price < entry) {
            ResolvedHandBackStop(price, source)
        } else {
            ResolvedHandBackStop(0.0, PaperHandBackSource.NONE)
        }
}

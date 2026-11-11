package agu.analys.engine.scalping

/** Asal harga stop loss yang akhirnya dipakai mesin exit paper. */
enum class PaperStopSource {
    /** SL dari sinyal, dan sudah memenuhi lantai friksi. */
    SIGNAL,

    /** SL dihitung dari lantai friksi — SL sinyal tidak ada, atau terlalu rapat. */
    FRICTION_FLOOR,

    /** Tidak ada SL yang bisa dipakai dengan aman. */
    NONE
}

/**
 * Hasil [PaperStopResolver.resolve].
 *
 * @param price harga SL; `0.0` bila [source] = [PaperStopSource.NONE]
 * @param source dari mana harga itu berasal — dipakai untuk mencatat alasan di log
 */
data class ResolvedPaperStop(val price: Double, val source: PaperStopSource) {
    /** `true` bila harga ini boleh dipakai untuk memasang mesin exit. */
    val usable: Boolean get() = source != PaperStopSource.NONE
}

/**
 * Rencana SL untuk satu entry: lantai friksi yang berlaku + SL yang akhirnya dipakai.
 *
 * Dipisahkan dari [ResolvedPaperStop] supaya pemanggil tidak perlu menghitung lantai dua kali
 * dengan angka yang bisa berbeda. Jalur auto-buy memakai [stop] untuk **sizing**, lalu mesin exit
 * memakai [stop] yang sama; ini juga bentuk yang bisa dipakai jalur real trade nanti.
 */
data class PaperStopPlan(val floorPct: Double, val stop: ResolvedPaperStop)

/**
 * Penentu **satu-satunya** harga SL untuk posisi paper (P2, P3).
 *
 * ## Kenapa perlu resolver terpisah
 *
 * Aturan "SL tidak boleh lebih rapat dari lantai friksi" adalah invarian keras
 * ([ScalpingRuntimeConfig.minFrictionMultiple], ditegakkan `ScalpingRiskEngine` untuk sinyal
 * scalping). Tetapi jalur **posisi paper** tidak lewat `ScalpingRiskEngine`: ia memakai SL dari
 * sinyal apa adanya selama "valid" (`0 < SL < entry`). Akibatnya:
 *
 * - sinyal SWING/INTRADAY/OFFICE_DAILY — yang memasang [ScalpingStage.ENTRY] yang sama sehingga
 *   lolos gerbang stage — membuka posisi dengan SL 1,5–2,8%, sementara friksi round-trip
 *   ≈ 0,97% (Tokocrypto USDT). Satu SL ≈ −1,3R…−2R (P2).
 * - DCA mengubah `entry` menjadi rata-rata sementara SL lama dipertahankan, sehingga SL bisa
 *   berada **di atas** entry. Stop loss yang mustahil itu lalu memicu penjualan paksa berlabel
 *   `STOP_LOSS` di jalur lama (P3).
 *
 * Karena itu keputusannya dikumpulkan di sini sebagai fungsi **murni**: bisa diuji tanpa
 * ViewModel maupun Android, dan dipakai bersama oleh auto-buy, beli manual, re-arm DCA, dan
 * serah-terima ke jalur lama.
 */
object PaperStopResolver {

    /**
     * Jarak SL minimum (% dari entry) yang diwajibkan lantai friksi.
     *
     * `costPct = fee beli + fee jual + 2 × slippage` — slippage dihitung **dua sisi**, sama
     * seperti yang dipakai `ScalpingRiskEngine` dan `PaperExitResolver`.
     *
     * @return `0.0` bila lantai dinonaktifkan ([minFrictionMultiple] `<= 0`), bila komponen biaya
     *   semuanya nol, atau bila hasilnya ≥ 100% (SL harus ≤ 0 — mustahil, jadi lantai dianggap mati
     *   alih-alih menghasilkan harga negatif).
     */
    fun frictionFloorPct(
        buyFeePct: Double,
        sellFeePct: Double,
        slippagePct: Double,
        minFrictionMultiple: Double
    ): Double {
        if (!minFrictionMultiple.isFinite() || minFrictionMultiple <= 0.0) return 0.0
        val buy = if (buyFeePct.isFinite() && buyFeePct > 0.0) buyFeePct else 0.0
        val sell = if (sellFeePct.isFinite() && sellFeePct > 0.0) sellFeePct else 0.0
        val slip = if (slippagePct.isFinite() && slippagePct > 0.0) slippagePct else 0.0
        val costPct = buy + sell + 2.0 * slip
        if (costPct <= 0.0) return 0.0
        val floor = minFrictionMultiple * costPct
        return if (floor.isFinite() && floor > 0.0 && floor < 100.0) floor else 0.0
    }

    /**
     * Harga SL yang dipakai mesin exit.
     *
     * Aturannya **hanya melebarkan, tidak pernah menyempitkan**:
     * - lantai nonaktif → pakai SL sinyal bila valid; selain itu [PaperStopSource.NONE]
     * - lantai aktif dan SL sinyal **lebih lebar** (≤ harga lantai) → pakai SL sinyal
     * - lantai aktif dan SL sinyal tidak ada / lebih rapat → pakai harga lantai
     *
     * "Lebih lebar" berarti harga SL lebih **rendah**, jadi risiko per unit lebih besar.
     * Mengambil yang lebih rendah membuat posisi tidak pernah memakai stop yang lebih rapat
     * daripada yang diizinkan lantai friksi.
     *
     * @param signalSl SL dari sinyal; `0` atau nilai tidak valid berarti tidak ada
     * @param entry harga masuk posisi
     * @param floorPct hasil [frictionFloorPct]; `0.0` berarti lantai nonaktif
     */
    /**
     * Hitung lantai dan SL untuk satu entry dalam satu langkah.
     *
     * Ini satu-satunya titik sambung yang boleh dipakai jalur baru: sizing dan pemasangan mesin
     * exit wajib memakai [stop] yang sama, bukan menghitung `price - signalSl` sendiri.
     */
    fun resolveForEntry(
        signalSl: Double,
        entry: Double,
        buyFeePct: Double,
        sellFeePct: Double,
        slippagePct: Double,
        minFrictionMultiple: Double
    ): PaperStopPlan {
        val floorPct = frictionFloorPct(
            buyFeePct = buyFeePct,
            sellFeePct = sellFeePct,
            slippagePct = slippagePct,
            minFrictionMultiple = minFrictionMultiple
        )
        return PaperStopPlan(floorPct = floorPct, stop = resolve(signalSl, entry, floorPct))
    }

    fun resolve(signalSl: Double, entry: Double, floorPct: Double): ResolvedPaperStop {
        if (!entry.isFinite() || entry <= 0.0) return ResolvedPaperStop(0.0, PaperStopSource.NONE)

        val floorActive = floorPct.isFinite() && floorPct > 0.0 && floorPct < 100.0
        val signalValid = signalSl.isFinite() && signalSl > 0.0 && signalSl < entry

        if (!floorActive) {
            return if (signalValid) {
                ResolvedPaperStop(signalSl, PaperStopSource.SIGNAL)
            } else {
                ResolvedPaperStop(0.0, PaperStopSource.NONE)
            }
        }

        val floorPrice = entry * (1.0 - floorPct / 100.0)
        return if (signalValid && signalSl <= floorPrice) {
            ResolvedPaperStop(signalSl, PaperStopSource.SIGNAL)
        } else {
            ResolvedPaperStop(floorPrice, PaperStopSource.FRICTION_FLOOR)
        }
    }
}

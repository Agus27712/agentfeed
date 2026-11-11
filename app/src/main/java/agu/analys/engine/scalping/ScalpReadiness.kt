package agu.analys.engine.scalping

import agu.analys.model.AISignalState
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction

/**
 * Status kesiapan scalping yang **jujur** — satu sumber kebenaran untuk seluruh UI mode scalping.
 *
 * ## Kenapa model ini ada
 *
 * Sebelum ini, kesimpulan "sinyal terdeteksi" dihitung di tiga tempat dengan sumber berbeda,
 * dan ketiganya bisa saling bertentangan:
 *
 * ```
 * ScalpingMtfEvaluator.kt:246   early = !ready && !isDangerous && step2Ok
 *                               ^^^ step2Ok = step1Ok && isOrderBookValid → TIDAK memeriksa setup
 * ScalpingMtfEvaluator.kt:294   early -> ScalpingStage.EARLY_ENTRY
 * SignalLifecycleManager.kt:112 EARLY_ENTRY, WAIT_PULLBACK -> LifecycleState.DETECTED
 * WaitingEntryRadarCard.kt:317  DETECTED -> "TERDETEKSI"
 * ```
 *
 * Akibatnya radar menampilkan "TERDETEKSI" / "EARLY BUY" padahal `scalpingSetup == "NONE"`,
 * artinya **tidak ada pola trading yang valid**. Dua-duanya "benar" menurut sumbernya
 * masing-masing, tetapi kesimpulannya bertentangan — dan yang salah adalah kesimpulannya.
 *
 * [resolve] memutus kontradiksi itu dengan satu aturan yang tidak bisa dilanggar:
 *
 * > **Bila `scalpingSetup` kosong atau `"NONE"`, status tidak boleh READY atau CONFIRMING** —
 * > walaupun `lifecycleState == DETECTED` atau `scalpingStage == EARLY_ENTRY`.
 *
 * Model ini murni (tanpa dependensi Android) sehingga bisa diuji langsung.
 */
enum class ScalpReadinessState(val label: String) {
    /** Ada posisi simulasi terbuka untuk pair ini. */
    HOLDING("POSISI AKTIF"),

    /** Semua checkpoint lolos — siap eksekusi. */
    READY("SIAP ENTRY"),

    /** Setup valid, tinggal menunggu trigger VWAP/VSA. */
    CONFIRMING("KONFIRMASI"),

    /** Belum ada dasar untuk masuk. Termasuk saat orderbook sudah mendukung tapi setup belum ada. */
    WATCHING("MEMANTAU"),

    /** Diblokir kondisi pasar (volatilitas ekstrem / noise tinggi). */
    BLOCKED("TERTAHAN")
}

/**
 * Satu baris checklist scalping.
 *
 * @param index urutan 1..5
 * @param label judul singkat
 * @param ok sudah lolos?
 * @param detail penjelasan dari engine (bukan dihitung ulang di UI)
 */
data class ScalpCheckpoint(
    val index: Int,
    val label: String,
    val ok: Boolean,
    val detail: String
)

/**
 * Potret kesiapan scalping untuk satu sinyal.
 *
 * @param state status terpadu
 * @param headline kalimat kesimpulan singkat
 * @param blocker penghalang utama, `null` bila tidak ada
 * @param setupPresent apakah ada pola scalping valid (`scalpingSetup != NONE`)
 * @param setupLabel nama setup, atau teks "belum ada"
 * @param checkpoints 5 baris checklist berurutan
 * @param canAutoBuy apakah sinyal ini memenuhi syarat sisi-sinyal untuk auto-buy paper
 */
data class ScalpReadinessSnapshot(
    val state: ScalpReadinessState,
    val headline: String,
    val blocker: String?,
    val setupPresent: Boolean,
    val setupLabel: String,
    val checkpoints: List<ScalpCheckpoint>,
    val canAutoBuy: Boolean
)

object ScalpReadiness {

    const val SETUP_NONE = "NONE"

    const val BLOCKER_NO_SETUP = "Setup scalping belum terbentuk"
    const val BLOCKER_EXTREME_VOL = "Volatilitas ekstrem (noise tinggi)"

    private const val SETUP_MISSING_DETAIL =
        "Belum ada setup valid (liquidity sweep / breakout retest / breakout / trend pullback)."

    /**
     * Apakah sinyal membawa pola scalping yang valid.
     *
     * `ScalpingMtfSnapshot.setupOk` **tidak** dipakai di sini: di engine, field itu sebenarnya
     * diisi `step2Ok` (tekanan orderbook), bukan hasil deteksi pola. Yang benar-benar
     * mensyaratkan pola adalah `triggerOk` (= `step3Ok`).
     */
    fun hasSetup(signal: AISignalState): Boolean {
        val name = signal.scalpingSetup.trim()
        return name.isNotEmpty() && !name.equals(SETUP_NONE, ignoreCase = true)
    }

    /**
     * Tentukan status kesiapan dari satu sinyal.
     *
     * @param signal sinyal engine (sudah diproses `ScalpingMtfEvaluator`)
     * @param hasPaperPosition ada posisi simulasi terbuka untuk pair ini?
     * @param minStage ambang stage minimum untuk auto-buy paper (default sama dengan
     *   `PaperAutoTradeConfig.minStage`)
     */
    fun resolve(
        signal: AISignalState,
        hasPaperPosition: Boolean = false,
        minStage: ScalpingStage = ScalpingStage.ENTRY
    ): ScalpReadinessSnapshot {
        val mtf = signal.mtf
        val setupPresent = hasSetup(signal)
        val setupLabel = if (setupPresent) signal.scalpingSetup.trim() else "Belum ada setup"
        val isBuy = signal.action == SignalAction.BUY

        val (state, headline, blocker) = when {
            hasPaperPosition -> Triple(
                ScalpReadinessState.HOLDING,
                "Posisi simulasi terbuka — exit dipegang engine paper.",
                null
            )

            mtf.extremeVolatility -> Triple(
                ScalpReadinessState.BLOCKED,
                "Tertahan: pasar sedang terlalu liar untuk masuk.",
                BLOCKER_EXTREME_VOL
            )

            // Inti perbaikan: tanpa setup, tidak boleh ada klaim "terdeteksi / siap".
            !setupPresent -> Triple(
                ScalpReadinessState.WATCHING,
                "Memantau — pola scalping belum terbentuk.",
                BLOCKER_NO_SETUP
            )

            // READY = engine benar-benar akan membeli (`action == BUY` hanya di-set engine
            // ketika `step4Ok`, jadi syarat ini sekaligus penjaga terhadap input tak konsisten).
            isBuy && mtf.entryPriceOk -> Triple(
                ScalpReadinessState.READY,
                "Siap entry: semua checkpoint lolos.",
                null
            )

            // Setup + trigger valid, tetapi gate Net R:R / skor belum lolos. Ini keadaan nyata
            // saat `step3Ok` terpenuhi namun `step4Ok` belum (engine mengirim HOLD).
            mtf.triggerOk -> Triple(
                ScalpReadinessState.CONFIRMING,
                "Setup & trigger valid — menunggu Net R:R dan skor.",
                mtf.entryPriceDetail.ifBlank { "Menunggu gate Net R:R dan skor." }
            )

            else -> Triple(
                ScalpReadinessState.WATCHING,
                "Memantau: ${mtf.waitingFor.ifBlank { "menunggu konfirmasi lanjutan" }}.",
                mtf.waitingFor.ifBlank { "Menunggu konfirmasi lanjutan" }
            )
        }

        // Selaras dengan syarat `tryPaperAutoBuy()`: aksi BUY + stage memenuhi ambang.
        // Saldo kas dan kepemilikan posisi tidak bisa dipastikan dari sini, jadi tidak dihitung.
        val canAutoBuy = !hasPaperPosition &&
            setupPresent &&
            isBuy &&
            signal.scalpingStage.ordinal >= minStage.ordinal

        return ScalpReadinessSnapshot(
            state = state,
            headline = headline,
            blocker = blocker,
            setupPresent = setupPresent,
            setupLabel = setupLabel,
            checkpoints = checkpoints(signal),
            canAutoBuy = canAutoBuy
        )
    }

    /**
     * Lima checkpoint scalping dengan label yang jujur.
     *
     * Urutan ini **berbeda** dari `ScalpingMtfSnapshot.resolvedCheckpoints()` yang lama:
     * di sana baris "Area of Value" diisi `setupOk` (= tekanan orderbook), sehingga labelnya
     * menyesatkan. Di sini tekanan orderbook dipisah dari status pola.
     */
    fun checkpoints(signal: AISignalState): List<ScalpCheckpoint> {
        val mtf = signal.mtf
        val setupPresent = hasSetup(signal)
        return listOf(
            ScalpCheckpoint(
                index = 1,
                label = "Bias & Ruang Naik",
                ok = mtf.biasOk,
                detail = mtf.biasDetail.ifBlank { "Ruang gerak ke resistance M15/H1." }
            ),
            ScalpCheckpoint(
                index = 2,
                label = "Setup Scalping",
                ok = setupPresent,
                detail = if (setupPresent) {
                    "Pola ${signal.scalpingSetup.trim()} terdeteksi."
                } else {
                    SETUP_MISSING_DETAIL
                }
            ),
            ScalpCheckpoint(
                index = 3,
                label = "Tekanan Orderbook",
                ok = mtf.setupOk,
                detail = mtf.setupDetail.ifBlank { "Rasio bid/ask." }
            ),
            ScalpCheckpoint(
                index = 4,
                label = "Trigger VWAP / VSA",
                ok = mtf.triggerOk,
                detail = mtf.triggerDetail.ifBlank { "Konfirmasi harga menembus VWAP 1M." }
            ),
            ScalpCheckpoint(
                index = 5,
                label = "Net R:R & Skor",
                ok = mtf.entryPriceOk,
                detail = mtf.entryPriceDetail.ifBlank { "Net R:R minimum setelah fee." }
            )
        )
    }
}

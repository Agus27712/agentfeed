package agu.analys.engine.scalping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji aturan rehidrasi state machine exit paper (P2).
 *
 * Bug yang dijaga di sini: setelah app restart, posisi paper **menggantung** karena
 * `PaperAutoTradeRuntime` hanya hidup di memori sementara `auto_sell_enabled` sudah
 * dimatikan saat engine mengambil alih exit.
 *
 * Kasus terpenting bukan "berhasil memulihkan", melainkan **kapan menolak memulihkan**:
 * `SpotPositionStore.setHolding` menulis SL sintetis `entry * 0.99`, jadi memulihkan
 * tanpa validasi akan membangun mesin dengan SL 1% padahal SL engine minimal sebesar
 * lantai friksi. Itu akan memalsukan stop-out di jurnal.
 */
class PaperRehydrateTest {

    // Friksi USDT Tokocrypto yang dipakai di seluruh proyek.
    private val buyFee = 0.2422
    private val sellFee = 0.2422
    private val slippage = 0.2422

    private val params = ScalpExitParams(
        mode = ScalpExitMode.CLASSIC,
        minEarlyProfitNetPct = 0.45,
        breakevenTriggerNetPct = 0.40,
        trailPct = 0.35,
        maxHoldBarsM1 = 90
    )

    private fun decide(
        entry: Double = 100.0,
        stopLoss: Double = 94.0,
        tp1: Double = 104.0,
        tp2: Double = 108.0,
        autoExitEnabled: Boolean = true,
        storedFsm: PaperExitFsmState? = null,
        openedAt: Long = 0L,
        now: Long = 1_000_000_000_000L
    ) = PaperRehydrate.decide(
        entry = entry,
        stopLoss = stopLoss,
        tp1 = tp1,
        tp2 = tp2,
        autoExitEnabled = autoExitEnabled,
        buyFeePct = buyFee,
        sellFeePct = sellFee,
        slippagePct = slippage,
        params = params,
        storedFsm = storedFsm,
        openedAt = openedAt,
        now = now
    )

    /** State tersimpan yang valid untuk posisi 100/94 — dasar tiap uji progres. */
    private fun state(
        activeStopLoss: Double = 94.0,
        peak: Double = 100.0,
        isBeArmed: Boolean = false,
        isTrailArmed: Boolean = false,
        isPartialDone: Boolean = false,
        partialR: Double = 0.0,
        barsHeld: Int = 0,
        atrPct: Double = 0.0,
        entry: Double = 100.0,
        stopLoss: Double = 94.0
    ) = PaperExitFsmState(
        entry = entry,
        stopLoss = stopLoss,
        activeStopLoss = activeStopLoss,
        peak = peak,
        isBeArmed = isBeArmed,
        isTrailArmed = isTrailArmed,
        isPartialDone = isPartialDone,
        partialR = partialR,
        barsHeld = barsHeld,
        atrPct = atrPct
    )

    // ── Jalur pulih ─────────────────────────────────────────────────────────

    @Test
    fun `posisi valid dipulihkan dengan parameter yang tersimpan`() {
        val action = decide()
        assertTrue("harus Restore, dapat $action", action is PaperRehydrateAction.Restore)
        val m = (action as PaperRehydrateAction.Restore).machine
        assertEquals(100.0, m.entry, 1e-9)
        assertEquals(94.0, m.stopLoss, 1e-9)
        assertEquals(104.0, m.tp1, 1e-9)
        assertEquals(108.0, m.tp2, 1e-9)
        assertTrue("risiko harus valid", m.isRiskValid)
    }

    @Test
    fun `risiko mesin dipulihkan sama dengan jarak entry ke SL`() {
        val m = (decide() as PaperRehydrateAction.Restore).machine
        // (100 - 94) / 100 * 100 = 6.0%
        assertEquals(6.0, m.riskPctValue, 1e-9)
    }

    @Test
    fun `mesin dipulihkan mulai dari fase HOLDING tanpa progres`() {
        val m = (decide() as PaperRehydrateAction.Restore).machine
        assertEquals(PaperExitPhase.HOLDING, m.phase)
        assertEquals(0, m.barsHeld)
        assertEquals(false, m.isBeArmed)
        assertEquals(false, m.isPartialDone)
    }

    @Test
    fun `harga impas net berada di atas entry karena friksi`() {
        val m = (decide() as PaperRehydrateAction.Restore).machine
        assertTrue("BE net (${m.netBreakevenPrice}) harus > entry", m.netBreakevenPrice > m.entry)
    }

    @Test
    fun `tp1 nol jatuh ke entry dan tp2 nol jatuh ke entry kali 1_02`() {
        val m = (decide(tp1 = 0.0, tp2 = 0.0) as PaperRehydrateAction.Restore).machine
        assertEquals(100.0, m.tp1, 1e-9)
        assertEquals(102.0, m.tp2, 1e-9)
    }

    // ── Jalur tolak: ini inti perbaikannya ──────────────────────────────────

    @Test
    fun `auto-exit dimatikan diserahkan kembali ke jalur lama`() {
        val action = decide(autoExitEnabled = false)
        assertTrue("harus HandBackToLegacy, dapat $action", action is PaperRehydrateAction.HandBackToLegacy)
        assertEquals(PaperRehydrate.REASON_AUTO_EXIT_OFF, (action as PaperRehydrateAction.HandBackToLegacy).reason)
    }

    @Test
    fun `SL nol ditolak - tidak boleh membangun mesin tanpa risiko`() {
        val action = decide(stopLoss = 0.0)
        assertTrue("harus HandBackToLegacy, dapat $action", action is PaperRehydrateAction.HandBackToLegacy)
        assertEquals(PaperRehydrate.REASON_INVALID_SL, (action as PaperRehydrateAction.HandBackToLegacy).reason)
    }

    @Test
    fun `SL di atas entry ditolak - arah risiko terbalik`() {
        val action = decide(stopLoss = 101.0)
        assertTrue(action is PaperRehydrateAction.HandBackToLegacy)
        assertEquals(PaperRehydrate.REASON_INVALID_SL, (action as PaperRehydrateAction.HandBackToLegacy).reason)
    }

    @Test
    fun `SL sama dengan entry ditolak - risiko nol`() {
        val action = decide(stopLoss = 100.0)
        assertTrue(action is PaperRehydrateAction.HandBackToLegacy)
        assertEquals(PaperRehydrate.REASON_INVALID_SL, (action as PaperRehydrateAction.HandBackToLegacy).reason)
    }

    @Test
    fun `entry nol dilewati - tidak ada yang bisa dipulihkan`() {
        val action = decide(entry = 0.0)
        assertTrue("harus Skip, dapat $action", action is PaperRehydrateAction.Skip)
        assertEquals(PaperRehydrate.REASON_NO_ENTRY, (action as PaperRehydrateAction.Skip).reason)
    }

    @Test
    fun `entry negatif dilewati`() {
        assertTrue(decide(entry = -5.0) is PaperRehydrateAction.Skip)
    }

    @Test
    fun `entry NaN dilewati - bukan angka yang bisa dihitung`() {
        assertTrue(decide(entry = Double.NaN) is PaperRehydrateAction.Skip)
    }

    @Test
    fun `SL NaN ditolak`() {
        assertTrue(decide(stopLoss = Double.NaN) is PaperRehydrateAction.HandBackToLegacy)
    }

    @Test
    fun `SL Infinity ditolak`() {
        assertTrue(decide(stopLoss = Double.POSITIVE_INFINITY) is PaperRehydrateAction.HandBackToLegacy)
    }

    @Test
    fun `urutan prioritas - auto-exit mati menang atas SL tidak valid`() {
        // Kepemilikan exit ditentukan lebih dulu: kalau auto-exit mati, jalur lama yang
        // berhak, apa pun kondisi SL-nya.
        val action = decide(stopLoss = 0.0, autoExitEnabled = false)
        assertTrue(action is PaperRehydrateAction.HandBackToLegacy)
        assertEquals(PaperRehydrate.REASON_AUTO_EXIT_OFF, (action as PaperRehydrateAction.HandBackToLegacy).reason)
    }

    @Test
    fun `setiap tindakan membawa alasan yang bisa dibaca manusia`() {
        val handBack = decide(stopLoss = 0.0) as PaperRehydrateAction.HandBackToLegacy
        val skip = decide(entry = 0.0) as PaperRehydrateAction.Skip
        assertTrue(handBack.reason.isNotBlank())
        assertTrue(skip.reason.isNotBlank())
        assertNotNull(handBack.reason)
    }

    // ── Progres FSM (P4.4) ──────────────────────────────────────────────────
    //
    // Sebelum P4.4, restart membuat mesin lahir kembali dalam keadaan nol: SL kembali ke titik awal,
    // trailing yang sudah terkunci lepas, dan time-stop menghitung dari nol. Uji di bawah menjaga
    // agar progres benar-benar dipulihkan, dan agar state yang rusak tidak dipakai setengah-setengah.

    @Test
    fun `tanpa state tersimpan progres mulai nol dan itu bukan peringatan`() {
        val action = decide() as PaperRehydrateAction.Restore
        assertFalse("posisi lama memang belum punya state", action.progressRestored)
        assertNull("tidak ada yang salah, jadi tidak ada peringatan", action.warning)
        assertEquals(0, action.machine.barsHeld)
        assertEquals(PaperExitPhase.HOLDING, action.machine.phase)
    }

    @Test
    fun `state tersimpan yang valid dipulihkan penuh`() {
        val tersimpan = state(
            activeStopLoss = 101.146,
            peak = 103.0,
            isBeArmed = true,
            isTrailArmed = true,
            barsHeld = 7
        )
        val action = decide(storedFsm = tersimpan) as PaperRehydrateAction.Restore

        assertTrue("progres harus dipulihkan", action.progressRestored)
        assertNull(action.warning)
        assertEquals(101.146, action.machine.activeStopLoss, 1e-9)
        assertEquals(103.0, action.machine.peak, 1e-9)
        assertEquals(7, action.machine.barsHeld)
        assertEquals(PaperExitPhase.TRAILING, action.machine.phase)
    }

    @Test
    fun `state tersimpan yang rusak direset tapi mesin tetap dibangun dengan SL asli`() {
        // peak di bawah entry = mustahil.
        val rusak = state(peak = 99.0, activeStopLoss = 101.0, isBeArmed = true, barsHeld = 5)
        val action = decide(storedFsm = rusak) as PaperRehydrateAction.Restore

        assertFalse("progres harus dibuang", action.progressRestored)
        assertNotNull("alasan penolakan wajib dilaporkan", action.warning)
        assertTrue(action.warning!!.isNotBlank())
        // Yang penting: entry & SL asli tetap benar, bukan SL sintetis 1%.
        assertEquals(100.0, action.machine.entry, 1e-9)
        assertEquals(94.0, action.machine.stopLoss, 1e-9)
        assertEquals(94.0, action.machine.activeStopLoss, 1e-9)
        assertEquals(100.0, action.machine.peak, 1e-9)
        assertEquals(0, action.machine.barsHeld)
    }

    @Test
    fun `state milik posisi lain ditolak dan dilaporkan`() {
        val milikPosisiLain = state(entry = 250.0, stopLoss = 235.0)
        val action = decide(storedFsm = milikPosisiLain) as PaperRehydrateAction.Restore
        assertFalse(action.progressRestored)
        assertNotNull(action.warning)
    }

    @Test
    fun `barsHeld ikut berjalan selama app tertutup`() {
        // App mati 3 jam = 180 bar M1. Replay offline menghitung bar terus-menerus, jadi app harus sama.
        val now = 1_000_000_000_000L
        val tigaJam = 3L * 60L * 60L * 1000L
        val action = decide(
            storedFsm = state(barsHeld = 5),
            openedAt = now - tigaJam,
            now = now
        ) as PaperRehydrateAction.Restore

        assertTrue(action.progressRestored)
        assertEquals(180, action.machine.barsHeld)
    }

    @Test
    fun `nilai tersimpan yang lebih besar tetap dipakai`() {
        val now = 1_000_000_000_000L
        val action = decide(
            storedFsm = state(barsHeld = 500),
            openedAt = now - 60_000L,
            now = now
        ) as PaperRehydrateAction.Restore
        assertEquals("jam dinding tidak boleh menurunkan hitungan yang sudah ada", 500, action.machine.barsHeld)
    }

    @Test
    fun `openedAt tidak diketahui tidak memakai jam dinding`() {
        val action = decide(storedFsm = state(barsHeld = 12), openedAt = 0L) as PaperRehydrateAction.Restore
        assertEquals("tanpa openedAt, pakai nilai tersimpan apa adanya", 12, action.machine.barsHeld)
    }

    @Test
    fun `atrPct tersimpan dipakai untuk ambang BE dan trailing S8`() {
        val action = decide(storedFsm = state(atrPct = 3.31)) as PaperRehydrateAction.Restore
        // Default: beTriggerAtrFraction 0,5 dan trailAtrFraction 0,6 (ScalpExitParams).
        assertEquals(0.5 * 3.31, action.machine.effectiveBreakevenTriggerNetPct, 1e-9)
        assertEquals(0.6 * 3.31, action.machine.effectiveTrailDistancePct, 1e-9)
    }

    @Test
    fun `tanpa atrPct ambang jatuh ke nilai persen tetap`() {
        val action = decide(storedFsm = state(atrPct = 0.0)) as PaperRehydrateAction.Restore
        assertEquals(0.40, action.machine.effectiveBreakevenTriggerNetPct, 1e-9)
        assertEquals(0.35, action.machine.effectiveTrailDistancePct, 1e-9)
    }

    @Test
    fun `mergedBarsHeld menjaga masukan yang tidak masuk akal`() {
        assertEquals("openedAt di masa depan diabaikan", 7, PaperRehydrate.mergedBarsHeld(7, 2_000L, 1_000L))
        assertEquals("stored negatif dinaikkan ke nol", 0, PaperRehydrate.mergedBarsHeld(-3, 0L, 1_000L))
        assertEquals("elapsed negatif tidak mungkin turun di bawah nol", 0, PaperRehydrate.mergedBarsHeld(0, 0L, 1_000L))
    }
}

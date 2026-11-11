package agu.analys.engine.scalping

import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji persistensi progres [PaperExitStateMachine] (P4.4).
 *
 * Bug yang dijaga di sini: sebelum P4.4, `PaperAutoTradeRuntime` menyimpan mesin exit **hanya di
 * memori**, jadi setiap restart membuat mesin lahir kembali dalam keadaan nol — SL kembali ke titik
 * awal, trailing yang sudah terkunci lepas, dan time-stop menghitung dari nol. Uji paling penting
 * karena itu bukan "snapshot bisa dibaca", melainkan **[lanjutanSamaDenganMesinUtuh]**: mesin yang
 * dipulihkan harus berperilaku persis seperti mesin yang tidak pernah terputus.
 */
class PaperExitFsmStateTest {

    // Friksi Tokocrypto USDT taker + slippage proyek.
    private val buyFee = 0.4044
    private val sellFee = 0.4044
    private val slippage = 0.08

    private val entry = 100.0
    private val stopLoss = 94.0
    private val tp1 = 104.0
    private val tp2 = 108.0

    /**
     * ATR 3% + mode TRAIL membuat trailing ter-arm setelah harga naik ~2,5%, sehingga ada progres
     * nyata untuk diuji. Lantai profit (S2) dimatikan agar ambangnya tidak menutupi perilaku
     * trailing yang sedang diuji.
     */
    private val atrPct = 3.0
    private val params = ScalpExitParams(
        mode = ScalpExitMode.TRAIL,
        breakevenTriggerNetPct = 0.40,
        trailPct = 0.35,
        maxHoldBarsM1 = 0,
        minProfitFrictionMultiple = 0.0
    )

    private fun machine() = PaperExitStateMachine(
        entry = entry,
        stopLoss = stopLoss,
        tp1 = tp1,
        tp2 = tp2,
        buyFeePct = buyFee,
        sellFeePct = sellFee,
        slippagePct = slippage,
        params = params,
        atrPct = atrPct
    )

    private fun b(i: Long, o: Double, h: Double, l: Double, c: Double) =
        CandleBar(timestamp = i * 60_000L, open = o, high = h, low = l, close = c, volume = 100.0)

    /** Deret naik yang meng-arm BE lalu trailing (peak 103, SL aktif ≈ 101,1). */
    private fun barsMenaikkan(): List<CandleBar> = listOf(
        b(1L, 100.0, 101.0, 100.0, 100.8),
        b(2L, 100.8, 102.0, 100.5, 101.5),
        b(3L, 101.5, 103.0, 101.0, 102.5)
    )

    /** Bar yang low-nya jatuh **di bawah trailing SL** tetapi **di atas SL awal**. */
    private fun barSapuTrailing(): CandleBar = b(4L, 102.5, 103.0, 100.0, 100.5)

    /** State valid sebagai titik awal; tiap uji negatif mengubah satu field saja. */
    private fun state(
        entry: Double = this.entry,
        stopLoss: Double = this.stopLoss,
        activeStopLoss: Double = this.stopLoss,
        peak: Double = this.entry,
        isBeArmed: Boolean = false,
        isTrailArmed: Boolean = false,
        isPartialDone: Boolean = false,
        partialR: Double = 0.0,
        barsHeld: Int = 0,
        atrPct: Double = this.atrPct
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

    // ── Snapshot & restore ──────────────────────────────────────────────────

    @Test
    fun `snapshot menangkap progres setelah trailing ter-arm`() {
        val m = machine()
        for (bar in barsMenaikkan()) m.onBar(bar, emptyList())

        val snap = m.snapshot()
        assertEquals(entry, snap.entry, 1e-9)
        assertEquals(stopLoss, snap.stopLoss, 1e-9)
        assertEquals(103.0, snap.peak, 1e-9)
        assertEquals(3, snap.barsHeld)
        assertTrue("BE harus ter-arm", snap.isBeArmed)
        assertTrue("trailing harus ter-arm", snap.isTrailArmed)
        assertFalse("belum ada partial", snap.isPartialDone)
        assertEquals(0.0, snap.partialR, 1e-9)
        assertEquals(atrPct, snap.atrPct, 1e-9)
        assertTrue(
            "SL aktif harus sudah naik di atas SL awal, dapat ${snap.activeStopLoss}",
            snap.activeStopLoss > stopLoss
        )
        assertEquals("SL aktif snapshot harus sama dengan mesin", m.activeStopLoss, snap.activeStopLoss, 1e-9)
    }

    @Test
    fun `restore pada mesin baru menghasilkan state identik`() {
        val a = machine()
        for (bar in barsMenaikkan()) a.onBar(bar, emptyList())
        val snap = a.snapshot()

        val fresh = machine()
        assertEquals("progres awal harus nol", PaperExitPhase.HOLDING, fresh.phase)
        assertTrue("restore harus diterima", fresh.restore(snap))

        assertEquals(a.activeStopLoss, fresh.activeStopLoss, 1e-9)
        assertEquals(a.peak, fresh.peak, 1e-9)
        assertEquals(a.isBeArmed, fresh.isBeArmed)
        assertEquals(a.isTrailArmed, fresh.isTrailArmed)
        assertEquals(a.isPartialDone, fresh.isPartialDone)
        assertEquals(a.partialR, fresh.partialR, 1e-9)
        assertEquals(a.barsHeld, fresh.barsHeld)
        assertEquals("fase harus ikut pulih", a.phase, fresh.phase)
    }

    /**
     * Uji terkuat: mesin yang dipulihkan harus mengambil keputusan yang sama dengan mesin yang
     * tidak pernah terputus, dan **berbeda** dari mesin yang kehilangan progres.
     */
    @Test
    fun `mesin yang direstore berlanjut sama dengan mesin yang tidak terputus`() {
        val utuh = machine()
        for (bar in barsMenaikkan()) utuh.onBar(bar, emptyList())

        val dipulihkan = machine()
        assertTrue(dipulihkan.restore(utuh.snapshot()))

        // Mesin yang kehilangan progres: SL masih di titik awal, jadi tidak tersapu.
        val kehilanganProgres = machine()

        val sapu = barSapuTrailing()
        val dUtuh = utuh.onBar(sapu, emptyList())
        val dPulih = dipulihkan.onBar(sapu, emptyList())
        val dHilang = kehilanganProgres.onBar(sapu, emptyList())

        assertNotNull("mesin utuh harus keluar", dUtuh)
        assertEquals(PaperExitReason.TRAIL_SL, dUtuh!!.reason)
        assertEquals("mesin dipulihkan harus keluar sama", dUtuh.reason, dPulih!!.reason)
        assertEquals(dUtuh.r, dPulih.r, 1e-9)
        assertEquals(dUtuh.exitPrice, dPulih.exitPrice, 1e-9)
        assertNull(
            "tanpa progres, bar ini TIDAK boleh menutup posisi — inilah bug yang ditutup P4.4",
            dHilang
        )
    }

    @Test
    fun `restore menolak state milik posisi lain`() {
        val m = machine()
        assertFalse("entry berbeda harus ditolak", m.restore(state(entry = 200.0)))
        assertFalse("SL berbeda harus ditolak", m.restore(state(stopLoss = 90.0)))
        assertEquals("mesin tidak boleh berubah", stopLoss, m.activeStopLoss, 1e-9)
    }

    // ── Kasus negatif: tiap invarian punya ujinya sendiri ───────────────────

    /** Setelah penolakan, mesin wajib tetap pada kondisi awal — bukan kondisi setengah jadi. */
    private fun assertTetapSegar(m: PaperExitStateMachine) {
        assertEquals(stopLoss, m.activeStopLoss, 1e-9)
        assertEquals(entry, m.peak, 1e-9)
        assertFalse(m.isBeArmed)
        assertFalse(m.isTrailArmed)
        assertFalse(m.isPartialDone)
        assertEquals(0.0, m.partialR, 1e-9)
        assertEquals(0, m.barsHeld)
        assertEquals(PaperExitPhase.HOLDING, m.phase)
    }

    @Test
    fun `peak di bawah entry ditolak`() {
        val m = machine()
        assertFalse(m.restore(state(peak = 99.0)))
        assertTetapSegar(m)
    }

    @Test
    fun `SL aktif lebih longgar dari SL awal ditolak`() {
        val m = machine()
        assertFalse(m.restore(state(activeStopLoss = 93.0)))
        assertTetapSegar(m)
    }

    @Test
    fun `angka tidak valid ditolak`() {
        val m = machine()
        assertFalse(m.restore(state(activeStopLoss = Double.NaN)))
        assertTetapSegar(m)
        assertFalse(m.restore(state(peak = Double.POSITIVE_INFINITY)))
        assertTetapSegar(m)
        assertFalse(m.restore(state(partialR = Double.NaN)))
        assertTetapSegar(m)
    }

    @Test
    fun `jumlah bar negatif ditolak`() {
        val m = machine()
        assertFalse(m.restore(state(barsHeld = -1)))
        assertTetapSegar(m)
    }

    @Test
    fun `partialR terisi tanpa penanda partial ditolak`() {
        val m = machine()
        assertFalse(m.restore(state(partialR = 0.5, isPartialDone = false)))
        assertTetapSegar(m)
    }

    @Test
    fun `trailing tanpa breakeven ditolak`() {
        val m = machine()
        assertFalse(m.restore(state(isTrailArmed = true, isBeArmed = false)))
        assertTetapSegar(m)
    }

    @Test
    fun `partial tanpa breakeven ditolak`() {
        val m = machine()
        assertFalse(m.restore(state(isPartialDone = true, isBeArmed = false, partialR = 0.5)))
        assertTetapSegar(m)
    }

    @Test
    fun `setiap penolakan membawa alasan yang bisa dibaca manusia`() {
        val m = machine()
        val alasan = listOf(
            state(entry = 200.0),
            state(activeStopLoss = Double.NaN),
            state(peak = 99.0),
            state(activeStopLoss = 93.0),
            state(barsHeld = -1),
            state(partialR = 0.5),
            state(isTrailArmed = true),
            state(isPartialDone = true, partialR = 0.5)
        ).map { m.restoreRejection(it) }
        assertTrue("semua harus ditolak", alasan.all { it != null })
        assertTrue("semua alasan harus terisi", alasan.all { !it.isNullOrBlank() })
    }

    @Test
    fun `state yang sah tidak ditolak`() {
        val m = machine()
        assertNull(m.restoreRejection(state()))
        assertNull(
            m.restoreRejection(
                state(activeStopLoss = 101.1, peak = 103.0, isBeArmed = true, isTrailArmed = true, barsHeld = 3)
            )
        )
    }

    // ── Serialisasi JSON ────────────────────────────────────────────────────

    @Test
    fun `JSON bolak-balik tanpa kehilangan nilai`() {
        val asli = state(
            activeStopLoss = 101.146,
            peak = 103.0,
            isBeArmed = true,
            isTrailArmed = true,
            barsHeld = 7,
            atrPct = 3.31
        )
        val hasil = PaperExitFsmState.fromJson(asli.toJsonString())
        assertEquals(asli, hasil)
    }

    @Test
    fun `JSON kosong atau rusak menghasilkan null`() {
        assertNull(PaperExitFsmState.fromJson(null))
        assertNull(PaperExitFsmState.fromJson(""))
        assertNull(PaperExitFsmState.fromJson("   "))
        assertNull(PaperExitFsmState.fromJson("bukan json"))
        assertNull("field inti hilang harus ditolak", PaperExitFsmState.fromJson("{\"v\":1}"))
    }

    @Test
    fun `versi skema yang tidak dikenal ditolak`() {
        assertNull(PaperExitFsmState.fromJson(jsonLengkap(v = 99)))
    }

    @Test
    fun `atrPct tidak masuk akal disanitasi ke nol tanpa membuang state`() {
        val hasil = PaperExitFsmState.fromJson(jsonLengkap(atrPct = "-5.0"))
        assertNotNull("state tetap harus terbaca", hasil)
        val h = hasil!!
        assertEquals(0.0, h.atrPct, 1e-9)
        assertEquals("field lain tidak boleh ikut hilang", 103.0, h.peak, 1e-9)
        assertTrue("flag proteksi tidak boleh ikut hilang", h.isTrailArmed)
    }

    @Test
    fun `atrPct bukan angka diperlakukan sebagai tidak diketahui`() {
        val hasil = PaperExitFsmState.fromJson(jsonLengkap(atrPct = "\"abc\""))
        assertNotNull(hasil)
        assertEquals(0.0, hasil!!.atrPct, 1e-9)
    }

    /**
     * JSON ditulis sebagai literal, bukan lewat `toJsonString()` lalu di-`replace`, karena
     * `JSONObject` menulis `3.0` sebagai `3` sehingga penggantian string akan diam-diam gagal.
     */
    private fun jsonLengkap(v: Int = 1, atrPct: String = "3.31"): String =
        "{\"v\":$v,\"entry\":100.0,\"sl\":94.0,\"active_sl\":101.146,\"peak\":103.0," +
            "\"be\":true,\"trail\":true,\"partial_done\":false,\"partial_r\":0.0," +
            "\"bars_held\":7,\"atr_pct\":$atrPct}"
}

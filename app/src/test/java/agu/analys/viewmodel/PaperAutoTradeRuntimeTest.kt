package agu.analys.viewmodel

import agu.analys.engine.scalping.PaperExitFsmState
import agu.analys.engine.scalping.PaperExitStateMachine
import agu.analys.engine.scalping.ScalpExitMode
import agu.analys.engine.scalping.ScalpExitParams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes registri runtime paper trade (P2).
 *
 * Yang dijaga di sini:
 * - key registri menormalkan simbol, tetapi simbol asli harus tetap bisa dipulihkan
 *   (dipakai untuk memantau harga posisi yang tidak sedang dibuka di layar);
 * - hitungan bar M1 hanya maju sekali per menit, sehingga evaluasi per-tick tidak
 *   mempercepat time-stop.
 */
class PaperAutoTradeRuntimeTest {

    @After
    fun tearDown() {
        PaperAutoTradeRuntime.clearAll()
    }

    private fun machine() = PaperExitStateMachine(
        entry = 100.0,
        stopLoss = 99.0,
        tp1 = 101.0,
        tp2 = 102.0,
        buyFeePct = 0.4044,
        sellFeePct = 0.4044,
        slippagePct = 0.08,
        params = ScalpExitParams(mode = ScalpExitMode.CLASSIC)
    )

    @Test
    fun `simbol asli tetap utuh walau key dinormalkan`() {
        PaperAutoTradeRuntime.put("tokocrypto", "BTC_IDR", machine())

        assertEquals(listOf("BTC_IDR"), PaperAutoTradeRuntime.activeSymbols("TOKOCRYPTO"))
        assertTrue(PaperAutoTradeRuntime.hasActivePosition("TOKOCRYPTO", "btc_idr"))
        assertNotNull(PaperAutoTradeRuntime.machineFor("TOKOCRYPTO", "BTC_IDR"))
        assertTrue(PaperAutoTradeRuntime.openedAt("TOKOCRYPTO", "BTC_IDR") > 0L)

        PaperAutoTradeRuntime.clear("TOKOCRYPTO", "BTC_IDR")
        assertTrue(PaperAutoTradeRuntime.activeSymbols("TOKOCRYPTO").isEmpty())
        assertNull(PaperAutoTradeRuntime.machineFor("TOKOCRYPTO", "BTC_IDR"))
    }

    /**
     * Kepemilikan exit tunggal (P2): selama engine paper aktif, jalur auto-sell lama harus
     * mundur supaya tidak ada dua mesin yang menjual posisi simulasi yang sama.
     */
    @Test
    fun `engine paper memegang exit hanya saat auto-exit aktif dan posisinya ada`() {
        PaperAutoTradeRuntime.put("TOKOCRYPTO", "BTC_IDR", machine())

        assertTrue(
            "auto-exit aktif + posisi paper terbuka -> jalur lama mundur",
            PaperAutoTradeRuntime.ownsExit("TOKOCRYPTO", "btc_idr", autoExitEnabled = true)
        )
        assertFalse(
            "auto-exit dimatikan -> kepemilikan kembali ke jalur lama, posisi tidak boleh menggantung",
            PaperAutoTradeRuntime.ownsExit("TOKOCRYPTO", "btc_idr", autoExitEnabled = false)
        )
        assertFalse(
            "pair lain tanpa posisi paper tidak boleh ikut terkunci",
            PaperAutoTradeRuntime.ownsExit("TOKOCRYPTO", "ETH_IDR", autoExitEnabled = true)
        )
    }

    @Test
    fun `kepemilikan exit dilepas setelah posisi paper ditutup`() {
        PaperAutoTradeRuntime.put("TOKOCRYPTO", "BTC_IDR", machine())
        assertTrue(PaperAutoTradeRuntime.ownsExit("TOKOCRYPTO", "BTC_IDR", autoExitEnabled = true))

        PaperAutoTradeRuntime.clear("TOKOCRYPTO", "BTC_IDR")

        assertFalse(
            "setelah mesin dilepas, jalur auto-sell lama harus bisa jalan lagi",
            PaperAutoTradeRuntime.ownsExit("TOKOCRYPTO", "BTC_IDR", autoExitEnabled = true)
        )
    }

    @Test
    fun `hanya tick pertama di menit baru yang dihitung sebagai bar M1`() {
        val minuteStart = (System.currentTimeMillis() / 60_000L) * 60_000L
        PaperAutoTradeRuntime.put("TOKOCRYPTO", "BTC_IDR", machine())

        assertFalse(
            "tick di menit yang sama dengan saat posisi dibuka bukan bar baru",
            PaperAutoTradeRuntime.markBarBucket("TOKOCRYPTO", "BTC_IDR", minuteStart).isNewBar
        )
        assertFalse(
            "tick 30 detik kemudian masih di menit yang sama",
            PaperAutoTradeRuntime.markBarBucket("TOKOCRYPTO", "BTC_IDR", minuteStart + 30_000L).isNewBar
        )
        assertTrue(
            "menit berikutnya adalah bar M1 baru",
            PaperAutoTradeRuntime.markBarBucket("TOKOCRYPTO", "BTC_IDR", minuteStart + 60_000L).isNewBar
        )
        assertFalse(
            "kembali ke menit yang sama tidak dihitung bar baru lagi",
            PaperAutoTradeRuntime.markBarBucket("TOKOCRYPTO", "BTC_IDR", minuteStart + 90_000L).isNewBar
        )
        assertFalse(
            "arm biasa bukan rehidrasi, jadi tidak ada tick pertama yang perlu dikecualikan",
            PaperAutoTradeRuntime.markBarBucket("TOKOCRYPTO", "BTC_IDR", minuteStart + 120_000L)
                .firstTickAfterRehydrate
        )
    }

    // ── Persistensi progres FSM (P4.4) ──────────────────────────────────────

    private fun fsm(
        activeStopLoss: Double = 99.0,
        peak: Double = 100.0,
        isBeArmed: Boolean = false,
        isTrailArmed: Boolean = false,
        isPartialDone: Boolean = false,
        barsHeld: Int = 0
    ) = PaperExitFsmState(
        entry = 100.0,
        stopLoss = 99.0,
        activeStopLoss = activeStopLoss,
        peak = peak,
        isBeArmed = isBeArmed,
        isTrailArmed = isTrailArmed,
        isPartialDone = isPartialDone,
        partialR = 0.0,
        barsHeld = barsHeld,
        atrPct = 3.0
    )

    /**
     * Tick pertama setelah rehidrasi harus dihitung sebagai bar M1 baru.
     *
     * `put` menandai menit berjalan sebagai "sudah dihitung", jadi tanpa penanda ini time-stop baru
     * dievaluasi sampai batas menit berikutnya — padahal jam tahan sudah lewat selama app tertutup.
     */
    @Test
    fun `tick pertama setelah rehidrasi dihitung sebagai bar M1 baru`() {
        val now = (System.currentTimeMillis() / 60_000L) * 60_000L
        PaperAutoTradeRuntime.put("TOKOCRYPTO", "BTC_IDR", machine(), markFirstTickAsNewBar = true)
        val mark = PaperAutoTradeRuntime.markBarBucket("TOKOCRYPTO", "BTC_IDR", now)
        assertTrue(
            "rehidrasi harus langsung bisa memicu time-stop",
            mark.isNewBar
        )
        assertTrue(
            "P8: tick ini juga harus dikenali sebagai tick pertama sesudah rehidrasi",
            mark.firstTickAfterRehydrate
        )
    }

    @Test
    fun `tick pertama pada arm biasa tetap bukan bar baru`() {
        val now = (System.currentTimeMillis() / 60_000L) * 60_000L
        PaperAutoTradeRuntime.put("TOKOCRYPTO", "BTC_IDR", machine())
        val mark = PaperAutoTradeRuntime.markBarBucket("TOKOCRYPTO", "BTC_IDR", now)
        assertFalse(mark.isNewBar)
        assertFalse(
            "tanpa sentinel rehidrasi, tidak ada tick pertama yang perlu dikecualikan",
            mark.firstTickAfterRehydrate
        )
    }

    @Test
    fun `penulisan pertama selalu dilakukan walau tanpa force`() {
        assertTrue(PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "BTC_IDR", fsm(), now = 1_000L))
    }

    @Test
    fun `pergeseran peak saja ditahan oleh throttle`() {
        val awal = fsm(peak = 100.0)
        assertTrue(PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "BTC_IDR", awal, now = 1_000L))

        val peakNaik = awal.copy(peak = 100.5)
        assertFalse(
            "peak bergerak tiap tick, jadi tidak boleh memicu penulisan tiap kali",
            PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "BTC_IDR", peakNaik, now = 1_500L)
        )
        assertTrue(
            "setelah jeda throttle, peak baru ikut tersimpan",
            PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "BTC_IDR", peakNaik, now = 3_000L)
        )
    }

    @Test
    fun `perubahan yang mengubah perilaku exit ditulis segera`() {
        val dasar = fsm()
        PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "BTC_IDR", dasar, now = 1_000L)

        assertTrue(
            "BE ter-arm harus langsung tersimpan",
            PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "BTC_IDR", dasar.copy(isBeArmed = true), now = 1_100L)
        )
        assertTrue(
            "trailing ter-arm harus langsung tersimpan",
            PaperAutoTradeRuntime.shouldWriteFsm(
                "TOKOCRYPTO", "BTC_IDR", dasar.copy(isBeArmed = true, isTrailArmed = true), now = 1_200L
            )
        )
        assertTrue(
            "SL naik harus langsung tersimpan",
            PaperAutoTradeRuntime.shouldWriteFsm(
                "TOKOCRYPTO", "BTC_IDR", dasar.copy(isBeArmed = true, activeStopLoss = 101.0), now = 1_300L
            )
        )
        assertTrue(
            "jumlah bar berubah harus langsung tersimpan",
            PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "BTC_IDR", dasar.copy(barsHeld = 3), now = 1_400L)
        )
    }

    @Test
    fun `force menembus throttle`() {
        val state = fsm()
        PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "BTC_IDR", state, now = 1_000L)
        assertTrue(PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "BTC_IDR", state, now = 1_100L, force = true))
    }

    @Test
    fun `seedPersistedFsm mencegah penulisan ulang yang tidak perlu`() {
        val state = fsm()
        val now = System.currentTimeMillis()
        PaperAutoTradeRuntime.seedPersistedFsm("TOKOCRYPTO", "BTC_IDR", state)
        assertFalse(
            "state sudah sinkron dengan disk setelah rehidrasi",
            PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "BTC_IDR", state, now = now)
        )
    }

    @Test
    fun `clear melepas jejak penulisan sehingga posisi berikutnya ditulis lagi`() {
        val state = fsm()
        PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "BTC_IDR", state, now = 1_000L)
        PaperAutoTradeRuntime.clear("TOKOCRYPTO", "BTC_IDR")
        assertTrue(
            "tanpa pembersihan ini, throttle posisi lama bisa menahan penulisan posisi baru",
            PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "BTC_IDR", state, now = 1_100L)
        )
    }

    @Test
    fun `clearAll membersihkan jejak penulisan semua posisi`() {
        PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "BTC_IDR", fsm(), now = 1_000L)
        PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "ETH_IDR", fsm(), now = 1_000L)

        PaperAutoTradeRuntime.clearAll()

        assertTrue(PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "BTC_IDR", fsm(), now = 1_100L))
        assertTrue(PaperAutoTradeRuntime.shouldWriteFsm("TOKOCRYPTO", "ETH_IDR", fsm(), now = 1_100L))
    }
}

package agu.analys.engine

import agu.analys.config.FeeSchedule
import agu.analys.engine.scalping.PaperExitResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji **paritas rumus P&L** setelah penyeragaman ke basis FSM (2026-10-15).
 *
 * Sebelumnya ada empat rumus berbeda untuk hal yang sama. Untuk Tokocrypto IDR, friksi yang
 * dipotong masing-masing adalah:
 *
 * | Tempat | Friksi |
 * |---|---|
 * | halaman portofolio (`PortfolioScreen`, `RealPortfolioView`, `ActiveHoldingSection`) | **0%** — bruto |
 * | radar jual (`SellSignalEvaluator`) & kartu posisi (`PositionContext`) | 0,3322% (fee jual maker saja) |
 * | FSM paper (`PaperExitResolver`) | 0,8119% (taker + slippage dua sisi) |
 *
 * Akibatnya koin yang sama bisa tampil berbeda ~0,81 poin persen antar halaman, dan pengguna
 * membacanya sebagai bug. Basis FSM dipilih karena paling jujur: auto-buy dan auto-exit adalah
 * order **market**, jadi taker + slippage memang benar-benar dibayar.
 *
 * Uji ini menjaga agar rumus itu tidak diam-diam menyimpang lagi.
 */
class NetPnlParityTest {

    private val tokocryptoIdr = "TOKOCRYPTO"
    private val idr = "IDR"

    /** Fee taker Tokocrypto IDR + slippage default: kerugian bila beli lalu jual di harga sama. */
    private val kerugianDiHargaSama = -0.811946

    /** Kenaikan harga yang dibutuhkan agar impas net. */
    private val kenaikanImpasPct = 0.818593

    @Test
    fun `beli lalu jual di harga sama rugi sebesar friksi taker plus slippage`() {
        val entry = 1_000_000.0
        val hasil = FeeSchedule.netProfitPctFor(
            entry = entry,
            price = entry,
            exchange = tokocryptoIdr,
            quoteAsset = idr
        )
        assertEquals(kerugianDiHargaSama, hasil, 1e-4)
    }

    @Test
    fun `harga impas membuat profit net tepat nol`() {
        val entry = 1_000_000.0
        val be = FeeSchedule.breakevenPrice(
            entry = entry,
            buyFeePct = FeeSchedule.buyRate(tokocryptoIdr, idr) * 100.0,
            sellFeePct = FeeSchedule.sellRate(tokocryptoIdr, idr) * 100.0
        )
        // Kenaikan harga yang dibutuhkan harus sama dengan angka yang dilaporkan.
        assertEquals(kenaikanImpasPct, (be / entry - 1.0) * 100.0, 1e-4)

        val diImpas = FeeSchedule.netProfitPctFor(
            entry = entry,
            price = be,
            exchange = tokocryptoIdr,
            quoteAsset = idr
        )
        assertEquals(0.0, diImpas, 1e-9)
    }

    @Test
    fun `paritas dengan PaperExitResolver untuk fee dan slippage yang sama`() {
        val entry = 2_500_000.0
        val buyFee = FeeSchedule.buyRate(tokocryptoIdr, idr) * 100.0
        val sellFee = FeeSchedule.sellRate(tokocryptoIdr, idr) * 100.0

        for (grossPct in listOf(-2.0, -0.5, 0.0, 0.5, 1.0, 1.5, 3.0, 5.0)) {
            val price = entry * (1.0 + grossPct / 100.0)
            val dariFeeSchedule = FeeSchedule.netProfitPct(
                entry = entry,
                price = price,
                buyFeePct = buyFee,
                sellFeePct = sellFee,
                slippagePct = FeeSchedule.DEFAULT_SLIPPAGE_PCT
            )
            val dariResolver = PaperExitResolver.netProfitPct(
                entry = entry,
                price = price,
                buyFeePct = buyFee,
                sellFeePct = sellFee,
                slippagePct = FeeSchedule.DEFAULT_SLIPPAGE_PCT
            )
            assertEquals(
                "bruto $grossPct% harus identik",
                dariResolver,
                dariFeeSchedule,
                1e-12
            )
        }
    }

    @Test
    fun `harga turun menghasilkan profit negatif dan harga naik menghasilkan positif`() {
        val entry = 1_000_000.0
        val turun = FeeSchedule.netProfitPctFor(entry, entry * 0.98, tokocryptoIdr, idr)
        val naik = FeeSchedule.netProfitPctFor(entry, entry * 1.05, tokocryptoIdr, idr)

        assertTrue("harga turun 2% harus negatif, dapat $turun", turun < 0.0)
        assertTrue("harga naik 5% harus positif, dapat $naik", naik > 0.0)
    }

    @Test
    fun `rupiah dan persen memakai basis biaya yang sama`() {
        // Invarian anti-drift: kalau dua helper jumlah dipakai bersama, persen yang dihitung dari
        // rupiah harus sama persis dengan netProfitPct. Kalau salah satu diubah sendirian, uji ini
        // gagal — jadi tidak mungkin lagi ada selisih antara angka persen dan angka rupiah.
        val entry = 1_840_000.0
        val price = 1_905_000.0
        val qty = 0.0027
        val buyFee = FeeSchedule.buyRate(tokocryptoIdr, idr) * 100.0
        val sellFee = FeeSchedule.sellRate(tokocryptoIdr, idr) * 100.0

        val cost = FeeSchedule.costBasisNet(qty, entry, buyFee)
        val net = FeeSchedule.netSellValue(qty, price, sellFee)
        val pctDariRupiah = ((net - cost) / cost) * 100.0
        val pctLangsung = FeeSchedule.netProfitPctFor(entry, price, tokocryptoIdr, idr)

        assertEquals(pctLangsung, pctDariRupiah, 1e-9)
    }

    @Test
    fun `biaya perolehan sudah termasuk fee beli dan slippage`() {
        val qty = 1.0
        val entry = 1_000_000.0
        val buyFee = FeeSchedule.buyRate(tokocryptoIdr, idr) * 100.0
        val cost = FeeSchedule.costBasisNet(qty, entry, buyFee)

        // 0,2222% fee taker + 0,08% slippage = 0,3022% di atas nominal.
        assertTrue("biaya perolehan harus di atas nominal", cost > qty * entry)
        assertEquals(0.3022, (cost / (qty * entry) - 1.0) * 100.0, 1e-4)
    }

    @Test
    fun `harga tidak valid menghasilkan nol tanpa melempar`() {
        assertEquals(0.0, FeeSchedule.netProfitPctFor(0.0, 1_000_000.0, tokocryptoIdr, idr), 0.0)
        assertEquals(0.0, FeeSchedule.netProfitPctFor(1_000_000.0, 0.0, tokocryptoIdr, idr), 0.0)
        assertEquals(0.0, FeeSchedule.netProfitPctFor(-1.0, 1_000_000.0, tokocryptoIdr, idr), 0.0)
        assertEquals(0.0, FeeSchedule.breakevenPrice(0.0, 0.1, 0.2), 0.0)
    }
}

package agu.analys.data

import agu.analys.model.TokocryptoSymbolInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **K1/K2** — daftar pair Tokocrypto hanya boleh berasal dari `common/symbols` resmi.
 *
 * Cacat lama: respons resmi berbentuk `data: { list: [...] }` dibaca sebagai array, sehingga
 * endpoint resmi selalu "gagal", lalu aplikasi memakai `exchangeInfo` gateway yang berisi
 * 3.729 simbol universe Binance — termasuk `UVXYBUSDT` yang tidak ada di Tokocrypto.
 *
 * Uji ini menjaga tiga hal:
 *  1. bentuk respons resmi terbaca;
 *  2. daftar yang tidak wajar (terlalu besar/kecil, tanpa IDR, overlap rendah) ditolak;
 *  3. repository tidak lagi memakai gateway sebagai sumber daftar pair.
 *
 * Kontrol negatif: kembalikan pembacaan `optJSONArray("data")` saja → uji bentuk resmi GAGAL;
 * hapus `rejectionReason` sebelum `symbolsMap.clear()` → uji penjaga sumber GAGAL.
 */
class TokocryptoSymbolFeedTest {

    private fun repoSource(): File {
        val kandidat = listOf(
            File(System.getProperty("user.dir"), "src/main/java/agu/analys/data/TokocryptoSymbolRepository.kt"),
            File(System.getProperty("user.dir"), "app/src/main/java/agu/analys/data/TokocryptoSymbolRepository.kt")
        )
        return kandidat.firstOrNull { it.exists() }
            ?: error("TokocryptoSymbolRepository.kt tidak ditemukan dari user.dir=${System.getProperty("user.dir")}")
    }

    private fun officialRoot(): JSONObject = JSONObject(
        """
        {"code":0,"msg":"Success","data":{"list":[
          {"type":1,"symbol":"BTC_USDT","baseAsset":"BTC","quoteAsset":"USDT","basePrecision":8,"quotePrecision":8,
           "spotTradingEnable":1,"filters":[
             {"filterType":"PRICE_FILTER","minPrice":"0.01000000","maxPrice":"1000000.00000000","tickSize":"0.01000000"},
             {"filterType":"LOT_SIZE","minQty":"0.00001000","maxQty":"9000.00000000","stepSize":"0.00001000"},
             {"filterType":"MARKET_LOT_SIZE","minQty":"0.00000000","maxQty":"100.00000000","stepSize":"0.00000000"},
             {"filterType":"NOTIONAL","minNotional":"1.00000000","applyToMarket":true,"avgPriceMins":5}
           ]},
          {"type":3,"symbol":"ALCH_IDR","baseAsset":"ALCH","quoteAsset":"IDR","basePrecision":8,"quotePrecision":2,
           "spotTradingEnable":1,"filters":[]},
          {"type":1,"symbol":"ETH_USDC","baseAsset":"ETH","quoteAsset":"USDC","basePrecision":8,"quotePrecision":8,
           "spotTradingEnable":1,"filters":[]}
        ]}}
        """.trimIndent()
    )

    private fun info(symbol: String, quote: String = "USDT") = TokocryptoSymbolInfo(
        symbol = symbol,
        baseAsset = symbol.substringBefore('_'),
        quoteAsset = quote
    )

    @Test
    fun `bentuk resmi data list terbaca`() {
        val list = TokocryptoSymbolFeed.extractList(officialRoot())
        assertEquals(3, list!!.length())
    }

    @Test
    fun `bentuk data array dan symbols array tetap terbaca`() {
        val dataArray = JSONObject("""{"data":[{"symbol":"BTC_USDT"}]}""")
        val symbolsArray = JSONObject("""{"symbols":[{"symbol":"BTC_USDT"}]}""")
        assertEquals(1, TokocryptoSymbolFeed.extractList(dataArray)!!.length())
        assertEquals(1, TokocryptoSymbolFeed.extractList(symbolsArray)!!.length())
    }

    @Test
    fun `parse resmi membaca type dan spotTradingEnable numerik`() {
        val parsed = TokocryptoSymbolFeed.parse(TokocryptoSymbolFeed.extractList(officialRoot())!!)
        assertEquals(2, parsed.size) // USDC dibuang
        val btc = parsed.first { it.symbol == "BTC_USDT" }
        val alch = parsed.first { it.symbol == "ALCH_IDR" }
        assertEquals(1, btc.symbolType)
        assertEquals(3, alch.symbolType)
        assertTrue(btc.spotTradingEnable)
        assertEquals(0.01, btc.priceFilter!!.tickSize, 0.0)
        assertEquals(1.0, btc.minNotionalFilter!!.minNotional, 0.0)
    }

    @Test
    fun `spotTradingEnable gagal-tertutup untuk nilai tak dikenal`() {
        fun item(json: String) = JSONObject(json)
        assertTrue(TokocryptoSymbolFeed.readSpotTradingEnable(item("""{"spotTradingEnable":1}""")))
        assertTrue(TokocryptoSymbolFeed.readSpotTradingEnable(item("""{"spotTradingEnable":"1"}""")))
        assertTrue(TokocryptoSymbolFeed.readSpotTradingEnable(item("""{"spotTradingEnable":true}""")))
        assertFalse(TokocryptoSymbolFeed.readSpotTradingEnable(item("""{"spotTradingEnable":0}""")))
        assertFalse(TokocryptoSymbolFeed.readSpotTradingEnable(item("""{"spotTradingEnable":"0"}""")))
        assertFalse(TokocryptoSymbolFeed.readSpotTradingEnable(item("""{"spotTradingEnable":null}""")))
        assertFalse(TokocryptoSymbolFeed.readSpotTradingEnable(item("""{"symbol":"X_USDT"}""")))
        assertTrue(TokocryptoSymbolFeed.readSpotTradingEnable(item("""{"status":"TRADING"}""")))
        assertFalse(TokocryptoSymbolFeed.readSpotTradingEnable(item("""{"status":"BREAK","spotTradingEnable":1}""")))
    }

    @Test
    fun `universe Binance 3729 simbol ditolak`() {
        val besar = (0 until 3_729).map { info("COIN${it}_USDT") }
        val alasan = TokocryptoSymbolFeed.rejectionReason(besar, emptySet())
        assertTrue(alasan != null && alasan.contains("terlalu banyak"))
    }

    @Test
    fun `daftar 63 simbol ditolak`() {
        val kecil = (0 until 63).map { info("COIN${it}_IDR", "IDR") }
        val alasan = TokocryptoSymbolFeed.rejectionReason(kecil, emptySet())
        assertTrue(alasan != null && alasan.contains("terlalu sedikit"))
    }

    @Test
    fun `daftar tanpa pair IDR ditolak`() {
        val tanpaIdr = (0 until 200).map { info("COIN${it}_USDT") }
        val alasan = TokocryptoSymbolFeed.rejectionReason(tanpaIdr, emptySet())
        assertTrue(alasan != null && alasan.contains("IDR"))
    }

    @Test
    fun `overlap rendah dengan snapshot lama ditolak`() {
        val lama = (0 until 200).map { "COIN${it}_IDR" }.toSet()
        val baru = (0 until 200).map { info("BARU${it}_IDR", "IDR") }
        val alasan = TokocryptoSymbolFeed.rejectionReason(baru, lama)
        assertTrue(alasan != null && alasan.contains("tumpang tindih"))
    }

    @Test
    fun `overlap tinggi diterima`() {
        val lama = (0 until 200).map { "COIN${it}_IDR" }.toSet()
        val baru = (0 until 190).map { info("COIN${it}_IDR", "IDR") } +
            (0 until 10).map { info("BARU${it}_IDR", "IDR") }
        assertNull(TokocryptoSymbolFeed.rejectionReason(baru, lama))
    }

    @Test
    fun `repository tidak memakai gateway sebagai sumber daftar pair`() {
        val src = repoSource().readLines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
            .joinToString("\n")
        assertFalse(
            "exchangeInfo gateway tidak boleh ada di jalur discovery",
            src.contains("tokocrypto.site/api/v3/exchangeInfo")
        )
        assertTrue(src.contains("open/v1/common/symbols"))
        val guard = src.indexOf("rejectionReason")
        val clear = src.indexOf("symbolsMap.clear()")
        assertTrue("rejectionReason harus dipanggil", guard >= 0)
        assertTrue("snapshot hanya boleh diganti setelah lolos guard", clear > guard)
    }
}

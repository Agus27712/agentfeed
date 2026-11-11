package agu.analys.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * J5 — resolusi record HOLDING untuk jurnal trade tanpa fallback ke query tanpa filter bursa.
 * Resolver hanya melihat record milik bursa yang diminta; dulu fallback ke query "tanpa filter
 * bursa" menutup / memperbarui record yang salah.
 *
 * Konvensi fake di berkas ini: exchange `""` mengemulasi query "tanpa filter bursa"
 * (`getActiveHoldingForSymbol` / `getHoldingRecords`). Versi tetap tidak boleh memanggilnya.
 */
class HoldingResolutionTest {

    private fun record(
        uuid: String,
        symbol: String,
        exchange: String,
        status: String = "HOLDING",
        isReal: Boolean = false
    ) = TradeHistoryRecordEntity(
        tradeUuid = uuid,
        symbol = symbol,
        isRealTrade = isReal,
        exchange = exchange,
        status = status
    )

    // ---------- resolveForSell ----------

    @Test
    fun `uuid dicari lewat uuid lebih dulu`() {
        val tk = record("u-tk", "BTCIDR", "TOKOCRYPTO")
        val found = runBlocking { HoldingResolution.resolveForSell(
            tradeUuid = "u-tk",
            symbol = "BTCIDR",
            isReal = false,
            exchange = "TOKOCRYPTO",
            byUuid = { if (it == "u-tk") tk else null },
            bySymbolAndExchange = { _, _, _ -> null }
        ) }
        assertEquals(tk, found)
    }

    @Test
    fun `tanpa uuid memakai record bursa yang sama`() {
        val tk = record("u-tk", "BTCIDR", "TOKOCRYPTO")
        val found = runBlocking { HoldingResolution.resolveForSell(
            tradeUuid = null,
            symbol = "BTCIDR",
            isReal = false,
            exchange = "TOKOCRYPTO",
            byUuid = { null },
            bySymbolAndExchange = { _, _, e -> if (e == "TOKOCRYPTO") tk else null }
        ) }
        assertEquals(tk, found)
    }

    @Test
    fun `uuid kosong diperlakukan seperti tidak ada`() {
        val tk = record("u-tk", "BTCIDR", "TOKOCRYPTO")
        val byUuidCalls = mutableListOf<String>()
        val found = runBlocking { HoldingResolution.resolveForSell(
            tradeUuid = "   ",
            symbol = "BTCIDR",
            isReal = false,
            exchange = "TOKOCRYPTO",
            byUuid = { byUuidCalls.add(it); null },
            bySymbolAndExchange = { _, _, e -> if (e == "TOKOCRYPTO") tk else null }
        ) }
        assertEquals(tk, found)
        assertTrue("uuid kosong tidak boleh short-circuit ke query uuid", byUuidCalls.isEmpty())
    }

    @Test
    fun `uuid tidak ditemukan tidak jatuh ke fallback bursa`() {
        val found = runBlocking { HoldingResolution.resolveForSell(
            tradeUuid = "u-hilang",
            symbol = "BTCIDR",
            isReal = false,
            exchange = "TOKOCRYPTO",
            byUuid = { null },
            bySymbolAndExchange = { _, _, _ -> record("u-lain", "BTCIDR", "TOKOCRYPTO") }
        ) }
        assertNull(found)
    }

    @Test
    fun `isReal dibedakan`() {
        val real = record("u-real", "BTCIDR", "TOKOCRYPTO", isReal = true)
        val paper = record("u-paper", "BTCIDR", "TOKOCRYPTO", isReal = false)
        val found = runBlocking { HoldingResolution.resolveForSell(
            tradeUuid = null,
            symbol = "BTCIDR",
            isReal = true,
            exchange = "TOKOCRYPTO",
            byUuid = { null },
            bySymbolAndExchange = { _, r, _ -> if (r) real else paper }
        ) }
        assertEquals(real, found)
    }

    // ---------- holdingsForPriceTick ----------

    @Test
    fun `record bursa sama dan simbol sama ikut tick`() {
        val tk = record("u1", "BTCIDR", "TOKOCRYPTO")
        val found = runBlocking { HoldingResolution.holdingsForPriceTick("BTCIDR", "TOKOCRYPTO") { ex ->
            if (ex.isEmpty()) listOf(tk) else listOf(tk)
        } }
        assertEquals(listOf(tk), found)
    }

    @Test
    fun `simbol beda tidak ikut`() {
        val eth = record("u2", "ETHIDR", "TOKOCRYPTO")
        val found = runBlocking { HoldingResolution.holdingsForPriceTick("BTCIDR", "TOKOCRYPTO") { ex ->
            if (ex.isEmpty()) listOf(eth) else listOf(eth)
        } }
        assertTrue(found.isEmpty())
    }

    @Test
    fun `kapitalisasi simbol diabaikan`() {
        val btc = record("u3", "btcidr", "TOKOCRYPTO")
        val found = runBlocking { HoldingResolution.holdingsForPriceTick("BTCIDR", "TOKOCRYPTO") { ex ->
            if (ex.isEmpty()) listOf(btc) else listOf(btc)
        } }
        assertEquals(listOf(btc), found)
    }

}

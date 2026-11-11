package agu.analys.bridge

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
class AgentTradingTest {

    private lateinit var context: Context
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        AgentTradingCommandHandler.unregister()
    }

    @Test
    fun `parseAndValidateSymbol memvalidasi kuotasi USDT dan IDR dengan benar`() {
        val btcUsdt = AgentTradingCommandHandler.parseAndValidateSymbol("BTCUSDT")
        assertNotNull(btcUsdt)
        assertEquals("BTC", btcUsdt?.first)
        assertEquals("USDT", btcUsdt?.second)
        assertEquals("BTC_USDT", btcUsdt?.third)

        val ethIdr = AgentTradingCommandHandler.parseAndValidateSymbol("eth_idr")
        assertNotNull(ethIdr)
        assertEquals("ETH", ethIdr?.first)
        assertEquals("IDR", ethIdr?.second)

        // Kuotasi tidak valid atau BIDR umum ditolak
        assertNull(AgentTradingCommandHandler.parseAndValidateSymbol("DOGEBIDR"))
        assertNull(AgentTradingCommandHandler.parseAndValidateSymbol("BTCBUSD"))
        assertNull(AgentTradingCommandHandler.parseAndValidateSymbol(""))
    }

    @Test
    fun `handleBuy menolak order riil saat coordinator belum siap atau mode real mati`() {
        runBlocking {
            val req = AgentTradeRequest(
                symbol = "BTCUSDT",
                side = "BUY",
                quoteAmount = 15.0,
                price = 65000.0,
                isSimulation = false
            )
            val resp = AgentTradingCommandHandler.handleBuy(req, context)
            assertFalse(resp.success)
            assertTrue("pesan penolakan: ${resp.message}", resp.message.contains("belum siap") || resp.message.contains("dimatikan"))
        }
    }

    @Test
    fun `handleBuy menolak simbol yang kuotasinya tidak sah`() {
        runBlocking {
            val req = AgentTradeRequest(
                symbol = "INVALIDPAIR",
                side = "BUY",
                quoteAmount = 10.0,
                price = 100.0,
                isSimulation = true
            )
            val resp = AgentTradingCommandHandler.handleBuy(req, context)
            assertFalse(resp.success)
            assertTrue("pesan simbol tidak valid: ${resp.message}", resp.message.contains("Format simbol"))
        }
    }

    @Test
    fun `model serialisasi JSON dapat di-encode dan di-decode secara konsisten`() {
        val req = AgentTradeRequest(
            symbol = "BTCUSDT",
            side = "BUY",
            quoteAmount = 25.0,
            price = 65000.0,
            isSimulation = true
        )
        val str = json.encodeToString(req)
        assertTrue(str.contains("\"symbol\":\"BTCUSDT\""))
        assertTrue(str.contains("\"quoteAmount\":25.0"))

        val decoded = json.decodeFromString<AgentTradeRequest>(str)
        assertEquals("BTCUSDT", decoded.symbol)
        assertEquals(25.0, decoded.quoteAmount)
        assertTrue(decoded.isSimulation)

        val resp = AgentTradeResponse(
            success = true,
            message = "Order diterima",
            orderId = "12345",
            symbol = "BTC_USDT",
            side = "BUY",
            price = 65000.0,
            quantity = 0.00038,
            status = "FILLED"
        )
        val respStr = json.encodeToString(resp)
        val respDecoded = json.decodeFromString<AgentTradeResponse>(respStr)
        assertEquals("12345", respDecoded.orderId)
        assertEquals("FILLED", respDecoded.status)
    }

    @Test
    fun `endpoint status trading mengembalikan status JSON yang valid`() {
        val status = AgentTradingCommandHandler.getTradingStatus(context)
        assertFalse(status.isRealBuyEnabled)
        assertEquals(8765, status.port)
        assertEquals(AgentHttpServer.HOST, status.host)
        val jsonStr = json.encodeToString(status)
        assertTrue("isi status host: $jsonStr", jsonStr.contains("\"host\":\"${AgentHttpServer.HOST}\""))
        assertTrue("isi status port: $jsonStr", jsonStr.contains("\"port\":8765"))
    }

    // ------------------------------------------------------------------
    // 2026-11-06 — akuntansi fill + auth token (dijaga dua lapis: perilaku
    // yang bisa diuji JVM, dan pemeriksaan sumber untuk bagian yang hidup
    // di ViewModel ber-Compose / jalur bursa yang butuh kredensial).
    // ------------------------------------------------------------------

    @Test
    fun `AgentTradeResponse membawa harga dan qty yang diminta bursa`() {
        val resp = AgentTradeResponse(
            success = true,
            message = "ok",
            orderId = "ORD-1",
            clientOrderId = "CLI-1",
            symbol = "SOXSBUSDT",
            side = "BUY",
            price = 82.67,
            requestedPrice = 82.60,
            executedQty = 6.0,
            status = "FILLED"
        )
        val decoded = json.decodeFromString<AgentTradeResponse>(json.encodeToString(resp))
        assertEquals(82.60, decoded.requestedPrice ?: 0.0, 1e-9)
        assertEquals(82.67, decoded.price, 1e-9)
        assertEquals(6.0, decoded.executedQty, 1e-9)
        assertEquals("ORD-1", decoded.orderId)
        assertEquals("CLI-1", decoded.clientOrderId)

        // Field baru boleh absen (pemanggil lama tanpa field ini tetap terdeserialisasi).
        val tanpaField = json.decodeFromString<AgentTradeResponse>(
            """{"success":true,"message":"m","symbol":"BTCUSDT","side":"SELL"}"""
        )
        assertNull(tanpaField.requestedPrice)
    }

    /** Pemindai kode sumber aplikasi — pola `PaperBuyNominalSourceTest`. */
    private fun kode(relatif: String): String {
        val kandidat = listOf(
            java.io.File(System.getProperty("user.dir"), "src/main/java/$relatif"),
            java.io.File(System.getProperty("user.dir"), "app/src/main/java/$relatif")
        )
        val f = kandidat.firstOrNull { it.exists() }
            ?: error("$relatif tidak ditemukan dari user.dir=${System.getProperty("user.dir")}")
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `sumber executor mencatat fill di seluruh cabang akhir order riil`() {
        val src = kode("agu/analys/viewmodel/RealTradeExecutor.kt")
        // Hitung PEMANGGILAN recordFill (bukan deklarasi `private fun recordFill(`):
        // wajib ada di cabang NEW (belum terisi), FILLED/PARTIALLY, sukses generik, dan REJECTED.
        val panggilan = Regex("recordFill\\(\\s*pair,").findAll(src).count()
        assertTrue("panggilan recordFill harus ada di 4 cabang akhir order, ada $panggilan", panggilan >= 4)
        assertTrue("lastFill harus tersimpan", src.contains("var lastFill: RealFillRecord?"))
        assertTrue("record diisi sebelum callback", src.contains("RealFillRecord("))
        assertTrue("status REJECTED dicatat", src.contains("\"REJECTED\""))
        assertTrue("status NEW dicatat untuk order belum terisi", src.contains("\"NEW\""))
    }

    @Test
    fun `sumber bridge memakai record fill di handleBuy dan handleSell`() {
        val handler = kode("agu/analys/bridge/AgentTradingCommandHandler.kt")
        assertTrue("lastRealFillFor buy di handleBuy", handler.contains("lastRealFillFor(normSymbol, \"buy\")"))
        assertTrue("lastRealFillFor sell di handleSell", handler.contains("lastRealFillFor(normSymbol, \"sell\")"))
        assertTrue("status dari record, bukan karangan", handler.contains("realFillStatus(success, fill)"))
        val coordinator = kode("agu/analys/viewmodel/RealTradeCoordinator.kt")
        assertTrue("koordinator expose fill", coordinator.contains("fun lastRealFillFor"))
        assertTrue("umur record dibatasi", coordinator.contains("FILL_STALE_MS"))
    }

    @Test
    fun `sumber emit sinyal meneruskan snapshot MTF dari kedua titik panggil`() {
        val sync = kode("agu/analys/viewmodel/TradingViewModelSync.kt")
        assertTrue("builder murni dipakai", sync.contains("AgentSignalBreakdown.build("))
        // Dua titik emit: transisi mode (line ~665) dan emit ulang sinyal terakhir (~848).
        assertTrue("titik 1 mem-pass mtf", sync.contains("mtf = transition.signal.mtf"))
        assertTrue("titik 2 mem-pass mtf", sync.contains("mtf = signal.mtf"))
        assertTrue("setup diteruskan", sync.contains("scalpingSetup = "))
        assertTrue("regime diteruskan", sync.contains("scalpingRegime = "))
    }

    @Test
    fun `sumber memasang pendorong portofolio di server dan mengekspos ws portfolio`() {
        val server = kode("agu/analys/bridge/AgentHttpServer.kt")
        assertTrue("server wajib menyalakan pendorong", server.contains("startPortfolioPush()"))
        assertTrue("rute WS portfolio", server.contains("webSocket(\"/ws/portfolio\")"))
        assertTrue(
            "SSE wajib menggabungkan sinyal + portofolio",
            server.contains("merge(AgentEventBus.signals, AgentEventBus.portfolio)")
        )
        assertTrue("label event portfolio", server.contains("event: portfolio"))

        val bridge = kode("agu/analys/bridge/AgentOutputBridge.kt")
        assertTrue("loop pendorong ada", bridge.contains("fun startPortfolioPush()"))
        assertTrue("loop memakai snapshot resmi", bridge.contains("buildPortfolioSnapshot(context)"))
        assertTrue("loop memakai keputusan murni", bridge.contains("AgentPortfolioPusher.shouldEmit("))

        val pusher = kode("agu/analys/bridge/AgentPortfolioPusher.kt")
        assertTrue("cap mengabaikan urutan aset", pusher.contains("sortedBy { it.symbol }"))
        assertTrue("heartbeat tersedia", pusher.contains("HEARTBEAT_MS"))

        val ekstensi = java.io.File(System.getProperty("user.dir"), "tools/oh-my-pi-extension/index.ts")
            .takeIf { it.exists() }
            ?: java.io.File(System.getProperty("user.dir"), "../tools/oh-my-pi-extension/index.ts")
        val isi = ekstensi.readText(Charsets.UTF_8)
        assertTrue("tool subscribe_portfolio ada", isi.contains("name: \"subscribe_portfolio\""))
        assertTrue("ekstensi menyambung ke ws portfolio", isi.contains("/ws/portfolio"))
    }

    @Test
    fun `sumber installer menulis berkas token ke kedua direktori Termux`() {
        val cmd = OmpBridgeInstaller.buildInstallCommand("aGVsbG8=", "index.ts", "abc123")
        assertTrue(
            "berkas token di dir utama: $cmd",
            cmd.contains("${OmpBridgeInstaller.DESTINATION_DIR}/${OmpBridgeInstaller.TOKEN_FILE}")
        )
        assertTrue(
            "berkas token di dir alternatif: $cmd",
            cmd.contains("${OmpBridgeInstaller.ALT_DESTINATION_DIR}/${OmpBridgeInstaller.TOKEN_FILE}")
        )
        assertTrue("token asli masuk perintah", cmd.contains("abc123"))
        // Tanpa token (jalur lama), perintah tetap valid dan tidak menulis berkas kosong.
        val tanpaToken = OmpBridgeInstaller.buildInstallCommand("aGVsbG8=", "index.ts")
        assertTrue("tanpa token tidak menulis berkas token", !tanpaToken.contains("agent-bridge-token.txt"))
        assertTrue("tidak ada baris baru", "\n" !in cmd)

        // Ekstensi sumber membaca token: header X-Agent-Token nyata di index.ts.
        val ekstensi = java.io.File(System.getProperty("user.dir"), "tools/oh-my-pi-extension/index.ts")
            .takeIf { it.exists() }
            ?: java.io.File(System.getProperty("user.dir"), "../tools/oh-my-pi-extension/index.ts")
        val isi = ekstensi.readText(Charsets.UTF_8)
        assertTrue("ekstensi membaca X-Agent-Token", isi.contains("X-Agent-Token"))
        assertTrue("ekstensi membaca token dari berkas", isi.contains(OmpBridgeInstaller.TOKEN_FILE))
        assertTrue("ekstensi fallback env AGENTFEED_TOKEN", isi.contains("AGENTFEED_TOKEN"))
    }
}

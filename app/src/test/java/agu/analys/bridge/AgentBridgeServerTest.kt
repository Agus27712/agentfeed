package agu.analys.bridge

import android.content.Context
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Uji **perilaku nyata** server bridge: bukan serialisasi model, tetapi "apakah sakelar di
 * Pengaturan benar-benar menyalakan server dan tidak menutup aplikasi".
 *
 * Latar belakang (2026-11-04): `start()` dulu memanggil `embeddedServer(...)` langsung di main
 * thread, dan kegagalannya berupa `NoSuchMethodError` (konflik kotlinx-coroutines dengan Ktor
 * 2.3.9). Karena `NoSuchMethodError` turunan `Error`, `catch (e: Exception)` tidak menangkapnya
 * sehingga aplikasi force close begitu sakelar dinyalakan. Uji di berkas ini menjaga tiga sifat:
 *
 * 1. `start()` **tidak memblokir** pemanggil (pekerjaan server berjalan di thread terpisah);
 * 2. keadaan akhir terlihat jelas lewat [AgentHttpServer.state] — `Running` hanya setelah port
 *    benar-benar melayani, bukan setelah objek server dibuat;
 * 3. `stop()` benar-benar menutup port, dan kegagalan menyala dilaporkan sebagai `Failed`
 *    (bukan dilempar ke pemanggil).
 */
@RunWith(RobolectricTestRunner::class)
class AgentBridgeServerTest {

    private lateinit var context: Context
    private lateinit var server: AgentHttpServer

    @Before
    fun siapkan() {
        runBlocking {
            context = RuntimeEnvironment.getApplication()
            server = AgentHttpServer.getInstance(context)
            server.stop()
            server.awaitSettled(10_000)
        }
    }

    private fun httpGet(path: String): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:${AgentHttpServer.PORT}$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 1_500
        conn.readTimeout = 1_500
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(path: String, body: String, token: String? = null): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:${AgentHttpServer.PORT}$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 1_500
        conn.readTimeout = 1_500
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (token != null) conn.setRequestProperty(AgentHttpServer.HEADER_TOKEN, token)
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `sakelar menyala membuka port dan stop menutupnya`() {
        runBlocking {
            server.start()
            val state = server.awaitSettled(20_000)
            assertTrue("keadaan akhir: $state", state is AgentBridgeServerState.Running)
            assertTrue(server.isRunning)

            val (healthCode, healthBody) = httpGet("/health")
            assertEquals(200, healthCode)
            assertTrue("badan /health: $healthBody", healthBody.contains("\"ok\":true"))

            val (rootCode, rootBody) = httpGet("/")
            assertEquals(200, rootCode)
            assertTrue("badan /: $rootBody", rootBody.contains("agentfeed-bridge"))

            server.stop()
            assertEquals(AgentBridgeServerState.Stopped, server.awaitSettled(10_000))
            assertTrue(!server.isRunning)
            val afterStop = runCatching { httpGet("/health") }
            assertTrue("port masih melayani setelah stop", afterStop.isFailure)
        }
    }

    @Test
    fun `start tidak memblokir thread pemanggil`() {
        val startedAt = System.nanoTime()
        server.start()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("start() memblokir pemanggil selama $elapsedMs ms", elapsedMs < 500)
        runBlocking { server.awaitSettled(20_000) }
    }

    @Test
    fun `port terpakai dilaporkan Failed bukan melempar keluar`() {
        runBlocking {
            ServerSocket(AgentHttpServer.PORT).use {
                server.start()
                val state = server.awaitSettled(20_000)
                assertTrue("keadaan akhir: $state", state is AgentBridgeServerState.Failed)
                assertTrue("server tidak boleh diklaim berjalan", !server.isRunning)
            }

            // Setelah port bebas, percobaan berikutnya harus bisa berhasil (bukan terkunci di Failed).
            server.start()
            val retry = server.awaitSettled(20_000)
            assertTrue("keadaan akhir percobaan ulang: $retry", retry is AgentBridgeServerState.Running)
            server.stop()
            server.awaitSettled(10_000)
        }
    }

    @Test
    fun `server hanya bind loopback bukan semua antarmuka`() {
        assertEquals("127.0.0.1", AgentHttpServer.HOST)
    }

    /**
     * Push portofolio ke SSE (2026-11-06): `/events` dulu hanya mengoleksi sinyal,
     * sehingga `AgentEventBus.portfolio` yang punya produsen pun tidak pernah sampai
     * ke client HTTP streaming. Uji ini memakai event bus langsung (tanpa prefs/singleton
     * Application yang berbeda-beda di Robolectric) dan memastikan label `portfolio`
     * benar-benar muncul beserta isinya.
     */
    @Test
    fun `stream events mengirim portofolio berlabel portfolio`() {
        runBlocking {
            server.start()
            assertTrue(server.awaitSettled(20_000) is AgentBridgeServerState.Running)
            val penanda = "UJISSE${System.currentTimeMillis() % 100000}"
            AgentEventBus.emitPortfolio(
                AgentPortfolioOutput(
                    ts = System.currentTimeMillis(),
                    exchange = penanda,
                    totalIdrValue = 160_000.0,
                    totalUsdtValue = 10.0,
                    assets = listOf(
                        AgentAsset("${penanda}USDT", 1.0, 0.0, 100.0, 110.0, 10.0, 10.0)
                    )
                )
            )

            val conn = URL("http://127.0.0.1:${AgentHttpServer.PORT}/events")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 2_000
            conn.readTimeout = 6_000
            val lines = mutableListOf<String>()
            try {
                conn.inputStream.bufferedReader().use { reader ->
                    while (lines.size < 80) {
                        val line = reader.readLine() ?: break
                        lines.add(line)
                        if (line.contains(penanda)) break
                    }
                }
            } finally {
                conn.disconnect()
            }

            assertTrue("event portfolio tidak muncul; baris=${lines}", lines.contains("event: portfolio"))
            assertTrue("isi snapshot tidak muncul; baris=${lines}", lines.any { it.contains(penanda) })
            server.stop()
            server.awaitSettled(10_000)
        }
    }

    /**
     * `/ws/portfolio` wajib mengirim snapshot terakhir segera setelah tersambung
     * (replay bus = 1), supaya agen yang baru terhubung tidak butuh menunggu heartbeat.
     */
    @Test
    fun `websocket portfolio mengirim snapshot terakhir saat tersambung`() {
        runBlocking {
            server.start()
            assertTrue(server.awaitSettled(20_000) is AgentBridgeServerState.Running)
            val penanda = "UJIWS${System.currentTimeMillis() % 100000}"
            AgentEventBus.emitPortfolio(
                AgentPortfolioOutput(
                    ts = System.currentTimeMillis(),
                    exchange = penanda,
                    totalIdrValue = 0.0,
                    totalUsdtValue = 1.0,
                    assets = listOf(
                        AgentAsset("${penanda}USDT", 1.0, 0.0, 1.0, 1.0, 0.0, 0.0)
                    )
                )
            )

            val diterima = java.util.concurrent.LinkedBlockingQueue<String>()
            val pendengar = object : java.net.http.WebSocket.Listener {
                private val buf = StringBuilder()
                override fun onText(
                    webSocket: java.net.http.WebSocket,
                    data: CharSequence,
                    last: Boolean
                ): java.util.concurrent.CompletionStage<*>? {
                    buf.append(data)
                    if (last) {
                        diterima.offer(buf.toString())
                        buf.setLength(0)
                    }
                    webSocket.request(1)
                    return null
                }
            }
            val ws = java.net.http.HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(
                    java.net.URI.create("ws://127.0.0.1:${AgentHttpServer.PORT}/ws/portfolio"),
                    pendengar
                )
                .get(5, java.util.concurrent.TimeUnit.SECONDS)
            try {
                var pesan = diterima.poll(6, java.util.concurrent.TimeUnit.SECONDS)
                // Replay bus bisa berisi snapshot penanda ini; kalau belum, tunggu satu putaran lagi.
                if (pesan == null || !pesan.contains(penanda)) {
                    pesan = diterima.poll(6, java.util.concurrent.TimeUnit.SECONDS) ?: pesan
                }
                assertTrue("snapshot portofolio tidak diterima: $pesan", pesan != null && pesan.contains(penanda))
            } finally {
                runCatching {
                    ws.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "selesai")
                        .get(2, java.util.concurrent.TimeUnit.SECONDS)
                }
                ws.abort()
                server.stop()
                server.awaitSettled(10_000)
            }
        }
    }

    /**
     * Keamanan endpoint tulis (2026-11-06): rute `api/v1/trading` menuntut header
     * `X-Agent-Token` yang cocok dengan token di AppPreferences. Tanpa token atau
     * token salah → 401 (fail-closed). Token benar → boleh lanjut (bisa 400/500
     * karena body kosong, tapi BOLEH tidak 401). Rute baca seperti `/health`
     * tidak boleh ikut terkunci (sudah dijaga uji di atas).
     */
    @Test
    fun `endpoint tulis menuntut X-Agent-Token tanpa token diterima`() {
        runBlocking {
            server.start()
            assertTrue("server tidak menyala", server.awaitSettled(20_000) is AgentBridgeServerState.Running)
            // Aturan Robolectric: instance Application baru per test, sementara singleton server
            // memakai context dari saat pertama dibuat — jadi token yang dibandingkan adalah
            // token yang server sendiri terpasang (lihat lastInstalledBridgeToken).
            val token = AgentHttpServer.lastInstalledBridgeToken
            assertTrue("token server tidak boleh kosong", !token.isNullOrBlank())
            val t = token ?: ""

            val (tanpaToken, badanTanpa) = httpPost("/api/v1/trading/buy", "{}")
            assertEquals("tetap 401 tanpa token", 401, tanpaToken)
            assertTrue("badan penolakan menerangkan header: $badanTanpa", badanTanpa.contains("X-Agent-Token"))

            val (tokenSalah, _) = httpPost("/api/v1/trading/buy", "{}", token = "salah-$t")
            assertEquals("tetap 401 dengan token salah", 401, tokenSalah)

            val (tokenBenar, _) = httpPost("/api/v1/trading/buy", "{}", token = t)
            assertTrue("token benar tidak boleh 401, dapat $tokenBenar", tokenBenar != 401)

            // Rute baca publik tidak boleh ikut menuntut token.
            val (healthCode, _) = httpGet("/health")
            assertEquals("health tanpa token tetap terbuka", 200, healthCode)

            server.stop()
            server.awaitSettled(10_000)
        }
    }
}

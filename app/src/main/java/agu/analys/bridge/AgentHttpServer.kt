package agu.analys.bridge

import android.content.Context
import agu.analys.util.AppLogManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Keadaan server bridge. UI **wajib** membaca ini, bukan menebak dari nilai sakelar:
 * sakelar bisa hidup sementara server gagal menyala (port dipakai, kelas hilang, dsb.).
 */
sealed interface AgentBridgeServerState {
    data object Stopped : AgentBridgeServerState
    data object Starting : AgentBridgeServerState
    data object Running : AgentBridgeServerState
    data object Stopping : AgentBridgeServerState
    data class Failed(val reason: String) : AgentBridgeServerState
}

/**
 * Server baca-saja Agent Bridge V3 (HTTP polling + WebSocket + SSE + data pasar).
 *
 * Tiga hal yang tidak boleh diubah tanpa alasan ukur:
 *
 * 1. **Server dinyalakan/dimatikan di luar main thread.** `embeddedServer(...).start()` membuka
 *    socket; mengerjakannya di main thread memicu `NetworkOnMainThreadException`/ANR saat sakelar
 *    di Pengaturan ditekan. Karena itu `start()`/`stop()` hanya menjadwalkan pekerjaan dan
 *    langsung kembali; hasilnya dilaporkan lewat [state].
 * 2. **Semua kegagalan ditangkap sebagai `Throwable`.** Kegagalan yang benar-benar pernah terjadi
 *    adalah `NoSuchMethodError` (konflik versi kotlinx-coroutines dengan mesin Ktor lama). Itu
 *    turunan `Error`, bukan `Exception`, sehingga `catch (e: Exception)` tidak menangkapnya dan
 *    aplikasi langsung tertutup saat sakelar dinyalakan. Sekarang kegagalan dilaporkan sebagai
 *    `Failed` dan sakelar dikembalikan ke posisi mati.
 * 3. **Tidak ada `java.time.Duration` di berkas ini.** `minSdk` aplikasi 24, sedangkan `java.time`
 *    baru ada di API 26; memakainya membuat sakelar ini menutup aplikasi di Android 7. Opsi
 *    WebSocket memakai `kotlin.time.Duration` dari Ktor 3.
 */
class AgentHttpServer(private val context: Context) {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /**
     * Satu thread khusus untuk menyalakan/mematikan server, supaya urutan permintaan pengguna
     * tidak saling menyalip (mis. nyalakan lalu cepat matikan).
     */
    private val lifecycleExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "agent-bridge-lifecycle").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + lifecycleExecutor.asCoroutineDispatcher())

    private val _state = MutableStateFlow<AgentBridgeServerState>(AgentBridgeServerState.Stopped)
    val state: StateFlow<AgentBridgeServerState> = _state.asStateFlow()

    /** Naik setiap kali pengguna meminta berhenti; dipakai membatalkan penyalaan yang belum tuntas. */
    @Volatile
    private var generation = 0

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private val bridge by lazy { AgentOutputBridge.getInstance(context) }

    @Synchronized
    fun start() {
        when (_state.value) {
            is AgentBridgeServerState.Running,
            is AgentBridgeServerState.Starting -> return
            is AgentBridgeServerState.Stopping -> {
                // Mulai diminta sementara server masih mematikan diri: jangan dibuang.
                // Permintaan ini dijadwalkan setelah pekerjaan berhenti selesai.
            }
            else -> Unit
        }
        val gen = ++generation
        _state.value = AgentBridgeServerState.Starting
        scope.launch { boot(gen) }
    }

    @Synchronized
    fun stop() {
        if (_state.value is AgentBridgeServerState.Stopping) return
        generation++
        val engine = server
        server = null
        if (engine == null) {
            _state.value = AgentBridgeServerState.Stopped
            return
        }
        _state.value = AgentBridgeServerState.Stopping
        scope.launch {
            runCatching { engine.stop(1_000, 2_000) }
            _state.value = AgentBridgeServerState.Stopped
            AppLogManager.info("AgentHttpServer", "Bridge V3 stopped")
        }
    }

    /** Menunggu keadaan akhir (Running/Failed/Stopped). Dipakai uji dan sakelar Pengaturan. */
    suspend fun awaitSettled(timeoutMillis: Long = 15_000): AgentBridgeServerState =
        withTimeoutOrNull(timeoutMillis) {
            state.first {
                it is AgentBridgeServerState.Running ||
                    it is AgentBridgeServerState.Failed ||
                    it is AgentBridgeServerState.Stopped
            }
        } ?: _state.value

    val isRunning: Boolean get() = _state.value is AgentBridgeServerState.Running

    /**
     * Gerbang keamanan endpoint tulis rute `api/v1/trading` (2026-11-06).
     *
     * Detak masuk wajib membawa [HEADER_TOKEN] yang cocok dengan token di
     * `AppPreferences.agentBridgeToken` (berkas token diinstal ke Termux bersama
     * `index.ts` lewat `OmpBridgeInstaller`). Bila token server kosong — mis.
     * penyimpanan aman gagal dibaca — **semua** permintaan tulis ditolak
     * (fail-closed), bukan diterima tanpa token.
     */
    private suspend fun ApplicationCall.requireAgentToken(expected: String): Boolean {
        val provided = request.headers[HEADER_TOKEN]
        if (expected.isNotBlank() && provided != null && provided == expected) return true
        respondText(
            """{"success":false,"message":"Ditolak: header $HEADER_TOKEN tidak sesuai token bridge. Periksa berkas token yang diinstal bersama ekstensi OMP."}""",
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized
        )
        return false
    }

    /**
     * Ubah kegagalan tak terduga di endpoint menjadi respons JSON, bukan crash proses.
     *
     * Dipanggil dari pelindung pipeline. Pesan dibersihkan supaya tidak merusak JSON
     * (petik, backslash, newline). `available=false` sengaja dibawa agar agen tidak
     * mengira kegagalan endpoint pasar sebagai harga nol. Semua operasi di sini
     * dibungkus `runCatching`: bila respons sudah terlanjur terkirim sebagian,
     * upaya menulis lagi gagal dan harus ditelan, bukan dilempar ke thread server.
     */
    private suspend fun respondGuardFailure(call: ApplicationCall, t: Throwable) {
        runCatching {
            AppLogManager.error(
                "AgentHttpServer",
                "Endpoint bridge gagal — ${t.javaClass.simpleName}: ${t.message ?: "tanpa pesan"}",
                t
            )
        }
        val reason = (t.message ?: t.javaClass.simpleName)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
            .take(240)
        val type = t.javaClass.simpleName.replace("\"", "")
        runCatching {
            call.respondText(
                """{"success":false,"available":false,"error":"$reason","errorType":"$type"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }

    private fun boot(gen: Int) {
        try {
            val engine = embeddedServer(CIO, port = PORT, host = HOST) { installRoutes() }
            engine.start(wait = false)
            if (gen != generation) {
                runCatching { engine.stop(0, 500) }
                return
            }
            val probe = probeHealth()
            if (probe != null) {
                runCatching { engine.stop(0, 500) }
                _state.value = AgentBridgeServerState.Failed(
                    "Port $PORT tidak menjawab pemeriksaan kesehatan — $probe"
                )
                AppLogManager.error("AgentHttpServer", "Pemeriksaan kesehatan port $PORT gagal — $probe", null)
                return
            }
            server = engine
            _state.value = AgentBridgeServerState.Running
            // Pendorong portofolio hidup bersama server: agen yang baru tersambung tidak
            // perlu menunggu sinyal pertama untuk menerima snapshot posisi.
            runCatching { AgentOutputBridge.getInstance(context).startPortfolioPush() }
            AppLogManager.info(
                "AgentHttpServer",
                "Bridge $HOST:$PORT siap (polling + ws + sse + market data)"
            )
        } catch (t: Throwable) {
            server = null
            val reason = "${t.javaClass.simpleName}: ${t.message ?: "tanpa pesan"}"
            _state.value = AgentBridgeServerState.Failed(reason)
            AppLogManager.error("AgentHttpServer", "Gagal menyalakan Bridge V3 — $reason", t)
        }
    }

    /**
     * Bukti nyata bahwa port melayani, bukan sekadar "objek server sudah dibuat".
     * Dijalankan di thread lifecycle (bukan main thread), jadi boleh memblokir.
     *
     * Probe memakai koneksi **TCP** langsung, bukan `GET /health` melalui HTTP. Alasannya:
     * aplikasi ini tidak mengizinkan cleartext HTTP (`usesCleartextTraffic` tidak disetel),
     * sehingga permintaan HTTP ke `http://127.0.0.1:8765` diblokir kebijakan keamanan jaringan
     * dan probe lama selalu gagal — port sebenarnya sehat, tapi UI melaporkan "tidak menjawab".
     * TCP tidak terpengaruh kebijakan itu, dan cukup untuk memastikan server mendengarkan.
     *
     * @return `null` bila port menjawab, atau teks alasan bila tidak (untuk ditampilkan ke UI).
     */
    private fun probeHealth(attempts: Int = 25, delayMs: Long = 120): String? {
        var lastReason = "belum pernah mencoba"
        repeat(attempts) {
            val reason = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", PORT), 400)
                }
                null
            }.getOrElse { error ->
                // Simpan alasan asli agar tidak ditebak-tebak ("port dipakai" vs "diblokir").
                "${error.javaClass.simpleName}: ${error.message ?: "tanpa pesan"}"
            }
            if (reason == null) return null
            lastReason = reason
            Thread.sleep(delayMs)
        }
        return lastReason
    }

    private fun Application.installRoutes() {
        install(WebSockets) {
            pingPeriod = PING_PERIOD
            timeout = PING_TIMEOUT
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }

        // PELINDUNG GLOBAL (2026-11-12). Semua endpoint yang dipanggil agen lewat
        // satu titik ini. `catch (e: Exception)` TIDAK cukup: insiden lama berupa
        // `NoSuchMethodError` (turunan Error, bukan Exception) menembus handler dan
        // menutup aplikasi begitu agen memanggil bridge. Throwable ditangkap di sini
        // supaya kegagalan apa pun berubah menjadi JSON 500, bukan force close.
        // CancellationException dilempar kembali agar pembatalan koneksi tetap wajar.
        intercept(ApplicationCallPipeline.ApplicationPhase.Call) {
            try {
                proceed()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                respondGuardFailure(call, t)
            }
        }

        // Token dibaca sekali saat rute dipasang. Bila kosong, semua endpoint tulis
        // fail-closed (requireAgentToken menolak tanpa token yang cocok).
        val bridgeToken = runCatching {
            agu.analys.util.AppPreferences(context).agentBridgeToken
        }.getOrDefault("")
        lastInstalledBridgeToken = bridgeToken
        routing {
            // Manifest mesin: agen bisa menemukan seluruh kemampuan server tanpa
            // membaca dokumentasi Markdown. Kontraknya ada di AgentMarketModels.kt.
            get("/") {
                call.respondText(
                    json.encodeToString(AgentMarketDataProvider.capabilities()),
                    ContentType.Application.Json
                )
            }
            get("/health") {
                call.respondText(
                    """{"ok":true,"service":"${AgentMarketDataProvider.SERVICE_ID}","apiVersion":"${AgentMarketDataProvider.API_VERSION}","running":$isRunning,"ts":${System.currentTimeMillis()}}""",
                    ContentType.Application.Json
                )
            }
            // --- POLLING ENDPOINTS (fallback) ---
            get("/signals/latest") {
                val f = File(bridge.getDir(), "latest.json")
                if (f.exists()) {
                    call.respondText(f.readText(), ContentType.Application.Json, HttpStatusCode.OK)
                } else {
                    call.respondText("""{"error":"no signal yet"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                }
            }
            get("/signals/history") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val f = File(bridge.getDir(), "signals.jsonl")
                if (!f.exists()) {
                    call.respondText("[]", ContentType.Application.Json)
                    return@get
                }
                val arr = "[" + f.readLines().takeLast(limit).joinToString(",") + "]"
                call.respondText(arr, ContentType.Application.Json)
            }
            get("/portfolio") {
                val f = File(bridge.getDir(), "portfolio.json")
                if (f.exists()) {
                    call.respondText(f.readText(), ContentType.Application.Json, HttpStatusCode.OK)
                } else {
                    // Jika belum ada file di disk, bangun snapshot secara dinamis dari
                    // coordinator aktif. Kegagalan coordinator (belum terdaftar,
                    // StateFlow null, Error linkage) tidak boleh menembus handler.
                    val dynamicPortfolio = runCatching {
                        AgentTradingCommandHandler.buildPortfolioSnapshot(context)
                    }.onFailure { err ->
                        AppLogManager.error("AgentHttpServer", "Gagal bangun snapshot portofolio", err)
                    }.getOrNull()
                    if (dynamicPortfolio != null) {
                        val payload = json.encodeToString(dynamicPortfolio)
                        runCatching { f.writeText(payload) }
                        call.respondText(payload, ContentType.Application.Json, HttpStatusCode.OK)
                    } else {
                        call.respondText("""{"error":"no portfolio"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                    }
                }
            }

            // --- DATA PASAR UNTUK AGEN (2026-11-08) ---
            // Rute read-only & publik, sama seperti /signals dan /portfolio. Tujuannya:
            // agen mengambil harga/candle/depth/aturan simbol dari server LOKAL, bukan
            // lagi dari proxy eksternal yang bisa basi atau mati. Semua payload membawa
            // `available` + `error` saat gagal; tidak ada angka sintetis.
            get("/api/v1/capabilities") {
                call.respondText(
                    json.encodeToString(AgentMarketDataProvider.capabilities()),
                    ContentType.Application.Json
                )
            }
            get("/api/v1/market/time") {
                call.respondText(
                    json.encodeToString(AgentMarketDataProvider.time()),
                    ContentType.Application.Json
                )
            }
            get("/api/v1/market/ticker") {
                val symbol = call.request.queryParameters["symbol"].orEmpty()
                call.respondText(
                    json.encodeToString(AgentMarketDataProvider.ticker(symbol)),
                    ContentType.Application.Json
                )
            }
            get("/api/v1/market/klines") {
                val symbol = call.request.queryParameters["symbol"].orEmpty()
                val interval = call.request.queryParameters["interval"] ?: "1m"
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 120
                call.respondText(
                    json.encodeToString(AgentMarketDataProvider.klines(symbol, interval, limit)),
                    ContentType.Application.Json
                )
            }
            get("/api/v1/market/depth") {
                val symbol = call.request.queryParameters["symbol"].orEmpty()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                call.respondText(
                    json.encodeToString(AgentMarketDataProvider.depth(symbol, limit)),
                    ContentType.Application.Json
                )
            }
            get("/api/v1/market/symbols") {
                val quote = call.request.queryParameters["quote"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
                call.respondText(
                    json.encodeToString(AgentMarketDataProvider.symbols(quote, limit)),
                    ContentType.Application.Json
                )
            }
            get("/api/v1/market/context") {
                val symbol = call.request.queryParameters["symbol"].orEmpty()
                val interval = call.request.queryParameters["interval"] ?: "1m"
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 120
                call.respondText(
                    json.encodeToString(
                        AgentMarketDataProvider.context(context, symbol, interval, limit)
                    ),
                    ContentType.Application.Json
                )
            }
            get("/api/v1/engine/analysis") {
                val symbol = call.request.queryParameters["symbol"].orEmpty()
                call.respondText(
                    json.encodeToString(
                        AgentMarketDataProvider.engineAnalysis(context, symbol)
                    ),
                    ContentType.Application.Json
                )
            }
            get("/api/v1/market/analysis") {
                val symbol = call.request.queryParameters["symbol"].orEmpty()
                call.respondText(
                    json.encodeToString(
                        AgentMarketDataProvider.engineAnalysis(context, symbol)
                    ),
                    ContentType.Application.Json
                )
            }

            // --- AGENT-ASSISTED TRADING ENDPOINTS (V3) ---
            route("/api/v1/trading") {
                // Daftar open order untuk rekonsiliasi agen. Harus dideklarasikan
                // SEBELUM /orders/{id} agar rute statis tidak tertelan parameter.
                get("/orders") {
                    if (!call.requireAgentToken(bridgeToken)) return@get
                    val symbol = call.request.queryParameters["symbol"]
                    val isSim = call.request.queryParameters["isSimulation"]?.toBoolean() ?: false
                    val ex = call.request.queryParameters["exchange"] ?: "TOKOCRYPTO"
                    val resp = AgentTradingCommandHandler.handleListOpenOrders(symbol, isSim, ex, context)
                    call.respondText(json.encodeToString(resp), ContentType.Application.Json)
                }
                get("/status") {
                    if (!call.requireAgentToken(bridgeToken)) return@get
                    val status = AgentTradingCommandHandler.getTradingStatus(context)
                    call.respondText(json.encodeToString(status), ContentType.Application.Json)
                }
                post("/buy") {
                    if (!call.requireAgentToken(bridgeToken)) return@post
                    try {
                        val body = call.receiveText()
                        val req = json.decodeFromString<AgentTradeRequest>(body)
                        val resp = AgentTradingCommandHandler.handleBuy(req, context)
                        call.respondText(
                            json.encodeToString(resp),
                            ContentType.Application.Json,
                            if (resp.success) HttpStatusCode.OK else HttpStatusCode.BadRequest
                        )
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Throwable) {
                        AppLogManager.error("AgentHttpServer", "Error parsing /api/v1/trading/buy", e)
                        call.respondText(
                            """{"success":false,"message":"Payload order BUY tidak valid: ${e.message}"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                    }
                }
                post("/sell") {
                    if (!call.requireAgentToken(bridgeToken)) return@post
                    try {
                        val body = call.receiveText()
                        val req = json.decodeFromString<AgentTradeRequest>(body)
                        val resp = AgentTradingCommandHandler.handleSell(req, context)
                        call.respondText(
                            json.encodeToString(resp),
                            ContentType.Application.Json,
                            if (resp.success) HttpStatusCode.OK else HttpStatusCode.BadRequest
                        )
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Throwable) {
                        AppLogManager.error("AgentHttpServer", "Error parsing /api/v1/trading/sell", e)
                        call.respondText(
                            """{"success":false,"message":"Payload order SELL tidak valid: ${e.message}"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                    }
                }
                post("/cancel") {
                    if (!call.requireAgentToken(bridgeToken)) return@post
                    try {
                        val body = call.receiveText()
                        val req = json.decodeFromString<AgentCancelRequest>(body)
                        val resp = AgentTradingCommandHandler.handleCancel(req, context)
                        call.respondText(
                            json.encodeToString(resp),
                            ContentType.Application.Json,
                            if (resp.success) HttpStatusCode.OK else HttpStatusCode.BadRequest
                        )
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Throwable) {
                        AppLogManager.error("AgentHttpServer", "Error parsing /api/v1/trading/cancel", e)
                        call.respondText(
                            """{"success":false,"message":"Payload pembatalan tidak valid: ${e.message}"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                    }
                }
                get("/orders/{id}") {
                    if (!call.requireAgentToken(bridgeToken)) return@get
                    val orderId = call.parameters["id"] ?: ""
                    val symbol = call.request.queryParameters["symbol"]
                    val isSim = call.request.queryParameters["isSimulation"]?.toBoolean() ?: false
                    val ex = call.request.queryParameters["exchange"] ?: "TOKOCRYPTO"
                    val resp = AgentTradingCommandHandler.handleGetOrder(orderId, symbol, isSim, ex, context)
                    call.respondText(
                        json.encodeToString(resp),
                        ContentType.Application.Json,
                        if (resp.success) HttpStatusCode.OK else HttpStatusCode.NotFound
                    )
                }
            }

            // --- PUSH ENDPOINTS ---
            // 1. WebSocket realtime
            webSocket("/ws/signals") {
                val remote = runCatching { call.request.origin.remoteHost }.getOrDefault("client")
                AppLogManager.info("AgentWS", "Client connected: $remote")
                try {
                    // Kirim snapshot sinyal terakhir jika ada
                    bridge.getLatest()?.let {
                        send(Frame.Text(json.encodeToString(it)))
                    }
                    // Push setiap ada sinyal baru
                    AgentEventBus.signals.collect { signal ->
                        send(Frame.Text(json.encodeToString(signal)))
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Throwable) {
                    AppLogManager.error("AgentWS", "WS error", e)
                } finally {
                    AppLogManager.info("AgentWS", "Client disconnected")
                }
            }
            // 2. Server-Sent Events (SSE) untuk client HTTP streaming (cukup curl -N)
            get("/events") {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.response.header(HttpHeaders.Connection, "keep-alive")
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    try {
                        write("event: ping\ndata: {\"ts\":${System.currentTimeMillis()}}\n\n")
                        flush()
                        // Sinyal dan portofolio diberi label per jenis supaya client bisa
                        // menyaring tanpa membuka koneksi kedua (2026-11-06).
                        merge(AgentEventBus.signals, AgentEventBus.portfolio).collect { item ->
                            when (item) {
                                is AgentSignalOutput -> {
                                    write("event: signal\n")
                                    write("data: ${json.encodeToString(item)}\n\n")
                                }
                                is AgentPortfolioOutput -> {
                                    write("event: portfolio\n")
                                    write("data: ${json.encodeToString(item)}\n\n")
                                }
                            }
                            flush()
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Throwable) {
                        AppLogManager.error("AgentSSE", "SSE error", e)
                    }
                }
            }
            // 3. WebSocket portofolio — snapshot awal dari berkas (replay bus = 1) lalu push
            webSocket("/ws/portfolio") {
                try {
                    AgentEventBus.portfolio.replayCache.lastOrNull()?.let {
                        send(Frame.Text(json.encodeToString(it)))
                    }
                    AgentEventBus.portfolio.collect { snapshot ->
                        send(Frame.Text(json.encodeToString(snapshot)))
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Throwable) {
                    AppLogManager.error("AgentWS", "WS portfolio error", e)
                }
            }
        }
    }

    companion object {
        const val PORT = 8765

        /**
         * Hanya loopback (2026-11-06). Sebelumnya `0.0.0.0` membuat port 8765 tebuka ke
         * seluruh LAN — termasuk `POST /api/v1/trading/buy` — sementara dokumentasi lama
         * mengklaim mengikat `127.0.0.1`. Ekstensi OMP memanggil `127.0.0.1` di perangkat
         * yang sama, jadi loopback menghilangkan kemampuan itu tanpa biaya. Lihat
         * `AGENT_BRIDGE.md` untuk kontrak keamanan terkini.
         */
        const val HOST = "127.0.0.1"

        /** Header token untuk endpoint tulis rute `api/v1/trading`. */
        const val HEADER_TOKEN = "X-Agent-Token"

        private val PING_PERIOD = 15.seconds
        private val PING_TIMEOUT = 15.seconds

        /**
         * Token yang terakhir terpasang di rute (hanya untuk uji). Di produksi token itu
         * memang berasal dari `AppPreferences.agentBridgeToken`; uji Robolectric tidak bisa
         * membaca dua instance Application berbeda, jadi [AgentBridgeServerTest] membaca
         * dari sini supaya yang dibandingkan tokennya yang server pakai sendiri.
         */
        @Volatile
        var lastInstalledBridgeToken: String? = null
            private set

        @Volatile
        private var INSTANCE: AgentHttpServer? = null

        fun getInstance(ctx: Context): AgentHttpServer =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentHttpServer(ctx.applicationContext).also { INSTANCE = it }
            }
    }
}

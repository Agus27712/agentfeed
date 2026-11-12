package smoke

import agu.analys.bridge.*
import android.content.Context
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Token sama persis dengan stub `agu/analys/util/AppPreferences.kt`. */
private const val TOKEN = "smoke-bridge-token"

fun main() {
    val context = Context()
    val bridge = AgentOutputBridge.getInstance(context)
    File(bridge.getDir(), "latest.json").writeText("{\"fixture\":\"latest\"}")
    File(bridge.getDir(), "portfolio.json").writeText("{\"fixture\":\"portfolio\"}")
    File(bridge.getDir(), "signals.jsonl").writeText("{\"id\":1}\n{\"id\":2}\n")
    val server = AgentHttpServer.getInstance(context)
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun request(
        path: String,
        withToken: Boolean = false,
        method: String = "GET",
        body: String? = null
    ): HttpRequest {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:8765$path"))
            .timeout(Duration.ofSeconds(10))
        if (withToken) b.header("X-Agent-Token", TOKEN)
        if (body != null) b.header("Content-Type", "application/json")
        when (method) {
            "POST" -> b.POST(HttpRequest.BodyPublishers.ofString(body ?: "{}"))
            else -> b.GET()
        }
        return b.build()
    }

    /** @return pasangan (status HTTP, body). Tidak memaksa status 200. */
    fun raw(
        path: String,
        withToken: Boolean = false,
        method: String = "GET",
        body: String? = null
    ): Pair<Int, String> {
        val r = client.send(request(path, withToken, method, body), HttpResponse.BodyHandlers.ofString())
        return r.statusCode() to r.body()
    }

    fun get(path: String): String {
        val (status, text) = raw(path)
        check(status == 200) { "$path: status $status, body=$text" }
        return text
    }

    fun awaitHealth() {
        var failure: Exception? = null
        repeat(100) {
            try { check(get("/health").contains("\"ok\":true")); return }
            catch (e: Exception) { failure = e; Thread.sleep(100) }
        }
        throw IllegalStateException("Server did not become healthy", failure)
    }

    var ws: WebSocket? = null
    var sse: java.io.InputStream? = null
    try {
        server.start()
        awaitHealth()
        check(server.isRunning)

        // --- Discovery & manifest --------------------------------------------
        check(get("/").contains("agentfeed-bridge-v3"))
        check(get("/").contains("2026-11-08"))
        check(get("/api/v1/capabilities").contains("/api/v1/market/context"))
        check(get("/health").contains("\"apiVersion\":\"2026-11-08\""))

        // --- Endpoint data pasar baru (kontrak produksi, angka SMOKE_FIXTURE) -
        check(get("/api/v1/market/time").contains("serverTime"))
        check(get("/api/v1/market/ticker?symbol=BTCUSDT").contains("\"available\":true"))
        check(get("/api/v1/market/ticker?symbol=BTCUSDT").contains("SMOKE_FIXTURE"))
        check(get("/api/v1/market/klines?symbol=BTCUSDT&interval=1m&limit=60").contains("\"candles\""))
        check(get("/api/v1/market/depth?symbol=BTCUSDT&limit=20").contains("\"bids\""))
        check(get("/api/v1/market/symbols?quote=USDT&limit=10").contains("\"symbols\""))
        check(get("/api/v1/market/context?symbol=BTCUSDT&interval=1m&limit=60").contains("\"market_context\""))
        check(get("/api/v1/market/context?symbol=BTCUSDT").contains("SMOKE_FIXTURE"))
        check(get("/api/v1/engine/analysis?symbol=BTCUSDT").contains("SMOKE_FIXTURE"))
        check(get("/api/v1/engine/analysis?symbol=BTCUSDT").contains("BULLISH_CONFLUENCE"))

        // --- Gerbang token fail-closed pada rute /api/v1/trading/* -----------
        check(raw("/api/v1/trading/orders").first == 401) { "GET /orders tanpa token harus 401" }
        check(raw("/api/v1/trading/status").first == 401) { "GET /status tanpa token harus 401" }
        check(raw("/api/v1/trading/buy", method = "POST", body = "{\"symbol\":\"BTCUSDT\",\"side\":\"BUY\"}").first == 401)
        val (ordersStatus, ordersBody) = raw("/api/v1/trading/orders", withToken = true)
        check(ordersStatus == 200 && ordersBody.contains("\"orders\"")) { "GET /orders ber-token: $ordersStatus $ordersBody" }
        val (statusCode, statusBody) = raw("/api/v1/trading/status", withToken = true)
        check(statusCode == 200 && statusBody.contains("apiVersion")) { "GET /status ber-token: $statusCode $statusBody" }

        // --- Endpoint lama tetap berfungsi -----------------------------------
        check(get("/signals/latest").contains("latest"))
        check(get("/portfolio").contains("portfolio"))
        check(get("/signals/history?limit=1") == "[{\"id\":2}]")

        // --- WebSocket sinyal -------------------------------------------------
        val messages = LinkedBlockingQueue<String>()
        ws = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(
            URI("ws://127.0.0.1:8765/ws/signals"), object : WebSocket.Listener {
                private val text = StringBuilder()
                override fun onOpen(socket: WebSocket) { socket.request(1) }
                override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*>? {
                    text.append(data)
                    if (last) { messages.add(text.toString()); text.setLength(0) }
                    socket.request(1)
                    return null
                }
            }
        ).get(10, TimeUnit.SECONDS)
        check(messages.poll(10, TimeUnit.SECONDS)?.contains("SNAPSHOT") == true)

        // --- SSE --------------------------------------------------------------
        val stream = client.send(request("/events"), HttpResponse.BodyHandlers.ofInputStream())
        check(stream.statusCode() == 200)
        check(stream.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"))
        sse = stream.body()
        val reader = sse.bufferedReader()
        fun line(): String = CompletableFuture.supplyAsync { reader.readLine() }.get(10, TimeUnit.SECONDS)
        check(line() == "event: ping")
        check(line().startsWith("data: "))
        check(line().isEmpty())
        runBlocking { AgentEventBus.emitSignal(bridge.snapshot.copy(symbol = "PUSH")) }
        check(messages.poll(10, TimeUnit.SECONDS)?.contains("PUSH") == true)
        check(line() == "event: signal")
        check(line().contains("PUSH"))
        ws.abort(); ws = null
        sse.close(); sse = null

        // --- Stop / restart ---------------------------------------------------
        server.stop()
        check(!server.isRunning)
        runBlocking { server.awaitSettled(20_000) }
        server.start()
        awaitHealth()
        println("PASS: AgentHttpServer V3 — manifest, 8 endpoint data pasar, fail-closed 401, HTTP lama, WebSocket, SSE, stop/restart")
    } finally {
        ws?.abort()
        sse?.close()
        server.stop()
        bridge.getDir().deleteRecursively()
    }
}

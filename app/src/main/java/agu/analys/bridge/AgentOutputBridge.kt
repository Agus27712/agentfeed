package agu.analys.bridge

import android.content.Context
import android.os.Environment
import agu.analys.util.AppLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class AgentOutputBridge(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jsonPretty = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonCompact = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Direktori output. Dulu blok `catch` memang lagi `getExternalFilesDir` yang
     * sama persis dengan yang baru gagal, sehingga kegagalan kedua tidak tertangkap
     * dan bisa menutup aplikasi saat agen memanggil `/signals` atau `/portfolio`.
     * Sekarang selalu ada fallback internal (`filesDir`) yang dijamin ada, dan
     * `runCatching` menangkap `Throwable` — termasuk `Error`, bukan cuma Exception.
     */
    private val outputDir: File by lazy {
        val internal = File(context.applicationContext.filesDir, "agentfeed-output")
            .apply { runCatching { mkdirs() } }
        runCatching {
            val docDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val target = File(docDir, "agentfeed/output")
            if (target.exists() || target.mkdirs()) target else internal
        }.getOrDefault(internal)
    }

    private val latestRef = AtomicReference<AgentSignalOutput?>()

    /** Penjaga agar loop pendorong portofolio tidak berlipat saat server dinyalakan ulang. */
    private val portfolioPushStarted = java.util.concurrent.atomic.AtomicBoolean(false)

	fun isEnabled(): Boolean {
		return runCatching {
			agu.analys.util.AppPreferences(context).isAgentOutputEnabled
		}.getOrDefault(false)
	}

    fun emitSignal(signal: AgentSignalOutput) {
        if (!isEnabled()) {
            AppLogManager.info(
                "AgentBridge",
                "SKIP emit (agent_output_enabled_false) ${signal.symbol}"
            )
            return
        }
        try {
            latestRef.set(signal)

            // 1. Simpan ke berkas lokal (signals.jsonl + latest.json) untuk fallback polling HTTP
            scope.launch {
                runCatching {
                    val line = jsonCompact.encodeToString(signal)
                    File(outputDir, "signals.jsonl").appendText(line + "\n")
                    File(outputDir, "latest.json").writeText(jsonPretty.encodeToString(signal))
                    maintainRolling(1000)
                }.onFailure { err ->
                    AppLogManager.error("AgentBridge", "Gagal simpan sinyal ke berkas lokal", err)
                }
            }

            // 2. Pancarkan realtime ke EventBus untuk WebSocket & SSE push
            scope.launch {
                AgentEventBus.emitSignal(signal)
            }

            AppLogManager.info("AgentBridge", "EMIT ${signal.symbol} ${signal.confidence}% -> file+ws")
        } catch (e: Throwable) {
            AppLogManager.error("AgentBridge", "emitSignal failed", e)
        }
    }

    fun emitPortfolio(portfolio: AgentPortfolioOutput) {
        if (!isEnabled()) return
        try {
            scope.launch {
                runCatching {
                    File(outputDir, "portfolio.json").writeText(jsonPretty.encodeToString(portfolio))
                }.onFailure { err ->
                    AppLogManager.error("AgentBridge", "Gagal simpan portofolio ke berkas lokal", err)
                }
                AgentEventBus.emitPortfolio(portfolio)
            }
            AppLogManager.info("AgentBridge", "EMIT portfolio (${portfolio.exchange}) -> file+ws")
        } catch (e: Throwable) {
            AppLogManager.error("AgentBridge", "emitPortfolio failed", e)
        }
    }

    fun getLatest(): AgentSignalOutput? = latestRef.get()

    /**
     * Mulai pendorong snapshot portofolio (2026-11-06, idempoten — hanya loop pertama yang
     * jalan). Dipanggil saat server bridge menyala; loop berhenti sendiri bila sakelar
     * mati karena setiap putaran memeriksa [isEnabled].
     *
     * Kenapa tiap [AgentPortfolioPusher.EVAL_INTERVAL_MS] dan bukan tiap tick harga:
     * snapshot dibangun dari cache posisi/harga yang sudah diperbarui di tempat lain;
     * push tiap detik hanya membanjiri agen dengan angka yang hampir sama. Perubahan isi
     * (atau heartbeat 5 menit) sudah cukup.
     */
    fun startPortfolioPush() {
        if (!portfolioPushStarted.compareAndSet(false, true)) return
        scope.launch {
            var previousSignature: String? = null
            var lastEmitMs = 0L
            while (isActive) {
                runCatching {
                    if (isEnabled()) {
                        val snapshot = AgentTradingCommandHandler.buildPortfolioSnapshot(context)
                        if (snapshot != null) {
                            val signature = AgentPortfolioPusher.signature(snapshot)
                            val now = System.currentTimeMillis()
                            if (AgentPortfolioPusher.shouldEmit(previousSignature, signature, now, lastEmitMs)) {
                                emitPortfolio(snapshot)
                                previousSignature = signature
                                lastEmitMs = now
                            }
                        }
                    }
                }.onFailure { err ->
                    AppLogManager.error("AgentBridge", "Gagal membangun snapshot portofolio", err)
                }
                delay(AgentPortfolioPusher.EVAL_INTERVAL_MS)
            }
        }
        AppLogManager.info("AgentBridge", "Portofolio push dimulai (interval ${AgentPortfolioPusher.EVAL_INTERVAL_MS} ms)")
    }

    fun getDir(): File = outputDir

    private fun maintainRolling(max: Int) {
        try {
            val f = File(outputDir, "signals.jsonl")
            if (!f.exists()) return
            val lines = f.readLines()
            if (lines.size > max) {
                f.writeText(lines.takeLast(max).joinToString("\n") + "\n")
            }
        } catch (_: Throwable) {}
    }

    companion object {
        @Volatile private var INSTANCE: AgentOutputBridge? = null

        fun getInstance(ctx: Context): AgentOutputBridge =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentOutputBridge(ctx.applicationContext).also { INSTANCE = it }
            }
    }
}

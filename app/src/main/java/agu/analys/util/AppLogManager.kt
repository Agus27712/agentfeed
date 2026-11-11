package agu.analys.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import agu.analys.AppContextProvider
import agu.analys.BuildConfig
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionStore
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LogCategory(val displayName: String, val emoji: String) {
    ALL("Semua Log", "📋"),
    TRAILING("Trailing Stop", "🛡️"),
    TRADE("Trading & Order", "💼"),
    MARKET("Market & Data", "📈"),
    AI_ENGINE("AI & Sinyal", "🤖"),
    SERVICE("Background Service", "⚙️"),
    ERROR("Error & Warning", "⚠️"),
    SYSTEM_LOGCAT("Android Logcat", "📜")
}

data class AppLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val priority: Int,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    val category: LogCategory = LogCategory.ALL
) {
    val levelName: String get() = when (priority) {
        Log.VERBOSE -> "VERBOSE"
        Log.DEBUG -> "DEBUG"
        Log.INFO -> "INFO"
        Log.WARN -> "WARN"
        Log.ERROR -> "ERROR"
        Log.ASSERT -> "ASSERT"
        else -> "LOG"
    }

    val formattedTime: String get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
}

object AppLogManager {
    private const val MAX_LOGS = 2000
    private val logBuffer = ConcurrentLinkedDeque<AppLogEntry>()
    private val _logVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val logVersion: kotlinx.coroutines.flow.StateFlow<Long> = _logVersion

    init {
        // Record authentic system startup diagnostic info
        val bootTime = System.currentTimeMillis()
        val runtime = Runtime.getRuntime()
        val maxMemMb = runtime.maxMemory() / (1024 * 1024)
        val totalMemMb = runtime.totalMemory() / (1024 * 1024)
        val freeMemMb = runtime.freeMemory() / (1024 * 1024)
        val osVersion = android.os.Build.VERSION.RELEASE
        val sdkInt = android.os.Build.VERSION.SDK_INT
        val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        logBuffer.add(
            AppLogEntry(
                timestamp = bootTime,
                priority = Log.INFO,
                tag = "SystemInit",
                message = "nadi agentfeed v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) siap. Device: $deviceModel (Android $osVersion, SDK $sdkInt) | RAM: ${freeMemMb}MB free / ${totalMemMb}MB alloc / ${maxMemMb}MB max",
                category = LogCategory.SERVICE
            )
        )
    }

    val timberTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val safeTag = tag ?: "AnalysApp"
            val category = resolveCategory(safeTag, message, priority, t)
            val entry = AppLogEntry(
                timestamp = System.currentTimeMillis(),
                priority = priority,
                tag = safeTag,
                message = message,
                throwable = t,
                category = category
            )
            appendEntry(entry)
        }
    }

    private fun appendEntry(entry: AppLogEntry) {
        logBuffer.add(entry)
        while (logBuffer.size > MAX_LOGS) {
            logBuffer.pollFirst()
        }
        _logVersion.value++
    }

    fun resolveCategory(tag: String, message: String, priority: Int = Log.INFO, throwable: Throwable? = null): LogCategory {
        val lower = "$tag $message".lowercase()
        return when {
            priority >= Log.WARN || throwable != null || lower.contains("error") || lower.contains("fail") || lower.contains("gagal") || lower.contains("exception") || lower.contains("terjadi kesalahan") -> LogCategory.ERROR
            lower.contains("trailing") || lower.contains("peak") || lower.contains("profit-lock") || lower.contains("step-tier") || lower.contains("slprice") || lower.contains("stop-loss") || lower.contains("tp-target") || lower.contains("sl-price") || lower.contains("sl_price") -> LogCategory.TRAILING
            lower.contains("order") || lower.contains("buy") || lower.contains("sell") || lower.contains("trade") || lower.contains("wallet") || lower.contains("pos") || lower.contains("position") || lower.contains("balance") || lower.contains("execution") || lower.contains("eksekusi") || lower.contains("holding") || lower.contains("profit") || lower.contains("pnl") -> LogCategory.TRADE
            lower.contains("gemini") || lower.contains("prompt") || lower.contains("signal") || lower.contains("ai") || lower.contains("screener") || lower.contains("sentiment") || lower.contains("evaluator") || lower.contains("indicator") -> LogCategory.AI_ENGINE
            lower.contains("service") || lower.contains("worker") || lower.contains("job") || lower.contains("notification") || lower.contains("schedule") || lower.contains("background") || lower.contains("scan") || lower.contains("boot") || lower.contains("init") -> LogCategory.SERVICE
            lower.contains("ticker") || lower.contains("candle") || lower.contains("tokocrypto") || lower.contains("market") || lower.contains("poll") || lower.contains("fetch") || lower.contains("api") || lower.contains("http") || lower.contains("connection") || lower.contains("koneksi") || lower.contains("ws") || lower.contains("price") || lower.contains("orderbook") || lower.contains("feed") -> LogCategory.MARKET
            else -> LogCategory.ALL
        }
    }

    fun log(category: LogCategory, priority: Int, tag: String, message: String, t: Throwable? = null) {
        val entry = AppLogEntry(
            timestamp = System.currentTimeMillis(),
            priority = priority,
            tag = tag,
            message = message,
            throwable = t,
            category = category
        )
        appendEntry(entry)
    }

    // Typed Convenience Logging APIs
    fun market(tag: String, message: String) = log(LogCategory.MARKET, Log.INFO, tag, message)
    fun trade(tag: String, message: String) = log(LogCategory.TRADE, Log.INFO, tag, message)
    fun trailing(tag: String, message: String) = log(LogCategory.TRAILING, Log.INFO, tag, message)
    fun aiEngine(tag: String, message: String) = log(LogCategory.AI_ENGINE, Log.INFO, tag, message)
    fun service(tag: String, message: String) = log(LogCategory.SERVICE, Log.INFO, tag, message)
    fun error(tag: String, message: String, t: Throwable? = null) = log(LogCategory.ERROR, Log.ERROR, tag, message, t)
    fun warn(tag: String, message: String, t: Throwable? = null) = log(LogCategory.ERROR, Log.WARN, tag, message, t)
    fun info(tag: String, message: String, category: LogCategory = LogCategory.ALL) = log(category, Log.INFO, tag, message)

    fun getLogs(category: LogCategory = LogCategory.ALL, searchQuery: String = ""): List<AppLogEntry> {
        val all = logBuffer.toList()
        return all.filter { entry ->
            val matchesCategory = when (category) {
                LogCategory.ALL -> true
                LogCategory.ERROR -> entry.priority >= Log.WARN || entry.category == LogCategory.ERROR || entry.throwable != null || entry.message.contains("error", true) || entry.message.contains("gagal", true) || entry.message.contains("fail", true)
                LogCategory.SYSTEM_LOGCAT -> true
                else -> {
                    val resolved = if (entry.category != LogCategory.ALL) entry.category else resolveCategory(entry.tag, entry.message, entry.priority, entry.throwable)
                    entry.category == category || resolved == category
                }
            }
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                entry.message.contains(searchQuery, ignoreCase = true) ||
                        entry.tag.contains(searchQuery, ignoreCase = true) ||
                        entry.levelName.contains(searchQuery, ignoreCase = true)
            }
            matchesCategory && matchesSearch
        }.reversed()
    }

    fun clearLogs() {
        logBuffer.clear()
        _logVersion.value++
    }

    suspend fun fetchNativeLogcat(lines: Int = 400): List<String> = withContext(Dispatchers.IO) {
        val result = mutableListOf<String>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", lines.toString()))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { result.add(it) }
            }
            reader.close()
        } catch (e: Exception) {
            result.add("Gagal membaca logcat sistem: ${e.localizedMessage}")
        }
        result
    }

    fun buildDiagnosticStateDump(context: Context): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
        val nowStr = sdf.format(Date())

        sb.appendLine("=======================================================")
        sb.appendLine("📊 DIAGNOSTIK STATUS APLIKASI LENGKAP & LOGCAT EXPORT")
        sb.appendLine("=======================================================")
        sb.appendLine("Waktu Ekspor : $nowStr")
        sb.appendLine("Versi Aplikasi: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Package ID    : ${context.packageName}")
        sb.appendLine("Foreground App: ${AppContextProvider.isAppInForeground}")
        sb.appendLine()

        val posStore = SpotPositionStore(context)
        val allSymbols = posStore.getAllStoredSymbols()
        val allPositions = posStore.getAllPositions()
        val activeTrailing = posStore.getAllActiveTrailingSymbols()

        sb.appendLine("-------------------------------------------------------")
        sb.appendLine("🛡️ STATUS TRAILING STOP & SPOT POSITIONS (${allPositions.size} Koin Terdata)")
        sb.appendLine("-------------------------------------------------------")
        sb.appendLine("Koin dengan Trailing Aktif: ${if (activeTrailing.isEmpty()) "TIDAK ADA" else activeTrailing.joinToString(", ")}")
        sb.appendLine()

        if (allPositions.isEmpty()) {
            sb.appendLine("Belum ada posisi atau data koin yang tersimpan di SpotPositionStore.")
        } else {
            allPositions.forEach { (sym, pos) ->
                sb.appendLine("▶ KOIN: $sym")
                sb.appendLine("   - Status Holding      : ${pos.state} (isHolding=${pos.isHolding})")
                sb.appendLine("   - Tipe Akun           : ${if (pos.isReal) "AKUN RIIL (TOKOCRYPTO)" else "SIMULASI"}")
                sb.appendLine("   - Modal Terinvestasi  : Rp ${String.format(Locale.US, "%,.2f", pos.investedAmount)}")
                sb.appendLine("   - Harga Beli (Entry)  : Rp ${String.format(Locale.US, "%,.4f", pos.entryPrice)}")
                sb.appendLine("   - Kuantitas Aset      : ${pos.quantity}")
                sb.appendLine("   - Harga Tertinggi(Peak): Rp ${String.format(Locale.US, "%,.4f", pos.peakPrice)}")
                sb.appendLine("   - Trailing Enabled    : ${pos.isTrailingEnabled}")
                sb.appendLine("   - Base Trailing Pct   : ${pos.trailingPercent}%")
                sb.appendLine("   - Smart Tier Enabled  : ${pos.isTieredTrailingEnabled}")
                sb.appendLine("   - Active Trailing Pct : ${pos.activeTrailingPercent}%")
                sb.appendLine("   - Trailing Stop Price : Rp ${String.format(Locale.US, "%,.4f", pos.trailingStopPrice)}")
                sb.appendLine("   - Trailing Triggered  : ${pos.isTrailingTriggered}")
                sb.appendLine("   - Auto-Sell Target TP : TP1=Rp ${pos.tp1Price} (${pos.tp1Percent}%), TP2=Rp ${pos.tp2Price} (${pos.tp2Percent}%)")
                sb.appendLine("   - Status Trigger TP   : TP1=${pos.isTp1Triggered}, TP2=${pos.isTp2Triggered}")
                sb.appendLine("   - Last Order ID       : ${pos.lastTrailingOrderId ?: "NONE"}")
                sb.appendLine("   - Config Tier JSON    : ${pos.tieredConfigJson.ifBlank { "DEFAULT_TIERS" }}")
                sb.appendLine()
            }
        }

        sb.appendLine("-------------------------------------------------------")
        sb.appendLine("🗄️ RAW DATA PREFERENCES SPOT POSITION STORE (${posStore.dumpRawPrefs().size} Keys)")
        sb.appendLine("-------------------------------------------------------")
        posStore.dumpRawPrefs().toSortedMap().forEach { (k, v) ->
            sb.appendLine("$k = $v")
        }
        sb.appendLine()

        sb.appendLine("-------------------------------------------------------")
        sb.appendLine("📜 LOGCAT IN-MEMORY APPLICATION (${logBuffer.size} Baris Log)")
        sb.appendLine("-------------------------------------------------------")
        logBuffer.forEach { entry ->
            val exc = if (entry.throwable != null) " | Exception: ${Log.getStackTraceString(entry.throwable)}" else ""
            sb.appendLine("[${entry.formattedTime}] [${entry.levelName}] [${entry.category.name}] [${entry.tag}] ${entry.message}$exc")
        }

        return sb.toString()
    }

    fun copyToClipboard(context: Context, text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Analys Diagnostic Log", text)
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun exportAndShareLog(context: Context, content: String) {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "diagnostik_log_$timeStamp.txt"
            val cacheDir = context.cacheDir
            val file = File(cacheDir, fileName)
            file.writeText(content)

            val uri: Uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) {
                Uri.fromFile(file)
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Export Logcat & Diagnostik Analys App - $timeStamp")
                putExtra(Intent.EXTRA_TEXT, content.take(3000) + "\n\n(Selengkapnya terlampir di file log)")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "Bagikan Logcat & Diagnostik")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            // Fallback to text intent if file share fails
            val textIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Diagnostik Analys App")
                putExtra(Intent.EXTRA_TEXT, content)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(textIntent, "Bagikan Logcat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

package agu.analys.util

import android.util.Log
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Custom Timber Debug Tree that provides structured, high-traceability logging
 * for debug builds.
 *
 * Captures:
 * - High-resolution timestamp (`yyyy-MM-dd HH:mm:ss.SSS`)
 * - Thread identity (`[ThreadName]`)
 * - Caller source code metadata (`Class.method:line`)
 * - Automatic domain/event context inference (Market Data, Trailing Stop, Trade Orders, AI Engine, Background Service)
 * - Clean multi-line formatting and stack trace handling
 */
class StructuredDebugTree(
    private val appPrefix: String = "AnalysApp"
) : Timber.DebugTree() {

    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        }
    }

    override fun createStackElementTag(element: StackTraceElement): String {
        val simpleClassName = element.className.substringAfterLast('.')
            .substringBefore('$') // Strip anonymous inner classes
        val line = element.lineNumber
        return "$appPrefix:$simpleClassName:$line"
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val safeTag = tag ?: appPrefix
        val timestamp = dateFormat.get()?.format(Date()) ?: System.currentTimeMillis().toString()
        val threadName = Thread.currentThread().name
        val levelName = when (priority) {
            Log.VERBOSE -> "VRB"
            Log.DEBUG -> "DBG"
            Log.INFO -> "INF"
            Log.WARN -> "WRN"
            Log.ERROR -> "ERR"
            Log.ASSERT -> "AST"
            else -> "LOG"
        }

        val eventContext = extractEventContext(safeTag, message)
        val formattedHeader = "[$timestamp] [$levelName] [$threadName]$eventContext"

        // Handle multi-line messages with structured headers per line
        val formattedMessage = if (message.contains("\n")) {
            val lines = message.split("\n")
            lines.joinToString("\n") { line -> "$formattedHeader $line" }
        } else {
            "$formattedHeader $message"
        }

        super.log(priority, safeTag, formattedMessage, t)
    }

    /**
     * Inspects the log tag and message to categorize and append contextual metadata,
     * significantly improving traceability during live market processing, order executions,
     * and background worker telemetry.
     */
    private fun extractEventContext(tag: String, message: String): String {
        val combined = "$tag $message".lowercase()
        val contexts = mutableListOf<String>()

        // 1. Market Data & Feeds
        if (combined.contains("ws") || combined.contains("websocket")) {
            contexts.add("WS")
        }
        if (combined.contains("ticker") || combined.contains("tick") || combined.contains("price")) {
            contexts.add("TICKER")
        }
        if (combined.contains("candle") || combined.contains("kline") || combined.contains("timeframe")) {
            contexts.add("CANDLE")
        }
        if (combined.contains("orderbook") || combined.contains("depth") || combined.contains("bids") || combined.contains("asks")) {
            contexts.add("ORDERBOOK")
        }

        // 2. Trailing Stop & Risk Management
        if (combined.contains("trailing")) {
            contexts.add("TRAILING")
        }
        if (combined.contains("peak")) {
            contexts.add("PEAK")
        }
        if (combined.contains("stop-limit") || combined.contains("stop loss") || combined.contains("slprice") || combined.contains("cut loss")) {
            contexts.add("STOP_LOSS")
        }
        if (combined.contains("tp1") || combined.contains("tp2") || combined.contains("take profit")) {
            contexts.add("TAKE_PROFIT")
        }

        // 3. Trade Orders & Execution
        if (combined.contains("tokocrypto") && (combined.contains("order") || combined.contains("trade"))) {
            contexts.add("REAL_TRADE")
        } else if (combined.contains("simulasi") || combined.contains("simorder") || combined.contains("paper")) {
            contexts.add("SIM_TRADE")
        }

        // 4. AI & Signal Engine
        if (combined.contains("gemini")) {
            contexts.add("LLM_AI")
        }
        if (combined.contains("screener") || combined.contains("sentiment")) {
            contexts.add("SCREENER")
        }
        if (combined.contains("scalping") || combined.contains("swing") || combined.contains("intraday") || combined.contains("confluence")) {
            contexts.add("ENGINE_STRATEGY")
        }

        // 5. System & Background Worker
        if (combined.contains("candidatescan") || combined.contains("worker")) {
            contexts.add("WORKER")
        }
        if (combined.contains("foreground") || combined.contains("service")) {
            contexts.add("SERVICE")
        }

        return if (contexts.isNotEmpty()) {
            " [" + contexts.distinct().joinToString("|") + "]"
        } else {
            ""
        }
    }
}

package agu.analys.ui.components.chart

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import agu.analys.model.CandleBar
import org.json.JSONArray
import org.json.JSONObject

private const val CHART_TAG = "LwcChart"
private const val CHART_URL = "file:///android_asset/chart/lightweight_chart.html"
private const val CHART_BACKGROUND = "#0B0E14"

/** Jembatan JS -> Android. Error dari halaman chart dicatat ke Logcat. */
internal class ChartBridge {
    @JavascriptInterface
    fun onError(message: String) {
        Log.w(CHART_TAG, message)
    }
}

/**
 * Lightweight Charts 5.2.1 (Apache-2.0) — data murni dari candle Tokocrypto.
 * Library di-bundle lokal di assets, tidak memakai CDN.
 *
 * Kontrak stabilitas tampilan (2026-11-12):
 * - `AndroidView.update` **dilarang** mendorong data. Blok itu berjalan pada setiap
 *   rekomposisi Compose (termasuk tiap tick harga), sehingga `setCandles` lama
 *   me-reset zoom & scroll berkali-kali per detik.
 * - Data didorong hanya lewat [LaunchedEffect] yang kuncinya sudah distabilkan.
 * - `resetKey` menandai pergantian pair/timeframe; hanya saat itu chart boleh
 *   melakukan fit ulang. Perubahan harga live memakai `updateLast` (inkremental).
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "ClickableViewAccessibility")
@Composable
fun LightweightChartView(
    candles: List<CandleBar>,
    currentPrice: Double = 0.0,
    showVolume: Boolean = true,
    showEma: Boolean = false,
    showBb: Boolean = false,
    showStochRsi: Boolean = false,
    entryPrice: Double = 0.0,
    targetPrice1: Double = 0.0,
    targetPrice2: Double = 0.0,
    stopLoss: Double = 0.0,
    /** Berubah saat pair/timeframe/permintaan "Fit" berganti → chart boleh reset view. */
    resetKey: String = "",
    modifier: Modifier = Modifier
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    // Naik setiap kali WebView mati (render process crash) → Compose membuat WebView baru.
    var generation by remember { mutableStateOf(0) }
    // Reset terakhir yang sudah dikirim ke JS. Membedakan "ganti pair" dari "tick harga".
    var appliedResetKey by remember { mutableStateOf<String?>(null) }

    fun candlesToJson(list: List<CandleBar>): String {
        val arr = JSONArray()
        list.forEach { c ->
            if (c.open > 0 && c.high > 0 && c.low > 0 && c.close > 0) {
                arr.put(
                    JSONObject()
                        .put("t", c.timestamp)
                        .put("o", c.open)
                        .put("h", c.high)
                        .put("l", c.low)
                        .put("c", c.close)
                        .put("v", c.volume)
                )
            }
        }
        return arr.toString()
    }

    fun levelsJson(): String = JSONObject()
        .put("entry", entryPrice)
        .put("tp1", targetPrice1)
        .put("tp2", targetPrice2)
        .put("sl", stopLoss)
        .toString()

    fun pushData(wv: WebView, resetView: Boolean) {
        val json = candlesToJson(candles)
        // JSON sebagai object literal — tidak ada masalah escaping string.
        // Argumen kedua = true hanya saat dataset benar-benar baru.
        wv.evaluateJavascript("setCandles($json, $resetView)", null)
        wv.evaluateJavascript("setLevels(${levelsJson()})", null)
        wv.evaluateJavascript("resizeChart()", null)
    }

    LaunchedEffect(
        candles, entryPrice, targetPrice1, targetPrice2, stopLoss,
        pageReady, generation, resetKey
    ) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        val reset = appliedResetKey != resetKey
        appliedResetKey = resetKey
        pushData(wv, reset)
    }

    LaunchedEffect(showVolume, showEma, showBb, showStochRsi, pageReady, generation) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        val json = JSONObject()
            .put("volume", showVolume)
            .put("ema", showEma)
            .put("bb", showBb)
            .put("stoch", showStochRsi)
            .toString()
        wv.evaluateJavascript("setIndicators($json)", null)
    }

    LaunchedEffect(currentPrice, pageReady, generation) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady || currentPrice <= 0.0 || candles.isEmpty()) return@LaunchedEffect
        val last = candles.last()
        val h = maxOf(last.high, currentPrice)
        val l = minOf(last.low, currentPrice)
        val json = JSONObject()
            .put("t", last.timestamp)
            .put("o", last.open)
            .put("h", h)
            .put("l", l)
            .put("c", currentPrice)
            .put("v", last.volume)
            .toString()
        // Inkremental: tidak pernah menyentuh visible logical range.
        wv.evaluateJavascript("updateLast($json)", null)
    }

    key(generation) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // Software layer hanya untuk emulator (MESA GPU crash). Di perangkat
                    // nyata dipakai layer hardware agar chart tajam & gesture mulus.
                    val emulator = Build.FINGERPRINT.contains("generic", true) ||
                        Build.HARDWARE.contains("goldfish", true) ||
                        Build.HARDWARE.contains("ranchu", true)
                    setLayerType(
                        if (emulator) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_HARDWARE,
                        null
                    )
                    setBackgroundColor(Color.parseColor(CHART_BACKGROUND))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.allowFileAccess = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    // Zoom bawaan WebView dimatikan: pinch & scroll sepenuhnya
                    // ditangani Lightweight Charts lewat touch events.
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = false
                    // Cegah parent Compose menyita gesture drag/pinch chart.
                    setOnTouchListener { v, ev ->
                        if (ev.actionMasked == MotionEvent.ACTION_DOWN ||
                            ev.actionMasked == MotionEvent.ACTION_MOVE
                        ) {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        false
                    }
                    addJavascriptInterface(ChartBridge(), "ChartBridge")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            // Jangan push data di sini: LaunchedEffect(pageReady) yang
                            // melakukannya sekali, dengan resetKey yang benar.
                            pageReady = true
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?
                        ): Boolean {
                            // Return true supaya app tidak crash. WebView dilepas lalu dibuat ulang.
                            view?.let {
                                (it.parent as? ViewGroup)?.removeView(it)
                                it.destroy()
                            }
                            webView = null
                            pageReady = false
                            appliedResetKey = null
                            generation++
                            return true
                        }
                    }
                    loadUrl(CHART_URL)
                    webView = this
                }
            },
            // SENGAJA KOSONG. `update` berjalan tiap rekomposisi; mendorong data
            // di sini adalah akar bug "chart selalu rekomposisi ulang".
            update = { }
        )
    }
}

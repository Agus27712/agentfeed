package agu.analys.bridge

import android.webkit.JavascriptInterface
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.TechnicalIndicators
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * JavaScript Interface Bridge between TradingView WebView runtime and Kotlin Native.
 * Missing market fields are kept as zero/unavailable; no synthetic prices, volume,
 * or percentage values are inserted because this app is also used as a learning tool.
 */
class TradingViewBridge(
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private val _events = MutableSharedFlow<TradingViewEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<TradingViewEvent> = _events.asSharedFlow()

    @JavascriptInterface
    fun onTick(jsonString: String) {
        coroutineScope.launch {
            try {
                val json = JSONObject(jsonString)
                val price = json.optDouble("price", 0.0)
                if (price <= 0.0) return@launch
                val tick = MarketTick(
                    symbol = json.optString("symbol", ""),
                    price = price,
                    high24h = json.optDouble("high24h", 0.0),
                    low24h = json.optDouble("low24h", 0.0),
                    volume24h = json.optDouble("volume24h", 0.0),
                    change24h = json.optDouble("change24h", 0.0),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
                _events.emit(TradingViewEvent.TickReceived(tick))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @JavascriptInterface
    fun onCandle(jsonString: String) {
        coroutineScope.launch {
            try {
                val json = JSONObject(jsonString)
                val open = json.optDouble("open", 0.0)
                val high = json.optDouble("high", 0.0)
                val low = json.optDouble("low", 0.0)
                val close = json.optDouble("close", 0.0)
                if (open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0) return@launch
                val candle = CandleBar(
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = json.optDouble("volume", 0.0)
                )
                _events.emit(TradingViewEvent.CandleBarReceived(candle))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @JavascriptInterface
    fun onIndicators(jsonString: String) {
        coroutineScope.launch {
            try {
                val json = JSONObject(jsonString)
                val indicators = TechnicalIndicators(
                    rsi14 = json.optDouble("rsi14", 0.0),
                    macd = json.optDouble("macd", 0.0),
                    macdSignal = json.optDouble("macdSignal", 0.0),
                    macdHist = json.optDouble("macdHist", 0.0),
                    ema20 = json.optDouble("ema20", 0.0),
                    ema50 = json.optDouble("ema50", 0.0),
                    ema200 = json.optDouble("ema200", 0.0),
                    bbUpper = json.optDouble("bbUpper", 0.0),
                    bbLower = json.optDouble("bbLower", 0.0),
                    atr = json.optDouble("atr", 0.0),
                    momentum = json.optDouble("momentum", 0.0)
                )
                _events.emit(TradingViewEvent.IndicatorsReceived(indicators))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @JavascriptInterface
    fun onStatusChange(statusString: String) {
        coroutineScope.launch {
            try {
                val json = JSONObject(statusString)
                val isConnected = json.optBoolean("connected", true)
                val message = json.optString("message", "Live Stream Connected")
                _events.emit(TradingViewEvent.StatusChanged(isConnected, message))
            } catch (e: Exception) {
                _events.emit(TradingViewEvent.StatusChanged(true, statusString))
            }
        }
    }
}

sealed interface TradingViewEvent {
    data class TickReceived(val tick: MarketTick) : TradingViewEvent
    data class CandleBarReceived(val candle: CandleBar) : TradingViewEvent
    data class IndicatorsReceived(val indicators: TechnicalIndicators) : TradingViewEvent
    data class StatusChanged(val isConnected: Boolean, val message: String) : TradingViewEvent
}

package agu.analys.util

import agu.analys.model.MarketTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Configurable, thread-safe throttling mechanism for high-frequency market price feeds.
 *
 * Prevents Main-Thread bottlenecking and Compose frame-drops during high-volatility spikes.
 *
 * Key Behaviors:
 * 1. Leading-Edge Immediate Emission: When market is idle or ticks arrive normally (> throttleIntervalMs),
 *    the price is dispatched with 0ms delay.
 * 2. Trailing-Edge Conflation: When high-volatility tick floods occur (< throttleIntervalMs), intermediate
 *    ticks are conflated, and the latest accurate price is guaranteed to emit at the end of the window.
 * 3. Dynamic Runtime Config: Can be adjusted on the fly (e.g. 50ms - 2000ms, or 0ms to bypass).
 */
class PriceFeedThrottler(
    private val scope: CoroutineScope,
    initialThrottleIntervalMs: Long = DEFAULT_THROTTLE_MS,
    private val onEmit: (MarketTick) -> Unit
) {
    @Volatile
    var throttleIntervalMs: Long = initialThrottleIntervalMs.coerceAtLeast(0L)
        set(value) {
            field = value.coerceAtLeast(0L)
        }

    private val mutex = Mutex()
    private var lastEmitTimeMs = 0L
    private var pendingTick: MarketTick? = null
    private var scheduledJob: Job? = null

    /**
     * Submits a new price tick into the throttler.
     */
    fun submit(tick: MarketTick) {
        val interval = throttleIntervalMs
        if (interval <= 0L) {
            onEmit(tick)
            return
        }

        val now = System.currentTimeMillis()
        scope.launch(Dispatchers.Default) {
            mutex.withLock {
                val elapsed = now - lastEmitTimeMs
                if (elapsed >= interval && scheduledJob == null) {
                    // Leading edge: Dispatch immediately
                    lastEmitTimeMs = now
                    pendingTick = null
                    onEmit(tick)
                } else {
                    // Volatility surge: Buffer latest tick and schedule trailing emission
                    pendingTick = tick
                    if (scheduledJob == null || !scheduledJob!!.isActive) {
                        val waitTime = (interval - elapsed).coerceIn(1L, interval)
                        scheduledJob = scope.launch(Dispatchers.Default) {
                            delay(waitTime)
                            var tickToDispatch: MarketTick? = null
                            mutex.withLock {
                                tickToDispatch = pendingTick
                                pendingTick = null
                                lastEmitTimeMs = System.currentTimeMillis()
                                scheduledJob = null
                            }
                            tickToDispatch?.let { onEmit(it) }
                        }
                    }
                }
            }
        }
    }

    /**
     * Bypasses any active throttle buffer and forces immediate emission (e.g. when opening a new coin).
     */
    fun emitImmediate(tick: MarketTick) {
        scope.launch(Dispatchers.Default) {
            mutex.withLock {
                scheduledJob?.cancel()
                scheduledJob = null
                pendingTick = null
                lastEmitTimeMs = System.currentTimeMillis()
                onEmit(tick)
            }
        }
    }

    /**
     * Resets internal timers and cancels pending scheduled jobs.
     */
    fun reset() {
        scope.launch(Dispatchers.Default) {
            mutex.withLock {
                scheduledJob?.cancel()
                scheduledJob = null
                pendingTick = null
                lastEmitTimeMs = 0L
            }
        }
    }

    companion object {
        const val DEFAULT_THROTTLE_MS = 100L
        const val FAST_THROTTLE_MS = 100L
        const val SMOOTH_THROTTLE_MS = 200L
        const val ECO_THROTTLE_MS = 500L
    }
}

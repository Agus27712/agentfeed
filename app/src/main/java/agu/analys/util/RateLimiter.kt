package agu.analys.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentLinkedQueue

class RateLimiter(
    private val minIntervalMs: Long = 200L,
    private val maxRequestsPerMinute: Int = 200
) {
    private val mutex = Mutex()
    private val lastRequestAt = AtomicLong(0L)
    private val requestTimestamps = ConcurrentLinkedQueue<Long>()

    suspend fun waitAndConsume() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            
            // Clean up old timestamps
            val oneMinuteAgo = now - 60_000L
            while (true) {
                val oldest = requestTimestamps.peek()
                if (oldest != null && oldest < oneMinuteAgo) {
                    requestTimestamps.poll()
                } else {
                    break
                }
            }

            // Check budget
            if (requestTimestamps.size >= maxRequestsPerMinute) {
                val oldest = requestTimestamps.peek() ?: now
                val waitTimeForBudget = 60_000L - (now - oldest)
                if (waitTimeForBudget > 0) {
                    delay(waitTimeForBudget)
                }
            }

            val currentNow = System.currentTimeMillis()
            val wait = minIntervalMs - (currentNow - lastRequestAt.get())
            if (wait > 0) {
                delay(wait)
            }
            
            val finalTime = System.currentTimeMillis()
            lastRequestAt.set(finalTime)
            requestTimestamps.add(finalTime)
        }
    }
}

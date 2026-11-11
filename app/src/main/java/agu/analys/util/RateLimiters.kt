package agu.analys.util

object RateLimiters {
    // Public Market endpoints (e.g. Ticker, Depth)
    val publicMarket = RateLimiter(minIntervalMs = 200L, maxRequestsPerMinute = 250)
    
    // Private Account endpoints (e.g. Account Balance, Open Orders)
    val privateAccount = RateLimiter(minIntervalMs = 300L, maxRequestsPerMinute = 150)
    
    // Trade endpoints (e.g. Create Order, Cancel Order)
    val privateTrade = RateLimiter(minIntervalMs = 200L, maxRequestsPerMinute = 200)
}

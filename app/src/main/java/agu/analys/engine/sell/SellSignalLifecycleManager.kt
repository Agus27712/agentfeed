package agu.analys.engine.sell

import agu.analys.model.SellLifecycleState
import agu.analys.model.SellSignalState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class SellTransition(
    val symbol: String,
    val newState: SellSignalState,
    val isNewReadyToSell: Boolean,
    val isNewTrailingTriggered: Boolean,
    val isNewStopLossHit: Boolean,
    val isNewRapidDropExit: Boolean = false
) {
    val hasTriggeringTransition: Boolean
        get() = isNewReadyToSell || isNewTrailingTriggered || isNewStopLossHit || isNewRapidDropExit
}

object SellSignalLifecycleManager {
    // Menyimpan state terakhir per key (real/sim + symbol), HANYA untuk deteksi transisi (edge-trigger notifikasi)
    private val activeStates = ConcurrentHashMap<String, SellLifecycleState>()
    private val lock = ReentrantLock()

    private fun buildKey(symbol: String, isReal: Boolean, exchange: String = "TOKOCRYPTO"): String {
        val ex = exchange.uppercase().trim()
        return "${ex}_${if (isReal) "real" else "sim"}_${symbol.uppercase().trim()}"
    }

    fun process(
        symbol: String,
        newState: SellSignalState,
        isReal: Boolean = false,
        exchange: String = "TOKOCRYPTO"
    ): SellTransition = lock.withLock {
        val key = buildKey(symbol, isReal, exchange)
        val previousState = activeStates[key] ?: activeStates["${if (isReal) "real" else "sim"}_${symbol.uppercase().trim()}"] ?: SellLifecycleState.NOT_HOLDING
        
        // Simpan state baru
        activeStates[key] = newState.state

        val isNewReadyToSell = previousState != SellLifecycleState.READY_TO_SELL && newState.state == SellLifecycleState.READY_TO_SELL
        val isNewTrailingTriggered = previousState != SellLifecycleState.TRAILING_TRIGGERED && newState.state == SellLifecycleState.TRAILING_TRIGGERED
        val isNewStopLossHit = previousState != SellLifecycleState.STOP_LOSS_HIT && newState.state == SellLifecycleState.STOP_LOSS_HIT
        val isNewRapidDropExit = previousState != SellLifecycleState.RAPID_DROP_EXIT && newState.state == SellLifecycleState.RAPID_DROP_EXIT

        return SellTransition(
            symbol = symbol,
            newState = newState,
            isNewReadyToSell = isNewReadyToSell,
            isNewTrailingTriggered = isNewTrailingTriggered,
            isNewStopLossHit = isNewStopLossHit,
            isNewRapidDropExit = isNewRapidDropExit
        )
    }

    fun reset(symbol: String, isReal: Boolean = false, exchange: String = "TOKOCRYPTO") = lock.withLock {
        activeStates.remove(buildKey(symbol, isReal, exchange))
        activeStates.remove("${if (isReal) "real" else "sim"}_${symbol.uppercase().trim()}")
    }
}

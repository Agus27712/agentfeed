package agu.analys.viewmodel

import agu.analys.model.MarketConnectionState

fun TradingViewModel.requestDeepAiAudit() {
    val tick = currentTick.value ?: return
    if (connectionState.value !is MarketConnectionState.Connected || isAuditLoading.value || isGeminiLoading.value) return
    aiNewsViewModel.requestDeepAiAudit(
        tick = tick,
        indicators = currentIndicators.value,
        signal = aiSignalState.value
    )
}

fun TradingViewModel.clearAuditReport() {
    aiNewsViewModel.clearAuditReport()
}

fun TradingViewModel.requestGeminiChartSummary() {
    val tick = currentTick.value ?: return
    if (connectionState.value !is MarketConnectionState.Connected || isAuditLoading.value || isGeminiLoading.value) return
    aiNewsViewModel.requestGeminiChartSummary(
        tick = tick,
        indicators = currentIndicators.value,
        signal = aiSignalState.value
    )
}

fun TradingViewModel.clearGeminiSummary() {
    aiNewsViewModel.clearGeminiSummary()
}

package agu.analys.model

/**
 * Stub smoke untuk `ScalpingMtfSnapshot` produksi. `AgentOutputModels.kt` adalah
 * berkas produksi yang ikut di-compile, dan hanya memakai field di bawah ini lewat
 * `AgentSignalBreakdown.build()`. Semua field punya default supaya
 * `ScalpingMtfSnapshot()` (perbandingan "snapshot kosong") tetap valid.
 */
data class ScalpingMtfSnapshot(
    val biasDirection: String = "mixed",
    val statusTitle: String = "",
    val completedCount: Int = 0,
    val setupOk: Boolean = false,
    val triggerOk: Boolean = false,
    val checkpoints: List<SmokeCheckpoint> = emptyList()
)

data class SmokeCheckpoint(
    val code: String = "",
    val label: String = "",
    val isOk: Boolean = false
)

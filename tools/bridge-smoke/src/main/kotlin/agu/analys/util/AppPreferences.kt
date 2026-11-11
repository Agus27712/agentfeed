package agu.analys.util

import android.content.Context

/**
 * Stub smoke untuk `AppPreferences` produksi. `AgentHttpServer.installRoutes()` membaca
 * `agentBridgeToken` dari sini; token tetap dijaga non-kosong supaya uji 401 (tanpa
 * header) benar-benar menguji fail-closed, bukan "server kehilangan token".
 */
class AppPreferences(@Suppress("UNUSED_PARAMETER") context: Context) {
    val agentBridgeToken: String = "smoke-bridge-token"
}

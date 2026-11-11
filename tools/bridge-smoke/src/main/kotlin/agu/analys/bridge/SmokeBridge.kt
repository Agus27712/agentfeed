package agu.analys.bridge
import android.content.Context
import java.nio.file.Files
class AgentOutputBridge {
    private val dir = Files.createTempDirectory("bridge-smoke").toFile()
    val snapshot = AgentSignalOutput(1L, "test", "SNAPSHOT", "USDT", "test", "BUY", 90f, 1.0, reason = "smoke")
    fun getDir() = dir
    fun getLatest() = snapshot
    /** Dipanggil AgentHttpServer.boot() saat server mencapai Running. */
    fun startPortfolioPush() = Unit
    companion object {
        private val instance = AgentOutputBridge()
        fun getInstance(context: Context) = instance
    }
}

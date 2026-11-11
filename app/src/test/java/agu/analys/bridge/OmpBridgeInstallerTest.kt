package agu.analys.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OmpBridgeInstallerTest {

    @Test
    fun `prepare menghasilkan perintah base64 yang menulis index ke tujuan Termux`() {
        val context = RuntimeEnvironment.getApplication()
        val prepared = OmpBridgeInstaller.prepare(context)

        assertEquals("index.ts", prepared.fileName)
        assertTrue(prepared.command.startsWith("mkdir -p ${OmpBridgeInstaller.DESTINATION_DIR}"))
        assertTrue(prepared.command.contains("| base64 -d > ${OmpBridgeInstaller.DESTINATION_DIR}/index.ts"))

        // Isi yang akan ditulis Termux harus persis sama dengan index.ts yang dibundel di APK.
        val decoded = String(java.util.Base64.getDecoder().decode(prepared.base64), Charsets.UTF_8)
        assertTrue("isi index.ts: $decoded", decoded.contains("agentfeed") || decoded.contains("agentfeedBridge"))
    }

    @Test
    fun `buildInstallCommand menghasilkan satu baris yang bisa ditempel`() {
        val base64 = java.util.Base64.getEncoder().encodeToString("halo".toByteArray(Charsets.UTF_8))
        val command = OmpBridgeInstaller.buildInstallCommand(base64, "index.ts")

        assertTrue(command.contains("mkdir -p"))
        assertTrue(command.contains("printf '%s'"))
        assertTrue(command.contains("${OmpBridgeInstaller.DESTINATION_DIR}/index.ts"))
        assertTrue(command.contains("${OmpBridgeInstaller.ALT_DESTINATION_DIR}/index.ts"))
        assertTrue("tidak boleh ada baris baru", "\n" !in command)
        assertTrue("command memuat base64 apa adanya", command.contains(base64))
    }
}

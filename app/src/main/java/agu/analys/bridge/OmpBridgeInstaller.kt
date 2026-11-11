package agu.analys.bridge

import android.content.Context
import android.content.Intent

/**
 * Pemasangan sekali klik ekstensi `oh-my-pi` (instrumen bridge) ke Termux dari Pengaturan.
 *
 * Kenapa bukan "salin ke Documents"? Pada targetSdk 35 aplikasi ini **tidak bisa** menulis ke
 * folder publik `Documents` (scoped storage), sehingga hasil salinannya tidak akan terbaca
 * Termux. Satu-satunya cara yang tidak bergantung izin penyimpanan adalah mengirim **perintah**
 * yang menulis `index.ts` langsung ke `~/.omp/agent/extensions/agentfeed/`:
 *
 * 1. Jalur otomatis — intent `com.termux.RUN_COMMAND`. Termux menjalankannya di shell-nya sendiri.
 *    Syarat: `allow-external-apps=true` di `~/.termux/termux.properties`. Bila prasyarat itu
 *    belum diset, Termux tidak mengeksekusi apa pun (tidak ada kesalahan di aplikasi).
 * 2. Jalur manual — perintah yang sama, disalin ke clipboard; pengguna menempelnya di Termux.
 *    Tidak perlu setelan apa pun, hanya butuh Termux terpasang.
 *
 * Isi `index.ts` dikirim sebagai base64 agar perintah tetap satu baris dan tidak bermasalah
 * dengan karakter khusus saat dimasukkan ke Termux.
 */
object OmpBridgeInstaller {

    /** Direktori aset hasil `syncOmpExtensionAssets` (di-generate saat build dari `tools/oh-my-pi-extension`). */
    const val ASSET_DIR = "omp-extension"

    /** Tujuan utama di dalam Termux (standar ekstensi OMP 18+). */
    const val DESTINATION_DIR = "~/.omp/agent/extensions/agentfeed"

    /** Tujuan alternatif untuk kompatibilitas nama direktori tunggal. */
    const val ALT_DESTINATION_DIR = "~/.omp/agent/extension/agentfeed"

    data class Prepared(val fileName: String, val base64: String, val command: String)

    /** Berkas token yang ikut ditulis bersama `index.ts` (dibaca ekstensi untuk header X-Agent-Token). */
    const val TOKEN_FILE = "agent-bridge-token.txt"

    /** Membaca aset dan merakit perintah pemasangan. Melempar bila aset tidak ada. */
    fun prepare(context: Context, fileName: String = "index.ts"): Prepared {
        val bytes = context.assets.open("$ASSET_DIR/$fileName").use { it.readBytes() }
        val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val token = runCatching {
            agu.analys.util.AppPreferences(context).agentBridgeToken
        }.getOrDefault("")
        val command = buildInstallCommand(base64, fileName, token)
        return Prepared(fileName, base64, command)
    }

    fun buildInstallCommand(base64: String, fileName: String = "index.ts", bridgeToken: String = ""): String {
        val core =
            "mkdir -p $DESTINATION_DIR $ALT_DESTINATION_DIR && printf '%s' '$base64' | base64 -d > $DESTINATION_DIR/$fileName && cp $DESTINATION_DIR/$fileName $ALT_DESTINATION_DIR/$fileName"
        if (bridgeToken.isBlank()) return core
        return "$core && printf '%s' '$bridgeToken' > $DESTINATION_DIR/$TOKEN_FILE && cp $DESTINATION_DIR/$TOKEN_FILE $ALT_DESTINATION_DIR/$TOKEN_FILE"
    }

    /**
     * Mengirim perintah pemasangan ke Termux lewat RunCommandService (`com.termux.RUN_COMMAND`).
     * Prasyarat di Termux: `allow-external-apps=true` di `~/.termux/termux.properties`.
     * @return `true` bila Termux menerima intent, `false` bila tidak.
     */
    fun runInTermux(context: Context, command: String): Boolean {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        return try {
            val component = context.startService(intent)
            component != null
        } catch (_: Throwable) {
            // Fallback jika background service ditolak ROM, buka aplikasi Termux langsung.
            // FLAG_ACTIVITY_NEW_TASK WAJIB: context di sini adalah application context,
            // tanpa flag ini startActivity melempar AndroidRuntimeException dan
            // fallback dianggap berhasil padahal tidak pernah membuka apa pun.
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    true
                } else false
            } catch (_: Throwable) {
                false
            }
        }
    }
}

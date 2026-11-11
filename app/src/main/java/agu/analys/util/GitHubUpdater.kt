package agu.analys.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import agu.analys.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class GitHubReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkName: String,
    val htmlUrl: String
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: GitHubReleaseInfo) : UpdateCheckResult()
    data class AlreadyLatest(val version: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object GitHubUpdater {
    const val DEFAULT_REPO = "Agus27712/agentfeed"

    suspend fun checkUpdate(context: Context, repo: String, token: String = ""): UpdateCheckResult = withContext(Dispatchers.IO) {
        val normalizedRepo = normalizeRepo(repo)
        val currentVersion = normalizeVersion(BuildConfig.VERSION_NAME)
        try {
            // 1. Coba fetch rilis terbaru (releases/latest)
            var releaseObj: JSONObject? = null
            val latestUrl = "https://api.github.com/repos/$normalizedRepo/releases/latest"
            val latestConn = openGitHubConnection(latestUrl, token)
            val latestCode = latestConn.responseCode

            if (latestCode in 200..299) {
                val body = latestConn.inputStream.bufferedReader().use { it.readText() }
                releaseObj = JSONObject(body)
            } else if (latestCode == 403) {
                return@withContext UpdateCheckResult.Error("Batas request GitHub terlampaui (rate limit). Coba lagi beberapa saat atau masukkan GitHub Token di Pengaturan.")
            } else if (latestCode == 401) {
                return@withContext UpdateCheckResult.Error("Akses ditolak (HTTP 401). Personal Access Token GitHub tidak valid atau tidak memiliki izin akses.")
            } else {
                // 2. Fallback: Cek daftar semua rilis (releases)
                val allReleasesUrl = "https://api.github.com/repos/$normalizedRepo/releases"
                val allConn = openGitHubConnection(allReleasesUrl, token)
                val allCode = allConn.responseCode
                if (allCode in 200..299) {
                    val body = allConn.inputStream.bufferedReader().use { it.readText() }
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        releaseObj = array.getJSONObject(0)
                    }
                } else if (allCode == 401) {
                    return@withContext UpdateCheckResult.Error("Akses ditolak (HTTP 401). Personal Access Token GitHub tidak valid atau tidak memiliki izin akses.")
                }
            }

            // Jika ada objek Release dari GitHub Releases API
            if (releaseObj != null) {
                val tagName = releaseObj.optString("tag_name", "").trim()
                val latestVersion = normalizeVersion(tagName)
                val htmlUrl = releaseObj.optString("html_url", "https://github.com/$normalizedRepo/releases")
                val rawNotes = releaseObj.optString("body", "Pembaruan versi $tagName tersedia.")
                val releaseNotes = extractLatestReleaseNotes(rawNotes)

                val assets = releaseObj.optJSONArray("assets") ?: JSONArray()
                var apkUrl = ""
                var apkName = "agentfeed-update.apk"
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    val browserUrl = asset.optString("browser_download_url", "")
                    val apiUrl = asset.optString("url", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkName = name
                        apkUrl = if (browserUrl.isNotBlank()) browserUrl else apiUrl
                        break
                    }
                }

                if (latestVersion.isNotEmpty() && compareVersions(latestVersion, currentVersion) > 0) {
                    if (apkUrl.isBlank()) {
                        // Tag versi baru tersedia, download via release web page
                        apkUrl = htmlUrl
                    }
                    return@withContext UpdateCheckResult.UpdateAvailable(
                        GitHubReleaseInfo(
                            tagName = tagName.ifBlank { "v$latestVersion" },
                            versionName = latestVersion,
                            releaseNotes = releaseNotes,
                            apkUrl = apkUrl,
                            apkName = apkName,
                            htmlUrl = htmlUrl
                        )
                    )
                } else {
                    return@withContext UpdateCheckResult.AlreadyLatest(BuildConfig.VERSION_NAME)
                }
            }

            // 3. Fallback: Cek Git Tags jika rilis belum diformalkan di GitHub Releases UI
            val tagsUrl = "https://api.github.com/repos/$normalizedRepo/tags"
            val tagsConn = openGitHubConnection(tagsUrl, token)
            val tagsCode = tagsConn.responseCode
            if (tagsCode in 200..299) {
                val body = tagsConn.inputStream.bufferedReader().use { it.readText() }
                val tagsArray = JSONArray(body)
                if (tagsArray.length() > 0) {
                    val latestTagObj = tagsArray.getJSONObject(0)
                    val tagName = latestTagObj.optString("name", "").trim()
                    val latestVersion = normalizeVersion(tagName)
                    val tagHtmlUrl = "https://github.com/$normalizedRepo/releases/tag/$tagName"

                    if (latestVersion.isNotEmpty() && compareVersions(latestVersion, currentVersion) > 0) {
                        return@withContext UpdateCheckResult.UpdateAvailable(
                            GitHubReleaseInfo(
                                tagName = tagName,
                                versionName = latestVersion,
                                releaseNotes = "Versi tag $tagName terdeteksi di GitHub ($normalizedRepo).",
                                apkUrl = "https://github.com/$normalizedRepo/releases/download/$tagName/app-release.apk",
                                apkName = "agentfeed-$latestVersion.apk",
                                htmlUrl = tagHtmlUrl
                            )
                        )
                    } else {
                        return@withContext UpdateCheckResult.AlreadyLatest(BuildConfig.VERSION_NAME)
                    }
                }
            } else if (tagsCode == 401) {
                return@withContext UpdateCheckResult.Error("Akses ditolak (HTTP 401). Personal Access Token GitHub tidak valid atau tidak memiliki izin akses.")
            }

            if (latestCode == 404 && tagsCode == 404) {
                return@withContext UpdateCheckResult.Error(
                    "Repository tidak ditemukan di GitHub (HTTP 404). Silakan pastikan:\n" +
                    "1. Nama repository di Pengaturan sudah benar (format: username/repository).\n" +
                    "2. Jika repository bersifat Private, pastikan Anda telah memasukkan GitHub Personal Access Token yang valid di Pengaturan.\n" +
                    "3. Jika repository bersifat Public, pastikan nama repository diinput secara tepat."
                )
            }

            return@withContext UpdateCheckResult.Error(
                "Repository ditemukan ($normalizedRepo), tetapi tidak ada rilis formal maupun tag rilis yang terdeteksi (HTTP $latestCode / $tagsCode).\n" +
                "Silakan buat minimal satu 'Release' atau 'Tag' di GitHub agar sistem dapat mendeteksi pembaruan."
            )
        } catch (e: Exception) {
            UpdateCheckResult.Error("Gagal memeriksa update: ${e.localizedMessage ?: "Koneksi terputus"}")
        }
    }

    private fun openGitHubConnection(urlStr: String, token: String = ""): HttpURLConnection {
        return (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "NadiAgentfeedApp/${BuildConfig.VERSION_NAME}")
            if (token.isNotBlank()) {
                val authHeader = if (token.startsWith("ghp_", true) || token.startsWith("github_pat_", true) || token.startsWith("Bearer ", true)) {
                    if (token.startsWith("Bearer ", true)) token else "Bearer $token"
                } else {
                    "token $token"
                }
                setRequestProperty("Authorization", authHeader)
            }
            connectTimeout = 12000
            readTimeout = 12000
        }
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        apkName: String,
        token: String = "",
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!apkUrl.startsWith("https://", ignoreCase = true) && !apkUrl.startsWith("http://", ignoreCase = true)) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "URL download tidak valid", Toast.LENGTH_SHORT).show()
                }
                return@withContext false
            }

            // Jika URL/nama bukan file APK mentah, arahkan ke browser
            val isApkFile = apkName.endsWith(".apk", ignoreCase = true) ||
                    apkUrl.substringBefore('?').substringBefore('#').endsWith(".apk", ignoreCase = true) ||
                    apkUrl.contains("/releases/assets/", ignoreCase = true) ||
                    apkUrl.contains("/releases/download/", ignoreCase = true)

            if (!isApkFile) {
                withContext(Dispatchers.Main) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    Toast.makeText(context, "Membuka halaman rilis di browser...", Toast.LENGTH_SHORT).show()
                }
                return@withContext true
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Memulai unduhan APK...", Toast.LENGTH_SHORT).show()
            }

            var currentUrl = apkUrl
            var connection: HttpURLConnection
            var redirects = 0
            while (true) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "NadiAgentfeedApp/${BuildConfig.VERSION_NAME}")

                    val isOfficialGitHubDomain = url.host.equals("github.com", ignoreCase = true) ||
                            url.host.equals("api.github.com", ignoreCase = true)

                    // Kirim Authorization hanya pada domain resmi GitHub sebelum redirect (S3/AWS membalas HTTP 400 jika dikirimi Authorization Header)
                    if (isOfficialGitHubDomain && redirects == 0 && token.isNotBlank()) {
                        val authHeader = if (token.startsWith("ghp_", true) || token.startsWith("github_pat_", true) || token.startsWith("Bearer ", true)) {
                            if (token.startsWith("Bearer ", true)) token else "Bearer $token"
                        } else {
                            "token $token"
                        }
                        setRequestProperty("Authorization", authHeader)
                    }

                    if (currentUrl.contains("/releases/assets/") && isOfficialGitHubDomain && redirects == 0) {
                        setRequestProperty("Accept", "application/octet-stream")
                    }
                }
                val code = connection.responseCode
                if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, 307, 308)) {
                    val newUrl = connection.getHeaderField("Location") ?: break
                    currentUrl = if (newUrl.startsWith("http")) newUrl else URL(URL(currentUrl), newUrl).toString()
                    redirects++
                    if (redirects > 5) throw IllegalStateException("Terlalu banyak redirect")
                    continue
                }
                break
            }

            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Server mengembalikan kode: HTTP ${connection.responseCode}")
            }

            val fileLength = connection.contentLengthLong
            val downloadDir = context.getExternalFilesDir(null) ?: context.cacheDir
            if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                throw IllegalStateException("Gagal membuat folder penyimpanan update")
            }
            val safeName = apkName.substringAfterLast('/').ifBlank { "agentfeed-update.apk" }
            val apkFile = File(downloadDir, safeName)
            if (apkFile.exists()) apkFile.delete()

            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val data = ByteArray(32 * 1024)
                    var total = 0L
                    var lastProgress = -1
                    while (true) {
                        val count = input.read(data)
                        if (count < 0) break
                        total += count
                        output.write(data, 0, count)
                        if (fileLength > 0) {
                            val progress = ((total * 100) / fileLength).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                withContext(Dispatchers.Main) { onProgress(progress) }
                            }
                        }
                    }
                    output.flush()
                    if (total <= 0L) throw IllegalStateException("File APK unduhan kosong")
                }
            }

            withContext(Dispatchers.Main) {
                onProgress(100)
                Toast.makeText(context, "Unduhan selesai. Membuka penginstal...", Toast.LENGTH_SHORT).show()
                installApk(context, apkFile)
            }
            true
        } catch (error: Exception) {
            withContext(Dispatchers.Main) {
                onProgress(-1)
                Toast.makeText(context, "Gagal update: ${error.localizedMessage ?: "Kesalahan tidak diketahui"}", Toast.LENGTH_LONG).show()
            }
            false
        }
    }

    fun installApk(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) {
            Toast.makeText(context, "File APK update tidak ditemukan atau kosong", Toast.LENGTH_LONG).show()
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settingsIntent)
                Toast.makeText(context, "Aktifkan izin instalasi aplikasi tidak dikenal, lalu ulangi.", Toast.LENGTH_LONG).show()
                return false
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (error: Exception) {
            Toast.makeText(context, "Tidak bisa membuka penginstal APK: ${error.localizedMessage ?: "error"}", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun openGitHubReleasesPage(context: Context, repo: String) {
        val normalizedRepo = normalizeRepo(repo)
        val url = "https://github.com/$normalizedRepo/releases"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Tidak dapat membuka browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun normalizeRepo(repo: String): String {
        val value = repo.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removeSuffix(".git")
            .trim('/')
        if (value.isBlank() || value == "user/nama-repo") return DEFAULT_REPO
        return if (value.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))) value else DEFAULT_REPO
    }

    private fun normalizeVersion(raw: String): String {
        val cleaned = raw.trim()
            .removePrefix("v").removePrefix("V")
            .trimStart('.', ' ', '-', '_')
        val digitsAndDots = buildString {
            var lastWasDot = false
            for (ch in cleaned) {
                if (ch.isDigit()) {
                    append(ch)
                    lastWasDot = false
                } else if (ch == '.' && !lastWasDot && isNotEmpty()) {
                    append(ch)
                    lastWasDot = true
                }
            }
        }.trimEnd('.')
        return digitsAndDots.ifBlank { "0" }
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun extractLatestReleaseNotes(rawBody: String): String {
        if (rawBody.isBlank()) return ""
        val lines = rawBody.lines()
        val cleanLines = mutableListOf<String>()
        var headerCount = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("## ") || trimmed.startsWith("# ") || trimmed.startsWith("### ")) {
                headerCount++
                if (headerCount > 1) {
                    // Ignore release notes from previous releases
                    break
                }
            }
            if (trimmed.startsWith("---") && cleanLines.isNotEmpty()) {
                break
            }
            if (trimmed.contains("perubahan sebelumnya", ignoreCase = true) ||
                trimmed.contains("previous release", ignoreCase = true) ||
                trimmed.contains("changelog lama", ignoreCase = true)) {
                break
            }
            cleanLines.add(line)
        }
        val result = cleanLines.joinToString("\n").trim()
        return result.ifBlank { rawBody.trim() }
    }
}

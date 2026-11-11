package agu.analys.viewmodel

import android.content.Context
import android.widget.Toast
import agu.analys.util.GitHubReleaseInfo
import agu.analys.util.GitHubUpdater
import agu.analys.util.UpdateCheckResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppUpdateCoordinator(private val scope: CoroutineScope) {
    private val _releaseInfo = MutableStateFlow<GitHubReleaseInfo?>(null)
    val releaseInfo: StateFlow<GitHubReleaseInfo?> = _releaseInfo.asStateFlow()

    private val _updateCheckStatus = MutableStateFlow<String?>(null)
    val updateCheckStatus: StateFlow<String?> = _updateCheckStatus.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress: StateFlow<Int?> = _downloadProgress.asStateFlow()

    fun checkUpdate(context: Context, repo: String = GitHubUpdater.DEFAULT_REPO, token: String = "") {
        scope.launch {
            _isCheckingUpdate.value = true
            _updateCheckStatus.value = "Memeriksa rilis terbaru di GitHub ($repo)..."
            _releaseInfo.value = null
            _downloadProgress.value = null
            when (val result = GitHubUpdater.checkUpdate(context, repo, token)) {
                is UpdateCheckResult.UpdateAvailable -> {
                    _releaseInfo.value = result.info
                    _updateCheckStatus.value = "Pembaruan ${result.info.tagName} tersedia!"
                    Toast.makeText(context, "Pembaruan ${result.info.tagName} ditemukan!", Toast.LENGTH_LONG).show()
                }
                is UpdateCheckResult.AlreadyLatest -> {
                    _releaseInfo.value = null
                    _updateCheckStatus.value = "Aplikasi sudah dalam versi terbaru (${result.version})."
                    Toast.makeText(context, "Aplikasi sudah versi terbaru (${result.version})", Toast.LENGTH_SHORT).show()
                }
                is UpdateCheckResult.Error -> {
                    _releaseInfo.value = null
                    _updateCheckStatus.value = result.message
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            }
            _isCheckingUpdate.value = false
        }
    }

    fun downloadAndInstall(context: Context, repo: String = GitHubUpdater.DEFAULT_REPO, token: String = "") {
        val release = _releaseInfo.value
        if (release == null || release.apkUrl.isBlank()) {
            GitHubUpdater.openGitHubReleasesPage(context, repo)
            return
        }
        scope.launch {
            _downloadProgress.value = 0
            GitHubUpdater.downloadAndInstallApk(context, release.apkUrl, release.apkName, token) { progress ->
                _downloadProgress.value = progress
            }
        }
    }
}

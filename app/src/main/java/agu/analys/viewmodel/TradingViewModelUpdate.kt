package agu.analys.viewmodel

fun TradingViewModel.checkGitHubUpdate(
    context: android.content.Context,
    repo: String = prefs.updateRepo,
    token: String = prefs.updateGitHubToken
) {
    updateCoordinator.checkUpdate(context, repo, token)
}

fun TradingViewModel.downloadAndInstallUpdate(
    context: android.content.Context,
    repo: String = prefs.updateRepo,
    token: String = prefs.updateGitHubToken
) {
    updateCoordinator.downloadAndInstall(context, repo, token)
}

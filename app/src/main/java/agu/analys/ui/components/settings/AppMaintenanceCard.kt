package agu.analys.ui.components.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.BuildConfig
import agu.analys.ui.theme.*
import agu.analys.util.GitHubReleaseInfo
import agu.analys.util.GitHubUpdater
import agu.analys.util.MarketDataCache

@Composable
fun AppMaintenanceCard(
    context: Context,
    cacheCleared: Boolean,
    onClearCache: () -> Unit,
    updateRepo: String,
    onUpdateRepoChange: (String) -> Unit,
    updateToken: String,
    onUpdateTokenChange: (String) -> Unit,
    releaseInfo: GitHubReleaseInfo?,
    checkingUpdate: Boolean,
    updateStatus: String?,
    downloadProgress: Int?,
    onCheckUpdate: () -> Unit,
    onDownloadAndInstall: () -> Unit
) {
    var showLogcatDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
    ) {
        Column(Modifier.padding(14.dp)) {
            // Diagnostik & Logcat Lengkap Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(TvSurfaceVariant, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = TvGreen, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text("Logcat & Diagnostik Sistem", color = TvTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Periksa state koin, trailing stop & log error", color = TvTextSecondary, fontSize = 10.sp)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showLogcatDialog = true },
                    modifier = Modifier.weight(1.2f).height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TvBlue)
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text("Buka Logcat", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                }

                OutlinedButton(
                    onClick = {
                        val dump = agu.analys.util.AppLogManager.buildDiagnosticStateDump(context)
                        agu.analys.util.AppLogManager.exportAndShareLog(context, dump)
                    },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TvTextPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp), tint = TvGreen)
                    Spacer(Modifier.width(4.dp))
                    Text("Ekspor Log", fontSize = 11.sp, color = TvTextPrimary, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = TvBorder, thickness = 0.5.dp)
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        MarketDataCache(context).clearAll()
                        onClearCache()
                        Toast.makeText(context, "Cache offline pasar berhasil dibersihkan", Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Bersihkan Cache Data Pasar", color = TvTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Hapus data candle dan tick tersimpan lokal", color = TvTextSecondary, fontSize = 10.sp)
                }
                Text(
                    if (cacheCleared) "0,00 MB (Bersih)" else "Bersihkan >",
                    color = if (cacheCleared) TvGreen else TvBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = TvBorder, thickness = 0.5.dp)
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Pembaruan Aplikasi",
                        color = TvTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Versi terpasang: v${BuildConfig.VERSION_NAME}",
                        color = TvTextSecondary,
                        fontSize = 11.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = TvSurfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, TvBorder)
                ) {
                    Text(
                        text = "GitHub Official",
                        color = TvTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            if (updateStatus != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    updateStatus,
                    color = if (releaseInfo != null) TvGreen else TvAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (releaseInfo != null && releaseInfo.releaseNotes.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvSurfaceVariant, RoundedCornerShape(6.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        "Catatan Rilis ${releaseInfo.tagName}:",
                        color = TvBlue,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        releaseInfo.releaseNotes.take(300) + if (releaseInfo.releaseNotes.length > 300) "..." else "",
                        color = TvTextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCheckUpdate,
                    enabled = !checkingUpdate,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TvSurfaceVariant)
                ) {
                    if (checkingUpdate) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TvGreen, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Default.SystemUpdate, null, modifier = Modifier.size(16.dp), tint = TvGreen)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        if (checkingUpdate) "Memeriksa..." else "Cek Update",
                        color = TvTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                if (releaseInfo != null) {
                    Button(
                        onClick = onDownloadAndInstall,
                        modifier = Modifier.weight(1.2f).height(42.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
                    ) {
                        Text(
                            if (downloadProgress == null) "Unduh & Install"
                            else if (downloadProgress == 100) "Membuka APK..."
                            else "Unduh ($downloadProgress%)",
                            color = Color.Black,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { GitHubUpdater.openGitHubReleasesPage(context, updateRepo) },
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TvBlue),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                    ) {
                        Text("Buka GitHub", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showLogcatDialog) {
        LogcatDiagnosticDialog(
            onDismissRequest = { showLogcatDialog = false }
        )
    }
}

package agu.analys.ui.components.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*

@Composable
fun RealBuyModeAndSecurityCard(
    isRealBuyMode: Boolean,
    hasPin: Boolean,
    hasApiCredentials: Boolean,
    isPinUnlocked: Boolean,
    userPublicIp: String = "",
    failedPinAttempts: Int = 0,
    marketDataSource: agu.analys.config.MarketDataSource = agu.analys.config.MarketDataSource.TOKOCRYPTO,
    onToggleRealBuyMode: () -> Unit,
    onOpenSetupDialog: () -> Unit,
    onRequirePinUnlock: () -> Unit,
    onWipeCredentials: () -> Unit,
    onCheckPublicIp: () -> Unit = {}
) {
    var showWipeConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isRealBuyMode) TvGreen else TvBorder
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            // Header + Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "MODE TRADING",
                            color = if (isRealBuyMode) TvGreen else TvTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isRealBuyMode) TvGreen.copy(alpha = 0.15f) else TvSurfaceVariant,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (isRealBuyMode) "REAL" else "SIMULASI",
                                color = if (isRealBuyMode) TvGreen else TvTextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (isRealBuyMode)
                            "Sistem akan mengeksekusi order Beli & Jual langsung ke Bursa via API."
                        else
                            "Mode Beli & Jual dialihkan ke SIMULASI (Saldo Virtual).",
                        color = TvTextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }

                Switch(
                    checked = isRealBuyMode,
                    onCheckedChange = { onToggleRealBuyMode() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = TvGreen,
                        uncheckedThumbColor = TvTextSecondary,
                        uncheckedTrackColor = TvSurfaceVariant
                    )
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = TvBorder)
            Spacer(Modifier.height(12.dp))

            // State: Not Configured vs Configured
            if (!hasPin || !hasApiCredentials) {
                // Belum dikonfigurasi
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
                        .border(1.dp, TvBorder, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = TvAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Kredensial API Exchange Belum Diatur",
                                color = TvAmber,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Untuk beralih ke Mode Real, masukkan API Key, Secret Key, dan PIN Keamanan 6-digit. Setelah disimpan, input akan otomatis disembunyikan dan dienkripsi lokal.",
                            color = TvTextSecondary,
                            fontSize = 10.5.sp,
                            lineHeight = 14.5.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = onOpenSetupDialog,
                            modifier = Modifier.fillMaxWidth().height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
                        ) {
                            Icon(Icons.Default.Key, null, modifier = Modifier.size(14.dp), tint = Color.Black)
                            Spacer(Modifier.width(6.dp))
                            Text("Konfigurasi API & PIN Mode Real", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 11.5.sp)
                        }
                    }
                }
            } else {
                // Sudah Terkonfigurasi & Terenkripsi
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
                        .border(1.dp, TvBorder, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = TvGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Kredensial Terenkripsi & Terlindungi",
                                    color = TvGreen,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .background(TvSurfaceVariant, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    if (isPinUnlocked) "PIN TERVERIFIKASI" else "PIN TERKUNCI",
                                    color = if (isPinUnlocked) TvGreen else TvBlue,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Text(
                            "API Key & Secret Key tersimpan aman di memori lokal. Mode Real dapat dipicu kapan saja tanpa perlu input ulang kunci API.",
                            color = TvTextSecondary,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )

                        if (failedPinAttempts > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "⚠️ Peringatan: $failedPinAttempts kali percobaan PIN salah! (5x salah akan auto-wipe kredensial)",
                                color = TvRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        // Action buttons: Update or Wipe
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (!isPinUnlocked) onRequirePinUnlock() else onOpenSetupDialog()
                                },
                                modifier = Modifier.weight(1f).height(34.dp),
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TvBlue),
                                border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Ubah API / PIN", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { showWipeConfirm = true },
                                modifier = Modifier.weight(1f).height(34.dp),
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TvRed),
                                border = androidx.compose.foundation.BorderStroke(1.dp, TvRed.copy(alpha = 0.6f)),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Hapus Kredensial", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // IP Whitelist Assistant Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                    .border(1.dp, TvBorder, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("🌐 IP PUBLIK HP ANDA SAAT INI:", color = TvBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (userPublicIp.isNotBlank()) userPublicIp else "Memuat...",
                                color = TvGreen,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        val context = LocalContext.current
                        OutlinedButton(
                            onClick = {
                                if (userPublicIp.isNotBlank() && !userPublicIp.contains("Gagal") && !userPublicIp.contains("Memuat")) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("IP Address", userPublicIp)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "IP Publik ($userPublicIp) berhasil disalin!", Toast.LENGTH_SHORT).show()
                                } else {
                                    onCheckPublicIp()
                                }
                            },
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TvBlue),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TvBlue)
                        ) {
                            Text("📋 SALIN IP", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        "💡 INFORMASI IP WHITELIST TOKOCRYPTO:\nJika Anda mengaktifkan pembatasan IP pada API Key Tokocrypto, masukkan IP Publik di atas ke whitelist exchange Anda.",
                        color = TvTextSecondary,
                        fontSize = 9.5.sp,
                        lineHeight = 13.5.sp
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvSurfaceVariant, RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                Text(
                    "🔒 Proteksi Otomatis: Jika PIN salah dimasukkan 5 kali berturut-turut, sistem akan langsung menghapus seluruh Kredensial API demi keamanan saldo Anda.",
                    color = TvTextSecondary,
                    fontSize = 9.5.sp,
                    lineHeight = 13.5.sp
                )
            }
        }
    }

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text("Hapus Semua Kredensial API?", color = TvTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Tindakan ini akan menghapus seluruh API Key, Secret Key, dan PIN Keamanan dari perangkat ini. Mode akan kembali ke Simulasi.",
                    color = TvTextSecondary,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWipeConfirm = false
                        onWipeCredentials()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TvRed)
                ) {
                    Text("Ya, Hapus Kredensial", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirm = false }) {
                    Text("Batal", color = TvTextSecondary, fontSize = 11.5.sp)
                }
            },
            containerColor = TvSurface,
            shape = RoundedCornerShape(14.dp)
        )
    }
}

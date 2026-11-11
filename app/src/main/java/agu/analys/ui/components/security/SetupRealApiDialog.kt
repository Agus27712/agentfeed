package agu.analys.ui.components.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.MarketDataSource
import agu.analys.ui.theme.*

@Composable
fun SetupRealApiDialog(
    targetExchange: MarketDataSource = MarketDataSource.TOKOCRYPTO,
    initialApiKey: String = "",
    initialSecretKey: String = "",
    userPublicIp: String = "",
    onCheckPublicIp: () -> Unit = {},
    onSaveAndActivate: (pin: String, apiKey: String, secretKey: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var secretKey by remember { mutableStateOf(initialSecretKey) }
    var showPin by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TvSurface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(TvGreen.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = TvGreen,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "AKTIVASI MODE REAL (${targetExchange.label.uppercase()})",
                    color = TvTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Atur PIN keamanan dan masukkan kredensial API ${targetExchange.label}. Setelah disimpan, form ini otomatis disembunyikan demi privasi Anda.",
                    color = TvTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Info Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvBlue.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .border(1.dp, TvBlue.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = TvBlue,
                            modifier = Modifier.size(16.dp).padding(top = 2.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Kredensial disimpan terenkripsi di perangkat lokal Anda dan dikunci dengan PIN. API Key & Secret tidak akan tampil terbuka di layar.",
                            color = TvTextSecondary,
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp
                        )
                    }
                }

                // Public IP Assistant Box inside Dialog
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvCardBackground, RoundedCornerShape(8.dp))
                        .border(0.5.dp, TvBorder, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("IP Publik HP (opsional, whitelist API):", color = TvTextSecondary, fontSize = 9.5.sp)
                            Text(
                                if (userPublicIp.isNotBlank()) userPublicIp else "Memuat IP...",
                                color = TvGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                if (userPublicIp.isNotBlank() && !userPublicIp.contains("Gagal") && !userPublicIp.contains("Memuat")) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("IP Address", userPublicIp)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "IP Publik disalin!", Toast.LENGTH_SHORT).show()
                                } else {
                                    onCheckPublicIp()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TvBlue),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TvBlue)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Salin IP", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // PIN Input
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                            pin = it
                            errorMessage = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Buat 6-Digit PIN Keamanan", fontSize = 11.sp) },
                    placeholder = { Text("Contoh: 123456", color = TvTextSecondary, fontSize = 11.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPin = !showPin }) {
                            Icon(
                                imageVector = if (showPin) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = TvTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvGreen,
                        unfocusedBorderColor = TvBorder,
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary
                    )
                )

                // Confirm PIN Input
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = {
                        if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                            confirmPin = it
                            errorMessage = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Konfirmasi PIN Keamanan", fontSize = 11.sp) },
                    placeholder = { Text("Ulangi 6-digit PIN", color = TvTextSecondary, fontSize = 11.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvGreen,
                        unfocusedBorderColor = TvBorder,
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary
                    )
                )

                // API Key Input
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("${targetExchange.label} API Key (Trade Permission)", fontSize = 11.sp) },
                    placeholder = { Text("Paste ${targetExchange.label} API Key...", color = TvTextSecondary, fontSize = 11.sp) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Key, null, tint = TvBlue, modifier = Modifier.size(18.dp))
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvGreen,
                        unfocusedBorderColor = TvBorder,
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary
                    )
                )

                // Secret Key Input
                OutlinedTextField(
                    value = secretKey,
                    onValueChange = {
                        secretKey = it
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("${targetExchange.label} Secret Key (Trade Permission)", fontSize = 11.sp) },
                    placeholder = { Text("Paste ${targetExchange.label} Secret Key...", color = TvTextSecondary, fontSize = 11.sp) },
                    singleLine = true,
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(
                                imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = TvTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvGreen,
                        unfocusedBorderColor = TvBorder,
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary
                    )
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = TvRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanPin = pin.trim()
                    val cleanConfirm = confirmPin.trim()
                    val cleanApi = apiKey.trim()
                    val cleanSecret = secretKey.trim()

                    if (cleanPin.length < 4) {
                        errorMessage = "PIN minimal 4 digit (rekomendasi 6 digit)."
                        return@Button
                    }
                    if (cleanPin != cleanConfirm) {
                        errorMessage = "Konfirmasi PIN tidak cocok dengan PIN pertama."
                        return@Button
                    }
                    if (cleanApi.isBlank()) {
                        errorMessage = "${targetExchange.label} API Key wajib diisi."
                        return@Button
                    }
                    if (cleanSecret.isBlank()) {
                        errorMessage = "${targetExchange.label} Secret Key wajib diisi."
                        return@Button
                    }

                    onSaveAndActivate(cleanPin, cleanApi, cleanSecret)
                },
                colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Simpan & Aktifkan Mode Real",
                    color = Color.Black,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Batal (Tetap Simulasi)", color = TvTextSecondary, fontSize = 12.sp)
            }
        }
    )
}

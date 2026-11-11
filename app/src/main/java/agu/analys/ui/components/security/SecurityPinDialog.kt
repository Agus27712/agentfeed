package agu.analys.ui.components.security

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*

@Composable
fun SecurityPinDialog(
    title: String = "AKSES MODE BELI & PORTOFOLIO REAL",
    subtitle: String = "Masukkan 6-digit PIN Keamanan Anda untuk melanjutkan.",
    isSetupMode: Boolean = false,
    errorMessage: String? = null,
    failedAttempts: Int = 0,
    maxAttempts: Int = 5,
    onPinSubmitted: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val displayError = errorMessage ?: localError

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
                        .background(
                            if (isSetupMode) TvBlue.copy(alpha = 0.15f) else TvGreen.copy(alpha = 0.15f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSetupMode) Icons.Default.Security else Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (isSetupMode) TvBlue else TvGreen,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = title,
                    color = TvTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = TvTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // PIN Indicator Dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    for (i in 0 until 6) {
                        val isFilled = i < pin.length
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(
                                    if (isFilled) (if (isSetupMode) TvBlue else TvGreen) else TvBorder,
                                    CircleShape
                                )
                                .border(
                                    1.dp,
                                    if (isFilled) (if (isSetupMode) TvBlue else TvGreen) else TvSurfaceVariant,
                                    CircleShape
                                )
                        )
                    }
                }

                if (displayError != null) {
                    Spacer(Modifier.height(4.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = displayError,
                            color = TvRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (failedAttempts > 0 && !isSetupMode) {
                            val remaining = (maxAttempts - failedAttempts).coerceAtLeast(0)
                            Text(
                                text = "⚠️ Sisa kesempatan: $remaining kali sebelum API & PIN dihapus otomatis!",
                                color = TvAmber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Custom Numeric Keypad
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val keyRows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("CLEAR", "0", "BACK")
                    )

                    keyRows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { key ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when (key) {
                                                "CLEAR", "BACK" -> TvSurfaceVariant
                                                else -> TvBorder
                                            }
                                        )
                                        .clickable {
                                            localError = null
                                            when (key) {
                                                "CLEAR" -> pin = ""
                                                "BACK" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                                else -> {
                                                    if (pin.length < 6) {
                                                        pin += key
                                                        if (pin.length == 6) {
                                                            onPinSubmitted(pin)
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    when (key) {
                                        "BACK" -> Icon(
                                            Icons.AutoMirrored.Filled.Backspace,
                                            contentDescription = "Hapus",
                                            tint = TvTextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        "CLEAR" -> Text(
                                            "C",
                                            color = TvTextSecondary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        else -> Text(
                                            key,
                                            color = TvTextPrimary,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (pin.length < 4) {
                        localError = "PIN minimal 4 digit (rekomendasi 6 digit)"
                    } else {
                        onPinSubmitted(pin)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSetupMode) TvBlue else TvGreen
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isSetupMode) "Simpan PIN Baru" else "Verifikasi PIN",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Batal",
                    color = TvTextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    )
}

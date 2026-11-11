package agu.analys.ui.components.simulation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import agu.analys.trading.SimulationWallet
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun SimulationTopUpModal(
    wallet: SimulationWallet,
    onTopUp: (Double) -> Unit,
    onSetBalance: ((Double) -> Unit)? = null,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val quickAmounts = listOf(1_000_000.0, 5_000_000.0, 10_000_000.0, 25_000_000.0, 50_000_000.0, 100_000_000.0)
    var manualInput by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    val parsedAmount = remember(manualInput) {
        parseManualAmount(manualInput)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = TvCardBackground),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = "Wallet",
                            tint = TvGreen,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Kelola Saldo Simulasi",
                            color = TvTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = TvTextSecondary)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Current Balance Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TvSurfaceVariant)
                        .padding(14.dp)
                ) {
                    Column {
                        Text(text = "Total Saldo IDR Simulasi Saat Ini", color = TvTextSecondary, fontSize = 11.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = PriceFormatter.formatPrice(wallet.getAvailableIdr()),
                            color = TvGreen,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        if (wallet.lockedIdr > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Terkunci di Open Orders: ${PriceFormatter.formatPrice(wallet.lockedIdr)}",
                                color = TvOrange,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Manual Input Section
                Text(
                    text = "Input Nominal Saldo (Manual):",
                    color = TvTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = manualInput,
                    onValueChange = {
                        manualInput = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }
                        inputError = null
                    },
                    placeholder = { Text("Contoh: 25000000 atau 5000000", color = TvTextSecondary.copy(alpha = 0.6f), fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary,
                        focusedBorderColor = TvBlue,
                        unfocusedBorderColor = TvBorder,
                        focusedContainerColor = TvBackground,
                        unfocusedContainerColor = TvBackground
                    ),
                    shape = RoundedCornerShape(8.dp)
                )

                if (parsedAmount != null && parsedAmount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Terbaca: ${PriceFormatter.formatPrice(parsedAmount)}",
                        color = TvBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (inputError != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = inputError ?: "",
                        color = TvRed,
                        fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Dual Action Buttons for Manual Input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Button 1: Tambah ke Saldo
                    Button(
                        onClick = {
                            val amt = parsedAmount
                            if (amt == null || amt <= 0.0) {
                                inputError = "Masukkan nominal valid (> 0)"
                            } else {
                                onTopUp(amt)
                                onDismiss()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text("+ Tambah Saldo", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // Button 2: Atur Ulang Nominal Jadi...
                    Button(
                        onClick = {
                            val amt = parsedAmount
                            if (amt == null || amt <= 0.0) {
                                inputError = "Masukkan nominal valid (> 0)"
                            } else {
                                if (onSetBalance != null) {
                                    onSetBalance(amt)
                                } else {
                                    onTopUp(amt)
                                }
                                onDismiss()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TvBlue),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text("= Atur Modal", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Pilih Cepat Nominal (Quick Fill / Top Up):",
                    color = TvTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(8.dp))

                // Quick Top Up Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickAmounts.take(3).forEach { amount ->
                        OutlinedButton(
                            onClick = {
                                manualInput = amount.toLong().toString()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = TvSurfaceVariant),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(TvBorder)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "+${formatShort(amount)}",
                                color = TvTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickAmounts.takeLast(3).forEach { amount ->
                        OutlinedButton(
                            onClick = {
                                manualInput = amount.toLong().toString()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = TvSurfaceVariant),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(TvBorder)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "+${formatShort(amount)}",
                                color = TvTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Reset Button
                OutlinedButton(
                    onClick = {
                        onReset()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TvRed),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(TvRed.copy(alpha = 0.5f))),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset",
                        tint = TvRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Reset Akun & Saldo ke Rp 10.000.000",
                        color = TvRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun parseManualAmount(input: String): Double? {
    val clean = input.trim().replace(".", "").replace(",", "")
    return clean.toDoubleOrNull()
}

private fun formatShort(amount: Double): String {
    return when {
        amount >= 1_000_000 -> "${(amount / 1_000_000).toInt()} Jt"
        amount >= 1_000 -> "${(amount / 1_000).toInt()} Rb"
        else -> amount.toInt().toString()
    }
}

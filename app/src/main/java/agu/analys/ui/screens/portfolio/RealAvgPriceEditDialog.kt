package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun RealAvgPriceEditDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    coinUpper: String,
    availableQty: Double,
    initialAvgBuy: Double,
    onSave: (coin: String, newAvgPrice: Double, newInvested: Double) -> Unit
) {
    if (!show) return

    val initialCost = if (initialAvgBuy > 0.0 && availableQty > 0.0) initialAvgBuy * availableQty else 0.0
    var avgBuyInput by remember(coinUpper, initialAvgBuy) {
        mutableStateOf(if (initialAvgBuy > 0.0) PriceFormatter.formatIdrNumber(initialAvgBuy) else "")
    }
    var totalCostInput by remember(coinUpper, initialCost) {
        mutableStateOf(if (initialCost > 0.0) PriceFormatter.formatIdrNumber(initialCost) else "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TvSurface,
        titleContentColor = TvTextPrimary,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.EditNote,
                    contentDescription = null,
                    tint = TvGreen,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Edit Modal Beli: $coinUpper",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TvTextPrimary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Perbarui harga beli rata-rata akun Tokocrypto Anda. Data ini menjadi Single Source of Truth (SSOT) untuk notifikasi, sinyal jual, profit badge, dan trailing stop.",
                    color = TvTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                if (availableQty > 0.0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = TvSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Saldo Koin:", color = TvTextSecondary, fontSize = 11.sp)
                            Text(
                                "${PriceFormatter.formatCryptoExact(availableQty, 8)} $coinUpper",
                                color = TvGreen,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = avgBuyInput,
                    onValueChange = { input ->
                        avgBuyInput = input
                        val p = PriceFormatter.parseCleanIdrDouble(input)
                        if (p > 0.0 && availableQty > 0.0) {
                            val calcTotal = p * availableQty
                            totalCostInput = PriceFormatter.formatIdrNumber(calcTotal)
                        }
                    },
                    label = { Text("Harga Rata-Rata Beli (IDR)", fontSize = 11.sp) },
                    placeholder = { Text("Contoh: 1.350.000.000", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvGreen,
                        unfocusedBorderColor = TvBorder,
                        focusedContainerColor = TvSurfaceVariant,
                        unfocusedContainerColor = TvSurfaceVariant,
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = totalCostInput,
                    onValueChange = { input ->
                        totalCostInput = input
                        val total = PriceFormatter.parseCleanIdrDouble(input)
                        if (total > 0.0 && availableQty > 0.0) {
                            val calcPrice = total / availableQty
                            avgBuyInput = PriceFormatter.formatIdrNumber(calcPrice)
                        }
                    },
                    label = { Text("Total Modal Pembelian (IDR)", fontSize = 11.sp) },
                    placeholder = { Text("Contoh: 50.000.000", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvGreen,
                        unfocusedBorderColor = TvBorder,
                        focusedContainerColor = TvSurfaceVariant,
                        unfocusedContainerColor = TvSurfaceVariant,
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedPrice = PriceFormatter.parseCleanIdrDouble(avgBuyInput)
                    val parsedTotal = PriceFormatter.parseCleanIdrDouble(totalCostInput)
                    val finalPrice = if (parsedPrice > 0.0) {
                        parsedPrice
                    } else if (parsedTotal > 0.0 && availableQty > 0.0) {
                        parsedTotal / availableQty
                    } else 0.0
                    val finalTotal = if (parsedTotal > 0.0) parsedTotal else finalPrice * availableQty

                    if (finalPrice > 0.0) {
                        onSave(coinUpper, finalPrice, finalTotal)
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = TvGreen, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Simpan", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = TvTextSecondary, fontSize = 12.sp)
            }
        }
    )
}

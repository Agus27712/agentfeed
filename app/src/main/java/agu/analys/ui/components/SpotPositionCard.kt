package agu.analys.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.AISignalState
import agu.analys.model.SignalAction
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionStore
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import kotlin.math.abs

@Composable
fun SpotPositionCard(
    symbol: String,
    signal: AISignalState,
    position: SpotPosition,
    sellSignalState: agu.analys.model.SellSignalState? = null,
    currentPrice: Double = 0.0,
    quoteAsset: String = "IDR",
    onPositionChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val store = remember(context) { SpotPositionStore(context) }
    var showDialog by remember { mutableStateOf(false) }
    var investedInput by remember { mutableStateOf("") }
    var entryInput by remember { mutableStateOf("") }

    val displaySymbol = symbol.removeSuffix("IDR").removeSuffix("USDT").uppercase()

    val currentValue = if (position.isHolding && currentPrice > 0.0) position.quantity * currentPrice else 0.0
    val profitLoss = if (position.isHolding) currentValue - position.investedAmount else 0.0
    val profitPercent = if (position.isHolding && position.investedAmount > 0.0) profitLoss / position.investedAmount * 100.0 else 0.0
    val profitColor = when {
        profitLoss > 0.0 -> TvGreen
        profitLoss < 0.0 -> TvRed
        else -> TvTextSecondary
    }

    val isEmergencySell = sellSignalState?.state == agu.analys.model.SellLifecycleState.STOP_LOSS_HIT || sellSignalState?.state == agu.analys.model.SellLifecycleState.RAPID_DROP_EXIT
    val isProfitSell = sellSignalState?.state == agu.analys.model.SellLifecycleState.READY_TO_SELL || sellSignalState?.state == agu.analys.model.SellLifecycleState.TRAILING_TRIGGERED
    val isReadyToSell = isProfitSell || isEmergencySell

    val cardBorderColor = when {
        isEmergencySell -> TvRed
        isProfitSell -> TvGreen
        else -> TvBorder
    }
    val buttonLabel = when {
        isEmergencySell -> "⚠️ Exit Darurat — ${sellSignalState?.reason ?: "Proteksi Modal"}"
        isProfitSell -> "🔥 Siap Jual — ${agu.analys.util.PriceFormatter.formatPercentage(sellSignalState?.netProfitPct ?: 0.0, includePlusSign = true)}"
        else -> "Ubah Data Pembelian"
    }
    val buttonColor = when {
        isEmergencySell -> TvRed.copy(alpha = 0.2f)
        isProfitSell -> TvGreen.copy(alpha = 0.2f)
        else -> TvSurfaceVariant
    }
    val buttonTextColor = when {
        isEmergencySell -> TvRed
        isProfitSell -> TvGreen
        else -> TvTextPrimary
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = TvSurface,
            titleContentColor = TvTextPrimary,
            textContentColor = TvTextSecondary,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccountBalanceWallet, null, tint = TvBlue, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Text("ATUR POSISI SAYA", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = TvBlue)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TvSurfaceVariant, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Default.Info, null, tint = TvBlue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Text(
                            "Masukkan data pembelian Anda. Aplikasi akan menghitung jumlah koin dan profit/loss secara otomatis.",
                            fontSize = 12.sp,
                            color = TvTextSecondary,
                            lineHeight = 17.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Punya $displaySymbol", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
                        Switch(
                            checked = true,
                            onCheckedChange = { },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = TvBlue
                            )
                        )
                    }

                    OutlinedTextField(
                        value = investedInput,
                        onValueChange = { investedInput = normalizeDecimalInput(it) },
                        modifier = Modifier.fillMaxWidth().testTag("position_invested_input"),
                        label = { Text("Nilai Pembelian ($quoteAsset)", fontSize = 12.sp) },
                        placeholder = { Text(if (quoteAsset.equals("IDR", true)) "100.000" else "100", color = TvTextSecondary.copy(alpha = 0.5f)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvBlue,
                            unfocusedBorderColor = TvBorder,
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary,
                            focusedLabelColor = TvBlue,
                            unfocusedLabelColor = TvTextSecondary,
                            cursorColor = TvBlue,
                            focusedContainerColor = TvSurfaceVariant,
                            unfocusedContainerColor = TvSurfaceVariant
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    OutlinedTextField(
                        value = entryInput,
                        onValueChange = { entryInput = normalizeDecimalInput(it) },
                        modifier = Modifier.fillMaxWidth().testTag("position_entry_input"),
                        label = { Text("Harga Beli per $displaySymbol ($quoteAsset)", fontSize = 12.sp) },
                        placeholder = { Text(if (currentPrice > 0.0) PriceFormatter.formatPrice(currentPrice, quoteAsset = quoteAsset) else "Harga beli", color = TvTextSecondary.copy(alpha = 0.5f)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvBlue,
                            unfocusedBorderColor = TvBorder,
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary,
                            focusedLabelColor = TvBlue,
                            unfocusedLabelColor = TvTextSecondary,
                            cursorColor = TvBlue,
                            focusedContainerColor = TvSurfaceVariant,
                            unfocusedContainerColor = TvSurfaceVariant
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    val amount = investedInput.toDoubleOrNull() ?: 0.0
                    val price = entryInput.toDoubleOrNull() ?: 0.0
                    if (amount > 0.0 && price > 0.0) {
                        val quantity = amount / price
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TvSurfaceVariant, RoundedCornerShape(12.dp))
                                .border(1.dp, TvBorder, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Jumlah $displaySymbol (otomatis)", fontSize = 11.sp, color = TvTextSecondary)
                                Text("${formatPositionQuantity(quantity)} $displaySymbol", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
                            }
                            if (currentPrice > 0.0) {
                                val valueNow = quantity * currentPrice
                                val pnl = valueNow - amount
                                val pct = pnl / amount * 100.0
                                Spacer(Modifier.height(6.dp))
                                Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Estimasi Nilai Sekarang", fontSize = 11.sp, color = TvTextSecondary)
                                    Text(PriceFormatter.formatPrice(valueNow, quoteAsset = quoteAsset), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Estimasi P/L", fontSize = 11.sp, color = TvTextSecondary)
                                    Text(
                                        "${formatSignedMoney(pnl, quoteAsset)} (${formatSignedPct(pct)})",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (pnl >= 0) TvGreen else TvRed
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = investedInput.toDoubleOrNull()?.let { it > 0.0 } == true &&
                            entryInput.toDoubleOrNull()?.let { it > 0.0 } == true,
                    onClick = {
                        store.markBought(symbol, investedInput.toDouble(), parseDecimal(entryInput)!!)
                        showDialog = false
                        onPositionChanged()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TvBlue,
                        disabledContainerColor = TvBorder
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("SIMPAN POSISI", fontWeight = FontWeight.ExtraBold, color = Color.White, fontSize = 14.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Batal", color = TvTextSecondary)
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TvCardBackground, RoundedCornerShape(16.dp))
            .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("POSISI SAYA", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvTextPrimary, letterSpacing = 0.6.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (position.isHolding) "Punya $displaySymbol" else "Belum punya",
                    fontSize = 11.sp,
                    color = TvTextSecondary
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Switch(
                    checked = position.isHolding,
                    onCheckedChange = { checked ->
                        if (checked) {
                            investedInput = if (position.investedAmount > 0) position.investedAmount.toString() else ""
                            entryInput = if (position.entryPrice > 0) position.entryPrice.toString() else ""
                            showDialog = true
                        } else {
                            store.markSold(symbol)
                            onPositionChanged()
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = TvBlue
                    ),
                    modifier = Modifier.testTag("asset_ownership_switch")
                )
            }
        }

        if (position.isHolding) {
            Spacer(Modifier.height(12.dp))
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PositionValue("Modal Investasi", PriceFormatter.formatPrice(position.investedAmount, quoteAsset = quoteAsset), Modifier.weight(1f))
                PositionValue("Harga Sekarang", PriceFormatter.formatPrice(currentPrice, quoteAsset = quoteAsset), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PositionValue("Harga Beli", PriceFormatter.formatPrice(position.entryPrice, quoteAsset = quoteAsset), Modifier.weight(1f))
                PositionValue("Nilai Sekarang", PriceFormatter.formatPrice(currentValue, quoteAsset = quoteAsset), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PositionValue("Jumlah $displaySymbol", formatPositionQuantity(position.quantity), Modifier.weight(1f))
                PositionValue("P/L", "${formatSignedMoney(profitLoss, quoteAsset)} (${formatSignedPct(profitPercent)})", Modifier.weight(1f), profitColor)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, null, tint = TvTextSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text("Data diperbarui otomatis setiap perubahan harga.", fontSize = 10.sp, color = TvTextSecondary)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    investedInput = position.investedAmount.toString()
                    entryInput = position.entryPrice.toString()
                    showDialog = true
                },
                modifier = Modifier.fillMaxWidth().height(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                shape = RoundedCornerShape(9.dp)
            ) { Text(buttonLabel, fontSize = 11.sp, color = buttonTextColor, fontWeight = if (isReadyToSell) FontWeight.ExtraBold else FontWeight.Normal) }
        } else {
            Spacer(Modifier.height(10.dp))
            Text(
                "Masukkan data pembelian kamu biar bisa liat estimasi P/L real-time.",
                fontSize = 11.sp,
                color = TvTextSecondary,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { showDialog = true },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TvBlue),
                shape = RoundedCornerShape(12.dp)
            ) { Text("ATUR POSISI", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color.White) }
        }
    }
}

@Composable
private fun PositionValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = TvTextPrimary) {
    Column(modifier) {
        Text(label, fontSize = 9.sp, color = TvTextSecondary)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 2)
    }
}

private fun formatPositionQuantity(value: Double): String = when {
    value <= 0.0 -> "-"
    value >= 1.0 -> String.format("%.10f", value).trimEnd('0').trimEnd('.')
    else -> String.format("%.16f", value).trimEnd('0').trimEnd('.')
}

private fun formatSignedMoney(value: Double, quoteAsset: String = "IDR"): String =
    (if (value >= 0) "+" else "-") + PriceFormatter.formatPrice(abs(value), quoteAsset = quoteAsset)

private fun formatSignedPct(value: Double): String =
    PriceFormatter.formatPercentage(value, includePlusSign = true)

private fun normalizeDecimalInput(value: String): String {
    val normalized = value.replace(',', '.')
    if (normalized.count { it == '.' } > 1) return normalized.dropLast(1)
    return normalized.filter { it.isDigit() || it == '.' }
}

private fun parseDecimal(value: String): Double? =
    value.replace(',', '.').toDoubleOrNull()

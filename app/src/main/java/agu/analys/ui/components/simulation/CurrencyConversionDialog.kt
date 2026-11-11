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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
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
import agu.analys.util.ExchangeRateManager
import agu.analys.util.PriceFormatter

/**
 * Dialog konversi saldo Portofolio Simulasi: **Rupiah (Rp) ⇄ USDT ($)**.
 *
 * Kurs yang dipakai **selalu** berasal dari [ExchangeRateManager] (rate real-time dari
 * exchange / cache terakhir). Tidak ada satu pun konstanta kurs di file ini:
 * selama rate belum tersedia (`<= 0.0`), tombol konversi dinonaktifkan dan UI
 * menampilkan status "mengambil rate…" alih-alih memakai angka tebakan.
 *
 * @param wallet dompet simulasi saat ini (saldo IDR & USDT)
 * @param onConvertIdrToUsdt panggil ke ViewModel untuk konversi Rp → $
 * @param onConvertUsdtToIdr panggil ke ViewModel untuk konversi $ → Rp
 * @param onRefreshRate minta refresh rate ke exchange (opsional)
 * @param onDismiss tutup dialog
 */
@Composable
fun CurrencyConversionDialog(
    wallet: SimulationWallet,
    onConvertIdrToUsdt: (Double) -> String,
    onConvertUsdtToIdr: (Double) -> String,
    onRefreshRate: () -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    // Rate real-time dari ExchangeRateManager — sumber tunggal kurs (tanpa hardcode).
    val rate by ExchangeRateManager.usdtIdrRate.collectAsState()
    val rateAvailable = rate > 0.0

    // true = Rp -> USDT, false = USDT -> Rp
    var idrToUsdt by remember { mutableStateOf(true) }

    var amountInput by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val parsedAmount = remember(amountInput) {
        PriceFormatter.parseCleanDouble(amountInput)
    }

    val sourceBalance = if (idrToUsdt) wallet.getAvailableIdr() else wallet.getAvailableUsdt()
    val sourceQuote = if (idrToUsdt) "IDR" else "USDT"
    val targetQuote = if (idrToUsdt) "USDT" else "IDR"

    val estimatedTarget: Double? = remember(parsedAmount, rate, idrToUsdt) {
        if (parsedAmount <= 0.0 || rate <= 0.0) null
        else if (idrToUsdt) parsedAmount / rate
        else parsedAmount * rate
    }

    val quickFractions = listOf(0.25, 0.5, 0.75, 1.0)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
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
                // HEADER
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "KONVERSI SALDO",
                            color = TvTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "Tukar Rupiah ⇄ USDT untuk pair berkuotasi $",
                            color = TvTextSecondary,
                            fontSize = 10.sp
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Tutup",
                            tint = TvTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ===== KARTU KURS (REAL-TIME) =====
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
                        .border(1.dp, TvBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Kurs USDT / IDR",
                            color = TvTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        if (rateAvailable) {
                            Text(
                                text = "${PriceFormatter.formatPrice(rate, quoteAsset = "IDR", decimals = 0)} / USDT",
                                color = TvTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "Live dari exchange • update tiap 60 detik",
                                color = TvGreen,
                                fontSize = 9.sp
                            )
                        } else {
                            Text(
                                text = "Mengambil rate…",
                                color = TvAmber,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Konversi ditahan sampai kurs tersedia",
                                color = TvTextSecondary,
                                fontSize = 9.sp
                            )
                        }
                    }
                    IconButton(onClick = onRefreshRate, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh kurs",
                            tint = TvBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ===== ARAH KONVERSI + TOMBOL SWAP =====
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvBackground, RoundedCornerShape(10.dp))
                        .border(1.dp, TvBorder, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Dari",
                            color = TvTextSecondary,
                            fontSize = 9.sp
                        )
                        Text(
                            text = if (idrToUsdt) "Rupiah (Rp)" else "USDT ($)",
                            color = if (idrToUsdt) TvBlue else TvGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tersedia ${PriceFormatter.formatPrice(sourceBalance, quoteAsset = sourceQuote)}",
                            color = TvTextSecondary,
                            fontSize = 9.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(TvSurfaceVariant)
                            .border(1.dp, TvBorder, RoundedCornerShape(10.dp))
                            .clickable {
                                idrToUsdt = !idrToUsdt
                                amountInput = ""
                                resultMessage = null
                                isError = false
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = "Balik arah konversi",
                            tint = TvTextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = "Ke",
                            color = TvTextSecondary,
                            fontSize = 9.sp
                        )
                        Text(
                            text = if (idrToUsdt) "USDT ($)" else "Rupiah (Rp)",
                            color = if (idrToUsdt) TvGreen else TvBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (idrToUsdt) "untuk pair BTC/USDT" else "untuk pair BTC/IDR",
                            color = TvTextSecondary,
                            fontSize = 9.sp
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ===== INPUT NOMINAL =====
                Text(
                    text = "Nominal ${if (idrToUsdt) "Rupiah" else "USDT"} yang dikonversi",
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = amountInput,
                    onValueChange = {
                        amountInput = it
                        resultMessage = null
                        isError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = if (idrToUsdt) "contoh: 500.000" else "contoh: 30",
                            color = TvTextSecondary.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary,
                        focusedBorderColor = TvBlue,
                        unfocusedBorderColor = TvBorder,
                        cursorColor = TvBlue,
                        focusedContainerColor = TvBackground,
                        unfocusedContainerColor = TvBackground
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                )

                Spacer(Modifier.height(8.dp))

                // ===== QUICK PERCENT =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickFractions.forEach { fraction ->
                        val label = "${(fraction * 100).toInt()}%"
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TvSurfaceVariant)
                                .border(1.dp, TvBorder, RoundedCornerShape(8.dp))
                                .clickable {
                                    val value = sourceBalance * fraction
                                    amountInput = if (idrToUsdt) {
                                        PriceFormatter.formatRawDecimal(kotlin.math.floor(value))
                                    } else {
                                        PriceFormatter.formatRawDecimal(value)
                                    }
                                    resultMessage = null
                                    isError = false
                                }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = TvTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ===== ESTIMASI HASIL =====
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvSurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Estimasi diterima",
                        color = TvTextSecondary,
                        fontSize = 10.sp
                    )
                    Text(
                        text = estimatedTarget?.let {
                            PriceFormatter.formatPrice(it, quoteAsset = targetQuote)
                        } ?: "—",
                        color = if (estimatedTarget != null) TvTextPrimary else TvTextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                if (!rateAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Kurs belum tersedia dari exchange — konversi dinonaktifkan agar nilai $ dan Rp tidak tercampur.",
                        color = TvAmber,
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
                }

                if (resultMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                (if (isError) TvRed else TvGreen).copy(alpha = 0.12f),
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                1.dp,
                                (if (isError) TvRed else TvGreen).copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = resultMessage!!,
                            color = if (isError) TvRed else TvGreen,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 13.sp
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ===== TOMBOL AKSI =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                    ) {
                        Text("Batal", color = TvTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val message = if (idrToUsdt) {
                                onConvertIdrToUsdt(parsedAmount)
                            } else {
                                onConvertUsdtToIdr(parsedAmount)
                            }
                            resultMessage = message
                            // Pesan sukses selalu diawali "✅", selain itu dianggap gagal.
                            isError = !message.startsWith("✅")
                            if (!isError) amountInput = ""
                        },
                        enabled = rateAvailable && parsedAmount > 0.0 && parsedAmount <= sourceBalance,
                        modifier = Modifier.weight(1.4f).height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TvBlue,
                            disabledContainerColor = TvSurfaceVariant
                        )
                    ) {
                        Text(
                            text = if (idrToUsdt) "Konversi ke USDT" else "Konversi ke Rupiah",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "Konversi memakai kurs live dari exchange, bukan angka tetap. Saldo USDT dipakai otomatis saat order di pair berkuotasi USDT.",
                    color = TvTextSecondary,
                    fontSize = 9.sp,
                    lineHeight = 12.sp
                )
            }
        }
    }
}
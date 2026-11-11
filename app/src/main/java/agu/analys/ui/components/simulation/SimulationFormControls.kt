package agu.analys.ui.components.simulation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*

@Composable
fun StepperInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onStepMinus: () -> Unit,
    onStepPlus: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(TvSurfaceVariant)
            .border(1.dp, TvBorder, RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(34.dp)
                .fillMaxHeight()
                .clickable { onStepMinus() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "Minus",
                tint = TvTextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (value.isEmpty()) {
                Text(
                    text = label,
                    color = TvTextSecondary.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(normalizeSimulationDecimalInput(it)) },
                textStyle = TextStyle(
                    color = TvTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                cursorBrush = SolidColor(TvGreen),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Box(
            modifier = Modifier
                .width(34.dp)
                .fillMaxHeight()
                .clickable { onStepPlus() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Plus",
                tint = TvTextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Normalisasi input angka lokal:
 * - koma → titik (1,5 → 1.5)
 * - hanya satu titik desimal
 * - buang karakter non-digit selain titik
 */
fun normalizeSimulationDecimalInput(value: String): String {
    val normalized = value.replace(',', '.')
    if (normalized.count { it == '.' } > 1) {
        val firstDot = normalized.indexOf('.')
        return normalized.filterIndexed { index, c ->
            c.isDigit() || (c == '.' && index == firstDot)
        }
    }
    return normalized.filter { it.isDigit() || it == '.' }
}

fun parseSimulationDecimal(value: String): Double? =
    normalizeSimulationDecimalInput(value).toDoubleOrNull()

fun calculatePriceStep(price: Double): Double {
    return when {
        price >= 1_000_000 -> 10_000.0
        price >= 100_000 -> 1_000.0
        price >= 10_000 -> 100.0
        price >= 1_000 -> 10.0
        price >= 100 -> 1.0
        price >= 10 -> 0.1
        price >= 1 -> 0.01
        price >= 0.1 -> 0.001
        else -> 0.0001
    }
}

fun calculateCoinStep(coinQty: Double, price: Double): Double {
    return when {
        coinQty >= 1000 -> 100.0
        coinQty >= 100 -> 10.0
        coinQty >= 10 -> 1.0
        coinQty >= 1 -> 0.1
        price >= 1_000_000 -> 0.0001
        price >= 1 -> 0.01
        else -> 0.001
    }
}

fun formatCoinDecimals(qty: Double): String {
    if (qty == 0.0) return "0"
    return if (qty >= 1000) {
        String.format("%.2f", qty)
    } else if (qty >= 1) {
        String.format("%.4f", qty).trimEnd('0').trimEnd('.')
    } else {
        String.format("%.8f", qty).trimEnd('0').trimEnd('.')
    }
}

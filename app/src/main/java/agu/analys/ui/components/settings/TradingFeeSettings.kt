package agu.analys.ui.components.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*

@Composable
fun TradingFeeSettings(
    buyMaker: String,
    buyTaker: String,
    sellMaker: String,
    sellTaker: String,
    onBuyMakerChange: (String) -> Unit,
    onBuyTakerChange: (String) -> Unit,
    onSellMakerChange: (String) -> Unit,
    onSellTakerChange: (String) -> Unit
) {
    SectionHeader("BIAYA TRADING (NET R:R & RADAR STATUS)")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Digunakan untuk menghitung estimasi biaya transaksi di Card Radar Live dan Net Risk-to-Reward riil.",
                color = TvTextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeeInputField("Beli Maker (%) - Limit", buyMaker, onBuyMakerChange, Modifier.weight(1f))
                FeeInputField("Beli Taker (%) - Instant", buyTaker, onBuyTakerChange, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeeInputField("Jual Maker (%) - Limit", sellMaker, onSellMakerChange, Modifier.weight(1f))
                FeeInputField("Jual Taker (%) - Instant", sellTaker, onSellTakerChange, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FeeInputField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = TvTextSecondary, fontSize = 10.sp)
        Spacer(Modifier.height(3.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TvGreen,
                unfocusedBorderColor = TvBorder,
                focusedTextColor = TvTextPrimary,
                unfocusedTextColor = TvTextPrimary
            )
        )
    }
}

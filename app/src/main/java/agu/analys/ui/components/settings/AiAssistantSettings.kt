package agu.analys.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*

@Composable
fun AiAssistantSettings(
    geminiKey: String,
    onKeyChange: (String) -> Unit
) {
    SectionHeader("INTEGRASI AI ASSISTANT")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("PROVIDER AI AKTIF", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvBlue)
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvBlue.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .border(1.dp, TvBlue, RoundedCornerShape(6.dp))
                    .padding(vertical = 10.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text("GOOGLE GEMINI FLASH", color = TvBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "API KEY GEMINI",
                fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = geminiKey,
                onValueChange = onKeyChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Masukkan API Key Gemini...", fontSize = 12.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TvBlue,
                    unfocusedBorderColor = TvBorder,
                    focusedTextColor = TvTextPrimary,
                    unfocusedTextColor = TvTextPrimary
                )
            )
        }
    }
}

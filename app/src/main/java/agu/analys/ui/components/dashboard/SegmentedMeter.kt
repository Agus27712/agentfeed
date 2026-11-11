package agu.analys.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

/**
 * 10-segment meter bar as seen in mockup:
 * Volume   8/10  [■■■■■■■■□□]
 * Momentum 7/10  [■■■■■■■□□□]
 */
@Composable
fun SegmentedMeterRow(
    label: String,
    score: Int,
    maxScore: Int = 10,
    activeColor: Color = TvGreen,
    inactiveColor: Color = Color(0xFF1E2836)
) {
    val clampedScore = score.coerceIn(0, maxScore)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TvTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = "$clampedScore/$maxScore",
            color = TvTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(36.dp)
        )
        Spacer(Modifier.width(4.dp))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 1..maxScore) {
                val isActive = i <= clampedScore
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .background(
                            color = if (isActive) activeColor else inactiveColor,
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }
        }
    }
}

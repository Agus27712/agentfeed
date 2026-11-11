package agu.analys.ui.util

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Modifier untuk mendeteksi gestur swipe back dari tepi kiri layar (Edge Swipe Back).
 * Mengembalikan user ke layar sebelumnya saat digeser dari kiri ke kanan.
 */
fun Modifier.edgeSwipeBack(
    enabled: Boolean = true,
    edgeThreshold: Dp = 60.dp,
    swipeThreshold: Dp = 70.dp,
    onBack: () -> Unit
): Modifier = if (!enabled) this else this.pointerInput(enabled) {
    val edgeThresholdPx = edgeThreshold.toPx()
    val swipeThresholdPx = swipeThreshold.toPx()
    var startFromEdge = false
    var totalDragX = 0f

    detectHorizontalDragGestures(
        onDragStart = { offset ->
            startFromEdge = offset.x <= edgeThresholdPx
            totalDragX = 0f
        },
        onHorizontalDrag = { change, dragAmount ->
            if (startFromEdge) {
                totalDragX += dragAmount
                if (totalDragX > swipeThresholdPx) {
                    change.consume()
                }
            }
        },
        onDragEnd = {
            if (startFromEdge && totalDragX >= swipeThresholdPx) {
                onBack()
            }
            startFromEdge = false
            totalDragX = 0f
        },
        onDragCancel = {
            startFromEdge = false
            totalDragX = 0f
        }
    )
}

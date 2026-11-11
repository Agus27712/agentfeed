package agu.analys.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import kotlin.math.abs

/**
 * Mini sparkline dengan kurva halus (Smooth Cubic Bezier Spline) & subtle translucent gradient fill.
 * Mendukung data candle 1 jam asli (SSOT dari detail chart) dengan live price endpoint sync,
 * serta fallback sintesis data 24H nyata jika candle sedang memuat.
 */
@Composable
fun MiniSparkline(
    tick: MarketTick?,
    candles: List<CandleBar>? = null,
    modifier: Modifier = Modifier,
    lineColor: Color
) {
    val points = remember(tick?.price, candles) {
        if (!candles.isNullOrEmpty() && candles.size >= 2) {
            val list = candles.takeLast(30).map { it.close }.toMutableList()
            val livePrice = tick?.price ?: 0.0
            if (livePrice > 0.0 && livePrice.isFinite()) {
                list[list.lastIndex] = livePrice
            }
            list
        } else {
            sparkPoints(tick)
        }
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        if (points.size < 2) return@Canvas
        val minV = points.minOrNull() ?: return@Canvas
        val maxV = points.maxOrNull() ?: return@Canvas
        val range = (maxV - minV).takeIf { it > 0 } ?: 1.0
        val w = size.width
        val h = size.height
        val padX = 2.dp.toPx()
        val padY = 3.dp.toPx()
        val usableW = (w - 2 * padX).coerceAtLeast(1f)
        val usableH = (h - 2 * padY).coerceAtLeast(1f)

        val offsets = points.mapIndexed { i, v ->
            val x = padX + usableW * (i.toFloat() / (points.size - 1))
            val normalizedY = ((v - minV) / range).toFloat().coerceIn(0f, 1f)
            val y = padY + usableH * (1f - normalizedY)
            Offset(x, y)
        }

        val strokePath = Path()
        val fillPath = Path()

        strokePath.moveTo(offsets[0].x, offsets[0].y)
        fillPath.moveTo(offsets[0].x, offsets[0].y)

        // Smooth cubic Bezier spline interpolation antar titik
        for (i in 0 until offsets.size - 1) {
            val p0 = offsets[i]
            val p1 = offsets[i + 1]
            val midX = (p0.x + p1.x) / 2f

            strokePath.cubicTo(
                x1 = midX, y1 = p0.y,
                x2 = midX, y2 = p1.y,
                x3 = p1.x, y3 = p1.y
            )
            fillPath.cubicTo(
                x1 = midX, y1 = p0.y,
                x2 = midX, y2 = p1.y,
                x3 = p1.x, y3 = p1.y
            )
        }

        val lastPoint = offsets.last()
        val firstPoint = offsets.first()
        fillPath.lineTo(lastPoint.x, h)
        fillPath.lineTo(firstPoint.x, h)
        fillPath.close()

        // 1. Gambar gradient fill halus di bawah kurva
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    lineColor.copy(alpha = 0.25f),
                    lineColor.copy(alpha = 0.05f),
                    Color.Transparent
                ),
                startY = padY,
                endY = h
            )
        )

        // 2. Gambar garis kurva halus (Smooth Spline)
        drawPath(
            path = strokePath,
            color = lineColor,
            style = Stroke(
                width = 2.2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // 3. Glowing endpoint pada harga live terakhir
        drawCircle(
            color = lineColor.copy(alpha = 0.32f),
            radius = 4.2.dp.toPx(),
            center = lastPoint
        )
        drawCircle(
            color = lineColor,
            radius = 2.dp.toPx(),
            center = lastPoint
        )
    }
}

/**
 * Bangun 7 titik intraday dari data 24H nyata:
 * Open, Intraday Dip, Mid, High, Consolidation, Live Price.
 */
private fun sparkPoints(tick: MarketTick?): List<Double> {
    if (tick == null || tick.price <= 0) return emptyList()
    val price = tick.price
    val high = tick.high24h.takeIf { it > 0 } ?: price
    val low = tick.low24h.takeIf { it > 0 } ?: price
    val chg = tick.change24h.takeIf { it.isFinite() } ?: 0.0
    val open = if (abs(chg) < 99.0) price / (1.0 + chg / 100.0) else price
    val mid = (high + low) / 2.0

    return if (chg >= 0) {
        listOf(
            open,
            low * 0.75 + open * 0.25,
            mid * 0.92,
            high * 0.95,
            high,
            price * 0.98 + high * 0.02,
            price
        )
    } else {
        listOf(
            open,
            high * 0.75 + open * 0.25,
            mid * 1.08,
            low * 1.05,
            low,
            price * 1.02 + low * -0.02,
            price
        )
    }.map { it.coerceIn(minOf(low, open, price), maxOf(high, open, price)) }
}


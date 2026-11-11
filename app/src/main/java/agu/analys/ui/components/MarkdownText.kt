package agu.analys.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.TvBlue
import agu.analys.ui.theme.TvBorder
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvSurfaceVariant
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class BulletItem(val text: String) : MarkdownBlock()
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock()
    data class Callout(val text: String) : MarkdownBlock()
    data object Divider : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
}

/**
 * Parser Markdown sederhana & tangguh untuk merender advice AI dengan bullet, bold, heading, dan keterangan indikator.
 */
fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val lines = markdown.lines()
    val blocks = mutableListOf<MarkdownBlock>()

    for (rawLine in lines) {
        val line = rawLine.trim()
        if (line.isBlank()) continue

        when {
            line.startsWith("### ") -> blocks.add(MarkdownBlock.Heading(3, line.removePrefix("### ").trim()))
            line.startsWith("## ") -> blocks.add(MarkdownBlock.Heading(2, line.removePrefix("## ").trim()))
            line.startsWith("# ") -> blocks.add(MarkdownBlock.Heading(1, line.removePrefix("# ").trim()))
            line == "---" || line == "***" || line == "___" -> blocks.add(MarkdownBlock.Divider)
            line.startsWith("> ") -> blocks.add(MarkdownBlock.Callout(line.removePrefix("> ").trim()))
            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> {
                val content = line.substring(2).trim()
                blocks.add(MarkdownBlock.BulletItem(content))
            }
            Regex("""^\d+[\.\)]\s+.*""").matches(line) -> {
                val num = line.substringBefore(" ").trim()
                val content = line.substringAfter(" ").trim()
                blocks.add(MarkdownBlock.NumberedItem(num, content))
            }
            else -> blocks.add(MarkdownBlock.Paragraph(line))
        }
    }
    return blocks
}

/**
 * Mengubah teks dengan markdown inline (**bold**, *italic*, `code`, tag) menjadi AnnotatedString.
 */
@Composable
fun parseInlineMarkdown(
    text: String,
    defaultColor: Color = TvTextPrimary
): AnnotatedString {
    val greenColor = TvGreen
    val redColor = TvRed
    val blueColor = TvBlue
    val textColor = defaultColor

    return buildAnnotatedString {
        var cursor = 0
        val pattern = Regex("""(\*\*(.+?)\*\*)|(__(.+?)__)|(\*(.+?)\*)|(_(.+?)_)|(`(.+?)`)""")
        val matches = pattern.findAll(text)

        for (match in matches) {
            val range = match.range
            if (range.first > cursor) {
                append(text.substring(cursor, range.first))
            }

            val matchText = match.value
            when {
                (matchText.startsWith("**") && matchText.endsWith("**") && matchText.length >= 4) ||
                (matchText.startsWith("__") && matchText.endsWith("__") && matchText.length >= 4) -> {
                    val inner = matchText.substring(2, matchText.length - 2)
                    val color = when {
                        inner.contains("Jenuh Beli", ignoreCase = true) ||
                        inner.contains("Overbought", ignoreCase = true) ||
                        inner.contains("JUAL", ignoreCase = true) ||
                        inner.contains("SELL", ignoreCase = true) ||
                        inner.contains("Bearish", ignoreCase = true) -> redColor

                        inner.contains("Jenuh Jual", ignoreCase = true) ||
                        inner.contains("Oversold", ignoreCase = true) ||
                        inner.contains("BELI", ignoreCase = true) ||
                        inner.contains("BUY", ignoreCase = true) ||
                        inner.contains("Bullish", ignoreCase = true) -> greenColor

                        inner.contains("TAHAN", ignoreCase = true) ||
                        inner.contains("HOLD", ignoreCase = true) ||
                        inner.contains("Netral", ignoreCase = true) -> Color(0xFFFFB300)

                        else -> textColor
                    }
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color))
                    append(inner)
                    pop()
                }
                matchText.startsWith("`") && matchText.endsWith("`") && matchText.length >= 2 -> {
                    val inner = matchText.substring(1, matchText.length - 1)
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = blueColor, fontWeight = FontWeight.SemiBold))
                    append(" $inner ")
                    pop()
                }
                (matchText.startsWith("*") && matchText.endsWith("*") && matchText.length >= 2) ||
                (matchText.startsWith("_") && matchText.endsWith("_") && matchText.length >= 2) -> {
                    val inner = matchText.substring(1, matchText.length - 1)
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(inner)
                    pop()
                }
                else -> {
                    append(matchText)
                }
            }
            cursor = range.last + 1
        }

        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}

/**
 * Komponen MarkdownText untuk Jetpack Compose dengan tampilan Dark Mode TradingView yang elegan.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = TvTextPrimary,
    fontSize: TextUnit = 12.sp,
    lineHeight: TextUnit = 17.5.sp
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val (hSize, hWeight, hColor) = when (block.level) {
                        1 -> Triple(14.sp, FontWeight.ExtraBold, TvGreen)
                        2 -> Triple(13.sp, FontWeight.Bold, TvBlue)
                        else -> Triple(12.5.sp, FontWeight.Bold, Color(0xFF90CAF9))
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = parseInlineMarkdown(block.text, hColor),
                        fontSize = hSize,
                        fontWeight = hWeight,
                        color = hColor,
                        lineHeight = (hSize.value + 4).sp
                    )
                }
                is MarkdownBlock.BulletItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 2.dp, top = 1.dp, bottom = 1.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp, end = 7.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(TvGreen.copy(alpha = 0.8f))
                        )
                        Text(
                            text = parseInlineMarkdown(block.text, textColor),
                            fontSize = fontSize,
                            color = textColor,
                            lineHeight = lineHeight,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownBlock.NumberedItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 2.dp, top = 1.dp, bottom = 1.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp, top = 1.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(TvSurfaceVariant)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = block.number,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TvBlue
                            )
                        }
                        Text(
                            text = parseInlineMarkdown(block.text, textColor),
                            fontSize = fontSize,
                            color = textColor,
                            lineHeight = lineHeight,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownBlock.Callout -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(TvSurfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = parseInlineMarkdown(block.text, TvTextSecondary),
                            fontSize = 11.sp,
                            fontStyle = FontStyle.Italic,
                            color = TvTextSecondary,
                            lineHeight = 15.sp
                        )
                    }
                }
                is MarkdownBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 3.dp),
                        thickness = 0.6.dp,
                        color = TvBorder
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    if (block.text.isNotBlank()) {
                        Text(
                            text = parseInlineMarkdown(block.text, textColor),
                            fontSize = fontSize,
                            color = textColor,
                            lineHeight = lineHeight
                        )
                    }
                }
            }
        }
    }
}

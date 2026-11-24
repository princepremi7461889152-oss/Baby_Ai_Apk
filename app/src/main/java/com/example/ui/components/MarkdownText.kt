package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.example.ui.theme.NotoSansDevanagariFamily

@Composable
fun BabyMarkdownText(
    markdown: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    selectable: Boolean = true,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val codeBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    val inlineCodeBg = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val linkColor = MaterialTheme.colorScheme.primary

    val content: @Composable () -> Unit = {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.CodeFence -> {
                        Surface(
                            color = codeBg,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = block.code,
                                style = MaterialTheme.typography.bodySmall,
                                color = color,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    is MdBlock.Heading -> {
                        Text(
                            text = parseInlineMarkdown(block.text, inlineCodeBg, linkColor),
                            style = when (block.level) {
                                1 -> MaterialTheme.typography.titleLarge
                                2 -> MaterialTheme.typography.titleMedium
                                else -> MaterialTheme.typography.titleSmall
                            },
                            color = color
                        )
                    }
                    is MdBlock.Bullet -> {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = block.marker,
                                style = style.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = parseInlineMarkdown(block.text, inlineCodeBg, linkColor),
                                style = style,
                                color = color,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    is MdBlock.Paragraph -> {
                        Text(
                            text = parseInlineMarkdown(block.text, inlineCodeBg, linkColor),
                            style = style,
                            color = color
                        )
                    }
                }
            }
        }
    }

    if (selectable) {
        SelectionContainer { content() }
    } else {
        content()
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val marker: String, val text: String) : MdBlock
    data class CodeFence(val code: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

private fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    val lines = raw.replace("\r\n", "\n").split("\n")
    val out = mutableListOf<MdBlock>()
    var inCode = false
    val codeBuf = StringBuilder()
    val paraBuf = mutableListOf<String>()

    fun flushPara() {
        if (paraBuf.isNotEmpty()) {
            out += MdBlock.Paragraph(paraBuf.joinToString("\n"))
            paraBuf.clear()
        }
    }

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            flushPara()
            if (inCode) {
                out += MdBlock.CodeFence(codeBuf.toString().trimEnd())
                codeBuf.clear()
                inCode = false
            } else {
                inCode = true
            }
            continue
        }
        if (inCode) {
            codeBuf.appendLine(line)
            continue
        }
        if (trimmed.isEmpty()) {
            flushPara()
            continue
        }
        when {
            trimmed.startsWith("### ") -> {
                flushPara()
                out += MdBlock.Heading(3, trimmed.removePrefix("### ").trim())
            }
            trimmed.startsWith("## ") -> {
                flushPara()
                out += MdBlock.Heading(2, trimmed.removePrefix("## ").trim())
            }
            trimmed.startsWith("# ") -> {
                flushPara()
                out += MdBlock.Heading(1, trimmed.removePrefix("# ").trim())
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                flushPara()
                out += MdBlock.Bullet("•", trimmed.drop(2).trim())
            }
            Regex("""^\d+[.)]\s+.+""").matches(trimmed) -> {
                flushPara()
                val splitIdx = trimmed.indexOfFirst { it == '.' || it == ')' }
                val marker = trimmed.substring(0, splitIdx + 1)
                val body = trimmed.substring(splitIdx + 1).trim()
                out += MdBlock.Bullet(marker, body)
            }
            else -> paraBuf += line
        }
    }
    flushPara()
    if (inCode && codeBuf.isNotEmpty()) {
        out += MdBlock.CodeFence(codeBuf.toString().trimEnd())
    }
    return if (out.isEmpty()) listOf(MdBlock.Paragraph(raw)) else out
}

private fun parseInlineMarkdown(input: String, codeBg: Color, linkColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < input.length) {
            when {
                input[i] == '[' -> {
                    val closeBracket = input.indexOf(']', i + 1)
                    if (closeBracket > i + 1 && closeBracket + 1 < input.length && input[closeBracket + 1] == '(') {
                        val closeParen = input.indexOf(')', closeBracket + 2)
                        val label = input.substring(i + 1, closeBracket)
                        val url = if (closeParen > closeBracket + 2) input.substring(closeBracket + 2, closeParen).trim() else ""
                        if (closeParen > closeBracket + 2 && (url.startsWith("https://") || url.startsWith("http://"))) {
                            withLink(
                                LinkAnnotation.Url(
                                    url = url,
                                    styles = TextLinkStyles(
                                        style = SpanStyle(
                                            color = linkColor,
                                            textDecoration = TextDecoration.Underline,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                )
                            ) {
                                append(label)
                            }
                            i = closeParen + 1
                            continue
                        }
                    }
                    append('[')
                    i++
                }
                input.startsWith("**", i) -> {
                    val end = input.indexOf("**", i + 2)
                    if (end > i + 2) {
                        val inner = input.substring(i + 2, end)
                        val start = length
                        append(inner)
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                        applyDevanagariToRange(inner, start)
                        i = end + 2
                    } else {
                        append("**")
                        i += 2
                    }
                }
                input[i] == '`' -> {
                    val end = input.indexOf('`', i + 1)
                    if (end > i + 1) {
                        val inner = input.substring(i + 1, end)
                        val start = length
                        append(" $inner ")
                        addStyle(
                            SpanStyle(
                                background = codeBg,
                                fontWeight = FontWeight.Medium
                            ),
                            start,
                            length
                        )
                        applyDevanagariToRange(inner, start + 1)
                        i = end + 1
                    } else {
                        append('`')
                        i++
                    }
                }
                input[i] == '*' && !input.startsWith("**", i) -> {
                    val end = input.indexOf('*', i + 1)
                    if (end > i + 1) {
                        val inner = input.substring(i + 1, end)
                        val start = length
                        append(inner)
                        addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                        applyDevanagariToRange(inner, start)
                        i = end + 1
                    } else {
                        append('*')
                        i++
                    }
                }
                else -> {
                    val ch = input[i]
                    val idx = length
                    append(ch)
                    if (ch in '\u0900'..'\u097F') {
                        addStyle(SpanStyle(fontFamily = NotoSansDevanagariFamily), idx, idx + 1)
                    }
                    i++
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.applyDevanagariToRange(text: String, offset: Int) {
    text.forEachIndexed { index, c ->
        if (c in '\u0900'..'\u097F') {
            addStyle(SpanStyle(fontFamily = NotoSansDevanagariFamily), offset + index, offset + index + 1)
        }
    }
}

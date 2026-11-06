package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.cypher.parser.CypherParser

internal object CypherAstNames {
    fun parseIntLiteral(ctx: CypherParser.IntegerLiteralContext): Int {
        val text = ctx.text
        return when {
            text.startsWith("0x", true) -> java.lang.Long.decode(text).toInt()
            text.startsWith("0o", true) -> text.substring(OCTAL_PREFIX_LENGTH).toInt(OCTAL_RADIX)
            else -> text.toInt()
        }
    }

    fun parseStringLiteral(text: String): String {
        // Remove surrounding quotes
        val content = text.substring(1, text.length - 1)
        val quote = text[0]
        val sb = StringBuilder()
        var i = 0
        while (i < content.length) {
            val c = content[i]
            if (c == '\\' && i + 1 < content.length) {
                i = appendEscape(content, i + 1, sb)
            } else if (c == quote && i + 1 < content.length && content[i + 1] == quote) {
                // Escaped quote via doubling: '' or ""
                sb.append(quote)
                i++
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    fun getSymbolicName(ctx: CypherParser.VariableContext): String {
        return getSymbolicName(ctx.symbolicName())
    }

    fun getSymbolicName(ctx: CypherParser.LabelNameContext): String {
        return getSymbolicName(ctx.symbolicName())
    }

    fun getSymbolicName(ctx: CypherParser.RelTypeNameContext): String {
        return getSymbolicName(ctx.symbolicName())
    }

    fun getSymbolicName(ctx: CypherParser.PropertyKeyNameContext): String {
        return getSymbolicName(ctx.symbolicName())
    }

    fun getSymbolicName(ctx: CypherParser.SymbolicNameContext): String {
        val esc = ctx.ESC_LITERAL()
        if (esc != null) {
            // Remove backtick delimiters
            val text = esc.text
            return text.substring(1, text.length - 1)
        }
        return ctx.text
    }
    private fun appendEscape(content: String, index: Int, sb: StringBuilder): Int {
        when (content[index]) {
            '\\' -> sb.append('\\')
            '\'' -> sb.append('\'')
            '"' -> sb.append('"')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'b' -> sb.append('\b')
            'u' -> {
                if (index + UNICODE_ESCAPE_DIGITS < content.length) {
                    sb.append(content.substring(index + 1, index + UNICODE_ESCAPE_END_OFFSET).toInt(HEX_RADIX).toChar())
                    return index + UNICODE_ESCAPE_DIGITS
                }
            }
            else -> { sb.append('\\'); sb.append(content[index]) }
        }
        return index
    }

}

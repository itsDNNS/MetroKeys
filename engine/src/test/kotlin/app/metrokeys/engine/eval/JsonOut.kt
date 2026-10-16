package app.metrokeys.engine.eval

import java.util.Locale

/** A number written exactly as given (fixed formats: rates with one decimal). */
internal class Num(val text: String) {
    companion object {
        fun pct(x: Double): Num = Num(String.format(Locale.ROOT, "%.1f", x))
        fun of(x: Double, digits: Int): Num = Num(String.format(Locale.ROOT, "%.${digits}f", x))
    }
}

/** Deterministic JSON: maps with sorted keys, one-space indentation, fixed number formats. */
internal object JsonOut {
    fun write(v: Any?): String {
        val sb = StringBuilder()
        value(sb, v, 0)
        sb.append('\n')
        return sb.toString()
    }

    private fun value(sb: StringBuilder, v: Any?, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is Num -> sb.append(v.text)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v.toString())
            is Double -> sb.append(String.format(Locale.ROOT, "%.6g", v))
            is String -> string(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                val sorted = java.util.TreeMap<String, Any?>()
                for ((k, x) in v.entries) sorted[k.toString()] = x
                var i = 0
                for ((k, x) in sorted) {
                    indent(sb, depth + 1)
                    string(sb, k)
                    sb.append(": ")
                    value(sb, x, depth + 1)
                    if (++i < sorted.size) sb.append(',')
                    sb.append('\n')
                }
                indent(sb, depth)
                sb.append('}')
            }
            is Iterable<*> -> {
                val list = v.toList()
                if (list.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                for ((i, x) in list.withIndex()) {
                    indent(sb, depth + 1)
                    value(sb, x, depth + 1)
                    if (i < list.size - 1) sb.append(',')
                    sb.append('\n')
                }
                indent(sb, depth)
                sb.append(']')
            }
            else -> string(sb, v.toString())
        }
    }

    private fun indent(sb: StringBuilder, depth: Int) {
        repeat(depth) { sb.append(' ') }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}

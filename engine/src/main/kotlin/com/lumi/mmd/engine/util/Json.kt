package com.lumi.mmd.engine.util

/**
 * 很小的 JSON 解析 / 生成器（glTF 与工程文件用）。
 * 解析结果直接用 Kotlin 的 Map / List / String / Double / Boolean / null 表示，配合下面的扩展函数取值。
 */
object Json {
    class ParseException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (p.pos < text.length) throw ParseException("JSON 末尾有多余内容（位置 ${p.pos}）")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\uFEFF') pos++ else break
            }
        }

        fun value(depth: Int): Any? {
            if (depth > 200) throw ParseException("JSON 嵌套过深")
            if (pos >= s.length) throw ParseException("JSON 意外结束")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw ParseException("JSON 语法错误（位置 $pos，字符 '$c'）")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("JSON 语法错误（位置 $pos）")
            pos += word.length
            return v
        }

        private fun num(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length) {
                val c = s[pos]
                if (c in '0'..'9' || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++ else break
            }
            return s.substring(start, pos).toDoubleOrNull() ?: throw ParseException("JSON 数字格式错误（位置 $start）")
        }

        private fun str(): String {
            pos++ // 开头的引号
            val start = pos
            // 快路径：没有转义
            while (pos < s.length) {
                val c = s[pos]
                if (c == '"') { val r = s.substring(start, pos); pos++; return r }
                if (c == '\\') break
                pos++
            }
            val sb = StringBuilder(s.substring(start, pos))
            while (pos < s.length) {
                val c = s[pos++]
                if (c == '"') return sb.toString()
                if (c != '\\') { sb.append(c); continue }
                if (pos >= s.length) break
                when (val e = s[pos++]) {
                    '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 > s.length) throw ParseException("JSON 转义不完整")
                        sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                        pos += 4
                    }
                    else -> sb.append(e)
                }
            }
            throw ParseException("JSON 字符串没有结束")
        }

        private fun arr(depth: Int): List<Any?> {
            pos++
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return list }
            while (true) {
                skipWs()
                list.add(value(depth + 1))
                skipWs()
                if (pos >= s.length) throw ParseException("JSON 数组没有结束")
                val c = s[pos++]
                if (c == ']') return list
                if (c != ',') throw ParseException("JSON 数组缺少逗号（位置 ${pos - 1}）")
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') throw ParseException("JSON 对象的键必须是字符串（位置 $pos）")
                val k = str()
                skipWs()
                if (pos >= s.length || s[pos] != ':') throw ParseException("JSON 对象缺少冒号（位置 $pos）")
                pos++
                skipWs()
                map[k] = value(depth + 1)
                skipWs()
                if (pos >= s.length) throw ParseException("JSON 对象没有结束")
                val c = s[pos++]
                if (c == '}') return map
                if (c != ',') throw ParseException("JSON 对象缺少逗号（位置 ${pos - 1}）")
            }
        }
    }

    // ------------------------------------------------------------------ 生成

    fun stringify(v: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, v, pretty, 0)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?, pretty: Boolean, indent: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v.toString())
            is Int, is Long -> sb.append(v.toString())
            is Float -> writeNumber(sb, v.toDouble())
            is Double -> writeNumber(sb, v)
            is Number -> writeNumber(sb, v.toDouble())
            is String -> quote(sb, v)
            is FloatArray -> { sb.append('['); for ((i, x) in v.withIndex()) { if (i > 0) sb.append(','); writeNumber(sb, x.toDouble()) }; sb.append(']') }
            is IntArray -> { sb.append('['); for ((i, x) in v.withIndex()) { if (i > 0) sb.append(','); sb.append(x) }; sb.append(']') }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) newline(sb, indent + 1)
                    quote(sb, k.toString())
                    sb.append(if (pretty) ": " else ":")
                    write(sb, x, pretty, indent + 1)
                }
                if (pretty && !first) newline(sb, indent)
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) newline(sb, indent + 1)
                    write(sb, x, pretty, indent + 1)
                }
                if (pretty && !first) newline(sb, indent)
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun newline(sb: StringBuilder, indent: Int) {
        sb.append('\n')
        for (i in 0 until indent) sb.append("  ")
    }

    private fun writeNumber(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) { sb.append('0'); return }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d.toFloat().toString())
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

// ---- 取值用的扩展函数：类型不符时返回 null / 默认值，不抛异常 ----

@Suppress("UNCHECKED_CAST")
fun Any?.jObj(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.jArr(): List<Any?>? = this as? List<Any?>

fun Any?.jStr(): String? = this as? String
fun Any?.jNum(): Double? = (this as? Number)?.toDouble()
fun Any?.jInt(default: Int = -1): Int = (this as? Number)?.toInt() ?: default
fun Any?.jFloat(default: Float = 0f): Float = (this as? Number)?.toFloat() ?: default
fun Any?.jBool(default: Boolean = false): Boolean = this as? Boolean ?: default

fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key].jObj()
fun Map<String, Any?>.arr(key: String): List<Any?>? = this[key].jArr()
fun Map<String, Any?>.str(key: String): String? = this[key].jStr()
fun Map<String, Any?>.int(key: String, default: Int = -1): Int = this[key].jInt(default)
fun Map<String, Any?>.float(key: String, default: Float = 0f): Float = this[key].jFloat(default)
fun Map<String, Any?>.bool(key: String, default: Boolean = false): Boolean = this[key].jBool(default)

/** 读一个定长的数字数组到 FloatArray；缺失或长度不够时返回 null。 */
fun Map<String, Any?>.floats(key: String, count: Int): FloatArray? {
    val a = this[key].jArr() ?: return null
    if (a.size < count) return null
    return FloatArray(count) { a[it].jFloat() }
}

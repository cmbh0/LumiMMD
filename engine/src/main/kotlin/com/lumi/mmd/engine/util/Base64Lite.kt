package com.lumi.mmd.engine.util

/** 极简 Base64 编解码（glTF 的 data URI、MCP 返回截图用）。java.util.Base64 需要 Android 8.0，这里自己实现以兼容 7.0。 */
object Base64Lite {
    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): String {
        val sb = StringBuilder((length + 2) / 3 * 4)
        var i = offset
        val end = offset + length
        while (i + 2 < end) {
            val v = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
            sb.append(CHARS[v ushr 18]).append(CHARS[(v ushr 12) and 63]).append(CHARS[(v ushr 6) and 63]).append(CHARS[v and 63])
            i += 3
        }
        when (end - i) {
            1 -> { val v = (data[i].toInt() and 0xFF) shl 16; sb.append(CHARS[v ushr 18]).append(CHARS[(v ushr 12) and 63]).append("==") }
            2 -> {
                val v = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(CHARS[v ushr 18]).append(CHARS[(v ushr 12) and 63]).append(CHARS[(v ushr 6) and 63]).append('=')
            }
        }
        return sb.toString()
    }

    private val table = IntArray(256) { -1 }.also {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (i in chars.indices) it[chars[i].code] = i
        it['-'.code] = 62
        it['_'.code] = 63
    }

    /** 从 text 的 start 位置开始解码；忽略空白与非法字符，遇到 '=' 结束。 */
    fun decode(text: String, start: Int = 0): ByteArray {
        val n = text.length
        val out = ByteArray(((n - start).toLong() * 3 / 4).toInt() + 3)
        var o = 0
        var acc = 0
        var bits = 0
        for (i in start until n) {
            val c = text[i].code
            if (c == '='.code) break
            val v = if (c < 256) table[c] else -1
            if (v < 0) continue
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = (acc shr bits).toByte()
            }
        }
        return if (o == out.size) out else out.copyOf(o)
    }
}

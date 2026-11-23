package com.lumi.mmd.engine.util

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** MMD 生态里常见的几种文本编码及自动判别。 */
object TextCodec {
    val UTF8: Charset = Charsets.UTF_8
    val UTF16LE: Charset = Charsets.UTF_16LE
    val LATIN1: Charset = Charsets.ISO_8859_1

    /** Shift-JIS（优先用带 NEC/IBM 扩展字符的 windows-31j）。 */
    val SJIS: Charset = firstAvailable("windows-31j", "MS932", "Shift_JIS") ?: Charsets.ISO_8859_1
    val GBK: Charset = firstAvailable("GB18030", "GBK", "GB2312") ?: Charsets.ISO_8859_1
    val BIG5: Charset? = firstAvailable("Big5-HKSCS", "Big5")
    val EUCKR: Charset? = firstAvailable("x-windows-949", "EUC-KR")

    private fun firstAvailable(vararg names: String): Charset? {
        for (n in names) {
            try {
                if (Charset.isSupported(n)) return Charset.forName(n)
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun decodeStrict(bytes: ByteArray, offset: Int, length: Int, cs: Charset): String? {
        return try {
            cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    fun isAscii(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Boolean {
        for (i in offset until offset + length) if (bytes[i] < 0) return false
        return true
    }

    /**
     * 给一段未知编码的文本（文件名、.x/.obj/.vpd 内容等）打分并选出最可信的解码结果。
     * 顺序：ASCII → 严格 UTF-8 → 在 Shift-JIS / GBK(/Big5/EUC-KR) 中按"可疑字符"计分取最优。
     */
    fun decodeAuto(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size, preferGbk: Boolean = false): String {
        if (length <= 0) return ""
        var off = offset
        var len = length
        // BOM
        if (len >= 3 && bytes[off] == 0xEF.toByte() && bytes[off + 1] == 0xBB.toByte() && bytes[off + 2] == 0xBF.toByte()) {
            return String(bytes, off + 3, len - 3, UTF8)
        }
        if (len >= 2 && bytes[off] == 0xFF.toByte() && bytes[off + 1] == 0xFE.toByte()) {
            off += 2; len -= 2
            return String(bytes, off, len, UTF16LE)
        }
        if (isAscii(bytes, off, len)) return String(bytes, off, len, LATIN1)
        decodeStrict(bytes, off, len, UTF8)?.let { return it }

        var best: String? = null
        var bestScore = Int.MAX_VALUE
        val candidates = ArrayList<Charset>(4)
        if (preferGbk) { candidates.add(GBK); candidates.add(SJIS) } else { candidates.add(SJIS); candidates.add(GBK) }
        BIG5?.let { candidates.add(it) }
        EUCKR?.let { candidates.add(it) }
        for ((rank, cs) in candidates.withIndex()) {
            val s = String(bytes, off, len, cs)
            val score = suspicion(s) * 8 + rank
            if (score < bestScore) { bestScore = score; best = s }
        }
        return best ?: String(bytes, off, len, LATIN1)
    }

    /** 越大越像乱码。 */
    private fun suspicion(s: String): Int {
        var score = 0
        for (ch in s) {
            val c = ch.code
            when {
                c == 0xFFFD -> score += 40                      // 无法解码
                c in 0xFF61..0xFF9F -> score += 6               // 半角片假名：GBK 被当成 SJIS 的典型症状
                c in 0xE000..0xF8FF -> score += 20              // 私用区
                c in 0x80..0x9F -> score += 20                  // C1 控制字符
                c in 0x3040..0x30FF -> score -= 1               // 平/片假名：很像真日文
                c in 0x4E00..0x9FFF -> score += 0               // 常用汉字
                c in 0x3400..0x4DBF -> score += 3               // 扩展 A：生僻
                c in 0xAC00..0xD7A3 -> score += 1               // 谚文
                c > 0x2E80 -> score += 1
            }
        }
        return score
    }

    /** 把 "a\b/c" 这类路径统一为 '/' 分隔，并去掉首尾空白/引号。 */
    fun normalizePath(p: String): String {
        var s = p.trim().trim('"')
        s = s.replace('\\', '/')
        while (s.startsWith("./")) s = s.substring(2)
        return s
    }
}

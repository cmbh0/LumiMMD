package com.lumi.mmd.engine.util

import java.io.EOFException
import java.nio.charset.Charset

/** 小端二进制读取器（PMX/PMD/VMD/GLB/FBX 等格式共用）。 */
class BinaryReader(val data: ByteArray, start: Int = 0, val end: Int = data.size) {
    var position: Int = start

    val remaining: Int get() = end - position

    fun hasRemaining(n: Int = 1): Boolean = position + n <= end

    private fun need(n: Int) {
        if (n < 0 || position + n > end) {
            throw EOFException("文件数据不完整：在偏移 $position 处需要 $n 字节，但只剩 ${end - position} 字节")
        }
    }

    fun skip(n: Int) { need(n); position += n }

    fun u8(): Int { need(1); return data[position++].toInt() and 0xFF }

    fun i8(): Int { need(1); return data[position++].toInt() }

    fun u16(): Int {
        need(2)
        val p = position
        position = p + 2
        return (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
    }

    fun i16(): Int = u16().toShort().toInt()

    fun i32(): Int {
        need(4)
        val p = position
        position = p + 4
        return (data[p].toInt() and 0xFF) or
            ((data[p + 1].toInt() and 0xFF) shl 8) or
            ((data[p + 2].toInt() and 0xFF) shl 16) or
            ((data[p + 3].toInt() and 0xFF) shl 24)
    }

    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL

    fun i64(): Long {
        val lo = u32()
        val hi = u32()
        return lo or (hi shl 32)
    }

    fun f32(): Float = Float.fromBits(i32())

    fun f64(): Double = Double.fromBits(i64())

    fun bytes(n: Int): ByteArray {
        need(n)
        val out = data.copyOfRange(position, position + n)
        position += n
        return out
    }

    fun floats(dst: FloatArray, offset: Int, count: Int) {
        need(count * 4)
        var p = position
        for (i in 0 until count) {
            val bits = (data[p].toInt() and 0xFF) or
                ((data[p + 1].toInt() and 0xFF) shl 8) or
                ((data[p + 2].toInt() and 0xFF) shl 16) or
                ((data[p + 3].toInt() and 0xFF) shl 24)
            dst[offset + i] = Float.fromBits(bits)
            p += 4
        }
        position = p
    }

    /** 定长、以 0 结尾（可不结尾）的字符串。 */
    fun fixedString(n: Int, charset: Charset): String {
        need(n)
        var len = 0
        while (len < n && data[position + len].toInt() != 0) len++
        val s = String(data, position, len, charset)
        position += n
        return s
    }

    /** 定长字符串的原始字节（去掉 0 结尾后的部分）。 */
    fun fixedBytes(n: Int): ByteArray {
        need(n)
        var len = 0
        while (len < n && data[position + len].toInt() != 0) len++
        val out = data.copyOfRange(position, position + len)
        position += n
        return out
    }

    fun string(n: Int, charset: Charset): String {
        need(n)
        val s = String(data, position, n, charset)
        position += n
        return s
    }
}

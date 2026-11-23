package com.lumi.mmd.engine.util

import java.nio.charset.Charset

/** 小端二进制写入器（VMD / PMX 导出用）。 */
class BinaryWriter(initial: Int = 1 shl 16) {
    var data = ByteArray(initial)
        private set
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
    }

    fun u8(v: Int) { ensure(1); data[size++] = v.toByte() }

    fun u16(v: Int) { ensure(2); data[size++] = v.toByte(); data[size++] = (v ushr 8).toByte() }

    fun i32(v: Int) {
        ensure(4)
        data[size++] = v.toByte(); data[size++] = (v ushr 8).toByte(); data[size++] = (v ushr 16).toByte(); data[size++] = (v ushr 24).toByte()
    }

    fun f32(v: Float) = i32(java.lang.Float.floatToRawIntBits(if (v != v) 0f else v))

    fun bytes(b: ByteArray, offset: Int = 0, length: Int = b.size) {
        ensure(length)
        System.arraycopy(b, offset, data, size, length)
        size += length
    }

    fun zeros(n: Int) { ensure(n); java.util.Arrays.fill(data, size, size + n, 0); size += n }

    /** 定长字符串：不足补 0，超长截断。 */
    fun fixed(b: ByteArray, n: Int) {
        val len = minOf(b.size, n)
        bytes(b, 0, len)
        if (len < n) zeros(n - len)
    }

    /** PMX 文本：4 字节长度 + 内容。 */
    fun text(s: String, cs: Charset) {
        val b = s.toByteArray(cs)
        i32(b.size)
        bytes(b)
    }

    /** 按 size 字节写一个序号（PMX 的顶点 / 骨骼 / 材质等序号）。 */
    fun index(v: Int, bytes: Int) {
        when (bytes) {
            1 -> u8(v)
            2 -> u16(v)
            else -> i32(v)
        }
    }

    fun toByteArray(): ByteArray = data.copyOf(size)
}

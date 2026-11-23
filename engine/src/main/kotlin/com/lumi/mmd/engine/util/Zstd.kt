package com.lumi.mmd.engine.util

/**
 * Zstandard 解压（RFC 8878），纯 Kotlin 实现。
 *
 * Blender 3.0 之后「压缩保存」的 .blend 用的就是它；安卓和 JVM 都没有自带的解压器。
 * 只做解压：不校验校验和，不支持预置字典（.blend 不用）。一个文件里可以有很多帧（Blender 为了能跳读，
 * 每隔一段就起一帧，末尾还有一个"可跳过帧"放索引），按顺序全部解开拼起来。
 */
object Zstd {
    class ZstdException(message: String) : RuntimeException(message)

    private const val MAGIC = 0xFD2FB528.toInt()
    private const val BLOCK_MAX = 128 * 1024

    private fun fail(msg: String): Nothing = throw ZstdException("zstd 数据损坏：$msg")

    fun isZstd(d: ByteArray): Boolean = d.size >= 4 && le32(d, 0) == MAGIC

    private fun le16(d: ByteArray, p: Int): Int = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8)
    private fun le24(d: ByteArray, p: Int): Int = le16(d, p) or ((d[p + 2].toInt() and 0xFF) shl 16)
    private fun le32(d: ByteArray, p: Int): Int = le16(d, p) or ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 3].toInt() and 0xFF) shl 24)

    /** 最高的那个 1 在第几位（x > 0）。 */
    private fun highBit(x: Int): Int = 31 - Integer.numberOfLeadingZeros(x)

    private class Out(initial: Int, val limit: Long) {
        var buf = ByteArray(initial)
        var pos = 0
        fun ensure(extra: Int) {
            val need = pos.toLong() + extra
            if (need > limit) throw ZstdException("解压后的数据太大（超过 ${limit / (1024 * 1024)} MB）")
            if (need > buf.size) {
                var cap = buf.size.toLong()
                while (cap < need) cap = cap * 2 + 65536
                buf = buf.copyOf(minOf(cap, limit).toInt())
            }
        }
    }

    /** FSE 解码表。 */
    private class Fse(val log: Int) {
        val symbol = ByteArray(1 shl log)
        val bits = ByteArray(1 shl log)
        val base = IntArray(1 shl log)
    }

    private class Frame {
        val rep = intArrayOf(1, 4, 8)
        var ll: Fse? = null
        var of: Fse? = null
        var ml: Fse? = null
        // 哈夫曼表（文字段）：留着给"沿用上一张表"的块用
        var hufBits = 0
        val hufSymbol = ByteArray(1 shl 12)
        val hufLen = ByteArray(1 shl 12)
        val literals = ByteArray(BLOCK_MAX + 32)
    }

    /** 从后往前读的比特流（FSE / 哈夫曼都是倒着写的）。 */
    private class Backward(val d: ByteArray, val start: Int, end: Int) {
        /** 还没读的比特数（可以变成负的：读过头的部分按 0 算）。 */
        var left: Int

        init {
            if (end <= start) fail("比特流是空的")
            val last = d[end - 1].toInt() and 0xFF
            if (last == 0) fail("比特流没有结束标记")
            left = (end - start - 1) * 8 + highBit(last)
        }

        fun read(n: Int): Int {
            if (n == 0) return 0
            left -= n
            var from = left
            var count = n
            var shift = 0
            if (from < 0) {
                // 读过了开头：缺的低位补 0
                shift = minOf(n, -from)
                count = n - shift
                from = 0
                if (count <= 0) return 0
            }
            val byte = start + (from ushr 3)
            val off = from and 7
            var v = 0L
            // 最多跨 5 个字节（31 位 + 7 位偏移）
            var i = 0
            val bytes = (off + count + 7) ushr 3
            while (i < bytes) {
                val idx = byte + i
                if (idx < d.size) v = v or ((d[idx].toLong() and 0xFF) shl (8 * i))
                i++
            }
            return (((v ushr off) and ((1L shl count) - 1)) shl shift).toInt()
        }
    }

    /** 从前往后读的比特流（FSE 表的描述）。 */
    private class Forward(val d: ByteArray, var pos: Int, val end: Int) {
        var bit = 0
        fun read(n: Int): Int {
            var v = 0
            for (i in 0 until n) {
                if (pos >= end) fail("表的描述不完整")
                v = v or (((d[pos].toInt() ushr bit) and 1) shl i)
                if (++bit == 8) { bit = 0; pos++ }
            }
            return v
        }
        fun rewind(n: Int) { bit -= n; while (bit < 0) { bit += 8; pos-- } }
        fun align() { if (bit != 0) { bit = 0; pos++ } }
    }

    private fun buildFse(freq: IntArray, count: Int, log: Int): Fse {
        val t = Fse(log)
        val size = 1 shl log
        val next = IntArray(count)
        var high = size
        // 概率"不到 1"的符号放在表的最后面
        for (s in 0 until count) if (freq[s] == -1) { t.symbol[--high] = s.toByte(); next[s] = 1 }
        val step = (size shr 1) + (size shr 3) + 3
        val mask = size - 1
        var pos = 0
        for (s in 0 until count) {
            if (freq[s] <= 0) continue
            next[s] = freq[s]
            for (i in 0 until freq[s]) {
                t.symbol[pos] = s.toByte()
                do { pos = (pos + step) and mask } while (pos >= high)
            }
        }
        if (pos != 0) fail("FSE 表铺不满")
        for (i in 0 until size) {
            val s = t.symbol[i].toInt() and 0xFF
            val n = next[s]++
            val nb = log - highBit(n)
            t.bits[i] = nb.toByte()
            t.base[i] = (n shl nb) - size
        }
        return t
    }

    private fun rleFse(symbol: Int): Fse = Fse(0).also { it.symbol[0] = symbol.toByte() }

    /** 读一张 FSE 表的描述；返回表，[outPos] 里放读完之后的位置。 */
    private fun readFse(d: ByteArray, pos: Int, end: Int, maxLog: Int, maxSymbols: Int, outPos: IntArray): Fse {
        val r = Forward(d, pos, end)
        val log = 5 + r.read(4)
        if (log > maxLog) fail("FSE 表的精度超出范围")
        var remaining = 1 shl log
        val freq = IntArray(maxSymbols)
        var symb = 0
        while (remaining > 0 && symb < maxSymbols) {
            val bits = highBit(remaining + 1) + 1
            var v = r.read(bits)
            val lower = (1 shl (bits - 1)) - 1
            val threshold = (1 shl bits) - 1 - (remaining + 1)
            if ((v and lower) < threshold) { r.rewind(1); v = v and lower } else if (v > lower) v -= threshold
            val proba = v - 1
            remaining -= if (proba < 0) -proba else proba
            freq[symb++] = proba
            if (proba == 0) {
                var repeat = r.read(2)
                while (true) {
                    var i = 0
                    while (i < repeat && symb < maxSymbols) { freq[symb++] = 0; i++ }
                    if (repeat == 3) repeat = r.read(2) else break
                }
            }
        }
        r.align()
        if (remaining != 0) fail("FSE 表的概率加起来不对")
        outPos[0] = r.pos
        return buildFse(freq, symb, log)
    }

    private val LL_DEFAULT = intArrayOf(4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1, -1, -1, -1, -1)
    private val ML_DEFAULT = intArrayOf(1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1)
    private val OF_DEFAULT = intArrayOf(1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1)
    private val llDefault by lazy { buildFse(LL_DEFAULT, LL_DEFAULT.size, 6) }
    private val mlDefault by lazy { buildFse(ML_DEFAULT, ML_DEFAULT.size, 6) }
    private val ofDefault by lazy { buildFse(OF_DEFAULT, OF_DEFAULT.size, 5) }

    private val LL_BASE = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536)
    private val LL_BITS = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
    private val ML_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
        35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539)
    private val ML_BITS = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)

    /**
     * 解压。[sizeHint] 是对解压后大小的估计（不知道就给 0），[maxSize] 是上限（防止坏文件把内存吃光）。
     */
    fun decompress(src: ByteArray, sizeHint: Int = 0, maxSize: Long = Int.MAX_VALUE - 1024L): ByteArray {
        val out = Out(maxOf(sizeHint, minOf(src.size.toLong() * 3, 64L * 1024 * 1024, maxSize).toInt(), 65536), maxSize)
        var pos = 0
        var frames = 0
        while (pos + 4 <= src.size) {
            val magic = le32(src, pos)
            if ((magic and 0xFFFFFFF0.toInt()) == 0x184D2A50) {
                // 可跳过的帧（Blender 的跳读索引）
                if (pos + 8 > src.size) break
                val n = le32(src, pos + 4).toLong() and 0xFFFFFFFFL
                pos = (pos + 8L + n).coerceAtMost(src.size.toLong()).toInt()
                continue
            }
            if (magic != MAGIC) { if (frames > 0) break else fail("不是 zstd 数据") }
            // 数据不完整时内部可能读到数组外面去：统一报成"数据损坏"
            pos = try { frame(src, pos + 4, out) } catch (e: IndexOutOfBoundsException) { fail("数据不完整") }
            frames++
        }
        if (frames == 0) fail("没有数据")
        return if (out.pos == out.buf.size) out.buf else out.buf.copyOf(out.pos)
    }

    /**
     * 流式解压：从 [src]（通常是映射进来的文件）一帧一帧地解，解出来的内容直接写进 [sink]，不在内存里攒。
     * 占的内存只有"最大的那一帧"那么多——Blender 存盘时每帧最多一两 MB。返回解出来的总字节数。
     */
    fun decompress(src: java.nio.ByteBuffer, sink: java.io.OutputStream, maxSize: Long = Long.MAX_VALUE): Long {
        val size = src.limit()
        fun u8(p: Int) = src.get(p).toInt() and 0xFF
        fun i32(p: Int) = u8(p) or (u8(p + 1) shl 8) or (u8(p + 2) shl 16) or (u8(p + 3) shl 24)
        val out = Out(256 * 1024, minOf(maxSize, MAX_FRAME))
        var pos = 0
        var frames = 0
        var total = 0L
        while (pos + 4 <= size) {
            val magic = i32(pos)
            if ((magic and 0xFFFFFFF0.toInt()) == 0x184D2A50) {
                if (pos + 8 > size) break
                val n = i32(pos + 4).toLong() and 0xFFFFFFFFL
                pos = (pos + 8L + n).coerceAtMost(size.toLong()).toInt()
                continue
            }
            if (magic != MAGIC) { if (frames > 0) break else fail("不是 zstd 数据") }
            // 先顺着块头量出这一帧压缩后有多长，再把它单独取出来解
            var p = pos + 4
            if (p >= size) fail("帧头不完整")
            val fhd = u8(p++)
            val single = (fhd ushr 5) and 1
            if (single == 0) p++
            p += intArrayOf(0, 1, 2, 4)[fhd and 3]
            p += when (fhd ushr 6) { 0 -> single; 1 -> 2; 2 -> 4; else -> 8 }
            while (true) {
                if (p + 3 > size) fail("数据不完整")
                val bh = u8(p) or (u8(p + 1) shl 8) or (u8(p + 2) shl 16)
                p += 3
                val type = (bh ushr 1) and 3
                if (type == 3) fail("未知的块类型")
                p += if (type == 1) 1 else bh ushr 3
                if (p > size) fail("数据不完整")
                if (bh and 1 == 1) break
            }
            if ((fhd ushr 2) and 1 == 1) p = minOf(p + 4, size)
            val length = p - pos
            if (length > MAX_FRAME) throw ZstdException("这份 zstd 数据是一整块压的（${length / (1024 * 1024)} MB），内存里放不下")
            val one = ByteArray(length)
            val view = src.duplicate()
            (view as java.nio.Buffer).position(pos)
            view.get(one)
            out.pos = 0
            try { frame(one, 4, out) } catch (e: IndexOutOfBoundsException) { fail("数据不完整") }
            total += out.pos
            if (total > maxSize) throw ZstdException("解压后的数据太大（超过 ${maxSize / (1024 * 1024)} MB）")
            sink.write(out.buf, 0, out.pos)
            pos = p
            frames++
        }
        if (frames == 0) fail("没有数据")
        return total
    }

    /** 单独一帧最多解出这么多（要整个放在内存里）。 */
    private const val MAX_FRAME = 192L * 1024 * 1024

    private fun frame(src: ByteArray, start: Int, out: Out): Int {
        var pos = start
        if (pos >= src.size) fail("帧头不完整")
        val fhd = src[pos++].toInt() and 0xFF
        val fcsFlag = fhd ushr 6
        val single = (fhd ushr 5) and 1
        val checksum = (fhd ushr 2) and 1
        val dictFlag = fhd and 3
        if (fhd and 0x08 != 0) fail("帧头的保留位不是 0")
        if (single == 0) pos++
        val dictBytes = intArrayOf(0, 1, 2, 4)[dictFlag]
        var dict = 0L
        for (i in 0 until dictBytes) dict = dict or ((src[pos + i].toLong() and 0xFF) shl (8 * i))
        if (dict != 0L) throw ZstdException("这份 zstd 数据需要预置字典，无法解压")
        pos += dictBytes
        val fcsBytes = when (fcsFlag) { 0 -> single; 1 -> 2; 2 -> 4; else -> 8 }
        if (pos + fcsBytes > src.size) fail("帧头不完整")
        var content = -1L
        if (fcsBytes > 0) {
            content = 0L
            for (i in 0 until fcsBytes) content = content or ((src[pos + i].toLong() and 0xFF) shl (8 * i))
            if (fcsBytes == 2) content += 256
        }
        pos += fcsBytes
        if (content in 1..(Int.MAX_VALUE - 1024L)) out.ensure(content.toInt())
        val frameStart = out.pos
        val f = Frame()
        while (true) {
            if (pos + 3 > src.size) fail("块头不完整")
            val bh = le24(src, pos); pos += 3
            val last = bh and 1
            val type = (bh ushr 1) and 3
            val size = bh ushr 3
            when (type) {
                0 -> {
                    if (pos + size > src.size) fail("原样块不完整")
                    out.ensure(size)
                    System.arraycopy(src, pos, out.buf, out.pos, size)
                    out.pos += size; pos += size
                }
                1 -> {
                    if (pos >= src.size) fail("重复块不完整")
                    out.ensure(size)
                    java.util.Arrays.fill(out.buf, out.pos, out.pos + size, src[pos])
                    out.pos += size; pos += 1
                }
                2 -> {
                    if (pos + size > src.size) fail("压缩块不完整")
                    out.ensure(BLOCK_MAX + 64)
                    block(src, pos, pos + size, out, frameStart, f)
                    pos += size
                }
                else -> fail("未知的块类型")
            }
            if (last == 1) break
        }
        if (checksum == 1) pos += 4
        return minOf(pos, src.size)
    }

    private fun block(src: ByteArray, start: Int, end: Int, out: Out, frameStart: Int, f: Frame) {
        var p = start
        if (p >= end) fail("空的压缩块")
        // ------------------------------------------------------------ 文字段
        val b0 = src[p].toInt() and 0xFF
        val litType = b0 and 3
        val sizeFormat = (b0 ushr 2) and 3
        var lit = f.literals
        var litPos = 0
        val litLen: Int
        if (litType < 2) {
            when (sizeFormat) {
                0, 2 -> { litLen = b0 ushr 3; p += 1 }
                1 -> { litLen = le16(src, p) ushr 4; p += 2 }
                else -> { litLen = le24(src, p) ushr 4; p += 3 }
            }
            if (litLen > BLOCK_MAX) fail("文字段太长")
            if (litType == 0) {
                if (p + litLen > end) fail("文字段不完整")
                lit = src; litPos = p; p += litLen
            } else {
                if (p >= end) fail("文字段不完整")
                java.util.Arrays.fill(lit, 0, litLen, src[p]); p += 1
            }
        } else {
            val comp: Int
            val streams: Int
            when (sizeFormat) {
                0, 1 -> { val v = le24(src, p); litLen = (v ushr 4) and 0x3FF; comp = (v ushr 14) and 0x3FF; streams = if (sizeFormat == 0) 1 else 4; p += 3 }
                2 -> { val v = le32(src, p); litLen = (v ushr 4) and 0x3FFF; comp = (v ushr 18) and 0x3FFF; streams = 4; p += 4 }
                else -> {
                    val v = (le32(src, p).toLong() and 0xFFFFFFFFL) or ((src[p + 4].toLong() and 0xFF) shl 32)
                    litLen = ((v ushr 4) and 0x3FFFF).toInt(); comp = ((v ushr 22) and 0x3FFFF).toInt(); streams = 4; p += 5
                }
            }
            if (litLen > BLOCK_MAX) fail("文字段太长")
            val litEnd = p + comp
            if (litEnd > end) fail("文字段不完整")
            var hp = p
            if (litType == 2) hp = readHuffman(src, p, litEnd, f) else if (f.hufBits == 0) fail("要沿用的哈夫曼表不存在")
            if (streams == 1) {
                huffStream(src, hp, litEnd, f, lit, 0, litLen)
            } else {
                if (hp + 6 > litEnd) fail("文字段的分流表不完整")
                val s1 = le16(src, hp); val s2 = le16(src, hp + 2); val s3 = le16(src, hp + 4)
                val base = hp + 6
                val each = (litLen + 3) / 4
                if (base + s1 + s2 + s3 > litEnd || each * 3 > litLen) fail("文字段的分流表不对")
                huffStream(src, base, base + s1, f, lit, 0, each)
                huffStream(src, base + s1, base + s1 + s2, f, lit, each, each)
                huffStream(src, base + s1 + s2, base + s1 + s2 + s3, f, lit, each * 2, each)
                huffStream(src, base + s1 + s2 + s3, litEnd, f, lit, each * 3, litLen - each * 3)
            }
            p = litEnd
        }
        // ------------------------------------------------------------ 序列段
        val buf = out.buf
        var op = out.pos
        if (p >= end) {
            System.arraycopy(lit, litPos, buf, op, litLen)
            out.pos = op + litLen
            return
        }
        var nseq = src[p++].toInt() and 0xFF
        if (nseq == 0) {
            System.arraycopy(lit, litPos, buf, op, litLen)
            out.pos = op + litLen
            return
        }
        if (nseq >= 128) {
            if (nseq == 255) { if (p + 2 > end) fail("序列段不完整"); nseq = le16(src, p) + 0x7F00; p += 2 }
            else { if (p >= end) fail("序列段不完整"); nseq = ((nseq - 128) shl 8) + (src[p].toInt() and 0xFF); p += 1 }
        }
        if (p >= end) fail("序列段不完整")
        val modes = src[p++].toInt() and 0xFF
        val at = intArrayOf(p)
        val ll = seqTable((modes ushr 6) and 3, src, at, end, 9, 36, llDefault, f.ll)
        val of = seqTable((modes ushr 4) and 3, src, at, end, 8, 32, ofDefault, f.of)
        val ml = seqTable((modes ushr 2) and 3, src, at, end, 9, 53, mlDefault, f.ml)
        f.ll = ll; f.of = of; f.ml = ml
        p = at[0]
        val br = Backward(src, p, end)
        var llState = br.read(ll.log)
        var ofState = br.read(of.log)
        var mlState = br.read(ml.log)
        val rep = f.rep
        val limit = op + BLOCK_MAX
        var litUsed = 0
        for (i in 0 until nseq) {
            val ofCode = of.symbol[ofState].toInt() and 0xFF
            val llCode = ll.symbol[llState].toInt() and 0xFF
            val mlCode = ml.symbol[mlState].toInt() and 0xFF
            if (llCode > 35 || mlCode > 52 || ofCode > 31) fail("序列的编码超出范围")
            val ofValue = (1 shl ofCode) + br.read(ofCode)
            val mlen = ML_BASE[mlCode] + br.read(ML_BITS[mlCode])
            val llen = LL_BASE[llCode] + br.read(LL_BITS[llCode])
            if (i < nseq - 1) {
                llState = ll.base[llState] + br.read(ll.bits[llState].toInt())
                mlState = ml.base[mlState] + br.read(ml.bits[mlState].toInt())
                ofState = of.base[ofState] + br.read(of.bits[ofState].toInt())
            }
            val offset: Int
            if (ofValue > 3) {
                offset = ofValue - 3
                rep[2] = rep[1]; rep[1] = rep[0]; rep[0] = offset
            } else {
                var idx = ofValue - 1
                if (llen == 0) idx++
                if (idx == 0) offset = rep[0]
                else {
                    offset = if (idx < 3) rep[idx] else rep[0] - 1
                    if (idx > 1) rep[2] = rep[1]
                    rep[1] = rep[0]; rep[0] = offset
                }
            }
            if (litUsed + llen > litLen) fail("序列用的文字比给的多")
            if (op + llen + mlen > limit) fail("块解压后超过 128 KB")
            System.arraycopy(lit, litPos + litUsed, buf, op, llen)
            litUsed += llen; op += llen
            if (offset <= 0 || offset > op - frameStart) fail("回指的位置在帧的外面")
            var from = op - offset
            if (offset >= mlen) {
                System.arraycopy(buf, from, buf, op, mlen); op += mlen
            } else {
                // 重叠的复制：一个字节一个字节来
                for (k in 0 until mlen) buf[op++] = buf[from++]
            }
        }
        val rest = litLen - litUsed
        if (op + rest > limit) fail("块解压后超过 128 KB")
        System.arraycopy(lit, litPos + litUsed, buf, op, rest)
        out.pos = op + rest
    }

    private fun seqTable(mode: Int, src: ByteArray, at: IntArray, end: Int, maxLog: Int, maxSymbols: Int, default: Fse, previous: Fse?): Fse = when (mode) {
        0 -> default
        1 -> { if (at[0] >= end) fail("序列表不完整"); rleFse(src[at[0]++].toInt() and 0xFF) }
        2 -> readFse(src, at[0], end, maxLog, maxSymbols, at)
        else -> previous ?: fail("要沿用的序列表不存在")
    }

    /** 读哈夫曼树的描述并建好解码表；返回读完之后的位置。 */
    private fun readHuffman(src: ByteArray, start: Int, end: Int, f: Frame): Int {
        var p = start
        if (p >= end) fail("哈夫曼表不完整")
        val header = src[p++].toInt() and 0xFF
        val weights = IntArray(256)
        var n: Int
        if (header >= 128) {
            n = header - 127
            val bytes = (n + 1) / 2
            if (p + bytes > end) fail("哈夫曼表不完整")
            for (i in 0 until n) {
                val b = src[p + i / 2].toInt() and 0xFF
                weights[i] = if (i % 2 == 0) b ushr 4 else b and 15
            }
            p += bytes
        } else {
            val fseEnd = p + header
            if (fseEnd > end) fail("哈夫曼表不完整")
            val at = IntArray(1)
            val t = readFse(src, p, fseEnd, 6, 13, at)
            val br = Backward(src, at[0], fseEnd)
            var s1 = br.read(t.log)
            var s2 = br.read(t.log)
            n = 0
            while (true) {
                if (n >= 254) fail("哈夫曼权重太多")
                weights[n++] = t.symbol[s1].toInt() and 0xFF
                s1 = t.base[s1] + br.read(t.bits[s1].toInt())
                if (br.left < 0) { weights[n++] = t.symbol[s2].toInt() and 0xFF; break }
                weights[n++] = t.symbol[s2].toInt() and 0xFF
                s2 = t.base[s2] + br.read(t.bits[s2].toInt())
                if (br.left < 0) { weights[n++] = t.symbol[s1].toInt() and 0xFF; break }
            }
            p = fseEnd
        }
        // 最后一个符号的权重不写出来：由"总和必须是 2 的整数次幂"推出
        var sum = 0
        for (i in 0 until n) { if (weights[i] > 11) fail("哈夫曼权重超出范围"); if (weights[i] > 0) sum += 1 shl (weights[i] - 1) }
        if (sum == 0) fail("哈夫曼权重全是 0")
        val maxBits = highBit(sum) + 1
        if (maxBits > 11) fail("哈夫曼码太长")
        val leftOver = (1 shl maxBits) - sum
        if (leftOver and (leftOver - 1) != 0) fail("哈夫曼权重的总和不对")
        weights[n++] = highBit(leftOver) + 1
        // 码长 = maxBits + 1 - 权重；码长长的排在表的前面
        val rankCount = IntArray(maxBits + 2)
        for (i in 0 until n) if (weights[i] > 0) rankCount[maxBits + 1 - weights[i]]++
        val rankIdx = IntArray(maxBits + 2)
        rankIdx[maxBits] = 0
        for (i in maxBits downTo 1) {
            rankIdx[i - 1] = rankIdx[i] + rankCount[i] * (1 shl (maxBits - i))
            java.util.Arrays.fill(f.hufLen, rankIdx[i], rankIdx[i - 1], i.toByte())
        }
        if (rankIdx[0] != (1 shl maxBits)) fail("哈夫曼表铺不满")
        for (i in 0 until n) {
            if (weights[i] == 0) continue
            val bits = maxBits + 1 - weights[i]
            val len = 1 shl (maxBits - bits)
            java.util.Arrays.fill(f.hufSymbol, rankIdx[bits], rankIdx[bits] + len, i.toByte())
            rankIdx[bits] += len
        }
        f.hufBits = maxBits
        return p
    }

    private fun huffStream(src: ByteArray, start: Int, end: Int, f: Frame, out: ByteArray, outPos: Int, count: Int) {
        if (count == 0) return
        val br = Backward(src, start, end)
        val bits = f.hufBits
        val mask = (1 shl bits) - 1
        val sym = f.hufSymbol
        val len = f.hufLen
        var state = br.read(bits)
        var o = outPos
        val stop = outPos + count
        while (o < stop) {
            out[o++] = sym[state]
            val nb = len[state].toInt()
            state = ((state shl nb) and mask) or br.read(nb)
        }
    }
}

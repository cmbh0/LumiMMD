package com.lumi.mmd.engine.format

import com.lumi.mmd.engine.util.Memory
import com.lumi.mmd.engine.util.Zstd
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

/**
 * .blend 文件的底层结构：文件头、数据块、结构说明（SDNA）。
 *
 * .blend 是 Blender 把内存里的结构体原样写出来的：每个数据块前面有个小头（类型、长度、它在内存里的旧地址），
 * 文件末尾的 DNA1 块说明每种结构体有哪些成员、各占多少字节。结构体之间用旧地址互相指。
 * 不同版本的 Blender 成员不一样，所以读的时候一律按名字查成员，不写死偏移。
 *
 * 这里处理了：
 *  - 压缩：gzip（2.x）和 zstd（3.0 起）；大文件不读进堆内存，见 [open]；
 *  - 32 / 64 位指针、大小端；
 *  - 5.0 起的新文件头和新块头；
 *  - 旧地址的作用范围：一个资源（物体、网格、材质……）后面跟着的 DATA 块，地址只保证在这个资源之内不重复。
 */
class BlendFile private constructor(
    /** 整个（解压后的）文件。大文件是映射进来的，不占堆内存。 */
    private val buf: ByteBuffer,
    /** 文件原来是怎么压缩的："" / "gzip" / "zstd"。 */
    val compressed: String,
) {
    /** 文件（解压后）的长度。 */
    val size: Int = buf.limit()
    /** 版本号：279 = 2.79，306 = 3.6，402 = 4.2，503 = 5.3。 */
    val version: Int
    val pointerSize: Int
    private val bigEndian: Boolean

    class Block(val code: String, val structIndex: Int, val address: Long, val length: Int, val count: Int, val offset: Int) {
        /** 所属资源块在 blocks 里的序号（资源块自己就是它自己）。 */
        var owner = -1
    }

    class Field(val name: String, val type: String, val offset: Int, val elemSize: Int, val count: Int, val pointer: Boolean) {
        val size: Int get() = elemSize * count
    }

    class Struct(val name: String, val size: Int) {
        val fields = HashMap<String, Field>()
    }

    val blocks = ArrayList<Block>()
    private val structs = ArrayList<Struct>()
    private val structByName = HashMap<String, Struct>()
    /** 资源块（OB、ME、MA……）：地址在整个文件里唯一。 */
    private val ids = HashMap<Long, Block>()
    /** 每个资源自己的 DATA 块。 */
    private val locals = HashMap<Int, HashMap<Long, Block>>()
    /** 兜底：不分范围，后来的盖掉先来的。 */
    private val all = HashMap<Long, Block>()

    init {
        if (size < 16 || ascii(0, 7) != "BLENDER") throw FormatException("不是 .blend 文件")
        var pos: Int
        val format: Int
        val c7 = u8(7).toChar()
        if (c7 == '_' || c7 == '-') {
            pointerSize = if (c7 == '_') 4 else 8
            bigEndian = u8(8).toChar() == 'V'
            version = ascii(9, 3).trim().toIntOrNull() ?: 0
            pos = 12
            format = 0
        } else {
            // 5.0 起：BLENDER17-01v0500（头长 17，格式版本 01，一律小端、64 位）
            val headerSize = ascii(7, 2).toIntOrNull() ?: throw FormatException("看不懂这个 .blend 的文件头")
            format = ascii(10, 2).toIntOrNull() ?: 1
            pointerSize = 8
            bigEndian = false
            version = ascii(13, 4).trim().toIntOrNull() ?: 0
            pos = headerSize
            if (format > 1) throw FormatException("这个 .blend 是更新版本的 Blender 存的（文件格式 $format），暂时读不了")
        }
        buf.order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        var owner = -1
        var dna: Block? = null
        val head = if (format >= 1) 32 else if (pointerSize == 8) 24 else 20
        while (pos + head <= size) {
            val code = code(pos)
            if (code == "ENDB") break
            val structIndex: Int; val address: Long; val length: Long; val count: Long
            if (format >= 1) {
                structIndex = i32(pos + 4); address = i64(pos + 8); length = i64(pos + 16); count = i64(pos + 24)
            } else if (pointerSize == 8) {
                length = i32(pos + 4).toLong(); address = i64(pos + 8); structIndex = i32(pos + 16); count = i32(pos + 20).toLong()
            } else {
                length = i32(pos + 4).toLong(); address = i32(pos + 8).toLong() and 0xFFFFFFFFL; structIndex = i32(pos + 12); count = i32(pos + 16).toLong()
            }
            if (length < 0 || pos.toLong() + head + length > size) throw FormatException(".blend 文件不完整（可能没有下载完）")
            val b = Block(code, structIndex, address, length.toInt(), count.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), pos + head)
            val index = blocks.size
            blocks.add(b)
            // 数据块特别多的文件（几十万个）光是这张索引就要占不少内存：快不够了就停下来说清楚
            if (index and 0xFFFF == 0xFFFF && !Memory.fits(24L * 1024 * 1024, 40L * 1024 * 1024)) {
                throw FormatException("这个 .blend 里的东西太多了（已经读到 ${index / 10000} 万个数据块），内存不够")
            }
            if (code == "DATA") {
                b.owner = owner
                if (address != 0L) locals.getOrPut(owner) { HashMap() }[address] = b
            } else {
                owner = index
                b.owner = index
                if (address != 0L) ids[address] = b
                if (code == "DNA1") dna = b
            }
            if (address != 0L) all[address] = b
            pos += head + length.toInt()
        }
        parseDna(dna ?: throw FormatException(".blend 文件里没有结构说明（DNA），无法读取"))
    }

    /** 块的类型码。两个字母的码（OB、ME……）在大端文件里是 "\\0\\0OB" 的样子，所以两头的 0 都去掉。 */
    private fun code(p: Int): String {
        var a = 0
        var n = 4
        while (n > 0 && buf.get(p + n - 1).toInt() == 0) n--
        while (a < n && buf.get(p + a).toInt() == 0) a++
        return ascii(p + a, n - a)
    }

    private fun ascii(p: Int, n: Int): String {
        val c = CharArray(n) { (buf.get(p + it).toInt() and 0xFF).toChar() }
        return String(c)
    }

    private fun parseDna(b: Block) {
        val start = b.offset
        val end = b.offset + b.length
        var p = start
        fun tag(t: String) {
            if (p + 4 > end || ascii(p, 4) != t) throw FormatException(".blend 的结构说明损坏（缺 $t）")
            p += 4
        }
        fun align() { p = start + ((p - start + 3) and 3.inv()) }
        fun strings(): Array<String> {
            val n = i32(p); p += 4
            if (n < 0 || n > 1_000_000) throw FormatException(".blend 的结构说明损坏")
            return Array(n) {
                val s = p
                while (p < end && buf.get(p).toInt() != 0) p++
                val str = ascii(s, p - s)
                p++
                str
            }
        }
        tag("SDNA"); tag("NAME")
        val names = strings()
        align(); tag("TYPE")
        val types = strings()
        align(); tag("TLEN")
        val lengths = IntArray(types.size) { i16(p + it * 2) and 0xFFFF }
        p += types.size * 2
        align(); tag("STRC")
        val n = i32(p); p += 4
        for (i in 0 until n) {
            val type = i16(p) and 0xFFFF
            val fieldCount = i16(p + 2) and 0xFFFF
            p += 4
            val s = Struct(types.getOrElse(type) { "?" }, lengths.getOrElse(type) { 0 })
            var off = 0
            for (k in 0 until fieldCount) {
                val ft = i16(p) and 0xFFFF
                val fn = i16(p + 2) and 0xFFFF
                p += 4
                val raw = names.getOrElse(fn) { "?" }
                val pointer = raw.startsWith("*") || raw.startsWith("(*")
                var count = 1
                var at = raw.indexOf('[')
                while (at >= 0) {
                    val close = raw.indexOf(']', at)
                    if (close < 0) break
                    count *= raw.substring(at + 1, close).toIntOrNull() ?: 1
                    at = raw.indexOf('[', close)
                }
                var name = raw
                if (name.startsWith("(*")) name = name.substring(2).substringBefore(')')
                name = name.trimStart('*').substringBefore('[')
                val elem = if (pointer) pointerSize else lengths.getOrElse(ft) { 0 }
                s.fields[name] = Field(name, types.getOrElse(ft) { "?" }, off, elem, count, pointer)
                off += elem * count
            }
            structs.add(s)
            structByName[s.name] = s
        }
    }

    // ------------------------------------------------------------------ 基本读取

    fun u8(p: Int): Int = buf.get(p).toInt() and 0xFF
    fun i16(p: Int): Int = buf.getShort(p).toInt()
    fun i32(p: Int): Int = buf.getInt(p)
    fun i64(p: Int): Long = buf.getLong(p)
    fun f32(p: Int): Float = buf.getFloat(p)
    fun pointer(p: Int): Long = if (pointerSize == 8) i64(p) else i32(p).toLong() and 0xFFFFFFFFL

    fun string(p: Int, max: Int): String {
        var n = 0
        while (n < max && p + n < size && buf.get(p + n).toInt() != 0) n++
        return String(bytes(p, n), Charsets.UTF_8)
    }

    /** 取出一段原始数据（复制到堆里；大块的数据用 [copyTo]）。 */
    fun bytes(p: Int, n: Int): ByteArray {
        val out = ByteArray(n)
        val view = buf.duplicate()
        (view as java.nio.Buffer).position(p)
        view.get(out)
        return out
    }

    /** 把一段原始数据直接写出去（打包在文件里的贴图用：不经过堆）。 */
    fun copyTo(p: Int, n: Int, out: java.io.OutputStream) {
        val view = buf.duplicate()
        (view as java.nio.Buffer).position(p)
        val chunk = ByteArray(minOf(n, 64 * 1024))
        var left = n
        while (left > 0) {
            val k = minOf(left, chunk.size)
            view.get(chunk, 0, k)
            out.write(chunk, 0, k)
            left -= k
        }
    }

    // ------------------------------------------------------------------ 结构体

    fun struct(name: String): Struct? = structByName[name]
    fun structOf(b: Block): Struct? = structs.getOrNull(b.structIndex)
    fun blocksOf(code: String): List<Block> = blocks.filter { it.code == code }

    /** 旧地址 → 数据块。先在 [owner] 这个资源自己的 DATA 块里找，再找资源块，最后兜底。 */
    fun find(owner: Int, address: Long): Block? {
        if (address == 0L) return null
        locals[owner]?.get(address)?.let { return it }
        ids[address]?.let { return it }
        return all[address]
    }

    /** 一个结构体实例的"视图"：按成员名读。 */
    inner class Ref(val block: Block, val pos: Int, val struct: Struct) {
        /** 这个实例的旧地址（别的结构体里的指针指的就是它）。 */
        val address: Long get() = block.address + (pos - block.offset)
        val owner: Int get() = block.owner

        fun has(name: String): Boolean = struct.fields.containsKey(name)

        private fun field(name: String, index: Int): Field? {
            val f = struct.fields[name] ?: return null
            if (index < 0 || index >= f.count) return null
            if (pos + f.offset + (index + 1) * f.elemSize > size) return null
            return f
        }

        fun int(name: String, index: Int = 0, default: Int = 0): Int {
            val f = field(name, index) ?: return default
            val p = pos + f.offset + index * f.elemSize
            return when (f.elemSize) { 1 -> u8(p); 2 -> i16(p); 4 -> if (f.type == "float") f32(p).toInt() else i32(p); 8 -> i64(p).toInt(); else -> default }
        }

        fun float(name: String, index: Int = 0, default: Float = 0f): Float {
            val f = field(name, index) ?: return default
            if (f.elemSize != 4 || f.type != "float") return default
            val v = f32(pos + f.offset + index * 4)
            return if (v == v) v else default
        }

        fun floats(name: String, n: Int): FloatArray? {
            val f = struct.fields[name] ?: return null
            if (f.type != "float" || f.count < n) return null
            return FloatArray(n) { f32(pos + f.offset + it * 4) }
        }

        fun str(name: String): String {
            val f = struct.fields[name] ?: return ""
            return string(pos + f.offset, f.size)
        }

        fun ptr(name: String, index: Int = 0): Long {
            val f = field(name, index) ?: return 0L
            if (!f.pointer) return 0L
            return pointer(pos + f.offset + index * f.elemSize)
        }

        /** 内嵌的结构体成员。 */
        fun sub(name: String): Ref? {
            val f = struct.fields[name] ?: return null
            if (f.pointer) return null
            val s = structByName[f.type] ?: return null
            return Ref(block, pos + f.offset, s)
        }

        /** 指针成员指向的数据块。 */
        fun target(name: String, index: Int = 0): Block? = find(owner, ptr(name, index))

        /** 指针成员指向的结构体（[type] 不给就用数据块自己记的类型）。 */
        fun ref(name: String, type: String? = null, index: Int = 0): Ref? = at(owner, ptr(name, index), type)

        /** 走一个 ListBase 成员（链表）：每个元素的第一个成员是 next 指针。 */
        fun list(name: String, type: String? = null): List<Ref> {
            val lb = sub(name) ?: return emptyList()
            val out = ArrayList<Ref>()
            var a = lb.ptr("first")
            var guard = 0
            while (a != 0L && guard++ < 200_000) {
                val r = at(owner, a, type) ?: break
                out.add(r)
                a = pointer(r.pos)
            }
            return out
        }
    }

    /** 把一个数据块当成 [type] 结构体的数组，取第 [index] 个。 */
    fun ref(b: Block, type: String? = null, index: Int = 0): Ref? {
        val s = (if (type != null) structByName[type] else null) ?: structOf(b) ?: return null
        if (s.size <= 0 || (index + 1) * s.size > b.length) return null
        return Ref(b, b.offset + index * s.size, s)
    }

    /** 旧地址 → 结构体视图。地址可以指在一个数组块的中间。 */
    fun at(owner: Int, address: Long, type: String? = null): Ref? {
        if (address == 0L) return null
        val b = find(owner, address)
        if (b != null) return ref(b, type)
        return null
    }

    companion object {
        /** 映射一个文件最多这么大（下标是 Int）。 */
        private const val MAX_MAPPED = Int.MAX_VALUE - 64L * 1024

        /** 看开头几个字节像不像（没压缩的）.blend。 */
        fun looksLike(d: ByteArray): Boolean = d.size >= 7 && String(d, 0, 7, Charsets.ISO_8859_1) == "BLENDER"

        fun isGzip(d: ByteArray): Boolean = d.size >= 2 && d[0] == 0x1f.toByte() && d[1] == 0x8b.toByte()

        /** 内存里的一份数据（小文件、测试）。压缩过的在内存里解开。 */
        fun of(raw: ByteArray): BlendFile {
            var d = raw
            var how = ""
            if (isGzip(d)) {
                how = "gzip"
                d = try {
                    val out = ByteArrayOutputStream(65536)
                    GZIPInputStream(ByteArrayInputStream(d), 65536).use { it.copyTo(out, 65536) }
                    out.toByteArray()
                } catch (e: Exception) { throw FormatException("这个 .blend 是压缩保存的，解压失败：${e.message}") }
            } else if (Zstd.isZstd(d)) {
                how = "zstd"
                d = try { Zstd.decompress(d) } catch (e: Zstd.ZstdException) { throw FormatException("这个 .blend 是压缩保存的，解压失败：${e.message}") }
            }
            return BlendFile(ByteBuffer.wrap(d), how)
        }

        /**
         * 打开一个文件。**文件内容不进堆内存**：没压缩的直接映射；压缩保存的先流式解到一个临时文件里，再映射那个临时文件。
         * （一个 300 MB 的 .blend 如果整个读进来再解压，要占掉六七百 MB 的堆——手机上应用总共只有两三百到五百 MB。）
         */
        fun open(file: File): BlendFile {
            val head = ByteArray(8)
            val got = try { FileInputStream(file).use { it.read(head) } } catch (e: IOException) { throw FormatException("读不了这个文件：${e.message}") }
            val h = if (got > 0) head.copyOf(got) else ByteArray(0)
            val gzip = isGzip(h)
            val zstd = Zstd.isZstd(h)
            if (!gzip && !zstd) {
                if (file.length() > MAX_MAPPED) throw FormatException("这个 .blend 超过 2 GB，读不了")
                return BlendFile(map(file), "")
            }
            // 上次解到一半应用就被关掉的话，临时文件会留下来（可能有几百 MB）：超过十分钟没动过的顺手清掉
            try {
                val old = System.currentTimeMillis() - 10 * 60 * 1000L
                File(System.getProperty("java.io.tmpdir") ?: ".").listFiles()?.forEach {
                    if (it.isFile && it.name.startsWith("lumi-blend-") && it.name.endsWith(".tmp") && it.lastModified() < old) it.delete()
                }
            } catch (_: Exception) {}
            val tmp = try { File.createTempFile("lumi-blend-", ".tmp") } catch (e: IOException) { throw FormatException("没法创建临时文件：${e.message}") }
            try {
                try {
                    BufferedOutputStream(FileOutputStream(tmp), 256 * 1024).use { out ->
                        if (gzip) {
                            GZIPInputStream(BufferedInputStream(FileInputStream(file), 256 * 1024), 65536).use { input ->
                                val chunk = ByteArray(256 * 1024)
                                var total = 0L
                                while (true) {
                                    val n = input.read(chunk)
                                    if (n < 0) break
                                    total += n
                                    if (total > MAX_MAPPED) throw FormatException("这个 .blend 解压之后超过 2 GB，读不了")
                                    out.write(chunk, 0, n)
                                }
                            }
                        } else {
                            if (file.length() > MAX_MAPPED) throw FormatException("这个 .blend 超过 2 GB，读不了")
                            Zstd.decompress(map(file), out, MAX_MAPPED)
                        }
                    }
                } catch (e: Zstd.ZstdException) {
                    throw FormatException("这个 .blend 是压缩保存的，解压失败：${e.message}")
                } catch (e: IOException) {
                    val space = (tmp.parentFile?.usableSpace ?: 0L) / (1024 * 1024)
                    throw FormatException(if (space < 64) "手机剩余空间不够（只剩 $space MB），解不开这个压缩保存的 .blend" else "这个 .blend 是压缩保存的，解压失败：${e.message}")
                }
                return BlendFile(map(tmp), if (gzip) "gzip" else "zstd")
            } finally {
                // 映射建立之后文件就可以删了（内容在映射解除之前一直读得到）
                if (!tmp.delete()) tmp.deleteOnExit()
            }
        }

        private fun map(file: File): ByteBuffer = try {
            RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        } catch (e: IOException) { throw FormatException("读不了这个文件：${e.message}") }
    }
}

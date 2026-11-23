package com.lumi.mmd.engine.image

import com.lumi.mmd.engine.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 解码后的图片：RGBA8888、非预乘 alpha、第 0 行是图片的最上面一行。 */
class ImageData(val width: Int, val height: Int, val pixels: ByteBuffer) {
    /** 是否存在 alpha < 255 的像素。 */
    var hasAlpha: Boolean = false

    fun computeHasAlpha(): ImageData {
        val p = pixels
        val n = width * height
        var i = 3
        var found = false
        val end = n * 4
        while (i < end) {
            if (p.get(i).toInt() != -1) { found = true; break }
            i += 4
        }
        hasAlpha = found
        return this
    }

    companion object {
        fun allocate(width: Int, height: Int): ImageData {
            // 大图先量一量放不放得下：把堆塞满的话，出事的是别的线程（整个应用被杀掉），所以宁可这张图不要了。
            // 这里抛出去的错误由 Images.decode 接住，那张贴图按"没加载"处理。
            val bytes = width.toLong() * height * 4
            if (bytes >= 4L * 1024 * 1024 && !com.lumi.mmd.engine.util.Memory.fits(bytes, 24L * 1024 * 1024)) {
                throw OutOfMemoryError("图片 ${width}x${height} 放不下")
            }
            val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            return ImageData(width, height, buf)
        }

        fun solid(r: Int, g: Int, b: Int, a: Int = 255, size: Int = 2): ImageData {
            val img = allocate(size, size)
            for (i in 0 until size * size) {
                img.pixels.put(r.toByte()).put(g.toByte()).put(b.toByte()).put(a.toByte())
            }
            img.pixels.position(0)
            img.hasAlpha = a < 255
            return img
        }
    }
}

/** 平台解码器：Android 上是 BitmapFactory，桌面工具上是 ImageIO。负责 PNG / JPEG / WebP / GIF 等。 */
fun interface PlatformImageDecoder {
    /** @param maxSize 允许的最大边长；超过时应在解码阶段就降采样。失败返回 null。 */
    fun decode(bytes: ByteArray, maxSize: Int): ImageData?
}

/**
 * 图片解码入口。按文件内容（魔数）而不是扩展名判断格式——MMD 模型里"扩展名是 .bmp 其实是 PNG"、
 * ".sph/.spa 其实是 BMP/PNG/JPG"的情况非常常见。
 * TGA、DDS 这两种 Android 不认识的格式，以及 BMP，由引擎自带的纯 Kotlin 解码器处理。
 */
object Images {
    private const val TAG = "Images"

    @Volatile
    @JvmStatic
    var platformDecoder: PlatformImageDecoder? = null

    /** 因为内存不够而没解出来的图片张数。 */
    @Volatile
    @JvmStatic
    var outOfMemoryCount = 0
        private set

    fun decode(bytes: ByteArray, nameHint: String, maxSize: Int): ImageData? {
        if (bytes.size < 4) return null
        return try {
            val img = decodeInternal(bytes, nameHint.lowercase(), maxSize) ?: return null
            val scaled = downscale(img, maxSize)
            scaled.pixels.position(0)
            scaled.computeHasAlpha()
        } catch (e: OutOfMemoryError) {
            outOfMemoryCount++
            Log.w(TAG, "内存不足，跳过贴图 $nameHint")
            null
        } catch (e: Exception) {
            Log.w(TAG, "贴图解码失败 $nameHint: ${e.message}")
            null
        }
    }

    private fun decodeInternal(b: ByteArray, name: String, maxSize: Int): ImageData? {
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        val b2 = b[2].toInt() and 0xFF
        val b3 = b[3].toInt() and 0xFF
        val platform = platformDecoder
        // PNG / JPEG / GIF / WebP → 平台解码器
        val isPng = b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47
        val isJpg = b0 == 0xFF && b1 == 0xD8
        val isGif = b0 == 'G'.code && b1 == 'I'.code && b2 == 'F'.code
        val isWebp = b.size > 12 && b0 == 'R'.code && b1 == 'I'.code && b2 == 'F'.code && b3 == 'F'.code && b[8] == 'W'.code.toByte()
        if (isPng || isJpg || isGif || isWebp) return platform?.decode(b, maxSize)
        if (b0 == 'D'.code && b1 == 'D'.code && b2 == 'S'.code && b3 == ' '.code) return DdsDecoder.decode(b)
        if (b0 == 'B'.code && b1 == 'M'.code) {
            return BmpDecoder.decode(b) ?: platform?.decode(b, maxSize)
        }
        if (name.endsWith(".tga") || name.endsWith(".vda") || name.endsWith(".icb") || name.endsWith(".vst")) {
            TgaDecoder.decode(b)?.let { return it }
        }
        platform?.decode(b, maxSize)?.let { return it }
        // 最后再按 TGA 试一次（TGA 没有魔数）
        return TgaDecoder.decode(b)
    }

    /** 把超过 maxSize 的图片按 2 的幂次做盒式降采样。 */
    fun downscale(img: ImageData, maxSize: Int): ImageData {
        var cur = img
        while (maxSize > 0 && (cur.width > maxSize || cur.height > maxSize) && cur.width > 1 && cur.height > 1) {
            cur = half(cur)
        }
        return cur
    }

    private fun half(src: ImageData): ImageData {
        val w = src.width / 2
        val h = src.height / 2
        val dst = ImageData.allocate(w, h)
        val s = src.pixels
        val d = dst.pixels
        val sw = src.width
        for (y in 0 until h) {
            var so = (y * 2) * sw * 4
            var dox = y * w * 4
            for (x in 0 until w) {
                for (c in 0 until 4) {
                    val v = (s.get(so + c).toInt() and 0xFF) + (s.get(so + 4 + c).toInt() and 0xFF) +
                        (s.get(so + sw * 4 + c).toInt() and 0xFF) + (s.get(so + sw * 4 + 4 + c).toInt() and 0xFF)
                    d.put(dox + c, ((v + 2) shr 2).toByte())
                }
                so += 8
                dox += 4
            }
        }
        return dst
    }

    /** 垂直翻转（原地）。 */
    internal fun flipVertical(img: ImageData) {
        val w4 = img.width * 4
        val a = ByteArray(w4)
        val c = ByteArray(w4)
        val p = img.pixels
        for (y in 0 until img.height / 2) {
            val y2 = img.height - 1 - y
            p.position(y * w4); p.get(a)
            p.position(y2 * w4); p.get(c)
            p.position(y * w4); p.put(c)
            p.position(y2 * w4); p.put(a)
        }
        p.position(0)
    }

    /** 32 位图里 alpha 全为 0 通常表示"没写 alpha"，按不透明处理。 */
    internal fun fixZeroAlpha(img: ImageData) {
        val p = img.pixels
        val end = img.width * img.height * 4
        var i = 3
        while (i < end) {
            if (p.get(i).toInt() != 0) return
            i += 4
        }
        i = 3
        while (i < end) { p.put(i, 0xFF.toByte()); i += 4 }
    }
}

// ============================================================================ TGA

internal object TgaDecoder {
    fun decode(b: ByteArray): ImageData? {
        if (b.size < 18) return null
        val idLen = b[0].toInt() and 0xFF
        val cmapType = b[1].toInt() and 0xFF
        val type = b[2].toInt() and 0xFF
        val cmapStart = u16(b, 3)
        val cmapLen = u16(b, 5)
        val cmapBits = b[7].toInt() and 0xFF
        val w = u16(b, 12)
        val h = u16(b, 14)
        val bpp = b[16].toInt() and 0xFF
        val desc = b[17].toInt() and 0xFF
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return null
        if (cmapType > 1) return null
        val base = type and 7
        val rle = (type and 8) != 0
        if (base !in 1..3 || (type and 0xF0) != 0) return null
        if (base == 1 && (cmapType != 1 || bpp != 8)) return null
        if (base == 2 && bpp != 15 && bpp != 16 && bpp != 24 && bpp != 32) return null
        if (base == 3 && bpp != 8 && bpp != 16) return null
        var pos = 18 + idLen
        // 调色板
        var palette: IntArray? = null
        if (cmapType == 1) {
            val entry = (cmapBits + 7) / 8
            if (pos + cmapLen * entry > b.size) return null
            if (base == 1) {
                palette = IntArray(cmapStart + cmapLen)
                for (i in 0 until cmapLen) palette[cmapStart + i] = readColor(b, pos + i * entry, cmapBits)
            }
            pos += cmapLen * entry
        }
        val bytesPer = (bpp + 7) / 8
        val img = ImageData.allocate(w, h)
        val out = img.pixels
        val total = w * h
        var n = 0
        if (!rle) {
            if (pos + total * bytesPer > b.size) return null
            while (n < total) {
                putPixel(out, n, pixel(b, pos, base, bpp, palette))
                pos += bytesPer
                n++
            }
        } else {
            while (n < total) {
                if (pos >= b.size) break
                val hdr = b[pos++].toInt() and 0xFF
                val count = (hdr and 0x7F) + 1
                if (hdr and 0x80 != 0) {
                    if (pos + bytesPer > b.size) break
                    val c = pixel(b, pos, base, bpp, palette)
                    pos += bytesPer
                    var k = 0
                    while (k < count && n < total) { putPixel(out, n++, c); k++ }
                } else {
                    var k = 0
                    while (k < count && n < total) {
                        if (pos + bytesPer > b.size) break
                        putPixel(out, n++, pixel(b, pos, base, bpp, palette))
                        pos += bytesPer
                        k++
                    }
                }
            }
        }
        out.position(0)
        if ((desc and 0x20) == 0) Images.flipVertical(img)
        if ((desc and 0x10) != 0) flipHorizontal(img)
        if (bpp == 32 || (base == 1 && cmapBits == 32)) Images.fixZeroAlpha(img)
        return img
    }

    private fun flipHorizontal(img: ImageData) {
        val p = img.pixels
        val w = img.width
        for (y in 0 until img.height) {
            for (x in 0 until w / 2) {
                val a = (y * w + x) * 4
                val c = (y * w + w - 1 - x) * 4
                val t = p.getInt(a)
                p.putInt(a, p.getInt(c))
                p.putInt(c, t)
            }
        }
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /** 返回 0xAABBGGRR（按 R,G,B,A 字节序写入时的小端整数）。 */
    private fun readColor(b: ByteArray, o: Int, bits: Int): Int {
        return when (bits) {
            15, 16 -> {
                val v = u16(b, o)
                val r = ((v shr 10) and 31) * 255 / 31
                val g = ((v shr 5) and 31) * 255 / 31
                val bl = (v and 31) * 255 / 31
                r or (g shl 8) or (bl shl 16) or (0xFF shl 24)
            }
            24 -> (b[o + 2].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o].toInt() and 0xFF) shl 16) or (0xFF shl 24)
            32 -> (b[o + 2].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
            else -> { val v = b[o].toInt() and 0xFF; v or (v shl 8) or (v shl 16) or (0xFF shl 24) }
        }
    }

    private fun pixel(b: ByteArray, o: Int, base: Int, bpp: Int, palette: IntArray?): Int {
        return when (base) {
            1 -> {
                val i = b[o].toInt() and 0xFF
                if (palette != null && i < palette.size) palette[i] else 0xFF000000.toInt()
            }
            3 -> {
                val v = b[o].toInt() and 0xFF
                val a = if (bpp == 16) b[o + 1].toInt() and 0xFF else 0xFF
                v or (v shl 8) or (v shl 16) or (a shl 24)
            }
            else -> readColor(b, o, bpp)
        }
    }

    private fun putPixel(out: ByteBuffer, n: Int, c: Int) {
        val o = n * 4
        out.put(o, c.toByte()); out.put(o + 1, (c shr 8).toByte()); out.put(o + 2, (c shr 16).toByte()); out.put(o + 3, (c ushr 24).toByte())
    }
}

// ============================================================================ BMP

internal object BmpDecoder {
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun i32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    fun decode(b: ByteArray): ImageData? {
        if (b.size < 26) return null
        val dataOffset = i32(b, 10)
        val dib = i32(b, 14)
        val w: Int
        var h: Int
        val bpp: Int
        var compression = 0
        var colorsUsed = 0
        val paletteEntry: Int
        if (dib == 12) {
            w = u16(b, 18); h = u16(b, 20); bpp = u16(b, 24); paletteEntry = 3
        } else if (dib >= 40) {
            if (b.size < 54) return null
            w = i32(b, 18); h = i32(b, 22); bpp = u16(b, 28); compression = i32(b, 30); colorsUsed = i32(b, 46); paletteEntry = 4
        } else return null
        val topDown = h < 0
        if (topDown) h = -h
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return null
        if (compression != 0 && compression != 3 && compression != 1) return null
        if (compression == 1 && bpp != 8) return null
        if (bpp != 1 && bpp != 4 && bpp != 8 && bpp != 16 && bpp != 24 && bpp != 32) return null
        if (dataOffset <= 0 || dataOffset >= b.size) return null

        // 位域掩码
        var rMask = 0; var gMask = 0; var bMask = 0; var aMask = 0
        if (bpp == 16) { rMask = 0x7C00; gMask = 0x03E0; bMask = 0x001F }
        if (bpp == 32) { rMask = 0x00FF0000; gMask = 0x0000FF00; bMask = 0x000000FF; aMask = 0xFF000000.toInt() }
        if (compression == 3 || dib >= 52) {
            val mo = 14 + 40
            if (compression == 3 && mo + 12 <= b.size) {
                rMask = i32(b, mo); gMask = i32(b, mo + 4); bMask = i32(b, mo + 8)
                aMask = if (dib >= 56 && mo + 16 <= b.size) i32(b, mo + 12) else 0
            } else if (dib >= 56 && bpp == 32 && mo + 16 <= b.size) {
                aMask = i32(b, mo + 12)
                if (aMask == 0) aMask = 0xFF000000.toInt()
            }
        }

        // 调色板
        var palette: IntArray? = null
        if (bpp <= 8) {
            var count = if (colorsUsed in 1..256) colorsUsed else (1 shl bpp)
            val po = 14 + dib
            count = minOf(count, (dataOffset - po) / paletteEntry, 256)
            if (count <= 0) return null
            palette = IntArray(256) { 0xFF000000.toInt() }
            for (i in 0 until count) {
                val o = po + i * paletteEntry
                if (o + 3 > b.size) break
                palette[i] = (b[o + 2].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o].toInt() and 0xFF) shl 16) or (0xFF shl 24)
            }
        }

        val img = ImageData.allocate(w, h)
        val out = img.pixels
        if (compression == 1) {
            decodeRle8(b, dataOffset, w, h, palette!!, out)
        } else {
            val rowBytes = ((w * bpp + 31) / 32) * 4
            for (y in 0 until h) {
                val row = dataOffset + y * rowBytes
                if (row + (w * bpp + 7) / 8 > b.size) break
                val dy = if (topDown) y else h - 1 - y
                var o = dy * w * 4
                when (bpp) {
                    24 -> {
                        var s = row
                        for (x in 0 until w) {
                            out.put(o, b[s + 2]); out.put(o + 1, b[s + 1]); out.put(o + 2, b[s]); out.put(o + 3, 0xFF.toByte())
                            s += 3; o += 4
                        }
                    }
                    32 -> {
                        var s = row
                        if (compression == 0) {
                            for (x in 0 until w) {
                                out.put(o, b[s + 2]); out.put(o + 1, b[s + 1]); out.put(o + 2, b[s]); out.put(o + 3, b[s + 3])
                                s += 4; o += 4
                            }
                        } else {
                            for (x in 0 until w) {
                                val v = i32(b, s)
                                out.put(o, extract(v, rMask).toByte()); out.put(o + 1, extract(v, gMask).toByte())
                                out.put(o + 2, extract(v, bMask).toByte())
                                out.put(o + 3, (if (aMask != 0) extract(v, aMask) else 255).toByte())
                                s += 4; o += 4
                            }
                        }
                    }
                    16 -> {
                        var s = row
                        for (x in 0 until w) {
                            val v = u16(b, s)
                            out.put(o, extract(v, rMask).toByte()); out.put(o + 1, extract(v, gMask).toByte())
                            out.put(o + 2, extract(v, bMask).toByte())
                            out.put(o + 3, (if (aMask != 0) extract(v, aMask) else 255).toByte())
                            s += 2; o += 4
                        }
                    }
                    else -> {
                        val pal = palette!!
                        for (x in 0 until w) {
                            val idx = when (bpp) {
                                8 -> b[row + x].toInt() and 0xFF
                                4 -> ((b[row + x / 2].toInt() and 0xFF) shr (if (x and 1 == 0) 4 else 0)) and 0x0F
                                else -> ((b[row + x / 8].toInt() and 0xFF) shr (7 - (x and 7))) and 1
                            }
                            val c = pal[idx]
                            out.put(o, c.toByte()); out.put(o + 1, (c shr 8).toByte()); out.put(o + 2, (c shr 16).toByte()); out.put(o + 3, 0xFF.toByte())
                            o += 4
                        }
                    }
                }
            }
        }
        out.position(0)
        if (bpp == 32) Images.fixZeroAlpha(img)
        return img
    }

    private fun extract(v: Int, mask: Int): Int {
        if (mask == 0) return 0
        val shift = Integer.numberOfTrailingZeros(mask)
        val bits = Integer.bitCount(mask)
        val x = (v and mask) ushr shift
        return if (bits >= 8) x ushr (bits - 8) else x * 255 / ((1 shl bits) - 1)
    }

    private fun decodeRle8(b: ByteArray, start: Int, w: Int, h: Int, pal: IntArray, out: ByteBuffer) {
        var p = start
        var x = 0
        var y = 0
        fun put(idx: Int) {
            if (x < w && y < h) {
                val c = pal[idx and 0xFF]
                val o = ((h - 1 - y) * w + x) * 4
                out.put(o, c.toByte()); out.put(o + 1, (c shr 8).toByte()); out.put(o + 2, (c shr 16).toByte()); out.put(o + 3, 0xFF.toByte())
            }
            x++
        }
        for (i in 0 until w * h) out.putInt(i * 4, 0xFF000000.toInt())
        while (p + 1 < b.size && y < h) {
            val n = b[p].toInt() and 0xFF
            val v = b[p + 1].toInt() and 0xFF
            p += 2
            if (n > 0) {
                for (k in 0 until n) put(v)
            } else when (v) {
                0 -> { x = 0; y++ }
                1 -> return
                2 -> { if (p + 1 >= b.size) return; x += b[p].toInt() and 0xFF; y += b[p + 1].toInt() and 0xFF; p += 2 }
                else -> {
                    for (k in 0 until v) { if (p >= b.size) return; put(b[p++].toInt()) }
                    if (v and 1 == 1) p++
                }
            }
        }
    }
}

// ============================================================================ DDS

internal object DdsDecoder {
    private fun i32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    fun decode(b: ByteArray): ImageData? {
        if (b.size < 128) return null
        val h = i32(b, 12)
        val w = i32(b, 16)
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return null
        val pfFlags = i32(b, 80)
        val fourCC = i32(b, 84)
        val bitCount = i32(b, 88)
        val rMask = i32(b, 92); val gMask = i32(b, 96); val bMask = i32(b, 100); val aMask = i32(b, 104)
        val data = 128
        val img = ImageData.allocate(w, h)
        val out = img.pixels
        if (pfFlags and 0x4 != 0) {
            val dxt = when (fourCC) {
                0x31545844 -> 1
                0x32545844, 0x33545844 -> 3
                0x34545844, 0x35545844 -> 5
                else -> return null // DX10 / BC6 / BC7 等不支持
            }
            decodeDxt(b, data, w, h, dxt, out)
        } else if (pfFlags and 0x40 != 0 || pfFlags and 0x20000 != 0 || pfFlags and 0x2 != 0) {
            val bytes = bitCount / 8
            if (bytes !in 1..4 || data + w * h * bytes > b.size) return null
            val lum = pfFlags and 0x20000 != 0
            val hasA = (pfFlags and 0x1 != 0 || pfFlags and 0x2 != 0) && aMask != 0
            var s = data
            for (i in 0 until w * h) {
                var v = 0
                for (k in 0 until bytes) v = v or ((b[s + k].toInt() and 0xFF) shl (8 * k))
                s += bytes
                val r = ext(v, rMask)
                val g = if (lum) r else ext(v, gMask)
                val bl = if (lum) r else ext(v, bMask)
                val a = if (hasA) ext(v, aMask) else 255
                val o = i * 4
                out.put(o, r.toByte()); out.put(o + 1, g.toByte()); out.put(o + 2, bl.toByte()); out.put(o + 3, a.toByte())
            }
        } else return null
        out.position(0)
        return img
    }

    private fun ext(v: Int, mask: Int): Int {
        if (mask == 0) return 0
        val shift = Integer.numberOfTrailingZeros(mask)
        val bits = Integer.bitCount(mask)
        val x = (v and mask) ushr shift
        return if (bits >= 8) x ushr (bits - 8) else x * 255 / ((1 shl bits) - 1)
    }

    private fun decodeDxt(b: ByteArray, start: Int, w: Int, h: Int, dxt: Int, out: ByteBuffer) {
        var p = start
        val blockBytes = if (dxt == 1) 8 else 16
        val colors = IntArray(4)
        val alphas = IntArray(8)
        for (by in 0 until (h + 3) / 4) {
            for (bx in 0 until (w + 3) / 4) {
                if (p + blockBytes > b.size) return
                var alphaBits = 0L
                if (dxt == 3) {
                    for (k in 0 until 8) alphaBits = alphaBits or ((b[p + k].toLong() and 0xFF) shl (8 * k))
                    p += 8
                } else if (dxt == 5) {
                    val a0 = b[p].toInt() and 0xFF
                    val a1 = b[p + 1].toInt() and 0xFF
                    alphas[0] = a0; alphas[1] = a1
                    if (a0 > a1) {
                        for (k in 1..6) alphas[k + 1] = ((7 - k) * a0 + k * a1) / 7
                    } else {
                        for (k in 1..4) alphas[k + 1] = ((5 - k) * a0 + k * a1) / 5
                        alphas[6] = 0; alphas[7] = 255
                    }
                    for (k in 0 until 6) alphaBits = alphaBits or ((b[p + 2 + k].toLong() and 0xFF) shl (8 * k))
                    p += 8
                }
                val c0 = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
                val c1 = (b[p + 2].toInt() and 0xFF) or ((b[p + 3].toInt() and 0xFF) shl 8)
                val bits = i32(b, p + 4)
                p += 8
                val r0 = ((c0 shr 11) and 31) * 255 / 31; val g0 = ((c0 shr 5) and 63) * 255 / 63; val b0 = (c0 and 31) * 255 / 31
                val r1 = ((c1 shr 11) and 31) * 255 / 31; val g1 = ((c1 shr 5) and 63) * 255 / 63; val b1 = (c1 and 31) * 255 / 31
                colors[0] = r0 or (g0 shl 8) or (b0 shl 16) or (0xFF shl 24)
                colors[1] = r1 or (g1 shl 8) or (b1 shl 16) or (0xFF shl 24)
                if (c0 > c1 || dxt != 1) {
                    colors[2] = ((2 * r0 + r1) / 3) or (((2 * g0 + g1) / 3) shl 8) or (((2 * b0 + b1) / 3) shl 16) or (0xFF shl 24)
                    colors[3] = ((r0 + 2 * r1) / 3) or (((g0 + 2 * g1) / 3) shl 8) or (((b0 + 2 * b1) / 3) shl 16) or (0xFF shl 24)
                } else {
                    colors[2] = ((r0 + r1) / 2) or (((g0 + g1) / 2) shl 8) or (((b0 + b1) / 2) shl 16) or (0xFF shl 24)
                    colors[3] = 0
                }
                for (py in 0 until 4) {
                    val y = by * 4 + py
                    if (y >= h) break
                    for (px in 0 until 4) {
                        val x = bx * 4 + px
                        if (x >= w) continue
                        val k = py * 4 + px
                        var c = colors[(bits ushr (k * 2)) and 3]
                        if (dxt == 3) {
                            val a = ((alphaBits ushr (k * 4)) and 0xF).toInt() * 17
                            c = (c and 0x00FFFFFF) or (a shl 24)
                        } else if (dxt == 5) {
                            val a = alphas[((alphaBits ushr (k * 3)) and 7).toInt()]
                            c = (c and 0x00FFFFFF) or (a shl 24)
                        }
                        val o = (y * w + x) * 4
                        out.put(o, c.toByte()); out.put(o + 1, (c shr 8).toByte()); out.put(o + 2, (c shr 16).toByte()); out.put(o + 3, (c ushr 24).toByte())
                    }
                }
            }
        }
    }
}

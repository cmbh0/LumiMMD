package com.lumi.mmd.engine.format

import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.ModelFormat
import com.lumi.mmd.engine.util.BinaryReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** STL（二进制 / ASCII）。Blender 导出的 STL 默认 Z 向上。按位置焊接顶点后重新计算平滑法线会丢失硬边，所以保留面法线。 */
object StlReader {
    fun read(bytes: ByteArray): ModelData {
        val b = MeshBuilder(ModelFormat.STL)
        b.setBasisRightHandedZUp()
        val mat = b.addMaterial(b.newMaterial("STL").also {
            it.diffuse[0] = 0.75f; it.diffuse[1] = 0.77f; it.diffuse[2] = 0.8f
            it.specular.fill(0.25f); it.shininess = 30f
            it.flags = it.flags or MaterialData.FLAG_DOUBLE_SIDED
            it.alphaMode = MaterialData.ALPHA_OPAQUE
        })
        val binaryCount = if (bytes.size >= 84) (bytes[80].toInt() and 0xFF) or ((bytes[81].toInt() and 0xFF) shl 8) or
            ((bytes[82].toInt() and 0xFF) shl 16) or ((bytes[83].toInt() and 0xFF) shl 24) else -1
        val isBinary = binaryCount >= 0 && 84L + binaryCount.toLong() * 50L == bytes.size.toLong()
        if (isBinary) {
            val r = BinaryReader(bytes, 84)
            val f = FloatArray(12)
            for (i in 0 until binaryCount) {
                r.floats(f, 0, 12)
                r.skip(2)
                val a = b.addVertex(f[3], f[4], f[5], f[0], f[1], f[2], 0f, 0f)
                val c = b.addVertex(f[6], f[7], f[8], f[0], f[1], f[2], 0f, 0f)
                val d = b.addVertex(f[9], f[10], f[11], f[0], f[1], f[2], 0f, 0f)
                b.addTriangle(mat, a, c, d)
            }
        } else {
            val sc = ByteScanner(bytes)
            var nx = 0f; var ny = 0f; var nz = 0f
            val tri = IntArray(3)
            var n = 0
            var sawSolid = false
            while (!sc.atEnd()) {
                val t = sc.nextToken()
                if (t < 0) { sc.skipLine(); continue }
                when (sc.tokenString(t).lowercase()) {
                    "solid" -> sawSolid = true
                    "facet" -> { sc.nextToken(); nx = sc.nextFloat(); ny = sc.nextFloat(); nz = sc.nextFloat(); n = 0 }
                    "vertex" -> {
                        val x = sc.nextFloat(); val y = sc.nextFloat(); val z = sc.nextFloat()
                        if (n < 3) tri[n] = b.addVertex(x, y, z, nx, ny, nz, 0f, 0f)
                        n++
                        if (n == 3) b.addTriangle(mat, tri[0], tri[1], tri[2])
                    }
                }
                sc.skipLine()
            }
            if (!sawSolid && b.vertexCount == 0) throw FormatException("不是有效的 STL 文件")
        }
        if (b.vertexCount == 0) throw FormatException("STL 文件里没有三角面")
        val model = b.build(GltfReader.UNIT_SCALE)
        model.suggestedScale = MeshBuilder.suggestScale(model, 1f)
        model.sanitize()
        return model
    }
}

/** PLY（ASCII / 二进制小端 / 大端）：顶点坐标、法线、顶点色、UV、多边形面。点云（没有面）不支持。 */
object PlyReader {
    private class Prop(val name: String, val type: String, val listCountType: String?)
    private class Element(val name: String, val count: Int) { val props = ArrayList<Prop>() }

    private fun sizeOf(t: String): Int = when (t) {
        "char", "uchar", "int8", "uint8" -> 1
        "short", "ushort", "int16", "uint16" -> 2
        "int", "uint", "int32", "uint32", "float", "float32" -> 4
        "double", "float64" -> 8
        else -> throw FormatException("PLY 属性类型未知：$t")
    }

    fun read(bytes: ByteArray): ModelData {
        if (bytes.size < 4 || bytes[0] != 'p'.code.toByte() || bytes[1] != 'l'.code.toByte() || bytes[2] != 'y'.code.toByte()) throw FormatException("不是 PLY 文件")
        // 头部
        var pos = 0
        var format = ""
        val elements = ArrayList<Element>()
        var headerEnd = -1
        while (pos < bytes.size) {
            var e = pos
            while (e < bytes.size && bytes[e] != '\n'.code.toByte()) e++
            val line = String(bytes, pos, e - pos, Charsets.ISO_8859_1).trim()
            pos = e + 1
            val tk = line.split(' ', '\t').filter { it.isNotEmpty() }
            if (tk.isEmpty()) continue
            when (tk[0]) {
                "format" -> format = tk.getOrElse(1) { "" }
                "element" -> elements.add(Element(tk.getOrElse(1) { "" }, tk.getOrElse(2) { "0" }.toIntOrNull() ?: 0))
                "property" -> {
                    val el = elements.lastOrNull() ?: continue
                    if (tk.getOrNull(1) == "list" && tk.size >= 5) el.props.add(Prop(tk[4], tk[3], tk[2]))
                    else if (tk.size >= 3) el.props.add(Prop(tk[2], tk[1], null))
                }
                "end_header" -> { headerEnd = pos; break }
            }
            if (headerEnd >= 0) break
        }
        if (headerEnd < 0) throw FormatException("PLY 头部不完整")
        val ascii = format == "ascii"
        val big = format == "binary_big_endian"
        if (!ascii && !big && format != "binary_little_endian") throw FormatException("PLY 格式未知：$format")

        val b = MeshBuilder(ModelFormat.PLY)
        b.setBasisRightHandedZUp()
        b.flipV = true
        val mat = b.addMaterial(b.newMaterial("PLY").also { it.flags = it.flags or MaterialData.FLAG_DOUBLE_SIDED; it.alphaMode = MaterialData.ALPHA_OPAQUE })
        val buf = ByteBuffer.wrap(bytes).order(if (big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        buf.position(headerEnd)
        val sc = ByteScanner(bytes, headerEnd)

        fun readNum(type: String): Double {
            if (ascii) {
                var t = sc.nextToken()
                while (t < 0 && !sc.atEnd()) { sc.skipLine(); t = sc.nextToken() }
                if (t < 0) throw FormatException("PLY 数据不完整")
                return ByteScanner.parseFloat(bytes, t, sc.tokenEnd, 0f).toDouble()
            }
            if (buf.remaining() < sizeOf(type)) throw FormatException("PLY 数据不完整")
            return when (type) {
                "char", "int8" -> buf.get().toDouble()
                "uchar", "uint8" -> (buf.get().toInt() and 0xFF).toDouble()
                "short", "int16" -> buf.short.toDouble()
                "ushort", "uint16" -> (buf.short.toInt() and 0xFFFF).toDouble()
                "int", "int32" -> buf.int.toDouble()
                "uint", "uint32" -> (buf.int.toLong() and 0xFFFFFFFFL).toDouble()
                "float", "float32" -> buf.float.toDouble()
                else -> buf.double
            }
        }

        var hasColor = false
        val vals = FloatArray(12)
        val poly = IntArray(64)
        for (el in elements) {
            if (el.name == "vertex") {
                // 属性名 → 槽位：0-2 位置, 3-5 法线, 6-7 UV, 8-11 颜色
                val slot = IntArray(el.props.size) { i ->
                    when (el.props[i].name) {
                        "x" -> 0; "y" -> 1; "z" -> 2; "nx" -> 3; "ny" -> 4; "nz" -> 5
                        "s", "u", "texture_u" -> 6; "t", "v", "texture_v" -> 7
                        "red", "r" -> 8; "green", "g" -> 9; "blue", "b" -> 10; "alpha", "a" -> 11
                        else -> -1
                    }
                }
                val colorScale = FloatArray(el.props.size) { i -> if (sizeOf(el.props[i].type) == 1) 1f / 255f else if (sizeOf(el.props[i].type) == 2) 1f / 65535f else 1f }
                hasColor = slot.any { it == 8 }
                if (hasColor) b.enableColors()
                for (i in 0 until el.count) {
                    vals.fill(0f); vals[11] = 1f
                    for ((k, p) in el.props.withIndex()) {
                        if (p.listCountType != null) { val c = readNum(p.listCountType).toInt(); for (j in 0 until c) readNum(p.type); continue }
                        val v = readNum(p.type).toFloat()
                        val s = slot[k]
                        if (s >= 8) vals[s] = v * colorScale[k] else if (s >= 0) vals[s] = v
                    }
                    val idx = b.addVertex(vals[0], vals[1], vals[2], vals[3], vals[4], vals[5], vals[6], vals[7])
                    if (hasColor) b.setColor(idx, vals[8], vals[9], vals[10], vals[11])
                }
            } else {
                val isFace = el.name == "face"
                for (i in 0 until el.count) {
                    for (p in el.props) {
                        if (p.listCountType != null) {
                            val c = readNum(p.listCountType).toInt()
                            if (c < 0 || c > 100000) throw FormatException("PLY 面数据异常")
                            val isIndex = isFace && (p.name == "vertex_indices" || p.name == "vertex_index")
                            for (j in 0 until c) {
                                val v = readNum(p.type).toInt()
                                if (isIndex && j < poly.size) poly[j] = v
                            }
                            if (isIndex) {
                                val n = minOf(c, poly.size)
                                val vc = b.vertexCount
                                for (k in 1 until n - 1) {
                                    if (poly[0] in 0 until vc && poly[k] in 0 until vc && poly[k + 1] in 0 until vc) b.addTriangle(mat, poly[0], poly[k], poly[k + 1])
                                }
                            }
                        } else readNum(p.type)
                    }
                }
            }
        }
        if (b.triangleCount == 0) throw FormatException("PLY 文件里没有面（点云暂不支持）")
        if (hasColor) b.materials[mat].flags = b.materials[mat].flags or MaterialData.FLAG_VERTEX_COLOR
        val model = b.build(GltfReader.UNIT_SCALE)
        model.suggestedScale = MeshBuilder.suggestScale(model, 1f)
        model.sanitize()
        return model
    }
}

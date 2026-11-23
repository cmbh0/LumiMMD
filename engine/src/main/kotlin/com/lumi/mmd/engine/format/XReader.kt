package com.lumi.mmd.engine.format

import com.lumi.mmd.engine.math.Mat4
import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.ModelFormat
import com.lumi.mmd.engine.util.FloatList
import com.lumi.mmd.engine.util.IntList
import com.lumi.mmd.engine.util.TextCodec

/**
 * DirectX .x 模型（MMD 的配件 / 舞台最常用的格式）。支持文本与二进制两种编码，
 * 解析 Frame 层级与变换、Mesh、法线、UV、顶点色、材质表（含 `{名字}` 形式的材质引用）。
 * MSZIP 压缩的 .x（tzip / bzip）不支持。
 *
 * .x 本来就是左手坐标系、顺时针为正面、UV 原点在左上，与 MMD 一致，无需换算；
 * 按 MMD 的惯例，配件默认放大 10 倍（通过 suggestedScale 交给上层）。
 */
object XReader {
    private const val T_EOF = 0
    private const val T_NAME = 1
    private const val T_STRING = 2
    private const val T_NUMBER = 3
    private const val T_OBRACE = 4
    private const val T_CBRACE = 5
    private const val T_OTHER = 6

    private abstract class Tokens {
        var text: String = ""
        var number: Double = 0.0
        abstract fun next(): Int
        private var pushed = -1
        private var pushedText = ""
        private var pushedNumber = 0.0

        fun read(): Int {
            if (pushed >= 0) { val t = pushed; pushed = -1; text = pushedText; number = pushedNumber; return t }
            return next()
        }

        fun unread(t: Int) { pushed = t; pushedText = text; pushedNumber = number }

        /** 读下一个数字，跳过中间夹杂的其它记号；遇到 } 或文件结束则抛错。 */
        fun num(): Double {
            while (true) {
                when (read()) {
                    T_NUMBER -> return number
                    T_CBRACE, T_EOF -> throw FormatException(".x 文件数据不完整")
                }
            }
        }

        fun int(): Int = num().toInt()
        fun float(): Float = num().toFloat()
    }

    private class TextTokens(val d: ByteArray, var p: Int) : Tokens() {
        override fun next(): Int {
            val n = d.size
            while (p < n) {
                val c = d[p].toInt().toChar()
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ';' || c == ',') { p++; continue }
                if (c == '#' || (c == '/' && p + 1 < n && d[p + 1] == '/'.code.toByte())) {
                    while (p < n && d[p] != '\n'.code.toByte()) p++
                    continue
                }
                break
            }
            if (p >= n) return T_EOF
            val c = d[p].toInt().toChar()
            when (c) {
                '{' -> { p++; return T_OBRACE }
                '}' -> { p++; return T_CBRACE }
                '"' -> {
                    val s = ++p
                    while (p < n && d[p] != '"'.code.toByte()) p++
                    text = TextCodec.decodeAuto(d, s, p - s)
                    if (p < n) p++
                    return T_STRING
                }
                '<' -> {
                    while (p < n && d[p] != '>'.code.toByte()) p++
                    if (p < n) p++
                    return T_OTHER
                }
                '[', ']', '(', ')' -> { p++; return T_OTHER }
            }
            val s = p
            if (c == '-' || c == '+' || c == '.' || c in '0'..'9') {
                while (p < n) {
                    val ch = d[p].toInt().toChar()
                    if (ch in '0'..'9' || ch == '.' || ch == '-' || ch == '+' || ch == 'e' || ch == 'E') p++ else break
                }
                // "1.#QNAN0" / "-1.#IND00" 之类
                if (p < n && d[p] == '#'.code.toByte()) {
                    while (p < n) { val ch = d[p].toInt().toChar(); if (ch == ';' || ch == ',' || ch == ' ' || ch == '\n' || ch == '\r') break; p++ }
                    number = 0.0
                    return T_NUMBER
                }
                val isName = p < n && (d[p].toInt().toChar().isLetter() || d[p] == '_'.code.toByte())
                if (!isName) {
                    number = ByteScannerDouble.parse(d, s, p)
                    return T_NUMBER
                }
            }
            while (p < n) {
                val ch = d[p].toInt().toChar()
                if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' || ch == ';' || ch == ',' || ch == '{' || ch == '}' || ch == '"') break
                p++
            }
            if (p == s) { p++; return T_OTHER }
            text = TextCodec.decodeAuto(d, s, p - s)
            return T_NAME
        }
    }

    private object ByteScannerDouble {
        fun parse(d: ByteArray, s: Int, e: Int): Double =
            com.lumi.mmd.engine.format.ByteScanner.parseFloat(d, s, e, 0f).toDouble()
    }

    private class BinaryTokens(val d: ByteArray, var p: Int, val float64: Boolean) : Tokens() {
        private var listLeft = 0
        private var listIsFloat = false

        private fun u16(): Int { if (p + 2 > d.size) return -1; val v = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8); p += 2; return v }
        private fun i32(): Int {
            if (p + 4 > d.size) { p = d.size; return 0 }
            val v = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8) or ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }

        override fun next(): Int {
            if (listLeft > 0) {
                listLeft--
                if (listIsFloat) {
                    if (float64) {
                        val lo = i32().toLong() and 0xFFFFFFFFL
                        val hi = i32().toLong()
                        number = Double.fromBits(lo or (hi shl 32))
                    } else number = Float.fromBits(i32()).toDouble()
                } else number = i32().toDouble()
                return T_NUMBER
            }
            while (true) {
                val tok = u16()
                if (tok < 0) return T_EOF
                when (tok) {
                    1 -> { val len = i32(); if (len < 0 || p + len > d.size) return T_EOF; text = TextCodec.decodeAuto(d, p, len); p += len; return T_NAME }
                    2 -> { val len = i32(); if (len < 0 || p + len > d.size) return T_EOF; text = TextCodec.decodeAuto(d, p, len); p += len; u16(); return T_STRING }
                    3 -> { number = i32().toDouble(); return T_NUMBER }
                    5 -> { p += 16; return T_OTHER }
                    6 -> { listLeft = i32(); listIsFloat = false; if (listLeft <= 0) continue; return next() }
                    7 -> { listLeft = i32(); listIsFloat = true; if (listLeft <= 0) continue; return next() }
                    10 -> return T_OBRACE
                    11 -> return T_CBRACE
                    31 -> { text = "template"; return T_NAME }
                    19, 20 -> continue
                    else -> return T_OTHER
                }
            }
        }
    }

    private class XMaterial(val data: MaterialData) { var texture: String = "" }

    private class Parser(val tk: Tokens) {
        val b = MeshBuilder(ModelFormat.X)
        val globalMaterials = HashMap<String, XMaterial>()
        val materialIndex = HashMap<XMaterial, Int>()
        var defaultMaterial = -1
        var meshCount = 0

        fun parse() {
            b.setBasisIdentity()
            val identity = FloatArray(16).also { Mat4.identity(it, 0) }
            while (true) {
                val t = tk.read()
                if (t == T_EOF) break
                if (t != T_NAME) continue
                parseObject(tk.text, identity)
            }
        }

        /** 读掉可选的对象名和左花括号；返回 false 表示这不是一个数据块。 */
        private fun open(): Boolean {
            var t = tk.read()
            if (t == T_NAME) t = tk.read()
            if (t != T_OBRACE) { tk.unread(t); return false }
            return true
        }

        private fun openNamed(): String? {
            var name = ""
            var t = tk.read()
            if (t == T_NAME) { name = tk.text; t = tk.read() }
            if (t != T_OBRACE) { tk.unread(t); return null }
            return name
        }

        private fun skipBlock() {
            var depth = 1
            while (depth > 0) {
                when (tk.read()) {
                    T_OBRACE -> depth++
                    T_CBRACE -> depth--
                    T_EOF -> return
                }
            }
        }

        private fun parseObject(type: String, world: FloatArray) {
            when (type) {
                "template" -> { if (open()) skipBlock() }
                "Frame" -> { if (open()) parseFrame(world) }
                "Mesh" -> { if (open()) parseMesh(world) }
                "Material" -> {
                    val name = openNamed() ?: return
                    val m = parseMaterial(name)
                    if (name.isNotEmpty()) globalMaterials[name] = m
                }
                else -> { if (open()) skipBlock() }
            }
        }

        private fun parseFrame(parentWorld: FloatArray) {
            var world = parentWorld
            while (true) {
                val t = tk.read()
                if (t == T_CBRACE || t == T_EOF) return
                if (t == T_OBRACE) { skipBlock(); continue }
                if (t != T_NAME) continue
                when (val type = tk.text) {
                    "FrameTransformMatrix" -> {
                        if (!open()) continue
                        val local = FloatArray(16)
                        for (i in 0 until 16) local[i] = tk.float()
                        skipBlock()
                        // D3D 行向量 + 行主序 与 这里的列向量 + 列主序 在内存布局上相同
                        val w = FloatArray(16)
                        Mat4.mul(w, 0, parentWorld, 0, local, 0)
                        world = w
                    }
                    else -> parseObject(type, world)
                }
            }
        }

        private fun parseMaterial(name: String): XMaterial {
            val m = b.newMaterial(name.ifEmpty { "材质" })
            val x = XMaterial(m)
            m.diffuse[0] = tk.float(); m.diffuse[1] = tk.float(); m.diffuse[2] = tk.float(); m.diffuse[3] = tk.float()
            m.shininess = tk.float()
            m.specular[0] = tk.float(); m.specular[1] = tk.float(); m.specular[2] = tk.float()
            val er = tk.float(); val eg = tk.float(); val eb = tk.float()
            // MMD 把 .x 的自发光当作"环境色"：与贴图相乘、不受光照影响
            m.ambient[0] = er * 0.7f; m.ambient[1] = eg * 0.7f; m.ambient[2] = eb * 0.7f
            if (m.diffuse[3] < 0.999f) m.alphaMode = MaterialData.ALPHA_BLEND
            if (m.specular[0] + m.specular[1] + m.specular[2] <= 0f || m.shininess <= 0f) m.shininess = 0f
            while (true) {
                val t = tk.read()
                if (t == T_CBRACE || t == T_EOF) break
                if (t == T_OBRACE) { skipBlock(); continue }
                if (t == T_NAME) {
                    val type = tk.text
                    if (!open()) continue
                    if (type == "TextureFilename" || type == "TextureFileName") {
                        while (true) {
                            val s = tk.read()
                            if (s == T_STRING) x.texture = tk.text
                            if (s == T_CBRACE || s == T_EOF) break
                        }
                    } else skipBlock()
                }
            }
            return x
        }

        private fun indexOf(x: XMaterial): Int = materialIndex.getOrPut(x) {
            val m = x.data
            // MMD 的写法：贴图名*sphere 贴图名
            for (part in x.texture.split('*')) {
                val p = TextCodec.normalizePath(part)
                if (p.isEmpty()) continue
                val lower = p.lowercase()
                if (lower.endsWith(".sph") || lower.endsWith(".spa")) continue
                m.texture = b.texture(p)
            }
            b.addMaterial(m)
        }

        private fun parseMesh(world: FloatArray) {
            meshCount++
            val nv = tk.int()
            if (nv < 0 || nv > 20_000_000) throw FormatException(".x 网格顶点数异常：$nv")
            val pos = FloatArray(nv * 3)
            for (i in 0 until nv * 3) pos[i] = tk.float()
            val nf = tk.int()
            if (nf < 0 || nf > 20_000_000) throw FormatException(".x 网格面数异常：$nf")
            val faceStart = IntArray(nf + 1)
            val faceIdx = IntList(nf * 3)
            for (f in 0 until nf) {
                val c = tk.int()
                if (c < 0 || c > 4096) throw FormatException(".x 面的顶点数异常：$c")
                faceStart[f] = faceIdx.size
                for (k in 0 until c) faceIdx.add(tk.int())
            }
            faceStart[nf] = faceIdx.size

            var normals: FloatArray? = null
            var faceNormalIdx: IntList? = null
            var uvs: FloatArray? = null
            var colors: FloatArray? = null
            var faceMaterial: IntArray? = null
            val meshMaterials = ArrayList<XMaterial>()

            while (true) {
                val t = tk.read()
                if (t == T_CBRACE || t == T_EOF) break
                if (t == T_OBRACE) { skipBlock(); continue }
                if (t != T_NAME) continue
                val type = tk.text
                when (type) {
                    "MeshNormals" -> {
                        if (!open()) continue
                        val nn = tk.int()
                        if (nn < 0 || nn > 20_000_000) throw FormatException(".x 法线数异常")
                        val ns = FloatArray(nn * 3)
                        for (i in 0 until nn * 3) ns[i] = tk.float()
                        val nfn = tk.int()
                        val fi = IntList(maxOf(16, nfn * 3))
                        for (f in 0 until nfn) { val c = tk.int(); for (k in 0 until c) fi.add(tk.int()) }
                        skipBlock()
                        normals = ns
                        if (fi.size == faceIdx.size) faceNormalIdx = fi
                    }
                    "MeshTextureCoords" -> {
                        if (!open()) continue
                        val n = tk.int()
                        if (n < 0 || n > 20_000_000) throw FormatException(".x UV 数异常")
                        val u = FloatArray(n * 2)
                        for (i in 0 until n * 2) u[i] = tk.float()
                        skipBlock()
                        uvs = u
                    }
                    "MeshVertexColors" -> {
                        if (!open()) continue
                        val n = tk.int()
                        val cs = FloatArray(nv * 4) { 1f }
                        for (i in 0 until n) {
                            val vi = tk.int()
                            val r = tk.float(); val g = tk.float(); val bl = tk.float(); val a = tk.float()
                            if (vi in 0 until nv) { cs[vi * 4] = r; cs[vi * 4 + 1] = g; cs[vi * 4 + 2] = bl; cs[vi * 4 + 3] = a }
                        }
                        skipBlock()
                        colors = cs
                    }
                    "MeshMaterialList" -> {
                        if (!open()) continue
                        tk.int() // 材质数
                        val n = tk.int()
                        if (n < 0 || n > 20_000_000) throw FormatException(".x 材质表异常")
                        val fm = IntArray(n)
                        for (i in 0 until n) fm[i] = tk.int()
                        faceMaterial = fm
                        while (true) {
                            val s = tk.read()
                            if (s == T_CBRACE || s == T_EOF) break
                            if (s == T_OBRACE) {
                                // { 材质名 } 引用
                                val r = tk.read()
                                if (r == T_NAME) globalMaterials[tk.text]?.let { meshMaterials.add(it) }
                                if (r != T_CBRACE) skipBlock()
                                continue
                            }
                            if (s == T_NAME && tk.text == "Material") {
                                val name = openNamed() ?: continue
                                val m = parseMaterial(name)
                                if (name.isNotEmpty()) globalMaterials[name] = m
                                meshMaterials.add(m)
                            } else if (s == T_NAME) {
                                if (open()) skipBlock()
                            }
                        }
                    }
                    else -> { if (open()) skipBlock() }
                }
            }

            // Frame 变换带镜像（行列式为负）时要交换绕序，否则会里外翻转
            val mirrored = world[0] * (world[5] * world[10] - world[6] * world[9]) - world[4] * (world[1] * world[10] - world[2] * world[9]) +
                world[8] * (world[1] * world[6] - world[2] * world[5]) < 0f
            // 组装：顶点 = (位置序号, 法线序号) 的组合
            if (colors != null) b.enableColors()
            val map = HashMap<Long, Int>(nv * 2)
            val nrm = normals
            val fni = faceNormalIdx
            val tmp = FloatArray(8)
            val corner = IntArray(64)
            for (f in 0 until nf) {
                val s = faceStart[f]
                val c = minOf(faceStart[f + 1] - s, corner.size)
                var matSlot = 0
                val fm = faceMaterial
                if (fm != null && fm.isNotEmpty()) matSlot = if (f < fm.size) fm[f] else fm[fm.size - 1]
                val material = if (matSlot in meshMaterials.indices) indexOf(meshMaterials[matSlot]) else {
                    if (defaultMaterial < 0) defaultMaterial = b.addMaterial(b.newMaterial("默认材质").also { it.alphaMode = MaterialData.ALPHA_OPAQUE })
                    defaultMaterial
                }
                var valid = true
                for (k in 0 until c) {
                    val vi = faceIdx[s + k]
                    if (vi < 0 || vi >= nv) { valid = false; break }
                    var ni = if (fni != null) fni[s + k] else if (nrm != null && nrm.size == nv * 3) vi else -1
                    if (nrm == null || ni < 0 || ni * 3 + 2 >= nrm.size) ni = -1
                    val key = vi.toLong() or ((ni + 1).toLong() shl 32)
                    var idx = map[key]
                    if (idx == null) {
                        Mat4.transformPoint(world, 0, pos[vi * 3], pos[vi * 3 + 1], pos[vi * 3 + 2], tmp, 0)
                        if (ni >= 0) Mat4.transformDir(world, 0, nrm!![ni * 3], nrm[ni * 3 + 1], nrm[ni * 3 + 2], tmp, 3) else { tmp[3] = 0f; tmp[4] = 0f; tmp[5] = 0f }
                        val u = uvs
                        val has = u != null && vi * 2 + 1 < u.size
                        idx = b.addVertex(tmp[0], tmp[1], tmp[2], tmp[3], tmp[4], tmp[5], if (has) u!![vi * 2] else 0f, if (has) u!![vi * 2 + 1] else 0f)
                        val cs = colors
                        if (cs != null) b.setColor(idx, cs[vi * 4], cs[vi * 4 + 1], cs[vi * 4 + 2], cs[vi * 4 + 3])
                        map[key] = idx
                    }
                    corner[k] = idx
                }
                if (!valid) continue
                for (k in 1 until c - 1) {
                    if (mirrored) b.addTriangle(material, corner[0], corner[k + 1], corner[k]) else b.addTriangle(material, corner[0], corner[k], corner[k + 1])
                }
            }
            if (colors != null) for (m in meshMaterials) m.data.flags = m.data.flags or MaterialData.FLAG_VERTEX_COLOR
        }
    }

    fun isX(bytes: ByteArray): Boolean = bytes.size >= 16 && bytes[0] == 'x'.code.toByte() && bytes[1] == 'o'.code.toByte() && bytes[2] == 'f'.code.toByte() && bytes[3] == ' '.code.toByte()

    fun read(bytes: ByteArray): ModelData {
        if (!isX(bytes)) throw FormatException("不是 DirectX .x 文件")
        val fmt = String(bytes, 8, 4, Charsets.ISO_8859_1)
        val floatSize = String(bytes, 12, 4, Charsets.ISO_8859_1)
        val tokens: Tokens = when (fmt) {
            "txt " -> TextTokens(bytes, 16)
            "bin " -> BinaryTokens(bytes, 16, floatSize == "0064")
            "tzip", "bzip" -> throw FormatException("该 .x 文件使用了 MSZIP 压缩，暂不支持。可以用 PMX 编辑器等工具另存为未压缩的 .x")
            else -> throw FormatException(".x 文件编码未知：$fmt")
        }
        val p = Parser(tokens)
        p.parse()
        if (p.b.vertexCount == 0) throw FormatException(".x 文件里没有网格")
        val model = p.b.build()
        // MMD 加载 .x 配件时默认放大 10 倍
        model.suggestedScale = MeshBuilder.suggestScale(model, 10f)
        model.sanitize()
        return model
    }
}

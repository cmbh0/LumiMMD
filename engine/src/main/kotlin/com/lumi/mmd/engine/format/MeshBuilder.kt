package com.lumi.mmd.engine.format

import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.ModelFormat
import com.lumi.mmd.engine.model.TextureRef
import com.lumi.mmd.engine.util.FloatList
import com.lumi.mmd.engine.util.IntList
import kotlin.math.sqrt

/**
 * 通用格式（OBJ / X / glTF / FBX / STL / PLY）导入器共用的网格组装器：
 * 收集顶点与"按材质分组"的三角形，最后统一做坐标系换算、补法线，产出 [ModelData]。
 *
 * 坐标系换算用一个 3x3 基变换表示。右手系（Y 向上或 Z 向上）换到 MMD 的左手系都是镜像变换，
 * 行列式为负时自动交换三角形绕序。
 */
class MeshBuilder(private val format: ModelFormat) {
    val positions = FloatList(4096)
    val normals = FloatList(4096)
    val uvs = FloatList(4096)
    var colors: FloatList? = null
    /** 蒙皮数据（可选）：每顶点 4 个骨骼序号 / 权重。 */
    var boneIndices: IntList? = null
    var boneWeights: FloatList? = null

    val materials = ArrayList<MaterialData>()
    val textures = ArrayList<TextureRef>()
    private val textureIndex = HashMap<String, Int>()
    private val embeddedIndex = HashMap<ByteArray, Int>()
    private val triangles = ArrayList<IntList>()
    val warnings = ArrayList<String>()

    /** 源坐标 → MMD 坐标的基变换（按行存储）。默认：右手 Y-up → 左手 Y-up（Z 取反）。 */
    private val basis = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, -1f)
    /** UV 的 V 是否需要翻转（原点在左下角的格式）。 */
    var flipV = false

    val vertexCount: Int get() = positions.size / 3

    /** 预先留出再加 [vertices] 个顶点的位置（大模型：一次到位，免得数组一倍一倍地长、白占一倍内存）。 */
    fun reserve(vertices: Int) {
        if (vertices < 4096) return
        positions.reserve(vertices * 3); normals.reserve(vertices * 3); uvs.reserve(vertices * 2)
    }

    fun setBasisRightHandedYUp() { set(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, -1f) }
    /** Blender 等 Z 向上的右手系：x' = x, y' = z, z' = y。 */
    fun setBasisRightHandedZUp() { set(1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f) }
    /** 已经是 MMD / DirectX 的左手 Y-up。 */
    fun setBasisIdentity() { set(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f) }

    private fun set(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float, g: Float, h: Float, i: Float) {
        basis[0] = a; basis[1] = b; basis[2] = c; basis[3] = d; basis[4] = e; basis[5] = f; basis[6] = g; basis[7] = h; basis[8] = i
    }

    fun addVertex(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float): Int {
        val i = positions.size / 3
        positions.add(px, py, pz)
        normals.add(nx, ny, nz)
        uvs.add(u, v)
        colors?.add(1f, 1f, 1f, 1f)
        boneIndices?.let { it.add(0); it.add(0); it.add(0); it.add(0) }
        boneWeights?.add(1f, 0f, 0f, 0f)
        return i
    }

    /** 开启顶点色（之前已加入的顶点补成白色）。 */
    fun enableColors() {
        if (colors != null) return
        val c = FloatList(maxOf(16, vertexCount * 4))
        for (i in 0 until vertexCount) c.add(1f, 1f, 1f, 1f)
        colors = c
    }

    fun setColor(vertex: Int, r: Float, g: Float, b: Float, a: Float) {
        val c = colors ?: return
        val o = vertex * 4
        c[o] = r; c[o + 1] = g; c[o + 2] = b; c[o + 3] = a
    }

    fun enableSkin() {
        if (boneIndices != null) return
        val bi = IntList(maxOf(16, vertexCount * 4))
        val bw = FloatList(maxOf(16, vertexCount * 4))
        for (i in 0 until vertexCount) { bi.add(0); bi.add(0); bi.add(0); bi.add(0); bw.add(1f, 0f, 0f, 0f) }
        boneIndices = bi; boneWeights = bw
    }

    fun setSkin(vertex: Int, b0: Int, b1: Int, b2: Int, b3: Int, w0: Float, w1: Float, w2: Float, w3: Float) {
        val bi = boneIndices ?: return
        val bw = boneWeights ?: return
        val o = vertex * 4
        bi[o] = b0; bi[o + 1] = b1; bi[o + 2] = b2; bi[o + 3] = b3
        bw[o] = w0; bw[o + 1] = w1; bw[o + 2] = w2; bw[o + 3] = w3
    }

    fun addMaterial(m: MaterialData): Int {
        materials.add(m)
        triangles.add(IntList(256))
        return materials.size - 1
    }

    /** 新建一个通用材质（Lambert + 高光）。 */
    fun newMaterial(name: String): MaterialData {
        val m = MaterialData()
        m.name = name
        m.shading = MaterialData.SHADING_STANDARD
        m.alphaMode = MaterialData.ALPHA_MASK
        m.alphaCutoff = 0.5f
        m.flags = MaterialData.FLAG_CAST_SHADOW or MaterialData.FLAG_RECEIVE_SHADOW or MaterialData.FLAG_GROUND_SHADOW
        m.diffuse[0] = 0.8f; m.diffuse[1] = 0.8f; m.diffuse[2] = 0.8f; m.diffuse[3] = 1f
        m.specular.fill(0f)
        m.shininess = 0f
        m.ambient.fill(0f)
        return m
    }

    fun texture(path: String): Int {
        if (path.isBlank()) return -1
        val key = com.lumi.mmd.engine.util.TextCodec.normalizePath(path)
        return textureIndex.getOrPut(key) { textures.add(TextureRef(key)); textures.size - 1 }
    }

    fun embeddedTexture(name: String, bytes: ByteArray): Int {
        return embeddedIndex.getOrPut(bytes) { textures.add(TextureRef(name, bytes)); textures.size - 1 }
    }

    fun addTriangle(material: Int, a: Int, b: Int, c: Int) {
        if (a == b || b == c || a == c) return
        triangles[material].add(a, b, c)
    }

    val triangleCount: Int get() = triangles.sumOf { it.size / 3 }

    /**
     * 产出最终模型。
     * @param scale 顶点坐标的整体缩放（单位换算）
     */
    fun build(scale: Float = 1f): ModelData {
        val model = ModelData()
        model.format = format
        val n = vertexCount
        // 一份一份地交出来（交出来之后表里不再占内存）：大模型组装时不会同时存着两份
        val pos = positions.detach()
        val nrm = normals.detach()
        val uv = uvs.detach()
        val b = basis
        val det = b[0] * (b[4] * b[8] - b[5] * b[7]) - b[1] * (b[3] * b[8] - b[5] * b[6]) + b[2] * (b[3] * b[7] - b[4] * b[6])
        val mirror = det < 0f
        val identity = b[0] == 1f && b[4] == 1f && b[8] == 1f && b[1] == 0f && b[2] == 0f && b[3] == 0f && b[5] == 0f && b[6] == 0f && b[7] == 0f
        for (v in 0 until n) {
            val o = v * 3
            if (!identity) {
                var x = pos[o]; var y = pos[o + 1]; var z = pos[o + 2]
                pos[o] = b[0] * x + b[1] * y + b[2] * z
                pos[o + 1] = b[3] * x + b[4] * y + b[5] * z
                pos[o + 2] = b[6] * x + b[7] * y + b[8] * z
                x = nrm[o]; y = nrm[o + 1]; z = nrm[o + 2]
                nrm[o] = b[0] * x + b[1] * y + b[2] * z
                nrm[o + 1] = b[3] * x + b[4] * y + b[5] * z
                nrm[o + 2] = b[6] * x + b[7] * y + b[8] * z
            }
            if (scale != 1f) { pos[o] *= scale; pos[o + 1] *= scale; pos[o + 2] *= scale }
        }
        if (flipV) for (v in 0 until n) uv[v * 2 + 1] = 1f - uv[v * 2 + 1]

        // 材质：去掉没有面的；索引按材质顺序拼接
        var total = 0
        for (t in triangles) total += t.size
        val idx = IntArray(total)
        var offset = 0
        for ((i, m) in materials.withIndex()) {
            val t = triangles[i]
            if (t.size == 0) continue
            val d = t.data
            if (mirror) {
                var k = 0
                while (k < t.size) { idx[offset + k] = d[k]; idx[offset + k + 1] = d[k + 2]; idx[offset + k + 2] = d[k + 1]; k += 3 }
            } else {
                System.arraycopy(d, 0, idx, offset, t.size)
            }
            m.indexOffset = offset
            m.indexCount = t.size
            offset += t.size
            model.materials.add(m)
            triangles[i] = IntList(16)
        }
        if (model.materials.isEmpty() && n > 0) warnings.add("模型里没有任何三角面")

        model.vertexCount = n
        model.positions = pos
        model.normals = nrm
        model.uvs = uv
        model.colors = colors?.toArray()
        model.indices = idx
        model.textures.addAll(textures)
        val bi = boneIndices
        val bw = boneWeights
        if (bi != null && bw != null) {
            val s = ShortArray(n * 4)
            for (i in 0 until n * 4) s[i] = bi[i].toShort()
            model.boneIndices = s
            model.boneWeights = bw.toArray()
        }
        fixNormals(model)
        model.warnings.addAll(warnings)
        return model
    }

    companion object {
        /** 把 4 个整数混合成一个 64 位哈希键（顶点去重用；碰撞概率可以忽略）。 */
        fun hashKey(a: Int, b: Int, c: Int, d: Int): Long {
            var h = a.toLong() * -0x61c8864680b583ebL
            h = (h xor (h ushr 29) xor b.toLong()) * -0x40a7b892e31b1a47L
            h = (h xor (h ushr 32) xor c.toLong()) * -0x6b2fb644ecceee15L
            h = (h xor (h ushr 29) xor d.toLong()) * -0x61c8864680b583ebL
            return h xor (h ushr 32)
        }

        /** 给法线缺失（零向量）的顶点按面积加权补上平滑法线，并把所有法线归一化。 */
        fun fixNormals(model: ModelData) {
            val n = model.vertexCount
            val nrm = model.normals
            val pos = model.positions
            val idx = model.indices
            var missing = false
            for (v in 0 until n) {
                val o = v * 3
                val l = nrm[o] * nrm[o] + nrm[o + 1] * nrm[o + 1] + nrm[o + 2] * nrm[o + 2]
                if (l < 1e-12f || l != l) { missing = true; break }
            }
            if (missing) {
                val need = BooleanArray(n)
                for (v in 0 until n) {
                    val o = v * 3
                    val l = nrm[o] * nrm[o] + nrm[o + 1] * nrm[o + 1] + nrm[o + 2] * nrm[o + 2]
                    if (l < 1e-12f || l != l) { need[v] = true; nrm[o] = 0f; nrm[o + 1] = 0f; nrm[o + 2] = 0f }
                }
                var t = 0
                while (t + 2 < idx.size) {
                    val a = idx[t]; val b = idx[t + 1]; val c = idx[t + 2]
                    t += 3
                    if (!need[a] && !need[b] && !need[c]) continue
                    val ax = pos[a * 3]; val ay = pos[a * 3 + 1]; val az = pos[a * 3 + 2]
                    val e1x = pos[b * 3] - ax; val e1y = pos[b * 3 + 1] - ay; val e1z = pos[b * 3 + 2] - az
                    val e2x = pos[c * 3] - ax; val e2y = pos[c * 3 + 1] - ay; val e2z = pos[c * 3 + 2] - az
                    // 左手系 + 顺时针为正面：法线 = e1 × e2
                    val fx = e1y * e2z - e1z * e2y; val fy = e1z * e2x - e1x * e2z; val fz = e1x * e2y - e1y * e2x
                    if (need[a]) { nrm[a * 3] += fx; nrm[a * 3 + 1] += fy; nrm[a * 3 + 2] += fz }
                    if (need[b]) { nrm[b * 3] += fx; nrm[b * 3 + 1] += fy; nrm[b * 3 + 2] += fz }
                    if (need[c]) { nrm[c * 3] += fx; nrm[c * 3 + 1] += fy; nrm[c * 3 + 2] += fz }
                }
            }
            for (v in 0 until n) {
                val o = v * 3
                val l = sqrt(nrm[o] * nrm[o] + nrm[o + 1] * nrm[o + 1] + nrm[o + 2] * nrm[o + 2])
                if (l > 1e-12f && l == l) { nrm[o] /= l; nrm[o + 1] /= l; nrm[o + 2] /= l } else { nrm[o] = 0f; nrm[o + 1] = 1f; nrm[o + 2] = 0f }
            }
        }

        /**
         * 给"单位不明"的模型估一个导入缩放，让它落在 MMD 场景的合理尺寸里。
         * @param assumed 按格式惯例假设的缩放（例如米 → MMD 单位的 12.5）
         */
        fun suggestScale(model: ModelData, assumed: Float): Float {
            model.computeBounds()
            val size = maxOf(model.boundsMax[0] - model.boundsMin[0], model.boundsMax[1] - model.boundsMin[1], model.boundsMax[2] - model.boundsMin[2])
            if (size <= 0f) return assumed
            val scaled = size * assumed
            return when {
                scaled < 0.5f -> 20f / size       // 小得看不见：放大到一人高
                scaled > 1500f -> 300f / size     // 大得离谱（多半是以厘米 / 毫米为单位）：缩到舞台大小
                else -> assumed
            }
        }
    }
}

/** 在字节数组上直接解析文本（OBJ / PLY / X 等大文件用），避免为每个数字创建字符串。 */
class ByteScanner(val data: ByteArray, var pos: Int = 0, val end: Int = data.size) {
    fun atEnd(): Boolean = pos >= end

    fun skipSpaces() {
        while (pos < end) { val c = data[pos]; if (c == ' '.code.toByte() || c == '\t'.code.toByte() || c == '\r'.code.toByte()) pos++ else break }
    }

    fun skipLine() {
        while (pos < end && data[pos] != '\n'.code.toByte()) pos++
        if (pos < end) pos++
    }

    fun atLineEnd(): Boolean { skipSpaces(); return pos >= end || data[pos] == '\n'.code.toByte() }

    /** 读一个以空白分隔的词（不产生字符串）：返回起点，结束位置在 [tokenEnd]。行尾返回 -1。 */
    var tokenEnd = 0
    fun nextToken(): Int {
        skipSpaces()
        if (pos >= end || data[pos] == '\n'.code.toByte()) return -1
        val start = pos
        while (pos < end) {
            val c = data[pos]
            if (c == ' '.code.toByte() || c == '\t'.code.toByte() || c == '\r'.code.toByte() || c == '\n'.code.toByte()) break
            pos++
        }
        tokenEnd = pos
        return start
    }

    fun tokenString(start: Int): String = String(data, start, tokenEnd - start, Charsets.ISO_8859_1)

    /** 行内剩余的内容（去掉首尾空白）。 */
    fun restOfLine(): ByteArray {
        skipSpaces()
        val start = pos
        while (pos < end && data[pos] != '\n'.code.toByte()) pos++
        var e = pos
        while (e > start && (data[e - 1] == '\r'.code.toByte() || data[e - 1] == ' '.code.toByte() || data[e - 1] == '\t'.code.toByte())) e--
        return data.copyOfRange(start, e)
    }

    fun nextFloat(default: Float = 0f): Float {
        val s = nextToken()
        if (s < 0) return default
        return parseFloat(data, s, tokenEnd, default)
    }

    fun nextInt(default: Int = 0): Int {
        val s = nextToken()
        if (s < 0) return default
        return parseInt(data, s, tokenEnd, default)
    }

    companion object {
        fun parseInt(d: ByteArray, start: Int, end: Int, default: Int): Int {
            var i = start
            if (i >= end) return default
            var neg = false
            if (d[i] == '-'.code.toByte()) { neg = true; i++ } else if (d[i] == '+'.code.toByte()) i++
            var v = 0L
            var any = false
            while (i < end) {
                val c = d[i] - '0'.code.toByte()
                if (c < 0 || c > 9) break
                v = v * 10 + c
                any = true
                i++
            }
            if (!any) return default
            return (if (neg) -v else v).toInt()
        }

        fun parseFloat(d: ByteArray, start: Int, end: Int, default: Float): Float {
            var i = start
            if (i >= end) return default
            var neg = false
            if (d[i] == '-'.code.toByte()) { neg = true; i++ } else if (d[i] == '+'.code.toByte()) i++
            var mant = 0.0
            var any = false
            while (i < end) {
                val c = d[i] - '0'.code.toByte()
                if (c < 0 || c > 9) break
                mant = mant * 10.0 + c
                any = true
                i++
            }
            if (i < end && d[i] == '.'.code.toByte()) {
                i++
                var scale = 0.1
                while (i < end) {
                    val c = d[i] - '0'.code.toByte()
                    if (c < 0 || c > 9) break
                    mant += c * scale
                    scale *= 0.1
                    any = true
                    i++
                }
            }
            if (!any) {
                // nan / inf 等
                return String(d, start, end - start, Charsets.ISO_8859_1).toFloatOrNull() ?: default
            }
            if (i < end && (d[i] == 'e'.code.toByte() || d[i] == 'E'.code.toByte())) {
                val ex = parseInt(d, i + 1, end, 0)
                mant *= Math.pow(10.0, ex.toDouble())
            }
            return (if (neg) -mant else mant).toFloat()
        }
    }
}

package com.lumi.mmd.engine.format

import com.lumi.mmd.engine.math.Mat4
import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.ModelFormat
import com.lumi.mmd.engine.util.LongIntMap
import java.util.zip.Inflater
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FBX（二进制，7.x）导入：网格、节点层级与变换（含 PreRotation / 轴心 / Geometric 变换）、
 * 材质与贴图（外部文件或内嵌）、顶点色。用于导入 Blender / Maya / Max 导出的道具与场景。
 *
 * 骨骼蒙皮与动画曲线不读取——带骨架的角色会按静止姿势导入成静态模型；需要动画的模型请用 glTF(.glb)。
 * ASCII 编码的 FBX 不支持。
 */
object FbxReader {
    private class Node(val name: String) {
        var props: Array<Any?> = EMPTY
        val children = ArrayList<Node>()
        fun child(n: String): Node? { for (c in children) if (c.name == n) return c; return null }
        fun str(i: Int): String = props.getOrNull(i) as? String ?: ""
        fun long(i: Int): Long = (props.getOrNull(i) as? Number)?.toLong() ?: 0L
    }

    private val EMPTY = arrayOfNulls<Any>(0)

    fun isBinaryFbx(b: ByteArray): Boolean {
        val magic = "Kaydara FBX Binary  "
        if (b.size < 27) return false
        for (i in magic.indices) if (b[i] != magic[i].code.toByte()) return false
        return true
    }

    private class Parser(val d: ByteArray, val version: Int) {
        var p = 27
        val wide = version >= 7500

        fun u32(): Long {
            if (p + 4 > d.size) throw FormatException("FBX 文件不完整")
            val v = (d[p].toLong() and 0xFF) or ((d[p + 1].toLong() and 0xFF) shl 8) or ((d[p + 2].toLong() and 0xFF) shl 16) or ((d[p + 3].toLong() and 0xFF) shl 24)
            p += 4
            return v
        }

        fun i32(): Int = u32().toInt()
        fun i64(): Long { val lo = u32(); val hi = u32(); return lo or (hi shl 32) }
        fun offset(): Long = if (wide) i64() else u32()

        /** 读一个节点；返回 null 表示遇到了结束用的空记录。 */
        fun node(depth: Int): Node? {
            if (depth > 64) throw FormatException("FBX 节点嵌套过深")
            val end = offset()
            val numProps = offset()
            offset() // 属性区长度
            if (p >= d.size) throw FormatException("FBX 文件不完整")
            val nameLen = d[p++].toInt() and 0xFF
            if (end == 0L) return null
            if (end > d.size || numProps > 1_000_000) throw FormatException("FBX 节点数据异常")
            val name = String(d, p, nameLen, Charsets.ISO_8859_1)
            p += nameLen
            val n = Node(name)
            if (numProps > 0) {
                val props = arrayOfNulls<Any>(numProps.toInt())
                for (i in props.indices) props[i] = prop()
                n.props = props
            }
            val nullSize = if (wide) 25 else 13
            while (p < end) {
                if (end - p <= nullSize) { p = end.toInt(); break }
                val c = node(depth + 1) ?: break
                n.children.add(c)
            }
            p = end.toInt()
            return n
        }

        private fun prop(): Any? {
            if (p >= d.size) throw FormatException("FBX 文件不完整")
            val type = d[p++].toInt().toChar()
            return when (type) {
                'Y' -> { val v = ((d[p].toInt() and 0xFF) or (d[p + 1].toInt() shl 8)).toShort().toInt(); p += 2; v }
                'C' -> (d[p++].toInt() != 0)
                'I' -> i32()
                'F' -> Float.fromBits(i32())
                'D' -> Double.fromBits(i64())
                'L' -> i64()
                'S', 'R' -> {
                    val len = i32()
                    if (len < 0 || p + len > d.size) throw FormatException("FBX 字符串越界")
                    val start = p
                    p += len
                    if (type == 'S') String(d, start, len, Charsets.UTF_8) else d.copyOfRange(start, start + len)
                }
                'f', 'd', 'l', 'i', 'b' -> array(type)
                else -> throw FormatException("FBX 属性类型未知：$type")
            }
        }

        private fun array(type: Char): Any {
            val count = i32()
            val encoding = i32()
            val compLen = i32()
            val elem = when (type) { 'f', 'i' -> 4; 'd', 'l' -> 8; else -> 1 }
            if (count < 0 || count.toLong() * elem > 1_500_000_000L) throw FormatException("FBX 数组过大")
            val raw: ByteArray
            var ro: Int
            if (encoding == 1) {
                if (compLen < 0 || p + compLen > d.size) throw FormatException("FBX 压缩数组越界")
                raw = ByteArray(count * elem)
                val inf = Inflater()
                try {
                    inf.setInput(d, p, compLen)
                    var got = 0
                    while (got < raw.size) {
                        val n = inf.inflate(raw, got, raw.size - got)
                        if (n <= 0) { if (inf.finished() || inf.needsInput() || inf.needsDictionary()) break }
                        got += n
                    }
                } catch (e: Exception) {
                    throw FormatException("FBX 数组解压失败：${e.message}")
                } finally { inf.end() }
                ro = 0
                p += compLen
            } else {
                if (p + count * elem > d.size) throw FormatException("FBX 数组越界")
                raw = d
                ro = p
                p += count * elem
            }
            fun i4(o: Int) = (raw[o].toInt() and 0xFF) or ((raw[o + 1].toInt() and 0xFF) shl 8) or ((raw[o + 2].toInt() and 0xFF) shl 16) or ((raw[o + 3].toInt() and 0xFF) shl 24)
            fun i8(o: Int) = (i4(o).toLong() and 0xFFFFFFFFL) or (i4(o + 4).toLong() shl 32)
            return when (type) {
                'f' -> DoubleArray(count) { Float.fromBits(i4(ro + it * 4)).toDouble() }
                'd' -> DoubleArray(count) { Double.fromBits(i8(ro + it * 8)) }
                'i' -> IntArray(count) { i4(ro + it * 4) }
                'l' -> IntArray(count) { i8(ro + it * 8).toInt() }
                else -> IntArray(count) { raw[ro + it].toInt() }
            }
        }
    }

    private class Props(node: Node?) {
        val map = HashMap<String, Node>()
        init { node?.child("Properties70")?.children?.forEach { if (it.name == "P") map[it.str(0)] = it } }
        fun vec3(name: String, dx: Double, dy: Double, dz: Double, out: DoubleArray): Boolean {
            val p = map[name]
            if (p == null || p.props.size < 7) { out[0] = dx; out[1] = dy; out[2] = dz; return false }
            out[0] = (p.props[4] as? Number)?.toDouble() ?: dx
            out[1] = (p.props[5] as? Number)?.toDouble() ?: dy
            out[2] = (p.props[6] as? Number)?.toDouble() ?: dz
            return true
        }
        fun num(name: String, def: Double): Double = (map[name]?.props?.getOrNull(4) as? Number)?.toDouble() ?: def
        fun has(name: String) = map.containsKey(name)
    }

    private fun typeName(n: Node): String = n.str(2)
    private fun objName(n: Node): String = n.str(1).substringBefore('\u0000')

    fun read(bytes: ByteArray): ModelData {
        if (!isBinaryFbx(bytes)) {
            val head = String(bytes, 0, minOf(bytes.size, 64), Charsets.ISO_8859_1)
            if (head.contains("FBX")) throw FormatException("这是 ASCII 编码的 FBX，暂不支持。请导出为二进制 FBX，或改用 glTF(.glb)")
            throw FormatException("不是 FBX 文件")
        }
        val version = (bytes[23].toInt() and 0xFF) or ((bytes[24].toInt() and 0xFF) shl 8) or ((bytes[25].toInt() and 0xFF) shl 16) or ((bytes[26].toInt() and 0xFF) shl 24)
        if (version < 7000) throw FormatException("FBX 版本太旧（$version），请用 FBX 7.x 重新导出")
        val parser = Parser(bytes, version)
        val top = HashMap<String, Node>()
        while (parser.p < bytes.size - 32) {
            val n = try { parser.node(0) } catch (e: IndexOutOfBoundsException) { throw FormatException("FBX 文件不完整") } ?: break
            top[n.name] = n
        }
        val objects = top["Objects"] ?: throw FormatException("FBX 里没有对象数据")
        val connections = top["Connections"]

        // 全局设置
        val gs = Props(top["GlobalSettings"])
        val upAxis = gs.num("UpAxis", 1.0).toInt()
        val unit = gs.num("UnitScaleFactor", 1.0)

        val geometries = HashMap<Long, Node>()
        val models = HashMap<Long, Node>()
        val materials = HashMap<Long, Node>()
        val textures = HashMap<Long, Node>()
        val videos = HashMap<Long, Node>()
        var hasSkin = false
        for (o in objects.children) {
            val id = o.long(0)
            when (o.name) {
                "Geometry" -> if (typeName(o) == "Mesh") geometries[id] = o
                "Model" -> models[id] = o
                "Material" -> materials[id] = o
                "Texture" -> textures[id] = o
                "Video" -> videos[id] = o
                "Deformer" -> hasSkin = true
            }
        }
        // 连接关系
        val parentOf = HashMap<Long, Long>()
        val geomOfModel = HashMap<Long, ArrayList<Long>>()
        val matsOfModel = HashMap<Long, ArrayList<Long>>()
        val texOfMaterial = HashMap<Long, HashMap<String, Long>>()
        val videoOfTexture = HashMap<Long, Long>()
        for (cn in connections?.children ?: emptyList<Node>()) {
            if (cn.name != "C") continue
            val kind = cn.str(0)
            val child = cn.long(1)
            val parent = cn.long(2)
            if (kind == "OO") {
                when {
                    models.containsKey(child) && (models.containsKey(parent) || parent == 0L) -> parentOf[child] = parent
                    geometries.containsKey(child) && models.containsKey(parent) -> geomOfModel.getOrPut(parent) { ArrayList() }.add(child)
                    materials.containsKey(child) && models.containsKey(parent) -> matsOfModel.getOrPut(parent) { ArrayList() }.add(child)
                    videos.containsKey(child) && textures.containsKey(parent) -> videoOfTexture[parent] = child
                    textures.containsKey(child) && materials.containsKey(parent) -> texOfMaterial.getOrPut(parent) { HashMap() }.putIfAbsent("DiffuseColor", child)
                }
            } else if (kind == "OP") {
                if (textures.containsKey(child) && materials.containsKey(parent)) texOfMaterial.getOrPut(parent) { HashMap() }[cn.str(3)] = child
            }
        }

        val b = MeshBuilder(ModelFormat.FBX)
        if (upAxis == 2) b.setBasisRightHandedZUp() else b.setBasisRightHandedYUp()
        b.flipV = true

        // 材质
        val materialIndex = HashMap<Long, Int>()
        var defaultMaterial = -1
        fun textureOf(texId: Long): Int {
            val t = textures[texId] ?: return -1
            val vid = videos[videoOfTexture[texId] ?: -1L]
            val content = vid?.child("Content")?.props?.getOrNull(0) as? ByteArray
            val path = (t.child("RelativeFilename")?.str(0)?.takeIf { it.isNotBlank() } ?: t.child("FileName")?.str(0)
                ?: vid?.child("RelativeFilename")?.str(0) ?: vid?.child("Filename")?.str(0) ?: "")
            if (content != null && content.size > 16) return b.embeddedTexture(path.ifBlank { objName(t) }, content)
            return b.texture(path)
        }
        fun materialOf(id: Long): Int = materialIndex.getOrPut(id) {
            val src = materials[id]
            val m = b.newMaterial(if (src != null) objName(src).removePrefix("Material::") else "材质")
            if (src != null) {
                val pr = Props(src)
                val v = DoubleArray(3)
                if (pr.vec3("DiffuseColor", 0.8, 0.8, 0.8, v)) { m.diffuse[0] = v[0].toFloat(); m.diffuse[1] = v[1].toFloat(); m.diffuse[2] = v[2].toFloat() }
                val df = pr.num("DiffuseFactor", 1.0).toFloat()
                if (df in 0.01f..1f) { m.diffuse[0] *= df; m.diffuse[1] *= df; m.diffuse[2] *= df }
                if (pr.vec3("SpecularColor", 0.0, 0.0, 0.0, v)) {
                    val sf = pr.num("SpecularFactor", 1.0).toFloat().coerceIn(0f, 1f)
                    m.specular[0] = v[0].toFloat() * sf; m.specular[1] = v[1].toFloat() * sf; m.specular[2] = v[2].toFloat() * sf
                }
                m.shininess = pr.num("ShininessExponent", pr.num("Shininess", 0.0)).toFloat().coerceIn(0f, 400f)
                if (m.specular[0] + m.specular[1] + m.specular[2] < 0.01f || m.shininess < 1f) m.shininess = 0f
                if (pr.vec3("EmissiveColor", 0.0, 0.0, 0.0, v)) {
                    val ef = pr.num("EmissiveFactor", 1.0).toFloat().coerceIn(0f, 4f)
                    m.emissive[0] = v[0].toFloat() * ef; m.emissive[1] = v[1].toFloat() * ef; m.emissive[2] = v[2].toFloat() * ef
                }
                var opacity = pr.num("Opacity", -1.0).toFloat()
                if (opacity < 0f) {
                    val tf = pr.num("TransparencyFactor", 0.0).toFloat()
                    opacity = if (pr.has("TransparentColor") && tf in 0f..1f) 1f - tf else 1f
                    // 许多导出器把 TransparencyFactor 写成 1 表示"不透明"
                    if (opacity <= 0f) opacity = 1f
                }
                m.diffuse[3] = opacity.coerceIn(0f, 1f)
                if (m.diffuse[3] < 0.999f) m.alphaMode = MaterialData.ALPHA_BLEND
                val tex = texOfMaterial[id]
                if (tex != null) {
                    val diff = tex["DiffuseColor"] ?: tex["Maya|baseColor"] ?: tex["base_color"]
                    if (diff != null) {
                        m.texture = textureOf(diff)
                        if (m.texture >= 0 && m.diffuse[0] + m.diffuse[1] + m.diffuse[2] < 0.05f) { m.diffuse[0] = 1f; m.diffuse[1] = 1f; m.diffuse[2] = 1f }
                    }
                    val em = tex["EmissiveColor"]
                    if (em != null && (m.emissive[0] + m.emissive[1] + m.emissive[2]) > 0f) {
                        val ti = textureOf(em)
                        if (ti >= 0) { m.sphereTexture = ti; m.sphereMode = MaterialData.SPHERE_ADD }
                    }
                }
            }
            b.addMaterial(m)
        }

        // 节点的世界矩阵（带缓存，按父链递归）
        val worldCache = HashMap<Long, FloatArray>()
        fun world(id: Long, depth: Int): FloatArray {
            worldCache[id]?.let { return it }
            val local = localMatrix(Props(models[id]))
            val parent = parentOf[id] ?: 0L
            val w = FloatArray(16)
            if (parent != 0L && models.containsKey(parent) && depth < 256) Mat4.mulAffine(w, 0, world(parent, depth + 1), 0, local, 0)
            else System.arraycopy(local, 0, w, 0, 16)
            worldCache[id] = w
            return w
        }

        var skippedPolys = 0
        for ((modelId, geomIds) in geomOfModel) {
            val modelNode = models[modelId] ?: continue
            val pr = Props(modelNode)
            val w = FloatArray(16)
            Mat4.mulAffine(w, 0, world(modelId, 0), 0, geometricMatrix(pr), 0)
            val det = w[0] * (w[5] * w[10] - w[6] * w[9]) - w[4] * (w[1] * w[10] - w[2] * w[9]) + w[8] * (w[1] * w[6] - w[2] * w[5])
            val mirrored = det < 0f
            val mats = matsOfModel[modelId] ?: ArrayList()
            for (gid in geomIds) {
                val g = geometries[gid] ?: continue
                skippedPolys += addGeometry(b, g, w, mirrored, mats, ::materialOf) {
                    if (defaultMaterial < 0) defaultMaterial = b.addMaterial(b.newMaterial("默认材质").also { it.alphaMode = MaterialData.ALPHA_OPAQUE })
                    defaultMaterial
                }
            }
        }
        if (b.vertexCount == 0) throw FormatException("FBX 里没有可显示的网格")
        if (hasSkin) b.warnings.add("该 FBX 带有骨骼蒙皮 / 动画，目前按静止姿势导入为静态模型；需要动画请改用 glTF(.glb)")
        if (skippedPolys > 0) b.warnings.add("有 $skippedPolys 个面的数据异常，已跳过")
        // UnitScaleFactor：1 个单位 = 多少厘米
        val scale = (unit / 100.0 * GltfReader.UNIT_SCALE).toFloat()
        val model = b.build(if (scale > 0f && scale == scale) scale else GltfReader.UNIT_SCALE / 100f)
        model.suggestedScale = MeshBuilder.suggestScale(model, 1f)
        model.sanitize()
        return model
    }

    /** XYZ 欧拉角（度）→ 矩阵：R = Rz · Ry · Rx。 */
    private fun eulerMatrix(v: DoubleArray, order: Int, out: FloatArray) {
        val k = Math.PI / 180.0
        val cx = cos(v[0] * k).toFloat(); val sx = sin(v[0] * k).toFloat()
        val cy = cos(v[1] * k).toFloat(); val sy = sin(v[1] * k).toFloat()
        val cz = cos(v[2] * k).toFloat(); val sz = sin(v[2] * k).toFloat()
        val rx = FloatArray(16); val ry = FloatArray(16); val rz = FloatArray(16)
        Mat4.identity(rx, 0); Mat4.identity(ry, 0); Mat4.identity(rz, 0)
        rx[5] = cx; rx[6] = sx; rx[9] = -sx; rx[10] = cx
        ry[0] = cy; ry[2] = -sy; ry[8] = sy; ry[10] = cy
        rz[0] = cz; rz[1] = sz; rz[4] = -sz; rz[5] = cz
        // RotationOrder: 0 XYZ, 1 XZY, 2 YZX, 3 YXZ, 4 ZXY, 5 ZYX —— "XYZ" 表示先绕 X、最后绕 Z
        val seq = when (order) {
            1 -> arrayOf(rx, rz, ry)
            2 -> arrayOf(ry, rz, rx)
            3 -> arrayOf(ry, rx, rz)
            4 -> arrayOf(rz, rx, ry)
            5 -> arrayOf(rz, ry, rx)
            else -> arrayOf(rx, ry, rz)
        }
        val t = FloatArray(16)
        Mat4.mulAffine(t, 0, seq[1], 0, seq[0], 0)
        Mat4.mulAffine(out, 0, seq[2], 0, t, 0)
    }

    private fun localMatrix(pr: Props): FloatArray {
        val v = DoubleArray(3)
        val m = FloatArray(16)
        val t = FloatArray(16)
        val r = FloatArray(16)
        Mat4.identity(m, 0)
        fun mulT(x: Double, y: Double, z: Double) { Mat4.setTranslation(t, 0, x.toFloat(), y.toFloat(), z.toFloat()); Mat4.mulAffine(m, 0, m, 0, t, 0) }
        val order = pr.num("RotationOrder", 0.0).toInt()
        // World = T · Roff · Rp · Rpre · R · Rpost⁻¹ · Rp⁻¹ · Soff · Sp · S · Sp⁻¹
        pr.vec3("Lcl Translation", 0.0, 0.0, 0.0, v); mulT(v[0], v[1], v[2])
        if (pr.vec3("RotationOffset", 0.0, 0.0, 0.0, v)) mulT(v[0], v[1], v[2])
        val rp = DoubleArray(3)
        val hasRp = pr.vec3("RotationPivot", 0.0, 0.0, 0.0, rp)
        if (hasRp) mulT(rp[0], rp[1], rp[2])
        if (pr.vec3("PreRotation", 0.0, 0.0, 0.0, v)) { eulerMatrix(v, 0, r); Mat4.mulAffine(m, 0, m, 0, r, 0) }
        if (pr.vec3("Lcl Rotation", 0.0, 0.0, 0.0, v)) { eulerMatrix(v, order, r); Mat4.mulAffine(m, 0, m, 0, r, 0) }
        if (pr.vec3("PostRotation", 0.0, 0.0, 0.0, v)) { eulerMatrix(v, 0, r); Mat4.invertRigid(r, 0, r, 0); Mat4.mulAffine(m, 0, m, 0, r, 0) }
        if (hasRp) mulT(-rp[0], -rp[1], -rp[2])
        if (pr.vec3("ScalingOffset", 0.0, 0.0, 0.0, v)) mulT(v[0], v[1], v[2])
        val sp = DoubleArray(3)
        val hasSp = pr.vec3("ScalingPivot", 0.0, 0.0, 0.0, sp)
        if (hasSp) mulT(sp[0], sp[1], sp[2])
        if (pr.vec3("Lcl Scaling", 1.0, 1.0, 1.0, v)) { Mat4.setScale(t, 0, v[0].toFloat(), v[1].toFloat(), v[2].toFloat()); Mat4.mulAffine(m, 0, m, 0, t, 0) }
        if (hasSp) mulT(-sp[0], -sp[1], -sp[2])
        return m
    }

    private fun geometricMatrix(pr: Props): FloatArray {
        val v = DoubleArray(3)
        val m = FloatArray(16)
        val t = FloatArray(16)
        Mat4.identity(m, 0)
        if (pr.vec3("GeometricTranslation", 0.0, 0.0, 0.0, v)) { Mat4.setTranslation(t, 0, v[0].toFloat(), v[1].toFloat(), v[2].toFloat()); Mat4.mulAffine(m, 0, m, 0, t, 0) }
        if (pr.vec3("GeometricRotation", 0.0, 0.0, 0.0, v)) { eulerMatrix(v, 0, t); Mat4.mulAffine(m, 0, m, 0, t, 0) }
        if (pr.vec3("GeometricScaling", 1.0, 1.0, 1.0, v)) { Mat4.setScale(t, 0, v[0].toFloat(), v[1].toFloat(), v[2].toFloat()); Mat4.mulAffine(m, 0, m, 0, t, 0) }
        return m
    }

    /** 一个图层元素（法线 / UV / 顶点色）：数据 + 索引 + 映射方式。 */
    private class Layer(node: Node?, dataName: String, indexName: String, val comps: Int) {
        val data: DoubleArray? = node?.child(dataName)?.props?.getOrNull(0) as? DoubleArray
        val index: IntArray? = node?.child(indexName)?.props?.getOrNull(0) as? IntArray
        val mapping: String = node?.child("MappingInformationType")?.str(0) ?: ""
        val indexed: Boolean = (node?.child("ReferenceInformationType")?.str(0) ?: "").let { it == "IndexToDirect" || it == "Index" } && index != null
        val valid: Boolean = data != null && data.isNotEmpty()

        /** 取第 polyVertex 个"多边形顶点"（控制点 controlPoint、所属多边形 polygon）对应的数据下标；-1 = 没有。 */
        fun resolve(polyVertex: Int, controlPoint: Int, polygon: Int): Int {
            if (!valid) return -1
            var i = when (mapping) {
                "ByPolygonVertex" -> polyVertex
                "ByVertice", "ByVertex", "ByControlPoint" -> controlPoint
                "ByPolygon" -> polygon
                "AllSame" -> 0
                else -> return -1
            }
            if (indexed) { val ix = index!!; if (i < 0 || i >= ix.size) return -1; i = ix[i] }
            return if (i >= 0 && (i + 1) * comps <= data!!.size) i else -1
        }
    }

    private fun addGeometry(
        b: MeshBuilder, g: Node, w: FloatArray, mirrored: Boolean, mats: List<Long>,
        materialOf: (Long) -> Int, defaultMaterial: () -> Int
    ): Int {
        val verts = g.child("Vertices")?.props?.getOrNull(0) as? DoubleArray ?: return 0
        val pvi = g.child("PolygonVertexIndex")?.props?.getOrNull(0) as? IntArray ?: return 0
        val cpCount = verts.size / 3
        val normals = Layer(g.child("LayerElementNormal"), "Normals", "NormalsIndex", 3)
        val uvs = Layer(g.child("LayerElementUV"), "UV", "UVIndex", 2)
        val colors = Layer(g.child("LayerElementColor"), "Colors", "ColorIndex", 4)
        val matNode = g.child("LayerElementMaterial")
        val matIdx = matNode?.child("Materials")?.props?.getOrNull(0) as? IntArray
        val matAllSame = (matNode?.child("MappingInformationType")?.str(0) ?: "AllSame") == "AllSame"
        if (colors.valid) b.enableColors()

        val slotMaterial = IntArray(maxOf(mats.size, 1)) { -1 }
        fun materialForSlot(slot: Int): Int {
            if (slot < 0 || slot >= mats.size) return defaultMaterial()
            if (slotMaterial[slot] < 0) slotMaterial[slot] = materialOf(mats[slot])
            return slotMaterial[slot]
        }

        val map = LongIntMap(maxOf(1024, pvi.size))
        val corner = IntArray(256)
        var polygon = 0
        var n = 0
        var skipped = 0
        var bad = false
        val nd = normals.data; val ud = uvs.data; val cd = colors.data
        for (pv in pvi.indices) {
            var cp = pvi[pv]
            val last = cp < 0
            if (last) cp = cp.inv()
            if (cp < 0 || cp >= cpCount) bad = true
            if (!bad && n < corner.size) {
                val ni = normals.resolve(pv, cp, polygon)
                val ui = uvs.resolve(pv, cp, polygon)
                val ci = colors.resolve(pv, cp, polygon)
                var nx = 0f; var ny = 0f; var nz = 0f
                if (ni >= 0) { nx = nd!![ni * 3].toFloat(); ny = nd[ni * 3 + 1].toFloat(); nz = nd[ni * 3 + 2].toFloat() }
                // 去重键：控制点 + UV 下标 + 量化后的法线 + 颜色下标，混合成 64 位哈希
                val qn = (((nx * 255f).toInt() and 0x1FF) shl 18) or (((ny * 255f).toInt() and 0x1FF) shl 9) or ((nz * 255f).toInt() and 0x1FF)
                val key = MeshBuilder.hashKey(cp, ui, qn, ci)
                var idx = map.get(key)
                if (idx < 0) {
                    val x = verts[cp * 3].toFloat(); val y = verts[cp * 3 + 1].toFloat(); val z = verts[cp * 3 + 2].toFloat()
                    val px = w[0] * x + w[4] * y + w[8] * z + w[12]
                    val py = w[1] * x + w[5] * y + w[9] * z + w[13]
                    val pz = w[2] * x + w[6] * y + w[10] * z + w[14]
                    var tx = w[0] * nx + w[4] * ny + w[8] * nz
                    var ty = w[1] * nx + w[5] * ny + w[9] * nz
                    var tz = w[2] * nx + w[6] * ny + w[10] * nz
                    val l = sqrt(tx * tx + ty * ty + tz * tz)
                    if (l > 1e-12f) { tx /= l; ty /= l; tz /= l }
                    idx = b.addVertex(px, py, pz, tx, ty, tz, if (ui >= 0) ud!![ui * 2].toFloat() else 0f, if (ui >= 0) ud!![ui * 2 + 1].toFloat() else 0f)
                    if (ci >= 0) b.setColor(idx, cd!![ci * 4].toFloat(), cd[ci * 4 + 1].toFloat(), cd[ci * 4 + 2].toFloat(), cd[ci * 4 + 3].toFloat())
                    map.put(key, idx)
                }
                corner[n] = idx
            }
            n++
            if (last) {
                if (bad || n < 3) {
                    if (bad) skipped++
                } else {
                    val slot = if (matIdx == null || matIdx.isEmpty()) 0 else if (matAllSame) matIdx[0] else matIdx.getOrElse(polygon) { 0 }
                    val material = materialForSlot(slot)
                    val cnt = minOf(n, corner.size)
                    for (k in 1 until cnt - 1) {
                        if (mirrored) b.addTriangle(material, corner[0], corner[k + 1], corner[k]) else b.addTriangle(material, corner[0], corner[k], corner[k + 1])
                    }
                }
                polygon++
                n = 0
                bad = false
            }
        }
        return skipped
    }
}

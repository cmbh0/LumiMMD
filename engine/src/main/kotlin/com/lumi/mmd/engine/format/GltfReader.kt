package com.lumi.mmd.engine.format

import com.lumi.mmd.engine.anim.BoneTrack
import com.lumi.mmd.engine.anim.MorphTrack
import com.lumi.mmd.engine.anim.Motion
import com.lumi.mmd.engine.math.Mat4
import com.lumi.mmd.engine.math.Quat
import com.lumi.mmd.engine.model.BoneData
import com.lumi.mmd.engine.model.LightData
import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.ModelFormat
import com.lumi.mmd.engine.model.MorphData
import com.lumi.mmd.engine.render.TextureResolver
import com.lumi.mmd.engine.util.Json
import com.lumi.mmd.engine.util.arr
import com.lumi.mmd.engine.util.bool
import com.lumi.mmd.engine.util.float
import com.lumi.mmd.engine.util.floats
import com.lumi.mmd.engine.util.int
import com.lumi.mmd.engine.util.jArr
import com.lumi.mmd.engine.util.jFloat
import com.lumi.mmd.engine.util.jInt
import com.lumi.mmd.engine.util.jObj
import com.lumi.mmd.engine.util.jStr
import com.lumi.mmd.engine.util.obj
import com.lumi.mmd.engine.util.str
import java.io.File
import java.net.URLDecoder
import com.lumi.mmd.engine.util.Base64Lite
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * glTF 2.0（.glb / .gltf）导入——Blender 导出模型、场景的首选格式。
 *
 * 两种导入方式：
 *  - 没有骨架也没有动画的场景：把所有节点的网格烘焙到世界空间，得到一个静态模型（渲染最快）；
 *  - 带蒙皮 / 节点动画 / 形态键的模型：每个节点转成一根骨骼。glTF 的骨骼带"静止旋转"，而 MMD 的骨骼
 *    静止时没有旋转，所以这里把网格烘焙到绑定姿势、把动画通道用"父节点静止朝向"做共轭变换，
 *    换算成 MMD 式的骨骼动作并按 30fps 重采样。这样 glTF 模型就能直接进入同一套骨骼 / 表情 / 渲染管线，
 *    骨骼命名符合 MMD 标准的模型还可以直接套用 VMD 动作。
 *
 * 另外会读取 KHR_lights_punctual 的灯光、KHR_materials_emissive_strength、KHR_materials_unlit。
 * 坐标：glTF 是右手系、单位米 → 翻转 Z 并乘以 12.5 得到 MMD 的左手系与单位。
 */
object GltfReader {
    const val UNIT_SCALE = 12.5f

    private class Ctx(val root: Map<String, Any?>, val buffers: Array<ByteArray?>, val baseDir: File?) {
        val accessors = root.arr("accessors") ?: emptyList()
        val views = root.arr("bufferViews") ?: emptyList()
        val nodes = root.arr("nodes") ?: emptyList()
        val meshes = root.arr("meshes") ?: emptyList()
        val materials = root.arr("materials") ?: emptyList()
        val textures = root.arr("textures") ?: emptyList()
        val images = root.arr("images") ?: emptyList()
        val skins = root.arr("skins") ?: emptyList()
        val animations = root.arr("animations") ?: emptyList()
    }

    fun isGlb(bytes: ByteArray): Boolean = bytes.size >= 12 && bytes[0] == 'g'.code.toByte() && bytes[1] == 'l'.code.toByte() &&
        bytes[2] == 'T'.code.toByte() && bytes[3] == 'F'.code.toByte()

    private fun i32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    fun read(bytes: ByteArray, file: File?): ModelData {
        val baseDir = file?.parentFile
        var jsonText: String
        var glbBin: ByteArray? = null
        if (isGlb(bytes)) {
            val version = i32(bytes, 4)
            if (version != 2) throw FormatException("只支持 glTF 2.0（该文件是 $version.0）")
            var p = 12
            var json: String? = null
            while (p + 8 <= bytes.size) {
                val len = i32(bytes, p)
                val type = i32(bytes, p + 4)
                p += 8
                if (len < 0 || p + len > bytes.size) throw FormatException("GLB 文件不完整")
                if (type == 0x4E4F534A && json == null) json = String(bytes, p, len, Charsets.UTF_8)
                else if (type == 0x004E4942 && glbBin == null) glbBin = bytes.copyOfRange(p, p + len)
                p += (len + 3) and 3.inv()
            }
            jsonText = json ?: throw FormatException("GLB 里没有 JSON 数据块")
        } else {
            jsonText = String(bytes, Charsets.UTF_8)
        }
        val root = try { Json.parse(jsonText).jObj() } catch (e: Json.ParseException) { throw FormatException("glTF 的 JSON 解析失败：${e.message}") }
            ?: throw FormatException("glTF 内容无效")
        val version = root.obj("asset")?.str("version") ?: "2.0"
        if (!version.startsWith("2")) throw FormatException("只支持 glTF 2.0（该文件是 $version）")
        for (ext in root.arr("extensionsRequired") ?: emptyList()) {
            when (ext.jStr()) {
                "KHR_draco_mesh_compression" -> throw FormatException("该 glTF 使用了 Draco 网格压缩，暂不支持。请在 Blender 导出时关闭「压缩」选项")
                "EXT_meshopt_compression" -> throw FormatException("该 glTF 使用了 meshopt 压缩，暂不支持")
            }
        }

        // 缓冲区
        val bufs = root.arr("buffers") ?: emptyList()
        val buffers = arrayOfNulls<ByteArray>(bufs.size)
        for ((i, b) in bufs.withIndex()) {
            val uri = b.jObj()?.str("uri")
            buffers[i] = when {
                uri == null -> if (i == 0) glbBin else null
                uri.startsWith("data:") -> decodeDataUri(uri)
                else -> resolveFile(baseDir, uri)?.readBytes() ?: throw FormatException("找不到 glTF 引用的数据文件：$uri")
            }
        }
        val c = Ctx(root, buffers, baseDir)
        val importer = Importer(c)
        val model = importer.run()
        model.suggestedScale = MeshBuilder.suggestScale(model, 1f)
        model.sanitize()
        return model
    }

    private fun decodeDataUri(uri: String): ByteArray? {
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        return try { Base64Lite.decode(uri, comma + 1) } catch (_: Exception) { null }
    }

    private fun resolveFile(baseDir: File?, uri: String): File? {
        val decoded = try { URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { uri }
        return TextureResolver.resolve(baseDir, decoded) ?: TextureResolver.resolve(baseDir, uri)
    }

    // ------------------------------------------------------------------ 访问器

    private fun componentSize(type: Int) = when (type) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> throw FormatException("glTF 访问器的分量类型未知：$type") }
    private fun componentCount(type: String?) = when (type) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT2" -> 4; "MAT3" -> 9; "MAT4" -> 16; else -> throw FormatException("glTF 访问器类型未知：$type") }

    private fun readRaw(c: Ctx, viewIndex: Int, byteOffset: Int, count: Int, comps: Int, compType: Int, normalized: Boolean, out: FloatArray, asInt: IntArray?) {
        val view = c.views.getOrNull(viewIndex).jObj() ?: throw FormatException("glTF bufferView 索引越界")
        val buf = c.buffers.getOrNull(view.int("buffer", 0)) ?: throw FormatException("glTF 缓冲区数据缺失")
        val size = componentSize(compType)
        val stride = view.int("byteStride", 0).let { if (it > 0) it else size * comps }
        val start = view.int("byteOffset", 0) + byteOffset
        if (count > 0 && start.toLong() + (count - 1).toLong() * stride + size * comps > buf.size) throw FormatException("glTF 访问器越界")
        for (i in 0 until count) {
            var p = start + i * stride
            for (k in 0 until comps) {
                val o = i * comps + k
                when (compType) {
                    5126 -> { val f = Float.fromBits(i32(buf, p)); if (asInt != null) asInt[o] = f.toInt() else out[o] = f }
                    5125 -> { val v = i32(buf, p); if (asInt != null) asInt[o] = v else out[o] = (v.toLong() and 0xFFFFFFFFL).toFloat() }
                    5123 -> { val v = (buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8); if (asInt != null) asInt[o] = v else out[o] = if (normalized) v / 65535f else v.toFloat() }
                    5122 -> { val v = ((buf[p].toInt() and 0xFF) or (buf[p + 1].toInt() shl 8)).toShort().toInt(); if (asInt != null) asInt[o] = v else out[o] = if (normalized) maxOf(v / 32767f, -1f) else v.toFloat() }
                    5121 -> { val v = buf[p].toInt() and 0xFF; if (asInt != null) asInt[o] = v else out[o] = if (normalized) v / 255f else v.toFloat() }
                    else -> { val v = buf[p].toInt(); if (asInt != null) asInt[o] = v else out[o] = if (normalized) maxOf(v / 127f, -1f) else v.toFloat() }
                }
                p += size
            }
        }
    }

    private fun accessorCount(c: Ctx, index: Int): Int = c.accessors.getOrNull(index).jObj()?.int("count", 0) ?: 0

    private fun readFloats(c: Ctx, index: Int, expectComps: Int = -1): FloatArray? {
        val a = c.accessors.getOrNull(index).jObj() ?: return null
        val count = a.int("count", 0)
        val comps = componentCount(a.str("type"))
        if (expectComps > 0 && comps != expectComps) return null
        if (count < 0 || count.toLong() * comps > 200_000_000L) throw FormatException("glTF 访问器元素数异常")
        val out = FloatArray(count * comps)
        val compType = a.int("componentType", 5126)
        val normalized = a.bool("normalized")
        if (a.containsKey("bufferView")) readRaw(c, a.int("bufferView"), a.int("byteOffset", 0), count, comps, compType, normalized, out, null)
        val sparse = a.obj("sparse")
        if (sparse != null) {
            val n = sparse.int("count", 0)
            val si = sparse.obj("indices"); val sv = sparse.obj("values")
            if (n > 0 && si != null && sv != null) {
                val idx = IntArray(n)
                readRaw(c, si.int("bufferView"), si.int("byteOffset", 0), n, 1, si.int("componentType", 5125), false, FloatArray(0), idx)
                val vals = FloatArray(n * comps)
                readRaw(c, sv.int("bufferView"), sv.int("byteOffset", 0), n, comps, compType, normalized, vals, null)
                for (i in 0 until n) if (idx[i] in 0 until count) System.arraycopy(vals, i * comps, out, idx[i] * comps, comps)
            }
        }
        return out
    }

    private fun readInts(c: Ctx, index: Int): IntArray? {
        val a = c.accessors.getOrNull(index).jObj() ?: return null
        val count = a.int("count", 0)
        val comps = componentCount(a.str("type"))
        if (count < 0 || count.toLong() * comps > 400_000_000L) throw FormatException("glTF 访问器元素数异常")
        val out = IntArray(count * comps)
        if (a.containsKey("bufferView")) readRaw(c, a.int("bufferView"), a.int("byteOffset", 0), count, comps, a.int("componentType", 5125), false, FloatArray(0), out)
        return out
    }

    // ------------------------------------------------------------------ 导入

    private class Importer(val c: Ctx) {
        val nodeCount = c.nodes.size
        val parent = IntArray(nodeCount) { -1 }
        /** 节点的静止局部 TRS：t(3) q(4) s(3)。 */
        val restT = FloatArray(nodeCount * 3)
        val restQ = FloatArray(nodeCount * 4)
        val restS = FloatArray(nodeCount * 3)
        val localM = FloatArray(nodeCount * 16)
        val globalM = FloatArray(nodeCount * 16)
        /** 节点静止姿势的全局旋转。 */
        val globalQ = FloatArray(nodeCount * 4)
        /** 节点的全局均匀缩放（近似）。 */
        val globalScale = FloatArray(nodeCount) { 1f }
        val reachable = BooleanArray(nodeCount)
        val order = ArrayList<Int>()
        val b = MeshBuilder(ModelFormat.GLTF)
        val materialMap = HashMap<Int, Int>()
        var defaultMaterial = -1
        val tmp = FloatArray(64)
        val morphByName = LinkedHashMap<String, MorphAcc>()

        class MorphAcc(val name: String) {
            val indices = com.lumi.mmd.engine.util.IntList(256)
            val deltas = com.lumi.mmd.engine.util.FloatList(768)
        }

        fun run(): ModelData {
            setupNodes()
            val rigged = c.skins.isNotEmpty() || c.animations.isNotEmpty() || hasMorphTargets()
            b.setBasisRightHandedYUp()
            if (rigged) b.enableSkin()
            for (n in order) {
                val node = c.nodes[n].jObj() ?: continue
                val meshIndex = node.int("mesh")
                if (meshIndex < 0) continue
                val mesh = c.meshes.getOrNull(meshIndex).jObj() ?: continue
                addMesh(n, node, mesh, rigged)
            }
            if (b.vertexCount == 0) throw FormatException("glTF 里没有可显示的网格")
            val model = b.build(UNIT_SCALE)
            if (rigged) {
                buildBones(model)
                buildMorphs(model)
                buildAnimations(model)
                MmdRig.addStandardIk(model)
            }
            readLights(model)
            return model
        }

        private fun hasMorphTargets(): Boolean {
            for (m in c.meshes) for (p in m.jObj()?.arr("primitives") ?: emptyList()) if ((p.jObj()?.arr("targets")?.size ?: 0) > 0) return true
            return false
        }

        private fun setupNodes() {
            for (i in 0 until nodeCount) {
                val node = c.nodes[i].jObj() ?: continue
                for (ch in node.arr("children") ?: emptyList()) {
                    val ci = ch.jInt()
                    if (ci in 0 until nodeCount && ci != i) parent[ci] = i
                }
                val m = node.floats("matrix", 16)
                val o = i * 16
                if (m != null) {
                    System.arraycopy(m, 0, localM, o, 16)
                    // 分解成 TRS（忽略剪切）
                    restT[i * 3] = m[12]; restT[i * 3 + 1] = m[13]; restT[i * 3 + 2] = m[14]
                    var sx = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
                    val sy = sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6])
                    val sz = sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])
                    val det = m[0] * (m[5] * m[10] - m[6] * m[9]) - m[4] * (m[1] * m[10] - m[2] * m[9]) + m[8] * (m[1] * m[6] - m[2] * m[5])
                    if (det < 0f) sx = -sx
                    restS[i * 3] = sx; restS[i * 3 + 1] = sy; restS[i * 3 + 2] = sz
                    val r = tmp
                    Mat4.identity(r, 0)
                    if (sx != 0f) { r[0] = m[0] / sx; r[1] = m[1] / sx; r[2] = m[2] / sx }
                    if (sy != 0f) { r[4] = m[4] / sy; r[5] = m[5] / sy; r[6] = m[6] / sy }
                    if (sz != 0f) { r[8] = m[8] / sz; r[9] = m[9] / sz; r[10] = m[10] / sz }
                    Mat4.toQuat(r, 0, restQ, i * 4)
                } else {
                    val t = node.floats("translation", 3)
                    val q = node.floats("rotation", 4)
                    val s = node.floats("scale", 3)
                    if (t != null) System.arraycopy(t, 0, restT, i * 3, 3)
                    if (q != null) { System.arraycopy(q, 0, restQ, i * 4, 4); Quat.normalize(restQ, i * 4) } else Quat.identity(restQ, i * 4)
                    if (s != null) System.arraycopy(s, 0, restS, i * 3, 3) else { restS[i * 3] = 1f; restS[i * 3 + 1] = 1f; restS[i * 3 + 2] = 1f }
                    Mat4.setTRS(localM, o, restT[i * 3], restT[i * 3 + 1], restT[i * 3 + 2], restQ[i * 4], restQ[i * 4 + 1], restQ[i * 4 + 2], restQ[i * 4 + 3],
                        restS[i * 3], restS[i * 3 + 1], restS[i * 3 + 2])
                }
            }
            // 默认场景的根节点
            val scenes = c.root.arr("scenes") ?: emptyList()
            val sceneIndex = c.root.int("scene", 0)
            val roots = ArrayList<Int>()
            val sceneNodes = scenes.getOrNull(sceneIndex).jObj()?.arr("nodes") ?: scenes.firstOrNull().jObj()?.arr("nodes")
            if (sceneNodes != null) for (n in sceneNodes) { val i = n.jInt(); if (i in 0 until nodeCount) roots.add(i) }
            if (roots.isEmpty()) for (i in 0 until nodeCount) if (parent[i] < 0) roots.add(i)
            for (r in roots) parent[r] = -1
            // 深度优先（父在前）
            val stack = ArrayList<Int>(roots.reversed())
            while (stack.isNotEmpty()) {
                val n = stack.removeAt(stack.size - 1)
                if (reachable[n]) continue
                reachable[n] = true
                order.add(n)
                val p = parent[n]
                if (p >= 0) {
                    Mat4.mulAffine(globalM, n * 16, globalM, p * 16, localM, n * 16)
                    Quat.mul(globalQ, n * 4, globalQ, p * 4, restQ, n * 4)
                    Quat.normalize(globalQ, n * 4)
                } else {
                    System.arraycopy(localM, n * 16, globalM, n * 16, 16)
                    Quat.copy(globalQ, n * 4, restQ, n * 4)
                }
                val g = n * 16
                val det = globalM[g] * (globalM[g + 5] * globalM[g + 10] - globalM[g + 6] * globalM[g + 9]) -
                    globalM[g + 4] * (globalM[g + 1] * globalM[g + 10] - globalM[g + 2] * globalM[g + 9]) +
                    globalM[g + 8] * (globalM[g + 1] * globalM[g + 6] - globalM[g + 2] * globalM[g + 5])
                globalScale[n] = Math.abs(det.toDouble()).pow(1.0 / 3.0).toFloat()
                val children = c.nodes[n].jObj()?.arr("children") ?: emptyList()
                for (k in children.indices.reversed()) { val ci = children[k].jInt(); if (ci in 0 until nodeCount && parent[ci] == n) stack.add(ci) }
            }
        }

        // -------------------------------------------------------------- 材质

        private fun textureIndex(info: Map<String, Any?>?): Int {
            if (info == null) return -1
            val tex = c.textures.getOrNull(info.int("index")).jObj() ?: return -1
            var src = tex.int("source")
            // 常见的图片扩展（WebP / basisu）
            if (src < 0) src = tex.obj("extensions")?.obj("EXT_texture_webp")?.int("source") ?: -1
            val img = c.images.getOrNull(src).jObj() ?: return -1
            val uri = img.str("uri")
            val name = img.str("name") ?: uri ?: "image$src"
            if (uri != null) {
                if (uri.startsWith("data:")) {
                    val bytes = decodeDataUri(uri) ?: return -1
                    return b.embeddedTexture(name, bytes)
                }
                val decoded = try { URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { uri }
                return b.texture(decoded)
            }
            val view = c.views.getOrNull(img.int("bufferView")).jObj() ?: return -1
            val buf = c.buffers.getOrNull(view.int("buffer", 0)) ?: return -1
            val start = view.int("byteOffset", 0)
            val len = view.int("byteLength", 0)
            if (start < 0 || len <= 0 || start + len > buf.size) return -1
            val key = "embedded:$src"
            return embedded.getOrPut(key) { b.embeddedTexture(name, buf.copyOfRange(start, start + len)) }
        }

        private val embedded = HashMap<String, Int>()

        private fun materialFor(index: Int, hasVertexColor: Boolean): Int {
            if (index < 0 || index >= c.materials.size) {
                if (defaultMaterial < 0) defaultMaterial = b.addMaterial(b.newMaterial("默认材质").also { it.alphaMode = MaterialData.ALPHA_OPAQUE })
                return defaultMaterial
            }
            val key = index * 2 + (if (hasVertexColor) 1 else 0)
            materialMap[key]?.let { return it }
            val src = c.materials[index].jObj() ?: emptyMap()
            val m = b.newMaterial(src.str("name") ?: "材质${index + 1}")
            val pbr = src.obj("pbrMetallicRoughness")
            val base = pbr?.floats("baseColorFactor", 4)
            if (base != null) System.arraycopy(base, 0, m.diffuse, 0, 4) else m.diffuse.fill(1f)
            m.texture = textureIndex(pbr?.obj("baseColorTexture"))
            val metallic = pbr?.float("metallicFactor", 1f) ?: 1f
            val roughness = (pbr?.float("roughnessFactor", 1f) ?: 1f).coerceIn(0.05f, 1f)
            // PBR → Blinn-Phong 的粗略换算：粗糙度决定高光的集中程度与强度
            val gloss = 1f - roughness
            m.shininess = if (gloss > 0.02f) (2f / (roughness * roughness * roughness * roughness) - 2f).coerceIn(4f, 400f) else 0f
            val specBase = 0.04f + 0.5f * gloss * gloss
            for (k in 0 until 3) m.specular[k] = (specBase * (1f - metallic) + m.diffuse[k] * metallic * (0.25f + 0.75f * gloss)).coerceIn(0f, 1f)
            // 没有环境反射时纯金属会发黑，适当保留一部分漫反射
            if (metallic > 0.5f) for (k in 0 until 3) m.diffuse[k] *= 1f - 0.45f * metallic
            val em = src.floats("emissiveFactor", 3)
            var strength = 1f
            src.obj("extensions")?.obj("KHR_materials_emissive_strength")?.let { strength = it.float("emissiveStrength", 1f) }
            if (em != null) for (k in 0 until 3) m.emissive[k] = (em[k] * strength).coerceIn(0f, 4f)
            val emTex = textureIndex(src.obj("emissiveTexture"))
            if (emTex >= 0 && (m.emissive[0] > 0f || m.emissive[1] > 0f || m.emissive[2] > 0f)) { m.sphereTexture = emTex; m.sphereMode = MaterialData.SPHERE_ADD }
            when (src.str("alphaMode")) {
                "BLEND" -> m.alphaMode = MaterialData.ALPHA_BLEND
                "MASK" -> { m.alphaMode = MaterialData.ALPHA_MASK; m.alphaCutoff = src.float("alphaCutoff", 0.5f) }
                else -> m.alphaMode = MaterialData.ALPHA_OPAQUE
            }
            if (src.bool("doubleSided")) m.flags = m.flags or MaterialData.FLAG_DOUBLE_SIDED
            if (src.obj("extensions")?.containsKey("KHR_materials_unlit") == true) m.shading = MaterialData.SHADING_UNLIT
            if (hasVertexColor) m.flags = m.flags or MaterialData.FLAG_VERTEX_COLOR
            val mi = b.addMaterial(m)
            materialMap[key] = mi
            return mi
        }

        // -------------------------------------------------------------- 网格

        private fun addMesh(nodeIndex: Int, node: Map<String, Any?>, mesh: Map<String, Any?>, rigged: Boolean) {
            val skinIndex = node.int("skin")
            val skin = if (rigged) c.skins.getOrNull(skinIndex).jObj() else null
            val joints = skin?.arr("joints")?.map { it.jInt() }?.toIntArray()
            var jointMats: FloatArray? = null
            if (joints != null) {
                val ibm = skin.int("inverseBindMatrices").let { if (it >= 0) readFloats(c, it, 16) else null }
                jointMats = FloatArray(joints.size * 16)
                for ((j, jn) in joints.withIndex()) {
                    if (jn !in 0 until nodeCount) { Mat4.identity(jointMats, j * 16); continue }
                    if (ibm != null && ibm.size >= (j + 1) * 16) Mat4.mulAffine(jointMats, j * 16, globalM, jn * 16, ibm, j * 16)
                    else System.arraycopy(globalM, jn * 16, jointMats, j * 16, 16)
                }
            }
            val targetNames = mesh.obj("extras")?.arr("targetNames")?.map { it.jStr() ?: "" }
            val meshName = mesh.str("name") ?: node.str("name") ?: "mesh"
            val gm = globalM
            val go = nodeIndex * 16
            val mirrored = run {
                val det = gm[go] * (gm[go + 5] * gm[go + 10] - gm[go + 6] * gm[go + 9]) - gm[go + 4] * (gm[go + 1] * gm[go + 10] - gm[go + 2] * gm[go + 9]) +
                    gm[go + 8] * (gm[go + 1] * gm[go + 6] - gm[go + 2] * gm[go + 5])
                det < 0f && joints == null
            }
            for (prim in mesh.arr("primitives") ?: emptyList()) {
                val p = prim.jObj() ?: continue
                val mode = p.int("mode", 4)
                if (mode != 4 && mode != 5 && mode != 6) continue // 点、线不导入
                val attrs = p.obj("attributes") ?: continue
                val pos = readFloats(c, attrs.int("POSITION"), 3) ?: continue
                val count = pos.size / 3
                if (count == 0) continue
                val nrm = attrs.int("NORMAL").let { if (it >= 0) readFloats(c, it, 3) else null }?.takeIf { it.size == pos.size }
                val uv = attrs.int("TEXCOORD_0").let { if (it >= 0) readFloats(c, it, 2) else null }?.takeIf { it.size == count * 2 }
                val colIndex = attrs.int("COLOR_0")
                var col: FloatArray? = null
                var colComps = 4
                if (colIndex >= 0) {
                    colComps = componentCount(c.accessors.getOrNull(colIndex).jObj()?.str("type"))
                    col = readFloats(c, colIndex)?.takeIf { it.size == count * colComps }
                }
                val jIdx = if (joints != null && attrs.int("JOINTS_0") >= 0) readInts(c, attrs.int("JOINTS_0"))?.takeIf { it.size == count * 4 } else null
                val jW = if (jIdx != null) readFloats(c, attrs.int("WEIGHTS_0"), 4)?.takeIf { it.size == count * 4 } else null
                val material = materialFor(p.int("material"), col != null)
                if (col != null) b.enableColors()

                val base = b.vertexCount
                val m = tmp
                // 每个顶点实际使用的线性变换（形态键的位移也要用它变换）
                val targets = p.arr("targets")
                val linear = if (targets != null && targets.isNotEmpty()) FloatArray(count * 9) else null
                for (v in 0 until count) {
                    val o = v * 3
                    val x = pos[o]; val y = pos[o + 1]; val z = pos[o + 2]
                    var nx = 0f; var ny = 0f; var nz = 0f
                    if (nrm != null) { nx = nrm[o]; ny = nrm[o + 1]; nz = nrm[o + 2] }
                    var b0 = nodeIndex; var b1 = 0; var b2 = 0; var b3 = 0
                    var w0 = 1f; var w1 = 0f; var w2 = 0f; var w3 = 0f
                    if (jIdx != null && jW != null && jointMats != null && joints != null) {
                        // 蒙皮网格：用绑定姿势的混合矩阵烘焙
                        val o4 = v * 4
                        var sum = jW[o4] + jW[o4 + 1] + jW[o4 + 2] + jW[o4 + 3]
                        if (sum <= 1e-6f) sum = 1f
                        for (k in 0 until 16) m[k] = 0f
                        for (k in 0 until 4) {
                            val w = jW[o4 + k] / sum
                            if (w == 0f) continue
                            val j = jIdx[o4 + k]
                            if (j !in joints.indices) continue
                            for (e in 0 until 16) m[e] += jointMats[j * 16 + e] * w
                        }
                        fun jn(k: Int): Int { val j = jIdx[o4 + k]; return if (j in joints.indices && joints[j] in 0 until nodeCount) joints[j] else 0 }
                        b0 = jn(0); b1 = jn(1); b2 = jn(2); b3 = jn(3)
                        w0 = jW[o4] / sum; w1 = jW[o4 + 1] / sum; w2 = jW[o4 + 2] / sum; w3 = jW[o4 + 3] / sum
                        if (w0 + w1 + w2 + w3 <= 1e-6f) { w0 = 1f }
                    } else {
                        System.arraycopy(gm, go, m, 0, 16)
                    }
                    val px = m[0] * x + m[4] * y + m[8] * z + m[12]
                    val py = m[1] * x + m[5] * y + m[9] * z + m[13]
                    val pz = m[2] * x + m[6] * y + m[10] * z + m[14]
                    var tx = m[0] * nx + m[4] * ny + m[8] * nz
                    var ty = m[1] * nx + m[5] * ny + m[9] * nz
                    var tz = m[2] * nx + m[6] * ny + m[10] * nz
                    val l = sqrt(tx * tx + ty * ty + tz * tz)
                    if (l > 1e-12f) { tx /= l; ty /= l; tz /= l }
                    val idx = b.addVertex(px, py, pz, tx, ty, tz, if (uv != null) uv[v * 2] else 0f, if (uv != null) uv[v * 2 + 1] else 0f)
                    if (col != null) {
                        val co = v * colComps
                        b.setColor(idx, col[co], col[co + 1], col[co + 2], if (colComps >= 4) col[co + 3] else 1f)
                    }
                    if (rigged) b.setSkin(idx, b0, b1, b2, b3, w0, w1, w2, w3)
                    if (linear != null) {
                        val lo = v * 9
                        linear[lo] = m[0]; linear[lo + 1] = m[1]; linear[lo + 2] = m[2]
                        linear[lo + 3] = m[4]; linear[lo + 4] = m[5]; linear[lo + 5] = m[6]
                        linear[lo + 6] = m[8]; linear[lo + 7] = m[9]; linear[lo + 8] = m[10]
                    }
                }

                // 三角形
                val indices = p.int("indices").let { if (it >= 0) readInts(c, it) else null }
                val n = indices?.size ?: count
                fun vi(i: Int): Int { val v = if (indices != null) indices[i] else i; return if (v in 0 until count) base + v else -1 }
                fun tri(a: Int, bb: Int, cc: Int) {
                    if (a < 0 || bb < 0 || cc < 0) return
                    if (mirrored) b.addTriangle(material, a, cc, bb) else b.addTriangle(material, a, bb, cc)
                }
                when (mode) {
                    4 -> { var i = 0; while (i + 2 < n) { tri(vi(i), vi(i + 1), vi(i + 2)); i += 3 } }
                    5 -> for (i in 0 until n - 2) { if (i and 1 == 0) tri(vi(i), vi(i + 1), vi(i + 2)) else tri(vi(i + 1), vi(i), vi(i + 2)) }
                    else -> for (i in 1 until n - 1) tri(vi(0), vi(i), vi(i + 1))
                }

                // 形态键 → 顶点表情
                if (targets != null && linear != null) {
                    for ((ti, t) in targets.withIndex()) {
                        val d = t.jObj()?.int("POSITION")?.let { if (it >= 0) readFloats(c, it, 3) else null } ?: continue
                        if (d.size != pos.size) continue
                        val name = targetNames?.getOrNull(ti)?.takeIf { it.isNotBlank() } ?: "$meshName.$ti"
                        val acc = morphByName.getOrPut(name) { MorphAcc(name) }
                        for (v in 0 until count) {
                            val o = v * 3
                            val dx = d[o]; val dy = d[o + 1]; val dz = d[o + 2]
                            if (dx == 0f && dy == 0f && dz == 0f) continue
                            val lo = v * 9
                            acc.indices.add(base + v)
                            acc.deltas.add(
                                linear[lo] * dx + linear[lo + 3] * dy + linear[lo + 6] * dz,
                                linear[lo + 1] * dx + linear[lo + 4] * dy + linear[lo + 7] * dz,
                                linear[lo + 2] * dx + linear[lo + 5] * dy + linear[lo + 8] * dz)
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------- 骨骼 / 表情 / 动画

        /** 节点 → 骨骼。骨骼序号就是节点序号（没用到的节点也占位，保证蒙皮索引不用重排）。 */
        private fun buildBones(model: ModelData) {
            val used = HashSet<String>()
            for (i in 0 until nodeCount) {
                val node = c.nodes[i].jObj()
                val bone = BoneData()
                var name = node?.str("name")?.takeIf { it.isNotBlank() } ?: "node$i"
                if (!used.add(name)) { name = "${name}_$i"; used.add(name) }
                bone.name = name
                bone.nameEn = name
                bone.parent = if (reachable[i]) parent[i] else -1
                val g = i * 16
                // 右手 → 左手：Z 取反；米 → MMD 单位
                bone.position[0] = globalM[g + 12] * UNIT_SCALE
                bone.position[1] = globalM[g + 13] * UNIT_SCALE
                bone.position[2] = -globalM[g + 14] * UNIT_SCALE
                bone.flags = BoneData.FLAG_ROTATABLE or BoneData.FLAG_TRANSLATABLE or BoneData.FLAG_VISIBLE or BoneData.FLAG_OPERABLE
                model.bones.add(bone)
            }
            for (i in 0 until nodeCount) {
                val children = c.nodes[i].jObj()?.arr("children") ?: continue
                val first = children.firstOrNull().jInt()
                if (first in 0 until nodeCount && parent[first] == i) {
                    model.bones[i].tailBone = first
                    model.bones[i].flags = model.bones[i].flags or BoneData.FLAG_TAIL_IS_BONE
                }
            }
            // 带蒙皮的网格节点本身不是骨骼：按 glTF 规范，蒙皮网格的顶点只跟着关节走，节点自己的变换不起作用。
            // 这种节点仍然占一个骨骼序号（保持序号 = 节点序号），但不在骨骼列表里显示，免得出现一堆摆了也没反应的"骨骼"。
            val isJoint = BooleanArray(nodeCount)
            c.root.arr("skins")?.forEach { skin -> skin.jObj()?.arr("joints")?.forEach { j -> val n = j.jInt(); if (n in 0 until nodeCount) isJoint[n] = true } }
            for (i in 0 until nodeCount) {
                val node = c.nodes[i].jObj() ?: continue
                if (isJoint[i] || node.int("mesh") < 0 || node.int("skin") < 0) continue
                if (!node.arr("children").isNullOrEmpty()) continue
                model.bones[i].flags = 0
            }
        }

        private fun buildMorphs(model: ModelData) {
            for (acc in morphByName.values) {
                val m = MorphData()
                m.name = acc.name
                m.nameEn = acc.name
                m.panel = 4
                m.type = MorphData.TYPE_VERTEX
                m.count = acc.indices.size
                m.indices = acc.indices.toArray()
                val d = acc.deltas.toArray()
                var i = 0
                while (i < d.size) { d[i] *= UNIT_SCALE; d[i + 1] *= UNIT_SCALE; d[i + 2] = -d[i + 2] * UNIT_SCALE; i += 3 }
                m.data = d
                model.morphs.add(m)
            }
        }

        private class Sampler(val times: FloatArray, val values: FloatArray, val comps: Int, val interp: Int) {
            private var cursor = 0

            fun sample(time: Float, out: FloatArray, quat: Boolean) {
                val n = times.size
                val stride = if (interp == 2) comps * 3 else comps
                val vo = if (interp == 2) comps else 0
                if (n == 0) return
                if (time <= times[0] || n == 1) { System.arraycopy(values, vo, out, 0, comps); return }
                if (time >= times[n - 1]) { System.arraycopy(values, (n - 1) * stride + vo, out, 0, comps); return }
                if (cursor >= n - 1 || times[cursor] > time) cursor = 0
                while (cursor < n - 2 && times[cursor + 1] <= time) cursor++
                val i = cursor
                val t0 = times[i]; val t1 = times[i + 1]
                val dt = t1 - t0
                val u = if (dt > 0f) (time - t0) / dt else 0f
                val a = i * stride + vo
                val bb = (i + 1) * stride + vo
                when (interp) {
                    1 -> System.arraycopy(values, a, out, 0, comps)
                    2 -> {
                        val u2 = u * u; val u3 = u2 * u
                        val h00 = 2 * u3 - 3 * u2 + 1; val h10 = u3 - 2 * u2 + u; val h01 = -2 * u3 + 3 * u2; val h11 = u3 - u2
                        for (k in 0 until comps) {
                            val outTan = values[i * stride + 2 * comps + k]
                            val inTan = values[(i + 1) * stride + k]
                            out[k] = h00 * values[a + k] + h10 * dt * outTan + h01 * values[bb + k] + h11 * dt * inTan
                        }
                        if (quat) Quat.normalize(out, 0)
                    }
                    else -> if (quat) Quat.slerp(out, 0, values, a, values, bb, u) else for (k in 0 until comps) out[k] = values[a + k] + (values[bb + k] - values[a + k]) * u
                }
            }
        }

        private fun buildAnimations(model: ModelData) {
            val morphNames = morphByName.keys.toList()
            for ((ai, anim) in c.animations.withIndex()) {
                val a = anim.jObj() ?: continue
                val samplers = a.arr("samplers") ?: continue
                val channels = a.arr("channels") ?: continue
                val loaded = HashMap<Int, Sampler?>()
                fun sampler(index: Int, comps: Int): Sampler? = loaded.getOrPut(index) {
                    val s = samplers.getOrNull(index).jObj() ?: return@getOrPut null
                    val times = readFloats(c, s.int("input"), 1) ?: return@getOrPut null
                    val values = readFloats(c, s.int("output")) ?: return@getOrPut null
                    val interp = when (s.str("interpolation")) { "STEP" -> 1; "CUBICSPLINE" -> 2; else -> 0 }
                    val need = times.size * comps * (if (interp == 2) 3 else 1)
                    if (times.isEmpty() || values.size < need) null else Sampler(times, values, comps, interp)
                }
                // 每个节点的 平移 / 旋转 通道
                val tOf = HashMap<Int, Sampler>()
                val rOf = HashMap<Int, Sampler>()
                val wOf = ArrayList<Pair<Int, Sampler>>()
                var duration = 0f
                var hasScale = false
                for (ch in channels) {
                    val co = ch.jObj() ?: continue
                    val target = co.obj("target") ?: continue
                    val node = target.int("node")
                    if (node !in 0 until nodeCount) continue
                    when (target.str("path")) {
                        "translation" -> sampler(co.int("sampler"), 3)?.let { tOf[node] = it; duration = maxOf(duration, it.times.last()) }
                        "rotation" -> sampler(co.int("sampler"), 4)?.let { rOf[node] = it; duration = maxOf(duration, it.times.last()) }
                        "scale" -> hasScale = true
                        "weights" -> {
                            val mesh = c.meshes.getOrNull(c.nodes[node].jObj()?.int("mesh") ?: -1).jObj() ?: continue
                            val count = mesh.arr("primitives")?.firstOrNull().jObj()?.arr("targets")?.size ?: 0
                            if (count > 0) sampler(co.int("sampler"), count)?.let { wOf.add(node to it); duration = maxOf(duration, it.times.last()) }
                        }
                    }
                }
                if (tOf.isEmpty() && rOf.isEmpty() && wOf.isEmpty()) continue
                val frames = minOf((duration * Motion.FPS + 0.999f).toInt(), 30 * 60 * 30)
                val motion = Motion()
                motion.name = a.str("name")?.takeIf { it.isNotBlank() } ?: "动画${ai + 1}"
                val v = FloatArray(16)
                val q = FloatArray(16)
                for (node in (tOf.keys + rOf.keys).toSortedSet()) {
                    if (!reachable[node]) continue
                    val ts = tOf[node]
                    val rs = rOf[node]
                    val track = BoneTrack(model.bones[node].name, frames + 1)
                    val p = parent[node]
                    // 父节点静止姿势的全局旋转（根节点为单位四元数）与全局缩放
                    if (p >= 0) Quat.copy(q, 8, globalQ, p * 4) else Quat.identity(q, 8)
                    Quat.conjugate(q, 12, q, 8)
                    val ps = if (p >= 0) globalScale[p] else 1f
                    Quat.conjugate(q, 4, restQ, node * 4)
                    for (f in 0..frames) {
                        val time = f / Motion.FPS
                        track.frames[f] = f
                        val o = f * 7
                        // 平移：R_p静止 · S_p · (t - t静止)
                        if (ts != null) {
                            ts.sample(time, v, false)
                            Quat.rotate(q, 8, (v[0] - restT[node * 3]) * ps, (v[1] - restT[node * 3 + 1]) * ps, (v[2] - restT[node * 3 + 2]) * ps, v, 4)
                            track.values[o] = v[4] * UNIT_SCALE; track.values[o + 1] = v[5] * UNIT_SCALE; track.values[o + 2] = -v[6] * UNIT_SCALE
                        }
                        // 旋转：R_p静止 · (q · q静止⁻¹) · R_p静止⁻¹，再做右手 → 左手的镜像
                        if (rs != null) {
                            rs.sample(time, q, true)
                            Quat.mul(q, 0, q, 0, q, 4)
                            Quat.mul(q, 0, q, 8, q, 0)
                            Quat.mul(q, 0, q, 0, q, 12)
                            Quat.normalize(q, 0)
                            track.values[o + 3] = -q[0]; track.values[o + 4] = -q[1]; track.values[o + 5] = q[2]; track.values[o + 6] = q[3]
                        } else {
                            track.values[o + 6] = 1f
                        }
                        // 线性补间
                        val io = f * 16
                        for (k in 0 until 4) { track.interp[io + k * 4] = 20; track.interp[io + k * 4 + 1] = 20; track.interp[io + k * 4 + 2] = 107; track.interp[io + k * 4 + 3] = 107 }
                    }
                    motion.boneTracks[track.name] = track
                }
                for ((node, s) in wOf) {
                    val mesh = c.meshes.getOrNull(c.nodes[node].jObj()?.int("mesh") ?: -1).jObj() ?: continue
                    val names = mesh.obj("extras")?.arr("targetNames")?.map { it.jStr() ?: "" }
                    val meshName = mesh.str("name") ?: c.nodes[node].jObj()?.str("name") ?: "mesh"
                    val w = FloatArray(s.comps)
                    val tracks = Array(s.comps) { k -> MorphTrack(names?.getOrNull(k)?.takeIf { it.isNotBlank() } ?: "$meshName.$k", frames + 1) }
                    for (f in 0..frames) {
                        s.sample(f / Motion.FPS, w, false)
                        for (k in 0 until s.comps) { tracks[k].frames[f] = f; tracks[k].weights[f] = w[k] }
                    }
                    for (t in tracks) if (t.name in morphNames) motion.morphTracks[t.name] = t
                }
                motion.recomputeMaxFrame()
                if (hasScale) model.warnings.add("动画「${motion.name}」包含缩放关键帧，缩放动画暂不支持（已忽略）")
                if (motion.hasModelMotion) model.animations.add(motion)
            }
        }

        // -------------------------------------------------------------- 灯光

        private fun readLights(model: ModelData) {
            val defs = c.root.obj("extensions")?.obj("KHR_lights_punctual")?.arr("lights") ?: return
            for (n in order) {
                val node = c.nodes[n].jObj() ?: continue
                val li = node.obj("extensions")?.obj("KHR_lights_punctual")?.int("light") ?: -1
                val def = defs.getOrNull(li).jObj() ?: continue
                val light = LightData()
                light.name = def.str("name") ?: node.str("name") ?: "light"
                light.type = when (def.str("type")) { "directional" -> 0; "point" -> 1; else -> 2 }
                def.floats("color", 3)?.let { System.arraycopy(it, 0, light.color, 0, 3) }
                light.intensity = def.float("intensity", 1f)
                // 灯光沿节点的 -Z 方向照射
                val g = n * 16
                var dx = -globalM[g + 8]; var dy = -globalM[g + 9]; var dz = -globalM[g + 10]
                val l = sqrt(dx * dx + dy * dy + dz * dz)
                if (l > 1e-9f) { dx /= l; dy /= l; dz /= l }
                light.direction[0] = dx; light.direction[1] = dy; light.direction[2] = -dz
                light.position[0] = globalM[g + 12] * UNIT_SCALE; light.position[1] = globalM[g + 13] * UNIT_SCALE; light.position[2] = -globalM[g + 14] * UNIT_SCALE
                model.lights.add(light)
            }
        }
    }
}

/** 给"骨骼命名符合 MMD 标准、但没有 IK 数据"的模型（例如经 Blender 转成 glTF 的 MMD 模型）补上标准的足 IK。 */
object MmdRig {
    fun addStandardIk(model: ModelData) {
        if (model.bones.any { it.ik != null }) return
        val index = HashMap<String, Int>()
        for ((i, b) in model.bones.withIndex()) index.putIfAbsent(b.name, i)
        fun find(vararg names: String): Int { for (n in names) index[n]?.let { return it }; return -1 }
        for (side in arrayOf("左", "右")) {
            val ik = find("${side}足ＩＫ", "${side}足IK")
            val ankle = find("${side}足首")
            val knee = find("${side}ひざ")
            val leg = find("${side}足")
            if (ik >= 0 && ankle >= 0 && knee >= 0 && leg >= 0) {
                val d = com.lumi.mmd.engine.model.IkData()
                d.target = ankle; d.loopCount = 40; d.limitAngle = 2f
                d.links.add(com.lumi.mmd.engine.model.IkLink().also {
                    it.bone = knee; it.hasLimit = true
                    it.lower[0] = -Math.PI.toFloat(); it.upper[0] = -0.5f * (Math.PI.toFloat() / 180f)
                })
                d.links.add(com.lumi.mmd.engine.model.IkLink().also { it.bone = leg })
                model.bones[ik].ik = d
                model.bones[ik].flags = model.bones[ik].flags or BoneData.FLAG_IK
            }
            val toeIk = find("${side}つま先ＩＫ", "${side}つま先IK")
            val toe = find("${side}つま先")
            if (toeIk >= 0 && toe >= 0 && ankle >= 0) {
                val d = com.lumi.mmd.engine.model.IkData()
                d.target = toe; d.loopCount = 3; d.limitAngle = 4f
                d.links.add(com.lumi.mmd.engine.model.IkLink().also { it.bone = ankle })
                model.bones[toeIk].ik = d
                model.bones[toeIk].flags = model.bones[toeIk].flags or BoneData.FLAG_IK
            }
        }
    }
}

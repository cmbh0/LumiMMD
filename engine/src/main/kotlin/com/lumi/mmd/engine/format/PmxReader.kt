package com.lumi.mmd.engine.format

import com.lumi.mmd.engine.model.BoneData
import com.lumi.mmd.engine.model.DisplayFrame
import com.lumi.mmd.engine.model.IkData
import com.lumi.mmd.engine.model.IkLink
import com.lumi.mmd.engine.model.JointData
import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.ModelFormat
import com.lumi.mmd.engine.model.MorphData
import com.lumi.mmd.engine.model.RigidBodyData
import com.lumi.mmd.engine.model.TextureRef
import com.lumi.mmd.engine.util.BinaryReader
import com.lumi.mmd.engine.util.TextCodec
import java.nio.charset.Charset

class FormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * PMX 2.0 / 2.1 解析器。
 *
 * 按 PMX 规格（PMXエディタ付属「PMX仕様.txt」）逐段读取：
 * 头 → 顶点 → 面 → 贴图 → 材质 → 骨骼 → 表情(モーフ) → 表示枠 → 刚体 → Joint（2.1 的 SoftBody 忽略）。
 * 顶点直接写进 SoA 数组，不为每个顶点创建对象。
 */
object PmxReader {

    fun isPmx(bytes: ByteArray): Boolean =
        bytes.size > 9 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'M'.code.toByte() &&
            bytes[2] == 'X'.code.toByte() && bytes[3] == ' '.code.toByte()

    private class Ctx(val r: BinaryReader, val cs: Charset, val addUv: Int, val vSize: Int, val tSize: Int, val mSize: Int, val bSize: Int, val moSize: Int, val rSize: Int) {
        fun text(): String {
            val len = r.i32()
            if (len <= 0) return ""
            if (len > r.remaining) throw FormatException("PMX 文本长度异常：$len")
            return r.string(len, cs)
        }

        /** 顶点索引：1/2 字节为无符号。 */
        fun vertexIndex(): Int = when (vSize) {
            1 -> r.u8()
            2 -> r.u16()
            else -> r.i32()
        }

        /** 其它索引：有符号，-1 表示"无"。 */
        fun index(size: Int): Int = when (size) {
            1 -> r.i8()
            2 -> r.i16()
            else -> r.i32()
        }

        fun vec3(dst: FloatArray, off: Int = 0) = r.floats(dst, off, 3)
        fun vec4(dst: FloatArray, off: Int = 0) = r.floats(dst, off, 4)
    }

    fun read(bytes: ByteArray): ModelData {
        if (!isPmx(bytes)) throw FormatException("不是 PMX 文件（文件头应为 \"PMX \"）")
        val r = BinaryReader(bytes)
        r.skip(4)
        val version = r.f32()
        val globalsCount = r.u8()
        if (globalsCount < 8) throw FormatException("PMX 头部信息不完整")
        val g = IntArray(globalsCount)
        for (i in 0 until globalsCount) g[i] = r.u8()
        val cs = if (g[0] == 1) TextCodec.UTF8 else TextCodec.UTF16LE
        val c = Ctx(r, cs, g[1], g[2], g[3], g[4], g[5], g[6], g[7])
        for (s in intArrayOf(c.vSize, c.tSize, c.mSize, c.bSize, c.moSize, c.rSize)) {
            if (s != 1 && s != 2 && s != 4) throw FormatException("PMX 索引宽度异常：$s")
        }
        if (c.addUv !in 0..4) throw FormatException("PMX 追加 UV 数异常：${c.addUv}")

        val model = ModelData()
        model.format = ModelFormat.PMX
        model.name = c.text()
        model.nameEn = c.text()
        model.comment = c.text()
        model.commentEn = c.text()

        readVertices(c, model)
        readIndices(c, model)
        readTextures(c, model)
        readMaterials(c, model)
        readBones(c, model)

        // 后面的段落即使损坏也不影响把模型画出来：出错就停止并记一条警告
        try {
            readMorphs(c, model)
            readDisplayFrames(c, model)
            readRigidBodies(c, model)
            readJoints(c, model)
        } catch (e: Exception) {
            model.warnings.add("PMX 后半段（表情/刚体/关节）数据不完整：${e.message}")
        }
        if (version >= 2.1f && r.hasRemaining(4)) {
            val softCount = try { r.i32() } catch (_: Exception) { 0 }
            if (softCount > 0) model.warnings.add("模型包含 $softCount 个 SoftBody（PMX 2.1），当前版本不模拟软体")
        }
        model.sanitize()
        return model
    }

    private fun readVertices(c: Ctx, model: ModelData) {
        val r = c.r
        val n = r.i32()
        if (n < 0 || n.toLong() * 38 > r.remaining.toLong() + 64) throw FormatException("PMX 顶点数异常：$n")
        val pos = FloatArray(n * 3)
        val nrm = FloatArray(n * 3)
        val uv = FloatArray(n * 2)
        val bi = ShortArray(n * 4)
        val bw = FloatArray(n * 4)
        val edge = FloatArray(n)
        val types = ByteArray(n)
        var sdefC: FloatArray? = null
        var sdefR0: FloatArray? = null
        var sdefR1: FloatArray? = null
        val skipUv = 16 * c.addUv
        val bSize = c.bSize
        val tmp = FloatArray(9)
        // 本应用导出的 PMX 把顶点色放在追加 UV1 里（说明里有记号）
        val colors = if (c.addUv >= 1 && model.comment.contains(PmxWriter.VCOLOR_TAG)) FloatArray(n * 4) else null
        for (v in 0 until n) {
            r.floats(pos, v * 3, 3)
            r.floats(nrm, v * 3, 3)
            r.floats(uv, v * 2, 2)
            if (colors != null) { r.floats(colors, v * 4, 4); if (skipUv > 16) r.skip(skipUv - 16) }
            else if (skipUv > 0) r.skip(skipUv)
            val type = r.u8()
            types[v] = type.toByte()
            val o = v * 4
            when (type) {
                0 -> {
                    val b0 = c.index(bSize)
                    if (b0 >= 0) { bi[o] = b0.toShort(); bw[o] = 1f }
                }
                1 -> {
                    val b0 = c.index(bSize)
                    val b1 = c.index(bSize)
                    val w = r.f32()
                    if (b0 >= 0) { bi[o] = b0.toShort(); bw[o] = w }
                    if (b1 >= 0) { bi[o + 1] = b1.toShort(); bw[o + 1] = 1f - w }
                }
                2, 4 -> {
                    val b0 = c.index(bSize); val b1 = c.index(bSize); val b2 = c.index(bSize); val b3 = c.index(bSize)
                    val w0 = r.f32(); val w1 = r.f32(); val w2 = r.f32(); val w3 = r.f32()
                    if (b0 >= 0) { bi[o] = b0.toShort(); bw[o] = w0 }
                    if (b1 >= 0) { bi[o + 1] = b1.toShort(); bw[o + 1] = w1 }
                    if (b2 >= 0) { bi[o + 2] = b2.toShort(); bw[o + 2] = w2 }
                    if (b3 >= 0) { bi[o + 3] = b3.toShort(); bw[o + 3] = w3 }
                }
                3 -> {
                    val b0 = c.index(bSize)
                    val b1 = c.index(bSize)
                    val w0 = r.f32()
                    r.floats(tmp, 0, 9)
                    if (b0 >= 0) { bi[o] = b0.toShort(); bw[o] = w0 }
                    if (b1 >= 0) { bi[o + 1] = b1.toShort(); bw[o + 1] = 1f - w0 }
                    if (b0 < 0 || b1 < 0) {
                        types[v] = 1
                    } else {
                        if (sdefC == null) {
                            sdefC = FloatArray(n * 3); sdefR0 = FloatArray(n * 3); sdefR1 = FloatArray(n * 3)
                        }
                        // 预处理成运行时直接可用的形式（同 mmd_tools / saba 的 SDEF 推导）
                        val w1 = 1f - w0
                        val p = v * 3
                        for (k in 0 until 3) {
                            val cc = tmp[k]
                            val r0 = tmp[3 + k]
                            val r1 = tmp[6 + k]
                            val rw = r0 * w0 + r1 * w1
                            val rr0 = cc + r0 - rw
                            val rr1 = cc + r1 - rw
                            sdefC!![p + k] = cc
                            sdefR0!![p + k] = (cc + rr0) * 0.5f
                            sdefR1!![p + k] = (cc + rr1) * 0.5f
                        }
                    }
                }
                else -> throw FormatException("PMX 顶点 $v 的变形方式未知：$type")
            }
            edge[v] = r.f32()
        }
        model.vertexCount = n
        model.positions = pos
        model.normals = nrm
        model.uvs = uv
        if (colors != null) { model.colors = colors; model.comment = model.comment.replace(PmxWriter.VCOLOR_TAG, "").trimEnd() }
        model.boneIndices = bi
        model.boneWeights = bw
        model.edgeScales = edge
        model.skinTypes = types
        model.sdefC = sdefC
        model.sdefR0 = sdefR0
        model.sdefR1 = sdefR1
    }

    private fun readIndices(c: Ctx, model: ModelData) {
        val r = c.r
        val n = r.i32()
        if (n < 0 || n.toLong() * c.vSize > r.remaining) throw FormatException("PMX 面索引数异常：$n")
        val idx = IntArray(n)
        when (c.vSize) {
            1 -> for (i in 0 until n) idx[i] = r.u8()
            2 -> for (i in 0 until n) idx[i] = r.u16()
            else -> for (i in 0 until n) idx[i] = r.i32()
        }
        model.indices = idx
    }

    private fun readTextures(c: Ctx, model: ModelData) {
        val n = c.r.i32()
        if (n < 0 || n > 100000) throw FormatException("PMX 贴图数异常：$n")
        for (i in 0 until n) model.textures.add(TextureRef(TextCodec.normalizePath(c.text())))
    }

    private fun readMaterials(c: Ctx, model: ModelData) {
        val r = c.r
        val n = r.i32()
        if (n < 0 || n > 100000) throw FormatException("PMX 材质数异常：$n")
        var offset = 0
        for (i in 0 until n) {
            val m = MaterialData()
            m.name = c.text()
            m.nameEn = c.text()
            c.vec4(m.diffuse)
            c.vec3(m.specular)
            m.shininess = r.f32()
            c.vec3(m.ambient)
            m.flags = r.u8()
            c.vec4(m.edgeColor)
            m.edgeSize = r.f32()
            m.texture = c.index(c.tSize)
            m.sphereTexture = c.index(c.tSize)
            m.sphereMode = r.u8()
            m.sharedToon = r.u8() != 0
            m.toonIndex = if (m.sharedToon) r.u8() else c.index(c.tSize)
            if (m.sharedToon && m.toonIndex !in 0..9) m.toonIndex = -1
            m.memo = c.text()
            m.indexCount = r.i32()
            m.indexOffset = offset
            offset += maxOf(0, m.indexCount)
            m.shading = MaterialData.SHADING_MMD
            m.alphaMode = MaterialData.ALPHA_BLEND
            if (m.memo.contains("[lumi ")) PmxWriter.applyMemoTag(m)
            model.materials.add(m)
        }
    }

    private fun readBones(c: Ctx, model: ModelData) {
        val r = c.r
        val n = r.i32()
        if (n < 0 || n > 65535) throw FormatException("PMX 骨骼数异常：$n")
        for (i in 0 until n) {
            val b = BoneData()
            b.name = c.text()
            b.nameEn = c.text()
            c.vec3(b.position)
            b.parent = c.index(c.bSize)
            b.level = r.i32()
            b.flags = r.u16()
            if ((b.flags and BoneData.FLAG_TAIL_IS_BONE) != 0) {
                b.tailBone = c.index(c.bSize)
            } else {
                c.vec3(b.tailOffset)
            }
            if ((b.flags and (BoneData.FLAG_APPEND_ROTATE or BoneData.FLAG_APPEND_TRANSLATE)) != 0) {
                b.appendParent = c.index(c.bSize)
                b.appendWeight = r.f32()
            }
            if ((b.flags and BoneData.FLAG_FIXED_AXIS) != 0) c.vec3(b.fixedAxis)
            if ((b.flags and BoneData.FLAG_LOCAL_AXIS) != 0) {
                c.vec3(b.localX)
                c.vec3(b.localZ)
            }
            if ((b.flags and BoneData.FLAG_EXTERNAL_PARENT) != 0) b.externalKey = r.i32()
            if ((b.flags and BoneData.FLAG_IK) != 0) {
                val ik = IkData()
                ik.target = c.index(c.bSize)
                ik.loopCount = r.i32()
                ik.limitAngle = r.f32()
                val linkCount = r.i32()
                if (linkCount < 0 || linkCount > 1024) throw FormatException("PMX IK 链长度异常：$linkCount")
                for (k in 0 until linkCount) {
                    val link = IkLink()
                    link.bone = c.index(c.bSize)
                    link.hasLimit = r.u8() != 0
                    if (link.hasLimit) {
                        c.vec3(link.lower)
                        c.vec3(link.upper)
                    }
                    ik.links.add(link)
                }
                b.ik = ik
            }
            model.bones.add(b)
        }
    }

    private fun readMorphs(c: Ctx, model: ModelData) {
        val r = c.r
        val n = r.i32()
        if (n < 0 || n > 100000) throw FormatException("PMX 表情数异常：$n")
        for (i in 0 until n) {
            val m = MorphData()
            m.name = c.text()
            m.nameEn = c.text()
            m.panel = r.u8()
            m.type = r.u8()
            val count = r.i32()
            if (count < 0 || count > r.remaining) throw FormatException("PMX 表情 ${m.name} 偏移数异常：$count")
            m.count = count
            val stride = m.stride
            if (stride == 0) throw FormatException("PMX 表情 ${m.name} 类型未知：${m.type}")
            val idx = IntArray(count)
            val data = FloatArray(count * stride)
            when (m.type) {
                MorphData.TYPE_GROUP, MorphData.TYPE_FLIP -> for (k in 0 until count) {
                    idx[k] = c.index(c.moSize)
                    data[k] = r.f32()
                }
                MorphData.TYPE_VERTEX -> for (k in 0 until count) {
                    idx[k] = c.vertexIndex()
                    r.floats(data, k * 3, 3)
                }
                MorphData.TYPE_BONE -> for (k in 0 until count) {
                    idx[k] = c.index(c.bSize)
                    r.floats(data, k * 7, 7)
                }
                MorphData.TYPE_UV, MorphData.TYPE_UV1, MorphData.TYPE_UV2, MorphData.TYPE_UV3, MorphData.TYPE_UV4 -> for (k in 0 until count) {
                    idx[k] = c.vertexIndex()
                    r.floats(data, k * 4, 4)
                }
                MorphData.TYPE_MATERIAL -> for (k in 0 until count) {
                    idx[k] = c.index(c.mSize)
                    val o = k * MorphData.MATERIAL_STRIDE
                    data[o] = r.u8().toFloat()
                    r.floats(data, o + 1, 28)
                }
                MorphData.TYPE_IMPULSE -> for (k in 0 until count) {
                    idx[k] = c.index(c.rSize)
                    val o = k * 7
                    data[o] = r.u8().toFloat()
                    r.floats(data, o + 1, 6)
                }
            }
            m.indices = idx
            m.data = data
            model.morphs.add(m)
        }
    }

    private fun readDisplayFrames(c: Ctx, model: ModelData) {
        val r = c.r
        val n = r.i32()
        if (n < 0 || n > 100000) throw FormatException("PMX 表示枠数异常：$n")
        for (i in 0 until n) {
            val f = DisplayFrame()
            f.name = c.text()
            f.nameEn = c.text()
            f.special = r.u8() != 0
            val count = r.i32()
            if (count < 0 || count > r.remaining) throw FormatException("PMX 表示枠元素数异常：$count")
            val types = ByteArray(count)
            val idx = IntArray(count)
            for (k in 0 until count) {
                val t = r.u8()
                types[k] = t.toByte()
                idx[k] = if (t == 0) c.index(c.bSize) else c.index(c.moSize)
            }
            f.elementTypes = types
            f.elementIndices = idx
            model.displayFrames.add(f)
        }
    }

    private fun readRigidBodies(c: Ctx, model: ModelData) {
        val r = c.r
        val n = r.i32()
        if (n < 0 || n > 100000) throw FormatException("PMX 刚体数异常：$n")
        for (i in 0 until n) {
            val b = RigidBodyData()
            b.name = c.text()
            b.nameEn = c.text()
            b.bone = c.index(c.bSize)
            b.group = r.u8() and 0x0F
            b.collisionMask = r.u16()
            b.shape = r.u8()
            c.vec3(b.size)
            c.vec3(b.position)
            c.vec3(b.rotation)
            b.mass = r.f32()
            b.linearDamping = r.f32()
            b.angularDamping = r.f32()
            b.restitution = r.f32()
            b.friction = r.f32()
            b.mode = r.u8()
            model.rigidBodies.add(b)
        }
    }

    private fun readJoints(c: Ctx, model: ModelData) {
        val r = c.r
        val n = r.i32()
        if (n < 0 || n > 100000) throw FormatException("PMX Joint 数异常：$n")
        for (i in 0 until n) {
            val j = JointData()
            j.name = c.text()
            j.nameEn = c.text()
            j.type = r.u8()
            j.bodyA = c.index(c.rSize)
            j.bodyB = c.index(c.rSize)
            c.vec3(j.position)
            c.vec3(j.rotation)
            c.vec3(j.linearLower)
            c.vec3(j.linearUpper)
            c.vec3(j.angularLower)
            c.vec3(j.angularUpper)
            c.vec3(j.linearSpring)
            c.vec3(j.angularSpring)
            model.joints.add(j)
        }
    }
}

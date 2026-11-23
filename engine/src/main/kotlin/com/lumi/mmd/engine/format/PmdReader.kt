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
import kotlin.math.sqrt

/**
 * PMD（MMD 旧模型格式）解析器。读完后直接转换成与 PMX 相同的 [ModelData]：
 * 骨骼类型 → PMX 标志位、IK 表 → 挂在 IK 骨上、base 表情 + 相对索引 → 绝对顶点索引、
 * 刚体相对坐标 → 模型坐标，等等。这样运行时只需要维护一套逻辑。
 */
object PmdReader {

    fun isPmd(bytes: ByteArray): Boolean =
        bytes.size > 283 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'm'.code.toByte() && bytes[2] == 'd'.code.toByte()

    fun read(bytes: ByteArray): ModelData {
        if (!isPmd(bytes)) throw FormatException("不是 PMD 文件（文件头应为 \"Pmd\"）")
        val r = BinaryReader(bytes)
        val sjis = TextCodec.SJIS
        r.skip(3)
        r.f32() // version
        val model = ModelData()
        model.format = ModelFormat.PMD
        model.name = r.fixedString(20, sjis)
        model.comment = r.fixedString(256, sjis)

        // ------------------------------------------------------------ 顶点
        val vn = r.i32()
        if (vn < 0 || vn.toLong() * 38 > r.remaining) throw FormatException("PMD 顶点数异常：$vn")
        val pos = FloatArray(vn * 3)
        val nrm = FloatArray(vn * 3)
        val uv = FloatArray(vn * 2)
        val bi = ShortArray(vn * 4)
        val bw = FloatArray(vn * 4)
        val edge = FloatArray(vn)
        val types = ByteArray(vn)
        for (v in 0 until vn) {
            r.floats(pos, v * 3, 3)
            r.floats(nrm, v * 3, 3)
            r.floats(uv, v * 2, 2)
            val b0 = r.u16()
            val b1 = r.u16()
            val w = r.u8() / 100f
            val o = v * 4
            bi[o] = b0.toShort(); bw[o] = w
            bi[o + 1] = b1.toShort(); bw[o + 1] = 1f - w
            types[v] = 1
            edge[v] = if (r.u8() == 0) 1f else 0f
        }
        model.vertexCount = vn
        model.positions = pos
        model.normals = nrm
        model.uvs = uv
        model.boneIndices = bi
        model.boneWeights = bw
        model.edgeScales = edge
        model.skinTypes = types

        // ------------------------------------------------------------ 面
        val fn = r.i32()
        if (fn < 0 || fn.toLong() * 2 > r.remaining) throw FormatException("PMD 面索引数异常：$fn")
        val idx = IntArray(fn)
        for (i in 0 until fn) idx[i] = r.u16()
        model.indices = idx

        // ------------------------------------------------------------ 材质
        val mn = r.i32()
        if (mn < 0 || mn.toLong() * 70 > r.remaining) throw FormatException("PMD 材质数异常：$mn")
        val toonOfMaterial = IntArray(mn)
        var offset = 0
        for (i in 0 until mn) {
            val m = MaterialData()
            m.name = "材质${i + 1}"
            r.floats(m.diffuse, 0, 3)
            m.diffuse[3] = r.f32()
            m.shininess = r.f32()
            r.floats(m.specular, 0, 3)
            r.floats(m.ambient, 0, 3)
            toonOfMaterial[i] = r.u8()
            val edgeFlag = r.u8()
            m.indexCount = r.i32()
            m.indexOffset = offset
            offset += maxOf(0, m.indexCount)
            val texName = r.fixedString(20, sjis)

            var flags = 0
            val alpha = m.diffuse[3]
            if (alpha < 1f) flags = flags or MaterialData.FLAG_DOUBLE_SIDED
            if (edgeFlag != 0) flags = flags or MaterialData.FLAG_EDGE or MaterialData.FLAG_GROUND_SHADOW
            // MMD 的老约定：alpha 恰为 0.98 的材质不参与自阴影
            if (kotlin.math.abs(alpha - 0.98f) > 1e-4f) flags = flags or MaterialData.FLAG_CAST_SHADOW or MaterialData.FLAG_RECEIVE_SHADOW
            m.flags = flags

            for (part in texName.split('*')) {
                val p = TextCodec.normalizePath(part)
                if (p.isEmpty()) continue
                val lower = p.lowercase()
                val ti = model.textures.size
                when {
                    lower.endsWith(".sph") -> { m.sphereTexture = ti; m.sphereMode = MaterialData.SPHERE_MUL }
                    lower.endsWith(".spa") -> { m.sphereTexture = ti; m.sphereMode = MaterialData.SPHERE_ADD }
                    else -> m.texture = ti
                }
                model.textures.add(TextureRef(p))
            }
            model.materials.add(m)
        }

        // ------------------------------------------------------------ 骨骼
        val bn = r.u16()
        val boneType = IntArray(bn)
        val boneTail = IntArray(bn)
        val boneIkParent = IntArray(bn)
        for (i in 0 until bn) {
            val b = BoneData()
            b.name = r.fixedString(20, sjis)
            val parent = r.u16()
            val tail = r.u16()
            val type = r.u8()
            val ikParent = r.u16()
            r.floats(b.position, 0, 3)
            b.parent = if (parent == 0xFFFF) -1 else parent
            boneType[i] = type
            boneTail[i] = tail
            boneIkParent[i] = ikParent
            var flags = BoneData.FLAG_ROTATABLE
            if (type == 1 || type == 2) flags = flags or BoneData.FLAG_TRANSLATABLE
            if (type != 6 && type != 7) flags = flags or BoneData.FLAG_VISIBLE or BoneData.FLAG_OPERABLE
            if (type != 9 && tail != 0 && tail != 0xFFFF) {
                b.tailBone = tail
                flags = flags or BoneData.FLAG_TAIL_IS_BONE
            }
            b.flags = flags
            model.bones.add(b)
        }
        for (i in 0 until bn) {
            val b = model.bones[i]
            when (boneType[i]) {
                5 -> { // 回転影響下：完全跟随指定骨的旋转
                    val src = boneIkParent[i]
                    if (src < bn && src != i) {
                        b.appendParent = src
                        b.appendWeight = 1f
                        b.flags = b.flags or BoneData.FLAG_APPEND_ROTATE
                    }
                }
                9 -> { // 回転連動：按比例跟随
                    val src = boneIkParent[i]
                    if (src < bn && src != i) {
                        b.appendParent = src
                        b.appendWeight = boneTail[i] * 0.01f
                        b.flags = b.flags or BoneData.FLAG_APPEND_ROTATE
                    }
                }
                8 -> { // 捩り：轴限制为指向 tail 骨的方向
                    val t = boneTail[i]
                    if (t < bn && t != i) {
                        val tp = model.bones[t].position
                        val dx = tp[0] - b.position[0]; val dy = tp[1] - b.position[1]; val dz = tp[2] - b.position[2]
                        val l = sqrt(dx * dx + dy * dy + dz * dz)
                        if (l > 1e-6f) {
                            b.fixedAxis[0] = dx / l; b.fixedAxis[1] = dy / l; b.fixedAxis[2] = dz / l
                            b.flags = b.flags or BoneData.FLAG_FIXED_AXIS
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------ IK
        val ikn = r.u16()
        for (i in 0 until ikn) {
            val ikBone = r.u16()
            val target = r.u16()
            val chain = r.u8()
            val iterations = r.u16()
            val weight = r.f32()
            val ik = IkData()
            ik.target = target
            ik.loopCount = iterations
            ik.limitAngle = weight * 4f
            for (k in 0 until chain) {
                val link = IkLink()
                link.bone = r.u16()
                if (link.bone < bn && model.bones[link.bone].name.contains("ひざ")) {
                    // MMD 对 PMD 的"ひざ"硬编码了只能绕 X 轴向后弯
                    link.hasLimit = true
                    link.lower[0] = -Math.PI.toFloat()
                    link.upper[0] = -0.5f * (Math.PI.toFloat() / 180f)
                }
                ik.links.add(link)
            }
            if (ikBone < bn) {
                val b = model.bones[ikBone]
                b.ik = ik
                b.flags = b.flags or BoneData.FLAG_IK
            }
        }

        // ------------------------------------------------------------ 表情
        val sn = r.u16()
        var baseIndices: IntArray? = null
        val morphIndexOfSkin = IntArray(sn) { -1 }
        for (i in 0 until sn) {
            val name = r.fixedString(20, sjis)
            val count = r.i32()
            val type = r.u8()
            if (count < 0 || count.toLong() * 16 > r.remaining) throw FormatException("PMD 表情顶点数异常：$count")
            val vi = IntArray(count)
            val data = FloatArray(count * 3)
            for (k in 0 until count) {
                vi[k] = r.i32()
                r.floats(data, k * 3, 3)
            }
            if (type == 0) {
                if (baseIndices == null) baseIndices = vi
                continue
            }
            val base = baseIndices
            val m = MorphData()
            m.name = name
            m.panel = type
            m.type = MorphData.TYPE_VERTEX
            m.count = count
            if (base != null) {
                for (k in 0 until count) {
                    val bidx = vi[k]
                    vi[k] = if (bidx in base.indices) base[bidx] else 0
                }
            }
            m.indices = vi
            m.data = data
            morphIndexOfSkin[i] = model.morphs.size
            model.morphs.add(m)
        }

        // ------------------------------------------------------------ 表示枠
        try {
            val skinDispCount = r.u8()
            val skinDisp = IntArray(skinDispCount) { r.u16() }
            val boneDispNameCount = r.u8()
            val frameNames = Array(boneDispNameCount) { r.fixedString(50, sjis).trim().trimEnd('\n') }
            val boneDispCount = r.i32()
            val frameBones = Array(boneDispNameCount) { ArrayList<Int>() }
            for (i in 0 until boneDispCount) {
                val bone = r.u16()
                val frame = r.u8()
                if (frame in 1..boneDispNameCount && bone < bn) frameBones[frame - 1].add(bone)
            }
            val face = DisplayFrame()
            face.name = "表情"
            face.nameEn = "Exp"
            face.special = true
            val faceIdx = skinDisp.map { if (it in 0 until sn) morphIndexOfSkin[it] else -1 }.filter { it >= 0 }
            face.elementTypes = ByteArray(faceIdx.size) { 1 }
            face.elementIndices = faceIdx.toIntArray()
            model.displayFrames.add(face)
            for (i in 0 until boneDispNameCount) {
                val f = DisplayFrame()
                f.name = frameNames[i]
                f.elementTypes = ByteArray(frameBones[i].size)
                f.elementIndices = frameBones[i].toIntArray()
                model.displayFrames.add(f)
            }

            // -------------------------------------------------------- 扩展：英文名
            if (r.hasRemaining(1)) {
                val english = r.u8()
                if (english == 1) {
                    model.nameEn = r.fixedString(20, sjis)
                    model.commentEn = r.fixedString(256, sjis)
                    for (i in 0 until bn) model.bones[i].nameEn = r.fixedString(20, sjis)
                    for (i in 1 until sn) {
                        val en = r.fixedString(20, sjis)
                        val mi = morphIndexOfSkin[i]
                        if (mi >= 0) model.morphs[mi].nameEn = en
                    }
                    for (i in 0 until boneDispNameCount) {
                        val en = r.fixedString(50, sjis)
                        model.displayFrames[i + 1].nameEn = en
                    }
                }
            }

            // -------------------------------------------------------- 扩展：Toon 贴图表
            val toonNames = Array(10) { "toon%02d.bmp".format(it + 1) }
            if (r.hasRemaining(1000)) {
                for (i in 0 until 10) {
                    val name = TextCodec.normalizePath(r.fixedString(100, sjis))
                    if (name.isNotEmpty()) toonNames[i] = name
                }
            }
            val toonBase = model.textures.size
            for (i in 0 until 10) model.textures.add(TextureRef(toonNames[i]))
            for (i in 0 until mn) {
                val t = toonOfMaterial[i]
                val m = model.materials[i]
                if (t in 0..9) {
                    m.sharedToon = false
                    m.toonIndex = toonBase + t
                } else {
                    m.toonIndex = -1
                }
            }

            // -------------------------------------------------------- 扩展：物理
            if (r.hasRemaining(4)) {
                val centerBone = model.bones.indexOfFirst { it.name == "センター" }.let { if (it >= 0) it else if (bn > 0) 0 else -1 }
                val rn = r.i32()
                if (rn < 0 || rn.toLong() * 83 > r.remaining) throw FormatException("PMD 刚体数异常：$rn")
                for (i in 0 until rn) {
                    val b = RigidBodyData()
                    b.name = r.fixedString(20, sjis)
                    val bone = r.u16()
                    b.group = r.u8() and 0x0F
                    b.collisionMask = r.u16()
                    b.shape = r.u8()
                    r.floats(b.size, 0, 3)
                    r.floats(b.position, 0, 3)
                    r.floats(b.rotation, 0, 3)
                    b.mass = r.f32()
                    b.linearDamping = r.f32()
                    b.angularDamping = r.f32()
                    b.restitution = r.f32()
                    b.friction = r.f32()
                    b.mode = r.u8()
                    // PMD 的刚体位置是相对"关联骨"的偏移；0xFFFF 表示关联到センター
                    b.bone = if (bone == 0xFFFF) centerBone else if (bone < bn) bone else -1
                    if (b.bone >= 0) {
                        val bp = model.bones[b.bone].position
                        b.position[0] += bp[0]; b.position[1] += bp[1]; b.position[2] += bp[2]
                    }
                    model.rigidBodies.add(b)
                }
                val jn = r.i32()
                if (jn < 0 || jn.toLong() * 124 > r.remaining) throw FormatException("PMD Joint 数异常：$jn")
                for (i in 0 until jn) {
                    val j = JointData()
                    j.name = r.fixedString(20, sjis)
                    j.bodyA = r.i32()
                    j.bodyB = r.i32()
                    r.floats(j.position, 0, 3)
                    r.floats(j.rotation, 0, 3)
                    r.floats(j.linearLower, 0, 3)
                    r.floats(j.linearUpper, 0, 3)
                    r.floats(j.angularLower, 0, 3)
                    r.floats(j.angularUpper, 0, 3)
                    r.floats(j.linearSpring, 0, 3)
                    r.floats(j.angularSpring, 0, 3)
                    model.joints.add(j)
                }
            }
        } catch (e: Exception) {
            model.warnings.add("PMD 扩展段数据不完整：${e.message}")
        }

        // 没有扩展段的老 PMD：补上默认 toon
        if (model.materials.any { !it.sharedToon && it.toonIndex < 0 } && toonOfMaterial.any { it in 0..9 }) {
            for (i in 0 until mn) {
                val m = model.materials[i]
                val t = toonOfMaterial[i]
                if (m.toonIndex < 0 && t in 0..9) { m.sharedToon = true; m.toonIndex = t }
            }
        }
        model.sanitize()
        return model
    }
}

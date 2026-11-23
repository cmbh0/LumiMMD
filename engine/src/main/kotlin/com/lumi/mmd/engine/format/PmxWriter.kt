package com.lumi.mmd.engine.format

import com.lumi.mmd.engine.model.BoneData
import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.MorphData
import com.lumi.mmd.engine.model.TextureRef
import com.lumi.mmd.engine.util.BinaryWriter
import com.lumi.mmd.engine.util.TextCodec
import java.io.File

/**
 * 把 [ModelData] 写成 PMX 2.0（UTF-16LE），MikuMikuDance / PmxEditor / Blender mmd_tools 都能打开。
 *
 * 读进来的 PMX 原样写回（骨骼、IK、表情、显示枠、刚体、关节都保留）；
 * 其它格式（OBJ / glTF …）或程序里新建的网格也能写，材质会换算成 MMD 的漫反射 / 环境光形式。
 */
object PmxWriter {
    private fun signedSize(count: Int) = if (count <= 127) 1 else if (count <= 32767) 2 else 4
    private fun vertexSize(count: Int) = if (count <= 255) 1 else if (count <= 65535) 2 else 4

    /**
     * 写到文件。内嵌贴图（glTF 等）会另存到同目录的 `<文件名>_tex/` 里。
     * @param bakeScale 把这个倍率烘进顶点 / 骨骼坐标（非 MMD 格式换算单位用）
     */
    fun save(model: ModelData, file: File, bakeScale: Float = 1f) {
        val dir = file.parentFile ?: File(".")
        dir.mkdirs()
        val textures = ArrayList<String>(model.textures.size)
        var texDir: File? = null
        val baseDir = model.baseDir
        for ((i, t) in model.textures.withIndex()) {
            val bytes = t.embedded
            if (bytes != null) {
                val td = texDir ?: File(dir, file.nameWithoutExtension + "_tex").also { it.mkdirs(); texDir = it }
                val ext = sniffExt(bytes)
                val name = "tex$i.$ext"
                try { File(td, name).writeBytes(bytes) } catch (_: Exception) {}
                textures.add(td.name + "\\" + name)
            } else {
                var path = t.path
                // 模型换了目录：贴图引用改成相对新位置的路径（同一个存储卷内）
                if (baseDir != null && baseDir.absoluteFile != dir.absoluteFile) {
                    val src = File(baseDir, path.replace('\\', '/'))
                    val rel = try { src.absoluteFile.relativeToOrNull(dir.absoluteFile)?.path } catch (_: Exception) { null }
                    if (rel != null) path = rel
                }
                textures.add(path.replace('/', '\\'))
            }
        }
        file.writeBytes(write(model, textures, bakeScale))
    }

    private fun sniffExt(b: ByteArray): String = when {
        b.size > 3 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "png"
        b.size > 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
        b.size > 1 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "bmp"
        b.size > 3 && b[0] == 'D'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == 'S'.code.toByte() -> "dds"
        b.size > 11 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() -> "webp"
        else -> "png"
    }

    fun write(model: ModelData, texturePaths: List<String> = model.textures.map { it.path.replace('/', '\\') }, bakeScale: Float = 1f): ByteArray {
        val n = model.vertexCount
        val w = BinaryWriter(64 + n * 48 + model.indices.size * 4)
        val cs = TextCodec.UTF16LE
        val s = bakeScale
        val bones: List<BoneData> = if (model.bones.isEmpty()) listOf(BoneData().also { it.name = "センター"; it.nameEn = "center"; it.flags = it.flags or BoneData.FLAG_TRANSLATABLE }) else model.bones
        val vSize = vertexSize(n)
        val tSize = signedSize(texturePaths.size)
        val mSize = signedSize(model.materials.size)
        val bSize = signedSize(bones.size)
        val moSize = signedSize(model.morphs.size)
        val rSize = signedSize(model.rigidBodies.size)

        // 顶点色（OBJ / glTF / PLY 带来的）PMX 本身没有位置放：存进"追加 UV1"，并在说明里做个记号，本应用读回来时能还原
        val colors = model.colors?.takeIf { it.size >= n * 4 }
        w.bytes(byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'X'.code.toByte(), ' '.code.toByte()))
        w.f32(2.0f)
        w.u8(8)
        w.u8(0)          // UTF-16LE
        w.u8(if (colors != null) 1 else 0)   // 追加 UV 数
        w.u8(vSize); w.u8(tSize); w.u8(mSize); w.u8(bSize); w.u8(moSize); w.u8(rSize)
        w.text(model.name.ifEmpty { "Lumi MMD" }, cs)
        w.text(model.nameEn, cs)
        val comment = model.comment.replace(VCOLOR_TAG, "").trimEnd()
        w.text(if (colors != null) (if (comment.isEmpty()) VCOLOR_TAG else comment + "\r\n" + VCOLOR_TAG) else comment, cs)
        w.text(model.commentEn, cs)

        // ---- 顶点
        val pos = model.positions; val nrm = model.normals; val uv = model.uvs
        val bi = model.boneIndices; val bw = model.boneWeights
        val types = model.skinTypes
        val sc = model.sdefC; val s0 = model.sdefR0; val s1 = model.sdefR1
        val edge = model.edgeScales
        w.i32(n)
        for (v in 0 until n) {
            val p = v * 3
            w.f32(pos[p] * s); w.f32(pos[p + 1] * s); w.f32(pos[p + 2] * s)
            w.f32(nrm[p]); w.f32(nrm[p + 1]); w.f32(nrm[p + 2])
            w.f32(uv[v * 2]); w.f32(uv[v * 2 + 1])
            if (colors != null) { w.f32(colors[v * 4]); w.f32(colors[v * 4 + 1]); w.f32(colors[v * 4 + 2]); w.f32(colors[v * 4 + 3]) }
            if (bi == null || bw == null) {
                w.u8(0); w.index(0, bSize)
            } else {
                val o = v * 4
                val b0 = bi[o].toInt() and 0xFFFF; val b1 = bi[o + 1].toInt() and 0xFFFF
                val b2 = bi[o + 2].toInt() and 0xFFFF; val b3 = bi[o + 3].toInt() and 0xFFFF
                val w0 = bw[o]; val w1 = bw[o + 1]; val w2 = bw[o + 2]; val w3 = bw[o + 3]
                var type = types?.get(v)?.toInt() ?: -1
                if (type == 3 && (sc == null || s0 == null || s1 == null)) type = 1
                if (type < 0 || type > 4) type = if (w2 != 0f || w3 != 0f) 2 else if (w1 != 0f) 1 else 0
                // 权重和类型对不上时（编辑过）按权重个数来
                if (type == 0 && w1 != 0f) type = 1
                if ((type == 0 || type == 1 || type == 3) && (w2 != 0f || w3 != 0f)) type = 2
                w.u8(type)
                when (type) {
                    0 -> w.index(b0, bSize)
                    1 -> { w.index(b0, bSize); w.index(b1, bSize); w.f32(w0 / maxOf(w0 + w1, 1e-9f)) }
                    3 -> {
                        w.index(b0, bSize); w.index(b1, bSize); w.f32(w0)
                        // 读入时存的是 C、(C+R0')/2、(C+R1')/2；还原成一组等价的 C / R0 / R1
                        for (k in 0 until 3) w.f32(sc!![p + k] * s)
                        for (k in 0 until 3) w.f32((2f * s0!![p + k] - sc!![p + k]) * s)
                        for (k in 0 until 3) w.f32((2f * s1!![p + k] - sc!![p + k]) * s)
                    }
                    else -> {
                        w.index(b0, bSize); w.index(b1, bSize); w.index(b2, bSize); w.index(b3, bSize)
                        w.f32(w0); w.f32(w1); w.f32(w2); w.f32(w3)
                    }
                }
            }
            w.f32(edge?.get(v) ?: 1f)
        }

        // ---- 面
        val idx = model.indices
        var used = 0
        for (m in model.materials) used += m.indexCount
        used = minOf(used, idx.size)
        w.i32(used)
        // 各材质的面在索引数组里未必紧挨着：按材质顺序重排着写
        for (m in model.materials) {
            val end = minOf(m.indexOffset + m.indexCount, idx.size)
            for (i in m.indexOffset until end) w.index(idx[i], vSize)
        }

        // ---- 贴图
        w.i32(texturePaths.size)
        for (t in texturePaths) w.text(t, cs)

        // ---- 材质
        w.i32(model.materials.size)
        for (m in model.materials) {
            w.text(m.name, cs); w.text(m.nameEn, cs)
            val mmd = m.shading == MaterialData.SHADING_MMD
            w.f32(m.diffuse[0]); w.f32(m.diffuse[1]); w.f32(m.diffuse[2]); w.f32(m.diffuse[3])
            w.f32(m.specular[0]); w.f32(m.specular[1]); w.f32(m.specular[2])
            w.f32(m.shininess)
            if (mmd) { w.f32(m.ambient[0]); w.f32(m.ambient[1]); w.f32(m.ambient[2]) }
            else { w.f32(m.diffuse[0] * 0.5f); w.f32(m.diffuse[1] * 0.5f); w.f32(m.diffuse[2] * 0.5f) }
            w.u8(m.flags and 0xFF)
            w.f32(m.edgeColor[0]); w.f32(m.edgeColor[1]); w.f32(m.edgeColor[2]); w.f32(m.edgeColor[3])
            w.f32(m.edgeSize)
            w.index(if (m.texture in texturePaths.indices) m.texture else -1, tSize)
            w.index(if (m.sphereTexture in texturePaths.indices) m.sphereTexture else -1, tSize)
            w.u8(m.sphereMode)
            if (m.sharedToon) { w.u8(1); w.u8(m.toonIndex.coerceIn(0, 9)) }
            else { w.u8(0); w.index(if (m.toonIndex in texturePaths.indices) m.toonIndex else -1, tSize) }
            w.text(memoWithTag(m), cs)
            w.i32(minOf(m.indexCount, maxOf(0, idx.size - m.indexOffset)))
        }

        // ---- 骨骼
        w.i32(bones.size)
        for (b in bones) {
            w.text(b.name, cs); w.text(b.nameEn, cs)
            w.f32(b.position[0] * s); w.f32(b.position[1] * s); w.f32(b.position[2] * s)
            w.index(b.parent, bSize)
            w.i32(b.level)
            var flags = b.flags and 0xFFFF
            val ik = b.ik
            flags = if (ik != null) flags or BoneData.FLAG_IK else flags and BoneData.FLAG_IK.inv()
            val append = (flags and (BoneData.FLAG_APPEND_ROTATE or BoneData.FLAG_APPEND_TRANSLATE)) != 0
            w.u16(flags)
            if ((flags and BoneData.FLAG_TAIL_IS_BONE) != 0) w.index(b.tailBone, bSize)
            else { w.f32(b.tailOffset[0] * s); w.f32(b.tailOffset[1] * s); w.f32(b.tailOffset[2] * s) }
            if (append) { w.index(b.appendParent, bSize); w.f32(b.appendWeight) }
            if ((flags and BoneData.FLAG_FIXED_AXIS) != 0) for (k in 0 until 3) w.f32(b.fixedAxis[k])
            if ((flags and BoneData.FLAG_LOCAL_AXIS) != 0) { for (k in 0 until 3) w.f32(b.localX[k]); for (k in 0 until 3) w.f32(b.localZ[k]) }
            if ((flags and BoneData.FLAG_EXTERNAL_PARENT) != 0) w.i32(b.externalKey)
            if (ik != null) {
                w.index(ik.target, bSize)
                w.i32(ik.loopCount)
                w.f32(ik.limitAngle)
                w.i32(ik.links.size)
                for (l in ik.links) {
                    w.index(l.bone, bSize)
                    w.u8(if (l.hasLimit) 1 else 0)
                    if (l.hasLimit) { for (k in 0 until 3) w.f32(l.lower[k]); for (k in 0 until 3) w.f32(l.upper[k]) }
                }
            }
        }

        // ---- 表情（没有追加 UV，所以 UV1~4 表情不写；Flip / Impulse 是 2.1 才有的）
        val morphs = model.morphs
        val keep = BooleanArray(morphs.size) { val t = morphs[it].type; t == MorphData.TYPE_GROUP || t == MorphData.TYPE_VERTEX || t == MorphData.TYPE_BONE || t == MorphData.TYPE_UV || t == MorphData.TYPE_MATERIAL }
        val newIndex = IntArray(morphs.size)
        var kept = 0
        for (i in morphs.indices) { newIndex[i] = if (keep[i]) kept++ else -1 }
        val moOut = signedSize(kept)
        // 表情序号宽度写在文件头里（第 8 个全局量）
        w.data[4 + 4 + 1 + 6] = moOut.toByte()
        w.i32(kept)
        for ((i, m) in morphs.withIndex()) {
            if (!keep[i]) continue
            w.text(m.name, cs); w.text(m.nameEn, cs)
            w.u8(m.panel.coerceIn(0, 4))
            w.u8(m.type)
            val d = m.data
            when (m.type) {
                MorphData.TYPE_GROUP -> {
                    var c = 0
                    for (k in 0 until m.count) if (m.indices[k] in morphs.indices && keep[m.indices[k]]) c++
                    w.i32(c)
                    for (k in 0 until m.count) {
                        val t = m.indices[k]
                        if (t !in morphs.indices || !keep[t]) continue
                        w.index(newIndex[t], moOut); w.f32(d[k])
                    }
                }
                MorphData.TYPE_VERTEX -> {
                    w.i32(m.count)
                    for (k in 0 until m.count) { w.index(m.indices[k], vSize); w.f32(d[k * 3] * s); w.f32(d[k * 3 + 1] * s); w.f32(d[k * 3 + 2] * s) }
                }
                MorphData.TYPE_BONE -> {
                    w.i32(m.count)
                    for (k in 0 until m.count) {
                        w.index(m.indices[k], bSize)
                        w.f32(d[k * 7] * s); w.f32(d[k * 7 + 1] * s); w.f32(d[k * 7 + 2] * s)
                        w.f32(d[k * 7 + 3]); w.f32(d[k * 7 + 4]); w.f32(d[k * 7 + 5]); w.f32(d[k * 7 + 6])
                    }
                }
                MorphData.TYPE_UV -> {
                    w.i32(m.count)
                    for (k in 0 until m.count) { w.index(m.indices[k], vSize); for (c in 0 until 4) w.f32(d[k * 4 + c]) }
                }
                else -> {
                    w.i32(m.count)
                    for (k in 0 until m.count) {
                        w.index(m.indices[k], mSize)
                        val o = k * MorphData.MATERIAL_STRIDE
                        w.u8(d[o].toInt())
                        for (c in 1..28) w.f32(d[o + c])
                    }
                }
            }
        }

        // ---- 显示枠
        val frames = model.displayFrames
        if (frames.isEmpty()) {
            // MMD 要求至少有 Root 和 表情 两个特殊枠
            w.i32(3)
            w.text("Root", cs); w.text("Root", cs); w.u8(1); w.i32(1); w.u8(0); w.index(0, bSize)
            w.text("表情", cs); w.text("Exp", cs); w.u8(1); w.i32(kept)
            for (i in 0 until kept) { w.u8(1); w.index(i, moOut) }
            w.text("骨骼", cs); w.text("Bones", cs); w.u8(0); w.i32(maxOf(0, bones.size - 1))
            for (i in 1 until bones.size) { w.u8(0); w.index(i, bSize) }
        } else {
            w.i32(frames.size)
            for (f in frames) {
                w.text(f.name, cs); w.text(f.nameEn, cs)
                w.u8(if (f.special) 1 else 0)
                var c = 0
                for (k in f.elementIndices.indices) {
                    val e = f.elementIndices[k]
                    if (f.elementTypes[k].toInt() == 0) { if (e in bones.indices) c++ } else if (e in morphs.indices && keep[e]) c++
                }
                w.i32(c)
                for (k in f.elementIndices.indices) {
                    val e = f.elementIndices[k]
                    if (f.elementTypes[k].toInt() == 0) { if (e in bones.indices) { w.u8(0); w.index(e, bSize) } }
                    else if (e in morphs.indices && keep[e]) { w.u8(1); w.index(newIndex[e], moOut) }
                }
            }
        }

        // ---- 刚体
        w.i32(model.rigidBodies.size)
        for (b in model.rigidBodies) {
            w.text(b.name, cs); w.text(b.nameEn, cs)
            w.index(b.bone, bSize)
            w.u8(b.group); w.u16(b.collisionMask); w.u8(b.shape)
            for (k in 0 until 3) w.f32(b.size[k] * s)
            for (k in 0 until 3) w.f32(b.position[k] * s)
            for (k in 0 until 3) w.f32(b.rotation[k])
            w.f32(b.mass); w.f32(b.linearDamping); w.f32(b.angularDamping); w.f32(b.restitution); w.f32(b.friction)
            w.u8(b.mode)
        }

        // ---- 关节
        w.i32(model.joints.size)
        for (j in model.joints) {
            w.text(j.name, cs); w.text(j.nameEn, cs)
            w.u8(j.type)
            w.index(j.bodyA, rSize); w.index(j.bodyB, rSize)
            for (k in 0 until 3) w.f32(j.position[k] * s)
            for (k in 0 until 3) w.f32(j.rotation[k])
            for (k in 0 until 3) w.f32(j.linearLower[k] * s)
            for (k in 0 until 3) w.f32(j.linearUpper[k] * s)
            for (k in 0 until 3) w.f32(j.angularLower[k])
            for (k in 0 until 3) w.f32(j.angularUpper[k])
            for (k in 0 until 3) w.f32(j.linearSpring[k])
            for (k in 0 until 3) w.f32(j.angularSpring[k])
        }
        return w.toByteArray()
    }

    const val VCOLOR_TAG = "[lumi vcolor=1]"
    private val MEMO_TAG = Regex("\\s*\\[lumi [^\\]]*\\]")

    /**
     * 非 MMD 风格的材质（glTF / OBJ 的"标准"着色、不透明 / 镂空方式、自发光）PMX 里没有对应的字段：
     * 记在材质的备注里，本应用读回来时还原；别的软件会把它当成普通备注。
     */
    private fun memoWithTag(m: MaterialData): String {
        val memo = m.memo.replace(MEMO_TAG, "")
        if (m.shading == MaterialData.SHADING_MMD && m.alphaMode == MaterialData.ALPHA_BLEND && m.emissive[0] == 0f && m.emissive[1] == 0f && m.emissive[2] == 0f) return memo
        val tag = "[lumi shading=${m.shading} alpha=${m.alphaMode} cutoff=${m.alphaCutoff} emissive=${m.emissive[0]},${m.emissive[1]},${m.emissive[2]} ambient=${m.ambient[0]},${m.ambient[1]},${m.ambient[2]}]"
        return if (memo.isEmpty()) tag else "$memo $tag"
    }

    /** 读取时调用：把 [memoWithTag] 记下的信息还原到材质上，并把记号从备注里去掉。 */
    fun applyMemoTag(m: MaterialData) {
        val match = Regex("\\[lumi ([^\\]]*)\\]").find(m.memo) ?: return
        for (kv in match.groupValues[1].split(' ')) {
            val i = kv.indexOf('=')
            if (i <= 0) continue
            val v = kv.substring(i + 1)
            try {
                when (kv.substring(0, i)) {
                    "shading" -> m.shading = v.toInt().coerceIn(0, 2)
                    "alpha" -> m.alphaMode = v.toInt().coerceIn(0, 2)
                    "cutoff" -> m.alphaCutoff = v.toFloat()
                    "emissive" -> v.split(',').let { if (it.size == 3) for (k in 0 until 3) m.emissive[k] = it[k].toFloat() }
                    "ambient" -> v.split(',').let { if (it.size == 3) for (k in 0 until 3) m.ambient[k] = it[k].toFloat() }
                }
            } catch (_: NumberFormatException) {
            }
        }
        m.memo = m.memo.replace(MEMO_TAG, "")
    }
}

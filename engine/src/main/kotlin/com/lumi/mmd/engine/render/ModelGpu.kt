package com.lumi.mmd.engine.render

import android.opengl.GLES30
import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.scene.ModelInstance
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * 一个模型实例在 GPU 上的资源：VAO、各顶点流、索引缓冲、骨骼矩阵纹理、材质贴图。
 * 顶点坐标单独放在一个动态 VBO 里（表情 / 部件编辑会改它），其余都是静态的。
 */
class ModelGpu(val model: ModelInstance, private val textures: TextureManager, sdefEnabled: Boolean) {
    private val data = model.data
    var vao = 0
        private set
    private var posVbo = 0
    private var normalVbo = 0
    private var uvVbo = 0
    private var skinVbo = 0
    private var sdefVbo = 0
    private var colorVbo = 0
    private var ibo = 0
    var boneTex = 0
        private set
    private var boneTexHeight = 0
    private var boneBuffer: FloatBuffer? = null

    val indexType: Int = if (data.vertexCount > 65535) GLES30.GL_UNSIGNED_INT else GLES30.GL_UNSIGNED_SHORT
    val indexBytes: Int = if (data.vertexCount > 65535) 4 else 2

    val skinned: Boolean = model.skeleton != null
    val sdef: Boolean = skinned && sdefEnabled && data.hasSdef
    /** 着色器变体标志。 */
    val flags: Int = (if (skinned) Shaders.FLAG_SKINNED else 0) or (if (sdef) Shaders.FLAG_SDEF else 0) or
        (if (data.colors != null) Shaders.FLAG_VCOLOR else 0)

    val tex = arrayOfNulls<GlTexture>(data.materials.size)
    val sphere = arrayOfNulls<GlTexture>(data.materials.size)
    val toon = arrayOfNulls<GlTexture>(data.materials.size)
    /** 使用共享 toon 的材质：GL 纹理 id；否则 0。 */
    val sharedToon = IntArray(data.materials.size)

    fun create(scratch: Scratch) {
        val n = data.vertexCount
        vao = GlUtil.genVertexArray()
        GLES30.glBindVertexArray(vao)

        // 位置（动态）
        posVbo = GlUtil.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, posVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 12, null, GLES30.GL_DYNAMIC_DRAW)
        uploadFloats(GLES30.GL_ARRAY_BUFFER, model.morphs.positions, 0, n * 3, 0, scratch)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)

        normalVbo = GlUtil.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, normalVbo)
        val normals = if (model.partsInitialized) model.parts.editedNormals ?: data.normals else data.normals
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 12, null, GLES30.GL_STATIC_DRAW)
        uploadFloats(GLES30.GL_ARRAY_BUFFER, normals, 0, n * 3, 0, scratch)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 0, 0)

        uvVbo = GlUtil.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, uvVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 8, null,
            if (model.morphs.hasUvMorphs) GLES30.GL_DYNAMIC_DRAW else GLES30.GL_STATIC_DRAW)
        uploadFloats(GLES30.GL_ARRAY_BUFFER, model.morphs.uvs, 0, n * 2, 0, scratch)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 0, 0)

        if (skinned) {
            // 每顶点 28 字节：4 × u16 骨骼序号 + 4 × f32 权重 + f32 描边倍率
            val bi = data.boneIndices!!
            val bw = data.boneWeights!!
            val es = data.edgeScales
            skinVbo = GlUtil.genBuffer()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, skinVbo)
            uploadPacked(GLES30.GL_ARRAY_BUFFER, n, 28, GLES30.GL_STATIC_DRAW, scratch) { buf, v ->
                val o = v * 4
                buf.putShort(bi[o]).putShort(bi[o + 1]).putShort(bi[o + 2]).putShort(bi[o + 3])
                buf.putFloat(bw[o]).putFloat(bw[o + 1]).putFloat(bw[o + 2]).putFloat(bw[o + 3])
                buf.putFloat(if (es != null) es[v] else 1f)
            }
            GLES30.glEnableVertexAttribArray(3)
            GLES30.glVertexAttribPointer(3, 4, GLES30.GL_UNSIGNED_SHORT, false, 28, 0)
            GLES30.glEnableVertexAttribArray(4)
            GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, 28, 8)
            GLES30.glEnableVertexAttribArray(5)
            GLES30.glVertexAttribPointer(5, 1, GLES30.GL_FLOAT, false, 28, 24)
        }
        if (sdef) {
            val c = data.sdefC!!; val r0 = data.sdefR0!!; val r1 = data.sdefR1!!
            val types = data.skinTypes
            sdefVbo = GlUtil.genBuffer()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, sdefVbo)
            uploadPacked(GLES30.GL_ARRAY_BUFFER, n, 40, GLES30.GL_STATIC_DRAW, scratch) { buf, v ->
                val o = v * 3
                val isSdef = types != null && types[v].toInt() == 3
                buf.putFloat(c[o]).putFloat(c[o + 1]).putFloat(c[o + 2]).putFloat(if (isSdef) 1f else 0f)
                buf.putFloat(r0[o]).putFloat(r0[o + 1]).putFloat(r0[o + 2])
                buf.putFloat(r1[o]).putFloat(r1[o + 1]).putFloat(r1[o + 2])
            }
            GLES30.glEnableVertexAttribArray(6)
            GLES30.glVertexAttribPointer(6, 4, GLES30.GL_FLOAT, false, 40, 0)
            GLES30.glEnableVertexAttribArray(7)
            GLES30.glVertexAttribPointer(7, 3, GLES30.GL_FLOAT, false, 40, 16)
            GLES30.glEnableVertexAttribArray(8)
            GLES30.glVertexAttribPointer(8, 3, GLES30.GL_FLOAT, false, 40, 28)
        }
        val colors = data.colors
        if (colors != null) {
            colorVbo = GlUtil.genBuffer()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, colorVbo)
            uploadPacked(GLES30.GL_ARRAY_BUFFER, n, 4, GLES30.GL_STATIC_DRAW, scratch) { buf, v ->
                val o = v * 4
                for (k in 0 until 4) buf.put((colors[o + k].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            }
            GLES30.glEnableVertexAttribArray(9)
            GLES30.glVertexAttribPointer(9, 4, GLES30.GL_UNSIGNED_BYTE, true, 0, 0)
        }

        // 索引
        val idx = data.indices
        ibo = GlUtil.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        val wide = indexBytes != 2
        uploadPacked(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size, indexBytes, GLES30.GL_STATIC_DRAW, scratch) { buf, i ->
            if (wide) buf.putInt(idx[i]) else buf.putShort(idx[i].toShort())
        }
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        // 骨骼矩阵纹理：每行 256 根骨骼，每根 4 个 texel
        val sk = model.skeleton
        if (sk != null) {
            boneTexHeight = (sk.count + BONES_PER_ROW - 1) / BONES_PER_ROW
            boneBuffer = GlUtil.floatBuffer(boneTexHeight * BONES_PER_ROW * 16)
            boneTex = GlUtil.genTexture()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, boneTex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, BONES_PER_ROW * 4, boneTexHeight, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, null)
            model.boneTexelsDirty = true
        }

        // 材质贴图
        val dir = data.baseDir
        for ((i, m) in data.materials.withIndex()) {
            if (m.texture >= 0) tex[i] = textures.acquire(data.textures[m.texture], dir, GlTexture.KIND_COLOR)
            if (m.sphereTexture >= 0 && m.sphereMode != MaterialData.SPHERE_NONE) {
                val kind = if (m.shading == MaterialData.SHADING_MMD) GlTexture.KIND_SPHERE else GlTexture.KIND_COLOR
                sphere[i] = textures.acquire(data.textures[m.sphereTexture], dir, kind)
            }
            if (m.toonIndex >= 0) {
                if (m.sharedToon) sharedToon[i] = textures.sharedToon(m.toonIndex)
                else toon[i] = textures.acquire(data.textures[m.toonIndex], dir, GlTexture.KIND_TOON)
            }
        }
        model.morphs.clearDirty()
    }

    /** 把 CPU 侧变化的数据（表情后的顶点、UV、部件法线、骨骼矩阵）同步到 GPU。 */
    fun sync(scratch: Scratch) {
        val mo = model.morphs
        if (mo.posDirtyMax >= mo.posDirtyMin) {
            val a = mo.posDirtyMin
            val count = mo.posDirtyMax - a + 1
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, posVbo)
            uploadFloats(GLES30.GL_ARRAY_BUFFER, mo.positions, a * 3, count * 3, a * 12, scratch)
        }
        if (mo.uvDirtyMax >= mo.uvDirtyMin) {
            val a = mo.uvDirtyMin
            val count = mo.uvDirtyMax - a + 1
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, uvVbo)
            uploadFloats(GLES30.GL_ARRAY_BUFFER, mo.uvs, a * 2, count * 2, a * 8, scratch)
        }
        mo.clearDirty()
        if (model.partsInitialized) {
            val ps = model.parts
            val en = ps.editedNormals
            if (en != null && ps.normalDirtyMax >= ps.normalDirtyMin) {
                val a = ps.normalDirtyMin
                val count = ps.normalDirtyMax - a + 1
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, normalVbo)
                uploadFloats(GLES30.GL_ARRAY_BUFFER, en, a * 3, count * 3, a * 12, scratch)
                ps.normalDirtyMin = Int.MAX_VALUE; ps.normalDirtyMax = -1
            }
        }
        val bb = boneBuffer
        if (bb != null && model.boneTexelsDirty) {
            model.boneTexelsDirty = false
            bb.position(0)
            bb.put(model.boneTexels)
            bb.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, boneTex)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, BONES_PER_ROW * 4, boneTexHeight, GLES30.GL_RGBA, GLES30.GL_FLOAT, bb)
        }
    }

    /** 在 VAO 已绑定的前提下，把索引缓冲绑回模型自己的那个。 */
    fun rebindIndexBuffer() {
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
    }

    fun destroy() {
        GlUtil.deleteVertexArray(vao); vao = 0
        GlUtil.deleteBuffer(posVbo); GlUtil.deleteBuffer(normalVbo); GlUtil.deleteBuffer(uvVbo)
        GlUtil.deleteBuffer(skinVbo); GlUtil.deleteBuffer(sdefVbo); GlUtil.deleteBuffer(colorVbo); GlUtil.deleteBuffer(ibo)
        GlUtil.deleteTexture(boneTex); boneTex = 0
        for (i in tex.indices) {
            tex[i]?.let { textures.release(it) }; tex[i] = null
            sphere[i]?.let { textures.release(it) }; sphere[i] = null
            toon[i]?.let { textures.release(it) }; toon[i] = null
        }
    }

    companion object {
        const val BONES_PER_ROW = 256
    }
}

/**
 * 一次往显卡送的数据最多这么多字节。
 *
 * 安卓上"直接缓冲"也算在应用的堆里：一个几百万顶点的场景要是整块送，暂存区就得跟模型一样大（上百 MB），
 * 模型本身已经占了大半个堆，再来这么一块就撑爆了。分段送的话暂存区只要 1 MB。
 */
internal const val UPLOAD_CHUNK = 1 shl 20

/** 把 [src] 里从 [offset] 起的 [count] 个浮点数分段写进当前绑定的缓冲（从 [byteOffset] 字节处开始）。 */
internal fun uploadFloats(target: Int, src: FloatArray, offset: Int, count: Int, byteOffset: Int, scratch: Scratch) {
    val step = UPLOAD_CHUNK / 4
    var o = 0
    while (o < count) {
        val c = minOf(step, count - o)
        GLES30.glBufferSubData(target, byteOffset + o * 4, c * 4, scratch.floats(src, offset + o, c))
        o += c
    }
}

/** 给当前绑定的缓冲分配 [count] × [stride] 字节，再分段填进去；[fill] 负责写第 i 项的 [stride] 个字节。 */
internal inline fun uploadPacked(target: Int, count: Int, stride: Int, usage: Int, scratch: Scratch, fill: (ByteBuffer, Int) -> Unit) {
    GLES30.glBufferData(target, count * stride, null, usage)
    val step = maxOf(1, UPLOAD_CHUNK / stride)
    var o = 0
    while (o < count) {
        val c = minOf(step, count - o)
        val buf = scratch.bytes(c * stride)
        for (i in o until o + c) fill(buf, i)
        buf.position(0)
        GLES30.glBufferSubData(target, o * stride, c * stride, buf)
        o += c
    }
}

/** 可复用的上传暂存区（直接缓冲），避免每次上传都分配。 */
class Scratch {
    private var buf: ByteBuffer = GlUtil.byteBuffer(1 shl 16)

    private fun ensure(bytes: Int) {
        if (buf.capacity() < bytes) {
            var cap = buf.capacity()
            while (cap < bytes) cap *= 2
            buf = GlUtil.byteBuffer(cap)
        }
    }

    /** 返回一个 position=0、limit=bytes 的字节缓冲供调用方填充。 */
    fun bytes(bytes: Int): ByteBuffer {
        ensure(bytes)
        buf.clear()
        buf.limit(bytes)
        return buf
    }

    fun floats(src: FloatArray, offset: Int, count: Int): FloatBuffer {
        ensure(count * 4)
        buf.clear()
        val fb = buf.asFloatBuffer()
        fb.put(src, offset, count)
        fb.position(0)
        return fb
    }

    fun ints(src: IntArray, offset: Int, count: Int): java.nio.IntBuffer {
        ensure(count * 4)
        buf.clear()
        val ib = buf.asIntBuffer()
        ib.put(src, offset, count)
        ib.position(0)
        return ib
    }

    /** 缓冲太大时释放掉（加载完大模型之后）。 */
    fun trim() {
        if (buf.capacity() > (1 shl 20)) buf = GlUtil.byteBuffer(1 shl 16)
    }
}

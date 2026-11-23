package com.lumi.mmd.engine.render

import android.opengl.GLES30
import com.lumi.mmd.engine.fx.Canvas
import com.lumi.mmd.engine.fx.Effects
import com.lumi.mmd.engine.fx.ScriptMesh
import com.lumi.mmd.engine.fx.SimWorld
import com.lumi.mmd.engine.scene.Camera
import com.lumi.mmd.engine.scene.Light

/** 脚本画布对应的 GPU 贴图：内容变了就重新上传，画布没了就删掉。 */
class CanvasTextures {
    private class Entry(var tex: Int, var version: Int, var repeat: Boolean, var nearest: Boolean)
    private val map = HashMap<Int, Entry>()
    private val scratch = Scratch()
    private val alive = HashSet<Int>()

    fun onContextCreated() { map.clear() }

    /** 每帧在画模型之前调用。 */
    fun sync(fx: Effects) {
        if (fx.canvases.isEmpty() && map.isEmpty()) return
        alive.clear()
        for (c in fx.canvases) {
            alive.add(c.id)
            val e = map[c.id]
            if (e != null && e.version == c.version && e.repeat == c.repeat && e.nearest == c.nearest) continue
            val fresh = e == null
            val en = e ?: Entry(GlUtil.genTexture(), 0, false, false).also { map[c.id] = it }
            upload(c, en, fresh)
        }
        if (map.size > alive.size) {
            val it = map.entries.iterator()
            while (it.hasNext()) { val e = it.next(); if (e.key !in alive) { GlUtil.deleteTexture(e.value.tex); it.remove() } }
        }
    }

    private fun upload(c: Canvas, e: Entry, fresh: Boolean) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, e.tex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val buf = scratch.bytes(c.pixels.size)
        buf.put(c.pixels); buf.position(0)
        if (fresh) GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, c.width, c.height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        else GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, c.width, c.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val wrap = if (c.repeat) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
        if (c.nearest) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        } else {
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        }
        e.version = c.version; e.repeat = c.repeat; e.nearest = c.nearest
    }

    /** 画布的贴图；还没有（或者画布已经没了）返回 0。 */
    fun get(id: Int): Int = map[id]?.tex ?: 0
}

/**
 * 画脚本搭的网格，以及粒子物理世界的几种样子：
 *  - balls：不透明的小球（每个粒子一个「假球」：方片 + 片元里算球面法线和深度）；
 *  - liquid：同样的假球，但先只写深度、再上色，于是只看得到最外面那层融在一起的表面，带菲涅尔和高光；
 *  - points：朝向镜头的贴图方片；
 *  - lines：把距离约束画成线（绳子、布的骨架）。
 */
class ScriptRenderer {
    private class MeshGpu(var vao: Int, var vbo: Int, var ibo: Int) { var version = 0; var indexVersion = 0; var vboCap = 0; var iboCap = 0 }
    private val gpu = HashMap<Int, MeshGpu>()
    private val alive = HashSet<Int>()
    private val scratch = Scratch()
    private var buf = FloatArray(12 * 1024)
    private var meshProgram = 0
    private var ballProgram = 0
    private var uMViewProj = -1; private var uMModel = -1; private var uMColor = -1; private var uMLight = -1; private var uMLightColor = -1
    private var uMEye = -1; private var uMParams = -1; private var uMTex = -1; private var uMPoint = -1
    private var uBView = -1; private var uBProj = -1; private var uBLight = -1; private var uBLightColor = -1; private var uBMode = -1; private var uBTex = -1
    private var ballVao = 0; private var ballVbo = 0; private var ballCap = 0
    private var lineVao = 0; private var lineVbo = 0; private var lineCap = 0
    private val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
    private val lv = FloatArray(3)
    var lastDrawCalls = 0
        private set

    fun onContextCreated() {
        gpu.clear(); ballVao = 0; ballVbo = 0; ballCap = 0; lineVao = 0; lineVbo = 0; lineCap = 0
        meshProgram = GlUtil.linkProgram(MESH_VS, MESH_FS, arrayOf("a_pos", "a_normal", "a_uv", "a_color"))
        ballProgram = GlUtil.linkProgram(BALL_VS, BALL_FS, arrayOf("a_posSize", "a_color"))
        uMViewProj = GLES30.glGetUniformLocation(meshProgram, "u_viewProj"); uMModel = GLES30.glGetUniformLocation(meshProgram, "u_model")
        uMColor = GLES30.glGetUniformLocation(meshProgram, "u_color"); uMLight = GLES30.glGetUniformLocation(meshProgram, "u_light")
        uMLightColor = GLES30.glGetUniformLocation(meshProgram, "u_lightColor"); uMEye = GLES30.glGetUniformLocation(meshProgram, "u_eye")
        uMParams = GLES30.glGetUniformLocation(meshProgram, "u_params"); uMTex = GLES30.glGetUniformLocation(meshProgram, "u_tex")
        uMPoint = GLES30.glGetUniformLocation(meshProgram, "u_point")
        uBView = GLES30.glGetUniformLocation(ballProgram, "u_view"); uBProj = GLES30.glGetUniformLocation(ballProgram, "u_proj")
        uBLight = GLES30.glGetUniformLocation(ballProgram, "u_light"); uBLightColor = GLES30.glGetUniformLocation(ballProgram, "u_lightColor")
        uBMode = GLES30.glGetUniformLocation(ballProgram, "u_mode"); uBTex = GLES30.glGetUniformLocation(ballProgram, "u_tex")
    }

    private fun translucent(m: ScriptMesh): Boolean = m.additive || m.transparent || m.color[3] < 0.999f

    /**
     * 画全部脚本几何。调用前深度测试是开着的；结束时恢复成「混合开、写深度开」的状态。
     * @param tex 把贴图名字换成 GL 贴图（内置粒子图、图片文件、"canvas:编号"）
     */
    fun draw(fx: Effects, cam: Camera, light: Light, tex: (String) -> Int, white: Int) {
        lastDrawCalls = 0
        if (meshProgram == 0) return
        if (fx.meshes.isEmpty() && gpu.isNotEmpty()) prune(fx)
        val anySim = fx.sims.any { it.renderMode != SimWorld.RENDER_NONE && it.count > 0 }
        if (fx.meshes.isEmpty() && !anySim) return
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        // ---- 不透明：网格、小球 ----
        if (fx.meshes.isNotEmpty()) {
            prune(fx)
            beginMeshes(cam, light)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDepthMask(true)
            for (m in fx.meshes) if (m.visible && !translucent(m)) drawMesh(m, tex, white)
        }
        if (anySim) {
            for (w in fx.sims) if (w.renderMode == SimWorld.RENDER_LINES && w.constraintCount > 0) drawLinks(w, cam, light, white)
            beginBalls(cam, light)
            for (w in fx.sims) if (w.renderMode == SimWorld.RENDER_BALLS && w.count > 0) {
                GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDepthMask(true)
                GLES30.glUniform1i(uBMode, 0)
                fillBalls(w, 1f); drawBalls(w.count)
            }
            // ---- 液体：先只写深度，再给最外层表面上色 ----
            for (w in fx.sims) if (w.renderMode == SimWorld.RENDER_LIQUID && w.count > 0) {
                fillBalls(w, 1f)
                GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDepthMask(true)
                GLES30.glColorMask(false, false, false, false)
                GLES30.glUniform1i(uBMode, 0)
                drawBalls(w.count)
                GLES30.glColorMask(true, true, true, true)
                GLES30.glDepthMask(false)
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glUniform1i(uBMode, 1)
                GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, w.count)
                lastDrawCalls++
            }
        }
        // ---- 半透明：网格、贴图粒子 ----
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glDepthMask(false)
        if (fx.meshes.any { it.visible && translucent(it) }) {
            beginMeshes(cam, light)
            for (m in fx.meshes) if (m.visible && translucent(m)) {
                if (m.additive) GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ZERO, GLES30.GL_ONE)
                else GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                drawMesh(m, tex, white)
            }
        }
        if (anySim && fx.sims.any { it.renderMode == SimWorld.RENDER_POINTS && it.count > 0 }) {
            beginBalls(cam, light)
            GLES30.glUniform1i(uBMode, 2)
            for (w in fx.sims) if (w.renderMode == SimWorld.RENDER_POINTS && w.count > 0) {
                if (w.additive) GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ZERO, GLES30.GL_ONE)
                else GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex(w.texture))
                fillBalls(w, 2f); drawBalls(w.count)
            }
        }
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun prune(fx: Effects) {
        if (gpu.isEmpty()) return
        alive.clear()
        for (m in fx.meshes) alive.add(m.id)
        if (gpu.size == alive.size && gpu.keys.all { it in alive }) return
        val it = gpu.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key in alive) continue
            GlUtil.deleteVertexArray(e.value.vao); GlUtil.deleteBuffer(e.value.vbo); GlUtil.deleteBuffer(e.value.ibo)
            it.remove()
        }
    }

    private fun beginMeshes(cam: Camera, light: Light) {
        GLES30.glUseProgram(meshProgram)
        GLES30.glUniformMatrix4fv(uMViewProj, 1, false, cam.viewProj, 0)
        val d = light.direction
        val l = Math.sqrt((d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).toDouble()).toFloat().coerceAtLeast(1e-6f)
        GLES30.glUniform3f(uMLight, -d[0] / l, -d[1] / l, -d[2] / l)
        GLES30.glUniform3f(uMLightColor, light.color[0], light.color[1], light.color[2])
        GLES30.glUniform3f(uMEye, cam.position[0], cam.position[1], cam.position[2])
        GLES30.glUniform1i(uMTex, 0)
    }

    private fun drawMesh(m: ScriptMesh, tex: (String) -> Int, white: Int) {
        if (m.vertexCount == 0) return
        val count = if (m.mode == ScriptMesh.MODE_POINTS && m.indexCount == 0) m.vertexCount else m.indexCount
        if (count == 0) return
        var g = gpu[m.id]
        if (g == null) {
            g = MeshGpu(GlUtil.genVertexArray(), GlUtil.genBuffer(), GlUtil.genBuffer())
            gpu[m.id] = g
            GLES30.glBindVertexArray(g.vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.vbo)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.ibo)
            attribs()
        }
        GLES30.glBindVertexArray(g.vao)
        if (g.version != m.version) {
            g.version = m.version
            val n = m.vertexCount
            if (buf.size < n * 12) buf = FloatArray(n * 12)
            val b = buf
            for (i in 0 until n) {
                val o = i * 12
                b[o] = m.positions[i * 3]; b[o + 1] = m.positions[i * 3 + 1]; b[o + 2] = m.positions[i * 3 + 2]
                b[o + 3] = m.normals[i * 3]; b[o + 4] = m.normals[i * 3 + 1]; b[o + 5] = m.normals[i * 3 + 2]
                b[o + 6] = m.uvs[i * 2]; b[o + 7] = m.uvs[i * 2 + 1]
                b[o + 8] = m.colors[i * 4]; b[o + 9] = m.colors[i * 4 + 1]; b[o + 10] = m.colors[i * 4 + 2]; b[o + 11] = m.colors[i * 4 + 3]
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.vbo)
            val bytes = n * 48
            if (bytes > g.vboCap) { g.vboCap = maxOf(bytes, g.vboCap * 2); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, g.vboCap, null, GLES30.GL_DYNAMIC_DRAW) }
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, bytes, scratch.floats(b, 0, n * 12))
        }
        if (g.indexVersion != m.indexVersion) {
            g.indexVersion = m.indexVersion
            val bytes = m.indexCount * 4
            val bb = scratch.bytes(maxOf(bytes, 4))
            bb.asIntBuffer().put(m.indices, 0, m.indexCount)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.ibo)
            if (bytes > g.iboCap) { g.iboCap = maxOf(bytes, g.iboCap * 2); GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.iboCap, null, GLES30.GL_DYNAMIC_DRAW) }
            if (bytes > 0) GLES30.glBufferSubData(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0, bytes, bb)
        }
        GLES30.glUniformMatrix4fv(uMModel, 1, false, m.world, 0)
        GLES30.glUniform4f(uMColor, m.color[0], m.color[1], m.color[2], m.color[3])
        GLES30.glUniform4f(uMParams, if (m.lit && m.mode == ScriptMesh.MODE_TRIANGLES) 1f else 0f, m.shine, m.glow, 0f)
        GLES30.glUniform1f(uMPoint, m.pointSize)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (m.texture.isEmpty()) white else tex(m.texture))
        if (m.doubleSided || m.mode != ScriptMesh.MODE_TRIANGLES) GLES30.glDisable(GLES30.GL_CULL_FACE) else GLES30.glEnable(GLES30.GL_CULL_FACE)
        when (m.mode) {
            ScriptMesh.MODE_LINES -> GLES30.glDrawElements(GLES30.GL_LINES, m.indexCount, GLES30.GL_UNSIGNED_INT, 0)
            ScriptMesh.MODE_POINTS -> if (m.indexCount > 0) GLES30.glDrawElements(GLES30.GL_POINTS, m.indexCount, GLES30.GL_UNSIGNED_INT, 0) else GLES30.glDrawArrays(GLES30.GL_POINTS, 0, m.vertexCount)
            else -> GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        }
        lastDrawCalls++
    }

    private fun attribs() {
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 48, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 48, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 48, 24)
        GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, 48, 32)
    }

    /** 把距离约束画成线段。 */
    private fun drawLinks(w: SimWorld, cam: Camera, light: Light, white: Int) {
        val n = w.constraintCount
        if (buf.size < n * 24) buf = FloatArray(n * 24)
        val b = buf
        for (c in 0 until n) {
            for (e in 0 until 2) {
                val p = if (e == 0) w.linkA(c) else w.linkB(c)
                val o = (c * 2 + e) * 12
                b[o] = w.pos[p * 3]; b[o + 1] = w.pos[p * 3 + 1]; b[o + 2] = w.pos[p * 3 + 2]
                b[o + 3] = 0f; b[o + 4] = 1f; b[o + 5] = 0f; b[o + 6] = 0f; b[o + 7] = 0f
                b[o + 8] = w.tint[p * 4]; b[o + 9] = w.tint[p * 4 + 1]; b[o + 10] = w.tint[p * 4 + 2]; b[o + 11] = w.tint[p * 4 + 3]
            }
        }
        if (lineVao == 0) {
            lineVao = GlUtil.genVertexArray(); lineVbo = GlUtil.genBuffer()
            GLES30.glBindVertexArray(lineVao); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo); attribs()
        }
        beginMeshes(cam, light)
        GLES30.glBindVertexArray(lineVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)
        val bytes = n * 96
        if (bytes > lineCap) { lineCap = maxOf(bytes, lineCap * 2); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lineCap, null, GLES30.GL_DYNAMIC_DRAW) }
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, bytes, scratch.floats(b, 0, n * 24))
        GLES30.glUniformMatrix4fv(uMModel, 1, false, identity, 0)
        GLES30.glUniform4f(uMColor, w.color[0], w.color[1], w.color[2], w.color[3])
        GLES30.glUniform4f(uMParams, 0f, 0f, 0f, 0f)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, white)
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDepthMask(true)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, n * 2)
        lastDrawCalls++
    }

    private fun beginBalls(cam: Camera, light: Light) {
        if (ballVao == 0) {
            ballVao = GlUtil.genVertexArray(); ballVbo = GlUtil.genBuffer()
            GLES30.glBindVertexArray(ballVao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ballVbo)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 32, 0); GLES30.glVertexAttribDivisor(0, 1)
            GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 32, 16); GLES30.glVertexAttribDivisor(1, 1)
        }
        GLES30.glBindVertexArray(ballVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ballVbo)
        GLES30.glUseProgram(ballProgram)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glUniformMatrix4fv(uBView, 1, false, cam.view, 0)
        GLES30.glUniformMatrix4fv(uBProj, 1, false, cam.proj, 0)
        // 光的方向换到视图空间
        val d = light.direction; val v = cam.view
        lv[0] = -(v[0] * d[0] + v[4] * d[1] + v[8] * d[2]); lv[1] = -(v[1] * d[0] + v[5] * d[1] + v[9] * d[2]); lv[2] = -(v[2] * d[0] + v[6] * d[1] + v[10] * d[2])
        val l = Math.sqrt((lv[0] * lv[0] + lv[1] * lv[1] + lv[2] * lv[2]).toDouble()).toFloat().coerceAtLeast(1e-6f)
        GLES30.glUniform3f(uBLight, lv[0] / l, lv[1] / l, lv[2] / l)
        GLES30.glUniform3f(uBLightColor, light.color[0], light.color[1], light.color[2])
        GLES30.glUniform1i(uBTex, 0)
    }

    private fun fillBalls(w: SimWorld, sizeScale: Float) {
        val n = w.count
        if (buf.size < n * 8) buf = FloatArray(n * 8)
        val b = buf
        // 液体默认画得比粒子本身大一些，相邻的球才会融成一片
        val fixed = if (w.renderRadius > 0f) w.renderRadius else if (w.renderMode == SimWorld.RENDER_LIQUID) w.spacing * 0.95f else 0f
        for (i in 0 until n) {
            val o = i * 8
            b[o] = w.pos[i * 3]; b[o + 1] = w.pos[i * 3 + 1]; b[o + 2] = w.pos[i * 3 + 2]
            b[o + 3] = (if (fixed > 0f) fixed else w.radius[i]) * sizeScale
            b[o + 4] = w.color[0] * w.tint[i * 4]; b[o + 5] = w.color[1] * w.tint[i * 4 + 1]; b[o + 6] = w.color[2] * w.tint[i * 4 + 2]; b[o + 7] = w.color[3] * w.tint[i * 4 + 3]
        }
        val bytes = n * 32
        if (bytes > ballCap) { ballCap = maxOf(bytes, ballCap * 2, 32 * 512); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, ballCap, null, GLES30.GL_DYNAMIC_DRAW) }
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, bytes, scratch.floats(b, 0, n * 8))
    }

    private fun drawBalls(n: Int) {
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, n)
        lastDrawCalls++
    }

    companion object {
        private const val MESH_VS = """#version 300 es
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec3 a_normal;
layout(location = 2) in vec2 a_uv;
layout(location = 3) in vec4 a_color;
uniform mat4 u_viewProj;
uniform mat4 u_model;
uniform float u_point;
out vec3 v_pos;
out vec3 v_normal;
out vec2 v_uv;
out vec4 v_color;
void main() {
    vec4 w = u_model * vec4(a_pos, 1.0);
    v_pos = w.xyz;
    v_normal = mat3(u_model) * a_normal;
    v_uv = a_uv;
    v_color = a_color;
    gl_Position = u_viewProj * w;
    gl_PointSize = u_point;
}
"""
        private const val MESH_FS = """#version 300 es
precision highp float;
in vec3 v_pos;
in vec3 v_normal;
in vec2 v_uv;
in vec4 v_color;
uniform sampler2D u_tex;
uniform vec4 u_color;
uniform vec3 u_light;
uniform vec3 u_lightColor;
uniform vec3 u_eye;
uniform vec4 u_params;   // x: 受光  y: 高光  z: 发光
out vec4 fragColor;
void main() {
    vec4 base = texture(u_tex, v_uv) * u_color * v_color;
    if (base.a <= 0.004) discard;
    vec3 c = base.rgb;
    if (u_params.x > 0.5) {
        vec3 n = normalize(v_normal);
        if (!gl_FrontFacing) n = -n;
        vec3 v = normalize(u_eye - v_pos);
        float d = max(dot(n, u_light), 0.0);
        // 和模型一样偏卡通的光照：暗部不会全黑
        vec3 lit = base.rgb * (vec3(0.5) + u_lightColor * (0.25 + 0.75 * d));
        float s = pow(max(dot(n, normalize(u_light + v)), 0.0), 48.0) * u_params.y;
        c = mix(min(lit, vec3(1.0)) + vec3(s), base.rgb, clamp(u_params.z, 0.0, 1.0));
    }
    fragColor = vec4(c, base.a);
}
"""
        private const val BALL_VS = """#version 300 es
layout(location = 0) in vec4 a_posSize;
layout(location = 1) in vec4 a_color;
uniform mat4 u_view;
uniform mat4 u_proj;
out vec2 v_local;
out vec3 v_center;
out float v_radius;
out vec4 v_color;
void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1)) * 2.0 - 1.0;
    vec3 center = (u_view * vec4(a_posSize.xyz, 1.0)).xyz;
    v_local = c;
    v_center = center;
    v_radius = a_posSize.w;
    v_color = a_color;
    gl_Position = u_proj * vec4(center + vec3(c * a_posSize.w, 0.0), 1.0);
}
"""
        private const val BALL_FS = """#version 300 es
precision highp float;
in vec2 v_local;
in vec3 v_center;
in float v_radius;
in vec4 v_color;
uniform mat4 u_proj;
uniform vec3 u_light;
uniform vec3 u_lightColor;
uniform int u_mode;      // 0: 不透明小球  1: 液体表面  2: 贴图方片
uniform sampler2D u_tex;
out vec4 fragColor;
void main() {
    if (u_mode == 2) {
        vec4 t = texture(u_tex, v_local * vec2(0.5, -0.5) + 0.5) * v_color;
        if (t.a <= 0.003) discard;
        fragColor = t;
        gl_FragDepth = gl_FragCoord.z;
        return;
    }
    float r2 = dot(v_local, v_local);
    if (r2 > 1.0) discard;
    // 视图空间里 +z 朝前，所以球面朝镜头的一侧 z 更小
    vec3 n = vec3(v_local, -sqrt(1.0 - r2));
    vec3 p = v_center + n * v_radius;
    vec4 clip = u_proj * vec4(p, 1.0);
    gl_FragDepth = clamp(clip.z / clip.w * 0.5 + 0.5, 0.0, 1.0);
    vec3 v = normalize(-p);
    if (u_mode == 1) {
        // 相邻的球交界处法线会突变；往镜头方向收一点，表面看起来更连贯
        n = normalize(mix(n, vec3(0.0, 0.0, -1.0), 0.35));
        float d = max(dot(n, u_light), 0.0);
        float f = pow(1.0 - max(dot(n, v), 0.0), 3.0);
        float s = pow(max(dot(n, normalize(u_light + v)), 0.0), 80.0);
        vec3 c = v_color.rgb * (0.55 + 0.45 * d) + vec3(0.85, 0.93, 1.0) * f * 0.55 + u_lightColor * s * 1.2;
        fragColor = vec4(c, clamp(v_color.a + f * 0.5 + s, 0.0, 1.0));
    } else {
        float d = max(dot(n, u_light), 0.0);
        float s = pow(max(dot(n, normalize(u_light + v)), 0.0), 40.0) * 0.35;
        fragColor = vec4(min(v_color.rgb * (vec3(0.5) + u_lightColor * (0.25 + 0.75 * d)), vec3(1.0)) + vec3(s), 1.0);
    }
}
"""
    }
}

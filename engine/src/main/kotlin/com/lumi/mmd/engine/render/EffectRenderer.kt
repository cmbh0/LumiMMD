package com.lumi.mmd.engine.render

import android.opengl.GLES30
import com.lumi.mmd.engine.fx.EffectTextures
import com.lumi.mmd.engine.fx.Effects
import com.lumi.mmd.engine.model.TextureRef
import com.lumi.mmd.engine.scene.Camera
import java.io.File
import kotlin.math.sqrt

/**
 * 画特效：粒子和贴片用实例化的"朝向镜头的方片"，拖尾用三角带，整屏的闪光 / 染色用一个全屏三角形。
 * 都是在模型画完之后画：要和模型比深度（被挡住的不画），但自己不写深度。
 */
class EffectRenderer {
    private var program = 0
    private var trailProgram = 0
    private var flatProgram = 0
    private var vao = 0
    private var vbo = 0
    private var vboCap = 0
    private var trailVao = 0
    private var trailVbo = 0
    private var trailCap = 0
    private var emptyVao = 0
    private val builtin = HashMap<String, Int>()
    private val files = HashMap<String, GlTexture>()
    private var data = FloatArray(9 * 1024)
    private var tdata = FloatArray(9 * 2 * 96)
    private val scratch = Scratch()
    private val color = FloatArray(4)
    private var uViewProj = -1; private var uRight = -1; private var uUp = -1; private var uTex = -1
    private var uTrailViewProj = -1; private var uFlatColor = -1

    /** 特效贴图用图片文件时，到哪里去找（资源库的根目录）。 */
    var imageRoot: File? = null

    var lastDrawCalls = 0
        private set

    fun onContextCreated() {
        builtin.clear(); files.clear()
        vao = 0; vbo = 0; vboCap = 0; trailVao = 0; trailVbo = 0; trailCap = 0
        program = GlUtil.linkProgram(VS, FS, arrayOf("a_posSize", "a_color", "a_rot"))
        trailProgram = GlUtil.linkProgram(TRAIL_VS, TRAIL_FS, arrayOf("a_pos", "a_uv", "a_color"))
        flatProgram = GlUtil.linkProgram(FLAT_VS, FLAT_FS, arrayOf())
        uViewProj = GLES30.glGetUniformLocation(program, "u_viewProj")
        uRight = GLES30.glGetUniformLocation(program, "u_right")
        uUp = GLES30.glGetUniformLocation(program, "u_up")
        uTex = GLES30.glGetUniformLocation(program, "u_tex")
        uTrailViewProj = GLES30.glGetUniformLocation(trailProgram, "u_viewProj")
        uFlatColor = GLES30.glGetUniformLocation(flatProgram, "u_color")
        emptyVao = GlUtil.genVertexArray()
        canvases.onContextCreated()
        scripted.onContextCreated()
    }

    /** 脚本画布的贴图。 */
    val canvases = CanvasTextures()
    /** 脚本搭的网格、粒子物理世界。 */
    val scripted = ScriptRenderer()

    /** 把贴图名字换成 GL 贴图："canvas:编号" 是脚本画的，内置名字是公式生成的，其余当作资源库里的图片。 */
    fun texture(name: String, textures: TextureManager): Int {
        if (name.startsWith(CANVAS_PREFIX)) {
            val id = canvases.get(name.substring(CANVAS_PREFIX.length).toIntOrNull() ?: 0)
            return if (id != 0) id else textures.white
        }
        builtin[name]?.let { return it }
        if (EffectTextures.has(name)) {
            val size = 64
            val px = EffectTextures.generate(name, size)!!
            val id = GlUtil.genTexture()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            val buf = scratch.bytes(px.size)
            buf.put(px); buf.position(0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, size, size, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            builtin[name] = id
            return id
        }
        // 图片文件：交给贴图管理器在后台解码，没好之前先用柔光点顶着
        val root = imageRoot
        if (root != null && files.size < 32) {
            val t = files.getOrPut(name) { textures.acquire(TextureRef(name), root, 0) }
            if (t.id != 0) return t.id
        } else files[name]?.let { if (it.id != 0) return it.id }
        return texture("soft", textures)
    }

    private fun ensureData(floats: Int) {
        if (data.size < floats) data = FloatArray(maxOf(floats, data.size * 2))
    }

    private fun setBlend(additive: Boolean) {
        if (additive) GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ZERO, GLES30.GL_ONE)
        else GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun drawInstances(n: Int) {
        if (n <= 0) return
        val bytes = n * 36
        if (bytes > vboCap) {
            vboCap = maxOf(bytes, vboCap * 2, 36 * 512)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vboCap, null, GLES30.GL_DYNAMIC_DRAW)
        }
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, bytes, scratch.floats(data, 0, n * 9))
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, n)
        lastDrawCalls++
    }

    /** 画粒子、贴片、拖尾。调用前深度测试应当是开着的。 */
    fun draw(fx: Effects, cam: Camera, textures: TextureManager) {
        lastDrawCalls = 0
        if (program == 0 || (fx.emitters.isEmpty() && fx.sprites.isEmpty() && fx.trails.isEmpty())) return
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // ---- 拖尾 ----
        if (fx.trails.any { it.pointCount >= 2 }) drawTrails(fx, cam)

        // ---- 粒子 / 贴片 ----
        if (vao == 0) {
            vao = GlUtil.genVertexArray()
            vbo = GlUtil.genBuffer()
            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 36, 0); GLES30.glVertexAttribDivisor(0, 1)
            GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 36, 16); GLES30.glVertexAttribDivisor(1, 1)
            GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, 36, 32); GLES30.glVertexAttribDivisor(2, 1)
        }
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uViewProj, 1, false, cam.viewProj, 0)
        GLES30.glUniform3f(uRight, cam.right[0], cam.right[1], cam.right[2])
        GLES30.glUniform3f(uUp, cam.up[0], cam.up[1], cam.up[2])
        GLES30.glUniform1i(uTex, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        for (e in fx.emitters) {
            val n = e.count
            if (n == 0) continue
            ensureData(n * 9)
            val d = data
            val cs = e.colorStart; val ce = e.colorEnd
            for (i in 0 until n) {
                val t = (e.age[i] / e.life[i]).coerceIn(0f, 1f)
                val o = i * 9
                d[o] = e.p[i * 3]; d[o + 1] = e.p[i * 3 + 1]; d[o + 2] = e.p[i * 3 + 2]
                d[o + 3] = (e.sizeStart + (e.sizeEnd - e.sizeStart) * t) * e.scale[i]
                d[o + 4] = cs[0] + (ce[0] - cs[0]) * t; d[o + 5] = cs[1] + (ce[1] - cs[1]) * t
                d[o + 6] = cs[2] + (ce[2] - cs[2]) * t; d[o + 7] = cs[3] + (ce[3] - cs[3]) * t
                d[o + 8] = e.rot[i]
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture(e.texture, textures))
            setBlend(e.additive)
            drawInstances(n)
        }
        for (s in fx.sprites) {
            if (!s.visible || s.color[3] <= 0f || s.size <= 0f) continue
            val d = data
            d[0] = s.world[0]; d[1] = s.world[1]; d[2] = s.world[2]; d[3] = s.size
            d[4] = s.color[0]; d[5] = s.color[1]; d[6] = s.color[2]; d[7] = s.color[3]
            d[8] = s.rotation * (Math.PI / 180.0).toFloat()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture(s.texture, textures))
            setBlend(s.additive)
            drawInstances(1)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun drawTrails(fx: Effects, cam: Camera) {
        if (trailVao == 0) {
            trailVao = GlUtil.genVertexArray()
            trailVbo = GlUtil.genBuffer()
            GLES30.glBindVertexArray(trailVao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, trailVbo)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 36, 0)
            GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 36, 12)
            GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, 36, 20)
        }
        GLES30.glBindVertexArray(trailVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, trailVbo)
        GLES30.glUseProgram(trailProgram)
        GLES30.glUniformMatrix4fv(uTrailViewProj, 1, false, cam.viewProj, 0)
        val cx = cam.position[0]; val cy = cam.position[1]; val cz = cam.position[2]
        for (t in fx.trails) {
            val n = t.pointCount
            if (n < 2) continue
            if (tdata.size < n * 18) tdata = FloatArray(n * 18)
            val d = tdata
            val p = t.pts
            for (i in 0 until n) {
                val a = maxOf(i - 1, 0) * 3; val b = minOf(i + 1, n - 1) * 3
                val tx = p[b] - p[a]; val ty = p[b + 1] - p[a + 1]; val tz = p[b + 2] - p[a + 2]
                val o = i * 3
                val vx = cx - p[o]; val vy = cy - p[o + 1]; val vz = cz - p[o + 2]
                // 侧向 = 切线 × 视线
                var sx = ty * vz - tz * vy; var sy = tz * vx - tx * vz; var sz = tx * vy - ty * vx
                val l = sqrt(sx * sx + sy * sy + sz * sz)
                if (l > 1e-6f) { sx /= l; sy /= l; sz /= l } else { sx = cam.up[0]; sy = cam.up[1]; sz = cam.up[2] }
                // 越老的点越靠近尾巴：u = 0 是尾巴，1 是头
                val u = i.toFloat() / (n - 1)
                val age = ((fx.time - t.born[i]) / maxOf(t.life, 0.01f)).coerceIn(0f, 1f)
                val w = t.width * 0.5f * (0.25f + 0.75f * (1f - age))
                for (k in 0 until 4) color[k] = t.colorStart[k] + (t.colorEnd[k] - t.colorStart[k]) * age
                val q = i * 18
                d[q] = p[o] + sx * w; d[q + 1] = p[o + 1] + sy * w; d[q + 2] = p[o + 2] + sz * w
                d[q + 3] = u; d[q + 4] = 0f
                d[q + 5] = color[0]; d[q + 6] = color[1]; d[q + 7] = color[2]; d[q + 8] = color[3]
                d[q + 9] = p[o] - sx * w; d[q + 10] = p[o + 1] - sy * w; d[q + 11] = p[o + 2] - sz * w
                d[q + 12] = u; d[q + 13] = 1f
                d[q + 14] = color[0]; d[q + 15] = color[1]; d[q + 16] = color[2]; d[q + 17] = color[3]
            }
            val bytes = n * 72
            if (bytes > trailCap) {
                trailCap = maxOf(bytes, trailCap * 2, 72 * 96)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, trailCap, null, GLES30.GL_DYNAMIC_DRAW)
            }
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, bytes, scratch.floats(d, 0, n * 18))
            setBlend(t.additive)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, n * 2)
            lastDrawCalls++
        }
    }

    /** 整屏盖一层颜色（闪光、转场）。在后处理之前、深度测试关掉的状态下调用。 */
    fun drawOverlay(r: Float, g: Float, b: Float, a: Float) {
        if (flatProgram == 0 || a <= 0f) return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(flatProgram)
        GLES30.glUniform4f(uFlatColor, r, g, b, a)
        GLES30.glBindVertexArray(emptyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    companion object {
        const val CANVAS_PREFIX = "canvas:"
        private const val VS = """#version 300 es
layout(location = 0) in vec4 a_posSize;
layout(location = 1) in vec4 a_color;
layout(location = 2) in float a_rot;
uniform mat4 u_viewProj;
uniform vec3 u_right;
uniform vec3 u_up;
out vec2 v_uv;
out vec4 v_color;
void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    vec2 o = c - 0.5;
    float s = sin(a_rot), k = cos(a_rot);
    vec2 r = vec2(o.x * k - o.y * s, o.x * s + o.y * k) * a_posSize.w;
    vec3 p = a_posSize.xyz + u_right * r.x + u_up * r.y;
    gl_Position = u_viewProj * vec4(p, 1.0);
    v_uv = vec2(c.x, 1.0 - c.y);
    v_color = a_color;
}
"""
        private const val FS = """#version 300 es
precision mediump float;
in vec2 v_uv;
in vec4 v_color;
uniform sampler2D u_tex;
out vec4 fragColor;
void main() {
    vec4 t = texture(u_tex, v_uv);
    vec4 c = t * v_color;
    if (c.a <= 0.003) discard;
    fragColor = c;
}
"""
        private const val TRAIL_VS = """#version 300 es
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec2 a_uv;
layout(location = 2) in vec4 a_color;
uniform mat4 u_viewProj;
out vec2 v_uv;
out vec4 v_color;
void main() {
    gl_Position = u_viewProj * vec4(a_pos, 1.0);
    v_uv = a_uv;
    v_color = a_color;
}
"""
        private const val TRAIL_FS = """#version 300 es
precision mediump float;
in vec2 v_uv;
in vec4 v_color;
out vec4 fragColor;
void main() {
    // 两边淡出，中间最亮
    float e = 1.0 - abs(v_uv.y * 2.0 - 1.0);
    float a = v_color.a * smoothstep(0.0, 0.7, e) * smoothstep(0.0, 0.15, v_uv.x);
    if (a <= 0.003) discard;
    fragColor = vec4(v_color.rgb, a);
}
"""
        private const val FLAT_VS = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""
        private const val FLAT_FS = """#version 300 es
precision mediump float;
uniform vec4 u_color;
out vec4 fragColor;
void main() { fragColor = u_color; }
"""
    }
}

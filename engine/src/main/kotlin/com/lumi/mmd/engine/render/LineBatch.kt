package com.lumi.mmd.engine.render

import android.opengl.GLES30
import com.lumi.mmd.engine.math.Mat4
import kotlin.math.sqrt

/**
 * 辅助线 / 点的批量绘制（骨骼、操纵轴、刚体线框……）。
 * 移动端 GL 的 glLineWidth 基本只支持 1 像素，所以这里在 CPU 上把线段展开成屏幕空间的细长四边形，
 * 粗细以像素为单位、与距离无关。
 */
class LineBatch {
    // 每条线 12 个 float：x0,y0,z0, x1,y1,z1, r,g,b,a, width, depthTest
    private var lines = FloatArray(12 * 256)
    private var lineCount = 0
    // 每个点 9 个 float：x,y,z, r,g,b,a, size, depthTest
    private var points = FloatArray(9 * 128)
    private var pointCount = 0

    // 每个三角形 14 个 float：三个顶点 xyz, r,g,b,a, depthTest
    private var tris = FloatArray(14 * 64)
    private var triCount = 0

    private var verts = FloatArray(7 * 6 * 256)
    private var vao = 0
    private var vbo = 0
    private var vboCapacity = 0
    private val scratch = Scratch()
    private val identity = FloatArray(16).also { Mat4.identity(it, 0) }
    private val c = FloatArray(8)
    private var biasPersp = false
    private var biasA = 0f

    /** 把参与深度测试的辅助图形的 NDC 深度往相机方向偏一点。 */
    private fun biased(z: Float): Float = if (biasPersp) z + (z - biasA) * DEPTH_BIAS else z - DEPTH_BIAS * 0.05f

    val isEmpty: Boolean get() = lineCount == 0 && pointCount == 0 && triCount == 0

    fun onContextCreated() { vao = 0; vbo = 0; vboCapacity = 0 }

    fun clear() { lineCount = 0; pointCount = 0; triCount = 0 }

    /** 实心三角形（世界坐标）：箭头、选中面的高亮等。 */
    fun tri(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
            r: Float, g: Float, b: Float, a: Float, depthTest: Boolean = false) {
        if ((triCount + 1) * 14 > tris.size) tris = tris.copyOf(tris.size * 2)
        val o = triCount * 14
        val t = tris
        t[o] = x0; t[o + 1] = y0; t[o + 2] = z0; t[o + 3] = x1; t[o + 4] = y1; t[o + 5] = z1; t[o + 6] = x2; t[o + 7] = y2; t[o + 8] = z2
        t[o + 9] = r; t[o + 10] = g; t[o + 11] = b; t[o + 12] = a; t[o + 13] = if (depthTest) 1f else 0f
        triCount++
    }

    fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, g: Float, b: Float, a: Float, width: Float = 2f, depthTest: Boolean = false) {
        if ((lineCount + 1) * 12 > lines.size) lines = lines.copyOf(lines.size * 2)
        val o = lineCount * 12
        val l = lines
        l[o] = x0; l[o + 1] = y0; l[o + 2] = z0; l[o + 3] = x1; l[o + 4] = y1; l[o + 5] = z1
        l[o + 6] = r; l[o + 7] = g; l[o + 8] = b; l[o + 9] = a; l[o + 10] = width; l[o + 11] = if (depthTest) 1f else 0f
        lineCount++
    }

    fun point(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float, size: Float = 8f, depthTest: Boolean = false) {
        if ((pointCount + 1) * 9 > points.size) points = points.copyOf(points.size * 2)
        val o = pointCount * 9
        val p = points
        p[o] = x; p[o + 1] = y; p[o + 2] = z; p[o + 3] = r; p[o + 4] = g; p[o + 5] = b; p[o + 6] = a; p[o + 7] = size; p[o + 8] = if (depthTest) 1f else 0f
        pointCount++
    }

    /** 圆（由线段拼成）：圆心 + 两个互相垂直的单位轴。 */
    fun circle(cx: Float, cy: Float, cz: Float, ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float, radius: Float,
               r: Float, g: Float, b: Float, a: Float, width: Float = 2f, segments: Int = 40, depthTest: Boolean = false) {
        var px = cx + ux * radius; var py = cy + uy * radius; var pz = cz + uz * radius
        for (i in 1..segments) {
            val ang = (i.toFloat() / segments) * (Math.PI * 2.0).toFloat()
            val cs = kotlin.math.cos(ang) * radius; val sn = kotlin.math.sin(ang) * radius
            val nx = cx + ux * cs + vx * sn; val ny = cy + uy * cs + vy * sn; val nz = cz + uz * cs + vz * sn
            line(px, py, pz, nx, ny, nz, r, g, b, a, width, depthTest)
            px = nx; py = ny; pz = nz
        }
    }

    private fun ensureVerts(count: Int) {
        if (count * 7 > verts.size) {
            var n = verts.size
            while (n < count * 7) n *= 2
            verts = verts.copyOf(n)
        }
    }

    private fun put(i: Int, x: Float, y: Float, z: Float, o: Int, src: FloatArray): Int {
        val v = verts
        val k = i * 7
        v[k] = x; v[k + 1] = y; v[k + 2] = z
        v[k + 3] = src[o]; v[k + 4] = src[o + 1]; v[k + 5] = src[o + 2]; v[k + 6] = src[o + 3]
        return i + 1
    }

    /** 画出并清空。需要已绑定目标帧缓冲、设置好视口。 */
    fun draw(program: Int, viewProj: FloatArray, width: Int, height: Int) {
        if (isEmpty) return
        // emitLine / emitPoint 各写 6 个顶点，emitTri 写 3 个
        ensureVerts((lineCount + pointCount) * 6 + triCount * 3)
        // 参与深度测试的线要往相机方向挪一点点，不然和它所贴着的面互相遮挡（闪烁）。
        // 透视投影下 z_ndc = A + B / d（d 是到相机的深度），把 d 缩小 ε 相当于 z_ndc += (z_ndc - A)·ε。
        var j = 0
        for (k in 1 until 3) if (kotlin.math.abs(viewProj[3 + 4 * k]) > kotlin.math.abs(viewProj[3 + 4 * j])) j = k
        if (kotlin.math.abs(viewProj[3 + 4 * j]) > 1e-6f) { biasPersp = true; biasA = viewProj[2 + 4 * j] / viewProj[3 + 4 * j] }
        else { biasPersp = false; biasA = 0f }
        // 两层：先画参与深度测试的，再画始终在最上层的
        var n = 0
        var split = 0
        for (layer in 0 until 2) {
            val wantDepth = if (layer == 0) 1f else 0f
            // 三角形先画，线和点压在上面
            for (i in 0 until triCount) {
                val o = i * 14
                if (tris[o + 13] != wantDepth) continue
                n = emitTri(n, o, viewProj, layer == 0)
            }
            for (i in 0 until lineCount) {
                val o = i * 12
                if (lines[o + 11] != wantDepth) continue
                n = emitLine(n, o, viewProj, width, height, layer == 0)
            }
            for (i in 0 until pointCount) {
                val o = i * 9
                if (points[o + 8] != wantDepth) continue
                n = emitPoint(n, o, viewProj, width, height, layer == 0)
            }
            if (layer == 0) split = n
        }
        clear()
        if (n == 0) return
        if (vao == 0) {
            vao = GlUtil.genVertexArray()
            vbo = GlUtil.genBuffer()
            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 28, 0)
            GLES30.glEnableVertexAttribArray(9)
            GLES30.glVertexAttribPointer(9, 4, GLES30.GL_FLOAT, false, 28, 12)
        }
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val bytes = n * 28
        if (bytes > vboCapacity) {
            vboCapacity = maxOf(bytes, vboCapacity * 2, 28 * 1024)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vboCapacity, null, GLES30.GL_DYNAMIC_DRAW)
        }
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, bytes, scratch.floats(verts, 0, n * 7))
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "u_viewProj"), 1, false, identity, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "u_pointSize"), 1f)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glDepthMask(false)
        if (split > 0) {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, split)
        }
        if (n > split) {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, split, n - split)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }
        GLES30.glDepthMask(true)
        GLES30.glBindVertexArray(0)
    }

    private fun emitLine(start: Int, o: Int, vp: FloatArray, width: Int, height: Int, depth: Boolean): Int {
        val l = lines
        Mat4.transformVec4(vp, 0, l[o], l[o + 1], l[o + 2], 1f, c, 0)
        Mat4.transformVec4(vp, 0, l[o + 3], l[o + 4], l[o + 5], 1f, c, 4)
        // 近平面裁剪（w > ε）
        val eps = 1e-3f
        if (c[3] < eps && c[7] < eps) return start
        if (c[3] < eps) {
            val t = (eps - c[3]) / (c[7] - c[3])
            for (k in 0 until 4) c[k] = c[k] + (c[4 + k] - c[k]) * t
        } else if (c[7] < eps) {
            val t = (eps - c[7]) / (c[3] - c[7])
            for (k in 0 until 4) c[4 + k] = c[4 + k] + (c[k] - c[4 + k]) * t
        }
        val ax = c[0] / c[3]; val ay = c[1] / c[3]; val az = if (depth) biased(c[2] / c[3]) else 0f
        val bx = c[4] / c[7]; val by = c[5] / c[7]; val bz = if (depth) biased(c[6] / c[7]) else 0f
        // 屏幕空间的垂直方向
        var dx = (bx - ax) * width; var dy = (by - ay) * height
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-4f) return start
        dx /= len; dy /= len
        val hw = l[o + 10] * 0.5f
        val px = -dy * hw * 2f / width
        val py = dx * hw * 2f / height
        var n = start
        n = put(n, ax + px, ay + py, az, o + 6, l)
        n = put(n, ax - px, ay - py, az, o + 6, l)
        n = put(n, bx + px, by + py, bz, o + 6, l)
        n = put(n, bx + px, by + py, bz, o + 6, l)
        n = put(n, ax - px, ay - py, az, o + 6, l)
        n = put(n, bx - px, by - py, bz, o + 6, l)
        return n
    }

    private fun emitTri(start: Int, o: Int, vp: FloatArray, depth: Boolean): Int {
        val t = tris
        var n = start
        // 有顶点落在相机后面就整个丢掉（辅助图形，不值得做裁剪）
        for (k in 0 until 3) {
            Mat4.transformVec4(vp, 0, t[o + k * 3], t[o + k * 3 + 1], t[o + k * 3 + 2], 1f, c, 0)
            if (c[3] < 1e-3f) return start
            n = put(n, c[0] / c[3], c[1] / c[3], if (depth) biased(c[2] / c[3]) else 0f, o + 9, t)
        }
        return n
    }

    private fun emitPoint(start: Int, o: Int, vp: FloatArray, width: Int, height: Int, depth: Boolean): Int {
        val p = points
        Mat4.transformVec4(vp, 0, p[o], p[o + 1], p[o + 2], 1f, c, 0)
        if (c[3] < 1e-3f) return start
        val x = c[0] / c[3]; val y = c[1] / c[3]; val z = if (depth) biased(c[2] / c[3]) else 0f
        val hx = p[o + 7] / width
        val hy = p[o + 7] / height
        var n = start
        // 菱形
        n = put(n, x - hx, y, z, o + 3, p)
        n = put(n, x, y - hy, z, o + 3, p)
        n = put(n, x + hx, y, z, o + 3, p)
        n = put(n, x + hx, y, z, o + 3, p)
        n = put(n, x, y + hy, z, o + 3, p)
        n = put(n, x - hx, y, z, o + 3, p)
        return n
    }

    companion object {
        /** 深度偏移量：相当于把辅助图形往相机方向拉近 0.4%。 */
        private const val DEPTH_BIAS = 0.004f
    }
}

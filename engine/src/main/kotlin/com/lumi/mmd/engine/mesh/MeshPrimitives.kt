package com.lumi.mmd.engine.mesh

import com.lumi.mmd.engine.math.Mat4
import com.lumi.mmd.engine.util.IntList
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 基础形体。都往已有的 [EditMesh] 里追加，返回新建的面；xf 是 4x4 变换矩阵（null = 不变换），
 * 形体默认以原点为中心。面的朝向统一由"应该朝外的方向"校正，所以不用操心每个面顶点的绕序。
 */
object MeshPrimitives {
    private class Ctx(val m: EditMesh, val material: Int, val xf: FloatArray?, val smooth: Boolean) {
        val faces = IntList(64)
        private val t = FloatArray(6)
        /** 变换矩阵带镜像（行列式为负）时，朝外的方向不变但绕序会反：统一靠法线校正，所以这里只变换位置。 */
        fun point(x: Float, y: Float, z: Float): Int {
            if (xf == null) return m.addPoint(x, y, z)
            Mat4.transformPoint(xf, 0, x, y, z, t, 0)
            return m.addPoint(t[0], t[1], t[2])
        }

        /** pts / uv 按任意绕序给出，(ox,oy,oz) 是（变换前）希望法线朝向的方向。 */
        fun face(pts: IntArray, uv: FloatArray, ox: Float, oy: Float, oz: Float, smoothFace: Boolean = smooth) {
            val n = pts.size
            var dx = ox; var dy = oy; var dz = oz
            if (xf != null) { Mat4.transformDir(xf, 0, ox, oy, oz, t, 3); dx = t[3]; dy = t[4]; dz = t[5] }
            val f = m.addFace(pts, n, material, smoothFace)
            m.faceNormalRaw(f, t)
            val s = m.fStart[f]
            if (t[0] * dx + t[1] * dy + t[2] * dz < 0f) {
                for (i in 0 until n) { m.cPoint[s + i] = pts[n - 1 - i]; m.cUV[(s + i) * 2] = uv[(n - 1 - i) * 2]; m.cUV[(s + i) * 2 + 1] = uv[(n - 1 - i) * 2 + 1] }
            } else {
                for (i in 0 until n) { m.cUV[(s + i) * 2] = uv[i * 2]; m.cUV[(s + i) * 2 + 1] = uv[i * 2 + 1] }
            }
            faces.add(f)
        }
    }

    /** 长方体：宽 w（X）、高 h（Y）、深 d（Z）。 */
    fun box(m: EditMesh, w: Float, h: Float, d: Float, material: Int = 0, xf: FloatArray? = null): IntArray {
        val c = Ctx(m, material, xf, false)
        val x = w * 0.5f; val y = h * 0.5f; val z = d * 0.5f
        val p = IntArray(8) { c.point(if (it and 1 == 0) -x else x, if (it and 2 == 0) -y else y, if (it and 4 == 0) -z else z) }
        val uv = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f)
        c.face(intArrayOf(p[0], p[1], p[3], p[2]), uv, 0f, 0f, -1f)   // 前（-Z，模型的正面）
        c.face(intArrayOf(p[5], p[4], p[6], p[7]), uv, 0f, 0f, 1f)    // 后
        c.face(intArrayOf(p[4], p[0], p[2], p[6]), uv, -1f, 0f, 0f)   // -X
        c.face(intArrayOf(p[1], p[5], p[7], p[3]), uv, 1f, 0f, 0f)    // +X
        c.face(intArrayOf(p[2], p[3], p[7], p[6]), uv, 0f, 1f, 0f)    // 上
        c.face(intArrayOf(p[4], p[5], p[1], p[0]), uv, 0f, -1f, 0f)   // 下
        return c.faces.toArray()
    }

    /** 平面（朝上）：宽 w（X）、深 d（Z），分成 sx × sz 格。 */
    fun plane(m: EditMesh, w: Float, d: Float, sx: Int = 1, sz: Int = 1, material: Int = 0, xf: FloatArray? = null): IntArray {
        val c = Ctx(m, material, xf, true)
        val nx = sx.coerceIn(1, 256); val nz = sz.coerceIn(1, 256)
        val p = IntArray((nx + 1) * (nz + 1))
        for (j in 0..nz) for (i in 0..nx) p[j * (nx + 1) + i] = c.point(-w * 0.5f + w * i / nx, 0f, -d * 0.5f + d * j / nz)
        for (j in 0 until nz) for (i in 0 until nx) {
            val a = j * (nx + 1) + i
            val u0 = i.toFloat() / nx; val u1 = (i + 1f) / nx; val v0 = 1f - j.toFloat() / nz; val v1 = 1f - (j + 1f) / nz
            c.face(intArrayOf(p[a], p[a + 1], p[a + nx + 2], p[a + nx + 1]), floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1), 0f, 1f, 0f)
        }
        return c.faces.toArray()
    }

    /** 经纬球：半径 r，segments 条经线，rings 条纬线段。 */
    fun sphere(m: EditMesh, r: Float, segments: Int = 24, rings: Int = 12, material: Int = 0, xf: FloatArray? = null): IntArray {
        val c = Ctx(m, material, xf, true)
        val seg = segments.coerceIn(3, 256); val rg = rings.coerceIn(2, 128)
        val top = c.point(0f, r, 0f)
        val bottom = c.point(0f, -r, 0f)
        val grid = IntArray((rg - 1) * seg)
        for (j in 1 until rg) {
            val phi = Math.PI * j / rg
            val y = (cos(phi) * r).toFloat(); val rr = (sin(phi) * r).toFloat()
            for (i in 0 until seg) {
                val th = 2.0 * Math.PI * i / seg
                grid[(j - 1) * seg + i] = c.point((cos(th) * rr).toFloat(), y, (sin(th) * rr).toFloat())
            }
        }
        for (i in 0 until seg) {
            val i1 = (i + 1) % seg
            val u0 = i.toFloat() / seg; val u1 = (i + 1f) / seg
            val th = 2.0 * Math.PI * (i + 0.5) / seg
            val ox = cos(th).toFloat(); val oz = sin(th).toFloat()
            c.face(intArrayOf(top, grid[i], grid[i1]), floatArrayOf((u0 + u1) * 0.5f, 0f, u0, 1f / rg, u1, 1f / rg), ox * 0.3f, 1f, oz * 0.3f)
            for (j in 1 until rg - 1) {
                val a = (j - 1) * seg; val b = j * seg
                val v0 = j.toFloat() / rg; val v1 = (j + 1f) / rg
                val phi = Math.PI * (j + 0.5) / rg
                c.face(intArrayOf(grid[a + i], grid[a + i1], grid[b + i1], grid[b + i]), floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1),
                    (ox * sin(phi)).toFloat(), cos(phi).toFloat(), (oz * sin(phi)).toFloat())
            }
            val a = (rg - 2) * seg
            c.face(intArrayOf(grid[a + i], grid[a + i1], bottom), floatArrayOf(u0, 1f - 1f / rg, u1, 1f - 1f / rg, (u0 + u1) * 0.5f, 1f), ox * 0.3f, -1f, oz * 0.3f)
        }
        return c.faces.toArray()
    }

    /** 圆柱 / 圆台：底面半径 r0，顶面半径 r1（0 = 圆锥），高 h。 */
    fun cylinder(m: EditMesh, r0: Float, r1: Float, h: Float, segments: Int = 24, caps: Boolean = true, material: Int = 0, xf: FloatArray? = null): IntArray {
        val c = Ctx(m, material, xf, true)
        val seg = segments.coerceIn(3, 256)
        val y = h * 0.5f
        val bot = IntArray(seg); val top = IntArray(seg)
        val apex = if (r1 <= 1e-6f) c.point(0f, y, 0f) else -1
        val base = if (r0 <= 1e-6f) c.point(0f, -y, 0f) else -1
        for (i in 0 until seg) {
            val th = 2.0 * Math.PI * i / seg
            val cs = cos(th).toFloat(); val sn = sin(th).toFloat()
            bot[i] = if (base >= 0) base else c.point(cs * r0, -y, sn * r0)
            top[i] = if (apex >= 0) apex else c.point(cs * r1, y, sn * r1)
        }
        val slope = if (h != 0f) (r0 - r1) / h else 0f
        for (i in 0 until seg) {
            val i1 = (i + 1) % seg
            val th = 2.0 * Math.PI * (i + 0.5) / seg
            val ox = cos(th).toFloat(); val oz = sin(th).toFloat()
            val u0 = i.toFloat() / seg; val u1 = (i + 1f) / seg
            if (apex >= 0) c.face(intArrayOf(bot[i], bot[i1], apex), floatArrayOf(u0, 1f, u1, 1f, (u0 + u1) * 0.5f, 0f), ox, slope, oz)
            else if (base >= 0) c.face(intArrayOf(base, top[i1], top[i]), floatArrayOf((u0 + u1) * 0.5f, 1f, u1, 0f, u0, 0f), ox, slope, oz)
            else c.face(intArrayOf(bot[i], bot[i1], top[i1], top[i]), floatArrayOf(u0, 1f, u1, 1f, u1, 0f, u0, 0f), ox, slope, oz)
        }
        if (caps) {
            val uv = FloatArray(seg * 2)
            for (i in 0 until seg) { val th = 2.0 * Math.PI * i / seg; uv[i * 2] = 0.5f + cos(th).toFloat() * 0.5f; uv[i * 2 + 1] = 0.5f - sin(th).toFloat() * 0.5f }
            if (base < 0) c.face(bot.copyOf(), uv, 0f, -1f, 0f, false)
            if (apex < 0) c.face(top.copyOf(), uv, 0f, 1f, 0f, false)
        }
        return c.faces.toArray()
    }

    /** 圆环：大半径 R（环心到管心），小半径 r（管的半径）。 */
    fun torus(m: EditMesh, R: Float, r: Float, segments: Int = 32, sides: Int = 12, material: Int = 0, xf: FloatArray? = null): IntArray {
        val c = Ctx(m, material, xf, true)
        val seg = segments.coerceIn(3, 256); val sd = sides.coerceIn(3, 128)
        val p = IntArray(seg * sd)
        for (i in 0 until seg) {
            val th = 2.0 * Math.PI * i / seg
            for (j in 0 until sd) {
                val ph = 2.0 * Math.PI * j / sd
                val rr = R + r * cos(ph)
                p[i * sd + j] = c.point((cos(th) * rr).toFloat(), (sin(ph) * r).toFloat(), (sin(th) * rr).toFloat())
            }
        }
        for (i in 0 until seg) {
            val i1 = (i + 1) % seg
            val th = 2.0 * Math.PI * (i + 0.5) / seg
            for (j in 0 until sd) {
                val j1 = (j + 1) % sd
                val ph = 2.0 * Math.PI * (j + 0.5) / sd
                val u0 = i.toFloat() / seg; val u1 = (i + 1f) / seg; val v0 = j.toFloat() / sd; val v1 = (j + 1f) / sd
                c.face(intArrayOf(p[i * sd + j], p[i1 * sd + j], p[i1 * sd + j1], p[i * sd + j1]), floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1),
                    (cos(th) * cos(ph)).toFloat(), sin(ph).toFloat(), (sin(th) * cos(ph)).toFloat())
            }
        }
        return c.faces.toArray()
    }

    /**
     * 车削（旋转体）：把一条轮廓线绕 Y 轴转一圈。杯子、花瓶、帽子、裙摆、车轮都可以这样做。
     * @param profile 轮廓上的点，依次是 (半径, 高度)，**从下往上沿着外表面**描；半径为 0 的点会收成一个尖
     * @param capEnds 两端半径不为 0 时是否封口
     */
    fun lathe(m: EditMesh, profile: FloatArray, segments: Int = 24, capEnds: Boolean = false, material: Int = 0, xf: FloatArray? = null): IntArray {
        val c = Ctx(m, material, xf, true)
        val n = profile.size / 2
        if (n < 2) return IntArray(0)
        val seg = segments.coerceIn(3, 256)
        val rings = Array(n) { k ->
            val r = profile[k * 2]; val y = profile[k * 2 + 1]
            if (r <= 1e-6f) { val pole = c.point(0f, y, 0f); IntArray(seg) { pole } }
            else IntArray(seg) { i -> val th = 2.0 * Math.PI * i / seg; c.point((cos(th) * r).toFloat(), y, (sin(th) * r).toFloat()) }
        }
        var len = 0f
        val dist = FloatArray(n)
        for (k in 1 until n) { len += sqrt(sq(profile[k * 2] - profile[k * 2 - 2]) + sq(profile[k * 2 + 1] - profile[k * 2 - 1])); dist[k] = len }
        if (len <= 0f) len = 1f
        for (k in 0 until n - 1) {
            val dr = profile[k * 2 + 2] - profile[k * 2]; val dy = profile[k * 2 + 3] - profile[k * 2 + 1]
            // 轮廓线段的"外侧"法线：切线顺时针转 90°
            val nr = dy; val ny = -dr
            val a = rings[k]; val b = rings[k + 1]
            val v0 = 1f - dist[k] / len; val v1 = 1f - dist[k + 1] / len
            for (i in 0 until seg) {
                val i1 = (i + 1) % seg
                val th = 2.0 * Math.PI * (i + 0.5) / seg
                val ox = (cos(th) * nr).toFloat(); val oz = (sin(th) * nr).toFloat()
                val u0 = i.toFloat() / seg; val u1 = (i + 1f) / seg
                if (a[i] == a[i1] && b[i] == b[i1]) continue
                if (a[i] == a[i1]) c.face(intArrayOf(a[i], b[i1], b[i]), floatArrayOf((u0 + u1) * 0.5f, v0, u1, v1, u0, v1), ox, ny, oz)
                else if (b[i] == b[i1]) c.face(intArrayOf(a[i], a[i1], b[i]), floatArrayOf(u0, v0, u1, v0, (u0 + u1) * 0.5f, v1), ox, ny, oz)
                else c.face(intArrayOf(a[i], a[i1], b[i1], b[i]), floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1), ox, ny, oz)
            }
        }
        if (capEnds) {
            val uv = FloatArray(seg * 2)
            for (i in 0 until seg) { val th = 2.0 * Math.PI * i / seg; uv[i * 2] = 0.5f + cos(th).toFloat() * 0.5f; uv[i * 2 + 1] = 0.5f - sin(th).toFloat() * 0.5f }
            if (profile[0] > 1e-6f) c.face(rings[0].copyOf(), uv, 0f, -1f, 0f, false)
            if (profile[(n - 1) * 2] > 1e-6f) c.face(rings[n - 1].copyOf(), uv, 0f, 1f, 0f, false)
        }
        return c.faces.toArray()
    }

    /**
     * 棱柱：把 XZ 平面上的一个多边形（可以是凹的）沿 Y 拉出高度 h。底面在 y=0。
     * @param outline 多边形顶点 (x, z) 依次排列
     */
    fun prism(m: EditMesh, outline: FloatArray, h: Float, material: Int = 0, xf: FloatArray? = null): IntArray {
        val c = Ctx(m, material, xf, false)
        val n = outline.size / 2
        if (n < 3) return IntArray(0)
        val bot = IntArray(n) { c.point(outline[it * 2], 0f, outline[it * 2 + 1]) }
        val top = IntArray(n) { c.point(outline[it * 2], h, outline[it * 2 + 1]) }
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        // 多边形的有向面积：决定哪边是"外"
        var area = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += outline[i * 2] * outline[j * 2 + 1] - outline[j * 2] * outline[i * 2 + 1]
            minX = minOf(minX, outline[i * 2]); maxX = maxOf(maxX, outline[i * 2]); minZ = minOf(minZ, outline[i * 2 + 1]); maxZ = maxOf(maxZ, outline[i * 2 + 1])
        }
        val sign = if (area >= 0f) 1f else -1f
        val w = maxOf(maxX - minX, 1e-6f); val d = maxOf(maxZ - minZ, 1e-6f)
        val uv = FloatArray(n * 2) { if (it % 2 == 0) (outline[it] - minX) / w else 1f - (outline[it] - minZ) / d }
        c.face(bot.copyOf(), uv, 0f, -1f, 0f)
        c.face(top.copyOf(), uv, 0f, 1f, 0f)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ex = outline[j * 2] - outline[i * 2]; val ez = outline[j * 2 + 1] - outline[i * 2 + 1]
            c.face(intArrayOf(bot[i], bot[j], top[j], top[i]), floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f), ez * sign, 0f, -ex * sign)
        }
        return c.faces.toArray()
    }

    private fun sq(x: Float) = x * x

    /**
     * 任意网格：直接给顶点和面（AI 建模的主要入口）。
     * @param vertices x,y,z 依次排列
     * @param faces 每个面是一组顶点序号（3 个以上）
     * @param uvs 可选：每个顶点的 u,v
     * @param flip 面的顶点是按逆时针给的（OBJ / Blender 的习惯）时传 true，会自动翻成 MMD 的顺时针
     */
    fun custom(m: EditMesh, vertices: FloatArray, faces: List<IntArray>, uvs: FloatArray?, flip: Boolean, smooth: Boolean, material: Int = 0, xf: FloatArray? = null): IntArray {
        val t = FloatArray(3)
        val n = vertices.size / 3
        val p = IntArray(n) {
            if (xf == null) m.addPoint(vertices[it * 3], vertices[it * 3 + 1], vertices[it * 3 + 2])
            else { Mat4.transformPoint(xf, 0, vertices[it * 3], vertices[it * 3 + 1], vertices[it * 3 + 2], t, 0); m.addPoint(t[0], t[1], t[2]) }
        }
        val out = IntList(faces.size)
        for (fc in faces) {
            if (fc.size < 3) continue
            var ok = true
            for (v in fc) if (v !in 0 until n) { ok = false; break }
            if (!ok) continue
            val k = fc.size
            val pts = IntArray(k) { p[fc[if (flip) k - 1 - it else it]] }
            var dup = false
            for (i in 0 until k) if (pts[i] == pts[(i + 1) % k]) { dup = true; break }
            if (dup) continue
            val f = m.addFace(pts, k, material, smooth)
            if (uvs != null) {
                val s = m.fStart[f]
                for (i in 0 until k) {
                    val v = fc[if (flip) k - 1 - i else i]
                    if (v * 2 + 1 < uvs.size) { m.cUV[(s + i) * 2] = uvs[v * 2]; m.cUV[(s + i) * 2 + 1] = uvs[v * 2 + 1] }
                }
            }
            out.add(f)
        }
        return out.toArray()
    }
}

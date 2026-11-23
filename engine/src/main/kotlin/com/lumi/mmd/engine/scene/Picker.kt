package com.lumi.mmd.engine.scene

import com.lumi.mmd.engine.math.Mat4

class PickResult {
    var model: ModelInstance? = null
    /** 命中的三角形在索引缓冲里的起始下标。 */
    var triangle = -1
    var material = -1
    /** 射线参数（世界空间里沿射线方向的距离）。 */
    var t = Float.MAX_VALUE
    val point = FloatArray(3)

    fun reset() { model = null; triangle = -1; material = -1; t = Float.MAX_VALUE }
}

/**
 * 点选拾取：把屏幕射线变换到各模型的局部空间，与"当前姿势下"的三角形逐个求交（Möller–Trumbore）。
 * 蒙皮在 CPU 上现算一遍（只在点击时发生），所以拾取结果与屏幕上看到的形状一致。
 */
object Picker {
    private val inv = FloatArray(16)
    private val lo = FloatArray(3)
    private val ld = FloatArray(3)
    private val bmin = FloatArray(3)
    private val bmax = FloatArray(3)

    /**
     * @param filter 返回 false 的模型不参与拾取
     * @return 是否命中
     */
    fun pick(scene: Scene, origin: FloatArray, dir: FloatArray, out: PickResult, filter: (ModelInstance) -> Boolean): Boolean {
        out.reset()
        for (m in scene.models) {
            if (!m.visible || m.data.vertexCount == 0 || !filter(m)) continue
            pickModel(m, origin, dir, out)
        }
        val hit = out.model ?: return false
        out.point[0] = origin[0] + dir[0] * out.t
        out.point[1] = origin[1] + dir[1] * out.t
        out.point[2] = origin[2] + dir[2] * out.t
        return hit.visible
    }

    fun pickModel(m: ModelInstance, origin: FloatArray, dir: FloatArray, out: PickResult) {
        if (!Mat4.invertAffine(inv, 0, m.worldMatrix, 0)) return
        Mat4.transformPoint(inv, 0, origin[0], origin[1], origin[2], lo, 0)
        Mat4.transformDir(inv, 0, dir[0], dir[1], dir[2], ld, 0)
        // 先与包围盒求交
        m.localBounds(bmin, bmax)
        var tmin = 0f
        var tmax = out.t
        for (k in 0 until 3) {
            val d = ld[k]
            if (kotlin.math.abs(d) < 1e-12f) {
                if (lo[k] < bmin[k] || lo[k] > bmax[k]) return
            } else {
                var t1 = (bmin[k] - lo[k]) / d
                var t2 = (bmax[k] - lo[k]) / d
                if (t1 > t2) { val s = t1; t1 = t2; t2 = s }
                if (t1 > tmin) tmin = t1
                if (t2 < tmax) tmax = t2
                if (tmin > tmax) return
            }
        }
        val pos = m.skinnedPositions()
        val idx = m.data.indices
        val ox = lo[0]; val oy = lo[1]; val oz = lo[2]
        val dx = ld[0]; val dy = ld[1]; val dz = ld[2]
        var best = out.t
        var bestTri = -1
        var bestMat = -1
        val mats = m.materials
        for (mi in mats.indices) {
            val st = mats[mi]
            val md = st.base
            if (!st.visible || md.indexCount <= 0 || st.diffuse[3] * m.opacity <= 0.01f) continue
            var i = md.indexOffset
            val end = md.indexOffset + md.indexCount
            while (i < end) {
                val a = idx[i] * 3; val b = idx[i + 1] * 3; val c = idx[i + 2] * 3
                val ax = pos[a]; val ay = pos[a + 1]; val az = pos[a + 2]
                val e1x = pos[b] - ax; val e1y = pos[b + 1] - ay; val e1z = pos[b + 2] - az
                val e2x = pos[c] - ax; val e2y = pos[c + 1] - ay; val e2z = pos[c + 2] - az
                val px = dy * e2z - dz * e2y; val py = dz * e2x - dx * e2z; val pz = dx * e2y - dy * e2x
                val det = e1x * px + e1y * py + e1z * pz
                if (det > -1e-12f && det < 1e-12f) { i += 3; continue }
                val invDet = 1f / det
                val tx = ox - ax; val ty = oy - ay; val tz = oz - az
                val u = (tx * px + ty * py + tz * pz) * invDet
                if (u < 0f || u > 1f) { i += 3; continue }
                val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
                val v = (dx * qx + dy * qy + dz * qz) * invDet
                if (v < 0f || u + v > 1f) { i += 3; continue }
                val t = (e2x * qx + e2y * qy + e2z * qz) * invDet
                if (t > 1e-4f && t < best) { best = t; bestTri = i; bestMat = mi }
                i += 3
            }
        }
        if (bestTri >= 0) {
            out.model = m
            out.triangle = bestTri
            out.material = bestMat
            out.t = best
        }
    }
}

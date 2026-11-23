package com.lumi.mmd.engine.physics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 三角网格碰撞体：把一个模型的面原样拿来挡东西（舞台的地面、台阶、桌子、墙）。
 *
 * 建一次包围盒树（BVH），之后「离这个点最近的面在哪」和「这条射线打到哪」都只看附近的几个面，
 * 十几万个面的舞台也只要几微秒。坐标是模型自己的坐标（绑定姿势），调用方负责把查询点换算进来。
 * 面是单面的：从正面（法线那一侧，也就是看得见的那一面）过来的东西会被挡住。
 */
class MeshShape(positions: FloatArray, indices: IntArray) {
    val triangleCount: Int
    /** 每个面 9 个数（三个顶点），按树里的顺序排好。 */
    private val tri: FloatArray
    private val nodeBox: FloatArray       // 每个节点 6 个：min xyz, max xyz
    private val nodeA: IntArray           // 内部节点：左孩子；叶子：-(第一个面 + 1)
    private val nodeB: IntArray           // 内部节点：右孩子；叶子：面数
    private var nodeCount = 0
    val min = FloatArray(3)
    val max = FloatArray(3)
    private val stack = IntArray(64)

    init {
        // 去掉退化的面（面积为零的面给不出法线）
        var n = 0
        val all = indices.size / 3
        val keep = IntArray(all)
        for (t in 0 until all) {
            val a = indices[t * 3] * 3; val b = indices[t * 3 + 1] * 3; val c = indices[t * 3 + 2] * 3
            if (a < 0 || b < 0 || c < 0 || a + 2 >= positions.size || b + 2 >= positions.size || c + 2 >= positions.size) continue
            val ux = positions[b] - positions[a]; val uy = positions[b + 1] - positions[a + 1]; val uz = positions[b + 2] - positions[a + 2]
            val vx = positions[c] - positions[a]; val vy = positions[c + 1] - positions[a + 1]; val vz = positions[c + 2] - positions[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            if (nx * nx + ny * ny + nz * nz < 1e-18f) continue
            keep[n++] = t
        }
        triangleCount = n
        val src = FloatArray(n * 9)
        val cent = FloatArray(n * 3)
        for (i in 0 until n) {
            val t = keep[i]
            for (k in 0 until 3) {
                val v = indices[t * 3 + k] * 3
                src[i * 9 + k * 3] = positions[v]; src[i * 9 + k * 3 + 1] = positions[v + 1]; src[i * 9 + k * 3 + 2] = positions[v + 2]
            }
            for (k in 0 until 3) cent[i * 3 + k] = (src[i * 9 + k] + src[i * 9 + 3 + k] + src[i * 9 + 6 + k]) / 3f
        }
        val order = IntArray(n) { it }
        val cap = max(1, 2 * n)
        nodeBox = FloatArray(cap * 6); nodeA = IntArray(cap); nodeB = IntArray(cap)
        if (n > 0) build(src, cent, order, 0, n)
        tri = FloatArray(n * 9)
        for (i in 0 until n) System.arraycopy(src, order[i] * 9, tri, i * 9, 9)
        if (n > 0) { for (k in 0 until 3) { min[k] = nodeBox[k]; max[k] = nodeBox[3 + k] } }
    }

    private fun build(src: FloatArray, cent: FloatArray, order: IntArray, first: Int, count: Int): Int {
        val node = nodeCount++
        val o = node * 6
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        var cx0 = Float.MAX_VALUE; var cy0 = Float.MAX_VALUE; var cz0 = Float.MAX_VALUE
        var cx1 = -Float.MAX_VALUE; var cy1 = -Float.MAX_VALUE; var cz1 = -Float.MAX_VALUE
        for (i in first until first + count) {
            val t = order[i] * 9
            for (k in 0 until 3) {
                val x = src[t + k * 3]; val y = src[t + k * 3 + 1]; val z = src[t + k * 3 + 2]
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
                if (z < z0) z0 = z; if (z > z1) z1 = z
            }
            val c = order[i] * 3
            if (cent[c] < cx0) cx0 = cent[c]; if (cent[c] > cx1) cx1 = cent[c]
            if (cent[c + 1] < cy0) cy0 = cent[c + 1]; if (cent[c + 1] > cy1) cy1 = cent[c + 1]
            if (cent[c + 2] < cz0) cz0 = cent[c + 2]; if (cent[c + 2] > cz1) cz1 = cent[c + 2]
        }
        nodeBox[o] = x0; nodeBox[o + 1] = y0; nodeBox[o + 2] = z0; nodeBox[o + 3] = x1; nodeBox[o + 4] = y1; nodeBox[o + 5] = z1
        val ex = cx1 - cx0; val ey = cy1 - cy0; val ez = cz1 - cz0
        val axis = if (ex >= ey && ex >= ez) 0 else if (ey >= ez) 1 else 2
        val extent = max(ex, max(ey, ez))
        if (count <= LEAF || extent <= 0f) { nodeA[node] = -(first + 1); nodeB[node] = count; return node }
        // 按中心在最长的那个轴上的位置，从中位数处分成两半
        val mid = first + count / 2
        select(cent, order, first, first + count - 1, mid, axis)
        val l = build(src, cent, order, first, mid - first)
        val r = build(src, cent, order, mid, first + count - mid)
        nodeA[node] = l; nodeB[node] = r
        return node
    }

    /** 快速选择：让 order[k] 落在排好序时该在的位置，左边的都不比它大。 */
    private fun select(cent: FloatArray, order: IntArray, lo0: Int, hi0: Int, k: Int, axis: Int) {
        var lo = lo0; var hi = hi0
        while (lo < hi) {
            val pivot = cent[order[(lo + hi) ushr 1] * 3 + axis]
            var i = lo; var j = hi
            while (i <= j) {
                while (cent[order[i] * 3 + axis] < pivot) i++
                while (cent[order[j] * 3 + axis] > pivot) j--
                if (i <= j) { val t = order[i]; order[i] = order[j]; order[j] = t; i++; j-- }
            }
            if (k <= j) hi = j else if (k >= i) lo = i else return
        }
    }

    private fun boxDist2(node: Int, x: Float, y: Float, z: Float): Float {
        val o = node * 6
        val dx = max(max(nodeBox[o] - x, 0f), x - nodeBox[o + 3])
        val dy = max(max(nodeBox[o + 1] - y, 0f), y - nodeBox[o + 4])
        val dz = max(max(nodeBox[o + 2] - z, 0f), z - nodeBox[o + 5])
        return dx * dx + dy * dy + dz * dz
    }

    /**
     * 离 (x,y,z) 最近的面上的点。只找 maxDist 以内的；找到返回距离，并写 out[0..2] = 那个点、out[3..5] = 那个面的法线；
     * 没有返回 -1。
     */
    fun closest(x: Float, y: Float, z: Float, maxDist: Float, out: FloatArray): Float {
        if (triangleCount == 0) return -1f
        var best = maxDist * maxDist
        var found = -1
        var bestFront = false
        var bx = 0f; var by = 0f; var bz = 0f
        var sp = 0
        stack[sp++] = 0
        while (sp > 0) {
            val node = stack[--sp]
            if (boxDist2(node, x, y, z) > best * 1.0002f + 1e-10f) continue
            val a = nodeA[node]
            if (a < 0) {
                val first = -a - 1
                for (t in first until first + nodeB[node]) {
                    val d = pointTriangle(t * 9, x, y, z)
                    if (d > best * 1.0002f + 1e-10f) continue
                    // 两个面一样近（薄墙的正反两面、共用一条棱的两个面）：要朝着这个点的那一个
                    val front = facing(t * 9, x, y, z) >= 0f
                    val tie = found >= 0 && d >= best * 0.9998f - 1e-10f
                    if (found < 0 || (!tie && d < best) || (tie && front && !bestFront) || (tie && front == bestFront && d < best)) {
                        best = d; found = t; bestFront = front; bx = cp[0]; by = cp[1]; bz = cp[2]
                    }
                }
            } else {
                // 先看近的那个孩子，另一个多半就不用看了
                val b = nodeB[node]
                val da = boxDist2(a, x, y, z); val db = boxDist2(b, x, y, z)
                if (sp + 2 > stack.size) continue
                val lim = best * 1.0002f + 1e-10f
                if (da < db) { if (db <= lim) stack[sp++] = b; if (da <= lim) stack[sp++] = a }
                else { if (da <= lim) stack[sp++] = a; if (db <= lim) stack[sp++] = b }
            }
        }
        if (found < 0) return -1f
        out[0] = bx; out[1] = by; out[2] = bz
        normal(found * 9, out, 3)
        return sqrt(best)
    }

    private val cp = FloatArray(3)

    /** 点在这个面的正面（> 0）还是背面（< 0）；没有归一化，只看符号。 */
    private fun facing(t: Int, px: Float, py: Float, pz: Float): Float {
        val ux = tri[t + 3] - tri[t]; val uy = tri[t + 4] - tri[t + 1]; val uz = tri[t + 5] - tri[t + 2]
        val vx = tri[t + 6] - tri[t]; val vy = tri[t + 7] - tri[t + 1]; val vz = tri[t + 8] - tri[t + 2]
        return (uy * vz - uz * vy) * (px - tri[t]) + (uz * vx - ux * vz) * (py - tri[t + 1]) + (ux * vy - uy * vx) * (pz - tri[t + 2])
    }

    private fun normal(t: Int, out: FloatArray, at: Int) {
        val ux = tri[t + 3] - tri[t]; val uy = tri[t + 4] - tri[t + 1]; val uz = tri[t + 5] - tri[t + 2]
        val vx = tri[t + 6] - tri[t]; val vy = tri[t + 7] - tri[t + 1]; val vz = tri[t + 8] - tri[t + 2]
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l < 1e-12f) { out[at] = 0f; out[at + 1] = 1f; out[at + 2] = 0f } else { out[at] = nx / l; out[at + 1] = ny / l; out[at + 2] = nz / l }
    }

    /** 点到三角形最近的点（Ericson《Real-Time Collision Detection》5.1.5），写进 cp，返回距离的平方。 */
    private fun pointTriangle(t: Int, px: Float, py: Float, pz: Float): Float {
        val ax = tri[t]; val ay = tri[t + 1]; val az = tri[t + 2]
        val abx = tri[t + 3] - ax; val aby = tri[t + 4] - ay; val abz = tri[t + 5] - az
        val acx = tri[t + 6] - ax; val acy = tri[t + 7] - ay; val acz = tri[t + 8] - az
        val apx = px - ax; val apy = py - ay; val apz = pz - az
        val d1 = abx * apx + aby * apy + abz * apz
        val d2 = acx * apx + acy * apy + acz * apz
        var v: Float; var w: Float
        if (d1 <= 0f && d2 <= 0f) { v = 0f; w = 0f }
        else {
            val bpx = apx - abx; val bpy = apy - aby; val bpz = apz - abz
            val d3 = abx * bpx + aby * bpy + abz * bpz
            val d4 = acx * bpx + acy * bpy + acz * bpz
            if (d3 >= 0f && d4 <= d3) { v = 1f; w = 0f }
            else {
                val vc = d1 * d4 - d3 * d2
                if (vc <= 0f && d1 >= 0f && d3 <= 0f) { v = d1 / (d1 - d3); w = 0f }
                else {
                    val cpx = apx - acx; val cpy = apy - acy; val cpz = apz - acz
                    val d5 = abx * cpx + aby * cpy + abz * cpz
                    val d6 = acx * cpx + acy * cpy + acz * cpz
                    if (d6 >= 0f && d5 <= d6) { v = 0f; w = 1f }
                    else {
                        val vb = d5 * d2 - d1 * d6
                        if (vb <= 0f && d2 >= 0f && d6 <= 0f) { v = 0f; w = d2 / (d2 - d6) }
                        else {
                            val va = d3 * d6 - d5 * d4
                            if (va <= 0f && (d4 - d3) >= 0f && (d5 - d6) >= 0f) { w = (d4 - d3) / ((d4 - d3) + (d5 - d6)); v = 1f - w }
                            else { val denom = 1f / (va + vb + vc); v = vb * denom; w = vc * denom }
                        }
                    }
                }
            }
        }
        val qx = ax + abx * v + acx * w; val qy = ay + aby * v + acy * w; val qz = az + abz * v + acz * w
        cp[0] = qx; cp[1] = qy; cp[2] = qz
        val dx = px - qx; val dy = py - qy; val dz = pz - qz
        return dx * dx + dy * dy + dz * dz
    }

    /** 射线（起点 o，方向 d，不必是单位长度）在 maxT 以内打到的最近的面：返回 t，法线写进 out[0..2]（朝着射线来的那一侧）；没打到返回 -1。 */
    fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxT: Float, out: FloatArray): Float {
        if (triangleCount == 0) return -1f
        var best = maxT
        var found = -1
        val ix = if (abs(dx) > 1e-12f) 1f / dx else Float.MAX_VALUE
        val iy = if (abs(dy) > 1e-12f) 1f / dy else Float.MAX_VALUE
        val iz = if (abs(dz) > 1e-12f) 1f / dz else Float.MAX_VALUE
        var sp = 0
        stack[sp++] = 0
        while (sp > 0) {
            val node = stack[--sp]
            val o = node * 6
            // 射线和包围盒：三个方向的进出区间求交
            var t0 = 0f; var t1 = best
            var a = (nodeBox[o] - ox) * ix; var b = (nodeBox[o + 3] - ox) * ix
            if (abs(dx) <= 1e-12f) { if (ox < nodeBox[o] || ox > nodeBox[o + 3]) continue } else { t0 = max(t0, min(a, b)); t1 = min(t1, max(a, b)) }
            a = (nodeBox[o + 1] - oy) * iy; b = (nodeBox[o + 4] - oy) * iy
            if (abs(dy) <= 1e-12f) { if (oy < nodeBox[o + 1] || oy > nodeBox[o + 4]) continue } else { t0 = max(t0, min(a, b)); t1 = min(t1, max(a, b)) }
            a = (nodeBox[o + 2] - oz) * iz; b = (nodeBox[o + 5] - oz) * iz
            if (abs(dz) <= 1e-12f) { if (oz < nodeBox[o + 2] || oz > nodeBox[o + 5]) continue } else { t0 = max(t0, min(a, b)); t1 = min(t1, max(a, b)) }
            if (t0 > t1) continue
            val na = nodeA[node]
            if (na < 0) {
                val first = -na - 1
                for (t in first until first + nodeB[node]) {
                    val hit = rayTriangle(t * 9, ox, oy, oz, dx, dy, dz)
                    if (hit >= 0f && hit < best) { best = hit; found = t }
                }
            } else if (sp + 2 <= stack.size) { stack[sp++] = na; stack[sp++] = nodeB[node] }
        }
        if (found < 0) return -1f
        normal(found * 9, out, 0)
        if (out[0] * dx + out[1] * dy + out[2] * dz > 0f) { out[0] = -out[0]; out[1] = -out[1]; out[2] = -out[2] }
        return best
    }

    /** Möller–Trumbore，两面都算。 */
    private fun rayTriangle(t: Int, ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float): Float {
        val ax = tri[t]; val ay = tri[t + 1]; val az = tri[t + 2]
        val e1x = tri[t + 3] - ax; val e1y = tri[t + 4] - ay; val e1z = tri[t + 5] - az
        val e2x = tri[t + 6] - ax; val e2y = tri[t + 7] - ay; val e2z = tri[t + 8] - az
        val px = dy * e2z - dz * e2y; val py = dz * e2x - dx * e2z; val pz = dx * e2y - dy * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (abs(det) < 1e-12f) return -1f
        val inv = 1f / det
        val tx = ox - ax; val ty = oy - ay; val tz = oz - az
        val u = (tx * px + ty * py + tz * pz) * inv
        if (u < -1e-5f || u > 1.00001f) return -1f
        val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
        val v = (dx * qx + dy * qy + dz * qz) * inv
        if (v < -1e-5f || u + v > 1.00001f) return -1f
        val hit = (e2x * qx + e2y * qy + e2z * qz) * inv
        return if (hit >= 0f) hit else -1f
    }

    companion object {
        private const val LEAF = 4
        /** 面数超过这个就不建网格碰撞了（内存和建树的时间），调用方退回包围盒。 */
        const val MAX_TRIANGLES = 600_000
    }
}

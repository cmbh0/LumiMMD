package com.lumi.mmd.engine.mesh

import com.lumi.mmd.engine.mesh.EditMesh.Companion.edgeKey
import com.lumi.mmd.engine.util.IntList
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 网格编辑操作。都直接修改传入的 [EditMesh]；做完之后调用方负责重算法线（[EditMesh.recalcNormals]）并重建模型。
 * faces / points 参数里已删除或越界的序号会被忽略。
 */
object MeshOps {
    private fun liveFaces(m: EditMesh, faces: IntArray): IntArray {
        val seen = BooleanArray(m.faceCount)
        val out = IntList(faces.size)
        for (f in faces) if (f in 0 until m.faceCount && !m.fDead[f] && !seen[f]) { seen[f] = true; out.add(f) }
        return out.toArray()
    }

    private fun livePoints(m: EditMesh, pts: IntArray): IntArray {
        val seen = BooleanArray(m.pointCount)
        val out = IntList(pts.size)
        for (p in pts) if (p in 0 until m.pointCount && !m.pDead[p] && !seen[p]) { seen[p] = true; out.add(p) }
        return out.toArray()
    }

    /** 这些面用到的点（去重）。 */
    fun pointsOfFaces(m: EditMesh, faces: IntArray): IntArray {
        val seen = BooleanArray(m.pointCount)
        val out = IntList(faces.size * 2)
        for (f in faces) {
            if (f !in 0 until m.faceCount || m.fDead[f]) continue
            val s = m.fStart[f]
            for (i in 0 until m.fSize[f]) { val p = m.cPoint[s + i]; if (!seen[p]) { seen[p] = true; out.add(p) } }
        }
        return out.toArray()
    }

    /** 一组面的平均法线（按面积加权）。 */
    fun regionNormal(m: EditMesh, faces: IntArray, out: FloatArray) {
        val t = FloatArray(3)
        var x = 0f; var y = 0f; var z = 0f
        for (f in faces) { m.faceNormalRaw(f, t); x += t[0]; y += t[1]; z += t[2] }
        val l = sqrt(x * x + y * y + z * z)
        if (l > 1e-20f) { out[0] = x / l; out[1] = y / l; out[2] = z / l } else { out[0] = 0f; out[1] = 1f; out[2] = 0f }
    }

    fun translatePoints(m: EditMesh, pts: IntArray, dx: Float, dy: Float, dz: Float) {
        for (p in pts) { m.pos[p * 3] += dx; m.pos[p * 3 + 1] += dy; m.pos[p * 3 + 2] += dz }
    }

    // ------------------------------------------------------------ 挤出

    /**
     * 挤出：选中的面整体抬起，四周补上侧壁。
     * @param distance 沿这些面的平均法线抬起的距离（0 = 只生成侧壁，之后自己拖动）
     * @return 被抬起的点
     */
    fun extrude(m: EditMesh, facesIn: IntArray, distance: Float): IntArray {
        val faces = liveFaces(m, facesIn)
        if (faces.isEmpty()) return IntArray(0)
        val count = HashMap<Long, Int>()
        for (f in faces) {
            val s = m.fStart[f]; val n = m.fSize[f]
            for (i in 0 until n) { val k = edgeKey(m.cPoint[s + i], m.cPoint[s + (i + 1) % n]); count[k] = (count[k] ?: 0) + 1 }
        }
        val before = m.pointCount
        val dup = IntArray(before) { -1 }
        // 边界边（只被选区里的一个面用到）：a, b, 角a, 角b, 面
        val sides = ArrayList<IntArray>()
        for (f in faces) {
            val s = m.fStart[f]; val n = m.fSize[f]
            for (i in 0 until n) {
                val a = m.cPoint[s + i]; val b = m.cPoint[s + (i + 1) % n]
                if (count[edgeKey(a, b)] != 1) continue
                sides.add(intArrayOf(a, b, s + i, s + (i + 1) % n, f))
                if (dup[a] < 0) dup[a] = m.addPoint(m.pos[a * 3], m.pos[a * 3 + 1], m.pos[a * 3 + 2], a)
                if (dup[b] < 0) dup[b] = m.addPoint(m.pos[b * 3], m.pos[b * 3 + 1], m.pos[b * 3 + 2], b)
            }
        }
        val nrm = FloatArray(3)
        regionNormal(m, faces, nrm)
        // 侧壁的 UV 取自边两端的角
        val sideUv = FloatArray(sides.size * 4)
        for ((i, e) in sides.withIndex()) {
            sideUv[i * 4] = m.cUV[e[2] * 2]; sideUv[i * 4 + 1] = m.cUV[e[2] * 2 + 1]
            sideUv[i * 4 + 2] = m.cUV[e[3] * 2]; sideUv[i * 4 + 3] = m.cUV[e[3] * 2 + 1]
        }
        for (f in faces) {
            val s = m.fStart[f]
            for (i in 0 until m.fSize[f]) { val p = m.cPoint[s + i]; if (p < before && dup[p] >= 0) m.cPoint[s + i] = dup[p] }
        }
        val quad = IntArray(4)
        for ((i, e) in sides.withIndex()) {
            quad[0] = e[0]; quad[1] = e[1]; quad[2] = dup[e[1]]; quad[3] = dup[e[0]]
            val nf = m.addFace(quad, 4, m.fMat[e[4]], m.fSmooth[e[4]])
            val s = m.fStart[nf]
            m.cUV[s * 2] = sideUv[i * 4]; m.cUV[s * 2 + 1] = sideUv[i * 4 + 1]
            m.cUV[(s + 1) * 2] = sideUv[i * 4 + 2]; m.cUV[(s + 1) * 2 + 1] = sideUv[i * 4 + 3]
            m.cUV[(s + 2) * 2] = sideUv[i * 4 + 2]; m.cUV[(s + 2) * 2 + 1] = sideUv[i * 4 + 3]
            m.cUV[(s + 3) * 2] = sideUv[i * 4]; m.cUV[(s + 3) * 2 + 1] = sideUv[i * 4 + 1]
        }
        val moved = pointsOfFaces(m, faces)
        if (distance != 0f) translatePoints(m, moved, nrm[0] * distance, nrm[1] * distance, nrm[2] * distance)
        return moved
    }

    // ------------------------------------------------------------ 内插

    /**
     * 内插：每个面向自己的中心收缩出一个小面，四周补一圈面。
     * @param amount 收缩比例 0..1（0.2 = 向中心收 20%）
     * @param depth 小面沿法线抬起（正）或压下（负）的距离
     */
    fun inset(m: EditMesh, facesIn: IntArray, amount: Float, depth: Float = 0f) {
        val faces = liveFaces(m, facesIn)
        val a = amount.coerceIn(0.001f, 0.999f)
        val c = FloatArray(3); val nrm = FloatArray(3)
        for (f in faces) {
            val n = m.fSize[f]; val s = m.fStart[f]
            m.faceCenter(f, c); m.faceNormal(f, nrm)
            val pts = IntArray(n) { m.cPoint[s + it] }
            val uv = FloatArray(n * 2) { m.cUV[s * 2 + it] }
            var uc = 0f; var vc = 0f
            for (i in 0 until n) { uc += uv[i * 2]; vc += uv[i * 2 + 1] }
            uc /= n; vc /= n
            val q = IntArray(n)
            for (i in 0 until n) {
                val p = pts[i]
                q[i] = m.addPoint(m.pos[p * 3] + (c[0] - m.pos[p * 3]) * a + nrm[0] * depth,
                    m.pos[p * 3 + 1] + (c[1] - m.pos[p * 3 + 1]) * a + nrm[1] * depth,
                    m.pos[p * 3 + 2] + (c[2] - m.pos[p * 3 + 2]) * a + nrm[2] * depth, p)
            }
            val quad = IntArray(4)
            for (i in 0 until n) {
                val j = (i + 1) % n
                quad[0] = pts[i]; quad[1] = pts[j]; quad[2] = q[j]; quad[3] = q[i]
                val nf = m.addFace(quad, 4, m.fMat[f], m.fSmooth[f])
                val ns = m.fStart[nf]
                m.cUV[ns * 2] = uv[i * 2]; m.cUV[ns * 2 + 1] = uv[i * 2 + 1]
                m.cUV[(ns + 1) * 2] = uv[j * 2]; m.cUV[(ns + 1) * 2 + 1] = uv[j * 2 + 1]
                m.cUV[(ns + 2) * 2] = uv[j * 2] + (uc - uv[j * 2]) * a; m.cUV[(ns + 2) * 2 + 1] = uv[j * 2 + 1] + (vc - uv[j * 2 + 1]) * a
                m.cUV[(ns + 3) * 2] = uv[i * 2] + (uc - uv[i * 2]) * a; m.cUV[(ns + 3) * 2 + 1] = uv[i * 2 + 1] + (vc - uv[i * 2 + 1]) * a
            }
            val fs = m.fStart[f]
            for (i in 0 until n) {
                m.cPoint[fs + i] = q[i]
                m.cUV[(fs + i) * 2] = uv[i * 2] + (uc - uv[i * 2]) * a
                m.cUV[(fs + i) * 2 + 1] = uv[i * 2 + 1] + (vc - uv[i * 2 + 1]) * a
                m.cOrig[fs + i] = -1
            }
        }
    }

    // ------------------------------------------------------------ 删除 / 复制 / 翻转

    fun deleteFaces(m: EditMesh, faces: IntArray): Int {
        var n = 0
        for (f in faces) if (f in 0 until m.faceCount && !m.fDead[f]) { m.fDead[f] = true; m.fSel[f] = false; n++ }
        m.removeOrphanPoints()
        m.edgeSel.clear()
        return n
    }

    /** 删除点：用到这些点的面一并删除。 */
    fun deletePoints(m: EditMesh, pts: IntArray): Int {
        val kill = BooleanArray(m.pointCount)
        for (p in pts) if (p in 0 until m.pointCount) kill[p] = true
        var n = 0
        for (f in 0 until m.faceCount) {
            if (m.fDead[f]) continue
            val s = m.fStart[f]
            for (i in 0 until m.fSize[f]) if (kill[m.cPoint[s + i]]) { m.fDead[f] = true; m.fSel[f] = false; n++; break }
        }
        for (p in 0 until m.pointCount) if (kill[p]) { m.pDead[p] = true; m.pSel[p] = false }
        m.removeOrphanPoints()
        m.edgeSel.clear()
        return n
    }

    /** 溶解边：把共用这条边的两个面并成一个（边本身消失，点保留）。返回是否成功。 */
    fun dissolveEdge(m: EditMesh, a: Int, b: Int): Boolean {
        var f1 = -1; var i1 = -1; var f2 = -1; var i2 = -1
        for (f in 0 until m.faceCount) {
            if (m.fDead[f]) continue
            val s = m.fStart[f]; val n = m.fSize[f]
            for (i in 0 until n) {
                val p = m.cPoint[s + i]; val q = m.cPoint[s + (i + 1) % n]
                if ((p == a && q == b) || (p == b && q == a)) {
                    if (f1 < 0) { f1 = f; i1 = i } else if (f2 < 0 && f != f1) { f2 = f; i2 = i } else return false
                }
            }
        }
        if (f1 < 0 || f2 < 0) return false
        val n1 = m.fSize[f1]; val n2 = m.fSize[f2]
        val s1 = m.fStart[f1]; val s2 = m.fStart[f2]
        // 两个面走这条边的方向必须相反（朝向一致的网格都是这样）
        if (m.cPoint[s1 + i1] != m.cPoint[s2 + (i2 + 1) % n2]) return false
        // 新的环：f1 从边的终点出发绕一圈回到边的起点（n1 个角），再接上 f2 里不属于这条边的 n2-2 个角
        val n = n1 + n2 - 2
        val src = IntArray(n)
        var k = 0
        for (t in 1..n1) src[k++] = s1 + (i1 + t) % n1
        for (t in 2..n2 - 1) src[k++] = s2 + (i2 + t) % n2
        val pts = IntArray(n) { m.cPoint[src[it]] }
        val uv = FloatArray(n * 2) { m.cUV[src[it / 2] * 2 + it % 2] }
        val nr = FloatArray(n * 3) { m.cN[src[it / 3] * 3 + it % 3] }
        val org = IntArray(n) { m.cOrig[src[it]] }
        val c = m.resizeFace(f1, n)
        for (i in 0 until n) {
            m.cPoint[c + i] = pts[i]
            m.cUV[(c + i) * 2] = uv[i * 2]; m.cUV[(c + i) * 2 + 1] = uv[i * 2 + 1]
            m.cN[(c + i) * 3] = nr[i * 3]; m.cN[(c + i) * 3 + 1] = nr[i * 3 + 1]; m.cN[(c + i) * 3 + 2] = nr[i * 3 + 2]
            m.cOrig[c + i] = org[i]
        }
        m.fDead[f2] = true; m.fSel[f2] = false
        m.edgeSel.remove(edgeKey(a, b))
        return true
    }

    /** 复制选中的面（原地），返回新面。 */
    fun duplicate(m: EditMesh, facesIn: IntArray): IntArray {
        val faces = liveFaces(m, facesIn)
        val map = IntArray(m.pointCount) { -1 }
        val out = IntArray(faces.size)
        for ((k, f) in faces.withIndex()) {
            val n = m.fSize[f]
            val pts = IntArray(n)
            for (i in 0 until n) {
                val p = m.cPoint[m.fStart[f] + i]
                if (map[p] < 0) map[p] = m.addPoint(m.pos[p * 3], m.pos[p * 3 + 1], m.pos[p * 3 + 2], p)
                pts[i] = map[p]
            }
            val nf = m.addFace(pts, n, m.fMat[f], m.fSmooth[f])
            for (i in 0 until n) {
                val src = m.fStart[f] + i; val dst = m.fStart[nf] + i
                m.cUV[dst * 2] = m.cUV[src * 2]; m.cUV[dst * 2 + 1] = m.cUV[src * 2 + 1]
                m.cN[dst * 3] = m.cN[src * 3]; m.cN[dst * 3 + 1] = m.cN[src * 3 + 1]; m.cN[dst * 3 + 2] = m.cN[src * 3 + 2]
            }
            out[k] = nf
        }
        return out
    }

    /** 翻转面的朝向。 */
    fun flip(m: EditMesh, faces: IntArray) {
        for (f in faces) {
            if (f !in 0 until m.faceCount || m.fDead[f]) continue
            val s = m.fStart[f]; val n = m.fSize[f]
            var i = 0; var j = n - 1
            while (i < j) {
                val a = s + i; val b = s + j
                val tp = m.cPoint[a]; m.cPoint[a] = m.cPoint[b]; m.cPoint[b] = tp
                val to = m.cOrig[a]; m.cOrig[a] = m.cOrig[b]; m.cOrig[b] = to
                for (k in 0 until 2) { val t = m.cUV[a * 2 + k]; m.cUV[a * 2 + k] = m.cUV[b * 2 + k]; m.cUV[b * 2 + k] = t }
                for (k in 0 until 3) { val t = m.cN[a * 3 + k]; m.cN[a * 3 + k] = m.cN[b * 3 + k]; m.cN[b * 3 + k] = t }
                i++; j--
            }
            for (c in s until s + n) { m.cN[c * 3] = -m.cN[c * 3]; m.cN[c * 3 + 1] = -m.cN[c * 3 + 1]; m.cN[c * 3 + 2] = -m.cN[c * 3 + 2]; m.cOrig[c] = -1 }
        }
    }

    // ------------------------------------------------------------ 细分 / 三角化 / 环切

    /** 在面 f 的环里，凡是相邻两点之间有中点（mid 里登记过）的地方把中点插进去。 */
    private fun insertMidpoints(m: EditMesh, f: Int, mid: HashMap<Long, Int>) {
        val s = m.fStart[f]; val n = m.fSize[f]
        var extra = 0
        for (i in 0 until n) if (mid.containsKey(edgeKey(m.cPoint[s + i], m.cPoint[s + (i + 1) % n]))) extra++
        if (extra == 0) return
        val pts = IntArray(n + extra); val uv = FloatArray((n + extra) * 2); val nr = FloatArray((n + extra) * 3); val org = IntArray(n + extra)
        var k = 0
        for (i in 0 until n) {
            val a = s + i; val b = s + (i + 1) % n
            pts[k] = m.cPoint[a]; uv[k * 2] = m.cUV[a * 2]; uv[k * 2 + 1] = m.cUV[a * 2 + 1]
            nr[k * 3] = m.cN[a * 3]; nr[k * 3 + 1] = m.cN[a * 3 + 1]; nr[k * 3 + 2] = m.cN[a * 3 + 2]; org[k] = m.cOrig[a]
            k++
            val mp = mid[edgeKey(m.cPoint[a], m.cPoint[b])] ?: continue
            pts[k] = mp
            uv[k * 2] = (m.cUV[a * 2] + m.cUV[b * 2]) * 0.5f; uv[k * 2 + 1] = (m.cUV[a * 2 + 1] + m.cUV[b * 2 + 1]) * 0.5f
            nr[k * 3] = m.cN[a * 3] + m.cN[b * 3]; nr[k * 3 + 1] = m.cN[a * 3 + 1] + m.cN[b * 3 + 1]; nr[k * 3 + 2] = m.cN[a * 3 + 2] + m.cN[b * 3 + 2]
            val l = sqrt(nr[k * 3] * nr[k * 3] + nr[k * 3 + 1] * nr[k * 3 + 1] + nr[k * 3 + 2] * nr[k * 3 + 2])
            if (l > 1e-12f) { nr[k * 3] /= l; nr[k * 3 + 1] /= l; nr[k * 3 + 2] /= l }
            org[k] = -1
            k++
        }
        val c = m.resizeFace(f, n + extra)
        for (i in 0 until n + extra) {
            m.cPoint[c + i] = pts[i]; m.cUV[(c + i) * 2] = uv[i * 2]; m.cUV[(c + i) * 2 + 1] = uv[i * 2 + 1]
            m.cN[(c + i) * 3] = nr[i * 3]; m.cN[(c + i) * 3 + 1] = nr[i * 3 + 1]; m.cN[(c + i) * 3 + 2] = nr[i * 3 + 2]; m.cOrig[c + i] = org[i]
        }
    }

    /** 新建一个面并给每个角填 UV。 */
    private fun faceUV(m: EditMesh, pts: IntArray, uv: FloatArray, material: Int, smooth: Boolean): Int {
        val f = m.addFace(pts, pts.size, material, smooth)
        val s = m.fStart[f]
        for (i in pts.indices) { m.cUV[(s + i) * 2] = uv[i * 2]; m.cUV[(s + i) * 2 + 1] = uv[i * 2 + 1] }
        return f
    }

    /**
     * 细分：三角形一分为四，四边形 / 多边形从中心分成若干四边形。相邻未选中的面会自动接上新加的中点，不留裂缝。
     * @return 细分后得到的面
     */
    fun subdivide(m: EditMesh, facesIn: IntArray): IntArray {
        val faces = liveFaces(m, facesIn)
        if (faces.isEmpty()) return faces
        val oldFaceCount = m.faceCount
        val inSet = BooleanArray(oldFaceCount)
        for (f in faces) inSet[f] = true
        val mid = HashMap<Long, Int>()
        val out = IntList(faces.size * 4)
        for (f in faces) {
            val n = m.fSize[f]; val s = m.fStart[f]
            val pts = IntArray(n) { m.cPoint[s + it] }
            val uv = FloatArray(n * 2) { m.cUV[s * 2 + it] }
            val mat = m.fMat[f]; val smooth = m.fSmooth[f]
            val mp = IntArray(n)
            val muv = FloatArray(n * 2)
            for (i in 0 until n) {
                val j = (i + 1) % n
                mp[i] = mid.getOrPut(edgeKey(pts[i], pts[j])) { m.addPointLerp(pts[i], pts[j], 0.5f) }
                muv[i * 2] = (uv[i * 2] + uv[j * 2]) * 0.5f; muv[i * 2 + 1] = (uv[i * 2 + 1] + uv[j * 2 + 1]) * 0.5f
            }
            m.fDead[f] = true; m.fSel[f] = false
            if (n == 3) {
                for (i in 0 until 3) {
                    val h = (i + 2) % 3
                    out.add(faceUV(m, intArrayOf(pts[i], mp[i], mp[h]), floatArrayOf(uv[i * 2], uv[i * 2 + 1], muv[i * 2], muv[i * 2 + 1], muv[h * 2], muv[h * 2 + 1]), mat, smooth))
                }
                out.add(faceUV(m, intArrayOf(mp[0], mp[1], mp[2]), floatArrayOf(muv[0], muv[1], muv[2], muv[3], muv[4], muv[5]), mat, smooth))
            } else {
                var cx = 0f; var cy = 0f; var cz = 0f; var cu = 0f; var cv = 0f
                for (i in 0 until n) { cx += m.pos[pts[i] * 3]; cy += m.pos[pts[i] * 3 + 1]; cz += m.pos[pts[i] * 3 + 2]; cu += uv[i * 2]; cv += uv[i * 2 + 1] }
                val c = m.addPoint(cx / n, cy / n, cz / n, pts[0])
                m.pOrig[c] = -1
                m.blendWeights(c, pts, FloatArray(n) { 1f / n })
                cu /= n; cv /= n
                for (i in 0 until n) {
                    val h = (i + n - 1) % n
                    out.add(faceUV(m, intArrayOf(pts[i], mp[i], c, mp[h]),
                        floatArrayOf(uv[i * 2], uv[i * 2 + 1], muv[i * 2], muv[i * 2 + 1], cu, cv, muv[h * 2], muv[h * 2 + 1]), mat, smooth))
                }
            }
        }
        for (f in 0 until oldFaceCount) if (!inSet[f] && !m.fDead[f]) insertMidpoints(m, f, mid)
        m.edgeSel.clear()
        return out.toArray()
    }

    /** 三角化：把多边形拆成三角形（扇形）。 */
    fun triangulate(m: EditMesh, facesIn: IntArray): IntArray {
        val faces = liveFaces(m, facesIn)
        val out = IntList(faces.size * 2)
        for (f in faces) {
            val n = m.fSize[f]
            if (n <= 3) { out.add(f); continue }
            val s = m.fStart[f]
            val pts = IntArray(n) { m.cPoint[s + it] }
            val uv = FloatArray(n * 2) { m.cUV[s * 2 + it] }
            val mat = m.fMat[f]; val smooth = m.fSmooth[f]
            m.fDead[f] = true; m.fSel[f] = false
            for (i in 1 until n - 1) {
                out.add(faceUV(m, intArrayOf(pts[0], pts[i], pts[i + 1]),
                    floatArrayOf(uv[0], uv[1], uv[i * 2], uv[i * 2 + 1], uv[(i + 1) * 2], uv[(i + 1) * 2 + 1]), mat, smooth))
            }
        }
        return out.toArray()
    }

    /** 在边 (a,b) 上按比例 t 加一个点，所有用到这条边的面都接上它。返回新点；这条边不存在时返回 -1。 */
    fun splitEdge(m: EditMesh, a: Int, b: Int, t: Float = 0.5f): Int {
        var exists = false
        m.forEachEdge { p, q -> if ((p == a && q == b) || (p == b && q == a)) exists = true }
        if (!exists) return -1
        val np = m.addPointLerp(a, b, t.coerceIn(0.01f, 0.99f))
        val mid = HashMap<Long, Int>()
        mid[edgeKey(a, b)] = np
        val fc = m.faceCount
        for (f in 0 until fc) if (!m.fDead[f]) insertMidpoints(m, f, mid)
        m.edgeSel.remove(edgeKey(a, b))
        return np
    }

    /** 连接：把同一个面上不相邻的两个点连起来，面一分为二。返回是否成功。 */
    fun connect(m: EditMesh, p: Int, q: Int): Boolean {
        for (f in 0 until m.faceCount) {
            if (m.fDead[f]) continue
            val s = m.fStart[f]; val n = m.fSize[f]
            var ip = -1; var iq = -1
            for (i in 0 until n) { if (m.cPoint[s + i] == p) ip = i; if (m.cPoint[s + i] == q) iq = i }
            if (ip < 0 || iq < 0) continue
            val d = (iq - ip + n) % n
            if (d < 2 || d > n - 2) continue
            val pts = IntArray(n) { m.cPoint[s + it] }
            val uv = FloatArray(n * 2) { m.cUV[s * 2 + it] }
            val mat = m.fMat[f]; val smooth = m.fSmooth[f]; val sel = m.fSel[f]
            m.fDead[f] = true; m.fSel[f] = false
            // 环 A：ip → iq；环 B：iq → ip
            val na = d + 1
            val pa = IntArray(na) { pts[(ip + it) % n] }
            val ua = FloatArray(na * 2) { uv[((ip + it / 2) % n) * 2 + it % 2] }
            val nb = n - d + 1
            val pb = IntArray(nb) { pts[(iq + it) % n] }
            val ub = FloatArray(nb * 2) { uv[((iq + it / 2) % n) * 2 + it % 2] }
            val fa = faceUV(m, pa, ua, mat, smooth); val fb = faceUV(m, pb, ub, mat, smooth)
            m.fSel[fa] = sel; m.fSel[fb] = sel
            return true
        }
        return false
    }

    /**
     * 环切：从边 (a,b) 出发，沿着一圈四边形把对面的边都从中间切开，得到一圈新的边。
     * @return 新加的点（按环的顺序）；这条边不在任何四边形上时返回空
     */
    fun loopCut(m: EditMesh, a: Int, b: Int, t: Float = 0.5f): IntArray {
        // 边 → 相邻的面（最多记两个）
        val ef = HashMap<Long, IntArray>()
        for (f in 0 until m.faceCount) {
            if (m.fDead[f]) continue
            val s = m.fStart[f]; val n = m.fSize[f]
            for (i in 0 until n) {
                val e = ef.getOrPut(edgeKey(m.cPoint[s + i], m.cPoint[s + (i + 1) % n])) { intArrayOf(-1, -1, 0) }
                if (e[2] < 2) e[e[2]] = f
                e[2]++
            }
        }
        val start = edgeKey(a, b)
        val se = ef[start] ?: return IntArray(0)
        val ringFaces = ArrayList<Int>()
        // 每个环面里被切的两条边：(p0,p1) 与对面的 (p2,p3)，按面内的角序号记
        val ringCut = ArrayList<Int>()
        val visited = HashSet<Int>()
        var closed = false
        for (dir in 0 until 2) {
            if (closed) break
            var f = se[dir]
            var ea = a; var eb = b
            while (f >= 0 && m.fSize[f] == 4 && visited.add(f)) {
                val s = m.fStart[f]
                var i = -1
                for (k in 0 until 4) {
                    val p = m.cPoint[s + k]; val q = m.cPoint[s + (k + 1) % 4]
                    if ((p == ea && q == eb) || (p == eb && q == ea)) { i = k; break }
                }
                if (i < 0) break
                ringFaces.add(f); ringCut.add(i)
                ea = m.cPoint[s + (i + 2) % 4]; eb = m.cPoint[s + (i + 3) % 4]
                val k = edgeKey(ea, eb)
                if (k == start) { closed = true; break }
                val e = ef[k] ?: break
                if (e[2] > 2) break
                f = if (e[0] == f) e[1] else e[0]
            }
        }
        if (ringFaces.isEmpty()) return IntArray(0)
        val mid = HashMap<Long, Int>()
        val tt = t.coerceIn(0.01f, 0.99f)
        val order = IntList(ringFaces.size + 1)
        fun midOf(p: Int, q: Int, frac: Float): Int = mid.getOrPut(edgeKey(p, q)) { m.addPointLerp(p, q, frac).also { order.add(it) } }
        val isRing = BooleanArray(m.faceCount)
        val oldFaceCount = m.faceCount
        for ((k, f) in ringFaces.withIndex()) {
            isRing[f] = true
            val s = m.fStart[f]; val i = ringCut[k]
            val p = IntArray(4) { m.cPoint[s + (i + it) % 4] }
            val uv = FloatArray(8) { m.cUV[(s + (i + it / 2) % 4) * 2 + it % 2] }
            // 切点离同一侧的端点（p0 / p3）的比例保持一致，新的一圈边才是平行的
            val m1 = midOf(p[0], p[1], tt)
            val m2 = midOf(p[3], p[2], tt)
            // 这条边是别的面先建的话，比例是按它的方向算的；用实际位置求 UV 的插值比例
            val f1 = fracOf(m, p[0], p[1], m1); val f2 = fracOf(m, p[3], p[2], m2)
            val u1 = uv[0] + (uv[2] - uv[0]) * f1; val v1 = uv[1] + (uv[3] - uv[1]) * f1
            val u2 = uv[6] + (uv[4] - uv[6]) * f2; val v2 = uv[7] + (uv[5] - uv[7]) * f2
            val mat = m.fMat[f]; val smooth = m.fSmooth[f]
            m.fDead[f] = true; m.fSel[f] = false
            faceUV(m, intArrayOf(p[0], m1, m2, p[3]), floatArrayOf(uv[0], uv[1], u1, v1, u2, v2, uv[6], uv[7]), mat, smooth)
            faceUV(m, intArrayOf(m1, p[1], p[2], m2), floatArrayOf(u1, v1, uv[2], uv[3], uv[4], uv[5], u2, v2), mat, smooth)
        }
        for (f in 0 until oldFaceCount) if (!isRing[f] && !m.fDead[f]) insertMidpoints(m, f, mid)
        // 选中新的一圈边
        m.selMode = EditMesh.SEL_EDGE
        m.clearSelection()
        for (f in oldFaceCount until m.faceCount) {
            val s = m.fStart[f]; val n = m.fSize[f]
            for (i in 0 until n) {
                val p = m.cPoint[s + i]; val q = m.cPoint[s + (i + 1) % n]
                if (mid.containsValue(p) && mid.containsValue(q)) m.edgeSel.add(edgeKey(p, q))
            }
        }
        m.syncSelection()
        return order.toArray()
    }

    private fun fracOf(m: EditMesh, a: Int, b: Int, p: Int): Float {
        val d = m.dist2(a, b)
        return if (d < 1e-20f) 0.5f else sqrt(m.dist2(a, p) / d).coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------ 合并 / 焊接 / 补面

    /** 把这些点并成一个（放在它们的中心）。返回保留下来的那个点。 */
    fun mergeToCenter(m: EditMesh, ptsIn: IntArray): Int {
        val pts = livePoints(m, ptsIn)
        if (pts.size < 2) return if (pts.isEmpty()) -1 else pts[0]
        var x = 0f; var y = 0f; var z = 0f
        for (p in pts) { x += m.pos[p * 3]; y += m.pos[p * 3 + 1]; z += m.pos[p * 3 + 2] }
        val t = pts[0]
        m.pos[t * 3] = x / pts.size; m.pos[t * 3 + 1] = y / pts.size; m.pos[t * 3 + 2] = z / pts.size
        val map = IntArray(m.pointCount) { it }
        for (p in pts) map[p] = t
        remapPoints(m, map)
        return t
    }

    private fun remapPoints(m: EditMesh, map: IntArray) {
        for (f in 0 until m.faceCount) {
            if (m.fDead[f]) continue
            val s = m.fStart[f]
            for (i in 0 until m.fSize[f]) { val p = m.cPoint[s + i]; if (map[p] != p) { m.cPoint[s + i] = map[p]; m.cOrig[s + i] = -1 } }
        }
        m.edgeSel.clear()
        m.cleanDegenerate()
    }

    /**
     * 按距离焊接：这些点里彼此距离小于 eps 的并成一个。
     * @return 被并掉的点数
     */
    fun weld(m: EditMesh, ptsIn: IntArray, eps: Float): Int {
        val pts = livePoints(m, ptsIn)
        if (pts.size < 2 || eps <= 0f) return 0
        val cell = eps
        val grid = HashMap<Long, IntList>()
        fun key(ix: Int, iy: Int, iz: Int): Long = (ix.toLong() and 0x1FFFFF) or ((iy.toLong() and 0x1FFFFF) shl 21) or ((iz.toLong() and 0x1FFFFF) shl 42)
        val map = IntArray(m.pointCount) { it }
        var merged = 0
        val e2 = eps * eps
        for (p in pts) {
            val ix = Math.floor((m.pos[p * 3] / cell).toDouble()).toInt(); val iy = Math.floor((m.pos[p * 3 + 1] / cell).toDouble()).toInt(); val iz = Math.floor((m.pos[p * 3 + 2] / cell).toDouble()).toInt()
            var target = -1
            loop@ for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val l = grid[key(ix + dx, iy + dy, iz + dz)] ?: continue
                for (i in 0 until l.size) if (m.dist2(l.data[i], p) <= e2) { target = l.data[i]; break@loop }
            }
            if (target >= 0) { map[p] = target; merged++ }
            else grid.getOrPut(key(ix, iy, iz)) { IntList(4) }.add(p)
        }
        if (merged > 0) remapPoints(m, map)
        return merged
    }

    /**
     * 用选中的点补一个面（点按绕中心的角度排序）。
     * @param toward 希望面朝向的方向（比如指向相机）；null = 不管
     */
    fun fill(m: EditMesh, ptsIn: IntArray, material: Int, toward: FloatArray? = null): Int {
        val pts = livePoints(m, ptsIn)
        if (pts.size < 3) return -1
        var cx = 0f; var cy = 0f; var cz = 0f
        for (p in pts) { cx += m.pos[p * 3]; cy += m.pos[p * 3 + 1]; cz += m.pos[p * 3 + 2] }
        cx /= pts.size; cy /= pts.size; cz /= pts.size
        // 平面法线：离中心最远的点 a，与 (a-c) 叉积最大的点 b
        var a = pts[0]; var best = -1f
        for (p in pts) { val d = sq(m.pos[p * 3] - cx) + sq(m.pos[p * 3 + 1] - cy) + sq(m.pos[p * 3 + 2] - cz); if (d > best) { best = d; a = p } }
        val ax = m.pos[a * 3] - cx; val ay = m.pos[a * 3 + 1] - cy; val az = m.pos[a * 3 + 2] - cz
        var nx = 0f; var ny = 0f; var nz = 0f
        best = -1f
        for (p in pts) {
            val bx = m.pos[p * 3] - cx; val by = m.pos[p * 3 + 1] - cy; val bz = m.pos[p * 3 + 2] - cz
            val x = ay * bz - az * by; val y = az * bx - ax * bz; val z = ax * by - ay * bx
            val l = x * x + y * y + z * z
            if (l > best) { best = l; nx = x; ny = y; nz = z }
        }
        var l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l < 1e-12f) return -1
        nx /= l; ny /= l; nz /= l
        if (toward != null && nx * toward[0] + ny * toward[1] + nz * toward[2] < 0f) { nx = -nx; ny = -ny; nz = -nz }
        // 平面内的基：u = 归一化的 (a-c)，v = n × u
        l = sqrt(ax * ax + ay * ay + az * az)
        val ux = ax / l; val uy = ay / l; val uz = az / l
        val vx = ny * uz - nz * uy; val vy = nz * ux - nx * uz; val vz = nx * uy - ny * ux
        val ang = FloatArray(pts.size)
        for ((i, p) in pts.withIndex()) {
            val bx = m.pos[p * 3] - cx; val by = m.pos[p * 3 + 1] - cy; val bz = m.pos[p * 3 + 2] - cz
            ang[i] = atan2(bx * vx + by * vy + bz * vz, bx * ux + by * uy + bz * uz)
        }
        val order = pts.indices.sortedBy { ang[it] }
        val loop = IntArray(pts.size) { pts[order[it]] }
        val f = m.addFace(loop, loop.size, material, true)
        // 朝向不对就翻过来
        val t = FloatArray(3)
        m.faceNormalRaw(f, t)
        if (t[0] * nx + t[1] * ny + t[2] * nz < 0f) flip(m, intArrayOf(f))
        return f
    }

    private fun sq(x: Float) = x * x

    // ------------------------------------------------------------ 镜像 / 平滑 / 材质 / UV

    /**
     * 镜像复制：把选中的面沿某个轴翻到另一边（面的朝向自动翻转）。
     * @param axis 0=X 1=Y 2=Z
     * @param weld 贴着镜像平面的点两边共用，不重复创建
     * @param boneMirror 骨骼序号 → 对侧骨骼序号（左↔右）；null = 权重照抄
     */
    fun mirror(m: EditMesh, facesIn: IntArray, axis: Int = 0, weld: Boolean = true, boneMirror: IntArray? = null): IntArray {
        val faces = liveFaces(m, facesIn)
        val before = m.pointCount
        val map = IntArray(before) { -1 }
        var size = 0f
        val mn = FloatArray(3); val mx = FloatArray(3)
        if (m.bounds(mn, mx)) size = maxOf(mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2])
        val eps = maxOf(size * 1e-4f, 1e-6f)
        val out = IntArray(faces.size)
        for ((k, f) in faces.withIndex()) {
            val n = m.fSize[f]
            val pts = IntArray(n)
            for (i in 0 until n) {
                val p = m.cPoint[m.fStart[f] + i]
                if (map[p] < 0) {
                    if (weld && abs(m.pos[p * 3 + axis]) <= eps) { m.pos[p * 3 + axis] = 0f; map[p] = p }
                    else {
                        val q = m.addPoint(m.pos[p * 3], m.pos[p * 3 + 1], m.pos[p * 3 + 2], p)
                        m.pos[q * 3 + axis] = -m.pos[q * 3 + axis]
                        m.pOrig[q] = -1
                        if (boneMirror != null) for (c in 0 until 4) {
                            val b = m.boneIdx[q * 4 + c].toInt() and 0xFFFF
                            if (b < boneMirror.size) m.boneIdx[q * 4 + c] = boneMirror[b].toShort()
                        }
                        map[p] = q
                    }
                }
                pts[n - 1 - i] = map[p]
            }
            val nf = m.addFace(pts, n, m.fMat[f], m.fSmooth[f])
            for (i in 0 until n) {
                val src = m.fStart[f] + i; val dst = m.fStart[nf] + (n - 1 - i)
                m.cUV[dst * 2] = m.cUV[src * 2]; m.cUV[dst * 2 + 1] = m.cUV[src * 2 + 1]
            }
            out[k] = nf
        }
        return out
    }

    /** 平滑（松弛）：每个点向相邻点的平均位置靠拢。 */
    fun smooth(m: EditMesh, ptsIn: IntArray, factor: Float = 0.5f, iterations: Int = 1) {
        val pts = livePoints(m, ptsIn)
        if (pts.isEmpty()) return
        val sel = BooleanArray(m.pointCount)
        for (p in pts) sel[p] = true
        val sum = FloatArray(m.pointCount * 3)
        val cnt = IntArray(m.pointCount)
        repeat(iterations.coerceIn(1, 50)) {
            sum.fill(0f); cnt.fill(0)
            m.forEachEdge { a, b ->
                if (sel[a]) { sum[a * 3] += m.pos[b * 3]; sum[a * 3 + 1] += m.pos[b * 3 + 1]; sum[a * 3 + 2] += m.pos[b * 3 + 2]; cnt[a]++ }
                if (sel[b]) { sum[b * 3] += m.pos[a * 3]; sum[b * 3 + 1] += m.pos[a * 3 + 1]; sum[b * 3 + 2] += m.pos[a * 3 + 2]; cnt[b]++ }
            }
            for (p in pts) {
                if (cnt[p] == 0) continue
                for (k in 0 until 3) m.pos[p * 3 + k] += (sum[p * 3 + k] / cnt[p] - m.pos[p * 3 + k]) * factor
            }
        }
    }

    fun setSmooth(m: EditMesh, faces: IntArray, on: Boolean) {
        for (f in faces) if (f in 0 until m.faceCount && !m.fDead[f]) m.fSmooth[f] = on
    }

    fun assignMaterial(m: EditMesh, faces: IntArray, material: Int) {
        if (material !in m.materials.indices) return
        for (f in faces) if (f in 0 until m.faceCount && !m.fDead[f]) m.fMat[f] = material
    }

    /**
     * 盒式 UV 投影：每个面按自己朝向最接近的坐标轴平面投影。
     * @param scale 多少个单位对应贴图的一整张（UV 0..1）
     */
    fun projectUV(m: EditMesh, faces: IntArray, scale: Float = 1f) {
        val t = FloatArray(3)
        val k = 1f / maxOf(scale, 1e-6f)
        for (f in faces) {
            if (f !in 0 until m.faceCount || m.fDead[f]) continue
            m.faceNormalRaw(f, t)
            val ax = abs(t[0]); val ay = abs(t[1]); val az = abs(t[2])
            val s = m.fStart[f]
            for (i in 0 until m.fSize[f]) {
                val c = s + i; val p = m.cPoint[c] * 3
                val u: Float; val v: Float
                if (ay >= ax && ay >= az) { u = m.pos[p]; v = -m.pos[p + 2] }
                else if (ax >= az) { u = m.pos[p + 2] * (if (t[0] > 0f) 1f else -1f); v = -m.pos[p + 1] }
                else { u = m.pos[p] * (if (t[2] > 0f) -1f else 1f); v = -m.pos[p + 1] }
                m.setCornerUV(c, u * k + 0.5f, v * k + 0.5f)
            }
        }
    }

    /**
     * 加厚：给一片薄面加上厚度（复制一份背面并把边缘缝起来）。
     * @return 新生成的背面
     */
    fun solidify(m: EditMesh, facesIn: IntArray, thickness: Float): IntArray {
        val faces = liveFaces(m, facesIn)
        if (faces.isEmpty()) return faces
        // 点法线（面积加权）
        val pn = FloatArray(m.pointCount * 3)
        val t = FloatArray(3)
        for (f in faces) {
            m.faceNormalRaw(f, t)
            val s = m.fStart[f]
            for (i in 0 until m.fSize[f]) { val p = m.cPoint[s + i] * 3; pn[p] += t[0]; pn[p + 1] += t[1]; pn[p + 2] += t[2] }
        }
        val count = HashMap<Long, Int>()
        for (f in faces) {
            val s = m.fStart[f]; val n = m.fSize[f]
            for (i in 0 until n) { val k = edgeKey(m.cPoint[s + i], m.cPoint[s + (i + 1) % n]); count[k] = (count[k] ?: 0) + 1 }
        }
        val before = m.pointCount
        val map = IntArray(before) { -1 }
        val out = IntArray(faces.size)
        val border = ArrayList<IntArray>()
        for ((k, f) in faces.withIndex()) {
            val n = m.fSize[f]
            val pts = IntArray(n)
            for (i in 0 until n) {
                val p = m.cPoint[m.fStart[f] + i]
                if (map[p] < 0) {
                    val l = sqrt(pn[p * 3] * pn[p * 3] + pn[p * 3 + 1] * pn[p * 3 + 1] + pn[p * 3 + 2] * pn[p * 3 + 2])
                    val q = if (l > 1e-20f) thickness / l else 0f
                    map[p] = m.addPoint(m.pos[p * 3] - pn[p * 3] * q, m.pos[p * 3 + 1] - pn[p * 3 + 1] * q, m.pos[p * 3 + 2] - pn[p * 3 + 2] * q, p)
                }
                pts[n - 1 - i] = map[p]
                val nx = m.cPoint[m.fStart[f] + (i + 1) % n]
                if (count[edgeKey(p, nx)] == 1) border.add(intArrayOf(p, nx, f))
            }
            val nf = m.addFace(pts, n, m.fMat[f], m.fSmooth[f])
            for (i in 0 until n) {
                val src = m.fStart[f] + i; val dst = m.fStart[nf] + (n - 1 - i)
                m.cUV[dst * 2] = m.cUV[src * 2]; m.cUV[dst * 2 + 1] = m.cUV[src * 2 + 1]
            }
            out[k] = nf
        }
        val quad = IntArray(4)
        for (e in border) {
            // 正面的边是 a→b；侧壁要让法线朝外：(b, a, a', b')
            quad[0] = e[1]; quad[1] = e[0]; quad[2] = map[e[0]]; quad[3] = map[e[1]]
            m.addFace(quad, 4, m.fMat[e[2]], false)
        }
        return out
    }
}

package com.lumi.mmd.engine.mesh

import com.lumi.mmd.engine.model.BoneData
import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.ModelFormat
import com.lumi.mmd.engine.model.MorphData
import com.lumi.mmd.engine.model.TextureRef
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 可编辑的多边形网格（建模用）。
 *
 * 三层结构：
 *  - 点（point）：一个空间位置 + 骨骼权重。贴图接缝两侧原本重复的顶点在这里是同一个点，拖动时不会裂开；
 *  - 角（corner）：面的一个顶角，带自己的 UV 和法线；
 *  - 面（face）：任意边数的多边形，带材质序号和"平滑 / 平直"标记。
 *
 * 约定：坐标系与 MMD 相同（左手系、Y 朝上）；面的法线方向 = Σ cross(vᵢ, vᵢ₊₁)，
 * 即从正面看顶点按顺时针排列（和 PMX 一致）。
 *
 * 用法：[fromModel] 把模型拆成可编辑网格 → 各种编辑操作（[MeshOps]）→ [build] 重新生成渲染 / 导出用的 [ModelData]。
 */
class EditMesh {
    // ------------------------------------------------------------ 点
    var pointCount = 0
        private set
    var pos = FloatArray(64 * 3)
    var boneIdx = ShortArray(64 * 4)
    var boneW = FloatArray(64 * 4)
    var edgeScale = FloatArray(64)
    /** 这个点来自原模型的哪个顶点（表情数据靠它对应；挤出 / 复制出来的点沿用原来那个点的）；全新的点是 -1。 */
    var pOrig = IntArray(64)
    var pDead = BooleanArray(64)
    var pSel = BooleanArray(64)

    // ------------------------------------------------------------ 角
    var cornerCount = 0
        private set
    var cPoint = IntArray(256)
    var cUV = FloatArray(256 * 2)
    var cN = FloatArray(256 * 3)
    var cOrig = IntArray(256)

    // ------------------------------------------------------------ 面
    var faceCount = 0
        private set
    var fStart = IntArray(64)
    var fSize = IntArray(64)
    var fMat = IntArray(64)
    var fSmooth = BooleanArray(64)
    var fDead = BooleanArray(64)
    var fSel = BooleanArray(64)

    val materials = ArrayList<MaterialData>()
    val textures = ArrayList<TextureRef>()
    val bones = ArrayList<BoneData>()
    /** 来源模型：骨骼之外的东西（表情、刚体、显示枠、名字…）在 [build] 时从它带过去。 */
    var base: ModelData = ModelData()
    /** 原模型每个顶点的"表情指纹"：两个顶点在所有顶点 / UV 表情里的位移完全一样时指纹才相同。 */
    private var origSig = LongArray(0)
    var name = ""

    var selMode = SEL_FACE
    /** 边选择模式下选中的边（[edgeKey]）。 */
    val edgeSel = HashSet<Long>()
    /** 相邻两个平滑面的夹角超过这个角度就不再平滑过渡（自动锐边）。 */
    var creaseAngleDeg = 45f

    val livePointCount: Int get() { var n = 0; for (i in 0 until pointCount) if (!pDead[i]) n++; return n }
    val liveFaceCount: Int get() { var n = 0; for (i in 0 until faceCount) if (!fDead[i]) n++; return n }

    // ------------------------------------------------------------ 基本构造

    /** 新建一个点；copyFrom >= 0 时权重等属性照抄那个点。 */
    fun addPoint(x: Float, y: Float, z: Float, copyFrom: Int = -1): Int {
        val p = pointCount
        if (p + 1 > pDead.size) {
            val cap = maxOf(64, p * 2)
            pos = pos.copyOf(cap * 3); boneIdx = boneIdx.copyOf(cap * 4); boneW = boneW.copyOf(cap * 4)
            edgeScale = edgeScale.copyOf(cap); pOrig = pOrig.copyOf(cap); pDead = pDead.copyOf(cap); pSel = pSel.copyOf(cap)
        }
        pos[p * 3] = x; pos[p * 3 + 1] = y; pos[p * 3 + 2] = z
        if (copyFrom >= 0) {
            for (k in 0 until 4) { boneIdx[p * 4 + k] = boneIdx[copyFrom * 4 + k]; boneW[p * 4 + k] = boneW[copyFrom * 4 + k] }
            edgeScale[p] = edgeScale[copyFrom]
            pOrig[p] = pOrig[copyFrom]
        } else {
            pOrig[p] = -1
            for (k in 0 until 4) { boneIdx[p * 4 + k] = 0; boneW[p * 4 + k] = 0f }
            boneW[p * 4] = 1f
            edgeScale[p] = 1f
        }
        pDead[p] = false; pSel[p] = false
        pointCount = p + 1
        return p
    }

    /** 在 a、b 两点之间按比例 t 新建一个点，权重按比例混合。 */
    fun addPointLerp(a: Int, b: Int, t: Float): Int {
        val p = addPoint(pos[a * 3] + (pos[b * 3] - pos[a * 3]) * t, pos[a * 3 + 1] + (pos[b * 3 + 1] - pos[a * 3 + 1]) * t,
            pos[a * 3 + 2] + (pos[b * 3 + 2] - pos[a * 3 + 2]) * t, a)
        blendWeights(p, intArrayOf(a, b), floatArrayOf(1f - t, t))
        edgeScale[p] = edgeScale[a] + (edgeScale[b] - edgeScale[a]) * t
        pOrig[p] = -1
        return p
    }

    /** 把若干个点的骨骼权重按比例混合后写给 dst（只保留最大的 4 个）。 */
    fun blendWeights(dst: Int, src: IntArray, factor: FloatArray) {
        val ids = IntArray(16); val ws = FloatArray(16)
        var n = 0
        for (s in src.indices) {
            val o = src[s] * 4
            for (k in 0 until 4) {
                val w = boneW[o + k] * factor[s]
                if (w <= 0f) continue
                val b = boneIdx[o + k].toInt() and 0xFFFF
                var found = -1
                for (i in 0 until n) if (ids[i] == b) { found = i; break }
                if (found >= 0) ws[found] += w else if (n < 16) { ids[n] = b; ws[n] = w; n++ }
            }
        }
        val o = dst * 4
        var sum = 0f
        for (k in 0 until 4) {
            var best = -1
            for (i in 0 until n) if (ws[i] > 0f && (best < 0 || ws[i] > ws[best])) best = i
            if (best < 0) { boneIdx[o + k] = 0; boneW[o + k] = 0f }
            else { boneIdx[o + k] = ids[best].toShort(); boneW[o + k] = ws[best]; sum += ws[best]; ws[best] = 0f }
        }
        if (sum > 1e-9f) for (k in 0 until 4) boneW[o + k] /= sum else boneW[o] = 1f
    }

    private fun allocCorners(n: Int): Int {
        val c = cornerCount
        if (c + n > cPoint.size) {
            val cap = maxOf(256, (c + n) * 2)
            cPoint = cPoint.copyOf(cap); cUV = cUV.copyOf(cap * 2); cN = cN.copyOf(cap * 3); cOrig = cOrig.copyOf(cap)
        }
        cornerCount = c + n
        return c
    }

    /** 新建一个面。pts 是按顺时针（从正面看）排列的点。UV 先置 0，法线之后由 [recalcNormals] 算。 */
    fun addFace(pts: IntArray, n: Int = pts.size, material: Int = 0, smooth: Boolean = true): Int {
        val f = faceCount
        if (f + 1 > fDead.size) {
            val cap = maxOf(64, f * 2)
            fStart = fStart.copyOf(cap); fSize = fSize.copyOf(cap); fMat = fMat.copyOf(cap)
            fSmooth = fSmooth.copyOf(cap); fDead = fDead.copyOf(cap); fSel = fSel.copyOf(cap)
        }
        val c = allocCorners(n)
        for (i in 0 until n) {
            cPoint[c + i] = pts[i]
            cUV[(c + i) * 2] = 0f; cUV[(c + i) * 2 + 1] = 0f
            cN[(c + i) * 3] = 0f; cN[(c + i) * 3 + 1] = 1f; cN[(c + i) * 3 + 2] = 0f
            cOrig[c + i] = -1
        }
        fStart[f] = c; fSize[f] = n; fMat[f] = material.coerceIn(0, maxOf(0, materials.size - 1))
        fSmooth[f] = smooth; fDead[f] = false; fSel[f] = false
        faceCount = f + 1
        return f
    }

    /** 把面 f 的顶点环换成新的一组角（旧的角作废）。返回新的起始角序号；UV / 法线要调用方填。 */
    fun resizeFace(f: Int, n: Int): Int {
        val c = allocCorners(n)
        fStart[f] = c; fSize[f] = n
        for (i in 0 until n) cOrig[c + i] = -1
        return c
    }

    fun copyCorner(dst: Int, src: Int) {
        cPoint[dst] = cPoint[src]
        cUV[dst * 2] = cUV[src * 2]; cUV[dst * 2 + 1] = cUV[src * 2 + 1]
        cN[dst * 3] = cN[src * 3]; cN[dst * 3 + 1] = cN[src * 3 + 1]; cN[dst * 3 + 2] = cN[src * 3 + 2]
        cOrig[dst] = cOrig[src]
    }

    fun setCornerUV(c: Int, u: Float, v: Float) { cUV[c * 2] = u; cUV[c * 2 + 1] = v; cOrig[c] = -1 }

    // ------------------------------------------------------------ 几何量

    /** 面的法线（未归一化，长度 = 面积的两倍）。 */
    fun faceNormalRaw(f: Int, out: FloatArray, oo: Int = 0) {
        val s = fStart[f]; val n = fSize[f]
        var nx = 0f; var ny = 0f; var nz = 0f
        val p = pos
        val a0 = cPoint[s] * 3
        // 以第一个顶点为原点做扇形叉积求和，数值上比直接用绝对坐标稳定
        var bx = p[cPoint[s + 1] * 3] - p[a0]; var by = p[cPoint[s + 1] * 3 + 1] - p[a0 + 1]; var bz = p[cPoint[s + 1] * 3 + 2] - p[a0 + 2]
        for (i in 2 until n) {
            val c = cPoint[s + i] * 3
            val cx = p[c] - p[a0]; val cy = p[c + 1] - p[a0 + 1]; val cz = p[c + 2] - p[a0 + 2]
            nx += by * cz - bz * cy; ny += bz * cx - bx * cz; nz += bx * cy - by * cx
            bx = cx; by = cy; bz = cz
        }
        out[oo] = nx; out[oo + 1] = ny; out[oo + 2] = nz
    }

    fun faceNormal(f: Int, out: FloatArray, oo: Int = 0) {
        faceNormalRaw(f, out, oo)
        val l = sqrt(out[oo] * out[oo] + out[oo + 1] * out[oo + 1] + out[oo + 2] * out[oo + 2])
        if (l > 1e-20f) { out[oo] /= l; out[oo + 1] /= l; out[oo + 2] /= l } else { out[oo] = 0f; out[oo + 1] = 1f; out[oo + 2] = 0f }
    }

    fun faceCenter(f: Int, out: FloatArray, oo: Int = 0) {
        val s = fStart[f]; val n = fSize[f]
        var x = 0f; var y = 0f; var z = 0f
        for (i in 0 until n) { val c = cPoint[s + i] * 3; x += pos[c]; y += pos[c + 1]; z += pos[c + 2] }
        out[oo] = x / n; out[oo + 1] = y / n; out[oo + 2] = z / n
    }

    /** 点 → 所在的角（CSR）。拓扑变化后重新建。 */
    class Adjacency(val start: IntArray, val corners: IntArray, val cornerFace: IntArray)

    fun buildAdjacency(): Adjacency {
        val start = IntArray(pointCount + 1)
        for (f in 0 until faceCount) {
            if (fDead[f]) continue
            val s = fStart[f]
            for (i in 0 until fSize[f]) start[cPoint[s + i] + 1]++
        }
        for (i in 0 until pointCount) start[i + 1] += start[i]
        val fill = start.copyOf(pointCount)
        val corners = IntArray(start[pointCount])
        val cornerFace = IntArray(cornerCount)
        for (f in 0 until faceCount) {
            if (fDead[f]) continue
            val s = fStart[f]
            for (i in 0 until fSize[f]) { val c = s + i; corners[fill[cPoint[c]]++] = c; cornerFace[c] = f }
        }
        return Adjacency(start, corners, cornerFace)
    }

    /**
     * 重算法线。mask 为 null 时全部重算；否则只算这些点以及与它们共面的点（这些面的朝向变了）。
     * 平直的面用面法线；平滑的面在夹角不超过 [creaseAngleDeg] 的相邻面之间按面积加权平均。
     */
    fun recalcNormals(mask: BooleanArray? = null) {
        val fn = FloatArray(faceCount * 3)
        val fu = FloatArray(faceCount * 3)
        for (f in 0 until faceCount) {
            if (fDead[f]) continue
            faceNormalRaw(f, fn, f * 3)
            val l = sqrt(fn[f * 3] * fn[f * 3] + fn[f * 3 + 1] * fn[f * 3 + 1] + fn[f * 3 + 2] * fn[f * 3 + 2])
            if (l > 1e-20f) { fu[f * 3] = fn[f * 3] / l; fu[f * 3 + 1] = fn[f * 3 + 1] / l; fu[f * 3 + 2] = fn[f * 3 + 2] / l } else fu[f * 3 + 1] = 1f
        }
        var todo: BooleanArray? = null
        if (mask != null) {
            todo = BooleanArray(pointCount)
            for (f in 0 until faceCount) {
                if (fDead[f]) continue
                val s = fStart[f]; val n = fSize[f]
                var hit = false
                for (i in 0 until n) { val p = cPoint[s + i]; if (p < mask.size && mask[p]) { hit = true; break } }
                if (hit) for (i in 0 until n) todo[cPoint[s + i]] = true
            }
        }
        val adj = buildAdjacency()
        val cosLimit = kotlin.math.cos(creaseAngleDeg.coerceIn(0f, 180f) * (Math.PI / 180.0)).toFloat()
        for (p in 0 until pointCount) {
            if (pDead[p] || (todo != null && !todo[p])) continue
            val a = adj.start[p]; val b = adj.start[p + 1]
            for (i in a until b) {
                val c = adj.corners[i]
                val f = adj.cornerFace[c]
                var nx: Float; var ny: Float; var nz: Float
                if (!fSmooth[f]) {
                    nx = fu[f * 3]; ny = fu[f * 3 + 1]; nz = fu[f * 3 + 2]
                } else {
                    nx = 0f; ny = 0f; nz = 0f
                    for (j in a until b) {
                        val g = adj.cornerFace[adj.corners[j]]
                        if (g != f) {
                            if (!fSmooth[g]) continue
                            if (fu[f * 3] * fu[g * 3] + fu[f * 3 + 1] * fu[g * 3 + 1] + fu[f * 3 + 2] * fu[g * 3 + 2] < cosLimit) continue
                        }
                        nx += fn[g * 3]; ny += fn[g * 3 + 1]; nz += fn[g * 3 + 2]
                    }
                    val l = sqrt(nx * nx + ny * ny + nz * nz)
                    if (l > 1e-20f) { nx /= l; ny /= l; nz /= l } else { nx = fu[f * 3]; ny = fu[f * 3 + 1]; nz = fu[f * 3 + 2] }
                }
                if (cN[c * 3] != nx || cN[c * 3 + 1] != ny || cN[c * 3 + 2] != nz) {
                    cN[c * 3] = nx; cN[c * 3 + 1] = ny; cN[c * 3 + 2] = nz
                }
            }
        }
    }

    /** 没有任何面用到的点标记为删除。 */
    fun removeOrphanPoints() {
        val used = BooleanArray(pointCount)
        for (f in 0 until faceCount) {
            if (fDead[f]) continue
            val s = fStart[f]
            for (i in 0 until fSize[f]) used[cPoint[s + i]] = true
        }
        for (p in 0 until pointCount) if (!used[p]) { pDead[p] = true; pSel[p] = false }
    }

    /** 去掉面里连续重复的点；剩下不足 3 个点的面删掉。合并点之后调用。 */
    fun cleanDegenerate() {
        for (f in 0 until faceCount) {
            if (fDead[f]) continue
            val s = fStart[f]; val n = fSize[f]
            var dup = false
            for (i in 0 until n) if (cPoint[s + i] == cPoint[s + (i + 1) % n]) { dup = true; break }
            if (!dup) {
                // 不相邻的重复点（面"捏"成了 8 字形）：整面删掉
                var pinch = false
                loop@ for (i in 0 until n) for (j in i + 2 until n) if (cPoint[s + i] == cPoint[s + j] && !(i == 0 && j == n - 1)) { pinch = true; break@loop }
                if (pinch && n <= 4) { fDead[f] = true; fSel[f] = false }
                continue
            }
            val keep = IntArray(n)
            var m = 0
            for (i in 0 until n) if (cPoint[s + i] != cPoint[s + (i + 1) % n]) keep[m++] = s + i
            if (m < 3) { fDead[f] = true; fSel[f] = false; continue }
            // 原地压缩（m <= n，不会覆盖还没读到的角）
            for (i in 0 until m) if (keep[i] != s + i) copyCorner(s + i, keep[i])
            fSize[f] = m
        }
        removeOrphanPoints()
    }

    // ------------------------------------------------------------ 选择

    fun clearSelection() {
        java.util.Arrays.fill(pSel, 0, pointCount, false)
        java.util.Arrays.fill(fSel, 0, faceCount, false)
        edgeSel.clear()
    }

    fun selectAll() {
        for (p in 0 until pointCount) pSel[p] = !pDead[p]
        for (f in 0 until faceCount) fSel[f] = !fDead[f]
        edgeSel.clear()
        if (selMode == SEL_EDGE) forEachEdge { a, b -> edgeSel.add(edgeKey(a, b)) }
    }

    inline fun forEachEdge(block: (Int, Int) -> Unit) {
        for (f in 0 until faceCount) {
            if (fDead[f]) continue
            val s = fStart[f]; val n = fSize[f]
            for (i in 0 until n) block(cPoint[s + i], cPoint[s + (i + 1) % n])
        }
    }

    /** 面 / 边模式下，按选中的面 / 边重新推出选中的点。改动选择后调用。 */
    fun syncSelection() {
        when (selMode) {
            SEL_FACE -> {
                java.util.Arrays.fill(pSel, 0, pointCount, false)
                for (f in 0 until faceCount) {
                    if (fDead[f] || !fSel[f]) continue
                    val s = fStart[f]
                    for (i in 0 until fSize[f]) pSel[cPoint[s + i]] = true
                }
            }
            SEL_EDGE -> {
                java.util.Arrays.fill(pSel, 0, pointCount, false)
                val it = edgeSel.iterator()
                while (it.hasNext()) {
                    val k = it.next()
                    val a = (k ushr 32).toInt(); val b = (k and 0xFFFFFFFFL).toInt()
                    if (a >= pointCount || b >= pointCount || pDead[a] || pDead[b]) { it.remove(); continue }
                    pSel[a] = true; pSel[b] = true
                }
                facesFromPoints()
            }
            else -> facesFromPoints()
        }
    }

    private fun facesFromPoints() {
        for (f in 0 until faceCount) {
            if (fDead[f]) { fSel[f] = false; continue }
            val s = fStart[f]
            var all = true
            for (i in 0 until fSize[f]) if (!pSel[cPoint[s + i]]) { all = false; break }
            fSel[f] = all
        }
    }

    /** 切换选择模式，尽量保留已选内容。 */
    fun changeSelMode(mode: Int) {
        if (mode == selMode) return
        syncSelection()
        selMode = mode
        edgeSel.clear()
        when (mode) {
            SEL_EDGE -> forEachEdge { a, b -> if (pSel[a] && pSel[b]) edgeSel.add(edgeKey(a, b)) }
            SEL_FACE -> facesFromPoints()
        }
        syncSelection()
    }

    fun invertSelection() {
        when (selMode) {
            SEL_FACE -> for (f in 0 until faceCount) fSel[f] = !fDead[f] && !fSel[f]
            SEL_EDGE -> {
                val all = HashSet<Long>()
                forEachEdge { a, b -> all.add(edgeKey(a, b)) }
                all.removeAll(edgeSel)
                edgeSel.clear(); edgeSel.addAll(all)
            }
            else -> for (p in 0 until pointCount) pSel[p] = !pDead[p] && !pSel[p]
        }
        syncSelection()
    }

    /** 把选择扩展到所有与已选部分相连的面（整个连通块）。 */
    fun selectLinked() {
        syncSelection()
        val reach = pSel.copyOf(pointCount)
        var changed = true
        while (changed) {
            changed = false
            for (f in 0 until faceCount) {
                if (fDead[f]) continue
                val s = fStart[f]; val n = fSize[f]
                var any = false; var all = true
                for (i in 0 until n) { if (reach[cPoint[s + i]]) any = true else all = false }
                if (any && !all) { for (i in 0 until n) reach[cPoint[s + i]] = true; changed = true }
            }
        }
        selectPoints(reach)
    }

    /** 选择向外扩一圈。 */
    fun growSelection() {
        syncSelection()
        val reach = pSel.copyOf(pointCount)
        for (f in 0 until faceCount) {
            if (fDead[f]) continue
            val s = fStart[f]; val n = fSize[f]
            var any = false
            for (i in 0 until n) if (pSel[cPoint[s + i]]) { any = true; break }
            if (any) for (i in 0 until n) reach[cPoint[s + i]] = true
        }
        selectPoints(reach)
    }

    /** 按"这些点被选中"设置当前模式下的选择。 */
    fun selectPoints(sel: BooleanArray) {
        for (p in 0 until pointCount) pSel[p] = p < sel.size && sel[p] && !pDead[p]
        when (selMode) {
            SEL_FACE -> facesFromPoints()
            SEL_EDGE -> { edgeSel.clear(); forEachEdge { a, b -> if (pSel[a] && pSel[b]) edgeSel.add(edgeKey(a, b)) } }
        }
        syncSelection()
    }

    fun selectByMaterial(material: Int, add: Boolean) {
        if (!add) clearSelection()
        val sel = if (add) { syncSelection(); pSel.copyOf(pointCount) } else BooleanArray(pointCount)
        for (f in 0 until faceCount) {
            if (fDead[f] || fMat[f] != material) continue
            val s = fStart[f]
            for (i in 0 until fSize[f]) sel[cPoint[s + i]] = true
        }
        selectPoints(sel)
    }

    fun selectedPoints(): IntArray {
        syncSelection()
        var n = 0
        for (p in 0 until pointCount) if (pSel[p] && !pDead[p]) n++
        val out = IntArray(n)
        n = 0
        for (p in 0 until pointCount) if (pSel[p] && !pDead[p]) out[n++] = p
        return out
    }

    fun selectedFaces(): IntArray {
        syncSelection()
        var n = 0
        for (f in 0 until faceCount) if (fSel[f] && !fDead[f]) n++
        val out = IntArray(n)
        n = 0
        for (f in 0 until faceCount) if (fSel[f] && !fDead[f]) out[n++] = f
        return out
    }

    fun setFacesSelected(faces: IntArray) {
        selMode = SEL_FACE
        clearSelection()
        for (f in faces) if (f in 0 until faceCount && !fDead[f]) fSel[f] = true
        syncSelection()
    }

    /** 选中元素的中心（选中点的平均位置）。没有选中时返回 false。 */
    fun selectionCenter(out: FloatArray): Boolean {
        var n = 0
        var x = 0.0; var y = 0.0; var z = 0.0
        for (p in 0 until pointCount) {
            if (!pSel[p] || pDead[p]) continue
            x += pos[p * 3]; y += pos[p * 3 + 1]; z += pos[p * 3 + 2]; n++
        }
        if (n == 0) return false
        out[0] = (x / n).toFloat(); out[1] = (y / n).toFloat(); out[2] = (z / n).toFloat()
        return true
    }

    fun bounds(min: FloatArray, max: FloatArray): Boolean {
        var any = false
        for (p in 0 until pointCount) {
            if (pDead[p]) continue
            for (k in 0 until 3) {
                val v = pos[p * 3 + k]
                if (!any || v < min[k]) min[k] = v
                if (!any || v > max[k]) max[k] = v
            }
            any = true
        }
        return any
    }

    // ------------------------------------------------------------ 快照（撤销）

    class Snapshot(
        val pointCount: Int, val pos: FloatArray, val boneIdx: ShortArray, val boneW: FloatArray, val edgeScale: FloatArray,
        val pOrig: IntArray, val pDead: BooleanArray, val pSel: BooleanArray,
        val cornerCount: Int, val cPoint: IntArray, val cUV: FloatArray, val cN: FloatArray, val cOrig: IntArray,
        val faceCount: Int, val fStart: IntArray, val fSize: IntArray, val fMat: IntArray, val fSmooth: BooleanArray, val fDead: BooleanArray, val fSel: BooleanArray,
        val materials: List<MaterialData>, val textures: List<TextureRef>, val bones: List<BoneData>, val selMode: Int, val edgeSel: Set<Long>,
    ) {
        val bytes: Int get() = pointCount * 42 + cornerCount * 28 + faceCount * 15
    }

    fun snapshot(): Snapshot = Snapshot(
        pointCount, pos.copyOf(pointCount * 3), boneIdx.copyOf(pointCount * 4), boneW.copyOf(pointCount * 4), edgeScale.copyOf(pointCount),
        pOrig.copyOf(pointCount), pDead.copyOf(pointCount), pSel.copyOf(pointCount),
        cornerCount, cPoint.copyOf(cornerCount), cUV.copyOf(cornerCount * 2), cN.copyOf(cornerCount * 3), cOrig.copyOf(cornerCount),
        faceCount, fStart.copyOf(faceCount), fSize.copyOf(faceCount), fMat.copyOf(faceCount), fSmooth.copyOf(faceCount), fDead.copyOf(faceCount), fSel.copyOf(faceCount),
        materials.map { copyMaterial(it) }, ArrayList(textures), ArrayList(bones), selMode, HashSet(edgeSel),
    )

    fun restore(s: Snapshot) {
        pointCount = s.pointCount
        val pc = maxOf(64, s.pointCount)
        pos = s.pos.copyOf(pc * 3); boneIdx = s.boneIdx.copyOf(pc * 4); boneW = s.boneW.copyOf(pc * 4); edgeScale = s.edgeScale.copyOf(pc)
        pOrig = s.pOrig.copyOf(pc); pDead = s.pDead.copyOf(pc); pSel = s.pSel.copyOf(pc)
        cornerCount = s.cornerCount
        val cc = maxOf(256, s.cornerCount)
        cPoint = s.cPoint.copyOf(cc); cUV = s.cUV.copyOf(cc * 2); cN = s.cN.copyOf(cc * 3); cOrig = s.cOrig.copyOf(cc)
        faceCount = s.faceCount
        val fc = maxOf(64, s.faceCount)
        fStart = s.fStart.copyOf(fc); fSize = s.fSize.copyOf(fc); fMat = s.fMat.copyOf(fc)
        fSmooth = s.fSmooth.copyOf(fc); fDead = s.fDead.copyOf(fc); fSel = s.fSel.copyOf(fc)
        materials.clear(); for (m in s.materials) materials.add(copyMaterial(m))
        textures.clear(); textures.addAll(s.textures)
        bones.clear(); bones.addAll(s.bones)
        selMode = s.selMode
        edgeSel.clear(); edgeSel.addAll(s.edgeSel)
    }

    // ------------------------------------------------------------ 生成渲染 / 导出用的模型数据

    /**
     * [build] 的结果。
     * @param vertexPoint 输出顶点 → 点（拖动时用它把点的新位置直接写回顶点数组）
     * @param vertexCorner 输出顶点 → 代表它的那个角（取法线用）
     * @param triFace 三角形 → 面（点选用）
     */
    class Built(val data: ModelData, val vertexPoint: IntArray, val vertexCorner: IntArray, val triFace: IntArray)

    fun build(): Built {
        val nMat = maxOf(1, materials.size)
        if (materials.isEmpty()) materials.add(defaultMaterial("材质1"))
        // 每个材质的面数
        val matFaces = IntArray(nMat + 1)
        for (f in 0 until faceCount) if (!fDead[f] && fSize[f] >= 3) matFaces[fMat[f].coerceIn(0, nMat - 1) + 1]++
        for (i in 0 until nMat) matFaces[i + 1] += matFaces[i]
        val order = IntArray(matFaces[nMat])
        val fill = matFaces.copyOf(nMat)
        for (f in 0 until faceCount) if (!fDead[f] && fSize[f] >= 3) order[fill[fMat[f].coerceIn(0, nMat - 1)]++] = f

        val head = IntArray(pointCount) { -1 }
        var next = IntArray(maxOf(16, pointCount + pointCount / 2))
        var vPoint = IntArray(next.size)
        var vCorner = IntArray(next.size)
        var vCount = 0
        var triCap = 0
        for (f in order) triCap += fSize[f] - 2
        val indices = IntArray(triCap * 3)
        val triFace = IntArray(triCap)
        var ni = 0
        val matIndexCount = IntArray(nMat)
        var loop = IntArray(16)
        val uv = cUV; val nn = cN
        for (m in 0 until nMat) {
            for (k in matFaces[m] until matFaces[m + 1]) {
                val f = order[k]
                val s = fStart[f]; val n = fSize[f]
                if (n > loop.size) loop = IntArray(n * 2)
                for (i in 0 until n) {
                    val c = s + i
                    val p = cPoint[c]
                    val src = sourceVertex(c)
                    val sig = if (src >= 0 && src < origSig.size) origSig[src] else 0L
                    var v = head[p]
                    while (v >= 0) {
                        val rc = vCorner[v]
                        if (uv[rc * 2] == uv[c * 2] && uv[rc * 2 + 1] == uv[c * 2 + 1] &&
                            nn[rc * 3] == nn[c * 3] && nn[rc * 3 + 1] == nn[c * 3 + 1] && nn[rc * 3 + 2] == nn[c * 3 + 2]) {
                            // 表情里位移不同的顶点（比如闭着的上下嘴唇）不能并成一个
                            val rs = sourceVertex(rc)
                            if ((if (rs >= 0 && rs < origSig.size) origSig[rs] else 0L) == sig) break
                        }
                        v = next[v]
                    }
                    if (v < 0) {
                        if (vCount + 1 > next.size) { val cap = next.size * 2; next = next.copyOf(cap); vPoint = vPoint.copyOf(cap); vCorner = vCorner.copyOf(cap) }
                        v = vCount++
                        vPoint[v] = p; vCorner[v] = c
                        next[v] = head[p]; head[p] = v
                    }
                    loop[i] = v
                }
                val before = ni
                ni = triangulate(f, loop, n, indices, ni)
                for (t in before / 3 until ni / 3) triFace[t] = f
                matIndexCount[m] += ni - before
            }
        }

        val d = ModelData()
        val b = base
        d.format = b.format
        d.name = name.ifEmpty { b.name }; d.nameEn = b.nameEn; d.comment = b.comment; d.commentEn = b.commentEn
        d.sourceFile = b.sourceFile; d.baseDir = b.baseDir
        d.suggestedScale = b.suggestedScale
        d.vertexCount = vCount
        val op = FloatArray(vCount * 3); val on = FloatArray(vCount * 3); val ou = FloatArray(vCount * 2)
        val obi = ShortArray(vCount * 4); val obw = FloatArray(vCount * 4); val oe = FloatArray(vCount)
        val bSdef = b.sdefC != null && b.sdefR0 != null && b.sdefR1 != null && b.skinTypes != null
        val types = ByteArray(vCount)
        var sdC: FloatArray? = null; var sd0: FloatArray? = null; var sd1: FloatArray? = null
        val bc = b.colors
        val oc = if (bc != null) FloatArray(vCount * 4) else null
        val boneCount = maxOf(1, bones.size)
        for (v in 0 until vCount) {
            val p = vPoint[v]; val c = vCorner[v]
            op[v * 3] = pos[p * 3]; op[v * 3 + 1] = pos[p * 3 + 1]; op[v * 3 + 2] = pos[p * 3 + 2]
            on[v * 3] = nn[c * 3]; on[v * 3 + 1] = nn[c * 3 + 1]; on[v * 3 + 2] = nn[c * 3 + 2]
            ou[v * 2] = uv[c * 2]; ou[v * 2 + 1] = uv[c * 2 + 1]
            var w1 = 0
            for (k in 0 until 4) {
                var bi = boneIdx[p * 4 + k].toInt() and 0xFFFF
                if (bi >= boneCount) bi = 0
                obi[v * 4 + k] = bi.toShort(); obw[v * 4 + k] = boneW[p * 4 + k]
                if (boneW[p * 4 + k] != 0f) w1 = k
            }
            oe[v] = edgeScale[p]
            types[v] = (if (w1 >= 2) 2 else w1).toByte()
            val o = sourceVertex(c)
            if (bSdef && o >= 0 && o < b.vertexCount && b.skinTypes!![o].toInt() == 3 && w1 == 1) {
                if (sdC == null) { sdC = FloatArray(vCount * 3); sd0 = FloatArray(vCount * 3); sd1 = FloatArray(vCount * 3) }
                types[v] = 3
                // 点被挪动过的话，SDEF 的参考点跟着平移
                for (k in 0 until 3) {
                    val dlt = pos[p * 3 + k] - b.positions[o * 3 + k]
                    sdC[v * 3 + k] = b.sdefC!![o * 3 + k] + dlt
                    sd0!![v * 3 + k] = b.sdefR0!![o * 3 + k] + dlt
                    sd1!![v * 3 + k] = b.sdefR1!![o * 3 + k] + dlt
                }
            }
            if (oc != null && bc != null) {
                val co = cOrig[c]
                if (co >= 0 && co * 4 + 3 < bc.size) System.arraycopy(bc, co * 4, oc, v * 4, 4)
                else { oc[v * 4] = 1f; oc[v * 4 + 1] = 1f; oc[v * 4 + 2] = 1f; oc[v * 4 + 3] = 1f }
            }
        }
        if (sdC != null) {
            // 非 SDEF 顶点的参考点随便填（不会被用到），但数组必须完整
            for (v in 0 until vCount) if (types[v].toInt() != 3) for (k in 0 until 3) { sdC[v * 3 + k] = op[v * 3 + k]; sd0!![v * 3 + k] = op[v * 3 + k]; sd1!![v * 3 + k] = op[v * 3 + k] }
        }
        d.positions = op; d.normals = on; d.uvs = ou
        d.boneIndices = obi; d.boneWeights = obw; d.edgeScales = oe; d.skinTypes = types
        d.sdefC = sdC; d.sdefR0 = sd0; d.sdefR1 = sd1
        d.colors = oc
        d.indices = if (ni == indices.size) indices else indices.copyOf(ni)
        d.textures.addAll(textures)
        var off = 0
        for (m in 0 until nMat) {
            val md = copyMaterial(materials[m])
            md.indexOffset = off; md.indexCount = matIndexCount[m]
            off += matIndexCount[m]
            d.materials.add(md)
        }
        if (bones.isEmpty()) bones.add(defaultBone())
        d.bones.addAll(bones)
        remapMorphs(d, vCorner, vCount)
        d.displayFrames.addAll(b.displayFrames)
        d.rigidBodies.addAll(b.rigidBodies)
        d.joints.addAll(b.joints)
        d.animations.addAll(b.animations)
        d.lights.addAll(b.lights)
        d.computeBounds()
        return Built(d, if (vPoint.size == vCount) vPoint else vPoint.copyOf(vCount), if (vCorner.size == vCount) vCorner else vCorner.copyOf(vCount),
            if (triFace.size == ni / 3) triFace else triFace.copyOf(ni / 3))
    }

    /**
     * 这个角"来自"原模型的哪个顶点：角本身没被改过就是它原来的顶点；
     * 否则退到它所在的点的来源（挤出 / 复制出来的点沿用原来那个点的）。表情、SDEF 等逐顶点数据靠它对应。
     */
    private fun sourceVertex(c: Int): Int {
        val o = cOrig[c]
        return if (o >= 0) o else pOrig[cPoint[c]]
    }

    private fun remapMorphs(d: ModelData, vCorner: IntArray, vCount: Int) {
        val b = base
        if (b.morphs.isEmpty()) return
        // 原顶点 → 由它产生的输出顶点（链表）
        var oHead: IntArray? = null
        var oNext: IntArray? = null
        for (m in b.morphs) {
            when (m.type) {
                MorphData.TYPE_VERTEX, MorphData.TYPE_UV, MorphData.TYPE_UV1, MorphData.TYPE_UV2, MorphData.TYPE_UV3, MorphData.TYPE_UV4 -> {
                    if (oHead == null) {
                        val h = IntArray(b.vertexCount) { -1 }
                        val nx = IntArray(vCount) { -1 }
                        for (v in vCount - 1 downTo 0) {
                            val src = sourceVertex(vCorner[v])
                            if (src >= 0 && src < h.size) { nx[v] = h[src]; h[src] = v }
                        }
                        oHead = h; oNext = nx
                    }
                    val stride = m.stride
                    val idx = com.lumi.mmd.engine.util.IntList(m.count + 16)
                    val data = com.lumi.mmd.engine.util.FloatList(m.count * stride + 64)
                    for (k in 0 until m.count) {
                        val ov = m.indices[k]
                        if (ov < 0 || ov >= oHead.size) continue
                        var v = oHead[ov]
                        while (v >= 0) {
                            idx.add(v)
                            for (c in 0 until stride) data.add(m.data[k * stride + c])
                            v = oNext!![v]
                        }
                    }
                    d.morphs.add(copyMorph(m, idx.toArray(), data.toArray()))
                }
                else -> d.morphs.add(m)
            }
        }
    }

    private fun copyMorph(m: MorphData, idx: IntArray, data: FloatArray): MorphData {
        val o = MorphData()
        o.name = m.name; o.nameEn = m.nameEn; o.panel = m.panel; o.type = m.type
        o.count = idx.size; o.indices = idx; o.data = data
        return o
    }

    private val triTmp = FloatArray(3)

    /** 把面 f 三角化，写进 out。loop 是各个角对应的输出顶点。凸的面用扇形，凹多边形用耳切。 */
    private fun triangulate(f: Int, loop: IntArray, n: Int, out: IntArray, start: Int): Int {
        var ni = start
        if (n == 3) { out[ni++] = loop[0]; out[ni++] = loop[1]; out[ni++] = loop[2]; return ni }
        val s = fStart[f]
        faceNormalRaw(f, triTmp)
        val nx = triTmp[0]; val ny = triTmp[1]; val nz = triTmp[2]
        if (n == 4) {
            // 固定从第 0 个角拉对角线（导入的三角形对并成四边形后，这样拆回去和原来一模一样）；
            // 只有这条对角线会让三角形翻面（凹四边形）时才换另一条
            val ok02 = cornerSign(s, 0, 1, 2, nx, ny, nz) >= 0f && cornerSign(s, 0, 2, 3, nx, ny, nz) >= 0f
            val use13 = !ok02 && cornerSign(s, 1, 2, 3, nx, ny, nz) >= 0f && cornerSign(s, 1, 3, 0, nx, ny, nz) >= 0f
            if (use13) {
                out[ni++] = loop[1]; out[ni++] = loop[2]; out[ni++] = loop[3]
                out[ni++] = loop[1]; out[ni++] = loop[3]; out[ni++] = loop[0]
            } else {
                out[ni++] = loop[0]; out[ni++] = loop[1]; out[ni++] = loop[2]
                out[ni++] = loop[0]; out[ni++] = loop[2]; out[ni++] = loop[3]
            }
            return ni
        }
        // 耳切
        val idx = IntArray(n) { it }
        var m = n
        var guard = 0
        while (m > 3 && guard < n * n) {
            var cut = false
            for (i in 0 until m) {
                val a = idx[(i + m - 1) % m]; val b = idx[i]; val c = idx[(i + 1) % m]
                if (cornerSign(s, a, b, c, nx, ny, nz) <= 0f) continue
                var inside = false
                for (j in 0 until m) {
                    val q = idx[j]
                    if (q == a || q == b || q == c) continue
                    if (cPoint[s + q] == cPoint[s + a] || cPoint[s + q] == cPoint[s + b] || cPoint[s + q] == cPoint[s + c]) continue
                    if (cornerSign(s, a, b, q, nx, ny, nz) >= 0f && cornerSign(s, b, c, q, nx, ny, nz) >= 0f && cornerSign(s, c, a, q, nx, ny, nz) >= 0f) { inside = true; break }
                }
                if (inside) continue
                out[ni++] = loop[a]; out[ni++] = loop[b]; out[ni++] = loop[c]
                for (j in i until m - 1) idx[j] = idx[j + 1]
                m--
                cut = true
                break
            }
            if (!cut) break
            guard++
        }
        // 剩下的（或者耳切卡住时）用扇形收尾
        for (i in 1 until m - 1) { out[ni++] = loop[idx[0]]; out[ni++] = loop[idx[i]]; out[ni++] = loop[idx[i + 1]] }
        return ni
    }

    /** 三角形 (a,b,c)（面内的角序号）的朝向与面法线的点积：> 0 表示同向。 */
    private fun cornerSign(s: Int, a: Int, b: Int, c: Int, nx: Float, ny: Float, nz: Float): Float {
        val p = pos
        val pa = cPoint[s + a] * 3; val pb = cPoint[s + b] * 3; val pc = cPoint[s + c] * 3
        val ux = p[pb] - p[pa]; val uy = p[pb + 1] - p[pa + 1]; val uz = p[pb + 2] - p[pa + 2]
        val vx = p[pc] - p[pa]; val vy = p[pc + 1] - p[pa + 1]; val vz = p[pc + 2] - p[pa + 2]
        return (uy * vz - uz * vy) * nx + (uz * vx - ux * vz) * ny + (ux * vy - uy * vx) * nz
    }

    fun dist2(a: Int, b: Int): Float {
        val dx = pos[a * 3] - pos[b * 3]; val dy = pos[a * 3 + 1] - pos[b * 3 + 1]; val dz = pos[a * 3 + 2] - pos[b * 3 + 2]
        return dx * dx + dy * dy + dz * dz
    }

    /** 整理：丢掉已删除的点 / 面 / 作废的角，序号重新排紧。撤销快照之外的引用（选择集）会跟着更新。 */
    fun compact() {
        val pMap = IntArray(pointCount) { -1 }
        var np = 0
        for (p in 0 until pointCount) if (!pDead[p]) pMap[p] = np++
        val nPos = FloatArray(maxOf(64, np) * 3); val nBi = ShortArray(maxOf(64, np) * 4); val nBw = FloatArray(maxOf(64, np) * 4)
        val nEs = FloatArray(maxOf(64, np)); val nOr = IntArray(maxOf(64, np)); val nSel = BooleanArray(maxOf(64, np))
        for (p in 0 until pointCount) {
            val q = pMap[p]
            if (q < 0) continue
            System.arraycopy(pos, p * 3, nPos, q * 3, 3)
            System.arraycopy(boneIdx, p * 4, nBi, q * 4, 4)
            System.arraycopy(boneW, p * 4, nBw, q * 4, 4)
            nEs[q] = edgeScale[p]; nOr[q] = pOrig[p]; nSel[q] = pSel[p]
        }
        var nf = 0; var nc = 0
        for (f in 0 until faceCount) if (!fDead[f]) { nf++; nc += fSize[f] }
        val ncp = IntArray(maxOf(256, nc)); val nuv = FloatArray(maxOf(256, nc) * 2); val nnn = FloatArray(maxOf(256, nc) * 3); val nco = IntArray(maxOf(256, nc))
        val nfs = IntArray(maxOf(64, nf)); val nfz = IntArray(maxOf(64, nf)); val nfm = IntArray(maxOf(64, nf))
        val nsm = BooleanArray(maxOf(64, nf)); val nfsel = BooleanArray(maxOf(64, nf))
        var c = 0; var g = 0
        for (f in 0 until faceCount) {
            if (fDead[f]) continue
            val s = fStart[f]; val n = fSize[f]
            nfs[g] = c; nfz[g] = n; nfm[g] = fMat[f]; nsm[g] = fSmooth[f]; nfsel[g] = fSel[f]
            for (i in 0 until n) {
                ncp[c] = pMap[cPoint[s + i]]
                nuv[c * 2] = cUV[(s + i) * 2]; nuv[c * 2 + 1] = cUV[(s + i) * 2 + 1]
                nnn[c * 3] = cN[(s + i) * 3]; nnn[c * 3 + 1] = cN[(s + i) * 3 + 1]; nnn[c * 3 + 2] = cN[(s + i) * 3 + 2]
                nco[c] = cOrig[s + i]
                c++
            }
            g++
        }
        val newEdges = HashSet<Long>()
        for (k in edgeSel) {
            val a = (k ushr 32).toInt(); val b = (k and 0xFFFFFFFFL).toInt()
            if (a < pointCount && b < pointCount && pMap[a] >= 0 && pMap[b] >= 0) newEdges.add(edgeKey(pMap[a], pMap[b]))
        }
        pos = nPos; boneIdx = nBi; boneW = nBw; edgeScale = nEs; pOrig = nOr; pSel = nSel; pDead = BooleanArray(nEs.size)
        pointCount = np
        cPoint = ncp; cUV = nuv; cN = nnn; cOrig = nco; cornerCount = nc
        fStart = nfs; fSize = nfz; fMat = nfm; fSmooth = nsm; fSel = nfsel; fDead = BooleanArray(nfs.size)
        faceCount = nf
        edgeSel.clear(); edgeSel.addAll(newEdges)
    }

    /** 作废的角 / 已删除的元素占比较大时整理一下。 */
    fun compactIfWasteful() {
        var live = 0
        for (f in 0 until faceCount) if (!fDead[f]) live += fSize[f]
        if (cornerCount > 1024 && cornerCount > live * 2) compact()
    }

    companion object {
        const val SEL_POINT = 0
        const val SEL_EDGE = 1
        const val SEL_FACE = 2

        fun edgeKey(a: Int, b: Int): Long = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()

        fun copyMaterial(m: MaterialData): MaterialData {
            val o = MaterialData()
            o.name = m.name; o.nameEn = m.nameEn
            System.arraycopy(m.diffuse, 0, o.diffuse, 0, 4); System.arraycopy(m.specular, 0, o.specular, 0, 3)
            o.shininess = m.shininess
            System.arraycopy(m.ambient, 0, o.ambient, 0, 3)
            o.flags = m.flags
            System.arraycopy(m.edgeColor, 0, o.edgeColor, 0, 4)
            o.edgeSize = m.edgeSize; o.texture = m.texture; o.sphereTexture = m.sphereTexture; o.sphereMode = m.sphereMode
            o.sharedToon = m.sharedToon; o.toonIndex = m.toonIndex; o.memo = m.memo
            o.indexOffset = m.indexOffset; o.indexCount = m.indexCount
            o.shading = m.shading
            System.arraycopy(m.emissive, 0, o.emissive, 0, 3)
            o.alphaMode = m.alphaMode; o.alphaCutoff = m.alphaCutoff
            return o
        }

        /** MMD 风格的默认材质：带描边、接受并投射阴影。 */
        fun defaultMaterial(name: String, r: Float = 0.8f, g: Float = 0.8f, b: Float = 0.82f, a: Float = 1f): MaterialData {
            val m = MaterialData()
            m.name = name
            m.diffuse[0] = r; m.diffuse[1] = g; m.diffuse[2] = b; m.diffuse[3] = a
            m.ambient[0] = r * 0.5f; m.ambient[1] = g * 0.5f; m.ambient[2] = b * 0.5f
            m.specular.fill(0.08f); m.shininess = 20f
            m.flags = MaterialData.FLAG_GROUND_SHADOW or MaterialData.FLAG_CAST_SHADOW or MaterialData.FLAG_RECEIVE_SHADOW or MaterialData.FLAG_EDGE
            m.edgeColor[0] = 0.1f; m.edgeColor[1] = 0.1f; m.edgeColor[2] = 0.12f; m.edgeColor[3] = 1f
            m.edgeSize = 0.8f
            m.sharedToon = true; m.toonIndex = 0
            m.shading = MaterialData.SHADING_MMD
            m.alphaMode = MaterialData.ALPHA_BLEND
            return m
        }

        fun defaultBone(): BoneData {
            val b = BoneData()
            b.name = "センター"; b.nameEn = "center"
            b.flags = BoneData.FLAG_ROTATABLE or BoneData.FLAG_TRANSLATABLE or BoneData.FLAG_VISIBLE or BoneData.FLAG_OPERABLE
            b.tailOffset[1] = 2f
            return b
        }

        /** 新建一个空网格（一根根骨骼、一个默认材质）。 */
        fun createEmpty(name: String): EditMesh {
            val m = EditMesh()
            m.name = name
            m.base = ModelData().also { it.format = ModelFormat.PRIMITIVE; it.name = name }
            m.materials.add(defaultMaterial("材质1"))
            m.bones.add(defaultBone())
            return m
        }

        /**
         * 把模型拆成可编辑网格。位置完全相同、权重也相同的顶点并成一个点；
         * 成对的三角形并回四边形（见下）。原样 [build] 回去得到的三角形与原模型一一对应。
         * @param positions 顶点位置的来源（默认是模型的初始位置；也可以传"部件调整"之后的结果）
         */
        fun fromModel(data: ModelData, positions: FloatArray = data.positions): EditMesh {
            val m = EditMesh()
            m.base = data
            m.name = data.name
            val n = data.vertexCount
            val bi = data.boneIndices; val bw = data.boneWeights
            val es = data.edgeScales
            // 开放寻址哈希表：位置的位模式 → 点
            var cap = 16
            while (cap < n * 2) cap = cap shl 1
            val table = IntArray(cap) { -1 }
            val mask = cap - 1
            val vp = IntArray(n)
            for (v in 0 until n) {
                val x = positions[v * 3] + 0f; val y = positions[v * 3 + 1] + 0f; val z = positions[v * 3 + 2] + 0f
                var h = java.lang.Float.floatToRawIntBits(x) * 73856093 xor java.lang.Float.floatToRawIntBits(y) * 19349663 xor java.lang.Float.floatToRawIntBits(z) * 83492791
                h = (h xor (h ushr 15)) and mask
                var found = -1
                while (true) {
                    val p = table[h]
                    if (p < 0) break
                    if (m.pos[p * 3] == x && m.pos[p * 3 + 1] == y && m.pos[p * 3 + 2] == z) {
                        var same = true
                        if (bi != null && bw != null) {
                            for (k in 0 until 4) if (m.boneIdx[p * 4 + k] != bi[v * 4 + k] || m.boneW[p * 4 + k] != bw[v * 4 + k]) { same = false; break }
                        }
                        if (same) { found = p; break }
                    }
                    h = (h + 1) and mask
                }
                if (found < 0) {
                    found = m.addPoint(x, y, z)
                    if (bi != null && bw != null) for (k in 0 until 4) { m.boneIdx[found * 4 + k] = bi[v * 4 + k]; m.boneW[found * 4 + k] = bw[v * 4 + k] }
                    m.edgeScale[found] = es?.get(v) ?: 1f
                    m.pOrig[found] = v
                    table[h] = found
                }
                vp[v] = found
            }
            // 表情指纹
            if (data.morphs.isNotEmpty()) {
                val sig = LongArray(n)
                for ((mi, mo) in data.morphs.withIndex()) {
                    val stride = when (mo.type) {
                        MorphData.TYPE_VERTEX -> 3
                        MorphData.TYPE_UV, MorphData.TYPE_UV1, MorphData.TYPE_UV2, MorphData.TYPE_UV3, MorphData.TYPE_UV4 -> 4
                        else -> 0
                    }
                    if (stride == 0) continue
                    for (k in 0 until mo.count) {
                        val v = mo.indices[k]
                        if (v < 0 || v >= n) continue
                        var hh = sig[v] * 1000003L + mi
                        for (c in 0 until stride) hh = hh * 31L + java.lang.Float.floatToRawIntBits(mo.data[k * stride + c] + 0f)
                        sig[v] = hh
                    }
                }
                m.origSig = sig
            }
            for (md in data.materials) m.materials.add(copyMaterial(md))
            if (m.materials.isEmpty()) m.materials.add(defaultMaterial("材质1"))
            m.textures.addAll(data.textures)
            m.bones.addAll(data.bones)
            if (m.bones.isEmpty()) m.bones.add(defaultBone())
            val idx = data.indices
            val loop = IntArray(4)
            val verts = IntArray(4)
            val pos = positions
            fun valid(i: Int): Boolean {
                val a = idx[i]; val b = idx[i + 1]; val c = idx[i + 2]
                if (a < 0 || b < 0 || c < 0 || a >= n || b >= n || c >= n) return false
                return vp[a] != vp[b] && vp[b] != vp[c] && vp[a] != vp[c]
            }
            // 三角形 i 的法线（未归一化）→ out
            fun triNormal(i: Int, out: FloatArray, oo: Int) {
                val a = idx[i] * 3; val b = idx[i + 1] * 3; val c = idx[i + 2] * 3
                val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
                val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
                out[oo] = uy * vz - uz * vy; out[oo + 1] = uz * vx - ux * vz; out[oo + 2] = ux * vy - uy * vx
            }
            val tn = FloatArray(6)
            for ((mi, md) in data.materials.withIndex()) {
                val end = minOf(md.indexOffset + md.indexCount, idx.size)
                var i = md.indexOffset
                while (i + 2 < end) {
                    if (!valid(i)) { i += 3; continue }
                    var count = 3
                    verts[0] = idx[i]; verts[1] = idx[i + 1]; verts[2] = idx[i + 2]
                    // 紧挨着的两个三角形共用一条边（同样的两个顶点、方向相反）且大致共面：并成一个四边形。
                    // 建模软件导出时四边形就是这样拆成两个三角形的；并回去之后固定从第 0 个角拉对角线，拆出来的三角形和原来完全一样。
                    val j = i + 3
                    if (j + 2 < end && valid(j)) {
                        merge@ for (r1 in 0 until 3) {
                            val x = idx[i + r1]; val y = idx[i + (r1 + 1) % 3]; val z = idx[i + (r1 + 2) % 3]
                            for (r2 in 0 until 3) {
                                // 第二个三角形转成 (x, z, w)
                                if (idx[j + r2] != x || idx[j + (r2 + 1) % 3] != z) continue
                                val w = idx[j + (r2 + 2) % 3]
                                if (w == y || vp[w] == vp[y] || vp[w] == vp[x] || vp[w] == vp[z]) continue
                                triNormal(i, tn, 0); triNormal(j, tn, 3)
                                val la = tn[0] * tn[0] + tn[1] * tn[1] + tn[2] * tn[2]; val lb = tn[3] * tn[3] + tn[4] * tn[4] + tn[5] * tn[5]
                                if (la < 1e-30f || lb < 1e-30f) continue
                                val dot = (tn[0] * tn[3] + tn[1] * tn[4] + tn[2] * tn[5]) / kotlin.math.sqrt(la * lb)
                                if (dot < 0.8f) continue
                                verts[0] = x; verts[1] = y; verts[2] = z; verts[3] = w
                                count = 4
                                break@merge
                            }
                        }
                    }
                    for (k in 0 until count) loop[k] = vp[verts[k]]
                    val f = m.addFace(loop, count, mi, true)
                    val s = m.fStart[f]
                    for (k in 0 until count) {
                        val v = verts[k]
                        m.cUV[(s + k) * 2] = data.uvs[v * 2]; m.cUV[(s + k) * 2 + 1] = data.uvs[v * 2 + 1]
                        m.cN[(s + k) * 3] = data.normals[v * 3]; m.cN[(s + k) * 3 + 1] = data.normals[v * 3 + 1]; m.cN[(s + k) * 3 + 2] = data.normals[v * 3 + 2]
                        m.cOrig[s + k] = v
                    }
                    i += if (count == 4) 6 else 3
                }
            }
            // 导入的模型多是有机曲面，锐边阈值放宽一些
            m.creaseAngleDeg = 70f
            m.removeOrphanPoints()
            return m
        }

        fun abs3(x: Float, y: Float, z: Float): Float = abs(x) + abs(y) + abs(z)
    }
}

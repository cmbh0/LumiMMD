package com.lumi.mmd.engine.scene

import com.lumi.mmd.engine.math.Mat4
import com.lumi.mmd.engine.math.Quat
import com.lumi.mmd.engine.mesh.EditMesh
import com.lumi.mmd.engine.mesh.EditMesh.Companion.edgeKey
import com.lumi.mmd.engine.mesh.MeshOps
import com.lumi.mmd.engine.mesh.MeshPrimitives
import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.model.TextureRef
import com.lumi.mmd.engine.render.LineBatch
import kotlin.math.sqrt

/**
 * 一次建模会话：把场景里的一个模型拆成可编辑网格（[EditMesh]），
 * 负责点选、操纵器拖动（实现 [MeshTarget]）、各种编辑操作、撤销 / 重做，以及把结果同步回场景。
 *
 * 同步方式：
 *  - 只挪动点（拖动操纵器）时，直接把新位置写进模型数据的顶点数组，渲染器只需重新上传顶点位置；
 *  - 拓扑变了（挤出、删除、细分……）或一次拖动结束后，重新生成模型数据并换一个模型实例（[ModelInstance.replacedBy]）。
 *
 * 会话期间模型保持初始姿势（[ModelInstance.restPose]），这样屏幕上看到的顶点位置就是模型数据里的位置。
 * 只在渲染线程上使用。
 */
class MeshSession(private val scene: Scene, model: ModelInstance) : MeshTarget {
    var model: ModelInstance = model
        private set
    val mesh: EditMesh
    private var built: EditMesh.Built

    /** 模型实例被替换时回调：调用方要释放旧实例的 GPU 资源，并把"选中的模型"指向新实例。 */
    var onReplaced: ((old: ModelInstance, neu: ModelInstance) -> Unit)? = null
    /** 有改动还没存盘。 */
    var dirty = false
    /** 每次网格内容变化加一（界面据此刷新统计信息）。 */
    var revision = 0
        private set
    /** true = 点一下是"加选 / 减选"；false = 点一下只选中这一个。 */
    var additive = true
    /** 屏幕密度（像素 / dp），决定点选容差。 */
    var density = 2f

    private sealed class UndoEntry { abstract val bytes: Int }
    private class PosUndo(val points: IntArray, val pos: FloatArray) : UndoEntry() { override val bytes: Int get() = points.size * 16 }
    private class FullUndo(val snap: EditMesh.Snapshot) : UndoEntry() { override val bytes: Int get() = snap.bytes }
    private val undoStack = ArrayList<UndoEntry>()
    private val redoStack = ArrayList<UndoEntry>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // 拖动期间
    private var dragPoints: IntArray? = null
    private var dragVerts: IntArray? = null
    private var dragMoved = false
    private val pivotTmp = FloatArray(3)
    private val t = FloatArray(32)
    private val inv = FloatArray(16)
    private val hit = PickResult()
    private val scr = FloatArray(3)

    init {
        // "调部件"里做过的移动 / 缩放 / 删除先合并进网格（删掉的部件连同空掉的材质一起拿掉），再拆成可编辑网格
        if (PartOps.hasEdits(model)) this.model = PartOps.merge(scene, model)
        val cur = this.model
        val d = cur.data
        val src = cur.morphs.basePositions
        mesh = EditMesh.fromModel(d, if (src.size >= d.vertexCount * 3) src else d.positions)
        cur.restPose = true
        built = mesh.build()
        swapInstance()
    }

    private fun swapInstance() {
        val old = model
        val neu = old.replacedBy(built.data)
        neu.restPose = true
        scene.replace(old, neu)
        model = neu
        neu.updateWorldMatrix()
        neu.update(scene.frame, 0f, false, true)
        onReplaced?.invoke(old, neu)
    }

    /** 拓扑或法线变了：重新生成模型数据并替换实例。 */
    private fun commit() {
        mesh.syncSelection()
        built = mesh.build()
        swapInstance()
        dirty = true
        revision++
        edgeCache = null
    }

    /**
     * 模型刚被存到了 [file]。之后每次改动都会从网格的"原始数据"重新生成模型，
     * 所以存盘位置要记在原始数据上——否则下一次存盘找不到上一次的文件，会另存出一份新的。
     */
    fun markSaved(file: java.io.File) {
        for (d in arrayOf(mesh.base, model.data, built.data)) {
            d.sourceFile = file
            if (d.baseDir == null) d.baseDir = file.parentFile
        }
        dirty = false
    }

    /** 结束会话：模型恢复正常的动作 / 姿势。 */
    fun finish() {
        mesh.compact()
        model.restPose = false
        model.poseDirty = true
    }

    // ------------------------------------------------------------------ 撤销 / 重做

    private fun trim(stack: ArrayList<UndoEntry>) {
        var total = 0L
        for (e in stack) total += e.bytes
        while (stack.size > 1 && (total > UNDO_BYTES || stack.size > 80)) { total -= stack.removeAt(0).bytes }
    }

    /** 做任何会改动网格的操作之前调用。 */
    private fun pushUndo() {
        undoStack.add(FullUndo(mesh.snapshot()))
        trim(undoStack)
        redoStack.clear()
    }

    private fun applyEntry(e: UndoEntry): UndoEntry = when (e) {
        is PosUndo -> {
            val cur = FloatArray(e.pos.size)
            for ((i, p) in e.points.withIndex()) {
                System.arraycopy(mesh.pos, p * 3, cur, i * 3, 3)
                System.arraycopy(e.pos, i * 3, mesh.pos, p * 3, 3)
            }
            recalcAround(e.points)
            PosUndo(e.points, cur)
        }
        is FullUndo -> {
            val cur = FullUndo(mesh.snapshot())
            mesh.restore(e.snap)
            cur
        }
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val e = undoStack.removeAt(undoStack.size - 1)
        redoStack.add(applyEntry(e))
        trim(redoStack)
        commit()
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val e = redoStack.removeAt(redoStack.size - 1)
        undoStack.add(applyEntry(e))
        commit()
        return true
    }

    private fun recalcAround(points: IntArray) {
        val mask = BooleanArray(mesh.pointCount)
        for (p in points) if (p < mask.size) mask[p] = true
        mesh.recalcNormals(mask)
    }

    // ------------------------------------------------------------------ MeshTarget（操纵器拖动）

    override fun pivot(out: FloatArray): Boolean = mesh.selectionCenter(out)

    private fun beginLiveDrag() {
        val pts = mesh.selectedPoints()
        dragPoints = pts
        val sel = BooleanArray(mesh.pointCount)
        for (p in pts) sel[p] = true
        val vp = built.vertexPoint
        var n = 0
        for (v in vp.indices) if (sel[vp[v]]) n++
        val verts = IntArray(n)
        n = 0
        for (v in vp.indices) if (sel[vp[v]]) verts[n++] = v
        dragVerts = verts
        dragMoved = false
    }

    override fun snapshot(): (() -> Unit)? {
        beginLiveDrag()
        val pts = dragPoints!!
        val pos = FloatArray(pts.size * 3)
        for ((i, p) in pts.withIndex()) System.arraycopy(mesh.pos, p * 3, pos, i * 3, 3)
        undoStack.add(PosUndo(pts, pos))
        trim(undoStack)
        redoStack.clear()
        return null
    }

    /** 把拖动中的点的新位置直接写进渲染用的顶点数组。 */
    private fun writeThrough() {
        val verts = dragVerts ?: return
        val vp = built.vertexPoint
        val dst = model.data.positions
        val src = mesh.pos
        for (v in verts) {
            val p = vp[v] * 3
            dst[v * 3] = src[p]; dst[v * 3 + 1] = src[p + 1]; dst[v * 3 + 2] = src[p + 2]
        }
        model.morphs.invalidatePositions()
        model.poseDirty = true
        dragMoved = true
    }

    override fun translate(dx: Float, dy: Float, dz: Float) {
        if (dragPoints == null) beginLiveDrag()
        MeshOps.translatePoints(mesh, dragPoints!!, dx, dy, dz)
        writeThrough()
    }

    override fun rotate(q: FloatArray, qo: Int) {
        if (dragPoints == null) beginLiveDrag()
        if (!mesh.selectionCenter(pivotTmp)) return
        val cx = pivotTmp[0]; val cy = pivotTmp[1]; val cz = pivotTmp[2]
        val pos = mesh.pos
        for (p in dragPoints!!) {
            Quat.rotate(q, qo, pos[p * 3] - cx, pos[p * 3 + 1] - cy, pos[p * 3 + 2] - cz, t, 0)
            pos[p * 3] = cx + t[0]; pos[p * 3 + 1] = cy + t[1]; pos[p * 3 + 2] = cz + t[2]
        }
        writeThrough()
    }

    override fun scale(sx: Float, sy: Float, sz: Float) {
        if (dragPoints == null) beginLiveDrag()
        if (!mesh.selectionCenter(pivotTmp)) return
        val cx = pivotTmp[0]; val cy = pivotTmp[1]; val cz = pivotTmp[2]
        val pos = mesh.pos
        for (p in dragPoints!!) {
            pos[p * 3] = cx + (pos[p * 3] - cx) * sx
            pos[p * 3 + 1] = cy + (pos[p * 3 + 1] - cy) * sy
            pos[p * 3 + 2] = cz + (pos[p * 3 + 2] - cz) * sz
        }
        writeThrough()
    }

    override fun scaleAxis(ax: Float, ay: Float, az: Float, factor: Float) {
        if (dragPoints == null) beginLiveDrag()
        if (!mesh.selectionCenter(pivotTmp)) return
        val cx = pivotTmp[0]; val cy = pivotTmp[1]; val cz = pivotTmp[2]
        val pos = mesh.pos
        val k = factor - 1f
        for (p in dragPoints!!) {
            val d = ((pos[p * 3] - cx) * ax + (pos[p * 3 + 1] - cy) * ay + (pos[p * 3 + 2] - cz) * az) * k
            pos[p * 3] += ax * d; pos[p * 3 + 1] += ay * d; pos[p * 3 + 2] += az * d
        }
        writeThrough()
    }

    /** 操纵器的"法线"朝向：Y 轴 = 选中面的平均法线（没有选中面时不提供）。拖动过程中保持不变，免得轴跟着抖。 */
    override fun orientation(out: FloatArray): Boolean {
        if (dragPoints != null && orientValid) { System.arraycopy(orientCache, 0, out, 0, 9); return true }
        val m = mesh
        var nx = 0f; var ny = 0f; var nz = 0f
        var any = false
        var area = 0f
        for (f in 0 until m.faceCount) {
            if (m.fDead[f] || !m.fSel[f]) continue
            m.faceNormalRaw(f, t, 0)
            nx += t[0]; ny += t[1]; nz += t[2]; any = true
            area += sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2])
        }
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        // 选中的是一整个封闭的形体（各个面的法线互相抵消）时，"平均法线"没有意义：用模型自己的坐标轴
        if (!any || l < 1e-12f || l < area * 0.35f) { orientValid = false; return false }
        nx /= l; ny /= l; nz /= l
        // X 轴：尽量水平（世界的上方向 × 法线）；法线本身朝上 / 朝下时取模型的 X 轴
        var xx = -nz; var xy = 0f; var xz = nx      // n × (0,1,0)
        var xl = sqrt(xx * xx + xz * xz)
        if (xl < 1e-3f) { xx = 1f; xy = 0f; xz = 0f; xl = 1f }
        xx /= xl; xz /= xl
        // Z = X × Y
        val zx = xy * nz - xz * ny; val zy = xz * nx - xx * nz; val zz = xx * ny - xy * nx
        out[0] = xx; out[1] = xy; out[2] = xz
        out[3] = nx; out[4] = ny; out[5] = nz
        out[6] = zx; out[7] = zy; out[8] = zz
        System.arraycopy(out, 0, orientCache, 0, 9)
        orientValid = true
        return true
    }

    private val orientCache = FloatArray(9)
    private var orientValid = false

    /** 选中面（没有选中时是全部面）的平均边长：界面上"挤出一点"之类的默认距离用它来定。 */
    fun typicalEdge(): Float {
        val m = mesh
        var sum = 0.0; var n = 0
        var anySel = false
        for (f in 0 until m.faceCount) if (!m.fDead[f] && m.fSel[f]) { anySel = true; break }
        for (f in 0 until m.faceCount) {
            if (m.fDead[f] || (anySel && !m.fSel[f])) continue
            val s = m.fStart[f]; val c = m.fSize[f]
            for (i in 0 until c) { sum += sqrt(m.dist2(m.cPoint[s + i], m.cPoint[s + (i + 1) % c]).toDouble()); n++ }
            if (n > 4000) break
        }
        return if (n == 0) 1f else (sum / n).toFloat()
    }

    override fun dragEnded() {
        val pts = dragPoints
        dragPoints = null; dragVerts = null
        if (pts == null) return
        if (!dragMoved) {
            // 没有真的拖动：把刚才压进去的撤销记录拿掉
            if (undoStack.isNotEmpty() && undoStack.last() is PosUndo) undoStack.removeAt(undoStack.size - 1)
            return
        }
        recalcAround(pts)
        commit()
    }

    // ------------------------------------------------------------------ 数值变换（面板 / MCP）

    /** 对选中的点做一次变换：先以选区中心为基准缩放、旋转（欧拉角，度，Y-X-Z 顺序），再平移。 */
    fun transformSelection(tx: Float, ty: Float, tz: Float, rxDeg: Float, ryDeg: Float, rzDeg: Float, sx: Float, sy: Float, sz: Float): Int {
        val pts = mesh.selectedPoints()
        if (pts.isEmpty()) return 0
        pushUndo()
        mesh.selectionCenter(pivotTmp)
        val cx = pivotTmp[0]; val cy = pivotTmp[1]; val cz = pivotTmp[2]
        val k = (Math.PI / 180.0).toFloat()
        Quat.fromEulerYXZ(t, 8, rxDeg * k, ryDeg * k, rzDeg * k)
        val pos = mesh.pos
        for (p in pts) {
            Quat.rotate(t, 8, (pos[p * 3] - cx) * sx, (pos[p * 3 + 1] - cy) * sy, (pos[p * 3 + 2] - cz) * sz, t, 0)
            pos[p * 3] = cx + t[0] + tx; pos[p * 3 + 1] = cy + t[1] + ty; pos[p * 3 + 2] = cz + t[2] + tz
        }
        recalcAround(pts)
        commit()
        return pts.size
    }

    /** 直接指定若干个点的新坐标。coords 依次是 x,y,z。 */
    fun setPoints(points: IntArray, coords: FloatArray): Int {
        pushUndo()
        var n = 0
        val moved = ArrayList<Int>()
        for ((i, p) in points.withIndex()) {
            if (p !in 0 until mesh.pointCount || mesh.pDead[p] || i * 3 + 2 >= coords.size) continue
            mesh.pos[p * 3] = coords[i * 3]; mesh.pos[p * 3 + 1] = coords[i * 3 + 1]; mesh.pos[p * 3 + 2] = coords[i * 3 + 2]
            moved.add(p); n++
        }
        recalcAround(moved.toIntArray())
        commit()
        return n
    }

    // ------------------------------------------------------------------ 选择

    var selMode: Int
        get() = mesh.selMode
        set(v) { mesh.changeSelMode(v); revision++ }

    fun selectAll() { mesh.selectAll(); mesh.syncSelection(); revision++ }
    fun clearSelection() { mesh.clearSelection(); revision++ }
    fun invertSelection() { mesh.invertSelection(); revision++ }
    fun selectLinked() { mesh.selectLinked(); revision++ }
    fun growSelection() { mesh.growSelection(); revision++ }
    fun selectMaterial(material: Int, add: Boolean = false) { mesh.selectByMaterial(material, add); revision++ }

    fun selectFaces(faces: IntArray, add: Boolean = false) {
        mesh.changeSelMode(EditMesh.SEL_FACE)
        if (!add) mesh.clearSelection()
        for (f in faces) if (f in 0 until mesh.faceCount && !mesh.fDead[f]) mesh.fSel[f] = true
        mesh.syncSelection(); revision++
    }

    fun selectPointList(points: IntArray, add: Boolean = false) {
        mesh.changeSelMode(EditMesh.SEL_POINT)
        if (!add) mesh.clearSelection()
        for (p in points) if (p in 0 until mesh.pointCount && !mesh.pDead[p]) mesh.pSel[p] = true
        mesh.syncSelection(); revision++
    }

    /** 选中朝向与 (dx,dy,dz) 夹角不超过 maxAngleDeg 的面（比如"所有朝上的面"）。方向在模型空间。 */
    fun selectFacesByNormal(dx: Float, dy: Float, dz: Float, maxAngleDeg: Float, add: Boolean = false, material: Int = -1): Int {
        mesh.changeSelMode(EditMesh.SEL_FACE)
        if (!add) mesh.clearSelection()
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        if (l < 1e-9f) return 0
        val limit = kotlin.math.cos(maxAngleDeg.coerceIn(0f, 180f) * (Math.PI / 180.0)).toFloat()
        var n = 0
        for (f in 0 until mesh.faceCount) {
            if (mesh.fDead[f] || (material >= 0 && mesh.fMat[f] != material)) continue
            mesh.faceNormal(f, t)
            if ((t[0] * dx + t[1] * dy + t[2] * dz) / l >= limit - 1e-5f) { mesh.fSel[f] = true; n++ }
        }
        mesh.syncSelection(); revision++
        return n
    }

    /** 选中落在一个长方体范围里的点 / 面（面按中心算）。范围在模型空间。 */
    fun selectInBox(min: FloatArray, max: FloatArray, faces: Boolean, add: Boolean = false): Int {
        mesh.changeSelMode(if (faces) EditMesh.SEL_FACE else EditMesh.SEL_POINT)
        if (!add) mesh.clearSelection()
        var n = 0
        if (faces) {
            for (f in 0 until mesh.faceCount) {
                if (mesh.fDead[f]) continue
                mesh.faceCenter(f, t)
                if (t[0] >= min[0] && t[0] <= max[0] && t[1] >= min[1] && t[1] <= max[1] && t[2] >= min[2] && t[2] <= max[2]) { mesh.fSel[f] = true; n++ }
            }
        } else {
            val p = mesh.pos
            for (i in 0 until mesh.pointCount) {
                if (mesh.pDead[i]) continue
                if (p[i * 3] >= min[0] && p[i * 3] <= max[0] && p[i * 3 + 1] >= min[1] && p[i * 3 + 1] <= max[1] && p[i * 3 + 2] >= min[2] && p[i * 3 + 2] <= max[2]) { mesh.pSel[i] = true; n++ }
            }
        }
        mesh.syncSelection(); revision++
        return n
    }

    /**
     * 单击选择。按当前的选择模式选最近的点 / 边，或手指下面的面。
     * @return 选择是否发生了变化
     */
    fun tap(x: Float, y: Float, w: Int, h: Int): Boolean {
        val cam = scene.camera
        val ro = FloatArray(3); val rd = FloatArray(3)
        cam.screenRay(x, y, w, h, ro, rd)
        hit.reset()
        Picker.pickModel(model, ro, rd, hit)
        val hitFace = if (hit.model === model && hit.triangle >= 0 && hit.triangle / 3 < built.triFace.size) built.triFace[hit.triangle / 3] else -1
        val m = mesh
        var changed = false
        when (m.selMode) {
            EditMesh.SEL_FACE -> {
                if (hitFace < 0) {
                    if (!additive && m.selectedFaces().isNotEmpty()) { m.clearSelection(); changed = true }
                } else {
                    if (additive) m.fSel[hitFace] = !m.fSel[hitFace]
                    else { m.clearSelection(); m.fSel[hitFace] = true }
                    changed = true
                }
            }
            EditMesh.SEL_POINT -> {
                val p = nearestPoint(x, y, w, h, if (hitFace >= 0) hit.t else Float.MAX_VALUE, ro, rd)
                if (p < 0) {
                    if (!additive) { m.clearSelection(); changed = true }
                } else {
                    if (additive) m.pSel[p] = !m.pSel[p] else { m.clearSelection(); m.pSel[p] = true }
                    changed = true
                }
            }
            else -> {
                val e = nearestEdge(x, y, w, h, if (hitFace >= 0) hit.t else Float.MAX_VALUE, ro, rd)
                if (e == 0L) {
                    if (!additive) { m.clearSelection(); changed = true }
                } else {
                    if (additive) { if (!m.edgeSel.remove(e)) m.edgeSel.add(e) } else { m.clearSelection(); m.edgeSel.add(e) }
                    changed = true
                }
            }
        }
        if (changed) { m.syncSelection(); revision++ }
        return changed
    }

    /** 点在世界空间里沿视线方向的深度（到射线起点的距离在射线方向上的投影）。 */
    private fun depthAlong(p: Int, ro: FloatArray, rd: FloatArray): Float {
        Mat4.transformPoint(model.worldMatrix, 0, mesh.pos[p * 3], mesh.pos[p * 3 + 1], mesh.pos[p * 3 + 2], t, 12)
        return (t[12] - ro[0]) * rd[0] + (t[13] - ro[1]) * rd[1] + (t[14] - ro[2]) * rd[2]
    }

    private fun project(p: Int, w: Int, h: Int, out: FloatArray, oo: Int): Boolean {
        Mat4.transformPoint(model.worldMatrix, 0, mesh.pos[p * 3], mesh.pos[p * 3 + 1], mesh.pos[p * 3 + 2], t, 12)
        if (!scene.camera.project(t[12], t[13], t[14], w, h, scr)) return false
        out[oo] = scr[0]; out[oo + 1] = scr[1]; out[oo + 2] = scr[2]
        return true
    }

    private fun nearestPoint(x: Float, y: Float, w: Int, h: Int, hitT: Float, ro: FloatArray, rd: FloatArray): Int {
        val tol = 26f * density
        var best = tol * tol
        var bestP = -1
        // 被手指下面的表面挡住的点不选（留一点余量）
        val limit = if (hitT == Float.MAX_VALUE) Float.MAX_VALUE else hitT * 1.02f + 0.05f
        val s = FloatArray(3)
        for (p in 0 until mesh.pointCount) {
            if (mesh.pDead[p]) continue
            if (!project(p, w, h, s, 0)) continue
            val dx = s[0] - x; val dy = s[1] - y
            val d2 = dx * dx + dy * dy
            if (d2 >= best) continue
            if (depthAlong(p, ro, rd) > limit) continue
            best = d2; bestP = p
        }
        return bestP
    }

    private fun nearestEdge(x: Float, y: Float, w: Int, h: Int, hitT: Float, ro: FloatArray, rd: FloatArray): Long {
        val tol = 22f * density
        var best = tol * tol
        var bestE = 0L
        val limit = if (hitT == Float.MAX_VALUE) Float.MAX_VALUE else hitT * 1.02f + 0.05f
        val sp = FloatArray(mesh.pointCount * 2)
        val ok = BooleanArray(mesh.pointCount)
        val s = FloatArray(3)
        for (p in 0 until mesh.pointCount) {
            if (mesh.pDead[p]) continue
            if (project(p, w, h, s, 0)) { sp[p * 2] = s[0]; sp[p * 2 + 1] = s[1]; ok[p] = true }
        }
        mesh.forEachEdge { a, b ->
            if (ok[a] && ok[b]) {
                val ax = sp[a * 2]; val ay = sp[a * 2 + 1]; val bx = sp[b * 2]; val by = sp[b * 2 + 1]
                val ex = bx - ax; val ey = by - ay
                val l2 = ex * ex + ey * ey
                var u = if (l2 > 1e-6f) ((x - ax) * ex + (y - ay) * ey) / l2 else 0f
                u = u.coerceIn(0f, 1f)
                val dx = ax + ex * u - x; val dy = ay + ey * u - y
                val d2 = dx * dx + dy * dy
                if (d2 < best) {
                    val da = depthAlong(a, ro, rd); val db = depthAlong(b, ro, rd)
                    if (da + (db - da) * u <= limit) { best = d2; bestE = edgeKey(a, b) }
                }
            }
        }
        return bestE
    }

    /** 框选：把屏幕矩形里的元素加进选择（面只选朝向相机的）。 */
    fun boxSelect(x0: Float, y0: Float, x1: Float, y1: Float, w: Int, h: Int): Int {
        val minX = minOf(x0, x1); val maxX = maxOf(x0, x1); val minY = minOf(y0, y1); val maxY = maxOf(y0, y1)
        val m = mesh
        val inside = BooleanArray(m.pointCount)
        val s = FloatArray(3)
        for (p in 0 until m.pointCount) {
            if (m.pDead[p]) continue
            if (project(p, w, h, s, 0) && s[0] >= minX && s[0] <= maxX && s[1] >= minY && s[1] <= maxY) inside[p] = true
        }
        if (!additive) m.clearSelection()
        var n = 0
        when (m.selMode) {
            EditMesh.SEL_POINT -> for (p in 0 until m.pointCount) if (inside[p]) { m.pSel[p] = true; n++ }
            EditMesh.SEL_EDGE -> m.forEachEdge { a, b -> if (inside[a] && inside[b] && m.edgeSel.add(edgeKey(a, b))) n++ }
            else -> {
                val cam = scene.camera
                if (!Mat4.invertAffine(inv, 0, model.worldMatrix, 0)) Mat4.identity(inv, 0)
                // 相机位置 / 视线方向换到模型空间
                Mat4.transformPoint(inv, 0, cam.position[0], cam.position[1], cam.position[2], t, 20)
                Mat4.transformDir(inv, 0, cam.forward[0], cam.forward[1], cam.forward[2], t, 24)
                for (f in 0 until m.faceCount) {
                    if (m.fDead[f]) continue
                    val st = m.fStart[f]
                    var all = true
                    for (i in 0 until m.fSize[f]) if (!inside[m.cPoint[st + i]]) { all = false; break }
                    if (!all) continue
                    m.faceNormalRaw(f, t, 0); m.faceCenter(f, t, 4)
                    val facing = if (cam.perspective) (t[4] - t[20]) * t[0] + (t[5] - t[21]) * t[1] + (t[6] - t[22]) * t[2]
                    else t[24] * t[0] + t[25] * t[1] + t[26] * t[2]
                    if (facing < 0f) { m.fSel[f] = true; n++ }
                }
            }
        }
        m.syncSelection(); revision++
        return n
    }

    // ------------------------------------------------------------------ 编辑操作

    private fun affectedMask(points: IntArray): BooleanArray {
        val mask = BooleanArray(mesh.pointCount)
        for (p in points) if (p < mask.size) mask[p] = true
        return mask
    }

    /** 在一次操作前后各取一次点数，新增的点 + 指定的点都要重算法线。 */
    private inline fun topo(extra: IntArray = IntArray(0), block: () -> Unit) {
        pushUndo()
        val before = mesh.pointCount
        block()
        val mask = BooleanArray(mesh.pointCount)
        for (p in before until mesh.pointCount) mask[p] = true
        for (p in extra) if (p < mask.size) mask[p] = true
        for (p in 0 until mesh.pointCount) if (mesh.pSel[p]) mask[p] = true
        mesh.recalcNormals(mask)
        commit()
    }

    /** 挤出选中的面。返回被挤出的面数。 */
    fun extrude(distance: Float): Int {
        val faces = mesh.selectedFaces()
        if (faces.isEmpty()) return 0
        val pts = MeshOps.pointsOfFaces(mesh, faces)
        topo(pts) {
            MeshOps.extrude(mesh, faces, distance)
            mesh.setFacesSelected(faces)
        }
        return faces.size
    }

    fun inset(amount: Float, depth: Float = 0f): Int {
        val faces = mesh.selectedFaces()
        if (faces.isEmpty()) return 0
        val pts = MeshOps.pointsOfFaces(mesh, faces)
        topo(pts) {
            MeshOps.inset(mesh, faces, amount, depth)
            mesh.setFacesSelected(faces)
        }
        return faces.size
    }

    fun subdivide(): Int {
        val faces = mesh.selectedFaces()
        if (faces.isEmpty()) return 0
        var n = 0
        topo(MeshOps.pointsOfFaces(mesh, faces)) {
            val out = MeshOps.subdivide(mesh, faces)
            n = out.size
            mesh.setFacesSelected(out)
        }
        return n
    }

    fun triangulate(): Int {
        val faces = mesh.selectedFaces()
        if (faces.isEmpty()) return 0
        var n = 0
        topo { val out = MeshOps.triangulate(mesh, faces); n = out.size; mesh.setFacesSelected(out) }
        return n
    }

    /** 删除选中的元素：面模式删面，点模式删点（连带用到它的面），边模式删掉这些边两侧的面。 */
    fun deleteSelected(): Int {
        var n = 0
        when (mesh.selMode) {
            EditMesh.SEL_POINT -> {
                val pts = mesh.selectedPoints()
                if (pts.isEmpty()) return 0
                val ring = neighbours(pts)
                topo(ring) { n = MeshOps.deletePoints(mesh, pts) ; mesh.clearSelection() }
            }
            EditMesh.SEL_EDGE -> {
                val keys = mesh.edgeSel.toLongArray()
                if (keys.isEmpty()) return 0
                val set = HashSet<Long>(); for (k in keys) set.add(k)
                val faces = ArrayList<Int>()
                for (f in 0 until mesh.faceCount) {
                    if (mesh.fDead[f]) continue
                    val s = mesh.fStart[f]; val c = mesh.fSize[f]
                    for (i in 0 until c) if (set.contains(edgeKey(mesh.cPoint[s + i], mesh.cPoint[s + (i + 1) % c]))) { faces.add(f); break }
                }
                val fa = faces.toIntArray()
                topo(MeshOps.pointsOfFaces(mesh, fa)) { n = MeshOps.deleteFaces(mesh, fa); mesh.clearSelection() }
            }
            else -> {
                val faces = mesh.selectedFaces()
                if (faces.isEmpty()) return 0
                topo(MeshOps.pointsOfFaces(mesh, faces)) { n = MeshOps.deleteFaces(mesh, faces); mesh.clearSelection() }
            }
        }
        return n
    }

    /** 这些点以及和它们共面的点。 */
    private fun neighbours(pts: IntArray): IntArray {
        val sel = affectedMask(pts)
        val out = BooleanArray(mesh.pointCount)
        for (f in 0 until mesh.faceCount) {
            if (mesh.fDead[f]) continue
            val s = mesh.fStart[f]; val n = mesh.fSize[f]
            var any = false
            for (i in 0 until n) if (sel[mesh.cPoint[s + i]]) { any = true; break }
            if (any) for (i in 0 until n) out[mesh.cPoint[s + i]] = true
        }
        var c = 0
        for (b in out) if (b) c++
        val r = IntArray(c); c = 0
        for (i in out.indices) if (out[i]) r[c++] = i
        return r
    }

    /** 溶解选中的边（两侧的面合并）。 */
    fun dissolveEdges(): Int {
        val keys = mesh.edgeSel.toLongArray()
        if (keys.isEmpty()) return 0
        var n = 0
        val pts = mesh.selectedPoints()
        topo(pts) {
            for (k in keys) if (MeshOps.dissolveEdge(mesh, (k ushr 32).toInt(), (k and 0xFFFFFFFFL).toInt())) n++
            mesh.clearSelection()
            mesh.removeOrphanPoints()
        }
        return n
    }

    fun duplicate(): Int {
        val faces = mesh.selectedFaces()
        if (faces.isEmpty()) return 0
        topo { val out = MeshOps.duplicate(mesh, faces); mesh.setFacesSelected(out) }
        return faces.size
    }

    fun flipNormals(): Int {
        val faces = mesh.selectedFaces()
        if (faces.isEmpty()) return 0
        topo(MeshOps.pointsOfFaces(mesh, faces)) { MeshOps.flip(mesh, faces) }
        return faces.size
    }

    fun mergeToCenter(): Int {
        val pts = mesh.selectedPoints()
        if (pts.size < 2) return 0
        val ring = neighbours(pts)
        topo(ring) {
            val keep = MeshOps.mergeToCenter(mesh, pts)
            mesh.clearSelection()
            if (keep >= 0 && !mesh.pDead[keep]) { mesh.selMode = EditMesh.SEL_POINT; mesh.pSel[keep] = true }
        }
        return pts.size
    }

    /** 按距离焊接。没有选中任何点时作用于整个网格。 */
    fun weld(eps: Float): Int {
        var pts = mesh.selectedPoints()
        if (pts.isEmpty()) pts = IntArray(mesh.pointCount) { it }
        var n = 0
        val ring = neighbours(pts)
        topo(ring) { n = MeshOps.weld(mesh, pts, eps) }
        return n
    }

    /** 用选中的点补一个面。 */
    fun fill(material: Int = -1): Boolean {
        val pts = mesh.selectedPoints()
        if (pts.size < 3) return false
        // 朝向相机
        val cam = scene.camera
        if (!Mat4.invertAffine(inv, 0, model.worldMatrix, 0)) Mat4.identity(inv, 0)
        Mat4.transformDir(inv, 0, -cam.forward[0], -cam.forward[1], -cam.forward[2], t, 0)
        val toward = floatArrayOf(t[0], t[1], t[2])
        var mat = material
        if (mat !in mesh.materials.indices) mat = dominantMaterial(pts)
        var f = -1
        topo(pts) {
            f = MeshOps.fill(mesh, pts, mat, toward)
            if (f >= 0) { MeshOps.projectUV(mesh, intArrayOf(f), uvScale()); mesh.setFacesSelected(intArrayOf(f)) }
        }
        return f >= 0
    }

    private fun dominantMaterial(pts: IntArray): Int {
        val sel = affectedMask(pts)
        val count = IntArray(maxOf(1, mesh.materials.size))
        for (f in 0 until mesh.faceCount) {
            if (mesh.fDead[f]) continue
            val s = mesh.fStart[f]
            for (i in 0 until mesh.fSize[f]) if (sel[mesh.cPoint[s + i]]) { count[mesh.fMat[f].coerceIn(0, count.size - 1)]++; break }
        }
        var best = 0
        for (i in count.indices) if (count[i] > count[best]) best = i
        return best
    }

    private fun uvScale(): Float {
        val mn = FloatArray(3); val mx = FloatArray(3)
        if (!mesh.bounds(mn, mx)) return 1f
        return maxOf(mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2], 1e-3f)
    }

    /** 镜像复制选中的面（没有选中时镜像整个网格）。axis：0=X（左右）1=Y 2=Z。 */
    fun mirror(axis: Int, weld: Boolean = true): Int {
        var faces = mesh.selectedFaces()
        if (faces.isEmpty()) { val l = ArrayList<Int>(); for (f in 0 until mesh.faceCount) if (!mesh.fDead[f]) l.add(f); faces = l.toIntArray() }
        if (faces.isEmpty()) return 0
        val boneMirror = if (axis == 0) mirrorBoneTable() else null
        topo(MeshOps.pointsOfFaces(mesh, faces)) {
            val out = MeshOps.mirror(mesh, faces, axis, weld, boneMirror)
            mesh.setFacesSelected(out)
        }
        return faces.size
    }

    /** 左 ↔ 右 骨骼对照表（按 MMD 的命名习惯：左… / 右…）。 */
    private fun mirrorBoneTable(): IntArray? {
        val bones = mesh.bones
        if (bones.size < 2) return null
        val byName = HashMap<String, Int>()
        for ((i, b) in bones.withIndex()) byName.putIfAbsent(b.name, i)
        val table = IntArray(bones.size) { it }
        var any = false
        for ((i, b) in bones.withIndex()) {
            val n = b.name
            val other = when {
                n.startsWith("左") -> "右" + n.substring(1)
                n.startsWith("右") -> "左" + n.substring(1)
                n.endsWith("左") -> n.substring(0, n.length - 1) + "右"
                n.endsWith("右") -> n.substring(0, n.length - 1) + "左"
                else -> null
            } ?: continue
            byName[other]?.let { table[i] = it; any = true }
        }
        return if (any) table else null
    }

    fun smooth(factor: Float = 0.5f, iterations: Int = 1): Int {
        val pts = mesh.selectedPoints()
        if (pts.isEmpty()) return 0
        topo(neighbours(pts)) { MeshOps.smooth(mesh, pts, factor, iterations) }
        return pts.size
    }

    fun setSmoothShading(on: Boolean): Int {
        val faces = mesh.selectedFaces()
        if (faces.isEmpty()) return 0
        topo(MeshOps.pointsOfFaces(mesh, faces)) { MeshOps.setSmooth(mesh, faces, on) }
        return faces.size
    }

    fun solidify(thickness: Float): Int {
        var faces = mesh.selectedFaces()
        if (faces.isEmpty()) { val l = ArrayList<Int>(); for (f in 0 until mesh.faceCount) if (!mesh.fDead[f]) l.add(f); faces = l.toIntArray() }
        if (faces.isEmpty()) return 0
        topo(MeshOps.pointsOfFaces(mesh, faces)) { MeshOps.solidify(mesh, faces, thickness) }
        return faces.size
    }

    /** 环切：以选中的一条边为起点。返回新加的点数。 */
    fun loopCut(t01: Float = 0.5f): Int {
        mesh.syncSelection()
        val k = mesh.edgeSel.firstOrNull() ?: run {
            // 点模式下恰好选了两个点也可以
            val pts = mesh.selectedPoints()
            if (pts.size == 2) edgeKey(pts[0], pts[1]) else return 0
        }
        var n = 0
        topo { n = MeshOps.loopCut(mesh, (k ushr 32).toInt(), (k and 0xFFFFFFFFL).toInt(), t01).size }
        if (n == 0) { undoStack.removeAt(undoStack.size - 1) }
        return n
    }

    /** 给选中的每条边中点加一个点（边模式），或把选中的两个点连起来（点模式）。 */
    fun splitOrConnect(): Int {
        var n = 0
        if (mesh.selMode == EditMesh.SEL_EDGE) {
            val keys = mesh.edgeSel.toLongArray()
            if (keys.isEmpty()) return 0
            topo {
                val made = ArrayList<Int>()
                for (k in keys) { val p = MeshOps.splitEdge(mesh, (k ushr 32).toInt(), (k and 0xFFFFFFFFL).toInt()); if (p >= 0) made.add(p) }
                n = made.size
                mesh.selMode = EditMesh.SEL_POINT
                mesh.clearSelection()
                for (p in made) mesh.pSel[p] = true
            }
        } else {
            val pts = mesh.selectedPoints()
            if (pts.size != 2) return 0
            topo(pts) { if (MeshOps.connect(mesh, pts[0], pts[1])) n = 1 }
        }
        return n
    }

    fun projectUV(): Int {
        val faces = mesh.selectedFaces()
        if (faces.isEmpty()) return 0
        topo { MeshOps.projectUV(mesh, faces, uvScale()) }
        return faces.size
    }

    /** 把选中的点整体绑到一根骨骼上（权重 100%），之后跟着这根骨骼动。 */
    fun bindToBone(bone: Int): Int {
        if (bone !in mesh.bones.indices) return 0
        val pts = mesh.selectedPoints()
        if (pts.isEmpty()) return 0
        pushUndo()
        for (p in pts) {
            mesh.boneIdx[p * 4] = bone.toShort(); mesh.boneW[p * 4] = 1f
            for (k in 1 until 4) { mesh.boneIdx[p * 4 + k] = 0; mesh.boneW[p * 4 + k] = 0f }
            mesh.pOrig[p] = -1
        }
        commit()
        return pts.size
    }

    // ------------------------------------------------------------------ 基础形体

    /**
     * 往网格里加一个基础形体，加完后选中它。
     * @param build 往 [EditMesh] 里添加几何并返回新建的面
     * @param bone 新几何绑定的骨骼（-1 = 第一根骨骼）
     */
    fun addShape(bone: Int = -1, build: (EditMesh) -> IntArray): Int {
        var n = 0
        pushUndo()
        val before = mesh.pointCount
        val faces = build(mesh)
        n = faces.size
        if (bone in mesh.bones.indices) for (p in before until mesh.pointCount) mesh.boneIdx[p * 4] = bone.toShort()
        mesh.setFacesSelected(faces)
        val mask = BooleanArray(mesh.pointCount)
        for (p in before until mesh.pointCount) mask[p] = true
        mesh.recalcNormals(mask)
        commit()
        return n
    }

    fun addBox(w: Float, h: Float, d: Float, xf: FloatArray?, material: Int = 0, bone: Int = -1) = addShape(bone) { MeshPrimitives.box(it, w, h, d, material, xf) }
    fun addSphere(r: Float, segments: Int, rings: Int, xf: FloatArray?, material: Int = 0, bone: Int = -1) = addShape(bone) { MeshPrimitives.sphere(it, r, segments, rings, material, xf) }
    fun addCylinder(r0: Float, r1: Float, h: Float, segments: Int, xf: FloatArray?, material: Int = 0, bone: Int = -1) = addShape(bone) { MeshPrimitives.cylinder(it, r0, r1, h, segments, true, material, xf) }
    fun addTorus(R: Float, r: Float, segments: Int, sides: Int, xf: FloatArray?, material: Int = 0, bone: Int = -1) = addShape(bone) { MeshPrimitives.torus(it, R, r, segments, sides, material, xf) }
    fun addPlane(w: Float, d: Float, sx: Int, sz: Int, xf: FloatArray?, material: Int = 0, bone: Int = -1) = addShape(bone) { MeshPrimitives.plane(it, w, d, sx, sz, material, xf) }

    // ------------------------------------------------------------------ 材质

    fun addMaterial(name: String, r: Float, g: Float, b: Float, a: Float = 1f): Int {
        pushUndo()
        mesh.materials.add(EditMesh.defaultMaterial(name.ifBlank { "材质${mesh.materials.size + 1}" }, r, g, b, a))
        commit()
        return mesh.materials.size - 1
    }

    /** 修改材质。block 里直接改 [MaterialData] 的字段。 */
    fun editMaterial(index: Int, block: (MaterialData) -> Unit): Boolean {
        if (index !in mesh.materials.indices) return false
        pushUndo()
        block(mesh.materials[index])
        commit()
        return true
    }

    fun setMaterialColor(index: Int, r: Float, g: Float, b: Float, a: Float): Boolean = editMaterial(index) { m ->
        m.diffuse[0] = r; m.diffuse[1] = g; m.diffuse[2] = b; m.diffuse[3] = a
        m.ambient[0] = r * 0.5f; m.ambient[1] = g * 0.5f; m.ambient[2] = b * 0.5f
    }

    /** 给材质指定贴图（path 相对模型所在目录或绝对路径；空串 = 去掉贴图）。 */
    fun setMaterialTexture(index: Int, path: String): Boolean = editMaterial(index) { m ->
        if (path.isBlank()) m.texture = -1
        else {
            var ti = mesh.textures.indexOfFirst { it.embedded == null && it.path == path }
            if (ti < 0) { mesh.textures.add(TextureRef(path)); ti = mesh.textures.size - 1 }
            m.texture = ti
        }
    }

    fun assignMaterial(material: Int): Int {
        val faces = mesh.selectedFaces()
        if (faces.isEmpty() || material !in mesh.materials.indices) return 0
        topo { MeshOps.assignMaterial(mesh, faces, material) }
        return faces.size
    }

    fun rename(name: String) { mesh.name = name; model.name = name; dirty = true }

    // ------------------------------------------------------------------ 辅助显示

    private var edgeCache: IntArray? = null
    private var edgeCacheRev = -1

    /** 全部（去重后的）边：a0,b0,a1,b1,…。面数太多时返回 null（不画线框）。 */
    private fun allEdges(): IntArray? {
        if (edgeCache != null && edgeCacheRev == revision) return edgeCache
        edgeCacheRev = revision
        if (mesh.liveFaceCount > WIRE_FACE_LIMIT) { edgeCache = null; return null }
        val seen = HashSet<Long>()
        val out = com.lumi.mmd.engine.util.IntList(1024)
        mesh.forEachEdge { a, b -> if (seen.add(edgeKey(a, b))) { out.add(a); out.add(b) } }
        edgeCache = out.toArray()
        return edgeCache
    }

    /** 画线框、选中的点 / 边 / 面。每帧渲染前调用。 */
    fun fillOverlay(batch: LineBatch) {
        val m = mesh
        val wm = model.worldMatrix
        val p = m.pos
        val d = density
        val n = m.pointCount
        // 把点变换到世界空间（只算一次）
        var wp = worldPos
        if (wp.size < n * 3) { wp = FloatArray(maxOf(n * 3, wp.size * 2)); worldPos = wp }
        for (i in 0 until n) {
            if (m.pDead[i]) continue
            val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
            wp[i * 3] = wm[0] * x + wm[4] * y + wm[8] * z + wm[12]
            wp[i * 3 + 1] = wm[1] * x + wm[5] * y + wm[9] * z + wm[13]
            wp[i * 3 + 2] = wm[2] * x + wm[6] * y + wm[10] * z + wm[14]
        }
        val edges = allEdges()
        if (edges != null) {
            var i = 0
            while (i < edges.size) {
                val a = edges[i] * 3; val b = edges[i + 1] * 3
                batch.line(wp[a], wp[a + 1], wp[a + 2], wp[b], wp[b + 1], wp[b + 2], 0.05f, 0.08f, 0.12f, 0.55f, 1f * d, true)
                i += 2
            }
        }
        // 选中的面：半透明填充 + 描边
        var shown = 0
        for (f in 0 until m.faceCount) {
            if (m.fDead[f] || !m.fSel[f]) continue
            if (++shown > 6000) break
            val s = m.fStart[f]; val c = m.fSize[f]
            val a = m.cPoint[s] * 3
            for (i in 1 until c - 1) {
                val b = m.cPoint[s + i] * 3; val e = m.cPoint[s + i + 1] * 3
                batch.tri(wp[a], wp[a + 1], wp[a + 2], wp[b], wp[b + 1], wp[b + 2], wp[e], wp[e + 1], wp[e + 2], 1f, 0.62f, 0.1f, 0.34f, true)
            }
            for (i in 0 until c) {
                val b = m.cPoint[s + i] * 3; val e = m.cPoint[s + (i + 1) % c] * 3
                batch.line(wp[b], wp[b + 1], wp[b + 2], wp[e], wp[e + 1], wp[e + 2], 1f, 0.72f, 0.15f, 1f, 1.8f * d, true)
            }
        }
        if (m.selMode == EditMesh.SEL_EDGE) {
            for (k in m.edgeSel) {
                val a = (k ushr 32).toInt() * 3; val b = (k and 0xFFFFFFFFL).toInt() * 3
                if (a / 3 >= n || b / 3 >= n) continue
                batch.line(wp[a], wp[a + 1], wp[a + 2], wp[b], wp[b + 1], wp[b + 2], 1f, 0.8f, 0.1f, 1f, 3f * d, false)
            }
        }
        if (m.selMode == EditMesh.SEL_POINT) {
            val all = n <= POINT_DRAW_LIMIT
            for (i in 0 until n) {
                if (m.pDead[i]) continue
                if (m.pSel[i]) batch.point(wp[i * 3], wp[i * 3 + 1], wp[i * 3 + 2], 1f, 0.75f, 0.1f, 1f, 9f * d, false)
                else if (all) batch.point(wp[i * 3], wp[i * 3 + 1], wp[i * 3 + 2], 0.1f, 0.12f, 0.16f, 0.9f, 5.5f * d, true)
            }
        } else if (m.selMode == EditMesh.SEL_EDGE) {
            for (i in 0 until n) if (!m.pDead[i] && m.pSel[i]) batch.point(wp[i * 3], wp[i * 3 + 1], wp[i * 3 + 2], 1f, 0.8f, 0.1f, 1f, 6f * d, false)
        }
    }

    private var worldPos = FloatArray(0)

    /** 给界面看的统计：点 / 面数量与选中数量。 */
    class Stats(val points: Int, val faces: Int, val selPoints: Int, val selEdges: Int, val selFaces: Int, val materials: Int)

    fun stats(): Stats {
        var sp = 0; var sf = 0
        for (i in 0 until mesh.pointCount) if (mesh.pSel[i] && !mesh.pDead[i]) sp++
        for (i in 0 until mesh.faceCount) if (mesh.fSel[i] && !mesh.fDead[i]) sf++
        return Stats(mesh.livePointCount, mesh.liveFaceCount, sp, if (mesh.selMode == EditMesh.SEL_EDGE) mesh.edgeSel.size else 0, sf, mesh.materials.size)
    }

    companion object {
        private const val UNDO_BYTES = 96L * 1024 * 1024
        /** 面数不超过这个值时画出完整线框。 */
        const val WIRE_FACE_LIMIT = 6000
        const val POINT_DRAW_LIMIT = 6000

        /** 由平移 / 欧拉角（度）/ 缩放拼一个变换矩阵（形体摆放用）。 */
        fun placement(px: Float, py: Float, pz: Float, rxDeg: Float = 0f, ryDeg: Float = 0f, rzDeg: Float = 0f, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f): FloatArray {
            val q = FloatArray(4)
            val k = (Math.PI / 180.0).toFloat()
            Quat.fromEulerYXZ(q, 0, rxDeg * k, ryDeg * k, rzDeg * k)
            val m = FloatArray(16)
            val v = FloatArray(3)
            Quat.rotate(q, 0, sx, 0f, 0f, v, 0); m[0] = v[0]; m[1] = v[1]; m[2] = v[2]
            Quat.rotate(q, 0, 0f, sy, 0f, v, 0); m[4] = v[0]; m[5] = v[1]; m[6] = v[2]
            Quat.rotate(q, 0, 0f, 0f, sz, v, 0); m[8] = v[0]; m[9] = v[1]; m[10] = v[2]
            m[12] = px; m[13] = py; m[14] = pz; m[15] = 1f
            return m
        }
    }
}

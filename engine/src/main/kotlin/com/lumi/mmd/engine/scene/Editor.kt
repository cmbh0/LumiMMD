package com.lumi.mmd.engine.scene

import com.lumi.mmd.engine.math.Mat4
import com.lumi.mmd.engine.math.Quat
import com.lumi.mmd.engine.model.BoneData
import com.lumi.mmd.engine.render.LineBatch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 场景编辑器：点选 + 拖动操纵。三种编辑对象——
 *  - 模型：整体移动 / 旋转 / 缩放（角色、舞台、配件；挂在骨骼上的配件在骨骼的坐标系里调整）
 *  - 部件：材质或连通块（头饰、衣服配件……）在绑定姿势里移动 / 旋转 / 缩放 / 隐藏，之后仍跟随骨骼
 *  - 骨骼：摆姿势（旋转；可移动的骨骼和 IK 目标还能平移），叠加在动作之上
 *
 * 操纵方式有三种，可以混着用：
 *  1. 操纵器：移动 = 三根带箭头的轴，旋转 = 三个环 + 最外面绕视线转的白圈，缩放 = 三根带方块的轴；按住手柄拖动；
 *  2. 选中对象后，手指从对象身上开始拖 → 按工具栏选的轴（自由 / X / Y / Z）操纵；从别处开始拖 → 转视角；
 *  3. 屏幕角落的 XYZ 拖动块（[beginPad] / [padDrag]），和 MMD 视图右下角那排图标一样。
 * 只在渲染线程上调用。
 */
class Editor(val scene: Scene) {
    var mode = MODE_VIEW
        set(value) { field = value; if (value != MODE_PART) selectedPart = null; if (value != MODE_BONE) selectedBone = -1 }
    var tool = TOOL_MOVE
    var axis = AXIS_FREE
    /** 部件模式下：true = 选连通块，false = 选整个材质。 */
    var islandMode = false
    /** true 时任何单指拖动都作用在选中对象上（不需要从对象身上起手）。 */
    var forceManipulate = false
    /** 模型模式下是否允许点选舞台。 */
    var pickStages = true

    var selectedModel: ModelInstance? = null
        private set
    var selectedPart: Part? = null
        private set
    var selectedBone = -1
        private set

    /** 选中对象或其参数变化时回调（渲染线程）。 */
    var onChanged: (() -> Unit)? = null

    private val undoStack = ArrayList<() -> Unit>()
    private var dragging = false
    private val hit = PickResult()
    private val ro = FloatArray(3)
    private val rd = FloatArray(3)
    private val t = FloatArray(64)
    private val frame = FloatArray(16)
    private val frameInv = FloatArray(16)
    private val pivot = FloatArray(3)
    private val scr = FloatArray(3)
    private var lastAngle = 0f
    /** 屏幕密度（像素 / dp），决定点选容差与辅助线粗细。 */
    var density = 2f

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    /** 每记下一步撤销就加一（调用方用它判断"刚才那次拖动有没有记撤销"）。 */
    var undoSerial = 0
        private set
    /** 占内存的撤销步骤（比如整份动作的快照）各占多少字节；总量超过上限时丢掉最早的步骤。 */
    private val undoBytes = java.util.IdentityHashMap<() -> Unit, Int>()

    fun select(model: ModelInstance?, part: Part? = null, bone: Int = -1) {
        selectedModel = model
        selectedPart = if (model != null) part else null
        selectedBone = if (model != null) bone else -1
        onChanged?.invoke()
    }

    fun clearSelection() = select(null)

    /** 模型被移出场景时调用。 */
    fun onModelRemoved(m: ModelInstance) {
        if (selectedModel === m) select(null)
        undoStack.clear(); undoBytes.clear()
    }

    // ------------------------------------------------------------------ 点选

    /** 单击。返回是否改变了选中对象。 */
    fun tap(x: Float, y: Float, w: Int, h: Int): Boolean {
        val cam = scene.camera
        cam.screenRay(x, y, w, h, ro, rd)
        when (mode) {
            MODE_BONE -> {
                if (pickBone(x, y, w, h)) return true
                if (Picker.pick(scene, ro, rd, hit) { it.skeleton != null && it.kind != ModelInstance.KIND_STAGE }) {
                    if (hit.model !== selectedModel) { select(hit.model); return true }
                }
                return false
            }
            MODE_PART -> {
                if (!Picker.pick(scene, ro, rd, hit) { true }) { if (selectedPart != null) { select(selectedModel); return true }; return false }
                val m = hit.model!!
                val part = m.parts.partOfTriangle(hit.triangle, islandMode)
                select(m, part)
                return true
            }
            else -> {
                if (!Picker.pick(scene, ro, rd, hit) { pickStages || it.kind != ModelInstance.KIND_STAGE }) {
                    if (selectedModel != null && mode == MODE_MODEL) { select(null); return true }
                    return false
                }
                if (hit.model !== selectedModel) { select(hit.model); return true }
                return false
            }
        }
    }

    private fun pickBone(x: Float, y: Float, w: Int, h: Int): Boolean {
        val cam = scene.camera
        val radius = 26f * density
        var best = radius * radius
        var bestModel: ModelInstance? = null
        var bestBone = -1
        val candidates = if (selectedModel?.skeleton != null) listOf(selectedModel!!) else scene.models.filter { it.visible && it.skeleton != null && it.kind != ModelInstance.KIND_STAGE }
        for (m in candidates) {
            val sk = m.skeleton ?: continue
            val driven = m.physics?.takeIf { m.physicsEnabled && scene.physicsEnabled }?.drivenBones
            for (b in 0 until sk.count) {
                val bd = sk.bones[b]
                if (!bd.isVisible || !bd.isOperable) continue
                if (driven != null && driven[b]) continue
                m.boneWorldPosition(b, t, 0)
                if (!cam.project(t[0], t[1], t[2], w, h, scr)) continue
                val dx = scr[0] - x; val dy = scr[1] - y
                val d2 = dx * dx + dy * dy
                if (d2 < best) { best = d2; bestModel = m; bestBone = b }
            }
        }
        if (bestModel == null) return false
        select(bestModel, null, bestBone)
        return true
    }

    // ------------------------------------------------------------------ 操纵器（移动：三根带箭头的轴；旋转：三个环）

    /** true = 操纵轴跟着对象自己的朝向（局部）；false = 世界坐标轴（全局）。 */
    var localAxes = true
    /** 是否显示并响应操纵器的手柄。 */
    var showGizmo = true
    /** 操纵器在屏幕上的半径（dp）。 */
    var gizmoSizeDp = 74f
    /** 正在拖动的手柄（HANDLE_*）；没有在拖动手柄时是 HANDLE_NONE。 */
    var activeHandle = HANDLE_NONE
        private set
    /** 本次拖动累计的量：旋转是弧度，移动是世界单位，缩放是倍率的自然对数。界面用它显示数值。 */
    var dragAmount = 0f
        private set
    /** 网格编辑模式（MODE_MESH）下被操纵的对象，由上层设置。 */
    var meshTarget: MeshTarget? = null

    private var dragAxis = AXIS_FREE
    private val meshOrient = FloatArray(9)
    private val gAxes = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val gm = FloatArray(16)
    private val gq = FloatArray(4)
    private val gv = FloatArray(8)
    private val gs = FloatArray(3)
    private var ringSx = 0f
    private var ringSy = 0f
    private var ringGain = 0f
    private var useDial = false

    val isDragging: Boolean get() = dragging

    /** 三根操纵轴在世界空间里的方向（单位向量）→ gAxes。要先调用 computeFrame。 */
    private fun computeGizmoAxes(m: ModelInstance) {
        val g = gAxes
        g.fill(0f); g[0] = 1f; g[4] = 1f; g[8] = 1f
        if (!localAxes) return
        when (mode) {
            MODE_MODEL -> basisFrom(m.worldMatrix, 0)
            MODE_MESH -> {
                val o = meshOrient
                if (meshTarget?.orientation(o) == true) {
                    Mat4.transformDir(m.worldMatrix, 0, o[0], o[1], o[2], g, 0)
                    Mat4.transformDir(m.worldMatrix, 0, o[3], o[4], o[5], g, 3)
                    Mat4.transformDir(m.worldMatrix, 0, o[6], o[7], o[8], g, 6)
                    normalizeAxes()
                } else basisFrom(m.worldMatrix, 0)
            }
            MODE_PART -> basisFrom(frame, 0)
            MODE_BONE -> {
                val sk = m.skeleton ?: return
                val b = selectedBone
                if (b < 0) return
                Mat4.mulAffine(gm, 0, m.worldMatrix, 0, sk.global, b * 16)
                val bd = sk.bones[b]
                if ((bd.flags and BoneData.FLAG_LOCAL_AXIS) != 0) {
                    // PMX 给手臂、手指定义的局部轴：X 沿着骨骼方向
                    val x = bd.localX; val z = bd.localZ
                    Mat4.transformDir(gm, 0, x[0], x[1], x[2], g, 0)
                    Mat4.transformDir(gm, 0, z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0], g, 3)
                    Mat4.transformDir(gm, 0, z[0], z[1], z[2], g, 6)
                    normalizeAxes()
                } else if (armLike(bd.name) && boneDirection(sk, b, gv)) {
                    // 没有定义局部轴的手臂 / 手指（PMD 模型、部分 PMX）：和 MMD 一样，X 取骨骼指向子骨骼的方向，
                    // Z 尽量朝模型的前方，Y 与两者垂直
                    val xx = gv[0]; val xy = gv[1]; val xz = gv[2]
                    // y = x × 前方(0,0,-1)；z = x × y
                    var yx = -xy; var yy = xx; var yz = 0f
                    val yl = sqrt(yx * yx + yy * yy)
                    if (yl < 1e-4f) { basisFrom(gm, 0); return }
                    yx /= yl; yy /= yl
                    // 手臂朝左（+X）时让 Y 朝上
                    if (yy < 0f) { yx = -yx; yy = -yy }
                    val zx = xy * yz - xz * yy; val zy = xz * yx - xx * yz; val zz = xx * yy - xy * yx
                    Mat4.transformDir(gm, 0, xx, xy, xz, g, 0)
                    Mat4.transformDir(gm, 0, yx, yy, yz, g, 3)
                    Mat4.transformDir(gm, 0, zx, zy, zz, g, 6)
                    normalizeAxes()
                } else basisFrom(gm, 0)
            }
        }
    }

    private fun armLike(name: String): Boolean =
        name.contains("腕") || name.contains("ひじ") || name.contains("手首") || name.contains("指") || name.contains("肩") || name.contains("手捩") || name.contains("腕捩") ||
            name.contains("arm", true) || name.contains("elbow", true) || name.contains("wrist", true) || name.contains("finger", true) || name.contains("thumb", true) || name.contains("shoulder", true)

    /** 骨骼在初始姿势里指向子骨骼的方向（骨骼自己的坐标系里，单位向量）→ out[0..2]。 */
    private fun boneDirection(sk: com.lumi.mmd.engine.anim.Skeleton, b: Int, out: FloatArray): Boolean {
        val bd = sk.bones[b]
        var x: Float; var y: Float; var z: Float
        if (bd.tailBone in 0 until sk.count) {
            val tb = sk.bones[bd.tailBone]
            x = tb.position[0] - bd.position[0]; y = tb.position[1] - bd.position[1]; z = tb.position[2] - bd.position[2]
        } else { x = bd.tailOffset[0]; y = bd.tailOffset[1]; z = bd.tailOffset[2] }
        val l = sqrt(x * x + y * y + z * z)
        if (l < 1e-5f) return false
        out[0] = x / l; out[1] = y / l; out[2] = z / l
        return true
    }

    private fun basisFrom(mat: FloatArray, o: Int) {
        val g = gAxes
        g[0] = mat[o]; g[1] = mat[o + 1]; g[2] = mat[o + 2]
        g[3] = mat[o + 4]; g[4] = mat[o + 5]; g[5] = mat[o + 6]
        g[6] = mat[o + 8]; g[7] = mat[o + 9]; g[8] = mat[o + 10]
        normalizeAxes()
    }

    private fun normalizeAxes() {
        val g = gAxes
        for (k in 0 until 3) {
            val o = k * 3
            val l = sqrt(g[o] * g[o] + g[o + 1] * g[o + 1] + g[o + 2] * g[o + 2])
            if (l > 1e-9f) { g[o] /= l; g[o + 1] /= l; g[o + 2] /= l }
            else { g[o] = if (k == 0) 1f else 0f; g[o + 1] = if (k == 1) 1f else 0f; g[o + 2] = if (k == 2) 1f else 0f }
        }
    }

    private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax; val vy = by - ay
        val l2 = vx * vx + vy * vy
        var tt = if (l2 > 1e-6f) ((px - ax) * vx + (py - ay) * vy) / l2 else 0f
        if (tt < 0f) tt = 0f else if (tt > 1f) tt = 1f
        val dx = px - (ax + vx * tt); val dy = py - (ay + vy * tt)
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * 手指按下的位置落在操纵器的哪个手柄上。
     * 旋转工具下同时算好"抓住的那一点沿环的切线在屏幕上的方向"，拖动时沿这个方向的位移换算成转角。
     */
    private fun pickHandle(x: Float, y: Float, w: Int, h: Int): Int {
        val m = selectedModel ?: return HANDLE_NONE
        if (!pivotWorld(pivot)) return HANDLE_NONE
        val cam = scene.camera
        if (!cam.project(pivot[0], pivot[1], pivot[2], w, h, scr)) return HANDLE_NONE
        val cx = scr[0]; val cy = scr[1]
        val radiusPx = gizmoSizeDp * density
        val len = cam.worldPerPixel(scr[2], h) * radiusPx
        computeFrame(m)
        computeGizmoAxes(m)
        val g = gAxes
        val dCenter = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
        var best = 22f * density
        var bestHandle = HANDLE_NONE

        if (tool != TOOL_ROTATE) {
            if (dCenter < 15f * density) return HANDLE_CENTER
            for (k in 0 until 3) {
                val o = k * 3
                if (!cam.project(pivot[0] + g[o] * len * 0.15f, pivot[1] + g[o + 1] * len * 0.15f, pivot[2] + g[o + 2] * len * 0.15f, w, h, gs)) continue
                val sx = gs[0]; val sy = gs[1]
                if (!cam.project(pivot[0] + g[o] * len * 1.15f, pivot[1] + g[o + 1] * len * 1.15f, pivot[2] + g[o + 2] * len * 1.15f, w, h, gs)) continue
                val d = distToSegment(x, y, sx, sy, gs[0], gs[1])
                if (d < best) { best = d; bestHandle = k + 1 }
            }
            return bestHandle
        }

        var bestAngle = 0f
        val twoPi = (Math.PI * 2.0).toFloat()
        val tolerance = best
        var bestScore = Float.MAX_VALUE
        for (k in 0 until 3) {
            val u = ((k + 1) % 3) * 3; val v = ((k + 2) % 3) * 3
            var prevOk = false; var prevX = 0f; var prevY = 0f
            var dMin = tolerance; var angMin = 0f
            for (i in 0..RING_SEGMENTS) {
                val ang = i * twoPi / RING_SEGMENTS
                val cs = cos(ang) * len; val sn = sin(ang) * len
                val ok = cam.project(pivot[0] + g[u] * cs + g[v] * sn, pivot[1] + g[u + 1] * cs + g[v + 1] * sn, pivot[2] + g[u + 2] * cs + g[v + 2] * sn, w, h, gs)
                if (ok && prevOk) {
                    val d = distToSegment(x, y, prevX, prevY, gs[0], gs[1])
                    if (d < dMin) { dMin = d; angMin = ang - twoPi / RING_SEGMENTS * 0.5f }
                }
                prevOk = ok; prevX = gs[0]; prevY = gs[1]
            }
            if (dMin >= tolerance) continue
            // 两个环在手指下面交叉时，优先选正对着相机的那个（侧对相机的环看起来只是一条线，多半不是想抓它）
            val face = abs(g[k * 3] * cam.forward[0] + g[k * 3 + 1] * cam.forward[1] + g[k * 3 + 2] * cam.forward[2])
            val score = dMin + (1f - face) * 10f * density
            if (score < bestScore) { bestScore = score; best = dMin; bestHandle = k + 1; bestAngle = angMin }
        }
        // 最外面的白圈（绕视线转）：手指离它比离三个轴环都近时选它
        val dView = abs(dCenter - radiusPx * VIEW_RING)
        if (dView < 18f * density && dView < bestScore) return HANDLE_VIEW
        if (bestHandle == HANDLE_NONE) return HANDLE_NONE
        // 抓住的那一点：正角度旋转时它往哪边走（直接用四元数转一小步得到，不用操心手性的符号）
        val k = bestHandle - 1
        val o = k * 3
        val u = ((k + 1) % 3) * 3; val v = ((k + 2) % 3) * 3
        val facing = g[o] * cam.forward[0] + g[o + 1] * cam.forward[1] + g[o + 2] * cam.forward[2]
        useDial = abs(facing) > 0.82f
        if (!useDial) {
            Quat.fromAxisAngle(gq, 0, g[o], g[o + 1], g[o + 2], 0.02f)
            var l = tangentOnScreen(g[u] * cos(bestAngle) + g[v] * sin(bestAngle), g[u + 1] * cos(bestAngle) + g[v + 1] * sin(bestAngle), g[u + 2] * cos(bestAngle) + g[v + 2] * sin(bestAngle))
            if (l < 0.3f) {
                // 抓在了环"侧面"的端点上，切线正对着相机：改用环上离相机最近那一点的切线
                val d = -(cam.forward[0] * g[o] + cam.forward[1] * g[o + 1] + cam.forward[2] * g[o + 2])
                var rx = -cam.forward[0] - g[o] * d; var ry = -cam.forward[1] - g[o + 1] * d; var rz = -cam.forward[2] - g[o + 2] * d
                val rl = sqrt(rx * rx + ry * ry + rz * rz)
                if (rl > 1e-6f) { rx /= rl; ry /= rl; rz /= rl }
                l = tangentOnScreen(rx, ry, rz)
            }
            if (l < 0.3f) useDial = true else ringGain = 1f / (radiusPx * l)
        }
        return bestHandle
    }

    /** 环上一点 r（单位向量）在正角度旋转时的运动方向，投到屏幕上 → ringSx / ringSy（单位向量）；返回投影后的长度（0..1）。 */
    private fun tangentOnScreen(rx: Float, ry: Float, rz: Float): Float {
        val cam = scene.camera
        Quat.rotate(gq, 0, rx, ry, rz, gv, 0)
        var tx = gv[0] - rx; var ty = gv[1] - ry; var tz = gv[2] - rz
        val tl = sqrt(tx * tx + ty * ty + tz * tz)
        if (tl < 1e-9f) return 0f
        tx /= tl; ty /= tl; tz /= tl
        val sx = tx * cam.right[0] + ty * cam.right[1] + tz * cam.right[2]
        val sy = -(tx * cam.up[0] + ty * cam.up[1] + tz * cam.up[2])
        val l = sqrt(sx * sx + sy * sy)
        if (l > 1e-6f) { ringSx = sx / l; ringSy = sy / l }
        return l
    }

    // ------------------------------------------------------------------ 拖动

    /**
     * 手指按下并开始移动时调用。
     * @return true = 这次拖动用来操纵选中的对象；false = 交给相机
     */
    fun beginDrag(x: Float, y: Float, w: Int, h: Int): Boolean {
        dragging = false
        activeHandle = HANDLE_NONE
        dragAmount = 0f
        val m = selectedModel ?: return false
        if (mode == MODE_VIEW || !m.visible) return false
        if (mode == MODE_PART && selectedPart == null) return false
        if (mode == MODE_BONE && selectedBone < 0) return false
        if (mode == MODE_MESH && meshTarget?.pivot(gv) != true) return false
        val handle = if (showGizmo) pickHandle(x, y, w, h) else HANDLE_NONE
        var grab = forceManipulate || handle != HANDLE_NONE
        if (!grab) {
            val cam = scene.camera
            // 起手点离操纵中心足够近也算
            if (pivotWorld(pivot) && cam.project(pivot[0], pivot[1], pivot[2], w, h, scr)) {
                val dx = scr[0] - x; val dy = scr[1] - y
                if (dx * dx + dy * dy < (40f * density) * (40f * density)) grab = true
            }
            if (!grab && mode != MODE_BONE && mode != MODE_MESH) {
                cam.screenRay(x, y, w, h, ro, rd)
                if (Picker.pick(scene, ro, rd, hit) { true } && hit.model === m) {
                    grab = if (mode == MODE_PART) {
                        val p = selectedPart!!
                        if (p.island >= 0) m.parts.partOfTriangle(hit.triangle, true) === p else hit.material == p.material
                    } else true
                }
            }
        }
        if (!grab) return false
        activeHandle = handle
        dragAxis = when (handle) {
            HANDLE_X -> AXIS_X
            HANDLE_Y -> AXIS_Y
            HANDLE_Z -> AXIS_Z
            HANDLE_CENTER, HANDLE_VIEW -> AXIS_FREE
            else -> axis
        }
        pushUndo()
        dragging = true
        if (pivotWorld(pivot) && scene.camera.project(pivot[0], pivot[1], pivot[2], w, h, scr)) lastAngle = atan2(y - scr[1], x - scr[0])
        return true
    }

    fun endDrag() {
        if (dragging) {
            dragging = false; activeHandle = HANDLE_NONE
            if (mode == MODE_MESH) meshTarget?.dragEnded()
            onChanged?.invoke()
        }
    }

    /** 拖动中：dx/dy 为本次的像素位移，x/y 为当前位置。 */
    fun drag(dx: Float, dy: Float, x: Float, y: Float, w: Int, h: Int) {
        if (!dragging) return
        val m = selectedModel ?: return
        val cam = scene.camera
        if (!pivotWorld(pivot)) return
        val visible = cam.project(pivot[0], pivot[1], pivot[2], w, h, scr)
        val depth = if (visible) scr[2] else abs(cam.distance)
        val wpp = cam.worldPerPixel(depth, h)
        computeFrame(m)
        computeGizmoAxes(m)
        val k = 0.0065f / density * 2f
        val g = gAxes
        val o = if (dragAxis == AXIS_FREE) 0 else (dragAxis - 1) * 3

        when (tool) {
            TOOL_MOVE -> {
                if (dragAxis == AXIS_FREE) {
                    moveWorld(m, (cam.right[0] * dx - cam.up[0] * dy) * wpp, (cam.right[1] * dx - cam.up[1] * dy) * wpp, (cam.right[2] * dx - cam.up[2] * dy) * wpp)
                    dragAmount += sqrt(dx * dx + dy * dy) * wpp
                } else {
                    val amount = amountAlongAxis(g[o], g[o + 1], g[o + 2], dx, dy, w, h, wpp)
                    moveWorld(m, g[o] * amount, g[o + 1] * amount, g[o + 2] * amount)
                    dragAmount += amount
                }
            }
            TOOL_ROTATE -> {
                if (activeHandle == HANDLE_VIEW) {
                    // 外圈：绕视线方向，像拨表盘一样转
                    val d = dialDelta(x, y)
                    rotateWorld(m, cam.forward[0], cam.forward[1], cam.forward[2], -d)
                    dragAmount -= d
                } else if (dragAxis == AXIS_FREE) {
                    Quat.fromAxisAngle(t, 8, cam.up[0], cam.up[1], cam.up[2], -dx * k)
                    Quat.fromAxisAngle(t, 12, cam.right[0], cam.right[1], cam.right[2], -dy * k)
                    Quat.mul(t, 8, t, 8, t, 12)
                    applyWorldQuat(m)
                    dragAmount += sqrt(dx * dx + dy * dy) * k
                } else {
                    val ax = g[o]; val ay = g[o + 1]; val az = g[o + 2]
                    val facing = ax * cam.forward[0] + ay * cam.forward[1] + az * cam.forward[2]
                    val angle: Float
                    if (activeHandle != HANDLE_NONE && !useDial) {
                        // 抓着环拖：沿环的切线方向的位移 → 转角
                        angle = (dx * ringSx + dy * ringSy) * ringGain
                    } else if (activeHandle != HANDLE_NONE) {
                        val d = dialDelta(x, y)
                        angle = if (facing > 0f) -d else d
                    } else {
                        // 工具栏锁定了轴：正角度时"靠近相机一侧的表面"的运动方向 m = a × (-forward)
                        val mx = -(ay * cam.forward[2] - az * cam.forward[1])
                        val my = -(az * cam.forward[0] - ax * cam.forward[2])
                        val mz = -(ax * cam.forward[1] - ay * cam.forward[0])
                        val sx = mx * cam.right[0] + my * cam.right[1] + mz * cam.right[2]
                        val sy = -(mx * cam.up[0] + my * cam.up[1] + mz * cam.up[2])
                        val len = sqrt(sx * sx + sy * sy)
                        if (len > 0.25f) angle = (dx * sx + dy * sy) / len * k
                        else { val d = dialDelta(x, y); angle = if (facing > 0f) -d else d }
                    }
                    rotateWorld(m, ax, ay, az, angle)
                    dragAmount += angle
                }
            }
            TOOL_SCALE -> {
                var pixels = -dy
                if (dragAxis != AXIS_FREE) {
                    val sx = g[o] * cam.right[0] + g[o + 1] * cam.right[1] + g[o + 2] * cam.right[2]
                    val sy = -(g[o] * cam.up[0] + g[o + 1] * cam.up[1] + g[o + 2] * cam.up[2])
                    val l = sqrt(sx * sx + sy * sy)
                    if (l > 0.2f) pixels = (dx * sx + dy * sy) / l
                }
                val f = exp(pixels * 0.006f / density * 2f)
                applyScale(m, f)
                dragAmount += pixels * 0.006f / density * 2f
            }
        }
        m.poseDirty = true
    }

    private fun dialDelta(x: Float, y: Float): Float {
        val a = atan2(y - scr[1], x - scr[0])
        var d = a - lastAngle
        if (d > Math.PI) d -= (Math.PI * 2).toFloat() else if (d < -Math.PI) d += (Math.PI * 2).toFloat()
        lastAngle = a
        return d
    }

    /** 绕世界空间的轴转 angle 弧度。 */
    private fun rotateWorld(m: ModelInstance, ax: Float, ay: Float, az: Float, angle: Float) {
        Quat.fromAxisAngle(t, 8, ax, ay, az, angle)
        applyWorldQuat(m)
    }

    /** t[8..11] 里的世界空间旋转增量换到对象所在的坐标系再应用：q' = F⁻¹ · q · F */
    private fun applyWorldQuat(m: ModelInstance) {
        Mat4.toQuat(frame, 0, t, 16)
        Quat.conjugate(t, 20, t, 16)
        Quat.mul(t, 24, t, 20, t, 8)
        Quat.mul(t, 24, t, 24, t, 16)
        Quat.normalize(t, 24)
        applyRotate(m, t, 24)
    }

    private fun moveWorld(m: ModelInstance, wx: Float, wy: Float, wz: Float) {
        Mat4.transformDir(frameInv, 0, wx, wy, wz, t, 4)
        applyMove(m, t[4], t[5], t[6])
    }

    // ------------------------------------------------------------------ 角落的 XYZ 拖动块（仿 MMD 视图右下角的图标）

    /** 按下某个 XYZ 拖动块。 */
    fun beginPad(): Boolean {
        dragging = false
        activeHandle = HANDLE_NONE
        dragAmount = 0f
        val m = selectedModel ?: return false
        if (mode == MODE_VIEW || !m.visible) return false
        if (!pivotWorld(pivot)) return false
        pushUndo()
        dragging = true
        return true
    }

    /** 在拖动块上拖动：沿第 axisIndex 根轴（0=X 1=Y 2=Z）旋转 / 移动 / 缩放，pixels 是手指的位移。 */
    fun padDrag(padTool: Int, axisIndex: Int, pixels: Float, w: Int, h: Int) {
        if (!dragging) return
        val m = selectedModel ?: return
        if (!pivotWorld(pivot)) return
        val cam = scene.camera
        val visible = cam.project(pivot[0], pivot[1], pivot[2], w, h, scr)
        val wpp = cam.worldPerPixel(if (visible) scr[2] else abs(cam.distance), h)
        computeFrame(m)
        computeGizmoAxes(m)
        val k = axisIndex.coerceIn(0, 2)
        val o = k * 3
        val g = gAxes
        activeHandle = k + 1
        dragAxis = k + 1
        when (padTool) {
            TOOL_ROTATE -> {
                val angle = pixels * 0.0065f / density * 2f
                rotateWorld(m, g[o], g[o + 1], g[o + 2], angle)
                dragAmount += angle
            }
            TOOL_MOVE -> {
                val amount = pixels * wpp
                moveWorld(m, g[o] * amount, g[o + 1] * amount, g[o + 2] * amount)
                dragAmount += amount
            }
            else -> {
                val e = pixels * 0.006f / density * 2f
                applyScale(m, exp(e))
                dragAmount += e
            }
        }
        m.poseDirty = true
    }

    private fun amountAlongAxis(ax: Float, ay: Float, az: Float, dx: Float, dy: Float, w: Int, h: Int, wpp: Float): Float {
        val cam = scene.camera
        // 轴在屏幕上的方向与"每单位多少像素"
        val sx = (ax * cam.right[0] + ay * cam.right[1] + az * cam.right[2]) / wpp
        val sy = -(ax * cam.up[0] + ay * cam.up[1] + az * cam.up[2]) / wpp
        val l2 = sx * sx + sy * sy
        // 轴几乎垂直于屏幕时用竖直拖动来控制前后
        if (l2 * wpp * wpp < 0.04f) {
            val facing = ax * cam.forward[0] + ay * cam.forward[1] + az * cam.forward[2]
            return -dy * wpp * (if (facing > 0f) 1f else -1f)
        }
        return (dx * sx + dy * sy) / l2
    }

    /**
     * 计算"对象所在的坐标系" F（仿射矩阵）及其逆：
     *  - 模型：父级（世界，或所挂骨骼）的坐标系
     *  - 部件：模型矩阵 × 主骨骼的蒙皮矩阵（绑定姿势空间 → 世界）
     *  - 骨骼：模型矩阵 × 父骨骼的全局矩阵
     */
    private fun computeFrame(m: ModelInstance) {
        Mat4.identity(frame, 0)
        when (mode) {
            MODE_MODEL -> {
                val p = m.attachParent
                if (p != null) {
                    System.arraycopy(p.worldMatrix, 0, frame, 0, 16)
                    val psk = p.skeleton
                    if (psk != null && m.attachBone in 0 until psk.count) Mat4.mulAffine(frame, 0, frame, 0, psk.global, m.attachBone * 16)
                }
            }
            MODE_MESH -> System.arraycopy(m.worldMatrix, 0, frame, 0, 16)
            MODE_PART -> {
                System.arraycopy(m.worldMatrix, 0, frame, 0, 16)
                val part = selectedPart
                if (part != null && m.skeleton != null && part.dominantBone >= 0) {
                    skinMatrix(m, part.dominantBone, t, 32)
                    Mat4.mulAffine(frame, 0, frame, 0, t, 32)
                }
            }
            MODE_BONE -> {
                System.arraycopy(m.worldMatrix, 0, frame, 0, 16)
                val sk = m.skeleton
                if (sk != null && selectedBone >= 0) {
                    val p = sk.parent[selectedBone]
                    if (p >= 0) Mat4.mulAffine(frame, 0, frame, 0, sk.global, p * 16)
                }
            }
        }
        if (!Mat4.invertAffine(frameInv, 0, frame, 0)) Mat4.identity(frameInv, 0)
    }

    /** 把骨骼纹理里的 3x4 蒙皮矩阵展开成列主序 4x4。 */
    private fun skinMatrix(m: ModelInstance, bone: Int, out: FloatArray, oo: Int) {
        val s = m.boneTexels
        val o = bone * 16
        out[oo] = s[o]; out[oo + 4] = s[o + 1]; out[oo + 8] = s[o + 2]; out[oo + 12] = s[o + 3]
        out[oo + 1] = s[o + 4]; out[oo + 5] = s[o + 5]; out[oo + 9] = s[o + 6]; out[oo + 13] = s[o + 7]
        out[oo + 2] = s[o + 8]; out[oo + 6] = s[o + 9]; out[oo + 10] = s[o + 10]; out[oo + 14] = s[o + 11]
        out[oo + 3] = 0f; out[oo + 7] = 0f; out[oo + 11] = 0f; out[oo + 15] = 1f
    }

    private fun applyMove(m: ModelInstance, x: Float, y: Float, z: Float) {
        when (mode) {
            MODE_MESH -> meshTarget?.translate(x, y, z)
            MODE_MODEL -> { m.position[0] += x; m.position[1] += y; m.position[2] += z }
            MODE_PART -> {
                val p = selectedPart ?: return
                p.offset[0] += x; p.offset[1] += y; p.offset[2] += z
                m.parts.apply(p)
            }
            MODE_BONE -> {
                val sk = m.skeleton ?: return
                val b = selectedBone
                if (b < 0) return
                // 不可移动的骨骼就不平移（否则会把模型拉断），改为什么也不做
                if (!sk.bones[b].isTranslatable && sk.bones[b].ik == null) return
                sk.userT[b * 3] += x; sk.userT[b * 3 + 1] += y; sk.userT[b * 3 + 2] += z
                sk.anyUserPose = true
            }
        }
    }

    private fun applyRotate(m: ModelInstance, q: FloatArray, qo: Int) {
        when (mode) {
            MODE_MESH -> meshTarget?.rotate(q, qo)
            MODE_MODEL -> { Quat.mul(m.rotation, 0, q, qo, m.rotation, 0); Quat.normalize(m.rotation, 0) }
            MODE_PART -> {
                val p = selectedPart ?: return
                Quat.mul(p.rotation, 0, q, qo, p.rotation, 0); Quat.normalize(p.rotation, 0)
                m.parts.apply(p)
            }
            MODE_BONE -> {
                val sk = m.skeleton ?: return
                val b = selectedBone
                if (b < 0 || !sk.bones[b].isRotatable) return
                Quat.mul(sk.userR, b * 4, q, qo, sk.userR, b * 4); Quat.normalize(sk.userR, b * 4)
                sk.anyUserPose = true
            }
        }
    }

    private fun applyScale(m: ModelInstance, f: Float) {
        when (mode) {
            MODE_MESH -> {
                if (dragAxis == AXIS_FREE) meshTarget?.scale(f, f, f)
                else {
                    // 操纵轴（世界空间）换到模型空间，沿它单轴缩放
                    val o = (dragAxis - 1) * 3
                    Mat4.transformDir(frameInv, 0, gAxes[o], gAxes[o + 1], gAxes[o + 2], t, 20)
                    val l = sqrt(t[20] * t[20] + t[21] * t[21] + t[22] * t[22])
                    if (l > 1e-9f) meshTarget?.scaleAxis(t[20] / l, t[21] / l, t[22] / l, f)
                }
            }
            MODE_MODEL -> m.scale = (m.scale * f).coerceIn(0.001f, 1000f)
            MODE_PART -> {
                val p = selectedPart ?: return
                if (dragAxis == AXIS_FREE) for (k in 0 until 3) p.scale[k] = (p.scale[k] * f).coerceIn(0.01f, 100f)
                else { val k = dragAxis - 1; p.scale[k] = (p.scale[k] * f).coerceIn(0.01f, 100f) }
                m.parts.apply(p)
            }
        }
    }

    /** 选中对象的操纵中心（世界坐标）。 */
    fun pivotWorld(out: FloatArray): Boolean {
        val m = selectedModel ?: return false
        when (mode) {
            MODE_MESH -> {
                if (meshTarget?.pivot(gv) != true) return false
                Mat4.transformPoint(m.worldMatrix, 0, gv[0], gv[1], gv[2], out, 0)
            }
            MODE_PART -> {
                val p = selectedPart ?: return false
                m.parts.currentPivot(p, t, 40)
                var x = t[40]; var y = t[41]; var z = t[42]
                if (m.skeleton != null && p.dominantBone >= 0) {
                    skinMatrix(m, p.dominantBone, t, 44)
                    Mat4.transformPoint(t, 44, x, y, z, t, 40)
                    x = t[40]; y = t[41]; z = t[42]
                }
                Mat4.transformPoint(m.worldMatrix, 0, x, y, z, out, 0)
            }
            MODE_BONE -> {
                if (selectedBone < 0 || m.skeleton == null) return false
                m.boneWorldPosition(selectedBone, out, 0)
            }
            else -> { out[0] = m.worldMatrix[12]; out[1] = m.worldMatrix[13]; out[2] = m.worldMatrix[14] }
        }
        return true
    }

    // ------------------------------------------------------------------ 撤销

    private fun trimUndo() {
        var total = 0L
        for (v in undoBytes.values) total += v
        while (undoStack.size > 64 || (total > UNDO_BYTE_LIMIT && undoStack.size > 1)) {
            undoBytes.remove(undoStack.removeAt(0))?.let { total -= it }
        }
    }

    /** 记一步自定义的撤销（关键帧编辑用）。[bytes] 是这一步占的内存。 */
    fun pushUndo(bytes: Int, action: () -> Unit) {
        undoStack.add(action)
        if (bytes > 0) undoBytes[action] = bytes
        undoSerial++
        trimUndo()
    }

    /** 丢掉最近记的一步撤销（记下之后发现其实什么都没改）。 */
    fun discardLastUndo() {
        if (undoStack.isEmpty()) return
        undoBytes.remove(undoStack.removeAt(undoStack.size - 1))
    }

    /** 把一个额外的动作并进最近一步撤销里（拖完骨骼自动登记的关键帧，要和那次拖动一起撤销）。 */
    fun amendUndo(bytes: Int, action: () -> Unit) {
        if (undoStack.isEmpty()) { pushUndo(bytes, action); return }
        val last = undoStack.removeAt(undoStack.size - 1)
        val total = bytes + (undoBytes.remove(last) ?: 0)
        val both: () -> Unit = { action(); last() }
        undoStack.add(both)
        if (total > 0) undoBytes[both] = total
        trimUndo()
    }

    private fun pushUndo() {
        val before = undoStack.size
        pushUndoEntry()
        if (undoStack.size != before) undoSerial++
        trimUndo()
    }

    private fun pushUndoEntry() {
        val m = selectedModel ?: return
        when (mode) {
            MODE_MESH -> meshTarget?.snapshot()?.let { undoStack.add(it) }
            MODE_MODEL -> {
                val p = m.position.copyOf(); val r = m.rotation.copyOf(); val s = m.scale
                undoStack.add { System.arraycopy(p, 0, m.position, 0, 3); System.arraycopy(r, 0, m.rotation, 0, 4); m.scale = s; m.poseDirty = true }
            }
            MODE_PART -> {
                val part = selectedPart ?: return
                recordPart(part)
            }
            MODE_BONE -> {
                val sk = m.skeleton ?: return
                val b = selectedBone
                if (b < 0) return
                val tt = sk.userT.copyOfRange(b * 3, b * 3 + 3); val rr = sk.userR.copyOfRange(b * 4, b * 4 + 4)
                undoStack.add { System.arraycopy(tt, 0, sk.userT, b * 3, 3); System.arraycopy(rr, 0, sk.userR, b * 4, 4); m.poseDirty = true }
            }
        }
    }

    /** 记录一个部件当前的状态（面板里改数值 / 隐藏 / 重置之前调用）。 */
    fun recordPart(part: Part) {
        val o = part.offset.copyOf(); val r = part.rotation.copyOf(); val s = part.scale.copyOf(); val hid = part.hidden; val del = part.deleted
        val model = part.set.model
        // 整块（材质级）部件的显示 / 隐藏记在材质上，一起记下来
        val shown = if (part.island < 0) model.materials.getOrNull(part.material)?.visible else null
        undoStack.add {
            System.arraycopy(o, 0, part.offset, 0, 3); System.arraycopy(r, 0, part.rotation, 0, 4); System.arraycopy(s, 0, part.scale, 0, 3); part.hidden = hid; part.deleted = del
            if (shown != null) model.materials.getOrNull(part.material)?.visible = shown
            model.parts.apply(part)
        }
        trimUndo()
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val action = undoStack.removeAt(undoStack.size - 1)
        undoBytes.remove(action)
        action.invoke()
        onChanged?.invoke()
        return true
    }

    // ------------------------------------------------------------------ 辅助线

    /** 往辅助线批次里填：骨骼、操纵器。每帧渲染前调用。 */
    fun fillOverlay(batch: LineBatch, w: Int, h: Int) {
        val m = selectedModel ?: return
        if (mode == MODE_VIEW || !m.visible) return
        val cam = scene.camera
        if (mode == MODE_BONE) drawBones(batch, m, w, h)
        if (!showGizmo) return
        if (!pivotWorld(pivot)) return
        if (!cam.project(pivot[0], pivot[1], pivot[2], w, h, scr)) return
        val len = cam.worldPerPixel(scr[2], h) * gizmoSizeDp * density
        computeFrame(m)
        computeGizmoAxes(m)
        val g = gAxes
        val lw = 2.2f * density
        val px = pivot[0]; val py = pivot[1]; val pz = pivot[2]
        // 从操纵中心指向相机的方向：箭头朝向相机摊开、环的背面画淡一些都要用
        var vx = cam.position[0] - px; var vy = cam.position[1] - py; var vz = cam.position[2] - pz
        if (!cam.perspective) { vx = -cam.forward[0]; vy = -cam.forward[1]; vz = -cam.forward[2] }
        val vl = sqrt(vx * vx + vy * vy + vz * vz)
        if (vl > 1e-6f) { vx /= vl; vy /= vl; vz /= vl }
        val handleDrag = dragging && activeHandle != HANDLE_NONE

        for (k in 0 until 3) {
            val o = k * 3
            val ax = g[o]; val ay = g[o + 1]; val az = g[o + 2]
            val active = if (dragging) dragAxis == k + 1 else axis == k + 1
            var r = if (k == 0) 1f else 0.25f
            var gg = if (k == 1) 0.9f else 0.3f
            var b = if (k == 2) 1f else 0.3f
            if (active && dragging) { r = 1f; gg = 0.86f; b = 0.2f }
            val a = if (handleDrag && !active) 0.22f else 1f
            val width = if (active) lw * 1.5f else lw
            if (tool == TOOL_ROTATE) {
                drawRing(batch, k, len, vx, vy, vz, r, gg, b, a, width)
            } else {
                batch.line(px + ax * len * 0.14f, py + ay * len * 0.14f, pz + az * len * 0.14f, px + ax * len, py + ay * len, pz + az * len, r, gg, b, a, width)
                val ex = px + ax * len; val ey = py + ay * len; val ez = pz + az * len
                if (tool == TOOL_MOVE) {
                    // 箭头：一个朝向相机摊开的三角形
                    var sx = ay * vz - az * vy; var sy = az * vx - ax * vz; var sz = ax * vy - ay * vx
                    val sl = sqrt(sx * sx + sy * sy + sz * sz)
                    if (sl > 0.12f) {
                        val hw = len * 0.075f / sl
                        sx *= hw; sy *= hw; sz *= hw
                        batch.tri(px + ax * len * 1.2f, py + ay * len * 1.2f, pz + az * len * 1.2f, ex + sx, ey + sy, ez + sz, ex - sx, ey - sy, ez - sz, r, gg, b, a)
                    } else batch.point(ex, ey, ez, r, gg, b, a, 9f * density)
                } else batch.point(ex, ey, ez, r, gg, b, a, 10f * density)
            }
            // 拖动中：沿这根轴画一条贯穿的辅助线
            if (dragging && active && tool != TOOL_ROTATE) {
                val far = len * 40f
                batch.line(px - ax * far, py - ay * far, pz - az * far, px + ax * far, py + ay * far, pz + az * far, r, gg, b, 0.5f, lw * 0.5f)
            }
            if (dragging && active && tool == TOOL_ROTATE) {
                // 旋转轴本身
                batch.line(px - ax * len * 1.4f, py - ay * len * 1.4f, pz - az * len * 1.4f, px + ax * len * 1.4f, py + ay * len * 1.4f, pz + az * len * 1.4f, r, gg, b, 0.45f, lw * 0.5f)
            }
        }
        if (tool == TOOL_ROTATE) {
            // 外圈：绕视线方向转
            val on = activeHandle == HANDLE_VIEW
            batch.circle(px, py, pz, cam.right[0], cam.right[1], cam.right[2], cam.up[0], cam.up[1], cam.up[2], len * VIEW_RING,
                1f, if (on) 0.86f else 1f, if (on) 0.2f else 1f, if (on) 1f else if (handleDrag) 0.15f else 0.5f, if (on) lw * 1.3f else lw * 0.7f, 64)
        }
        val centerOn = activeHandle == HANDLE_CENTER
        batch.point(px, py, pz, 1f, if (centerOn) 0.86f else 1f, if (centerOn) 0.2f else 1f, 1f, (if (tool == TOOL_ROTATE) 6f else 9f) * density)
    }

    /** 第 k 根轴的旋转环；背对相机的半圈画得淡一些，看得出立体感。 */
    private fun drawRing(batch: LineBatch, k: Int, len: Float, vx: Float, vy: Float, vz: Float, r: Float, g: Float, b: Float, a: Float, width: Float) {
        val ax = gAxes
        val u = ((k + 1) % 3) * 3; val v = ((k + 2) % 3) * 3
        val step = (Math.PI * 2.0).toFloat() / RING_SEGMENTS
        var prx = ax[u]; var pry = ax[u + 1]; var prz = ax[u + 2]
        // 环相对视线倾斜的程度：0 = 正对相机（没有"背面"），1 = 完全侧对
        val facing = ax[k * 3] * vx + ax[k * 3 + 1] * vy + ax[k * 3 + 2] * vz
        val tilt = sqrt(maxOf(0f, 1f - facing * facing))
        for (i in 1..RING_SEGMENTS) {
            val ang = i * step
            val cs = cos(ang); val sn = sin(ang)
            val rx = ax[u] * cs + ax[v] * sn; val ry = ax[u + 1] * cs + ax[v + 1] * sn; val rz = ax[u + 2] * cs + ax[v + 2] * sn
            val front = tilt < 0.35f || ((rx + prx) * vx + (ry + pry) * vy + (rz + prz) * vz) * 0.5f > -0.18f * tilt
            batch.line(pivot[0] + prx * len, pivot[1] + pry * len, pivot[2] + prz * len, pivot[0] + rx * len, pivot[1] + ry * len, pivot[2] + rz * len,
                r, g, b, if (front) a else a * 0.28f, if (front) width else width * 0.6f)
            prx = rx; pry = ry; prz = rz
        }
    }

    private fun drawBones(batch: LineBatch, m: ModelInstance, w: Int, h: Int) {
        val sk = m.skeleton ?: return
        val driven = m.physics?.takeIf { m.physicsEnabled && scene.physicsEnabled }?.drivenBones
        val lw = 1.2f * density
        for (b in 0 until sk.count) {
            val bd = sk.bones[b]
            if (!bd.isVisible) continue
            if (driven != null && driven[b]) continue
            m.boneWorldPosition(b, t, 52)
            val sel = b == selectedBone
            // 骨骼指向：子骨骼，或自身朝向上的偏移
            var has = false
            if (bd.tailBone in 0 until sk.count) { m.boneWorldPosition(bd.tailBone, t, 56); has = true }
            else if (bd.tailOffset[0] != 0f || bd.tailOffset[1] != 0f || bd.tailOffset[2] != 0f) {
                Mat4.transformPoint(sk.global, b * 16, bd.tailOffset[0], bd.tailOffset[1], bd.tailOffset[2], t, 60)
                Mat4.transformPoint(m.worldMatrix, 0, t[60], t[61], t[62], t, 56)
                has = true
            }
            val ik = bd.ik != null
            val r = if (sel) 1f else if (ik) 1f else 0.35f
            val g = if (sel) 0.8f else if (ik) 0.45f else 0.75f
            val bl = if (sel) 0.1f else if (ik) 0.3f else 1f
            if (has) batch.line(t[52], t[53], t[54], t[56], t[57], t[58], r, g, bl, if (sel) 1f else 0.55f, if (sel) lw * 1.8f else lw)
            if (bd.isOperable) batch.point(t[52], t[53], t[54], r, g, bl, if (sel) 1f else 0.85f, (if (sel) 8f else 4.5f) * density)
        }
    }

    companion object {
        /** 撤销记录里"大块头"（动作快照）的总大小上限。 */
        private const val UNDO_BYTE_LIMIT = 24L * 1024 * 1024
        const val MODE_VIEW = 0
        const val MODE_MODEL = 1
        const val MODE_PART = 2
        const val MODE_BONE = 3
        /** 网格编辑（建模）：操纵 [MeshTarget] 里选中的顶点 / 面。 */
        const val MODE_MESH = 4

        const val TOOL_MOVE = 0
        const val TOOL_ROTATE = 1
        const val TOOL_SCALE = 2

        const val AXIS_FREE = 0
        const val AXIS_X = 1
        const val AXIS_Y = 2
        const val AXIS_Z = 3

        const val HANDLE_NONE = 0
        const val HANDLE_X = 1
        const val HANDLE_Y = 2
        const val HANDLE_Z = 3
        /** 移动 / 缩放工具中心的方块：在屏幕平面里自由移动 / 等比缩放。 */
        const val HANDLE_CENTER = 4
        /** 旋转工具最外面的白圈：绕视线方向转。 */
        const val HANDLE_VIEW = 5

        private const val RING_SEGMENTS = 64
        private const val VIEW_RING = 1.2f
    }
}

/** 网格编辑模式下被操纵的对象（选中的顶点 / 面）。坐标都在模型空间里。 */
interface MeshTarget {
    /** 选中元素的中心 → out[0..2]；没有选中任何东西时返回 false。 */
    fun pivot(out: FloatArray): Boolean
    fun translate(dx: Float, dy: Float, dz: Float)
    /** 绕选中元素的中心旋转。 */
    fun rotate(q: FloatArray, qo: Int)
    /** 以选中元素的中心为基准缩放。 */
    fun scale(sx: Float, sy: Float, sz: Float)
    /**
     * 一次拖动开始之前调用：记下当前状态。
     * 返回"恢复到这个状态"的操作交给 [Editor] 的撤销栈；自己管理撤销的实现返回 null。
     */
    fun snapshot(): (() -> Unit)?
    /** 一次拖动结束（手指抬起）。 */
    fun dragEnded() {}
    /**
     * "局部轴"模式下操纵器的朝向（模型空间里的三根单位轴，依次写进 out[0..8]）：
     * 通常让 Y 轴沿选中面的法线，这样挤出之后可以顺着法线拖。返回 false = 用模型自己的坐标轴。
     */
    fun orientation(out: FloatArray): Boolean = false
    /** 沿模型空间里的某个方向（单位向量）单轴缩放。 */
    fun scaleAxis(ax: Float, ay: Float, az: Float, factor: Float) {}
}

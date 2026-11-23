package com.lumi.mmd.engine.scene

import com.lumi.mmd.engine.anim.MaterialState
import com.lumi.mmd.engine.anim.MorphController
import com.lumi.mmd.engine.anim.Motion
import com.lumi.mmd.engine.anim.MotionBinding
import com.lumi.mmd.engine.anim.MotionClip
import com.lumi.mmd.engine.anim.Skeleton
import com.lumi.mmd.engine.math.Mat4
import com.lumi.mmd.engine.math.Quat
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.physics.ModelPhysics
import java.util.concurrent.atomic.AtomicInteger

/**
 * 场景里的一个模型实例：角色、舞台或配件。
 * 持有骨骼 / 表情 / 物理 / 动作绑定等运行时状态，以及整体的位移、旋转、缩放和"挂到别的模型骨骼上"的关系。
 */
class ModelInstance(val data: ModelData, var kind: Int, keepId: Int = 0) {
    val id: Int = if (keepId > 0) keepId else nextId.getAndIncrement()
    var name: String = data.name.ifBlank { data.sourceFile?.nameWithoutExtension ?: "模型" }
    var visible = true

    // ---- 整体变换（相对父级：世界，或所挂的骨骼）----
    val position = FloatArray(3)
    val rotation = floatArrayOf(0f, 0f, 0f, 1f)
    var scale = 1f

    /** 挂载：把本模型固定在另一个模型的某根骨骼上（头饰、手持物等）。 */
    var attachParent: ModelInstance? = null
    var attachBone = -1

    val worldMatrix = FloatArray(16).also { Mat4.identity(it, 0) }

    val skeleton: Skeleton? = if (data.isSkinned) Skeleton(data.bones) else null
    val materials: Array<MaterialState> = Array(data.materials.size) { MaterialState(data.materials[it]) }
    val morphs = MorphController(data, skeleton, materials)
    val physics: ModelPhysics? = if (skeleton != null && data.hasPhysics) ModelPhysics(data, skeleton) else null
    var physicsEnabled = true
    /** 这个模型自己的物理参数倍数（null = 完全按全局的来）。 */
    var physicsTuning: com.lumi.mmd.engine.physics.PhysicsTuning? = null

    // ---- 动作：时间轴上的若干片段（按开始位置排好，不重叠）----
    val clips = ArrayList<MotionClip>()
    /** 当前帧（或者正在编辑的那一帧）所在的片段。关键帧编辑、动作 / 表情槽都是对它操作。 */
    var activeClip: MotionClip? = null
        private set
    private var appliedClip: MotionClip? = null

    /** 当前片段的动作绑定（没有片段时为 null）。 */
    val motion: MotionBinding? get() = activeClip?.binding(data)
    /** 时间轴帧号 − motionOffset = 当前片段的动作里的帧号。 */
    var motionOffset: Float
        get() = activeClip?.offset?.toFloat() ?: 0f
        set(v) { activeClip?.let { it.start = Math.round(v) + it.trimIn } }
    /** 当前片段的来源（见 [MotionClip.sources]）。 */
    var motionSources: List<String>
        get() = activeClip?.sources ?: emptyList()
        set(v) { activeClip?.sources = v }

    /** 整体的位移 / 转向动画由谁接管（操控模式）。不为 null 时不按片段取动作。 */
    var animator: Animator? = null
        set(v) { if (field !== v) { field = v; poseDirty = true; physics?.requestReset() } }

    /** 脚本临时创建的模型（几何体、分身）：不存进工程，脚本停掉就消失。 */
    var transient = false

    /** 画在第几层：数字大的盖在数字小的上面（和剪辑软件的上下层一样），同一层里按正常的远近遮挡。 */
    var layer = 0

    /**
     * 碰撞属性：这个模型挡不挡别的东西（别的模型的头发裙子、脚本的质点 / 布 / 水 / 刚体、操控模式里的角色、射线查询）。
     * [COLLISION_NONE] 不挡；[COLLISION_BOX] 用整个模型的包围盒挡（道具、箱子）；[COLLISION_MESH] 用模型的每一个面挡（舞台、台阶、桌子）。
     * 按绑定姿势的形状算，跟着模型的位置 / 旋转 / 缩放走，不跟骨骼动作变形。
     */
    var collision = COLLISION_NONE
        set(v) { field = v.coerceIn(COLLISION_NONE, COLLISION_MESH) }
    private var meshShape: com.lumi.mmd.engine.physics.MeshShape? = null
    private var meshTried = false
    private var bindBox: FloatArray? = null

    /** 网格碰撞用的面（第一次用到时建一次树）。面太多、或者模型没有面时返回 null，调用方退回包围盒。 */
    fun collisionMesh(): com.lumi.mmd.engine.physics.MeshShape? {
        if (!meshTried) {
            meshTried = true
            if (data.indices.size >= 3 && data.indices.size / 3 <= com.lumi.mmd.engine.physics.MeshShape.MAX_TRIANGLES) {
                meshShape = com.lumi.mmd.engine.physics.MeshShape(data.positions, data.indices).takeIf { it.triangleCount > 0 }
            }
        }
        return meshShape
    }

    /** 绑定姿势下顶点的范围（模型自己的坐标）：min xyz, max xyz。 */
    fun bindBounds(): FloatArray {
        bindBox?.let { return it }
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        val p = data.positions
        var i = 0
        while (i + 2 < p.size) {
            for (k in 0 until 3) { if (p[i + k] < b[k]) b[k] = p[i + k]; if (p[i + k] > b[3 + k]) b[3 + k] = p[i + k] }
            i += 3
        }
        if (p.size < 3) b.fill(0f)
        bindBox = b
        return b
    }

    /** 每根骨骼 16 个 float：蒙皮矩阵的 3 行 + 旋转四元数（直接上传成骨骼纹理）。 */
    val boneTexels: FloatArray = FloatArray((skeleton?.count ?: 0) * 16)
    /** 骨骼纹理需要重新上传。 */
    var boneTexelsDirty = true

    // ---- 渲染选项 ----
    var castShadow = true
    var receiveShadow = true
    var drawEdge = true
    var edgeScale = 1f
    var opacity = 1f

    /** 部件（材质 / 连通块）编辑。 */
    val parts: PartSet by lazy { PartSet(this) }
    val partsInitialized: Boolean get() = partsLazy
    private var partsLazy = false

    /** 用户改动了姿势 / 表情等，下一帧必须重算骨骼。 */
    var poseDirty = true
    private var wasAnimated = false
    private var skinned: FloatArray? = null
    private val tmp = FloatArray(32)
    private val boundsMinTmp = FloatArray(3)
    private val boundsMaxTmp = FloatArray(3)

    val isCharacter: Boolean get() = kind == KIND_CHARACTER
    val isStage: Boolean get() = kind == KIND_STAGE

    internal fun markPartsCreated() { partsLazy = true }

    /** 绑定姿势下"顶点到它主要骨骼"的典型最大距离，用来把骨骼包围盒外扩成网格包围盒。 */
    private val skinMargin: Float by lazy {
        val bi = data.boneIndices
        val bw = data.boneWeights
        if (bi == null || bw == null || data.bones.isEmpty()) return@lazy 0f
        val pos = data.positions
        var maxD2 = 0f
        // 抽样即可（最多约 2 万个顶点）
        val step = maxOf(1, data.vertexCount / 20000)
        var v = 0
        while (v < data.vertexCount) {
            var best = 0
            for (k in 1 until 4) if (bw[v * 4 + k] > bw[v * 4 + best]) best = k
            val b = bi[v * 4 + best].toInt() and 0xFFFF
            if (b < data.bones.size) {
                val bp = data.bones[b].position
                val dx = pos[v * 3] - bp[0]; val dy = pos[v * 3 + 1] - bp[1]; val dz = pos[v * 3 + 2] - bp[2]
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 > maxD2) maxD2 = d2
            }
            v += step
        }
        kotlin.math.sqrt(maxD2) + 0.5f
    }

    /** 找出 frame 所在的片段并设为当前片段：最后一个已经开始的；都还没开始就取第一个。 */
    fun selectClip(frame: Float): MotionClip? {
        var best: MotionClip? = null
        for (c in clips) if (c.start <= frame && (best == null || c.start >= best.start)) best = c
        if (best == null) for (c in clips) if (best == null || c.start < best.start) best = c
        activeClip = best
        return best
    }

    fun selectClip(frame: Int): MotionClip? = selectClip(frame.toFloat())

    /** 把某个片段设为当前片段（它必须在 [clips] 里）。 */
    fun focusClip(c: MotionClip?) { activeClip = if (c != null && clips.contains(c)) c else null }

    /**
     * 换掉当前片段的动作（没有片段就新建一段，从第 0 帧开始）；null = 去掉当前片段。
     */
    fun setMotion(m: Motion?) {
        val c = activeClip
        if (m == null) {
            if (c != null) clips.remove(c)
            activeClip = clips.firstOrNull()
        } else if (c != null) {
            c.motion = m; c.owned = false; c.trimIn = 0; c.trimOut = -1
            c.name = m.name.ifBlank { c.name }
        } else {
            val n = MotionClip(m, m.name.ifBlank { "动作" })
            clips.add(n)
            activeClip = n
        }
        skeleton?.let { sk -> for (i in 0 until sk.count) sk.ikEnabled[i] = true }
        appliedClip = null
        poseDirty = true
        physics?.requestReset()
    }

    /** 去掉所有片段。 */
    fun clearClips() {
        clips.clear(); activeClip = null; appliedClip = null
        skeleton?.let { sk -> for (i in 0 until sk.count) sk.ikEnabled[i] = true }
        poseDirty = true
        physics?.requestReset()
    }

    /** 片段被移动 / 增删之后调用：按开始位置重新排好。 */
    fun sortClips() {
        clips.sortBy { it.start }
        if (activeClip != null && !clips.contains(activeClip)) activeClip = null
        poseDirty = true
    }

    /** 动作每被编辑一次加一（上层据此判断要不要把改动存成文件）。 */
    var motionRevision = 0

    /** 当前片段的动作是不是它独占的（可以直接修改）。从文件加载的动作可能被多个片段共享。 */
    val motionOwned: Boolean get() = activeClip?.owned == true

    /**
     * 取一份可以修改的动作：没有片段就新建一段空的，共享的动作先复制一份。
     * 打关键帧之前调用。
     */
    fun editableMotion(): Motion {
        val c = activeClip
        if (c != null && c.owned) return c.motion
        if (c != null) {
            c.motion = c.motion.copy(); c.owned = true
            poseDirty = true
            return c.motion
        }
        val m = Motion().also { it.modelName = name }
        val n = MotionClip(m, "自制动作")
        n.owned = true
        clips.add(n)
        activeClip = n
        poseDirty = true
        return m
    }

    /** 把当前片段的动作整份换成 [m]（独占的一份；null = 去掉当前片段）。不重置物理。 */
    fun restoreMotion(m: Motion?) {
        val c = activeClip
        if (m == null) { if (c != null) clips.remove(c); activeClip = clips.firstOrNull() }
        else if (c != null) { c.motion = m; c.owned = true }
        else { val n = MotionClip(m, "自制动作"); n.owned = true; clips.add(n); activeClip = n }
        motionRevision++
        poseDirty = true
    }

    /** 某一时刻所有片段的样子（撤销用）。 */
    class ClipState(val clip: MotionClip, val motion: Motion, val start: Int, val trimIn: Int, val trimOut: Int, val owned: Boolean, val sources: List<String>, val name: String, val fadeIn: Int = 0)

    /**
     * 记下所有片段现在的样子。
     * @param copyActive 接下来要改当前片段的关键帧：把它的动作复制一份存起来（共享的动作改之前本来就会先复制，不用再存一份）
     */
    fun snapshotClips(copyActive: Boolean): List<ClipState> = clips.map { c ->
        val keep = if (copyActive && c === activeClip && c.owned) c.motion.copy() else c.motion
        ClipState(c, keep, c.start, c.trimIn, c.trimOut, c.owned, c.sources, c.name, c.fadeIn)
    }

    fun restoreClips(state: List<ClipState>) {
        clips.clear()
        for (s in state) {
            val c = s.clip
            if (c.motion !== s.motion) c.motion = s.motion
            c.start = s.start; c.trimIn = s.trimIn; c.trimOut = s.trimOut; c.owned = s.owned; c.sources = s.sources; c.name = s.name; c.fadeIn = s.fadeIn
            clips.add(c)
        }
        if (activeClip != null && !clips.contains(activeClip)) activeClip = null
        appliedClip = null
        motionRevision++
        poseDirty = true
    }

    /** 动作里新增 / 删除了整条轨道之后调用：重新按名字对应骨骼和表情。 */
    fun rebindMotion() {
        activeClip?.rebind()
        skeleton?.let { sk -> for (i in 0 until sk.count) sk.ikEnabled[i] = true }
        appliedClip = null
        poseDirty = true
    }

    /**
     * 网格被重建（建模）之后，用新的模型数据生成一个"替身"：
     * 沿用原来的序号、名字、位置、挂载关系、动作、渲染选项、手动摆的姿势和表情。
     * 调用方负责在场景里把旧的换成它（[Scene.replace]）。
     */
    fun replacedBy(newData: ModelData): ModelInstance {
        val n = ModelInstance(newData, kind, id)
        n.name = name; n.visible = visible
        System.arraycopy(position, 0, n.position, 0, 3)
        System.arraycopy(rotation, 0, n.rotation, 0, 4)
        n.scale = scale
        n.attachParent = attachParent; n.attachBone = attachBone
        System.arraycopy(worldMatrix, 0, n.worldMatrix, 0, 16)
        n.physicsEnabled = physicsEnabled; n.physicsTuning = physicsTuning
        n.clips.addAll(clips); n.activeClip = activeClip
        n.animator = animator; n.layer = layer
        n.castShadow = castShadow; n.receiveShadow = receiveShadow; n.drawEdge = drawEdge; n.edgeScale = edgeScale; n.opacity = opacity
        n.restPose = restPose
        val a = skeleton; val b = n.skeleton
        if (a != null && b != null && a.count == b.count) {
            System.arraycopy(a.userT, 0, b.userT, 0, a.userT.size)
            System.arraycopy(a.userR, 0, b.userR, 0, a.userR.size)
            b.anyUserPose = a.anyUserPose
        }
        if (morphs.count > 0 && n.morphs.count > 0) {
            val byName = HashMap<String, Int>()
            for (i in 0 until n.morphs.count) byName[newData.morphs[i].name] = i
            for (i in 0 until morphs.count) {
                val w = morphs.userWeights[i]
                if (w != 0f) byName[data.morphs[i].name]?.let { n.morphs.userWeights[it] = w }
            }
        }
        for (i in materials.indices) if (i < n.materials.size) n.materials[i].visible = materials[i].visible
        return n
    }

    /** 建模时为 true：不套用动作和手动摆的姿势，模型保持初始姿势（顶点位置与模型数据一致）。 */
    var restPose = false
        set(v) { if (field != v) { field = v; poseDirty = true; physics?.requestReset() } }

    fun setPosition(x: Float, y: Float, z: Float) { position[0] = x; position[1] = y; position[2] = z }

    /** 更新 worldMatrix。挂载的父模型必须已经先更新过。 */
    fun updateWorldMatrix() {
        val m = worldMatrix
        Mat4.setTRS(m, 0, position[0], position[1], position[2], rotation[0], rotation[1], rotation[2], rotation[3], scale, scale, scale)
        val parent = attachParent ?: return
        val psk = parent.skeleton
        if (psk != null && attachBone >= 0 && attachBone < psk.count) {
            Mat4.mulAffine(m, 0, psk.global, attachBone * 16, m, 0)
        }
        Mat4.mulAffine(m, 0, parent.worldMatrix, 0, m, 0)
    }

    /**
     * 推进到 frame 时刻。
     * @param dt 距上一帧的秒数（给物理用）
     * @param physicsOn 场景的物理总开关
     * @param jumped 时间轴发生了跳转（拖动进度条、循环回到开头）
     */
    fun update(frame: Float, dt: Float, physicsOn: Boolean, jumped: Boolean) {
        val sk = skeleton
        if (sk == null) {
            morphs.evaluate()
            return
        }
        val phys = physics
        val physActive = phys != null && physicsOn && physicsEnabled
        val anim = animator
        val animated = clips.isNotEmpty() || anim != null || sk.hasScriptLayer
        if (!animated && !physActive && !poseDirty && !wasAnimated) {
            morphs.evaluate()
            return
        }
        wasAnimated = animated || physActive
        poseDirty = false
        sk.beginFrame()
        morphs.beginFrame()
        if (restPose) {
            morphs.evaluate()
            sk.prepare(false)
            sk.update(false)
            sk.update(true)
            updateBoneTexels()
            return
        }
        if (anim != null) {
            anim.apply(this, sk, morphs, dt)
        } else {
            val c = selectClip(frame)
            if (c !== appliedClip) {
                // 换了片段：上一段留下的 IK 开关不带过来
                appliedClip = c
                for (i in 0 until sk.count) sk.ikEnabled[i] = true
            }
            val from = if (c != null) ClipOps.fadingFrom(clips, c, frame) else null
            if (c != null && from != null) blendClips(from, c, frame, sk)
            else c?.binding(data)?.apply(c.localFrame(frame), sk, morphs)
        }
        sk.applyScriptLayer()
        morphs.evaluate()
        sk.prepare()
        sk.update(false)
        if (phys != null) {
            phys.enabled = physActive
            if (jumped) phys.requestReset()
            phys.update(dt)
        }
        sk.update(true)
        updateBoneTexels()
    }

    private var fadeT = FloatArray(0)
    private var fadeR = FloatArray(0)
    private var fadeW = FloatArray(0)

    /**
     * 两段之间的过渡：先求出前一段此刻的姿势，再求这一段的，按比重混合（位移、表情线性，旋转走球面）。
     * 只在过渡的那几帧里走这条路，平时每帧只求一段。
     */
    private fun blendClips(from: MotionClip, to: MotionClip, frame: Float, sk: Skeleton) {
        val nT = sk.count * 3; val nR = sk.count * 4; val nW = morphs.animWeights.size
        if (fadeT.size != nT) { fadeT = FloatArray(nT); fadeR = FloatArray(nR) }
        if (fadeW.size != nW) fadeW = FloatArray(nW)
        from.binding(data).apply(from.fadeOutFrame(frame, to), sk, morphs)
        System.arraycopy(sk.animT, 0, fadeT, 0, nT)
        System.arraycopy(sk.animR, 0, fadeR, 0, nR)
        System.arraycopy(morphs.animWeights, 0, fadeW, 0, nW)
        sk.beginFrame()
        morphs.beginFrame()
        // IK 开关不做混合：按后一段的来
        for (i in 0 until sk.count) sk.ikEnabled[i] = true
        to.binding(data).apply(to.localFrame(frame), sk, morphs)
        val k = ClipOps.fadeWeight(to, frame)
        val t = sk.animT; val r = sk.animR; val w = morphs.animWeights
        for (i in 0 until nT) t[i] = fadeT[i] + (t[i] - fadeT[i]) * k
        var o = 0
        while (o < nR) { Quat.slerp(r, o, fadeR, o, r, o, k); o += 4 }
        for (i in 0 until nW) w[i] = fadeW[i] + (w[i] - fadeW[i]) * k
    }

    private fun updateBoneTexels() {
        val sk = skeleton ?: return
        val g = sk.global
        val bp = sk.bindPos
        val out = boneTexels
        val needQuat = data.hasSdef
        for (b in 0 until sk.count) {
            val o = b * 16
            val px = bp[b * 3]; val py = bp[b * 3 + 1]; val pz = bp[b * 3 + 2]
            val m00 = g[o]; val m10 = g[o + 1]; val m20 = g[o + 2]
            val m01 = g[o + 4]; val m11 = g[o + 5]; val m21 = g[o + 6]
            val m02 = g[o + 8]; val m12 = g[o + 9]; val m22 = g[o + 10]
            out[o] = m00; out[o + 1] = m01; out[o + 2] = m02; out[o + 3] = g[o + 12] - (m00 * px + m01 * py + m02 * pz)
            out[o + 4] = m10; out[o + 5] = m11; out[o + 6] = m12; out[o + 7] = g[o + 13] - (m10 * px + m11 * py + m12 * pz)
            out[o + 8] = m20; out[o + 9] = m21; out[o + 10] = m22; out[o + 11] = g[o + 14] - (m20 * px + m21 * py + m22 * pz)
            if (needQuat) Mat4.toQuat(g, o, out, o + 12)
        }
        if (sk.anyScriptScale) {
            // 脚本给骨骼设的缩放：以骨骼自己的位置为中心，把绑在它上面的顶点放大 / 缩小
            val sc = sk.scriptScale
            for (b in 0 until sk.count) {
                val sx = sc[b * 3]; val sy = sc[b * 3 + 1]; val sz = sc[b * 3 + 2]
                if (sx == 1f && sy == 1f && sz == 1f) continue
                val o = b * 16
                val px = bp[b * 3]; val py = bp[b * 3 + 1]; val pz = bp[b * 3 + 2]
                for (r in 0 until 3) {
                    val k = o + r * 4
                    val t = out[k + 3] + out[k] * px + out[k + 1] * py + out[k + 2] * pz
                    out[k] *= sx; out[k + 1] *= sy; out[k + 2] *= sz
                    out[k + 3] = t - (out[k] * px + out[k + 1] * py + out[k + 2] * pz)
                }
            }
        }
        boneTexelsDirty = true
    }

    /** 回到初始姿势并清空用户的姿势 / 表情调整。 */
    fun resetPose() {
        skeleton?.clearUserPose()
        morphs.resetUserWeights()
        poseDirty = true
        physics?.requestReset()
    }

    /** 让物理空转一会儿，使头发裙摆自然垂落。 */
    fun settlePhysics(frame: Float, seconds: Float = 1f) {
        val phys = physics ?: return
        if (!physicsEnabled) return
        poseDirty = true
        update(frame, 0f, false, true)
        phys.enabled = true
        phys.requestReset()
        var t = 0f
        while (t < seconds) {
            update(frame, 1f / 60f, true, false)
            t += 1f / 60f
        }
    }

    /** 模型空间里的当前包围盒（蒙皮模型用骨骼位置近似）。 */
    fun localBounds(min: FloatArray, max: FloatArray) {
        val sk = skeleton
        if (sk == null || sk.count == 0) {
            System.arraycopy(data.boundsMin, 0, min, 0, 3)
            System.arraycopy(data.boundsMax, 0, max, 0, 3)
            return
        }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        val g = sk.global
        for (b in 0 until sk.count) {
            val o = b * 16
            val x = g[o + 12]; val y = g[o + 13]; val z = g[o + 14]
            if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
            if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
        }
        // 骨骼只是"骨架"，往外扩一圈把网格包进去
        val e = skinMargin
        min[0] = minX - e; min[1] = minY - e; min[2] = minZ - e
        max[0] = maxX + e; max[1] = maxY + e; max[2] = maxZ + e
    }

    /** 世界空间包围盒。 */
    fun worldBounds(min: FloatArray, max: FloatArray) {
        val lmin = boundsMinTmp; val lmax = boundsMaxTmp
        localBounds(lmin, lmax)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (k in 0 until 8) {
            val x = if (k and 1 == 0) lmin[0] else lmax[0]
            val y = if (k and 2 == 0) lmin[1] else lmax[1]
            val z = if (k and 4 == 0) lmin[2] else lmax[2]
            Mat4.transformPoint(worldMatrix, 0, x, y, z, tmp, 0)
            if (tmp[0] < minX) minX = tmp[0]; if (tmp[1] < minY) minY = tmp[1]; if (tmp[2] < minZ) minZ = tmp[2]
            if (tmp[0] > maxX) maxX = tmp[0]; if (tmp[1] > maxY) maxY = tmp[1]; if (tmp[2] > maxZ) maxZ = tmp[2]
        }
        min[0] = minX; min[1] = minY; min[2] = minZ
        max[0] = maxX; max[1] = maxY; max[2] = maxZ
    }

    /**
     * 当前姿势下网格在世界空间的精确包围盒（逐顶点计算）。
     * 比 [worldBounds] 慢，但 [worldBounds] 对蒙皮模型只是"骨骼位置外扩一圈"的近似值，
     * 像"落地"这种要对齐到地面的一次性操作必须用精确值，否则角色会悬空。
     * @return false = 没有顶点，或者还没有可用的姿势数据
     */
    fun exactWorldBounds(min: FloatArray, max: FloatArray): Boolean {
        val n = data.vertexCount
        if (n == 0) return false
        val p = skinnedPositions()
        val w = worldMatrix
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (v in 0 until n) {
            val x = p[v * 3]; val y = p[v * 3 + 1]; val z = p[v * 3 + 2]
            val wx = w[0] * x + w[4] * y + w[8] * z + w[12]
            val wy = w[1] * x + w[5] * y + w[9] * z + w[13]
            val wz = w[2] * x + w[6] * y + w[10] * z + w[14]
            if (wx < minX) minX = wx; if (wy < minY) minY = wy; if (wz < minZ) minZ = wz
            if (wx > maxX) maxX = wx; if (wy > maxY) maxY = wy; if (wz > maxZ) maxZ = wz
        }
        if (!(maxX - minX).isFinite() || !(maxY - minY).isFinite() || !(maxZ - minZ).isFinite()) return false
        // 蒙皮矩阵还没算过（全零）时所有顶点都落在同一点上：结果不可用
        if (skeleton != null && maxX - minX == 0f && maxY - minY == 0f && maxZ - minZ == 0f) return false
        min[0] = minX; min[1] = minY; min[2] = minZ
        max[0] = maxX; max[1] = maxY; max[2] = maxZ
        return true
    }

    /**
     * 在 CPU 上算出当前姿势下的顶点位置（模型空间），用于点选拾取。
     * 线性混合蒙皮；SDEF 顶点也按线性处理（拾取不需要那么精确）。
     */
    fun skinnedPositions(): FloatArray {
        val src = morphs.positions
        val sk = skeleton ?: return src
        val n = data.vertexCount
        var out = skinned
        if (out == null || out.size != n * 3) { out = FloatArray(n * 3); skinned = out }
        val bi = data.boneIndices!!
        val bw = data.boneWeights!!
        val m = boneTexels
        for (v in 0 until n) {
            val x = src[v * 3]; val y = src[v * 3 + 1]; val z = src[v * 3 + 2]
            var ox = 0f; var oy = 0f; var oz = 0f
            val o4 = v * 4
            for (k in 0 until 4) {
                val w = bw[o4 + k]
                if (w == 0f) continue
                val o = (bi[o4 + k].toInt() and 0xFFFF) * 16
                ox += w * (m[o] * x + m[o + 1] * y + m[o + 2] * z + m[o + 3])
                oy += w * (m[o + 4] * x + m[o + 5] * y + m[o + 6] * z + m[o + 7])
                oz += w * (m[o + 8] * x + m[o + 9] * y + m[o + 10] * z + m[o + 11])
            }
            out[v * 3] = ox; out[v * 3 + 1] = oy; out[v * 3 + 2] = oz
        }
        return out
    }

    /** 骨骼在世界空间的位置。 */
    fun boneWorldPosition(bone: Int, out: FloatArray, oo: Int) {
        val sk = skeleton ?: return
        val o = bone * 16
        Mat4.transformPoint(worldMatrix, 0, sk.global[o + 12], sk.global[o + 13], sk.global[o + 14], out, oo)
    }

    /** 整体旋转（欧拉角，度；顺序同 MMD：Y-X-Z）。 */
    fun setRotationEuler(xDeg: Float, yDeg: Float, zDeg: Float) {
        val k = (Math.PI / 180.0).toFloat()
        Quat.fromEulerYXZ(rotation, 0, xDeg * k, yDeg * k, zDeg * k)
    }

    fun getRotationEuler(out: FloatArray) {
        Quat.toEulerYXZ(rotation, 0, out, 0)
        val k = (180.0 / Math.PI).toFloat()
        out[0] *= k; out[1] *= k; out[2] *= k
    }

    companion object {
        const val KIND_CHARACTER = 0
        const val COLLISION_NONE = 0
        const val COLLISION_BOX = 1
        const val COLLISION_MESH = 2
        val COLLISION_NAMES = arrayOf("none", "box", "mesh")
        const val KIND_STAGE = 1
        const val KIND_ACCESSORY = 2
        private val nextId = AtomicInteger(1)
    }
}

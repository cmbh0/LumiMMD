package com.lumi.mmd.engine.scene

import com.lumi.mmd.engine.anim.Motion
import com.lumi.mmd.engine.anim.MotionClip

/**
 * 场景：模型列表 + 相机 + 灯光 + 播放时间轴。
 * 只在渲染线程上读写（UI 线程通过事件队列投递操作）。
 */
class Scene {
    val models = ArrayList<ModelInstance>()
    /** 默认的地板（可以删、可以改、可以加回来）。 */
    val floor = Floor()
    /** 带「碰撞」属性的模型这一帧的碰撞体（世界坐标）。每帧更新模型之前重新收集。 */
    val colliders = ArrayList<com.lumi.mmd.engine.fx.Collider>()
    private val colliderPool = ArrayList<com.lumi.mmd.engine.fx.Collider>()

    /** 重新收集 [colliders]。 */
    fun refreshColliders() {
        var n = 0
        for (m in models) {
            if (m.collision == ModelInstance.COLLISION_NONE || !m.visible) continue
            if (m.attachParent == null) m.updateWorldMatrix()
            val w = m.worldMatrix
            val s = kotlin.math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
            if (s < 1e-6f) continue
            if (n == colliderPool.size) colliderPool.add(com.lumi.mmd.engine.fx.Collider())
            val c = colliderPool[n++]
            for (col in 0 until 3) for (row in 0 until 3) c.r[col * 3 + row] = w[col * 4 + row] / s
            c.id = m.id; c.owner = -1; c.friction = 0.6f
            c.vx = 0f; c.vy = 0f; c.vz = 0f
            val mesh = if (m.collision == ModelInstance.COLLISION_MESH) m.collisionMesh() else null
            if (mesh != null) {
                c.shape = com.lumi.mmd.engine.fx.Collider.MESH
                c.mesh = mesh; c.scale = s
                c.px = w[12]; c.py = w[13]; c.pz = w[14]
            } else {
                // 包围盒：绑定姿势下顶点的范围，跟着模型转
                val b = m.bindBounds()
                val cx = (b[0] + b[3]) * 0.5f; val cy = (b[1] + b[4]) * 0.5f; val cz = (b[2] + b[5]) * 0.5f
                c.shape = com.lumi.mmd.engine.fx.Collider.BOX
                c.mesh = null; c.scale = 1f
                c.px = w[0] * cx + w[4] * cy + w[8] * cz + w[12]
                c.py = w[1] * cx + w[5] * cy + w[9] * cz + w[13]
                c.pz = w[2] * cx + w[6] * cy + w[10] * cz + w[14]
                c.hx = maxOf((b[3] - b[0]) * 0.5f * s, 0.01f); c.hy = maxOf((b[4] - b[1]) * 0.5f * s, 0.01f); c.hz = maxOf((b[5] - b[2]) * 0.5f * s, 0.01f)
            }
            c.updateBound()
        }
        colliders.clear()
        for (i in 0 until n) colliders.add(colliderPool[i])
    }

    /**
     * (x, z) 这个位置脚下的地面有多高：地板和带碰撞的模型里最高的那个（只看 fromY 以下的）。
     * 什么都没有返回 NaN。
     */
    fun groundHeight(x: Float, fromY: Float, z: Float, skipModel: Int = 0): Float {
        var best = if (floor.solid && floor.height <= fromY) floor.height else Float.NaN
        for (c in colliders) {
            if (c.id == skipModel) continue
            val t = c.raycast(x, fromY, z, 0f, -1f, 0f, 1e5f, groundTmp)
            if (t >= 0f) { val y = fromY - t; if (best.isNaN() || y > best) best = y }
        }
        return best
    }
    private val groundTmp = FloatArray(6)

    val camera = Camera()
    val light = Light()

    /** 镜头轨道上的片段（VMD 的 カメラ・照明 数据），按开始位置排好。 */
    val cameraClips = ArrayList<MotionClip>()
    /** 当前帧所在的镜头片段。 */
    var activeCameraClip: MotionClip? = null
        private set

    /** 当前镜头片段的动作。赋值 = 换掉当前片段的动作（没有片段就新建一段）；赋 null = 去掉当前片段。 */
    var cameraMotion: Motion?
        get() = activeCameraClip?.motion
        set(v) {
            val c = activeCameraClip
            if (v == null) {
                if (c != null) cameraClips.remove(c)
                activeCameraClip = cameraClips.firstOrNull()
            } else if (c != null) {
                if (c.motion !== v) { c.motion = v; c.trimIn = 0; c.trimOut = -1 }
            } else {
                val n = MotionClip(v, v.name.ifBlank { "镜头" })
                cameraClips.add(n)
                activeCameraClip = n
            }
        }
    /** 时间轴帧号 − cameraOffset = 当前镜头片段的动作里的帧号。 */
    val cameraOffset: Int get() = activeCameraClip?.offset ?: 0
    var useCameraMotion = true

    fun selectCameraClip(frame: Float): MotionClip? {
        var best: MotionClip? = null
        for (c in cameraClips) if (c.start <= frame && (best == null || c.start >= best.start)) best = c
        if (best == null) for (c in cameraClips) if (best == null || c.start < best.start) best = c
        activeCameraClip = best
        return best
    }

    fun focusCameraClip(c: MotionClip?) { activeCameraClip = if (c != null && cameraClips.contains(c)) c else null }

    fun sortCameraClips() {
        cameraClips.sortBy { it.start }
        if (activeCameraClip != null && !cameraClips.contains(activeCameraClip)) activeCameraClip = null
    }

    /** 画面时间和音乐对齐用的时钟。 */
    val sync = SyncClock()
    /** 上一次 [update]：时间轴循环回到了开头。 */
    var looped = false
        private set
    /** 上一次 [update]：画面为了对上音乐，直接跳了一步。 */
    var resynced = false
        private set

    /** 当前帧（30fps，可为小数）。 */
    var frame = 0f
        private set
    var playing = false
    var loop = true
    var speed = 1f
    var physicsEnabled = true
    /** 物理的全局参数（重力、风、阻尼……）。每个模型还可以有自己的一份倍数（[ModelInstance.physicsTuning]）。 */
    val physics = com.lumi.mmd.engine.physics.PhysicsTuning()
    private val tuningScratch = com.lumi.mmd.engine.physics.PhysicsTuning()
    /** 时间轴总长度（帧）。 */
    var maxFrame = 0
        private set

    /** 时间轴的最短长度（帧），例如背景音乐的长度。 */
    var extraDuration = 0

    private var jumped = true
    private val camValues = FloatArray(8)
    private val boundsA = FloatArray(3)
    private val boundsB = FloatArray(3)
    private val lightValues = FloatArray(6)
    /** 手动转视角时保存的相机，镜头动作停用后恢复。 */
    val userCamera = Camera()
    private var cameraFromMotion = false

    val durationSeconds: Float get() = maxFrame / Motion.FPS
    val timeSeconds: Float get() = frame / Motion.FPS

    fun add(model: ModelInstance) {
        models.add(model)
        recomputeDuration()
    }

    fun remove(model: ModelInstance) {
        models.remove(model)
        for (m in models) if (m.attachParent === model) { m.attachParent = null; m.attachBone = -1 }
        recomputeDuration()
    }

    /** 原地把一个模型换成它的替身（建模时网格重建之后），挂在它身上的配件跟着换过去。 */
    fun replace(old: ModelInstance, neu: ModelInstance) {
        val i = models.indexOf(old)
        if (i < 0) { models.add(neu) } else models[i] = neu
        for (m in models) if (m.attachParent === old) m.attachParent = neu
        recomputeDuration()
    }

    fun findById(id: Int): ModelInstance? = models.firstOrNull { it.id == id }

    fun recomputeDuration() {
        var m = 0
        for (model in models) for (c in model.clips) m = maxOf(m, c.end)
        for (c in cameraClips) m = maxOf(m, c.end)
        for (c in audioClips) m = maxOf(m, c.end)
        maxFrame = maxOf(m, extraDuration)
    }

    /** 音乐轨道：按开始位置排好、互不重叠的若干段（同一个文件可以放好几段）。播放由宿主负责，这里只记摆放。 */
    val audioClips = ArrayList<AudioClip>()

    /** 时间轴的 frame 处正在响的那一段音乐（没有则为 null）。 */
    fun audioClipAt(frame: Float): AudioClip? {
        for (c in audioClips) if (c.contains(frame)) return c
        return null
    }

    /**
     * 跳到某一帧。
     * @param beyondEnd 允许跳到最后一个关键帧之后（做动画时要在后面的空白处打新的关键帧）
     */
    fun seek(f: Float, beyondEnd: Boolean = false) {
        frame = if (beyondEnd) f.coerceIn(0f, MAX_TIMELINE) else f.coerceIn(0f, maxOf(maxFrame.toFloat(), 0f))
        jumped = true
        cameraHeld = false
    }

    /**
     * 暂停时手动转了镜头（准备打镜头关键帧）：在离开这一帧或开始播放之前，镜头动作先不覆盖它。
     * 相当于 MMD 里"没登记的镜头改动，换帧就丢"。
     */
    fun holdCamera() { cameraHeld = true; cameraHeldFrame = frame }
    private var cameraHeld = false
    private var cameraHeldFrame = 0f
    val isCameraHeld: Boolean get() = cameraHeld && !playing && frame == cameraHeldFrame

    /** 镜头轨道被修改之后调用：下一帧重新按轨道取值。 */
    fun releaseCamera() { cameraHeld = false }

    fun seekSeconds(s: Float) = seek(s * Motion.FPS)

    /**
     * 推进时间并更新所有模型。
     *
     * 画面的时间永远按真实经过的时间平滑地走；有音乐时只是慢慢向音乐的位置靠拢（见 [SyncClock]），
     * 物理拿到的时间步和动作走过的时间是同一个数，所以头发和裙子不会被"时间跳了一下"甩飞。
     *
     * @param dt 真实经过的秒数
     * @param audioSeconds 音乐此刻的位置（秒）；NaN = 没有音乐时钟，画面按自己的时钟走
     * @param hold 音乐已经按了播放但还没真正出声：画面先等它，这一帧时间不往前走
     * @return 是否播放到了结尾（非循环模式）
     */
    fun update(dt: Float, audioSeconds: Float = Float.NaN, hold: Boolean = false): Boolean {
        var ended = false
        looped = false
        resynced = false
        var physDt = dt
        if (playing) {
            if (hold) {
                sync.reset()
            } else {
                val adv = sync.advance(dt * speed, dt, frame / Motion.FPS, audioSeconds)
                if (sync.snapped) {
                    frame = audioSeconds * Motion.FPS
                    jumped = true
                    resynced = true
                } else {
                    frame += adv * Motion.FPS
                    physDt = adv
                }
            }
            if (maxFrame > 0 && frame > maxFrame) {
                if (loop) {
                    frame %= maxFrame.toFloat()
                    jumped = true
                    looped = true
                    sync.reset()
                } else {
                    frame = maxFrame.toFloat()
                    ended = true
                }
            }
        } else {
            sync.reset()
        }
        updateModels(physDt)
        return ended
    }

    /** 以固定时间步推进到指定帧（离线渲染 / 导出用，结果是确定性的）。 */
    fun stepTo(f: Float, dt: Float) {
        frame = f
        updateModels(dt)
    }

    private fun updateModels(dt: Float) {
        val j = jumped
        jumped = false
        refreshColliders()
        for (m in models) {
            val ph = m.physics ?: continue
            tuningScratch.combine(physics, m.physicsTuning)
            ph.applyTuning(tuningScratch)
            ph.setEnvironment(m.worldMatrix, tuningScratch.ground && floor.solid, floor.height, colliders, m.id)
        }
        // 父模型先更新，挂载在骨骼上的配件才能拿到当帧的骨骼矩阵
        for (m in models) if (m.attachParent == null) { m.updateWorldMatrix(); m.update(frame, dt, physicsEnabled, j) }
        for (pass in 0 until 3) {
            for (m in models) {
                val p = m.attachParent ?: continue
                if (depthOf(m) == pass + 1) { m.updateWorldMatrix(); m.update(frame, dt, physicsEnabled, j) }
            }
        }
        applyCameraMotion()
    }

    private fun depthOf(m: ModelInstance): Int {
        var d = 0
        var p = m.attachParent
        while (p != null && d < 8) { d++; p = p.attachParent }
        return d
    }

    private val camFade = FloatArray(8)

    private fun applyCameraMotion() {
        val clip = if (cameraLocked) null else selectCameraClip(frame)
        val cm = clip?.motion
        val track = cm?.camera
        val local = clip?.localFrame(frame) ?: frame
        if (cm != null && useCameraMotion && track != null && track.count > 0) {
            if (!cameraFromMotion) { userCamera.copyFrom(camera); cameraFromMotion = true }
            if (cameraHeld && (playing || frame != cameraHeldFrame)) cameraHeld = false
            if (!cameraHeld) {
                val persp = track.sample(local, camValues)
                // 和前一段镜头之间的过渡
                val from = ClipOps.fadingFrom(cameraClips, clip, frame)
                val ft = from?.motion?.camera
                if (from != null && ft != null && ft.count > 0) {
                    ft.sample(from.fadeOutFrame(frame, clip), camFade)
                    ClipOps.blendCamera(camFade, camValues, ClipOps.fadeWeight(clip, frame), camValues)
                }
                camera.setFromVmd(camValues, persp)
            }
        } else if (cameraFromMotion) {
            camera.copyFrom(userCamera)
            cameraFromMotion = false
        }
        val lt = cm?.light
        if (cm != null && useCameraMotion && lt != null && lt.count > 0) {
            lt.sample(local, lightValues)
            light.color[0] = lightValues[0]; light.color[1] = lightValues[1]; light.color[2] = lightValues[2]
            light.direction[0] = lightValues[3]; light.direction[1] = lightValues[4]; light.direction[2] = lightValues[5]
        }
    }

    /**
     * 操控模式 / 脚本接管了相机：镜头动作暂时不生效。
     *
     * 接管的那一刻如果镜头动作正在驱动相机，就先记下来：接管期间相机完全归接管的一方
     * （不会在下一帧被"恢复成自由视角"盖掉），放手时再原样交还给镜头动作，用户的自由视角也不会被冲掉。
     */
    var cameraLocked = false
        set(v) {
            if (v == field) return
            field = v
            if (v) {
                resumeCameraMotion = cameraFromMotion
                cameraFromMotion = false
            } else if (resumeCameraMotion) {
                resumeCameraMotion = false
                cameraFromMotion = true
            }
        }
    private var resumeCameraMotion = false

    /** 相机当前是否由镜头动作驱动（此时手势不直接改相机）。 */
    val cameraDriven: Boolean get() = cameraFromMotion

    companion object {
        /** 时间轴允许的最大帧号（约 37 分钟）。 */
        const val MAX_TIMELINE = 66000f
    }

    /** 所有角色的世界包围盒（没有角色时退而求其次用全部模型）。返回 false 表示场景为空。 */
    fun characterBounds(min: FloatArray, max: FloatArray): Boolean {
        var any = false
        val a = boundsA; val b = boundsB
        for (pass in 0 until 2) {
            for (m in models) {
                if (!m.visible) continue
                if (pass == 0 && m.kind == ModelInstance.KIND_STAGE) continue
                m.worldBounds(a, b)
                if (!any) {
                    System.arraycopy(a, 0, min, 0, 3); System.arraycopy(b, 0, max, 0, 3); any = true
                } else {
                    for (k in 0 until 3) { if (a[k] < min[k]) min[k] = a[k]; if (b[k] > max[k]) max[k] = b[k] }
                }
            }
            if (any) break
        }
        return any
    }
}

package com.lumi.mmd.engine.control

import com.lumi.mmd.engine.anim.MorphController
import com.lumi.mmd.engine.anim.MotionBinding
import com.lumi.mmd.engine.anim.Skeleton
import com.lumi.mmd.engine.math.Quat
import com.lumi.mmd.engine.scene.Animator
import com.lumi.mmd.engine.scene.Camera
import com.lumi.mmd.engine.scene.ModelInstance
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 操控模式：用摇杆带着一个角色在场景里走、跑、疾跑、蹲、趴、跳。
 *
 * 分两部分：
 *  - 运动：按摇杆方向（相对镜头）和姿态算出速度、朝向、起跳和下落，结果写到骨架的"整体位移"上
 *    （走的是骨骼而不是模型的位置，所以头发和裙子会被带着甩起来）；
 *  - 动画：按速度在 待机 / 走 / 跑 / 疾跑 之间连续混合，蹲、趴、空中各有一套，姿态切换时从上一刻的姿势平滑过渡过去。
 *    循环动作的播放速度按实际移动速度配，脚不打滑。
 */
class CharacterController(val model: ModelInstance) : Animator {
    val rig = Rig(model.data)
    val clips = Locomotion(rig)
    /** 模型有没有标准的腿部骨骼：没有的话只能平移，没有走跑的动画。 */
    val animated: Boolean = rig.usable && model.skeleton != null

    // ---- 输入（每帧由宿主填）----
    /** 摇杆，相对镜头：x 右、y 前，长度不超过 1。 */
    var moveX = 0f
    var moveY = 0f
    /** 按着疾跑。 */
    var sprint = false

    // ---- 状态 ----
    /** 在模型空间里的位置（y 是离地高度）和朝向（弧度；0 = 面朝 -Z）。 */
    var x = 0f
    var y = 0f
    var z = 0f
    var yaw = 0f
    var stance = STAND
        private set
    var grounded = true
        private set
    /** 脚下地面的高度（和 y 同一个坐标）。由场景每帧给：地板，或者脚下带碰撞的模型。 */
    var groundY = 0f
    /** 当前的水平速度（单位 / 秒）。 */
    val speed: Float get() = sqrt(vx * vx + vz * vz)
    /** 当前主要在播的动作的名字（界面 / 调试用）。 */
    var stateName = "待机"
        private set

    private var vx = 0f
    private var vy = 0f
    private var vz = 0f
    private var phase = 0f
    private var idlePhase = 0f
    private var landLeft = 0f
    private var category = CAT_STAND
    private var fade = 1f
    private var fadeDuration = 0.2f
    private var hasOutput = false

    private val u = rig.leg
    private val bindings = HashMap<Locomotion.Clip, MotionBinding>()
    private val n = model.skeleton?.count ?: 0
    private val bufT = FloatArray(n * 3)
    private val bufR = FloatArray(n * 4)
    private val fromT = FloatArray(n * 3)
    private val fromR = FloatArray(n * 4)
    private val outT = FloatArray(n * 3)
    private val outR = FloatArray(n * 4)
    private val q = FloatArray(4)
    private val v = FloatArray(4)
    private val camTarget = FloatArray(3)
    private var camPrimed = false

    private fun binding(c: Locomotion.Clip): MotionBinding = bindings.getOrPut(c) { MotionBinding(c.motion, model.data) }

    // ------------------------------------------------------------------ 速度表（以腿长为单位）

    private val walkSpeed = clips.walk.speed
    private val runSpeed = clips.run.speed
    private val sprintSpeed = clips.sprint.speed
    private val crouchSpeed = clips.crouchWalk.speed
    private val crouchFast = clips.crouchWalk.speed * 1.9f
    private val crawlSpeed = clips.crawl.speed
    private val crawlFast = clips.crawl.speed * 1.5f
    private val gravity = 23f * u
    private val jumpSpeed = sqrt(2f * gravity * 0.9f * u)

    // ------------------------------------------------------------------ 按钮

    /** 按了一下按钮：jump 跳，crouch 蹲 / 起，prone 趴 / 起。 */
    fun press(button: String) {
        when (button) {
            "jump" -> when {
                !grounded -> {}
                stance == PRONE -> setStance(CROUCH)
                else -> { if (stance == CROUCH) setStance(STAND); vy = jumpSpeed; grounded = false; landLeft = 0f }
            }
            "crouch" -> if (grounded) setStance(if (stance == CROUCH) STAND else CROUCH)
            "prone" -> if (grounded) setStance(if (stance == PRONE) STAND else PRONE)
        }
    }

    fun setStance(s: Int) { stance = s.coerceIn(STAND, PRONE) }

    /** 把角色放到某个位置（模型空间）。 */
    fun teleport(nx: Float, nz: Float, nyaw: Float) { x = nx; z = nz; y = groundY; yaw = nyaw; vx = 0f; vy = 0f; vz = 0f; grounded = true }

    // ------------------------------------------------------------------ 运动

    /**
     * 推进运动状态。
     * @param cameraForwardX / Z 镜头看过去的方向在地面上的投影（世界坐标，不必归一化）。屏幕右方按左手系由它推出来。
     */
    fun step(dt: Float, cameraForwardX: Float, cameraForwardZ: Float) {
        val h = dt.coerceIn(0f, 0.05f)
        if (h <= 0f) return
        // 摇杆方向 → 世界方向 → 模型空间方向
        var fx = cameraForwardX; var fz = cameraForwardZ
        val fl = sqrt(fx * fx + fz * fz)
        if (fl < 1e-5f) { fx = 0f; fz = -1f } else { fx /= fl; fz /= fl }
        // 屏幕右方：镜头朝 +Z 看时右边是 +X（和 Camera 里的 right 一致）
        val rx = fz; val rz = -fx
        var mag = sqrt(moveX * moveX + moveY * moveY)
        var dx = 0f; var dz = 0f
        if (mag > 0.08f) {
            val wx = (rx * moveX + fx * moveY) / mag
            val wz = (rz * moveX + fz * moveY) / mag
            // 世界 → 模型空间（只考虑模型绕 Y 的转动）
            Quat.conjugate(q, 0, model.rotation, 0)
            Quat.rotate(q, 0, wx, 0f, wz, v, 0)
            val l = sqrt(v[0] * v[0] + v[2] * v[2])
            if (l > 1e-5f) { dx = v[0] / l; dz = v[2] / l }
            mag = min(mag, 1f)
        } else mag = 0f

        // 目标速度
        val top = when (stance) {
            CROUCH -> if (sprint) crouchFast else if (mag > 0.55f) crouchSpeed * 1.25f else crouchSpeed * 0.8f
            PRONE -> if (sprint) crawlFast else crawlSpeed
            else -> if (sprint && mag > 0.3f) sprintSpeed else if (mag > 0.55f) runSpeed else walkSpeed
        }
        var want = if (mag > 0f) top else 0f

        // 朝向：转向移动方向
        if (mag > 0f) {
            val target = atan2(-dx, -dz)
            var d = target - yaw
            while (d > PI) d -= (2.0 * PI).toFloat()
            while (d < -PI) d += (2.0 * PI).toFloat()
            val rate = (when (stance) { PRONE -> 150f; CROUCH -> 480f; else -> 720f }) * DEG * h
            yaw += d.coerceIn(-rate, rate)
            // 趴着时先转过去再爬；站着时转身的同时就可以走，但背对着目标方向时先慢下来
            val align = cos(d)
            if (stance == PRONE) want *= max(0f, align).let { it * it }
            else if (align < 0f) want *= 0.35f
        }
        if (!grounded) want = speed.coerceAtLeast(want * 0.6f)   // 空中基本保持起跳时的速度

        // 速度向目标靠拢（空中几乎不能变向）
        val tx: Float; val tz: Float
        if (mag > 0f && stance != PRONE) { tx = dx * want; tz = dz * want }
        else { tx = -sin(yaw) * want; tz = -cos(yaw) * want }
        val accel = (if (!grounded) 2.5f else if (want > speed) 12f else 16f)
        val k = 1f - exp(-accel * h)
        vx += (tx - vx) * k; vz += (tz - vz) * k
        x += vx * h; z += vz * h

        // 跳和落
        // 脚下空了（走出了台子的边）：掉下去
        if (grounded && y > groundY + 0.05f) { grounded = false; vy = 0f }
        if (!grounded) {
            vy -= gravity * h
            y += vy * h
            if (y <= groundY && vy < 0f) {
                y = groundY
                // 落得越快，落地时蹲得越久
                landLeft = (0.14f + min(0.2f, -vy / jumpSpeed * 0.12f))
                vy = 0f
                grounded = true
            }
        } else {
            y = groundY
            if (landLeft > 0f) landLeft = max(0f, landLeft - h)
        }
    }

    // ------------------------------------------------------------------ 动画

    private fun clear(sk: Skeleton) {
        sk.beginFrame()
    }

    /** 把 clip 在 phase 处的姿势取到骨架的动作层里。 */
    private fun sample(c: Locomotion.Clip, phase01: Float, sk: Skeleton) {
        binding(c).apply(phase01 * c.frames, sk, null)
    }

    /** 骨架动作层 = (1-w)·buf + w·骨架动作层。 */
    private fun mixFromBuffer(sk: Skeleton, t: FloatArray, r: FloatArray, w: Float) {
        val at = sk.animT; val ar = sk.animR
        val a = 1f - w
        for (i in 0 until n * 3) at[i] = t[i] * a + at[i] * w
        var i = 0
        while (i < n * 4) {
            var bx = ar[i]; var by = ar[i + 1]; var bz = ar[i + 2]; var bw = ar[i + 3]
            if (r[i] * bx + r[i + 1] * by + r[i + 2] * bz + r[i + 3] * bw < 0f) { bx = -bx; by = -by; bz = -bz; bw = -bw }
            var qx = r[i] * a + bx * w; var qy = r[i + 1] * a + by * w; var qz = r[i + 2] * a + bz * w; var qw = r[i + 3] * a + bw * w
            val l = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
            if (l > 1e-9f) { qx /= l; qy /= l; qz /= l; qw /= l } else { qx = 0f; qy = 0f; qz = 0f; qw = 1f }
            ar[i] = qx; ar[i + 1] = qy; ar[i + 2] = qz; ar[i + 3] = qw
            i += 4
        }
    }

    /** 两个动作按权重 w 混合（w = 0 全是 a，1 全是 b），结果在骨架的动作层里。 */
    private fun blend(a: Locomotion.Clip, pa: Float, b: Locomotion.Clip, pb: Float, w: Float, sk: Skeleton) {
        if (w <= 0.001f) { sample(a, pa, sk); return }
        if (w >= 0.999f) { sample(b, pb, sk); return }
        sample(a, pa, sk)
        System.arraycopy(sk.animT, 0, bufT, 0, n * 3); System.arraycopy(sk.animR, 0, bufR, 0, n * 4)
        clear(sk)
        sample(b, pb, sk)
        mixFromBuffer(sk, bufT, bufR, w)
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    override fun apply(model: ModelInstance, skeleton: Skeleton, morphs: MorphController, dt: Float) {
        val h = dt.coerceIn(0f, 0.1f)
        if (animated) pose(skeleton, h)
        // 整体位移和朝向
        Quat.fromEulerYXZ(q, 0, 0f, yaw, 0f)
        skeleton.setRoot(x, y, z, q[0], q[1], q[2], q[3])
    }

    private fun pose(sk: Skeleton, h: Float) {
        val s = speed
        // 这一刻属于哪一类动作
        val cat = when {
            !grounded -> CAT_AIR
            landLeft > 0f -> CAT_LAND
            stance == CROUCH -> CAT_CROUCH
            stance == PRONE -> CAT_PRONE
            else -> CAT_STAND
        }
        if (cat != category) {
            // 换了一类：从上一刻的姿势过渡过去
            val dur = when {
                cat == CAT_AIR -> 0.12f
                cat == CAT_LAND -> 0.08f
                category == CAT_LAND -> 0.22f
                cat == CAT_PRONE || category == CAT_PRONE -> 0.55f
                else -> 0.26f
            }
            if (hasOutput) {
                System.arraycopy(outT, 0, fromT, 0, n * 3); System.arraycopy(outR, 0, fromR, 0, n * 4)
                fade = 0f; fadeDuration = dur
            }
            category = cat
        }
        idlePhase = (idlePhase + h / 4f) % 1f

        when (cat) {
            CAT_AIR -> {
                val w = smooth(jumpSpeed * 0.35f, -jumpSpeed * 0.35f, vy)
                blend(clips.jumpRise, 0f, clips.jumpFall, 0f, w, sk)
                stateName = if (w < 0.5f) "起跳" else "下落"
            }
            CAT_LAND -> { sample(clips.land, 0f, sk); stateName = "落地" }
            CAT_CROUCH -> {
                val w = smooth(0f, crouchSpeed * 0.6f, s)
                if (w > 0f) phase = (phase + s * h / clips.crouchWalk.stride) % 1f
                blend(clips.crouchIdle, idlePhase, clips.crouchWalk, phase, w, sk)
                stateName = if (w < 0.5f) "蹲" else "蹲着走"
            }
            CAT_PRONE -> {
                val w = smooth(0f, crawlSpeed * 0.5f, s)
                if (w > 0f) phase = (phase + s * h / clips.crawl.stride) % 1f
                blend(clips.proneIdle, idlePhase, clips.crawl, phase, w, sk)
                stateName = if (w < 0.5f) "趴" else "匍匐"
            }
            else -> {
                // 待机 → 走 → 跑 → 疾跑：按速度连续混合，步幅也跟着混合
                val a: Locomotion.Clip; val b: Locomotion.Clip; val w: Float
                when {
                    s < walkSpeed -> { a = clips.idle; b = clips.walk; w = smooth(0f, walkSpeed * 0.7f, s) }
                    s < runSpeed -> { a = clips.walk; b = clips.run; w = (s - walkSpeed) / (runSpeed - walkSpeed) }
                    else -> { a = clips.run; b = clips.sprint; w = ((s - runSpeed) / (sprintSpeed - runSpeed)).coerceIn(0f, 1f) }
                }
                val stride = if (a === clips.idle) b.stride else a.stride + (b.stride - a.stride) * w
                if (s > 0.01f * u) phase = (phase + s * h / stride) % 1f
                blend(a, if (a === clips.idle) idlePhase else phase, b, phase, w, sk)
                stateName = (if (w < 0.5f) a else b).title
            }
        }
        if (fade < 1f) {
            fade = min(1f, fade + h / fadeDuration)
            val k = fade * fade * (3f - 2f * fade)
            if (k < 1f) mixFromBuffer(sk, fromT, fromR, k)
        }
        System.arraycopy(sk.animT, 0, outT, 0, n * 3); System.arraycopy(sk.animR, 0, outR, 0, n * 4)
        hasOutput = true
    }

    // ------------------------------------------------------------------ 镜头

    /** 角色在世界里的位置（脚下）。 */
    fun worldPosition(out: FloatArray) {
        com.lumi.mmd.engine.math.Mat4.transformPoint(model.worldMatrix, 0, x, y, z, out, 0)
    }

    /** 让镜头的注视点跟着角色（转视角、拉远近仍然由手势决定）。 */
    fun followCamera(camera: Camera, dt: Float) {
        val lift = when {
            stance == PRONE && grounded -> 0.22f
            stance == CROUCH && grounded -> 0.48f
            else -> 0.66f
        } * rig.height
        com.lumi.mmd.engine.math.Mat4.transformPoint(model.worldMatrix, 0, x, y + lift, z, v, 0)
        if (!camPrimed) { camTarget[0] = v[0]; camTarget[1] = v[1]; camTarget[2] = v[2]; camPrimed = true }
        val k = 1f - exp(-16f * dt.coerceIn(0f, 0.1f))
        val ky = 1f - exp(-6f * dt.coerceIn(0f, 0.1f))
        camTarget[0] += (v[0] - camTarget[0]) * k
        camTarget[1] += (v[1] - camTarget[1]) * ky
        camTarget[2] += (v[2] - camTarget[2]) * k
        camera.target[0] = camTarget[0]; camera.target[1] = camTarget[1]; camera.target[2] = camTarget[2]
    }

    /** 把镜头摆到角色身后（刚进入操控模式时）。 */
    fun placeCameraBehind(camera: Camera) {
        camera.rotY = (PI - yaw).toFloat()
        camera.rotX = -12f * DEG
        camera.rotZ = 0f
        camera.distance = rig.height * 2.3f * model.scale
        camera.perspective = true
        if (camera.fovDeg < 20f || camera.fovDeg > 70f) camera.fovDeg = 40f
        camPrimed = false
        followCamera(camera, 1f)
    }

    /** 开始接管这个模型的动作。 */
    fun attach() {
        // 从模型现在站的地方开始
        teleport(0f, 0f, 0f)
        model.animator = this
        model.poseDirty = true
    }

    fun detach() {
        if (model.animator === this) model.animator = null
        model.skeleton?.clearRoot()
        model.poseDirty = true
        model.physics?.requestReset()
    }

    companion object {
        const val STAND = 0
        const val CROUCH = 1
        const val PRONE = 2
        private const val CAT_STAND = 0
        private const val CAT_CROUCH = 1
        private const val CAT_PRONE = 2
        private const val CAT_AIR = 3
        private const val CAT_LAND = 4
        private const val DEG = (PI / 180.0).toFloat()

        @Suppress("unused")
        private fun wrap(a: Float): Float { var d = a; while (d > PI) d -= (2.0 * PI).toFloat(); while (d < -PI) d += (2.0 * PI).toFloat(); return abs(d) }
    }
}

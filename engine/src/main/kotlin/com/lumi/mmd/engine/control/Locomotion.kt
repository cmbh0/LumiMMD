package com.lumi.mmd.engine.control

import com.lumi.mmd.engine.anim.Motion
import com.lumi.mmd.engine.math.Quat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 操控模式用的一整套动作：待机、走、跑、疾跑、蹲、蹲着走、趴、匍匐、起跳、下落、落地。
 *
 * 每一个都是按这个模型的身材（[Rig]）一帧一帧算出姿势、再登记成关键帧的普通 [Motion]：
 * 腿用足ＩＫ 带着走（脚的落点、抬脚的高度、脚掌的俯仰），重心的起伏、骨盆和肩的反向扭转、摆臂都是关键帧。
 * 循环动作是"原地踏步"的：角色往前走多少由控制器决定，[Clip.stride] 记着动作循环一次脚在地上蹬过的距离，
 * 播放速度按实际移动速度去配，脚就不会在地上打滑。
 * 因为是普通的动作数据，也可以当成片段放到时间轴上，或者导出成 VMD。
 */
class Locomotion(val rig: Rig) {
    class Clip(
        val id: String,
        /** 给人看的名字。 */
        val title: String,
        val motion: Motion,
        /** 循环一次多少帧（动作的最后一帧和第 0 帧姿势相同）。 */
        val frames: Int,
        /** 循环一次蹬过的距离（模型单位）；0 = 原地的姿势。 */
        val stride: Float,
        /** 这个动作本来的移动速度（单位 / 秒）。 */
        val speed: Float,
    )

    private val u = rig.leg
    private val q = FloatArray(4)
    private val foot = FloatArray(3)

    val idle: Clip
    val walk: Clip
    val run: Clip
    val sprint: Clip
    val crouchIdle: Clip
    val crouchWalk: Clip
    val proneIdle: Clip
    val crawl: Clip
    val jumpRise: Clip
    val jumpFall: Clip
    val land: Clip
    val all: List<Clip>

    // ------------------------------------------------------------------ 登记关键帧的小工具

    private inner class Frame(val motion: Motion, val frame: Int) {
        private val values = LinkedHashMap<String, FloatArray>()
        private fun slot(name: String): FloatArray = values.getOrPut(name) { floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f) }

        /** 旋转（度，顺序同 MMD：先 Z 再 X 最后 Y）。 */
        fun rot(name: String, rx: Float, ry: Float, rz: Float) {
            if (name.isEmpty()) return
            Quat.fromEulerYXZ(q, 0, rx * DEG, ry * DEG, rz * DEG)
            val s = slot(name)
            s[3] = q[0]; s[4] = q[1]; s[5] = q[2]; s[6] = q[3]
        }

        fun pos(name: String, x: Float, y: Float, z: Float) {
            if (name.isEmpty()) return
            val s = slot(name)
            s[0] = x; s[1] = y; s[2] = z
        }

        fun flush() {
            for ((name, s) in values) motion.boneTrack(name).set(frame, s[0], s[1], s[2], s[3], s[4], s[5], s[6])
        }
    }

    /** 做一个循环动作：每 step 帧登记一次，pose(相位 0..1, 这一帧) 负责摆姿势。 */
    private fun cycle(id: String, title: String, frames: Int, stride: Float, speed: Float, step: Int = 1, pose: (Float, Frame) -> Unit): Clip {
        val m = Motion()
        m.name = title
        m.modelName = rig.model.name
        var f = 0
        while (f <= frames) {
            val fr = Frame(m, f)
            pose((f % frames).toFloat() / frames, fr)
            fr.flush()
            f += step
        }
        m.recomputeMaxFrame()
        return Clip(id, title, m, frames, stride, speed)
    }

    /** 只有一个姿势的"动作"（起跳、下落、落地）。 */
    private fun still(id: String, title: String, pose: (Frame) -> Unit): Clip {
        val m = Motion()
        m.name = title
        m.modelName = rig.model.name
        for (f in intArrayOf(0, 10)) { val fr = Frame(m, f); pose(fr); fr.flush() }
        m.recomputeMaxFrame()
        return Clip(id, title, m, 10, 0f, 0f)
    }

    // ------------------------------------------------------------------ 身体各部分

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * 一只脚在一个循环里的轨迹。
     * @param phi 这只脚自己的相位：0 = 落地的瞬间
     * @param duty 着地的时间占一个循环的比例
     * @param front 落地时脚在髋前面多远；back 离地时在髋后面多远（负数）
     * @param lift 摆动时抬多高；heel 蹬地时脚跟抬起使踝关节升高多少
     * @param toeOff 离地时脚尖朝下的角度；strike 落地前脚尖翘起的角度
     * 结果写进 foot：[0] 前后（前为正），[1] 高度，[2] 脚掌俯仰（正 = 脚尖朝上）
     */
    private fun footPath(phi: Float, duty: Float, front: Float, back: Float, lift: Float, heel: Float, toeOff: Float, strike: Float) {
        val p = ((phi % 1f) + 1f) % 1f
        if (p < duty) {
            val k = p / duty
            foot[0] = front + (back - front) * k
            // 落地后脚掌很快放平；快离地时脚跟抬起来
            val push = smooth(0.62f, 1f, k)
            foot[1] = heel * push
            foot[2] = strike * (1f - smooth(0f, 0.14f, k)) - toeOff * push
        } else {
            val s = (p - duty) / (1f - duty)
            // 三次 Hermite：两端的速度等于着地时脚相对身体往后的速度，这样离地和落地的瞬间脚相对地面是静止的
            val m = (back - front) * (1f - duty) / duty
            val s2 = s * s; val s3 = s2 * s
            foot[0] = (2f * s3 - 3f * s2 + 1f) * back + (s3 - 2f * s2 + s) * m + (-2f * s3 + 3f * s2) * front + (s3 - s2) * m
            foot[1] = heel * (1f - s) * (1f - s) + lift * sin(PI.toFloat() * s.pow(0.8f))
            foot[2] = -toeOff + (strike + toeOff) * smooth(0.12f, 0.82f, s)
        }
    }

    /**
     * 把一只脚的 IK 目标登记下来。x 是往外（离开身体中线）的距离，f 是往前的距离，y 是抬起的高度。
     * 目标离髋太远、腿伸直了也够不着时，把目标沿着"髋 → 目标"的方向拉回来一点，免得膝盖绷直抖动。
     */
    private fun placeFoot(fr: Frame, left: Boolean, out: Float, f: Float, y: Float, pitch: Float, centerY: Float, centerZ: Float, yaw: Float = 0f) {
        val name = if (left) rig.nIkL else rig.nIkR
        if (name.isEmpty()) return
        var x = out; var fy = y; var ff = f
        // 髋关节大概的位置（忽略骨盆的转动），相对踝关节原本的位置
        val hy = rig.hipY + centerY - rig.ankleY
        val hz = centerZ
        val dx = x; val dy = fy - hy; val dz = -ff - hz
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val reach = u * 0.985f
        if (d > reach) {
            val k = reach / d
            x = dx * k; fy = hy + dy * k; ff = -(hz + dz * k)
        }
        val sx = if (left) 1f else -1f
        fr.pos(name, x * sx, fy, -ff)
        fr.rot(name, pitch, yaw * sx, 0f)
    }

    /**
     * 一条手臂。
     * @param drop 上臂比水平低多少度（90 = 垂直向下）
     * @param swing 前后摆（正 = 往前）
     * @param elbow 肘弯多少度
     * @param twist 上臂绕竖直方向往里收的角度
     */
    private fun arm(fr: Frame, left: Boolean, drop: Float, swing: Float, elbow: Float, twist: Float = 0f, elbowIn: Float = 0f) {
        val sx = if (left) 1f else -1f
        val lower = drop - rig.armDrop
        fr.rot(if (left) rig.nArmL else rig.nArmR, swing, twist * sx, -lower * sx)
        fr.rot(if (left) rig.nElbowL else rig.nElbowR, elbowIn, elbow * sx, 0f)
    }

    private fun torso(fr: Frame, lean: Float, twist: Float, roll: Float, head: Float, headYaw: Float = 0f) {
        // 往前倾是负的 X
        fr.rot(rig.nUpper, -lean * 0.65f, twist, roll)
        fr.rot(rig.nUpper2, -lean * 0.35f, twist * 0.4f, 0f)
        fr.rot(rig.nNeck, head * 0.5f, headYaw * 0.4f - twist * 0.3f, 0f)
        fr.rot(rig.nHead, head * 0.5f, headYaw * 0.6f - twist * 0.3f, -roll * 0.5f)
    }

    // ------------------------------------------------------------------ 各个动作

    /** 走 / 跑 / 疾跑共用的骨架，参数不同。 */
    private fun gait(
        id: String, title: String, frames: Int, duty: Float, front: Float, back: Float, lift: Float, heel: Float, toeOff: Float, strike: Float,
        drop: Float, bob: Float, bobPhase: Float, lean: Float, hipYaw: Float, hipRoll: Float, sway: Float,
        armSwing: Float, armDrop: Float, elbowBase: Float, elbowSwing: Float, narrow: Float, head: Float, speed: Float,
    ): Clip {
        val stride = (front - back) / duty
        val tau = (2.0 * PI).toFloat()
        return cycle(id, title, frames, stride * u, speed * u) { phi, fr ->
            // 右脚在相位 0 落地，左脚在 0.5
            val c = cos(tau * phi); val s = sin(tau * phi)
            val cy = (-drop + bob * cos(2f * tau * (phi - bobPhase))) * u
            fr.pos(rig.nCenter, -sway * u * s, cy, 0f)
            // 骨盆：哪条腿在前，那一侧的髋就往前送；支撑腿对侧的髋略微下沉
            fr.rot(rig.nLower, 0f, -hipYaw * c, -hipRoll * s)
            torso(fr, lean, hipYaw * 0.6f * c, hipRoll * 0.5f * s, head)
            footPath(phi, duty, front * u, back * u, lift * u, heel * u, toeOff, strike)
            placeFoot(fr, false, -narrow * u, foot[0], foot[1], foot[2], cy, 0f)
            footPath(phi + 0.5f, duty, front * u, back * u, lift * u, heel * u, toeOff, strike)
            placeFoot(fr, true, -narrow * u, foot[0], foot[1], foot[2], cy, 0f)
            // 摆臂：和同侧的腿反着来（右脚在前时右手在后）
            val la = armSwing * c; val ra = -armSwing * c
            arm(fr, true, armDrop, la, elbowBase + elbowSwing * max(0f, c), twist = 6f)
            arm(fr, false, armDrop, ra, elbowBase + elbowSwing * max(0f, -c), twist = 6f)
        }
    }

    init {
        val tau = (2.0 * PI).toFloat()

        idle = cycle("idle", "待机", 120, 0f, 0f, step = 6) { phi, fr ->
            val s = sin(tau * phi); val c = cos(tau * phi)
            // 呼吸：重心微微起伏，重量在两脚之间慢慢换
            val cy = (-0.012f + 0.006f * c) * u
            fr.pos(rig.nCenter, 0.012f * u * s, cy, 0f)
            fr.rot(rig.nLower, 0f, 0f, 1.2f * s)
            torso(fr, 1.5f + 1.2f * c, 1.5f * s, -0.8f * s, 2f + 1f * c, 2.5f * s)
            placeFoot(fr, true, 0.02f * u, 0f, 0f, 0f, cy, 0f, yaw = 6f)
            placeFoot(fr, false, 0.02f * u, 0f, 0f, 0f, cy, 0f, yaw = 6f)
            arm(fr, true, 76f, 2f + 1.5f * c, 14f + 2f * c, twist = 8f)
            arm(fr, false, 76f, 2f + 1.5f * c, 14f + 2f * c, twist = 8f)
        }

        walk = gait("walk", "走", 28, duty = 0.60f, front = 0.36f, back = -0.40f, lift = 0.10f, heel = 0.09f, toeOff = 28f, strike = 14f,
            drop = 0.075f, bob = -0.018f, bobPhase = 0f, lean = 4f, hipYaw = 7f, hipRoll = 2.5f, sway = 0.025f,
            armSwing = 22f, armDrop = 78f, elbowBase = 14f, elbowSwing = 16f, narrow = 0.035f, head = 3f, speed = 1.55f)

        run = gait("run", "跑", 18, duty = 0.38f, front = 0.34f, back = -0.58f, lift = 0.34f, heel = 0.17f, toeOff = 46f, strike = 8f,
            drop = 0.105f, bob = 0.04f, bobPhase = 0.44f, lean = 13f, hipYaw = 11f, hipRoll = 3f, sway = 0.015f,
            armSwing = 40f, armDrop = 80f, elbowBase = 78f, elbowSwing = 14f, narrow = 0.05f, head = 9f, speed = 4.9f)

        sprint = gait("sprint", "疾跑", 14, duty = 0.30f, front = 0.34f, back = -0.64f, lift = 0.44f, heel = 0.20f, toeOff = 55f, strike = 4f,
            drop = 0.13f, bob = 0.045f, bobPhase = 0.40f, lean = 21f, hipYaw = 14f, hipRoll = 3f, sway = 0.01f,
            armSwing = 58f, armDrop = 82f, elbowBase = 92f, elbowSwing = 10f, narrow = 0.055f, head = 15f, speed = 7.2f)

        // ---- 蹲 ----
        val crouchDrop = 0.44f
        fun crouchBody(fr: Frame, cy: Float, breathe: Float, twist: Float) {
            fr.rot(rig.nLower, 6f, 0f, 0f)
            torso(fr, 26f + breathe, twist, 0f, 20f)
        }
        crouchIdle = cycle("crouch", "蹲", 96, 0f, 0f, step = 6) { phi, fr ->
            val c = cos(tau * phi)
            val cy = (-crouchDrop + 0.006f * c) * u
            fr.pos(rig.nCenter, 0f, cy, 0.04f * u)
            crouchBody(fr, cy, 1.2f * c, 0f)
            // 一脚在前踩实，一脚在后、脚跟抬起
            placeFoot(fr, true, 0.05f * u, 0.20f * u, 0f, 0f, cy, 0.04f * u, yaw = 10f)
            placeFoot(fr, false, 0.07f * u, -0.24f * u, 0.11f * u, -38f, cy, 0.04f * u, yaw = 14f)
            arm(fr, true, 70f, 30f + c, 72f, twist = 18f)
            arm(fr, false, 70f, 24f + c, 78f, twist = 18f)
        }
        crouchWalk = cycle("crouch_walk", "蹲着走", 24, (0.30f + 0.32f) / 0.62f * u, 1.5f * u) { phi, fr ->
            val c = cos(tau * phi); val s = sin(tau * phi)
            val cy = (-crouchDrop + 0.03f - 0.014f * cos(2f * tau * phi)) * u
            fr.pos(rig.nCenter, -0.02f * u * s, cy, 0.02f * u)
            fr.rot(rig.nLower, 6f, -6f * c, -2f * s)
            torso(fr, 27f, 4f * c, 1f * s, 20f)
            footPath(phi, 0.62f, 0.30f * u, -0.32f * u, 0.10f * u, 0.07f * u, 26f, 8f)
            placeFoot(fr, false, 0.03f * u, foot[0], foot[1], foot[2], cy, 0.02f * u, yaw = 8f)
            footPath(phi + 0.5f, 0.62f, 0.30f * u, -0.32f * u, 0.10f * u, 0.07f * u, 26f, 8f)
            placeFoot(fr, true, 0.03f * u, foot[0], foot[1], foot[2], cy, 0.02f * u, yaw = 8f)
            arm(fr, true, 70f, 28f + 9f * c, 74f, twist = 18f)
            arm(fr, false, 70f, 28f - 9f * c, 74f, twist = 18f)
        }

        // ---- 趴 ----
        // 身体绕 センター 往前倒到接近水平，再把它放低到贴地；腿的 IK 目标挪到身后
        val pitch = -84f
        val bodyY = 0.15f * u                       // 趴下后髋离地的高度
        val a = rig.hipY - rig.centerY              // 髋在 センター 上方多少
        val cp = cos(pitch * DEG); val sp = sin(pitch * DEG)
        val hipFwd = -a * sp                        // 倒下后髋往前移了多少（前 = -Z）
        val proneY = bodyY - a * cp - rig.centerY   // センター 要下降多少
        fun proneLegs(fr: Frame, cy: Float, lf: Float, rf: Float, lo: Float, ro: Float, ly: Float, ry: Float) {
            // 脚的目标：髋的正后方差不多一条腿的距离，贴地，脚尖朝后下方
            val backZ = hipFwd - 0.955f * u
            val name = arrayOf(rig.nIkR, rig.nIkL)
            for (i in 0 until 2) {
                val left = i == 1
                val sx = if (left) 1f else -1f
                val f = if (left) lf else rf; val o = if (left) lo else ro; val y = if (left) ly else ry
                fr.pos(name[i], (0.03f * u + o) * sx, 0.07f * u + y - rig.ankleY, -(backZ + f))
                fr.rot(name[i], -158f, 8f * sx, 0f)
            }
        }
        fun proneBody(fr: Frame, roll: Float, yaw: Float, look: Float) {
            fr.rot(rig.nLower, -4f, 0f, 0f)
            // 上身略微抬起，头抬起来看前面
            fr.rot(rig.nUpper, 12f, 0f, 0f)
            fr.rot(rig.nUpper2, 12f, 0f, 0f)
            fr.rot(rig.nNeck, 24f, look * 0.5f, 0f)
            fr.rot(rig.nHead, 26f, look * 0.5f, 0f)
        }
        proneIdle = cycle("prone", "趴", 96, 0f, 0f, step = 6) { phi, fr ->
            val c = cos(tau * phi)
            fr.pos(rig.nCenter, 0f, proneY + 0.004f * u * c, 0f)
            fr.rot(rig.nCenter, pitch, 0f, 0f)
            proneBody(fr, 0f, 0f, 3f * sin(tau * phi))
            proneLegs(fr, proneY, 0f, 0f, 0.05f * u, 0.05f * u, 0f, 0f)
            // 两肘撑地：上臂朝前下方，前臂贴地朝前
            arm(fr, true, 62f, 118f, 96f, twist = -6f)
            arm(fr, false, 62f, 118f, 96f, twist = -6f)
        }
        crawl = cycle("crawl", "匍匐", 40, 0.85f * u, 0.8f * u, step = 2) { phi, fr ->
            val c = cos(tau * phi); val s = sin(tau * phi)
            fr.pos(rig.nCenter, 0.02f * u * s, proneY + 0.012f * u * (1f - cos(2f * tau * phi)) * 0.5f, 0f)
            // 身体左右滚一点：哪边的手往前伸，那边的肩就往前送
            fr.rot(rig.nCenter, pitch, 5f * c, 7f * c)
            proneBody(fr, 0f, 0f, -4f * c)
            // 右手往前伸的时候左膝往前提（对侧手脚一起动）
            val rk = max(0f, -c); val lk = max(0f, c)
            proneLegs(fr, proneY, 0.30f * u * lk, 0.30f * u * rk, 0.05f * u + 0.16f * u * lk, 0.05f * u + 0.16f * u * rk, 0.05f * u * lk, 0.05f * u * rk)
            arm(fr, true, 62f, 118f - 22f * c, 96f + 24f * c, twist = -6f)
            arm(fr, false, 62f, 118f + 22f * c, 96f - 24f * c, twist = -6f)
        }

        // ---- 跳 ----
        jumpRise = still("jump_rise", "起跳") { fr ->
            val cy = 0.02f * u
            fr.pos(rig.nCenter, 0f, cy, 0f)
            fr.rot(rig.nLower, 4f, 0f, 0f)
            torso(fr, 8f, 0f, 0f, 6f)
            // 一条腿提膝，一条腿蹬直在后
            placeFoot(fr, true, 0f, 0.22f * u, 0.36f * u, -30f, cy, 0f)
            placeFoot(fr, false, 0f, -0.16f * u, 0.10f * u, -50f, cy, 0f)
            arm(fr, true, 58f, -28f, 70f, twist = 10f)
            arm(fr, false, 52f, 48f, 62f, twist = 10f)
        }
        jumpFall = still("jump_fall", "下落") { fr ->
            val cy = 0f
            fr.pos(rig.nCenter, 0f, cy, 0f)
            torso(fr, 10f, 0f, 0f, 8f)
            // 两腿微屈、略分开，准备落地
            placeFoot(fr, true, 0.05f * u, 0.12f * u, 0.14f * u, -22f, cy, 0f)
            placeFoot(fr, false, 0.05f * u, -0.04f * u, 0.07f * u, -28f, cy, 0f)
            arm(fr, true, 40f, 6f, 38f, twist = 4f)
            arm(fr, false, 40f, 6f, 38f, twist = 4f)
        }
        land = still("land", "落地") { fr ->
            val cy = -0.30f * u
            fr.pos(rig.nCenter, 0f, cy, 0.03f * u)
            fr.rot(rig.nLower, 6f, 0f, 0f)
            torso(fr, 24f, 0f, 0f, 16f)
            placeFoot(fr, true, 0.06f * u, 0.10f * u, 0f, 0f, cy, 0.03f * u, yaw = 10f)
            placeFoot(fr, false, 0.06f * u, -0.06f * u, 0f, 0f, cy, 0.03f * u, yaw = 10f)
            arm(fr, true, 62f, 18f, 46f, twist = 12f)
            arm(fr, false, 62f, 18f, 46f, twist = 12f)
        }

        all = listOf(idle, walk, run, sprint, crouchIdle, crouchWalk, proneIdle, crawl, jumpRise, jumpFall, land)
    }

    fun find(id: String): Clip? = all.firstOrNull { it.id == id || it.title == id }

    companion object {
        private const val DEG = (PI / 180.0).toFloat()

        @Suppress("unused")
        private fun clamp01(v: Float): Float = min(1f, max(0f, v))
    }
}

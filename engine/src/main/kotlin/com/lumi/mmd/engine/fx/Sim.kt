package com.lumi.mmd.engine.fx

import com.lumi.mmd.engine.scene.Scene
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 碰撞体：球 / 盒 / 胶囊（胶囊的轴是自己的 Y 轴）。模型的刚体、脚本自己的刚体、脚本放的挡板都会变成这个，
 * 粒子（布、绳、液体）拿它来碰撞。
 */
class Collider : com.lumi.mmd.engine.physics.PhysicsWorld.StaticShape {
    var shape = SPHERE
    /** shape = MESH 时：模型的面（模型自己的坐标），配合 px/py/pz（原点）、r（旋转）、scale（缩放）使用。 */
    var mesh: com.lumi.mmd.engine.physics.MeshShape? = null
    var scale = 1f
    private val mq = FloatArray(6)
    override val ownerId: Int get() = id
    var px = 0f; var py = 0f; var pz = 0f
    /** 旋转矩阵，按列存：r[0..2] 是 X 轴。 */
    val r = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    var radius = 1f
    var halfHeight = 0f
    var hx = 1f; var hy = 1f; var hz = 1f
    var vx = 0f; var vy = 0f; var vz = 0f
    override var friction = 0.5f
    /** 包住整个形状的球的半径（快速排除用）。 */
    var bound = 1f

    // 脚本自己放的碰撞体才用得到下面这些
    var id = 0
    var owner = 0
    val anchor = Anchor()
    val pos = FloatArray(3)
    val quat = floatArrayOf(0f, 0f, 0f, 1f)
    private var hasLast = false

    fun updateBound() {
        bound = when (shape) {
            BOX -> sqrt(hx * hx + hy * hy + hz * hz)
            CAPSULE -> radius + halfHeight
            MESH -> Float.MAX_VALUE
            else -> radius
        }
    }

    /**
     * 网格：离球心最近的面。从正面靠近到半径以内算碰到；已经钻到面的背后一点点（[BACK] 以内）也算，沿面的法线推回正面。
     */
    private fun meshContact(x: Float, y: Float, z: Float, rad: Float, out: FloatArray): Boolean {
        val m = mesh ?: return false
        val inv = 1f / scale
        val dx = x - px; val dy = y - py; val dz = z - pz
        val lx = (dx * r[0] + dy * r[1] + dz * r[2]) * inv
        val ly = (dx * r[3] + dy * r[4] + dz * r[5]) * inv
        val lz = (dx * r[6] + dy * r[7] + dz * r[8]) * inv
        val back = max(rad, BACK)
        val reach = max(rad, back) * inv
        if (lx < m.min[0] - reach || ly < m.min[1] - reach || lz < m.min[2] - reach || lx > m.max[0] + reach || ly > m.max[1] + reach || lz > m.max[2] + reach) return false
        val d = m.closest(lx, ly, lz, reach, mq)
        if (d < 0f) return false
        var nx = lx - mq[0]; var ny = ly - mq[1]; var nz = lz - mq[2]
        val side = nx * mq[3] + ny * mq[4] + nz * mq[5]          // 到那个面所在平面的距离，背面为负
        val depth: Float
        if (side >= 0f && d > 1e-7f) {
            if (d * scale >= rad) return false
            nx /= d; ny /= d; nz /= d
            depth = rad - d * scale
        } else {
            if (-side * scale > back) return false
            nx = mq[3]; ny = mq[4]; nz = mq[5]
            depth = rad - side * scale
        }
        out[0] = nx * r[0] + ny * r[3] + nz * r[6]
        out[1] = nx * r[1] + ny * r[4] + nz * r[7]
        out[2] = nx * r[2] + ny * r[5] + nz * r[8]
        out[3] = depth
        return true
    }

    private fun meshDistance(x: Float, y: Float, z: Float, out: FloatArray): Float {
        val m = mesh ?: return Float.MAX_VALUE
        val inv = 1f / scale
        val dx = x - px; val dy = y - py; val dz = z - pz
        val lx = (dx * r[0] + dy * r[1] + dz * r[2]) * inv
        val ly = (dx * r[3] + dy * r[4] + dz * r[5]) * inv
        val lz = (dx * r[6] + dy * r[7] + dz * r[8]) * inv
        val d = m.closest(lx, ly, lz, 1e9f, mq)
        if (d < 0f) return Float.MAX_VALUE
        var nx = lx - mq[0]; var ny = ly - mq[1]; var nz = lz - mq[2]
        val side = nx * mq[3] + ny * mq[4] + nz * mq[5]
        if (d > 1e-7f && side >= 0f) { nx /= d; ny /= d; nz /= d } else { nx = mq[3]; ny = mq[4]; nz = mq[5] }
        out[0] = nx * r[0] + ny * r[3] + nz * r[6]
        out[1] = nx * r[1] + ny * r[4] + nz * r[7]
        out[2] = nx * r[2] + ny * r[5] + nz * r[8]
        return (if (side < 0f) -d else d) * scale
    }

    private fun meshRaycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float, out: FloatArray): Float {
        val m = mesh ?: return -1f
        val inv = 1f / scale
        val ex = ox - px; val ey = oy - py; val ez = oz - pz
        // 方向也除以缩放：这样求出来的 t 直接就是世界里的距离
        val t = m.raycast(
            (ex * r[0] + ey * r[1] + ez * r[2]) * inv, (ex * r[3] + ey * r[4] + ez * r[5]) * inv, (ex * r[6] + ey * r[7] + ez * r[8]) * inv,
            (dx * r[0] + dy * r[1] + dz * r[2]) * inv, (dx * r[3] + dy * r[4] + dz * r[5]) * inv, (dx * r[6] + dy * r[7] + dz * r[8]) * inv,
            maxDist, mq)
        if (t < 0f) return -1f
        out[0] = mq[0] * r[0] + mq[1] * r[3] + mq[2] * r[6]
        out[1] = mq[0] * r[1] + mq[1] * r[4] + mq[2] * r[7]
        out[2] = mq[0] * r[2] + mq[1] * r[5] + mq[2] * r[8]
        return t
    }

    fun setQuat(x: Float, y: Float, z: Float, w: Float) {
        r[0] = 1f - 2f * (y * y + z * z); r[1] = 2f * (x * y + w * z); r[2] = 2f * (x * z - w * y)
        r[3] = 2f * (x * y - w * z); r[4] = 1f - 2f * (x * x + z * z); r[5] = 2f * (y * z + w * x)
        r[6] = 2f * (x * z + w * y); r[7] = 2f * (y * z - w * x); r[8] = 1f - 2f * (x * x + y * y)
    }

    /** 脚本放的碰撞体：按锚点（模型 / 骨骼）算出这一帧的位置，并由位移得到速度。 */
    fun place(scene: Scene, dt: Float, tmp: FloatArray) {
        var x = pos[0]; var y = pos[1]; var z = pos[2]
        if (anchor.resolve(scene, tmp)) { x += tmp[0]; y += tmp[1]; z += tmp[2] }
        if (hasLast && dt > 1e-5f) { vx = (x - px) / dt; vy = (y - py) / dt; vz = (z - pz) / dt } else { vx = 0f; vy = 0f; vz = 0f }
        if (vx * vx + vy * vy + vz * vz > 400f * 400f) { vx = 0f; vy = 0f; vz = 0f }
        px = x; py = y; pz = z; hasLast = true
        setQuat(quat[0], quat[1], quat[2], quat[3])
        updateBound()
    }

    /**
     * 半径 rad 的球在 (x,y,z)：碰到了就把「推出去的方向」写进 out[0..2]、深度写进 out[3]，返回 true。
     */
    override fun contact(x: Float, y: Float, z: Float, rad: Float, out: FloatArray): Boolean {
        if (shape == MESH) return meshContact(x, y, z, rad, out)
        val dx = x - px; val dy = y - py; val dz = z - pz
        val reach = bound + rad
        if (dx * dx + dy * dy + dz * dz > reach * reach) return false
        if (shape == BOX) {
            val lx = dx * r[0] + dy * r[1] + dz * r[2]
            val ly = dx * r[3] + dy * r[4] + dz * r[5]
            val lz = dx * r[6] + dy * r[7] + dz * r[8]
            val qx = abs(lx) - hx; val qy = abs(ly) - hy; val qz = abs(lz) - hz
            var nx: Float; var ny: Float; var nz: Float; val depth: Float
            if (qx <= 0f && qy <= 0f && qz <= 0f) {
                // 球心在盒子里：从最浅的那个面出去
                nx = 0f; ny = 0f; nz = 0f
                if (qx >= qy && qx >= qz) { nx = if (lx >= 0f) 1f else -1f; depth = rad - qx }
                else if (qy >= qz) { ny = if (ly >= 0f) 1f else -1f; depth = rad - qy }
                else { nz = if (lz >= 0f) 1f else -1f; depth = rad - qz }
            } else {
                val cx = max(qx, 0f) * (if (lx >= 0f) 1f else -1f)
                val cy = max(qy, 0f) * (if (ly >= 0f) 1f else -1f)
                val cz = max(qz, 0f) * (if (lz >= 0f) 1f else -1f)
                val d = sqrt(cx * cx + cy * cy + cz * cz)
                if (d >= rad || d < 1e-9f) return false
                nx = cx / d; ny = cy / d; nz = cz / d; depth = rad - d
            }
            out[0] = nx * r[0] + ny * r[3] + nz * r[6]
            out[1] = nx * r[1] + ny * r[4] + nz * r[7]
            out[2] = nx * r[2] + ny * r[5] + nz * r[8]
            out[3] = depth
            return true
        }
        var cx = 0f; var cy = 0f; var cz = 0f
        if (shape == CAPSULE) {
            val t = (dx * r[3] + dy * r[4] + dz * r[5]).coerceIn(-halfHeight, halfHeight)
            cx = r[3] * t; cy = r[4] * t; cz = r[5] * t
        }
        val ex = dx - cx; val ey = dy - cy; val ez = dz - cz
        val d2 = ex * ex + ey * ey + ez * ez
        val rr = radius + rad
        if (d2 >= rr * rr) return false
        val d = sqrt(d2)
        if (d < 1e-9f) { out[0] = 0f; out[1] = 1f; out[2] = 0f } else { out[0] = ex / d; out[1] = ey / d; out[2] = ez / d }
        out[3] = rr - d
        return true
    }

    /** 射线（起点 o，单位方向 d）和这个形状相交的最近距离；没碰到返回负数。法线写进 out[0..2]。 */
    fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float, out: FloatArray): Float {
        if (shape == MESH) return meshRaycast(ox, oy, oz, dx, dy, dz, maxDist, out)
        // 沿射线做球体步进（sphere tracing）：对球、胶囊、盒子通用，精度到 1e-3
        var t = 0f
        for (i in 0 until 64) {
            val x = ox + dx * t; val y = oy + dy * t; val z = oz + dz * t
            val d = distance(x, y, z, out)
            if (d < 1e-3f) return t
            t += d
            if (t > maxDist) return -1f
        }
        return -1f
    }

    /** 点到表面的有符号距离（里面为负），最近方向的法线写进 out[0..2]。 */
    fun distance(x: Float, y: Float, z: Float, out: FloatArray): Float {
        if (shape == MESH) return meshDistance(x, y, z, out)
        val dx = x - px; val dy = y - py; val dz = z - pz
        if (shape == BOX) {
            val lx = dx * r[0] + dy * r[1] + dz * r[2]
            val ly = dx * r[3] + dy * r[4] + dz * r[5]
            val lz = dx * r[6] + dy * r[7] + dz * r[8]
            val qx = abs(lx) - hx; val qy = abs(ly) - hy; val qz = abs(lz) - hz
            val ax = max(qx, 0f); val ay = max(qy, 0f); val az = max(qz, 0f)
            val outside = sqrt(ax * ax + ay * ay + az * az)
            var nx = 0f; var ny = 0f; var nz = 0f
            if (outside > 1e-9f) { nx = ax / outside * sign(lx); ny = ay / outside * sign(ly); nz = az / outside * sign(lz) }
            else if (qx >= qy && qx >= qz) nx = sign(lx) else if (qy >= qz) ny = sign(ly) else nz = sign(lz)
            out[0] = nx * r[0] + ny * r[3] + nz * r[6]; out[1] = nx * r[1] + ny * r[4] + nz * r[7]; out[2] = nx * r[2] + ny * r[5] + nz * r[8]
            return outside + min(max(qx, max(qy, qz)), 0f)
        }
        var cx = 0f; var cy = 0f; var cz = 0f
        if (shape == CAPSULE) {
            val t = (dx * r[3] + dy * r[4] + dz * r[5]).coerceIn(-halfHeight, halfHeight)
            cx = r[3] * t; cy = r[4] * t; cz = r[5] * t
        }
        val ex = dx - cx; val ey = dy - cy; val ez = dz - cz
        val d = sqrt(ex * ex + ey * ey + ez * ez)
        if (d < 1e-9f) { out[0] = 0f; out[1] = 1f; out[2] = 0f } else { out[0] = ex / d; out[1] = ey / d; out[2] = ez / d }
        return d - radius
    }

    private fun sign(v: Float): Float = if (v >= 0f) 1f else -1f

    companion object {
        const val SPHERE = 0
        const val BOX = 1
        const val CAPSULE = 2
        const val MESH = 3
        /** 网格碰撞体：钻到面背后这么深以内还会被推回正面（世界单位）。再深就当成是在另一侧了。 */
        const val BACK = 0.8f
    }
}

/**
 * 脚本用的粒子物理世界：质点 + 约束，按 XPBD（小步长的位置动力学）求解；液体用 PBF（基于位置的流体，
 * Macklin & Müller 2013：每个粒子的密度约束 + XSPH 粘性）。
 *
 * 这是一层通用的东西，不是某一种特效：
 *  - 布、绳、软体、头发 = 质点 + 距离约束（compliance 是柔度，0 = 完全不可伸长，结果和步长无关）；
 *  - 液体 = 标记为 fluid 的质点；
 *  - 沙子、珠子 = 互相碰撞的质点；
 *  - 脚本可以在每个子步里读写任何质点的位置 / 速度、加力，用它实现自己的物理（见 [hook]）。
 * 和地面、模型的刚体、脚本放的碰撞体碰撞（单向：粒子被推开，不反推模型）。
 * 单位和场景一致（MMD 单位，约 8 cm；重力默认 -98）。只在渲染线程上用。
 */
class SimWorld(val id: Int, val owner: Int, capacity: Int) {
    val capacity = capacity.coerceIn(8, MAX_PARTICLES)
    var count = 0
        private set

    val pos = FloatArray(this.capacity * 3)
    val prev = FloatArray(this.capacity * 3)
    val vel = FloatArray(this.capacity * 3)
    val force = FloatArray(this.capacity * 3)
    val invMass = FloatArray(this.capacity)
    val radius = FloatArray(this.capacity)
    /** 每个粒子的颜色（乘在 [color] 上）。 */
    val tint = FloatArray(this.capacity * 4)
    val flags = ByteArray(this.capacity)
    /** 液体粒子的密度 / 静止密度（1 = 正好，>1 = 被压缩）。 */
    val density = FloatArray(this.capacity)

    // ---- 距离约束 ----
    var constraintCount = 0
        private set
    private var cA = IntArray(256)
    private var cB = IntArray(256)
    private var cRest = FloatArray(256)
    private var cCompliance = FloatArray(256)
    private var cLambda = FloatArray(256)

    // ---- 钉住的点 ----
    class Pin(val index: Int) { val anchor = Anchor(); val from = FloatArray(3); val to = FloatArray(3); var fresh = true; var savedInvMass = 0f }
    val pins = ArrayList<Pin>()

    // ---- 参数 ----
    val gravity = floatArrayOf(0f, -98f, 0f)
    val wind = FloatArray(3)
    /** 空气阻力：速度每秒衰减的比例（0 = 没有）。 */
    var damping = 0.02f
    /** 风对粒子的拖拽强度。 */
    var drag = 0f
    var substeps = 4
    var iterations = 2
    var fixedStep = 1f / 60f
    var maxSteps = 3
    /** 自动推进（每帧跟着场景走）。关掉以后由脚本自己调 step。 */
    var auto = true
    var paused = false
    var ground = true
    /** true = 地面跟着场景的地板走（地板删了就没有地面，地板抬高了它也抬高）；脚本给了具体的高度或 false 时为 false。 */
    var groundAuto = true

    fun followFloor(f: com.lumi.mmd.engine.scene.Floor) { if (groundAuto) { ground = f.solid; groundY = f.height } }
    var groundY = 0f
    var friction = 0.3f
    var bounce = 0f
    /** 和模型的刚体碰撞。 */
    var collideModels = true
    /** 容器（轴对齐的盒子）：粒子出不去。 */
    var hasBounds = false
    val bounds = FloatArray(6)

    // ---- 液体 ----
    /** 液体粒子静止时的间距。 */
    var spacing = 0.4f
        set(v) { field = v.coerceIn(0.02f, 10f); calibrated = false }
    /** 粘性（XSPH），0 = 像水，0.3 以上像蜂蜜。 */
    var viscosity = 0.02f
    /** 表面张力 / 内聚：0 = 只排斥不吸引，1 = 完整的密度约束（容易结块）。 */
    var cohesion = 0.1f
    /** 密度约束的松弛：越大越软、越稳。 */
    var relaxation = 0.05f

    // ---- 显示 ----
    var renderMode = RENDER_NONE
    val color = floatArrayOf(0.35f, 0.6f, 1f, 0.8f)
    /** 画出来的半径；0 = 用粒子自己的半径。 */
    var renderRadius = 0f
    var texture = "soft"
    var additive = false

    /** 每个子步开始时调用（参数是子步长，秒）：脚本在这里加力、改位置 / 速度，实现自己的物理。 */
    var hook: ((Float) -> Unit)? = null
    /** 模拟已经走过的时间（秒）和步数。 */
    var time = 0f
        private set
    var steps = 0L
        private set
    var lastContacts = 0
        private set

    private var accumulator = 0f
    private var calibrated = false
    private var kernel = 0.8f
    private var restDensity = 1f
    private var gradScale = 1f
    private var hasFluid = false
    private var hasCollide = false
    private var ring = 0

    // ---- 邻居搜索 ----
    private var tableSize = 0
    private var cellStart = IntArray(0)
    private var sorted = IntArray(0)
    private var neigh = IntArray(0)
    private val ncount = IntArray(this.capacity)
    private val lambda = FloatArray(this.capacity)
    private val delta = FloatArray(this.capacity * 3)
    /** 积分之后、解约束之前的位置。速度按「约束把它挪了多少」来修正，没被碰过的粒子速度一位都不丢。 */
    private val pred = FloatArray(this.capacity * 3)
    private val cn = FloatArray(this.capacity * 4)
    private val t = FloatArray(8)

    fun isFluid(i: Int): Boolean = (flags[i].toInt() and FLAG_FLUID) != 0

    /** 加一个质点。mass = 0 表示固定不动。满了返回 -1。 */
    fun add(x: Float, y: Float, z: Float, mass: Float, rad: Float, flag: Int): Int {
        if (count >= capacity) return -1
        val i = count++
        init(i, x, y, z, mass, rad, flag)
        return i
    }

    private fun init(i: Int, x: Float, y: Float, z: Float, mass: Float, rad: Float, flag: Int) {
        val o = i * 3
        pos[o] = x; pos[o + 1] = y; pos[o + 2] = z
        prev[o] = x; prev[o + 1] = y; prev[o + 2] = z
        vel[o] = 0f; vel[o + 1] = 0f; vel[o + 2] = 0f
        force[o] = 0f; force[o + 1] = 0f; force[o + 2] = 0f
        invMass[i] = if (mass > 1e-9f) 1f / mass else 0f
        radius[i] = rad
        flags[i] = flag.toByte()
        density[i] = 0f
        tint[i * 4] = 1f; tint[i * 4 + 1] = 1f; tint[i * 4 + 2] = 1f; tint[i * 4 + 3] = 1f
        if ((flag and FLAG_FLUID) != 0) hasFluid = true
        if ((flag and FLAG_COLLIDE) != 0) hasCollide = true
    }

    /**
     * 喷出一个液体粒子。满了以后回收最早的液体粒子（所以可以一直倒水）。
     * @return 粒子编号；一个液体粒子的位置都腾不出来时返回 -1
     */
    fun emitFluid(x: Float, y: Float, z: Float, vx: Float, vy: Float, vz: Float): Int {
        var i = add(x, y, z, 1f, spacing * 0.5f, FLAG_FLUID)
        if (i < 0) {
            for (k in 0 until count) {
                val c = (ring + k) % count
                if (isFluid(c)) { i = c; ring = c + 1; break }
            }
            if (i < 0) return -1
            init(i, x, y, z, 1f, spacing * 0.5f, FLAG_FLUID)
        }
        vel[i * 3] = vx; vel[i * 3 + 1] = vy; vel[i * 3 + 2] = vz
        return i
    }

    /** 距离约束：让 a、b 两点保持 rest 的距离。compliance 是柔度（0 = 刚性；越大越像橡皮筋）。 */
    fun link(a: Int, b: Int, rest: Float, compliance: Float): Int {
        if (a < 0 || b < 0 || a >= count || b >= count || a == b) return -1
        if (constraintCount >= MAX_CONSTRAINTS) return -1
        if (constraintCount == cA.size) {
            val n = cA.size * 2
            cA = cA.copyOf(n); cB = cB.copyOf(n); cRest = cRest.copyOf(n); cCompliance = cCompliance.copyOf(n); cLambda = cLambda.copyOf(n)
        }
        val i = constraintCount++
        cA[i] = a; cB[i] = b
        cRest[i] = if (rest >= 0f) rest else dist(a, b)
        cCompliance[i] = max(compliance, 0f)
        return i
    }

    fun linkA(i: Int): Int = cA[i]
    fun linkB(i: Int): Int = cB[i]
    fun linkRest(i: Int): Float = cRest[i]
    fun setLinkRest(i: Int, v: Float) { if (i in 0 until constraintCount) cRest[i] = max(v, 0f) }
    /** 去掉一条约束（剪断）。之后的约束编号会变。 */
    fun unlink(i: Int) {
        if (i !in 0 until constraintCount) return
        val last = --constraintCount
        cA[i] = cA[last]; cB[i] = cB[last]; cRest[i] = cRest[last]; cCompliance[i] = cCompliance[last]
    }

    fun dist(a: Int, b: Int): Float {
        val dx = pos[a * 3] - pos[b * 3]; val dy = pos[a * 3 + 1] - pos[b * 3 + 1]; val dz = pos[a * 3 + 2] - pos[b * 3 + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** 把一个点钉在原地，或者钉到模型的骨骼上（跟着走）。 */
    fun pin(i: Int): Pin {
        pins.firstOrNull { it.index == i }?.let { return it }
        val p = Pin(i)
        p.savedInvMass = invMass[i]
        invMass[i] = 0f
        for (k in 0 until 3) { p.from[k] = pos[i * 3 + k]; p.to[k] = pos[i * 3 + k] }
        pins.add(p)
        return p
    }

    fun unpin(i: Int) {
        val p = pins.firstOrNull { it.index == i } ?: return
        invMass[i] = p.savedInvMass
        pins.remove(p)
    }

    fun clear() {
        count = 0; constraintCount = 0; pins.clear(); hasFluid = false; hasCollide = false; accumulator = 0f; ring = 0
    }

    // ------------------------------------------------------------------ 推进

    /** 每帧调用：按固定步长补齐这一帧的时间。 */
    fun update(dt: Float, scene: Scene, colliders: List<Collider>) {
        if (paused || !auto || dt <= 0f) return
        accumulator += min(dt, 0.1f)
        var n = (accumulator / fixedStep).toInt()
        if (n <= 0) { movePins(scene, false); return }
        accumulator -= n * fixedStep
        if (n > maxSteps) { n = maxSteps; accumulator = 0f }
        movePins(scene, true)
        for (s in 0 until n) step(fixedStep, colliders, (s + 1f) / n, s.toFloat() / n)
    }

    private fun movePins(scene: Scene, commit: Boolean) {
        if (!commit) return
        for (p in pins) {
            val i = p.index
            if (i >= count) continue
            for (k in 0 until 3) p.from[k] = if (p.fresh) pos[i * 3 + k] else p.to[k]
            if (p.anchor.resolve(scene, t)) { p.to[0] = t[0]; p.to[1] = t[1]; p.to[2] = t[2] }
            if (p.fresh) { for (k in 0 until 3) p.from[k] = p.to[k]; p.fresh = false }
        }
    }

    /** 推进一步（dt 秒，内部再分成 [substeps] 个子步）。脚本自己控制节奏时直接调这个。 */
    fun step(dt: Float, colliders: List<Collider>, pinEnd: Float = 1f, pinStart: Float = 0f) {
        if (count == 0 || dt <= 0f) return
        if (!calibrated) calibrate()
        val sub = substeps.coerceIn(1, 32)
        val h = dt / sub
        var contacts = 0
        for (s in 0 until sub) {
            hook?.invoke(h)
            integrate(h)
            // 钉住的点跟着骨骼走，在子步之间插值
            val a = pinStart + (pinEnd - pinStart) * (s + 1f) / sub
            for (p in pins) {
                val i = p.index
                if (i >= count) continue
                for (k in 0 until 3) { val v = p.from[k] + (p.to[k] - p.from[k]) * a; pos[i * 3 + k] = v }
            }
            for (i in 0 until constraintCount) cLambda[i] = 0f
            val grid = hasFluid || hasCollide
            if (grid) buildNeighbours()
            for (it in 0 until iterations.coerceIn(1, 32)) {
                solveLinks(h)
                if (grid) { if (hasFluid) solveFluid(); solveParticleContacts() }
            }
            contacts += solveColliders(h, colliders)
            finish(h)
            time += h
        }
        lastContacts = contacts
        steps++
    }

    private fun integrate(h: Float) {
        val keep = Math.pow((1f - damping.coerceIn(0f, 0.999f)).toDouble(), h.toDouble()).toFloat()
        val hasWind = drag > 0f
        for (i in 0 until count) {
            val o = i * 3
            val w = invMass[i]
            if (w <= 0f) { prev[o] = pos[o]; prev[o + 1] = pos[o + 1]; prev[o + 2] = pos[o + 2]; vel[o] = 0f; vel[o + 1] = 0f; vel[o + 2] = 0f; continue }
            var vx = vel[o] + h * (gravity[0] + force[o] * w)
            var vy = vel[o + 1] + h * (gravity[1] + force[o + 1] * w)
            var vz = vel[o + 2] + h * (gravity[2] + force[o + 2] * w)
            if (hasWind) {
                val k = min(1f, drag * h * w)
                vx += (wind[0] - vx) * k; vy += (wind[1] - vy) * k; vz += (wind[2] - vz) * k
            }
            vx *= keep; vy *= keep; vz *= keep
            // 速度上限：一个子步里最多走 MAX_SPEED，免得穿过薄的碰撞体
            val v2 = vx * vx + vy * vy + vz * vz
            if (v2 > MAX_SPEED * MAX_SPEED) { val k = MAX_SPEED / sqrt(v2); vx *= k; vy *= k; vz *= k }
            vel[o] = vx; vel[o + 1] = vy; vel[o + 2] = vz
            prev[o] = pos[o]; prev[o + 1] = pos[o + 1]; prev[o + 2] = pos[o + 2]
            pos[o] += vx * h; pos[o + 1] += vy * h; pos[o + 2] += vz * h
            pred[o] = pos[o]; pred[o + 1] = pos[o + 1]; pred[o + 2] = pos[o + 2]
            force[o] = 0f; force[o + 1] = 0f; force[o + 2] = 0f
            cn[i * 4 + 3] = 0f
        }
    }

    /** XPBD 的距离约束：Δλ = (-C - α̃λ) / (w_a + w_b + α̃)，α̃ = 柔度 / h²。 */
    private fun solveLinks(h: Float) {
        val ih2 = 1f / (h * h)
        for (c in 0 until constraintCount) {
            val a = cA[c]; val b = cB[c]
            val wa = invMass[a]; val wb = invMass[b]
            val w = wa + wb
            if (w <= 0f) continue
            val ao = a * 3; val bo = b * 3
            val dx = pos[ao] - pos[bo]; val dy = pos[ao + 1] - pos[bo + 1]; val dz = pos[ao + 2] - pos[bo + 2]
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < 1e-9f) continue
            val alpha = cCompliance[c] * ih2
            val dl = (-(d - cRest[c]) - alpha * cLambda[c]) / (w + alpha)
            cLambda[c] += dl
            val k = dl / d
            pos[ao] += dx * k * wa; pos[ao + 1] += dy * k * wa; pos[ao + 2] += dz * k * wa
            pos[bo] -= dx * k * wb; pos[bo + 1] -= dy * k * wb; pos[bo + 2] -= dz * k * wb
        }
    }

    private fun calibrate() {
        calibrated = true
        kernel = spacing * 2f
        // 把粒子按 spacing 排成立方晶格时的密度，就是静止密度；同时记下那时梯度平方和的量级，松弛项按它来定
        val h = kernel
        var rho = 0f
        var gx = 0f; var gy = 0f; var gz = 0f; var g2 = 0f
        for (x in -2..2) for (y in -2..2) for (z in -2..2) {
            val px = x * spacing; val py = y * spacing; val pz = z * spacing
            val r2 = px * px + py * py + pz * pz
            if (r2 >= h * h) continue
            rho += poly6(r2, h)
            if (r2 > 0f) {
                val r = sqrt(r2)
                val g = spiky(r, h) / r
                // 只取半边，模拟「表面上的粒子」那种不对称的情况，得到一个不为零的量级
                g2 += g * g * r2
                if (y >= 0) { gx += g * px; gy += g * py; gz += g * pz }
            }
        }
        restDensity = rho
        gradScale = (g2 + gx * gx + gy * gy + gz * gz) / (rho * rho)
    }

    private fun poly6(r2: Float, h: Float): Float { val d = h * h - r2; return (315.0 / (64.0 * PI)).toFloat() / pow9(h) * d * d * d }
    /** spiky 核的梯度大小（带符号：负的，指向里面）。 */
    private fun spiky(r: Float, h: Float): Float { val d = h - r; return (-45.0 / PI).toFloat() / (h * h * h * h * h * h) * d * d }
    private fun pow9(h: Float): Float { val h3 = h * h * h; return h3 * h3 * h3 }

    private fun cellOf(v: Float, inv: Float): Int = floor(v * inv).toInt()
    private fun hash(x: Int, y: Int, z: Int): Int = ((x * 73856093) xor (y * 19349663) xor (z * 83492791)) and (tableSize - 1)

    private fun buildNeighbours() {
        var cell = if (hasFluid) kernel else 0f
        if (hasCollide || hasFluid) {
            var maxR = 0f
            for (i in 0 until count) if ((flags[i].toInt() and (FLAG_COLLIDE or FLAG_FLUID)) != 0 && radius[i] > maxR) maxR = radius[i]
            cell = max(cell, maxR * 2f)
        }
        if (cell <= 0f) return
        val inv = 1f / cell
        var size = 16
        while (size < count * 2) size = size shl 1
        if (size != tableSize) { tableSize = size; cellStart = IntArray(size + 1) }
        if (sorted.size < count) { sorted = IntArray(capacity); neigh = IntArray(capacity * MAX_NEIGHBOURS) }
        java.util.Arrays.fill(cellStart, 0)
        val mask = FLAG_COLLIDE or FLAG_FLUID
        for (i in 0 until count) {
            if ((flags[i].toInt() and mask) == 0) continue
            cellStart[hash(cellOf(pos[i * 3], inv), cellOf(pos[i * 3 + 1], inv), cellOf(pos[i * 3 + 2], inv))]++
        }
        var sum = 0
        for (c in 0 until size) { sum += cellStart[c]; cellStart[c] = sum }
        cellStart[size] = sum
        for (i in 0 until count) {
            if ((flags[i].toInt() and mask) == 0) continue
            val hsh = hash(cellOf(pos[i * 3], inv), cellOf(pos[i * 3 + 1], inv), cellOf(pos[i * 3 + 2], inv))
            sorted[--cellStart[hsh]] = i
        }
        val c2 = cell * cell
        for (i in 0 until count) {
            var n = 0
            if ((flags[i].toInt() and mask) != 0) {
                val x = pos[i * 3]; val y = pos[i * 3 + 1]; val z = pos[i * 3 + 2]
                val cx = cellOf(x, inv); val cy = cellOf(y, inv); val cz = cellOf(z, inv)
                val base = i * MAX_NEIGHBOURS
                loop@ for (ix in cx - 1..cx + 1) for (iy in cy - 1..cy + 1) for (iz in cz - 1..cz + 1) {
                    val hsh = hash(ix, iy, iz)
                    for (k in cellStart[hsh] until cellStart[hsh + 1]) {
                        val j = sorted[k]
                        if (j == i) continue
                        val jx = pos[j * 3]; val jy = pos[j * 3 + 1]; val jz = pos[j * 3 + 2]
                        // 不同的格子可能撞到同一个哈希桶：确认它真的在这个格子里，否则会被数两次
                        if (cellOf(jx, inv) != ix || cellOf(jy, inv) != iy || cellOf(jz, inv) != iz) continue
                        val dx = x - jx; val dy = y - jy; val dz = z - jz
                        if (dx * dx + dy * dy + dz * dz >= c2) continue
                        neigh[base + n++] = j
                        if (n >= MAX_NEIGHBOURS) break@loop
                    }
                }
            }
            ncount[i] = n
        }
    }

    /** PBF 的一次迭代：算每个液体粒子的 λ，再按 Δp = (1/ρ0) Σ (λi + λj) ∇W 移动。 */
    private fun solveFluid() {
        val h = kernel; val h2 = h * h
        val eps = relaxation * gradScale
        for (i in 0 until count) {
            if (!isFluid(i)) { lambda[i] = 0f; continue }
            val x = pos[i * 3]; val y = pos[i * 3 + 1]; val z = pos[i * 3 + 2]
            var rho = poly6(0f, h)
            var gx = 0f; var gy = 0f; var gz = 0f; var g2 = 0f
            val base = i * MAX_NEIGHBOURS
            for (k in 0 until ncount[i]) {
                val j = neigh[base + k]
                if (!isFluid(j)) continue
                val dx = x - pos[j * 3]; val dy = y - pos[j * 3 + 1]; val dz = z - pos[j * 3 + 2]
                val r2 = dx * dx + dy * dy + dz * dz
                if (r2 >= h2) continue
                rho += poly6(r2, h)
                if (r2 > 1e-12f) {
                    val r = sqrt(r2)
                    val g = spiky(r, h) / r / restDensity
                    val ax = g * dx; val ay = g * dy; val az = g * dz
                    gx += ax; gy += ay; gz += az
                    g2 += ax * ax + ay * ay + az * az
                }
            }
            val d = rho / restDensity
            density[i] = d
            var c = d - 1f
            if (c < 0f) c *= cohesion
            lambda[i] = -c / (g2 + gx * gx + gy * gy + gz * gz + eps)
        }
        for (i in 0 until count) {
            val o = i * 3
            delta[o] = 0f; delta[o + 1] = 0f; delta[o + 2] = 0f
            if (!isFluid(i) || invMass[i] <= 0f) continue
            val x = pos[o]; val y = pos[o + 1]; val z = pos[o + 2]
            val base = i * MAX_NEIGHBOURS
            var ax = 0f; var ay = 0f; var az = 0f
            for (k in 0 until ncount[i]) {
                val j = neigh[base + k]
                if (!isFluid(j)) continue
                val dx = x - pos[j * 3]; val dy = y - pos[j * 3 + 1]; val dz = z - pos[j * 3 + 2]
                val r2 = dx * dx + dy * dy + dz * dz
                if (r2 >= h2 || r2 < 1e-12f) continue
                val r = sqrt(r2)
                val g = (lambda[i] + lambda[j]) * spiky(r, h) / r / restDensity
                ax += g * dx; ay += g * dy; az += g * dz
            }
            delta[o] = ax; delta[o + 1] = ay; delta[o + 2] = az
        }
        // 一次移动不超过半个间距：个别粒子挤在一起时不至于被弹飞
        val lim = spacing * 0.5f
        for (i in 0 until count) {
            if (!isFluid(i)) continue
            val o = i * 3
            var ax = delta[o]; var ay = delta[o + 1]; var az = delta[o + 2]
            val l2 = ax * ax + ay * ay + az * az
            if (l2 > lim * lim) { val k = lim / sqrt(l2); ax *= k; ay *= k; az *= k }
            pos[o] += ax; pos[o + 1] += ay; pos[o + 2] += az
        }
    }

    /** 粒子之间的接触：两个都是液体的归 PBF 管，其余的按半径互相推开。 */
    private fun solveParticleContacts() {
        if (!hasCollide) return
        for (i in 0 until count) {
            val fi = flags[i].toInt()
            if ((fi and (FLAG_COLLIDE or FLAG_FLUID)) == 0) continue
            val base = i * MAX_NEIGHBOURS
            val io = i * 3
            for (k in 0 until ncount[i]) {
                val j = neigh[base + k]
                if (j < i) continue
                val fj = flags[j].toInt()
                if ((fi and FLAG_FLUID) != 0 && (fj and FLAG_FLUID) != 0) continue
                val wa = invMass[i]; val wb = invMass[j]
                val w = wa + wb
                if (w <= 0f) continue
                val jo = j * 3
                val dx = pos[io] - pos[jo]; val dy = pos[io + 1] - pos[jo + 1]; val dz = pos[io + 2] - pos[jo + 2]
                val rr = radius[i] + radius[j]
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 >= rr * rr || d2 < 1e-12f) continue
                val d = sqrt(d2)
                val kk = (rr - d) / d / w
                pos[io] += dx * kk * wa; pos[io + 1] += dy * kk * wa; pos[io + 2] += dz * kk * wa
                pos[jo] -= dx * kk * wb; pos[jo + 1] -= dy * kk * wb; pos[jo + 2] -= dz * kk * wb
            }
        }
    }

    /** 和地面、容器、碰撞体的接触：推到表面外面，并按摩擦减掉沿表面的滑动。 */
    private fun solveColliders(h: Float, colliders: List<Collider>): Int {
        var contacts = 0
        // 先求出粒子的包围盒，离得远的碰撞体整个跳过
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var maxR = 0f
        for (i in 0 until count) {
            val o = i * 3
            if (pos[o] < minX) minX = pos[o]; if (pos[o] > maxX) maxX = pos[o]
            if (pos[o + 1] < minY) minY = pos[o + 1]; if (pos[o + 1] > maxY) maxY = pos[o + 1]
            if (pos[o + 2] < minZ) minZ = pos[o + 2]; if (pos[o + 2] > maxZ) maxZ = pos[o + 2]
            if (radius[i] > maxR) maxR = radius[i]
        }
        near.clear()
        for (c in colliders) {
            val b = c.bound + maxR
            if (c.px + b < minX || c.px - b > maxX || c.py + b < minY || c.py - b > maxY || c.pz + b < minZ || c.pz - b > maxZ) continue
            near.add(c)
        }
        for (i in 0 until count) {
            if (invMass[i] <= 0f) continue
            val o = i * 3
            val rad = radius[i]
            if (ground && pos[o + 1] < groundY + rad) {
                val depth = groundY + rad - pos[o + 1]
                pos[o + 1] = groundY + rad
                slide(i, 0f, 1f, 0f, depth, friction, 0f, 0f, 0f, h)
                contacts++
            }
            if (hasBounds) {
                for (k in 0 until 3) {
                    val lo = bounds[k] + rad; val hi = bounds[k + 3] - rad
                    if (pos[o + k] < lo) {
                        val depth = lo - pos[o + k]; pos[o + k] = lo
                        slide(i, if (k == 0) 1f else 0f, if (k == 1) 1f else 0f, if (k == 2) 1f else 0f, depth, friction, 0f, 0f, 0f, h); contacts++
                    } else if (pos[o + k] > hi && hi > lo) {
                        val depth = pos[o + k] - hi; pos[o + k] = hi
                        slide(i, if (k == 0) -1f else 0f, if (k == 1) -1f else 0f, if (k == 2) -1f else 0f, depth, friction, 0f, 0f, 0f, h); contacts++
                    }
                }
            }
            for (ci in 0 until near.size) {
                val c = near[ci]
                if (!c.contact(pos[o], pos[o + 1], pos[o + 2], rad, t)) continue
                val nx = t[0]; val ny = t[1]; val nz = t[2]; val depth = t[3]
                pos[o] += nx * depth; pos[o + 1] += ny * depth; pos[o + 2] += nz * depth
                slide(i, nx, ny, nz, depth, max(friction, c.friction), c.vx, c.vy, c.vz, h)
                contacts++
            }
        }
        return contacts
    }

    private val near = ArrayList<Collider>()

    /** 摩擦：把这个子步里相对于表面的切向位移按库仑摩擦减小（最多减 μ × 压入深度）；同时记下接触法线给反弹用。 */
    private fun slide(i: Int, nx: Float, ny: Float, nz: Float, depth: Float, mu: Float, cvx: Float, cvy: Float, cvz: Float, h: Float) {
        val o = i * 3
        val dx = pos[o] - prev[o] - cvx * h; val dy = pos[o + 1] - prev[o + 1] - cvy * h; val dz = pos[o + 2] - prev[o + 2] - cvz * h
        val dn = dx * nx + dy * ny + dz * nz
        val tx = dx - nx * dn; val ty = dy - ny * dn; val tz = dz - nz * dn
        val tl = sqrt(tx * tx + ty * ty + tz * tz)
        if (tl > 1e-9f && mu > 0f) {
            val k = min(1f, mu * depth / tl)
            pos[o] -= tx * k; pos[o + 1] -= ty * k; pos[o + 2] -= tz * k
        }
        cn[i * 4] = nx; cn[i * 4 + 1] = ny; cn[i * 4 + 2] = nz; cn[i * 4 + 3] = 1f
    }

    /** 子步收尾：由位移得到速度，处理反弹，液体再做一次 XSPH 粘性。 */
    private fun finish(h: Float) {
        val ih = 1f / h
        for (i in 0 until count) {
            if (invMass[i] <= 0f) continue
            val o = i * 3
            val ovx = vel[o]; val ovy = vel[o + 1]; val ovz = vel[o + 2]
            // 数学上等于 (pos - prev) / h，但直接那样算会把位置的舍入误差（坐标几十时约 4e-6）每个子步都乘上 1/h 灌进速度里
            var vx = ovx + (pos[o] - pred[o]) * ih; var vy = ovy + (pos[o + 1] - pred[o + 1]) * ih; var vz = ovz + (pos[o + 2] - pred[o + 2]) * ih
            if (bounce > 0f && cn[i * 4 + 3] > 0f) {
                val nx = cn[i * 4]; val ny = cn[i * 4 + 1]; val nz = cn[i * 4 + 2]
                val before = ovx * nx + ovy * ny + ovz * nz
                // 撞上去的速度够大才弹，不然静止在地上的东西会一直抖
                if (before < -2f * abs(gravity[1]) * h - 1e-3f) {
                    val now = vx * nx + vy * ny + vz * nz
                    val add = -bounce * before - now
                    if (add > 0f) { vx += nx * add; vy += ny * add; vz += nz * add }
                }
            }
            vel[o] = vx; vel[o + 1] = vy; vel[o + 2] = vz
        }
        if (hasFluid && viscosity > 0f) {
            val hk = kernel; val h2 = hk * hk
            val w0 = 1f / restDensity
            for (i in 0 until count) {
                val o = i * 3
                delta[o] = 0f; delta[o + 1] = 0f; delta[o + 2] = 0f
                if (!isFluid(i)) continue
                val base = i * MAX_NEIGHBOURS
                var ax = 0f; var ay = 0f; var az = 0f
                for (k in 0 until ncount[i]) {
                    val j = neigh[base + k]
                    if (!isFluid(j)) continue
                    val dx = pos[o] - pos[j * 3]; val dy = pos[o + 1] - pos[j * 3 + 1]; val dz = pos[o + 2] - pos[j * 3 + 2]
                    val r2 = dx * dx + dy * dy + dz * dz
                    if (r2 >= h2) continue
                    val w = poly6(r2, hk) * w0
                    ax += (vel[j * 3] - vel[o]) * w; ay += (vel[j * 3 + 1] - vel[o + 1]) * w; az += (vel[j * 3 + 2] - vel[o + 2]) * w
                }
                delta[o] = ax; delta[o + 1] = ay; delta[o + 2] = az
            }
            val c = viscosity.coerceIn(0f, 1f)
            for (i in 0 until count) {
                if (!isFluid(i) || invMass[i] <= 0f) continue
                val o = i * 3
                vel[o] += delta[o] * c; vel[o + 1] += delta[o + 1] * c; vel[o + 2] += delta[o + 2] * c
            }
        }
    }

    /** 这个粒子现在的邻居（上一个子步的搜索结果）。 */
    fun neighbours(i: Int, out: IntArray): Int {
        if (i !in 0 until count || neigh.isEmpty()) return 0
        val n = min(ncount[i], out.size)
        System.arraycopy(neigh, i * MAX_NEIGHBOURS, out, 0, n)
        return n
    }

    /** 总动能（½mv²，固定点不算）——检查自己写的物理守不守恒时用。 */
    fun kineticEnergy(): Float {
        var e = 0f
        for (i in 0 until count) {
            if (invMass[i] <= 0f) continue
            val o = i * 3
            e += 0.5f / invMass[i] * (vel[o] * vel[o] + vel[o + 1] * vel[o + 1] + vel[o + 2] * vel[o + 2])
        }
        return e
    }

    companion object {
        const val MAX_PARTICLES = 6000
        const val MAX_CONSTRAINTS = 40000
        const val MAX_NEIGHBOURS = 48
        const val MAX_SPEED = 600f
        const val FLAG_FLUID = 1
        const val FLAG_COLLIDE = 2
        const val RENDER_NONE = 0
        const val RENDER_POINTS = 1
        const val RENDER_BALLS = 2
        const val RENDER_LIQUID = 3
        const val RENDER_LINES = 4
        val RENDER_NAMES = listOf("none", "points", "balls", "liquid", "lines")
    }
}

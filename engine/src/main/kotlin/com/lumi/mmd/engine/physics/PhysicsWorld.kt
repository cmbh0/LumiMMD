package com.lumi.mmd.engine.physics

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 纯 Kotlin 实现的刚体物理（给 MMD 的头发 / 裙子 / 饰品用）。
 *
 * MMD 本体用的是 Bullet：刚体（球 / 盒 / 胶囊）+ btGeneric6DofSpringConstraint。
 * 这里按同样的语义重新实现了一套精简的顺序冲量（PGS）求解器：
 *  - 6 自由度弹簧关节：线性 3 轴 + 角度 3 轴（欧拉 XYZ 分解、轴的取法、上下限 / 锁定 / 自由 的判定、
 *    弹簧"电机"的目标速度与冲量上限都与 Bullet 的实现保持一致，所以 PMX 里的参数可以原样使用）；
 *  - 碰撞：球 / 胶囊统一成"线段 + 半径"，另有盒-线段、盒-盒（SAT）以及地面；按 MMD 的分组 / 掩码过滤；
 *  - 运动学刚体（跟随骨骼）在子步之间插值，并带上由位移换算出的速度，推开头发时更平滑；
 *  - 每帧零分配：约束行、接触点都放在可增长的扁平数组里。
 *
 * 不依赖任何 native 库，所以引擎可以保持 100% Kotlin。
 */
class PhysicsWorld {

    class Body {
        var shape = SHAPE_SPHERE
        var radius = 0f
        var halfHeight = 0f
        var hx = 0f; var hy = 0f; var hz = 0f
        var invMass = 0f
        var invIx = 0f; var invIy = 0f; var invIz = 0f
        var linDamp = 0f
        var angDamp = 0f
        var restitution = 0f
        var friction = 0.5f
        var groupBit = 1
        var mask = 0xFFFF
        var kinematic = true

        // 状态
        var px = 0f; var py = 0f; var pz = 0f
        var qx = 0f; var qy = 0f; var qz = 0f; var qw = 1f
        var vx = 0f; var vy = 0f; var vz = 0f
        var wx = 0f; var wy = 0f; var wz = 0f

        // 运动学刚体：本帧起点与目标
        var sx = 0f; var sy = 0f; var sz = 0f
        var sqx = 0f; var sqy = 0f; var sqz = 0f; var sqw = 1f
        var tx = 0f; var ty = 0f; var tz = 0f
        var tqx = 0f; var tqy = 0f; var tqz = 0f; var tqw = 1f

        /** 旋转矩阵（列主序：r[0..2] 是 X 轴）。 */
        val r = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
        var minX = 0f; var minY = 0f; var minZ = 0f
        var maxX = 0f; var maxY = 0f; var maxZ = 0f

        fun setPose(x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, rw: Float) {
            px = x; py = y; pz = z; qx = rx; qy = ry; qz = rz; qw = rw
        }
    }

    class Joint {
        var a = -1
        var b = -1
        val posA = FloatArray(3); val rotA = FloatArray(9)
        val posB = FloatArray(3); val rotB = FloatArray(9)
        val linLo = FloatArray(3); val linHi = FloatArray(3)
        val angLo = FloatArray(3); val angHi = FloatArray(3)
        val linK = FloatArray(3); val angK = FloatArray(3)
    }

    /**
     * 不会动的碰撞体（模型的「碰撞」属性：包围盒或整张网格）。坐标是世界坐标；
     * 这个物理世界如果在某个模型自己的坐标里（头发裙子），用 [staticMatrix] 换算。
     */
    interface StaticShape {
        val friction: Float
        val ownerId: Int
        /** 半径 rad 的球在 (x,y,z)：碰到了把推出去的方向写进 out[0..2]、深度写进 out[3]。 */
        fun contact(x: Float, y: Float, z: Float, rad: Float, out: FloatArray): Boolean
    }

    var statics: List<StaticShape> = emptyList()
    /** 这个世界的坐标 → 世界坐标（4×4，列主序）；null = 本来就是世界坐标。 */
    var staticMatrix: FloatArray? = null
    /** 不和这个编号的碰撞体碰（模型不撞自己）。 */
    var staticSkip = 0
    private val sc = FloatArray(4)

    val bodies = ArrayList<Body>()
    val joints = ArrayList<Joint>()

    var gravityX = 0f
    var gravityY = -98f
    var gravityZ = 0f

    /** 地面（平面 n·x = d，位于刚体所在的坐标系里）。 */
    var groundEnabled = true
    var groundNx = 0f; var groundNy = 1f; var groundNz = 0f; var groundD = 0f

    var iterations = 10
    var fixedStep = 1f / 60f
    var maxSubSteps = 3

    // ---- 可调参数（见 PhysicsTuning）----
    /** 风：加在所有物理刚体上的加速度。 */
    var windX = 0f
    var windY = 0f
    var windZ = 0f
    /** 阵风的强弱（0 = 匀速的风，1 = 忽大忽小）。 */
    var windTurbulence = 0f
    /** 阻尼的倍数（>1 更"粘"，摆动更快停下来）。 */
    var linDampScale = 1f
    var angDampScale = 1f
    /** 关节弹簧硬度的倍数（>1 头发裙子更硬、更快弹回原位）。 */
    var springScale = 1f
    /** 关节可转动范围的倍数（>1 摆得更开）。 */
    var limitScale = 1f
    /** 模拟走过的时间（阵风用）。 */
    private var clock = 0f
    private var accumulator = 0f

    // 候选碰撞对（通过了分组 / 掩码过滤，且至少一方是动力学刚体）
    private var pairs = IntArray(0)
    private var pairCount = 0

    // ---- 约束行 ----
    private var rowCap = 512
    private var rowCount = 0
    private var rowA = IntArray(rowCap)
    private var rowB = IntArray(rowCap)
    private var rowJ = FloatArray(rowCap * 12)
    private var rowM = FloatArray(rowCap * 12)
    private var rowEff = FloatArray(rowCap)
    private var rowBias = FloatArray(rowCap)
    private var rowLo = FloatArray(rowCap)
    private var rowHi = FloatArray(rowCap)
    private var rowLambda = FloatArray(rowCap)
    private var rowFric = IntArray(rowCap)
    private var rowMu = FloatArray(rowCap)

    private val t = FloatArray(64)
    private val m3 = FloatArray(27)

    /** 最近一次 step 的接触点数（调试 / 统计）。 */
    var lastContactCount = 0
        private set

    fun clear() {
        bodies.clear(); joints.clear(); pairCount = 0; accumulator = 0f; groundIndex = -1
    }

    /** 刚体 / 关节都添加完之后调用：建立候选碰撞对。 */
    fun finalizeSetup() {
        val n = bodies.size
        var list = IntArray(maxOf(16, n * 8))
        var c = 0
        for (i in 0 until n) {
            val a = bodies[i]
            for (j in i + 1 until n) {
                val b = bodies[j]
                if (a.kinematic && b.kinematic) continue
                if ((a.groupBit and b.mask) == 0 || (b.groupBit and a.mask) == 0) continue
                if (c + 2 > list.size) list = list.copyOf(list.size * 2)
                list[c++] = i; list[c++] = j
            }
        }
        pairs = list
        pairCount = c / 2
        bodies.remove(groundBody)
        bodies.add(groundBody)
        groundIndex = bodies.size - 1
        for (b in bodies) { updateRotation(b); updateAabb(b) }
    }

    /** 清零所有速度，并把运动学刚体的插值起点对齐到当前位置（跳帧 / 重置时用）。 */
    fun resetVelocities() {
        for (b in bodies) {
            b.vx = 0f; b.vy = 0f; b.vz = 0f; b.wx = 0f; b.wy = 0f; b.wz = 0f
            b.sx = b.px; b.sy = b.py; b.sz = b.pz
            b.sqx = b.qx; b.sqy = b.qy; b.sqz = b.qz; b.sqw = b.qw
            b.tx = b.px; b.ty = b.py; b.tz = b.pz
            b.tqx = b.qx; b.tqy = b.qy; b.tqz = b.qz; b.tqw = b.qw
            updateRotation(b); updateAabb(b)
        }
        accumulator = 0f
    }

    fun setKinematicTarget(b: Body, x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, rw: Float) {
        b.tx = x; b.ty = y; b.tz = z
        b.tqx = rx; b.tqy = ry; b.tqz = rz; b.tqw = rw
    }

    /**
     * 推进 dt 秒（内部按固定步长拆成若干子步）。
     * @return 实际执行的子步数
     */
    fun step(dt: Float): Int {
        if (dt <= 0f) return 0
        accumulator += dt
        var steps = (accumulator / fixedStep).toInt()
        if (steps <= 0) return 0
        accumulator -= steps * fixedStep
        if (steps > maxSubSteps) {
            steps = maxSubSteps
            accumulator = 0f
        }
        for (b in bodies) {
            if (!b.kinematic) continue
            b.sx = b.px; b.sy = b.py; b.sz = b.pz
            b.sqx = b.qx; b.sqy = b.qy; b.sqz = b.qz; b.sqw = b.qw
        }
        for (s in 0 until steps) subStep(fixedStep, (s + 1).toFloat() / steps)
        return steps
    }

    // ------------------------------------------------------------------ 子步

    private fun subStep(h: Float, alpha: Float) {
        val invH = 1f / h
        clock += h
        val hasWind = windX != 0f || windY != 0f || windZ != 0f
        // 1) 运动学刚体：插值到本子步的位置，并换算出速度
        for (b in bodies) {
            if (b.kinematic) {
                val nx = b.sx + (b.tx - b.sx) * alpha
                val ny = b.sy + (b.ty - b.sy) * alpha
                val nz = b.sz + (b.tz - b.sz) * alpha
                var tqx = b.tqx; var tqy = b.tqy; var tqz = b.tqz; var tqw = b.tqw
                if (b.sqx * tqx + b.sqy * tqy + b.sqz * tqz + b.sqw * tqw < 0f) { tqx = -tqx; tqy = -tqy; tqz = -tqz; tqw = -tqw }
                var nqx = b.sqx + (tqx - b.sqx) * alpha
                var nqy = b.sqy + (tqy - b.sqy) * alpha
                var nqz = b.sqz + (tqz - b.sqz) * alpha
                var nqw = b.sqw + (tqw - b.sqw) * alpha
                val l = sqrt(nqx * nqx + nqy * nqy + nqz * nqz + nqw * nqw)
                if (l > 1e-12f) { nqx /= l; nqy /= l; nqz /= l; nqw /= l } else { nqx = 0f; nqy = 0f; nqz = 0f; nqw = 1f }
                b.vx = (nx - b.px) * invH; b.vy = (ny - b.py) * invH; b.vz = (nz - b.pz) * invH
                // ω = 2 · (q_new ⊗ q_old⁻¹).xyz / h
                var dx = -nqw * b.qx + nqx * b.qw - nqy * b.qz + nqz * b.qy
                var dy = -nqw * b.qy + nqx * b.qz + nqy * b.qw - nqz * b.qx
                var dz = -nqw * b.qz - nqx * b.qy + nqy * b.qx + nqz * b.qw
                val dw = nqw * b.qw + nqx * b.qx + nqy * b.qy + nqz * b.qz
                if (dw < 0f) { dx = -dx; dy = -dy; dz = -dz }
                b.wx = 2f * dx * invH; b.wy = 2f * dy * invH; b.wz = 2f * dz * invH
                // 速度过大说明是瞬移（跳帧），不让它把头发打飞
                val v2 = b.vx * b.vx + b.vy * b.vy + b.vz * b.vz
                if (v2 > MAX_KINEMATIC_SPEED * MAX_KINEMATIC_SPEED) { b.vx = 0f; b.vy = 0f; b.vz = 0f; b.wx = 0f; b.wy = 0f; b.wz = 0f }
                b.px = nx; b.py = ny; b.pz = nz
                b.qx = nqx; b.qy = nqy; b.qz = nqz; b.qw = nqw
            } else {
                // 重力 + 阻尼（与 Bullet 相同：v *= (1 - damping)^dt）
                var ax = gravityX; var ay = gravityY; var az = gravityZ
                if (hasWind) {
                    // 每个刚体所在的位置风力略有不同，再随时间起伏：看起来像一阵一阵的风，而不是整体平移
                    var gust = 1f
                    if (windTurbulence > 0f) {
                        val ph = b.px * 0.31f + b.py * 0.17f + b.pz * 0.23f
                        gust = 1f + windTurbulence * (0.6f * kotlin.math.sin(clock * 2.1f + ph) + 0.4f * kotlin.math.sin(clock * 5.3f + ph * 2.7f))
                    }
                    ax += windX * gust; ay += windY * gust; az += windZ * gust
                }
                b.vx += ax * h; b.vy += ay * h; b.vz += az * h
                val ld = (1f - b.linDamp).coerceIn(0f, 1f).pow(h * linDampScale)
                val ad = (1f - b.angDamp).coerceIn(0f, 1f).pow(h * angDampScale)
                b.vx *= ld; b.vy *= ld; b.vz *= ld
                b.wx *= ad; b.wy *= ad; b.wz *= ad
            }
            updateRotation(b)
            updateAabb(b)
        }

        // 2) 建立约束行
        rowCount = 0
        for (j in joints) buildJoint(j, h, invH)
        val contactStart = rowCount
        collide(invH)
        lastContactCount = (rowCount - contactStart) / 3

        // 3) 迭代求解
        val n = rowCount
        val bs = bodies
        for (it in 0 until iterations) {
            for (i in 0 until n) {
                val a = bs[rowA[i]]
                val b = bs[rowB[i]]
                val o = i * 12
                val j = rowJ
                val cdot = j[o] * a.vx + j[o + 1] * a.vy + j[o + 2] * a.vz +
                    j[o + 3] * a.wx + j[o + 4] * a.wy + j[o + 5] * a.wz +
                    j[o + 6] * b.vx + j[o + 7] * b.vy + j[o + 8] * b.vz +
                    j[o + 9] * b.wx + j[o + 10] * b.wy + j[o + 11] * b.wz
                var lo = rowLo[i]
                var hi = rowHi[i]
                val f = rowFric[i]
                if (f >= 0) {
                    val lim = rowMu[i] * rowLambda[f]
                    lo = -lim; hi = lim
                }
                val old = rowLambda[i]
                var nl = old + rowEff[i] * (rowBias[i] - cdot)
                if (nl < lo) nl = lo else if (nl > hi) nl = hi
                val d = nl - old
                if (d == 0f) continue
                rowLambda[i] = nl
                val m = rowM
                a.vx += d * m[o]; a.vy += d * m[o + 1]; a.vz += d * m[o + 2]
                a.wx += d * m[o + 3]; a.wy += d * m[o + 4]; a.wz += d * m[o + 5]
                b.vx += d * m[o + 6]; b.vy += d * m[o + 7]; b.vz += d * m[o + 8]
                b.wx += d * m[o + 9]; b.wy += d * m[o + 10]; b.wz += d * m[o + 11]
            }
        }

        // 4) 积分
        for (b in bodies) {
            if (b.kinematic) continue
            // 数值保护
            val v2 = b.vx * b.vx + b.vy * b.vy + b.vz * b.vz
            if (v2 != v2 || v2 > MAX_SPEED * MAX_SPEED) {
                if (v2 != v2) { b.vx = 0f; b.vy = 0f; b.vz = 0f } else {
                    val s = MAX_SPEED / sqrt(v2); b.vx *= s; b.vy *= s; b.vz *= s
                }
            }
            val w2 = b.wx * b.wx + b.wy * b.wy + b.wz * b.wz
            if (w2 != w2 || w2 > MAX_ANGULAR * MAX_ANGULAR) {
                if (w2 != w2) { b.wx = 0f; b.wy = 0f; b.wz = 0f } else {
                    val s = MAX_ANGULAR / sqrt(w2); b.wx *= s; b.wy *= s; b.wz *= s
                }
            }
            b.px += b.vx * h; b.py += b.vy * h; b.pz += b.vz * h
            // q += 0.5 · ω ⊗ q · h
            val hx = b.wx * h * 0.5f; val hy = b.wy * h * 0.5f; val hz = b.wz * h * 0.5f
            val qx = b.qx; val qy = b.qy; val qz = b.qz; val qw = b.qw
            var nx = qx + (hx * qw + hy * qz - hz * qy)
            var ny = qy + (hy * qw + hz * qx - hx * qz)
            var nz = qz + (hz * qw + hx * qy - hy * qx)
            var nw = qw - (hx * qx + hy * qy + hz * qz)
            val l = sqrt(nx * nx + ny * ny + nz * nz + nw * nw)
            if (l > 1e-12f && l == l) { nx /= l; ny /= l; nz /= l; nw /= l } else { nx = 0f; ny = 0f; nz = 0f; nw = 1f }
            b.qx = nx; b.qy = ny; b.qz = nz; b.qw = nw
        }
    }

    fun updateRotation(b: Body) {
        val x = b.qx; val y = b.qy; val z = b.qz; val w = b.qw
        val x2 = x + x; val y2 = y + y; val z2 = z + z
        val xx = x * x2; val xy = x * y2; val xz = x * z2
        val yy = y * y2; val yz = y * z2; val zz = z * z2
        val wx = w * x2; val wy = w * y2; val wz = w * z2
        val r = b.r
        r[0] = 1f - (yy + zz); r[1] = xy + wz; r[2] = xz - wy
        r[3] = xy - wz; r[4] = 1f - (xx + zz); r[5] = yz + wx
        r[6] = xz + wy; r[7] = yz - wx; r[8] = 1f - (xx + yy)
    }

    private fun updateAabb(b: Body) {
        val r = b.r
        val ex: Float; val ey: Float; val ez: Float
        when (b.shape) {
            SHAPE_BOX -> {
                ex = abs(r[0]) * b.hx + abs(r[3]) * b.hy + abs(r[6]) * b.hz
                ey = abs(r[1]) * b.hx + abs(r[4]) * b.hy + abs(r[7]) * b.hz
                ez = abs(r[2]) * b.hx + abs(r[5]) * b.hy + abs(r[8]) * b.hz
            }
            SHAPE_CAPSULE -> {
                ex = abs(r[3]) * b.halfHeight + b.radius
                ey = abs(r[4]) * b.halfHeight + b.radius
                ez = abs(r[5]) * b.halfHeight + b.radius
            }
            else -> { ex = b.radius; ey = b.radius; ez = b.radius }
        }
        b.minX = b.px - ex; b.minY = b.py - ey; b.minZ = b.pz - ez
        b.maxX = b.px + ex; b.maxY = b.py + ey; b.maxZ = b.pz + ez
    }

    // ------------------------------------------------------------------ 约束行

    private fun growRows() {
        val cap = rowCap * 2
        rowA = rowA.copyOf(cap); rowB = rowB.copyOf(cap)
        rowJ = rowJ.copyOf(cap * 12); rowM = rowM.copyOf(cap * 12)
        rowEff = rowEff.copyOf(cap); rowBias = rowBias.copyOf(cap)
        rowLo = rowLo.copyOf(cap); rowHi = rowHi.copyOf(cap)
        rowLambda = rowLambda.copyOf(cap); rowFric = rowFric.copyOf(cap); rowMu = rowMu.copyOf(cap)
        rowCap = cap
    }

    /** 添加一行约束，返回行号；有效质量为 0（两个刚体都不可动）时返回 -1。 */
    private fun addRow(
        ia: Int, ib: Int,
        lax: Float, lay: Float, laz: Float, aax: Float, aay: Float, aaz: Float,
        lbx: Float, lby: Float, lbz: Float, abx: Float, aby: Float, abz: Float,
        bias: Float, lo: Float, hi: Float
    ): Int {
        if (rowCount >= rowCap) growRows()
        val a = bodies[ia]
        val b = bodies[ib]
        val i = rowCount
        val o = i * 12
        val j = rowJ
        val m = rowM
        j[o] = lax; j[o + 1] = lay; j[o + 2] = laz; j[o + 3] = aax; j[o + 4] = aay; j[o + 5] = aaz
        j[o + 6] = lbx; j[o + 7] = lby; j[o + 8] = lbz; j[o + 9] = abx; j[o + 10] = aby; j[o + 11] = abz
        m[o] = lax * a.invMass; m[o + 1] = lay * a.invMass; m[o + 2] = laz * a.invMass
        invInertiaMul(a, aax, aay, aaz, m, o + 3)
        m[o + 6] = lbx * b.invMass; m[o + 7] = lby * b.invMass; m[o + 8] = lbz * b.invMass
        invInertiaMul(b, abx, aby, abz, m, o + 9)
        var k = 0f
        for (c in 0 until 12) k += j[o + c] * m[o + c]
        if (k < 1e-12f) return -1
        rowA[i] = ia; rowB[i] = ib
        rowEff[i] = 1f / k
        rowBias[i] = bias
        rowLo[i] = lo; rowHi[i] = hi
        rowLambda[i] = 0f
        rowFric[i] = -1
        rowMu[i] = 0f
        rowCount = i + 1
        return i
    }

    private fun invInertiaMul(b: Body, x: Float, y: Float, z: Float, out: FloatArray, o: Int) {
        if (b.invMass == 0f) { out[o] = 0f; out[o + 1] = 0f; out[o + 2] = 0f; return }
        val r = b.r
        val lx = (r[0] * x + r[1] * y + r[2] * z) * b.invIx
        val ly = (r[3] * x + r[4] * y + r[5] * z) * b.invIy
        val lz = (r[6] * x + r[7] * y + r[8] * z) * b.invIz
        out[o] = r[0] * lx + r[3] * ly + r[6] * lz
        out[o + 1] = r[1] * lx + r[4] * ly + r[7] * lz
        out[o + 2] = r[2] * lx + r[5] * ly + r[8] * lz
    }

    private fun buildJoint(jt: Joint, h: Float, invH: Float) {
        val a = bodies[jt.a]
        val b = bodies[jt.b]
        if (a.invMass == 0f && b.invMass == 0f) return
        val ra = m3            // 关节坐标系在 A 侧的世界朝向（0..8）
        val rb = m3            // B 侧（9..17）
        mul3(a.r, 0, jt.rotA, 0, ra, 0)
        mul3(b.r, 0, jt.rotB, 0, rb, 9)
        val ar = a.r
        val br = b.r
        // 锚点相对各自质心的偏移（世界朝向）
        val rax = ar[0] * jt.posA[0] + ar[3] * jt.posA[1] + ar[6] * jt.posA[2]
        val ray = ar[1] * jt.posA[0] + ar[4] * jt.posA[1] + ar[7] * jt.posA[2]
        val raz = ar[2] * jt.posA[0] + ar[5] * jt.posA[1] + ar[8] * jt.posA[2]
        val rbx = br[0] * jt.posB[0] + br[3] * jt.posB[1] + br[6] * jt.posB[2]
        val rby = br[1] * jt.posB[0] + br[4] * jt.posB[1] + br[7] * jt.posB[2]
        val rbz = br[2] * jt.posB[0] + br[5] * jt.posB[1] + br[8] * jt.posB[2]
        val dx = (b.px + rbx) - (a.px + rax)
        val dy = (b.py + rby) - (a.py + ray)
        val dz = (b.pz + rbz) - (a.pz + raz)
        val erp = JOINT_ERP * invH

        // ---- 线性 3 轴：沿 A 侧关节坐标系的各轴 ----
        for (i in 0 until 3) {
            val lo = jt.linLo[i]
            val hi = jt.linHi[i]
            val k = jt.linK[i] * springScale
            val free = lo > hi
            if (free && k == 0f) continue
            val nx = ra[i * 3]; val ny = ra[i * 3 + 1]; val nz = ra[i * 3 + 2]
            val diff = nx * dx + ny * dy + nz * dz
            // rA × n, rB × n
            val cax = ray * nz - raz * ny; val cay = raz * nx - rax * nz; val caz = rax * ny - ray * nx
            val cbx = rby * nz - rbz * ny; val cby = rbz * nx - rbx * nz; val cbz = rbx * ny - rby * nx
            var limited = false
            if (!free) {
                if (lo == hi) {
                    addRow(jt.a, jt.b, -nx, -ny, -nz, -cax, -cay, -caz, nx, ny, nz, cbx, cby, cbz, -erp * (diff - lo), -INF, INF)
                    limited = true
                } else if (diff < lo) {
                    addRow(jt.a, jt.b, -nx, -ny, -nz, -cax, -cay, -caz, nx, ny, nz, cbx, cby, cbz, -erp * (diff - lo), 0f, INF)
                    limited = true
                } else if (diff > hi) {
                    addRow(jt.a, jt.b, -nx, -ny, -nz, -cax, -cay, -caz, nx, ny, nz, cbx, cby, cbz, -erp * (diff - hi), -INF, 0f)
                    limited = true
                }
            }
            if (!limited && k != 0f) {
                // 弹簧：等价于 Bullet 的"电机"——目标速度很大，但冲量被限制在 |k·Δ|·dt
                val force = diff * k
                val maxImpulse = abs(force) * h
                if (maxImpulse > 0f) {
                    val target = -force * invH / iterations
                    addRow(jt.a, jt.b, -nx, -ny, -nz, -cax, -cay, -caz, nx, ny, nz, cbx, cby, cbz, target, -maxImpulse, maxImpulse)
                }
            }
        }

        // ---- 角度 3 轴 ----
        // M = RAᵀ · RB；按 Bullet 的 matrixToEulerXYZ 取角（度量的是 A 相对 B 的转动）
        val m00 = ra[0] * rb[9] + ra[1] * rb[10] + ra[2] * rb[11]
        val m10 = ra[3] * rb[9] + ra[4] * rb[10] + ra[5] * rb[11]
        val m20 = ra[6] * rb[9] + ra[7] * rb[10] + ra[8] * rb[11]
        val m01 = ra[0] * rb[12] + ra[1] * rb[13] + ra[2] * rb[14]
        val m11 = ra[3] * rb[12] + ra[4] * rb[13] + ra[5] * rb[14]
        val m21 = ra[6] * rb[12] + ra[7] * rb[13] + ra[8] * rb[14]
        val m22 = ra[6] * rb[15] + ra[7] * rb[16] + ra[8] * rb[17]
        val ang = t
        if (m20 < 1f) {
            if (m20 > -1f) {
                ang[0] = atan2(-m21, m22)
                ang[1] = asin(m20)
                ang[2] = atan2(-m10, m00)
            } else {
                ang[0] = -atan2(m01, m11); ang[1] = -HALF_PI; ang[2] = 0f
            }
        } else {
            ang[0] = atan2(m01, m11); ang[1] = HALF_PI; ang[2] = 0f
        }
        // 轴：axis0 = B 的 X，axis2 = A 的 Z
        val b0x = rb[9]; val b0y = rb[10]; val b0z = rb[11]
        val a2x = ra[6]; val a2y = ra[7]; val a2z = ra[8]
        var y1x = a2y * b0z - a2z * b0y; var y1y = a2z * b0x - a2x * b0z; var y1z = a2x * b0y - a2y * b0x
        var l = sqrt(y1x * y1x + y1y * y1y + y1z * y1z)
        if (l < 1e-6f) return
        y1x /= l; y1y /= l; y1z /= l
        var x0x = y1y * a2z - y1z * a2y; var x0y = y1z * a2x - y1x * a2z; var x0z = y1x * a2y - y1y * a2x
        l = sqrt(x0x * x0x + x0y * x0y + x0z * x0z)
        if (l > 1e-6f) { x0x /= l; x0y /= l; x0z /= l }
        var z2x = b0y * y1z - b0z * y1y; var z2y = b0z * y1x - b0x * y1z; var z2z = b0x * y1y - b0y * y1x
        l = sqrt(z2x * z2x + z2y * z2y + z2z * z2z)
        if (l > 1e-6f) { z2x /= l; z2y /= l; z2z /= l }

        for (i in 0 until 3) {
            val lo = jt.angLo[i] * limitScale
            val hi = jt.angHi[i] * limitScale
            val k = jt.angK[i] * springScale
            val free = lo > hi
            if (free && k == 0f) continue
            val ax: Float; val ay: Float; val az: Float
            when (i) {
                0 -> { ax = x0x; ay = x0y; az = x0z }
                1 -> { ax = y1x; ay = y1y; az = y1z }
                else -> { ax = z2x; ay = z2y; az = z2z }
            }
            var angle = ang[i]
            var limited = false
            if (!free) {
                if (lo == hi) {
                    addRow(jt.a, jt.b, 0f, 0f, 0f, ax, ay, az, 0f, 0f, 0f, -ax, -ay, -az, -erp * wrapPi(angle - lo), -INF, INF)
                    limited = true
                } else {
                    // 与 btAdjustAngleToLimits 相同：越过 ±π 时选离限位更近的那个等价角
                    if (angle < lo) {
                        val dl = abs(wrapPi(lo - angle)); val dh = abs(wrapPi(hi - angle))
                        if (dl >= dh) angle += TWO_PI
                    } else if (angle > hi) {
                        val dh = abs(wrapPi(angle - hi)); val dl = abs(wrapPi(angle - lo))
                        if (dl < dh) angle -= TWO_PI
                    }
                    if (angle < lo) {
                        addRow(jt.a, jt.b, 0f, 0f, 0f, ax, ay, az, 0f, 0f, 0f, -ax, -ay, -az, -erp * (angle - lo), 0f, INF)
                        limited = true
                    } else if (angle > hi) {
                        addRow(jt.a, jt.b, 0f, 0f, 0f, ax, ay, az, 0f, 0f, 0f, -ax, -ay, -az, -erp * (angle - hi), -INF, 0f)
                        limited = true
                    }
                }
            }
            if (!limited && k != 0f) {
                val force = -angle * k
                val maxImpulse = abs(force) * h
                if (maxImpulse > 0f) {
                    val target = force * invH / iterations
                    addRow(jt.a, jt.b, 0f, 0f, 0f, ax, ay, az, 0f, 0f, 0f, -ax, -ay, -az, target, -maxImpulse, maxImpulse)
                }
            }
        }
    }

    private fun wrapPi(a: Float): Float {
        var r = a % TWO_PI
        if (r > PI) r -= TWO_PI else if (r < -PI) r += TWO_PI
        return r
    }

    /** out = a · b（3x3，列主序）。 */
    private fun mul3(a: FloatArray, ao: Int, b: FloatArray, bo: Int, out: FloatArray, oo: Int) {
        for (c in 0 until 3) {
            val b0 = b[bo + c * 3]; val b1 = b[bo + c * 3 + 1]; val b2 = b[bo + c * 3 + 2]
            out[oo + c * 3] = a[ao] * b0 + a[ao + 3] * b1 + a[ao + 6] * b2
            out[oo + c * 3 + 1] = a[ao + 1] * b0 + a[ao + 4] * b1 + a[ao + 7] * b2
            out[oo + c * 3 + 2] = a[ao + 2] * b0 + a[ao + 5] * b1 + a[ao + 8] * b2
        }
    }

    // ------------------------------------------------------------------ 碰撞

    private fun collide(invH: Float) {
        val bs = bodies
        val ps = pairs
        for (p in 0 until pairCount) {
            val ia = ps[p * 2]
            val ib = ps[p * 2 + 1]
            val a = bs[ia]
            val b = bs[ib]
            if (a.maxX < b.minX || b.maxX < a.minX || a.maxY < b.minY || b.maxY < a.minY || a.maxZ < b.minZ || b.maxZ < a.minZ) continue
            val aBox = a.shape == SHAPE_BOX
            val bBox = b.shape == SHAPE_BOX
            if (!aBox && !bBox) {
                if (roundRound(a, b)) addContact(ia, ib, invH)
            } else if (aBox && bBox) {
                if (boxBox(a, b)) addContact(ia, ib, invH)
            } else if (aBox) {
                // 盒(A) vs 球/胶囊(B)：roundBox 给出的法线是从盒指向圆体，正好是 A→B
                if (roundBox(b, a)) addContact(ia, ib, invH)
            } else {
                if (roundBox(a, b)) {
                    t[C_NX] = -t[C_NX]; t[C_NY] = -t[C_NY]; t[C_NZ] = -t[C_NZ]
                    addContact(ia, ib, invH)
                }
            }
        }
        if (groundEnabled && groundIndex >= 0) {
            for (i in bs.indices) {
                val b = bs[i]
                if (b.kinematic) continue
                groundContacts(i, b, invH)
            }
        }
        if (statics.isNotEmpty() && groundIndex >= 0) {
            for (i in bs.indices) {
                val b = bs[i]
                if (b.kinematic) continue
                staticContacts(i, b, invH)
            }
        }
    }

    /** 和不会动的碰撞体的接触：在刚体上取几个点（盒子的八个角和中心、胶囊的两头和中间、球心）去问。 */
    private fun staticContacts(ib: Int, b: Body, invH: Float) {
        val m = staticMatrix
        val s = if (m != null) sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]) else 1f
        if (s < 1e-6f) return
        val r = b.r
        val count = when (b.shape) { SHAPE_BOX -> 9; SHAPE_CAPSULE -> if (b.halfHeight > 0f) 3 else 1; else -> 1 }
        for (k in 0 until count) {
            var lx = b.px; var ly = b.py; var lz = b.pz
            var rad = b.radius
            if (b.shape == SHAPE_BOX) {
                if (k < 8) {
                    val sx = if (k and 1 == 0) -b.hx else b.hx
                    val sy = if (k and 2 == 0) -b.hy else b.hy
                    val sz = if (k and 4 == 0) -b.hz else b.hz
                    lx += r[0] * sx + r[3] * sy + r[6] * sz; ly += r[1] * sx + r[4] * sy + r[7] * sz; lz += r[2] * sx + r[5] * sy + r[8] * sz
                    rad = 0f
                } else rad = minOf(b.hx, b.hy, b.hz)
            } else if (count == 3 && k < 2) {
                val h = if (k == 0) -b.halfHeight else b.halfHeight
                lx += r[3] * h; ly += r[4] * h; lz += r[5] * h
            }
            var wx = lx; var wy = ly; var wz = lz
            if (m != null) {
                wx = m[0] * lx + m[4] * ly + m[8] * lz + m[12]
                wy = m[1] * lx + m[5] * ly + m[9] * lz + m[13]
                wz = m[2] * lx + m[6] * ly + m[10] * lz + m[14]
            }
            for (st in statics) {
                if (st.ownerId == staticSkip) continue
                if (!st.contact(wx, wy, wz, rad * s, sc)) continue
                var nx = sc[0]; var ny = sc[1]; var nz = sc[2]
                if (m != null) {
                    val ax = (m[0] * nx + m[1] * ny + m[2] * nz) / s
                    val ay = (m[4] * nx + m[5] * ny + m[6] * nz) / s
                    val az = (m[8] * nx + m[9] * ny + m[10] * nz) / s
                    nx = ax; ny = ay; nz = az
                }
                t[C_NX] = nx; t[C_NY] = ny; t[C_NZ] = nz
                t[C_PX] = lx - nx * rad; t[C_PY] = ly - ny * rad; t[C_PZ] = lz - nz * rad
                t[C_DEPTH] = sc[3] / s
                addContact(groundIndex, ib, invH)
            }
        }
    }

    /** 根据 t[C_*] 里的接触信息（法线 A→B、接触点、穿透深度）建立 1 条法向行 + 2 条摩擦行。 */
    private fun addContact(ia: Int, ib: Int, invH: Float) {
        val a = bodies[ia]
        val b = bodies[ib]
        val nx = t[C_NX]; val ny = t[C_NY]; val nz = t[C_NZ]
        val px = t[C_PX]; val py = t[C_PY]; val pz = t[C_PZ]
        val depth = t[C_DEPTH]
        val rax = px - a.px; val ray = py - a.py; val raz = pz - a.pz
        val rbx = px - b.px; val rby = py - b.py; val rbz = pz - b.pz
        // 法向相对速度（B 相对 A）
        val vrx = (b.vx + b.wy * rbz - b.wz * rby) - (a.vx + a.wy * raz - a.wz * ray)
        val vry = (b.vy + b.wz * rbx - b.wx * rbz) - (a.vy + a.wz * rax - a.wx * raz)
        val vrz = (b.vz + b.wx * rby - b.wy * rbx) - (a.vz + a.wx * ray - a.wy * rax)
        val vn = vrx * nx + vry * ny + vrz * nz
        var bias = CONTACT_ERP * maxOf(0f, depth - CONTACT_SLOP) * invH
        if (bias > MAX_CONTACT_BIAS) bias = MAX_CONTACT_BIAS
        val rest = a.restitution * b.restitution
        if (rest > 0f && vn < -1f) bias = maxOf(bias, -rest * vn)
        var cax = ray * nz - raz * ny; var cay = raz * nx - rax * nz; var caz = rax * ny - ray * nx
        var cbx = rby * nz - rbz * ny; var cby = rbz * nx - rbx * nz; var cbz = rbx * ny - rby * nx
        val normalRow = addRow(ia, ib, -nx, -ny, -nz, -cax, -cay, -caz, nx, ny, nz, cbx, cby, cbz, bias, 0f, INF)
        if (normalRow < 0) return
        val mu = a.friction * b.friction
        if (mu <= 0f) return
        // 两个切向
        var t1x: Float; var t1y: Float; var t1z: Float
        if (abs(nx) > 0.70710678f) { t1x = ny; t1y = -nx; t1z = 0f } else { t1x = 0f; t1y = nz; t1z = -ny }
        val l = sqrt(t1x * t1x + t1y * t1y + t1z * t1z)
        t1x /= l; t1y /= l; t1z /= l
        val t2x = ny * t1z - nz * t1y; val t2y = nz * t1x - nx * t1z; val t2z = nx * t1y - ny * t1x
        cax = ray * t1z - raz * t1y; cay = raz * t1x - rax * t1z; caz = rax * t1y - ray * t1x
        cbx = rby * t1z - rbz * t1y; cby = rbz * t1x - rbx * t1z; cbz = rbx * t1y - rby * t1x
        var row = addRow(ia, ib, -t1x, -t1y, -t1z, -cax, -cay, -caz, t1x, t1y, t1z, cbx, cby, cbz, 0f, 0f, 0f)
        if (row >= 0) { rowFric[row] = normalRow; rowMu[row] = mu }
        cax = ray * t2z - raz * t2y; cay = raz * t2x - rax * t2z; caz = rax * t2y - ray * t2x
        cbx = rby * t2z - rbz * t2y; cby = rbz * t2x - rbx * t2z; cbz = rbx * t2y - rby * t2x
        row = addRow(ia, ib, -t2x, -t2y, -t2z, -cax, -cay, -caz, t2x, t2y, t2z, cbx, cby, cbz, 0f, 0f, 0f)
        if (row >= 0) { rowFric[row] = normalRow; rowMu[row] = mu }
    }

    /** 球 / 胶囊 vs 球 / 胶囊：两条线段的最近点。 */
    private fun roundRound(a: Body, b: Body): Boolean {
        val ha = if (a.shape == SHAPE_CAPSULE) a.halfHeight else 0f
        val hb = if (b.shape == SHAPE_CAPSULE) b.halfHeight else 0f
        // 线段端点：中心 ± Y 轴 * 半高
        val p1x = a.px - a.r[3] * ha; val p1y = a.py - a.r[4] * ha; val p1z = a.pz - a.r[5] * ha
        val d1x = a.r[3] * ha * 2f; val d1y = a.r[4] * ha * 2f; val d1z = a.r[5] * ha * 2f
        val p2x = b.px - b.r[3] * hb; val p2y = b.py - b.r[4] * hb; val p2z = b.pz - b.r[5] * hb
        val d2x = b.r[3] * hb * 2f; val d2y = b.r[4] * hb * 2f; val d2z = b.r[5] * hb * 2f
        val rx = p1x - p2x; val ry = p1y - p2y; val rz = p1z - p2z
        val aa = d1x * d1x + d1y * d1y + d1z * d1z
        val ee = d2x * d2x + d2y * d2y + d2z * d2z
        val f = d2x * rx + d2y * ry + d2z * rz
        var s: Float
        var u: Float
        if (aa <= 1e-9f && ee <= 1e-9f) {
            s = 0f; u = 0f
        } else if (aa <= 1e-9f) {
            s = 0f; u = (f / ee).coerceIn(0f, 1f)
        } else {
            val c = d1x * rx + d1y * ry + d1z * rz
            if (ee <= 1e-9f) {
                u = 0f; s = (-c / aa).coerceIn(0f, 1f)
            } else {
                val bb = d1x * d2x + d1y * d2y + d1z * d2z
                val denom = aa * ee - bb * bb
                s = if (denom > 1e-9f) ((bb * f - c * ee) / denom).coerceIn(0f, 1f) else 0.5f
                u = (bb * s + f) / ee
                if (u < 0f) { u = 0f; s = (-c / aa).coerceIn(0f, 1f) } else if (u > 1f) { u = 1f; s = ((bb - c) / aa).coerceIn(0f, 1f) }
            }
        }
        val c1x = p1x + d1x * s; val c1y = p1y + d1y * s; val c1z = p1z + d1z * s
        val c2x = p2x + d2x * u; val c2y = p2y + d2y * u; val c2z = p2z + d2z * u
        var nx = c2x - c1x; var ny = c2y - c1y; var nz = c2z - c1z
        val d2 = nx * nx + ny * ny + nz * nz
        val rsum = a.radius + b.radius
        if (d2 >= rsum * rsum) return false
        val d = sqrt(d2)
        if (d > 1e-6f) { nx /= d; ny /= d; nz /= d } else { nx = 0f; ny = 1f; nz = 0f }
        val depth = rsum - d
        t[C_NX] = nx; t[C_NY] = ny; t[C_NZ] = nz
        val k = a.radius - depth * 0.5f
        t[C_PX] = c1x + nx * k; t[C_PY] = c1y + ny * k; t[C_PZ] = c1z + nz * k
        t[C_DEPTH] = depth
        return true
    }

    /** 球 / 胶囊(s) vs 盒(box)。法线从盒指向圆体。 */
    private fun roundBox(s: Body, box: Body): Boolean {
        val br = box.r
        val hh = if (s.shape == SHAPE_CAPSULE) s.halfHeight else 0f
        // 线段两端变换到盒的局部空间
        val cx = s.px - box.px; val cy = s.py - box.py; val cz = s.pz - box.pz
        val lcx = br[0] * cx + br[1] * cy + br[2] * cz
        val lcy = br[3] * cx + br[4] * cy + br[5] * cz
        val lcz = br[6] * cx + br[7] * cy + br[8] * cz
        val ax = s.r[3] * hh; val ay = s.r[4] * hh; val az = s.r[5] * hh
        val lax = br[0] * ax + br[1] * ay + br[2] * az
        val lay = br[3] * ax + br[4] * ay + br[5] * az
        val laz = br[6] * ax + br[7] * ay + br[8] * az
        val p0x = lcx - lax; val p0y = lcy - lay; val p0z = lcz - laz
        val dx = lax * 2f; val dy = lay * 2f; val dz = laz * 2f
        val dd = dx * dx + dy * dy + dz * dz
        var u = 0.5f
        var spx = lcx; var spy = lcy; var spz = lcz
        var bpx = 0f; var bpy = 0f; var bpz = 0f
        for (it in 0 until 5) {
            spx = p0x + dx * u; spy = p0y + dy * u; spz = p0z + dz * u
            bpx = spx.coerceIn(-box.hx, box.hx); bpy = spy.coerceIn(-box.hy, box.hy); bpz = spz.coerceIn(-box.hz, box.hz)
            if (dd < 1e-9f) break
            val nu = (((bpx - p0x) * dx + (bpy - p0y) * dy + (bpz - p0z) * dz) / dd).coerceIn(0f, 1f)
            if (abs(nu - u) < 1e-4f) { u = nu; break }
            u = nu
        }
        spx = p0x + dx * u; spy = p0y + dy * u; spz = p0z + dz * u
        bpx = spx.coerceIn(-box.hx, box.hx); bpy = spy.coerceIn(-box.hy, box.hy); bpz = spz.coerceIn(-box.hz, box.hz)
        var nx = spx - bpx; var ny = spy - bpy; var nz = spz - bpz
        val d2 = nx * nx + ny * ny + nz * nz
        val depth: Float
        if (d2 > 1e-10f) {
            val d = sqrt(d2)
            if (d >= s.radius) return false
            nx /= d; ny /= d; nz /= d
            depth = s.radius - d
        } else {
            // 圆体轴线已经在盒子内部：沿穿透最浅的面推出去
            val ex = box.hx - abs(spx); val ey = box.hy - abs(spy); val ez = box.hz - abs(spz)
            if (ex <= ey && ex <= ez) { nx = if (spx >= 0f) 1f else -1f; ny = 0f; nz = 0f; depth = ex + s.radius }
            else if (ey <= ez) { nx = 0f; ny = if (spy >= 0f) 1f else -1f; nz = 0f; depth = ey + s.radius }
            else { nx = 0f; ny = 0f; nz = if (spz >= 0f) 1f else -1f; depth = ez + s.radius }
            bpx = spx; bpy = spy; bpz = spz
        }
        // 回到世界空间
        t[C_NX] = br[0] * nx + br[3] * ny + br[6] * nz
        t[C_NY] = br[1] * nx + br[4] * ny + br[7] * nz
        t[C_NZ] = br[2] * nx + br[5] * ny + br[8] * nz
        t[C_PX] = box.px + br[0] * bpx + br[3] * bpy + br[6] * bpz
        t[C_PY] = box.py + br[1] * bpx + br[4] * bpy + br[7] * bpz
        t[C_PZ] = box.pz + br[2] * bpx + br[5] * bpy + br[8] * bpz
        t[C_DEPTH] = depth
        return true
    }

    /** 盒 vs 盒：分离轴定理，取穿透最浅的轴，生成一个接触点。法线 A→B。 */
    private fun boxBox(a: Body, b: Body): Boolean {
        val ar = a.r
        val br = b.r
        val ha = t; val hb = t
        ha[20] = a.hx; ha[21] = a.hy; ha[22] = a.hz
        hb[24] = b.hx; hb[25] = b.hy; hb[26] = b.hz
        val dx = b.px - a.px; val dy = b.py - a.py; val dz = b.pz - a.pz
        var best = Float.MAX_VALUE
        var bnx = 0f; var bny = 0f; var bnz = 0f
        // 15 条候选轴
        for (k in 0 until 15) {
            var nx: Float; var ny: Float; var nz: Float
            if (k < 3) { nx = ar[k * 3]; ny = ar[k * 3 + 1]; nz = ar[k * 3 + 2] }
            else if (k < 6) { val c = (k - 3) * 3; nx = br[c]; ny = br[c + 1]; nz = br[c + 2] }
            else {
                val i = (k - 6) / 3 * 3
                val j = (k - 6) % 3 * 3
                nx = ar[i + 1] * br[j + 2] - ar[i + 2] * br[j + 1]
                ny = ar[i + 2] * br[j] - ar[i] * br[j + 2]
                nz = ar[i] * br[j + 1] - ar[i + 1] * br[j]
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                if (l < 1e-4f) continue
                nx /= l; ny /= l; nz /= l
            }
            val ra = abs(ar[0] * nx + ar[1] * ny + ar[2] * nz) * a.hx + abs(ar[3] * nx + ar[4] * ny + ar[5] * nz) * a.hy + abs(ar[6] * nx + ar[7] * ny + ar[8] * nz) * a.hz
            val rb = abs(br[0] * nx + br[1] * ny + br[2] * nz) * b.hx + abs(br[3] * nx + br[4] * ny + br[5] * nz) * b.hy + abs(br[6] * nx + br[7] * ny + br[8] * nz) * b.hz
            val dist = dx * nx + dy * ny + dz * nz
            val overlap = ra + rb - abs(dist)
            if (overlap <= 0f) return false
            // 棱-棱轴略微加一点惩罚，优先选面法线（更稳定）
            val score = if (k >= 6) overlap * 1.05f + 1e-4f else overlap
            if (score < best) {
                best = score
                if (dist < 0f) { bnx = -nx; bny = -ny; bnz = -nz } else { bnx = nx; bny = ny; bnz = nz }
                t[C_DEPTH] = overlap
            }
        }
        // A 上沿 +n 最远的顶点、B 上沿 -n 最远的顶点，取中点作为接触点
        var sax = a.px; var say = a.py; var saz = a.pz
        var sbx = b.px; var sby = b.py; var sbz = b.pz
        for (i in 0 until 3) {
            val c = i * 3
            val ea = ha[20 + i]
            val sa = if (ar[c] * bnx + ar[c + 1] * bny + ar[c + 2] * bnz >= 0f) ea else -ea
            sax += ar[c] * sa; say += ar[c + 1] * sa; saz += ar[c + 2] * sa
            val eb = hb[24 + i]
            val sb = if (br[c] * bnx + br[c + 1] * bny + br[c + 2] * bnz >= 0f) -eb else eb
            sbx += br[c] * sb; sby += br[c + 1] * sb; sbz += br[c + 2] * sb
        }
        t[C_NX] = bnx; t[C_NY] = bny; t[C_NZ] = bnz
        t[C_PX] = (sax + sbx) * 0.5f; t[C_PY] = (say + sby) * 0.5f; t[C_PZ] = (saz + sbz) * 0.5f
        return true
    }

    private fun groundContacts(ib: Int, b: Body, invH: Float) {
        val nx = groundNx; val ny = groundNy; val nz = groundNz
        // AABB 最低点都在地面之上就跳过
        val ex = (b.maxX - b.minX) * 0.5f; val ey = (b.maxY - b.minY) * 0.5f; val ez = (b.maxZ - b.minZ) * 0.5f
        val cdist = nx * (b.minX + ex) + ny * (b.minY + ey) + nz * (b.minZ + ez) - groundD
        if (cdist > abs(nx) * ex + abs(ny) * ey + abs(nz) * ez) return
        val ia = groundBodyIndex()
        when (b.shape) {
            SHAPE_BOX -> {
                val r = b.r
                for (k in 0 until 8) {
                    val sx = if (k and 1 == 0) -b.hx else b.hx
                    val sy = if (k and 2 == 0) -b.hy else b.hy
                    val sz = if (k and 4 == 0) -b.hz else b.hz
                    val x = b.px + r[0] * sx + r[3] * sy + r[6] * sz
                    val y = b.py + r[1] * sx + r[4] * sy + r[7] * sz
                    val z = b.pz + r[2] * sx + r[5] * sy + r[8] * sz
                    val d = nx * x + ny * y + nz * z - groundD
                    if (d < 0f) {
                        t[C_NX] = nx; t[C_NY] = ny; t[C_NZ] = nz
                        t[C_PX] = x; t[C_PY] = y; t[C_PZ] = z; t[C_DEPTH] = -d
                        addContact(ia, ib, invH)
                    }
                }
            }
            else -> {
                val hh = if (b.shape == SHAPE_CAPSULE) b.halfHeight else 0f
                val ends = if (hh > 0f) 2 else 1
                for (k in 0 until ends) {
                    val s = if (ends == 1) 0f else if (k == 0) -hh else hh
                    val x = b.px + b.r[3] * s; val y = b.py + b.r[4] * s; val z = b.pz + b.r[5] * s
                    val d = nx * x + ny * y + nz * z - groundD - b.radius
                    if (d < 0f) {
                        t[C_NX] = nx; t[C_NY] = ny; t[C_NZ] = nz
                        t[C_PX] = x - nx * b.radius; t[C_PY] = y - ny * b.radius; t[C_PZ] = z - nz * b.radius
                        t[C_DEPTH] = -d
                        addContact(ia, ib, invH)
                    }
                }
            }
        }
    }

    private var groundIndex = -1

    /** 地面用一个隐藏的静止刚体表示（finalizeSetup 时放到刚体表末尾）。 */
    private fun groundBodyIndex(): Int = groundIndex

    private val groundBody = Body().also { it.kinematic = true; it.friction = 0.5f; it.restitution = 0f; it.groupBit = 0; it.mask = 0 }

    companion object {
        const val SHAPE_SPHERE = 0
        const val SHAPE_BOX = 1
        const val SHAPE_CAPSULE = 2

        private const val INF = Float.MAX_VALUE
        private const val PI = Math.PI.toFloat()
        private const val TWO_PI = (Math.PI * 2.0).toFloat()
        private const val HALF_PI = (Math.PI * 0.5).toFloat()

        private const val JOINT_ERP = 0.2f
        private const val CONTACT_ERP = 0.2f
        private const val CONTACT_SLOP = 0.02f
        private const val MAX_CONTACT_BIAS = 30f
        private const val MAX_SPEED = 1000f
        private const val MAX_ANGULAR = 200f
        private const val MAX_KINEMATIC_SPEED = 2000f

        private const val C_NX = 40
        private const val C_NY = 41
        private const val C_NZ = 42
        private const val C_PX = 43
        private const val C_PY = 44
        private const val C_PZ = 45
        private const val C_DEPTH = 46
    }
}

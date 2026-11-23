package com.lumi.mmd.engine.physics

import com.lumi.mmd.engine.anim.Skeleton
import com.lumi.mmd.engine.math.Mat4
import com.lumi.mmd.engine.math.Quat
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.RigidBodyData
import kotlin.math.sqrt

/**
 * 把模型的刚体 / Joint 数据接到 [PhysicsWorld] 上，并负责"骨骼 ↔ 刚体"的双向同步：
 *  - 跟随骨骼的刚体（运动学）：每帧取骨骼的全局矩阵作为目标姿态；
 *  - 物理演算的刚体：模拟结束后把结果写回骨骼（"物理+骨骼位置对齐"的刚体只取旋转，位置仍跟骨骼）。
 *
 * 物理世界建立在模型的局部坐标系里，所以整体移动 / 缩放模型不影响模拟。
 */
class ModelPhysics(private val model: ModelData, private val skeleton: Skeleton) {
    val world = PhysicsWorld()
    private val count = model.rigidBodies.size
    private val bodies = arrayOfNulls<PhysicsWorld.Body>(count)
    private val boneOf = IntArray(count)
    private val mode = IntArray(count)
    /** 刚体相对骨骼的偏移：位置(3) + 四元数(4)。 */
    private val offset = FloatArray(count * 7)
    /** 偏移的逆。 */
    private val invOffset = FloatArray(count * 7)
    /** 需要回写骨骼的动力学刚体，按骨骼层级（父在前）排序。 */
    private val writeBack: IntArray

    var enabled = true
    private var needsReset = true
    /** 由物理驱动的骨骼（头发、裙摆……），摆姿势时不让用户去拖它们。 */
    val drivenBones = BooleanArray(skeleton.count)
    private val tq = FloatArray(16)
    private val tm = FloatArray(16)

    val bodyCount: Int get() = count
    val jointCount: Int get() = world.joints.size

    init {
        val q = FloatArray(4)
        for (i in 0 until count) {
            val d = model.rigidBodies[i]
            val b = PhysicsWorld.Body()
            b.shape = when (d.shape) {
                RigidBodyData.SHAPE_BOX -> PhysicsWorld.SHAPE_BOX
                RigidBodyData.SHAPE_CAPSULE -> PhysicsWorld.SHAPE_CAPSULE
                else -> PhysicsWorld.SHAPE_SPHERE
            }
            val sx = maxOf(d.size[0], 1e-3f); val sy = maxOf(d.size[1], 0f); val sz = maxOf(d.size[2], 1e-3f)
            when (b.shape) {
                PhysicsWorld.SHAPE_SPHERE -> b.radius = sx
                PhysicsWorld.SHAPE_CAPSULE -> { b.radius = sx; b.halfHeight = sy * 0.5f }
                else -> { b.hx = sx; b.hy = maxOf(sy, 1e-3f); b.hz = sz }
            }
            b.kinematic = d.mode == RigidBodyData.MODE_STATIC
            if (!b.kinematic) {
                val mass = if (d.mass > 1e-6f) d.mass else 1e-3f
                b.invMass = 1f / mass
                // 惯性张量（与 Bullet 的 calculateLocalInertia 一致）
                val ix: Float; val iy: Float; val iz: Float
                when (b.shape) {
                    PhysicsWorld.SHAPE_SPHERE -> { val e = 0.4f * mass * b.radius * b.radius; ix = e; iy = e; iz = e }
                    else -> {
                        val lx: Float; val ly: Float; val lz: Float
                        if (b.shape == PhysicsWorld.SHAPE_CAPSULE) { lx = 2f * b.radius; ly = 2f * (b.radius + b.halfHeight); lz = 2f * b.radius }
                        else { lx = 2f * b.hx; ly = 2f * b.hy; lz = 2f * b.hz }
                        ix = mass / 12f * (ly * ly + lz * lz)
                        iy = mass / 12f * (lx * lx + lz * lz)
                        iz = mass / 12f * (lx * lx + ly * ly)
                    }
                }
                b.invIx = if (ix > 1e-12f) 1f / ix else 0f
                b.invIy = if (iy > 1e-12f) 1f / iy else 0f
                b.invIz = if (iz > 1e-12f) 1f / iz else 0f
            }
            b.linDamp = d.linearDamping
            b.angDamp = d.angularDamping
            b.restitution = d.restitution
            b.friction = d.friction
            b.groupBit = 1 shl (d.group and 15)
            b.mask = d.collisionMask and 0xFFFF
            Quat.fromEulerYXZ(q, 0, d.rotation[0], d.rotation[1], d.rotation[2])
            b.setPose(d.position[0], d.position[1], d.position[2], q[0], q[1], q[2], q[3])
            bodies[i] = b
            world.bodies.add(b)
            boneOf[i] = d.bone
            mode[i] = d.mode

            // offset = boneBind⁻¹ · bodyBind = T(pos - bonePos) · R
            val o = i * 7
            if (d.bone >= 0) {
                val bp = model.bones[d.bone].position
                offset[o] = d.position[0] - bp[0]; offset[o + 1] = d.position[1] - bp[1]; offset[o + 2] = d.position[2] - bp[2]
            } else {
                offset[o] = d.position[0]; offset[o + 1] = d.position[1]; offset[o + 2] = d.position[2]
            }
            offset[o + 3] = q[0]; offset[o + 4] = q[1]; offset[o + 5] = q[2]; offset[o + 6] = q[3]
            // 逆：R⁻¹ 与 -R⁻¹·t
            Quat.conjugate(invOffset, o + 3, offset, o + 3)
            Quat.rotate(invOffset, o + 3, -offset[o], -offset[o + 1], -offset[o + 2], invOffset, o)
        }

        val jq = FloatArray(4)
        val jm = FloatArray(9)
        for (d in model.joints) {
            val a = bodies[d.bodyA] ?: continue
            val b = bodies[d.bodyB] ?: continue
            val j = PhysicsWorld.Joint()
            j.a = d.bodyA; j.b = d.bodyB
            Quat.fromEulerYXZ(jq, 0, d.rotation[0], d.rotation[1], d.rotation[2])
            quatToMat3(jq[0], jq[1], jq[2], jq[3], jm)
            world.updateRotation(a); world.updateRotation(b)
            relativeFrame(a, d.position, jm, j.posA, j.rotA)
            relativeFrame(b, d.position, jm, j.posB, j.rotB)
            for (k in 0 until 3) {
                j.linLo[k] = d.linearLower[k]; j.linHi[k] = d.linearUpper[k]
                j.angLo[k] = d.angularLower[k]; j.angHi[k] = d.angularUpper[k]
                j.linK[k] = d.linearSpring[k]; j.angK[k] = d.angularSpring[k]
            }
            world.joints.add(j)
        }
        world.finalizeSetup()

        for (i in 0 until count) if (mode[i] != RigidBodyData.MODE_STATIC && boneOf[i] >= 0) drivenBones[boneOf[i]] = true
        writeBack = (0 until count)
            .filter { mode[it] != RigidBodyData.MODE_STATIC && boneOf[it] >= 0 }
            .sortedBy { depthOf(boneOf[it]) }
            .toIntArray()
    }

    private fun depthOf(bone: Int): Int {
        var d = 0
        var p = skeleton.parent[bone]
        while (p >= 0) { d++; p = skeleton.parent[p] }
        return d
    }

    private fun quatToMat3(x: Float, y: Float, z: Float, w: Float, m: FloatArray) {
        val x2 = x + x; val y2 = y + y; val z2 = z + z
        val xx = x * x2; val xy = x * y2; val xz = x * z2
        val yy = y * y2; val yz = y * z2; val zz = z * z2
        val wx = w * x2; val wy = w * y2; val wz = w * z2
        m[0] = 1f - (yy + zz); m[1] = xy + wz; m[2] = xz - wy
        m[3] = xy - wz; m[4] = 1f - (xx + zz); m[5] = yz + wx
        m[6] = xz + wy; m[7] = yz - wx; m[8] = 1f - (xx + yy)
    }

    /** 把世界（模型）空间里的关节坐标系换算到刚体的局部空间。 */
    private fun relativeFrame(b: PhysicsWorld.Body, pos: FloatArray, rot: FloatArray, outPos: FloatArray, outRot: FloatArray) {
        val r = b.r
        val dx = pos[0] - b.px; val dy = pos[1] - b.py; val dz = pos[2] - b.pz
        outPos[0] = r[0] * dx + r[1] * dy + r[2] * dz
        outPos[1] = r[3] * dx + r[4] * dy + r[5] * dz
        outPos[2] = r[6] * dx + r[7] * dy + r[8] * dz
        // outRot = Rᵀ · rot
        for (c in 0 until 3) {
            val x = rot[c * 3]; val y = rot[c * 3 + 1]; val z = rot[c * 3 + 2]
            outRot[c * 3] = r[0] * x + r[1] * y + r[2] * z
            outRot[c * 3 + 1] = r[3] * x + r[4] * y + r[5] * z
            outRot[c * 3 + 2] = r[6] * x + r[7] * y + r[8] * z
        }
    }

    /** 下一次 update 时把所有刚体瞬移到骨骼当前的位置并清零速度（跳帧、换动作、重新开启物理时调用）。 */
    fun requestReset() {
        needsReset = true
    }

    /** 骨骼全局矩阵 × 偏移 → 刚体姿态，写到 tq[0..6]（位置 + 四元数）。 */
    private fun poseFromBone(i: Int) {
        val o = i * 7
        val bone = boneOf[i]
        if (bone < 0) {
            System.arraycopy(offset, o, tq, 0, 7)
            return
        }
        val g = skeleton.global
        val go = bone * 16
        Mat4.transformPoint(g, go, offset[o], offset[o + 1], offset[o + 2], tq, 0)
        Mat4.toQuat(g, go, tq, 8)
        Quat.mul(tq, 3, tq, 8, offset, o + 3)
    }

    /**
     * 在"物理前"骨骼更新之后调用：同步运动学刚体 → 模拟 → 把结果写回骨骼。
     * @param dt 本帧经过的时间（秒）
     */
    fun update(dt: Float) {
        if (count == 0) return
        if (!enabled) { needsReset = true; return }
        if (needsReset) {
            needsReset = false
            for (i in 0 until count) {
                val b = bodies[i]!!
                poseFromBone(i)
                b.setPose(tq[0], tq[1], tq[2], tq[3], tq[4], tq[5], tq[6])
            }
            world.resetVelocities()
        } else {
            for (i in 0 until count) {
                if (mode[i] != RigidBodyData.MODE_STATIC) continue
                val b = bodies[i]!!
                poseFromBone(i)
                world.setKinematicTarget(b, tq[0], tq[1], tq[2], tq[3], tq[4], tq[5], tq[6])
            }
            world.step(dt)
        }
        writeBackToBones()
    }

    private fun writeBackToBones() {
        val g = skeleton.global
        for (i in writeBackDyn ?: writeBack) {
            val b = bodies[i]!!
            val bone = boneOf[i]
            val o = i * 7
            val go = bone * 16
            // boneGlobal = bodyWorld · offset⁻¹
            tq[0] = b.qx; tq[1] = b.qy; tq[2] = b.qz; tq[3] = b.qw
            Quat.mul(tq, 4, tq, 0, invOffset, o + 3)
            Quat.rotate(tq, 0, invOffset[o], invOffset[o + 1], invOffset[o + 2], tq, 8)
            val nx = tq[4]; val ny = tq[5]; val nz = tq[6]; val nw = tq[7]
            val l = sqrt(nx * nx + ny * ny + nz * nz + nw * nw)
            if (l < 1e-6f || l != l) continue
            if (mode[i] == RigidBodyData.MODE_DYNAMIC_BONE) {
                // 位置仍取骨骼（动画）的位置，只用物理的旋转；随后把刚体的位置对齐回骨骼
                val px = g[go + 12]; val py = g[go + 13]; val pz = g[go + 14]
                Mat4.setTR(g, go, px, py, pz, nx / l, ny / l, nz / l, nw / l)
                Mat4.transformPoint(g, go, offset[o], offset[o + 1], offset[o + 2], tq, 12)
                b.px = tq[12]; b.py = tq[13]; b.pz = tq[14]
            } else {
                Mat4.setTR(g, go, b.px + tq[8], b.py + tq[9], b.pz + tq[10], nx / l, ny / l, nz / l, nw / l)
            }
            skeleton.recomputeLocalFromGlobal(bone)
            skeleton.updateSubtree(bone)
        }
    }

    /** 让模拟空转一段时间，使头发 / 裙子从初始姿势自然垂落（加载模型或重置后调用）。 */
    fun settle(seconds: Float) {
        if (count == 0 || !enabled) return
        var t = 0f
        val h = 1f / 60f
        update(h)
        while (t < seconds) { update(h); t += h }
    }

    fun setGravity(x: Float, y: Float, z: Float) {
        world.gravityX = x; world.gravityY = y; world.gravityZ = z
    }

    // ------------------------------------------------------------------ 调参 / 脚本

    private val baseInvMass = FloatArray(count) { bodies[it]!!.invMass }
    private val baseInvI = FloatArray(count * 3) { val b = bodies[it / 3]!!; when (it % 3) { 0 -> b.invIx; 1 -> b.invIy; else -> b.invIz } }
    private val baseRadius = FloatArray(count * 4) { val b = bodies[it / 4]!!; when (it % 4) { 0 -> b.radius; 1 -> b.hx; 2 -> b.hy; else -> b.hz } }
    private val baseHalf = FloatArray(count) { bodies[it]!!.halfHeight }
    private var massScale = 1f
    private var sizeScale = 1f

    /** 把一组参数套到这个模型的物理上（全局参数 × 这个模型自己的倍数，由调用方先乘好）。 */
    fun applyTuning(t: PhysicsTuning) {
        world.gravityX = t.gravityX; world.gravityY = -98f * t.gravity; world.gravityZ = t.gravityZ
        world.windX = t.windX; world.windY = t.windY; world.windZ = t.windZ
        world.windTurbulence = t.turbulence
        world.linDampScale = t.damping; world.angDampScale = t.angularDamping
        world.springScale = t.stiffness; world.limitScale = t.limits
        world.iterations = t.iterations.coerceIn(1, 60)
        world.fixedStep = 1f / t.hz.coerceIn(30, 240)
        world.maxSubSteps = t.maxSubSteps.coerceIn(1, 12)
        world.groundEnabled = t.ground
        if (t.mass != massScale) {
            massScale = t.mass
            val k = 1f / maxOf(massScale, 1e-3f)
            for (i in 0 until count) {
                val b = bodies[i]!!
                if (b.kinematic && mode[i] == RigidBodyData.MODE_STATIC) continue
                b.invMass = baseInvMass[i] * k
                b.invIx = baseInvI[i * 3] * k; b.invIy = baseInvI[i * 3 + 1] * k; b.invIz = baseInvI[i * 3 + 2] * k
            }
        }
        if (t.colliderSize != sizeScale) {
            sizeScale = t.colliderSize
            for (i in 0 until count) {
                val b = bodies[i]!!
                b.radius = baseRadius[i * 4] * sizeScale; b.hx = baseRadius[i * 4 + 1] * sizeScale
                b.hy = baseRadius[i * 4 + 2] * sizeScale; b.hz = baseRadius[i * 4 + 3] * sizeScale
                b.halfHeight = baseHalf[i] * sizeScale
            }
        }
    }

    /**
     * 这一帧的环境：地板在哪、场景里有哪些带「碰撞」属性的模型。
     * 刚体在模型自己的坐标里，地板和别的模型在世界里，所以要带上这个模型的世界矩阵。在 [applyTuning] 之后调用。
     */
    fun setEnvironment(worldMatrix: FloatArray, floorOn: Boolean, floorHeight: Float, statics: List<PhysicsWorld.StaticShape>, selfId: Int) {
        val m = worldMatrix
        val s = kotlin.math.sqrt(m[1] * m[1] + m[5] * m[5] + m[9] * m[9])
        if (s < 1e-6f || !floorOn) world.groundEnabled = false
        else {
            // 世界里的 y = (m1, m5, m9)·p + m13；让它 ≥ 地板高度
            world.groundNx = m[1] / s; world.groundNy = m[5] / s; world.groundNz = m[9] / s
            world.groundD = (floorHeight - m[13]) / s
        }
        world.statics = statics
        world.staticMatrix = m
        world.staticSkip = selfId
    }

    fun bodyName(i: Int): String = model.rigidBodies[i].name
    fun bodyBone(i: Int): Int = boneOf[i]
    fun bodyMode(i: Int): Int = mode[i]
    fun body(i: Int): PhysicsWorld.Body = bodies[i]!!

    /**
     * 改一个刚体的模式：0 = 跟着骨骼走（不参与物理），1 = 物理演算，2 = 物理演算 + 位置对齐骨骼。
     * 脚本要自己摆裙子骨骼时，先把那些刚体改成 0。
     */
    fun setBodyMode(i: Int, newMode: Int) {
        if (i !in 0 until count || newMode !in 0..2 || mode[i] == newMode) return
        val b = bodies[i]!!
        val wasStatic = mode[i] == RigidBodyData.MODE_STATIC
        mode[i] = newMode
        b.kinematic = newMode == RigidBodyData.MODE_STATIC
        if (b.kinematic) {
            b.vx = 0f; b.vy = 0f; b.vz = 0f; b.wx = 0f; b.wy = 0f; b.wz = 0f
        } else if (wasStatic && b.invMass == 0f) {
            // 原来就是跟骨骼的刚体没有质量数据：给一个默认值
            b.invMass = 1f; b.invIx = 2.5f; b.invIy = 2.5f; b.invIz = 2.5f
        }
        rebuildDriven()
    }

    private var writeBackDyn: IntArray? = null

    private fun rebuildDriven() {
        drivenBones.fill(false)
        for (i in 0 until count) if (mode[i] != RigidBodyData.MODE_STATIC && boneOf[i] >= 0) drivenBones[boneOf[i]] = true
        writeBackDyn = (0 until count)
            .filter { mode[it] != RigidBodyData.MODE_STATIC && boneOf[it] >= 0 }
            .sortedBy { depthOf(boneOf[it]) }
            .toIntArray()
    }

    /** 给刚体一个冲量（模型空间，质量已计入）。 */
    fun impulse(i: Int, x: Float, y: Float, z: Float) {
        if (i !in 0 until count) return
        val b = bodies[i]!!
        if (b.kinematic) return
        b.vx += x * b.invMass; b.vy += y * b.invMass; b.vz += z * b.invMass
    }
}

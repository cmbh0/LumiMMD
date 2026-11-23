package com.lumi.mmd.engine.fx

import com.lumi.mmd.engine.physics.PhysicsWorld
import com.lumi.mmd.engine.scene.Scene
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 脚本自己搭的网格：顶点（位置 / 法线 / UV / 颜色）和三角形都由脚本给，每一帧都可以改。
 * 用来画内置特效里没有的任何形状：水面、布、带子、软体、自己算出来的曲面……
 * 可以跟着骨骼走，可以让顶点直接跟着 [SimWorld] 的质点走（布料），也可以整体跟着一个脚本刚体走。
 * 只在渲染线程上用。
 */
class ScriptMesh(val id: Int, val owner: Int) {
    var vertexCount = 0
        private set
    var indexCount = 0
        private set
    var positions = FloatArray(0); private set
    var normals = FloatArray(0); private set
    var uvs = FloatArray(0); private set
    var colors = FloatArray(0); private set
    var indices = IntArray(0); private set
    /** 顶点数据变了就加一；渲染器据此重新上传。 */
    var version = 1
    var indexVersion = 1

    var mode = MODE_TRIANGLES
    val color = floatArrayOf(1f, 1f, 1f, 1f)
    var lit = true
    var additive = false
    var doubleSided = true
    var visible = true
    /** 贴图自己带透明（当半透明来画：不写深度，放在不透明的东西后面画）。 */
    var transparent = false
    /** 高光强度（0 = 没有）和发光（0..1：不受光照的比例）。 */
    var shine = 0.2f
    var glow = 0f
    var pointSize = 6f
    var texture = ""
    /** 每帧自动重算法线（顶点在动的网格用）。 */
    var autoNormals = false

    val anchor = Anchor()
    val pos = FloatArray(3)
    val quat = floatArrayOf(0f, 0f, 0f, 1f)
    val scale = floatArrayOf(1f, 1f, 1f)
    val world = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    var followSim: SimWorld? = null
    var followFirst = 0
    var followBody: PhysicsWorld.Body? = null
    private val tm = FloatArray(16)

    /** 改顶点数（已有的数据保留，新的顶点：法线朝上、白色）。 */
    fun resize(n: Int) {
        val c = n.coerceIn(0, MAX_VERTICES)
        if (c * 3 > positions.size) {
            val cap = maxOf(c, positions.size / 3 * 2, 16)
            positions = positions.copyOf(cap * 3); normals = normals.copyOf(cap * 3); uvs = uvs.copyOf(cap * 2); colors = colors.copyOf(cap * 4)
        }
        for (i in vertexCount until c) {
            positions[i * 3] = 0f; positions[i * 3 + 1] = 0f; positions[i * 3 + 2] = 0f
            normals[i * 3] = 0f; normals[i * 3 + 1] = 1f; normals[i * 3 + 2] = 0f
            uvs[i * 2] = 0f; uvs[i * 2 + 1] = 0f
            colors[i * 4] = 1f; colors[i * 4 + 1] = 1f; colors[i * 4 + 2] = 1f; colors[i * 4 + 3] = 1f
        }
        vertexCount = c
        version++
    }

    /** 设置索引（从 0 起）。越界的三角形会被丢掉。 */
    fun setIndices(src: IntArray, n: Int) {
        val c = n.coerceIn(0, MAX_INDICES)
        if (indices.size < c) indices = IntArray(c)
        var k = 0
        val per = when (mode) { MODE_LINES -> 2; MODE_POINTS -> 1; else -> 3 }
        var i = 0
        while (i + per <= c) {
            var ok = true
            for (j in 0 until per) if (src[i + j] < 0 || src[i + j] >= vertexCount) ok = false
            if (ok) for (j in 0 until per) indices[k++] = src[i + j]
            i += per
        }
        indexCount = k
        indexVersion++
    }

    /** 按三角形重新算每个顶点的法线（面积加权）。 */
    fun recalcNormals() {
        if (mode != MODE_TRIANGLES) return
        java.util.Arrays.fill(normals, 0, vertexCount * 3, 0f)
        val p = positions
        var i = 0
        while (i + 2 < indexCount) {
            val a = indices[i] * 3; val b = indices[i + 1] * 3; val c = indices[i + 2] * 3
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            normals[a] += nx; normals[a + 1] += ny; normals[a + 2] += nz
            normals[b] += nx; normals[b + 1] += ny; normals[b + 2] += nz
            normals[c] += nx; normals[c + 1] += ny; normals[c + 2] += nz
            i += 3
        }
        for (v in 0 until vertexCount) {
            val o = v * 3
            val l = sqrt(normals[o] * normals[o] + normals[o + 1] * normals[o + 1] + normals[o + 2] * normals[o + 2])
            if (l > 1e-12f) { normals[o] /= l; normals[o + 1] /= l; normals[o + 2] /= l } else { normals[o] = 0f; normals[o + 1] = 1f; normals[o + 2] = 0f }
        }
        version++
    }

    // ---- 现成的形状 ----

    /** XZ 平面上的一张网：(nx+1)×(nz+1) 个顶点，按行排（第 r 行第 c 列的顶点是 r*(nx+1)+c，从 0 数）。 */
    fun grid(nx: Int, nz: Int, w: Float, d: Float) {
        val cx = nx.coerceIn(1, 200); val cz = nz.coerceIn(1, 200)
        mode = MODE_TRIANGLES
        vertexCount = 0
        resize((cx + 1) * (cz + 1))
        for (r in 0..cz) for (c in 0..cx) {
            val i = r * (cx + 1) + c
            positions[i * 3] = (c.toFloat() / cx - 0.5f) * w; positions[i * 3 + 1] = 0f; positions[i * 3 + 2] = (r.toFloat() / cz - 0.5f) * d
            uvs[i * 2] = c.toFloat() / cx; uvs[i * 2 + 1] = r.toFloat() / cz
        }
        val idx = IntArray(cx * cz * 6)
        var k = 0
        for (r in 0 until cz) for (c in 0 until cx) {
            val a = r * (cx + 1) + c; val b = a + 1; val e = a + cx + 1; val f = e + 1
            idx[k++] = a; idx[k++] = e; idx[k++] = b; idx[k++] = b; idx[k++] = e; idx[k++] = f
        }
        setIndices(idx, k)
        recalcNormals()
    }

    fun sphere(radius: Float, segments: Int) {
        val seg = segments.coerceIn(4, 96); val rings = maxOf(seg / 2, 3)
        mode = MODE_TRIANGLES
        vertexCount = 0
        resize((seg + 1) * (rings + 1))
        for (r in 0..rings) for (s in 0..seg) {
            val i = r * (seg + 1) + s
            val th = (r.toFloat() / rings * PI).toFloat(); val ph = (s.toFloat() / seg * 2.0 * PI).toFloat()
            val x = sin(th) * cos(ph); val y = cos(th); val z = sin(th) * sin(ph)
            positions[i * 3] = x * radius; positions[i * 3 + 1] = y * radius; positions[i * 3 + 2] = z * radius
            normals[i * 3] = x; normals[i * 3 + 1] = y; normals[i * 3 + 2] = z
            uvs[i * 2] = s.toFloat() / seg; uvs[i * 2 + 1] = r.toFloat() / rings
        }
        val idx = IntArray(seg * rings * 6)
        var k = 0
        for (r in 0 until rings) for (s in 0 until seg) {
            val a = r * (seg + 1) + s; val b = a + 1; val e = a + seg + 1; val f = e + 1
            idx[k++] = a; idx[k++] = b; idx[k++] = e; idx[k++] = b; idx[k++] = f; idx[k++] = e
        }
        setIndices(idx, k)
        version++
    }

    fun box(w: Float, h: Float, d: Float) {
        mode = MODE_TRIANGLES
        vertexCount = 0
        resize(24)
        val hx = w * 0.5f; val hy = h * 0.5f; val hz = d * 0.5f
        // 六个面：法线 n，面内两个方向 u、v
        val faces = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f,
            0f, 0f, 1f, -1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, -1f, 1f, 0f, 0f, 0f, 1f, 0f)
        val idx = IntArray(36)
        for (f in 0 until 6) {
            val o = f * 9
            for (c in 0 until 4) {
                val su = if (c == 1 || c == 2) 1f else -1f; val sv = if (c >= 2) 1f else -1f
                val i = f * 4 + c
                positions[i * 3] = (faces[o] + faces[o + 3] * su + faces[o + 6] * sv) * hx
                positions[i * 3 + 1] = (faces[o + 1] + faces[o + 4] * su + faces[o + 7] * sv) * hy
                positions[i * 3 + 2] = (faces[o + 2] + faces[o + 5] * su + faces[o + 8] * sv) * hz
                normals[i * 3] = faces[o]; normals[i * 3 + 1] = faces[o + 1]; normals[i * 3 + 2] = faces[o + 2]
                uvs[i * 2] = (su + 1f) * 0.5f; uvs[i * 2 + 1] = (1f - sv) * 0.5f
            }
            val b = f * 4
            idx[f * 6] = b; idx[f * 6 + 1] = b + 1; idx[f * 6 + 2] = b + 2; idx[f * 6 + 3] = b; idx[f * 6 + 4] = b + 2; idx[f * 6 + 5] = b + 3
        }
        setIndices(idx, 36)
        version++
    }

    /** 每帧：跟随质点 / 刚体 / 骨骼，算出世界矩阵。 */
    fun update(scene: Scene) {
        val sim = followSim
        if (sim != null) {
            val n = minOf(vertexCount, sim.count - followFirst)
            if (n > 0) { System.arraycopy(sim.pos, followFirst * 3, positions, 0, n * 3); version++ }
        }
        if (autoNormals || sim != null) recalcNormals()
        val b = followBody
        var qx = quat[0]; var qy = quat[1]; var qz = quat[2]; var qw = quat[3]
        var px = pos[0]; var py = pos[1]; var pz = pos[2]
        if (b != null) { qx = b.qx; qy = b.qy; qz = b.qz; qw = b.qw; px = b.px; py = b.py; pz = b.pz }
        val m = world
        m[0] = (1f - 2f * (qy * qy + qz * qz)) * scale[0]; m[1] = 2f * (qx * qy + qw * qz) * scale[0]; m[2] = 2f * (qx * qz - qw * qy) * scale[0]; m[3] = 0f
        m[4] = 2f * (qx * qy - qw * qz) * scale[1]; m[5] = (1f - 2f * (qx * qx + qz * qz)) * scale[1]; m[6] = 2f * (qy * qz + qw * qx) * scale[1]; m[7] = 0f
        m[8] = 2f * (qx * qz + qw * qy) * scale[2]; m[9] = 2f * (qy * qz - qw * qx) * scale[2]; m[10] = (1f - 2f * (qx * qx + qy * qy)) * scale[2]; m[11] = 0f
        m[12] = px; m[13] = py; m[14] = pz; m[15] = 1f
        if (b == null && anchor.matrix(scene, tm)) {
            // world = 骨骼的世界矩阵 × 自己的变换
            val r = FloatArray(16)
            for (c in 0 until 4) for (rw in 0 until 4) {
                r[c * 4 + rw] = tm[rw] * m[c * 4] + tm[4 + rw] * m[c * 4 + 1] + tm[8 + rw] * m[c * 4 + 2] + tm[12 + rw] * m[c * 4 + 3]
            }
            System.arraycopy(r, 0, m, 0, 16)
        }
    }

    companion object {
        const val MAX_VERTICES = 65000
        const val MAX_INDICES = 300000
        const val MODE_TRIANGLES = 0
        const val MODE_LINES = 1
        const val MODE_POINTS = 2
        val MODE_NAMES = listOf("triangles", "lines", "points")
    }
}

/** 脚本创建的刚体：用的是和模型头发 / 裙子同一个求解器（[PhysicsWorld]），所以摩擦、反弹、6 自由度弹簧关节都是一样的语义。 */
class RigidBody(val id: Int, val owner: Int) {
    val body = PhysicsWorld.Body()
    val collider = Collider()
    var mesh: ScriptMesh? = null
}

class RigidJoint(val id: Int, val owner: Int, val a: RigidBody, val b: RigidBody) {
    val joint = PhysicsWorld.Joint()
}

/**
 * 所有脚本刚体共用的一个物理世界。模型的刚体以「跟着动的障碍物」的身份放进来，所以脚本的箱子、球会被角色撞开、
 * 落在舞台的刚体上；反过来不会推动模型（单向）。
 */
class RigidSim {
    val world = PhysicsWorld()
    val bodies = ArrayList<RigidBody>()
    val joints = ArrayList<RigidJoint>()
    private val proxies = ArrayList<PhysicsWorld.Body>()
    var dirty = true
    var collideModels = true
    private val q = FloatArray(4)

    fun update(dt: Float, modelColliders: List<Collider>) {
        if (bodies.isEmpty()) { if (world.bodies.isNotEmpty()) { world.clear(); proxies.clear() }; return }
        val want = if (collideModels) modelColliders.size else 0
        if (want != proxies.size) {
            proxies.clear()
            for (i in 0 until want) proxies.add(PhysicsWorld.Body().also { it.kinematic = true; it.groupBit = 2; it.mask = 0xFFFF })
            for (i in 0 until want) sync(proxies[i], modelColliders[i], true)
            dirty = true
        }
        if (dirty) rebuild()
        for (i in 0 until want) sync(proxies[i], modelColliders[i], false)
        world.step(dt)
        for (b in bodies) fill(b)
    }

    private fun sync(b: PhysicsWorld.Body, c: Collider, snap: Boolean) {
        b.shape = when (c.shape) { Collider.BOX -> PhysicsWorld.SHAPE_BOX; Collider.CAPSULE -> PhysicsWorld.SHAPE_CAPSULE; else -> PhysicsWorld.SHAPE_SPHERE }
        b.radius = c.radius; b.halfHeight = c.halfHeight; b.hx = c.hx; b.hy = c.hy; b.hz = c.hz
        b.friction = c.friction
        matToQuat(c.r, q)
        if (snap) { b.setPose(c.px, c.py, c.pz, q[0], q[1], q[2], q[3]); b.sx = c.px; b.sy = c.py; b.sz = c.pz; b.sqx = q[0]; b.sqy = q[1]; b.sqz = q[2]; b.sqw = q[3] }
        world.setKinematicTarget(b, c.px, c.py, c.pz, q[0], q[1], q[2], q[3])
    }

    private fun rebuild() {
        dirty = false
        world.clear()
        for (b in bodies) world.bodies.add(b.body)
        for (p in proxies) world.bodies.add(p)
        for (j in joints) {
            j.joint.a = bodies.indexOf(j.a); j.joint.b = bodies.indexOf(j.b)
            if (j.joint.a >= 0 && j.joint.b >= 0) world.joints.add(j.joint)
        }
        world.finalizeSetup()
    }

    /** 把刚体现在的姿态写到它的碰撞体上（粒子拿去碰撞）。 */
    fun fill(rb: RigidBody) {
        val b = rb.body; val c = rb.collider
        c.shape = when (b.shape) { PhysicsWorld.SHAPE_BOX -> Collider.BOX; PhysicsWorld.SHAPE_CAPSULE -> Collider.CAPSULE; else -> Collider.SPHERE }
        c.radius = b.radius; c.halfHeight = b.halfHeight; c.hx = b.hx; c.hy = b.hy; c.hz = b.hz
        c.px = b.px; c.py = b.py; c.pz = b.pz; c.vx = b.vx; c.vy = b.vy; c.vz = b.vz
        c.friction = b.friction
        c.setQuat(b.qx, b.qy, b.qz, b.qw)
        c.updateBound()
    }

    fun removeOwner(owner: Int) {
        if (joints.removeAll { it.owner == owner } or bodies.removeAll { it.owner == owner }) dirty = true
        joints.removeAll { it.a !in bodies || it.b !in bodies }
    }

    fun clear() { bodies.clear(); joints.clear(); proxies.clear(); world.clear(); dirty = true }

    companion object {
        const val MAX_BODIES = 200

        fun matToQuat(r: FloatArray, q: FloatArray) {
            val m00 = r[0]; val m10 = r[1]; val m20 = r[2]; val m01 = r[3]; val m11 = r[4]; val m21 = r[5]; val m02 = r[6]; val m12 = r[7]; val m22 = r[8]
            val tr = m00 + m11 + m22
            if (tr > 0f) { val s = sqrt(tr + 1f) * 2f; q[3] = 0.25f * s; q[0] = (m21 - m12) / s; q[1] = (m02 - m20) / s; q[2] = (m10 - m01) / s }
            else if (m00 > m11 && m00 > m22) { val s = sqrt(1f + m00 - m11 - m22) * 2f; q[3] = (m21 - m12) / s; q[0] = 0.25f * s; q[1] = (m01 + m10) / s; q[2] = (m02 + m20) / s }
            else if (m11 > m22) { val s = sqrt(1f + m11 - m00 - m22) * 2f; q[3] = (m02 - m20) / s; q[0] = (m01 + m10) / s; q[1] = 0.25f * s; q[2] = (m12 + m21) / s }
            else { val s = sqrt(1f + m22 - m00 - m11) * 2f; q[3] = (m10 - m01) / s; q[0] = (m02 + m20) / s; q[1] = (m12 + m21) / s; q[2] = 0.25f * s }
            val l = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            if (l > 1e-12f) { q[0] /= l; q[1] /= l; q[2] /= l; q[3] /= l } else { q[0] = 0f; q[1] = 0f; q[2] = 0f; q[3] = 1f }
        }
    }
}

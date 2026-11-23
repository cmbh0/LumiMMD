package com.lumi.mmd.engine.fx

import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.scene.Scene
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 特效可以跟着的东西：某个模型，或者它的某根骨骼。 */
class Anchor {
    var model = 0
    var bone = ""
    val offset = FloatArray(3)
    private var cachedModel: ModelInstance? = null
    private var cachedBone = -2
    private var cachedName = ""

    val active: Boolean get() = model != 0

    /** 算出世界坐标写进 out[0..2]。没有可跟的东西时返回 false，out 不动。 */
    fun resolve(scene: Scene, out: FloatArray): Boolean {
        if (model == 0) return false
        var m = cachedModel
        if (m == null || m.id != model || !scene.models.contains(m)) {
            m = scene.findById(model) ?: return false
            cachedModel = m; cachedBone = -2
        }
        if (cachedBone == -2 || cachedName != bone) {
            cachedName = bone
            cachedBone = if (bone.isEmpty()) -1 else m.data.bones.indexOfFirst { it.name == bone }
        }
        val w = m.worldMatrix
        val sk = m.skeleton
        var x = offset[0]; var y = offset[1]; var z = offset[2]
        if (cachedBone >= 0 && sk != null) {
            val g = sk.global
            val o = cachedBone * 16
            val lx = g[o] * x + g[o + 4] * y + g[o + 8] * z + g[o + 12]
            val ly = g[o + 1] * x + g[o + 5] * y + g[o + 9] * z + g[o + 13]
            val lz = g[o + 2] * x + g[o + 6] * y + g[o + 10] * z + g[o + 14]
            x = lx; y = ly; z = lz
        }
        out[0] = w[0] * x + w[4] * y + w[8] * z + w[12]
        out[1] = w[1] * x + w[5] * y + w[9] * z + w[13]
        out[2] = w[2] * x + w[6] * y + w[10] * z + w[14]
        return true
    }

    /** 跟着的那个模型 / 骨骼的完整世界矩阵（带旋转和缩放）写进 out[0..15]。 */
    fun matrix(scene: Scene, out: FloatArray): Boolean {
        if (!resolve(scene, out)) return false
        val m = cachedModel ?: return false
        val w = m.worldMatrix
        val sk = m.skeleton
        if (cachedBone >= 0 && sk != null) {
            val g = sk.global
            val o = cachedBone * 16
            for (c in 0 until 4) for (r in 0 until 4) {
                out[c * 4 + r] = w[r] * g[o + c * 4] + w[4 + r] * g[o + c * 4 + 1] + w[8 + r] * g[o + c * 4 + 2] + w[12 + r] * g[o + c * 4 + 3]
            }
        } else System.arraycopy(w, 0, out, 0, 16)
        return true
    }
}

/** 粒子发射器。粒子在世界空间里运动，发射器本身可以跟着骨骼走。 */
class Emitter(val id: Int, val owner: Int, capacity: Int) {
    val capacity = capacity.coerceIn(8, MAX_PER_EMITTER)
    var texture = "soft"
    /** 叠加发光（火花、光点）还是普通的半透明（烟、花瓣）。 */
    var additive = true
    var enabled = true
    /** 每秒发射多少个。 */
    var rate = 0f
    var lifeMin = 1f
    var lifeMax = 1f
    var speedMin = 0f
    var speedMax = 0f
    /** 发射方向，以及偏离它的最大角度（度）：0 = 一条线，180 = 四面八方。 */
    val dir = floatArrayOf(0f, 1f, 0f)
    var spread = 180f
    val gravity = FloatArray(3)
    /** 空气阻力：每秒速度衰减的比例。 */
    var drag = 0f
    var sizeStart = 1f
    var sizeEnd = 1f
    /** 大小的随机幅度（0.3 = ±30%）。 */
    var sizeJitter = 0f
    val colorStart = floatArrayOf(1f, 1f, 1f, 1f)
    val colorEnd = floatArrayOf(1f, 1f, 1f, 0f)
    /** 自转速度（度 / 秒），每个粒子在 ±spin 之间随机。 */
    var spin = 0f
    /** 发射位置（没有跟随对象时）。 */
    val pos = FloatArray(3)
    /** 在多大的盒子范围里随机发射（半边长）。 */
    val area = FloatArray(3)
    val anchor = Anchor()

    var count = 0
        private set
    internal val p = FloatArray(this.capacity * 3)
    internal val v = FloatArray(this.capacity * 3)
    internal val age = FloatArray(this.capacity)
    internal val life = FloatArray(this.capacity)
    internal val scale = FloatArray(this.capacity)
    internal val rot = FloatArray(this.capacity)
    internal val rotV = FloatArray(this.capacity)
    private var acc = 0f
    private var pending = 0
    private var seed = (id * 2654435761L + 12345L).toInt() or 1
    private val origin = FloatArray(3)

    /** 一次喷出 n 个。 */
    fun burst(n: Int) { pending += n.coerceIn(0, capacity) }

    fun clearParticles() { count = 0; pending = 0; acc = 0f }

    private fun rnd(): Float {
        var x = seed
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        seed = x
        return (x ushr 8) * (1f / 16777216f)
    }

    internal fun update(dt: Float, scene: Scene) {
        // 1) 老的粒子往前走
        val gx = gravity[0] * dt; val gy = gravity[1] * dt; val gz = gravity[2] * dt
        val keep = if (drag > 0f) maxOf(0f, 1f - drag * dt) else 1f
        var i = 0
        while (i < count) {
            val a = age[i] + dt
            if (a >= life[i]) {
                val last = count - 1
                if (i != last) {
                    System.arraycopy(p, last * 3, p, i * 3, 3); System.arraycopy(v, last * 3, v, i * 3, 3)
                    age[i] = age[last]; life[i] = life[last]; scale[i] = scale[last]; rot[i] = rot[last]; rotV[i] = rotV[last]
                }
                count = last
                continue
            }
            age[i] = a
            val o = i * 3
            v[o] = (v[o] + gx) * keep; v[o + 1] = (v[o + 1] + gy) * keep; v[o + 2] = (v[o + 2] + gz) * keep
            p[o] += v[o] * dt; p[o + 1] += v[o + 1] * dt; p[o + 2] += v[o + 2] * dt
            rot[i] += rotV[i] * dt
            i++
        }
        // 2) 发射新的
        var n = pending
        pending = 0
        if (enabled && rate > 0f && dt > 0f) {
            acc += rate * dt
            val k = acc.toInt()
            acc -= k
            n += k
        }
        if (n <= 0) return
        if (!anchor.resolve(scene, origin)) { origin[0] = pos[0]; origin[1] = pos[1]; origin[2] = pos[2] }
        else { origin[0] += pos[0]; origin[1] += pos[1]; origin[2] += pos[2] }
        // 以发射方向为 Z 轴搭一组正交基
        var dx = dir[0]; var dy = dir[1]; var dz = dir[2]
        val dl = sqrt(dx * dx + dy * dy + dz * dz)
        if (dl < 1e-6f) { dx = 0f; dy = 1f; dz = 0f } else { dx /= dl; dy /= dl; dz /= dl }
        var ux: Float; var uy: Float; var uz: Float
        if (kotlin.math.abs(dy) < 0.99f) { ux = dz; uy = 0f; uz = -dx } else { ux = 1f; uy = 0f; uz = 0f }
        val ul = sqrt(ux * ux + uy * uy + uz * uz); ux /= ul; uy /= ul; uz /= ul
        val wx = dy * uz - dz * uy; val wy = dz * ux - dx * uz; val wz = dx * uy - dy * ux
        val cosMax = cos(spread.coerceIn(0f, 180f) * RAD)
        for (k in 0 until n) {
            if (count >= capacity) break
            val c = count++
            val o = c * 3
            p[o] = origin[0] + (rnd() * 2f - 1f) * area[0]
            p[o + 1] = origin[1] + (rnd() * 2f - 1f) * area[1]
            p[o + 2] = origin[2] + (rnd() * 2f - 1f) * area[2]
            val ct = 1f - rnd() * (1f - cosMax)
            val st = sqrt(maxOf(0f, 1f - ct * ct))
            val ph = rnd() * TWO_PI
            val a = st * cos(ph); val b = st * sin(ph)
            val sp = speedMin + (speedMax - speedMin) * rnd()
            v[o] = (dx * ct + ux * a + wx * b) * sp
            v[o + 1] = (dy * ct + uy * a + wy * b) * sp
            v[o + 2] = (dz * ct + uz * a + wz * b) * sp
            // 新粒子在这一帧里出生的时刻是随机的，免得高发射率时排成一层一层
            val born = rnd() * dt
            age[c] = born
            p[o] += v[o] * born; p[o + 1] += v[o + 1] * born; p[o + 2] += v[o + 2] * born
            life[c] = maxOf(0.02f, lifeMin + (lifeMax - lifeMin) * rnd())
            scale[c] = 1f + (rnd() * 2f - 1f) * sizeJitter
            rot[c] = rnd() * TWO_PI
            rotV[c] = (rnd() * 2f - 1f) * spin * RAD
        }
    }

    companion object {
        const val MAX_PER_EMITTER = 6000
        private const val RAD = (Math.PI / 180.0).toFloat()
        private const val TWO_PI = (Math.PI * 2.0).toFloat()
    }
}

/** 一张始终朝着镜头的贴片（光晕、标记、图片）。 */
class Sprite(val id: Int, val owner: Int) {
    var texture = "soft"
    var additive = true
    var visible = true
    var size = 1f
    /** 旋转（度）。 */
    var rotation = 0f
    val color = floatArrayOf(1f, 1f, 1f, 1f)
    val pos = FloatArray(3)
    val anchor = Anchor()
    internal val world = FloatArray(3)
}

/** 拖尾：跟着一个点走过的路径画一条逐渐变淡的光带（挥手、甩裙摆、刀光）。 */
class Trail(val id: Int, val owner: Int) {
    var enabled = true
    var additive = true
    var width = 0.5f
    /** 光带上每一点存在多久（秒）。 */
    var life = 0.5f
    val colorStart = floatArrayOf(1f, 1f, 1f, 1f)
    val colorEnd = floatArrayOf(1f, 1f, 1f, 0f)
    val pos = FloatArray(3)
    val anchor = Anchor()

    internal val pts = FloatArray(MAX_POINTS * 3)
    internal val born = FloatArray(MAX_POINTS)
    internal var count = 0
    private val tmp = FloatArray(3)

    val pointCount: Int get() = count

    fun clearPoints() { count = 0 }

    internal fun update(now: Float, scene: Scene) {
        // 过期的点从头上去掉
        var drop = 0
        while (drop < count && now - born[drop] > life) drop++
        if (drop > 0) {
            System.arraycopy(pts, drop * 3, pts, 0, (count - drop) * 3)
            System.arraycopy(born, drop, born, 0, count - drop)
            count -= drop
        }
        if (!enabled) return
        if (anchor.resolve(scene, tmp)) { tmp[0] += pos[0]; tmp[1] += pos[1]; tmp[2] += pos[2] }
        else { tmp[0] = pos[0]; tmp[1] = pos[1]; tmp[2] = pos[2] }
        if (count > 0) {
            val o = (count - 1) * 3
            val dx = tmp[0] - pts[o]; val dy = tmp[1] - pts[o + 1]; val dz = tmp[2] - pts[o + 2]
            // 没怎么动就不加点，只把最后一点挪到当前位置
            if (dx * dx + dy * dy + dz * dz < MIN_STEP * MIN_STEP && count > 1) {
                pts[o] = tmp[0]; pts[o + 1] = tmp[1]; pts[o + 2] = tmp[2]
                return
            }
        }
        if (count >= MAX_POINTS) {
            System.arraycopy(pts, 3, pts, 0, (count - 1) * 3)
            System.arraycopy(born, 1, born, 0, count - 1)
            count--
        }
        val o = count * 3
        pts[o] = tmp[0]; pts[o + 1] = tmp[1]; pts[o + 2] = tmp[2]
        born[count] = now
        count++
    }

    companion object {
        const val MAX_POINTS = 96
        private const val MIN_STEP = 0.05f
    }
}

/**
 * 场景里的全部特效：粒子、贴片、拖尾，以及整屏的闪光 / 染色和镜头抖动。
 * 只在渲染线程上用。每个特效都记着是谁（哪个脚本）创建的，脚本停掉时把它的东西一起清走。
 */
class Effects {
    val emitters = ArrayList<Emitter>()
    val sprites = ArrayList<Sprite>()
    val trails = ArrayList<Trail>()
    /** 脚本画的贴图、搭的网格、粒子物理世界、放的碰撞体、刚体。 */
    val canvases = ArrayList<Canvas>()
    val meshes = ArrayList<ScriptMesh>()
    val sims = ArrayList<SimWorld>()
    val colliders = ArrayList<Collider>()
    val rigid = RigidSim()
    /** 这一帧场景里所有能碰的东西：模型的刚体 + 脚本的碰撞体 + 脚本的刚体。 */
    val worldColliders = ArrayList<Collider>()
    private val modelColliders = ArrayList<Collider>()
    private val colliderPool = ArrayList<Collider>()
    private var lastModelColliders = -1
    private val ct = FloatArray(16)

    /** 一直盖在画面上的颜色（转场、染色），rgba。 */
    val screen = FloatArray(4)
    private val flash = FloatArray(4)
    private var flashLeft = 0f
    private var flashTotal = 0f
    private var shakeAmount = 0f
    private var shakeLeft = 0f
    private var shakeTotal = 0f
    /** 特效自己的时钟（秒），暂停时间轴时也照常走。 */
    var time = 0f
        private set
    private var nextId = 1

    val particleCount: Int get() { var n = 0; for (e in emitters) n += e.count; return n }
    val isEmpty: Boolean get() = emitters.isEmpty() && sprites.isEmpty() && trails.isEmpty() && meshes.isEmpty() && sims.isEmpty() && rigid.bodies.isEmpty() && screen[3] <= 0f && flashLeft <= 0f && shakeLeft <= 0f

    fun newEmitter(owner: Int, capacity: Int): Emitter? {
        if (emitters.size >= MAX_EMITTERS) return null
        return Emitter(nextId++, owner, capacity).also { emitters.add(it) }
    }

    fun newSprite(owner: Int): Sprite? {
        if (sprites.size >= MAX_SPRITES) return null
        return Sprite(nextId++, owner).also { sprites.add(it) }
    }

    fun newTrail(owner: Int): Trail? {
        if (trails.size >= MAX_TRAILS) return null
        return Trail(nextId++, owner).also { trails.add(it) }
    }

    fun newCanvas(owner: Int, w: Int, h: Int): Canvas? {
        if (canvases.size >= MAX_CANVASES) return null
        return Canvas(nextId++, owner, w.coerceIn(1, Canvas.MAX_SIZE), h.coerceIn(1, Canvas.MAX_SIZE)).also { canvases.add(it) }
    }

    fun newMesh(owner: Int): ScriptMesh? {
        if (meshes.size >= MAX_MESHES) return null
        return ScriptMesh(nextId++, owner).also { meshes.add(it) }
    }

    fun newSim(owner: Int, capacity: Int): SimWorld? {
        if (sims.size >= MAX_SIMS) return null
        return SimWorld(nextId++, owner, capacity).also { sims.add(it) }
    }

    fun newCollider(owner: Int): Collider? {
        if (colliders.size >= MAX_COLLIDERS) return null
        return Collider().also { it.id = nextId++; it.owner = owner; colliders.add(it) }
    }

    fun newBody(owner: Int): RigidBody? {
        if (rigid.bodies.size >= RigidSim.MAX_BODIES) return null
        return RigidBody(nextId++, owner).also { rigid.bodies.add(it); rigid.dirty = true }
    }

    fun newJoint(owner: Int, a: RigidBody, b: RigidBody): RigidJoint = RigidJoint(nextId++, owner, a, b).also { rigid.joints.add(it); rigid.dirty = true }

    fun removeMesh(m: ScriptMesh) { meshes.remove(m) }
    fun removeBody(b: RigidBody) {
        b.mesh?.let { meshes.remove(it) }
        rigid.bodies.remove(b); rigid.joints.removeAll { it.a === b || it.b === b }; rigid.dirty = true
    }

    /**
     * 把场景里所有模型的刚体换算到世界坐标，变成碰撞体（带上由前后两帧位移得到的速度）。
     * 只有在有粒子世界 / 脚本刚体需要时才算。
     */
    private fun gatherColliders(scene: Scene, dt: Float) {
        modelColliders.clear()
        var n = 0
        for (m in scene.models) {
            val ph = m.physics ?: continue
            if (!m.visible) continue
            val w = m.worldMatrix
            val s = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
            if (s < 1e-6f) continue
            for (i in 0 until ph.bodyCount) {
                val b = ph.body(i)
                if (n == colliderPool.size) colliderPool.add(Collider())
                val c = colliderPool[n++]
                val x = w[0] * b.px + w[4] * b.py + w[8] * b.pz + w[12]
                val y = w[1] * b.px + w[5] * b.py + w[9] * b.pz + w[13]
                val z = w[2] * b.px + w[6] * b.py + w[10] * b.pz + w[14]
                if (lastModelColliders >= n && dt > 1e-5f) { c.vx = (x - c.px) / dt; c.vy = (y - c.py) / dt; c.vz = (z - c.pz) / dt } else { c.vx = 0f; c.vy = 0f; c.vz = 0f }
                if (c.vx * c.vx + c.vy * c.vy + c.vz * c.vz > 400f * 400f) { c.vx = 0f; c.vy = 0f; c.vz = 0f }
                c.px = x; c.py = y; c.pz = z
                c.shape = when (b.shape) { com.lumi.mmd.engine.physics.PhysicsWorld.SHAPE_BOX -> Collider.BOX; com.lumi.mmd.engine.physics.PhysicsWorld.SHAPE_CAPSULE -> Collider.CAPSULE; else -> Collider.SPHERE }
                c.radius = b.radius * s; c.halfHeight = b.halfHeight * s; c.hx = b.hx * s; c.hy = b.hy * s; c.hz = b.hz * s
                c.friction = b.friction
                // 旋转 = 模型的旋转（去掉缩放）× 刚体的旋转
                c.setQuat(b.qx, b.qy, b.qz, b.qw)
                val r = c.r
                for (col in 0 until 3) {
                    val ax = r[col * 3]; val ay = r[col * 3 + 1]; val az = r[col * 3 + 2]
                    ct[col * 3] = (w[0] * ax + w[4] * ay + w[8] * az) / s
                    ct[col * 3 + 1] = (w[1] * ax + w[5] * ay + w[9] * az) / s
                    ct[col * 3 + 2] = (w[2] * ax + w[6] * ay + w[10] * az) / s
                }
                System.arraycopy(ct, 0, r, 0, 9)
                c.updateBound()
                c.id = m.id; c.owner = i
                modelColliders.add(c)
            }
        }
        lastModelColliders = n
    }

    /** 整屏闪一下：从这个颜色淡出。 */
    fun flash(r: Float, g: Float, b: Float, a: Float, seconds: Float) {
        flash[0] = r; flash[1] = g; flash[2] = b; flash[3] = a.coerceIn(0f, 1f)
        flashTotal = maxOf(seconds, 0.01f); flashLeft = flashTotal
    }

    /** 镜头抖动：amount 是幅度（场景单位），逐渐减弱。 */
    fun shake(amount: Float, seconds: Float) {
        shakeAmount = amount; shakeTotal = maxOf(seconds, 0.01f); shakeLeft = shakeTotal
    }

    fun update(dt: Float, scene: Scene) {
        val h = dt.coerceIn(0f, 0.1f)
        time += h
        for (e in emitters) e.update(h, scene)
        for (t in trails) t.update(time, scene)
        for (s in sprites) {
            if (s.anchor.resolve(scene, s.world)) { s.world[0] += s.pos[0]; s.world[1] += s.pos[1]; s.world[2] += s.pos[2] }
            else { s.world[0] = s.pos[0]; s.world[1] = s.pos[1]; s.world[2] = s.pos[2] }
        }
        if (flashLeft > 0f) flashLeft = maxOf(0f, flashLeft - h)
        if (shakeLeft > 0f) shakeLeft = maxOf(0f, shakeLeft - h)
        if (sims.isEmpty() && rigid.bodies.isEmpty() && colliders.isEmpty()) worldColliders.clear()      // 没有人用：留着只会过期
        if (sims.isNotEmpty() || rigid.bodies.isNotEmpty() || colliders.isNotEmpty()) {
            refreshColliders(scene, h)
            rigid.world.groundEnabled = scene.floor.solid
            rigid.world.groundD = scene.floor.height
            rigid.world.statics = if (rigid.collideModels) scene.colliders else emptyList()
            rigid.update(h, modelColliders)
            // 用下标走：on_step 里脚本出错会被停掉，它的世界会在循环中途被移除
            var i = 0
            while (i < sims.size) { val w = sims[i]; w.followFloor(scene.floor); w.update(h, scene, if (w.collideModels) worldColliders else scriptColliders); i++ }
        }
        for (m in meshes) m.update(scene)
    }

    private val scriptColliders = ArrayList<Collider>()

    /** 重新收集碰撞体（每帧一次；脚本做射线 / 距离查询前也可以调）。 */
    fun refreshColliders(scene: Scene, dt: Float) {
        gatherColliders(scene, dt)
        scriptColliders.clear()
        for (c in colliders) { c.place(scene, dt, ct); scriptColliders.add(c) }
        for (b in rigid.bodies) { rigid.fill(b); scriptColliders.add(b.collider) }
        worldColliders.clear()
        worldColliders.addAll(modelColliders)
        // 带「碰撞」属性的模型（包围盒 / 网格）
        worldColliders.addAll(scene.colliders)
        worldColliders.addAll(scriptColliders)
    }

    /** 这一帧盖在画面上的颜色（常驻的染色和闪光合在一起）。返回 false = 不用画。 */
    fun overlayColor(out: FloatArray): Boolean {
        val fa = if (flashLeft > 0f) flash[3] * (flashLeft / flashTotal) else 0f
        val sa = screen[3].coerceIn(0f, 1f)
        val a = fa + sa * (1f - fa)
        if (a <= 0.002f) return false
        // 闪光盖在染色上面
        for (k in 0 until 3) out[k] = (flash[k] * fa + screen[k] * sa * (1f - fa)) / a
        out[3] = a
        return true
    }

    /** 这一帧镜头该偏多少（场景单位，x / y 是屏幕方向）。 */
    fun shakeOffset(out: FloatArray): Boolean {
        if (shakeLeft <= 0f) return false
        val k = shakeAmount * (shakeLeft / shakeTotal)
        val t = time * 47f
        out[0] = (sin(t * 1.31f) + sin(t * 2.93f + 1.7f) * 0.5f) * k * 0.67f
        out[1] = (cos(t * 1.73f) + sin(t * 3.41f + 0.4f) * 0.5f) * k * 0.67f
        return true
    }

    /** 清掉某个脚本创建的全部特效。 */
    fun removeOwner(owner: Int) {
        emitters.removeAll { it.owner == owner }
        sprites.removeAll { it.owner == owner }
        trails.removeAll { it.owner == owner }
        canvases.removeAll { it.owner == owner }
        meshes.removeAll { it.owner == owner }
        sims.removeAll { it.owner == owner }
        colliders.removeAll { it.owner == owner }
        rigid.removeOwner(owner)
    }

    fun clear() {
        emitters.clear(); sprites.clear(); trails.clear()
        canvases.clear(); meshes.clear(); sims.clear(); colliders.clear(); rigid.clear(); worldColliders.clear()
        screen.fill(0f); flashLeft = 0f; shakeLeft = 0f
    }

    companion object {
        const val MAX_EMITTERS = 64
        const val MAX_SPRITES = 256
        const val MAX_TRAILS = 32
        const val MAX_CANVASES = 24
        const val MAX_MESHES = 96
        const val MAX_SIMS = 8
        const val MAX_COLLIDERS = 64

        /** 0..1 的随机数，只和输入有关（给脚本做稳定的"随机"用）。 */
        fun hash(n: Int): Float {
            var x = n * 374761393 + 668265263
            x = (x xor (x ushr 13)) * 1274126177
            x = x xor (x ushr 16)
            return (x and 0xFFFFFF) / 16777216f
        }

        /** 平滑的三维噪声，范围约 -1..1。 */
        fun noise(x: Float, y: Float, z: Float): Float {
            val xi = kotlin.math.floor(x).toInt(); val yi = kotlin.math.floor(y).toInt(); val zi = kotlin.math.floor(z).toInt()
            val fx = x - xi; val fy = y - yi; val fz = z - zi
            val u = fx * fx * (3f - 2f * fx); val v = fy * fy * (3f - 2f * fy); val w = fz * fz * (3f - 2f * fz)
            fun h(a: Int, b: Int, c: Int): Float = hash(a * 73856093 xor b * 19349663 xor c * 83492791) * 2f - 1f
            val x00 = h(xi, yi, zi) + (h(xi + 1, yi, zi) - h(xi, yi, zi)) * u
            val x10 = h(xi, yi + 1, zi) + (h(xi + 1, yi + 1, zi) - h(xi, yi + 1, zi)) * u
            val x01 = h(xi, yi, zi + 1) + (h(xi + 1, yi, zi + 1) - h(xi, yi, zi + 1)) * u
            val x11 = h(xi, yi + 1, zi + 1) + (h(xi + 1, yi + 1, zi + 1) - h(xi, yi + 1, zi + 1)) * u
            val y0 = x00 + (x10 - x00) * v
            val y1 = x01 + (x11 - x01) * v
            return y0 + (y1 - y0) * w
        }

        @Suppress("unused")
        private fun angle(c: Float): Float = acos(c.coerceIn(-1f, 1f))
    }
}

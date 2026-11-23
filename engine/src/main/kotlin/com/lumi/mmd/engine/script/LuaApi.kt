package com.lumi.mmd.engine.script

import com.lumi.mmd.engine.anim.Motion
import com.lumi.mmd.engine.fx.Anchor
import com.lumi.mmd.engine.fx.Effects
import com.lumi.mmd.engine.fx.Emitter
import com.lumi.mmd.engine.fx.Sprite
import com.lumi.mmd.engine.fx.Trail
import com.lumi.mmd.engine.math.Quat
import com.lumi.mmd.engine.mesh.EditMesh
import com.lumi.mmd.engine.mesh.MeshPrimitives
import com.lumi.mmd.engine.physics.PhysicsTuning
import com.lumi.mmd.engine.scene.ModelInstance
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction

private const val DEG = (Math.PI / 180.0).toFloat()

private fun n(v: Float): LuaValue = LuaValue.valueOf(v.toDouble())
private fun n(v: Int): LuaValue = LuaValue.valueOf(v)
private fun str(v: String): LuaValue = LuaValue.valueOf(v)
private fun bool(v: Boolean): LuaValue = LuaValue.valueOf(v)
private fun multi(vararg v: Float): Varargs = LuaValue.varargsOf(Array<LuaValue>(v.size) { n(v[it]) })
private fun Varargs.f(i: Int): Float = checkdouble(i).toFloat()
private fun Varargs.f(i: Int, d: Float): Float = if (isnoneornil(i)) d else checkdouble(i).toFloat()
private fun fail(msg: String): Nothing = throw LuaError(msg)

private inline fun fn(crossinline f: (Varargs) -> Varargs): VarArgFunction = object : VarArgFunction() {
    override fun invoke(a: Varargs): Varargs = f(a)
}

/** 没有返回值的函数。 */
private inline fun proc(crossinline f: (Varargs) -> Unit): VarArgFunction = object : VarArgFunction() {
    override fun invoke(a: Varargs): Varargs { f(a); return LuaValue.NONE }
}

/** 读表里的一个数：t.key；不是数字就用默认值。 */
private fun LuaValue.num(key: String, d: Float): Float { val v = get(key); return if (v.isnumber()) v.tofloat() else d }

/** 读一组数：{a, b, c}、{x=,y=,z=} / {r=,g=,b=,a=} 都行；单个数字则每一项都取它。读到了返回 true。 */
private fun readVec(v: LuaValue, out: FloatArray, count: Int, names: String = "xyzw"): Boolean {
    if (v.isnumber()) { for (i in 0 until count) out[i] = v.tofloat(); return true }
    if (!v.istable()) return false
    for (i in 0 until count) {
        var e = v.get(i + 1)
        if (!e.isnumber()) e = v.get(names[i].toString())
        if (e.isnumber()) out[i] = e.tofloat()
    }
    return true
}

/**
 * 一种"句柄"类型（模型、骨骼、材质……）：Lua 里是一张小表，里面只记着编号，
 * 属性（m.opacity）和方法（m:bone("头")）都通过元表转到 Kotlin。
 * 记编号而不是对象本身，是因为模型在建模 / 找回贴图之后会被整个换掉，编号不变。
 */
private class LuaType(val name: String) {
    val methods = HashMap<String, LuaValue>()
    val getters = HashMap<String, (LuaTable) -> LuaValue>()
    val setters = HashMap<String, (LuaTable, LuaValue) -> Unit>()
    val meta = LuaTable()

    init {
        meta.set("__index", object : TwoArgFunction() {
            override fun call(t: LuaValue, k: LuaValue): LuaValue {
                if (!k.isstring()) return LuaValue.NIL
                val key = k.tojstring()
                methods[key]?.let { return it }
                val g = getters[key] ?: return LuaValue.NIL
                return g(t as LuaTable)
            }
        })
        meta.set("__newindex", object : ThreeArgFunction() {
            override fun call(t: LuaValue, k: LuaValue, v: LuaValue): LuaValue {
                val s = if (k.isstring()) setters[k.tojstring()] else null
                if (s != null) s(t as LuaTable, v)
                else if (k.isstring() && getters.containsKey(k.tojstring())) fail("${this@LuaType.name}.${k.tojstring()} 是只读的")
                else t.rawset(k, v)
                return LuaValue.NONE
            }
        })
        meta.set("__tostring", object : VarArgFunction() {
            override fun invoke(a: Varargs): Varargs = str(describe(a.arg1()))
        })
    }

    /**
     * print / tostring 时的样子：<model 1 "初音">、<bone 12 "右腕">、<emitter 3>。
     * 骨骼、材质、刚体写的是它在模型里的序号（从 1 起，和 .index 一样）。
     * （LuaJ 的函数类自己有个叫 name 的字段，所以在那些匿名对象里不能直接写 name。）
     */
    fun describe(h: LuaValue): String {
        if (!h.istable()) return "<$name>"
        val b = h.rawget("__b")
        val no = if (b.isnumber()) (b.toint() + 1).toString() else h.rawget("__id").tojstring()
        val nm = try { getters["name"]?.invoke(h as LuaTable) } catch (_: Throwable) { null }
        return if (nm != null && nm.isstring()) "<$name $no \"${nm.tojstring()}\">" else "<$name $no>"
    }

    /** 方法：f 拿到的是 (句柄, 除 self 以外的参数)。 */
    inline fun method(name: String, crossinline f: (LuaTable, Varargs) -> Varargs) {
        val type = this.name
        methods[name] = object : VarArgFunction() {
            override fun invoke(a: Varargs): Varargs {
                val self = a.arg1()
                if (!self.istable()) fail("$type:$name 要用冒号调用（x:$name(...)，不是 x.$name(...)）")
                return f(self as LuaTable, a.subargs(2))
            }
        }
    }

    inline fun action(name: String, crossinline f: (LuaTable, Varargs) -> Unit) = method(name) { t, a -> f(t, a); LuaValue.NONE }

    fun new(): LuaTable = LuaTable().also { it.setmetatable(meta) }
}

/**
 * 脚本能用的全部接口。每个脚本一份（接口要知道是哪个脚本在调用：它改过的东西、创建的特效，脚本停掉时要收拾干净）。
 * 接口说明见 [ScriptLibrary.REFERENCE]。
 */
internal class LuaApi(private val engine: ScriptEngine, private val script: Script) {
    private val host get() = engine.host
    private val scene get() = host.scene
    private val owner get() = script.id

    private val modelT = LuaType("model")
    private val boneT = LuaType("bone")
    private val matT = LuaType("material")
    private val bodyT = LuaType("body")
    private val emitterT = LuaType("emitter")
    private val spriteT = LuaType("sprite")
    private val trailT = LuaType("trail")

    private val v3 = FloatArray(4)
    private val v4 = FloatArray(4)
    private val q = FloatArray(4)
    private val emitters = HashMap<Int, Emitter>()
    private val sprites = HashMap<Int, Sprite>()
    private val trails = HashMap<Int, Trail>()

    // ------------------------------------------------------------------ 句柄 ↔ 对象

    private fun handle(m: ModelInstance): LuaTable = modelT.new().also { it.rawset("__id", n(m.id)) }

    private fun modelOf(t: LuaValue): ModelInstance {
        val id = t.rawget("__id")
        if (!id.isnumber()) fail("这里需要一个模型（scene.main()、scene.find(...) 的返回值）")
        return scene.findById(id.toint()) ?: fail("这个模型已经不在场景里了")
    }

    private fun boneHandle(m: ModelInstance, bone: Int): LuaTable = boneT.new().also { it.rawset("__id", n(m.id)); it.rawset("__b", n(bone)) }

    private fun boneIndex(t: LuaTable, m: ModelInstance): Int {
        val b = t.rawget("__b").toint()
        if (m.skeleton == null || b < 0 || b >= m.skeleton.count) fail("这根骨骼已经不存在了")
        return b
    }

    private fun findBone(m: ModelInstance, name: String): Int {
        val bones = m.data.bones
        var i = bones.indexOfFirst { it.name == name }
        if (i < 0) i = bones.indexOfFirst { it.nameEn.equals(name, true) && name.isNotEmpty() }
        return i
    }

    /** 记下脚本第一次动这个模型的骨骼 / 材质 / 表情之前的状态，脚本停掉时放回去。 */
    private fun touchBones(m: ModelInstance) {
        val id = m.id
        script.remember("bones$id") {
            scene.findById(id)?.let { t -> t.skeleton?.clearScriptLayer(); t.skeleton?.clearRoot(); t.poseDirty = true }
        }
        m.poseDirty = true
    }

    private fun keepAttach(id: Int) {
        script.rememberSaved("m$id.attach") {
            val t = scene.findById(id)
            val oldP = t?.attachParent?.id ?: 0; val oldB = t?.attachBone ?: -1
            val back: () -> Unit = { scene.findById(id)?.let { it.attachParent = if (oldP != 0) scene.findById(oldP) else null; it.attachBone = oldB } }
            back
        }
    }

    private fun touchMaterials(m: ModelInstance) {
        val id = m.id
        script.remember("mats$id") { scene.findById(id)?.let { t -> for (s in t.materials) s.clearFx() } }
    }

    private fun touchMorphs(m: ModelInstance) {
        val id = m.id
        script.remember("morphs$id") { scene.findById(id)?.let { t -> t.morphs.clearScriptWeights(); t.poseDirty = true } }
        m.poseDirty = true
    }

    // ------------------------------------------------------------------ 安装

    fun install(g: Globals) {
        g.set("print", proc { a ->
            val sb = StringBuilder()
            // 表会展开写出来（print(m:morphs()) 直接看到名字），模型 / 骨骼写成 <bone 12 "右腕">
            for (i in 1..a.narg()) { if (i > 1) sb.append('\t'); engine.dump(a.arg(i), sb, 0) }
            engine.print(script, sb.toString())
        })
        g.set("require", fn { a ->
            val name = a.checkjstring(1)
            val loaded = g.get("package").get("loaded")
            val have = loaded.get(name)
            if (!have.isnil()) return@fn have
            val src = host.scriptSource(name) ?: fail("require：脚本库里没有叫「$name」的脚本")
            val r = g.load(src, "@$name").call()
            val v = if (r.isnil()) LuaValue.TRUE else r
            loaded.set(name, v)
            v
        })
        val os = LuaTable()
        os.set("time", fn { n((System.currentTimeMillis() / 1000L).toInt()) })
        os.set("clock", fn { LuaValue.valueOf(System.nanoTime() / 1e9) })
        os.set("date", fn { str(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())) })
        g.set("os", os)

        installMath(g.get("math"))
        installTime(g)
        installScene(g)
        installModel()
        installBone()
        installMaterial()
        installBody()
        installPhysics(g)
        installCamera(g)
        installFx(g)
        installUi(g)
        installCanvas(g)
        installMesh(g)
        installSim(g)
    }

    private fun installMath(m: LuaValue) {
        if (!m.istable()) return
        m.set("clamp", fn { a -> n(a.f(1).coerceIn(a.f(2), a.f(3))) })
        m.set("lerp", fn { a -> n(a.f(1) + (a.f(2) - a.f(1)) * a.f(3)) })
        m.set("smoothstep", fn { a -> val t = ((a.f(3) - a.f(1)) / (a.f(2) - a.f(1))).coerceIn(0f, 1f); n(t * t * (3f - 2f * t)) })
        m.set("sign", fn { a -> val v = a.f(1); n(if (v > 0f) 1 else if (v < 0f) -1 else 0) })
        m.set("round", fn { a -> n(Math.round(a.f(1))) })
        m.set("noise", fn { a -> n(Effects.noise(a.f(1), a.f(2, 0f), a.f(3, 0f))) })
    }

    private fun installTime(g: Globals) {
        val t = LuaTable()
        t.set("play", proc { host.setPlaying(true) })
        t.set("pause", proc { host.setPlaying(false) })
        t.set("seek", proc { a -> host.seekSeconds(a.f(1).coerceAtLeast(0f)) })
        for (k in arrayOf("now", "frame", "dt", "real", "duration", "local", "progress")) t.set(k, n(0f))
        t.set("playing", LuaValue.FALSE)
        t.set("fps", n(Motion.FPS))
        g.set("time", t)
        script.timeTable = t

        val input = LuaTable()
        input.set("x", n(0f)); input.set("y", n(0f))
        input.set("down", fn { a -> bool(host.input.isDown(a.checkjstring(1))) })
        g.set("input", input)
        script.inputTable = input
    }

    // ------------------------------------------------------------------ scene

    private fun mainModel(): ModelInstance? {
        if (script.target != 0) scene.findById(script.target)?.let { return it }
        host.selectedModel()?.let { if (scene.models.contains(it)) return it }
        return scene.models.firstOrNull { it.isCharacter } ?: scene.models.firstOrNull()
    }

    private fun installScene(g: Globals) {
        val s = LuaTable()
        s.set("models", fn { a ->
            val kind = if (a.isnoneornil(1)) "" else a.checkjstring(1)
            val out = LuaTable()
            var i = 1
            for (m in scene.models) {
                val ok = when (kind) { "" -> true; "character" -> m.isCharacter; "stage" -> m.isStage; "accessory" -> m.kind == ModelInstance.KIND_ACCESSORY; else -> true }
                if (ok) out.set(i++, handle(m))
            }
            out
        })
        s.set("main", fn { mainModel()?.let { handle(it) } ?: LuaValue.NIL })
        s.set("find", fn { a ->
            val name = a.checkjstring(1)
            val m = scene.models.firstOrNull { it.name == name } ?: scene.models.firstOrNull { it.name.contains(name, true) }
            m?.let { handle(it) } ?: LuaValue.NIL
        })
        s.set("get", fn { a -> scene.findById(a.checkint(1))?.let { handle(it) } ?: LuaValue.NIL })
        s.set("create", fn { a -> createPrimitive(a.checkjstring(1), if (a.istable(2)) a.checktable(2) else LuaTable()) })
        s.set("clone", fn { a ->
            val src = modelOf(a.arg1())
            if (script.ownedModels.size >= MAX_OWNED) fail("一个脚本最多创建 $MAX_OWNED 个模型")
            val c = host.addModel(src.data, src.kind)
            c.name = src.name + " 分身"
            System.arraycopy(src.position, 0, c.position, 0, 3); System.arraycopy(src.rotation, 0, c.rotation, 0, 4); c.scale = src.scale
            // 分身跟着同样的动作走（共用同一份动作数据）
            for (clip in src.clips) c.clips.add(clip.placedCopy())
            c.physicsEnabled = src.physicsEnabled
            script.ownedModels.add(c.id)
            host.sceneChanged()
            handle(c)
        })
        s.set("remove", proc { a ->
            val m = modelOf(a.arg1())
            if (m.id !in script.ownedModels) fail("脚本只能移除它自己创建的模型（scene.create / scene.clone 出来的）")
            script.ownedModels.remove(m.id)
            host.removeModel(m)
            host.sceneChanged()
        })
        g.set("scene", s)
    }

    private fun createPrimitive(kind: String, o: LuaTable): LuaValue {
        if (script.ownedModels.size >= MAX_OWNED) fail("一个脚本最多创建 $MAX_OWNED 个模型")
        val name = o.get("name").optjstring(kind)
        val em = EditMesh.createEmpty(name)
        val size = FloatArray(3) { 1f }
        readVec(o.get("size"), size, 3)
        val r = o.num("radius", size[0] * 0.5f)
        val seg = o.num("segments", 24f).toInt().coerceIn(3, 96)
        when (kind) {
            "box", "cube" -> MeshPrimitives.box(em, size[0], size[1], size[2])
            "plane" -> MeshPrimitives.plane(em, size[0], size[2])
            "sphere", "ball" -> MeshPrimitives.sphere(em, r, seg, maxOf(seg / 2, 3))
            "cylinder" -> MeshPrimitives.cylinder(em, r, r, o.num("height", size[1]), seg)
            "cone" -> MeshPrimitives.cylinder(em, r, 0f, o.num("height", size[1]), seg)
            "torus", "ring" -> MeshPrimitives.torus(em, r, o.num("thickness", r * 0.25f), seg * 2, maxOf(seg / 2, 6))
            else -> fail("scene.create：不认识的形状「$kind」（可用：box plane sphere cylinder cone torus）")
        }
        val data = em.build().data
        val c = floatArrayOf(0.8f, 0.8f, 0.85f, 1f)
        if (readVec(o.get("color"), c, 4, "rgba")) {
            val md = data.materials[0]
            md.diffuse[0] = c[0]; md.diffuse[1] = c[1]; md.diffuse[2] = c[2]; md.diffuse[3] = c[3]
            md.ambient[0] = c[0] * 0.5f; md.ambient[1] = c[1] * 0.5f; md.ambient[2] = c[2] * 0.5f
        }
        val m = host.addModel(data, ModelInstance.KIND_ACCESSORY)
        m.name = name
        val p = FloatArray(3)
        if (readVec(o.get("pos"), p, 3)) m.setPosition(p[0], p[1], p[2])
        script.ownedModels.add(m.id)
        host.sceneChanged()
        return handle(m)
    }

    // ------------------------------------------------------------------ model

    private fun installModel() {
        val t = modelT
        t.getters["id"] = { h -> h.rawget("__id") }
        t.getters["valid"] = { h -> bool(scene.findById(h.rawget("__id").toint()) != null) }
        t.getters["name"] = { h -> str(modelOf(h).name) }
        t.getters["kind"] = { h -> str(when (modelOf(h).kind) { ModelInstance.KIND_STAGE -> "stage"; ModelInstance.KIND_ACCESSORY -> "accessory"; else -> "character" }) }
        fun prop(name: String, get: (ModelInstance) -> LuaValue, set: (ModelInstance, LuaValue) -> Unit) {
            t.getters[name] = { h -> get(modelOf(h)) }
            t.setters[name] = { h, v ->
                val m = modelOf(h)
                val id = m.id
                script.rememberSaved("m$id.$name") {
                    val old = scene.findById(id)?.let { get(it) }
                    val back: () -> Unit = { if (old != null) scene.findById(id)?.let { set(it, old) } }
                    back
                }
                set(m, v)
            }
        }
        prop("visible", { bool(it.visible) }) { m, v -> m.visible = v.toboolean() }
        prop("opacity", { n(it.opacity) }) { m, v -> m.opacity = v.tofloat().coerceIn(0f, 1f) }
        prop("layer", { n(it.layer) }) { m, v -> m.layer = v.toint().coerceIn(-100, 100) }
        // 碰撞："none" / "box"（包围盒）/ "mesh"（按模型的每个面）；true = "mesh"，false = "none"
        prop("collision", { str(ModelInstance.COLLISION_NAMES[it.collision]) }) { m, v ->
            val i = if (v.isboolean()) (if (v.toboolean()) ModelInstance.COLLISION_MESH else ModelInstance.COLLISION_NONE)
                else ModelInstance.COLLISION_NAMES.indexOf(v.tojstring())
            if (i < 0) fail("collision 只能是 \"none\"、\"box\"、\"mesh\"")
            m.collision = i
        }
        prop("scale", { n(it.scale) }) { m, v -> m.scale = v.tofloat().coerceIn(0.001f, 1000f) }
        prop("x", { n(it.position[0]) }) { m, v -> m.position[0] = v.tofloat() }
        prop("y", { n(it.position[1]) }) { m, v -> m.position[1] = v.tofloat() }
        prop("z", { n(it.position[2]) }) { m, v -> m.position[2] = v.tofloat() }
        prop("edge", { bool(it.drawEdge) }) { m, v -> m.drawEdge = v.toboolean() }
        prop("shadow", { bool(it.castShadow) }) { m, v -> m.castShadow = v.toboolean() }
        prop("physics", { bool(it.physicsEnabled) }) { m, v -> m.physicsEnabled = v.toboolean() }

        t.method("pos") { h, _ -> val m = modelOf(h); multi(m.position[0], m.position[1], m.position[2]) }
        t.action("set_pos") { h, a ->
            val m = modelOf(h); val id = m.id
            script.rememberSaved("m$id.pos") {
                val old = scene.findById(id)?.position?.copyOf()
                val back: () -> Unit = { if (old != null) scene.findById(id)?.setPosition(old[0], old[1], old[2]) }
                back
            }
            m.setPosition(a.f(1), a.f(2), a.f(3))
        }
        t.method("rot") { h, _ -> val m = modelOf(h); m.getRotationEuler(v3); multi(v3[0], v3[1], v3[2]) }
        t.action("set_rot") { h, a ->
            val m = modelOf(h); val id = m.id
            script.rememberSaved("m$id.rot") {
                val old = scene.findById(id)?.rotation?.copyOf()
                val back: () -> Unit = { if (old != null) scene.findById(id)?.let { System.arraycopy(old, 0, it.rotation, 0, 4) } }
                back
            }
            m.setRotationEuler(a.f(1), a.f(2), a.f(3))
        }
        t.method("bounds") { h, _ ->
            val m = modelOf(h); val lo = FloatArray(3); val hi = FloatArray(3)
            m.worldBounds(lo, hi)
            multi(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2])
        }
        t.method("bone") { h, a ->
            val m = modelOf(h)
            val i = findBone(m, a.checkjstring(1))
            if (i < 0) LuaValue.NIL else boneHandle(m, i)
        }
        t.method("bones") { h, a ->
            // 不带参数：全部骨骼的名字；带一段文字：名字里含这段文字的骨骼（句柄）
            val m = modelOf(h)
            val out = LuaTable()
            if (a.isnoneornil(1)) { for ((i, b) in m.data.bones.withIndex()) out.set(i + 1, str(b.name)) }
            else {
                val key = a.checkjstring(1)
                var k = 1
                for ((i, b) in m.data.bones.withIndex()) if (b.name.contains(key, true) || b.nameEn.contains(key, true)) out.set(k++, boneHandle(m, i))
            }
            out
        }
        t.method("morph") { h, a ->
            val m = modelOf(h)
            val name = a.checkjstring(1)
            val i = m.data.morphs.indexOfFirst { it.name == name }
            if (i < 0) return@method LuaValue.NIL
            if (a.isnoneornil(2)) return@method n(m.morphs.weightOf(i))
            touchMorphs(m)
            m.morphs.setScriptWeight(i, a.f(2))
            LuaValue.NONE
        }
        t.method("morphs") { h, _ ->
            val out = LuaTable()
            for ((i, mo) in modelOf(h).data.morphs.withIndex()) out.set(i + 1, str(mo.name))
            out
        }
        t.method("material") { h, a ->
            val m = modelOf(h)
            val i = if (a.isnumber(1)) a.checkint(1) - 1 else m.data.materials.indexOfFirst { it.name == a.checkjstring(1) }
            if (i < 0 || i >= m.materials.size) LuaValue.NIL else matHandle(m, i)
        }
        t.method("materials") { h, a ->
            // 不带参数：全部材质（句柄）；带一段文字：名字里含这段文字的
            val m = modelOf(h)
            val key = if (a.isnoneornil(1)) "" else a.checkjstring(1)
            val out = LuaTable()
            var k = 1
            for ((i, md) in m.data.materials.withIndex()) if (key.isEmpty() || md.name.contains(key, true)) out.set(k++, matHandle(m, i))
            out
        }
        t.method("bodies") { h, a ->
            val m = modelOf(h)
            val ph = m.physics
            val out = LuaTable()
            if (ph == null) return@method out
            val key = if (a.isnoneornil(1)) "" else a.checkjstring(1)
            var k = 1
            for (i in 0 until ph.bodyCount) if (key.isEmpty() || ph.bodyName(i).contains(key, true)) out.set(k++, bodyT.new().also { it.rawset("__id", n(m.id)); it.rawset("__b", n(i)) })
            out
        }
        t.action("root") { h, a ->
            // 整个骨架的位移和转向（度）。走的是骨骼，所以头发裙子会跟着甩
            val m = modelOf(h)
            val sk = m.skeleton ?: return@action
            touchBones(m)
            Quat.fromEulerYXZ(q, 0, a.f(5, 0f) * DEG, a.f(4, 0f) * DEG, a.f(6, 0f) * DEG)
            sk.setRoot(a.f(1, 0f), a.f(2, 0f), a.f(3, 0f), q[0], q[1], q[2], q[3])
        }
        t.action("attach") { h, a ->
            val m = modelOf(h); val id = m.id
            val parent = modelOf(a.arg1())
            if (parent === m) fail("不能把模型挂到它自己身上")
            keepAttach(id)
            m.attachParent = parent
            m.attachBone = if (a.isnoneornil(2)) -1 else findBone(parent, a.checkjstring(2))
        }
        t.action("detach") { h, _ -> val m = modelOf(h); keepAttach(m.id); m.attachParent = null; m.attachBone = -1 }
        t.action("reset") { h, _ ->
            val m = modelOf(h)
            m.skeleton?.clearScriptLayer(); m.skeleton?.clearRoot()
            m.morphs.clearScriptWeights()
            for (s in m.materials) s.clearFx()
            m.poseDirty = true
        }
        t.action("physics_set") { h, a ->
            // 这个模型自己的物理倍数：m:physics_set("stiffness", 2)
            val m = modelOf(h); val id = m.id
            val key = a.checkjstring(1)
            // 一项一项地记：脚本只动了 stiffness，用户同时在界面上调的别的参数不会被它带走
            script.rememberSaved("m$id.tuning.$key") {
                val old = scene.findById(id)?.physicsTuning?.get(key) ?: PhysicsTuning.DEFAULT.get(key)
                val back: () -> Unit = {
                    val t2 = scene.findById(id)
                    if (t2 != null && old != null) {
                        val tn2 = t2.physicsTuning ?: PhysicsTuning().also { x -> t2.physicsTuning = x }
                        tn2.set(key, old)
                        if (tn2.isDefault) t2.physicsTuning = null
                    }
                }
                back
            }
            val tn = m.physicsTuning ?: PhysicsTuning().also { m.physicsTuning = it }
            if (!tn.set(key, a.f(2))) fail("没有叫「${a.checkjstring(1)}」的物理参数。可用：" + PhysicsTuning.NAMES.joinToString(" "))
        }
        t.action("physics_reset") { h, _ -> modelOf(h).physics?.requestReset() }
    }

    // ------------------------------------------------------------------ bone

    private fun installBone() {
        val t = boneT
        t.getters["name"] = { h -> val m = modelOf(h); str(m.data.bones[boneIndex(h, m)].name) }
        t.getters["index"] = { h -> n(h.rawget("__b").toint() + 1) }
        t.getters["parent"] = { h ->
            val m = modelOf(h); val p = m.skeleton!!.parent[boneIndex(h, m)]
            if (p < 0) LuaValue.NIL else boneHandle(m, p)
        }
        // 在动作的基础上再转 / 再移（度；顺序同 MMD）
        t.action("rotate") { h, a ->
            val m = modelOf(h); val b = boneIndex(h, m); touchBones(m)
            Quat.fromEulerYXZ(q, 0, a.f(1, 0f) * DEG, a.f(2, 0f) * DEG, a.f(3, 0f) * DEG)
            m.skeleton!!.setScriptRotation(b, 1, q[0], q[1], q[2], q[3])
        }
        // 不管动作怎么写，直接定成这个角度
        t.action("set_rot") { h, a ->
            val m = modelOf(h); val b = boneIndex(h, m); touchBones(m)
            Quat.fromEulerYXZ(q, 0, a.f(1, 0f) * DEG, a.f(2, 0f) * DEG, a.f(3, 0f) * DEG)
            m.skeleton!!.setScriptRotation(b, 2, q[0], q[1], q[2], q[3])
        }
        t.action("set_quat") { h, a ->
            val m = modelOf(h); val b = boneIndex(h, m); touchBones(m)
            var x = a.f(1); var y = a.f(2); var z = a.f(3); var w = a.f(4)
            val l = kotlin.math.sqrt(x * x + y * y + z * z + w * w)
            if (l < 1e-6f) { x = 0f; y = 0f; z = 0f; w = 1f } else { x /= l; y /= l; z /= l; w /= l }
            m.skeleton!!.setScriptRotation(b, 2, x, y, z, w)
        }
        t.action("move") { h, a ->
            val m = modelOf(h); val b = boneIndex(h, m); touchBones(m)
            m.skeleton!!.setScriptTranslation(b, 1, a.f(1, 0f), a.f(2, 0f), a.f(3, 0f))
        }
        t.action("set_move") { h, a ->
            val m = modelOf(h); val b = boneIndex(h, m); touchBones(m)
            m.skeleton!!.setScriptTranslation(b, 2, a.f(1, 0f), a.f(2, 0f), a.f(3, 0f))
        }
        // 缩放：一个数 = 三个方向一起；绑在这根骨上的部分变大变小，子骨离它的距离跟着变
        t.action("scale") { h, a ->
            val m = modelOf(h); val b = boneIndex(h, m); touchBones(m)
            val sx = a.f(1, 1f); val sy = a.f(2, sx); val sz = a.f(3, if (a.isnoneornil(2)) sx else 1f)
            m.skeleton!!.setScriptScale(b, sx.coerceIn(0.01f, 100f), sy.coerceIn(0.01f, 100f), sz.coerceIn(0.01f, 100f))
        }
        t.action("clear") { h, _ ->
            val m = modelOf(h); val b = boneIndex(h, m); val sk = m.skeleton!!
            sk.setScriptRotation(b, 0, 0f, 0f, 0f, 1f); sk.setScriptTranslation(b, 0, 0f, 0f, 0f); sk.setScriptScale(b, 1f, 1f, 1f)
            m.poseDirty = true
        }
        // 世界坐标里的位置（上一帧算出来的）
        t.method("pos") { h, _ ->
            val m = modelOf(h); val b = boneIndex(h, m)
            m.boneWorldPosition(b, v3, 0)
            multi(v3[0], v3[1], v3[2])
        }
        // 当前的旋转（动作 + 脚本 + 手动姿势合起来，度）
        t.method("rot") { h, _ ->
            val m = modelOf(h); val b = boneIndex(h, m)
            m.skeleton!!.baseRotation(b, q, 0)
            Quat.toEulerYXZ(q, 0, v3, 0)
            multi(v3[0] / DEG, v3[1] / DEG, v3[2] / DEG)
        }
        t.method("children") { h, _ ->
            val m = modelOf(h); val b = boneIndex(h, m)
            val out = LuaTable(); var k = 1
            val par = m.skeleton!!.parent
            for (i in par.indices) if (par[i] == b) out.set(k++, boneHandle(m, i))
            out
        }
    }

    // ------------------------------------------------------------------ material

    private fun matHandle(m: ModelInstance, i: Int): LuaTable = matT.new().also { it.rawset("__id", n(m.id)); it.rawset("__b", n(i)) }

    private fun installMaterial() {
        val t = matT
        fun st(h: LuaTable): com.lumi.mmd.engine.anim.MaterialState {
            val m = modelOf(h)
            val i = h.rawget("__b").toint()
            if (i < 0 || i >= m.materials.size) fail("这块材质已经不存在了")
            touchMaterials(m)
            return m.materials[i]
        }
        t.getters["name"] = { h -> val m = modelOf(h); str(m.data.materials[h.rawget("__b").toint()].name) }
        t.getters["index"] = { h -> n(h.rawget("__b").toint() + 1) }
        t.getters["alpha"] = { h -> n(st(h).fxAlpha) }
        t.setters["alpha"] = { h, v -> st(h).fxAlpha = v.tofloat().coerceIn(0f, 1f) }
        t.getters["visible"] = { h -> bool(st(h).fxVisible) }
        t.setters["visible"] = { h, v -> st(h).fxVisible = v.toboolean() }
        t.getters["edge"] = { h -> n(st(h).fxEdge) }
        t.setters["edge"] = { h, v -> st(h).fxEdge = v.tofloat().coerceIn(0f, 20f) }
        // 光照方式："mmd"（toon）/ "lit"（普通光照）/ "unlit"（不受光，原色）；nil = 材质自己的
        val shadings = listOf("mmd", "lit", "unlit")
        t.getters["shading"] = { h -> val s = st(h); str(shadings[(if (s.fxShading >= 0) s.fxShading else s.base.shading).coerceIn(0, 2)]) }
        t.setters["shading"] = { h, v ->
            if (v.isnil()) st(h).fxShading = -1
            else { val i = shadings.indexOf(v.tojstring()); if (i < 0) fail("shading 只能是 \"mmd\"、\"lit\"、\"unlit\""); st(h).fxShading = i }
        }
        // 这块材质单独的三渲二级数：数字 / false（这块不要）/ nil（跟着 render 的设置）
        t.getters["cel"] = { h -> val s = st(h); if (s.fxCel > 0) n(s.fxCel) else if (s.fxCel == 0) LuaValue.FALSE else LuaValue.NIL }
        t.setters["cel"] = { h, v -> st(h).fxCel = if (v.isnil()) -1 else if (v.isboolean()) (if (v.toboolean()) 2 else 0) else v.toint().coerceIn(0, 8) }
        // 颜色乘：(1,1,1) 是原样
        t.action("color") { h, a -> val s = st(h); s.fxMul[0] = a.f(1, 1f); s.fxMul[1] = a.f(2, 1f); s.fxMul[2] = a.f(3, 1f) }
        // 发光：往颜色上加
        t.action("glow") { h, a -> val s = st(h); s.fxAdd[0] = a.f(1, 0f); s.fxAdd[1] = a.f(2, 0f); s.fxAdd[2] = a.f(3, 0f) }
        // 染色：往 (r,g,b) 这个颜色靠，amount 0..1
        t.action("tint") { h, a -> val s = st(h); s.fxTint[0] = a.f(1, 0f); s.fxTint[1] = a.f(2, 0f); s.fxTint[2] = a.f(3, 0f); s.fxTint[3] = a.f(4, 1f).coerceIn(0f, 1f) }
        t.action("reset") { h, _ -> st(h).clearFx() }
    }

    // ------------------------------------------------------------------ rigid body

    private fun installBody() {
        val t = bodyT
        fun ph(h: LuaTable): com.lumi.mmd.engine.physics.ModelPhysics = modelOf(h).physics ?: fail("这个模型没有物理")
        fun idx(h: LuaTable, p: com.lumi.mmd.engine.physics.ModelPhysics): Int {
            val i = h.rawget("__b").toint()
            if (i < 0 || i >= p.bodyCount) fail("这个刚体已经不存在了")
            return i
        }
        val modes = arrayOf("bone", "physics", "aligned")
        t.getters["name"] = { h -> val p = ph(h); str(p.bodyName(idx(h, p))) }
        t.getters["bone"] = { h ->
            val m = modelOf(h); val p = ph(h); val b = p.bodyBone(idx(h, p))
            if (b < 0) LuaValue.NIL else boneHandle(m, b)
        }
        t.getters["mode"] = { h -> val p = ph(h); str(modes[p.bodyMode(idx(h, p)).coerceIn(0, 2)]) }
        t.setters["mode"] = { h, v ->
            val m = modelOf(h); val p = ph(h); val i = idx(h, p)
            val want = modes.indexOf(v.tojstring())
            if (want < 0) fail("刚体的 mode 只能是 \"bone\"（跟着骨骼）、\"physics\"（物理）、\"aligned\"（物理 + 位置对齐骨骼）")
            val old = p.bodyMode(i); val id = m.id
            script.remember("body$id.$i.mode") { scene.findById(id)?.physics?.setBodyMode(i, old) }
            p.setBodyMode(i, want)
        }
        fun prop(name: String, get: (com.lumi.mmd.engine.physics.PhysicsWorld.Body) -> Float, set: (com.lumi.mmd.engine.physics.PhysicsWorld.Body, Float) -> Unit) {
            t.getters[name] = { h -> val p = ph(h); n(get(p.body(idx(h, p)))) }
            t.setters[name] = { h, v ->
                val m = modelOf(h); val p = ph(h); val i = idx(h, p); val id = m.id
                val b = p.body(i); val old = get(b)
                script.remember("body$id.$i.$name") { scene.findById(id)?.physics?.let { pp -> if (i < pp.bodyCount) set(pp.body(i), old) } }
                set(b, v.tofloat())
            }
        }
        prop("damping", { it.linDamp }) { b, v -> b.linDamp = v.coerceIn(0f, 1f) }
        prop("angular_damping", { it.angDamp }) { b, v -> b.angDamp = v.coerceIn(0f, 1f) }
        prop("friction", { it.friction }) { b, v -> b.friction = v.coerceIn(0f, 10f) }
        prop("bounce", { it.restitution }) { b, v -> b.restitution = v.coerceIn(0f, 1f) }
        prop("mass", { if (it.invMass > 0f) 1f / it.invMass else 0f }) { b, v ->
            if (!b.kinematic && v > 1e-4f && b.invMass > 0f) {
                val k = (1f / v) / b.invMass
                b.invMass *= k; b.invIx *= k; b.invIy *= k; b.invIz *= k
            }
        }
        // 推一下（冲量，模型空间）
        t.action("impulse") { h, a -> val p = ph(h); p.impulse(idx(h, p), a.f(1, 0f), a.f(2, 0f), a.f(3, 0f)) }
        t.method("pos") { h, _ ->
            val m = modelOf(h); val p = ph(h); val b = p.body(idx(h, p))
            com.lumi.mmd.engine.math.Mat4.transformPoint(m.worldMatrix, 0, b.px, b.py, b.pz, v3, 0)
            multi(v3[0], v3[1], v3[2])
        }
    }

    // ------------------------------------------------------------------ physics / camera / light / post

    private fun installPhysics(g: Globals) {
        val p = LuaTable()
        // 一项一项地记（而不是整套参数存一份）：脚本只改了风，用户同时在「物理」页里调的别的参数不受影响
        fun keep(vararg names: String) {
            for (name in names) script.rememberSaved("physics.$name") {
                val old = scene.physics.get(name)
                val back: () -> Unit = { if (old != null) scene.physics.set(name, old) }
                back
            }
        }
        p.set("set", proc { a ->
            val name = a.checkjstring(1)
            keep(name)
            if (!scene.physics.set(name, if (a.arg(2).isboolean()) (if (a.toboolean(2)) 1f else 0f) else a.f(2)))
                fail("没有叫「$name」的物理参数。可用：" + PhysicsTuning.NAMES.joinToString(" "))
        })
        p.set("get", fn { a -> scene.physics.get(a.checkjstring(1))?.let { n(it) } ?: LuaValue.NIL })
        p.set("wind", proc { a ->
            keep("windX", "windY", "windZ")
            scene.physics.windX = a.f(1, 0f); scene.physics.windY = a.f(2, 0f); scene.physics.windZ = a.f(3, 0f)
            if (!a.isnoneornil(4)) { keep("turbulence"); scene.physics.turbulence = a.f(4).coerceIn(0f, 1f) }
        })
        p.set("gravity", proc { a -> keep("gravity"); scene.physics.gravity = a.f(1, 1f).coerceIn(-5f, 5f) })
        p.set("enabled", proc { a ->
            script.rememberSaved("physics.enabled") {
                val on = scene.physicsEnabled
                val back: () -> Unit = { scene.physicsEnabled = on }
                back
            }
            scene.physicsEnabled = a.toboolean(1)
        })
        p.set("reset", proc { for (m in scene.models) m.physics?.requestReset() })
        g.set("physics", p)
    }

    private fun installCamera(g: Globals) {
        val c = LuaTable()
        fun keep() {
            val cam = scene.camera
            val saved = com.lumi.mmd.engine.scene.Camera().also { it.copyFrom(cam) }
            val locked = scene.cameraLocked
            script.remember("camera") { scene.camera.copyFrom(saved); scene.cameraLocked = locked }
        }
        c.set("get", fn {
            val cam = scene.camera
            val t = LuaTable()
            t.set("x", n(cam.position[0])); t.set("y", n(cam.position[1])); t.set("z", n(cam.position[2]))
            t.set("tx", n(cam.target[0])); t.set("ty", n(cam.target[1])); t.set("tz", n(cam.target[2]))
            t.set("distance", n(cam.distance)); t.set("fov", n(cam.fovDeg))
            t.set("pitch", n(cam.rotX / DEG)); t.set("yaw", n(cam.rotY / DEG)); t.set("roll", n(cam.rotZ / DEG))
            t
        })
        // 接管镜头：给了哪一项就改哪一项。接管期间镜头动作不生效，脚本停掉或 camera.release() 后恢复
        c.set("set", proc { a ->
            val o = a.checktable(1)
            keep()
            val cam = scene.camera
            scene.cameraLocked = true
            cam.target[0] = o.num("tx", cam.target[0]); cam.target[1] = o.num("ty", cam.target[1]); cam.target[2] = o.num("tz", cam.target[2])
            cam.distance = o.num("distance", cam.distance).coerceIn(0.1f, 5000f)
            cam.fovDeg = o.num("fov", cam.fovDeg).coerceIn(1f, 150f)
            cam.rotX = o.num("pitch", cam.rotX / DEG) * DEG; cam.rotY = o.num("yaw", cam.rotY / DEG) * DEG; cam.rotZ = o.num("roll", cam.rotZ / DEG) * DEG
        })
        c.set("release", proc { scene.cameraLocked = false })
        c.set("shake", proc { a -> host.effects.shake(a.f(1, 1f), a.f(2, 0.4f)) })
        // 世界坐标 → 屏幕位置（0..1，左上角是 0,0）；在镜头后面返回 nil
        c.set("project", fn { a ->
            val ok = scene.camera.project(a.f(1), a.f(2), a.f(3), 1000, 1000, v3)
            if (!ok) LuaValue.NIL else multi(v3[0] / 1000f, v3[1] / 1000f)
        })
        g.set("camera", c)

        val l = LuaTable()
        fun keepLight() {
            script.rememberSaved("light") {
                val d = scene.light.direction.copyOf(); val col = scene.light.color.copyOf()
                val back: () -> Unit = { System.arraycopy(d, 0, scene.light.direction, 0, 3); System.arraycopy(col, 0, scene.light.color, 0, 3) }
                back
            }
        }
        l.set("dir", proc { a -> keepLight(); scene.light.direction[0] = a.f(1); scene.light.direction[1] = a.f(2); scene.light.direction[2] = a.f(3) })
        l.set("color", proc { a -> keepLight(); scene.light.color[0] = a.f(1); scene.light.color[1] = a.f(2); scene.light.color[2] = a.f(3) })
        l.set("get", fn { val d = scene.light.direction; val col = scene.light.color; multi(d[0], d[1], d[2], col[0], col[1], col[2]) })
        g.set("light", l)

        val post = LuaTable()
        fun keepPost() {
            val s = host.settings
            // 画风那一组是跟着工程保存的：存盘时要能把脚本的改动临时拿掉
            script.rememberSaved("post") {
                val saved = com.lumi.mmd.engine.render.RenderSettings().also { s.copyLookTo(it) }
                val back: () -> Unit = { saved.copyLookTo(s) }
                back
            }
        }
        fun color(v: LuaValue, dst: FloatArray, max: Float = 4f): Boolean {
            if (!v.istable() && !v.isnumber()) return false
            readVec(v, dst, minOf(dst.size, if (v.istable()) maxOf(v.length(), 3) else 3), "rgba")
            for (i in dst.indices) dst[i] = dst[i].coerceIn(0f, max)
            return true
        }
        // 画面怎么画：post.set 和 render.set 是同一个函数（post 是老名字）
        val setLook = proc { a ->
            val o = a.checktable(1)
            keepPost()
            val s = host.settings
            fun flag(k: String, d: Boolean): Boolean { val v = o.get(k); return if (v.isboolean()) v.toboolean() else d }
            val b = o.get("bloom")
            if (b.isnumber()) { s.bloomStrength = b.tofloat().coerceIn(0f, 3f); s.bloom = s.bloomStrength > 0f } else if (b.isboolean()) s.bloom = b.toboolean()
            s.bloomThreshold = o.num("bloom_threshold", s.bloomThreshold).coerceIn(0f, 1f)
            s.exposure = o.num("exposure", s.exposure).coerceIn(0.05f, 8f)
            s.saturation = o.num("saturation", s.saturation).coerceIn(0f, 3f)
            s.vignette = o.num("vignette", s.vignette).coerceIn(0f, 1f)
            s.contrast = o.num("contrast", s.contrast).coerceIn(0f, 4f)
            s.posterize = o.num("posterize", s.posterize.toFloat()).toInt().coerceIn(0, 64)
            color(o.get("grade"), s.grade)
            s.rim = o.num("rim", s.rim).coerceIn(0f, 3f)
            val rc = o.get("rim_color")
            if (rc.isboolean() && !rc.toboolean()) s.rimColor[3] = 0f else if (!rc.isnil()) { s.rimColor[3] = 1f; color(rc, s.rimColor) }
            s.grid = flag("grid", s.grid)
            // 影子
            val sh = o.get("shadows")
            if (sh.isnumber()) { s.shadowStrength = sh.tofloat().coerceIn(0f, 1f); s.shadows = s.shadowStrength > 0f } else if (sh.isboolean()) s.shadows = sh.toboolean()
            s.groundShadow = flag("ground_shadow", s.groundShadow)
            // 描边
            val ed = o.get("edges")
            if (ed.isnumber()) { s.edgeWidth = ed.tofloat().coerceIn(0f, 10f); s.edges = s.edgeWidth > 0f } else if (ed.isboolean()) s.edges = ed.toboolean()
            s.edgeWidth = o.num("edge_width", s.edgeWidth).coerceIn(0f, 10f)
            val ec = o.get("edge_color")
            if (ec.isboolean() && !ec.toboolean()) s.edgeColor[3] = 0f else if (!ec.isnil()) { s.edgeColor[3] = 1f; color(ec, s.edgeColor, 1f) }
            s.edgeAll = flag("edge_all", s.edgeAll)
            // 三渲二
            val cel = o.get("cel")
            if (cel.isnumber()) s.celBands = cel.toint().coerceIn(0, 8) else if (cel.isboolean()) s.celBands = if (cel.toboolean()) 2 else 0
            s.celSoftness = o.num("cel_soft", s.celSoftness).coerceIn(0f, 1f)
            s.celShift = o.num("cel_shift", s.celShift).coerceIn(-1f, 1f)
            s.celSpecular = o.num("cel_specular", s.celSpecular).coerceIn(0f, 4f)
            color(o.get("shade"), s.shadeColor)
            s.specular = o.num("specular", s.specular).coerceIn(0f, 8f)
            // 环境光（上 / 下两个颜色）
            color(o.get("sky"), s.skyColor); color(o.get("ground"), s.groundColor)
            val bg = o.get("background")
            if (bg.istable()) {
                if (bg.get(1).istable()) { color(bg.get(1), s.backgroundTop, 1f); color(if (bg.get(2).istable()) bg.get(2) else bg.get(1), s.backgroundBottom, 1f) }
                else { color(bg, s.backgroundTop, 1f); color(bg, s.backgroundBottom, 1f) }
                s.customBackground = true
            }
        }
        fun rgb(c: FloatArray, count: Int = 3): LuaValue { val t = LuaTable(); for (i in 0 until count) t.set(i + 1, n(c[i])); return t }
        val getLook = fn {
            val s = host.settings
            val t = LuaTable()
            t.set("bloom", n(if (s.bloom) s.bloomStrength else 0f)); t.set("bloom_threshold", n(s.bloomThreshold))
            t.set("exposure", n(s.exposure)); t.set("saturation", n(s.saturation)); t.set("vignette", n(s.vignette))
            t.set("contrast", n(s.contrast)); t.set("posterize", n(s.posterize)); t.set("grade", rgb(s.grade))
            t.set("rim", n(s.rim)); t.set("rim_color", if (s.rimColor[3] > 0f) rgb(s.rimColor) else LuaValue.FALSE)
            t.set("grid", bool(s.grid)); t.set("shadows", n(if (s.shadows) s.shadowStrength else 0f)); t.set("ground_shadow", bool(s.groundShadow))
            t.set("edges", bool(s.edges)); t.set("edge_width", n(s.edgeWidth)); t.set("edge_all", bool(s.edgeAll))
            t.set("edge_color", if (s.edgeColor[3] > 0f) rgb(s.edgeColor) else LuaValue.FALSE)
            t.set("cel", n(s.celBands)); t.set("cel_soft", n(s.celSoftness)); t.set("cel_shift", n(s.celShift)); t.set("cel_specular", n(s.celSpecular))
            t.set("shade", rgb(s.shadeColor)); t.set("specular", n(s.specular))
            t.set("sky", rgb(s.skyColor)); t.set("ground", rgb(s.groundColor))
            val bg = LuaTable(); bg.set(1, rgb(s.backgroundTop)); bg.set(2, rgb(s.backgroundBottom)); t.set("background", bg)
            t
        }
        post.set("set", setLook)
        post.set("get", getLook)
        // 背景色：一组 = 纯色，两组 = 上下渐变
        val background = proc { a ->
            keepPost()
            val s = host.settings
            s.backgroundTop[0] = a.f(1); s.backgroundTop[1] = a.f(2); s.backgroundTop[2] = a.f(3)
            s.backgroundBottom[0] = a.f(4, a.f(1)); s.backgroundBottom[1] = a.f(5, a.f(2)); s.backgroundBottom[2] = a.f(6, a.f(3))
            s.customBackground = true
        }
        post.set("background", background)
        g.set("post", post)

        val render = LuaTable()
        render.set("set", setLook)
        render.set("get", getLook)
        render.set("background", background)
        // 回到脚本动它之前的样子
        render.set("reset", proc { script.undo("post") })
        // 一句话换成三渲二：render.toon() 或 render.toon{ bands = 2, shade = {..}, edge = 1.4, edge_color = {..}, rim = 0.4 }
        render.set("toon", proc { a ->
            val o = if (a.arg(1).istable()) a.arg(1) else LuaTable()
            keepPost()
            val s = host.settings
            s.applyToon(o.num("bands", 2f).toInt())
            s.celSoftness = o.num("soft", s.celSoftness).coerceIn(0f, 1f)
            s.celShift = o.num("shift", 0f).coerceIn(-1f, 1f)
            s.celSpecular = o.num("specular", 0f).coerceIn(0f, 4f)
            color(o.get("shade"), s.shadeColor)
            s.edgeWidth = o.num("edge", s.edgeWidth).coerceIn(0f, 10f)
            val ec = o.get("edge_color")
            if (!ec.isnil() && !ec.isboolean()) { s.edgeColor[3] = 1f; color(ec, s.edgeColor, 1f) }
            s.rim = o.num("rim", s.rim).coerceIn(0f, 3f)
            s.saturation = o.num("saturation", s.saturation).coerceIn(0f, 3f)
        })
        g.set("render", render)

        // ---- 地板 ----
        val fl = LuaTable()
        fun keepFloor() {
            script.rememberSaved("floor") {
                val old = com.lumi.mmd.engine.scene.Floor().also { it.copyFrom(scene.floor) }
                val back: () -> Unit = { scene.floor.copyFrom(old) }
                back
            }
        }
        fl.set("set", proc { a ->
            val o = a.checktable(1)
            keepFloor()
            val f = scene.floor
            fun flag(k: String, d: Boolean): Boolean { val v = o.get(k); return if (v.isboolean()) v.toboolean() else d }
            f.enabled = flag("enabled", f.enabled)
            f.height = o.num("height", f.height).coerceIn(-100000f, 100000f)
            f.grid = flag("grid", f.grid); f.shadow = flag("shadow", f.shadow); f.collide = flag("collide", f.collide)
            f.cell = o.num("cell", f.cell).coerceIn(0.1f, 1000f)
            val c = o.get("color")
            if (c.isboolean() && !c.toboolean()) f.color[3] = 0f
            else if (c.istable()) { if (c.length() < 4 && !c.get("a").isnumber()) f.color[3] = 1f; color(c, f.color, 1f) }
            val gc = o.get("grid_color")
            if (gc.istable()) color(gc, f.gridColor, 1f)
        })
        fl.set("get", fn {
            val f = scene.floor
            val t = LuaTable()
            t.set("enabled", bool(f.enabled)); t.set("height", n(f.height)); t.set("grid", bool(f.grid)); t.set("shadow", bool(f.shadow))
            t.set("collide", bool(f.collide)); t.set("cell", n(f.cell)); t.set("color", rgb(f.color, 4)); t.set("grid_color", rgb(f.gridColor, 4))
            t
        })
        fl.set("remove", proc { keepFloor(); scene.floor.enabled = false })
        // 加回一块默认的地板
        fl.set("reset", proc { keepFloor(); scene.floor.reset() })
        g.set("floor", fl)
    }

    // ------------------------------------------------------------------ fx

    private fun readAnchor(o: LuaValue, anchor: Anchor) {
        val m = o.get("model")
        if (m.istable()) {
            anchor.model = modelOf(m).id
            val b = o.get("bone")
            anchor.bone = if (b.isstring()) b.tojstring() else if (b.istable()) { val mm = modelOf(b); mm.data.bones[b.rawget("__b").toint()].name } else ""
        } else if (m.isboolean() && !m.toboolean()) { anchor.model = 0; anchor.bone = "" }
        readVec(o.get("offset"), anchor.offset, 3)
    }

    private fun configure(e: Emitter, o: LuaValue) {
        val tex = o.get("texture"); if (!tex.isnil()) e.texture = texOf(tex)
        val add = o.get("additive"); if (add.isboolean()) e.additive = add.toboolean()
        e.rate = o.num("rate", e.rate).coerceIn(0f, 5000f)
        val life = o.get("life")
        if (life.isnumber()) { e.lifeMin = life.tofloat(); e.lifeMax = life.tofloat() }
        else if (life.istable()) { e.lifeMin = life.get(1).optdouble(e.lifeMin.toDouble()).toFloat(); e.lifeMax = life.get(2).optdouble(e.lifeMin.toDouble()).toFloat() }
        val sp = o.get("speed")
        if (sp.isnumber()) { e.speedMin = sp.tofloat(); e.speedMax = sp.tofloat() }
        else if (sp.istable()) { e.speedMin = sp.get(1).optdouble(e.speedMin.toDouble()).toFloat(); e.speedMax = sp.get(2).optdouble(e.speedMin.toDouble()).toFloat() }
        readVec(o.get("dir"), e.dir, 3)
        e.spread = o.num("spread", e.spread)
        val gv = o.get("gravity")
        if (gv.isnumber()) { e.gravity[0] = 0f; e.gravity[1] = gv.tofloat(); e.gravity[2] = 0f } else readVec(gv, e.gravity, 3)
        e.drag = o.num("drag", e.drag).coerceIn(0f, 60f)
        val size = o.get("size")
        if (size.isnumber()) { e.sizeStart = size.tofloat(); e.sizeEnd = size.tofloat() }
        else if (size.istable()) { e.sizeStart = size.get(1).optdouble(e.sizeStart.toDouble()).toFloat(); e.sizeEnd = size.get(2).optdouble(e.sizeStart.toDouble()).toFloat() }
        e.sizeJitter = o.num("size_jitter", e.sizeJitter).coerceIn(0f, 1f)
        if (readVec(o.get("color"), e.colorStart, 4, "rgba") && o.get("color_end").isnil()) {
            // 只给了一个颜色：结尾用同样的颜色淡出
            e.colorEnd[0] = e.colorStart[0]; e.colorEnd[1] = e.colorStart[1]; e.colorEnd[2] = e.colorStart[2]; e.colorEnd[3] = 0f
        }
        readVec(o.get("color_end"), e.colorEnd, 4, "rgba")
        e.spin = o.num("spin", e.spin)
        readVec(o.get("pos"), e.pos, 3)
        readVec(o.get("area"), e.area, 3)
        readAnchor(o, e.anchor)
        val en = o.get("enabled"); if (en.isboolean()) e.enabled = en.toboolean()
    }

    private fun textureName(name: String): String {
        if (com.lumi.mmd.engine.fx.EffectTextures.has(name)) return name
        return host.resolveImage(name) ?: fail("没有叫「$name」的粒子贴图。内置的有：" + com.lumi.mmd.engine.fx.EffectTextures.NAMES.joinToString(" ") + "；也可以写资源库里一张图片的名字")
    }

    private fun installFx(g: Globals) {
        val fx = LuaTable()
        fx.set("emitter", fn { a ->
            val o = if (a.istable(1)) a.checktable(1) else LuaTable()
            val e = host.effects.newEmitter(owner, o.num("max", 1500f).toInt()) ?: fail("粒子发射器太多了（最多 ${Effects.MAX_EMITTERS} 个）")
            // 默认：往上飘、慢慢淡出的柔光点
            e.speedMin = 1f; e.speedMax = 2f; e.lifeMin = 0.8f; e.lifeMax = 1.4f; e.sizeStart = 0.6f; e.sizeEnd = 0.1f; e.rate = 30f
            configure(e, o)
            emitters[e.id] = e
            emitterT.new().also { it.rawset("__id", n(e.id)) }
        })
        fun em(h: LuaTable): Emitter = emitters[h.rawget("__id").toint()]?.takeIf { host.effects.emitters.contains(it) } ?: fail("这个发射器已经被移除了")
        emitterT.getters["count"] = { h -> n(em(h).count) }
        emitterT.action("set") { h, a -> configure(em(h), a.checktable(1)) }
        emitterT.action("burst") { h, a -> em(h).burst(a.checkint(1)) }
        emitterT.action("move") { h, a -> val e = em(h); e.pos[0] = a.f(1); e.pos[1] = a.f(2); e.pos[2] = a.f(3) }
        emitterT.action("attach") { h, a ->
            val e = em(h)
            e.anchor.model = modelOf(a.arg1()).id
            e.anchor.bone = if (a.isnoneornil(2)) "" else a.checkjstring(2)
            e.anchor.offset[0] = a.f(3, 0f); e.anchor.offset[1] = a.f(4, 0f); e.anchor.offset[2] = a.f(5, 0f)
        }
        emitterT.action("stop") { h, _ -> em(h).enabled = false }
        emitterT.action("start") { h, _ -> em(h).enabled = true }
        emitterT.action("clear") { h, _ -> em(h).clearParticles() }
        emitterT.action("remove") { h, _ -> emitters.remove(h.rawget("__id").toint())?.let { host.effects.emitters.remove(it) } }

        fun configureSprite(s: Sprite, o: LuaValue) {
            val tex = o.get("texture"); if (!tex.isnil()) s.texture = texOf(tex)
            val add = o.get("additive"); if (add.isboolean()) s.additive = add.toboolean()
            val vis = o.get("visible"); if (vis.isboolean()) s.visible = vis.toboolean()
            s.size = o.num("size", s.size)
            s.rotation = o.num("rotation", s.rotation)
            readVec(o.get("color"), s.color, 4, "rgba")
            readVec(o.get("pos"), s.pos, 3)
            readAnchor(o, s.anchor)
        }
        fx.set("sprite", fn { a ->
            val s = host.effects.newSprite(owner) ?: fail("贴片太多了（最多 ${Effects.MAX_SPRITES} 个）")
            if (a.istable(1)) configureSprite(s, a.checktable(1))
            sprites[s.id] = s
            spriteT.new().also { it.rawset("__id", n(s.id)) }
        })
        fun spr(h: LuaTable): Sprite = sprites[h.rawget("__id").toint()]?.takeIf { host.effects.sprites.contains(it) } ?: fail("这个贴片已经被移除了")
        spriteT.action("set") { h, a -> configureSprite(spr(h), a.checktable(1)) }
        spriteT.action("move") { h, a -> val s = spr(h); s.pos[0] = a.f(1); s.pos[1] = a.f(2); s.pos[2] = a.f(3) }
        spriteT.action("remove") { h, _ -> sprites.remove(h.rawget("__id").toint())?.let { host.effects.sprites.remove(it) } }

        fun configureTrail(t: Trail, o: LuaValue) {
            val add = o.get("additive"); if (add.isboolean()) t.additive = add.toboolean()
            val en = o.get("enabled"); if (en.isboolean()) t.enabled = en.toboolean()
            t.width = o.num("width", t.width).coerceIn(0.01f, 50f)
            t.life = o.num("life", t.life).coerceIn(0.05f, 10f)
            if (readVec(o.get("color"), t.colorStart, 4, "rgba") && o.get("color_end").isnil()) {
                t.colorEnd[0] = t.colorStart[0]; t.colorEnd[1] = t.colorStart[1]; t.colorEnd[2] = t.colorStart[2]; t.colorEnd[3] = 0f
            }
            readVec(o.get("color_end"), t.colorEnd, 4, "rgba")
            readVec(o.get("pos"), t.pos, 3)
            readAnchor(o, t.anchor)
        }
        fx.set("trail", fn { a ->
            val t = host.effects.newTrail(owner) ?: fail("拖尾太多了（最多 ${Effects.MAX_TRAILS} 条）")
            if (a.istable(1)) configureTrail(t, a.checktable(1))
            trails[t.id] = t
            trailT.new().also { it.rawset("__id", n(t.id)) }
        })
        fun tr(h: LuaTable): Trail = trails[h.rawget("__id").toint()]?.takeIf { host.effects.trails.contains(it) } ?: fail("这条拖尾已经被移除了")
        trailT.action("set") { h, a -> configureTrail(tr(h), a.checktable(1)) }
        trailT.action("move") { h, a -> val t = tr(h); t.pos[0] = a.f(1); t.pos[1] = a.f(2); t.pos[2] = a.f(3) }
        trailT.action("clear") { h, _ -> tr(h).clearPoints() }
        trailT.action("remove") { h, _ -> trails.remove(h.rawget("__id").toint())?.let { host.effects.trails.remove(it) } }

        // 整屏闪一下 / 一直盖着一层颜色
        fx.set("flash", proc { a -> host.effects.flash(a.f(1, 1f), a.f(2, 1f), a.f(3, 1f), a.f(4, 1f), a.f(5, 0.3f)) })
        fx.set("screen", proc { a ->
            val sc = host.effects.screen
            val old = sc.copyOf()
            script.remember("screen") { System.arraycopy(old, 0, host.effects.screen, 0, 4) }
            sc[0] = a.f(1, 0f); sc[1] = a.f(2, 0f); sc[2] = a.f(3, 0f); sc[3] = a.f(4, 0f).coerceIn(0f, 1f)
        })
        // 画一条线：只画这一帧，想一直有就每帧都调用
        fx.set("line", proc { a ->
            engine.addLine(a.f(1), a.f(2), a.f(3), a.f(4), a.f(5), a.f(6), a.f(7, 1f), a.f(8, 1f), a.f(9, 1f), a.f(10, 1f), a.f(11, 2f))
        })
        fx.set("textures", fn { val out = LuaTable(); for ((i, nm) in com.lumi.mmd.engine.fx.EffectTextures.NAMES.withIndex()) out.set(i + 1, str(nm)); out })
        g.set("fx", fx)
    }

    // ------------------------------------------------------------------ ui

    private fun installUi(g: Globals) {
        val ui = LuaTable()
        // ui.text("id", "文字", {x=0.5, y=0.9, size=18, color={1,1,1,1}})；文字给 nil = 去掉
        ui.set("text", proc { a ->
            val id = a.checkjstring(1)
            val text = if (a.isnoneornil(2)) null else a.arg(2).tojstring()
            val o = if (a.istable(3)) a.checktable(3) else LuaTable()
            val c = floatArrayOf(1f, 1f, 1f, 1f)
            readVec(o.get("color"), c, 4, "rgba")
            fun ch(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val argb = (ch(c[3]) shl 24) or (ch(c[0]) shl 16) or (ch(c[1]) shl 8) or ch(c[2])
            host.uiText(owner, id, text?.take(200), o.num("x", 0.5f), o.num("y", 0.88f), o.num("size", 16f).coerceIn(6f, 96f), argb)
        })
        ui.set("button", proc { a ->
            val id = a.checkjstring(1)
            val label = if (a.isnoneornil(2)) null else a.arg(2).tojstring()
            val o = if (a.istable(3)) a.checktable(3) else LuaTable()
            host.uiButton(owner, id, label?.take(12), o.num("x", 0.5f), o.num("y", 0.75f))
        })
        ui.set("clear", proc { host.uiClear(owner) })
        ui.set("toast", proc { a -> host.toast(a.arg1().tojstring().take(200)) })
        g.set("ui", ui)
    }

    // ------------------------------------------------------------------ 画布 / 网格 / 物理

    private val canvasT = LuaType("canvas")
    private val meshT = LuaType("mesh")
    private val worldT = LuaType("world")
    private val colliderT = LuaType("collider")
    private val rigidT = LuaType("rigid")
    private val canvases = HashMap<Int, com.lumi.mmd.engine.fx.Canvas>()
    private val meshes = HashMap<Int, com.lumi.mmd.engine.fx.ScriptMesh>()
    private val worlds = HashMap<Int, com.lumi.mmd.engine.fx.SimWorld>()
    private val shapes = HashMap<Int, com.lumi.mmd.engine.fx.Collider>()
    private val rigids = HashMap<Int, com.lumi.mmd.engine.fx.RigidBody>()
    private val ca = FloatArray(4)
    private val cb = FloatArray(4)
    private val t8 = FloatArray(8)

    private fun floats(t: LuaValue, max: Int): FloatArray {
        if (!t.istable()) fail("这里需要一张数字表，比如 {0,0,0, 1,0,0, 0,1,0}")
        val n = minOf(t.length(), max)
        return FloatArray(n) { t.get(it + 1).tofloat() }
    }

    private fun canvasOf(h: LuaValue): com.lumi.mmd.engine.fx.Canvas =
        canvases[h.rawget("__id").toint()]?.takeIf { host.effects.canvases.contains(it) } ?: fail("这块画布已经被移除了")

    /** 贴图参数：内置名字、资源库里的图片名，或者一块画布。 */
    private fun texOf(v: LuaValue): String {
        if (v.istable() && v.getmetatable() === canvasT.meta) return com.lumi.mmd.engine.render.EffectRenderer.CANVAS_PREFIX + canvasOf(v).id
        val s = v.tojstring()
        if (s.startsWith(com.lumi.mmd.engine.render.EffectRenderer.CANVAS_PREFIX)) return s
        return textureName(s)
    }

    private fun eulerToQuat(rx: Float, ry: Float, rz: Float, out: FloatArray) = Quat.fromEulerYXZ(out, 0, rx * DEG, ry * DEG, rz * DEG)

    private fun installCanvas(g: Globals) {
        val t = LuaTable()
        t.set("new", fn { a ->
            val w = a.checkint(1); val h = a.optint(2, w)
            val max = com.lumi.mmd.engine.fx.Canvas.MAX_SIZE
            if (w < 1 || h < 1 || w > max || h > max) fail("画布的边长要在 1..$max 之间")
            val c = host.effects.newCanvas(owner, w, h) ?: fail("画布太多了（最多 ${Effects.MAX_CANVASES} 块）")
            if (a.istable(3)) { c.repeat = a.arg(3).get("repeat").toboolean(); c.nearest = a.arg(3).get("nearest").toboolean() }
            canvases[c.id] = c
            canvasT.new().also { it.rawset("__id", n(c.id)) }
        })
        g.set("canvas", t)
        val ct = canvasT
        ct.getters["width"] = { h -> n(canvasOf(h).width) }
        ct.getters["height"] = { h -> n(canvasOf(h).height) }
        ct.getters["name"] = { h -> str(com.lumi.mmd.engine.render.EffectRenderer.CANVAS_PREFIX + canvasOf(h).id) }
        ct.action("clear") { h, a -> canvasOf(h).clear(a.f(1, 0f), a.f(2, 0f), a.f(3, 0f), a.f(4, 0f)) }
        ct.action("color") { h, a -> canvasOf(h).color(a.f(1, 1f), a.f(2, 1f), a.f(3, 1f), a.f(4, 1f)) }
        ct.action("linear") { h, a ->
            ca.fill(1f); cb.fill(1f); readVec(a.arg(5), ca, 4, "rgba"); readVec(a.arg(6), cb, 4, "rgba")
            canvasOf(h).linear(a.f(1), a.f(2), a.f(3), a.f(4), ca, cb)
        }
        ct.action("radial") { h, a ->
            ca.fill(1f); cb.fill(1f); readVec(a.arg(5), ca, 4, "rgba"); readVec(a.arg(6), cb, 4, "rgba")
            canvasOf(h).radial(a.f(1), a.f(2), a.f(3), a.f(4), ca, cb)
        }
        ct.action("blend") { h, a ->
            val i = com.lumi.mmd.engine.fx.Canvas.BLEND_NAMES.indexOf(a.checkjstring(1))
            if (i < 0) fail("混合方式只能是 " + com.lumi.mmd.engine.fx.Canvas.BLEND_NAMES.joinToString(" / "))
            canvasOf(h).blend = i
        }
        ct.action("feather") { h, a -> canvasOf(h).feather = a.f(1, 0f).coerceIn(0f, 256f) }
        ct.action("circle") { h, a -> canvasOf(h).circle(a.f(1), a.f(2), a.f(3)) }
        ct.action("ring") { h, a -> canvasOf(h).ring(a.f(1), a.f(2), a.f(3), a.f(4, 1f)) }
        ct.action("ellipse") { h, a -> canvasOf(h).ellipse(a.f(1), a.f(2), a.f(3), a.f(4), a.f(5, 0f) * DEG) }
        ct.action("rect") { h, a -> canvasOf(h).rect(a.f(1), a.f(2), a.f(3), a.f(4), a.f(5, 0f)) }
        ct.action("line") { h, a -> canvasOf(h).line(a.f(1), a.f(2), a.f(3), a.f(4), a.f(5, 1f)) }
        ct.action("poly") { h, a -> canvasOf(h).polygon(floats(a.arg1(), 512)) }
        ct.method("pixel") { h, a ->
            val c = canvasOf(h)
            if (a.narg() <= 2) { if (c.get(a.checkint(1), a.checkint(2), v4)) multi(v4[0], v4[1], v4[2], v4[3]) else LuaValue.NIL }
            else { c.set(a.checkint(1), a.checkint(2), a.f(3), a.f(4), a.f(5), a.f(6, 1f)); LuaValue.NONE }
        }
        // 逐像素：f(x, y, r, g, b, a) 返回新的 r, g, b, a（不返回 = 不改）
        ct.action("each") { h, a ->
            val c = canvasOf(h); val f = a.checkfunction(1)
            val px = c.pixels
            for (y in 0 until c.height) for (x in 0 until c.width) {
                val o = (y * c.width + x) * 4
                val r = f.invoke(LuaValue.varargsOf(arrayOf(n(x), n(y), n((px[o].toInt() and 255) / 255f), n((px[o + 1].toInt() and 255) / 255f),
                    n((px[o + 2].toInt() and 255) / 255f), n((px[o + 3].toInt() and 255) / 255f))))
                if (r.narg() >= 3) for (k in 0 until 4) {
                    val v = if (k < r.narg()) r.arg(k + 1).tofloat() else 1f
                    px[o + k] = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
                }
            }
            c.touch()
        }
        ct.action("noise") { h, a -> canvasOf(h).noise(a.f(1, 0.2f), a.f(2, 8f), a.optint(3, 1)) }
        ct.action("blur") { h, a -> canvasOf(h).blur(a.f(1, 2f)) }
        // 把别的图画上来：另一块画布、某块材质原来的贴图、资源库里的图片
        ct.action("image") { h, a ->
            val c = canvasOf(h)
            val src = a.arg1()
            val x = a.f(2, 0f); val y = a.f(3, 0f); val w = a.f(4, c.width.toFloat()); val hh = a.f(5, c.height.toFloat()); val alpha = a.f(6, 1f)
            if (src.istable() && src.getmetatable() === canvasT.meta) { c.drawCanvas(canvasOf(src), x, y, w, hh, alpha); return@action }
            val bytes: ByteArray; val hint: String
            if (src.istable() && src.getmetatable() === matT.meta) {
                val m = modelOf(src); val md = m.data.materials[src.rawget("__b").toint()]
                if (md.texture < 0) fail("材质「${md.name}」没有贴图")
                val ref = m.data.textures[md.texture]
                hint = ref.path
                bytes = ref.embedded ?: com.lumi.mmd.engine.render.TextureResolver.resolve(m.data.baseDir, ref.path)?.readBytes() ?: fail("找不到材质「${md.name}」的贴图文件 ${ref.path}")
            } else {
                hint = src.checkjstring()
                bytes = host.imageBytes(hint) ?: fail("资源库里没有叫「$hint」的图片")
            }
            val img = com.lumi.mmd.engine.image.Images.decode(bytes, hint, com.lumi.mmd.engine.fx.Canvas.MAX_SIZE) ?: fail("这张图片解不出来：$hint")
            val p = img.pixels
            c.drawImage({ p.get(it).toInt() }, img.width, img.height, x, y, w, hh, alpha)
        }
        ct.action("remove") { h, _ -> canvases.remove(h.rawget("__id").toint())?.let { host.effects.canvases.remove(it) } }

        // 材质换成自己画的贴图：mat:texture(画布)；mat:texture(nil) 换回原来的
        matT.action("texture") { h, a ->
            val m = modelOf(h); val i = h.rawget("__b").toint()
            if (i < 0 || i >= m.materials.size) fail("这块材质已经不存在了")
            touchMaterials(m)
            m.materials[i].fxCanvas = if (a.isnoneornil(1)) 0 else canvasOf(a.arg1()).id
        }
        // 这块材质在贴图上占的范围：u0, v0, u1, v1（0..1）。画瞳孔之前先用它找到眼睛在贴图的哪一块
        matT.method("uv_bounds") { h, _ ->
            val m = modelOf(h); val i = h.rawget("__b").toint()
            val d = m.data
            var first = 0
            for (k in 0 until i) first += d.materials[k].indexCount
            val cnt = d.materials[i].indexCount
            var u0 = Float.MAX_VALUE; var v0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE; var v1 = -Float.MAX_VALUE
            for (k in first until first + cnt) {
                val v = d.indices[k]
                val u = d.uvs[v * 2]; val w = d.uvs[v * 2 + 1]
                if (u < u0) u0 = u; if (u > u1) u1 = u; if (w < v0) v0 = w; if (w > v1) v1 = w
            }
            if (cnt == 0) LuaValue.NIL else multi(u0, v0, u1, v1)
        }
    }

    private fun meshOf(h: LuaValue): com.lumi.mmd.engine.fx.ScriptMesh =
        meshes[h.rawget("__id").toint()]?.takeIf { host.effects.meshes.contains(it) } ?: fail("这个网格已经被移除了")

    private fun meshHandle(m: com.lumi.mmd.engine.fx.ScriptMesh): LuaTable { meshes[m.id] = m; return meshT.new().also { it.rawset("__id", n(m.id)) } }

    private fun configureMesh(m: com.lumi.mmd.engine.fx.ScriptMesh, o: LuaValue) {
        val tex = o.get("texture"); if (!tex.isnil()) m.texture = if (tex.isboolean()) "" else texOf(tex)
        readVec(o.get("color"), m.color, 4, "rgba")
        fun flag(k: String, d: Boolean): Boolean { val v = o.get(k); return if (v.isboolean()) v.toboolean() else d }
        m.lit = flag("lit", m.lit); m.additive = flag("additive", m.additive); m.doubleSided = flag("double_sided", m.doubleSided)
        m.visible = flag("visible", m.visible); m.transparent = flag("transparent", m.transparent); m.autoNormals = flag("auto_normals", m.autoNormals)
        m.shine = o.num("shine", m.shine).coerceIn(0f, 4f); m.glow = o.num("glow", m.glow).coerceIn(0f, 1f)
        m.pointSize = o.num("point_size", m.pointSize).coerceIn(1f, 64f)
        val mode = o.get("mode")
        if (mode.isstring()) {
            val i = com.lumi.mmd.engine.fx.ScriptMesh.MODE_NAMES.indexOf(mode.tojstring())
            if (i < 0) fail("mode 只能是 triangles / lines / points")
            m.mode = i
        }
        readVec(o.get("pos"), m.pos, 3)
        val rot = o.get("rot")
        if (rot.istable()) { v3[0] = 0f; v3[1] = 0f; v3[2] = 0f; readVec(rot, v3, 3); eulerToQuat(v3[0], v3[1], v3[2], m.quat) }
        readVec(o.get("scale"), m.scale, 3)
        readAnchor(o, m.anchor)
    }

    private fun installMesh(g: Globals) {
        val t = LuaTable()
        t.set("new", fn { a ->
            val m = host.effects.newMesh(owner) ?: fail("网格太多了（最多 ${Effects.MAX_MESHES} 个）")
            if (a.istable(1)) configureMesh(m, a.arg1())
            meshHandle(m)
        })
        g.set("mesh", t)
        val mt = meshT
        val maxV = com.lumi.mmd.engine.fx.ScriptMesh.MAX_VERTICES
        fun vi(m: com.lumi.mmd.engine.fx.ScriptMesh, a: Varargs): Int {
            val i = a.checkint(1) - 1
            if (i < 0 || i >= m.vertexCount) fail("顶点编号 ${i + 1} 超出范围（1..${m.vertexCount}）")
            return i
        }
        mt.getters["count"] = { h -> n(meshOf(h).vertexCount) }
        mt.getters["triangles"] = { h -> n(meshOf(h).indexCount / 3) }
        mt.action("set") { h, a -> configureMesh(meshOf(h), a.checktable(1)) }
        mt.action("grid") { h, a -> meshOf(h).grid(a.checkint(1), a.checkint(2), a.f(3, 10f), a.f(4, 10f)) }
        mt.action("sphere") { h, a -> meshOf(h).sphere(a.f(1, 1f), a.optint(2, 24)) }
        mt.action("box") { h, a -> meshOf(h).box(a.f(1, 1f), a.f(2, 1f), a.f(3, 1f)) }
        mt.action("resize") { h, a -> meshOf(h).resize(a.checkint(1)) }
        mt.action("build") { h, a ->
            val m = meshOf(h); val o = a.checktable(1)
            val p = floats(o.get("positions"), maxV * 3)
            m.resize(0); m.resize(p.size / 3)
            System.arraycopy(p, 0, m.positions, 0, m.vertexCount * 3)
            val nm = o.get("normals"); if (nm.istable()) { val f = floats(nm, m.vertexCount * 3); System.arraycopy(f, 0, m.normals, 0, f.size) }
            val uv = o.get("uvs"); if (uv.istable()) { val f = floats(uv, m.vertexCount * 2); System.arraycopy(f, 0, m.uvs, 0, f.size) }
            val col = o.get("colors"); if (col.istable()) { val f = floats(col, m.vertexCount * 4); System.arraycopy(f, 0, m.colors, 0, f.size) }
            val ix = o.get("indices")
            if (ix.istable()) { val f = floats(ix, com.lumi.mmd.engine.fx.ScriptMesh.MAX_INDICES); m.setIndices(IntArray(f.size) { f[it].toInt() - 1 }, f.size) }
            else m.setIndices(IntArray(m.vertexCount) { it }, m.vertexCount)
            if (!nm.istable()) m.recalcNormals()
            m.version++
        }
        mt.action("indices") { h, a -> val m = meshOf(h); val f = floats(a.arg1(), com.lumi.mmd.engine.fx.ScriptMesh.MAX_INDICES); m.setIndices(IntArray(f.size) { f[it].toInt() - 1 }, f.size) }
        mt.method("positions") { h, a ->
            val m = meshOf(h)
            if (a.isnoneornil(1)) { val out = LuaTable(m.vertexCount * 3, 0); for (i in 0 until m.vertexCount * 3) out.rawset(i + 1, n(m.positions[i])); out }
            else { val f = floats(a.arg1(), m.vertexCount * 3); System.arraycopy(f, 0, m.positions, 0, f.size); m.version++; LuaValue.NONE }
        }
        mt.method("vertex") { h, a ->
            val m = meshOf(h); val i = vi(m, a)
            if (a.narg() == 1) multi(m.positions[i * 3], m.positions[i * 3 + 1], m.positions[i * 3 + 2])
            else { m.positions[i * 3] = a.f(2); m.positions[i * 3 + 1] = a.f(3); m.positions[i * 3 + 2] = a.f(4); m.version++; LuaValue.NONE }
        }
        mt.method("normal") { h, a ->
            val m = meshOf(h); val i = vi(m, a)
            if (a.narg() == 1) multi(m.normals[i * 3], m.normals[i * 3 + 1], m.normals[i * 3 + 2])
            else { m.normals[i * 3] = a.f(2); m.normals[i * 3 + 1] = a.f(3); m.normals[i * 3 + 2] = a.f(4); m.version++; LuaValue.NONE }
        }
        mt.method("uv") { h, a ->
            val m = meshOf(h); val i = vi(m, a)
            if (a.narg() == 1) multi(m.uvs[i * 2], m.uvs[i * 2 + 1])
            else { m.uvs[i * 2] = a.f(2); m.uvs[i * 2 + 1] = a.f(3); m.version++; LuaValue.NONE }
        }
        mt.method("vcolor") { h, a ->
            val m = meshOf(h); val i = vi(m, a)
            if (a.narg() == 1) multi(m.colors[i * 4], m.colors[i * 4 + 1], m.colors[i * 4 + 2], m.colors[i * 4 + 3])
            else { m.colors[i * 4] = a.f(2); m.colors[i * 4 + 1] = a.f(3); m.colors[i * 4 + 2] = a.f(4); m.colors[i * 4 + 3] = a.f(5, 1f); m.version++; LuaValue.NONE }
        }
        mt.action("recalc") { h, _ -> meshOf(h).recalcNormals() }
        mt.action("move") { h, a -> val m = meshOf(h); m.pos[0] = a.f(1); m.pos[1] = a.f(2); m.pos[2] = a.f(3) }
        mt.action("rotate") { h, a -> eulerToQuat(a.f(1, 0f), a.f(2, 0f), a.f(3, 0f), meshOf(h).quat) }
        mt.action("scale") { h, a -> val m = meshOf(h); m.scale[0] = a.f(1, 1f); m.scale[1] = a.f(2, a.f(1, 1f)); m.scale[2] = a.f(3, a.f(1, 1f)) }
        mt.action("attach") { h, a ->
            val m = meshOf(h)
            if (a.isnoneornil(1)) { m.anchor.model = 0; m.anchor.bone = "" }
            else { m.anchor.model = modelOf(a.arg1()).id; m.anchor.bone = if (a.isnoneornil(2)) "" else a.checkjstring(2) }
        }
        // 顶点直接跟着物理世界的质点走：第 i 个顶点 = 第 first+i-1 个质点（布料、软体）
        mt.action("follow") { h, a ->
            val m = meshOf(h)
            if (a.isnoneornil(1)) { m.followSim = null; return@action }
            m.followSim = worldOf(a.arg1()); m.followFirst = (a.optint(2, 1) - 1).coerceAtLeast(0)
        }
        mt.action("remove") { h, _ -> meshes.remove(h.rawget("__id").toint())?.let { host.effects.removeMesh(it) } }
    }

    private fun worldOf(h: LuaValue): com.lumi.mmd.engine.fx.SimWorld =
        worlds[h.rawget("__id").toint()]?.takeIf { host.effects.sims.contains(it) } ?: fail("这个物理世界已经被移除了")

    private fun configureWorld(w: com.lumi.mmd.engine.fx.SimWorld, o: LuaValue) {
        val gv = o.get("gravity")
        if (gv.isnumber()) { w.gravity[0] = 0f; w.gravity[1] = gv.tofloat(); w.gravity[2] = 0f } else readVec(gv, w.gravity, 3)
        readVec(o.get("wind"), w.wind, 3)
        w.drag = o.num("drag", w.drag).coerceIn(0f, 1000f)
        w.damping = o.num("damping", w.damping).coerceIn(0f, 0.999f)
        w.substeps = o.num("substeps", w.substeps.toFloat()).toInt().coerceIn(1, 32)
        w.iterations = o.num("iterations", w.iterations.toFloat()).toInt().coerceIn(1, 32)
        val gr = o.get("ground")
        // 没写 ground：跟着场景的地板（地板删了、抬高了，粒子也跟着）；写了就按写的来
        if (gr.isboolean()) { w.ground = gr.toboolean(); w.groundAuto = false } else if (gr.isnumber()) { w.ground = true; w.groundY = gr.tofloat(); w.groundAuto = false }
        w.friction = o.num("friction", w.friction).coerceIn(0f, 10f)
        w.bounce = o.num("bounce", w.bounce).coerceIn(0f, 1f)
        fun flag(k: String, d: Boolean): Boolean { val v = o.get(k); return if (v.isboolean()) v.toboolean() else d }
        w.collideModels = flag("collide", w.collideModels); w.auto = flag("auto", w.auto); w.paused = flag("paused", w.paused); w.additive = flag("additive", w.additive)
        val sp = o.get("spacing"); if (sp.isnumber()) w.spacing = sp.tofloat()
        w.viscosity = o.num("viscosity", w.viscosity).coerceIn(0f, 1f)
        w.cohesion = o.num("cohesion", w.cohesion).coerceIn(0f, 1f)
        w.relaxation = o.num("relaxation", w.relaxation).coerceIn(0.001f, 10f)
        val bd = o.get("bounds")
        if (bd.istable()) { val f = floats(bd, 6); if (f.size == 6) { System.arraycopy(f, 0, w.bounds, 0, 6); w.hasBounds = true } else fail("bounds 要 6 个数：{x0,y0,z0, x1,y1,z1}") }
        else if (bd.isboolean() && !bd.toboolean()) w.hasBounds = false
        val rm = o.get("render")
        if (rm.isstring()) {
            val i = com.lumi.mmd.engine.fx.SimWorld.RENDER_NAMES.indexOf(rm.tojstring())
            if (i < 0) fail("render 只能是 " + com.lumi.mmd.engine.fx.SimWorld.RENDER_NAMES.joinToString(" / "))
            w.renderMode = i
        }
        readVec(o.get("color"), w.color, 4, "rgba")
        w.renderRadius = o.num("radius", w.renderRadius).coerceIn(0f, 50f)
        val tex = o.get("texture"); if (!tex.isnil()) w.texture = texOf(tex)
    }

    private fun installSim(g: Globals) {
        val t = LuaTable()
        val fxs = host.effects
        t.set("world", fn { a ->
            val o = if (a.istable(1)) a.checktable(1) else LuaTable()
            val w = fxs.newSim(owner, o.num("max", 2000f).toInt()) ?: fail("物理世界太多了（最多 ${Effects.MAX_SIMS} 个）")
            configureWorld(w, o)
            worlds[w.id] = w
            worldT.new().also { it.rawset("__id", n(w.id)) }
        })
        val wt = worldT
        fun pi(w: com.lumi.mmd.engine.fx.SimWorld, a: Varargs, k: Int = 1): Int {
            val i = a.checkint(k) - 1
            if (i < 0 || i >= w.count) fail("质点编号 ${i + 1} 超出范围（1..${w.count}）")
            return i
        }
        fun full(): Nothing = fail("质点放满了（建世界时用 max 调大，最多 ${com.lumi.mmd.engine.fx.SimWorld.MAX_PARTICLES}）")
        wt.getters["count"] = { h -> n(worldOf(h).count) }
        wt.getters["links"] = { h -> n(worldOf(h).constraintCount) }
        wt.getters["time"] = { h -> n(worldOf(h).time) }
        wt.getters["steps"] = { h -> LuaValue.valueOf(worldOf(h).steps.toDouble()) }
        wt.getters["contacts"] = { h -> n(worldOf(h).lastContacts) }
        wt.getters["energy"] = { h -> n(worldOf(h).kineticEnergy()) }
        wt.getters["on_step"] = { h -> h.rawget("__step") }
        // w.on_step = function(h) … end：每个子步开始时调用，在里面读写质点就是在写自己的物理
        wt.setters["on_step"] = { h, v ->
            val w = worldOf(h)
            h.rawset("__step", v)
            if (!v.isfunction()) w.hook = null
            else w.hook = { dt ->
                if (inLua) v.call(n(dt))
                else if (script.running) engine.call(script, v, ScriptEngine.FRAME_BUDGET, n(dt))
            }
        }
        wt.action("set") { h, a -> configureWorld(worldOf(h), a.checktable(1)) }
        wt.method("add") { h, a ->
            val w = worldOf(h)
            val o = if (a.istable(4)) a.arg(4) else LuaValue.NIL
            var mass = 1f; var rad = 0.2f; var flag = 0
            if (o.istable()) {
                mass = o.num("mass", 1f); rad = o.num("radius", 0.2f)
                if (o.get("collide").toboolean()) flag = flag or com.lumi.mmd.engine.fx.SimWorld.FLAG_COLLIDE
                if (o.get("fluid").toboolean()) { flag = flag or com.lumi.mmd.engine.fx.SimWorld.FLAG_FLUID; rad = o.num("radius", w.spacing * 0.5f) }
            }
            val i = w.add(a.f(1), a.f(2), a.f(3), mass, rad, flag)
            if (i < 0) full()
            if (o.istable()) {
                v3[0] = 0f; v3[1] = 0f; v3[2] = 0f
                if (readVec(o.get("vel"), v3, 3)) { w.vel[i * 3] = v3[0]; w.vel[i * 3 + 1] = v3[1]; w.vel[i * 3 + 2] = v3[2] }
                v4.fill(1f)
                if (readVec(o.get("color"), v4, 4, "rgba")) System.arraycopy(v4, 0, w.tint, i * 4, 4)
            }
            n(i + 1)
        }
        // 用液体粒子填满一个盒子：返回 第一个的编号, 个数
        wt.method("fill") { h, a ->
            val w = worldOf(h)
            val s = w.spacing
            val x0 = minOf(a.f(1), a.f(4)); val y0 = minOf(a.f(2), a.f(5)); val z0 = minOf(a.f(3), a.f(6))
            val x1 = maxOf(a.f(1), a.f(4)); val y1 = maxOf(a.f(2), a.f(5)); val z1 = maxOf(a.f(3), a.f(6))
            val first = w.count
            var y = y0 + s * 0.5f
            while (y < y1) {
                var x = x0 + s * 0.5f
                while (x < x1) {
                    var z = z0 + s * 0.5f
                    while (z < z1) { if (w.add(x, y, z, 1f, s * 0.5f, com.lumi.mmd.engine.fx.SimWorld.FLAG_FLUID) < 0) return@method LuaValue.varargsOf(n(first + 1), n(w.count - first)); z += s }
                    x += s
                }
                y += s
            }
            LuaValue.varargsOf(n(first + 1), n(w.count - first))
        }
        wt.method("emit") { h, a -> val i = worldOf(h).emitFluid(a.f(1), a.f(2), a.f(3), a.f(4, 0f), a.f(5, 0f), a.f(6, 0f)); if (i < 0) LuaValue.NIL else n(i + 1) }
        wt.method("link") { h, a ->
            val w = worldOf(h)
            val o = a.arg(3)
            val k = w.link(pi(w, a, 1), pi(w, a, 2), if (o.istable()) o.num("rest", -1f) else -1f, if (o.istable()) o.num("compliance", 0f) else 0f)
            if (k < 0) fail("约束加不进去（两个点相同，或者超过了 ${com.lumi.mmd.engine.fx.SimWorld.MAX_CONSTRAINTS} 条）")
            n(k + 1)
        }
        wt.action("unlink") { h, a -> worldOf(h).unlink(a.checkint(1) - 1) }
        wt.method("rest") { h, a ->
            val w = worldOf(h); val k = a.checkint(1) - 1
            if (k < 0 || k >= w.constraintCount) fail("约束编号超出范围")
            if (a.narg() == 1) n(w.linkRest(k)) else { w.setLinkRest(k, a.f(2)); LuaValue.NONE }
        }
        wt.method("ends") { h, a ->
            val w = worldOf(h); val k = a.checkint(1) - 1
            if (k < 0 || k >= w.constraintCount) fail("约束编号超出范围")
            LuaValue.varargsOf(n(w.linkA(k) + 1), n(w.linkB(k) + 1))
        }
        // 一条绳子：n 段，返回 第一个质点的编号, 个数
        wt.method("rope") { h, a ->
            val w = worldOf(h)
            val seg = a.checkint(7).coerceIn(1, 2000)
            val o = a.arg(8)
            val mass = if (o.istable()) o.num("mass", 1f) else 1f; val rad = if (o.istable()) o.num("radius", 0.1f) else 0.1f
            val comp = if (o.istable()) o.num("compliance", 0f) else 0f
            val flag = if (o.istable() && o.get("collide").toboolean()) com.lumi.mmd.engine.fx.SimWorld.FLAG_COLLIDE else 0
            val first = w.count
            for (i in 0..seg) {
                val k = i.toFloat() / seg
                val p = w.add(a.f(1) + (a.f(4) - a.f(1)) * k, a.f(2) + (a.f(5) - a.f(2)) * k, a.f(3) + (a.f(6) - a.f(3)) * k, mass, rad, flag)
                if (p < 0) full()
                if (i > 0) w.link(p - 1, p, -1f, comp)
                // 隔一个再连一条，绳子就有了抗弯的硬度
                if (i > 1 && o.istable() && o.get("bend").isnumber()) w.link(p - 2, p, -1f, o.get("bend").tofloat())
            }
            LuaValue.varargsOf(n(first + 1), n(seg + 1))
        }
        // 一块布：(nx+1)×(nz+1) 个质点，排法和 mesh:grid(nx, nz, w, d) 一样，所以 mesh:follow(world, first) 就能把它画出来
        wt.method("cloth") { h, a ->
            val w = worldOf(h)
            val nx = a.checkint(1).coerceIn(1, 200); val nz = a.checkint(2).coerceIn(1, 200)
            val wd = a.f(3, 10f); val dp = a.f(4, 10f)
            val o = if (a.istable(5)) a.arg(5) else LuaTable()
            val mass = o.num("mass", 1f); val rad = o.num("radius", 0.15f); val comp = o.num("compliance", 0f); val bend = o.num("bend", 0.0005f)
            val flag = if (o.get("collide").toboolean()) com.lumi.mmd.engine.fx.SimWorld.FLAG_COLLIDE else 0
            v3[0] = 0f; v3[1] = 0f; v3[2] = 0f; readVec(o.get("pos"), v3, 3)
            val ox = v3[0]; val oy = v3[1]; val oz = v3[2]
            val vertical = o.get("vertical").toboolean()
            val first = w.count
            if (w.capacity - first < (nx + 1) * (nz + 1)) full()
            for (r in 0..nz) for (c in 0..nx) {
                val lx = (c.toFloat() / nx - 0.5f) * wd; val lz = (r.toFloat() / nz - 0.5f) * dp
                if (vertical) w.add(ox + lx, oy - lz, oz, mass, rad, flag) else w.add(ox + lx, oy, oz + lz, mass, rad, flag)
            }
            fun id(r: Int, c: Int): Int = first + r * (nx + 1) + c
            for (r in 0..nz) for (c in 0..nx) {
                if (c < nx) w.link(id(r, c), id(r, c + 1), -1f, comp)
                if (r < nz) w.link(id(r, c), id(r + 1, c), -1f, comp)
                // 对角线：抗剪
                if (c < nx && r < nz) { w.link(id(r, c), id(r + 1, c + 1), -1f, comp); w.link(id(r, c + 1), id(r + 1, c), -1f, comp) }
                // 隔一个：抗弯
                if (bend >= 0f) { if (c + 2 <= nx) w.link(id(r, c), id(r, c + 2), -1f, bend); if (r + 2 <= nz) w.link(id(r, c), id(r + 2, c), -1f, bend) }
            }
            LuaValue.varargsOf(n(first + 1), n((nx + 1) * (nz + 1)))
        }
        wt.action("pin") { h, a ->
            val w = worldOf(h); val i = pi(w, a)
            val p = w.pin(i)
            if (!a.isnoneornil(2)) {
                p.anchor.model = modelOf(a.arg(2)).id
                p.anchor.bone = if (a.isnoneornil(3)) "" else a.checkjstring(3)
                if (a.narg() >= 6) { p.anchor.offset[0] = a.f(4); p.anchor.offset[1] = a.f(5); p.anchor.offset[2] = a.f(6) }
                else {
                    // 没给偏移：保持它现在相对骨骼的位置（按世界方向）
                    p.anchor.offset.fill(0f)
                    if (p.anchor.resolve(scene, v3)) { v4[0] = w.pos[i * 3] - v3[0]; v4[1] = w.pos[i * 3 + 1] - v3[1]; v4[2] = w.pos[i * 3 + 2] - v3[2]; pinOffset(p.anchor, v4) }
                }
                p.fresh = true
            } else { p.anchor.model = 0; for (k in 0 until 3) { p.from[k] = w.pos[i * 3 + k]; p.to[k] = w.pos[i * 3 + k] } }
        }
        wt.action("unpin") { h, a -> val w = worldOf(h); w.unpin(pi(w, a)) }
        wt.method("pos") { h, a ->
            val w = worldOf(h); val i = pi(w, a)
            if (a.narg() == 1) multi(w.pos[i * 3], w.pos[i * 3 + 1], w.pos[i * 3 + 2])
            else {
                w.pos[i * 3] = a.f(2); w.pos[i * 3 + 1] = a.f(3); w.pos[i * 3 + 2] = a.f(4)
                // 钉住的点：同时改它被钉的位置
                w.pins.firstOrNull { it.index == i }?.let { p -> if (!p.anchor.active) for (k in 0 until 3) { p.from[k] = w.pos[i * 3 + k]; p.to[k] = w.pos[i * 3 + k] } }
                LuaValue.NONE
            }
        }
        wt.method("vel") { h, a ->
            val w = worldOf(h); val i = pi(w, a)
            if (a.narg() == 1) multi(w.vel[i * 3], w.vel[i * 3 + 1], w.vel[i * 3 + 2])
            else { w.vel[i * 3] = a.f(2); w.vel[i * 3 + 1] = a.f(3); w.vel[i * 3 + 2] = a.f(4); LuaValue.NONE }
        }
        wt.action("force") { h, a -> val w = worldOf(h); val i = pi(w, a); w.force[i * 3] += a.f(2); w.force[i * 3 + 1] += a.f(3); w.force[i * 3 + 2] += a.f(4) }
        wt.action("impulse") { h, a -> val w = worldOf(h); val i = pi(w, a); val k = w.invMass[i]; w.vel[i * 3] += a.f(2) * k; w.vel[i * 3 + 1] += a.f(3) * k; w.vel[i * 3 + 2] += a.f(4) * k }
        wt.method("mass") { h, a ->
            val w = worldOf(h); val i = pi(w, a)
            if (a.narg() == 1) n(if (w.invMass[i] > 0f) 1f / w.invMass[i] else 0f)
            else { val m = a.f(2); w.invMass[i] = if (m > 1e-9f) 1f / m else 0f; LuaValue.NONE }
        }
        wt.method("radius") { h, a ->
            val w = worldOf(h); val i = pi(w, a)
            if (a.narg() == 1) n(w.radius[i]) else { w.radius[i] = a.f(2).coerceIn(0f, 100f); LuaValue.NONE }
        }
        wt.action("tint") { h, a -> val w = worldOf(h); val i = pi(w, a); w.tint[i * 4] = a.f(2, 1f); w.tint[i * 4 + 1] = a.f(3, 1f); w.tint[i * 4 + 2] = a.f(4, 1f); w.tint[i * 4 + 3] = a.f(5, 1f) }
        wt.method("density") { h, a -> val w = worldOf(h); n(w.density[pi(w, a)]) }
        wt.method("neighbors") { h, a ->
            val w = worldOf(h); val i = pi(w, a)
            val tmp = IntArray(com.lumi.mmd.engine.fx.SimWorld.MAX_NEIGHBOURS)
            val c = w.neighbours(i, tmp)
            val out = LuaTable(c, 0)
            for (k in 0 until c) out.rawset(k + 1, n(tmp[k] + 1))
            out
        }
        // 自己控制节奏：sim.world{auto=false}，然后每帧 w:step(dt)
        wt.action("step") { h, a ->
            val w = worldOf(h)
            val was = inLua
            inLua = true
            try { w.step(a.f(1, 1f / 60f).coerceIn(0f, 0.1f), fxs.worldColliders) } finally { inLua = was }
        }
        wt.action("clear") { h, _ -> worldOf(h).clear() }
        wt.action("remove") { h, _ -> worlds.remove(h.rawget("__id").toint())?.let { fxs.sims.remove(it) } }

        // ---- 碰撞体：粒子和脚本刚体都会撞上它 ----
        fun shapeOf(v: LuaValue): Int = when (if (v.isstring()) v.tojstring() else "sphere") {
            "sphere" -> com.lumi.mmd.engine.fx.Collider.SPHERE; "box" -> com.lumi.mmd.engine.fx.Collider.BOX; "capsule" -> com.lumi.mmd.engine.fx.Collider.CAPSULE
            else -> fail("shape 只能是 sphere / box / capsule")
        }
        fun configureCollider(c: com.lumi.mmd.engine.fx.Collider, o: LuaValue) {
            if (o.get("shape").isstring()) c.shape = shapeOf(o.get("shape"))
            val size = o.get("size")
            if (!size.isnil()) {
                v3[0] = 1f; v3[1] = 1f; v3[2] = 1f; readVec(size, v3, 3)
                when (c.shape) {
                    com.lumi.mmd.engine.fx.Collider.BOX -> { c.hx = v3[0] * 0.5f; c.hy = v3[1] * 0.5f; c.hz = v3[2] * 0.5f }
                    com.lumi.mmd.engine.fx.Collider.CAPSULE -> { c.radius = v3[0]; c.halfHeight = (if (size.istable()) v3[1] else 0f) * 0.5f }
                    else -> c.radius = v3[0]
                }
            }
            readVec(o.get("pos"), c.pos, 3)
            val rot = o.get("rot")
            if (rot.istable()) { v3[0] = 0f; v3[1] = 0f; v3[2] = 0f; readVec(rot, v3, 3); eulerToQuat(v3[0], v3[1], v3[2], c.quat) }
            c.friction = o.num("friction", c.friction).coerceIn(0f, 10f)
            readAnchor(o, c.anchor)
            c.updateBound()
        }
        t.set("collider", fn { a ->
            val c = fxs.newCollider(owner) ?: fail("碰撞体太多了（最多 ${Effects.MAX_COLLIDERS} 个）")
            configureCollider(c, a.checktable(1))
            shapes[c.id] = c
            colliderT.new().also { it.rawset("__id", n(c.id)) }
        })
        fun col(h: LuaValue): com.lumi.mmd.engine.fx.Collider = shapes[h.rawget("__id").toint()]?.takeIf { fxs.colliders.contains(it) } ?: fail("这个碰撞体已经被移除了")
        colliderT.action("set") { h, a -> configureCollider(col(h), a.checktable(1)) }
        colliderT.action("move") { h, a -> val c = col(h); c.pos[0] = a.f(1); c.pos[1] = a.f(2); c.pos[2] = a.f(3) }
        colliderT.action("rotate") { h, a -> eulerToQuat(a.f(1, 0f), a.f(2, 0f), a.f(3, 0f), col(h).quat) }
        colliderT.action("remove") { h, _ -> shapes.remove(h.rawget("__id").toint())?.let { fxs.colliders.remove(it) } }

        // ---- 刚体 ----
        t.set("body", fn { a ->
            val o = a.checktable(1)
            val rb = fxs.newBody(owner) ?: fail("刚体太多了（最多 ${com.lumi.mmd.engine.fx.RigidSim.MAX_BODIES} 个）")
            val b = rb.body
            val shape = shapeOf(o.get("shape"))
            v3[0] = 1f; v3[1] = 1f; v3[2] = 1f
            val size = o.get("size"); readVec(size, v3, 3)
            val mass = o.num("mass", 1f)
            val vis = fxs.newMesh(owner)
            val lx: Float; val ly: Float; val lz: Float
            when (shape) {
                com.lumi.mmd.engine.fx.Collider.BOX -> {
                    b.shape = com.lumi.mmd.engine.physics.PhysicsWorld.SHAPE_BOX; b.hx = v3[0] * 0.5f; b.hy = v3[1] * 0.5f; b.hz = v3[2] * 0.5f
                    lx = v3[0]; ly = v3[1]; lz = v3[2]; vis?.box(lx, ly, lz)
                }
                com.lumi.mmd.engine.fx.Collider.CAPSULE -> {
                    b.shape = com.lumi.mmd.engine.physics.PhysicsWorld.SHAPE_CAPSULE; b.radius = v3[0]; b.halfHeight = (if (size.istable()) v3[1] else 0f) * 0.5f
                    lx = 2f * b.radius; ly = 2f * (b.radius + b.halfHeight); lz = lx
                    vis?.sphere(1f, 20); vis?.let { it.scale[0] = b.radius; it.scale[1] = b.radius + b.halfHeight; it.scale[2] = b.radius }
                }
                else -> { b.shape = com.lumi.mmd.engine.physics.PhysicsWorld.SHAPE_SPHERE; b.radius = v3[0]; lx = 2f * b.radius; ly = lx; lz = lx; vis?.sphere(b.radius, 20) }
            }
            b.kinematic = mass <= 1e-9f
            if (!b.kinematic) {
                b.invMass = 1f / mass
                // 惯性张量和模型刚体的算法一致（Bullet 的 calculateLocalInertia）
                val ix: Float; val iy: Float; val iz: Float
                if (shape == com.lumi.mmd.engine.fx.Collider.SPHERE) { val e = 0.4f * mass * b.radius * b.radius; ix = e; iy = e; iz = e }
                else { ix = mass / 12f * (ly * ly + lz * lz); iy = mass / 12f * (lx * lx + lz * lz); iz = mass / 12f * (lx * lx + ly * ly) }
                b.invIx = 1f / maxOf(ix, 1e-9f); b.invIy = 1f / maxOf(iy, 1e-9f); b.invIz = 1f / maxOf(iz, 1e-9f)
            }
            b.friction = o.num("friction", 0.5f).coerceIn(0f, 10f); b.restitution = o.num("bounce", 0f).coerceIn(0f, 1f)
            b.linDamp = o.num("damping", 0.02f).coerceIn(0f, 1f); b.angDamp = o.num("angular_damping", 0.05f).coerceIn(0f, 1f)
            b.groupBit = 1; b.mask = 0xFFFF
            v3[0] = 0f; v3[1] = 0f; v3[2] = 0f; readVec(o.get("pos"), v3, 3)
            val px = v3[0]; val py = v3[1]; val pz = v3[2]
            q[0] = 0f; q[1] = 0f; q[2] = 0f; q[3] = 1f
            val rot = o.get("rot")
            if (rot.istable()) { v3[0] = 0f; v3[1] = 0f; v3[2] = 0f; readVec(rot, v3, 3); eulerToQuat(v3[0], v3[1], v3[2], q) }
            teleport(b, px, py, pz, q[0], q[1], q[2], q[3])
            v3[0] = 0f; v3[1] = 0f; v3[2] = 0f
            if (readVec(o.get("vel"), v3, 3)) { b.vx = v3[0]; b.vy = v3[1]; b.vz = v3[2] }
            if (vis != null) {
                if (o.get("visible").isboolean() && !o.get("visible").toboolean()) fxs.removeMesh(vis)
                else {
                    readVec(o.get("color"), vis.color, 4, "rgba")
                    val tex = o.get("texture"); if (!tex.isnil()) vis.texture = texOf(tex)
                    vis.followBody = b; rb.mesh = vis
                }
            }
            fxs.rigid.fill(rb)
            rigids[rb.id] = rb
            rigidT.new().also { it.rawset("__id", n(rb.id)) }
        })
        fun rig(h: LuaValue): com.lumi.mmd.engine.fx.RigidBody = rigids[h.rawget("__id").toint()]?.takeIf { fxs.rigid.bodies.contains(it) } ?: fail("这个刚体已经被移除了")
        rigidT.getters["mesh"] = { h -> rig(h).mesh?.takeIf { fxs.meshes.contains(it) }?.let { meshHandle(it) } ?: LuaValue.NIL }
        rigidT.getters["mass"] = { h -> val b = rig(h).body; n(if (b.invMass > 0f) 1f / b.invMass else 0f) }
        rigidT.method("pos") { h, a ->
            val b = rig(h).body
            if (a.narg() == 0) multi(b.px, b.py, b.pz) else { teleport(b, a.f(1), a.f(2), a.f(3), b.qx, b.qy, b.qz, b.qw); LuaValue.NONE }
        }
        rigidT.method("rot") { h, a ->
            val b = rig(h).body
            if (a.narg() == 0) { q[0] = b.qx; q[1] = b.qy; q[2] = b.qz; q[3] = b.qw; Quat.toEulerYXZ(q, 0, v3, 0); multi(v3[0] / DEG, v3[1] / DEG, v3[2] / DEG) }
            else { eulerToQuat(a.f(1, 0f), a.f(2, 0f), a.f(3, 0f), q); teleport(b, b.px, b.py, b.pz, q[0], q[1], q[2], q[3]); LuaValue.NONE }
        }
        rigidT.method("vel") { h, a ->
            val b = rig(h).body
            if (a.narg() == 0) multi(b.vx, b.vy, b.vz) else { b.vx = a.f(1); b.vy = a.f(2); b.vz = a.f(3); LuaValue.NONE }
        }
        rigidT.method("spin") { h, a ->
            val b = rig(h).body
            if (a.narg() == 0) multi(b.wx, b.wy, b.wz) else { b.wx = a.f(1); b.wy = a.f(2); b.wz = a.f(3); LuaValue.NONE }
        }
        rigidT.action("impulse") { h, a -> val b = rig(h).body; b.vx += a.f(1) * b.invMass; b.vy += a.f(2) * b.invMass; b.vz += a.f(3) * b.invMass }
        // 质量为 0 的刚体不受力，用 move 带着走（会按位移算出速度，把别的东西推开）
        rigidT.action("move") { h, a ->
            val b = rig(h).body
            if (a.narg() >= 6) eulerToQuat(a.f(4), a.f(5), a.f(6), q) else { q[0] = b.qx; q[1] = b.qy; q[2] = b.qz; q[3] = b.qw }
            if (b.kinematic) fxs.rigid.world.setKinematicTarget(b, a.f(1), a.f(2), a.f(3), q[0], q[1], q[2], q[3])
            else teleport(b, a.f(1), a.f(2), a.f(3), q[0], q[1], q[2], q[3])
        }
        rigidT.action("remove") { h, _ -> rigids.remove(h.rawget("__id").toint())?.let { fxs.removeBody(it) } }

        // 关节：6 自由度弹簧（和 PMX 的 Joint 同一种）。默认把两个刚体在 pos 这一点焊死；放开某个轴就给它上下限
        t.set("joint", proc { a ->
            val ra = rig(a.arg(1)); val rb = rig(a.arg(2))
            val o = if (a.istable(3)) a.arg(3) else LuaTable()
            val j = fxs.newJoint(owner, ra, rb).joint
            v3[0] = (ra.body.px + rb.body.px) * 0.5f; v3[1] = (ra.body.py + rb.body.py) * 0.5f; v3[2] = (ra.body.pz + rb.body.pz) * 0.5f
            readVec(o.get("pos"), v3, 3)
            frame(ra.body, v3, j.posA, j.rotA); frame(rb.body, v3, j.posB, j.rotB)
            readVec(o.get("lin_lo"), j.linLo, 3); readVec(o.get("lin_hi"), j.linHi, 3)
            if (readVec(o.get("ang_lo"), j.angLo, 3)) for (k in 0 until 3) j.angLo[k] *= DEG
            if (readVec(o.get("ang_hi"), j.angHi, 3)) for (k in 0 until 3) j.angHi[k] *= DEG
            readVec(o.get("lin_spring"), j.linK, 3); readVec(o.get("ang_spring"), j.angK, 3)
        })

        // ---- 查询：射线、最近距离（地面 + 模型刚体 + 脚本的碰撞体和刚体）----
        fun modelOfCollider(c: com.lumi.mmd.engine.fx.Collider, list: List<com.lumi.mmd.engine.fx.Collider>, i: Int): LuaValue {
            // 排在前面的是模型的刚体，它们的 id 记的是模型编号
            if (fxs.colliders.contains(c) || fxs.rigid.bodies.any { it.collider === c }) return LuaValue.NIL
            return scene.findById(c.id)?.let { handle(it) } ?: LuaValue.NIL
        }
        t.set("raycast", fn { a ->
            var dx = a.f(4); var dy = a.f(5); var dz = a.f(6)
            val l = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
            if (l < 1e-9f) fail("射线的方向不能是 0")
            dx /= l; dy /= l; dz /= l
            val maxD = a.f(7, 1000f)
            if (fxs.worldColliders.isEmpty()) fxs.refreshColliders(scene, 0f)
            var best = -1f; var hit: com.lumi.mmd.engine.fx.Collider? = null; var bi = -1
            var nx = 0f; var ny = 1f; var nz = 0f
            val floor = scene.floor
            if (floor.solid && dy < -1e-6f && a.f(2) > floor.height) { val tg = (floor.height - a.f(2)) / dy; if (tg <= maxD) best = tg }
            val list = fxs.worldColliders
            for (i in list.indices) {
                val c = list[i]
                // 先看射线离它的包围球够不够近
                val ox = c.px - a.f(1); val oy = c.py - a.f(2); val oz = c.pz - a.f(3)
                val along = ox * dx + oy * dy + oz * dz
                val d2 = ox * ox + oy * oy + oz * oz - along * along
                if (d2 > c.bound * c.bound || along < -c.bound) continue
                val tt = c.raycast(a.f(1), a.f(2), a.f(3), dx, dy, dz, if (best >= 0f) best else maxD, t8)
                if (tt >= 0f && (best < 0f || tt < best)) { best = tt; hit = c; bi = i; nx = t8[0]; ny = t8[1]; nz = t8[2] }
            }
            if (best < 0f) LuaValue.NIL
            else LuaValue.varargsOf(arrayOf(n(best), n(a.f(1) + dx * best), n(a.f(2) + dy * best), n(a.f(3) + dz * best), n(nx), n(ny), n(nz),
                if (hit != null) modelOfCollider(hit, list, bi) else LuaValue.NIL))
        })
        t.set("distance", fn { a ->
            if (fxs.worldColliders.isEmpty()) fxs.refreshColliders(scene, 0f)
            var best = if (scene.floor.solid) a.f(2) - scene.floor.height else Float.MAX_VALUE; var nx = 0f; var ny = 1f; var nz = 0f
            var hit: com.lumi.mmd.engine.fx.Collider? = null; var bi = -1
            val list = fxs.worldColliders
            for (i in list.indices) {
                val d = list[i].distance(a.f(1), a.f(2), a.f(3), t8)
                if (d < best) { best = d; nx = t8[0]; ny = t8[1]; nz = t8[2]; hit = list[i]; bi = i }
            }
            LuaValue.varargsOf(arrayOf(n(best), n(nx), n(ny), n(nz), if (hit != null) modelOfCollider(hit, list, bi) else LuaValue.NIL))
        })
        g.set("sim", t)
    }

    /** 正在执行脚本主动调用的 w:step（这时 on_step 里出的错要原样抛回给外层的脚本）。 */
    private var inLua = false

    private fun teleport(b: com.lumi.mmd.engine.physics.PhysicsWorld.Body, x: Float, y: Float, z: Float, qx: Float, qy: Float, qz: Float, qw: Float) {
        b.setPose(x, y, z, qx, qy, qz, qw)
        b.sx = x; b.sy = y; b.sz = z; b.sqx = qx; b.sqy = qy; b.sqz = qz; b.sqw = qw
        b.tx = x; b.ty = y; b.tz = z; b.tqx = qx; b.tqy = qy; b.tqz = qz; b.tqw = qw
        host.effects.rigid.world.updateRotation(b)
    }

    /** 关节在世界里的位置（轴向 = 世界坐标轴）换到刚体自己的坐标系里。 */
    private fun frame(b: com.lumi.mmd.engine.physics.PhysicsWorld.Body, p: FloatArray, outPos: FloatArray, outRot: FloatArray) {
        host.effects.rigid.world.updateRotation(b)
        val r = b.r
        val dx = p[0] - b.px; val dy = p[1] - b.py; val dz = p[2] - b.pz
        for (k in 0 until 3) outPos[k] = r[k * 3] * dx + r[k * 3 + 1] * dy + r[k * 3 + 2] * dz
        // Rᵀ · I
        for (c in 0 until 3) for (k in 0 until 3) outRot[c * 3 + k] = r[k * 3 + c]
    }

    /** 把「世界方向上的偏移」换成锚点（骨骼）自己坐标系里的偏移。 */
    private fun pinOffset(anchor: Anchor, world: FloatArray) {
        val m = FloatArray(16)
        if (!anchor.matrix(scene, m)) return
        // 只用旋转缩放部分的逆（按列正交处理）
        for (c in 0 until 3) {
            val l2 = m[c * 4] * m[c * 4] + m[c * 4 + 1] * m[c * 4 + 1] + m[c * 4 + 2] * m[c * 4 + 2]
            anchor.offset[c] = if (l2 > 1e-12f) (m[c * 4] * world[0] + m[c * 4 + 1] * world[1] + m[c * 4 + 2] * world[2]) / l2 else 0f
        }
    }

    companion object {
        const val MAX_OWNED = 64
    }
}

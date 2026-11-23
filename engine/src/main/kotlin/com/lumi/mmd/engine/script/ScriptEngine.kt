package com.lumi.mmd.engine.script

import com.lumi.mmd.engine.anim.Motion
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JseMathLib

/** 场景里的一个 Lua 脚本。 */
class Script(val id: Int, var name: String, source: String) {
    var source: String = source
        internal set
    /** 开关。出错的脚本会自己停下来，[error] 里是原因；改了代码或重新打开时清掉。 */
    var enabled = false
        internal set
    var error: String? = null
        internal set
    /** 只在时间轴的这一段里运行（帧）；end < 0 = 从头到尾一直运行。 */
    var start = 0
    var end = -1
    /** 作用于哪个模型（脚本里 scene.main() 返回它）；0 = 当前选中的角色。 */
    var target = 0
    /** 脚本在脚本库里的文件（保存工程时用）；空 = 还没存成文件。 */
    var file = ""
    /** 此刻是否在运行（开着、没出错、当前帧在它的时间段里）。 */
    var running = false
        internal set

    internal var globals: Globals? = null
    internal var budget: Budget? = null
    internal var api: LuaApi? = null
    internal var fnUpdate: LuaValue = LuaValue.NIL
    internal var fnTick: LuaValue = LuaValue.NIL
    internal var fnButton: LuaValue = LuaValue.NIL
    internal var fnEvent: LuaValue = LuaValue.NIL
    internal var fnExit: LuaValue = LuaValue.NIL
    internal var timeTable: LuaTable? = null
    internal var inputTable: LuaTable? = null
    internal var startedAt = 0f
    /** 脚本改过的东西怎么放回去：按"改的是什么"去重，脚本停掉时全部执行一遍。 */
    internal val restore = LinkedHashMap<String, Change>()
    /** 脚本自己创建的模型（几何体、分身），停掉时一起移除。 */
    internal val ownedModels = ArrayList<Int>()

    /**
     * 一处改动。[capture] 不为空表示这样东西会被存进工程（位置、透明度、物理参数……）：
     * 调用它会记下「现在的值」并返回一个放回去的函数，存盘时靠它把脚本的改动临时拿掉再放回来。
     */
    internal class Change(val undo: () -> Unit, val capture: (() -> (() -> Unit))?)

    /** 现在就把 key 这一项恢复回去（脚本自己想撤掉改动时用）。 */
    internal fun undo(key: String) { restore.remove(key)?.undo?.invoke() }

    /** 只在运行时存在的东西（脚本层的骨骼、材质效果、表情……）：停掉时执行 undo 即可。 */
    internal fun remember(key: String, undo: () -> Unit) {
        if (!restore.containsKey(key)) restore[key] = Change(undo, null)
    }

    /** 会被存进工程的东西：现在（脚本还没改之前）先 capture 一次，得到的就是 undo。 */
    internal fun rememberSaved(key: String, capture: () -> (() -> Unit)) {
        if (!restore.containsKey(key)) restore[key] = Change(capture(), capture)
    }
}

internal class ScriptTimeout : Error("script timeout")

/** 给每次调用设一个"最多执行多少条指令"的上限：写了死循环的脚本会被中止，而不是把整个应用卡死。 */
internal class Budget : DebugLib() {
    @JvmField var left = Long.MAX_VALUE

    override fun onInstruction(pc: Int, v: Varargs?, top: Int) {
        if (--left < 0) throw ScriptTimeout()
        super.onInstruction(pc, v, top)
    }
}

/**
 * Lua 脚本引擎：管理场景里的脚本，每帧调用它们的 update，把出错的停掉。
 *
 * 每个脚本有自己独立的 Lua 环境（互不干扰），只能通过这里提供的接口（见 [LuaApi]）接触场景，
 * 没有文件、网络和 Java 反射（没有 io / os.execute / luajava）。
 * 全部在渲染线程上运行。
 */
class ScriptEngine(val host: ScriptHost) {
    val scripts = ArrayList<Script>()
    /** 脚本列表或某个脚本的状态变了就加一（界面据此刷新）。 */
    var revision = 0
        private set
    /** 引擎启动以来的真实时间（秒），暂停时间轴时也照常走。 */
    var realTime = 0f
        private set

    /** fx.line 这一帧要画的线：每条 11 个数（两端坐标、rgba、粗细）。宿主每帧画完后清空。 */
    var lines = FloatArray(11 * 64)
        private set
    var lineCount = 0

    private var nextId = 1
    private var console: Script? = null
    private val consoleOut = StringBuilder()
    private var capture = false

    fun find(id: Int): Script? = scripts.firstOrNull { it.id == id }

    fun add(name: String, source: String, file: String = ""): Script {
        val s = Script(nextId++, name, source)
        s.file = file
        scripts.add(s)
        revision++
        return s
    }

    fun remove(s: Script) {
        stop(s, true)
        scripts.remove(s)
        revision++
    }

    fun clear() {
        for (s in scripts.toList()) stop(s, false)
        scripts.clear()
        console?.let { stop(it, false) }
        console = null
        revision++
    }

    fun setEnabled(s: Script, on: Boolean) {
        if (on) s.error = null
        s.enabled = on
        if (!on) stop(s, true)
        revision++
    }

    /** 换代码：正在运行的话从头重新运行。 */
    fun setSource(s: Script, source: String) {
        s.source = source
        s.error = null
        if (s.running) stop(s, true)
        revision++
    }

    fun restart(s: Script) {
        s.error = null
        if (s.running) stop(s, true)
        revision++
    }

    /** 只检查语法。没问题返回 null，否则返回出错信息。 */
    fun check(source: String, name: String = "脚本"): String? = try {
        checker.load(source, "@$name"); null
    } catch (e: LuaError) { clean(e.message ?: "语法错误") } catch (e: Throwable) { e.toString() }

    private val checker: Globals by lazy { Globals().also { LoadState.install(it); LuaC.install(it) } }

    // ------------------------------------------------------------------ 每帧

    private fun inRange(s: Script): Boolean {
        if (s.end < 0) return true
        val f = host.scene.frame
        return f >= s.start && f <= s.end
    }

    /**
     * 每帧调用一次（在场景更新之前）：该启动的启动、该停的停，然后调用每个脚本的 update(dt)。
     * @param dt 真实经过的秒数
     */
    fun update(dt: Float) {
        realTime += dt
        var changed = false
        for (i in scripts.indices) {
            val s = scripts.getOrNull(i) ?: break
            val want = s.enabled && s.error == null && inRange(s)
            if (want && !s.running) { start(s); changed = true }
            else if (!want && s.running) { stop(s, true); changed = true }
            if (!s.running) continue
            refreshTime(s, dt)
            if (!s.fnTick.isnil() && !call(s, s.fnTick, FRAME_BUDGET, LuaValue.valueOf(dt.toDouble()))) { changed = true; continue }
            if (!s.fnUpdate.isnil() && !call(s, s.fnUpdate, FRAME_BUDGET, LuaValue.valueOf(dt.toDouble()))) changed = true
        }
        console?.let { c ->
            if (c.running) {
                refreshTime(c, dt)
                if (!c.fnTick.isnil()) call(c, c.fnTick, FRAME_BUDGET, LuaValue.valueOf(dt.toDouble()))
                val up = c.globals?.get("update") ?: LuaValue.NIL
                if (up.isfunction()) call(c, up, FRAME_BUDGET, LuaValue.valueOf(dt.toDouble()))
            }
        }
        if (changed) revision++
    }

    private fun refreshTime(s: Script, dt: Float) {
        val scene = host.scene
        val t = s.timeTable ?: return
        t.rawset("now", LuaValue.valueOf(scene.timeSeconds.toDouble()))
        t.rawset("frame", LuaValue.valueOf(scene.frame.toDouble()))
        t.rawset("dt", LuaValue.valueOf(dt.toDouble()))
        t.rawset("real", LuaValue.valueOf((realTime - s.startedAt).toDouble()))
        t.rawset("playing", LuaValue.valueOf(scene.playing))
        t.rawset("duration", LuaValue.valueOf(scene.durationSeconds.toDouble()))
        val local = (scene.frame - s.start) / Motion.FPS
        t.rawset("local", LuaValue.valueOf(local.toDouble()))
        val len = if (s.end >= 0) (s.end - s.start).toFloat() else scene.maxFrame.toFloat() - s.start
        t.rawset("progress", LuaValue.valueOf(if (len > 0f) ((scene.frame - s.start) / len).coerceIn(0f, 1f).toDouble() else 0.0))
        val inp = s.inputTable ?: return
        inp.rawset("x", LuaValue.valueOf(host.input.moveX.toDouble()))
        inp.rawset("y", LuaValue.valueOf(host.input.moveY.toDouble()))
    }

    /** 屏幕按钮按下 / 松开：转给所有在运行的脚本的 on_button(name, down)。 */
    fun button(name: String, down: Boolean) {
        for (s in scripts.toList()) {
            if (s.running && !s.fnButton.isnil()) call(s, s.fnButton, FRAME_BUDGET, LuaValue.valueOf(name), LuaValue.valueOf(down))
        }
        console?.let { c -> val f = c.globals?.get("on_button"); if (c.running && f != null && f.isfunction()) call(c, f, FRAME_BUDGET, LuaValue.valueOf(name), LuaValue.valueOf(down)) }
    }

    /** 播放 / 暂停 / 跳转等事件：转给脚本的 on_event(name)。 */
    fun event(name: String) {
        for (s in scripts.toList()) {
            if (s.running && !s.fnEvent.isnil()) call(s, s.fnEvent, FRAME_BUDGET, LuaValue.valueOf(name))
        }
    }

    // ------------------------------------------------------------------ 启动 / 停止

    private fun newGlobals(s: Script): Globals {
        val g = Globals()
        g.load(BaseLib())
        g.load(PackageLib())
        g.load(Bit32Lib())
        g.load(TableLib())
        g.load(StringLib())
        g.load(CoroutineLib())
        g.load(JseMathLib())
        val budget = Budget()
        g.load(budget)
        LoadState.install(g)
        LuaC.install(g)
        // 沙箱：不给读写文件、加载外部代码、调试钩子这些口子
        for (k in arrayOf("dofile", "loadfile", "debug")) g.set(k, LuaValue.NIL)
        g.set("collectgarbage", object : VarArgFunction() { override fun invoke(a: Varargs): Varargs = LuaValue.valueOf(0) })
        val pkg = g.get("package")
        if (pkg.istable()) { pkg.set("path", ""); pkg.set("cpath", ""); pkg.set("loadlib", LuaValue.NIL); pkg.set("searchpath", LuaValue.NIL) }
        s.globals = g
        s.budget = budget
        val api = LuaApi(this, s)
        api.install(g)
        s.api = api
        return g
    }

    private fun start(s: Script) {
        s.restore.clear(); s.ownedModels.clear()
        s.startedAt = realTime
        try {
            val g = newGlobals(s)
            s.budget!!.left = INIT_BUDGET
            g.load(ScriptLibrary.PRELUDE, "@prelude").call()
            s.fnTick = g.get("__tick")
            val chunk = g.load(s.source, "@" + s.name)
            s.running = true
            refreshTime(s, 0f)
            s.budget!!.left = INIT_BUDGET
            chunk.call()
            val init = g.get("init")
            if (init.isfunction()) { s.budget!!.left = INIT_BUDGET; init.call() }
            s.fnUpdate = g.get("update").let { if (it.isfunction()) it else LuaValue.NIL }
            s.fnButton = g.get("on_button").let { if (it.isfunction()) it else LuaValue.NIL }
            s.fnEvent = g.get("on_event").let { if (it.isfunction()) it else LuaValue.NIL }
            s.fnExit = g.get("on_exit").let { if (it.isfunction()) it else LuaValue.NIL }
        } catch (e: Throwable) {
            fail(s, describe(e))
        }
    }

    private fun stop(s: Script, callExit: Boolean) {
        if (s.running && callExit && !s.fnExit.isnil()) {
            try { s.budget?.left = FRAME_BUDGET; s.fnExit.call() } catch (_: Throwable) {}
        }
        s.running = false
        cleanup(s)
        s.globals = null; s.budget = null; s.api = null; s.timeTable = null; s.inputTable = null
        s.fnUpdate = LuaValue.NIL; s.fnTick = LuaValue.NIL; s.fnButton = LuaValue.NIL; s.fnEvent = LuaValue.NIL; s.fnExit = LuaValue.NIL
    }

    /** 把脚本改过的东西放回去，清掉它创建的特效、文字、模型。 */
    private fun cleanup(s: Script) {
        host.effects.removeOwner(s.id)
        host.uiClear(s.id)
        val undo = s.restore.values.toList().asReversed()
        s.restore.clear()
        for (u in undo) try { u.undo() } catch (_: Throwable) {}
        if (s.ownedModels.isNotEmpty()) {
            for (id in s.ownedModels.toList()) host.scene.findById(id)?.let { host.removeModel(it) }
            s.ownedModels.clear()
            host.sceneChanged()
        }
    }

    /**
     * 在「没有脚本改动」的状态下执行 [block]（存盘用）：把正在运行的脚本对位置、透明度、物理参数这类
     * 会进工程文件的东西做的改动临时拿掉，[block] 结束后原样放回。这样脚本的效果不会被存成永久的。
     */
    fun <T> withBaseState(block: () -> T): T {
        val running = ArrayList<Script>()
        for (s in scripts) if (s.restore.isNotEmpty()) running.add(s)
        console?.let { if (it.restore.isNotEmpty()) running.add(it) }
        if (running.isEmpty()) return block()
        running.sortByDescending { it.startedAt }          // 后启动的先撤
        val redo = ArrayList<() -> Unit>()
        for (s in running) for (c in s.restore.values.toList().asReversed()) {
            val cap = c.capture ?: continue
            try { redo.add(cap()); c.undo() } catch (_: Throwable) {}
        }
        try { return block() } finally {
            for (i in redo.indices.reversed()) try { redo[i]() } catch (_: Throwable) {}
        }
    }

    private fun fail(s: Script, text: String) {
        // 「脚本名:3 ……」→「第 3 行：……」（报错本来就挂在这个脚本名下）
        val message = Regex("^" + Regex.escape(s.name) + ":(\\d+):?\\s*").replace(text) { "第 ${it.groupValues[1]} 行：" }
        s.error = message
        host.scriptLog(if (s === console) null else s, message, true)
        stop(s, false)
        revision++
    }

    private fun describe(e: Throwable): String = when (e) {
        is ScriptTimeout -> "运行太久被中止了：一次调用执行了太多指令，多半是写了死循环"
        is LuaError -> clean(e.message ?: "脚本出错")
        is StackOverflowError -> "函数自己调自己的层数太多（递归没有出口？）"
        is OutOfMemoryError -> "占用的内存太多"
        else -> e.toString()
    }

    /** LuaJ 的报错前面会带 "@名字:行号"，去掉 @，再把常见的几句英文换成中文说明。 */
    private fun clean(msg: String): String {
        var m = msg.replace("@", "").replace("vm error: ", "").trim()
        // 调用栈对写脚本的人没什么用（第一行已经带了行号），去掉
        val tb = m.indexOf("stack traceback:")
        if (tb >= 0) m = m.substring(0, tb).trim()
        m = m.replace("attempt to index ? (a nil value)", "在一个 nil 上取成员（前面那个东西是空的，比如没找到的骨骼 / 模型）")
            .replace("index expected, got nil", "在一个 nil 上取 / 写成员（前面那个东西是空的，比如没找到的骨骼 / 模型，或者变量名写错了）")
            .replace("unexpected symbol", "语法错误：出现了不该在这里的符号（unexpected symbol）")
            .replace("'end' expected", "语法错误：少了 end（'end' expected）")
            .replace("'then' expected", "语法错误：if 后面少了 then（'then' expected）")
            .replace("'do' expected", "语法错误：for / while 后面少了 do（'do' expected）")
            .replace("'=' expected", "语法错误：这里应该是一个赋值或函数调用（'=' expected）")
            .replace("unfinished string", "语法错误：字符串的引号没有配对（unfinished string）")
            .replace("syntax error", "语法错误：这一行不是合法的 Lua（syntax error）")
            .replace("attempt to call nil", "调用了一个不存在的函数")
            .replace("attempt to call a nil value", "调用了一个不存在的函数")
            .replace("attempt to perform arithmetic on", "拿来做算术的不是数字：")
            .replace("attempt to compare", "不能比较：")
            .replace("attempt to concatenate", "不能用 .. 连接：")
        return m.take(600)
    }

    internal fun call(s: Script, f: LuaValue, limit: Long, vararg args: LuaValue): Boolean {
        val b = s.budget ?: return false
        b.left = limit
        return try {
            f.invoke(LuaValue.varargsOf(args))
            true
        } catch (e: Throwable) {
            fail(s, describe(e))
            false
        }
    }

    internal fun print(s: Script, text: String) {
        if (capture && s === console) { if (consoleOut.length < 20000) consoleOut.append(text).append('\n') }
        host.scriptLog(if (s === console) null else s, text, false)
    }

    internal fun addLine(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, g: Float, b: Float, a: Float, width: Float) {
        if (lineCount >= MAX_LINES) return
        if ((lineCount + 1) * 11 > lines.size) lines = lines.copyOf(lines.size * 2)
        val o = lineCount * 11
        val l = lines
        l[o] = x0; l[o + 1] = y0; l[o + 2] = z0; l[o + 3] = x1; l[o + 4] = y1; l[o + 5] = z1
        l[o + 6] = r; l[o + 7] = g; l[o + 8] = b; l[o + 9] = a; l[o + 10] = width
        lineCount++
    }

    // ------------------------------------------------------------------ 控制台（AI 试代码、临时跑一段）

    /**
     * 在"控制台"环境里运行一段代码，返回它 print 的内容和返回值。
     * 控制台是一个一直开着的脚本环境：前一次定义的变量、创建的特效后面还在；里面定义了 update(dt) 的话每帧也会被调用。
     * [resetConsole] 把它清空。
     */
    fun eval(code: String): String {
        evalFailed = false
        var c = console
        if (c == null || !c.running) {
            c = Script(0, "控制台", "")
            c.enabled = true
            console = c
            start(c)
            if (!c.running) { evalFailed = true; return "控制台启动失败：" + (c.error ?: "") }
        }
        consoleOut.setLength(0)
        capture = true
        try {
            val g = c.globals!!
            refreshTime(c, 0f)
            // 先当成表达式试一次（这样 "1+1"、"scene.main().name" 能直接看到结果），不行再当成语句
            val chunk = try { g.load("return $code", "@控制台") } catch (_: LuaError) { g.load(code, "@控制台") }
            c.budget!!.left = INIT_BUDGET
            val r = chunk.invoke()
            if (r.narg() > 0) {
                val sb = StringBuilder()
                for (i in 1..r.narg()) { if (i > 1) sb.append('\t'); dump(r.arg(i), sb, 0) }
                consoleOut.append("=> ").append(sb).append('\n')
            }
        } catch (e: Throwable) {
            evalFailed = true
            consoleOut.append("出错：").append(describe(e)).append('\n')
            // 出错不关掉控制台：变量和特效都留着
            c.budget?.left = Long.MAX_VALUE
        } finally {
            capture = false
        }
        revision++
        return consoleOut.toString().trimEnd()
    }

    /** 最近一次 [eval] 是不是出错了。 */
    var evalFailed = false
        private set

    /**
     * 把一个 Lua 值写成人能看懂的样子（print 和控制台的返回值都用它）：普通的表（数组、键值）展开写出来，
     * 模型 / 骨骼这类对象写成 <bone 12 "右腕">，别的值照原样。最多展开三层、每层六十项。
     */
    internal fun dump(v: LuaValue, sb: StringBuilder, depth: Int) {
        val meta = if (v.istable()) v.getmetatable() else null
        if (meta != null) {
            // 模型 / 骨骼 / 材质这类对象：元表里的 __tostring 知道它是什么、叫什么
            val ts = meta.get("__tostring")
            sb.append(if (ts.isfunction()) try { ts.call(v).tojstring() } catch (_: Throwable) { v.tojstring() } else v.tojstring())
            return
        }
        if (!v.istable() || depth >= 3) {
            if (v.isstring() && !v.isnumber() && depth > 0) sb.append('"').append(v.tojstring()).append('"') else sb.append(v.tojstring())
            return
        }
        val t = v.checktable()
        sb.append('{')
        var n = 0
        val len = t.length()
        for (i in 1..len) {
            if (n > 0) sb.append(", ")
            if (n >= 60) { sb.append("… (").append(len).append(" 项)"); n = Int.MAX_VALUE / 2; break }
            dump(t.get(i), sb, depth + 1); n++
        }
        if (n < Int.MAX_VALUE / 2) {
            var k = LuaValue.NIL
            while (true) {
                val nx = t.next(k)
                k = nx.arg1()
                if (k.isnil()) break
                if (k.isint() && k.toint() in 1..len) continue
                if (n > 0) sb.append(", ")
                if (n >= 60) { sb.append('…'); break }
                sb.append(k.tojstring()).append(" = ")
                dump(nx.arg(2), sb, depth + 1); n++
            }
        }
        sb.append('}')
    }

    fun resetConsole() {
        console?.let { stop(it, false) }
        console = null
        revision++
    }

    val consoleActive: Boolean get() = console?.running == true

    companion object {
        /** 每帧一次的调用最多执行多少条指令。 */
        const val FRAME_BUDGET = 3_000_000L
        /** 启动（整段代码 + init）时放宽一些。 */
        const val INIT_BUDGET = 30_000_000L
        const val MAX_LINES = 2000
    }
}

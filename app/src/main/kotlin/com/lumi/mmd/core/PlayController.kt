package com.lumi.mmd.core

import com.lumi.mmd.engine.anim.MotionClip
import com.lumi.mmd.engine.control.Locomotion
import com.lumi.mmd.engine.control.Rig
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.physics.PhysicsTuning
import com.lumi.mmd.engine.scene.ClipOps
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.script.Script
import com.lumi.mmd.engine.script.ScriptCallbacks
import com.lumi.mmd.engine.script.ScriptLibrary
import com.lumi.mmd.engine.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 第四版新增的那部分功能：Lua 脚本、特效、片段轨道、物理参数、操控模式。
 * 和 [SceneController] 是一体的（共用它的场景和线程），只是为了不让那个文件继续膨胀才分出来。
 * 没有特别说明的方法都可以在界面线程调用；名字以 Gl 结尾的只能在渲染线程上调用。
 */
class PlayController(private val c: SceneController) : ScriptCallbacks {
    private val scene get() = c.scene
    private val runtime get() = c.runtime
    private val engine get() = c.runtime.scripts
    val scriptDir: File get() = File(c.library.root, SCRIPT_PACK)

    // ---- 脚本的输出 ----
    private val log = ArrayDeque<String>()
    private var logDirty = false
    private var lastLogPublish = 0L
    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** 脚本的 print 和报错（最近 300 行，旧的在前）。 */
    val scriptLog: StateFlow<List<String>> = _log
    /** 启动以来一共输出过多少行（日志只留最近的 300 行，MCP 靠这个数分清哪些是新的）。 */
    @Volatile var logTotal = 0L
        private set

    // ---- 脚本放在屏幕上的文字 / 按钮 ----
    private val items = LinkedHashMap<String, OverlayItem>()
    private var overlayDirty = false
    private val _overlay = MutableStateFlow<List<OverlayItem>>(emptyList())
    val overlay: StateFlow<List<OverlayItem>> = _overlay

    // ---- 选中的片段 ----
    private var selLane = -1
    private var selLaneId = 0
    private var selClip = 0

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ================================================================== 脚本引擎的回调（渲染线程）

    override fun scriptLog(script: Script?, text: String, error: Boolean) {
        val line = (if (error) "✕ " else "") + "[" + (script?.name ?: "控制台") + "] " + text
        synchronized(log) {
            log.addLast(line)
            logTotal++
            while (log.size > 300) log.removeFirst()
        }
        logDirty = true
        if (error) c.toast("脚本「${script?.name ?: "控制台"}」出错：" + text.take(120))
    }

    override fun uiText(owner: Int, id: String, text: String?, x: Float, y: Float, size: Float, color: Int) {
        val key = "t$owner/$id"
        if (text == null) { if (items.remove(key) != null) overlayDirty = true; return }
        val it = OverlayItem(key, id, text, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), size, color, false)
        if (items[key] != it) { items[key] = it; overlayDirty = true }
    }

    override fun uiButton(owner: Int, id: String, label: String?, x: Float, y: Float) {
        val key = "b$owner/$id"
        if (label == null) { if (items.remove(key) != null) overlayDirty = true; return }
        val it = OverlayItem(key, id, label, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), 14f, -1, true)
        if (items[key] != it) { items[key] = it; overlayDirty = true }
    }

    override fun uiClear(owner: Int) {
        val before = items.size
        items.keys.removeAll { it.startsWith("t$owner/") || it.startsWith("b$owner/") }
        if (items.size != before) overlayDirty = true
    }

    override fun toast(text: String) = c.toast(text)
    override fun setPlaying(on: Boolean) = c.setPlaying(on)
    override fun seekSeconds(seconds: Float) { scene.seekSeconds(seconds); c.audioFollowGl() }
    override fun selectedModel(): ModelInstance? = c.editor.selectedModel

    override fun addModel(data: ModelData, kind: Int): ModelInstance {
        val m = ModelInstance(data, kind)
        m.transient = true
        scene.add(m)
        c.dirty(false)
        return m
    }

    override fun removeModel(model: ModelInstance) {
        if (c.editor.selectedModel === model) c.editor.select(null)
        scene.remove(model)
        c.renderer.releaseModel(model)
        c.dirty(false)
    }

    override fun scriptSource(name: String): String? = fileFor(name).takeIf { it.isFile }?.readText()
    override fun sceneChanged() = c.dirty(false)

    override fun resolveImage(name: String): String? {
        val root = c.library.root
        val f = root.walkTopDown().maxDepth(6).firstOrNull { it.isFile && it.name.equals(name, true) } ?: return null
        return f.relativeTo(root).path
    }

    override fun imageBytes(name: String): ByteArray? {
        val root = c.library.root
        val direct = File(root, name)
        val f = if (direct.isFile) direct else root.walkTopDown().maxDepth(6).firstOrNull { it.isFile && it.name.equals(name, true) } ?: return null
        return if (f.length() > 32L * 1024 * 1024) null else f.readBytes()
    }

    // ================================================================== 每帧（渲染线程）

    /** 有脚本在跑、有特效、或者在操控模式：需要一直渲染。 */
    val busyGl: Boolean
        get() = runtime.controller != null || engine.consoleActive || !runtime.effects.isEmpty || engine.scripts.any { it.running }

    private var lastRevision = -1
    private var lastControl = ""

    /** 每帧调用：把日志、屏幕文字推给界面；脚本列表变了就让界面刷新。 */
    fun publishGl(now: Long) {
        if (logDirty && now - lastLogPublish > 200_000_000L) {
            lastLogPublish = now
            // 取快照和交给界面要在同一把锁里：否则刚好这时用户点了「清空」，旧的快照会晚一步把清掉的内容又放回去
            synchronized(log) { logDirty = false; _log.value = log.toList() }
        }
        if (overlayDirty) { overlayDirty = false; _overlay.value = items.values.toList() }
        if (engine.revision != lastRevision) { lastRevision = engine.revision; c.dirty(false) }
        val ctl = runtime.controller
        val sig = if (ctl == null) "" else "${ctl.stance}/${ctl.stateName}/${runtime.sprintLock}"
        if (sig != lastControl) { lastControl = sig; c.dirty(false) }
    }

    fun buildUiGl(): PlayUi {
        val lanes = ArrayList<LaneUi>()
        fun laneUi(lane: List<MotionClip>): List<ClipUi> = lane.map { k ->
            ClipUi(k.id, k.name, k.start, k.end, k.trimIn, k.motion.maxFrame, k.owned,
                when { k.sources.size >= 2 -> "+表情"; k.owned && k.sources.isEmpty() -> "自制"; else -> "" },
                fade = k.fade, canFade = ClipOps.previous(lane, k) != null)
        }
        lanes.add(LaneUi(Lanes.CAMERA, 0, "镜头", laneUi(scene.cameraClips)))
        for (m in scene.models) {
            if (m.transient) continue
            if (m.skeleton == null && m.clips.isEmpty()) continue
            if (m.isStage && m.clips.isEmpty()) continue
            lanes.add(LaneUi(Lanes.MODEL, m.id, m.name, laneUi(m.clips)))
        }
        if (scene.audioClips.isNotEmpty()) lanes.add(LaneUi(Lanes.AUDIO, 0, "音乐", scene.audioClips.map { a -> ClipUi(a.id, a.name, a.start, a.end, a.trimIn, a.sourceFrames, false) }))
        for (s in engine.scripts) {
            val end = if (s.end >= 0) s.end else maxOf(scene.maxFrame, s.start + 30)
            lanes.add(LaneUi(Lanes.SCRIPT, s.id, s.name, listOf(ClipUi(s.id, if (s.end < 0) "全程" else s.name, s.start, end, 0, 0, false, if (!s.enabled) "关" else if (s.error != null) "出错" else ""))))
        }
        val ctl = runtime.controller
        return PlayUi(
            scripts = engine.scripts.map { s -> ScriptUi(s.id, s.name, s.enabled, s.running, s.error ?: "", s.target, s.start, s.end, s.source.count { it == '\n' } + 1) },
            lanes = lanes, clipLane = selLane, clipLaneId = selLaneId, clip = selClip,
            control = if (ctl == null) ControlUi() else ControlUi(true, ctl.model.id, ctl.stance, ctl.stateName, runtime.sprintLock, ctl.animated),
            physics = PhysicsTuning.NAMES.associateWith { scene.physics.get(it) ?: 0f },
            particles = runtime.effects.particleCount,
            consoleActive = engine.consoleActive,
        )
    }

    // ================================================================== 脚本

    private fun sanitize(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').ifBlank { "脚本" }.take(60)
    private fun fileFor(name: String): File = File(scriptDir, sanitize(name) + ".lua")

    /** 脚本库里已有的脚本名字。 */
    fun libraryScripts(): List<String> = scriptDir.listFiles()?.filter { it.isFile && it.extension.equals("lua", true) }?.map { it.nameWithoutExtension }?.sortedBy { it.lowercase() } ?: emptyList()

    private fun writeFile(name: String, code: String): File? = try {
        scriptDir.mkdirs()
        fileFor(name).also { it.writeText(code) }
    } catch (e: Exception) { Log.w(TAG, "保存脚本失败: $e"); null }

    /** 新建一个脚本放进场景（同名的会被换掉），同时存进脚本库。 */
    fun createScript(name: String, code: String, enable: Boolean = true, done: ((Int) -> Unit)? = null) {
        val clean = sanitize(name)
        c.post {
            val f = writeFile(clean, code)
            val s = engine.scripts.firstOrNull { it.name == clean } ?: engine.add(clean, code, f?.path ?: "")
            engine.setSource(s, code)
            if (f != null) s.file = f.path
            engine.setEnabled(s, enable)
            c.notifyLibraryChanged()
            c.dirty()
            done?.let { cb -> val id = s.id; mainHandler.post { cb(id) } }
        }
    }

    fun addExample(name: String) {
        val ex = ScriptLibrary.EXAMPLES.firstOrNull { it.name == name } ?: return
        createScript(ex.name, ex.source.trim() + "\n", true)
        c.toast("已添加并运行「${ex.name}」")
    }

    /** 把脚本库（或刚导入的）一个 .lua 文件放进场景。 */
    fun addScriptFile(path: String, enable: Boolean = true) {
        val f = File(path)
        val code = try { f.readText() } catch (e: Exception) { c.toast("读不了 ${f.name}"); return }
        if (code.length > 400_000) { c.toast("${f.name} 太大了，不像是脚本"); return }
        c.post {
            val name = f.nameWithoutExtension
            val s = engine.scripts.firstOrNull { it.name == name } ?: engine.add(name, code, f.path)
            engine.setSource(s, code)
            s.file = f.path
            engine.setEnabled(s, enable)
            c.dirty()
        }
        c.toast("已添加脚本「${f.nameWithoutExtension}」" + if (enable) "并运行" else "")
    }

    fun setScriptEnabled(id: Int, on: Boolean) = c.post { engine.find(id)?.let { engine.setEnabled(it, on); c.dirty() } }

    /** 保存改过的代码：存回文件，正在运行的话从头重新运行。 */
    fun updateScript(id: Int, code: String, run: Boolean = true) = c.post {
        val s = engine.find(id) ?: return@post
        val err = engine.check(code, s.name)
        engine.setSource(s, code)
        val f = if (s.file.isNotEmpty() && File(s.file).parentFile?.isDirectory == true) File(s.file) else fileFor(s.name)
        try { f.parentFile?.mkdirs(); f.writeText(code); s.file = f.path } catch (e: Exception) { c.toast("没能存进文件：${e.message}") }
        if (err != null) { engine.setEnabled(s, false); scriptLog(s, err, true); c.toast("脚本有语法错误，没有运行（见「输出」）") }
        else if (run) { if (s.enabled) engine.restart(s) else engine.setEnabled(s, true); c.toast("已保存，脚本从头运行") }
        else c.toast("已保存")
        c.dirty()
    }

    fun deleteScript(id: Int) = c.post { engine.find(id)?.let { engine.remove(it); c.dirty() } }

    fun setScriptTarget(id: Int, modelId: Int) = c.post {
        val s = engine.find(id) ?: return@post
        s.target = modelId
        engine.restart(s)
        c.dirty()
    }

    /** 脚本只在时间轴的这一段里运行；end < 0 = 全程。 */
    fun setScriptRange(id: Int, start: Int, end: Int) = c.post {
        val s = engine.find(id) ?: return@post
        s.start = start.coerceAtLeast(0)
        s.end = if (end < 0) -1 else maxOf(end, s.start + 1)
        c.dirty()
    }

    /** 取脚本的代码（在界面线程回调）。 */
    fun readScript(id: Int, done: (String) -> Unit) = c.post {
        val src = engine.find(id)?.source ?: ""
        mainHandler.post { done(src) }
    }

    /**
     * 现拼一份完整的接口说明：固定的接口部分 + 此刻场景里每个模型真实的骨骼 / 表情 / 材质 / 刚体 + 示例清单。
     * 在界面线程回调。
     */
    fun apiDocument(done: (String) -> Unit) = c.post {
        val text = try {
            com.lumi.mmd.engine.script.ScriptDoc.full(c.scene, engine.scripts, c.editor.selectedModel)
        } catch (e: Throwable) {
            com.lumi.mmd.engine.script.ScriptLibrary.REFERENCE + "\n\n（读取场景内容时出错：$e）"
        }
        mainHandler.post { done(text) }
    }

    /** 在控制台里运行一段代码，结果进日志。 */
    fun eval(code: String) = c.post {
        synchronized(log) { log.addLast("› " + code.lines().first().take(200) + if (code.contains('\n')) " …" else "") }
        val out = engine.eval(code)
        if (out.isNotEmpty()) synchronized(log) { for (l in out.lines().take(60)) log.addLast("  $l") }
        synchronized(log) { while (log.size > 300) log.removeFirst() }
        logDirty = true
        c.dirty(false)
    }

    fun resetConsole() = c.post { engine.resetConsole(); c.dirty(false) }

    /** 清空脚本输出（界面上的「清空输出」和 MCP 的 script_log {clear: true}）。任意线程。 */
    fun clearLog() { synchronized(log) { log.clear(); logDirty = false; _log.value = emptyList() } }

    internal fun logSnapshot(): List<String> = synchronized(log) { log.toList() }

    // ---- 工程 ----

    internal fun saveGl(root: MutableMap<String, Any?>, order: List<ModelInstance>) {
        root["scripts"] = engine.scripts.map { s ->
            linkedMapOf("name" to s.name, "file" to s.file, "enabled" to s.enabled, "start" to s.start, "end" to s.end,
                "target" to order.indexOfFirst { it.id == s.target })
        }
        if (!scene.physics.isDefault) root["physicsTuning"] = scene.physics.toMap()
    }

    /** @param loaded 工程里的模型按保存时的顺序（没加载成功的为 null） */
    internal fun restoreGl(scripts: List<Map<String, Any?>>, tuning: Map<String, Any?>?, loaded: Array<ModelInstance?>) {
        scene.physics.reset()
        tuning?.forEach { (k, v) -> when (v) { is Number -> scene.physics.set(k, v.toFloat()); is Boolean -> scene.physics.set(k, if (v) 1f else 0f); else -> {} } }
        for (o in scripts) {
            val file = o["file"] as? String ?: ""
            val f = File(file)
            if (file.isEmpty() || !f.isFile) continue
            val code = try { f.readText() } catch (_: Exception) { continue }
            val s = engine.add((o["name"] as? String)?.ifBlank { null } ?: f.nameWithoutExtension, code, file)
            s.start = (o["start"] as? Number)?.toInt() ?: 0
            s.end = (o["end"] as? Number)?.toInt() ?: -1
            s.target = loaded.getOrNull((o["target"] as? Number)?.toInt() ?: -1)?.id ?: 0
            if (o["enabled"] == true) engine.setEnabled(s, true)
        }
    }

    internal fun resetGl() {
        runtime.reset()
        scene.physics.reset()
        items.clear(); overlayDirty = true
        selLane = -1; selClip = 0
    }

    // ================================================================== 物理

    /** 设一个物理参数。modelId = 0 是全局的；否则是那个模型自己的倍数。 */
    fun setPhysics(name: String, value: Float, modelId: Int = 0) = c.post {
        if (modelId == 0) scene.physics.set(name, value)
        else scene.findById(modelId)?.let { m -> (m.physicsTuning ?: PhysicsTuning().also { m.physicsTuning = it }).set(name, value) }
        c.dirty()
    }

    fun resetPhysics(modelId: Int = 0) = c.post {
        if (modelId == 0) scene.physics.reset() else scene.findById(modelId)?.physicsTuning = null
        for (m in scene.models) m.physics?.requestReset()
        c.dirty()
    }

    /** 让头发裙子回到原位重新落下。 */
    fun settlePhysics() = c.post { for (m in scene.models) { m.physics?.requestReset(); m.poseDirty = true }; c.dirty(false) }

    // ================================================================== 操控模式

    fun setControl(on: Boolean) = c.post {
        if (!on) { runtime.stopControl(); c.dirty(false); return@post }
        val m = c.editor.selectedModel?.takeIf { it.isCharacter && it.skeleton != null && !it.transient }
            ?: scene.models.firstOrNull { it.isCharacter && it.skeleton != null && !it.transient }
        if (m == null) { c.toast("场景里还没有角色：先添加一个模型"); return@post }
        c.leaveEditForControlGl()
        val ctl = runtime.startControl(m)
        if (!ctl.animated) c.toast("「${m.name}」没有标准的腿部骨骼（足ＩＫ），可以移动，但没有走跑的动画")
        c.dirty(false)
    }

    /** 摇杆（-1..1）。直接写，不排队：手感要跟手。 */
    fun stick(x: Float, y: Float) {
        runtime.input.moveX = x.coerceIn(-1f, 1f); runtime.input.moveY = y.coerceIn(-1f, 1f)
        c.wake()
    }

    /** 屏幕按钮按下 / 松开（操控模式的 jump crouch prone sprint，或者脚本自己加的）。 */
    fun button(name: String, down: Boolean) { runtime.input.button(name, down); c.wake() }

    fun setSprintLock(on: Boolean) { runtime.sprintLock = on; c.wake() }

    // ================================================================== 片段轨道

    private fun laneGl(kind: Int, id: Int): Pair<MutableList<MotionClip>, ModelInstance?>? = when (kind) {
        Lanes.CAMERA -> scene.cameraClips to null
        Lanes.MODEL -> scene.findById(id)?.let { it.clips to it }
        else -> null
    }

    private fun afterClipEditGl(m: ModelInstance?) {
        if (m != null) { m.sortClips(); m.motionRevision++; m.poseDirty = true } else { scene.sortCameraClips(); scene.releaseCamera() }
        scene.recomputeDuration()
        c.dirty()
    }

    /** 渲染线程：选中音乐轨道上的某一段。 */
    internal fun selectAudioGl(clipId: Int) { selLane = Lanes.AUDIO; selLaneId = 0; selClip = clipId }

    fun selectClip(kind: Int, laneId: Int, clipId: Int) = c.post {
        selLane = kind; selLaneId = laneId; selClip = clipId
        if (kind == Lanes.MODEL) scene.findById(laneId)?.let { m -> if (m.isCharacter || m.skeleton != null) c.editor.select(m) }
        c.dirty(false)
    }

    /**
     * 拖动片段：move = 整体挪到 start；否则 start / end 里不为 null 的那个是被拖的边缘。
     * @param begin 这次拖动的第一下（记一步撤销）
     */
    fun dragClip(kind: Int, laneId: Int, clipId: Int, move: Boolean, start: Int?, end: Int?, begin: Boolean) = c.post {
        if (kind == Lanes.SCRIPT) {
            val s = engine.find(laneId) ?: return@post
            val curEnd = if (s.end >= 0) s.end else maxOf(scene.maxFrame, s.start + 30)
            if (move && start != null) { val len = curEnd - s.start; s.start = start.coerceAtLeast(0); s.end = s.start + len }
            else {
                if (start != null) s.start = start.coerceIn(0, curEnd - 1)
                if (end != null) s.end = maxOf(end, s.start + 1) else if (s.end < 0 && start != null) s.end = curEnd
            }
            c.dirty()
            return@post
        }
        if (kind == Lanes.AUDIO) {
            val lane = scene.audioClips
            val clip = lane.firstOrNull { it.id == clipId } ?: return@post
            if (begin) c.pushAudioUndoGl()
            if (move) { if (start != null) ClipOps.move(lane, clip, start) }
            else {
                if (start != null) ClipOps.trimStart(lane, clip, start)
                if (end != null) ClipOps.trimEnd(lane, clip, end)
            }
            c.afterAudioEditGl()
            return@post
        }
        val (lane, m) = laneGl(kind, laneId) ?: return@post
        val clip = lane.firstOrNull { it.id == clipId } ?: return@post
        if (begin) c.pushClipUndoGl(m)
        if (move) { if (start != null) ClipOps.move(lane, clip, start) }
        else {
            if (start != null) ClipOps.trimStart(lane, clip, start)
            if (end != null) ClipOps.trimEnd(lane, clip, end)
        }
        afterClipEditGl(m)
    }

    private fun selectedGl(): Triple<MutableList<MotionClip>, ModelInstance?, MotionClip>? {
        val (lane, m) = laneGl(selLane, selLaneId) ?: return null
        val clip = lane.firstOrNull { it.id == selClip } ?: return null
        return Triple(lane, m, clip)
    }

    /** 在当前帧把选中的片段切成两段。 */
    fun splitClip() = c.post {
        if (selLane == Lanes.SCRIPT) { c.toast("脚本的时间段不能分割"); return@post }
        val f = Math.round(scene.frame)
        if (selLane == Lanes.AUDIO) {
            val clip = scene.audioClips.firstOrNull { it.id == selClip } ?: run { c.toast("先点一个片段"); return@post }
            if (f <= clip.start || f >= clip.end) { c.toast("把时间线拖到这个片段的中间再分割"); return@post }
            c.pushAudioUndoGl()
            ClipOps.splitAudio(scene.audioClips, clip, f)?.let { selClip = it.id }
            c.afterAudioEditGl()
            return@post
        }
        val (lane, m, clip) = selectedGl() ?: run { c.toast("先点一个片段"); return@post }
        if (f <= clip.start || f >= clip.end) { c.toast("把时间线拖到这个片段的中间再分割"); return@post }
        c.pushClipUndoGl(m)
        val b = ClipOps.split(lane, clip, f)
        if (b != null) selClip = b.id
        afterClipEditGl(m)
    }

    fun duplicateClip() = c.post {
        if (selLane == Lanes.SCRIPT) { c.toast("脚本不能在轨道上复制"); return@post }
        if (selLane == Lanes.AUDIO) {
            val clip = scene.audioClips.firstOrNull { it.id == selClip } ?: run { c.toast("先点一个片段"); return@post }
            c.pushAudioUndoGl()
            selClip = ClipOps.duplicateAudio(scene.audioClips, clip).id
            c.afterAudioEditGl()
            return@post
        }
        val (lane, m, clip) = selectedGl() ?: run { c.toast("先点一个片段"); return@post }
        c.pushClipUndoGl(m)
        selClip = ClipOps.duplicate(lane, clip).id
        afterClipEditGl(m)
    }

    fun deleteClip() = c.post {
        when (selLane) {
            Lanes.SCRIPT -> { engine.find(selLaneId)?.let { engine.remove(it) }; selClip = 0; c.dirty(); return@post }
            Lanes.AUDIO -> {
                val clip = scene.audioClips.firstOrNull { it.id == selClip } ?: run { c.toast("先点一个片段"); return@post }
                c.pushAudioUndoGl()
                ClipOps.remove(scene.audioClips, clip)
                selClip = 0
                c.afterAudioEditGl()
                return@post
            }
        }
        val (lane, m, clip) = selectedGl() ?: run { c.toast("先点一个片段"); return@post }
        c.pushClipUndoGl(m)
        ClipOps.remove(lane, clip)
        selClip = 0
        afterClipEditGl(m)
    }

    /**
     * 选中的片段和它前面那一段之间的过渡：开头的 frames 帧里从前一段的姿势平滑地变过来。0 = 直接切换。
     */
    fun setClipFade(frames: Int) = c.post {
        val (lane, m, clip) = selectedGl() ?: run { c.toast("先点一个动作或镜头片段"); return@post }
        if (ClipOps.previous(lane, clip) == null) { c.toast("这是轨道上的第一段，前面没有可以过渡的片段"); return@post }
        c.pushClipUndoGl(m)
        clip.fadeIn = frames.coerceIn(0, ClipOps.MAX_FADE)
        afterClipEditGl(m)
    }

    /** 脚本的时间段恢复成"全程"。 */
    fun scriptWholeTimeline(id: Int) = c.post { engine.find(id)?.let { it.start = 0; it.end = -1; c.dirty() } }

    /** 某个角色能用的内置动作（走、跑、蹲……）：(id, 名字)。在界面线程回调。 */
    fun builtinMotions(modelId: Int, done: (List<Pair<String, String>>) -> Unit) = c.post {
        val m = scene.findById(modelId)
        val rig = m?.let { Rig(it.data) }
        val list = if (rig != null && rig.usable) Locomotion(rig).all.filter { it.stride > 0f || it.id == "idle" || it.id == "crouch" || it.id == "prone" }.map { it.id to it.title } else emptyList()
        mainHandler.post { done(list) }
    }

    /** 把一个内置动作当成片段加到角色的轨道最后（循环 repeat 次）。 */
    fun addBuiltin(modelId: Int, id: String, repeat: Int = 4) = c.post {
        val m = scene.findById(modelId) ?: return@post
        val rig = Rig(m.data)
        if (!rig.usable) { c.toast("「${m.name}」没有标准的腿部骨骼，用不了内置动作"); return@post }
        val src = Locomotion(rig).find(id) ?: return@post
        val motion = src.motion.copy()
        for (k in 1 until repeat.coerceIn(1, 100)) for ((name, t) in src.motion.boneTracks) {
            val dst = motion.boneTrack(name)
            for (i in 0 until t.count) { val o = i * 7; dst.set(t.frames[i] + k * src.frames, t.values[o], t.values[o + 1], t.values[o + 2], t.values[o + 3], t.values[o + 4], t.values[o + 5], t.values[o + 6]) }
        }
        motion.recomputeMaxFrame()
        motion.name = src.title
        val clip = MotionClip(motion, src.title)
        clip.owned = true
        c.pushClipUndoGl(m)
        ClipOps.insert(m.clips, clip, ClipOps.end(m.clips))
        selLane = Lanes.MODEL; selLaneId = m.id; selClip = clip.id
        afterClipEditGl(m)
        c.toast("已把「${src.title}」加到 ${m.name} 的轨道上")
    }

    /** 把一个 VMD 接到轨道最后：镜头动作放镜头轨道，其它的放到 targetId（没有就是选中的 / 第一个角色）的轨道上。 */
    fun appendAuto(path: String, targetId: Int) {
        c.loadMotionAsync(path) { motion ->
            val camera = motion.hasCamera && !motion.hasModelMotion
            val m = if (camera) null else ((scene.findById(targetId) ?: c.editor.selectedModel)?.takeIf { it.skeleton != null && !it.transient }
                ?: scene.models.firstOrNull { it.isCharacter && it.skeleton != null && !it.transient })
            if (!camera && m == null) { c.toast("场景里还没有可以套用动作的模型，请先加载角色"); return@loadMotionAsync }
            val lane = if (m == null) scene.cameraClips else m.clips
            val clip = MotionClip(if (m == null) motion.copy() else motion, File(path).nameWithoutExtension)
            clip.sources = listOf(path)
            c.pushClipUndoGl(m)
            ClipOps.insert(lane, clip, ClipOps.end(lane))
            selLane = if (m == null) Lanes.CAMERA else Lanes.MODEL; selLaneId = m?.id ?: 0; selClip = clip.id
            if (m == null) scene.useCameraMotion = true
            afterClipEditGl(m)
            c.toast("已接在${if (m == null) "镜头" else " ${m.name} "}轨道的最后")
        }
    }

    /** 把资源库里的一个 VMD 作为新片段接到轨道最后（而不是换掉当前片段）。 */
    fun appendClipFile(kind: Int, laneId: Int, path: String) {
        c.loadMotionAsync(path) { motion ->
            val (lane, m) = laneGl(kind, laneId) ?: return@loadMotionAsync
            if (m == null && !motion.hasCamera) { c.toast("${File(path).name} 里没有镜头数据"); return@loadMotionAsync }
            if (m != null && !motion.hasModelMotion) { c.toast("${File(path).name} 是镜头动作，放到镜头轨道上"); return@loadMotionAsync }
            val clip = MotionClip(if (m == null) motion.copy() else motion, File(path).nameWithoutExtension)
            clip.sources = listOf(path)
            c.pushClipUndoGl(m)
            ClipOps.insert(lane, clip, ClipOps.end(lane))
            selLane = kind; selLaneId = laneId; selClip = clip.id
            if (m == null) scene.useCameraMotion = true
            afterClipEditGl(m)
        }
    }

    companion object {
        private const val TAG = "Play"
        /** 脚本在资源库里放在这个文件夹下。 */
        const val SCRIPT_PACK = "脚本"
    }
}

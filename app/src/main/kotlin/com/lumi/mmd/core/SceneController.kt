package com.lumi.mmd.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLES30
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.lumi.mmd.engine.anim.Motion
import com.lumi.mmd.engine.format.FormatException
import com.lumi.mmd.engine.format.ModelLoader
import com.lumi.mmd.engine.render.TextureResolver
import com.lumi.mmd.engine.format.ModelRefs
import com.lumi.mmd.engine.format.PmxWriter
import com.lumi.mmd.engine.format.VmdWriter
import com.lumi.mmd.engine.format.VpdReader
import com.lumi.mmd.engine.mcp.McpHost
import com.lumi.mmd.engine.mcp.McpServer
import com.lumi.mmd.engine.mcp.SceneTools
import com.lumi.mmd.engine.mesh.EditMesh
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.math.Quat
import com.lumi.mmd.engine.render.GlUtil
import com.lumi.mmd.engine.render.Renderer
import com.lumi.mmd.engine.scene.Editor
import com.lumi.mmd.engine.scene.KeyOps
import com.lumi.mmd.engine.scene.MeshSession
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.scene.Part
import com.lumi.mmd.engine.scene.PartOps
import com.lumi.mmd.engine.scene.Scene
import com.lumi.mmd.engine.scene.SceneIO
import com.lumi.mmd.engine.scene.SceneOps
import com.lumi.mmd.engine.util.Json
import com.lumi.mmd.engine.util.Log
import com.lumi.mmd.engine.util.arr
import com.lumi.mmd.engine.util.bool
import com.lumi.mmd.engine.util.float
import com.lumi.mmd.engine.util.int
import com.lumi.mmd.engine.util.jObj
import com.lumi.mmd.engine.util.jStr
import com.lumi.mmd.engine.util.obj
import com.lumi.mmd.engine.util.str
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 场景控制器：界面与引擎之间的唯一桥梁。
 *
 * 线程模型：
 *  - 场景 / 渲染器 / 编辑器只在 GL 线程上访问；界面线程调用的每个方法都只是往 GL 线程投递一个任务；
 *  - 模型、动作的解析放在单独的加载线程上（一次一个，避免几个大模型同时解析把内存撑爆），解析完再交给 GL 线程；
 *  - GL 线程把场景状态整理成不可变的快照，通过 StateFlow 发给界面。
 */
class SceneController(private val context: Context) : McpHost {
    override val scene = Scene()
    val renderer = Renderer()
    override val editor = Editor(scene)
    /** 音乐轨道的播放：两个播放器轮流放，下一段提前准备好（见引擎里的 AudioDeck）。 */
    private val audioIo = Executors.newSingleThreadExecutor { r -> Thread(r, "lumi-audio-io").also { it.isDaemon = true } }
    private val voices = arrayOf(AudioPlayer(), AudioPlayer())
    private val deck = com.lumi.mmd.engine.scene.AudioDeck(voices[0], voices[1]) { task -> try { audioIo.execute { task() } } catch (_: Throwable) {} }

    /** 任意线程：马上让音乐停下。应用进后台时用——渲染线程这时可能已经停了，排着队的"暂停"要等回到前台才轮得到。 */
    fun silenceNow() { for (v in voices) v.pause() }
    val library = Library(context)
    /** 脚本、片段轨道、物理参数、操控模式（第四版新增的部分）。 */
    val play = PlayController(this)
    /** 脚本引擎、特效、操控模式和场景更新串在一起的那一帧。 */
    override val runtime = com.lumi.mmd.engine.scene.SceneRuntime(scene, renderer.settings, play)
    /** 屏幕的刷新率（由界面告诉我们）：用来把每帧的时间步对齐到整数个刷新周期，画面更稳。 */
    @Volatile var refreshRate = 60f

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private val _playback = MutableStateFlow(PlaybackUi())
    val playback: StateFlow<PlaybackUi> = _playback
    private val _detail = MutableStateFlow(DetailUi())
    val detail: StateFlow<DetailUi> = _detail
    private val _prefs = MutableStateFlow(RenderPrefs())
    val prefs: StateFlow<RenderPrefs> = _prefs
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages
    private val _loading = MutableStateFlow("")
    /** 正在进行的耗时操作的提示文字；空 = 没有。单独一个流，任何线程都可以更新。 */
    val loading: StateFlow<String> = _loading
    private val _libraryVersion = MutableStateFlow(0)
    /** 资源库内容变化时 +1，界面据此重新扫描。 */
    val libraryVersion: StateFlow<Int> = _libraryVersion

    /** 由 [MmdSurfaceView] 注入：往 GL 线程投递任务、请求重绘、切换连续渲染。 */
    @Volatile var glPost: ((Runnable) -> Unit)? = null
    @Volatile var glRequestRender: (() -> Unit)? = null
    /** 渲染线程上现在有没有可用的 GL 上下文（应用在后台时没有）。 */
    @Volatile var glHasContext: (() -> Boolean)? = null
    /** 渲染线程：应用在后台时临时把 GL 上下文接上；返回 true 表示用完要调 [glReturnContext]。 */
    @Volatile var glBorrowContext: (() -> Boolean)? = null
    @Volatile var glReturnContext: (() -> Unit)? = null
    @Volatile var glSetContinuous: ((Boolean) -> Unit)? = null

    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "model-loader").also { it.priority = Thread.NORM_PRIORITY - 1 } }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lumi-io") }
    private val motionCache = HashMap<String, Motion>()

    // ---- 仅 GL 线程使用 ----
    private var width = 1
    private var height = 1
    private var lastNanos = 0L
    @Volatile private var activeUntil = 0L
    private var continuous = true
    private var stateDirty = true
    private var detailWanted = false
    private var detailDirty = false
    private var projectDirty = false
    private var lastProjectSave = 0L
    private var lastPlaybackPublish = 0L
    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var fps = 0
    private var lastFrame = 0f
    private var grabbing = false
    private var draggingCamera = false
    private val pendingLoads = java.util.concurrent.atomic.AtomicInteger(0)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var shotScale = 0
    private var shotTransparent = false
    /** 当前镜头片段的来源文件。 */
    private var cameraMotionFile: String
        get() = scene.activeCameraClip?.sources?.firstOrNull() ?: ""
        set(v) { scene.activeCameraClip?.sources = if (v.isEmpty()) emptyList() else listOf(v) }
    @Volatile private var restoring = false
    private val tmp3 = FloatArray(3)
    private val bMin = FloatArray(3)
    private val bMax = FloatArray(3)
    private val projectFile = File(context.filesDir, "project.json")

    init {
        AndroidPlatform.install()
        editor.density = context.resources.displayMetrics.density
        editor.onChanged = { stateDirty = true; detailDirty = true; projectDirty = true }
        renderer.effects = runtime.effects
        renderer.effectRenderer.imageRoot = library.root
        loadPrefs()
    }

    // ------------------------------------------------------------------ 基础设施

    /** 往 GL 线程投递任务并唤醒渲染。 */
    fun post(block: () -> Unit) {
        val p = glPost
        if (p == null) { Log.w(TAG, "GL 线程尚未就绪，操作被忽略"); return }
        p(Runnable {
            try { block() } catch (e: Throwable) { Log.e(TAG, "GL 任务出错", e); toast("操作失败：${e.message ?: e.javaClass.simpleName}") }
            kick()
        })
        glRequestRender?.invoke()
    }

    /** 接下来一小段时间保持连续渲染（让物理把头发裙摆摆平、让贴图陆续加载出来）。 */
    private fun kick(seconds: Float = 2.5f) {
        activeUntil = maxOf(activeUntil, System.nanoTime() + (seconds * 1e9f).toLong())
        stateDirty = true
    }

    fun toast(msg: String) { if (msg.isNotBlank()) _messages.tryEmit(msg) }

    /** 渲染线程：场景被改动了，刷新界面（project = 顺便稍后存一次工程）。 */
    internal fun dirty(project: Boolean = true) {
        stateDirty = true; detailDirty = true
        if (project) projectDirty = true
        kick()
    }

    /** 任意线程：让渲染循环醒过来跑一会儿（摇杆、屏幕按钮直接写输入状态，不走任务队列）。 */
    internal fun wake() {
        activeUntil = maxOf(activeUntil, System.nanoTime() + 1_500_000_000L)
        glRequestRender?.invoke()
    }

    /** 从别的线程通知"界面状态要重新生成"。 */
    private fun markState() { val p = glPost ?: return; p(Runnable { stateDirty = true }); glRequestRender?.invoke() }

    private fun setLoading(text: String) { _loading.value = text }

    // ------------------------------------------------------------------ GL 生命周期

    fun onContextCreated() {
        glThread = Thread.currentThread()
        renderer.onContextCreated()
        applyPrefsGl(_prefs.value)
        lastNanos = 0L
        kick(4f)
    }

    fun onResize(w: Int, h: Int) {
        width = maxOf(w, 1); height = maxOf(h, 1)
        kick()
    }

    /**
     * 把这一帧的时间步对齐到整数个屏幕刷新周期。
     * 两帧之间实际量出来的时间总带着一两毫秒的抖动（线程调度），直接拿来推进动画，匀速的动作看起来会一顿一顿；
     * 屏幕其实是按固定周期刷新的，量出来的值离"整数个周期"很近时，就取那个整数。
     */
    private fun steadyDt(dt: Float): Float {
        val rate = refreshRate
        if (rate < 30f || rate > 240f) return dt
        val period = 1f / rate
        val k = Math.round(dt / period)
        if (k < 1 || k > 4) return dt
        return if (kotlin.math.abs(dt - k * period) < period * 0.25f) k * period else dt
    }

    fun drawFrame() {
        val now = System.nanoTime()
        var dt = if (lastNanos == 0L) 1f / 60f else (now - lastNanos) / 1e9f
        lastNanos = now
        if (dt > 0.1f || dt <= 0f) dt = 1f / 30f
        dt = steadyDt(dt)

        // 音乐按它自己的速度放，画面的时间平滑地向它靠拢（引擎里的 SyncClock），从不直接跳到播放器报的位置上
        // 时间线走进 / 走出音乐轨道上的某一段时，由 deck 负责让对应的播放器出声 / 停下
        val pos = if (scene.playing) deck.update(scene.audioClips, scene.frame, now) else Float.NaN
        val hold = pos == AudioPlayer.HOLD
        val ended = runtime.update(dt, if (hold) Float.NaN else pos, hold)
        if (scene.playing) {
            if (ended) { scene.playing = false; deck.pause(); stateDirty = true; prepareAudioSoon() }
            else if (scene.looped) audioPlayGl()      // 时间轴循环回到了开头：音乐也从头来
        }

        if (glThread == null) glThread = Thread.currentThread()
        // 「只看它」只在调部件时有意义：换了工具或者换了模型就自动结束
        if (soloSaved != null && (editor.mode != Editor.MODE_PART || editor.selectedModel?.id != soloModel)) endSoloGl()
        renderer.highlightModel = if (editor.mode == Editor.MODE_PART) editor.selectedModel else null
        renderer.highlightPart = if (editor.mode == Editor.MODE_PART) editor.selectedPart else null
        session?.fillOverlay(renderer.overlay)
        editor.fillOverlay(renderer.overlay, width, height)
        runtime.fillOverlay(renderer.overlay)
        renderer.render(scene, 0, width, height)
        play.publishGl(now)
        if (shotScale > 0) {
            val sc = shotScale
            shotScale = 0
            try { takeScreenshotGl(sc, shotTransparent) } catch (e: Throwable) { Log.e(TAG, "截图失败", e); toast("截图失败：${describe(e)}") }
        }

        // 统计与状态发布
        frameCount++
        if (fpsWindowStart == 0L) fpsWindowStart = now
        if (now - fpsWindowStart >= 1_000_000_000L) {
            fps = (frameCount * 1e9 / (now - fpsWindowStart) + 0.5).toInt()
            frameCount = 0; fpsWindowStart = now
        }
        if (now - lastPlaybackPublish > 100_000_000L || stateDirty || editor.isDragging) {
            lastPlaybackPublish = now
            val f = curFrame()
            val km = keyModel()
            var boneKeyed = false; var frameKeyed = false
            if (!scene.playing && km != null && km.motion != null) {
                if (editor.selectedModel === km && editor.selectedBone >= 0) boneKeyed = KeyOps.hasBoneKey(km, editor.selectedBone, f)
                frameKeyed = boneKeyed || frameHasKeyGl(km, f)
            }
            val camKeyed = !scene.playing && (scene.cameraMotion?.camera?.indexOf(f - scene.cameraOffset) ?: -1) >= 0
            _playback.value = PlaybackUi(scene.playing, scene.frame, scene.maxFrame, fps, renderer.lastDrawCalls, renderer.lastTriangles, renderer.textures.pendingCount,
                dragText(), boneKeyed, frameKeyed, camKeyed)
            // 内存不够、有贴图没加载：贴图都处理完之后说一声
            val skipped = renderer.textures.skippedForMemory
            if (skipped > skippedTexturesTold && renderer.textures.pendingCount == 0) {
                toast("内存不够，有 ${skipped - skippedTexturesTold} 张贴图没有加载。可以在「设置」里把贴图上限调低，或者先移除别的模型")
                skippedTexturesTold = skipped
            }
        }
        if (stateDirty) { stateDirty = false; _state.value = buildState() }
        if (detailWanted && detailDirty) { detailDirty = false; _detail.value = buildDetail() }
        val ms = session
        val meshRev = if (ms == null) -1 else ms.revision
        if (meshRev != meshShown) { meshShown = meshRev; _mesh.value = buildMeshUi(); stateDirty = true }
        if (timelineWanted) publishTimelineGl()
        if (projectDirty && !restoring && now - lastProjectSave > 3_000_000_000L) saveProjectGl()

        val active = scene.playing || now < activeUntil || renderer.textures.pendingCount > 0 || pendingLoads.get() > 0 || play.busyGl
        if (active != continuous) { continuous = active; glSetContinuous?.invoke(active) }
    }

    // ------------------------------------------------------------------ 触摸（由视图在 GL 线程调用）

    fun touchTap(x: Float, y: Float) {
        val s = session
        if (s != null && editor.mode == Editor.MODE_MESH) {
            scene.camera.update(width.toFloat() / height)
            s.tap(x, y, width, height)
            kick()
            return
        }
        // 浏览模式下点一下模型只是选中它（供面板显示），不显示操纵轴
        editor.tap(x, y, width, height)
        if (editor.mode == Editor.MODE_PART && editor.selectedPart != null) detailDirty = true
        fixBoneToolGl()
        kick()
    }

    /** 双击：把相机的注视点移到点中的位置。 */
    fun touchDoubleTap(x: Float, y: Float) {
        if (scene.cameraDriven) return
        val ro = FloatArray(3); val rd = FloatArray(3)
        scene.camera.screenRay(x, y, width, height, ro, rd)
        val hit = com.lumi.mmd.engine.scene.PickResult()
        if (com.lumi.mmd.engine.scene.Picker.pick(scene, ro, rd, hit) { true }) {
            System.arraycopy(hit.point, 0, scene.camera.target, 0, 3)
            scene.camera.distance = minOf(scene.camera.distance, maxOf(hit.t * 0.9f, 4f))
            scene.userCamera.copyFrom(scene.camera)
            projectDirty = true
        }
        kick()
    }

    /** 镜头正由镜头关键帧驱动：播放中不让手动转；暂停时可以转（为了摆好镜头再登记关键帧），换帧后恢复。 */
    private fun cameraLocked(): Boolean = scene.cameraDriven && scene.playing

    fun dragStart(x: Float, y: Float) {
        dragUndoSerial = editor.undoSerial
        grabbing = editor.beginDrag(x, y, width, height)
        boxing = !grabbing && boxSelect && session != null && editor.mode == Editor.MODE_MESH
        if (boxing) { boxX = x; boxY = y; boxRect.value = floatArrayOf(x, y, x, y) }
        draggingCamera = !grabbing && !boxing
        kick()
    }

    fun dragMove(dx: Float, dy: Float, x: Float, y: Float) {
        if (grabbing) {
            editor.drag(dx, dy, x, y, width, height)
            detailDirty = true
        } else if (boxing) {
            boxRect.value = floatArrayOf(boxX, boxY, x, y)
        } else if (draggingCamera && !cameraLocked()) {
            scene.camera.orbit(dx, dy, height)
            if (scene.cameraDriven) scene.holdCamera()
        }
        kick()
    }

    fun dragEnd() {
        if (grabbing) {
            val wasBone = editor.mode == Editor.MODE_BONE
            editor.endDrag()
            if (wasBone) autoKeyBoneGl()
            projectDirty = true
        } else if (boxing) {
            val r = boxRect.value
            boxRect.value = null
            if (r != null) {
                scene.camera.update(width.toFloat() / height)
                session?.boxSelect(r[0], r[1], r[2], r[3], width, height)
            }
        } else if (draggingCamera && !scene.cameraDriven) { scene.userCamera.copyFrom(scene.camera); projectDirty = true }
        grabbing = false; draggingCamera = false; boxing = false
        kick()
    }

    /**
     * 操控模式下转视角 / 拉远近。手势是界面层收的，不经过渲染视图：
     * 这样一只手推摇杆、另一只手转视角时，两根手指不会被当成"双指缩放"。
     */
    fun lookDrag(dx: Float, dy: Float) = post { scene.camera.orbit(dx, dy, height) }
    fun lookZoom(zoom: Float) = post { scene.camera.zoom(zoom) }

    fun twoFinger(panX: Float, panY: Float, zoom: Float) {
        if (cameraLocked()) return
        scene.camera.pan(panX, panY, height)
        scene.camera.zoom(zoom)
        if (scene.cameraDriven) scene.holdCamera() else scene.userCamera.copyFrom(scene.camera)
        projectDirty = true
        kick()
    }

    private fun frameHasKeyGl(m: ModelInstance, frame: Int): Boolean {
        val b = m.motion ?: return false
        val f = frame - Math.round(m.motionOffset)
        for (t in b.motion.boneTracks.values) if (t.indexOf(f) >= 0) return true
        for (t in b.motion.morphTracks.values) if (t.indexOf(f) >= 0) return true
        return false
    }

    private fun dragText(): String {
        if (!editor.isDragging) return ""
        val axis = when (editor.activeHandle) { Editor.HANDLE_X -> " X"; Editor.HANDLE_Y -> " Y"; Editor.HANDLE_Z -> " Z"; else -> "" }
        val a = editor.dragAmount
        return when (editor.tool) {
            Editor.TOOL_ROTATE -> "旋转$axis  %.1f°".format(Math.toDegrees(a.toDouble()))
            Editor.TOOL_MOVE -> "移动$axis  %.2f".format(a)
            else -> "缩放$axis  ×%.2f".format(Math.exp(a.toDouble()))
        }
    }

    private fun publishTimelineGl() {
        val m = keyModel()
        val cam = scene.cameraMotion?.camera
        var key = (m?.id ?: -1).toLong() * 1000003L + (m?.motionRevision ?: 0)
        key = key * 31 + System.identityHashCode(m?.motion?.motion)
        key = key * 31 + (cam?.count ?: 0)
        key = key * 31 + (if (cam != null && cam.count > 0) cam.frames[cam.count - 1] else 0)
        key = key * 31 + System.identityHashCode(cam)
        if (key == timelineKey) return
        timelineKey = key
        val rows = if (m == null) emptyList() else KeyOps.rows(m).map { TrackUi(it.kind, it.index, it.name, it.frames) }
        _timeline.value = TimelineUi(m?.id ?: -1, m?.name ?: "", rows, KeyOps.cameraKeyFrames(scene), ++timelineVersion)
    }

    // ------------------------------------------------------------------ 状态快照

    private fun slotName(m: ModelInstance, first: String?): String = when {
        m.motion == null -> ""
        first == null -> "自制动作"
        first.startsWith("embedded:") -> "模型自带的动画"
        File(first).name.startsWith("工程_") -> "自制动作"
        else -> File(first).name
    }

    private fun kindName(k: Int) = when (k) { ModelInstance.KIND_STAGE -> "舞台"; ModelInstance.KIND_ACCESSORY -> "配件"; else -> "角色" }

    private fun buildState(): UiState {
        val models = scene.models.map { m ->
            val d = m.data
            val euler = FloatArray(3)
            m.getRotationEuler(euler)
            val binding = m.motion
            val motionText = if (binding == null) "" else {
                val name = binding.motion.name.ifBlank { "动作" }
                if (binding.totalBoneTracks > 0) "$name（骨骼匹配 ${binding.matchedBones}/${binding.totalBoneTracks}）" else name
            }
            ModelUi(
                id = m.id, name = m.name, kind = m.kind, visible = m.visible,
                summary = "${d.format.label} · ${kindName(m.kind)} · ${d.vertexCount} 顶点 · ${d.triangleCount} 面" +
                    (if (d.bones.isNotEmpty()) " · ${d.bones.size} 骨骼" else "") + (if (d.hasPhysics) " · ${d.rigidBodies.size} 刚体" else ""),
                skinned = m.skeleton != null, hasPhysics = m.physics != null, physics = m.physicsEnabled,
                edge = m.drawEdge, castShadow = m.castShadow, opacity = m.opacity, scale = m.scale,
                position = m.position.toList(), rotation = euler.toList(),
                motion = motionText, animations = d.animations.map { it.name },
                attachTo = m.attachParent?.id ?: -1,
                attachBone = m.attachParent?.skeleton?.bones?.getOrNull(m.attachBone)?.name ?: "",
                warnings = d.warnings.toList(),
                motionFile = slotName(m, m.motionSources.firstOrNull()),
                morphFile = m.motionSources.drop(1).joinToString(" + ") { File(it).name },
                missingTextures = d.sourceFile?.path?.let { missingByFile[it] } ?: 0,
                motionOffset = Math.round(m.motionOffset),
                layer = m.layer,
                collision = m.collision,
                ownPhysics = m.physicsTuning?.let { t -> com.lumi.mmd.engine.physics.PhysicsTuning.NAMES.associateWith { n -> t.get(n) ?: 0f } } ?: emptyMap(),
                bodies = m.physics?.bodyCount ?: 0,
                transient = m.transient,
                controllable = m.isCharacter && m.skeleton != null && !m.transient,
            )
        }
        val sel = editor.selectedModel
        val part = editor.selectedPart
        val bone = editor.selectedBone
        val boneData = if (bone >= 0) sel?.skeleton?.bones?.getOrNull(bone) else null
        // 引擎那边自己换了模式（点了骨骼列表 / 部件列表）时，界面模式跟上
        val uiOnly = uiMode == Modes.MORPH || uiMode == Modes.CAMERA || uiMode == Modes.CLIPS
        if (!uiOnly && !(uiMode == Modes.MESH && session == null) && uiMode != editor.mode) uiMode = editor.mode
        else if (uiOnly && editor.mode != Editor.MODE_VIEW) uiMode = editor.mode
        return UiState(
            models = models,
            selectedModel = sel?.id ?: -1,
            selectedPart = part?.key ?: "",
            selectedPartName = part?.name ?: "",
            selectedBone = bone,
            selectedBoneName = if (bone >= 0) sel?.skeleton?.bones?.getOrNull(bone)?.name ?: "" else "",
            mode = uiMode, tool = editor.tool, axis = editor.axis,
            islandMode = editor.islandMode, lockDrag = editor.forceManipulate, canUndo = session?.canUndo ?: editor.canUndo,
            loop = scene.loop, speed = scene.speed, physics = scene.physicsEnabled,
            hasCameraMotion = scene.cameraMotion != null, useCameraMotion = scene.useCameraMotion,
            cameraMotionName = scene.cameraMotion?.name ?: "",
            cameraFile = if (scene.cameraMotion?.hasCamera != true) "" else if (cameraMotionFile.isEmpty() || File(cameraMotionFile).name.startsWith("工程_")) "自制镜头" else File(cameraMotionFile).name,
            audioName = scene.audioClips.let { l -> if (l.isEmpty()) "" else if (l.size == 1) l[0].name else "${l[0].name} 等 ${l.size} 段" },
            audioFrames = com.lumi.mmd.engine.scene.ClipOps.end(scene.audioClips),
            play = play.buildUiGl(),
            localAxes = editor.localAxes, autoKey = autoKey, canRedo = session?.canRedo ?: false,
            pending = pendingCountGl(), clipboard = clip?.size ?: 0,
            boneMovable = boneData?.let { it.isTranslatable || it.ik != null } ?: false,
            boneRotatable = boneData?.isRotatable ?: true,
            cameraDriven = scene.cameraDriven, cameraKeys = scene.cameraMotion?.camera?.count ?: 0,
            floor = scene.floor.let { f -> FloorUi(f.enabled, f.height, f.grid, f.shadow, f.collide, f.cell, f.color.toList()) },
            style = renderer.settings.let { s -> StyleUi(s.celBands, s.edgeAll, s.rim, !s.styleIsDefault) },
        )
    }

    private fun pendingCountGl(): Int {
        val m = keyModel() ?: return 0
        var n = KeyOps.posedBones(m).size
        for (i in 0 until m.morphs.count) if (m.morphs.userWeights[i] != 0f) n++
        return n
    }

    private fun buildDetail(): DetailUi {
        val m = editor.selectedModel ?: return DetailUi()
        val parts = ArrayList<PartUi>()
        val ps = m.parts
        var deleted = 0
        val euler = FloatArray(3)
        val deg = (180.0 / Math.PI).toFloat()
        fun ui(p: Part, visible: Boolean, islandCount: Int): PartUi {
            Quat.toEulerYXZ(p.rotation, 0, euler, 0)
            return PartUi(p.key, p.name, p.material, p.island, p.triangleCount, visible, p.isEdited, islandCount,
                listOf(p.offset[0], p.offset[1], p.offset[2]), listOf(euler[0] * deg, euler[1] * deg, euler[2] * deg), listOf(p.scale[0], p.scale[1], p.scale[2]))
        }
        for (p in ps.materialParts) {
            // 删掉的部件不在列表里（连同它里面的连通块）
            if (p.deleted) { deleted++; continue }
            val st = m.materials[p.material]
            val islands = ps.islandsComputed(p.material)
            parts.add(ui(p, st.visible && !p.hidden, islands?.size ?: -1))
            if (islands != null) deleted += islands.count { it.deleted }
            // 只展开当前选中部件所在材质的连通块，列表不至于太长
            if (islands != null && editor.selectedPart?.material == p.material && (editor.islandMode || editor.selectedPart?.island ?: -1 >= 0)) {
                for (ip in islands.take(60)) if (!ip.deleted) parts.add(ui(ip, !ip.hidden, 0))
            }
        }
        val solo = soloKey.takeIf { it.isNotEmpty() && soloModel == m.id } ?: ""
        val morphs = ArrayList<MorphUi>(m.morphs.count)
        for (i in 0 until m.morphs.count) {
            val d = m.data.morphs[i]
            morphs.add(MorphUi(i, d.name.ifBlank { "表情$i" }, d.panel, KeyOps.morphWeight(m, i, curFrame())))
        }
        val bones = ArrayList<BoneUi>()
        val sk = m.skeleton
        if (sk != null) {
            val driven = m.physics?.drivenBones
            for (b in 0 until sk.count) {
                val bd = sk.bones[b]
                if (!bd.isVisible || !bd.isOperable) continue
                if (driven != null && driven[b]) continue
                bones.add(BoneUi(b, bd.name.ifBlank { "骨骼$b" }, bd.ik != null, bd.isTranslatable || bd.ik != null))
            }
        }
        return DetailUi(m.id, parts, deleted, solo, morphs, bones)
    }

    /** 查询某个模型可用来挂载的骨骼名（结果在界面线程回调）。 */
    fun queryBones(id: Int, done: (List<String>) -> Unit) = post {
        val sk = scene.findById(id)?.skeleton
        val names = ArrayList<String>()
        if (sk != null) for (b in 0 until sk.count) { val n = sk.bones[b].name; if (n.isNotBlank()) names.add(n) }
        mainHandler.post { done(names) }
    }

    /** 界面打开 / 关闭需要明细列表的面板时调用。 */
    fun wantDetail(want: Boolean) = post { detailWanted = want; detailDirty = true }

    // ------------------------------------------------------------------ 编辑器命令

    /** 切换工作模式（[Modes]）。 */
    fun setMode(mode: Int) = post {
        val wasBone = editor.mode == Editor.MODE_BONE
        if (mode != Modes.MESH && session != null) endMeshGl()
        val engineMode = if (mode == Modes.MORPH || mode == Modes.CAMERA || mode == Modes.CLIPS) Editor.MODE_VIEW else mode
        // 进编辑的时候退出操控模式（两边都要用画面上的手势）
        if (mode != Modes.VIEW && runtime.controller != null) runtime.stopControl()
        if (engineMode != Editor.MODE_VIEW && editor.selectedModel == null || (mode == Modes.MORPH && editor.selectedModel == null)) {
            // 进入编辑模式时默认选中第一个合适的模型
            val first = scene.models.firstOrNull { it.visible && (mode != Modes.BONE || it.skeleton != null) && it.kind != ModelInstance.KIND_STAGE }
                ?: scene.models.firstOrNull { it.visible }
            if (first != null) editor.select(first)
        }
        if (mode == Modes.MESH) {
            val m = editor.selectedModel
            if (m == null) { toast("场景里还没有模型：先添加一个，或者在菜单的「角色」里点「新建模型」"); editor.mode = Editor.MODE_VIEW; uiMode = Modes.MESH; detailDirty = true; return@post }
            if (m.data.vertexCount > 400_000) toast("这个模型有 ${m.data.vertexCount} 个顶点，建模会比较慢")
            try { beginMeshGl(m) } catch (e: OutOfMemoryError) { toast("内存不足，无法编辑这个模型的网格"); editor.mode = Editor.MODE_MODEL; uiMode = Modes.MODEL }
        } else {
            editor.mode = engineMode
            uiMode = mode
        }
        fixBoneToolGl(enteringBoneMode = engineMode == Editor.MODE_BONE && !wasBone)
        detailDirty = true
    }

    /**
     * 摆姿势模式下让工具和选中的骨骼对得上：大多数骨骼只能旋转，对它们用「移动」拖动不会有任何反应，
     * 所以刚进入这个模式、或者选中了一根不能移动的骨骼时，自动换成「旋转」。
     */
    private fun fixBoneToolGl(enteringBoneMode: Boolean = false) {
        if (editor.mode != Editor.MODE_BONE) return
        if (editor.tool == Editor.TOOL_SCALE || enteringBoneMode) editor.tool = Editor.TOOL_ROTATE
        val bd = editor.selectedModel?.skeleton?.bones?.getOrNull(editor.selectedBone) ?: return
        val movable = bd.isTranslatable || bd.ik != null
        if (editor.tool == Editor.TOOL_MOVE && !movable) editor.tool = Editor.TOOL_ROTATE
        else if (editor.tool == Editor.TOOL_ROTATE && !bd.isRotatable && movable) editor.tool = Editor.TOOL_MOVE
    }

    fun setTool(tool: Int) = post {
        editor.tool = tool
        if (editor.mode == Editor.MODE_BONE && tool == Editor.TOOL_MOVE) {
            val bd = editor.selectedModel?.skeleton?.bones?.getOrNull(editor.selectedBone)
            if (bd != null && !bd.isTranslatable && bd.ik == null) {
                editor.tool = Editor.TOOL_ROTATE
                toast("「${bd.name}」这根骨骼不能移动，只能旋转")
            }
        }
    }
    fun setAxis(axis: Int) = post { editor.axis = axis }
    fun setIslandMode(on: Boolean) = post { editor.islandMode = on; detailDirty = true }
    fun setLockDrag(on: Boolean) = post { editor.forceManipulate = on }
    fun undo() = post {
        val s = session
        if (s != null) { if (!s.undo()) toast("没有可撤销的操作"); projectDirty = true; return@post }
        if (!editor.undo()) toast("没有可撤销的操作") else { detailDirty = true; projectDirty = true }
    }

    fun selectModel(id: Int) = post {
        val m = scene.findById(id)
        if (session != null && m != null && session?.model !== m) { endMeshGl(); beginMeshGl(scene.findById(id) ?: m) }
        else editor.select(m)
        detailDirty = true
    }

    fun selectPart(key: String) = post {
        val m = editor.selectedModel ?: return@post
        val p = m.parts.findByKey(key) ?: return@post
        if (editor.mode != Editor.MODE_PART) editor.mode = Editor.MODE_PART
        editor.select(m, p)
        detailDirty = true
    }

    fun selectBone(index: Int) = post {
        val m = editor.selectedModel ?: return@post
        if (m.skeleton == null) return@post
        val entering = editor.mode != Editor.MODE_BONE
        if (entering) editor.mode = Editor.MODE_BONE
        editor.select(m, null, index)
        fixBoneToolGl(entering)
    }

    private inline fun withModel(id: Int, crossinline block: (ModelInstance) -> Unit) = post {
        val m = scene.findById(id) ?: return@post
        block(m)
        m.poseDirty = true
        projectDirty = true
    }

    fun setModelVisible(id: Int, v: Boolean) = withModel(id) { it.visible = v }
    fun setModelKind(id: Int, kind: Int) = withModel(id) { it.kind = kind }
    private var skippedTexturesTold = 0
    private var modelUndoKey = ""
    private var modelUndoAt = 0L

    /** 用滑杆 / 加减号改模型的位置、角度、大小：连着来的一串改动只记一步撤销（和画面里拖箭头一样能撤销）。 */
    private fun recordModelOnce(m: ModelInstance, what: String) {
        val now = System.nanoTime() / 1_000_000L
        val key = "${m.id}/$what"
        if (key != modelUndoKey || now - modelUndoAt > 900L) {
            val p = m.position.copyOf(); val r = m.rotation.copyOf(); val sc = m.scale
            editor.pushUndo(64) { System.arraycopy(p, 0, m.position, 0, 3); System.arraycopy(r, 0, m.rotation, 0, 4); m.scale = sc; m.poseDirty = true }
        }
        modelUndoKey = key; modelUndoAt = now
    }

    fun setModelScale(id: Int, s: Float) = withModel(id) { recordModelOnce(it, "s"); it.scale = s.coerceIn(0.001f, 1000f) }
    fun setModelPosition(id: Int, axis: Int, v: Float) = withModel(id) { if (axis in 0..2 && v == v) { recordModelOnce(it, "p$axis"); it.position[axis] = v } }
    fun setModelRotation(id: Int, x: Float, y: Float, z: Float) = withModel(id) { recordModelOnce(it, "r"); it.setRotationEuler(x, y, z) }
    /** 绕世界坐标轴再转一个角度（用四元数累乘，不受欧拉角万向节的影响）。axis: 0=X 1=Y 2=Z。 */
    fun rotateModelBy(id: Int, axis: Int, degrees: Float) = withModel(id) { m ->
        val q = FloatArray(4)
        Quat.fromAxisAngle(q, 0, if (axis == 0) 1f else 0f, if (axis == 1) 1f else 0f, if (axis == 2) 1f else 0f, Math.toRadians(degrees.toDouble()).toFloat())
        Quat.mul(m.rotation, 0, q, 0, m.rotation, 0)
        Quat.normalize(m.rotation, 0)
    }
    fun setModelPhysics(id: Int, on: Boolean) = withModel(id) { it.physicsEnabled = on; it.physics?.requestReset() }
    fun setModelEdge(id: Int, on: Boolean) = withModel(id) { it.drawEdge = on }
    fun setModelShadow(id: Int, on: Boolean) = withModel(id) { it.castShadow = on }
    fun setModelOpacity(id: Int, a: Float) = withModel(id) { it.opacity = a.coerceIn(0f, 1f) }
    /** 画在第几层：大的盖在小的上面。 */
    fun setModelLayer(id: Int, layer: Int) = withModel(id) { it.layer = layer.coerceIn(-9, 9) }
    /** 碰撞属性：0 没有 / 1 包围盒 / 2 按网格。别的模型的头发裙子、脚本的粒子和刚体、操控的角色会被它挡住。 */
    fun setModelCollision(id: Int, mode: Int) = withModel(id) {
        it.collision = mode.coerceIn(ModelInstance.COLLISION_NONE, ModelInstance.COLLISION_MESH)
        if (it.collision == ModelInstance.COLLISION_MESH && it.collisionMesh() == null) { it.collision = ModelInstance.COLLISION_BOX; toast("这个模型的面太多（或者没有面），改用包围盒") }
    }

    // ---- 地板 ----

    /** 改地板（高度、网格、影子、碰撞、底色……）。 */
    fun editFloor(change: (com.lumi.mmd.engine.scene.Floor) -> Unit) = post {
        change(scene.floor)
        dirty()
    }
    /** 删掉地板：不画网格、不接影子，东西会一直往下掉。 */
    fun removeFloor() = editFloor { it.enabled = false }
    /** 添加默认地板（已经有的话变回默认的样子）。 */
    fun addDefaultFloor() = editFloor { it.reset() }

    // ---- 画风 ----

    /** 三渲二开关 / 级数（0 = 关）。打开时顺手把「所有模型都描边」带上，关掉时还原。 */
    fun setCel(bands: Int) = post {
        val s = renderer.settings
        if (bands > 0 && s.celBands == 0) s.edgeAll = true
        if (bands <= 0) s.edgeAll = false
        s.celBands = bands.coerceIn(0, 8)
        dirty()
    }
    fun setEdgeAll(on: Boolean) = post { renderer.settings.edgeAll = on; dirty() }
    /** 从预设里挑一个背景（脚本 / AI 指定的背景色就此作废）。 */
    fun pickBackground(index: Int) {
        post { renderer.settings.customBackground = false }
        updatePrefs(_prefs.value.copy(background = index))
    }

    /** 打开系统的「不限制后台」设置：部分系统即使有前台服务也会冻结后台应用。 */
    fun openBackgroundSettings() {
        val tries = listOf(
            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)),
            android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)),
        )
        for (i in tries) {
            try { context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Throwable) {}
        }
        toast("打不开系统设置：请手动在「应用信息 → 电池」里选「无限制」")
    }

    /** 画风回到默认（脚本 / AI 改过的三渲二、描边颜色、调色、自定义背景全部清掉）。 */
    fun resetStyle() = post {
        renderer.settings.resetStyle()
        renderer.settings.customBackground = false
        applyPrefsGl(_prefs.value)
        dirty()
    }

    /** MCP 的 render 工具改了画面设置：把「设置」页里对应的开关对齐，不然下次动任何一个开关都会把 AI 改的盖回去。渲染线程。 */
    override fun lookChanged() {
        val s = renderer.settings
        // 背景不再是 AI 指定的颜色（preset=default）：换回「舞台与背景」里选的那个预设
        if (!s.customBackground) {
            val bg = _prefs.value.background.coerceIn(0, Backgrounds.top.size - 1)
            System.arraycopy(Backgrounds.top[bg], 0, s.backgroundTop, 0, 3)
            System.arraycopy(Backgrounds.bottom[bg], 0, s.backgroundBottom, 0, 3)
        }
        val p = _prefs.value.copy(shadows = s.shadows, edges = s.edges, edgeWidth = s.edgeWidth, bloom = s.bloom, bloomStrength = s.bloomStrength,
            exposure = s.exposure, saturation = s.saturation, rim = s.rim, vignette = s.vignette)
        if (p != _prefs.value) {
            _prefs.value = p
            sp.edit().putBoolean("shadows", p.shadows).putBoolean("edges", p.edges).putFloat("edgeWidth", p.edgeWidth).putBoolean("bloom", p.bloom)
                .putFloat("bloomStrength", p.bloomStrength).putFloat("exposure", p.exposure).putFloat("saturation", p.saturation).putFloat("rim", p.rim)
                .putFloat("vignette", p.vignette).apply()
        }
    }
    fun renameModel(id: Int, name: String) = withModel(id) { if (name.isNotBlank()) it.name = name.trim() }

    fun resetModelTransform(id: Int) = withModel(id) {
        it.position.fill(0f); Quat.identity(it.rotation, 0); it.scale = it.data.suggestedScale
    }

    /** 把模型缩放到"一个人高"左右（单位不明的 OBJ / FBX 等用）。 */
    fun fitModelSize(id: Int, targetHeight: Float) = withModel(id) {
        val d = it.data
        val size = maxOf(d.boundsMax[0] - d.boundsMin[0], d.boundsMax[1] - d.boundsMin[1], d.boundsMax[2] - d.boundsMin[2])
        if (size > 0f) it.scale = targetHeight / size
    }

    /** 把模型放到地面上（包围盒最低点对齐 y=0）。 */
    fun dropToGround(id: Int) = withModel(id) {
        if (it.attachParent != null) { toast("挂载在别的模型上的配件不能单独落地"); return@withModel }
        it.updateWorldMatrix()
        // 要用逐顶点的精确包围盒：蒙皮模型的 worldBounds 只是骨骼位置外扩的近似值，拿它对齐地面角色会悬空
        if (!it.exactWorldBounds(bMin, bMax)) it.worldBounds(bMin, bMax)
        it.position[1] -= bMin[1]
    }

    /** 挂到另一个模型的骨骼上（parentId < 0 = 解除）。挂上时保持当前的偏移为零，方便随后用操纵工具微调。 */
    fun attachModel(id: Int, parentId: Int, boneName: String) = withModel(id) { m ->
        val parent = if (parentId >= 0) scene.findById(parentId) else null
        if (parent == null || parent === m || parent.attachParent === m) {
            m.attachParent = null; m.attachBone = -1
            return@withModel
        }
        val sk = parent.skeleton
        val bone = sk?.bones?.indexOfFirst { it.name == boneName } ?: -1
        m.attachParent = parent
        m.attachBone = bone
        m.position.fill(0f)
        Quat.identity(m.rotation, 0)
        // 父模型有缩放时抵消掉，让配件保持原来的大小
        if (parent.scale > 0f) m.scale = m.scale / parent.scale
    }

    fun removeModel(id: Int) = post {
        val m = scene.findById(id) ?: return@post
        removeModel(m)
    }

    fun clearMotion(id: Int) = withModel(id) { pushClipUndoGl(it); it.selectClip(scene.frame); it.setMotion(null); scene.recomputeDuration() }

    fun setEmbeddedAnimation(id: Int, index: Int) = withModel(id) { m ->
        val a = m.data.animations.getOrNull(index) ?: return@withModel
        m.setMotion(a)
        m.motionSources = listOf("embedded:$index")
        scene.recomputeDuration()
    }

    fun resetPose(id: Int) = withModel(id) { it.resetPose(); detailDirty = true }

    /** 把选中的那根骨骼的手动姿势清掉（回到动作 / 初始姿势）。 */
    fun resetSelectedBone() = post {
        val m = editor.selectedModel ?: return@post
        val sk = m.skeleton ?: return@post
        val b = editor.selectedBone
        if (b !in 0 until sk.count) return@post
        sk.userT[b * 3] = 0f; sk.userT[b * 3 + 1] = 0f; sk.userT[b * 3 + 2] = 0f
        Quat.identity(sk.userR, b * 4)
        m.poseDirty = true; projectDirty = true
    }

    /** 设置表情的权重（滑杆上的值 = 动作里的值 + 手动调整；开着自动关键帧时直接写成当前帧的关键帧）。 */
    fun setMorph(index: Int, weight: Float) = post {
        val m = editor.selectedModel ?: return@post
        if (index !in 0 until m.morphs.count) return@post
        val f = curFrame()
        if (autoKey && !scene.playing) {
            val at = settleFrameGl()
            // 拖一次滑杆会连着来很多次：同一个表情、同一帧只记一步撤销
            val key = (m.id.toLong() shl 40) or (index.toLong() shl 24) or at.toLong()
            if (key != morphUndoKey || editor.undoSerial != morphUndoSerial) { keyUndoGl(m); morphUndoKey = key; morphUndoSerial = editor.undoSerial }
            KeyOps.setMorphKey(m, scene, index, at, weight)
        } else m.morphs.userWeights[index] = weight - (KeyOps.morphWeight(m, index, f) - m.morphs.userWeights[index])
        m.poseDirty = true; projectDirty = true
    }

    fun resetMorphs() = post {
        val m = editor.selectedModel ?: return@post
        m.morphs.resetUserWeights(); m.poseDirty = true; detailDirty = true; projectDirty = true
    }

    // ---- 部件 ----

    private inline fun withPart(key: String, crossinline block: (ModelInstance, Part) -> Unit) = post {
        val m = editor.selectedModel ?: return@post
        val p = m.parts.findByKey(key) ?: return@post
        editor.recordPart(p); partUndoKey = ""
        block(m, p)
        m.parts.apply(p)
        detailDirty = true
        projectDirty = true
    }

    private var partUndoKey = ""
    private var partUndoAt = 0L

    /** 拖滑杆、按住加减号时数值是一串连着来的：同一个部件的同一项，0.9 秒之内只记一次撤销。 */
    private inline fun slidePart(key: String, what: String, crossinline block: (Part) -> Unit) = post {
        val m = editor.selectedModel ?: return@post
        val p = m.parts.findByKey(key) ?: return@post
        val now = System.nanoTime() / 1_000_000L
        val id = "${m.id}/$key/$what"
        if (id != partUndoKey || now - partUndoAt > 900L) editor.recordPart(p)
        partUndoKey = id; partUndoAt = now
        block(p)
        m.parts.apply(p)
        detailDirty = true
        projectDirty = true
    }

    fun setPartVisible(key: String, visible: Boolean) { post { endSoloGl() }; withPart(key) { m, p ->
        if (p.island < 0) m.materials[p.material].visible = visible
        p.hidden = !visible && p.island >= 0
    } }

    fun resetPart(key: String) = withPart(key) { m, p ->
        p.reset()
        if (p.island < 0) m.materials[p.material].visible = true
    }

    fun resetAllParts() = post {
        val m = editor.selectedModel ?: return@post
        endSoloGl()
        m.parts.resetAll()
        for (st in m.materials) st.visible = true
        detailDirty = true; projectDirty = true
    }

    /** 数值微调：axis 0..2 = 位移 xyz，3 = 整体缩放。 */
    fun nudgePart(key: String, axis: Int, delta: Float) = withPart(key) { _, p ->
        if (axis in 0..2) p.offset[axis] += delta
        else for (k in 0 until 3) p.scale[k] = (p.scale[k] * (1f + delta)).coerceIn(0.01f, 100f)
    }

    /** 部件的位移：axis 0..2 = X / Y / Z。 */
    fun setPartOffset(key: String, axis: Int, v: Float) = slidePart(key, "o$axis") { p ->
        if (axis in 0..2 && v == v) p.offset[axis] = v.coerceIn(-100000f, 100000f)
    }

    /** 部件的旋转（度）：绕 X、绕 Y、绕 Z，绕它自己的中心转。 */
    fun setPartRotation(key: String, x: Float, y: Float, z: Float) = slidePart(key, "r") { p ->
        val k = (Math.PI / 180.0).toFloat()
        Quat.fromEulerYXZ(p.rotation, 0, x * k, y * k, z * k)
        Quat.normalize(p.rotation, 0)
    }

    /** 部件的大小：axis 0..2 = 只改一个方向，3 = 三个方向一起（保持原来的比例）。 */
    fun setPartScale(key: String, axis: Int, v: Float) = slidePart(key, "s$axis") { p ->
        val t = if (v == v) v.coerceIn(0.01f, 100f) else 1f
        if (axis in 0..2) p.scale[axis] = t
        else {
            val cur = Math.cbrt((p.scale[0] * p.scale[1] * p.scale[2]).toDouble()).toFloat().coerceAtLeast(1e-4f)
            for (i in 0 until 3) p.scale[i] = (p.scale[i] * t / cur).coerceIn(0.01f, 100f)
        }
    }

    /**
     * 删掉一个部件。它从画面和列表里消失，点「撤销」或者列表底下的「恢复」能找回来；
     * 进建模、另存为 PMX 的时候才真正从模型数据里拿掉。
     */
    fun deletePart(key: String) = post {
        val m = editor.selectedModel ?: return@post
        val p = m.parts.findByKey(key) ?: return@post
        if (p.deleted) return@post
        if (p.island < 0 && m.parts.materialParts.count { !it.deleted } <= 1) { toast("这是最后一个部件了；想去掉整个模型，用「模型」里的「移除」"); return@post }
        endSoloGl()
        editor.recordPart(p); partUndoKey = ""
        p.deleted = true
        if (p.island < 0) m.materials[p.material].visible = false
        m.parts.apply(p)
        val sel = editor.selectedPart
        if (sel === p || (p.island < 0 && sel?.material == p.material)) editor.select(m)
        detailDirty = true; projectDirty = true
        toast("已删除「${p.name}」，点撤销可以找回")
    }

    /** 把删掉的部件全部找回来。 */
    fun restoreDeletedParts() = post {
        val m = editor.selectedModel ?: return@post
        val list = m.parts.deletedParts()
        if (list.isEmpty()) return@post
        endSoloGl()
        for (p in list) {
            editor.recordPart(p)
            p.deleted = false
            if (p.island < 0) m.materials[p.material].visible = true
            m.parts.apply(p)
        }
        partUndoKey = ""
        detailDirty = true; projectDirty = true
        toast("已恢复 ${list.size} 个部件")
    }

    private var soloKey = ""
    private var soloModel = -1
    private var soloSaved: BooleanArray? = null

    /** 只看这一个部件（别的整块部件临时藏起来）；再点一次恢复原样。不存进工程。 */
    fun soloPart(key: String) = post {
        val m = editor.selectedModel ?: return@post
        val p = m.parts.findByKey(key) ?: return@post
        val same = soloKey == key && soloModel == m.id
        endSoloGl()
        if (same) return@post
        soloSaved = BooleanArray(m.materials.size) { m.materials[it].visible }
        for ((i, st) in m.materials.withIndex()) st.visible = i == p.material
        soloKey = key; soloModel = m.id
        detailDirty = true
    }

    private fun endSoloGl() {
        val saved = soloSaved ?: return
        scene.findById(soloModel)?.let { m -> if (saved.size == m.materials.size) for ((i, st) in m.materials.withIndex()) st.visible = saved[i] }
        soloSaved = null; soloKey = ""; soloModel = -1
        detailDirty = true
    }

    // ------------------------------------------------------------------ 播放

    fun togglePlay() = post { if (scene.playing) pauseGl() else playGl() }

    private fun playGl() {
        if (session != null) { toast("建模时模型保持初始姿势；点「完成」退出建模后再播放"); return }
        if (scene.maxFrame <= 0) { toast("还没有动作：先打几个关键帧，或到资源库加载一个 VMD"); return }
        if (scene.maxFrame > 0 && scene.frame >= scene.maxFrame - 0.5f) scene.seek(0f)
        scene.playing = true
        audioPlayGl()
    }

    private fun pauseGl() {
        scene.playing = false
        deck.pause()
        prepareAudioSoon()
    }

    /** 让音乐从和当前帧对应的位置开始播。 */
    private fun audioPlayGl() {
        scene.sync.reset()
        deck.start(scene.audioClips, scene.frame, scene.speed, System.nanoTime())
    }

    /** 渲染线程：时间轴的位置被外面改了（脚本、MCP）——正在播放的话让音乐跟过去。 */
    internal fun audioFollowGl() { if (scene.playing) audioPlayGl() else prepareAudioSoon() }

    private val prepareAudio = Runnable { post { if (!scene.playing) deck.prepare(scene.audioClips, scene.frame) } }

    /**
     * 暂停着的时候时间线停稳了（或者音乐轨道改过了）：过一小会儿把播放器准备到当前位置，
     * 这样按下播放马上就有声音。拖动进度条的过程中不会每一下都去准备。
     */
    private fun prepareAudioSoon() {
        mainHandler.removeCallbacks(prepareAudio)
        mainHandler.postDelayed(prepareAudio, 250)
    }

    // ------------------------------------------------------------------ 音乐轨道

    /** 问系统的解码器：这个文件能不能放、有多长（秒）。放不了返回 null。会卡一小会儿，不要在渲染线程调用。 */
    internal fun probeAudio(file: File): Float? {
        val p = AudioPlayer()
        return try { if (p.load(file)) p.durationSeconds.takeIf { it > 0f } else null } finally { p.release() }
    }

    /** 改音乐轨道之前调用：记一步撤销。 */
    internal fun pushAudioUndoGl() {
        class S(val clip: com.lumi.mmd.engine.scene.AudioClip, val start: Int, val trimIn: Int, val trimOut: Int)
        val snap = scene.audioClips.map { S(it, it.start, it.trimIn, it.trimOut) }
        editor.pushUndo(512) {
            scene.audioClips.clear()
            for (x in snap) { x.clip.start = x.start; x.clip.trimIn = x.trimIn; x.clip.trimOut = x.trimOut; scene.audioClips.add(x.clip) }
            afterAudioEditGl()
        }
    }

    /** 音乐轨道改过之后：重新算总长，正在播放的话让声音重新对上。 */
    internal fun afterAudioEditGl() {
        scene.audioClips.sortBy { it.start }
        scene.recomputeDuration()
        if (scene.playing) audioPlayGl() else prepareAudioSoon()
        projectDirty = true; stateDirty = true
        kick()
    }

    override fun beforeAudioEdit() = pushAudioUndoGl()
    override fun audioChanged() = afterAudioEditGl()
    override fun audioSeconds(file: File): Float? = probeAudio(file) ?: com.lumi.mmd.engine.scene.AudioDeck.wavSeconds(file)

    /**
     * 往音乐轨道上放一个文件。
     * @param replace true = 把轨道上原来的全换掉，新的从头放（「动作与音乐」里的音乐槽）；false = 接在最后面（轨道里的「添加」）
     */
    fun addAudio(path: String, replace: Boolean) {
        val file = File(path)
        audioIo.execute {
            val seconds = probeAudio(file)
            post {
                if (seconds == null) { toast("无法播放 ${file.name}"); return@post }
                pushAudioUndoGl()
                if (replace) scene.audioClips.clear()
                val clip = com.lumi.mmd.engine.scene.AudioClip(path, file.name, Math.round(seconds * Motion.FPS).coerceAtLeast(1))
                com.lumi.mmd.engine.scene.ClipOps.insert(scene.audioClips, clip, if (replace) 0 else com.lumi.mmd.engine.scene.ClipOps.end(scene.audioClips))
                play.selectAudioGl(clip.id)
                afterAudioEditGl()
                toast(if (replace) "已设置音乐：${file.name}" else "已把「${file.name}」接在音乐轨道的最后")
            }
        }
    }

    /** 某个模型的动作从第几帧开始播。 */
    fun setMotionOffset(id: Int, frames: Int) = withModel(id) { it.motionOffset = frames.coerceIn(0, 100000).toFloat(); scene.recomputeDuration() }

    fun pause() = post { pauseGl() }

    fun seek(frame: Float) = post {
        scene.seek(frame, beyondEnd = !scene.playing)
        if (scene.playing) audioPlayGl() else prepareAudioSoon()
    }

    fun setLoop(on: Boolean) = post { scene.loop = on; projectDirty = true }

    fun setSpeed(s: Float) = post {
        scene.speed = s.coerceIn(0.1f, 2f)
        if (scene.playing) audioPlayGl()
    }

    fun setPhysics(on: Boolean) = post { scene.physicsEnabled = on; for (m in scene.models) { m.poseDirty = true; m.physics?.requestReset() } }
    fun setUseCameraMotion(on: Boolean) = post { scene.useCameraMotion = on; projectDirty = true }
    fun clearCameraMotion() = post { scene.cameraMotion = null; cameraMotionFile = ""; cameraSavedHash = 0; scene.recomputeDuration(); projectDirty = true }

    /** 把音乐轨道清空。 */
    fun clearAudio() = post {
        if (scene.audioClips.isEmpty()) return@post
        pushAudioUndoGl()
        scene.audioClips.clear()
        afterAudioEditGl()
    }

    fun resetCamera() = post {
        if (scene.cameraDriven) { toast("正在使用镜头动作，先关闭「镜头」再手动调整视角"); return@post }
        scene.camera.reset()
        scene.camera.fovDeg = _prefs.value.fov
        if (scene.characterBounds(bMin, bMax)) {
            scene.camera.update(width.toFloat() / height)
            scene.camera.frame(bMin, bMax)
        }
        scene.userCamera.copyFrom(scene.camera)
        projectDirty = true
    }

    /** 把视角对准选中的模型。 */
    fun focusSelected() = post {
        val m = editor.selectedModel ?: return@post
        if (cameraLocked()) return@post
        m.updateWorldMatrix()
        // 建模 / 摆骨骼时对准选中的那一小块，其它时候对准整个模型
        if (editor.pivotWorld(tmp3) && (editor.mode == Editor.MODE_BONE || editor.mode == Editor.MODE_MESH || editor.mode == Editor.MODE_PART)) {
            System.arraycopy(tmp3, 0, scene.camera.target, 0, 3)
        } else {
            if (!m.exactWorldBounds(bMin, bMax)) m.worldBounds(bMin, bMax)
            scene.camera.update(width.toFloat() / height)
            scene.camera.frame(bMin, bMax)
        }
        if (scene.cameraDriven) scene.holdCamera() else scene.userCamera.copyFrom(scene.camera)
    }

    /** 把镜头转到预设方向：0 正面 1 背面 2 左侧 3 右侧 4 顶部 5 底部。 */
    fun cameraPreset(which: Int) = post {
        if (cameraLocked()) return@post
        val c = scene.camera
        val d90 = (Math.PI / 2).toFloat()
        c.rotZ = 0f
        when (which) {
            0 -> { c.rotX = 0f; c.rotY = 0f }
            1 -> { c.rotX = 0f; c.rotY = d90 * 2f }
            2 -> { c.rotX = 0f; c.rotY = d90 }
            3 -> { c.rotX = 0f; c.rotY = -d90 }
            4 -> { c.rotX = -d90 * 0.99f; c.rotY = 0f }
            else -> { c.rotX = d90 * 0.99f; c.rotY = 0f }
        }
        if (scene.cameraDriven) scene.holdCamera() else scene.userCamera.copyFrom(c)
        projectDirty = true
    }

    /** 镜头视角（度）。镜头模式下调它再登记关键帧，可以做推拉变焦。 */
    fun setCameraFov(fov: Float) = post {
        scene.camera.fovDeg = fov.coerceIn(5f, 120f)
        if (scene.cameraDriven) scene.holdCamera() else scene.userCamera.copyFrom(scene.camera)
    }

    // ------------------------------------------------------------------ 加载

    private fun beginLoad(text: String) { pendingLoads.incrementAndGet(); setLoading(text); glRequestRender?.invoke() }
    private fun endLoad() { if (pendingLoads.decrementAndGet() <= 0) { pendingLoads.set(0); setLoading("") } }

    private fun describe(e: Throwable): String = when (e) {
        is FormatException -> e.message ?: "文件格式错误"
        is OutOfMemoryError -> "内存不足，模型太大了。可以在设置里调低贴图上限，或先移除场景里的其它模型"
        is java.io.EOFException -> e.message ?: "文件不完整"
        else -> "${e.javaClass.simpleName}: ${e.message ?: ""}"
    }

    /**
     * 加载一个模型文件并加入场景。
     * @param kind 角色 / 舞台 / 配件；null = 自动判断
     */
    fun loadModel(path: String, kind: Int? = null, saved: Map<String, Any?>? = null, done: ((ModelInstance?) -> Unit)? = null) {
        val file = File(path)
        beginLoad("正在加载 ${file.name}…")
        loader.execute {
            var inst: ModelInstance? = null
            var error: String? = null
            try {
                val data = ModelLoader.loadModel(file)
                countMissing(data).let { if (it > 0) missingByFile[file.path] = it else missingByFile.remove(file.path) }
                val k = kind ?: SceneOps.guessKind(data, file.name)
                inst = ModelInstance(data, k)
                inst.scale = data.suggestedScale
                if (k == ModelInstance.KIND_STAGE) { inst.castShadow = false }
            } catch (e: Throwable) {
                error = describe(e)
                Log.w(TAG, "加载 ${file.name} 失败", e)
            }
            val result = inst
            val p = glPost
            if (p == null || result == null) {
                endLoad()
                if (error != null) toast("${file.name} 加载失败：$error")
                done?.invoke(null)
                return@execute
            }
            p(Runnable {
                try {
                    addModelGl(result, saved)
                    // 场景太大、只导入了一部分：当场说一声（详细的在模型页里）
                    if (saved == null) result.data.warnings.firstOrNull { it.startsWith("场景太大") }?.let { toast(it.substringBefore("（").substringBefore("。")) }
                } catch (e: Throwable) {
                    Log.e(TAG, "加入场景失败", e)
                    toast("${file.name} 加载失败：${describe(e)}")
                }
                endLoad()
                kick(4f)
                done?.invoke(result)
            })
            glRequestRender?.invoke()
        }
    }

    private fun addModelGl(m: ModelInstance, saved: Map<String, Any?>?) {
        val d = m.data
        if (saved != null) {
            SceneIO.restoreModel(m, saved)
        } else {
            // 新加入的角色依次排开，不要叠在一起
            if (m.kind == ModelInstance.KIND_CHARACTER) {
                val others = scene.models.count { it.kind == ModelInstance.KIND_CHARACTER && it.attachParent == null }
                if (others > 0) {
                    val step = 10f
                    val n = (others + 1) / 2
                    m.position[0] = if (others % 2 == 1) step * n else -step * n
                }
            }
            if (d.animations.isNotEmpty()) { m.setMotion(d.animations[0]); m.motionSources = listOf("embedded:0") }
            if (m.kind == ModelInstance.KIND_STAGE && _prefs.value.stageLight && SceneOps.applyStageLight(scene, d)) toast("已使用舞台文件自带的灯光方向")
        }
        scene.add(m)
        scene.recomputeDuration()
        if (saved == null) {
            editor.select(m)
            val first = scene.models.size == 1
            if (first && !scene.cameraDriven) {
                m.updateWorldMatrix()
                m.update(scene.frame, 0f, false, true)
                if (d.vertexCount > 0) {
                    if (!m.exactWorldBounds(bMin, bMax)) m.worldBounds(bMin, bMax)
                    scene.camera.update(width.toFloat() / height)
                    scene.camera.frame(bMin, bMax)
                    scene.userCamera.copyFrom(scene.camera)
                }
            }
            val w = d.warnings
            if (w.isNotEmpty()) toast("${m.name}：${w.first()}" + if (w.size > 1) "（另有 ${w.size - 1} 条提示）" else "")
            else toast("已加载 ${m.name}")
        }
        detailDirty = true
        projectDirty = true
    }

    private fun loadMotionCached(path: String): Motion {
        synchronized(motionCache) { motionCache[path]?.let { return it } }
        val m = ModelLoader.loadMotion(File(path))
        synchronized(motionCache) { if (motionCache.size > 8) motionCache.clear(); motionCache[path] = m }
        return m
    }

    /**
     * 应用一个 VMD。镜头 / 灯光动作自动设为场景镜头；模型动作应用到目标模型。
     * @param targetId 目标模型；<0 = 当前选中的（没有选中则是第一个角色）
     * @param merge true = 叠加到已有动作上（身体 + 表情 + 口型分开的 VMD）
     * @param all true = 应用到所有角色
     */
    fun applyMotion(path: String, targetId: Int = -1, merge: Boolean = false, all: Boolean = false, auto: Boolean = false, wanted: Int = Slot.AUTO) {
        val file = File(path)
        beginLoad("正在读取 ${file.name}…")
        loader.execute {
            try {
                val motion = loadMotionCached(path)
                // 表情槽里换文件：从最底下那段动作重新叠，而不是在上一个表情上面再叠一层
                val base: Motion? = if (merge && !all) try {
                    val m0 = (if (targetId >= 0) scene.findById(targetId) else editor.selectedModel) ?: scene.models.firstOrNull { it.kind == ModelInstance.KIND_CHARACTER && it.skeleton != null }
                    val first = m0?.motionSources?.firstOrNull()
                    if (m0 != null && m0.motionSources.size >= 2 && first != null && !first.startsWith("embedded:") && File(first).isFile) loadMotionCached(first) else null
                } catch (_: Throwable) { null } else null
                post {
                    if (wanted == Slot.CAMERA && !motion.hasCamera) toast("${file.name} 里没有镜头数据，按角色动作处理")
                    if ((wanted == Slot.MOTION || wanted == Slot.MORPH) && motion.hasCamera && !motion.hasModelMotion) toast("${file.name} 是镜头动作，已放到「相机」")
                    if (motion.hasCamera && !motion.hasModelMotion) {
                        // 镜头关键帧会被就地修改，所以用一份自己的拷贝（缓存里的那份可能还要给别处用）
                        scene.selectCameraClip(scene.frame)
                        scene.cameraMotion = motion.copy()
                        scene.activeCameraClip?.let { it.name = file.nameWithoutExtension; it.owned = false }
                        scene.useCameraMotion = true
                        cameraMotionFile = path
                        cameraSavedHash = cameraHash()
                        scene.recomputeDuration()
                        toast("已设置镜头动作：${file.nameWithoutExtension}")
                    } else if (!motion.hasModelMotion) {
                        toast("${file.name} 里没有骨骼 / 表情关键帧")
                    } else {
                        val targets = if (all) scene.models.filter { it.kind == ModelInstance.KIND_CHARACTER && it.skeleton != null }
                        else listOfNotNull(
                            (if (targetId >= 0) scene.findById(targetId) else editor.selectedModel)?.takeIf { it.skeleton != null }
                                ?: scene.models.firstOrNull { it.kind == ModelInstance.KIND_CHARACTER && it.skeleton != null }
                                ?: scene.models.firstOrNull { it.skeleton != null })
                        if (targets.isEmpty()) toast("场景里还没有可以套用动作的模型，请先加载角色")
                        for (m in targets) {
                            // 自动安排：只有表情 / 口型的 VMD 叠在已有的身体动作上，其它的直接换掉
                            val overlay = merge || (auto && motion.boneTracks.isEmpty() && (m.motion?.motion?.boneTracks?.isNotEmpty() == true))
                            applyMotionGl(m, motion, path, overlay, base)
                        }
                        scene.recomputeDuration()
                        if (motion.hasCamera) { scene.selectCameraClip(scene.frame); scene.cameraMotion = motion.copy(); cameraMotionFile = path; cameraSavedHash = cameraHash() }
                    }
                    projectDirty = true
                    detailDirty = true
                }
            } catch (e: Throwable) {
                toast("${file.name} 读取失败：${describe(e)}")
            } finally { endLoad() }
        }
    }

    private fun applyMotionGl(m: ModelInstance, motion: Motion, path: String, merge: Boolean, base: Motion? = null) {
        val current = m.motion?.motion
        if (merge && current != null && m.motionSources.none { it.startsWith("embedded:") }) {
            val combined = Motion()
            combined.mergeFrom(base ?: current)
            combined.overlay(motion)
            val keep = m.motionSources
            m.setMotion(combined)
            m.motionSources = if (base != null) listOf(keep[0], path) else keep + path
        } else {
            m.setMotion(motion)
            m.motionSources = listOf(path)
            m.activeClip?.name = File(path).nameWithoutExtension
        }
        val b = m.motion ?: return
        if (b.totalBoneTracks > 0 && b.matchedBones * 4 < b.totalBoneTracks) {
            toast("${m.name}：动作里只有 ${b.matchedBones}/${b.totalBoneTracks} 根骨骼能对上，这个模型的骨骼命名可能不是 MMD 标准")
        } else toast("已应用动作到 ${m.name}")
    }

    fun applyPose(path: String) {
        val file = File(path)
        loader.execute {
            try {
                val pose = VpdReader.read(file.readBytes())
                post {
                    val m = editor.selectedModel?.takeIf { it.skeleton != null } ?: scene.models.firstOrNull { it.kind == ModelInstance.KIND_CHARACTER && it.skeleton != null }
                    if (m == null) { toast("请先加载角色模型"); return@post }
                    val n = SceneOps.applyPose(m, pose)
                    toast("已套用姿势（${n}/${pose.size} 根骨骼）")
                    projectDirty = true
                }
            } catch (e: Throwable) { toast("${file.name} 读取失败：${describe(e)}") }
        }
    }

    /** 把这个文件设为音乐（换掉音乐轨道上原来的）。 */
    fun setAudio(path: String) = addAudio(path, replace = true)

    /** 导入用户选的文件 / 文件夹到资源库。完成后回调（界面线程之外）。 */
    fun importUris(uris: List<Uri>, tree: Boolean, done: (ImportResult) -> Unit) {
        setLoading("正在导入…")
        io.execute {
            val result = try {
                if (tree) library.importTree(uris[0]) { setLoading(it) } else library.importFiles(uris) { setLoading(it) }
            } catch (e: Throwable) {
                Log.e(TAG, "导入失败", e)
                ImportResult(emptyList(), "导入失败：${describe(e)}")
            }
            setLoading(if (pendingLoads.get() > 0) "正在加载…" else "")
            _libraryVersion.value = _libraryVersion.value + 1
            if (result.message.isNotBlank()) toast(result.message)
            done(result)
        }
    }

    fun notifyLibraryChanged() { _libraryVersion.value = _libraryVersion.value + 1 }

    /**
     * 从资源库里删掉一个文件。模型会连它的贴图一起删（它所在的包里没有别的东西时整包删掉）。
     * 场景里正在用它的（模型、音乐、动作片段、脚本）也一起移除。
     */
    fun deleteLibraryItem(path: String, name: String) {
        io.execute {
            val freed = try { library.deleteItem(path) } catch (e: Throwable) { Log.w(TAG, "删除失败", e); -1L }
            notifyLibraryChanged()
            if (freed < 0) { toast("没能删除「$name」"); return@execute }
            post {
                val n = dropMissingGl()
                toast("已删除「$name」" + (if (freed > 0) "，腾出 ${sizeText(freed)}" else "") + (if (n > 0) "；场景里用到它的 $n 项也移除了" else ""))
            }
        }
    }

    /** 从资源库里删掉一整个包（一次导入的所有文件）。 */
    fun deleteLibraryPack(pack: String) {
        io.execute {
            val before = try { library.packBytes(pack) } catch (_: Throwable) { 0L }
            val ok = try { library.deletePack(pack) } catch (e: Throwable) { false }
            notifyLibraryChanged()
            if (!ok) { toast("没能删除「$pack」"); return@execute }
            post {
                val n = dropMissingGl()
                toast("已删除「$pack」" + (if (before > 0) "，腾出 ${sizeText(before)}" else "") + (if (n > 0) "；场景里用到它的 $n 项也移除了" else ""))
            }
        }
    }

    private fun sizeText(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * 渲染线程：资源库里的文件被删掉之后，把场景里还在用它们的东西移除——
     * 模型留着的话，下次重新装贴图时会变成白模，工程也恢复不出来。返回移除了多少项。
     */
    private fun dropMissingGl(): Int {
        val root = library.root.path + File.separator
        fun gone(path: String): Boolean = path.startsWith(root) && !File(path).exists()
        var n = 0
        for (m in scene.models.toList()) {
            val f = m.data.sourceFile
            if (!m.transient && f != null && gone(f.path)) {
                if (session?.model === m) { session?.dirty = false; endMeshGl() }
                removeModel(m)
                n++
            }
        }
        fun dropClips(lane: MutableList<com.lumi.mmd.engine.anim.MotionClip>): Int {
            val dead = lane.filter { c -> !c.owned && c.sources.isNotEmpty() && c.sources.none { it.startsWith("embedded:") || !gone(it) } }
            lane.removeAll(dead)
            return dead.size
        }
        for (m in scene.models) {
            val k = dropClips(m.clips)
            if (k > 0) { m.sortClips(); m.motionRevision++; m.poseDirty = true; n += k }
        }
        val kc = dropClips(scene.cameraClips)
        if (kc > 0) { scene.sortCameraClips(); scene.releaseCamera(); n += kc }
        val deadAudio = scene.audioClips.filter { gone(it.path) }
        if (deadAudio.isNotEmpty()) { scene.audioClips.removeAll(deadAudio); n += deadAudio.size; afterAudioEditGl() }
        for (sc in runtime.scripts.scripts.toList()) if (sc.file.isNotEmpty() && gone(sc.file)) { runtime.scripts.remove(sc); n++ }
        if (n > 0) {
            scene.recomputeDuration()
            if (scene.frame > scene.maxFrame) scene.seek(scene.maxFrame.toFloat())
            projectDirty = true; stateDirty = true; detailDirty = true
            kick()
        }
        return n
    }

    // ------------------------------------------------------------------ 屏幕方向

    private val _orientation = MutableStateFlow(context.getSharedPreferences("ui", Context.MODE_PRIVATE).getInt("orientation", 0))
    /** 0 = 跟随系统的自动旋转，1 = 横屏，2 = 竖屏。 */
    val orientation: StateFlow<Int> = _orientation
    fun setOrientation(v: Int) {
        _orientation.value = v
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putInt("orientation", v).apply()
    }

    // ------------------------------------------------------------------ 添加文件：自动安排到各个槽里

    private val _texturePrompt = MutableStateFlow<TexturePrompt?>(null)
    /** 有模型的贴图没跟着导入、需要用户帮忙授权时不为空。 */
    val texturePrompt: StateFlow<TexturePrompt?> = _texturePrompt
    private val _modelChoice = MutableStateFlow<ModelChoice?>(null)
    /** 一次导入里有好几个模型时不为空：界面让用户挑一个。 */
    val modelChoice: StateFlow<ModelChoice?> = _modelChoice
    private var afterChoice: (() -> Unit)? = null
    private var choiceKind: Int? = null
    /** 资源库里的模型文件 → 还缺几张贴图（加载时顺手数出来的）。 */
    private val missingByFile = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** 资源库里的模型文件 → 它原来在手机里的位置（第一行 Uri，第二行大小，之后是候选路径）。 */
    private val sourcePrefs get() = context.getSharedPreferences("sources", Context.MODE_PRIVATE)

    /**
     * 添加文件：先放进资源库，再按内容自动安排——模型加进场景，动作套给角色，镜头、音乐、姿势各就各位。
     * @param slot 指定放进哪个槽（[Slot]）；AUTO = 看文件内容
     * @param targetId 动作 / 表情给哪个模型；<0 = 选中的（没有选中则是第一个角色）
     */
    fun addFiles(uris: List<Uri>, tree: Boolean = false, slot: Int = Slot.AUTO, targetId: Int = -1) {
        if (uris.isEmpty()) return
        importUris(uris, tree) { result -> placeImported(result, slot, targetId) }
    }

    /** 把资源库里已有的一个文件放进场景（槽位里"从资源库选"用）。 */
    fun useLibraryFile(path: String, type: Int, slot: Int = Slot.AUTO, targetId: Int = -1) {
        io.execute { placeImported(ImportResult(listOf(LibraryItem(path, File(path).name, "", type, File(path).length())), ""), slot, targetId) }
    }

    private fun kindForSlot(slot: Int): Int? = if (slot == Slot.STAGE) ModelInstance.KIND_STAGE else null

    private fun placeImported(result: ImportResult, slot: Int, targetId: Int) {
        val items = result.items
        if (items.isEmpty()) return
        val models = items.filter { it.type == LibraryItem.TYPE_MMD_MODEL || it.type == LibraryItem.TYPE_OTHER_MODEL }
        val others = items.filter { it.type != LibraryItem.TYPE_MMD_MODEL && it.type != LibraryItem.TYPE_OTHER_MODEL }
        // 单独选的模型文件：贴图不会跟着来，先回它原来的文件夹找
        for (m in models) result.sources[m.path]?.let { checkTextures(m.path, it) }
        // 一个包里带了好几段身体动作时不替用户做主
        val bodyMotions = others.count { it.type == LibraryItem.TYPE_MOTION && isBodyMotion(it.path) }
        val after = {
            var skipped = 0
            for (o in others) {
                if (o.type == LibraryItem.TYPE_MOTION && bodyMotions > 1 && slot == Slot.AUTO && isBodyMotion(o.path)) { skipped++; continue }
                placeOne(o, slot, targetId)
            }
            if (skipped > 0) toast("导入了 $skipped 段动作，在「动作与音乐」里点「动作」挑一段")
        }
        when {
            models.isEmpty() -> after()
            models.size == 1 -> loadModel(models[0].path, kindForSlot(slot)) { after() }
            else -> {
                // MMD 模型优先排在前面
                val sorted = models.sortedBy { it.type }
                afterChoice = after
                choiceKind = kindForSlot(slot)
                _modelChoice.value = ModelChoice(sorted.map { it.path to (if (it.pack.isNotEmpty()) it.pack + " / " else "") + it.name }, "这里面有 ${models.size} 个模型，加载哪一个？")
            }
        }
    }

    /** 用户在"加载哪一个"里做了选择（path = null 表示都不加载）。 */
    fun chooseModel(path: String?) {
        val after = afterChoice
        val kind = choiceKind
        afterChoice = null; choiceKind = null
        _modelChoice.value = null
        if (path == null) { if (after != null) io.execute(after); return }
        loadModel(path, kind) { if (after != null) io.execute(after) }
    }

    private fun isBodyMotion(path: String): Boolean = try { loadMotionCached(path).boneTracks.isNotEmpty() } catch (_: Throwable) { false }

    private fun placeOne(item: LibraryItem, slot: Int, targetId: Int) {
        when (item.type) {
            LibraryItem.TYPE_MOTION -> if (slot == Slot.CLIP) play.appendAuto(item.path, targetId)
                else applyMotion(item.path, targetId, merge = slot == Slot.MORPH, auto = slot == Slot.AUTO || slot == Slot.MOTION || slot == Slot.CAMERA, wanted = slot)
            LibraryItem.TYPE_POSE -> applyPose(item.path)
            LibraryItem.TYPE_AUDIO -> addAudio(item.path, replace = slot != Slot.CLIP)
            LibraryItem.TYPE_SCRIPT -> play.addScriptFile(item.path)
        }
    }

    /** 清空某个模型的「表情」槽：只留下最底下那段动作。 */
    fun clearMorphSlot(id: Int) {
        loader.execute {
            val m0 = scene.findById(id) ?: return@execute
            val first = m0.motionSources.firstOrNull() ?: return@execute
            if (m0.motionSources.size < 2 || first.startsWith("embedded:")) return@execute
            try {
                val base = loadMotionCached(first)
                post {
                    val m = scene.findById(id) ?: return@post
                    m.setMotion(base); m.motionSources = listOf(first)
                    scene.recomputeDuration(); projectDirty = true; detailDirty = true
                }
            } catch (e: Throwable) { toast("读取失败：${describe(e)}") }
        }
    }

    // ---- 找贴图 ----

    private fun countMissing(data: com.lumi.mmd.engine.model.ModelData): Int {
        var n = 0
        for (t in data.textures) if (t.embedded == null && t.path.isNotBlank() && TextureResolver.resolve(data.baseDir, t.path) == null) n++
        return n
    }

    private fun rememberSource(path: String, uri: Uri) {
        val guesses = SourceAccess.guessPaths(context, uri)
        sourcePrefs.edit().putString(path, (listOf(uri.toString(), library.sizeOf(uri).toString()) + guesses).joinToString("\n")).apply()
    }

    private class SourceInfo(val uri: Uri?, val size: Long, val guesses: List<String>)

    private fun sourceOf(path: String): SourceInfo? {
        val lines = sourcePrefs.getString(path, null)?.split('\n') ?: return null
        if (lines.size < 2) return null
        return SourceInfo(lines[0].takeIf { it.isNotBlank() }?.let { Uri.parse(it) }, lines[1].toLongOrNull() ?: -1L, lines.drop(2))
    }

    /** 直接去模型原来的文件夹里读（需要存储权限）。读不到那个文件夹时返回 null。 */
    private fun fetchDirect(path: String): Pair<Int, List<String>>? {
        val src = sourceOf(path) ?: return null
        val file = File(path)
        // 路径重新猜一遍：有些猜法（向媒体库问）要有了存储权限才问得出来
        val guesses = (src.guesses + (src.uri?.let { try { SourceAccess.guessPaths(context, it) } catch (_: Throwable) { emptyList() } } ?: emptyList())).distinct()
        val real = SourceAccess.verify(guesses, file.name, src.size) ?: SourceAccess.verify(guesses, file.name, -1L) ?: return null
        val dir = real.parentFile ?: return null
        if (dir.list() == null) return null
        setLoading("正在把贴图一起带进来…")
        return SourceAccess.fetchMissing(file, FileSourceDir(dir)) { n -> if (n % 4 == 0) setLoading("正在把贴图一起带进来… $n") }
    }

    private fun describeMissing(list: List<String>): String =
        list.take(3).joinToString("、") { it.substringAfterLast('/') } + if (list.size > 3) " 等 ${list.size} 个" else ""

    /** 单独导入的模型文件：检查贴图齐不齐，不齐就回原来的文件夹找；没有权限读那个文件夹时提示用户授权。io 线程。 */
    private fun checkTextures(path: String, uri: Uri?) {
        val file = File(path)
        setLoading("正在检查贴图…")
        var missing = ModelRefs.missing(file)
        if (missing.isEmpty() && ModelRefs.optional(file).isEmpty()) { missingByFile.remove(path); setLoading(if (pendingLoads.get() > 0) "正在加载…" else ""); return }
        if (uri != null) rememberSource(path, uri)
        val direct = try { fetchDirect(path) } catch (e: Throwable) { Log.w(TAG, "找贴图失败", e); null }
        setLoading(if (pendingLoads.get() > 0) "正在加载…" else "")
        if (direct != null) {
            missing = direct.second
            if (direct.first > 0) toast("已把同一个文件夹里的 ${direct.first} 个贴图文件一起导入" + if (missing.isNotEmpty()) "；还有 ${missing.size} 个原文件夹里也没有" else "")
            else if (missing.isNotEmpty()) toast("有 ${missing.size} 张贴图在原来的文件夹里也没找到：${describeMissing(missing)}")
            notifyLibraryChanged()
        }
        if (missing.isEmpty()) missingByFile.remove(path) else missingByFile[path] = missing.size
        markState()
        if (missing.isNotEmpty() && direct == null) {
            val src = sourceOf(path)
            _texturePrompt.value = TexturePrompt(path, file.name, missing,
                folderHint = try { SourceAccess.folderHint(context, src?.uri, src?.guesses ?: emptyList()) } catch (_: Throwable) { null },
                folderOnly = SourceAccess.canReadStorage(context))
        }
    }

    /** 场景里某个模型缺贴图：重新走一遍"找贴图"。 */
    fun findTextures(modelId: Int) = post {
        val path = scene.findById(modelId)?.data?.sourceFile?.path
        if (path == null) { toast("这个模型不是从文件加载的"); return@post }
        io.execute {
            checkTextures(path, null)
            if ((missingByFile[path] ?: 0) == 0) { reloadModelFile(path); toast("贴图都找到了") }
            else if (_texturePrompt.value == null) toast("还有 ${missingByFile[path]} 张贴图没找到")
        }
    }

    /** 用户刚给了存储权限：再试一次直接读原来的文件夹。 */
    fun retryTextures() {
        val p = _texturePrompt.value ?: return
        if (!SourceAccess.canReadStorage(context)) return
        io.execute {
            // 从设置页回来时这里可能被连着调两次：第一次已经处理完了就不用再来
            if (_texturePrompt.value?.path != p.path) return@execute
            val r = try { fetchDirect(p.path) } catch (e: Throwable) { null }
            setLoading(if (pendingLoads.get() > 0) "正在加载…" else "")
            if (r == null) {
                _texturePrompt.value = p.copy(folderOnly = true)
                toast("权限有了，但推不出这个文件原来放在哪。请点「选择模型所在的文件夹」")
            } else finishFetch(p, r)
        }
    }

    /** 用户用系统的文件夹选择器选了模型所在的文件夹。 */
    fun texturesFromFolder(tree: Uri) {
        val p = _texturePrompt.value ?: return
        io.execute {
            if (_texturePrompt.value?.path != p.path) return@execute
            setLoading("正在把贴图一起带进来…")
            val r = try {
                SourceAccess.fetchMissing(File(p.path), TreeSourceDir(context, tree, p.name, sourceOf(p.path)?.size ?: -1L)) { n -> if (n % 4 == 0) setLoading("正在把贴图一起带进来… $n") }
            } catch (e: Throwable) { Log.w(TAG, "从文件夹找贴图失败", e); 0 to p.missing }
            setLoading(if (pendingLoads.get() > 0) "正在加载…" else "")
            finishFetch(p, r)
        }
    }

    private fun finishFetch(p: TexturePrompt, r: Pair<Int, List<String>>) {
        val (found, still) = r
        if (still.isEmpty()) missingByFile.remove(p.path) else missingByFile[p.path] = still.size
        _texturePrompt.value = null
        notifyLibraryChanged()
        toast(when {
            found == 0 -> "这个文件夹里没有找到缺的贴图（${describeMissing(still)}）"
            still.isEmpty() -> "找到了全部 $found 个贴图文件"
            else -> "找到了 $found 个，还有 ${still.size} 个没找到：${describeMissing(still)}"
        })
        if (found > 0) reloadModelFile(p.path)
        markState()
    }

    fun dismissTexturePrompt() { _texturePrompt.value = null }

    /** 文件补齐之后，把场景里用这个文件的模型重新加载一遍（位置、动作、姿势都保留）。 */
    private fun reloadModelFile(path: String) {
        loader.execute {
            val file = File(path)
            TextureResolver.clearCache()
            val targets = java.util.concurrent.atomic.AtomicInteger(0)
            try {
                // 每个实例各用一份数据（调部件会改动数据）
                val first = ModelLoader.loadModel(file)
                missingByFile[path] = countMissing(first).also { if (it == 0) missingByFile.remove(path) }
                post {
                    var data: com.lumi.mmd.engine.model.ModelData? = first
                    for (old in scene.models.toList()) {
                        if (old.data.sourceFile?.path != path || session?.model === old) continue
                        val d = data ?: ModelLoader.loadModel(file)
                        data = null
                        val neu = old.replacedBy(d)
                        scene.replace(old, neu)
                        renderer.releaseModel(old)
                        if (editor.selectedModel === old) editor.select(neu)
                        neu.poseDirty = true
                        targets.incrementAndGet()
                    }
                    detailDirty = true; stateDirty = true
                    kick(4f)
                }
            } catch (e: Throwable) { Log.w(TAG, "重新加载 ${file.name} 失败", e) }
        }
    }

    // ------------------------------------------------------------------ 设置

    private val sp get() = context.getSharedPreferences("render", Context.MODE_PRIVATE)

    private fun loadPrefs() {
        val s = sp
        val d = RenderPrefs()
        _prefs.value = RenderPrefs(
            msaa = s.getInt("msaa", d.msaa), renderScale = s.getFloat("renderScale", d.renderScale),
            shadows = s.getBoolean("shadows", d.shadows), shadowSize = s.getInt("shadowSize", d.shadowSize),
            edges = s.getBoolean("edges", d.edges), edgeWidth = s.getFloat("edgeWidth", d.edgeWidth),
            grid = s.getBoolean("grid", d.grid), groundShadow = s.getBoolean("groundShadow", d.groundShadow),
            sdef = s.getBoolean("sdef", d.sdef), bloom = s.getBoolean("bloom", d.bloom), bloomStrength = s.getFloat("bloomStrength", d.bloomStrength),
            exposure = s.getFloat("exposure", d.exposure), saturation = s.getFloat("saturation", d.saturation), rim = s.getFloat("rim", d.rim),
            vignette = s.getFloat("vignette", d.vignette), maxTexture = s.getInt("maxTexture", d.maxTexture), physicsHz = s.getInt("physicsHz", d.physicsHz),
            background = s.getInt("background", d.background),
            lightX = s.getFloat("lightX", d.lightX), lightY = s.getFloat("lightY", d.lightY), lightZ = s.getFloat("lightZ", d.lightZ),
            lightIntensity = s.getFloat("lightIntensity", d.lightIntensity), fov = s.getFloat("fov", d.fov),
            showFps = s.getBoolean("showFps", d.showFps), stageLight = s.getBoolean("stageLight", d.stageLight),
            darkUi = s.getBoolean("darkUi", d.darkUi),
        )
    }

    fun updatePrefs(p: RenderPrefs) {
        val old = _prefs.value
        _prefs.value = p
        sp.edit()
            .putInt("msaa", p.msaa).putFloat("renderScale", p.renderScale).putBoolean("shadows", p.shadows).putInt("shadowSize", p.shadowSize)
            .putBoolean("edges", p.edges).putFloat("edgeWidth", p.edgeWidth).putBoolean("grid", p.grid).putBoolean("groundShadow", p.groundShadow)
            .putBoolean("sdef", p.sdef).putBoolean("bloom", p.bloom).putFloat("bloomStrength", p.bloomStrength).putFloat("exposure", p.exposure)
            .putFloat("saturation", p.saturation).putFloat("rim", p.rim).putFloat("vignette", p.vignette).putInt("maxTexture", p.maxTexture)
            .putInt("physicsHz", p.physicsHz).putInt("background", p.background)
            .putFloat("lightX", p.lightX).putFloat("lightY", p.lightY).putFloat("lightZ", p.lightZ).putFloat("lightIntensity", p.lightIntensity)
            .putFloat("fov", p.fov).putBoolean("showFps", p.showFps).putBoolean("stageLight", p.stageLight).putBoolean("darkUi", p.darkUi)
            .apply()
        post {
            applyPrefsGl(p)
            if (old.sdef != p.sdef) renderer.invalidateAllModels()
            val lightChanged = old.lightX != p.lightX || old.lightY != p.lightY || old.lightZ != p.lightZ || old.lightIntensity != p.lightIntensity
            if (lightChanged) applyLightGl(p)
            if (old.fov != p.fov && !scene.cameraDriven) { scene.camera.fovDeg = p.fov; scene.userCamera.fovDeg = p.fov }
        }
    }

    private fun applyLightGl(p: RenderPrefs) {
        scene.light.direction[0] = p.lightX; scene.light.direction[1] = p.lightY; scene.light.direction[2] = p.lightZ
        scene.light.color.fill(p.lightIntensity)
    }

    private fun applyPrefsGl(p: RenderPrefs) {
        val s = renderer.settings
        s.msaa = p.msaa; s.renderScale = p.renderScale; s.shadows = p.shadows; s.shadowMapSize = p.shadowSize
        // 网格、地面影子现在是地板自己的属性（跟着工程存）；设置里的这两项只当「新场景的默认值」
        s.edges = p.edges; s.edgeWidth = p.edgeWidth; s.sdef = p.sdef
        s.bloom = p.bloom; s.bloomStrength = p.bloomStrength; s.exposure = p.exposure; s.saturation = p.saturation
        s.rim = p.rim; s.vignette = p.vignette
        // 背景是脚本 / AI 指定的颜色时不要用预设盖掉它（在背景里重新挑一个预设才换回来）
        if (!s.customBackground) {
            val bg = p.background.coerceIn(0, Backgrounds.top.size - 1)
            System.arraycopy(Backgrounds.top[bg], 0, s.backgroundTop, 0, 3)
            System.arraycopy(Backgrounds.bottom[bg], 0, s.backgroundBottom, 0, 3)
        }
        renderer.textures.maxTextureSize = p.maxTexture
    }

    /** 用设置里的灯光覆盖当前灯光（比如导入舞台灯光之后想恢复）。 */
    fun resetLight() = post { applyLightGl(_prefs.value) }

    // ------------------------------------------------------------------ 截图

    /**
     * 截图保存到相册（Pictures/LumiMMD）。
     * @param scale 相对屏幕分辨率的倍率（1 或 2）
     */
    fun screenshot(scale: Int, transparent: Boolean) = post { shotScale = scale.coerceIn(1, 4); shotTransparent = transparent }

    /** 真正的截图在 drawFrame 里做（那时一定有可用的 GL 上下文）。 */
    private fun takeScreenshotGl(scale: Int, transparent: Boolean) {
        val w = (width * scale).coerceAtMost(8192)
        val h = (height * scale).coerceAtMost(8192)
        val tex = GlUtil.genTexture()
        val fbo = GlUtil.genFramebuffer()
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) { toast("截图失败：无法创建帧缓冲"); return }
            val s = renderer.settings
            val oldGrid = s.grid
            s.transparentBackground = transparent
            if (transparent) s.grid = false
            // 截图里不要选中高亮和辅助线
            renderer.highlightModel = null; renderer.highlightPart = null
            renderer.overlay.clear()
            renderer.render(scene, fbo, w, h)
            s.transparentBackground = false
            s.grid = oldGrid
            val buf = GlUtil.byteBuffer(w * h * 4)
            renderer.readPixels(fbo, w, h, buf)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            io.execute { saveScreenshot(buf, w, h, transparent) }
        } catch (e: OutOfMemoryError) {
            toast("截图失败：内存不足，换成 1x 再试")
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GlUtil.deleteFramebuffer(fbo)
            GlUtil.deleteTexture(tex)
        }
    }

    private fun saveScreenshot(buf: java.nio.ByteBuffer, w: Int, h: Int, transparent: Boolean) {
        try {
            // GL 读回来的行序是自下而上，翻转一下
            val row = ByteArray(w * 4)
            val flipped = java.nio.ByteBuffer.allocateDirect(w * h * 4)
            for (y in h - 1 downTo 0) {
                buf.position(y * w * 4); buf.get(row)
                if (!transparent) { var i = 3; while (i < row.size) { row[i] = -1; i += 4 } }
                flipped.put(row)
            }
            flipped.position(0)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(flipped)
            val name = "LumiMMD_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".png"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LumiMMD")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("无法写入相册")
                resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: throw java.io.IOException("无法写入相册")
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                toast("截图已保存到 相册/Pictures/LumiMMD/$name")
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "LumiMMD").also { it.mkdirs() }
                val f = File(dir, name)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                toast("截图已保存到 ${f.path}")
            }
            bmp.recycle()
        } catch (e: Throwable) {
            Log.e(TAG, "保存截图失败", e)
            toast("保存截图失败：${describe(e)}")
        }
    }

    // ------------------------------------------------------------------ 工程（自动保存 / 恢复）

    /** 工程数据。脚本运行时改的东西（位置、透明度、物理参数……）不算数：存的是脚本没动过时的样子。 */
    private fun projectJsonGl(): String {
        flushEditsGl()
        return runtime.scripts.withBaseState { projectJsonBaseGl() }
    }

    private fun projectJsonBaseGl(): String {
        val root = LinkedHashMap<String, Any?>()
        root["version"] = 2
        val saved = scene.models.filter { !it.transient }
        root["models"] = saved.map { SceneIO.saveModel(it, scene, saved) }
        root["cameraClips"] = scene.cameraClips.filter { it.motion.hasCamera }.map { SceneIO.saveClip(it) }
        play.saveGl(root, saved)
        root["camera"] = SceneIO.saveCamera(scene.userCamera)
        root["light"] = SceneIO.saveLight(scene.light)
        root["useCameraMotion"] = scene.useCameraMotion
        root["audioClips"] = scene.audioClips.map { SceneIO.saveAudioClip(it) }
        // 旧版本只认这一项（一整首从头放）
        root["audio"] = scene.audioClips.firstOrNull()?.path ?: ""
        root["frame"] = scene.frame
        root["loop"] = scene.loop
        root["physics"] = scene.physicsEnabled
        root["floor"] = scene.floor.toMap()
        renderer.settings.styleToMap().takeIf { it.isNotEmpty() }?.let { root["look"] = it }
        return Json.stringify(root)
    }

    private fun saveProjectGl() {
        projectDirty = false
        lastProjectSave = System.nanoTime()
        // 「只看它」临时藏起来的部件不算数：存盘时按原来的显示状态存
        val solo = soloSaved
        val soloTarget = if (solo != null) scene.findById(soloModel)?.takeIf { it.materials.size == solo.size } else null
        val during = soloTarget?.let { m -> BooleanArray(m.materials.size) { m.materials[it].visible } }
        if (soloTarget != null && solo != null) for ((i, st) in soloTarget.materials.withIndex()) st.visible = solo[i]
        val text = try { projectJsonGl() } catch (e: Throwable) { Log.w(TAG, "生成工程数据失败", e); return }
        finally { if (soloTarget != null && during != null) for ((i, st) in soloTarget.materials.withIndex()) st.visible = during[i] }
        io.execute { writeProject(text) }
    }

    private fun writeProject(text: String) {
        try {
            val tmp = File(projectFile.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(projectFile)) { projectFile.writeText(text); tmp.delete() }
        } catch (e: Exception) { Log.w(TAG, "保存工程失败", e) }
    }

    /** Activity 进入后台前调用：尽量把最新状态落盘（最多等一小会儿）。 */
    fun saveProjectNow() {
        val p = glPost ?: return
        val latch = CountDownLatch(1)
        var text: String? = null
        p(Runnable {
            try { if (!restoring) text = projectJsonGl() } catch (_: Throwable) {} finally { latch.countDown() }
        })
        glRequestRender?.invoke()
        if (latch.await(600, TimeUnit.MILLISECONDS)) text?.let { writeProject(it) }
    }

    /** 启动时恢复上次的场景。 */
    fun restoreProject() {
        if (!projectFile.isFile) return
        io.execute {
            val root = try { Json.parse(projectFile.readText()).jObj() } catch (e: Exception) { null } ?: return@execute
            val models = root.arr("models")?.mapNotNull { it.jObj() } ?: emptyList()
            // 地板和画风：空场景也要恢复（删掉的地板不能重启一次就自己回来）
            post {
                root.obj("floor")?.let { scene.floor.fromMap(it) } ?: defaultFloorGl()
                renderer.settings.styleFromMap(root.obj("look"))
                if (!renderer.settings.customBackground) applyPrefsGl(_prefs.value)
            }
            if (models.isEmpty() && (root.str("audio") ?: "").isEmpty()) return@execute
            restoring = true
            post {
                root.obj("camera")?.let { SceneIO.restoreCamera(scene.camera, it); scene.userCamera.copyFrom(scene.camera) }
                root.obj("light")?.let { SceneIO.restoreLight(scene.light, it) }
                scene.loop = root.bool("loop", true)
                scene.physicsEnabled = root.bool("physics", true)
                scene.useCameraMotion = root.bool("useCameraMotion", true)
            }
            // 依次加载模型（保持原来的顺序，挂载关系才能按序号恢复）
            val loaded = arrayOfNulls<ModelInstance>(models.size)
            var missing = 0
            for ((i, mj) in models.withIndex()) {
                val path = mj.str("file") ?: ""
                if (path.isEmpty() || !File(path).isFile) { missing++; continue }
                val latch = CountDownLatch(1)
                loadModel(path, mj.int("kind", 0), mj) { loaded[i] = it; latch.countDown() }
                latch.await(120, TimeUnit.SECONDS)
            }
            // 动作：每个模型轨道上的片段（旧版工程只有一段：motions + motionOffset）
            fun buildClip(m: ModelInstance?, o: Map<String, Any?>): com.lumi.mmd.engine.anim.MotionClip? {
                val sources = o.arr("sources")?.mapNotNull { it.jStr() } ?: return null
                val first = sources.firstOrNull() ?: return null
                val motion: Motion
                val ok = ArrayList<String>()
                if (first.startsWith("embedded:")) {
                    motion = m?.data?.animations?.getOrNull(first.substringAfter(':').toIntOrNull() ?: 0) ?: return null
                    ok.add(first)
                } else {
                    val combined = Motion()
                    for (s in sources) if (File(s).isFile) { if (ok.isEmpty()) combined.mergeFrom(loadMotionCached(s)) else combined.overlay(loadMotionCached(s)); ok.add(s) }
                    if (ok.isEmpty()) return null
                    // 镜头片段会被就地修改，用自己的一份；模型的动作改之前会先复制，可以直接用缓存里的
                    motion = if (m == null) loadMotionCached(ok[0]).copy() else if (ok.size == 1) loadMotionCached(ok[0]) else combined
                }
                val c = com.lumi.mmd.engine.anim.MotionClip(motion, o.str("name")?.ifBlank { null } ?: File(first).nameWithoutExtension)
                c.sources = ok
                c.start = o.int("start", 0).coerceAtLeast(0); c.trimIn = o.int("in", 0).coerceAtLeast(0); c.trimOut = o.int("out", -1)
                c.fadeIn = o.int("fade", 0).coerceIn(0, com.lumi.mmd.engine.scene.ClipOps.MAX_FADE)
                return c
            }
            for ((i, mj) in models.withIndex()) {
                val m = loaded[i] ?: continue
                val list = mj.arr("clips")?.mapNotNull { it.jObj() }
                    ?: mj.arr("motions")?.mapNotNull { it.jStr() }?.takeIf { it.isNotEmpty() }?.let { old ->
                        listOf(linkedMapOf<String, Any?>("sources" to old, "start" to Math.round(mj.float("motionOffset", 0f))))
                    } ?: emptyList()
                val clips = ArrayList<com.lumi.mmd.engine.anim.MotionClip>()
                for (o in list) try { buildClip(m, o)?.let { clips.add(it) } } catch (e: Throwable) { Log.w(TAG, "恢复动作失败", e) }
                if (clips.isNotEmpty()) post { m.clips.addAll(clips); m.sortClips(); m.poseDirty = true; scene.recomputeDuration() }
            }
            val camClips = ArrayList<com.lumi.mmd.engine.anim.MotionClip>()
            val camList = root.arr("cameraClips")?.mapNotNull { it.jObj() }
                ?: (root.str("cameraMotion") ?: "").takeIf { it.isNotEmpty() }?.let { listOf(linkedMapOf<String, Any?>("sources" to listOf(it))) } ?: emptyList()
            for (o in camList) try { buildClip(null, o)?.takeIf { it.motion.hasCamera }?.let { camClips.add(it) } } catch (e: Throwable) { Log.w(TAG, "恢复镜头失败", e) }
            val savedScripts = root.arr("scripts")?.mapNotNull { it.jObj() } ?: emptyList()
            val savedTuning = root["physicsTuning"].jObj()
            // 音乐轨道（旧版工程只有一个文件，从头放）
            val audioList = root.arr("audioClips")?.mapNotNull { it.jObj() }
                ?: (root.str("audio") ?: "").takeIf { it.isNotEmpty() }?.let { listOf(linkedMapOf<String, Any?>("file" to it)) } ?: emptyList()
            val audioClips = ArrayList<com.lumi.mmd.engine.scene.AudioClip>()
            val lengths = HashMap<String, Int>()
            for (o in audioList) {
                val path = o.str("file") ?: continue
                val f = File(path)
                if (!f.isFile) continue
                // 存工程时记过文件有多长就直接用；没记过（旧版工程）再去问解码器
                val frames = lengths.getOrPut(path) { o.int("frames", 0).takeIf { it > 0 } ?: probeAudio(f)?.let { Math.round(it * Motion.FPS) } ?: 0 }
                if (frames <= 0) continue
                val c = com.lumi.mmd.engine.scene.AudioClip(path, o.str("name")?.ifBlank { null } ?: f.name, frames)
                c.start = o.int("start", 0).coerceAtLeast(0)
                c.trimIn = o.int("in", 0).coerceIn(0, frames - 1)
                c.trimOut = o.int("out", -1).let { if (it < 0) -1 else it.coerceIn(c.trimIn + 1, frames) }
                audioClips.add(c)
            }
            post {
                for ((i, mj) in models.withIndex()) {
                    val m = loaded[i] ?: continue
                    val pi = mj.int("attachTo", -1)
                    val parent = loaded.getOrNull(pi)
                    if (parent != null && parent !== m) {
                        m.attachParent = parent
                        m.attachBone = parent.skeleton?.bones?.indexOfFirst { it.name == mj.str("attachBone") } ?: -1
                    }
                }
                scene.cameraClips.addAll(camClips); scene.sortCameraClips()
                for (c in camClips) cameraHashes[c.id] = cameraHash(c)
                play.restoreGl(savedScripts, savedTuning, loaded)
                scene.audioClips.clear()
                for (c in audioClips.sortedBy { it.start }) com.lumi.mmd.engine.scene.ClipOps.insert(scene.audioClips, c, c.start)
                scene.recomputeDuration()
                scene.seek(root.float("frame", 0f))
                prepareAudioSoon()
                editor.select(null)
                restoring = false
                projectDirty = false
                if (missing > 0) toast("上次的场景里有 $missing 个模型文件已不存在，已跳过")
            }
        }
    }

    /** 清空场景。 */
    fun newScene() = post {
        pauseGl()
        session?.let { it.dirty = false; endMeshGl() }
        clip = null
        cameraHashes.clear()
        savedMotionRevision.clear()
        // onModelRemoved 顺带清掉撤销记录：场景都清空了，之前的操作没有可撤销的对象了
        for (m in scene.models.toList()) { scene.remove(m); editor.onModelRemoved(m); renderer.releaseModel(m) }
        editor.select(null)
        scene.cameraMotion = null; cameraMotionFile = ""
        scene.audioClips.clear()
        deck.release()
        play.resetGl()
        scene.cameraClips.clear(); scene.focusCameraClip(null)
        scene.extraDuration = 0
        scene.recomputeDuration()
        scene.seek(0f)
        scene.camera.reset(); scene.camera.fovDeg = _prefs.value.fov; scene.userCamera.copyFrom(scene.camera)
        applyLightGl(_prefs.value)
        defaultFloorGl()
        renderer.settings.resetStyle(); renderer.settings.customBackground = false
        applyPrefsGl(_prefs.value)
        detailDirty = true
        projectDirty = true
    }

    /** 一块默认的地板；网格和影子按「设置」里记着的习惯来。 */
    private fun defaultFloorGl() {
        scene.floor.reset()
        scene.floor.grid = _prefs.value.grid
        scene.floor.shadow = _prefs.value.groundShadow
    }

    /** 系统内存紧张时：丢掉可以重新读出来的缓存。 */
    fun trimMemory() {
        synchronized(motionCache) { motionCache.clear() }
    }

    fun release() {
        try { mcpServer?.stop() } catch (_: Throwable) {}
        McpService.onStopRequested = null
        McpService.onStateChanged = null
        McpService.stop(context)
        mainHandler.removeCallbacks(prepareAudio)
        deck.release()
        audioIo.shutdown()
        renderer.textures.shutdown()
        loader.shutdown()
        io.shutdown()
    }


    // ================================================================== 第二版新增：关键帧动画 / 建模 / MCP

    private val _timeline = MutableStateFlow(TimelineUi())
    /** 时间轴数据（界面打开时间轴面板时才生成）。 */
    val timeline: StateFlow<TimelineUi> = _timeline
    private val _mesh = MutableStateFlow(MeshUi())
    val mesh: StateFlow<MeshUi> = _mesh
    private val _mcp = MutableStateFlow(McpUi())
    val mcp: StateFlow<McpUi> = _mcp
    /** 框选时的矩形（视图像素：x0,y0,x1,y1），没有在框选时为 null。 */
    val boxRect = MutableStateFlow<FloatArray?>(null)

    @Volatile private var glThread: Thread? = null
    /** Activity 是否在前台（后台时没有可用的 GL 表面，不能截图）。 */
    @Volatile var resumed = true
    private var uiMode = Modes.VIEW
    private var timelineWanted = false
    private var timelineKey = 0L
    private var timelineVersion = 0
    private var autoKey = false
    private var clip: KeyOps.Clip? = null
    private var session: MeshSession? = null
    private var meshShown = -2
    private var boxSelect = false
    private var boxing = false
    private var boxX = 0f
    private var boxY = 0f
    private val savedMotionRevision = HashMap<Int, Int>()
    /** 镜头片段编号 → 上次存盘时关键帧的摘要。 */
    private val cameraHashes = HashMap<Int, Int>()
    private var cameraSavedHash: Int
        get() = scene.activeCameraClip?.let { cameraHashes[it.id] } ?: 0
        set(v) { scene.activeCameraClip?.let { if (v == 0) cameraHashes.remove(it.id) else cameraHashes[it.id] = v } }
    private var mcpServer: McpServer? = null

    private fun curFrame(): Int = Math.round(scene.frame)

    /** 做关键帧操作之前：停止播放并对齐到整数帧。 */
    private fun settleFrameGl(): Int {
        if (scene.playing) pauseGl()
        val f = curFrame()
        if (scene.frame != f.toFloat()) scene.seek(f.toFloat(), true)
        return f
    }

    private fun keyModel(): ModelInstance? = editor.selectedModel?.takeIf { it.skeleton != null || it.morphs.count > 0 }
        ?: scene.models.firstOrNull { it.kind == ModelInstance.KIND_CHARACTER && it.skeleton != null }

    fun wantTimeline(want: Boolean) = post { timelineWanted = want; timelineKey = 0L }
    fun setAutoKey(on: Boolean) = post { autoKey = on }
    fun setLocalAxes(on: Boolean) = post { editor.localAxes = on }

    /** 跳到某一帧（整数帧；允许超过当前动作的结尾，方便往后接着做）。 */
    fun seekFrame(frame: Int) = post {
        scene.seek(frame.coerceAtLeast(0).toFloat(), true)
        if (scene.playing) audioPlayGl() else prepareAudioSoon()
    }

    fun stepFrame(delta: Int) = post { if (scene.playing) pauseGl(); scene.seek((curFrame() + delta).coerceAtLeast(0).toFloat(), true); prepareAudioSoon() }

    /** 跳到上一个 / 下一个有关键帧的帧。 */
    fun jumpKey(forward: Boolean) = post {
        if (scene.playing) pauseGl()
        val f = KeyOps.neighbourKey(keyModel(), scene, curFrame(), forward)
        if (f >= 0) scene.seek(f.toFloat(), true)
        else if (!forward) scene.seek(0f, true)
        else toast("后面没有关键帧了")
    }

    /** 登记关键帧（◆）。按当前模式：镜头模式登记镜头；表情模式登记调过的表情；其它模式登记摆过的骨骼和调过的表情。 */
    fun registerKey() = post {
        val f = settleFrameGl()
        if (uiMode == Modes.CAMERA) {
            cameraUndoGl()
            KeyOps.registerCamera(scene, f)
            toast("已登记镜头关键帧 · 第 $f 帧")
            projectDirty = true
            return@post
        }
        val m = keyModel()
        if (m == null) { toast("先加载并选中一个带骨骼的模型"); return@post }
        keyUndoGl(m)
        val nm = KeyOps.registerPosedMorphs(m, scene, f)
        val nb = if (uiMode == Modes.MORPH) 0 else KeyOps.registerPosed(m, scene, f, if (uiMode == Modes.BONE && editor.selectedModel === m) editor.selectedBone else -1)
        if (nb + nm == 0) { editor.discardLastUndo(); toast(if (uiMode == Modes.MORPH) "先拖动表情滑杆，再登记" else "先选中一根骨骼摆个姿势，再登记") }
        else toast("第 $f 帧：登记了" + (if (nb > 0) " $nb 根骨骼" else "") + (if (nm > 0) " $nm 个表情" else ""))
        m.poseDirty = true
        detailDirty = true; projectDirty = true
    }

    /** 登记某一个表情的关键帧（表情面板里每行的 ◆）。 */
    fun registerMorph(index: Int) = post {
        val f = settleFrameGl()
        val m = keyModel() ?: return@post
        keyUndoGl(m)
        if (KeyOps.registerMorph(m, scene, index, f)) { detailDirty = true; projectDirty = true } else editor.discardLastUndo()
    }

    /** 删除当前帧的关键帧：镜头模式删镜头帧；骨骼模式删选中骨骼的；all=true 删这一帧上所有轨道的。 */
    fun deleteKey(all: Boolean = false) = post {
        val f = settleFrameGl()
        if (uiMode == Modes.CAMERA) {
            cameraUndoGl()
            val ok = KeyOps.deleteCameraKey(scene, f)
            if (!ok) editor.discardLastUndo()
            toast(if (ok) "已删除第 $f 帧的镜头关键帧" else "第 $f 帧没有镜头关键帧")
            projectDirty = true
            return@post
        }
        val m = keyModel() ?: return@post
        keyUndoGl(m)
        val bone = if (uiMode == Modes.BONE && editor.selectedModel === m) editor.selectedBone else -1
        if (!all && bone >= 0) {
            val name = m.skeleton?.bones?.getOrNull(bone)?.name ?: ""
            val ok = KeyOps.deleteBoneKey(m, scene, bone, f)
            if (!ok) editor.discardLastUndo()
            toast(if (ok) "已删除「$name」在第 $f 帧的关键帧" else "「$name」在第 $f 帧没有关键帧（长按可删除这一帧的全部关键帧）")
        } else {
            val n = KeyOps.deleteFrame(m, scene, f)
            if (n == 0) editor.discardLastUndo()
            toast(if (n > 0) "已删除第 $f 帧的 $n 个关键帧" else "第 $f 帧没有关键帧")
        }
        detailDirty = true; projectDirty = true
    }

    /** 复制当前帧：有关键帧就复制关键帧，没有就复制当前姿势。 */
    fun copyKeys() = post {
        val f = settleFrameGl()
        val m = keyModel() ?: return@post
        var c = KeyOps.copy(m, f)
        if (c.isEmpty) {
            val set = java.util.TreeSet<Int>()
            for (r in KeyOps.rows(m)) if (r.kind == KeyOps.ROW_BONE) set.add(r.index)
            for (b in KeyOps.posedBones(m)) set.add(b)
            c = KeyOps.copy(m, f, set.toIntArray(), pose = true)
        }
        if (c.isEmpty) toast("这一帧没有可复制的关键帧或姿势") else { clip = c; toast("已复制 ${c.size} 项（第 $f 帧）") }
    }

    fun pasteKeys(mirror: Boolean = false) = post {
        val f = settleFrameGl()
        val m = keyModel() ?: return@post
        val c = clip
        if (c == null) { toast("剪贴板是空的，先复制"); return@post }
        keyUndoGl(m)
        val n = KeyOps.paste(m, scene, c, f, mirror)
        if (n == 0) editor.discardLastUndo()
        toast(if (n > 0) "已粘贴 $n 项到第 $f 帧" + (if (mirror) "（左右反转）" else "") else "这个模型上没有对得上的骨骼")
        detailDirty = true; projectDirty = true
    }

    /** 设置"到达当前帧"这一段的补间曲线：0 线性 1 缓入 2 缓出 3 缓入缓出。 */
    fun setCurve(preset: Int) = post {
        val f = settleFrameGl()
        val m = keyModel() ?: return@post
        val bone = editor.selectedBone
        val c = when (preset) { 1 -> intArrayOf(64, 0, 107, 107); 2 -> intArrayOf(20, 20, 64, 127); 3 -> intArrayOf(64, 0, 64, 127); else -> intArrayOf(20, 20, 107, 107) }
        if (bone >= 0 && KeyOps.hasBoneKey(m, bone, f)) keyUndoGl(m)
        if (bone < 0 || !KeyOps.setBoneCurve(m, bone, f, -1, c[0], c[1], c[2], c[3])) toast("补间曲线设在关键帧上：先选中骨骼，并跳到它的某个关键帧")
        else { toast("已设置补间：" + arrayOf("线性", "缓入", "缓出", "缓入缓出")[preset.coerceIn(0, 3)]); projectDirty = true }
    }

    /** 从当前帧开始把后面的关键帧整体后移（正）/ 前移（负）。 */
    fun shiftKeys(delta: Int) = post {
        val f = settleFrameGl()
        val m = keyModel() ?: return@post
        keyUndoGl(m)
        val n = KeyOps.shiftKeys(m, scene, f, delta)
        if (n == 0) editor.discardLastUndo()
        toast(if (n > 0) "已把第 $f 帧之后的 $n 个关键帧" + (if (delta > 0) "后移 $delta 帧" else "前移 ${-delta} 帧") else "后面没有关键帧")
        projectDirty = true
    }

    /** 清空选中模型的全部关键帧。 */
    fun clearKeys() = post {
        val m = keyModel() ?: return@post
        keyUndoGl(m)
        KeyOps.clearAll(m, scene)
        toast("已清空 ${m.name} 的动作")
        detailDirty = true; projectDirty = true
    }

    /** 把动作导出成 VMD 存进资源库（「自制动作」）。camera=true 导出镜头。 */
    fun exportVmd(camera: Boolean) = post {
        try {
            if (camera) {
                // 镜头轨道上有好几段时，按它们在时间轴上的摆放合成一份
                val lane = scene.cameraClips.filter { it.motion.hasCamera }
                if (lane.isEmpty()) { toast("还没有镜头关键帧"); return@post }
                val cm = com.lumi.mmd.engine.scene.ClipOps.bake(lane)
                toast("已导出：资源库/" + saveMotion("镜头_" + stamp(), VmdWriter.write(cm, KeyOps.CAMERA_MODEL_NAME)))
            } else {
                val m = keyModel()
                val mo = m?.takeIf { it.clips.isNotEmpty() }?.let { com.lumi.mmd.engine.scene.ClipOps.bake(it.clips) }
                if (m == null || mo == null || mo.keyCount == 0) { toast("选中的模型还没有关键帧"); return@post }
                toast("已导出：资源库/" + saveMotion(m.name + "_" + stamp(), VmdWriter.write(mo, m.name)))
            }
        } catch (e: Throwable) { toast("导出失败：${describe(e)}") }
    }

    private fun stamp(): String = java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
    private fun sanitizeName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifBlank { "未命名" }.take(60)

    // ---- 关键帧的撤销 ----

    private var dragUndoSerial = -1
    private var morphUndoKey = 0L
    private var morphUndoSerial = -1

    /**
     * 改模型的关键帧之前调用：把当前的整份动作记下来，按「撤销」时原样放回去。
     * amend=true 时并进上一步撤销里（拖骨骼之后自动登记的关键帧，和那次拖动算同一步）。
     * 导入的长动作一份有几 MB，[Editor] 会按总大小限制能撤销的步数。
     */
    private fun keyUndoGl(m: ModelInstance, amend: Boolean = false) {
        val id = m.id
        m.selectClip(scene.frame)
        // 记下所有片段的摆放，以及当前片段（要改的那一段）的整份动作
        val before = m.snapshotClips(copyActive = true)
        // 登记关键帧会把"还没登记的手动姿势"收进关键帧里，所以手动姿势也要一起记下来
        val userT = m.skeleton?.userT?.copyOf(); val userR = m.skeleton?.userR?.copyOf()
        val userW = m.morphs.userWeights.copyOf()
        val bytes = (m.activeClip?.takeIf { it.owned }?.motion?.keyCount ?: 0) * 100 + (userT?.size ?: 0) * 8 + userW.size * 4 + 512
        val action: () -> Unit = {
            scene.findById(id)?.let { t ->
                t.restoreClips(before)
                val sk = t.skeleton
                if (sk != null && userT != null && userR != null && sk.userT.size == userT.size && sk.userR.size == userR.size) {
                    System.arraycopy(userT, 0, sk.userT, 0, userT.size); System.arraycopy(userR, 0, sk.userR, 0, userR.size)
                }
                if (t.morphs.userWeights.size == userW.size) System.arraycopy(userW, 0, t.morphs.userWeights, 0, userW.size)
                t.poseDirty = true
                scene.recomputeDuration()
            }
        }
        if (amend) editor.amendUndo(bytes, action) else editor.pushUndo(bytes, action)
    }

    /** 镜头轨道此刻的样子：每个片段的摆放，copy = 当前片段的动作也复制一份（要改它的关键帧）。 */
    private fun cameraSnapshotGl(copy: Boolean): () -> Unit {
        scene.selectCameraClip(scene.frame)
        val active = scene.activeCameraClip
        class S(val clip: com.lumi.mmd.engine.anim.MotionClip, val motion: Motion, val start: Int, val trimIn: Int, val trimOut: Int, val sources: List<String>, val owned: Boolean, val fadeIn: Int)
        val list = scene.cameraClips.map { c -> S(c, if (copy && c === active) c.motion.copy() else c.motion, c.start, c.trimIn, c.trimOut, c.sources, c.owned, c.fadeIn) }
        val use = scene.useCameraMotion
        return {
            scene.cameraClips.clear()
            for (x in list) {
                if (x.clip.motion !== x.motion) x.clip.motion = x.motion
                x.clip.start = x.start; x.clip.trimIn = x.trimIn; x.clip.trimOut = x.trimOut; x.clip.sources = x.sources; x.clip.owned = x.owned; x.clip.fadeIn = x.fadeIn
                scene.cameraClips.add(x.clip)
            }
            scene.sortCameraClips()
            scene.useCameraMotion = use
            cameraHashes.clear()
            scene.releaseCamera()
            scene.recomputeDuration()
        }
    }

    /** 改镜头关键帧之前调用。 */
    private fun cameraUndoGl() {
        val bytes = (scene.cameraMotion?.keyCount ?: 0) * 64 + 512
        editor.pushUndo(bytes, cameraSnapshotGl(copy = true))
    }

    /** 改片段的摆放（移动、裁剪、分割、增删）之前调用：记一步撤销。m = null 表示镜头轨道。 */
    internal fun pushClipUndoGl(m: ModelInstance?) {
        if (m == null) { editor.pushUndo(512, cameraSnapshotGl(copy = false)); return }
        val id = m.id
        val snap = m.snapshotClips(copyActive = false)
        editor.pushUndo(512) {
            scene.findById(id)?.let { t -> t.restoreClips(snap); scene.recomputeDuration() }
        }
    }

    override fun beforeClipEdit(model: ModelInstance?) = pushClipUndoGl(model)

    /** 在加载线程上读一个 VMD，读完到渲染线程上交给 block。 */
    internal fun loadMotionAsync(path: String, block: (Motion) -> Unit) {
        val file = File(path)
        beginLoad("正在读取 ${file.name}…")
        loader.execute {
            try {
                val motion = loadMotionCached(path)
                post { block(motion) }
            } catch (e: Throwable) { toast("${file.name} 读取失败：${describe(e)}") } finally { endLoad() }
        }
    }

    /** 渲染线程：要进操控模式了——先退出编辑。 */
    internal fun leaveEditForControlGl() {
        if (session != null) endMeshGl()
        editor.mode = Editor.MODE_VIEW
        uiMode = Modes.VIEW
        detailDirty = true
    }

    override fun beforeKeyEdit(model: ModelInstance?) { if (model != null) keyUndoGl(model) else cameraUndoGl() }

    // ---- 拖动块（画面角落的 XYZ 块）----

    fun padStart() = post { dragUndoSerial = editor.undoSerial; if (!editor.beginPad()) toast("先选中要操作的对象") }
    fun padDrag(tool: Int, axis: Int, pixels: Float) = post {
        editor.padDrag(tool, axis, pixels, width, height)
        detailDirty = true
    }
    fun padEnd() = post {
        val wasBone = editor.mode == Editor.MODE_BONE
        editor.endDrag()
        if (wasBone) autoKeyBoneGl()
        projectDirty = true
    }

    private fun autoKeyBoneGl() {
        if (!autoKey || scene.playing) return
        val m = editor.selectedModel ?: return
        val b = editor.selectedBone
        if (b < 0) return
        keyUndoGl(m, amend = editor.undoSerial != dragUndoSerial)
        KeyOps.registerBone(m, scene, b, settleFrameGl())
    }

    // ---- 建模 ----

    private fun beginMeshGl(m: ModelInstance): MeshSession {
        session?.let { if (it.model === m) return it; endMeshGl() }
        pauseGl()
        endSoloGl()
        val s = MeshSession(scene, m)
        s.density = editor.density
        s.onReplaced = { old, neu -> renderer.releaseModel(old); if (editor.selectedModel === old || editor.selectedModel == null) editor.select(neu) }
        // 会话开始时模型实例已经换过一次了
        renderer.releaseModel(m)
        editor.select(s.model)
        editor.mode = Editor.MODE_MESH
        editor.meshTarget = s
        if (editor.tool == Editor.TOOL_SCALE) editor.tool = Editor.TOOL_MOVE
        uiMode = Modes.MESH
        session = s
        meshShown = -2
        return s
    }

    private fun endMeshGl() {
        val s = session ?: return
        s.finish()
        session = null
        editor.meshTarget = null
        boxing = false; boxRect.value = null
        if (editor.mode == Editor.MODE_MESH) editor.mode = Editor.MODE_MODEL
        if (s.dirty) {
            try {
                val path = saveModelGl(s.model, null)
                toast("模型已保存：资源库/$path")
            } catch (e: Throwable) { Log.w(TAG, "保存模型失败", e); toast("保存模型失败：${describe(e)}") }
            s.dirty = false
        }
        meshShown = -2
        detailDirty = true; projectDirty = true
    }

    /**
     * 把模型写成 PMX 放进资源库并让场景引用它。
     * 应用自己生成的文件（「自建模型」里的、或带 _edit 后缀的）直接覆盖；别处来的模型存成同目录下的 "原名_edit.pmx"，不动原文件。
     */
    private fun saveModelGl(m: ModelInstance, name: String?): String {
        val src = m.data.sourceFile
        val ownDir = File(library.root, "自建模型")
        val file: File = when {
            name != null -> File(src?.parentFile?.takeIf { it.absolutePath.startsWith(library.root.absolutePath) } ?: ownDir, sanitizeName(name) + ".pmx")
            src != null && (src.name.endsWith("_edit.pmx") || src.parentFile == ownDir) && src.name.endsWith(".pmx") -> src
            src != null && src.absolutePath.startsWith(library.root.absolutePath) -> File(src.parentFile, src.nameWithoutExtension + "_edit.pmx")
            else -> {
                var f = File(ownDir, sanitizeName(m.name) + ".pmx")
                var i = 2
                while (f.exists()) f = File(ownDir, sanitizeName(m.name) + "_" + (i++) + ".pmx")
                f
            }
        }
        file.parentFile?.mkdirs()
        m.data.name = m.name
        PmxWriter.save(m.data, file)
        m.data.sourceFile = file
        if (m.data.baseDir == null) m.data.baseDir = file.parentFile
        session?.let { if (it.model === m) it.markSaved(file) }
        notifyLibraryChanged()
        projectDirty = true
        return file.relativeTo(library.root).path
    }

    /** 把部件调整合并进模型数据，场景里换成合并后的实例。 */
    private fun mergePartsGl(m: ModelInstance): ModelInstance {
        endSoloGl()
        val wasSelected = editor.selectedModel === m
        val neu = PartOps.merge(scene, m)
        if (neu !== m) {
            renderer.releaseModel(m)
            if (wasSelected || editor.selectedModel == null) editor.select(neu)
            detailDirty = true; projectDirty = true
        }
        return neu
    }

    /** 把选中的模型另存为 PMX。 */
    fun saveModelAs(name: String) = post {
        var m = session?.model ?: editor.selectedModel
        if (m == null) { toast("先选中一个模型"); return@post }
        try {
            // 「部件」里挪过、删掉的东西先写进模型数据：存出去的文件和画面上看到的一样
            if (session == null && PartOps.hasEdits(m)) m = mergePartsGl(m)
            toast("已保存：资源库/" + saveModelGl(m, name.ifBlank { m.name })); session?.dirty = false
        } catch (e: Throwable) { toast("保存失败：${describe(e)}") }
    }

    /** 新建一个模型并进入建模：shape = box / sphere / cylinder / cone / torus / plane / empty。 */
    fun newModel(shape: String) = post {
        var n = 1
        while (scene.models.any { it.name == "新模型$n" }) n++
        val em = EditMesh.createEmpty("新模型$n")
        val m = ModelInstance(em.build().data, ModelInstance.KIND_ACCESSORY)
        m.name = "新模型$n"
        addModelGl(m, null)
        val s = beginMeshGl(m)
        if (shape != "empty") addShapeGl(s, shape, floatArrayOf(0f, 5f, 0f), 6f)
        // 第一个模型：把镜头对准它
        s.model.updateWorldMatrix()
        if (!scene.cameraDriven && s.model.exactWorldBounds(bMin, bMax)) {
            scene.camera.update(width.toFloat() / height)
            scene.camera.frame(bMin, bMax)
            scene.camera.rotX = -0.3f; scene.camera.rotY = -0.5f
            scene.camera.distance *= 1.5f
            scene.userCamera.copyFrom(scene.camera)
        }
        s.clearSelection()
        toast("已新建「${m.name}」。点画面里的面 / 点 / 线来选择，再用右边的工具编辑")
        detailDirty = true
    }

    private fun addShapeGl(s: MeshSession, shape: String, center: FloatArray, size: Float): Int {
        val xf = MeshSession.placement(center[0], center[1], center[2])
        val h = size * 0.5f
        return when (shape) {
            "sphere" -> s.addSphere(h, 24, 12, xf)
            "cylinder" -> s.addCylinder(h * 0.7f, h * 0.7f, size, 24, xf)
            "cone" -> s.addCylinder(h * 0.8f, 0f, size, 24, xf)
            "torus" -> s.addTorus(h * 0.8f, h * 0.25f, 32, 12, xf)
            "plane" -> s.addPlane(size, size, 4, 4, xf)
            else -> s.addBox(size, size, size, xf)
        }
    }

    /** 往正在编辑的模型里加一个基础形体：放在镜头注视点附近，大小随镜头远近。 */
    fun meshAddShape(shape: String) = post {
        val s = session ?: return@post
        val m = s.model
        val inv = FloatArray(16)
        if (!com.lumi.mmd.engine.math.Mat4.invertAffine(inv, 0, m.worldMatrix, 0)) com.lumi.mmd.engine.math.Mat4.identity(inv, 0)
        val c = FloatArray(3)
        val t = scene.camera.target
        com.lumi.mmd.engine.math.Mat4.transformPoint(inv, 0, t[0], t[1], t[2], c, 0)
        val size = (scene.camera.distance * 0.16f / maxOf(m.scale, 1e-4f)).coerceIn(0.01f, 1000f)
        val n = addShapeGl(s, shape, c, size)
        editor.tool = Editor.TOOL_MOVE
        toast("已添加（$n 个面，已选中）。拖箭头摆到位，换「缩放」调大小")
        projectDirty = true
    }

    fun setMeshSelMode(mode: Int) = post { session?.selMode = mode }
    fun setMeshAdditive(on: Boolean) = post { session?.additive = on; meshShown = -2 }
    fun setBoxSelect(on: Boolean) = post { boxSelect = on; meshShown = -2 }

    /** 建模操作。op 的取值见函数体；value 是该操作的参数（距离 / 比例等，0 = 用默认值）。 */
    fun meshOp(op: String, value: Float = 0f) = post {
        val s = session ?: return@post
        val e = s.typicalEdge()
        val msg: String = when (op) {
            "all" -> { s.selectAll(); "" }
            "none" -> { s.clearSelection(); "" }
            "invert" -> { s.invertSelection(); "" }
            "linked" -> { s.selectLinked(); "" }
            "grow" -> { s.growSelection(); "" }
            "extrude" -> {
                val n = s.extrude(if (value != 0f) value else e * 0.5f)
                if (n > 0) { editor.tool = Editor.TOOL_MOVE; editor.localAxes = true; "挤出了 $n 个面。拖绿色箭头（法线方向）继续拉" } else "先在「面」模式下选中要挤出的面"
            }
            "inset" -> { val n = s.inset(if (value != 0f) value else 0.2f); if (n > 0) "内插了 $n 个面" else "先选中面" }
            "subdivide" -> { val n = s.subdivide(); if (n > 0) "细分成了 $n 个面" else "先选中面" }
            "triangulate" -> { val n = s.triangulate(); if (n > 0) "已三角化" else "先选中面" }
            "loopcut" -> { val n = s.loopCut(if (value != 0f) value else 0.5f); if (n > 0) "环切：加了一圈 $n 个点（已选中，可以拖动）" else "环切要先在「线」模式下选中一条四边形上的边" }
            "split" -> { val n = s.splitOrConnect(); if (n > 0) "完成" else "「线」模式选中边 = 在中点加点；「点」模式选中同一个面上的两个点 = 连一条线" }
            "delete" -> { val n = s.deleteSelected(); if (n > 0) "已删除" else "没有选中任何东西" }
            "dissolve" -> { val n = s.dissolveEdges(); if (n > 0) "溶解了 $n 条边" else "先在「线」模式下选中边" }
            "duplicate" -> { val n = s.duplicate(); if (n > 0) { editor.tool = Editor.TOOL_MOVE; "复制了 $n 个面（已选中，拖箭头把它移开）" } else "先选中面" }
            "flip" -> { val n = s.flipNormals(); if (n > 0) "已翻转 $n 个面的朝向" else "先选中面" }
            "merge" -> { val n = s.mergeToCenter(); if (n > 0) "已把 $n 个点合并到中心" else "至少选中两个点" }
            "weld" -> { val n = s.weld(if (value != 0f) value else e * 0.02f); if (n > 0) "焊接掉了 $n 个重合的点" else "没有足够近的点" }
            "fill" -> if (s.fill()) "已补面" else "至少选中三个点"
            "mirrorX" -> { val n = s.mirror(0); if (n > 0) "已左右镜像 $n 个面" else "没有可镜像的面" }
            "mirrorY" -> { val n = s.mirror(1); if (n > 0) "已上下镜像" else "没有可镜像的面" }
            "mirrorZ" -> { val n = s.mirror(2); if (n > 0) "已前后镜像" else "没有可镜像的面" }
            "solidify" -> { val n = s.solidify(if (value != 0f) value else e * 0.3f); if (n > 0) "已加厚" else "没有可加厚的面" }
            "smooth" -> { val n = s.smooth(0.5f, 2); if (n > 0) "已平滑 $n 个点" else "先选中要平滑的部分" }
            "shadeSmooth" -> { val n = s.setSmoothShading(true); if (n > 0) "已设为平滑着色" else "先选中面" }
            "shadeFlat" -> { val n = s.setSmoothShading(false); if (n > 0) "已设为平直着色" else "先选中面" }
            "uv" -> { val n = s.projectUV(); if (n > 0) "已重新投影贴图坐标" else "先选中面" }
            "undo" -> if (s.undo()) "" else "没有可撤销的操作"
            "redo" -> if (s.redo()) "" else "没有可重做的操作"
            else -> ""
        }
        if (msg.isNotEmpty()) toast(msg)
        projectDirty = true
    }

    fun meshAddMaterial(argb: Int) = post {
        val s = session ?: return@post
        val i = s.addMaterial("", ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f, ((argb ushr 24) and 0xFF) / 255f)
        val n = s.assignMaterial(i)
        toast(if (n > 0) "新材质已用到选中的 $n 个面上" else "已新建材质（选中面后点它即可使用）")
    }

    fun meshSetMaterialColor(index: Int, argb: Int) = post {
        session?.setMaterialColor(index, ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f, ((argb ushr 24) and 0xFF) / 255f)
    }

    fun meshAssignMaterial(index: Int) = post {
        val s = session ?: return@post
        val n = s.assignMaterial(index)
        if (n == 0) { s.selectMaterial(index); toast("已选中这个材质的所有面（先选中面再点材质 = 给面换材质）") }
        else toast("已把 $n 个面换成这个材质")
    }

    fun meshBindBone(name: String) = post {
        val s = session ?: return@post
        val b = s.mesh.bones.indexOfFirst { it.name == name }
        val n = s.bindToBone(b)
        toast(if (n > 0) "选中的 $n 个点现在跟着「$name」动" else "先选中要绑定的部分")
    }

    fun redo() = post { if (session?.redo() != true) toast("没有可重做的操作") }

    private fun buildMeshUi(): MeshUi {
        val s = session ?: return MeshUi()
        val st = s.stats()
        val m = s.mesh
        val count = IntArray(m.materials.size)
        for (f in 0 until m.faceCount) if (!m.fDead[f] && m.fMat[f] in count.indices) count[m.fMat[f]]++
        val mats = m.materials.mapIndexed { i, md ->
            val c = (((md.diffuse[3] * 255f).toInt().coerceIn(0, 255)) shl 24) or (((md.diffuse[0] * 255f).toInt().coerceIn(0, 255)) shl 16) or
                (((md.diffuse[1] * 255f).toInt().coerceIn(0, 255)) shl 8) or ((md.diffuse[2] * 255f).toInt().coerceIn(0, 255))
            MeshMaterialUi(i, md.name.ifBlank { "材质${i + 1}" }, c, count[i])
        }
        return MeshUi(true, s.model.id, m.selMode, s.additive, boxSelect, st.points, st.faces, st.selPoints, st.selEdges, st.selFaces,
            s.canUndo, s.canRedo, s.dirty, mats, m.bones.filter { it.isVisible && it.isOperable }.map { it.name }.take(400))
    }

    // ---- 把编辑过的动作 / 模型落盘（工程里只记文件路径）----

    private fun cameraHash(): Int = scene.activeCameraClip?.let { cameraHash(it) } ?: 0

    private fun cameraHash(c: com.lumi.mmd.engine.anim.MotionClip): Int {
        val t = c.motion.camera ?: return 0
        var h = t.count
        for (i in 0 until t.count) h = h * 31 + t.frames[i]
        for (i in 0 until t.count * 8) h = h * 31 + java.lang.Float.floatToRawIntBits(t.values[i])
        return if (h == 0) 1 else h
    }

    private fun flushEditsGl() {
        val dir = File(library.root, "自制动作")
        fun own(f: File?) = f != null && f.parentFile == dir && f.name.startsWith("工程_")
        for (m in scene.models) {
            if (m.transient || m.clips.none { it.owned }) continue
            if (savedMotionRevision[m.id] == m.motionRevision && m.clips.all { c -> !c.owned || c.sources.singleOrNull()?.let { File(it).isFile } == true }) continue
            // 这个模型里改过的片段各存一个文件（两段不能共用一个文件名）
            val used = HashSet<String>()
            for ((i, c) in m.clips.withIndex()) {
                if (!c.owned) continue
                try {
                    dir.mkdirs()
                    val cur = c.sources.singleOrNull()?.let { File(it) }
                    val file = if (own(cur) && used.add(cur!!.path)) cur
                        else File(dir, "工程_" + sanitizeName(m.name) + "_" + stamp() + (if (i > 0) "_$i" else "") + ".vmd").also { used.add(it.path) }
                    file.writeBytes(VmdWriter.write(c.motion, m.name))
                    c.sources = listOf(file.absolutePath)
                    synchronized(motionCache) { motionCache.remove(file.absolutePath) }
                } catch (e: Throwable) { Log.w(TAG, "保存动作失败", e) }
            }
            savedMotionRevision[m.id] = m.motionRevision
        }
        val usedCam = HashSet<String>()
        for ((i, c) in scene.cameraClips.withIndex()) {
            if (!c.motion.hasCamera) continue
            val h = cameraHash(c)
            val cur = c.sources.singleOrNull()?.let { File(it) }
            if (h == cameraHashes[c.id] && cur != null && cur.isFile) { usedCam.add(cur.path); continue }
            try {
                dir.mkdirs()
                val file = if (own(cur) && usedCam.add(cur!!.path)) cur else File(dir, "工程_镜头_" + stamp() + (if (i > 0) "_$i" else "") + ".vmd").also { usedCam.add(it.path) }
                file.writeBytes(VmdWriter.write(c.motion, KeyOps.CAMERA_MODEL_NAME))
                c.sources = listOf(file.absolutePath)
                cameraHashes[c.id] = h
                synchronized(motionCache) { motionCache.remove(file.absolutePath) }
            } catch (e: Throwable) { Log.w(TAG, "保存镜头动作失败", e) }
        }
        val s = session
        if (s != null && s.dirty) {
            try { saveModelGl(s.model, null); s.dirty = false } catch (e: Throwable) { Log.w(TAG, "保存模型失败", e) }
        }
    }

    // ---- MCP ----

    /** 打开 / 关闭 MCP 服务。 */
    fun setMcpEnabled(on: Boolean) {
        io.execute {
            try {
                if (!on) {
                    mcpServer?.stop(); mcpServer = null
                    McpService.onStopRequested = null
                    McpService.onStateChanged = null
                    McpService.stop(context)
                    _mcp.value = McpUi()
                    return@execute
                }
                val sp = context.getSharedPreferences("mcp", Context.MODE_PRIVATE)
                var token = sp.getString("token", "") ?: ""
                if (token.isEmpty()) { token = McpServer.randomToken(); sp.edit().putString("token", token).apply() }
                val server = mcpServer ?: McpServer(SceneTools(this), "lumi-mmd", appVersion()).also { mcpServer = it }
                server.onCall = { name, _ ->
                    _mcp.value = _mcp.value.copy(calls = server.callCount, lastCall = name)
                    glRequestRender?.invoke()
                }
                server.onRequest = { method, ms -> _mcp.value = _mcp.value.copy(requests = server.requestCount, lastRequest = method, lastRequestMs = ms) }
                if (!server.isRunning) server.start(sp.getInt("port", 8765), token)
                // 挂一个前台服务：切到别的应用（AI 客户端就在这台手机上时）系统才不会把我们冻住
                McpService.onStopRequested = { setMcpEnabled(false) }
                McpService.onStateChanged = { refreshMcp() }
                McpService.start(context, "端口 ${server.port} · 切到别的应用时也会继续应答")
                publishMcp()
            } catch (e: Throwable) {
                Log.e(TAG, "启动 MCP 失败", e)
                _mcp.value = McpUi(error = "启动失败：${describe(e)}")
            }
        }
    }

    /** 换一个新的访问密钥（旧地址随即失效）。 */
    fun resetMcpToken() {
        io.execute {
            val sp = context.getSharedPreferences("mcp", Context.MODE_PRIVATE)
            sp.edit().putString("token", McpServer.randomToken()).apply()
            val running = mcpServer?.isRunning == true
            mcpServer?.stop()
            if (running) { mcpServer = null; setMcpEnabled(true) }
        }
    }

    /** 网络变化（换了 Wi-Fi）之后刷新显示的地址。 */
    fun refreshMcp() { io.execute { if (mcpServer?.isRunning == true) publishMcp() } }

    private fun publishMcp() {
        val server = mcpServer ?: return
        var ips = McpServer.localAddresses()
        if (ips.isEmpty()) {
            // 个别系统上枚举不到网卡：改问系统当前网络的地址
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val lp = cm.getLinkProperties(cm.activeNetwork)
                ips = lp?.linkAddresses?.mapNotNull { a -> (a.address as? java.net.Inet4Address)?.takeIf { !it.isLoopbackAddress }?.hostAddress } ?: emptyList()
            } catch (_: Throwable) {
            }
        }
        val suffix = if (server.token.isEmpty()) "" else "/" + server.token
        // 本机地址永远排在最后一个：AI 客户端就装在这台手机上时用它（不经过 Wi-Fi，最快）
        val all = if (ips.isEmpty()) listOf("127.0.0.1") else ips + "127.0.0.1"
        _mcp.value = McpUi(true, all.map { "http://$it:${server.port}/mcp$suffix" }, all.map { "http://$it:${server.port}/sse$suffix" }, server.callCount, server.lastCall,
            if (ips.isEmpty()) "没有连上 Wi-Fi：下面的地址只能在本机上用" else "",
            requests = server.requestCount, lastRequest = server.lastRequest, lastRequestMs = server.lastRequestMs,
            keepAlive = McpService.running, unrestricted = backgroundUnrestricted())
    }

    /** 系统有没有把本应用放进「不限制后台 / 不优化电池」的名单。 */
    private fun backgroundUnrestricted(): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Throwable) { true }

    private fun appVersion(): String = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "2" } catch (_: Throwable) { "2" }

    override fun <T> render(block: () -> T): T {
        if (Thread.currentThread() === glThread) return block()
        val p = glPost ?: throw IllegalStateException("The app is still starting. Try again in a moment.")
        val task = java.util.concurrent.FutureTask(java.util.concurrent.Callable { block() })
        p(Runnable {
            // 应用在后台时渲染线程上没有 GL 上下文：借来用一下，截图、释放缓冲这些才做得成
            val borrowed = try { glBorrowContext?.invoke() ?: false } catch (_: Throwable) { false }
            try { task.run() } finally { if (borrowed) try { glReturnContext?.invoke() } catch (_: Throwable) {} }
            kick()
        })
        glRequestRender?.invoke()
        try {
            return task.get(25, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            task.cancel(false)
            throw IllegalStateException("Lumi MMD did not respond within 25 s (it is busy loading something, or the system has suspended it in the background). " +
                "Try again; if it keeps happening, bring the app to the foreground.")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    override val meshSession: MeshSession? get() = session
    override fun beginMesh(model: ModelInstance): MeshSession = beginMeshGl(model)
    override fun endMesh() { val keepDirty = session?.dirty ?: false; session?.dirty = false; endMeshGl(); if (keepDirty) projectDirty = true }

    override fun libraryFiles(): List<Map<String, Any?>> = library.scan().map { item ->
        linkedMapOf<String, Any?>("path" to File(item.path).relativeTo(library.root).path,
            "type" to when (item.type) { LibraryItem.TYPE_MOTION -> "motion"; LibraryItem.TYPE_POSE -> "pose"; LibraryItem.TYPE_AUDIO -> "audio"; LibraryItem.TYPE_SCRIPT -> "script"; else -> "model" },
            "size" to item.sizeBytes)
    }

    override fun resolve(path: String): File? {
        val root = library.root.canonicalFile
        val f = (if (File(path).isAbsolute) File(path) else File(root, path)).canonicalFile
        return if (f.path.startsWith(root.path) && f.isFile) f else null
    }

    override fun addModel(data: ModelData, kind: Int): ModelInstance {
        val m = ModelInstance(data, kind)
        m.scale = data.suggestedScale
        if (kind == ModelInstance.KIND_STAGE) m.castShadow = false
        addModelGl(m, null)
        return m
    }

    override fun removeModel(model: ModelInstance) {
        if (session?.model === model) { session?.dirty = false; endMeshGl() }
        if (runtime.controller?.model === model) runtime.stopControl()
        scene.remove(model)
        editor.onModelRemoved(model)
        renderer.releaseModel(model)
        scene.recomputeDuration()
        detailDirty = true; projectDirty = true
    }

    override fun saveModel(model: ModelInstance, name: String?): String {
        // 「部件」里挪过、删掉的东西先合并进模型数据
        val m = if (session == null && PartOps.hasEdits(model)) mergePartsGl(model) else model
        val p = saveModelGl(m, name)
        if (session?.model === m) session?.dirty = false
        return p
    }

    override fun saveMotion(name: String, bytes: ByteArray): String {
        val dir = File(library.root, "自制动作").also { it.mkdirs() }
        val file = File(dir, sanitizeName(name) + ".vmd")
        file.writeBytes(bytes)
        notifyLibraryChanged()
        return file.relativeTo(library.root).path
    }

    override fun changed() {
        projectDirty = true; stateDirty = true; detailDirty = true; meshShown = -2
        activeUntil = maxOf(activeUntil, System.nanoTime() + 2_500_000_000L)
        glRequestRender?.invoke()
    }

    override fun setPlaying(on: Boolean) { if (on) playGl() else pauseGl() }

    override fun scriptNames(): List<String> = play.libraryScripts()
    override fun readScript(name: String): String? = play.scriptSource(name)
    override fun saveScript(name: String, code: String): String {
        val dir = play.scriptDir.also { it.mkdirs() }
        val f = File(dir, sanitizeName(name) + ".lua")
        f.writeText(code)
        notifyLibraryChanged()
        // 返回完整路径：工程里记的是它，下次打开应用要靠它把脚本找回来
        return f.path
    }
    override fun displayPath(path: String): String = try { File(path).relativeTo(library.root).path } catch (_: Exception) { path }
    override fun scriptLog(): List<String> = play.logSnapshot()
    override fun scriptLogTotal(): Long = play.logTotal
    override fun clearScriptLog() = play.clearLog()

    /** 不等真实时间流逝，把场景往前推进一段（MCP 的 advance：让 AI 看到特效 / 物理过了一会儿之后的样子）。 */
    override fun advance(seconds: Float) {
        var t = 0f
        val h = 1f / 60f
        while (t < seconds - 1e-4f) { runtime.update(h); t += h }
        stateDirty = true
        kick()
    }

    /** 给 MCP 用的截图：按当前镜头渲染一张缩小的图，压成 JPEG。渲染线程。 */
    override fun screenshot(maxSide: Int): ByteArray? {
        if (Thread.currentThread() !== glThread) return null
        // 在后台也能截：画面是画进引擎自己的帧缓冲的，只要 GL 上下文还在（render() 会临时接上）
        if (!(glHasContext?.invoke() ?: resumed) || width <= 0 || height <= 0) return null
        val k = minOf(1f, maxSide.toFloat() / maxOf(width, height))
        val w = maxOf(16, Math.round(width * k)); val h = maxOf(16, Math.round(height * k))
        val tex = GlUtil.genTexture()
        val fbo = GlUtil.genFramebuffer()
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) return null
            scene.update(0f)
            renderer.highlightModel = null; renderer.highlightPart = null
            renderer.overlay.clear()
            // 建模时把线框和选中高亮也画进去，AI 才看得到自己选了什么
            session?.fillOverlay(renderer.overlay)
            renderer.render(scene, fbo, w, h)
            val buf = GlUtil.byteBuffer(w * h * 4)
            renderer.readPixels(fbo, w, h, buf)
            val row = ByteArray(w * 4)
            val flipped = java.nio.ByteBuffer.allocateDirect(w * h * 4)
            for (y in h - 1 downTo 0) {
                buf.position(y * w * 4); buf.get(row)
                var i = 3; while (i < row.size) { row[i] = -1; i += 4 }
                flipped.put(row)
            }
            flipped.position(0)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(flipped)
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle()
            return out.toByteArray()
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GlUtil.deleteFramebuffer(fbo)
            GlUtil.deleteTexture(tex)
        }
    }

    // ---- 新手教程 ----

    private val uiPrefs get() = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _tutorial = MutableStateFlow(!uiPrefs.getBoolean("tutorialDone", false))
    /** 是否要显示新手教程。 */
    val tutorial: StateFlow<Boolean> = _tutorial
    fun showTutorial(show: Boolean) {
        _tutorial.value = show
        if (!show) uiPrefs.edit().putBoolean("tutorialDone", true).apply()
    }

    companion object {
        private const val TAG = "Controller"
    }
}

package com.lumi.mmd.desktop

import android.opengl.GLES30
import com.lumi.mmd.engine.format.ModelLoader
import com.lumi.mmd.engine.format.PmxWriter
import com.lumi.mmd.engine.image.Images
import com.lumi.mmd.engine.image.PlatformImageDecoder
import com.lumi.mmd.engine.mcp.McpHost
import com.lumi.mmd.engine.mcp.McpServer
import com.lumi.mmd.engine.mcp.SceneTools
import com.lumi.mmd.engine.mcp.ToolResult
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.render.GlUtil
import com.lumi.mmd.engine.render.Renderer
import com.lumi.mmd.engine.scene.Editor
import com.lumi.mmd.engine.scene.MeshSession
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.scene.Scene
import com.lumi.mmd.engine.util.Json
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * 桌面上的 MCP 宿主：和手机应用用同一套引擎、同一套 MCP 工具，只是画面渲染到离屏缓冲里。
 * 用来在没有手机的环境里端到端地验证"AI 通过 MCP 做动画 / 建模"这条链路。
 *
 * 用法：mcp=端口  lib=资源库目录  [token=密钥]  [size=宽x高]  [model=启动时加载的模型]
 * 另外多了几个只在桌面版有的调试工具（debug_*），用来模拟手指点击、拖动操纵器。
 */
class DesktopHost(private val width: Int, private val height: Int, private val libRoot: File) : McpHost {
    override val scene = Scene()
    override val editor = Editor(scene)
    val renderer = Renderer()
    private val tasks = LinkedBlockingQueue<Runnable>()
    private val glThread = Thread.currentThread()
    private var session: MeshSession? = null
    private val log = java.util.Collections.synchronizedList(ArrayList<String>())
    private val scriptDir = File(libRoot, "脚本")
    /** 脚本放在屏幕上的文字（桌面版只记下来，不画）。 */
    val uiTexts = java.util.concurrent.ConcurrentHashMap<String, String>()

    override val runtime = com.lumi.mmd.engine.scene.SceneRuntime(scene, renderer.settings, object : com.lumi.mmd.engine.script.ScriptCallbacks {
        override fun scriptLog(script: com.lumi.mmd.engine.script.Script?, text: String, error: Boolean) {
            val line = (if (error) "[出错] " else "") + "[" + (script?.name ?: "控制台") + "] " + text
            log.add(line)
            logTotal++
            while (log.size > 400) log.removeAt(0)
            println(line)
        }
        override fun uiText(owner: Int, id: String, text: String?, x: Float, y: Float, size: Float, color: Int) {
            if (text == null) uiTexts.remove("$owner/$id") else uiTexts["$owner/$id"] = text
        }
        override fun uiClear(owner: Int) { uiTexts.keys.removeIf { it.startsWith("$owner/") } }
        override fun setPlaying(on: Boolean) { this@DesktopHost.setPlaying(on) }
        override fun seekSeconds(seconds: Float) { scene.seekSeconds(seconds) }
        override fun selectedModel(): ModelInstance? = editor.selectedModel
        override fun addModel(data: ModelData, kind: Int): ModelInstance { val m = ModelInstance(data, kind); scene.add(m); return m }
        override fun removeModel(model: ModelInstance) { this@DesktopHost.removeModel(model) }
        override fun scriptSource(name: String): String? = File(scriptDir, "$name.lua").takeIf { it.isFile }?.readText()
        override fun imageBytes(name: String): ByteArray? =
            libRoot.walkTopDown().maxDepth(6).firstOrNull { it.isFile && it.name == name }?.readBytes()
        override fun resolveImage(name: String): String? =
            libRoot.walkTopDown().maxDepth(6).firstOrNull { it.isFile && it.name == name }?.relativeTo(libRoot)?.path
    })

    override fun scriptNames(): List<String> = scriptDir.listFiles()?.filter { it.extension == "lua" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()
    override fun readScript(name: String): String? = File(scriptDir, "$name.lua").takeIf { it.isFile }?.readText()
    override fun saveScript(name: String, code: String): String {
        scriptDir.mkdirs()
        val f = File(scriptDir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "script" } + ".lua")
        f.writeText(code)
        return f.absolutePath
    }
    override fun displayPath(path: String): String = try { File(path).relativeTo(libRoot).path } catch (_: Exception) { path }
    override fun scriptLog(): List<String> = synchronized(log) { log.toList() }
    @Volatile private var logTotal = 0L
    override fun scriptLogTotal(): Long = logTotal
    override fun clearScriptLog() { log.clear() }

    override fun advance(seconds: Float) {
        var t = 0f
        val h = 1f / 60f
        while (t < seconds - 1e-4f) { runtime.update(h); t += h }
    }

    private var fbo = 0
    private var fboW = 0
    private var fboH = 0
    private var tex = 0

    init {
        renderer.onContextCreated()
        renderer.effects = runtime.effects
        renderer.effectRenderer.imageRoot = libRoot
        renderer.settings.msaa = 4
        editor.density = 2f
        scene.camera.update(width.toFloat() / height)
    }

    override fun <T> render(block: () -> T): T {
        if (Thread.currentThread() === glThread) return block()
        val f = CompletableFuture<T>()
        tasks.put(Runnable { try { f.complete(block()) } catch (e: Throwable) { f.completeExceptionally(e) } })
        try {
            return f.get(60, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** 主循环：在持有 GL 上下文的线程上执行排队的任务。 */
    fun loop() {
        while (true) {
            val r = tasks.poll(200, TimeUnit.MILLISECONDS) ?: continue
            r.run()
        }
    }

    override val meshSession: MeshSession? get() = session

    override fun beginMesh(model: ModelInstance): MeshSession {
        session?.let { if (it.model === model) return it; endMesh() }
        val s = MeshSession(scene, model)
        s.density = editor.density
        s.onReplaced = { old, neu -> renderer.releaseModel(old); if (editor.selectedModel === old || editor.selectedModel == null) editor.select(neu) }
        renderer.releaseModel(model)
        editor.select(s.model)
        editor.mode = Editor.MODE_MESH
        editor.meshTarget = s
        session = s
        return s
    }

    override fun endMesh() {
        val s = session ?: return
        s.finish()
        session = null
        editor.meshTarget = null
        editor.mode = Editor.MODE_MODEL
    }

    private fun typeOf(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ModelLoader.isModelFile(name) -> "model"
            ext == "vmd" -> "motion"
            ext == "vpd" -> "pose"
            ext in setOf("mp3", "wav", "ogg", "m4a", "flac") -> "audio"
            else -> null
        }
    }

    override fun libraryFiles(): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        libRoot.walkTopDown().maxDepth(6).filter { it.isFile }.forEach { f ->
            val t = typeOf(f.name) ?: return@forEach
            out.add(linkedMapOf("path" to f.relativeTo(libRoot).path, "type" to t, "size" to f.length()))
        }
        return out.sortedBy { it["path"].toString() }
    }

    override fun resolve(path: String): File? {
        val f = File(libRoot, path).canonicalFile
        if (!f.path.startsWith(libRoot.canonicalPath) || !f.isFile) return null
        return f
    }

    override fun addModel(data: ModelData, kind: Int): ModelInstance {
        val m = ModelInstance(data, kind)
        m.scale = data.suggestedScale
        scene.add(m)
        editor.select(m)
        if (scene.models.size == 1 && data.vertexCount > 0) {
            m.updateWorldMatrix(); m.update(scene.frame, 0f, false, true)
            val mn = FloatArray(3); val mx = FloatArray(3)
            if (!m.exactWorldBounds(mn, mx)) m.worldBounds(mn, mx)
            scene.camera.update(width.toFloat() / height)
            scene.camera.frame(mn, mx)
            scene.userCamera.copyFrom(scene.camera)
        }
        return m
    }

    override fun removeModel(model: ModelInstance) {
        if (session?.model === model) endMesh()
        scene.remove(model)
        editor.onModelRemoved(model)
        renderer.releaseModel(model)
    }

    override fun saveModel(model: ModelInstance, name: String?): String {
        val dir = File(libRoot, "自建模型").also { it.mkdirs() }
        val base = (name ?: model.name).replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "model" }
        val file = File(dir, "$base.pmx")
        PmxWriter.save(model.data, file)
        return file.relativeTo(libRoot).path
    }

    override fun saveMotion(name: String, bytes: ByteArray): String {
        val dir = File(libRoot, "自制动作").also { it.mkdirs() }
        val file = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "motion" } + ".vmd")
        file.writeBytes(bytes)
        return file.relativeTo(libRoot).path
    }

    private fun ensureFbo(w: Int, h: Int) {
        if (fbo != 0 && fboW == w && fboH == h) return
        if (fbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0); GLES30.glDeleteTextures(1, intArrayOf(tex), 0) }
        tex = GlUtil.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        fbo = GlUtil.genFramebuffer()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        fboW = w; fboH = h
    }

    /** 渲染一帧到离屏缓冲并读回来（RGB，自上而下）。 */
    fun renderImage(w: Int, h: Int): BufferedImage {
        ensureFbo(w, h)
        runtime.update(0f)
        fun pass() {
            renderer.highlightModel = if (editor.mode == Editor.MODE_PART) editor.selectedModel else null
            renderer.highlightPart = if (editor.mode == Editor.MODE_PART) editor.selectedPart else null
            session?.fillOverlay(renderer.overlay)
            editor.fillOverlay(renderer.overlay, w, h)
            runtime.fillOverlay(renderer.overlay)
            renderer.render(scene, fbo, w, h)
        }
        pass()
        var guard = 0
        while (renderer.textures.pendingCount > 0 && guard++ < 3000) { Thread.sleep(5); renderer.textures.pump(50_000_000L) }
        pass()
        GLES30.glFinish()
        val pixels = GlUtil.byteBuffer(w * h * 4)
        renderer.readPixels(fbo, w, h, pixels)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val o = ((h - 1 - y) * w + x) * 4
            img.setRGB(x, y, ((pixels.get(o).toInt() and 0xFF) shl 16) or ((pixels.get(o + 1).toInt() and 0xFF) shl 8) or (pixels.get(o + 2).toInt() and 0xFF))
        }
        GlUtil.checkError("screenshot")
        return img
    }

    override fun screenshot(maxSide: Int): ByteArray? {
        val k = maxSide.toFloat() / maxOf(width, height)
        val w = maxOf(16, Math.round(width * minOf(k, 1f))); val h = maxOf(16, Math.round(height * minOf(k, 1f)))
        val img = renderImage(w, h)
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam
        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = 0.88f
        ImageIO.createImageOutputStream(out).use { ios -> writer.output = ios; writer.write(null, IIOImage(img, null, null), param) }
        writer.dispose()
        return out.toByteArray()
    }

    override fun changed() {}

    override fun setPlaying(on: Boolean) { scene.playing = on }

    val viewWidth: Int get() = width
    val viewHeight: Int get() = height
}

fun main(args: Array<String>) {
    val opt = HashMap<String, String>()
    for (a in args) { val i = a.indexOf('='); if (i > 0) opt[a.substring(0, i)] = a.substring(i + 1) }
    val size = (opt["size"] ?: "1280x720").split('x')
    val lib = File(opt["lib"] ?: ".").absoluteFile
    // stdio 接法：标准输出只能有协议消息，其它的输出一律改到标准错误
    val stdio = "--stdio" in args || opt["stdio"] == "1" || opt["stdio"] == "true"
    val protocolOut = System.out
    if (stdio) System.setOut(System.err)
    initEgl()
    Images.platformDecoder = PlatformImageDecoder { bytes, _ -> decodeWithImageIO(bytes) }
    val host = DesktopHost(size[0].toInt(), size[1].toInt(), lib)
    opt["model"]?.let { p -> for (one in p.split(',')) { val f = File(one); host.addModel(ModelLoader.loadModel(f), ModelInstance.KIND_CHARACTER) } }
    val base = SceneTools(host)
    val tools = DebugTools(base, host)
    val server = McpServer(tools, "lumi-mmd-desktop", "4.2.0")
    server.start((opt["mcp"] ?: "8765").toInt(), opt["token"] ?: "")
    println("MCP 服务已启动：http://127.0.0.1:${server.port}/mcp${if (server.token.isEmpty()) "" else "/" + server.token}   资源库：$lib")
    System.out.flush()
    if (stdio) {
        // 客户端把这个程序当子进程起：一行一条 JSON-RPC。输入关了就退出
        Thread({
            try { server.serveStdio(System.`in`, protocolOut) } catch (e: Throwable) { System.err.println("stdio: ${e.message}") }
            server.stop()
            Runtime.getRuntime().halt(0)
        }, "mcp-stdio").start()
    }
    host.loop()
}

/** 在公共工具之外再加几个桌面版专用的调试工具：模拟触摸、切换编辑模式、把画面存成文件。 */
class DebugTools(private val base: SceneTools, private val host: DesktopHost) : com.lumi.mmd.engine.mcp.McpTools {
    override val instructions: String get() = base.instructions

    private fun prop(type: String) = linkedMapOf("type" to type)

    override fun list(): List<Map<String, Any?>> = base.list() + listOf(
        linkedMapOf("name" to "debug_editor", "description" to "Desktop only: set editor mode/tool/axes and select a bone, like tapping the UI.",
            "inputSchema" to linkedMapOf("type" to "object", "properties" to linkedMapOf("mode" to prop("string"), "tool" to prop("string"), "bone" to prop("string"), "local" to prop("boolean"), "axis" to prop("integer"), "sel" to prop("string"), "additive" to prop("boolean")))),
        linkedMapOf("name" to "debug_touch", "description" to "Desktop only: simulate a tap (x,y) or a drag (x,y → x2,y2 in `steps`) in view pixels.",
            "inputSchema" to linkedMapOf("type" to "object", "properties" to linkedMapOf("x" to prop("number"), "y" to prop("number"), "x2" to prop("number"), "y2" to prop("number"), "steps" to prop("integer"), "hold" to prop("boolean")))),
        linkedMapOf("name" to "debug_save", "description" to "Desktop only: render the view at full size and save it as a PNG file.",
            "inputSchema" to linkedMapOf("type" to "object", "properties" to linkedMapOf("path" to prop("string")))),
    )

    override fun call(name: String, args: Map<String, Any?>): ToolResult = when (name) {
        "debug_editor" -> host.render {
            val e = host.editor
            when (args["mode"]) { "view" -> e.mode = Editor.MODE_VIEW; "model" -> e.mode = Editor.MODE_MODEL; "part" -> e.mode = Editor.MODE_PART; "bone" -> e.mode = Editor.MODE_BONE; "mesh" -> e.mode = Editor.MODE_MESH }
            when (args["tool"]) { "move" -> e.tool = Editor.TOOL_MOVE; "rotate" -> e.tool = Editor.TOOL_ROTATE; "scale" -> e.tool = Editor.TOOL_SCALE }
            (args["local"] as? Boolean)?.let { e.localAxes = it }
            (args["axis"] as? Number)?.let { e.axis = it.toInt() }
            (args["additive"] as? Boolean)?.let { host.meshSession?.additive = it }
            when (args["sel"]) { "point" -> host.meshSession?.selMode = 0; "edge" -> host.meshSession?.selMode = 1; "face" -> host.meshSession?.selMode = 2 }
            (args["bone"] as? String)?.let { bn ->
                val m = e.selectedModel ?: host.scene.models.first()
                val b = m.skeleton!!.bones.indexOfFirst { it.name == bn }
                e.select(m, null, b)
            }
            ToolResult(Json.stringify(linkedMapOf("mode" to e.mode, "tool" to e.tool, "bone" to e.selectedBone, "local" to e.localAxes)))
        }
        "debug_touch" -> host.render {
            val e = host.editor
            val w = host.viewWidth; val h = host.viewHeight
            val x = (args["x"] as Number).toFloat(); val y = (args["y"] as Number).toFloat()
            host.scene.update(0f)
            host.scene.camera.update(w.toFloat() / h)
            if (args["x2"] == null) {
                val s = host.meshSession
                val changed = if (e.mode == Editor.MODE_MESH && s != null) s.tap(x, y, w, h) else e.tap(x, y, w, h)
                ToolResult(Json.stringify(linkedMapOf("tap" to changed, "bone" to e.selectedBone, "model" to e.selectedModel?.id)))
            } else {
                val x2 = (args["x2"] as Number).toFloat(); val y2 = (args["y2"] as Number).toFloat()
                val steps = (args["steps"] as? Number)?.toInt() ?: 12
                val grabbed = e.beginDrag(x, y, w, h)
                val handle = e.activeHandle
                var px = x; var py = y
                if (grabbed) {
                    for (i in 1..steps) {
                        val cx = x + (x2 - x) * i / steps; val cy = y + (y2 - y) * i / steps
                        e.drag(cx - px, cy - py, cx, cy, w, h)
                        px = cx; py = cy
                        host.scene.update(0f)
                    }
                } else {
                    host.scene.camera.orbit(x2 - x, y2 - y, h)
                }
                val amount = e.dragAmount
                if (args["hold"] != true) e.endDrag()
                ToolResult(Json.stringify(linkedMapOf("grabbed" to grabbed, "handle" to handle, "amount" to amount)))
            }
        }
        "debug_save" -> host.render {
            val f = File(args["path"] as String)
            ImageIO.write(host.renderImage(host.viewWidth, host.viewHeight), "png", f)
            ToolResult(f.path)
        }
        else -> base.call(name, args)
    }
}

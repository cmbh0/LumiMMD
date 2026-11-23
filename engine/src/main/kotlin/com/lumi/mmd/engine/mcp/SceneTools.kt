package com.lumi.mmd.engine.mcp

import com.lumi.mmd.engine.format.ModelLoader
import com.lumi.mmd.engine.format.VmdWriter
import com.lumi.mmd.engine.math.Quat
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.scene.Editor
import com.lumi.mmd.engine.scene.KeyOps
import com.lumi.mmd.engine.scene.MeshSession
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.scene.Scene
import com.lumi.mmd.engine.scene.SceneOps
import com.lumi.mmd.engine.util.Json
import com.lumi.mmd.engine.util.jArr
import com.lumi.mmd.engine.util.jObj
import java.io.File

/** MCP 工具要用到的、由宿主（手机应用 / 桌面测试程序）提供的能力。 */
interface McpHost {
    val scene: Scene
    val editor: Editor
    /** 在渲染线程上执行 block 并等它做完。场景、模型、网格只能在这里面读写。超时或应用在后台时抛异常。 */
    fun <T> render(block: () -> T): T
    /** 当前的建模会话（没有在建模时为 null）。渲染线程。 */
    val meshSession: MeshSession?
    /** 开始编辑某个模型的网格（已经在编辑别的模型时先结束那个）。渲染线程。 */
    fun beginMesh(model: ModelInstance): MeshSession
    /** 结束建模。渲染线程。 */
    fun endMesh()
    /** 资源库里的文件：每项含 path（相对资源库）、type（model/motion/pose/audio）、size。任意线程。 */
    fun libraryFiles(): List<Map<String, Any?>>
    /** 把工具参数里的路径解析成资源库里的文件；不在资源库里或不存在时返回 null。 */
    fun resolve(path: String): File?
    /** 把一个模型加进场景并选中它。渲染线程。 */
    fun addModel(data: ModelData, kind: Int): ModelInstance
    fun removeModel(model: ModelInstance)
    /** 把模型存成 PMX 放进资源库，返回相对路径。渲染线程。 */
    fun saveModel(model: ModelInstance, name: String?): String
    /** 把一段 VMD 存进资源库，返回相对路径。任意线程。 */
    fun saveMotion(name: String, bytes: ByteArray): String
    /** 按当前镜头渲染一张截图（JPEG），最长边不超过 maxSide。渲染线程。 */
    fun screenshot(maxSide: Int): ByteArray?
    /** 场景被改动了：刷新界面、稍后自动保存工程。任意线程。 */
    fun changed()
    /** 播放 / 暂停。渲染线程。 */
    fun setPlaying(on: Boolean)
    /** 马上要改关键帧了（model=null 表示改镜头的）：宿主可以在这里记一步撤销，让用户能撤掉 AI 的改动。渲染线程。 */
    fun beforeKeyEdit(model: ModelInstance?) {}

    /** 脚本、特效、操控模式。 */
    val runtime: com.lumi.mmd.engine.scene.SceneRuntime
    /** 脚本库里有哪些脚本（名字，不带扩展名）。任意线程。 */
    fun scriptNames(): List<String> = emptyList()
    fun readScript(name: String): String? = null
    /** 把脚本存进脚本库，返回文件的完整路径（工程里记的就是它，下次打开要靠它把脚本找回来）。任意线程。 */
    fun saveScript(name: String, code: String): String = ""
    /** 给 AI 看的路径：在资源库里的写成相对资源库的。 */
    fun displayPath(path: String): String = path
    /** 最近的脚本输出（print 和报错），旧的在前。任意线程。 */
    fun scriptLog(): List<String> = emptyList()
    /** 启动以来一共输出过多少行（[scriptLog] 只留最近的几百行，靠这个数才知道哪些是新的）。 */
    fun scriptLogTotal(): Long = scriptLog().size.toLong()
    /** 清空脚本输出。任意线程。 */
    fun clearScriptLog() {}
    /** 一个声音文件有多长（秒）；读不出来返回 null。可能会卡一小会儿，不要在渲染线程调用。 */
    fun audioSeconds(file: File): Float? = com.lumi.mmd.engine.scene.AudioDeck.wavSeconds(file)
    /** 马上要改音乐轨道了：宿主可以记一步撤销。渲染线程。 */
    fun beforeAudioEdit() {}
    /** 音乐轨道改过了：正在播放的话让声音重新对上。渲染线程。 */
    fun audioChanged() {}
    /** 不等真实时间流逝，直接把场景往前推进 seconds 秒（脚本、物理、操控、特效照常运行）。渲染线程。 */
    fun advance(seconds: Float)
    /** 马上要改片段的摆放了：宿主可以记一步撤销。渲染线程。 */
    fun beforeClipEdit(model: ModelInstance?) {}
    /** 画面设置（影子、描边、泛光、三渲二……）被工具改过了：宿主把自己界面上的开关对齐。渲染线程。 */
    fun lookChanged() {}
}

internal class ToolError(message: String) : RuntimeException(message)

private const val REF_NOTE = "Give the exact name, or the number written as a string (e.g. \"0\")."

/**
 * 提供给 AI 的全部工具：看场景、摆姿势、打关键帧、建模、截图。
 * 工具说明用英文写（各家模型对英文的工具描述理解得最稳），返回值是紧凑的 JSON。
 */
class SceneTools(internal val host: McpHost) : McpTools {
    private class Tool(val name: String, val description: String, val schema: Map<String, Any?>, val run: (Map<String, Any?>) -> ToolResult)

    private val tools = LinkedHashMap<String, Tool>()

    override val instructions: String = """
Lumi MMD is an Android MikuMikuDance-style editor. These tools let you inspect the 3D scene, pose and animate models with keyframes, and build or edit polygon meshes. Call `screenshot` often to look at the result.

Conventions
- Coordinates: left-handed, Y up. A character stands at the origin facing -Z (towards the default camera). +X is the character's LEFT. 1 unit is about 8 cm; a typical character is about 20 units tall.
- Time: frames at 30 fps. Keyframes live on integer frames.
- Rotations are Euler angles in DEGREES [x, y, z], composed as Ry*Rx*Rz (MMD order), relative to the bone's rest pose (same meaning as in VMD files):
  +x tilts the front upwards / leans back (頭 x=+20 looks up, x=-20 nods down);
  +y turns towards the model's own right (its face swings towards -X);
  +z rolls so that things on the +X side go up (左腕 z=+30 raises the left arm, z=-30 lowers it; for 右腕 the signs are mirrored).
- Bones have Japanese names (センター, 上半身, 首, 頭, 左腕, 左ひじ, 左手首, 右腕, 左足ＩＫ, 右足ＩＫ ...). Use `list_bones` to get the exact names. Move feet with the ＩＫ bones (translation); the legs follow automatically.
- Morph (facial expression) weights are 0..1.

- Lua scripting: you can WRITE and RUN Lua scripts in the app. A script can drive anything every frame (bones, morphs, materials, opacity, draw layers, physics, camera, particles, on-screen text and buttons).
  Workflow: `script_api` (read the API once) -> `script_write {name, code, screenshot: true}` (saves it, runs it, returns errors with line numbers, print output and a picture) -> `script_edit` to fix or tweak a few lines -> `script_stop` / `script_run`.
  `lua_eval` executes a piece of Lua immediately (look something up, try a call). `advance` lets simulated time pass. Scripts written this way are saved with the project and keep running in the app.
- Clips: each character has a track of motion clips (like a video editor); the camera and the music have their own tracks. Clips can cross-fade (`fade`). See `clips`.

Typical animation workflow: get_scene -> list_bones -> set_bone_keys / set_morph_keys (several frames per call) -> screenshot with `frame` to check -> timeline play -> export_vmd.
Typical modelling workflow: create_model (or mesh_begin on an existing model) -> mesh_add_primitive / mesh_add_geometry -> mesh_select -> mesh_op (extrude, inset, subdivide ...) / mesh_transform -> mesh_material -> screenshot -> mesh_end (saves a .pmx).
Face winding: front faces are CLOCKWISE when seen from outside (MMD / DirectX style). mesh_add_geometry accepts counter-clockwise input with winding="ccw".
""".trim()

    override fun list(): List<Map<String, Any?>> = tools.values.map { linkedMapOf("name" to it.name, "description" to it.description, "inputSchema" to it.schema) }

    override fun call(name: String, args: Map<String, Any?>): ToolResult {
        val t = tools[name] ?: return ToolResult("Unknown tool: $name", isError = true)
        return try {
            t.run(args)
        } catch (e: ToolError) {
            ToolResult(e.message ?: "error", isError = true)
        }
    }

    internal fun fail(message: String): Nothing = throw ToolError(message)

    // ------------------------------------------------------------------ 小工具

    /*
     * 参数表（inputSchema）只用各家客户端都认的那一小部分 JSON Schema：
     * 每个节点一个字符串 type，数组必有 items，对象必有非空的 properties，不用 oneOf / anyOf / 类型数组 / $ref。
     * 这样不管客户端把它转成 OpenAI、Gemini 还是 Claude 的函数声明都不会被拒收。
     */
    internal fun tool(name: String, description: String, props: Map<String, Any?> = emptyMap(), required: List<String> = emptyList(), run: (Map<String, Any?>) -> ToolResult) {
        val schema = linkedMapOf<String, Any?>("type" to "object", "properties" to props)
        if (required.isNotEmpty()) schema["required"] = required
        tools[name] = Tool(name, description, schema, run)
    }

    internal fun pNum(d: String): Map<String, Any?> = linkedMapOf("type" to "number", "description" to d)
    internal fun pInt(d: String): Map<String, Any?> = linkedMapOf("type" to "integer", "description" to d)
    internal fun pStr(d: String): Map<String, Any?> = linkedMapOf("type" to "string", "description" to d)
    internal fun pBool(d: String): Map<String, Any?> = linkedMapOf("type" to "boolean", "description" to d)
    internal fun pEnum(d: String, vararg v: String): Map<String, Any?> = linkedMapOf("type" to "string", "enum" to v.toList(), "description" to d)
    internal fun pVec(n: Int, d: String): Map<String, Any?> = linkedMapOf("type" to "array", "items" to linkedMapOf("type" to "number"), "minItems" to n, "maxItems" to n, "description" to d)
    internal fun pArr(items: Map<String, Any?>, d: String): Map<String, Any?> = linkedMapOf("type" to "array", "items" to items, "description" to d)
    internal fun pObj(props: Map<String, Any?>, d: String = ""): Map<String, Any?> = linkedMapOf<String, Any?>("type" to "object", "properties" to props).also { if (d.isNotEmpty()) it["description"] = d }
    /**
     * 模型 / 骨骼 / 表情既可以用序号也可以用名字指定。
     * 参数表里统一声明成字符串（序号写成 "3" 这样）：很多客户端不接受「整数或字符串」这种两可的类型。
     * 实际收到整数也照样认。
     */
    internal fun pRef(d: String): Map<String, Any?> = linkedMapOf("type" to "string", "description" to "$d $REF_NOTE")
    internal val pModel: Map<String, Any?> = pRef("Model id or name. Defaults to the model being edited / selected.")

    internal fun ok(v: Any?): ToolResult = ToolResult(Json.stringify(v))
    // 有些客户端（或者模型）会把数字、布尔写成字符串（"30" "true"），把数组写成一段 JSON 文本：能看懂的都收下
    internal fun Map<String, Any?>.f(key: String, default: Float): Float = when (val v = this[key]) {
        is Number -> v.toFloat(); is String -> v.trim().toFloatOrNull() ?: default; else -> default
    }
    internal fun Map<String, Any?>.i(key: String, default: Int): Int = when (val v = this[key]) {
        is Number -> v.toInt(); is String -> v.trim().toDoubleOrNull()?.toInt() ?: default; else -> default
    }
    internal fun Map<String, Any?>.b(key: String, default: Boolean): Boolean = when (val v = this[key]) {
        is Boolean -> v
        is String -> when (v.trim().lowercase()) { "true", "1", "yes", "on" -> true; "false", "0", "no", "off" -> false; else -> default }
        is Number -> v.toDouble() != 0.0
        else -> default
    }
    internal fun Map<String, Any?>.s(key: String): String? = this[key] as? String
    /** 取一个数组参数；写成 JSON 文本（"[1,2,3]"）的也认。 */
    internal fun arrOf(v: Any?): List<Any?>? = v.jArr() ?: (v as? String)?.trim()?.takeIf { it.startsWith("[") }?.let { runCatching { Json.parse(it) }.getOrNull().jArr() }
    internal fun Map<String, Any?>.vec(key: String, n: Int): FloatArray? {
        val a = arrOf(this[key]) ?: return null
        if (a.size < n) fail("'$key' must be an array of $n numbers")
        return FloatArray(n) { (a[it] as? Number)?.toFloat() ?: fail("'$key' must contain numbers") }
    }
    /** 颜色：3 个数（第 4 个当 1）或 4 个数。 */
    internal fun Map<String, Any?>.rgba(key: String): FloatArray? {
        val a = arrOf(this[key]) ?: return null
        if (a.size != 3 && a.size != 4) fail("'$key' must be [r,g,b] or [r,g,b,a]")
        return FloatArray(4) { if (it < a.size) (a[it] as? Number)?.toFloat() ?: fail("'$key' must contain numbers") else 1f }
    }
    internal fun pColor(d: String): Map<String, Any?> = linkedMapOf("type" to "array", "items" to linkedMapOf("type" to "number"), "minItems" to 3, "maxItems" to 4, "description" to d)
    internal fun round(v: Float): Float = Math.round(v * 1000f) / 1000f
    internal fun vecOut(a: FloatArray, o: Int = 0, n: Int = 3): List<Float> = List(n) { round(a[o + it]) }
    internal fun num(v: Any?, what: String): Float = (v as? Number)?.toFloat() ?: (v as? String)?.trim()?.toFloatOrNull() ?: fail("$what must be a number")

    internal fun model(a: Map<String, Any?>, key: String = "model"): ModelInstance {
        val s = host.scene
        return when (val v = a[key]) {
            null -> host.meshSession?.model ?: host.editor.selectedModel ?: s.models.firstOrNull { it.kind == ModelInstance.KIND_CHARACTER } ?: s.models.firstOrNull()
                ?: fail("The scene has no models. Use load_model or create_model first.")
            is Number -> s.findById(v.toInt()) ?: fail("No model with id ${v.toInt()}. Call get_scene to list models.")
            else -> {
                val name = v.toString()
                s.models.firstOrNull { it.name == name }
                    ?: asIndex(name)?.let { id -> s.findById(id) }
                    ?: s.models.firstOrNull { it.name.contains(name, true) }
                    ?: fail("No model named '$name'. Call get_scene to list models.")
            }
        }
    }

    /** "3" 这样的字符串当序号用（名字对不上的时候）。 */
    internal fun asIndex(v: String): Int? = v.trim().takeIf { it.isNotEmpty() && it.length <= 9 && it.all { c -> c in '0'..'9' } }?.toInt()

    internal fun bone(m: ModelInstance, v: Any?): Int {
        val sk = m.skeleton ?: fail("Model '${m.name}' has no bones.")
        return when (v) {
            is Number -> v.toInt().also { if (it !in 0 until sk.count) fail("Bone index $it out of range (0..${sk.count - 1}).") }
            is String -> {
                var i = sk.bones.indexOfFirst { it.name == v }
                if (i < 0) i = sk.bones.indexOfFirst { it.nameEn.isNotEmpty() && it.nameEn.equals(v, true) }
                if (i < 0) asIndex(v)?.let { n -> if (n !in 0 until sk.count) fail("Bone index $n out of range (0..${sk.count - 1})."); i = n }
                if (i < 0) fail("No bone named '$v' in '${m.name}'. Call list_bones for the exact names.")
                i
            }
            else -> fail("Missing bone (index or name).")
        }
    }

    internal fun morph(m: ModelInstance, v: Any?): Int = when (v) {
        is Number -> v.toInt().also { if (it !in 0 until m.morphs.count) fail("Morph index $it out of range.") }
        is String -> {
            var i = m.data.morphs.indexOfFirst { it.name == v }
            if (i < 0) i = m.data.morphs.indexOfFirst { it.nameEn.isNotEmpty() && it.nameEn.equals(v, true) }
            if (i < 0) asIndex(v)?.let { n -> if (n !in 0 until m.morphs.count) fail("Morph index $n out of range."); i = n }
            if (i < 0) fail("No morph named '$v'. Call list_morphs for the exact names.")
            i
        }
        else -> fail("Missing morph (index or name).")
    }

    internal fun kindName(k: Int) = when (k) { ModelInstance.KIND_STAGE -> "stage"; ModelInstance.KIND_ACCESSORY -> "accessory"; else -> "character" }
    internal fun kindOf(s: String?): Int? = when (s) { "stage" -> ModelInstance.KIND_STAGE; "accessory" -> ModelInstance.KIND_ACCESSORY; "character" -> ModelInstance.KIND_CHARACTER; else -> null }

    internal fun lookInfo(): Map<String, Any?> {
        val s = host.runtime.settings
        fun c(v: FloatArray) = v.map { round(it) }
        return linkedMapOf(
            "cel" to s.celBands, "cel_soft" to round(s.celSoftness), "cel_shift" to round(s.celShift), "cel_specular" to round(s.celSpecular), "shade" to c(s.shadeColor),
            "edges" to s.edges, "edge_width" to round(s.edgeWidth), "edge_color" to c(s.edgeColor), "edge_all" to s.edgeAll,
            "shadows" to s.shadows, "shadow_strength" to round(s.shadowStrength),
            "rim" to round(s.rim), "rim_color" to c(s.rimColor), "specular" to round(s.specular), "sky" to c(s.skyColor), "ground" to c(s.groundColor),
            "background" to c(s.backgroundTop) + c(s.backgroundBottom),
            "bloom" to round(if (s.bloom) s.bloomStrength else 0f), "exposure" to round(s.exposure), "saturation" to round(s.saturation),
            "contrast" to round(s.contrast), "vignette" to round(s.vignette), "posterize" to s.posterize, "grade" to c(s.grade))
    }

    internal fun floorInfo(): Map<String, Any?> {
        val f = host.scene.floor
        return linkedMapOf("enabled" to f.enabled, "height" to round(f.height), "grid" to f.grid, "shadow" to f.shadow, "collide" to f.collide,
            "cell" to round(f.cell), "color" to f.color.map { round(it) }, "grid_color" to f.gridColor.map { round(it) })
    }

    internal fun modelInfo(m: ModelInstance): Map<String, Any?> {
        val e = FloatArray(3)
        m.getRotationEuler(e)
        return linkedMapOf(
            "id" to m.id, "name" to m.name, "kind" to kindName(m.kind), "visible" to m.visible, "opacity" to round(m.opacity), "layer" to m.layer,
            "collision" to ModelInstance.COLLISION_NAMES[m.collision],
            "position" to vecOut(m.position), "rotation" to vecOut(e), "scale" to round(m.scale),
            "bones" to (m.skeleton?.count ?: 0), "morphs" to m.morphs.count, "vertices" to m.data.vertexCount, "triangles" to m.data.triangleCount,
            "keyframes" to (m.motion?.motion?.keyCount ?: 0),
            "attached_to" to m.attachParent?.let { p -> linkedMapOf("model" to p.id, "bone" to (p.skeleton?.bones?.getOrNull(m.attachBone)?.name ?: "")) },
        )
    }

    private val curves = mapOf(
        "linear" to intArrayOf(20, 20, 107, 107), "ease_in" to intArrayOf(64, 0, 107, 107),
        "ease_out" to intArrayOf(20, 20, 64, 127), "ease_in_out" to intArrayOf(64, 0, 64, 127),
    )

    private fun curveBytes(v: Any?): ByteArray? {
        val c: IntArray = when (v) {
            null -> return null
            is String -> curves[v.trim().lowercase()] ?: run {
                // "x1,y1,x2,y2"（带不带方括号都行）
                val n = v.trim().removePrefix("[").removeSuffix("]").split(',', ' ').filter { it.isNotBlank() }.map { it.trim().toDoubleOrNull() }
                if (n.size < 4 || n.any { it == null }) fail("Unknown curve '$v' (use linear, ease_in, ease_out, ease_in_out or \"x1,y1,x2,y2\" with values 0..127).")
                IntArray(4) { n[it]!!.toInt() }
            }
            is List<*> -> { if (v.size < 4) fail("curve needs 4 numbers"); IntArray(4) { (v[it] as? Number)?.toInt() ?: 0 } }
            else -> return null
        }
        return ByteArray(16) { c[it % 4].coerceIn(0, 127).toByte() }
    }

    internal val deg = (Math.PI / 180.0).toFloat()

    init {
        registerScene()
        registerAnimation()
        registerMesh()
        registerPlayTools()
    }

    // ------------------------------------------------------------------ 场景

    private fun registerScene() {
        tool("get_scene", "Overview of the scene: models (id, name, kind, transform, bone/morph counts), camera, current frame, timeline length, selection and whether a mesh is being edited.") {
            ok(host.render {
                val s = host.scene
                linkedMapOf(
                    "models" to s.models.map { modelInfo(it) },
                    "selected_model" to host.editor.selectedModel?.id,
                    "frame" to Math.round(s.frame), "last_frame" to s.maxFrame, "fps" to 30, "playing" to s.playing,
                    "camera" to cameraInfo() + linkedMapOf("keyframes" to (s.cameraMotion?.camera?.count ?: 0)),
                    "editing_mesh" to host.meshSession?.model?.id,
                    "floor" to floorInfo(),
                )
            })
        }

        tool("list_library", "List files in the app's asset library that can be loaded: models (pmx, pmd, obj, gltf ...), motions (vmd), poses (vpd), audio.",
            linkedMapOf("type" to pEnum("Only this kind of file.", "model", "motion", "pose", "audio"), "filter" to pStr("Only paths containing this text."))) { a ->
            val type = a.s("type"); val filter = a.s("filter")
            val files = host.libraryFiles().filter { (type == null || it["type"] == type) && (filter == null || it["path"].toString().contains(filter, true)) }
            ok(linkedMapOf("count" to files.size, "files" to files.take(300)))
        }

        tool("load_model", "Load a model file from the library into the scene. Returns the new model's id.",
            linkedMapOf("path" to pStr("Library path from list_library."), "kind" to pEnum("How to treat it (default: guessed).", "character", "stage", "accessory")), listOf("path")) { a ->
            val file = host.resolve(a.s("path") ?: fail("Missing path")) ?: fail("File not found in the library: ${a.s("path")}")
            val data = ModelLoader.loadModel(file)
            ok(host.render {
                val m = host.addModel(data, kindOf(a.s("kind")) ?: SceneOps.guessKind(data, file.name))
                host.changed()
                modelInfo(m)
            })
        }

        tool("floor", "The default floor: an infinite horizontal plane that draws the grid, receives shadows and stops falling things (hair / skirt bodies, script particles and rigid bodies, the controlled character). " +
            "Without arguments: returns its state. enabled=false removes the floor (nothing is drawn, things fall forever); enabled=true or reset=true adds a default floor back. " +
            "The other fields modify it. Saved with the project.",
            linkedMapOf("enabled" to pBool("false = remove the floor, true = add it back (keeps its settings)."),
                "reset" to pBool("true = replace with a fresh default floor (height 0, grid, shadow, collision)."),
                "height" to pNum("World height (y) of the floor."),
                "grid" to pBool("Draw the grid lines."), "shadow" to pBool("Receive shadows."), "collide" to pBool("Stop falling things."),
                "cell" to pNum("Size of one small grid cell (default 5; every 5th line is bold)."),
                "color" to pColor("Solid fill [r,g,b] or [r,g,b,a], 0..1. a=0 (the default floor) = transparent floor with only grid and shadow."),
                "grid_color" to pColor("Grid line colour [r,g,b] or [r,g,b,a], 0..1."))) { a ->
            ok(host.render {
                val f = host.scene.floor
                if (a.b("reset", false)) f.reset()
                if (a["enabled"] is Boolean) f.enabled = a.b("enabled", true)
                if (a["height"] is Number) f.height = a.f("height", 0f).coerceIn(-100000f, 100000f)
                if (a["grid"] is Boolean) f.grid = a.b("grid", true)
                if (a["shadow"] is Boolean) f.shadow = a.b("shadow", true)
                if (a["collide"] is Boolean) f.collide = a.b("collide", true)
                if (a["cell"] is Number) f.cell = a.f("cell", 5f).coerceIn(0.1f, 1000f)
                a.rgba("color")?.let { for (i in 0 until 4) f.color[i] = it[i].coerceIn(0f, 1f) }
                a.rgba("grid_color")?.let { for (i in 0 until 4) f.gridColor[i] = it[i].coerceIn(0f, 1f) }
                if (a.isNotEmpty()) host.changed()
                floorInfo()
            })
        }

        tool("render", "Read or change how the picture is drawn (the look): shadows, outlines, toon / cel shading (三渲二), rim light, ambient colours, background, colour grading. " +
            "Without arguments: returns the current values. Only the given fields change. preset=\"toon\" switches to an anime cel-shaded look in one step " +
            "(2 light bands, cool shadow tint, outlines on every model, including glTF / OBJ ones); preset=\"default\" returns to the normal look. Fields given together with a preset are applied after it. " +
            "For per-material control (m:material(name).shading / .cel / :color / :tint) or animated changes use a Lua script: see `render` in script_api.",
            linkedMapOf(
                "preset" to pEnum("toon = cel-shaded look; default = normal look.", "toon", "default"),
                "cel" to pInt("Cel shading: number of light bands. 0 = off (models use their own toon ramp / normal lighting), 1 = two flat tones (lit / shadow), 2 = three tones, up to 8."),
                "cel_soft" to pNum("Softness of the border between tones, 0 (hard) .. 1 (smooth). Default 0.04."),
                "cel_shift" to pNum("Moves the light / shadow border: -1 (more shadow) .. 1 (more light). Default 0."),
                "cel_specular" to pNum("Strength of hard-edged highlights in cel mode, 0 = none."),
                "shade" to pVec(3, "Cel shading: colour the dark tone is multiplied by, [r,g,b] 0..1. Default [0.70,0.64,0.80] (cool violet)."),
                "edges" to pBool("Draw outlines."), "edge_width" to pNum("Outline width multiplier (1 = as authored)."),
                "edge_color" to pColor("Outline colour for every model, [r,g,b] (or [r,g,b,amount]: amount 0 = every material keeps its own outline colour again)."),
                "edge_all" to pBool("Also outline models / materials that have no outline of their own (glTF, OBJ, FBX ...)."),
                "shadows" to pBool("Shadow maps on / off."), "shadow_strength" to pNum("Shadow darkness 0..1."),
                "rim" to pNum("Rim light strength 0..3 (0 = off)."),
                "rim_color" to pColor("Rim light colour [r,g,b] (or [r,g,b,amount]: amount 0 = follow the light colour again)."),
                "specular" to pNum("Highlight multiplier for normal (non-cel) lighting. 1 = as authored, 0 = matte."),
                "sky" to pVec(3, "Ambient colour from above [r,g,b] (non-MMD materials)."), "ground" to pVec(3, "Ambient colour from below [r,g,b]."),
                "background" to pArr(linkedMapOf("type" to "number"), "Background colour: [r,g,b] solid, or [r,g,b, r,g,b] = top and bottom of a gradient. 0..1."),
                "bloom" to pNum("Bloom strength 0..3 (0 = off)."), "exposure" to pNum("Exposure, 1 = normal."), "saturation" to pNum("Saturation, 1 = normal, 0 = grey."),
                "contrast" to pNum("Contrast, 1 = normal."), "vignette" to pNum("Vignette 0..1."),
                "posterize" to pInt("Posterize: colour levels per channel (e.g. 6); 0 = off."),
                "grade" to pVec(3, "Colour the whole picture is multiplied by [r,g,b], [1,1,1] = none."))) { a ->
            ok(host.render {
                val s = host.runtime.settings
                when (a.s("preset")) {
                    "toon" -> s.applyToon()
                    "default" -> { s.resetStyle(); s.edges = true; s.edgeWidth = 1f; s.saturation = 1f; s.rim = 0f }
                    null -> {}
                    else -> fail("preset must be toon or default")
                }
                if (a["cel"] is Number) s.celBands = a.i("cel", 0).coerceIn(0, 8)
                if (a["cel_soft"] is Number) s.celSoftness = a.f("cel_soft", 0f).coerceIn(0f, 1f)
                if (a["cel_shift"] is Number) s.celShift = a.f("cel_shift", 0f).coerceIn(-1f, 1f)
                if (a["cel_specular"] is Number) s.celSpecular = a.f("cel_specular", 0f).coerceIn(0f, 4f)
                a.vec("shade", 3)?.let { for (i in 0 until 3) s.shadeColor[i] = it[i].coerceIn(0f, 4f) }
                if (a["edges"] is Boolean) s.edges = a.b("edges", true)
                if (a["edge_width"] is Number) s.edgeWidth = a.f("edge_width", 1f).coerceIn(0f, 10f)
                a.rgba("edge_color")?.let { for (i in 0 until 4) s.edgeColor[i] = it[i].coerceIn(0f, 1f) }
                if (a["edge_all"] is Boolean) s.edgeAll = a.b("edge_all", false)
                if (a["shadows"] is Boolean) s.shadows = a.b("shadows", true)
                if (a["shadow_strength"] is Number) s.shadowStrength = a.f("shadow_strength", 1f).coerceIn(0f, 1f)
                if (a["rim"] is Number) s.rim = a.f("rim", 0f).coerceIn(0f, 3f)
                a.rgba("rim_color")?.let { for (i in 0 until 4) s.rimColor[i] = it[i].coerceIn(0f, if (i == 3) 1f else 4f) }
                if (a["specular"] is Number) s.specular = a.f("specular", 1f).coerceIn(0f, 8f)
                a.vec("sky", 3)?.let { for (i in 0 until 3) s.skyColor[i] = it[i].coerceIn(0f, 4f) }
                a.vec("ground", 3)?.let { for (i in 0 until 3) s.groundColor[i] = it[i].coerceIn(0f, 4f) }
                (a["background"] as? List<*>)?.let { l ->
                    val v = l.map { (it as? Number)?.toFloat() ?: fail("background must be numbers") }
                    if (v.size != 3 && v.size != 6) fail("background needs 3 numbers (solid) or 6 (top + bottom)")
                    for (i in 0 until 3) { s.backgroundTop[i] = v[i].coerceIn(0f, 1f); s.backgroundBottom[i] = v[if (v.size == 6) i + 3 else i].coerceIn(0f, 1f) }
                    s.customBackground = true
                }
                if (a["bloom"] is Number) { s.bloomStrength = a.f("bloom", 0f).coerceIn(0f, 3f); s.bloom = s.bloomStrength > 0f }
                if (a["exposure"] is Number) s.exposure = a.f("exposure", 1f).coerceIn(0.05f, 8f)
                if (a["saturation"] is Number) s.saturation = a.f("saturation", 1f).coerceIn(0f, 3f)
                if (a["contrast"] is Number) s.contrast = a.f("contrast", 1f).coerceIn(0f, 4f)
                if (a["vignette"] is Number) s.vignette = a.f("vignette", 0f).coerceIn(0f, 1f)
                if (a["posterize"] is Number) s.posterize = a.i("posterize", 0).coerceIn(0, 64)
                a.vec("grade", 3)?.let { for (i in 0 until 3) s.grade[i] = it[i].coerceIn(0f, 4f) }
                if (a.isNotEmpty()) { host.lookChanged(); host.changed() }
                lookInfo()
            })
        }

        tool("remove_model", "Remove a model from the scene.", linkedMapOf("model" to pModel), listOf("model")) { a ->
            ok(host.render { val m = model(a); host.removeModel(m); host.changed(); linkedMapOf("removed" to m.id) })
        }

        tool("set_model", "Change a model's placement or options. Only the given fields change.",
            linkedMapOf("model" to pModel, "position" to pVec(3, "World position [x,y,z]."), "rotation" to pVec(3, "Euler degrees [x,y,z]."),
                "scale" to pNum("Uniform scale."), "visible" to pBool("Show or hide."), "name" to pStr("New display name."),
                "opacity" to pNum("Whole-model opacity 0..1 (1 = solid)."),
                "layer" to pInt("Draw layer: a model on a higher layer is always drawn over models on lower layers, regardless of depth (like layers in a video editor). Default 0."),
                "kind" to pEnum("character / stage / accessory", "character", "stage", "accessory"),
                "collision" to pEnum("Whether this model blocks things: other models' hair / skirt physics, script particles, cloth, liquid and rigid bodies, the controlled character, raycasts. " +
                    "none = does not block (default); box = its bounding box (props, crates); mesh = every face of the model, exact (stages, stairs, tables, walls). " +
                    "Uses the rest-pose shape and follows the model's position / rotation / scale.", "none", "box", "mesh"),
                "attach_to" to pObj(linkedMapOf("model" to pRef("Parent model id or name."), "bone" to pRef("Bone on the parent (index or name).")),
                    "Attach this model to a bone of another model (hat on 頭, prop in 右手首 ...). To detach, pass `detach: true`."), "detach" to pBool("true = detach this model from whatever it is attached to."))) { a ->
            ok(host.render {
                val m = model(a)
                a.vec("position", 3)?.let { System.arraycopy(it, 0, m.position, 0, 3) }
                a.vec("rotation", 3)?.let { m.setRotationEuler(it[0], it[1], it[2]) }
                if (a["scale"] is Number) m.scale = a.f("scale", 1f).coerceIn(0.001f, 1000f)
                if (a["visible"] is Boolean) m.visible = a.b("visible", true)
                if (a["opacity"] is Number) m.opacity = a.f("opacity", 1f).coerceIn(0f, 1f)
                if (a["layer"] is Number) m.layer = a.i("layer", 0).coerceIn(-100, 100)
                a.s("collision")?.let { c ->
                    val i = ModelInstance.COLLISION_NAMES.indexOf(c)
                    if (i < 0) fail("collision must be none, box or mesh")
                    m.collision = i
                    if (i == ModelInstance.COLLISION_MESH && m.collisionMesh() == null) fail("This model has no faces (or more than ${com.lumi.mmd.engine.physics.MeshShape.MAX_TRIANGLES}); use collision=box instead.")
                }
                a.s("name")?.takeIf { it.isNotBlank() }?.let { m.name = it }
                kindOf(a.s("kind"))?.let { m.kind = it }
                if (a.b("detach", false)) { m.attachParent = null; m.attachBone = -1 }
                else if (a.containsKey("attach_to")) {
                    val at = a["attach_to"].jObj()?.takeIf { it["model"] != null || it["bone"] != null }
                    if (at == null) { m.attachParent = null; m.attachBone = -1 }
                    else {
                        val p = model(at)
                        if (p === m) fail("A model cannot be attached to itself.")
                        m.attachParent = p; m.attachBone = bone(p, at["bone"])
                    }
                }
                m.poseDirty = true
                host.changed()
                modelInfo(m)
            })
        }

        val views = arrayOf("front", "back", "left", "right", "top", "three_quarter")
        tool("set_camera", "Move the viewing camera (orbit camera: it looks at `target` from `distance`). Only the given fields change. This does not create keyframes; use set_camera_keys for that.",
            linkedMapOf("target" to pVec(3, "Point looked at."), "distance" to pNum("Distance from the target."), "rotation" to pVec(3, "Degrees [x,y,z]. x<0 looks down from above (x=-90 is a top view), y orbits around the target (y=90 puts the camera on the +X side, i.e. a character's left), z rolls."),
                "fov" to pNum("Field of view in degrees (MMD default 30)."), "view" to pEnum("Preset direction (front = looking at a character's face; left / right = the character's own left / right side; three_quarter = from the front, slightly above and to one side).", *views),
                "fit" to pBool("Frame the selected model (or the whole scene) in view."))) { a ->
            ok(host.render { applyCamera(a); host.changed(); cameraInfo() })
        }

        tool("screenshot", "Render the current view and return it as an image so you can SEE the scene. Optionally seek to a frame and/or switch to a preset view first (the camera stays there).",
            linkedMapOf("size" to pInt("Longest side in pixels (256-1280, default 768)."),
                "view" to pEnum("Preset direction (front = looking at a character's face; left / right = the character's own side; top; three_quarter = front, slightly above and to one side).", *views),
                "fit" to pBool("Frame the selected model in view first."), "frame" to pInt("Seek to this frame first."))) { a ->
            var info: Map<String, Any?> = emptyMap()
            val bytes = host.render {
                if (a["frame"] is Number) { host.setPlaying(false); host.scene.seek(a.i("frame", 0).toFloat(), true); host.scene.update(0f) }
                if (a.containsKey("view") || a.b("fit", false)) applyCamera(a)
                info = linkedMapOf("frame" to Math.round(host.scene.frame), "camera" to cameraInfo())
                host.screenshot(a.i("size", 768).coerceIn(256, 1280))
            } ?: fail("Could not render a screenshot (is the app in the background?).")
            ToolResult(Json.stringify(info), bytes, "image/jpeg")
        }

        tool("save_project", "Ask the app to save the current scene (models, placement, motions) so that it is restored on the next launch.") {
            host.changed(); ok(linkedMapOf("ok" to true))
        }
    }

    internal fun cameraInfo(): Map<String, Any?> {
        val c = host.scene.camera
        return linkedMapOf("target" to vecOut(c.target), "distance" to round(c.distance), "rotation" to listOf(round(c.rotX / deg), round(c.rotY / deg), round(c.rotZ / deg)), "fov" to round(c.fovDeg))
    }

    private fun applyCamera(a: Map<String, Any?>) {
        val s = host.scene
        val c = s.camera
        val view = a.s("view")
        if (view != null) {
            c.rotZ = 0f
            // 镜头角度的符号（与 VMD 镜头数据一致）：rotX 为负 = 从上往下看；rotY = +90° 时相机在 +X 一侧（角色的左边）
            when (view) {
                "front" -> { c.rotX = 0f; c.rotY = 0f }
                "back" -> { c.rotX = 0f; c.rotY = 180f * deg }
                "left" -> { c.rotX = 0f; c.rotY = 90f * deg }
                "right" -> { c.rotX = 0f; c.rotY = -90f * deg }
                "top" -> { c.rotX = -89f * deg; c.rotY = 0f }
                else -> { c.rotX = -20f * deg; c.rotY = -35f * deg }
            }
        }
        if (view != null || a.b("fit", false)) {
            val mn = FloatArray(3); val mx = FloatArray(3)
            val m = host.meshSession?.model ?: host.editor.selectedModel
            var has = false
            if (m != null && m.data.vertexCount > 0) { m.updateWorldMatrix(); has = m.exactWorldBounds(mn, mx); if (!has) { m.worldBounds(mn, mx); has = true } }
            if (!has) has = s.characterBounds(mn, mx)
            if (has) { c.update(c.aspect); c.frame(mn, mx) }
        }
        a.vec("target", 3)?.let { System.arraycopy(it, 0, c.target, 0, 3) }
        if (a["distance"] is Number) c.distance = a.f("distance", 45f).coerceIn(0.5f, 5000f)
        a.vec("rotation", 3)?.let { c.rotX = it[0] * deg; c.rotY = it[1] * deg; c.rotZ = it[2] * deg }
        if (a["fov"] is Number) c.fovDeg = a.f("fov", 30f).coerceIn(1f, 150f)
        c.update(c.aspect)
        if (s.cameraDriven) s.holdCamera() else s.userCamera.copyFrom(c)
    }

    // ------------------------------------------------------------------ 动画

    private fun registerAnimation() {
        tool("list_bones", "List a model's bones: index, name, parent, rest position, and whether it can move / is an IK target. By default only bones meant to be posed are listed.",
            linkedMapOf("model" to pModel, "filter" to pStr("Only names containing this text."), "all" to pBool("Include hidden / helper bones too."),
                "offset" to pInt("Skip this many results."), "limit" to pInt("Max results (default 200)."))) { a ->
            ok(host.render {
                val m = model(a)
                val sk = m.skeleton ?: fail("Model '${m.name}' has no bones.")
                val filter = a.s("filter"); val all = a.b("all", false)
                val out = ArrayList<Map<String, Any?>>()
                for ((i, b) in sk.bones.withIndex()) {
                    if (!all && (!b.isVisible || !b.isOperable)) continue
                    if (filter != null && !b.name.contains(filter, true) && !b.nameEn.contains(filter, true)) continue
                    val e = linkedMapOf<String, Any?>("index" to i, "name" to b.name)
                    if (b.nameEn.isNotEmpty() && b.nameEn != b.name) e["name_en"] = b.nameEn
                    e["parent"] = if (b.parent >= 0) sk.bones[b.parent].name else null
                    e["rest"] = vecOut(b.position)
                    if (b.isTranslatable) e["can_move"] = true
                    if (!b.isRotatable) e["can_rotate"] = false
                    if (b.isIk) e["ik"] = true
                    out.add(e)
                }
                val off = a.i("offset", 0).coerceAtLeast(0); val lim = a.i("limit", 200).coerceIn(1, 1000)
                linkedMapOf("model" to m.id, "total" to out.size, "bones" to out.drop(off).take(lim))
            })
        }

        tool("list_morphs", "List a model's morphs (facial expressions etc.): index, name, panel (eyebrow / eye / mouth / other) and current weight.",
            linkedMapOf("model" to pModel, "filter" to pStr("Only names containing this text."))) { a ->
            ok(host.render {
                val m = model(a)
                val filter = a.s("filter")
                val panels = arrayOf("system", "eyebrow", "eye", "mouth", "other")
                val out = ArrayList<Map<String, Any?>>()
                for ((i, mo) in m.data.morphs.withIndex()) {
                    if (filter != null && !mo.name.contains(filter, true)) continue
                    out.add(linkedMapOf("index" to i, "name" to mo.name, "panel" to panels[mo.panel.coerceIn(0, 4)], "weight" to round(m.morphs.weightOf(i))))
                }
                linkedMapOf("model" to m.id, "total" to out.size, "morphs" to out.take(500))
            })
        }

        tool("get_pose", "Current local pose of bones at the current frame (rotation in Euler degrees and translation, relative to rest) plus their world position. Useful to see where hands and feet are.",
            linkedMapOf("model" to pModel, "bones" to pArr(pRef("Bone index or name."), "Bones to query (default: all bones that are keyed or posed)."))) { a ->
            ok(host.render {
                val m = model(a)
                val sk = m.skeleton ?: fail("Model '${m.name}' has no bones.")
                host.scene.update(0f)
                val frame = Math.round(host.scene.frame)
                val list: List<Int> = arrOf(a["bones"])?.map { bone(m, it) } ?: run {
                    val set = java.util.TreeSet<Int>()
                    for (r in KeyOps.rows(m)) if (r.kind == KeyOps.ROW_BONE) set.add(r.index)
                    for (b in KeyOps.posedBones(m)) set.add(b)
                    set.toList()
                }
                val t = FloatArray(3); val q = FloatArray(4); val e = FloatArray(3); val w = FloatArray(3)
                linkedMapOf("model" to m.id, "frame" to frame, "bones" to list.take(400).map { b ->
                    KeyOps.bonePose(m, b, frame, t, q)
                    Quat.toEulerYXZ(q, 0, e, 0)
                    m.boneWorldPosition(b, w, 0)
                    linkedMapOf("name" to sk.bones[b].name, "rot" to listOf(round(e[0] / deg), round(e[1] / deg), round(e[2] / deg)), "pos" to vecOut(t), "world" to vecOut(w), "keyed" to KeyOps.hasBoneKey(m, b, frame))
                })
            })
        }

        val keyItem = pObj(linkedMapOf(
            "bone" to pRef("Bone index or name."), "frame" to pInt("Frame number (30 fps)."),
            "rot" to pVec(3, "Rotation in Euler degrees [x,y,z] relative to rest. Omit to keep the value the bone already has at that frame."),
            "quat" to pVec(4, "Alternative to rot: quaternion [x,y,z,w]."),
            "pos" to pVec(3, "Translation [x,y,z] relative to rest (only for movable bones such as センター or the ＩＫ bones). Omit to keep."),
            "curve" to pStr("Easing of the segment arriving at this key: \"linear\" (default), \"ease_in\", \"ease_out\", \"ease_in_out\", or four bezier handle values 0..127 written as \"x1,y1,x2,y2\"."),
        ))
        tool("set_bone_keys", "Create or overwrite bone keyframes. Send many keys in one call (several bones x several frames). Values are relative to the rest pose, like VMD.",
            linkedMapOf("model" to pModel, "keys" to pArr(keyItem, "Keyframes to write."), "seek" to pInt("Afterwards jump to this frame (default: the last key's frame).")), listOf("keys")) { a ->
            val keys = arrOf(a["keys"]) ?: fail("Missing keys")
            ok(host.render {
                val m = model(a)
                host.setPlaying(false)
                var n = 0
                var last = 0
                val t = FloatArray(3); val q = FloatArray(4)
                host.beforeKeyEdit(m)
                for (k in keys) {
                    val o = k.jObj() ?: continue
                    val b = bone(m, o["bone"])
                    val frame = o.i("frame", -1)
                    if (frame < 0) fail("Every key needs a frame >= 0.")
                    // 没给的分量沿用这一帧上已有的（补间出来的）值
                    val track = m.motion?.trackOfBone(b)
                    t.fill(0f); Quat.identity(q, 0)
                    if (track != null && track.count > 0) track.sample(maxOf(0, frame - Math.round(m.motionOffset)).toFloat(), 0, t, 0, q, 0)
                    o.vec("pos", 3)?.let { System.arraycopy(it, 0, t, 0, 3) }
                    val qu = o.vec("quat", 4)
                    val rot = o.vec("rot", 3)
                    if (qu != null) System.arraycopy(qu, 0, q, 0, 4)
                    else if (rot != null) Quat.fromEulerYXZ(q, 0, rot[0] * deg, rot[1] * deg, rot[2] * deg)
                    KeyOps.setBoneKey(m, host.scene, b, frame, t[0], t[1], t[2], q[0], q[1], q[2], q[3], curveBytes(o["curve"]))
                    // 这根骨骼上还没登记的手动姿势会盖在动作上面，清掉才能看到刚写的关键帧
                    m.skeleton?.let { sk -> sk.userT[b * 3] = 0f; sk.userT[b * 3 + 1] = 0f; sk.userT[b * 3 + 2] = 0f; Quat.identity(sk.userR, b * 4) }
                    n++; last = frame
                }
                host.scene.seek(a.i("seek", last).toFloat(), true)
                host.changed()
                linkedMapOf("written" to n, "frame" to Math.round(host.scene.frame), "last_frame" to host.scene.maxFrame)
            })
        }

        tool("set_morph_keys", "Create or overwrite morph (expression) keyframes. Weights are 0..1 and interpolate linearly.",
            linkedMapOf("model" to pModel, "keys" to pArr(pObj(linkedMapOf("morph" to pRef("Morph index or name."), "frame" to pInt("Frame number."), "weight" to pNum("0..1"))), "Keyframes to write.")), listOf("keys")) { a ->
            val keys = arrOf(a["keys"]) ?: fail("Missing keys")
            ok(host.render {
                val m = model(a)
                var n = 0
                host.beforeKeyEdit(m)
                for (k in keys) {
                    val o = k.jObj() ?: continue
                    val frame = o.i("frame", -1)
                    if (frame < 0) fail("Every key needs a frame >= 0.")
                    KeyOps.setMorphKey(m, host.scene, morph(m, o["morph"]), frame, o.f("weight", 0f))
                    n++
                }
                host.changed()
                linkedMapOf("written" to n, "last_frame" to host.scene.maxFrame)
            })
        }

        tool("set_camera_keys", "Create or overwrite camera keyframes (camera animation). The camera is an orbit camera looking at `target` from `distance`.",
            linkedMapOf("keys" to pArr(pObj(linkedMapOf("frame" to pInt("Frame number."), "target" to pVec(3, "Point looked at."), "distance" to pNum("Distance from target."),
                "rotation" to pVec(3, "Degrees [x,y,z], same meaning as in set_camera (x<0 = from above)."), "fov" to pNum("Field of view in degrees."))), "Camera keys. Omitted fields take the current camera's value.")), listOf("keys")) { a ->
            val keys = arrOf(a["keys"]) ?: fail("Missing keys")
            ok(host.render {
                val s = host.scene
                val c = s.camera
                var n = 0
                host.beforeKeyEdit(null)
                for (k in keys) {
                    val o = k.jObj() ?: continue
                    val frame = o.i("frame", -1)
                    if (frame < 0) fail("Every key needs a frame >= 0.")
                    val tg = o.vec("target", 3) ?: c.target
                    val r = o.vec("rotation", 3)
                    KeyOps.setCameraKey(s, frame, o.f("distance", c.distance), tg[0], tg[1], tg[2],
                        if (r != null) r[0] * deg else c.rotX, if (r != null) r[1] * deg else c.rotY, if (r != null) r[2] * deg else c.rotZ, o.f("fov", c.fovDeg))
                    n++
                }
                host.changed()
                linkedMapOf("written" to n, "camera_keys" to (s.cameraMotion?.camera?.count ?: 0), "last_frame" to s.maxFrame)
            })
        }

        tool("get_keys", "List existing keyframes. Without `bone` / `morph` it returns which tracks have keys and on which frames; with one of them it returns that track's values.",
            linkedMapOf("model" to pModel, "bone" to pRef("Return this bone's keys with values."), "morph" to pRef("Return this morph's keys with values."), "camera" to pBool("Return camera keys instead."))) { a ->
            ok(host.render {
                val s = host.scene
                if (a.b("camera", false)) {
                    val t = s.cameraMotion?.camera
                    return@render linkedMapOf("camera_keys" to (0 until (t?.count ?: 0)).map { i ->
                        val v = t!!.values; val o = i * 8
                        linkedMapOf("frame" to t.frames[i], "distance" to round(-v[o]), "target" to vecOut(v, o + 1), "rotation" to listOf(round(v[o + 4] / deg), round(v[o + 5] / deg), round(v[o + 6] / deg)), "fov" to round(v[o + 7]))
                    })
                }
                val m = model(a)
                val off = Math.round(m.motionOffset)
                if (a["bone"] != null) {
                    val b = bone(m, a["bone"])
                    val t = m.motion?.trackOfBone(b)
                    val e = FloatArray(3)
                    return@render linkedMapOf("bone" to m.skeleton!!.bones[b].name, "keys" to (0 until (t?.count ?: 0)).take(2000).map { i ->
                        val v = t!!.values; val o = i * 7
                        Quat.toEulerYXZ(v, o + 3, e, 0)
                        linkedMapOf("frame" to t.frames[i] + off, "rot" to listOf(round(e[0] / deg), round(e[1] / deg), round(e[2] / deg)), "pos" to vecOut(v, o))
                    })
                }
                if (a["morph"] != null) {
                    val mi = morph(m, a["morph"])
                    val t = m.motion?.trackOfMorph(mi)
                    return@render linkedMapOf("morph" to m.data.morphs[mi].name, "format" to "[frame, weight]", "keys" to (0 until (t?.count ?: 0)).take(5000).map { i -> listOf(t!!.frames[i] + off, round(t.weights[i])) })
                }
                val rows = KeyOps.rows(m)
                linkedMapOf("model" to m.id, "last_frame" to s.maxFrame,
                    "bones" to rows.filter { it.kind == KeyOps.ROW_BONE }.take(400).map { linkedMapOf("name" to it.name, "count" to it.frames.size, "frames" to it.frames.take(60)) },
                    "morphs" to rows.filter { it.kind == KeyOps.ROW_MORPH }.take(200).map { linkedMapOf("name" to it.name, "count" to it.frames.size, "frames" to it.frames.take(60)) },
                    "camera_keys" to KeyOps.cameraKeyFrames(s).take(200))
            })
        }

        tool("delete_keys", "Delete keyframes. Give `frame` (one frame) or `from` / `to` (range). Limit to some bones / morphs, or the camera; otherwise every track of the model is affected. all=true wipes the model's whole animation.",
            linkedMapOf("model" to pModel, "frame" to pInt("Single frame."), "from" to pInt("Range start."), "to" to pInt("Range end (inclusive)."),
                "bones" to pArr(pRef("Bone index or name."), "Only these bones."), "morphs" to pArr(pRef("Morph index or name."), "Only these morphs."),
                "camera" to pBool("Delete camera keys instead of model keys."), "all" to pBool("Remove all keys of the model."))) { a ->
            ok(host.render {
                val s = host.scene
                val from = if (a["frame"] is Number) a.i("frame", 0) else a.i("from", 0)
                val to = if (a["frame"] is Number) a.i("frame", 0) else a.i("to", Int.MAX_VALUE / 2)
                var n = 0
                if (a.b("camera", false)) {
                    host.beforeKeyEdit(null)
                    for (f in KeyOps.cameraKeyFrames(s)) if (f in from..to && KeyOps.deleteCameraKey(s, f)) n++
                } else {
                    val m = model(a)
                    host.beforeKeyEdit(m)
                    if (a.b("all", false)) { n = m.motion?.motion?.keyCount ?: 0; KeyOps.clearAll(m, s) }
                    else {
                        val bones = arrOf(a["bones"])?.map { bone(m, it) }
                        val morphs = arrOf(a["morphs"])?.map { morph(m, it) }
                        if (bones == null && morphs == null) n = KeyOps.deleteRange(m, s, from, to)
                        else {
                            bones?.forEach { b -> for (f in KeyOps.boneKeyFrames(m, b)) if (f in from..to && KeyOps.deleteBoneKey(m, s, b, f)) n++ }
                            morphs?.forEach { mi -> for (f in KeyOps.morphKeyFrames(m, mi)) if (f in from..to && KeyOps.deleteMorphKey(m, s, mi, f)) n++ }
                        }
                    }
                }
                host.changed()
                linkedMapOf("deleted" to n, "last_frame" to s.maxFrame)
            })
        }

        tool("pose_bones", "Pose bones immediately WITHOUT keyframes (a static pose layered on top of any animation). Use set_bone_keys to animate. reset=true clears the manual pose first.",
            linkedMapOf("model" to pModel, "reset" to pBool("Clear all manual posing (and manual morph weights) first."),
                "bones" to pArr(pObj(linkedMapOf("bone" to pRef("Bone index or name."), "rot" to pVec(3, "Euler degrees [x,y,z]."), "pos" to pVec(3, "Translation."))), "Bones to pose."),
                "morphs" to pArr(pObj(linkedMapOf("morph" to pRef("Morph index or name."), "weight" to pNum("0..1"))), "Morph weights to set."))) { a ->
            ok(host.render {
                val m = model(a)
                if (a.b("reset", false)) m.resetPose()
                var n = 0
                val sk = m.skeleton
                arrOf(a["bones"])?.forEach { k ->
                    val o = k.jObj() ?: return@forEach
                    if (sk == null) fail("Model '${m.name}' has no bones.")
                    val b = bone(m, o["bone"])
                    o.vec("rot", 3)?.let { Quat.fromEulerYXZ(sk.userR, b * 4, it[0] * deg, it[1] * deg, it[2] * deg) }
                    o.vec("pos", 3)?.let { System.arraycopy(it, 0, sk.userT, b * 3, 3) }
                    sk.anyUserPose = true
                    n++
                }
                arrOf(a["morphs"])?.forEach { k ->
                    val o = k.jObj() ?: return@forEach
                    val mi = morph(m, o["morph"])
                    m.morphs.userWeights[mi] = o.f("weight", 0f) - m.morphs.animWeights[mi]
                    n++
                }
                m.poseDirty = true
                host.changed()
                linkedMapOf("posed" to n)
            })
        }

        tool("timeline", "Control playback: seek to a frame, play or pause.",
            linkedMapOf("action" to pEnum("What to do.", "seek", "play", "pause"), "frame" to pInt("Frame for seek, or the frame to start playing from.")), listOf("action")) { a ->
            ok(host.render {
                val s = host.scene
                when (a.s("action")) {
                    "seek" -> { host.setPlaying(false); s.seek(a.i("frame", 0).toFloat(), true) }
                    "play" -> { if (a["frame"] is Number) s.seek(a.i("frame", 0).toFloat()); host.setPlaying(true) }
                    "pause" -> host.setPlaying(false)
                    else -> fail("action must be seek, play or pause")
                }
                host.changed()
                linkedMapOf("frame" to Math.round(s.frame), "last_frame" to s.maxFrame, "playing" to s.playing)
            })
        }

        tool("load_motion", "Apply a motion (.vmd) or pose (.vpd) file from the library to a model. Camera motions are applied to the scene camera.",
            linkedMapOf("path" to pStr("Library path from list_library."), "model" to pModel, "merge" to pBool("Merge into the model's current motion instead of replacing it.")), listOf("path")) { a ->
            val path = a.s("path") ?: fail("Missing path")
            val file = host.resolve(path) ?: fail("File not found in the library: $path")
            if (file.name.endsWith(".vpd", true)) {
                val pose = com.lumi.mmd.engine.format.VpdReader.read(file.readBytes())
                return@tool ok(host.render { val m = model(a); val n = SceneOps.applyPose(m, pose); host.changed(); linkedMapOf("posed_bones" to n) })
            }
            val motion = ModelLoader.loadMotion(file)
            ok(host.render {
                val s = host.scene
                if (motion.hasCamera && !motion.hasModelMotion) {
                    s.cameraMotion = motion; s.useCameraMotion = true; s.recomputeDuration()
                    host.changed()
                    return@render linkedMapOf("camera_keys" to (motion.camera?.count ?: 0), "last_frame" to s.maxFrame)
                }
                val m = model(a)
                if (a.b("merge", false) && m.motion != null) { m.editableMotion().mergeFrom(motion); m.rebindMotion() }
                else { m.setMotion(motion); m.motionSources = listOf(file.absolutePath) }
                s.recomputeDuration()
                host.changed()
                linkedMapOf("model" to m.id, "matched_bones" to (m.motion?.matchedBones ?: 0), "of" to motion.boneTracks.size, "matched_morphs" to (m.motion?.matchedMorphs ?: 0), "last_frame" to s.maxFrame)
            })
        }

        tool("export_vmd", "Save a model's animation (or the camera animation) as a .vmd file in the library, usable in MikuMikuDance and other tools.",
            linkedMapOf("model" to pModel, "camera" to pBool("Export the camera animation instead."), "name" to pStr("File name without extension."))) { a ->
            var name = a.s("name")?.takeIf { it.isNotBlank() }
            val bytes = host.render {
                if (a.b("camera", false)) {
                    val cm = host.scene.cameraMotion ?: fail("There is no camera animation.")
                    if (name == null) name = "camera"
                    VmdWriter.write(cm, KeyOps.CAMERA_MODEL_NAME)
                } else {
                    val m = model(a)
                    val mo = m.motion?.motion ?: fail("Model '${m.name}' has no animation.")
                    if (name == null) name = m.name
                    VmdWriter.write(mo, m.name)
                }
            }
            ok(linkedMapOf("path" to host.saveMotion(name!!, bytes), "bytes" to bytes.size))
        }
    }
}

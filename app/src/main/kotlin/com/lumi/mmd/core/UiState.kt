package com.lumi.mmd.core

/** 界面用的只读快照。渲染线程生成，界面线程读取。 */
data class ModelUi(
    val id: Int,
    val name: String,
    val kind: Int,
    val visible: Boolean,
    val summary: String,
    val skinned: Boolean,
    val hasPhysics: Boolean,
    val physics: Boolean,
    val edge: Boolean,
    val castShadow: Boolean,
    val opacity: Float,
    val scale: Float,
    val position: List<Float>,
    val rotation: List<Float>,
    val motion: String,
    val animations: List<String>,
    val attachTo: Int,
    val attachBone: String,
    val warnings: List<String>,
    /** 「动作」槽里的文件名（没有动作时为空；在应用里做的动作显示成"自制"）。 */
    val motionFile: String = "",
    /** 「表情」槽里的文件名（叠在动作上面的表情 / 口型 VMD）。 */
    val morphFile: String = "",
    /** 还没找到的贴图数量。 */
    val missingTextures: Int = 0,
    /** 动作从第几帧开始播（排布用）。 */
    val motionOffset: Int = 0,
    /** 画在第几层（大的盖在小的上面）。 */
    val layer: Int = 0,
    /** 碰撞属性：0 没有 / 1 包围盒 / 2 按网格。 */
    val collision: Int = 0,
    /** 这个模型有自己的一套物理倍数。 */
    val ownPhysics: Map<String, Float> = emptyMap(),
    val bodies: Int = 0,
    /** 脚本临时创建的（不存进工程）。 */
    val transient: Boolean = false,
    /** 能不能用操控模式（有标准的腿部骨骼）。 */
    val controllable: Boolean = false,
)

/** 场景里的一个 Lua 脚本。 */
data class ScriptUi(
    val id: Int, val name: String, val enabled: Boolean, val running: Boolean, val error: String,
    /** 作用于哪个模型（0 = 当前选中的角色）。 */
    val target: Int, val start: Int, val end: Int, val lines: Int,
)

/** 轨道上的一个片段。 */
data class ClipUi(
    val id: Int, val name: String, val start: Int, val end: Int, val trimIn: Int, val sourceFrames: Int, val edited: Boolean, val tag: String = "",
    /** 和前一段之间的过渡有多少帧（0 = 直接切换）。 */
    val fade: Int = 0,
    /** 前面有没有可以过渡的片段。 */
    val canFade: Boolean = false,
)

/** 片段视图里的一条轨道。kind 见 [Lanes]。id：模型序号 / 脚本序号。 */
data class LaneUi(val kind: Int, val id: Int, val name: String, val clips: List<ClipUi>)

object Lanes {
    const val CAMERA = 0
    const val MODEL = 1
    const val AUDIO = 2
    const val SCRIPT = 3
}

/** 操控模式的状态。 */
data class ControlUi(
    val on: Boolean = false, val model: Int = 0,
    /** 0 站 1 蹲 2 趴。 */
    val stance: Int = 0,
    val state: String = "", val sprintLock: Boolean = false,
    /** 这个模型有没有走跑的动画（没有标准腿部骨骼的只能平移）。 */
    val animated: Boolean = true,
)

/** 脚本放在屏幕上的文字 / 按钮。x、y 是 0..1 的屏幕位置。 */
data class OverlayItem(val key: String, val id: String, val text: String, val x: Float, val y: Float, val size: Float, val color: Int, val button: Boolean)

/** 第四版新增的那部分界面状态：脚本、片段轨道、操控、物理参数。 */
data class PlayUi(
    val scripts: List<ScriptUi> = emptyList(),
    val lanes: List<LaneUi> = emptyList(),
    /** 选中的片段：轨道种类、轨道 id、片段 id（没有选中时 clip = 0）。 */
    val clipLane: Int = -1, val clipLaneId: Int = 0, val clip: Int = 0,
    val control: ControlUi = ControlUi(),
    /** 全局物理参数（参数名 → 值）。 */
    val physics: Map<String, Float> = emptyMap(),
    val particles: Int = 0,
    val consoleActive: Boolean = false,
)

/** 添加文件时让它落在哪个槽里。AUTO = 按文件内容自动安排。 */
object Slot {
    const val AUTO = 0
    const val MODEL = 1
    const val STAGE = 2
    const val MOTION = 3
    const val MORPH = 4
    const val CAMERA = 5
    const val MUSIC = 6
    const val POSE = 7
    /** 作为新的一段接在轨道最后（而不是换掉当前那段）。 */
    const val CLIP = 8
    const val SCRIPT = 9
}

/** 模型的贴图没有跟着一起导入：请用户选一种办法把它们找回来。 */
data class TexturePrompt(
    val path: String,
    val name: String,
    val missing: List<String>,
    /** 文件夹选择器一打开停在哪（推不出来为 null）。 */
    val folderHint: android.net.Uri? = null,
    /** 已经有存储权限，但推不出这个文件原来在哪：只能选文件夹。 */
    val folderOnly: Boolean = false,
)

/** 一次导入里有好几个模型：让用户挑要加载哪一个。 */
data class ModelChoice(val items: List<Pair<String, String>>, val title: String)

data class PartUi(
    val key: String,
    val name: String,
    val material: Int,
    val island: Int,
    val triangles: Int,
    val visible: Boolean,
    val edited: Boolean,
    val islandCount: Int,
    /** 位移 xyz。 */
    val offset: List<Float> = listOf(0f, 0f, 0f),
    /** 旋转（度）：绕 X、绕 Y、绕 Z。 */
    val rotation: List<Float> = listOf(0f, 0f, 0f),
    /** 缩放 xyz。 */
    val scale: List<Float> = listOf(1f, 1f, 1f),
)

data class MorphUi(val index: Int, val name: String, val panel: Int, val weight: Float)

data class BoneUi(val index: Int, val name: String, val ik: Boolean, val movable: Boolean)

/** 选中模型的明细（部件 / 表情 / 骨骼列表），打开对应面板时才生成。 */
data class DetailUi(
    val modelId: Int = -1,
    val parts: List<PartUi> = emptyList(),
    /** 被删掉的部件有几个（不在 parts 里；可以一键恢复）。 */
    val deletedParts: Int = 0,
    /** 「只看它」正开着：除了这一个，别的整块部件都被临时隐藏了。 */
    val soloPart: String = "",
    val morphs: List<MorphUi> = emptyList(),
    val bones: List<BoneUi> = emptyList(),
)

data class UiState(
    val models: List<ModelUi> = emptyList(),
    val selectedModel: Int = -1,
    val selectedPart: String = "",
    val selectedPartName: String = "",
    val selectedBone: Int = -1,
    val selectedBoneName: String = "",
    val mode: Int = 0,
    val tool: Int = 0,
    val axis: Int = 0,
    val islandMode: Boolean = false,
    val lockDrag: Boolean = false,
    val canUndo: Boolean = false,
    val loop: Boolean = true,
    val speed: Float = 1f,
    val physics: Boolean = true,
    val hasCameraMotion: Boolean = false,
    val useCameraMotion: Boolean = true,
    val cameraMotionName: String = "",
    /** 「相机」槽里的文件名（在应用里登记的镜头关键帧显示成"自制"）。 */
    val cameraFile: String = "",
    val audioName: String = "",
    /** 音乐的长度（帧）。 */
    val audioFrames: Int = 0,
    val play: PlayUi = PlayUi(),
    /** 操纵轴：true = 局部（跟着对象自己的朝向），false = 全局。 */
    val localAxes: Boolean = true,
    /** 自动关键帧：摆完骨骼 / 调完表情自动登记到当前帧。 */
    val autoKey: Boolean = false,
    val canRedo: Boolean = false,
    /** 还没登记成关键帧的手动姿势（骨骼数 + 表情数）。 */
    val pending: Int = 0,
    /** 剪贴板里的关键帧数。 */
    val clipboard: Int = 0,
    /** 选中的骨骼是否可以移动 / 旋转。 */
    val boneMovable: Boolean = false,
    val boneRotatable: Boolean = true,
    /** 镜头正由镜头关键帧驱动。 */
    val cameraDriven: Boolean = false,
    val cameraKeys: Int = 0,
    val floor: FloorUi = FloorUi(),
    val style: StyleUi = StyleUi(),
)

/** 时间轴上的一行。 */
class TrackUi(val kind: Int, val index: Int, val name: String, val frames: IntArray)

/** 时间轴数据：选中模型里所有带关键帧的轨道 + 镜头关键帧。version 变了才需要重画。 */
class TimelineUi(val modelId: Int = -1, val modelName: String = "", val tracks: List<TrackUi> = emptyList(), val camera: IntArray = IntArray(0), val version: Int = 0)

data class MeshMaterialUi(val index: Int, val name: String, val color: Int, val faces: Int)

/** 建模状态。 */
data class MeshUi(
    val active: Boolean = false,
    val modelId: Int = -1,
    val selMode: Int = 2,
    val additive: Boolean = true,
    val boxSelect: Boolean = false,
    val points: Int = 0,
    val faces: Int = 0,
    val selPoints: Int = 0,
    val selEdges: Int = 0,
    val selFaces: Int = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val materials: List<MeshMaterialUi> = emptyList(),
    val bones: List<String> = emptyList(),
)

/** MCP 服务状态。 */
data class McpUi(
    val running: Boolean = false,
    val urls: List<String> = emptyList(),
    val legacyUrls: List<String> = emptyList(),
    val calls: Int = 0,
    val lastCall: String = "",
    val error: String = "",
    /** 一共收到过多少条请求（握手、列工具、调工具都算）。 */
    val requests: Int = 0,
    /** 最近一条请求的方法名，和服务端从收到到答完用了多少毫秒。 */
    val lastRequest: String = "",
    val lastRequestMs: Float = 0f,
    /** 前台服务（通知栏那一条）在不在：不在的话切到后台以后系统可能把应用冻住。 */
    val keepAlive: Boolean = false,
    /** 系统有没有把本应用放进「不限制后台」的名单。 */
    val unrestricted: Boolean = true,
)

/** 地板（场景里的那块默认地面）。 */
data class FloorUi(
    val enabled: Boolean = true,
    val height: Float = 0f,
    val grid: Boolean = true,
    val shadow: Boolean = true,
    val collide: Boolean = true,
    val cell: Float = 5f,
    /** 底色（a = 0 是透明的地板）。 */
    val color: List<Float> = listOf(0.55f, 0.56f, 0.62f, 0f),
)

/** 画风（跟着工程保存的那一组渲染设置）。 */
data class StyleUi(
    /** 三渲二的光照级数；0 = 关。 */
    val cel: Int = 0,
    val edgeAll: Boolean = false,
    val rim: Float = 0f,
    /** 有没有哪一项不是默认值（脚本 / AI 改过）。 */
    val custom: Boolean = false,
)

/** 播放进度等高频变化的数据，单独一个流，避免整个界面跟着重组。 */
data class PlaybackUi(
    val playing: Boolean = false,
    val frame: Float = 0f,
    val maxFrame: Int = 0,
    val fps: Int = 0,
    val drawCalls: Int = 0,
    val triangles: Int = 0,
    val pendingTextures: Int = 0,
    /** 正在拖动操纵器时的读数，如"旋转 X 23.5°"；没有在拖动时为空。 */
    val dragText: String = "",
    /** 当前帧上：选中的骨骼有关键帧 / 任意轨道有关键帧 / 镜头有关键帧。 */
    val boneKeyed: Boolean = false,
    val frameKeyed: Boolean = false,
    val cameraKeyed: Boolean = false,
)

data class RenderPrefs(
    val msaa: Int = 4,
    val renderScale: Float = 1f,
    val shadows: Boolean = true,
    val shadowSize: Int = 2048,
    val edges: Boolean = true,
    val edgeWidth: Float = 1f,
    val grid: Boolean = true,
    val groundShadow: Boolean = true,
    val sdef: Boolean = true,
    val bloom: Boolean = false,
    val bloomStrength: Float = 0.6f,
    val exposure: Float = 1f,
    val saturation: Float = 1f,
    val rim: Float = 0f,
    val vignette: Float = 0f,
    val maxTexture: Int = 2048,
    val physicsHz: Int = 60,
    val background: Int = 0,
    val lightX: Float = -0.5f,
    val lightY: Float = -1f,
    val lightZ: Float = 0.5f,
    val lightIntensity: Float = 0.6f,
    val fov: Float = 30f,
    val showFps: Boolean = true,
    val stageLight: Boolean = true,
    /** 编辑面板用深色（默认是白底紫罗兰）。 */
    val darkUi: Boolean = false,
)

/** 背景预设：上、下两种颜色。 */
object Backgrounds {
    // 第 0 个是默认的：以前是深色的「紫夜」，现在是浅色的「浅紫」（紫夜挪到了最后）。
    // 这样没有特意换过背景的人，升级之后跟着变成浅色；特意选了别的背景的人不受影响。
    val names = listOf("浅紫", "灰", "白", "黑", "天蓝", "暖阳", "绿幕", "紫夜")
    val top = listOf(
        floatArrayOf(0.886f, 0.858f, 0.992f), floatArrayOf(0.42f, 0.43f, 0.45f), floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 0f, 0f),
        floatArrayOf(0.36f, 0.62f, 0.92f), floatArrayOf(0.98f, 0.78f, 0.56f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0.185f, 0.145f, 0.30f))
    val bottom = listOf(
        floatArrayOf(0.976f, 0.968f, 1f), floatArrayOf(0.26f, 0.27f, 0.29f), floatArrayOf(0.92f, 0.93f, 0.95f), floatArrayOf(0f, 0f, 0f),
        floatArrayOf(0.82f, 0.90f, 0.98f), floatArrayOf(0.96f, 0.90f, 0.82f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0.055f, 0.045f, 0.10f))

    /** 这个背景是不是亮的（压在画面上的字据此用深色还是白色）。 */
    fun isLight(index: Int): Boolean {
        val i = index.coerceIn(0, names.size - 1)
        val t = top[i]; val b = bottom[i]
        val lum = 0.2126f * (t[0] + b[0]) / 2f + 0.7152f * (t[1] + b[1]) / 2f + 0.0722f * (t[2] + b[2]) / 2f
        return lum > 0.6f
    }
}

/** 界面上的工作模式。前五个和引擎编辑器的模式一一对应；「表情」「镜头」是界面自己的模式（此时画面里单指拖动是转视角）。 */
object Modes {
    const val VIEW = 0
    const val MODEL = 1
    const val PART = 2
    const val BONE = 3
    const val MESH = 4
    const val MORPH = 5
    const val CAMERA = 6
    /** 片段：在轨道上摆放动作、镜头、音乐、脚本（画面保持浏览状态）。 */
    const val CLIPS = 7
}

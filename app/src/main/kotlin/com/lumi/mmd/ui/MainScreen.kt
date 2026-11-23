package com.lumi.mmd.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lumi.mmd.core.McpUi
import com.lumi.mmd.core.MeshUi
import com.lumi.mmd.core.ModelChoice
import com.lumi.mmd.core.Modes
import com.lumi.mmd.core.PlaybackUi
import com.lumi.mmd.core.SceneController
import com.lumi.mmd.core.Slot
import com.lumi.mmd.core.SourceAccess
import com.lumi.mmd.core.TexturePrompt
import com.lumi.mmd.core.UiState
import com.lumi.mmd.engine.scene.Editor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 压在三维画面上的字：白色，靠上下两条很淡的渐变和文字阴影保证在亮背景上也看得清。 */
val SceneInk = Ink(
    paper = Color(0x00000000), text = Color.White, muted = Color(0xC2F1ECFF), faint = Color(0x66F1ECFF),
    line = Color(0x59E3D9FF), well = Color(0x33C7B6FF), accent = Color(0xFFCBBBFF), onAccent = Color(0xFF1A1230),
    warn = Color(0xFFF5B544), danger = Color(0xFFFF6F66), light = false,
)

/** 背景是亮的时候压在画面上的字：深紫色，靠一圈白色的光晕和画面隔开。 */
val SceneInkDay = Ink(
    paper = Color(0x00000000), text = Color(0xFF241B3D), muted = Color(0xC2372C59), faint = Color(0x73372C59),
    line = Color(0x402B1A66), well = Color(0x1F5B3FD0), accent = Violet, onAccent = Color.White,
    warn = Color(0xFFB25E09), danger = Color(0xFFD23A2E), light = true,
)

internal val SceneShadow = Shadow(Color(0xB30B0618), Offset(0f, 1f), 5f)
internal val SceneGlow = Shadow(Color(0xF2FFFFFF), Offset(0f, 0f), 8f)

/** 压在画面上的字的阴影：暗背景是一点黑影，亮背景是一圈白晕。 */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun sceneShadow(): Shadow = if (SceneTone.light) SceneGlow else SceneShadow

private class ModeDef(val id: Int, val label: String, val about: String)

/** 编辑工具。说明文字显示在菜单的「编辑」里。 */
internal val EDIT_MODES: List<Triple<Int, String, String>> = listOf(
    Triple(Modes.CLIPS, "轨道", "像剪辑软件那样排动作、镜头、音乐、脚本"),
    Triple(Modes.MODEL, "模型", "移动、旋转、缩放整个模型"),
    Triple(Modes.BONE, "骨骼", "摆姿势，打关键帧做动画"),
    Triple(Modes.MORPH, "表情", "拖表情滑杆，打关键帧"),
    Triple(Modes.CAMERA, "镜头", "摆好视角，登记镜头关键帧"),
    Triple(Modes.PART, "部件", "隐藏或调整模型的某一部分"),
    Triple(Modes.MESH, "建模", "改模型的点线面，或从零新建"),
)

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * 主界面。写法参照 Reze Design：
 *  - 平时画面占满整个屏幕，上面只有一行字（菜单、场景名、「添加」），下面只有一条播放线；
 *  - 所有设置都收在左上角的菜单里：一张纸面卡片，里面是一行行的列表，点一行往里进一层；
 *  - 要摆姿势、打关键帧、建模时才进「编辑」，这时才出现工具面板和时间轴，点「完成」回到干净的画面。
 * 横屏时卡片和工具面板靠边放，竖屏时放在下半屏，模型始终露在外面。
 *
 * @param glView 渲染视图。这里只把它当作普通的 View 放在最底层，方便在没有 GPU 的环境（界面测试）里换成占位视图。
 */
@Composable
fun MainScreen(controller: SceneController, glView: View) {
    val state by controller.state.collectAsState()
    val play by controller.playback.collectAsState()
    val prefs by controller.prefs.collectAsState()
    SceneTone.light = com.lumi.mmd.core.Backgrounds.isLight(prefs.background)
    val loading by controller.loading.collectAsState()
    val meshUi by controller.mesh.collectAsState()
    val mcp by controller.mcp.collectAsState()
    val tutorial by controller.tutorial.collectAsState()
    val orientation by controller.orientation.collectAsState()
    val texturePrompt by controller.texturePrompt.collectAsState()
    val modelChoice by controller.modelChoice.collectAsState()
    /** 菜单卡片停在哪一层：null = 没打开，"" = 第一层。 */
    var route by remember { mutableStateOf<String?>(null) }
    var hideUi by remember { mutableStateOf(false) }
    var showTimeline by rememberSaveable { mutableStateOf(false) }
    var showPanel by rememberSaveable { mutableStateOf(true) }
    /** 底下的播放条平时收着，只留一颗播放钮；点一下才展开成带进度线的一整条。 */
    var barOpen by rememberSaveable { mutableStateOf(false) }
    val overlay by controller.play.overlay.collectAsState()
    val control = state.play.control
    val context = LocalContext.current
    val view = LocalView.current

    // 播放时、AI 通过 MCP 操作时保持屏幕常亮
    LaunchedEffect(play.playing, mcp.running, control.on) { view.keepScreenOn = play.playing || mcp.running || control.on }
    // 屏幕的刷新率：渲染那边用它把每帧的时间步对齐到整数个刷新周期
    LaunchedEffect(Unit) {
        val rate = try { (if (android.os.Build.VERSION.SDK_INT >= 30) context.display else @Suppress("DEPRECATION") context.findActivity()?.windowManager?.defaultDisplay)?.refreshRate } catch (_: Throwable) { null }
        if (rate != null && rate >= 30f) controller.refreshRate = rate
    }
    LaunchedEffect(orientation) {
        context.findActivity()?.requestedOrientation = when (orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }
    LaunchedEffect(Unit) {
        // 消息是从渲染 / 加载线程发出来的；Toast 只能在主线程弹，这里明确切到主线程收
        withContext(Dispatchers.Main.immediate) {
            controller.messages.collect { Toast.makeText(context, it, if (it.length > 24) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
        }
    }
    val bench = state.mode != Modes.VIEW
    BackHandler(enabled = route != null || hideUi || bench || control.on || barOpen) {
        when {
            route != null -> route = if (route.isNullOrEmpty()) null else parentRoute(route!!)
            hideUi -> hideUi = false
            bench -> controller.setMode(Modes.VIEW)
            control.on -> controller.play.setControl(false)
            else -> barOpen = false
        }
    }

    // ---- 系统的文件选择器 ----
    var pickSlot by remember { mutableIntStateOf(Slot.AUTO) }
    var pickTarget by remember { mutableIntStateOf(-1) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) controller.addFiles(uris, slot = pickSlot, targetId = pickTarget)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) controller.addFiles(listOf(uri), tree = true)
    }
    val textureFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) controller.texturesFromFolder(uri)
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) controller.retryTextures() else controller.toast("没有给权限。可以改用「选择模型所在的文件夹」")
    }
    val settingsPage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { controller.retryTextures() }
    val pick: (Int, Int) -> Unit = { slot, target ->
        pickSlot = slot; pickTarget = target
        try { pickFiles.launch(arrayOf("*/*")) } catch (e: Exception) { controller.toast("无法打开系统的文件选择器") }
    }
    val pickDir: () -> Unit = { try { pickFolder.launch(null) } catch (e: Exception) { controller.toast("无法打开系统的文件夹选择器") } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
        BoxSelectOverlay(controller)

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight
            val fullHeight = maxHeight
            // 压在画面上的字：画面背景亮就用深紫色，暗就用白色
            val sceneInk = if (SceneTone.light) SceneInkDay else SceneInk
            when {
                hideUi -> InkTheme(sceneInk) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                            BareIcon(if (play.playing) LumiIcons.Pause else LumiIcons.Play, if (play.playing) "暂停" else "播放", size = 44.dp) { controller.togglePlay() }
                            BareIcon(LumiIcons.Fullscreen, "显示界面", size = 44.dp) { hideUi = false }
                        }
                    }
                }
                !bench && control.on && route == null -> InkTheme(sceneInk) {
                    ControlLayer(controller, control, state.models.firstOrNull { it.id == control.model }?.name ?: "", wide)
                }
                !bench -> InkTheme(sceneInk) {
                    HomeChrome(
                        controller, state, play, wide, showFps = prefs.showFps, mcp = mcp, barOpen = barOpen, onBar = { barOpen = it },
                        empty = state.models.isEmpty() && loading.isEmpty() && route == null && !tutorial,
                        onMenu = { route = "" }, onAdd = { pick(Slot.AUTO, -1) }, onHide = { hideUi = true },
                        onEdit = {
                            when {
                                state.models.isNotEmpty() -> controller.setMode(if (state.models.any { it.skinned }) Modes.BONE else Modes.MODEL)
                                // 还没有模型，但轨道上已经有东西了（音乐、镜头、脚本）：直接进轨道
                                state.play.lanes.any { it.clips.isNotEmpty() } -> controller.setMode(Modes.CLIPS)
                                else -> controller.toast("先点右上角的「添加」放一个模型进来（想从零建模：菜单 → 角色 → 新建模型）")
                            }
                        },
                    )
                }
                else -> InkTheme(if (prefs.darkUi) BenchInk else PaperInk) {
                    BenchChrome(
                        controller, state, play, meshUi, wide, panelHeight = fullHeight * 0.36f, showFps = prefs.showFps, mcp = mcp,
                        showTimeline = showTimeline, showPanel = showPanel, onTimeline = { showTimeline = !showTimeline }, onPanel = { showPanel = !showPanel },
                        onMenu = { route = it }, onPick = pick, tracksHeight = if (wide) (fullHeight * 0.44f).coerceAtLeast(150.dp) else fullHeight * 0.40f,
                    )
                }
            }

            // 脚本放在屏幕上的字幕和按钮（编辑时不显示，免得盖住面板）
            if (!bench) ScriptOverlay(controller, overlay)

            // 菜单卡片
            val r = route
            if (r != null) {
                MenuCard(
                    controller, state, play, prefs, mcp, route = r, wide = wide, onRoute = { route = it },
                    onPick = pick, onPickFolder = pickDir,
                )
            }
        }

        texturePrompt?.let { p ->
            TextureDialog(
                p,
                onGrant = {
                    try {
                        if (SourceAccess.needsSettingsPage) {
                            try { settingsPage.launch(SourceAccess.allFilesSettings(context)) }
                            catch (e: Exception) { settingsPage.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                        } else storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    } catch (e: Exception) { controller.toast("打不开权限设置。请改用「选择模型所在的文件夹」") }
                },
                onFolder = { try { textureFolder.launch(p.folderHint) } catch (e: Exception) { controller.toast("无法打开系统的文件夹选择器") } },
                onDismiss = { controller.dismissTexturePrompt() },
            )
        }
        modelChoice?.let { ModelChoiceDialog(it) { path -> controller.chooseModel(path) } }

        if (loading.isNotEmpty()) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 52.dp), contentAlignment = Alignment.TopCenter) {
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0xEB1A1230)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 1.5.dp)
                    HSpace(9)
                    Text(loading, fontSize = 12.5.sp, color = Color.White, maxLines = 2, modifier = Modifier.widthIn(max = 260.dp))
                }
            }
        }

        if (tutorial) Tutorial(onDone = { controller.showTutorial(false) })
    }
}

internal fun parentRoute(route: String): String? = if (route.isEmpty()) null else route.substringBeforeLast('/', "")

// ============================================================================ 平时：干净的画面

@Composable
private fun HomeChrome(
    controller: SceneController, state: UiState, play: PlaybackUi, wide: Boolean, showFps: Boolean, mcp: McpUi, empty: Boolean,
    barOpen: Boolean, onBar: (Boolean) -> Unit, onMenu: () -> Unit, onAdd: () -> Unit, onHide: () -> Unit, onEdit: () -> Unit,
) {
    val lead = state.models.firstOrNull { it.id == state.selectedModel } ?: state.models.firstOrNull()
    val shadowed = TextStyle(shadow = sceneShadow())
    Box(Modifier.fillMaxSize()) {
        // 顶上一条很淡的渐变压住标题；底下那条只在播放条展开时才有
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(96.dp).background(Brush.verticalGradient(listOf(onScene(Color(0x800B0618), Color(0xB3FBF9FF)), Color.Transparent))))
        if (barOpen) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(116.dp).background(Brush.verticalGradient(listOf(Color.Transparent, onScene(Color(0x8C0B0618), Color(0xCCFBF9FF))))))
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 10.dp, top = 2.dp), verticalAlignment = Alignment.Top) {
                BareIcon(LumiIcons.Menu, "菜单", size = 46.dp, iconSize = 20.dp, onClick = onMenu)
                Column(Modifier.weight(1f).padding(top = 8.dp, start = 2.dp, end = 8.dp)) {
                    Text(lead?.name ?: "Lumi MMD", fontSize = 19.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, style = shadowed)
                    val facts = ArrayList<String>()
                    if (state.models.size > 1) facts.add("${state.models.size} 个模型")
                    lead?.motionFile?.takeIf { it.isNotEmpty() }?.let { facts.add(it.substringBeforeLast('.')) }
                    if (state.audioName.isNotEmpty()) facts.add("♪ " + state.audioName.substringBeforeLast('.'))
                    val running = state.play.scripts.count { it.running }
                    if (running > 0) facts.add("$running 个脚本")
                    if (showFps && state.models.isNotEmpty()) facts.add("${play.fps} FPS")
                    if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), fontSize = 10.5.sp, color = ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = shadowed)
                    if (mcp.running) Text(if (mcp.calls > 0) "AI：${mcp.lastCall}（${mcp.calls}）" else "AI 操作已开启", fontSize = 10.5.sp, color = ink.accent, maxLines = 1, style = shadowed)
                }
                if (state.canUndo) BareIcon(LumiIcons.Undo, "撤销", size = 42.dp) { controller.undo() }
                BareIcon(LumiIcons.Edit, "编辑", size = 42.dp, onClick = onEdit)
                HSpace(4)
                SolidButton("添加", Modifier.padding(top = 4.dp), icon = LumiIcons.Plus, fill = Violet, content = Color.White, onClick = onAdd)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (empty) {
                    Column(Modifier.align(Alignment.Center).padding(horizontal = 28.dp).widthIn(max = 340.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("场景还是空的", fontSize = 21.sp, fontWeight = FontWeight.Medium, style = shadowed)
                        VSpace(8)
                        Text("点右上角的「添加」选文件：模型（.pmx，或者整个 zip）、动作、镜头、音乐、脚本，可以一次选好几个，应用会把它们各自放好。",
                            fontSize = 12.5.sp, color = ink.muted, textAlign = TextAlign.Center, lineHeight = 19.sp, style = shadowed)
                    }
                }
            }
            if (barOpen) {
                // 展开的播放条：进度线 + 循环 / 速度
                Column(Modifier.fillMaxWidth().padding(start = 2.dp, end = 4.dp, bottom = 2.dp)) {
                    if (!wide) Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) { ViewWords(controller, state) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BareIcon(if (play.playing) LumiIcons.Pause else LumiIcons.Play, if (play.playing) "暂停" else "播放", size = 46.dp, iconSize = 20.dp) { controller.togglePlay() }
                        Text(formatTime(play.frame), fontSize = 12.sp, fontFamily = Mono, style = shadowed)
                        Scrubber(controller, play, Modifier.weight(1f).padding(horizontal = 10.dp))
                        Text(formatTime(play.maxFrame.toFloat()), fontSize = 12.sp, fontFamily = Mono, color = ink.muted, style = shadowed)
                        if (wide) { HSpace(10); ViewWords(controller, state) }
                        BareIcon(LumiIcons.ChevronDown, "收起播放条", size = 40.dp, iconSize = 15.dp) { onBar(false) }
                    }
                }
            } else {
                // 平时：左下一颗播放钮（带时间，点时间展开），右下是操控和隐藏界面
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val pill = RoundedCornerShape(50)
                    Row(Modifier.clip(pill).background(onScene(Color(0x66120C24), Color(0xCCFFFFFF))).border(HairWidth, onScene(Color(0x4DE3D9FF), Color(0x402B1A66)), pill), verticalAlignment = Alignment.CenterVertically) {
                        BareIcon(if (play.playing) LumiIcons.Pause else LumiIcons.Play, if (play.playing) "暂停" else "播放", size = 42.dp, iconSize = 18.dp) { controller.togglePlay() }
                        Row(
                            Modifier.clip(pill).clickable { onBar(true) }.padding(start = 2.dp, end = 12.dp).height(42.dp).semantics(mergeDescendants = true) { contentDescription = "展开播放条" },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (play.maxFrame > 0) Text(formatTime(play.frame) + " / " + formatTime(play.maxFrame.toFloat()), Modifier.padding(end = 6.dp), fontSize = 11.5.sp, fontFamily = Mono)
                            Icon(LumiIcons.ChevronUp, null, Modifier.size(13.dp), tint = ink.muted)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    BareIcon(LumiIcons.Gamepad, "操控模式", size = 44.dp, iconSize = 20.dp) { controller.play.setControl(true) }
                    BareIcon(LumiIcons.Fullscreen, "隐藏界面", size = 44.dp, onClick = onHide)
                }
            }
        }
    }
}

/** 进度线：拖动就是跳帧。 */
@Composable
private fun Scrubber(controller: SceneController, play: PlaybackUi, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val max = play.maxFrame.coerceAtLeast(1).toFloat()
    LumiSlider(
        value = (dragging ?: play.frame).coerceIn(0f, max), valueRange = 0f..max, enabled = play.maxFrame > 0,
        onValueChange = { dragging = it; controller.seekFrame(it.roundToInt()) }, onValueChangeFinished = { dragging = null },
        modifier = modifier.height(34.dp).semantics { contentDescription = "进度" },
    )
}

/** 播放线右边的几个词：镜头 / 自由、循环、速度。 */
@Composable
private fun ViewWords(controller: SceneController, state: UiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.hasCameraMotion) {
            Word("镜头", state.useCameraMotion, Modifier.semantics { contentDescription = "跟随镜头动作" }) { controller.setUseCameraMotion(true) }
            Word("自由", !state.useCameraMotion, Modifier.semantics { contentDescription = "自由视角" }) { controller.setUseCameraMotion(false) }
            HSpace(8)
        }
        Word("循环", state.loop, Modifier.semantics { contentDescription = if (state.loop) "循环播放：开" else "循环播放：关" }) { controller.setLoop(!state.loop) }
        SpeedWord(controller, state)
    }
}

@Composable
private fun SpeedWord(controller: SceneController, state: UiState) {
    val speeds = listOf(0.25f, 0.5f, 1f, 1.5f, 2f)
    val text = "×" + (if (state.speed == state.speed.toInt().toFloat()) state.speed.toInt().toString() else state.speed.toString())
    Word(text, false, Modifier.semantics { contentDescription = "播放速度" }, dim = if (state.speed == 1f) ink.muted else ink.text) {
        controller.setSpeed(speeds[(speeds.indexOfFirst { it >= state.speed - 0.01f }.coerceAtLeast(0) + 1) % speeds.size])
    }
}

// ============================================================================ 编辑：工具面板 + 时间轴

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BenchChrome(
    controller: SceneController, state: UiState, play: PlaybackUi, meshUi: MeshUi, wide: Boolean, panelHeight: Dp, showFps: Boolean, mcp: McpUi,
    showTimeline: Boolean, showPanel: Boolean, onTimeline: () -> Unit, onPanel: () -> Unit, onMenu: (String) -> Unit,
    onPick: (Int, Int) -> Unit, tracksHeight: Dp,
) {
    val tracks = state.mode == Modes.CLIPS
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        // 顶上一行：完成 · 工具 · 撤销 / 面板开关
        Row(Modifier.fillMaxWidth().height(44.dp).background(ink.paper).blockTouches().bottomLine(), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(LumiIcons.Menu, "菜单", size = 40.dp) { onMenu("") }
            Row(
                Modifier.fillMaxHeight().clickable { controller.setMode(Modes.VIEW) }.padding(start = 4.dp, end = 10.dp).semantics(mergeDescendants = true) { contentDescription = "完成" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LumiIcons.ChevronLeft, null, Modifier.size(15.dp), tint = ink.text)
                Text("完成", Modifier.padding(start = 3.dp), fontSize = 13.sp)
            }
            BarSep()
            // 竖屏放不下六个词：这一行可以左右滑，没露出来的那一侧淡出；选中的词总会滑到看得见的地方
            val wordScroll = rememberScrollState()
            Row(Modifier.weight(1f).fadeEdges(wordScroll).horizontalScroll(wordScroll), verticalAlignment = Alignment.CenterVertically) {
                for ((id, label, _) in EDIT_MODES) {
                    val on = state.mode == id
                    val reveal = remember { BringIntoViewRequester() }
                    LaunchedEffect(on) { if (on) try { reveal.bringIntoView() } catch (_: IllegalStateException) {} }
                    Word(label, on, Modifier.bringIntoViewRequester(reveal).semantics { contentDescription = "模式 $label" }) { controller.setMode(id) }
                }
            }
            IconBtn(LumiIcons.Undo, "撤销", enabled = state.canUndo) { controller.undo() }
            if (state.mode == Modes.MESH) IconBtn(LumiIcons.Redo, "重做", enabled = state.canRedo) { controller.redo() }
            if (!tracks) {
                IconBtn(LumiIcons.Timeline, "时间轴", selected = showTimeline, onClick = onTimeline)
                IconBtn(LumiIcons.PanelRight, "操作面板", selected = showPanel, onClick = onPanel)
            }
            HSpace(4)
        }
        if (tracks) {
            // 轨道：画面在上，下面一整块是轨道
            Box(Modifier.weight(1f).fillMaxWidth()) { ViewportOverlay(controller, state, play, meshUi, showFps, mcp) }
            ClipTracks(controller, state, play, Modifier.fillMaxWidth().height(tracksHeight), onPick, onMenu)
        } else if (wide) {
            // 横屏：工具面板占满右边一条，时间轴只垫在画面下面（屏幕本来就矮，不能再压缩面板）
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { ViewportOverlay(controller, state, play, meshUi, showFps, mcp) }
                    if (showTimeline) TimelinePanel(controller, state, play, Modifier.fillMaxWidth().height(118.dp))
                }
                if (showPanel) EditPanel(controller, state, play, meshUi, Modifier.width(236.dp).fillMaxHeight(), onMenu)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) { ViewportOverlay(controller, state, play, meshUi, showFps, mcp) }
            if (showTimeline) TimelinePanel(controller, state, play, Modifier.fillMaxWidth().height(150.dp))
            if (showPanel) EditPanel(controller, state, play, meshUi, Modifier.fillMaxWidth().height(panelHeight), onMenu)
        }
        KeyBar(controller, state, play, wide, keys = !tracks)
    }
}

/** 画面上的悬浮元素：选中了什么、拖动时的读数、右下角的 XYZ 拖动块。 */
@Composable
private fun ViewportOverlay(controller: SceneController, state: UiState, play: PlaybackUi, mesh: MeshUi, showFps: Boolean, mcp: McpUi) {
    val shadowed = TextStyle(shadow = sceneShadow())
    Box(Modifier.fillMaxSize()) {
        val hint = when {
            play.dragText.isNotEmpty() -> play.dragText
            state.mode == Modes.BONE && state.selectedBone >= 0 -> state.selectedBoneName
            state.mode == Modes.PART && state.selectedPartName.isNotEmpty() -> state.selectedPartName
            else -> ""
        }
        if (hint.isNotEmpty()) {
            Text(hint, Modifier.align(Alignment.TopStart).padding(horizontal = 10.dp, vertical = 8.dp), fontSize = 13.sp,
                color = if (play.dragText.isNotEmpty()) onScene(Color(0xFFF5B544), Color(0xFFB25E09)) else onScene(Color.White, Color(0xFF241B3D)), fontFamily = if (play.dragText.isNotEmpty()) Mono else null, maxLines = 1, style = shadowed)
        }
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalAlignment = Alignment.End) {
            if (showFps) Text("${play.fps} FPS · ${play.triangles / 1000}k 面" + if (play.pendingTextures > 0) " · 贴图 ${play.pendingTextures}" else "", fontSize = 10.sp, color = onScene(Color(0x99FFFFFF), Color(0xA6372C59)), style = shadowed)
            if (mcp.running && mcp.calls > 0) Text("AI：${mcp.lastCall}（${mcp.calls}）", fontSize = 10.sp, color = ink.accent, style = shadowed)
        }
        // 右下：局部 / 全局 + XYZ 拖动块（和 MMD 画面右下角那排图标一个用法：按住拖）
        val manip = state.mode == Modes.MODEL || state.mode == Modes.BONE || state.mode == Modes.PART || (state.mode == Modes.MESH && mesh.active)
        if (manip) {
            Row(Modifier.align(Alignment.BottomEnd).padding(8.dp).blockTouches(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BareIcon(if (state.localAxes) LumiIcons.Local else LumiIcons.Globe, if (state.localAxes) "局部轴（点击切换到全局）" else "全局轴（点击切换到局部）",
                    tint = onScene(Color.White, Color(0xFF241B3D)), size = 38.dp) { controller.setLocalAxes(!state.localAxes) }
                val toolIcon = when (state.tool) { Editor.TOOL_ROTATE -> LumiIcons.Rotate; Editor.TOOL_SCALE -> LumiIcons.Scale; else -> LumiIcons.Move }
                AxisPad(controller, state.tool, 0, "X", Color(0xFFFF6B6B), toolIcon)
                AxisPad(controller, state.tool, 1, "Y", Color(0xFF5FD97A), toolIcon)
                AxisPad(controller, state.tool, 2, "Z", Color(0xFF6C9BFF), toolIcon)
            }
        }
    }
}

/** 一个轴的拖动块：按住左右 / 上下拖，对象就沿这根轴移动 / 旋转 / 缩放（取决于当前工具）。 */
@Composable
private fun AxisPad(controller: SceneController, tool: Int, axis: Int, name: String, color: Color, toolIcon: ImageVector) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(onScene(Color(0x66120C24), Color(0xD9FFFFFF))).border(1.dp, color.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
            .semantics { contentDescription = "$name 轴拖动块" }
            .pointerInput(tool, axis) {
                detectDragGestures(
                    onDragStart = { controller.padStart() },
                    onDragEnd = { controller.padEnd() },
                    onDragCancel = { controller.padEnd() },
                ) { change, drag -> change.consume(); controller.padDrag(tool, axis, drag.x - drag.y) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(toolIcon, null, Modifier.size(20.dp).align(Alignment.Center), tint = color.copy(alpha = 0.6f))
        Text(name, Modifier.align(Alignment.BottomEnd).padding(end = 4.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

/** 框选时画出的虚线矩形（坐标是整个渲染视图上的像素）。 */
@Composable
private fun BoxSelectOverlay(controller: SceneController) {
    val rect by controller.boxRect.collectAsState()
    val r = rect ?: return
    val fill = onScene(Color(0x26FFFFFF), Color(0x267B4DFF))
    val edge = onScene(Color.White, Violet)
    Canvas(Modifier.fillMaxSize()) {
        val x = minOf(r[0], r[2]); val y = minOf(r[1], r[3])
        val w = kotlin.math.abs(r[2] - r[0]); val h = kotlin.math.abs(r[3] - r[1])
        drawRect(fill, Offset(x, y), Size(w, h))
        drawRect(edge, Offset(x, y), Size(w, h), style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
    }
}

/** 编辑时底下的一条：逐帧 / 逐关键帧跳转、帧号、关键帧操作。竖屏放不下一行，分成上下两行。 */
@Composable
private fun KeyBar(controller: SceneController, state: UiState, play: PlaybackUi, wide: Boolean, keys: Boolean = true) {
    var jump by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val frame = play.frame.roundToInt()
    val transport: @Composable () -> Unit = {
        IconBtn(LumiIcons.ToStart, "回到开头") { controller.seekFrame(0) }
        IconBtn(LumiIcons.PrevKey, "上一个关键帧") { controller.jumpKey(false) }
        IconBtn(LumiIcons.ChevronLeft, "上一帧") { controller.stepFrame(-1) }
        IconBtn(if (play.playing) LumiIcons.Pause else LumiIcons.Play, if (play.playing) "暂停" else "播放", size = 40.dp) { controller.togglePlay() }
        IconBtn(LumiIcons.ChevronRight, "下一帧") { controller.stepFrame(1) }
        IconBtn(LumiIcons.NextKey, "下一个关键帧") { controller.jumpKey(true) }
        // 帧号（点一下可以直接输入）
        Column(
            Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(6.dp)).clickable { jump = true }.padding(horizontal = 6.dp, vertical = 2.dp)
                .semantics(mergeDescendants = true) { contentDescription = "当前帧" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("$frame", fontSize = 14.sp, fontFamily = Mono, fontWeight = FontWeight.Medium, color = if (play.frameKeyed) KeyGold else ink.text, lineHeight = 16.sp)
            Text("/ ${play.maxFrame} 帧 · ${formatTime(play.frame)}", fontSize = 9.sp, fontFamily = Mono, color = ink.muted, lineHeight = 11.sp)
        }
    }
    val keyOps: @Composable () -> Unit = {
        // 关键帧操作：登记 / 删除 / 复制 / 粘贴 / 反转粘贴
        val keyed = if (state.mode == Modes.CAMERA) play.cameraKeyed else if (state.mode == Modes.BONE && state.selectedBone >= 0) play.boneKeyed else play.frameKeyed
        Box {
            IconBtn(if (keyed) LumiIcons.Key else LumiIcons.KeyAdd, "登记关键帧", tint = KeyGold, size = 40.dp) { controller.registerKey() }
            if (state.pending > 0 && state.mode != Modes.CAMERA) {
                Text("${state.pending}", Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(50)).background(ink.danger).padding(horizontal = 4.dp), fontSize = 9.sp, color = Color.White, lineHeight = 12.sp)
            }
        }
        IconBtn(LumiIcons.KeyDelete, "删除关键帧", enabled = keyed || play.frameKeyed, onLongClick = { controller.deleteKey(all = true) }) { controller.deleteKey() }
        IconBtn(LumiIcons.Copy, "复制这一帧") { controller.copyKeys() }
        IconBtn(LumiIcons.Paste, "粘贴到这一帧", enabled = state.clipboard > 0) { controller.pasteKeys(false) }
        IconBtn(LumiIcons.PasteMirror, "左右反转粘贴", enabled = state.clipboard > 0) { controller.pasteKeys(true) }
        IconBtn(LumiIcons.KeyAuto, if (state.autoKey) "自动关键帧：开" else "自动关键帧：关", selected = state.autoKey) { controller.setAutoKey(!state.autoKey) }
        Box {
            IconBtn(LumiIcons.Settings, "更多关键帧操作") { more = true }
            DropdownMenu(more, { more = false }) {
                MenuItem(LumiIcons.ShiftRight, "从这一帧起整体后移 5 帧") { more = false; controller.shiftKeys(5) }
                MenuItem(LumiIcons.ShiftLeft, "从这一帧起整体前移 5 帧") { more = false; controller.shiftKeys(-5) }
                MenuItem(LumiIcons.KeyDelete, "删除这一帧的全部关键帧") { more = false; controller.deleteKey(all = true) }
                MenuItem(LumiIcons.Eraser, "清空这个模型的全部关键帧", ink.danger) { more = false; controller.clearKeys() }
            }
        }
        BarSep()
        IconBtn(LumiIcons.Loop, if (state.loop) "循环播放：开" else "循环播放：关", selected = state.loop) { controller.setLoop(!state.loop) }
        SpeedWord(controller, state)
    }
    Column(Modifier.fillMaxWidth().background(ink.paper).blockTouches()) {
        Hairline()
        if (!keys) {
            // 轨道模式：只要走带的那几个按钮
            Row(Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                transport(); BarSep()
                IconBtn(LumiIcons.Loop, if (state.loop) "循环播放：开" else "循环播放：关", selected = state.loop) { controller.setLoop(!state.loop) }
                SpeedWord(controller, state)
            }
        } else if (wide) {
            Row(Modifier.fillMaxWidth().height(46.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                transport(); BarSep(); keyOps(); Spacer(Modifier.width(4.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) { keyOps() }
            Row(Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) { transport() }
        }
    }
    if (jump) {
        var text by remember { mutableStateOf(frame.toString()) }
        val go = { jump = false; text.trim().toIntOrNull()?.let { controller.seekFrame(it) }; Unit }
        LumiDialog(onDismissRequest = { jump = false }, title = { Text("跳到第几帧") },
            text = { OutlinedTextField(text, { text = it.filter { c -> c.isDigit() }.take(6) }, singleLine = true, label = { Text("帧号（30 帧 = 1 秒）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() })) },
            confirmButton = { TextButton(go) { Text("跳转") } }, dismissButton = { TextButton({ jump = false }) { Text("取消") } })
    }
}

@Composable
internal fun MenuItem(icon: ImageVector, text: String, color: Color = ink.text, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, fontSize = 13.sp, color = color) }, leadingIcon = { Icon(icon, null, Modifier.size(17.dp), tint = color) }, onClick = onClick)
}

// ============================================================================ 对话框

/** 贴图没跟着模型一起导入：选一种办法把它们找回来。 */
@Composable
private fun TextureDialog(p: TexturePrompt, onGrant: () -> Unit, onFolder: () -> Unit, onDismiss: () -> Unit) {
    LumiDialog(
        onDismissRequest = onDismiss,
        title = { Text("贴图没有一起导入", fontSize = 17.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val names = p.missing.take(3).joinToString("、") { it.substringAfterLast('/') } + if (p.missing.size > 3) "…" else ""
                Text("「${p.name}」要用的 ${p.missing.size} 张贴图（$names）还在它原来的文件夹里。系统只把你选中的那一个文件交给了应用，所以需要你点一下：",
                    fontSize = 13.sp, lineHeight = 19.sp, color = ink.muted)
                VSpace(6)
                if (!p.folderOnly) ListRow("允许访问所有文件", value = "以后都自动带上贴图", chevron = true, onClick = onGrant)
                ListRow("选择模型所在的文件夹", value = if (p.folderHint != null) "点「使用此文件夹」" else "只授权这一个", chevron = true, onClick = onFolder)
                VSpace(8)
                Text("也可以一开始就选整个 zip，或者在「角色」里点「添加整个文件夹」。", fontSize = 11.5.sp, lineHeight = 16.sp, color = ink.muted)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("先不管") } },
    )
}

/** 一次导入里有好几个模型：挑一个加载。 */
@Composable
private fun ModelChoiceDialog(c: ModelChoice, onChoose: (String?) -> Unit) {
    LumiDialog(
        onDismissRequest = { onChoose(null) },
        title = { Text(c.title, fontSize = 16.sp) },
        text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                for ((path, name) in c.items) ListRow(name, chevron = true) { onChoose(path) }
            }
        },
        confirmButton = { TextButton({ onChoose(null) }) { Text("都不加载") } },
    )
}

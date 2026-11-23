package com.lumi.mmd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.mmd.core.Backgrounds
import com.lumi.mmd.core.LibraryItem
import com.lumi.mmd.core.McpUi
import com.lumi.mmd.core.ModelUi
import com.lumi.mmd.core.PlaybackUi
import com.lumi.mmd.core.RenderPrefs
import com.lumi.mmd.core.SceneController
import com.lumi.mmd.core.StyleUi
import com.lumi.mmd.core.FloorUi
import com.lumi.mmd.core.Slot
import com.lumi.mmd.core.Modes
import com.lumi.mmd.core.UiState
import com.lumi.mmd.engine.scene.ModelInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun titleOf(route: String, state: UiState): String {
    val last = route.substringAfterLast('/')
    return when {
        route.isEmpty() -> "选项"
        last == "cast" -> "角色"
        last == "clips" -> "动作与音乐"
        last == "script" -> "脚本与特效"
        last == "luaconsole" -> "控制台"
        last == "luaapi" -> "接口说明"
        last.startsWith("lua") -> state.play.scripts.firstOrNull { it.id == last.drop(3).toIntOrNull() }?.name ?: "脚本"
        last == "physics" -> "物理"
        last.startsWith("phys") -> (state.models.firstOrNull { it.id == last.drop(4).toIntOrNull() }?.name ?: "模型") + " 的物理"
        last == "stage" -> "舞台与背景"
        last == "look" -> "灯光与画面"
        last == "export" -> "导出"
        last == "mcp" -> "AI 操作"
        last == "library" -> "资源库"
        last == "settings" -> "设置"
        last.startsWith("m") && last.drop(1).toIntOrNull() != null -> state.models.firstOrNull { it.id == last.drop(1).toInt() }?.name ?: "模型"
        last.startsWith("pick") -> when (last.drop(4).toIntOrNull()) {
            Slot.MODEL -> "选一个模型"; Slot.STAGE -> "选一个舞台"; Slot.MOTION -> "选动作"; Slot.MORPH -> "选表情"
            Slot.CAMERA -> "选镜头"; Slot.MUSIC -> "选音乐"; Slot.POSE -> "选姿势"; Slot.CLIP -> "接一段"; else -> "选文件"
        }
        else -> ""
    }
}

/**
 * 菜单卡片：一张纸，里面是一行行的列表，像手机自己的设置那样点一行往里进一层，左上角返回上一层。
 * 卡片外面的画面原样露着，点一下画面卡片就收起来。横屏时卡片靠左，竖屏时在下半屏。
 */
@Composable
fun MenuCard(
    controller: SceneController, state: UiState, play: PlaybackUi, prefs: RenderPrefs, mcp: McpUi,
    route: String, wide: Boolean, onRoute: (String?) -> Unit, onPick: (Int, Int) -> Unit, onPickFolder: () -> Unit,
) {
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onRoute(null) } })
    InkTheme(PaperInk) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(10.dp), contentAlignment = if (wide) Alignment.CenterStart else Alignment.BottomCenter) {
            val shape = RoundedCornerShape(12.dp)
            // 正在写脚本时卡片放大：横屏占满整行（一行能多写些），竖屏占满键盘上面的地方
            var typing by remember { mutableStateOf(false) }
            Column(
                (if (wide) (if (typing) Modifier.fillMaxWidth() else Modifier.width(344.dp)).fillMaxHeight() else Modifier.fillMaxWidth().fillMaxHeight(if (typing) 1f else 0.56f))
                    .clip(shape).background(ink.paper).border(HairWidth, ink.line, shape).blockTouches().semantics { contentDescription = "菜单卡片" },
            ) {
                // 顶栏：返回（写着回到哪里）· 标题 · 关闭
                Box(Modifier.fillMaxWidth().height(46.dp).bottomLine()) {
                    val parent = parentRoute(route)
                    if (parent != null) {
                        Row(
                            Modifier.align(Alignment.CenterStart).fillMaxHeight().clickable { onRoute(parent) }.padding(start = 8.dp, end = 12.dp)
                                .semantics(mergeDescendants = true) { contentDescription = "返回" },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(LumiIcons.ChevronLeft, null, Modifier.size(15.dp), tint = ink.text)
                            Text(titleOf(parent, state), Modifier.padding(start = 2.dp).widthIn(max = 84.dp), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(titleOf(route, state), Modifier.align(Alignment.Center).widthIn(max = 150.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BareIcon(LumiIcons.Close, "关闭", Modifier.align(Alignment.CenterEnd).padding(end = 3.dp), onClick = { onRoute(null) })
                }
                val go: (String) -> Unit = { onRoute(if (route.isEmpty()) it else "$route/$it") }
                val back: () -> Unit = { onRoute(parentRoute(route) ?: "") }
                val close: () -> Unit = { onRoute(null) }
                val last = route.substringAfterLast('/')
                key(route) {
                    val scriptId = if (last.startsWith("lua") && last != "luaconsole" && last != "luaapi") last.drop(3).toIntOrNull() else null
                    if (last == "library" || last == "mcp") {
                        // 这两页自己会滚动（资源库是懒加载的长列表），不能再套一层滚动
                        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                            if (last == "library") LibraryPanel(controller, state, close) else McpPanel(controller, mcp)
                        }
                    } else if (scriptId != null) {
                        // 脚本页：上面一行按钮固定，下面自己滚动
                        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                            val sc = state.play.scripts.firstOrNull { it.id == scriptId }
                            if (sc == null) LaunchedEffect(Unit) { back() } else ScriptPage(controller, state, sc, back) { typing = it }
                        }
                    } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 14.dp)) {
                        when {
                            route.isEmpty() -> RootSection(controller, state, prefs, mcp, go, close)
                            last == "cast" -> CastSection(controller, state, go, close)
                            last == "clips" -> ClipsSection(controller, state, go, close, onPick)
                            last == "script" -> ScriptSection(controller, state, go, onPick)
                            last == "luaconsole" -> ConsolePage(controller)
                            last == "luaapi" -> ApiPage(controller)
                            last.startsWith("lua") -> {
                                val sc = state.play.scripts.firstOrNull { it.id == last.drop(3).toIntOrNull() }
                                if (sc == null) LaunchedEffect(Unit) { back() } else ScriptPage(controller, state, sc, back)
                            }
                            last == "physics" -> PhysicsSection(controller, state, go)
                            last.startsWith("phys") -> {
                                val m = state.models.firstOrNull { it.id == last.drop(4).toIntOrNull() }
                                if (m == null) LaunchedEffect(Unit) { back() } else ModelPhysicsPage(controller, m)
                            }
                            last == "stage" -> StageSection(controller, state, prefs, go, onPick)
                            last == "look" -> { LightSection(controller, prefs); LookSection(controller, prefs); StyleSection(controller, state.style) }
                            last == "export" -> ExportSection(controller, state)
                            last == "settings" -> SettingsSection(controller, state, prefs, go, close)
                            last.startsWith("pick") -> PickSection(controller, state, last.drop(4).toIntOrNull() ?: Slot.AUTO, back, onPick, onPickFolder)
                            last.startsWith("m") -> {
                                val m = state.models.firstOrNull { it.id == last.drop(1).toIntOrNull() }
                                if (m == null) LaunchedEffect(Unit) { back() } else ModelPage(controller, state, m, go)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(top = 10.dp), fontSize = 11.5.sp, lineHeight = 17.sp, color = ink.muted)
}

// ---------------------------------------------------------------------------- 第一层

@Composable
private fun RootSection(controller: SceneController, state: UiState, prefs: RenderPrefs, mcp: McpUi, go: (String) -> Unit, close: () -> Unit) {
    val lead = state.models.firstOrNull { it.id == state.selectedModel && it.skinned } ?: state.models.firstOrNull { it.skinned }
    val stage = state.models.firstOrNull { it.kind == ModelInstance.KIND_STAGE }
    val running = state.play.scripts.count { it.running }
    ListRow("角色", icon = LumiIcons.Person, value = when (state.models.size) { 0 -> "还没有"; 1 -> state.models[0].name; else -> "${state.models.size} 个" }, chevron = true) { go("cast") }
    ListRow("动作与音乐", icon = LumiIcons.Play, value = listOfNotNull(lead?.motionFile?.takeIf { it.isNotEmpty() }, state.audioName.takeIf { it.isNotEmpty() }).joinToString(" · "), chevron = true) { go("clips") }
    ListRow("脚本与特效", icon = LumiIcons.Code, value = if (running > 0) "$running 个在运行" else "Lua", valueColor = if (running > 0) ink.accent else ink.muted, chevron = true) { go("script") }
    ListRow("物理", icon = LumiIcons.Wind, value = "头发和裙子的手感", chevron = true) { go("physics") }
    ListRow("舞台与背景", icon = LumiIcons.Home, value = stage?.name ?: Backgrounds.names.getOrElse(prefs.background) { "" }, chevron = true) { go("stage") }
    ListRow("灯光与画面", icon = LumiIcons.Sun, chevron = true) { go("look") }
    ListRow("导出", icon = LumiIcons.Save, value = "截图 · VMD · PMX", chevron = true) { go("export") }
    ListRow("AI 操作", icon = LumiIcons.Robot, value = if (mcp.running) "已开启" else "MCP", valueColor = if (mcp.running) ink.accent else ink.muted, chevron = true) { go("mcp") }
    ListRow("设置", icon = LumiIcons.Settings, value = "资源库 · 画质 · 教程", chevron = true) { go("settings") }
    // 操控模式只有一个入口：首页右下角的手柄
}

// ---------------------------------------------------------------------------- 角色

private fun kindLabel(k: Int) = when (k) { ModelInstance.KIND_STAGE -> "舞台"; ModelInstance.KIND_ACCESSORY -> "配件"; else -> "角色" }

@Composable
private fun CastSection(controller: SceneController, state: UiState, go: (String) -> Unit, close: () -> Unit) {
    for (m in state.models) {
        ListRow(
            m.name, value = if (m.missingTextures > 0) "缺 ${m.missingTextures} 张贴图" else kindLabel(m.kind), valueColor = if (m.missingTextures > 0) ink.warn else ink.muted,
            end = {
                BareIcon(if (m.visible) LumiIcons.Eye else LumiIcons.EyeOff, if (m.visible) "隐藏 ${m.name}" else "显示 ${m.name}", tint = if (m.visible) ink.text else ink.faint) { controller.setModelVisible(m.id, !m.visible) }
                BareIcon(LumiIcons.Close, "移除 ${m.name}") { controller.removeModel(m.id) }
            },
        ) { controller.selectModel(m.id); go("m${m.id}") }
    }
    ListRow("添加模型", icon = LumiIcons.Plus, value = "从手机里选，或用导入过的", chevron = true) { go("pick${Slot.MODEL}") }
    ListRow("新建模型", icon = LumiIcons.NewFile, value = "从零建模") { controller.newModel("box"); close() }
    Note("行尾的叉只是把模型从场景里拿走，文件还在资源库里；要把文件也删掉，到「添加模型」那一页点它右边的垃圾桶。")
}

// ---------------------------------------------------------------------------- 动作与音乐（片段槽位）

@Composable
private fun ClipsSection(controller: SceneController, state: UiState, go: (String) -> Unit, close: () -> Unit, onPick: (Int, Int) -> Unit) {
    val chars = state.models.filter { it.skinned }
    val target = chars.firstOrNull { it.id == state.selectedModel } ?: chars.firstOrNull()
    if (chars.size > 1 && target != null) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).bottomLine(), verticalAlignment = Alignment.CenterVertically) {
            Text("给", fontSize = 12.sp, color = ink.muted)
            Words(chars.map { it.name to it.id }, target.id) { controller.selectModel(it) }
        }
    }
    val id = target?.id ?: -1
    SlotRow("动作", target?.motionFile ?: "", enabled = target != null, onOpen = { go("pick${Slot.MOTION}") }, onRemove = { controller.clearMotion(id) })
    SlotRow("表情", target?.morphFile ?: "", enabled = target != null, onOpen = { go("pick${Slot.MORPH}") }, onRemove = { controller.clearMorphSlot(id) })
    SlotRow("镜头", state.cameraFile, onOpen = { go("pick${Slot.CAMERA}") }, onRemove = { controller.clearCameraMotion() })
    SlotRow("音乐", state.audioName, onOpen = { go("pick${Slot.MUSIC}") }, onRemove = { controller.clearAudio() })
    ListRow("姿势", labelWidth = 44.dp, value = "套用一个 .vpd", enabled = target != null, chevron = true) { go("pick${Slot.POSE}") }
    if (target == null) Note("先在「角色」里添加一个模型，动作和表情才有地方放。镜头和音乐可以先放好。")
    if (target != null && target.animations.isNotEmpty()) {
        SectionTitle("模型自带的动画")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((i, a) in target.animations.withIndex()) Chip(a, false) { controller.setEmbeddedAnimation(target.id, i) }
        }
    }
    Note("点一行去挑文件（从手机里选，或者用导入过的），右边的叉是清空。一次选好几个文件也行：身体动作、只有表情的 VMD、镜头会自动放进各自的槽里。" +
        "音乐支持 mp3、wav、ogg、m4a、flac，画面跟着音乐走。\n想接好几段动作、把音乐挪个位置或者只用其中一截：点右上角的铅笔进「编辑 → 轨道」。")
}

/**
 * 给某个槽挑文件：从手机里选，或者用导入过的（资源库里的）。
 * 每个文件右边的垃圾桶是把它从资源库里删掉——模型会连贴图一起删。
 */
@Composable
private fun PickSection(controller: SceneController, state: UiState, slot: Int, back: () -> Unit, onPick: (Int, Int) -> Unit, onPickFolder: () -> Unit) {
    val version by controller.libraryVersion.collectAsState()
    var items by remember { mutableStateOf<List<LibraryItem>?>(null) }
    var deleting by remember { mutableStateOf<LibraryItem?>(null) }
    LaunchedEffect(version) { items = withContext(Dispatchers.IO) { controller.library.scan() } }
    val wanted: (LibraryItem) -> Boolean = { item ->
        when (slot) {
            Slot.MODEL, Slot.STAGE -> item.type == LibraryItem.TYPE_MMD_MODEL || item.type == LibraryItem.TYPE_OTHER_MODEL
            Slot.MOTION, Slot.MORPH, Slot.CAMERA -> item.type == LibraryItem.TYPE_MOTION
            Slot.CLIP -> item.type == LibraryItem.TYPE_MOTION || item.type == LibraryItem.TYPE_AUDIO
            Slot.SCRIPT -> item.type == LibraryItem.TYPE_SCRIPT
            Slot.MUSIC -> item.type == LibraryItem.TYPE_AUDIO
            Slot.POSE -> item.type == LibraryItem.TYPE_POSE
            else -> true
        }
    }
    val target = state.selectedModel
    val model = slot == Slot.MODEL || slot == Slot.STAGE
    ListRow("从手机里选文件", icon = LumiIcons.Export, value = if (model) ".pmx / .zip / .blend / .glb …" else "") { onPick(slot, target); back() }
    if (model) ListRow("选整个文件夹", icon = LumiIcons.Folder, value = "模型和贴图一起带进来") { onPickFolder(); back() }
    val list = items?.filter(wanted)
    if (list != null) {
        SectionTitle(if (list.isEmpty()) "还没有导入过这类文件" else "导入过的（点一下使用）")
        for (item in list) {
            ListRow(
                item.name, value = (if (slot == Slot.CLIP) (if (item.type == LibraryItem.TYPE_AUDIO) "音乐 · " else "动作 · ") else "") + item.pack,
                end = { BareIcon(LumiIcons.Trash, "删除 ${item.name}", tint = ink.muted) { deleting = item } },
            ) { controller.useLibraryFile(item.path, item.type, slot, target); back() }
        }
        if (list.isNotEmpty()) Note("垃圾桶是把文件从应用的资源库里删掉（不影响你手机里的原文件）。全部导入过的文件在「设置 → 资源库」。")
    }
    deleting?.let { item -> DeleteItemDialog(controller, item, items ?: emptyList()) { deleting = null } }
}

/** 确认从资源库删除一个文件。others = 资源库里的全部条目（用来看同一个包里还有没有别的东西）。 */
@Composable
internal fun DeleteItemDialog(controller: SceneController, item: LibraryItem, others: List<LibraryItem>, onClose: () -> Unit) {
    val model = item.type == LibraryItem.TYPE_MMD_MODEL || item.type == LibraryItem.TYPE_OTHER_MODEL
    val siblings = others.count { it.pack == item.pack && it.path != item.path }
    val what = when {
        model && siblings == 0 -> "会把这个模型和它的贴图从应用的资源库里删掉。"
        model -> "会把这个模型文件从应用的资源库里删掉。同一个包里还有 $siblings 个别的文件，贴图可能是共用的，所以贴图先留着（想连贴图一起清掉，到「设置 → 资源库」里删除整包）。"
        else -> "会把这个文件从应用的资源库里删掉。"
    }
    LumiDialog(
        onDismissRequest = onClose,
        title = { Text("删除「${item.name}」？", fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Text(what + "你手机里的原文件不受影响。\n场景里如果正在用它，也会一起从场景里移除。", fontSize = 13.sp, lineHeight = 19.sp) },
        confirmButton = { TextButton(onClick = { controller.deleteLibraryItem(item.path, item.name); onClose() }) { Text("删除", color = ink.danger) } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

// ---------------------------------------------------------------------------- 舞台与背景

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageSection(controller: SceneController, state: UiState, p: RenderPrefs, go: (String) -> Unit, onPick: (Int, Int) -> Unit) {
    val stage = state.models.firstOrNull { it.kind == ModelInstance.KIND_STAGE }
    SlotRow("舞台", stage?.name ?: "", onOpen = { go("pick${Slot.STAGE}") }, onRemove = { stage?.let { controller.removeModel(it.id) } })
    Note("舞台可以是 PMX（选它的 zip）、Blender 的 .blend 或者 GLB。")
    SectionTitle("背景")
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((i, n) in Backgrounds.names.withIndex()) Chip(n, p.background == i) { controller.pickBackground(i) }
    }
    FloorSection(controller, state.floor, p)
}

/** 地板：默认的那块地面。可以改、可以删，删了以后可以再加一块默认的。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FloorSection(controller: SceneController, f: FloorUi, p: RenderPrefs) {
    SectionTitle("地板")
    if (!f.enabled) {
        Note("场景里现在没有地板：不画网格、不接影子，会掉的东西（头发裙子的刚体、脚本的粒子和积木、操控的角色）会一直往下掉。")
        ListRow("添加默认地板", icon = LumiIcons.Plus, color = ink.accent, value = "高度 0，带网格、影子和碰撞") { controller.addDefaultFloor() }
        return
    }
    ListRow("默认地板", value = "删除", valueColor = ink.danger) { controller.removeFloor() }
    // 网格、影子这两个开关顺带记成「新场景的默认值」
    SwitchRow("网格", f.grid) { on -> controller.editFloor { it.grid = on }; controller.updatePrefs(p.copy(grid = on)) }
    SwitchRow("接影子", f.shadow) { on -> controller.editFloor { it.shadow = on }; controller.updatePrefs(p.copy(groundShadow = on)) }
    SwitchRow("碰撞", f.collide, hint = "挡住掉下来的东西；关掉以后它们会穿过地板") { on -> controller.editFloor { it.collide = on } }
    LabeledSlider("高度", f.height, -30f..30f, format = { "%.1f".format(it) }, unbounded = true) { v -> controller.editFloor { it.height = v } }
    if (f.grid) LabeledSlider("格子大小", f.cell, 1f..20f, format = { "%.1f".format(it) }) { v -> controller.editFloor { it.cell = v.coerceIn(0.1f, 1000f) } }
    Text("底色", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = ink.muted)
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val a = f.color.getOrElse(3) { 0f }
        fun same(c: FloatArray) = a > 0.5f && kotlin.math.abs(f.color[0] - c[0]) < 0.01f && kotlin.math.abs(f.color[1] - c[1]) < 0.01f && kotlin.math.abs(f.color[2] - c[2]) < 0.01f
        Chip("透明", a <= 0.003f) { controller.editFloor { it.color[3] = 0f } }
        for ((name, c) in FloorColors) Chip(name, same(c)) { controller.editFloor { fl -> fl.color[0] = c[0]; fl.color[1] = c[1]; fl.color[2] = c[2]; fl.color[3] = 1f } }
    }
    if (f != FloorUi(grid = f.grid, shadow = f.shadow)) ListRow("恢复成默认地板", value = "高度 0、透明、有碰撞", chevron = false) { controller.addDefaultFloor() }
}

private val FloorColors = listOf(
    "白" to floatArrayOf(0.94f, 0.95f, 0.97f),
    "浅灰" to floatArrayOf(0.72f, 0.74f, 0.78f),
    "深灰" to floatArrayOf(0.22f, 0.23f, 0.26f),
    "木色" to floatArrayOf(0.72f, 0.56f, 0.40f),
    "草绿" to floatArrayOf(0.46f, 0.66f, 0.42f),
)

// ---------------------------------------------------------------------------- 灯光

@Composable
private fun LightSection(controller: SceneController, p: RenderPrefs) {
    SectionTitle("灯光")
    LabeledSlider("强度", p.lightIntensity, 0.2f..1f) { controller.updatePrefs(p.copy(lightIntensity = it)) }
    LabeledSlider("左右", p.lightX, -1f..1f) { controller.updatePrefs(p.copy(lightX = it)) }
    LabeledSlider("高低", p.lightY, -1f..-0.05f) { controller.updatePrefs(p.copy(lightY = it)) }
    LabeledSlider("前后", p.lightZ, -1f..1f) { controller.updatePrefs(p.copy(lightZ = it)) }
    VSpace(6)
    SwitchRow("阴影", p.shadows) { controller.updatePrefs(p.copy(shadows = it)) }
    SwitchRow("用舞台自带的灯光", p.stageLight, hint = "加载 Blender 的 GLB 舞台时，采用其中日光的方向") { controller.updatePrefs(p.copy(stageLight = it)) }
    ListRow("恢复默认灯光", icon = LumiIcons.Reset) { controller.resetLight() }
}

// ---------------------------------------------------------------------------- 画面

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookSection(controller: SceneController, p: RenderPrefs) {
    SectionTitle("风格")
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("经典 MMD", !p.bloom && p.rim == 0f && p.vignette == 0f && p.saturation == 1f) { controller.updatePrefs(p.copy(bloom = false, exposure = 1f, saturation = 1f, rim = 0f, vignette = 0f)) }
        Chip("柔光", p.bloom && p.bloomStrength == 0.7f && p.rim == 0.25f) { controller.updatePrefs(p.copy(bloom = true, bloomStrength = 0.7f, exposure = 1.05f, saturation = 1.05f, rim = 0.25f, vignette = 0.2f)) }
        Chip("鲜艳", p.bloom && p.saturation == 1.3f) { controller.updatePrefs(p.copy(bloom = true, bloomStrength = 0.4f, exposure = 1.08f, saturation = 1.3f, rim = 0.4f, vignette = 0.1f)) }
        Chip("电影", p.bloom && p.vignette == 0.55f) { controller.updatePrefs(p.copy(bloom = true, bloomStrength = 0.9f, exposure = 0.95f, saturation = 0.85f, rim = 0.3f, vignette = 0.55f)) }
    }
    SectionTitle("后期")
    SwitchRow("泛光", p.bloom) { controller.updatePrefs(p.copy(bloom = it)) }
    if (p.bloom) LabeledSlider("泛光强度", p.bloomStrength, 0f..2f) { controller.updatePrefs(p.copy(bloomStrength = it)) }
    LabeledSlider("曝光", p.exposure, 0.5f..1.6f) { controller.updatePrefs(p.copy(exposure = it)) }
    LabeledSlider("饱和度", p.saturation, 0f..1.6f) { controller.updatePrefs(p.copy(saturation = it)) }
    LabeledSlider("边缘光", p.rim, 0f..1f) { controller.updatePrefs(p.copy(rim = it)) }
    LabeledSlider("暗角", p.vignette, 0f..1f) { controller.updatePrefs(p.copy(vignette = it)) }
    SectionTitle("描边")
    SwitchRow("描边", p.edges) { controller.updatePrefs(p.copy(edges = it)) }
    if (p.edges) LabeledSlider("粗细", p.edgeWidth, 0.2f..3f) { controller.updatePrefs(p.copy(edgeWidth = it)) }
}

/** 画风：三渲二。更细的东西（暗部颜色、描边颜色、调色）由脚本的 render.set 或者 AI 来调，这里给开关和「恢复默认」。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleSection(controller: SceneController, s: StyleUi) {
    SectionTitle("三渲二（卡通渲染）")
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("关", s.cel == 0) { controller.setCel(0) }
        Chip("两级（亮 / 暗）", s.cel == 1) { controller.setCel(1) }
        Chip("三级", s.cel == 2) { controller.setCel(2) }
        Chip("四级", s.cel == 3) { controller.setCel(3) }
    }
    Note("把光照切成几块平涂的颜色，像动画片那样。关着的时候用模型自己的 toon 贴图。")
    SwitchRow("所有模型都描边", s.edgeAll, hint = "glTF / OBJ 这类没有描边设置的模型也画上轮廓线") { controller.setEdgeAll(it) }
    if (s.custom) ListRow("画风恢复默认", value = "清掉脚本 / AI 改过的三渲二、描边颜色、调色", valueColor = ink.muted) { controller.resetStyle() }
}

// ---------------------------------------------------------------------------- 导出

@Composable
private fun ExportSection(controller: SceneController, state: UiState) {
    var transparent by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    SectionTitle("图片")
    SwitchRow("透明背景", transparent, hint = "PNG 带透明通道，方便做封面") { transparent = it }
    ListRow("截图", icon = LumiIcons.Photo, value = "存到相册") { controller.screenshot(1, transparent) }
    ListRow("截图 2x", icon = LumiIcons.Photo, value = "两倍分辨率") { controller.screenshot(2, transparent) }
    SectionTitle("文件")
    ListRow("导出动作 VMD", icon = LumiIcons.Save, value = "选中的角色") { controller.exportVmd(false) }
    ListRow("导出镜头 VMD", icon = LumiIcons.Camera) { controller.exportVmd(true) }
    ListRow("把选中的模型存为 PMX", icon = LumiIcons.Cube, enabled = state.models.isNotEmpty()) { saveAs = true }
    Note("导出的文件放在资源库里（「自制动作」「自建模型」），任何 MMD 工具都能读。")
    if (saveAs) {
        var name by remember { mutableStateOf(state.models.firstOrNull { it.id == state.selectedModel }?.name ?: "") }
        LumiDialog(onDismissRequest = { saveAs = false }, title = { Text("存为 PMX") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("文件名") }) },
            confirmButton = { TextButton({ saveAs = false; controller.saveModelAs(name) }) { Text("保存") } },
            dismissButton = { TextButton({ saveAs = false }) { Text("取消") } })
    }
}

// ---------------------------------------------------------------------------- 设置

@Composable
private fun SettingsSection(controller: SceneController, state: UiState, p: RenderPrefs, go: (String) -> Unit, close: () -> Unit) {
    val orientation by controller.orientation.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    ListRow("资源库", icon = LumiIcons.Folder, value = "导入过的文件 · 删除", chevron = true) { go("library") }
    ListRow("新手教程", icon = LumiIcons.Help, value = "再看一遍") { controller.showTutorial(true); close() }
    ChoiceRow("屏幕方向", listOf("自动" to 0, "横屏" to 1, "竖屏" to 2), orientation) { controller.setOrientation(it) }
    SwitchRow("深色界面", p.darkUi, hint = "默认是白底紫罗兰；打开后编辑面板换成深色。画面的背景在「舞台与背景」里换") { controller.updatePrefs(p.copy(darkUi = it)) }
    LabeledSlider("视角", p.fov, 10f..90f, format = ::formatDegrees) { controller.updatePrefs(p.copy(fov = it)) }
    SectionTitle("画质")
    ChoiceRow("抗锯齿", listOf("关" to 1, "2x" to 2, "4x" to 4), if (p.msaa >= 4) 4 else if (p.msaa >= 2) 2 else 1) { controller.updatePrefs(p.copy(msaa = it)) }
    ChoiceRow("分辨率", listOf("50%" to 0.5f, "75%" to 0.75f, "100%" to 1f), p.renderScale) { controller.updatePrefs(p.copy(renderScale = it)) }
    ChoiceRow("贴图上限", listOf("1024" to 1024, "2048" to 2048, "4096" to 4096), p.maxTexture) { controller.updatePrefs(p.copy(maxTexture = it)) }
    if (p.shadows) ChoiceRow("阴影精度", listOf("1024" to 1024, "2048" to 2048, "4096" to 4096), p.shadowSize) { controller.updatePrefs(p.copy(shadowSize = it)) }
    SwitchRow("SDEF 球面变形", p.sdef, hint = "关节处的体积保持更好，略耗性能") { controller.updatePrefs(p.copy(sdef = it)) }
    SwitchRow("显示帧率", p.showFps) { controller.updatePrefs(p.copy(showFps = it)) }
    Note("手机发热或掉帧时，先降分辨率和抗锯齿。贴图上限只对之后加载的模型生效。")
    SectionTitle("其它")
    ListRow("清空场景", icon = LumiIcons.Trash, color = ink.danger) { confirmClear = true }
    Note("手势：单指拖动转视角，双指平移 / 缩放，双击把视角中心移到点中的位置。\nLumi MMD 4.1 · 原生 OpenGL ES 3.0 渲染引擎 · Lua 脚本由 LuaJ 运行")
    if (confirmClear) {
        LumiDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空场景？") },
            text = { Text("会移除场景里的所有模型、动作、镜头、音乐和脚本（资源库里的文件不受影响）。", fontSize = 13.sp) },
            confirmButton = { TextButton({ confirmClear = false; controller.newScene(); close() }) { Text("清空", color = ink.danger) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("取消") } })
    }
}

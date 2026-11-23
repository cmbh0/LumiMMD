package com.lumi.mmd.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.widthIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.mmd.core.Backgrounds
import com.lumi.mmd.core.ImportResult
import com.lumi.mmd.core.LibraryItem
import com.lumi.mmd.core.ModelUi
import com.lumi.mmd.core.PartUi
import com.lumi.mmd.core.SceneController
import com.lumi.mmd.core.UiState
import com.lumi.mmd.engine.scene.ModelInstance
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable

// ============================================================================ 资源库

private fun typeLabel(t: Int) = when (t) {
    LibraryItem.TYPE_MMD_MODEL -> "MMD 模型"
    LibraryItem.TYPE_OTHER_MODEL -> "模型 / 场景"
    LibraryItem.TYPE_MOTION -> "动作"
    LibraryItem.TYPE_POSE -> "姿势"
    else -> "音乐"
}


@Composable
fun LibraryPanel(controller: SceneController, state: UiState, onClose: () -> Unit) {
    val version by controller.libraryVersion.collectAsState()
    var items by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var usage by remember { mutableStateOf(0L) }
    var filter by remember { mutableIntStateOf(-1) }
    var action by remember { mutableStateOf<LibraryItem?>(null) }
    var deletePack by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<LibraryItem?>(null) }

    LaunchedEffect(version) {
        val (list, bytes) = withContext(Dispatchers.IO) { controller.library.scan() to controller.library.totalBytes() }
        items = list; usage = bytes
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MiniChip("全部", filter == -1) { filter = -1 }
            MiniChip("MMD 模型", filter == 0) { filter = 0 }
            MiniChip("模型 / 场景", filter == 1) { filter = 1 }
            MiniChip("动作", filter == 2) { filter = 2 }
            MiniChip("姿势", filter == 3) { filter = 3 }
            MiniChip("音乐", filter == 4) { filter = 4 }
            MiniChip("脚本", filter == 5) { filter = 5 }
        }
        val formats = "支持：PMX / PMD / VMD / VPD / X；Blender 的工程文件 .blend（直接读，不用先导出）；GLB / glTF / VRM / FBX / OBJ / STL / PLY；音乐 MP3 / WAV / OGG 等。带贴图的模型请导入整个 zip 或文件夹。"
        val shown = if (filter < 0) items else items.filter { it.type == filter }
        if (shown.isEmpty()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(if (items.isEmpty()) "资源库是空的。回到画面点右上角的「添加」导入文件，导入过的都会留在这里" else "这个分类下没有文件", color = TextDim, fontSize = 13.sp)
                if (items.isEmpty()) Text(formats, fontSize = 11.sp, color = TextDim, lineHeight = 15.sp, modifier = Modifier.padding(top = 10.dp))
            }
        } else {
            val grouped = shown.groupBy { it.pack }
            LazyColumn(Modifier.fillMaxSize()) {
                for ((pack, list) in grouped) {
                    item(key = "pack:$pack") {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(pack, Modifier.weight(1f), fontSize = 11.5.sp, color = ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("删除整包", Modifier.clickable { deletePack = pack }.padding(horizontal = 4.dp, vertical = 6.dp), fontSize = 11.5.sp, color = ink.danger)
                        }
                    }
                    items(list, key = { it.path }) { item ->
                        ListRow(item.name, value = typeLabel(item.type) + " · " + formatSize(item.sizeBytes),
                            end = { BareIcon(LumiIcons.Trash, "删除 ${item.name}", tint = ink.muted) { deleting = item } }) { action = item }
                    }
                }
                item(key = "footer") { Text("资源库共占用 ${formatSize(usage)}。点一个文件是使用它，右边的垃圾桶是删除（模型会连贴图一起删）；「删除整包」删掉一次导入的全部文件。\n$formats", fontSize = 11.sp, color = TextDim, lineHeight = 15.sp, modifier = Modifier.padding(vertical = 12.dp)) }
            }
        }
    }

    action?.let { item ->
        LumiDialog(
            onDismissRequest = { action = null },
            title = { Text(item.name, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    val close = { action = null; onClose() }
                    when (item.type) {
                        LibraryItem.TYPE_MMD_MODEL, LibraryItem.TYPE_OTHER_MODEL -> {
                            Text("加载到场景，作为：", fontSize = 13.sp, color = TextDim)
                            PillButton("自动判断", Modifier.fillMaxWidth()) { controller.loadModel(item.path, null); close() }
                            PillButton("角色（可套用动作、物理、摆姿势）", Modifier.fillMaxWidth()) { controller.loadModel(item.path, ModelInstance.KIND_CHARACTER); close() }
                            PillButton("舞台 / 场景", Modifier.fillMaxWidth()) { controller.loadModel(item.path, ModelInstance.KIND_STAGE); close() }
                            PillButton("配件（可挂到角色的骨骼上）", Modifier.fillMaxWidth()) { controller.loadModel(item.path, ModelInstance.KIND_ACCESSORY); close() }
                        }
                        LibraryItem.TYPE_MOTION -> {
                            val sel = state.models.firstOrNull { it.id == state.selectedModel }
                            Text(if (sel != null) "当前选中：${sel.name}" else "没有选中模型时会应用到第一个角色。镜头动作会自动识别。", fontSize = 13.sp, color = TextDim)
                            PillButton("应用动作", Modifier.fillMaxWidth()) { controller.applyMotion(item.path); close() }
                            PillButton("叠加到现有动作（表情 / 口型 VMD）", Modifier.fillMaxWidth()) { controller.applyMotion(item.path, merge = true); close() }
                            PillButton("应用到所有角色", Modifier.fillMaxWidth()) { controller.applyMotion(item.path, all = true); close() }
                        }
                        LibraryItem.TYPE_POSE -> PillButton("套用这个姿势", Modifier.fillMaxWidth()) { controller.applyPose(item.path); close() }
                        LibraryItem.TYPE_SCRIPT -> PillButton("放进场景并运行", Modifier.fillMaxWidth()) { controller.play.addScriptFile(item.path); close() }
                        else -> PillButton("设为背景音乐", Modifier.fillMaxWidth()) { controller.setAudio(item.path); close() }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { action = null }) { Text("取消") } },
        )
    }
    deleting?.let { item -> DeleteItemDialog(controller, item, items) { deleting = null } }
    deletePack?.let { pack ->
        LumiDialog(
            onDismissRequest = { deletePack = null },
            title = { Text("删除「$pack」？") },
            text = { Text("会从应用的资源库里删除这个包里的所有文件（不影响你手机里的原文件）。场景里如果正在用它们，也会一起从场景里移除。", fontSize = 13.sp, lineHeight = 19.sp) },
            confirmButton = { TextButton(onClick = { controller.deleteLibraryPack(pack); deletePack = null }) { Text("删除", color = Danger) } },
            dismissButton = { TextButton(onClick = { deletePack = null }) { Text("取消") } },
        )
    }
}

// ============================================================================ 场景

/** 菜单里一个模型的那一页：类别、位置大小、挂载、显示。动作在「动作与音乐」里。 */
@Composable
fun ModelPage(controller: SceneController, state: UiState, m: ModelUi, go: (String) -> Unit = {}) {
    var attach by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(m.summary, Modifier.padding(top = 10.dp), fontSize = 11.sp, color = ink.muted, lineHeight = 15.sp)
        if (m.missingTextures > 0) ListRow("缺 ${m.missingTextures} 张贴图", icon = LumiIcons.Info, color = ink.warn, value = "去找回来", chevron = true) { controller.findTextures(m.id) }
        for (w in m.warnings.take(3)) Text("· $w", Modifier.padding(top = 4.dp), fontSize = 11.sp, color = ink.warn, lineHeight = 15.sp)
        ChoiceRow("类别", listOf("角色" to ModelInstance.KIND_CHARACTER, "舞台" to ModelInstance.KIND_STAGE, "配件" to ModelInstance.KIND_ACCESSORY), m.kind) { controller.setModelKind(m.id, it) }

        SectionTitle("变换")
        ModelTransformSliders(controller, m)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillButton("落地") { controller.dropToGround(m.id) }
            PillButton("复位") { controller.resetModelTransform(m.id) }
            PillButton("缩放到一人高") { controller.fitModelSize(m.id, 20f) }
            PillButton("立起来") { controller.rotateModelBy(m.id, 0, -90f) }
            PillButton("转身") { controller.rotateModelBy(m.id, 1, 90f) }
            PillButton("对准它") { controller.focusSelected() }
        }

        SectionTitle("挂到骨骼上")
        val parent = state.models.firstOrNull { it.id == m.attachTo }
        if (parent != null) ListRow("挂在「${parent.name}」的 ${m.attachBone.ifEmpty { "根" }}", value = "解除", valueColor = ink.danger) { controller.attachModel(m.id, -1, "") }
        else ListRow("挂到…", value = "头饰、道具跟着角色动", enabled = state.models.any { it.id != m.id && it.skinned }, chevron = true) { attach = true }

        SectionTitle("显示")
        LabeledSlider("不透明度", m.opacity, 0f..1f, format = { "%.0f%%".format(it * 100f) }) { controller.setModelOpacity(m.id, it) }
        // 上下层：层数大的整个盖在层数小的上面，不管谁离镜头近
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).bottomLine(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("上下层", fontSize = 13.5.sp)
                Text("层数大的盖在层数小的上面", fontSize = 10.5.sp, color = ink.muted)
            }
            BareIcon(LumiIcons.Minus, "下移一层", enabled = m.layer > -9) { controller.setModelLayer(m.id, m.layer - 1) }
            Text(if (m.layer == 0) "0" else "%+d".format(m.layer), Modifier.widthIn(min = 26.dp).semantics { contentDescription = "层 ${m.layer}" }, fontSize = 14.sp, fontFamily = Mono,
                color = if (m.layer == 0) ink.muted else ink.accent, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            BareIcon(LumiIcons.Plus, "上移一层", enabled = m.layer < 9) { controller.setModelLayer(m.id, m.layer + 1) }
        }
        if (m.skinned) SwitchRow("描边", m.edge) { controller.setModelEdge(m.id, it) }
        SwitchRow("投射阴影", m.castShadow) { controller.setModelShadow(m.id, it) }
        // 碰撞属性：让这个模型变成「实心」的——别的模型的头发裙子、脚本的粒子 / 布 / 水 / 积木、操控的角色会被它挡住
        Column(Modifier.fillMaxWidth().bottomLine().padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("碰撞", Modifier.weight(1f), fontSize = 13.5.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Chip("无", m.collision == 0) { controller.setModelCollision(m.id, 0) }
                    Chip("包围盒", m.collision == 1) { controller.setModelCollision(m.id, 1) }
                    Chip("网格", m.collision == 2) { controller.setModelCollision(m.id, 2) }
                }
            }
            Text(when (m.collision) { 1 -> "按包围盒挡住别的东西（箱子、道具）"; 2 -> "按每个面挡住别的东西（舞台、台阶、桌子）"; else -> "不挡东西。打开后，头发裙子、脚本的粒子和积木、操控的角色会被它挡住" },
                Modifier.padding(top = 4.dp), fontSize = 10.5.sp, lineHeight = 14.sp, color = ink.muted)
        }
        if (m.hasPhysics) {
            SwitchRow("物理演算", m.physics, hint = "头发和裙摆；手感在菜单的「物理」里调") { controller.setModelPhysics(m.id, it) }
        }

        VSpace(8)
        if (m.skinned) ListRow("重置姿势与表情", icon = LumiIcons.Reset) { controller.resetPose(m.id) }
    }
    if (attach) AttachDialog(controller, state, m) { attach = false }
}

private val commonAttachBones = listOf("頭", "首", "上半身2", "上半身", "下半身", "センター", "右手首", "左手首", "右ひじ", "左ひじ", "右肩", "左肩", "右足首", "左足首", "両目")

@Composable
private fun AttachDialog(controller: SceneController, state: UiState, m: ModelUi, onDismiss: () -> Unit) {
    val candidates = state.models.filter { it.id != m.id && it.skinned && it.attachTo != m.id }
    var parent by remember { mutableStateOf(candidates.firstOrNull()) }
    var bones by remember { mutableStateOf<List<String>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(parent?.id) {
        val p = parent
        if (p != null) controller.queryBones(p.id) { bones = it } else bones = emptyList()
    }
    LumiDialog(
        onDismissRequest = onDismiss,
        title = { Text("把「${m.name}」挂到…", fontSize = 16.sp) },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in candidates) Chip(c.name, c.id == parent?.id) { parent = c }
                }
                val p = parent
                if (p == null) { Text("场景里没有带骨骼的模型", fontSize = 13.sp, color = TextDim); return@Column }
                val common = commonAttachBones.filter { it in bones }
                if (common.isNotEmpty()) {
                    Text("常用骨骼", fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (b in common) Chip(b, false) { controller.attachModel(m.id, p.id, b); onDismiss() }
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, label = { Text("搜索骨骼") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                val shown = (if (query.isBlank()) bones else bones.filter { it.contains(query, ignoreCase = true) }).take(200)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 6.dp)) {
                    items(shown) { b ->
                        Text(b, Modifier.fillMaxWidth().clickable { controller.attachModel(m.id, p.id, b); onDismiss() }.padding(vertical = 9.dp, horizontal = 4.dp), fontSize = 14.sp)
                    }
                }
                Text("挂上之后切到「移动模型」模式，可以拖动微调位置、角度和大小。", fontSize = 11.sp, color = TextDim, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ============================================================================ 变换滑杆

/** 缩放用对数刻度：滑杆走一格是乘一个固定的比例，×0.01 到 ×100 都够得着。 */
@Composable
fun ScaleSlider(label: String, scale: Float, modifier: Modifier = Modifier, min: Float = 0.01f, max: Float = 100f, onChange: (Float) -> Unit) {
    LabeledSlider(
        label, log10(scale.coerceIn(min, max)), log10(min)..log10(max), modifier, format = { "×" + (if (10f.pow(it) < 10f) "%.2f" else "%.1f").format(10f.pow(it)) },
        step = 0.01f, toNumber = { 10f.pow(it) }, fromNumber = { log10(it.coerceIn(min, max)) },
    ) { onChange(10f.pow(it)) }
}

/** 一个模型的位置 / 旋转 / 大小，全部是滑杆：拖是粗调，两头的加减号细调，点数值直接输入。 */
@Composable
fun ModelTransformSliders(controller: SceneController, m: ModelUi, which: Int = -1) {
    if (which < 0 || which == 0) {
        for (a in 0 until 3) LabeledSlider("位置 ${"XYZ"[a]}", m.position[a], -50f..50f, step = 0.1f, unbounded = true) { controller.setModelPosition(m.id, a, it) }
    }
    if (which < 0 || which == 1) {
        LabeledSlider("朝向", m.rotation[1], -180f..180f, format = ::formatDegrees, step = 1f) { controller.setModelRotation(m.id, m.rotation[0], it, m.rotation[2]) }
        LabeledSlider("俯仰", m.rotation[0], -90f..90f, format = ::formatDegrees, step = 1f) { controller.setModelRotation(m.id, it, m.rotation[1], m.rotation[2]) }
        LabeledSlider("侧倾", m.rotation[2], -180f..180f, format = ::formatDegrees, step = 1f) { controller.setModelRotation(m.id, m.rotation[0], m.rotation[1], it) }
    }
    if (which < 0 || which == 2) ScaleSlider("缩放", m.scale, min = 0.001f, max = 1000f) { controller.setModelScale(m.id, it) }
}

// ============================================================================ 部件

@Composable
fun PartsPanel(controller: SceneController, state: UiState) {
    val detail by controller.detail.collectAsState()
    val model = state.models.firstOrNull { it.id == state.selectedModel }
    if (model == null) { NeedSelection(state); return }
    val list = rememberLazyListState()
    // 在画面里点中了一个部件：列表滚到它那一行（它的调整项就展开在那一行下面）
    val at = detail.parts.indexOfFirst { it.key == state.selectedPart }
    LaunchedEffect(state.selectedPart, at >= 0) { if (at >= 0) list.animateScrollToItem(at + 1) }
    LazyColumn(Modifier.fillMaxSize().semantics { contentDescription = "部件列表" }, state = list) {
        item(key = "head") {
            Column {
                Text("点一行、或者直接点画面里的部件来选中它：下面展开位移 / 旋转 / 大小的滑杆，也可以在画面里按住它拖。垃圾桶是删除（能撤销）。",
                    fontSize = 11.sp, color = TextDim, lineHeight = 15.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("整块", !state.islandMode, icon = LumiIcons.Layers) { controller.setIslandMode(false) }
                    Chip("细分", state.islandMode, icon = LumiIcons.Mesh) { controller.setIslandMode(true) }
                    IconBtn(LumiIcons.Reset, "全部部件复位", tint = Danger) { controller.resetAllParts() }
                }
            }
        }
        items(detail.parts, key = { it.key }) { p ->
            val sel = p.key == state.selectedPart
            Column(Modifier.fillMaxWidth().padding(start = if (p.island >= 0) 14.dp else 0.dp)) {
                Row(
                    Modifier.fillMaxWidth().bottomLine().then(if (sel) Modifier.background(ink.well) else Modifier)
                        .clickable { controller.selectPart(p.key) }.padding(start = 4.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (sel) ink.accent else ink.text)
                        Text("${p.triangles} 面" + (if (p.islandCount > 1) " · ${p.islandCount} 个连通块" else "") + (if (p.edited) " · 已调整" else ""), fontSize = 10.5.sp,
                            color = if (p.edited) ink.warn else ink.muted)
                    }
                    BareIcon(if (p.visible) LumiIcons.Eye else LumiIcons.EyeOff, if (p.visible) "隐藏 ${p.name}" else "显示 ${p.name}", tint = if (p.visible) ink.text else ink.faint, size = 34.dp, iconSize = 16.dp) { controller.setPartVisible(p.key, !p.visible) }
                    BareIcon(LumiIcons.Trash, "删除部件 ${p.name}", tint = ink.muted, size = 34.dp, iconSize = 15.dp) { controller.deletePart(p.key) }
                }
                if (sel) PartEditor(controller, p, detail.soloPart == p.key)
            }
        }
        if (detail.deletedParts > 0) item(key = "deleted") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已删除 ${detail.deletedParts} 个部件", Modifier.weight(1f), fontSize = 11.5.sp, color = ink.muted)
                PillButton("恢复") { controller.restoreDeletedParts() }
            }
        }
        item(key = "foot") { VSpace(10) }
    }
}

/** 选中的部件展开出来的调整项：位移 / 旋转 / 大小各三四根滑杆，一次只摆一组。 */
@Composable
private fun PartEditor(controller: SceneController, p: PartUi, solo: Boolean) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(ink.well).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Words(listOf("位移" to 0, "旋转" to 1, "大小" to 2), tab, fontSize = 12) { tab = it }
        when (tab) {
            0 -> for (a in 0 until 3) LabeledSlider("位移 ${"XYZ"[a]}", p.offset[a], -5f..5f, step = 0.05f, unbounded = true) { controller.setPartOffset(p.key, a, it) }
            1 -> {
                LabeledSlider("绕 X 转", p.rotation[0], -90f..90f, format = ::formatDegrees, step = 1f) { controller.setPartRotation(p.key, it, p.rotation[1], p.rotation[2]) }
                LabeledSlider("绕 Y 转", p.rotation[1], -180f..180f, format = ::formatDegrees, step = 1f) { controller.setPartRotation(p.key, p.rotation[0], it, p.rotation[2]) }
                LabeledSlider("绕 Z 转", p.rotation[2], -180f..180f, format = ::formatDegrees, step = 1f) { controller.setPartRotation(p.key, p.rotation[0], p.rotation[1], it) }
            }
            else -> {
                val all = Math.cbrt((p.scale[0] * p.scale[1] * p.scale[2]).toDouble()).toFloat()
                ScaleSlider("整体大小", all) { controller.setPartScale(p.key, 3, it) }
                for (a in 0 until 3) ScaleSlider("只改 ${"XYZ"[a]} 方向", p.scale[a]) { controller.setPartScale(p.key, a, it) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("只看它", solo, icon = LumiIcons.Focus) { controller.soloPart(p.key) }
            Chip("复位", false, icon = LumiIcons.Reset) { controller.resetPart(p.key) }
        }
    }
}

@Composable
private fun NeedSelection(state: UiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(if (state.models.isEmpty()) "先在「资源库」里加载一个模型" else "先在画面里点一下模型，或在「场景」里选中它", color = TextDim, fontSize = 13.sp)
    }
}

// ============================================================================ 设置

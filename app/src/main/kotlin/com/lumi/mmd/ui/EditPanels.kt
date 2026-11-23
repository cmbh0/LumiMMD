package com.lumi.mmd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.mmd.core.MeshUi
import com.lumi.mmd.core.Modes
import com.lumi.mmd.core.PlaybackUi
import com.lumi.mmd.core.SceneController
import com.lumi.mmd.core.UiState
import com.lumi.mmd.engine.scene.Editor
import com.lumi.mmd.engine.scene.ModelInstance
import androidx.compose.runtime.saveable.rememberSaveable

/** 编辑时的工具面板：内容随当前工具变化。横屏时在画面右边，竖屏时在画面下面。 */
@Composable
fun EditPanel(controller: SceneController, state: UiState, play: PlaybackUi, meshUi: MeshUi, modifier: Modifier = Modifier, onMenu: (String) -> Unit) {
    val needDetail = state.mode == Modes.BONE || state.mode == Modes.MORPH || state.mode == Modes.PART
    DisposableEffect(needDetail) { controller.wantDetail(needDetail); onDispose { } }
    Column(modifier.background(PanelColor).blockTouches().padding(horizontal = 10.dp).padding(top = 6.dp)) {
        when (state.mode) {
            Modes.MODEL -> ModelOps(controller, state, onMenu)
            Modes.BONE -> BoneOps(controller, state, play)
            Modes.MORPH -> MorphOps(controller, state)
            Modes.CAMERA -> CameraOps(controller, state, play)
            Modes.PART -> PartsPanel(controller, state)
            Modes.MESH -> MeshOps(controller, state, meshUi)
        }
    }
}

/** 移动 / 旋转 / 缩放 + 局部 / 全局。 */
@Composable
private fun ManipRow(controller: SceneController, state: UiState, move: Boolean = true, rotate: Boolean = true, scale: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        ToolBtn(LumiIcons.Move, "移动", selected = state.tool == Editor.TOOL_MOVE, enabled = move, width = 45.dp) { controller.setTool(Editor.TOOL_MOVE) }
        ToolBtn(LumiIcons.Rotate, "旋转", selected = state.tool == Editor.TOOL_ROTATE, enabled = rotate, width = 45.dp) { controller.setTool(Editor.TOOL_ROTATE) }
        ToolBtn(LumiIcons.Scale, "缩放", selected = state.tool == Editor.TOOL_SCALE, enabled = scale, width = 45.dp) { controller.setTool(Editor.TOOL_SCALE) }
        ToolBtn(if (state.localAxes) LumiIcons.Local else LumiIcons.Globe, if (state.localAxes) "局部轴" else "全局轴", width = 52.dp) { controller.setLocalAxes(!state.localAxes) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(vertical = 4.dp), fontSize = 10.5.sp, lineHeight = 14.sp, color = TextDim)
}

// ============================================================================ 模型

@Composable
private fun ModelOps(controller: SceneController, state: UiState, onMenu: (String) -> Unit) {
    val m = state.models.firstOrNull { it.id == state.selectedModel }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ManipRow(controller, state)
        if (m == null) { Hint("在画面里点一下模型来选中它。"); return@Column }
        PanelLabel(m.name)
        ToolGrid {
            ToolBtn(LumiIcons.Ground, "落地") { controller.dropToGround(m.id) }
            ToolBtn(LumiIcons.StandUp, "立起来") { controller.rotateModelBy(m.id, 0, 90f) }
            ToolBtn(LumiIcons.TurnAround, "转身") { controller.rotateModelBy(m.id, 1, 90f) }
            ToolBtn(LumiIcons.Reset, "复位") { controller.resetModelTransform(m.id) }
            ToolBtn(LumiIcons.Focus, "对准") { controller.focusSelected() }
            ToolBtn(if (m.visible) LumiIcons.Eye else LumiIcons.EyeOff, if (m.visible) "显示中" else "已隐藏") { controller.setModelVisible(m.id, !m.visible) }
            ToolBtn(LumiIcons.Settings, "更多") { onMenu("cast/m${m.id}") }
            ToolBtn(LumiIcons.Trash, "移除", tint = Danger) { controller.removeModel(m.id) }
        }
        // 位置 / 旋转 / 大小：和画面里的箭头、圆环是同一组数，这里用滑杆和加减号调
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Words(listOf("位置" to 0, "旋转" to 1, "大小" to 2), tab, Modifier.padding(top = 8.dp), fontSize = 12) { tab = it }
        ModelTransformSliders(controller, m, tab)
        Hint("滑杆粗调，两头的加减号细调（按住连续走），点数值可以直接输入。画面里拖箭头沿一个方向移动，拖圆环绕那根轴旋转；右下角的 X / Y / Z 块也可以拖。")
    }
}

// ============================================================================ 骨骼

@Composable
private fun BoneOps(controller: SceneController, state: UiState, play: PlaybackUi) {
    val detail by controller.detail.collectAsState()
    val model = state.models.firstOrNull { it.id == state.selectedModel }
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        ManipRow(controller, state, move = state.selectedBone < 0 || state.boneMovable, rotate = state.boneRotatable, scale = false)
        if (model == null || !model.skinned) { Hint(if (model == null) "先在画面里点一下角色。" else "「${model.name}」没有骨骼。"); return@Column }
        Row(Modifier.padding(top = 5.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(ink.well).padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (play.boneKeyed) LumiIcons.Key else LumiIcons.Bone, null, Modifier.size(14.dp), tint = if (play.boneKeyed) KeyGold else IconTint)
            Text(if (state.selectedBone >= 0) state.selectedBoneName else "点画面里的蓝色节点选骨骼", Modifier.weight(1f).padding(start = 6.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ToolBtn(LumiIcons.Reset, "骨骼复位", enabled = state.selectedBone >= 0, width = 48.dp) { controller.resetSelectedBone() }
            ToolBtn(LumiIcons.Eraser, "全部复位", width = 48.dp) { controller.resetPose(model.id) }
            ToolBtn(LumiIcons.Focus, "对准", width = 44.dp) { controller.focusSelected() }
            CurveMenu(controller, play.boneKeyed)
        }
        Row(Modifier.padding(top = 5.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, ink.line, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("搜索骨骼…", fontSize = 12.sp, color = ink.faint)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = ink.text, fontSize = 12.sp), cursorBrush = SolidColor(Accent),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索骨骼" })
            }
            if (query.isNotEmpty()) Icon(LumiIcons.Close, "清除搜索", Modifier.size(15.dp).clickable { query = "" }, tint = TextDim)
        }
        val list = if (query.isBlank()) detail.bones else detail.bones.filter { it.name.contains(query, ignoreCase = true) }
        LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
            items(list, key = { it.index }) { b ->
                val sel = b.index == state.selectedBone
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(7.dp)).background(if (sel) SelectedBg else Color.Transparent)
                        .clickable { controller.selectBone(b.index) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(b.name, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (b.ik) Text("IK", fontSize = 10.sp, color = ink.warn) else if (b.movable) Icon(LumiIcons.Move, "可移动", Modifier.size(12.dp), tint = TextDim)
                }
            }
        }
    }
}

/** 补间曲线：点开选一种。 */
@Composable
private fun CurveMenu(controller: SceneController, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolBtn(LumiIcons.Curve, "补间", width = 44.dp) { if (enabled) open = true else controller.setCurve(0) }
        DropdownMenu(open, { open = false }) {
            for ((i, name) in listOf("线性（匀速）", "缓入（慢→快）", "缓出（快→慢）", "缓入缓出").withIndex()) {
                DropdownMenuItem(text = { Text(name, fontSize = 13.sp) }, onClick = { open = false; controller.setCurve(i) })
            }
        }
    }
}

// ============================================================================ 表情

@Composable
private fun MorphOps(controller: SceneController, state: UiState) {
    val detail by controller.detail.collectAsState()
    val model = state.models.firstOrNull { it.id == state.selectedModel }
    if (model == null) { Hint("先在画面里点一下角色。"); return }
    var filter by remember { mutableIntStateOf(-1) }
    // 滑杆拖动时的即时值放在本地，避免等渲染线程回传造成抖动
    val local = remember(detail.modelId, detail.morphs.size) { mutableStateMapOf<Int, Float>() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((id, name) in listOf(-1 to "全部", 2 to "目", 3 to "口", 1 to "眉", 4 to "其他")) MiniChip(name, filter == id) { filter = id }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            IconTextBtn(LumiIcons.Eraser, "归零", color = Danger) { local.clear(); controller.resetMorphs() }
        }
        val list = if (filter < 0) detail.morphs else detail.morphs.filter { if (filter == 4) it.panel == 4 || it.panel == 0 else it.panel == filter }
        if (list.isEmpty()) { Hint(if (detail.morphs.isEmpty()) "「${model.name}」没有表情。" else "这个分类下没有表情。"); return@Column }
        LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
            items(list, key = { it.index }) { mo ->
                val v = (local[mo.index] ?: mo.weight).coerceIn(0f, 1f)
                Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(mo.name, Modifier.weight(1f), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("%.2f".format(v), fontSize = 10.sp, color = TextDim)
                        IconBtn(LumiIcons.KeyAdd, "登记表情 ${mo.name}", size = 26.dp, tint = KeyGold) { local.remove(mo.index); controller.registerMorph(mo.index) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 两头的加减号一下走 0.05
                        StepButton(LumiIcons.Minus, "表情 ${mo.name} 减", enabled = v > 0f, size = 26.dp) { val n = (Math.round((v - 0.05f) * 20f) / 20f).coerceIn(0f, 1f); local[mo.index] = n; controller.setMorph(mo.index, n) }
                        LumiSlider(value = v, onValueChange = { local[mo.index] = it; controller.setMorph(mo.index, it) }, valueRange = 0f..1f,
                            onValueChangeFinished = { }, modifier = Modifier.weight(1f).height(26.dp).semantics { contentDescription = "表情 ${mo.name}" })
                        StepButton(LumiIcons.Plus, "表情 ${mo.name} 加", enabled = v < 1f, size = 26.dp) { val n = (Math.round((v + 0.05f) * 20f) / 20f).coerceIn(0f, 1f); local[mo.index] = n; controller.setMorph(mo.index, n) }
                    }
                }
            }
        }
    }
}

@Composable
fun MiniChip(text: String, selected: Boolean, onClick: () -> Unit) {
    // 分类筛选：一排词，选中的加下划线
    Word(text, selected, fontSize = 12, onClick = onClick)
}

// ============================================================================ 镜头

@Composable
private fun CameraOps(controller: SceneController, state: UiState, play: PlaybackUi) {
    val prefs by controller.prefs.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PanelLabel("视点")
        ToolGrid {
            for ((i, name) in listOf("正面", "背面", "左侧", "右侧", "顶部", "底部").withIndex()) ToolBtn(LumiIcons.Camera, name, width = 46.dp) { controller.cameraPreset(i) }
            ToolBtn(LumiIcons.Focus, "对准", width = 46.dp) { controller.focusSelected() }
            ToolBtn(LumiIcons.Home, "复位", width = 46.dp) { controller.resetCamera() }
        }
        PanelLabel("镜头关键帧（${state.cameraKeys} 个）")
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            IconTextBtn(LumiIcons.KeyAdd, "登记镜头", color = ink.accent, filled = play.cameraKeyed) { controller.registerKey() }
            IconTextBtn(LumiIcons.KeyDelete, "删除", color = Danger, enabled = play.cameraKeyed) { controller.deleteKey() }
        }
        var fov by remember(prefs.fov) { mutableStateOf(prefs.fov) }
        LabeledSlider("视角", fov, 10f..90f, format = { formatDegrees(it) }) { fov = it; controller.setCameraFov(it) }
        if (state.hasCameraMotion) {
            SwitchRow("使用镜头动作", state.useCameraMotion) { controller.setUseCameraMotion(it) }
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                IconTextBtn(LumiIcons.Trash, "清除", color = Danger) { controller.clearCameraMotion() }
            }
        }
        Hint("做镜头动画：先拖时间轴到某一帧，用手指把画面转到想要的角度，点「登记镜头」；换一帧再摆再登记。播放时镜头会在关键帧之间平滑过渡。暂停时转动镜头不会马上被覆盖，换帧后恢复。")
    }
}

// ============================================================================ 建模

private val Palette = listOf(
    0xFFE9EDF2, 0xFFBFC6D0, 0xFF6B7380, 0xFF2A2E36, 0xFFF25C5C, 0xFFFF9B50, 0xFFFFD35C, 0xFF8BD66B,
    0xFF4CC7A8, 0xFF5BC8F5, 0xFF5B7CF5, 0xFF9B6BF2, 0xFFF27EC2, 0xFFB5764A, 0xFFF2D0B0, 0xFFFFFFFF,
).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeshOps(controller: SceneController, state: UiState, mesh: MeshUi) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (!mesh.active) {
            Hint("建模：选中一个已有的模型来修改它，或者从一个基础形体开始新建。")
            PanelLabel("新建模型")
            ShapeRow { controller.newModel(it) }
            if (state.models.isNotEmpty()) {
                PanelLabel("修改已有模型")
                for (m in state.models) IconTextBtn(LumiIcons.Mesh, m.name, Modifier.padding(vertical = 2.dp).fillMaxWidth()) { controller.selectModel(m.id); controller.setMode(Modes.MESH) }
            }
            return@Column
        }
        Text("点 ${mesh.points} · 面 ${mesh.faces}" + when {
            mesh.selFaces > 0 && mesh.selMode == 2 -> "  ｜ 已选 ${mesh.selFaces} 面"
            mesh.selEdges > 0 -> "  ｜ 已选 ${mesh.selEdges} 边"
            mesh.selPoints > 0 -> "  ｜ 已选 ${mesh.selPoints} 点"
            else -> "  ｜ 未选择"
        }, fontSize = 10.5.sp, color = if (mesh.selPoints > 0) KeyGold else TextDim)

        PanelLabel("选择")
        ToolGrid {
            ToolBtn(LumiIcons.Point, "点", selected = mesh.selMode == 0, width = 37.dp) { controller.setMeshSelMode(0) }
            ToolBtn(LumiIcons.Edge, "线", selected = mesh.selMode == 1, width = 37.dp) { controller.setMeshSelMode(1) }
            ToolBtn(LumiIcons.Polygon, "面", selected = mesh.selMode == 2, width = 37.dp) { controller.setMeshSelMode(2) }
            ToolBtn(LumiIcons.MultiSelect, "多选", selected = mesh.additive, width = 37.dp) { controller.setMeshAdditive(!mesh.additive) }
            ToolBtn(LumiIcons.BoxSelect, "框选", selected = mesh.boxSelect, width = 37.dp) { controller.setBoxSelect(!mesh.boxSelect) }
            ToolBtn(LumiIcons.SelectAll, "全选", width = 37.dp) { controller.meshOp("all") }
            ToolBtn(LumiIcons.SelectNone, "取消", width = 37.dp) { controller.meshOp("none") }
            ToolBtn(LumiIcons.Invert, "反选", width = 37.dp) { controller.meshOp("invert") }
            ToolBtn(LumiIcons.Link, "相连", width = 37.dp) { controller.meshOp("linked") }
            ToolBtn(LumiIcons.Grow, "扩大", width = 37.dp) { controller.meshOp("grow") }
        }

        PanelLabel("变换")
        ManipRow(controller, state)

        PanelLabel("编辑")
        ToolGrid {
            ToolBtn(LumiIcons.Extrude, "挤出", width = 37.dp) { controller.meshOp("extrude") }
            ToolBtn(LumiIcons.Inset, "内插", width = 37.dp) { controller.meshOp("inset") }
            ToolBtn(LumiIcons.Subdivide, "细分", width = 37.dp) { controller.meshOp("subdivide") }
            ToolBtn(LumiIcons.LoopCut, "环切", width = 37.dp) { controller.meshOp("loopcut") }
            ToolBtn(LumiIcons.SplitEdge, "加点", width = 37.dp) { controller.meshOp("split") }
            ToolBtn(LumiIcons.Duplicate, "复制", width = 37.dp) { controller.meshOp("duplicate") }
            ToolBtn(LumiIcons.Trash, "删除", tint = Danger, width = 37.dp) { controller.meshOp("delete") }
            ToolBtn(LumiIcons.Dissolve, "溶解", width = 37.dp) { controller.meshOp("dissolve") }
            ToolBtn(LumiIcons.Merge, "合并", width = 37.dp) { controller.meshOp("merge") }
            ToolBtn(LumiIcons.Weld, "焊接", width = 37.dp) { controller.meshOp("weld") }
            ToolBtn(LumiIcons.Fill, "补面", width = 37.dp) { controller.meshOp("fill") }
            ToolBtn(LumiIcons.Flip, "翻转", width = 37.dp) { controller.meshOp("flip") }
            ToolBtn(LumiIcons.Mirror, "镜像", width = 37.dp, onLongClick = { controller.meshOp("mirrorZ") }) { controller.meshOp("mirrorX") }
            ToolBtn(LumiIcons.Solidify, "加厚", width = 37.dp) { controller.meshOp("solidify") }
            ToolBtn(LumiIcons.Smooth, "松弛", width = 37.dp) { controller.meshOp("smooth") }
            ToolBtn(LumiIcons.ShadeSmooth, "平滑", width = 37.dp) { controller.meshOp("shadeSmooth") }
            ToolBtn(LumiIcons.ShadeFlat, "平直", width = 37.dp) { controller.meshOp("shadeFlat") }
            ToolBtn(LumiIcons.Triangulate, "三角", width = 37.dp) { controller.meshOp("triangulate") }
            ToolBtn(LumiIcons.Uv, "展UV", width = 37.dp) { controller.meshOp("uv") }
            ToolBtn(LumiIcons.Focus, "对准", width = 37.dp) { controller.focusSelected() }
        }

        PanelLabel("添加形体")
        ShapeRow { controller.meshAddShape(it) }

        PanelLabel("材质（先选面再点 = 换材质；没选面时点 = 选中它的面）")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (mt in mesh.materials.take(40)) MaterialSwatch(mt.name, mt.color, controller, mt.index)
            var open by remember { mutableStateOf(false) }
            Box {
                Box(Modifier.size(30.dp).clip(CircleShape).border(1.dp, Accent, CircleShape).clickable { open = true }.semantics { contentDescription = "新建材质" }, contentAlignment = Alignment.Center) {
                    Icon(LumiIcons.Plus, null, Modifier.size(15.dp), tint = Accent)
                }
                ColorMenu(open, { open = false }) { controller.meshAddMaterial(it) }
            }
        }

        if (mesh.bones.size > 1) {
            PanelLabel("跟随骨骼")
            var open by remember { mutableStateOf(false) }
            Box {
                IconTextBtn(LumiIcons.Bone, "把选中部分绑到骨骼…") { open = true }
                DropdownMenu(open, { open = false }) {
                    for (b in mesh.bones.take(200)) DropdownMenuItem(text = { Text(b, fontSize = 13.sp) }, onClick = { open = false; controller.meshBindBone(b) })
                }
            }
        }

        VSpace(8)
        Hint("点左上角的「完成」（或换到别的工具）时，改动会存成 PMX 放进资源库：原文件不动，另存为 *_edit.pmx。建模时模型保持初始姿势。")
    }
}

@Composable
private fun ShapeRow(onPick: (String) -> Unit) {
    ToolGrid {
        val shapes: List<Triple<ImageVector, String, String>> = listOf(
            Triple(LumiIcons.Cube, "方块", "box"), Triple(LumiIcons.Sphere, "球", "sphere"), Triple(LumiIcons.Cylinder, "圆柱", "cylinder"),
            Triple(LumiIcons.Cone, "圆锥", "cone"), Triple(LumiIcons.Torus, "圆环", "torus"), Triple(LumiIcons.Plane, "平面", "plane"))
        for ((icon, name, id) in shapes) ToolBtn(icon, name, width = 37.dp) { onPick(id) }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MaterialSwatch(name: String, argb: Int, controller: SceneController, index: Int) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(Color(argb or (0xFF shl 24))).border(1.5.dp, ink.line, CircleShape)
                .semantics { contentDescription = "材质 $name" }
                .combinedClickable(onClick = { controller.meshAssignMaterial(index) }, onLongClick = { open = true }),
        )
        ColorMenu(open, { open = false }) { controller.meshSetMaterialColor(index, it) }
    }
}

/** 一个小调色板：点一个颜色。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorMenu(open: Boolean, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    DropdownMenu(open, onDismiss) {
        FlowRow(Modifier.width(168.dp).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 4) {
            for (c in Palette) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(c)).border(1.dp, ink.line, CircleShape).clickable { onDismiss(); onPick(c) }
                    .semantics { contentDescription = "颜色 %06X".format(c and 0xFFFFFF) })
            }
        }
    }
}

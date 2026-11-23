package com.lumi.mmd.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.mmd.core.ClipUi
import com.lumi.mmd.core.ControlUi
import com.lumi.mmd.core.LaneUi
import com.lumi.mmd.core.Lanes
import com.lumi.mmd.core.OverlayItem
import com.lumi.mmd.core.PlaybackUi
import com.lumi.mmd.core.SceneController
import com.lumi.mmd.core.Slot
import com.lumi.mmd.core.UiState
import kotlin.math.max
import kotlin.math.roundToInt

// ============================================================================ 操控模式：左边摇杆，右边蹲 / 趴 / 跳 / 冲刺

/**
 * 操控模式盖在画面上的那一层。
 * 空白处拖动 = 转视角、双指 = 拉远近，都在这一层自己收（不交给下面的渲染视图）：
 * 这样左手推着摇杆、右手转视角时，两根手指是分开算的，不会被当成一次双指缩放。
 */
@Composable
fun ControlLayer(controller: SceneController, control: ControlUi, name: String, wide: Boolean) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().semantics { contentDescription = "转视角" }.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var span = 0f
                    while (true) {
                        val e = awaitPointerEvent()
                        val pressed = e.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size == 1) {
                            val d = pressed[0].positionChange()
                            if (d != Offset.Zero) controller.lookDrag(d.x, d.y)
                            span = 0f
                        } else {
                            val now = (pressed[0].position - pressed[1].position).getDistance()
                            if (span > 10f && now > 10f) controller.lookZoom(now / span)
                            span = now
                        }
                        e.changes.forEach { it.consume() }
                    }
                }
            },
        )
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val shadowed = TextStyle(shadow = sceneShadow())
            Column(Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 10.dp)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink.text, maxLines = 1, style = shadowed)
                Text(
                    if (!control.animated) "这个模型没有标准的腿部骨骼，只能平移" else control.state + if (control.sprintLock) " · 自动冲刺" else "",
                    Modifier.semantics { contentDescription = "操控状态" }, fontSize = 11.sp, color = ink.muted, style = shadowed,
                )
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 8.dp).clip(RoundedCornerShape(50)).background(onScene(Color(0x66120C24), Color(0xCCFFFFFF)))
                    .border(HairWidth, onScene(Color(0x66E3D9FF), Color(0x402B1A66)), RoundedCornerShape(50)).clickable { controller.play.setControl(false) }
                    .padding(horizontal = 13.dp, vertical = 7.dp).semantics(mergeDescendants = true) { contentDescription = "退出操控"; role = Role.Button },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 亮背景上这颗药丸是白的，字就得是深色（以前写死成白字，换成浅色背景之后看不见）
                val exitInk = onScene(Color.White, Color(0xFF241B3D))
                Icon(LumiIcons.Close, null, Modifier.size(13.dp), tint = exitInk)
                Text("退出操控", Modifier.padding(start = 5.dp), fontSize = 12.sp, color = exitInk)
            }

            // 左：摇杆
            Joystick(Modifier.align(Alignment.BottomStart).padding(start = if (wide) 34.dp else 18.dp, bottom = if (wide) 20.dp else 34.dp)) { x, y -> controller.play.stick(x, y) }

            // 右：冲刺在上，跳在右，蹲和趴在下面一排（和吃鸡手游的摆法一样）
            Box(Modifier.align(Alignment.BottomEnd).padding(end = if (wide) 28.dp else 14.dp, bottom = if (wide) 16.dp else 30.dp).size(196.dp, 176.dp)) {
                PadButton(LumiIcons.Sprint, "冲刺", control.sprintLock, 54.dp, Modifier.offset(132.dp, 0.dp),
                    onDown = { controller.play.button("sprint", true) },
                    onUp = { held -> controller.play.button("sprint", false); if (held < 260L) controller.play.setSprintLock(!control.sprintLock) })
                PadButton(LumiIcons.Jump, "跳跃", false, 68.dp, Modifier.offset(124.dp, 60.dp),
                    onDown = { controller.play.button("jump", true) }, onUp = { controller.play.button("jump", false) })
                PadButton(LumiIcons.Crouch, "下蹲", control.stance == 1, 54.dp, Modifier.offset(62.dp, 118.dp),
                    onDown = { controller.play.button("crouch", true) }, onUp = { controller.play.button("crouch", false) })
                PadButton(LumiIcons.Prone, "趴下", control.stance == 2, 54.dp, Modifier.offset(0.dp, 118.dp),
                    onDown = { controller.play.button("prone", true) }, onUp = { controller.play.button("prone", false) })
            }
        }
    }
}

/** 摇杆：按住往哪边推，角色就朝镜头的那个方向走；推到一半以上是跑。 */
@Composable
private fun Joystick(modifier: Modifier = Modifier, onMove: (Float, Float) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var held by remember { mutableStateOf(false) }
    val reach = with(LocalDensity.current) { 46.dp.toPx() }
    val move by rememberUpdatedState(onMove)
    DisposableEffect(Unit) { onDispose { move(0f, 0f) } }
    Box(
        modifier.size(140.dp).semantics { contentDescription = "摇杆" }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val center = Offset(size.width / 2f, size.height / 2f)
                fun push(p: Offset) {
                    var d = p - center
                    val len = d.getDistance()
                    if (len > reach) d = d * (reach / len)
                    knob = d
                    move(d.x / reach, -d.y / reach)
                }
                held = true
                push(down.position)
                try {
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        push(c.position)
                    }
                } finally {
                    held = false; knob = Offset.Zero; move(0f, 0f)
                }
            }
        },
    ) {
        val base = onScene(Color(0x33120C24), Color(0x2E7B4DFF))
        val ring = onScene(Color(0x8CE3D9FF), Color(0xA65B3FD0))
        val half = onScene(Color(0x33E3D9FF), Color(0x4D5B3FD0))
        val knobOn = onScene(Color(0xE6B9A2FF), Color(0xE67B4DFF))
        val knobOff = onScene(Color(0x99E3D9FF), Color(0xB38E6BFF))
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f - 2.dp.toPx()
            drawCircle(base, r)
            drawCircle(ring, r, style = Stroke(1.5.dp.toPx()))
            // 一半的位置：过了这一圈就是跑
            drawCircle(half, reach * 0.55f, style = Stroke(1.dp.toPx()))
            drawCircle(if (held) knobOn else knobOff, 25.dp.toPx(), center + knob)
            drawCircle(Color(0x40FFFFFF), 25.dp.toPx(), center + knob, style = Stroke(1.dp.toPx()))
        }
    }
}

/** 操控模式右边的圆按钮。按下 / 松开各报告一次（松开时带上按了多少毫秒）。 */
@Composable
private fun PadButton(icon: ImageVector, label: String, on: Boolean, size: Dp, modifier: Modifier = Modifier, onDown: () -> Unit, onUp: (Long) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    Box(
        modifier.size(size).clip(CircleShape)
            .background(if (pressed) Color(0xCC8E6BFF) else if (on) Color(0x998E6BFF) else onScene(Color(0x40120C24), Color(0xA6FFFFFF)))
            .border(1.5.dp, if (on || pressed) onScene(Color(0xFFE3D9FF), Violet) else onScene(Color(0x8CE3D9FF), Color(0x995B3FD0)), CircleShape)
            .semantics(mergeDescendants = true) { contentDescription = label; role = Role.Button; selected = on }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    d.consume()
                    val t0 = System.nanoTime()
                    pressed = true
                    down()
                    try {
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == d.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                        }
                    } finally {
                        pressed = false
                        up((System.nanoTime() - t0) / 1_000_000L)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val face = if (on || pressed) Color.White else onScene(Color.White, Color(0xFF3A2A6B))
            Icon(icon, null, Modifier.size(size * 0.42f), tint = face)
            Text(label, fontSize = 9.sp, lineHeight = 11.sp, color = face.copy(alpha = 0.9f))
        }
    }
}

// ============================================================================ 脚本放在屏幕上的字和按钮

@Composable
fun ScriptOverlay(controller: SceneController, items: List<OverlayItem>) {
    if (items.isEmpty()) return
    // 每一项以它的 (x, y)（屏幕的 0..1）为中心摆放，超出屏幕的部分往里收
    androidx.compose.ui.layout.Layout(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        content = { for (it in items) androidx.compose.runtime.key(it.key) { OverlayView(controller, it) } },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placed = measurables.map { it.measure(loose) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            for ((i, pl) in placed.withIndex()) {
                val item = items.getOrNull(i) ?: continue
                val x = (item.x.coerceIn(0f, 1f) * constraints.maxWidth - pl.width / 2f).roundToInt().coerceIn(0, max(0, constraints.maxWidth - pl.width))
                val y = (item.y.coerceIn(0f, 1f) * constraints.maxHeight - pl.height / 2f).roundToInt().coerceIn(0, max(0, constraints.maxHeight - pl.height))
                pl.place(x, y)
            }
        }
    }
}

@Composable
private fun OverlayView(controller: SceneController, item: OverlayItem) {
    if (!item.button) {
        Text(
            item.text, Modifier.padding(horizontal = 12.dp).semantics { contentDescription = "脚本文字 ${item.text}" }, fontSize = item.size.coerceIn(8f, 64f).sp, color = scriptTextColor(item.color),
            style = TextStyle(shadow = sceneShadow()), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        return
    }
    var pressed by remember { mutableStateOf(false) }
    val id by rememberUpdatedState(item.id)
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (pressed) Color(0xCC8E6BFF) else onScene(Color(0x73120C24), Color(0xD9FFFFFF)))
            .border(1.dp, onScene(Color(0xB3E3D9FF), Color(0x805B3FD0)), RoundedCornerShape(50))
            .semantics(mergeDescendants = true) { contentDescription = "脚本按钮 ${item.text}"; role = Role.Button }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    d.consume()
                    pressed = true
                    controller.play.button(id, true)
                    try {
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { q -> q.id == d.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                        }
                    } finally { pressed = false; controller.play.button(id, false) }
                }
            }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) { Text(item.text, fontSize = 13.sp, color = if (pressed) Color.White else onScene(Color.White, Color(0xFF241B3D)), maxLines = 1) }
}

// ============================================================================ 轨道：像剪辑软件那样摆放片段

private val LaneHeight = 36.dp
private val LaneHead = 78.dp

private fun laneColor(kind: Int): Color = when (kind) {
    Lanes.CAMERA -> Color(0xFF5FB7E8)
    Lanes.AUDIO -> Color(0xFF58C99A)
    Lanes.SCRIPT -> Color(0xFFF2B34C)
    else -> Color(0xFF9C7BFF)
}

private fun laneIcon(kind: Int): ImageVector = when (kind) {
    Lanes.CAMERA -> LumiIcons.Camera
    Lanes.AUDIO -> LumiIcons.Music
    Lanes.SCRIPT -> LumiIcons.Code
    else -> LumiIcons.Person
}

/**
 * 轨道面板：镜头、每个角色、音乐、每个脚本各占一条；片段可以拖动、拖两头裁剪、在当前帧分割、复制、删除。
 * 顶上的标尺点一下 / 拖动是跳帧；空白处左右拖是滚动。
 */
@Composable
fun ClipTracks(
    controller: SceneController, state: UiState, play: PlaybackUi, modifier: Modifier = Modifier,
    onPick: (Int, Int) -> Unit, onMenu: (String) -> Unit,
) {
    val p = state.play
    val lanes = p.lanes
    val density = LocalDensity.current
    val total = max(max(play.maxFrame, state.audioFrames), 150) + 90
    /** 一帧多少 dp；0 = 还没定（第一次摆放时按总长铺满）。 */
    var dpPerFrame by rememberSaveable { mutableFloatStateOf(0f) }
    /** 左边缘是第几帧。 */
    var scroll by rememberSaveable { mutableFloatStateOf(0f) }
    var addOpen by remember { mutableStateOf(false) }
    var builtins by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val selLane = lanes.firstOrNull { it.kind == p.clipLane && it.id == p.clipLaneId }
    val selClip = selLane?.clips?.firstOrNull { it.id == p.clip }
    val targetModel = (selLane?.takeIf { it.kind == Lanes.MODEL }?.id)
        ?: state.models.firstOrNull { it.id == state.selectedModel && it.skinned }?.id ?: state.models.firstOrNull { it.skinned }?.id ?: -1

    Column(modifier.background(ink.paper).blockTouches()) {
        Hairline()
        // 工具条
        Row(Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconTextBtn(LumiIcons.Plus, "添加", filled = true) { addOpen = true }
                DropdownMenu(addOpen, { addOpen = false; builtins = null }) {
                    val list = builtins
                    if (list == null) {
                        // 动作、镜头、音乐都从同一页里挑（那一页最上面是「从手机里选文件」），接在各自轨道的最后
                        MenuItem(LumiIcons.Folder, "动作、镜头、音乐（接在后面）") { addOpen = false; onMenu("clips/pick${Slot.CLIP}") }
                        MenuItem(LumiIcons.Sprint, "内置动作：走、跑、蹲、趴…") {
                            if (targetModel < 0) { addOpen = false; controller.toast("先添加一个角色") }
                            else controller.play.builtinMotions(targetModel) { got ->
                                if (got.isEmpty()) { addOpen = false; controller.toast("这个模型没有标准的腿部骨骼，用不了内置动作") } else builtins = got
                            }
                        }
                    } else {
                        for ((id, title) in list) MenuItem(LumiIcons.Sprint, title) { addOpen = false; builtins = null; controller.play.addBuiltin(targetModel, id) }
                    }
                }
            }
            HSpace(4)
            val motionClip = selClip != null && (p.clipLane == Lanes.MODEL || p.clipLane == Lanes.CAMERA)
            val editable = motionClip || (selClip != null && p.clipLane == Lanes.AUDIO)
            ToolBtn(LumiIcons.Scissors, "分割", enabled = editable, width = 44.dp) { controller.play.splitClip() }
            ToolBtn(LumiIcons.Copy, "复制", enabled = editable, width = 44.dp) { controller.play.duplicateClip() }
            ToolBtn(LumiIcons.Trash, "删除", enabled = selClip != null, width = 44.dp) { controller.play.deleteClip() }
            // 过渡：这一段开头的若干帧里，从前一段的姿势平滑地变过来（轨道上的第一段没有前一段，用不了）
            Box {
                var fadeOpen by remember { mutableStateOf(false) }
                ToolBtn(LumiIcons.Curve, "过渡", enabled = motionClip && selClip?.canFade == true, selected = (selClip?.fade ?: 0) > 0, width = 44.dp) { fadeOpen = true }
                DropdownMenu(fadeOpen, { fadeOpen = false }) {
                    for ((label, frames) in FadeChoices) {
                        DropdownMenuItem(
                            text = { Text(label, fontSize = 13.sp, color = if (selClip?.fade == frames) ink.accent else ink.text) },
                            onClick = { fadeOpen = false; controller.play.setClipFade(frames) },
                        )
                    }
                }
            }
            if (p.clipLane == Lanes.SCRIPT && selLane != null) {
                ToolBtn(LumiIcons.Loop, "全程", width = 44.dp) { controller.play.scriptWholeTimeline(selLane.id) }
                ToolBtn(LumiIcons.Code, "代码", width = 44.dp) { onMenu("script/lua${selLane.id}") }
            }
            BarSep()
            ToolBtn(LumiIcons.Minus, "缩小", width = 44.dp) { dpPerFrame = (dpPerFrame / 1.6f).coerceAtLeast(0.02f) }
            ToolBtn(LumiIcons.Plus, "放大", width = 44.dp) { dpPerFrame = (dpPerFrame * 1.6f).coerceAtMost(14f) }
            ToolBtn(LumiIcons.Focus, "全览", width = 44.dp) { dpPerFrame = 0f; scroll = 0f }
            HSpace(8)
            Text(
                when {
                    selClip == null -> "点一个片段选中它"
                    p.clipLane == Lanes.AUDIO -> "第 ${selClip.start}–${selClip.end} 帧" + (if (selClip.trimIn > 0) " · 从这首歌的 ${formatTime(selClip.trimIn.toFloat())} 起" else "")
                    p.clipLane == Lanes.SCRIPT -> "第 ${selClip.start}–${selClip.end} 帧里运行"
                    else -> "第 ${selClip.start}–${selClip.end} 帧" + (if (selClip.trimIn > 0) " · 从原动作的第 ${selClip.trimIn} 帧起" else "") +
                        (if (selClip.fade > 0) " · 过渡 ${selClip.fade} 帧" else "")
                },
                Modifier.semantics { contentDescription = "片段信息" }, fontSize = 11.sp, color = ink.muted, maxLines = 1,
            )
            HSpace(8)
        }
        Hairline()
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val areaDp = (maxWidth - LaneHead).coerceAtLeast(40.dp)
            if (dpPerFrame <= 0f) dpPerFrame = (areaDp.value / total).coerceIn(0.02f, 14f)
            val ppf = with(density) { dpPerFrame.dp.toPx() }
            val areaPx = with(density) { areaDp.toPx() }
            val maxScroll = max(0f, total - areaPx / ppf * 0.6f)
            if (scroll > maxScroll) scroll = maxScroll
            val ppfNow by rememberUpdatedState(ppf)
            val maxScrollNow by rememberUpdatedState(maxScroll)
            // 播放时让当前帧一直看得见
            LaunchedEffect(play.playing, (play.frame / 10f).toInt()) {
                if (play.playing) {
                    val visible = areaPx / ppf
                    if (play.frame < scroll || play.frame > scroll + visible * 0.92f) scroll = (play.frame - visible * 0.1f).coerceIn(0f, maxScroll)
                }
            }
            val lineColor = ink.line; val mutedColor = ink.muted; val accent = ink.accent; val wellColor = ink.well
            Column(Modifier.fillMaxSize()) {
                // 标尺：点一下 / 拖动 = 跳到那一帧
                Row(Modifier.fillMaxWidth().height(22.dp)) {
                    Box(Modifier.width(LaneHead).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                        Text("${play.frame.roundToInt()} 帧", Modifier.padding(start = 10.dp), fontSize = 10.sp, fontFamily = Mono, color = ink.muted)
                    }
                    val scrollNow by rememberUpdatedState(scroll)
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clipToBounds().semantics { contentDescription = "轨道标尺" }
                            .pointerInput(Unit) { detectTapGestures { o -> controller.seekFrame((scrollNow + o.x / ppfNow).roundToInt().coerceAtLeast(0)) } }
                            .pointerInput(Unit) { detectDragGestures { change, _ -> change.consume(); controller.seekFrame((scrollNow + change.position.x / ppfNow).roundToInt().coerceAtLeast(0)) } },
                    ) {
                        val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 48)
                        Canvas(Modifier.fillMaxSize()) { drawClipRuler(measurer, scroll, ppf, play.frame, lineColor, mutedColor, accent) }
                    }
                }
                Hairline()
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        for (lane in lanes) {
                            Row(Modifier.fillMaxWidth().height(LaneHeight).bottomLine()) {
                                // 轨道名
                                Row(
                                    Modifier.width(LaneHead).fillMaxHeight().padding(start = 8.dp, end = 4.dp).semantics(mergeDescendants = true) { contentDescription = "轨道 ${lane.name}" },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(laneIcon(lane.kind), null, Modifier.size(13.dp), tint = laneColor(lane.kind))
                                    Text(lane.name, Modifier.padding(start = 5.dp), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                // 片段区：空白处左右拖 = 滚动
                                Box(
                                    Modifier.weight(1f).fillMaxHeight().clipToBounds().background(wellColor.copy(alpha = wellColor.alpha * 0.5f))
                                        .pointerInput(Unit) {
                                            detectDragGestures { change, drag -> change.consume(); scroll = (scroll - drag.x / ppfNow).coerceIn(0f, maxScrollNow) }
                                        },
                                ) {
                                    for (clip in lane.clips) {
                                        ClipBox(controller, lane, clip, selected = p.clipLane == lane.kind && p.clipLaneId == lane.id && p.clip == clip.id, scroll = scroll, ppf = ppf)
                                    }
                                }
                            }
                        }
                        if (lanes.all { it.clips.isEmpty() }) {
                            Text("轨道上还没有东西。点左上的「添加」放一段动作、镜头或者音乐。",
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp), fontSize = 11.5.sp, lineHeight = 17.sp, color = ink.muted)
                        }
                    }
                    // 当前帧的竖线
                    Canvas(Modifier.fillMaxSize().padding(start = LaneHead)) {
                        val x = (play.frame - scroll) * ppf
                        if (x in 0f..size.width) drawLine(accent, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
                    }
                }
            }
        }
    }
}

/** 过渡可以选多长（帧）。 */
private val FadeChoices = listOf("直接切换" to 0, "0.25 秒" to 8, "0.5 秒" to 15, "1 秒" to 30, "2 秒" to 60, "3 秒" to 90)

/**
 * 在画布上写一行字。
 *
 * 不用 drawText(measurer, 字符串, 位置, …) 那个版本：它会按「画布右边还剩多宽」去排版，
 * 字的起点贴着（或者超出）画布右边缘时算出来的宽度是负数，直接抛异常——
 * 4.0 的轨道标尺就是这样把应用带崩的（刻度的数字正好落在最右边 3dp 之内时）。
 * 这里先不限宽度把字排好，再画上去：位置在哪都不会出错，超出画布的部分自然被裁掉。
 */
internal fun DrawScope.drawLabel(measurer: androidx.compose.ui.text.TextMeasurer, text: String, topLeft: Offset, style: TextStyle) {
    drawText(measurer.measure(text, style, softWrap = false, maxLines = 1), topLeft = topLeft)
}

/** 轨道顶上的标尺：刻度线、帧号 / 时间、当前帧的小方块。scroll = 左边缘是第几帧，ppf = 一帧多少像素。 */
internal fun DrawScope.drawClipRuler(measurer: androidx.compose.ui.text.TextMeasurer, scroll: Float, ppf: Float, frame: Float, line: Color, text: Color, accent: Color) {
    if (ppf <= 0f || size.width <= 0f) return
    // 刻度间隔：挑一个让相邻两个数字隔开 56dp 以上的
    val want = 56.dp.toPx() / ppf
    val step = intArrayOf(5, 10, 15, 30, 60, 150, 300, 600, 900, 1800, 3600, 9000, 18000).firstOrNull { it >= want } ?: 36000
    val style = TextStyle(fontSize = 9.sp, color = text, fontFamily = Mono)
    var f = ((scroll / step).toInt()) * step
    var guard = 0
    while ((f - scroll) * ppf < size.width && guard++ < 400) {
        val x = (f - scroll) * ppf
        if (x >= 0f) {
            drawLine(line, Offset(x, size.height * 0.45f), Offset(x, size.height), 1f)
            drawLabel(measurer, if (step >= 30) formatTime(f.toFloat()) else "$f", Offset(x + 3.dp.toPx(), 1.dp.toPx()), style)
        }
        f += step
    }
    val px = (frame - scroll) * ppf
    if (px in 0f..size.width) drawRect(accent, Offset(px - 4.dp.toPx(), size.height - 7.dp.toPx()), Size(8.dp.toPx(), 7.dp.toPx()))
}

/** 轨道上的一个片段：点 = 选中，拖 = 挪动；选中后两头各有一个把手，拖把手 = 裁剪。 */
@Composable
private fun ClipBox(controller: SceneController, lane: LaneUi, clip: ClipUi, selected: Boolean, scroll: Float, ppf: Float) {
    val density = LocalDensity.current
    val color = laneColor(lane.kind)
    val widthPx = ((clip.end - clip.start) * ppf).coerceAtLeast(with(density) { 6.dp.toPx() })
    val current by rememberUpdatedState(clip)
    val ppfNow by rememberUpdatedState(ppf)
    val movable = true
    val shape = RoundedCornerShape(5.dp)
    // 过渡：片段开头画一个斜角，宽度就是过渡的那几帧
    val fadePx = if (clip.fade > 0 && clip.canFade) (clip.fade * ppf).coerceAtMost(widthPx) else 0f
    // 白底上：过渡的斜角是"从白里淡入"，选中的边框和把手用这条轨道自己的颜色；深色界面下还是原来的深色斜角、白边
    val light = ink.light
    val wedgeColor = if (light) Color(0xD9FFFFFF) else Color(0xB3120C24)
    val wedgeLine = if (light) color else Color.White.copy(alpha = 0.75f)
    val selectedEdge = if (light) color else Color.White
    val handleColor = if (light) color else Color(0xE6FFFFFF)
    val gripColor = if (light) Color.White else Color(0xFF3B2C6E)
    Box(
        Modifier.offset { IntOffset(((clip.start - scroll) * ppf).roundToInt(), 0) }
            .padding(vertical = 4.dp).width(with(density) { widthPx.toDp() }).fillMaxHeight()
            .clip(shape).background(color.copy(alpha = if (selected) 0.62f else 0.30f))
            .drawBehind {
                if (fadePx > 0.5f) {
                    val wedge = androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(fadePx, 0f); lineTo(0f, size.height); close() }
                    drawPath(wedge, wedgeColor)
                    drawLine(wedgeLine, Offset(0f, size.height), Offset(fadePx, 0f), 1.dp.toPx())
                }
            }
            .border(if (selected) 1.5.dp else HairWidth, if (selected) selectedEdge else color, shape)
            .semantics(mergeDescendants = true) {
                contentDescription = "片段 ${if (lane.kind == Lanes.SCRIPT) lane.name else clip.name}"; this.selected = selected
                stateDescription = "第 ${clip.start} 到 ${clip.end} 帧" + if (clip.fade > 0) "，过渡 ${clip.fade} 帧" else ""
            }
            .pointerInput(lane.kind, lane.id, clip.id) { detectTapGestures { controller.play.selectClip(lane.kind, lane.id, clip.id) } }
            .then(if (!movable) Modifier else Modifier.pointerInput(lane.kind, lane.id, clip.id) {
                var from = 0; var moved = 0f; var first = true
                detectDragGestures(
                    onDragStart = { from = current.start; moved = 0f; first = true; controller.play.selectClip(lane.kind, lane.id, clip.id) },
                ) { change, drag ->
                    change.consume()
                    moved += drag.x
                    controller.play.dragClip(lane.kind, lane.id, clip.id, move = true, start = (from + moved / ppfNow).roundToInt().coerceAtLeast(0), end = null, begin = first)
                    first = false
                }
            }),
    ) {
        Text(
            clip.name + (if (clip.tag.isNotEmpty()) " · ${clip.tag}" else ""), Modifier.align(Alignment.CenterStart).padding(horizontal = if (selected && movable) 14.dp else 6.dp),
            fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false,
        )
        if (selected && movable) {
            // 两头的把手
            for (left in listOf(true, false)) {
                Box(
                    Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd).width(13.dp).fillMaxHeight().background(handleColor)
                        .semantics { contentDescription = if (left) "片段开头" else "片段结尾" }
                        .pointerInput(lane.kind, lane.id, clip.id, left) {
                            var from = 0; var moved = 0f; var first = true
                            detectDragGestures(onDragStart = { from = if (left) current.start else current.end; moved = 0f; first = true }) { change, drag ->
                                change.consume()
                                moved += drag.x
                                val f = (from + moved / ppfNow).roundToInt().coerceAtLeast(0)
                                controller.play.dragClip(lane.kind, lane.id, clip.id, move = false, start = if (left) f else null, end = if (left) null else f, begin = first)
                                first = false
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.width(2.dp).height(12.dp).background(gripColor)) }
            }
        }
    }
}

/**
 * 脚本写在画面上的字用什么颜色：脚本给的是什么就是什么；只有一种情况会改——画面背景是亮的、
 * 而脚本给的是白色或者很浅的颜色（默认就是白色），那样根本看不见，换成深紫色。
 */
@Composable
private fun scriptTextColor(argb: Int): Color {
    val c = Color(argb)
    val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    return onScene(c, if (lum > 0.7f) Color(0xFF241B3D).copy(alpha = c.alpha) else c)
}

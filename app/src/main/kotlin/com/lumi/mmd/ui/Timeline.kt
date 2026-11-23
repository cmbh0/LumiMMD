package com.lumi.mmd.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.mmd.core.Modes
import com.lumi.mmd.core.PlaybackUi
import com.lumi.mmd.core.SceneController
import com.lumi.mmd.core.TimelineUi
import com.lumi.mmd.core.UiState
import com.lumi.mmd.engine.scene.KeyOps
import kotlin.math.abs
import kotlin.math.roundToInt

private val KeyMorph = Color(0xFFF0669C)
private val KeyCamera = Color(0xFF3FA4F0)

/** 把若干个升序数组并成一个去重的升序数组（"全部"那一行用）。 */
private fun unionFrames(tl: TimelineUi): IntArray {
    var total = tl.camera.size
    for (t in tl.tracks) total += t.frames.size
    if (total == 0) return IntArray(0)
    val all = IntArray(total)
    var n = 0
    for (t in tl.tracks) { System.arraycopy(t.frames, 0, all, n, t.frames.size); n += t.frames.size }
    System.arraycopy(tl.camera, 0, all, n, tl.camera.size)
    all.sort()
    var m = 0
    for (i in all.indices) if (i == 0 || all[i] != all[i - 1]) all[m++] = all[i]
    return all.copyOf(m)
}

/** frames 里第一个 >= frame 的下标。 */
private fun lowerBound(frames: IntArray, frame: Int): Int {
    var lo = 0; var hi = frames.size
    while (lo < hi) { val mid = (lo + hi) ushr 1; if (frames[mid] < frame) lo = mid + 1 else hi = mid }
    return lo
}

/**
 * 时间轴（仿 MMD 左边的帧操作面板）：横向是帧，纵向一行一条轨道，菱形是关键帧。
 *  - 在上面的标尺上按住拖动 = 拖动播放头；
 *  - 点格子 = 跳到那一帧（骨骼模式下顺便选中那一行的骨骼）；
 *  - 在格子区域拖动 = 滚动。
 */
@Composable
fun TimelinePanel(controller: SceneController, state: UiState, play: PlaybackUi, modifier: Modifier = Modifier) {
    val tl by controller.timeline.collectAsState()
    DisposableEffect(Unit) { controller.wantTimeline(true); onDispose { controller.wantTimeline(false) } }
    var cellDp by rememberSaveable { mutableFloatStateOf(9f) }
    val scroll = remember { mutableFloatStateOf(-2f) }       // 最左边一列是第几帧（可以是小数）
    val rowScroll = remember { mutableFloatStateOf(0f) }     // 像素
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val union = remember(tl.version) { unionFrames(tl) }
    val k = ink     // 画布里不能直接读界面颜色，先取出来
    val panel = k.paper
    val labelStyle = TextStyle(fontSize = 10.sp, color = k.text)
    val rulerStyle = TextStyle(fontSize = 9.sp, color = k.muted, fontFamily = Mono)
    val layouts = remember(tl.version, density.density, density.fontScale) { HashMap<String, TextLayoutResult>() }
    val frame = play.frame.roundToInt()

    val labelPx = with(density) { 70.dp.toPx() }
    val rulerPx = with(density) { 18.dp.toPx() }
    val rowPx = with(density) { 20.dp.toPx() }
    val cellPx = with(density) { cellDp.dp.toPx() }
    var viewWidth by remember { mutableFloatStateOf(1f) }

    // 播放头跑出可见范围时把它拉回来
    LaunchedEffect(frame, cellPx, viewWidth) {
        val visible = (viewWidth - labelPx) / cellPx
        if (visible <= 4f) return@LaunchedEffect
        if (frame < scroll.floatValue + 1f) scroll.floatValue = frame - 2f
        else if (frame > scroll.floatValue + visible - 2f) scroll.floatValue = if (play.playing) frame - 2f else frame - visible + 3f
    }

    Column(modifier.background(PanelColor).blockTouches()) {
        Hairline()
        Row(Modifier.fillMaxWidth().height(28.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (tl.modelName.isEmpty()) "时间轴" else tl.modelName, Modifier.weight(1f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = k.muted)
            IconBtn(LumiIcons.Minus, "时间轴缩小", size = 28.dp) { cellDp = (cellDp / 1.4f).coerceAtLeast(2.5f) }
            IconBtn(LumiIcons.Plus, "时间轴放大", size = 28.dp) { cellDp = (cellDp * 1.4f).coerceAtMost(26f) }
        }
        // 行：0 = 全部，1 = 镜头，之后是各条轨道
        val rowCount = 2 + tl.tracks.size
        Canvas(
            Modifier.fillMaxSize().clipToBounds().semantics { contentDescription = "时间轴" }
                .pointerInput(tl.version, cellPx, state.mode) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val sx = down.position.x; val sy = down.position.y
                        val inRuler = sy < rulerPx
                        fun frameAt(x: Float): Int = (scroll.floatValue + (x - labelPx) / cellPx).let { Math.floor(it.toDouble()).toInt() }.coerceAtLeast(0)
                        var dragged = false
                        var lx = sx; var ly = sy
                        if (inRuler && sx > labelPx) controller.seekFrame(frameAt(sx))
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (!dragged && (abs(ch.position.x - sx) > slop || abs(ch.position.y - sy) > slop)) dragged = true
                            if (dragged) {
                                if (inRuler) controller.seekFrame(frameAt(ch.position.x))
                                else {
                                    scroll.floatValue = (scroll.floatValue - (ch.position.x - lx) / cellPx).coerceAtLeast(-3f)
                                    val maxRow = maxOf(0f, rowCount * rowPx - (size.height - rulerPx))
                                    rowScroll.floatValue = (rowScroll.floatValue - (ch.position.y - ly)).coerceIn(0f, maxRow)
                                }
                                ch.consume()
                            }
                            lx = ch.position.x; ly = ch.position.y
                        }
                        if (!dragged && !inRuler) {
                            val row = Math.floor(((sy - rulerPx + rowScroll.floatValue) / rowPx).toDouble()).toInt()
                            if (sx > labelPx) controller.seekFrame(frameAt(sx))
                            val track = tl.tracks.getOrNull(row - 2)
                            if (track != null && track.kind == KeyOps.ROW_BONE && (state.mode == Modes.BONE || sx <= labelPx)) controller.selectBone(track.index)
                            else if (row == 1 && sx <= labelPx) controller.setMode(Modes.CAMERA)
                        }
                    }
                },
        ) {
            viewWidth = size.width
            val first = scroll.floatValue
            val firstFrame = Math.floor(first.toDouble()).toInt()
            val lastFrame = firstFrame + ((size.width - labelPx) / cellPx).toInt() + 2
            fun xOf(f: Int): Float = labelPx + (f - first) * cellPx

            // ---- 行 ----
            clipRect(0f, rulerPx, size.width, size.height) {
                val r0 = (rowScroll.floatValue / rowPx).toInt()
                val r1 = minOf(rowCount - 1, r0 + (size.height / rowPx).toInt() + 1)
                for (r in r0..r1) {
                    val y = rulerPx + r * rowPx - rowScroll.floatValue
                    val track = tl.tracks.getOrNull(r - 2)
                    val selected = track != null && track.kind == KeyOps.ROW_BONE && track.index == state.selectedBone && tl.modelId == state.selectedModel
                    if (selected) drawRect(k.accent.copy(alpha = 0.16f), Offset(0f, y), Size(size.width, rowPx))
                    val frames = when (r) { 0 -> union; 1 -> tl.camera; else -> track!!.frames }
                    val color = when { r == 0 -> k.text; r == 1 -> KeyCamera; track!!.kind == KeyOps.ROW_MORPH -> KeyMorph; else -> KeyGold }
                    clipRect(labelPx, y, size.width, y + rowPx) {
                        var i = lowerBound(frames, firstFrame - 1)
                        val h = minOf(rowPx * 0.3f, maxOf(cellPx * 0.48f, 2.2f))
                        while (i < frames.size && frames[i] <= lastFrame) {
                            diamond(xOf(frames[i]) + cellPx * 0.5f, y + rowPx * 0.5f, h, color)
                            i++
                        }
                    }
                    val name = when (r) { 0 -> "全部"; 1 -> "镜头"; else -> track!!.name }
                    val layout = layouts.getOrPut(name) { measurer.measure(name, labelStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (labelPx - 8f).toInt().coerceAtLeast(8))) }
                    drawRect(panel, Offset(0f, y), Size(labelPx, rowPx))
                    if (selected) drawRect(k.accent.copy(alpha = 0.22f), Offset(0f, y), Size(labelPx, rowPx))
                    drawLine(k.line.copy(alpha = k.line.alpha * 0.5f), Offset(0f, y + rowPx), Offset(size.width, y + rowPx), 1f)
                    drawText(layout, color = if (r <= 1) k.muted else if (track!!.kind == KeyOps.ROW_MORPH) KeyMorph else k.text,
                        topLeft = Offset(6f, y + (rowPx - layout.size.height) / 2f))
                }
            }

            // ---- 竖线、动作结尾、播放头 ----
            clipRect(labelPx, 0f, size.width, size.height) {
                val step = if (cellPx >= 5f) 1 else if (cellPx >= 2f) 5 else 10
                var f = maxOf(0, firstFrame - firstFrame % step)
                while (f <= lastFrame) {
                    val x = xOf(f)
                    if (f % 5 == 0) drawLine(k.line.copy(alpha = k.line.alpha * 0.8f), Offset(x, 0f), Offset(x, size.height), 1f)
                    else drawLine(k.line.copy(alpha = k.line.alpha * 0.35f), Offset(x, rulerPx), Offset(x, size.height), 1f)
                    f += step
                }
                if (first < 0f) drawRect(Color(0x55000000), Offset(labelPx, 0f), Size(xOf(0) - labelPx, size.height))
                if (play.maxFrame > 0) {
                    val x = xOf(play.maxFrame + 1)
                    drawLine(k.danger.copy(alpha = 0.55f), Offset(x, 0f), Offset(x, size.height), 1.5f)
                }
                val cx = xOf(frame)
                drawRect(k.accent.copy(alpha = 0.18f), Offset(cx, 0f), Size(cellPx, size.height))
                drawLine(k.accent, Offset(cx + cellPx * 0.5f, 0f), Offset(cx + cellPx * 0.5f, size.height), 1.5f)
            }

            // ---- 标尺 ----
            drawRect(panel, Offset(0f, 0f), Size(size.width, rulerPx))
            clipRect(labelPx, 0f, size.width, rulerPx) {
                val every = when { cellPx >= 16f -> 5; cellPx >= 7f -> 10; cellPx >= 3.5f -> 20; cellPx >= 1.8f -> 50; else -> 100 }
                var f = maxOf(0, firstFrame - firstFrame % every)
                while (f <= lastFrame) {
                    val t = f.toString()
                    val layout = layouts.getOrPut("#$t") { measurer.measure(t, rulerStyle) }
                    drawText(layout, topLeft = Offset(xOf(f) + 2f, (rulerPx - layout.size.height) / 2f))
                    f += every
                }
                val cx = xOf(frame)
                drawRect(k.accent, Offset(cx, rulerPx - 3f), Size(maxOf(cellPx, 3f), 3f))
            }
            drawLine(k.line, Offset(labelPx, 0f), Offset(labelPx, size.height), 1f)
            drawLine(k.line, Offset(0f, rulerPx), Offset(size.width, rulerPx), 1f)
        }
    }
}

private fun DrawScope.diamond(cx: Float, cy: Float, h: Float, color: Color) {
    val p = Path()
    p.moveTo(cx, cy - h); p.lineTo(cx + h, cy); p.lineTo(cx, cy + h); p.lineTo(cx - h, cy); p.close()
    drawPath(p, color)
}

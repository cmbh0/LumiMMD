package com.lumi.mmd.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Brush

/** 画示意图用的画笔：就是 DrawScope，另外带一个量文字的工具，图里可以直接写标注。 */
private class Pen(scope: DrawScope, val measurer: TextMeasurer) : DrawScope by scope

/** 教程的一页：标题、一句话的概括、几条要点、一段循环播放的示意动画。 */
private class Step(val title: String, val text: String, val points: List<String> = emptyList(), val draw: Pen.(Float) -> Unit)

private val Lilac = Color(0xFFB9A2FF)
private val Stage = Color(0xFF17122A)

private val Red = Color(0xFFFF5A5A)
private val Green = Color(0xFF5BE072)
private val Blue = Color(0xFF5A8CFF)
private val Gold = Color(0xFFFFC857)
private val Chalk = Color(0xFFE8ECF3)
private val Dim = Color(0x55FFFFFF)

/** 0..1 之间来回的平滑波形。 */
private fun wave(t: Float): Float = 0.5f - 0.5f * cos(t * 2f * PI.toFloat())
/** 把 t 映射到 [a,b] 区间内的进度（区间外夹到 0 / 1）。 */
private fun seg(t: Float, a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)

private fun DrawScope.finger(x: Float, y: Float, pressed: Boolean = true) {
    val r = size.minDimension * 0.045f
    drawCircle(Color.White.copy(alpha = if (pressed) 0.28f else 0.12f), r * 1.9f, Offset(x, y))
    drawCircle(Color.White.copy(alpha = if (pressed) 0.95f else 0.5f), r, Offset(x, y))
}

/** 在图里写一行标注，以 (x, y) 为中心。k = 字号倍数。 */
private fun Pen.label(text: String, x: Float, y: Float, color: Color = Chalk, k: Float = 1f) {
    val r = measurer.measure(text, TextStyle(fontSize = (size.minDimension * 0.043f * k).toSp(), color = color), softWrap = false, maxLines = 1)
    drawText(r, topLeft = Offset(x - r.size.width / 2f, y - r.size.height / 2f))
}

/**
 * 小人。foot = 脚底的 y，s = 身高的一半左右。
 * pose：0 站，1 蹲，2 趴；swing = 两条腿前后摆开多少（-1..1，走路时来回变）。
 */
private fun DrawScope.figure(cx: Float, foot: Float, s: Float, color: Color, pose: Int = 0, swing: Float = 0f, alpha: Float = 1f) {
    val c = color.copy(alpha = color.alpha * alpha)
    val w = s * 0.11f
    when (pose) {
        2 -> {  // 趴：身体横过来
            drawCircle(c, s * 0.2f, Offset(cx + s * 0.95f, foot - s * 0.3f))
            drawLine(c, Offset(cx + s * 0.7f, foot - s * 0.24f), Offset(cx - s * 0.35f, foot - s * 0.16f), w, StrokeCap.Round)
            drawLine(c, Offset(cx - s * 0.35f, foot - s * 0.16f), Offset(cx - s * 1.2f + swing * s * 0.12f, foot - s * 0.08f), w, StrokeCap.Round)
            drawLine(c, Offset(cx + s * 0.55f, foot - s * 0.22f), Offset(cx + s * 0.8f - swing * s * 0.12f, foot - s * 0.03f), w, StrokeCap.Round)
        }
        1 -> {  // 蹲：膝盖弯着，上身前倾
            val hip = Offset(cx - s * 0.12f, foot - s * 0.55f)
            val neck = Offset(cx + s * 0.16f, foot - s * 1.15f)
            drawCircle(c, s * 0.2f, Offset(neck.x + s * 0.08f, neck.y - s * 0.22f))
            drawLine(c, hip, neck, w, StrokeCap.Round)
            drawLine(c, neck, Offset(neck.x + s * 0.34f, neck.y + s * 0.34f), w, StrokeCap.Round)
            for (side in listOf(-1f, 1f)) {
                val knee = Offset(cx + s * 0.34f + side * swing * s * 0.1f, foot - s * 0.5f)
                drawLine(c, hip, knee, w, StrokeCap.Round)
                drawLine(c, knee, Offset(cx + s * 0.1f + side * swing * s * 0.22f, foot), w, StrokeCap.Round)
            }
        }
        else -> {
            val hip = Offset(cx, foot - s * 0.95f)
            val neck = Offset(cx, foot - s * 1.72f)
            drawCircle(c, s * 0.2f, Offset(cx, foot - s * 1.95f))
            drawLine(c, hip, neck, w, StrokeCap.Round)
            drawLine(c, Offset(cx, neck.y + s * 0.12f), Offset(cx - s * 0.42f + swing * s * 0.25f, neck.y + s * 0.62f), w, StrokeCap.Round)
            drawLine(c, Offset(cx, neck.y + s * 0.12f), Offset(cx + s * 0.42f - swing * s * 0.25f, neck.y + s * 0.62f), w, StrokeCap.Round)
            drawLine(c, hip, Offset(cx - s * 0.2f - swing * s * 0.4f, foot), w, StrokeCap.Round)
            drawLine(c, hip, Offset(cx + s * 0.2f + swing * s * 0.4f, foot), w, StrokeCap.Round)
        }
    }
}

/** 手机屏幕的外框，返回 (左, 上, 宽, 高)。 */
private fun DrawScope.screen(): FloatArray {
    val u = size.minDimension
    val x0 = size.width * 0.5f - u * 0.43f; val y0 = size.height * 0.5f - u * 0.28f; val sw = u * 0.86f; val sh = u * 0.56f
    drawRoundRect(Color(0xFF0E0A1A), Offset(x0, y0), Size(sw, sh), CornerRadius(u * 0.04f))
    drawRoundRect(Dim, Offset(x0, y0), Size(sw, sh), CornerRadius(u * 0.04f), style = Stroke(u * 0.006f))
    return floatArrayOf(x0, y0, sw, sh)
}

private fun DrawScope.diamond(cx: Float, cy: Float, h: Float, color: Color) {
    val p = Path()
    p.moveTo(cx, cy - h); p.lineTo(cx + h, cy); p.lineTo(cx, cy + h); p.lineTo(cx - h, cy); p.close()
    drawPath(p, color)
}

/** 画一个带透视感的立方体线框：绕竖轴转 yaw，高度 h（相对单位），以 (cx, cy) 为底面中心。 */
private fun DrawScope.box(cx: Float, cy: Float, s: Float, h: Float, yaw: Float, color: Color, top: Color? = null) {
    val pts = Array(8) { i ->
        val x = if (i and 1 == 0) -1f else 1f
        val z = if (i and 2 == 0) -1f else 1f
        val y = if (i and 4 == 0) 0f else h
        val rx = x * cos(yaw) - z * sin(yaw)
        val rz = x * sin(yaw) + z * cos(yaw)
        Offset(cx + rx * s, cy - y * s + rz * s * 0.38f)
    }
    if (top != null) {
        val p = Path()
        p.moveTo(pts[4].x, pts[4].y); p.lineTo(pts[5].x, pts[5].y); p.lineTo(pts[7].x, pts[7].y); p.lineTo(pts[6].x, pts[6].y); p.close()
        drawPath(p, top)
    }
    val w = size.minDimension * 0.012f
    for ((a, b) in listOf(0 to 1, 1 to 3, 3 to 2, 2 to 0, 4 to 5, 5 to 7, 7 to 6, 6 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)) drawLine(color, pts[a], pts[b], w, StrokeCap.Round)
}

private val steps = listOf(
    Step("欢迎使用 Lumi MMD", "画面占满整个屏幕，界面只有角上的几个按钮。", listOf(
        "左上角 ☰ 是菜单：角色、动作与音乐、脚本、物理、舞台、画面、导出都在里面，点一行往里进一层。",
        "右上角「添加」导入文件，铅笔进入编辑。",
        "左下角是播放钮；点它旁边的时间，才展开成带进度线的播放条。",
        "右下角的手柄是操控模式，方框是隐藏全部界面。",
    )) { t ->
        val u = size.minDimension
        val (x0, y0, sw, sh) = screen()
        // 画面里的小人
        figure(x0 + sw * 0.62f, y0 + sh * 0.8f, u * 0.115f, Chalk)
        // 上面一行：菜单、场景名、铅笔、添加
        val ty = y0 + sh * 0.12f
        for (i in 0 until 3) drawLine(Chalk, Offset(x0 + sw * 0.04f, ty - u * 0.014f + i * u * 0.014f), Offset(x0 + sw * 0.085f, ty - u * 0.014f + i * u * 0.014f), u * 0.005f, StrokeCap.Round)
        drawLine(Chalk, Offset(x0 + sw * 0.12f, ty), Offset(x0 + sw * 0.3f, ty), u * 0.012f, StrokeCap.Round)
        drawLine(Chalk, Offset(x0 + sw * 0.745f, ty + u * 0.014f), Offset(x0 + sw * 0.775f, ty - u * 0.014f), u * 0.007f, StrokeCap.Round)
        drawRoundRect(Lilac, Offset(x0 + sw * 0.83f, ty - u * 0.022f), Size(sw * 0.13f, u * 0.044f), CornerRadius(u * 0.022f))
        // 左下：播放钮（一颗胶囊）；右下：手柄、隐藏界面
        val by = y0 + sh * 0.88f
        drawRoundRect(Color(0x66B9A2FF), Offset(x0 + sw * 0.03f, by - u * 0.026f), Size(sw * 0.2f, u * 0.052f), CornerRadius(u * 0.026f))
        val tri = Path(); tri.moveTo(x0 + sw * 0.055f, by - u * 0.015f); tri.lineTo(x0 + sw * 0.08f, by); tri.lineTo(x0 + sw * 0.055f, by + u * 0.015f); tri.close()
        drawPath(tri, Chalk)
        drawLine(Chalk, Offset(x0 + sw * 0.11f, by), Offset(x0 + sw * 0.19f, by), u * 0.006f, StrokeCap.Round)
        drawCircle(Chalk, u * 0.017f, Offset(x0 + sw * 0.86f, by), style = Stroke(u * 0.005f))
        drawRect(Chalk, Offset(x0 + sw * 0.925f, by - u * 0.014f), Size(u * 0.028f, u * 0.028f), style = Stroke(u * 0.005f))
        // 轮流指出四个角
        val k = (t * 4f).toInt().coerceIn(0, 3)
        when (k) {
            0 -> { finger(x0 + sw * 0.07f, ty + u * 0.035f); label("菜单", x0 + sw * 0.1f, ty + u * 0.1f, Gold) }
            1 -> { finger(x0 + sw * 0.9f, ty + u * 0.035f); label("添加文件", x0 + sw * 0.84f, ty + u * 0.1f, Gold) }
            2 -> { finger(x0 + sw * 0.15f, by - u * 0.005f); label("播放 · 点时间展开进度条", x0 + sw * 0.3f, by - u * 0.075f, Gold) }
            else -> { finger(x0 + sw * 0.87f, by - u * 0.005f); label("操控模式", x0 + sw * 0.83f, by - u * 0.075f, Gold) }
        }
        // 点菜单：一张纸从左边出来，里面一行一行
        val open = seg(t, 0.08f, 0.14f) * (1f - seg(t, 0.2f, 0.25f))
        if (open > 0f) {
            val cw = sw * 0.34f * open
            drawRoundRect(Color(0xFFFBF9FF), Offset(x0 + sw * 0.03f, y0 + sh * 0.26f), Size(cw, sh * 0.5f), CornerRadius(u * 0.02f))
            if (open > 0.9f) for (i in 0 until 4) {
                val ly = y0 + sh * (0.34f + i * 0.1f)
                drawLine(Color(0xFF1C1730), Offset(x0 + sw * 0.06f, ly), Offset(x0 + sw * (0.16f + (i % 3) * 0.03f), ly), u * 0.008f, StrokeCap.Round)
                drawLine(Color(0x332B1A66), Offset(x0 + sw * 0.06f, ly + sh * 0.045f), Offset(x0 + sw * 0.34f, ly + sh * 0.045f), u * 0.002f)
            }
        }
    },
    Step("添加文件", "点右上角的「添加」，在手机里选文件，可以一次选好几个。", listOf(
        "模型：.pmx / .pmd，或者整个 zip。舞台和道具还可以是 Blender 的 .blend（直接读，不用先导出）、GLB、FBX、OBJ。",
        "动作、表情、镜头（.vmd）、姿势（.vpd）、音乐（mp3 / wav / ogg / m4a / flac）、脚本（.lua）。",
        "应用会按内容把它们各自放好：身体动作给角色，只有表情的叠上去，镜头动作给相机。",
        "只选了一个 .pmx 也可以：应用会回它原来的文件夹把贴图带进来，第一次需要你授权一下。",
        "导入过的文件留在应用的资源库里，可以反复用；不要了就点它右边的垃圾桶删掉（模型会连贴图一起删）。",
    )) { t ->
        val w = size.width; val h = size.height
        val u = minOf(w, h)
        val names = listOf(Gold, Color(0xFFFF9DBB), Color(0xFF8FD0FF), Green)
        // 右边四个槽，左边四个文件飞进去
        for (i in 0 until 4) {
            val y = h * (0.26f + i * 0.16f)
            val x1 = w * 0.56f
            drawLine(Chalk, Offset(x1, y), Offset(x1 + u * 0.09f, y), u * 0.012f, StrokeCap.Round)
            drawLine(Dim, Offset(x1, y + u * 0.055f), Offset(w * 0.92f, y + u * 0.055f), u * 0.003f)
            val k = seg(t, 0.08f + i * 0.14f, 0.3f + i * 0.14f)
            val e = k * k * (3f - 2f * k)
            val fx = w * 0.14f + (x1 + u * 0.16f - w * 0.14f) * e
            val fy = h * (0.3f + i * 0.13f) + (y - h * (0.3f + i * 0.13f)) * e
            drawRoundRect(names[i], Offset(fx, fy - u * 0.03f), Size(u * (0.13f + 0.07f * e), u * 0.06f), CornerRadius(u * 0.012f))
        }
        if (t < 0.12f) finger(w * 0.2f, h * 0.36f)
    },
    Step("看：转、缩放、平移", "画面上的手势只有四个。", listOf(
        "单指拖动 = 转视角。",
        "两根手指捏合 = 拉近拉远。",
        "两根手指一起拖 = 平移。",
        "双击模型上的某个地方 = 把视线的中心移到那里。",
    )) { t ->
        val w = size.width; val h = size.height
        val cx = w * 0.5f; val cy = h * 0.62f
        if (t < 0.5f) {
            val k = wave(t * 2f)
            box(cx, cy, h * 0.17f, 1.6f, -0.9f + k * 1.8f, Chalk)
            finger(w * (0.3f + 0.4f * k), h * 0.82f)
        } else {
            val k = wave((t - 0.5f) * 2f)
            box(cx, cy, h * (0.12f + 0.09f * k), 1.6f, 0.5f, Chalk)
            val d = w * (0.1f + 0.14f * k)
            finger(cx - d, h * 0.3f + d * 0.3f); finger(cx + d, h * 0.86f - d * 0.3f)
        }
    },
    Step("播放：画面跟着音乐走", "音乐按它原来的速度放，画面去对它，不用设置谁先谁后。", listOf(
        "点左下角的播放钮开始；再点一下暂停。",
        "点播放钮旁边的时间，展开进度条：拖动跳到任意位置，右边是循环和倍速。用完点 ⌄ 收起来。",
        "有音乐时，画面的时间平滑地贴着音乐走，动作不会因为对时间而一顿一顿。",
        "有镜头动作时，进度条旁边可以在「镜头 / 自由」之间切换。",
    )) { t ->
        val u = size.minDimension
        val (x0, y0, sw, sh) = screen()
        // 跟着拍子动的小人
        val beat = wave(t * 8f)
        figure(x0 + sw * 0.5f, y0 + sh * 0.66f - beat * u * 0.012f, u * 0.1f, Chalk, swing = (wave(t * 4f) - 0.5f) * 0.9f)
        val by = y0 + sh * 0.86f
        val open = seg(t, 0.18f, 0.3f)
        // 音乐的波形
        for (i in 0 until 26) {
            val x = x0 + sw * (0.12f + i * 0.03f)
            val hgt = u * (0.012f + 0.03f * abs(sin(i * 1.7f) * cos(i * 0.6f)))
            drawLine(if (i / 26f < t) Green else Color(0x5558C99A), Offset(x, y0 + sh * 0.17f - hgt), Offset(x, y0 + sh * 0.17f + hgt), u * 0.008f, StrokeCap.Round)
        }
        label("音乐", x0 + sw * 0.07f, y0 + sh * 0.17f, Green, 0.8f)
        // 播放钮
        drawRoundRect(Color(0x66B9A2FF), Offset(x0 + sw * 0.03f, by - u * 0.026f), Size(sw * (0.2f + 0.74f * open), u * 0.052f), CornerRadius(u * 0.026f))
        val tri = Path(); tri.moveTo(x0 + sw * 0.055f, by - u * 0.015f); tri.lineTo(x0 + sw * 0.08f, by); tri.lineTo(x0 + sw * 0.055f, by + u * 0.015f); tri.close()
        drawPath(tri, Chalk)
        if (open < 0.5f) {
            drawLine(Chalk, Offset(x0 + sw * 0.11f, by), Offset(x0 + sw * 0.19f, by), u * 0.006f, StrokeCap.Round)
            if (t > 0.05f) { finger(x0 + sw * 0.16f, by + u * 0.01f, t > 0.12f); label("点时间", x0 + sw * 0.17f, by - u * 0.07f, Gold) }
        } else {
            val a = x0 + sw * 0.13f; val b = x0 + sw * 0.9f
            drawLine(Dim, Offset(a, by), Offset(b, by), u * 0.004f)
            drawLine(Chalk, Offset(a, by), Offset(a + (b - a) * t, by), u * 0.006f, StrokeCap.Round)
            drawCircle(Chalk, u * 0.014f, Offset(a + (b - a) * t, by))
            label("进度条", x0 + sw * 0.5f, by - u * 0.07f, Gold)
        }
    },
    Step("编辑：箭头和圆环", "点右上角的铅笔进入「编辑」，顶上一排词是工具：轨道、模型、骨骼、表情、镜头、部件、建模。", listOf(
        "选中模型或骨骼后出现操纵器：「移动」是三根箭头，拖哪根就沿哪个方向走。",
        "「旋转」是三个圆环，拖哪个就绕哪根轴转；「缩放」是三个方块。",
        "红 = X，绿 = Y，蓝 = Z。右下角的 X / Y / Z 方块按住拖也一样，手指不会挡住模型。",
        "想要准确的数：面板里每一项都是滑杆，两头的减号和加号是细调（按住不放连续走），点数值可以直接输入。",
        "「部件」把模型拆成一块一块：点一行展开它的位移 / 旋转 / 大小，眼睛是隐藏，垃圾桶是删除（能撤销）。",
        "点左上角的「完成」回到干净的画面。",
    )) { t ->
        val w = size.width; val h = size.height
        val cx = w * 0.46f; val cy = h * 0.5f; val r = h * 0.3f
        val sw = w * 0.014f
        if (t < 0.5f) {
            // 旋转：手指沿着蓝色的环走，手臂跟着转
            val k = wave(t * 2f)
            val ang = -0.9f + k * 1.3f
            drawCircle(Blue, r, Offset(cx, cy), style = Stroke(sw))
            drawOval(Green.copy(alpha = 0.8f), Offset(cx - r, cy - r * 0.22f), Size(r * 2f, r * 0.44f), style = Stroke(sw * 0.8f))
            drawOval(Red.copy(alpha = 0.8f), Offset(cx - r * 0.22f, cy - r), Size(r * 0.44f, r * 2f), style = Stroke(sw * 0.8f))
            drawCircle(Color.White.copy(alpha = 0.5f), r * 1.2f, Offset(cx, cy), style = Stroke(sw * 0.5f))
            val ex = cx + cos(ang) * r * 0.86f; val ey = cy + sin(ang) * r * 0.86f
            drawLine(Gold, Offset(cx, cy), Offset(ex, ey), sw * 1.6f, StrokeCap.Round)
            drawCircle(Chalk, sw * 1.3f, Offset(cx, cy))
            finger(cx + cos(ang) * r, cy + sin(ang) * r)
        } else {
            // 移动：拖红色箭头
            val k = wave((t - 0.5f) * 2f)
            val ox = cx - r * 0.5f + k * r * 0.7f
            fun arrow(dx: Float, dy: Float, c: Color) {
                val ex = ox + dx * r; val ey = cy + dy * r
                drawLine(c, Offset(ox, cy), Offset(ex, ey), sw * 1.2f, StrokeCap.Round)
                val p = Path()
                val nx = -dy; val ny = dx
                p.moveTo(ex + dx * r * 0.18f, ey + dy * r * 0.18f)
                p.lineTo(ex + nx * r * 0.09f, ey + ny * r * 0.09f); p.lineTo(ex - nx * r * 0.09f, ey - ny * r * 0.09f); p.close()
                drawPath(p, c)
            }
            arrow(1f, 0f, Red); arrow(0f, -1f, Green); arrow(-0.55f, 0.45f, Blue)
            box(ox, cy + r * 0.12f, r * 0.2f, 1.2f, 0.6f, Chalk.copy(alpha = 0.7f))
            finger(ox + r * 0.8f, cy)
        }
        // 右下角的 XYZ 块
        val bs = h * 0.11f
        for ((i, c) in listOf(Red, Green, Blue).withIndex()) {
            val x = w * 0.66f + i * bs * 1.15f; val y = h * 0.82f
            drawRoundRect(c.copy(alpha = 0.25f), Offset(x, y), Size(bs, bs), CornerRadius(bs * 0.2f))
            drawRoundRect(c, Offset(x, y), Size(bs, bs), CornerRadius(bs * 0.2f), style = Stroke(sw * 0.5f))
        }
    },
    Step("做动画：摆姿势 → 打关键帧", "关键帧 = 记下「这一帧是这个姿势」，中间的过渡由应用自己算。", listOf(
        "在「编辑」里选「骨骼」，点蓝色节点选一根骨骼，把它摆好。",
        "点底部的 ◆ 把这一帧登记成关键帧。",
        "用 ◀ ▶ 换到后面的某一帧，再摆一个姿势、再登记。",
        "点播放，中间的过渡是自动生成的。打开「自动关键帧」后，每次摆完会自己登记。",
        "表情、镜头也是同样的办法：拖表情滑杆或者摆好视角，再点 ◆。",
        "底部还有复制、粘贴、左右反转粘贴；时间轴上能看到每一根骨骼的关键帧。",
    )) { t ->
        val w = size.width; val h = size.height
        val sw = w * 0.014f
        // 时间轴
        val x0 = w * 0.1f; val x1 = w * 0.9f; val ty = h * 0.84f
        drawRoundRect(Color(0xFF0F1218), Offset(x0 - w * 0.03f, ty - h * 0.08f), Size(x1 - x0 + w * 0.06f, h * 0.16f), CornerRadius(h * 0.03f))
        for (i in 0..16) drawLine(Dim, Offset(x0 + (x1 - x0) * i / 16f, ty - h * 0.05f), Offset(x0 + (x1 - x0) * i / 16f, ty + h * 0.05f), 1f)
        val kA = x0 + (x1 - x0) * 2f / 16f; val kB = x0 + (x1 - x0) * 13f / 16f
        // 阶段：0-0.2 在 A 摆好并登记；0.2-0.4 播放头走到 B；0.4-0.6 在 B 摆姿势并登记；0.6-1 播放
        val head = when {
            t < 0.2f -> kA
            t < 0.4f -> kA + (kB - kA) * seg(t, 0.2f, 0.4f)
            t < 0.6f -> kB
            else -> kA + (kB - kA) * wave(seg(t, 0.6f, 1f))
        }
        if (t > 0.1f) diamond(kA, ty, h * 0.035f, Gold)
        if (t > 0.5f) diamond(kB, ty, h * 0.035f, Gold)
        drawLine(Color(0xFF5BC8F5), Offset(head, ty - h * 0.08f), Offset(head, ty + h * 0.08f), sw)
        // 小人的手臂：A 帧放下，B 帧举起
        val pose = when {
            t < 0.4f -> 0f
            t < 0.5f -> seg(t, 0.4f, 0.5f)
            t < 0.6f -> 1f
            else -> wave(seg(t, 0.6f, 1f))
        }
        val cx = w * 0.5f; val cy = h * 0.4f
        drawCircle(Chalk, h * 0.05f, Offset(cx, cy - h * 0.2f))
        drawLine(Chalk, Offset(cx, cy - h * 0.14f), Offset(cx, cy + h * 0.1f), sw, StrokeCap.Round)
        drawLine(Chalk, Offset(cx, cy + h * 0.1f), Offset(cx - h * 0.07f, cy + h * 0.26f), sw, StrokeCap.Round)
        drawLine(Chalk, Offset(cx, cy + h * 0.1f), Offset(cx + h * 0.07f, cy + h * 0.26f), sw, StrokeCap.Round)
        drawLine(Chalk, Offset(cx, cy - h * 0.09f), Offset(cx - h * 0.13f, cy + h * 0.03f), sw, StrokeCap.Round)
        val a = 0.75f - pose * 1.9f
        val sx = cx; val sy = cy - h * 0.09f
        val ex = sx + cos(a) * h * 0.19f; val ey = sy + sin(a) * h * 0.19f
        drawLine(Gold, Offset(sx, sy), Offset(ex, ey), sw * 1.3f, StrokeCap.Round)
        drawCircle(Color(0xFF5BC8F5), sw * 1.2f, Offset(sx, sy))
        if (t in 0.4f..0.5f) finger(ex, ey)
        // 登记按钮闪一下
        val flash = if (t in 0.1f..0.2f || t in 0.5f..0.6f) 1f else 0.35f
        diamond(w * 0.86f, h * 0.2f, h * 0.07f, Gold.copy(alpha = flash))
        if (flash == 1f) finger(w * 0.86f, h * 0.24f)
    },
    Step("轨道：像剪视频那样排", "编辑 →「轨道」。镜头、每个角色、音乐、每个脚本各占一条。", listOf(
        "点一个片段选中它；按住拖动 = 挪到别的时间。",
        "选中后两头出现白色把手：拖把手 = 裁掉开头或结尾。",
        "把时间线停在片段中间，点「分割」切成两段；「复制」接一份在后面。",
        "「添加」可以再接一段动作、镜头或者音乐，或者放内置的走、跑、蹲、趴。",
        "两段动作的接头太生硬？选中后一段点「过渡」：开头的半秒、一秒里从前一段的姿势平滑地变过来。",
        "音乐也是片段：可以挪到后面再开始、只用其中一截、切开、放好几段。",
        "脚本那一条的长短 = 脚本在哪一段时间里运行。",
    )) { t ->
        val w = size.width; val h = size.height; val u = size.minDimension
        val left = w * 0.5f - u * 0.3f; val right = w * 0.5f + u * 0.44f
        val names = listOf("镜头", "角色", "音乐", "脚本")
        val colors = listOf(Color(0xFF5FB7E8), Lilac, Green, Gold)
        val laneH = u * 0.105f
        val top = h * 0.5f - laneH * 2f
        val move = seg(t, 0.08f, 0.3f) * u * 0.1f
        val split = seg(t, 0.45f, 0.55f)
        val trim = seg(t, 0.72f, 0.92f) * u * 0.09f
        for (i in 0 until 4) {
            val y = top + i * laneH
            label(names[i], left - u * 0.07f, y + laneH * 0.5f, colors[i], 0.85f)
            drawLine(Dim, Offset(left, y + laneH), Offset(right, y + laneH), u * 0.002f)
            val cy = y + laneH * 0.16f; val ch = laneH * 0.68f
            when (i) {
                0 -> drawRoundRect(colors[0].copy(alpha = 0.55f), Offset(left + u * 0.04f, cy), Size(u * 0.3f, ch), CornerRadius(u * 0.012f))
                1 -> {
                    val a = left + u * 0.02f + move; val len = u * 0.44f - trim
                    val cut = a + len * 0.55f
                    val gap = split * u * 0.012f
                    drawRoundRect(colors[1].copy(alpha = 0.75f), Offset(a, cy), Size(cut - a - gap, ch), CornerRadius(u * 0.012f))
                    drawRoundRect(colors[1].copy(alpha = 0.75f), Offset(cut + gap, cy), Size(a + len - cut - gap, ch), CornerRadius(u * 0.012f))
                    if (t in 0.72f..0.95f) drawRect(Color.White, Offset(a + len - u * 0.014f, cy), Size(u * 0.014f, ch))
                    if (t in 0.05f..0.32f) finger(a + u * 0.14f, cy + ch * 0.6f, t > 0.08f)
                    if (t in 0.7f..0.95f) finger(a + len, cy + ch * 0.7f)
                }
                2 -> drawRoundRect(colors[2].copy(alpha = 0.55f), Offset(left, cy), Size(u * 0.7f, ch), CornerRadius(u * 0.012f))
                else -> drawRoundRect(colors[3].copy(alpha = 0.55f), Offset(left + u * 0.26f, cy), Size(u * 0.24f, ch), CornerRadius(u * 0.012f))
            }
        }
        // 时间线
        val px = left + u * 0.02f + u * 0.1f + (u * 0.44f) * 0.55f
        val lineX = if (t < 0.4f) left + (px - left) * seg(t, 0.3f, 0.4f) else px
        drawLine(Chalk, Offset(lineX, top - u * 0.03f), Offset(lineX, top + laneH * 4f), u * 0.005f)
        val msg = when { t < 0.32f -> "拖动 = 挪位置"; t < 0.7f -> "分割"; else -> "拖把手 = 裁剪" }
        label(msg, w * 0.5f, top + laneH * 4f + u * 0.07f, Gold)
    },
    Step("脚本与特效：Lua", "菜单 →「脚本与特效」。脚本每一帧都在运行，能驱动场景里几乎所有东西。", listOf(
        "先试「示例」：点「添加」就加进场景并运行——樱花、手上的光点、裙子变换、出场特效、字幕……",
        "点进一个脚本可以改代码，「保存并运行」马上看到结果；报错会写在下面的「输出」里。",
        "写代码时有颜色；上面那一条会给出补全（敲 scene. 就列出能用的），旁边是手机键盘上不好找的符号。",
        "脚本能转骨骼、改表情、改材质的颜色和透明度、换上下层、吹风、放粒子和拖尾、新建几何体、接管镜头、加屏幕按钮。",
        "关掉脚本，它改过的东西自动复原。",
        "不会写？在「AI 操作」里连上 AI，说出你想要的效果，它会直接把脚本写进来、运行、看到报错自己改。",
    )) { t ->
        val w = size.width; val h = size.height; val u = size.minDimension
        // 左：代码卡片
        val cx0 = w * 0.5f - u * 0.44f; val cy0 = h * 0.5f - u * 0.24f
        drawRoundRect(Color(0xFF241B3D), Offset(cx0, cy0), Size(u * 0.42f, u * 0.48f), CornerRadius(u * 0.03f))
        label("Lua", cx0 + u * 0.08f, cy0 + u * 0.055f, Gold, 0.9f)
        val on = t > 0.22f
        drawRoundRect(if (on) Color(0xFF7B4DFF) else Color(0xFF4A4263), Offset(cx0 + u * 0.27f, cy0 + u * 0.03f), Size(u * 0.11f, u * 0.055f), CornerRadius(u * 0.03f))
        drawCircle(Color.White, u * 0.021f, Offset(cx0 + (if (on) u * 0.352f else u * 0.298f), cy0 + u * 0.0575f))
        if (t in 0.1f..0.3f) finger(cx0 + u * 0.33f, cy0 + u * 0.085f, t > 0.18f)
        val widths = floatArrayOf(0.26f, 0.2f, 0.3f, 0.16f, 0.24f, 0.1f)
        val tints = listOf(Lilac, Chalk, Green, Chalk, Color(0xFF8FD0FF), Lilac)
        for (i in widths.indices) {
            val y = cy0 + u * (0.14f + i * 0.052f)
            drawLine(tints[i].copy(alpha = 0.85f), Offset(cx0 + u * (0.04f + (if (i in 1..4) 0.03f else 0f)), y), Offset(cx0 + u * (0.04f + widths[i]), y), u * 0.012f, StrokeCap.Round)
        }
        // 右：小人 + 特效
        val fx = w * 0.5f + u * 0.24f; val foot = h * 0.5f + u * 0.2f
        figure(fx, foot, u * 0.13f, Chalk, swing = -0.6f)
        if (on) {
            val k = seg(t, 0.22f, 1f)
            // 手上的光点
            val hand = Offset(fx + u * 0.085f, foot - u * 0.145f)
            for (i in 0 until 9) {
                val f = (k * 3f + i / 9f) % 1f
                val ang = i * 2.4f
                drawCircle(Gold.copy(alpha = 1f - f), u * (0.014f - 0.008f * f), Offset(hand.x + cos(ang) * f * u * 0.1f, hand.y - f * u * 0.12f + sin(ang) * f * u * 0.04f))
            }
            // 飘落的花瓣
            for (i in 0 until 10) {
                val f = (k * 1.6f + i * 0.37f) % 1f
                val x = fx - u * 0.2f + ((i * 53) % 100) / 100f * u * 0.4f + sin(f * 6f + i) * u * 0.02f
                drawCircle(Color(0xFFFF9DBB).copy(alpha = 0.9f * (1f - f * 0.5f)), u * 0.011f, Offset(x, foot - u * 0.42f + f * u * 0.42f))
            }
            // 脚下的光圈
            drawCircle(Lilac.copy(alpha = 0.6f * (1f - (k * 2f) % 1f)), u * 0.04f + ((k * 2f) % 1f) * u * 0.12f, Offset(fx, foot), style = Stroke(u * 0.006f))
        }
        label(if (on) "运行中" else "打开开关", cx0 + u * 0.21f, cy0 + u * 0.53f, Gold)
    },
    Step("上下层和透明", "两个模型谁盖住谁、谁是半透明的，可以直接指定。", listOf(
        "菜单 → 角色 → 点一个模型 →「上下层」：层数大的整个盖在层数小的上面，不管谁离镜头近。",
        "同一页的「不透明度」让整个模型半透明（身体里面的面不会透出来）。",
        "想让它随时间变化？脚本里写 m.layer = 1、m.opacity = 0.4，或者只改某个材质：m:material(\"裙\").alpha = 0.3。",
        "示例「上下层与透明」「出场特效」可以直接看效果。",
    )) { t ->
        val w = size.width; val h = size.height; val u = size.minDimension
        val foot = h * 0.5f + u * 0.24f
        val phase = (t * 3f).toInt().coerceIn(0, 2)
        val ax = w * 0.5f - u * 0.07f; val bx = w * 0.5f + u * 0.07f
        // 用实心的剪影，才看得出谁盖住谁
        fun body(cx: Float, color: Color, alpha: Float) {
            val c = color.copy(alpha = alpha)
            drawCircle(c, u * 0.065f, Offset(cx, foot - u * 0.4f))
            val p = Path()
            p.moveTo(cx - u * 0.06f, foot - u * 0.33f); p.lineTo(cx + u * 0.06f, foot - u * 0.33f)
            p.lineTo(cx + u * 0.13f, foot); p.lineTo(cx - u * 0.13f, foot); p.close()
            drawPath(p, c)
        }
        val fade = if (phase == 2) 1f - 0.6f * seg(t, 0.68f, 0.85f) else 1f
        if (phase == 0) { body(bx, Gold, 1f); body(ax, Lilac, 1f) }
        else { body(ax, Lilac, 1f); body(bx, Gold, fade) }
        label("A", ax - u * 0.17f, foot - u * 0.2f, Lilac, 1.1f)
        label("B", bx + u * 0.17f, foot - u * 0.2f, Gold, 1.1f)
        label(when (phase) { 0 -> "A 在前面"; 1 -> "B 的层 +1：B 盖到上面"; else -> "B 的不透明度 40%" }, w * 0.5f, foot + u * 0.08f, Chalk)
        // 层的示意：两片叠着的纸
        val lx = w * 0.5f - u * 0.4f; val ly = h * 0.5f - u * 0.2f
        val up = if (phase >= 1) 1 else 0
        for (i in 0 until 2) {
            val isB = i == up
            val c = if (isB) Gold else Lilac
            val p = Path()
            val oy = ly + (1 - i) * u * 0.05f
            p.moveTo(lx, oy); p.lineTo(lx + u * 0.09f, oy - u * 0.035f); p.lineTo(lx + u * 0.18f, oy); p.lineTo(lx + u * 0.09f, oy + u * 0.035f); p.close()
            drawPath(p, c.copy(alpha = 0.9f))
        }
        label("层", lx + u * 0.09f, ly + u * 0.13f, Dim.copy(alpha = 0.8f), 0.85f)
    },
    Step("物理：头发和裙子的手感", "菜单 →「物理」。拖滑杆，头发和裙子马上有反应。", listOf(
        "重力、风力和风向、阵风：让裙子和头发往一边飘。",
        "重量、弹簧硬度、摆动阻尼：决定是轻飘飘的还是垂坠的，甩起来多久停。",
        "关节活动范围、碰撞体大小：裙子穿进腿里时把碰撞体调大一点。",
        "「一键预设」：轻盈、厚重、大风、失重、水下。",
        "可以单独调某一个模型；脚本里也能改（physics.wind、m:physics_set）。",
        "调得太极端裙子发抖时，把「每秒模拟」和「约束迭代次数」调高。",
    )) { t ->
        val w = size.width; val h = size.height; val u = size.minDimension
        val wind = wave(t)                       // 风力滑杆来回
        val cx = w * 0.5f - u * 0.16f; val top = h * 0.5f - u * 0.2f
        // 头 + 三缕头发（一节一节的链子）
        drawCircle(Chalk, u * 0.075f, Offset(cx, top))
        for (sIdx in 0 until 3) {
            var p = Offset(cx - u * 0.05f + sIdx * u * 0.05f, top + u * 0.06f)
            for (i in 0 until 5) {
                val a = wind * 0.95f * (i + 1) / 5f + sin(t * 2f * PI.toFloat() * 3f + i * 0.7f + sIdx) * 0.12f * (0.3f + wind)
                val q = Offset(p.x + sin(a) * u * 0.05f, p.y + cos(a) * u * 0.05f)
                drawLine(Lilac, p, q, u * 0.014f, StrokeCap.Round)
                p = q
            }
        }
        // 裙子：上窄下宽，下摆被风带着偏
        val sy = top + u * 0.16f
        val sway = wind * u * 0.07f + sin(t * 2f * PI.toFloat() * 3f) * u * 0.012f * (0.3f + wind)
        val skirt = Path()
        skirt.moveTo(cx - u * 0.05f, sy + u * 0.16f); skirt.lineTo(cx + u * 0.05f, sy + u * 0.16f)
        skirt.lineTo(cx + u * 0.13f + sway, sy + u * 0.33f); skirt.lineTo(cx - u * 0.13f + sway, sy + u * 0.33f); skirt.close()
        drawPath(skirt, Color(0xCC8FD0FF))
        // 风
        for (i in 0 until 3) {
            val y = top + u * (0.02f + i * 0.13f)
            val f = (t * 2f + i * 0.3f) % 1f
            val x = cx - u * 0.3f + f * u * 0.16f
            drawLine(Chalk.copy(alpha = wind * (1f - f)), Offset(x, y), Offset(x + u * 0.07f, y), u * 0.006f, StrokeCap.Round)
        }
        // 右边三根滑杆
        val names = listOf("风力", "重量", "硬度")
        val vals = floatArrayOf(wind, 0.45f, 0.6f)
        for (i in 0 until 3) {
            val y = h * 0.5f - u * 0.14f + i * u * 0.14f
            val a = w * 0.5f + u * 0.12f; val b = w * 0.5f + u * 0.42f
            label(names[i], a + u * 0.05f, y - u * 0.045f, if (i == 0) Gold else Dim.copy(alpha = 0.9f), 0.85f)
            drawLine(Dim, Offset(a, y), Offset(b, y), u * 0.005f, StrokeCap.Round)
            drawLine(if (i == 0) Lilac else Chalk, Offset(a, y), Offset(a + (b - a) * vals[i], y), u * 0.007f, StrokeCap.Round)
            drawCircle(if (i == 0) Lilac else Chalk, u * 0.018f, Offset(a + (b - a) * vals[i], y))
        }
        finger(w * 0.5f + u * 0.12f + u * 0.3f * wind, h * 0.5f - u * 0.14f + u * 0.035f)
    },
    Step("操控模式：摇杆带着角色跑", "点首页右下角的手柄进入，左上角的「退出」回来。", listOf(
        "左边是摇杆：往哪推，角色就朝镜头的那个方向走；推过一半是跑。",
        "右边四个键：冲刺（按住冲，点一下锁定自动冲刺）、跳跃、下蹲、趴下。蹲和趴再点一次站起来。",
        "空白处单指拖动转视角，双指捏合拉远近；镜头一直跟着角色。",
        "走、跑、冲刺、蹲走、匍匐、起跳和落地的动画是按这个模型的腿长现算的，需要标准的 MMD 腿部骨骼（足ＩＫ）。",
        "头发和裙子会跟着跑动甩起来；点「退出操控」回到原来的样子。",
    )) { t ->
        val u = size.minDimension
        val (x0, y0, sw, sh) = screen()
        val phase = (t * 5f).toInt().coerceIn(0, 4)       // 走 跑 跳 蹲 趴
        val cx = x0 + sw * 0.5f; val foot = y0 + sh * 0.72f
        val sw2 = sin(t * 2f * PI.toFloat() * (if (phase == 1) 14f else 8f))
        val jump = if (phase == 2) sin(seg(t, 0.4f, 0.6f) * PI.toFloat()) * u * 0.09f else 0f
        drawLine(Dim, Offset(x0 + sw * 0.2f, foot), Offset(x0 + sw * 0.8f, foot), u * 0.003f)
        when (phase) {
            3 -> figure(cx, foot, u * 0.1f, Chalk, pose = 1, swing = sw2 * 0.5f)
            4 -> figure(cx, foot, u * 0.1f, Chalk, pose = 2, swing = sw2)
            else -> figure(cx, foot - jump, u * 0.1f, Chalk, swing = if (phase == 2) 0.3f else sw2 * (if (phase == 1) 1f else 0.6f))
        }
        if (phase == 1) for (i in 0 until 3) drawLine(Dim, Offset(cx - u * 0.2f, foot - u * (0.06f + i * 0.05f)), Offset(cx - u * 0.12f, foot - u * (0.06f + i * 0.05f)), u * 0.005f, StrokeCap.Round)
        // 左：摇杆
        val jc = Offset(x0 + sw * 0.15f, y0 + sh * 0.72f)
        drawCircle(Color(0x33B9A2FF), u * 0.085f, jc)
        drawCircle(Dim, u * 0.085f, jc, style = Stroke(u * 0.005f))
        val push = when (phase) { 0 -> 0.45f; 1 -> 1f; 3 -> 0.5f; 4 -> 0.5f; else -> 0.3f }
        val knob = Offset(jc.x + u * 0.03f * push, jc.y - u * 0.055f * push)
        drawCircle(Lilac, u * 0.04f, knob)
        finger(knob.x, knob.y + u * 0.01f)
        // 右：四个键
        val names = listOf("冲", "跳", "蹲", "趴")
        val pos = listOf(Offset(x0 + sw * 0.88f, y0 + sh * 0.42f), Offset(x0 + sw * 0.88f, y0 + sh * 0.66f), Offset(x0 + sw * 0.77f, y0 + sh * 0.84f), Offset(x0 + sw * 0.66f, y0 + sh * 0.84f))
        val active = when (phase) { 1 -> 0; 2 -> 1; 3 -> 2; 4 -> 3; else -> -1 }
        for (i in 0 until 4) {
            val r = if (i == 1) u * 0.052f else u * 0.042f
            drawCircle(if (i == active) Color(0xCC7B4DFF) else Color(0x33B9A2FF), r, pos[i])
            drawCircle(if (i == active) Chalk else Dim, r, pos[i], style = Stroke(u * 0.005f))
            label(names[i], pos[i].x, pos[i].y, Chalk, 0.85f)
        }
        label(listOf("走", "跑 / 冲刺", "跳跃", "下蹲", "趴下")[phase], cx, y0 + sh * 0.13f, Gold)
    },
    Step("建模：点、线、面", "「编辑」里的「建模」可以直接改模型的形状，也可以从零开始。", listOf(
        "选点 / 线 / 面，然后挤出、内插、细分、环切、合并……",
        "可以添加方块、球、圆柱等基本形状，从零搭一个道具。",
        "做好的模型存成 PMX，别的 MMD 工具也能打开。",
        "自己建的模型一样能被脚本驱动（示例「环绕的几何体」）。",
    )) { t ->
        val w = size.width; val h = size.height
        val cx = w * 0.5f; val cy = h * 0.74f
        val k = wave(t)
        box(cx, cy, h * 0.2f, 1f, 0.55f, Chalk)
        // 顶面被挤出
        box(cx, cy - h * 0.2f, h * 0.2f, k * 1.3f, 0.55f, Gold, Gold.copy(alpha = 0.35f))
        // 法线方向的箭头
        val topY = cy - h * 0.2f - k * 1.3f * h * 0.2f
        drawLine(Green, Offset(cx, topY), Offset(cx, topY - h * 0.16f), w * 0.014f, StrokeCap.Round)
        val p = Path()
        p.moveTo(cx, topY - h * 0.21f); p.lineTo(cx - h * 0.03f, topY - h * 0.15f); p.lineTo(cx + h * 0.03f, topY - h * 0.15f); p.close()
        drawPath(p, Green)
        finger(cx + h * 0.02f, topY - h * 0.1f)
    },
    Step("让 AI 来帮你做", "菜单 →「AI 操作」，打开开关，把显示的地址填到支持 MCP 的 AI 客户端里（手机和电脑连同一个 Wi-Fi）。", listOf(
        "之后直接对 AI 说“让她挥挥手”“做一张小桌子”，它就能在这里摆姿势、打关键帧、建模。",
        "AI 能直接写 Lua 脚本并运行：写进来、跑起来、看到报错（带行号）和输出再接着改；写好的脚本留在「脚本与特效」里。",
        "也能调物理参数、在轨道上排动作和音乐、加过渡。",
        "它可以自己截图看效果，所以能一边看一边改。",
        "只在你信任的网络里打开这个开关；不用时关掉。",
    )) { t ->
        val w = size.width; val h = size.height
        val sw = w * 0.014f
        // 左：手机；右：机器人（尺寸按画布较短的一边算，窄屏上也不会顶到边）
        val u = minOf(w, h)
        val px = w * 0.25f; val py = h * 0.46f
        drawRoundRect(Chalk, Offset(px - u * 0.17f, py - u * 0.11f), Size(u * 0.34f, u * 0.22f), CornerRadius(u * 0.03f), style = Stroke(sw))
        drawCircle(Gold, u * 0.03f, Offset(px, py - u * 0.025f))
        drawLine(Gold, Offset(px, py), Offset(px, py + u * 0.06f), sw, StrokeCap.Round)
        val rx = w * 0.78f
        val bot = Color(0xFF7FE0A0)
        drawRoundRect(bot, Offset(rx - u * 0.12f, py - u * 0.085f), Size(u * 0.24f, u * 0.19f), CornerRadius(u * 0.035f), style = Stroke(sw))
        drawLine(bot, Offset(rx, py - u * 0.085f), Offset(rx, py - u * 0.155f), sw, StrokeCap.Round)
        drawCircle(bot, u * 0.02f, Offset(rx, py - u * 0.17f))
        drawCircle(bot, u * 0.02f, Offset(rx - u * 0.048f, py))
        drawCircle(bot, u * 0.02f, Offset(rx + u * 0.048f, py))
        // 中间来回的数据包
        val a = px + u * 0.2f; val b = rx - u * 0.15f
        drawLine(Dim, Offset(a, py - u * 0.035f), Offset(b, py - u * 0.035f), 2f)
        drawLine(Dim, Offset(a, py + u * 0.035f), Offset(b, py + u * 0.035f), 2f)
        for (i in 0 until 3) {
            val f = (t * 2f + i / 3f) % 1f
            drawCircle(bot, u * 0.016f, Offset(b - (b - a) * f, py - u * 0.035f))
            drawCircle(Color(0xFF5BC8F5), u * 0.016f, Offset(a + (b - a) * f, py + u * 0.035f))
        }
        // 开关
        val on = abs(t - 0.5f) < 0.45f
        val sx = w * 0.5f - u * 0.11f
        drawRoundRect(if (on) Color(0xFF2E7D5B) else Color(0xFF3A3F4B), Offset(sx, h * 0.74f), Size(u * 0.22f, u * 0.11f), CornerRadius(u * 0.055f))
        drawCircle(Color.White, u * 0.044f, Offset(sx + (if (on) u * 0.165f else u * 0.055f), h * 0.74f + u * 0.055f))
    },
    Step("几个小提示", "遇到问题先看这里。", listOf(
        "模型是白的？贴图没跟着来：按提示授权一次，或者一开始就选整个 zip / 文件夹。",
        "卡、发热：菜单 → 设置，先降「分辨率」和「抗锯齿」，再关阴影和泛光。",
        "做错了：编辑里的撤销箭头；片段的移动、裁剪、分割也能撤销。",
        "场景会自动保存，下次打开接着做。「清空场景」在设置的最下面。",
        "手机空间不够：菜单 → 设置 → 资源库，把不用的模型删掉。",
        "导出：截图（可透明背景）、动作 VMD、镜头 VMD、模型 PMX，别的 MMD 工具都能读。",
        "这个教程随时可以再看：菜单 → 设置 → 新手教程。点左上角的页码可以直接跳到某一页。",
    )) { t ->
        val w = size.width; val h = size.height; val u = size.minDimension
        val x0 = w * 0.5f - u * 0.34f; val y0 = h * 0.5f - u * 0.26f
        drawRoundRect(Color(0xFF241B3D), Offset(x0, y0), Size(u * 0.68f, u * 0.52f), CornerRadius(u * 0.035f))
        val rows = listOf("贴图", "流畅", "撤销", "保存", "导出")
        for (i in rows.indices) {
            val y = y0 + u * (0.07f + i * 0.095f)
            val done = t > 0.1f + i * 0.16f
            drawRoundRect(if (done) Color(0xFF7B4DFF) else Color(0xFF4A4263), Offset(x0 + u * 0.05f, y - u * 0.026f), Size(u * 0.052f, u * 0.052f), CornerRadius(u * 0.014f))
            if (done) {
                val p = Path(); p.moveTo(x0 + u * 0.062f, y); p.lineTo(x0 + u * 0.073f, y + u * 0.012f); p.lineTo(x0 + u * 0.092f, y - u * 0.012f)
                drawPath(p, Color.White, style = Stroke(u * 0.008f, cap = StrokeCap.Round))
            }
            label(rows[i], x0 + u * 0.17f, y, if (done) Chalk else Dim.copy(alpha = 0.9f), 0.9f)
            drawLine(Dim, Offset(x0 + u * 0.25f, y), Offset(x0 + u * (0.4f + (i % 3) * 0.08f), y), u * 0.008f, StrokeCap.Round)
        }
    },
)

/**
 * 新手教程：第一次打开时显示，之后可以从「设置」里再看。每一页是一段循环播放的示意动画加几句说明。
 * 横屏时动画在左、说明在右，竖屏时动画在上、说明在下。
 */
@Composable
fun Tutorial(onDone: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    var toc by remember { mutableStateOf(false) }
    val step = steps[page]
    val transition = rememberInfiniteTransition(label = "tutorial")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val measurer = rememberTextMeasurer()
    InkTheme(PaperInk) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Color(0xC20B0618)).blockTouches()
                .windowInsetsPadding(WindowInsets.safeDrawing).semantics { contentDescription = "新手教程" },
            contentAlignment = Alignment.Center,
        ) {
            val wide = maxWidth > maxHeight
            val stage: @Composable (Modifier) -> Unit = { m -> Canvas(m.background(Stage)) { val pen = Pen(this, measurer); step.draw(pen, t) } }
            val words: @Composable (Modifier) -> Unit = { m ->
                Column(m.padding(start = 18.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) {
                    // 页码：点一下打开目录
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp)).clickable { toc = !toc }.padding(vertical = 3.dp, horizontal = 2.dp).semantics(mergeDescendants = true) { contentDescription = "教程目录" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${page + 1} / ${steps.size}", fontSize = 11.sp, fontFamily = Mono, color = ink.muted)
                        Text(if (toc) "  收起目录" else "  目录", fontSize = 11.sp, color = ink.accent)
                    }
                    if (toc) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
                            for ((i, st) in steps.withIndex()) {
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { page = i; toc = false }.padding(vertical = 7.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${i + 1}", Modifier.width(24.dp), fontSize = 11.sp, fontFamily = Mono, color = if (i == page) ink.accent else ink.faint)
                                    Text(st.title, fontSize = 13.sp, color = if (i == page) ink.accent else ink.text, maxLines = 1)
                                }
                            }
                        }
                    } else {
                        Text(step.title, Modifier.padding(top = 2.dp, bottom = 6.dp), fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp)
                        // 每一页各有自己的滚动位置（翻页后从头看起）；下面还有字没露出来时，底边渐隐提示可以往上划
                        key(page) {
                            val scroll = rememberScrollState()
                            val paper = ink.paper
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                                    Text(step.text, fontSize = 13.sp, lineHeight = 20.sp, color = ink.text)
                                    for ((i, line) in step.points.withIndex()) {
                                        Row(Modifier.padding(top = 7.dp)) {
                                            Box(Modifier.padding(top = 2.dp).size(16.dp).clip(CircleShape).background(ink.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                                                Text("${i + 1}", fontSize = 9.5.sp, lineHeight = 10.sp, fontFamily = Mono, color = ink.accent)
                                            }
                                            Text(line, Modifier.padding(start = 8.dp), fontSize = 12.5.sp, lineHeight = 19.sp, color = ink.muted)
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                                if (scroll.canScrollForward) {
                                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(26.dp).background(Brush.verticalGradient(listOf(paper.copy(alpha = 0f), paper))))
                                }
                            }
                        }
                    }
                    // 进度：一排小点
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                        for (i in steps.indices) Box(Modifier.padding(horizontal = 2.dp).size(if (i == page) 6.dp else 4.dp).clip(CircleShape).background(if (i == page) ink.accent else ink.faint.copy(alpha = 0.5f)))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("跳过", Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDone).padding(horizontal = 8.dp, vertical = 8.dp), fontSize = 13.sp, color = ink.muted)
                        Spacer(Modifier.weight(1f))
                        if (page > 0) Text("上一步", Modifier.clip(RoundedCornerShape(6.dp)).clickable { page--; toc = false }.padding(horizontal = 10.dp, vertical = 8.dp), fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        if (page < steps.size - 1) SolidButton("下一步", fill = ink.accent, content = ink.onAccent) { page++; toc = false } else SolidButton("开始使用", fill = ink.accent, content = ink.onAccent, onClick = onDone)
                    }
                }
            }
            val shape = RoundedCornerShape(14.dp)
            if (wide) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp).widthIn(max = 780.dp).heightIn(max = 350.dp).fillMaxHeight().clip(shape).background(ink.paper)) {
                    stage(Modifier.fillMaxHeight().weight(0.9f))
                    words(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 20.dp).widthIn(max = 440.dp).heightIn(max = 640.dp).fillMaxHeight().clip(shape).background(ink.paper)) {
                    stage(Modifier.fillMaxWidth().weight(0.8f))
                    words(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

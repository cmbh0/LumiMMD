package com.lumi.mmd.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.delay
import androidx.compose.ui.window.Dialog

// ============================================================================ 颜色
//
// 界面的写法参照 Reze Design：画面是主角，界面只是压在画面上的字。
// 能不用底色块就不用——一行一条发丝线，滑杆是一根细线加一颗小珠子，选中的东西用下划线而不是色块。
// 只有三种带含义的颜色：紫色 = 选中，琥珀色 = 提醒，红色 = 删除；其余都是墨色的深浅。

/** 一套界面颜色。 */
class Ink(
    /** 面的底色。 */
    val paper: Color,
    /** 正文（墨色）。 */
    val text: Color,
    /** 次要文字。 */
    val muted: Color,
    /** 更淡：禁用、占位。 */
    val faint: Color,
    /** 发丝线。 */
    val line: Color,
    /** 轻微下沉的底（选中的行、输入框）。 */
    val well: Color,
    /** 选中。 */
    val accent: Color,
    val onAccent: Color,
    /** 提醒。 */
    val warn: Color,
    /** 删除等不可恢复的操作。 */
    val danger: Color,
    val light: Boolean,
)

/** 紫罗兰：整套界面的主色。深浅两档，浅的用在深色底上。 */
val Violet = Color(0xFF7B4DFF)
val VioletSoft = Color(0xFFB9A2FF)

/** 纸面：整套界面默认的样子（面板、菜单卡片、对话框）。带一点紫的白纸，墨色也偏紫。 */
val PaperInk = Ink(
    paper = Color(0xFFFBF9FF), text = Color(0xFF1C1730), muted = Color(0xFF6E6887), faint = Color(0xFFB8B2CE),
    line = Color(0x1F2B1A66), well = Color(0x0F5B3FD0), accent = Violet, onAccent = Color.White,
    warn = Color(0xFFB25E09), danger = Color(0xFFD23A2E), light = true,
)

/** 深色的工作台（设置里打开「深色界面」才用）：很深的紫黑色。 */
val BenchInk = Ink(
    paper = Color(0xFF15111F), text = Color(0xFFEFEBFA), muted = Color(0xFFA49DBB), faint = Color(0xFF625B79),
    line = Color(0x2EC7B6FF), well = Color(0x17C7B6FF), accent = VioletSoft, onAccent = Color(0xFF1A1230),
    warn = Color(0xFFF5B544), danger = Color(0xFFFF6F66), light = false,
)

val LocalInk = staticCompositionLocalOf { PaperInk }

/** 三维画面的背景是不是亮的（压在画面上的字和按钮据此选深色还是浅色）。主界面按当前选的背景来设。 */
object SceneTone {
    private val state = mutableStateOf(true)
    var light: Boolean
        get() = state.value
        set(v) { state.value = v }
}

/** 压在三维画面上的颜色：背景暗时用 [dark]，背景亮时用 [light]。 */
@Composable
@ReadOnlyComposable
fun onScene(dark: Color, light: Color): Color = if (SceneTone.light) light else dark

/** 当前这块界面用的颜色。 */
val ink: Ink @Composable @ReadOnlyComposable get() = LocalInk.current

// 旧名字（各个面板里一直在用），现在都从当前的 Ink 里取
val Accent: Color @Composable @ReadOnlyComposable get() = LocalInk.current.accent
val AccentDim: Color @Composable @ReadOnlyComposable get() = LocalInk.current.accent.copy(alpha = 0.45f)
val PanelColor: Color @Composable @ReadOnlyComposable get() = LocalInk.current.paper
val BarColor: Color @Composable @ReadOnlyComposable get() = LocalInk.current.paper
val CardColor: Color @Composable @ReadOnlyComposable get() = LocalInk.current.well
val TextDim: Color @Composable @ReadOnlyComposable get() = LocalInk.current.muted
val Danger: Color @Composable @ReadOnlyComposable get() = LocalInk.current.danger
val Warn: Color @Composable @ReadOnlyComposable get() = LocalInk.current.warn

/** 关键帧的颜色（时间轴和按钮上通用，不随纸面 / 工作台变化）。 */
val KeyGold = Color(0xFFEE9A12)

/** 发丝线的粗细。 */
val HairWidth: Dp = 0.75.dp

/** 等宽字体：数值、时间、帧号用。 */
val Mono = FontFamily.Monospace

@Composable
fun InkTheme(colors: Ink, content: @Composable () -> Unit) {
    val scheme = if (colors.light) lightColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, secondary = colors.accent, background = colors.paper, onBackground = colors.text,
        surface = colors.paper, onSurface = colors.text, surfaceVariant = Color(0xFFEEEAF8), onSurfaceVariant = colors.muted,
        surfaceContainerLowest = Color.White, surfaceContainerLow = colors.paper, surfaceContainer = colors.paper,
        surfaceContainerHigh = colors.paper, surfaceContainerHighest = Color(0xFFEEEAF8),
        primaryContainer = Color(0xFFE9E0FF), onPrimaryContainer = colors.text, secondaryContainer = Color(0xFFE9E0FF), onSecondaryContainer = colors.text,
        outline = Color(0x402B1A66), outlineVariant = Color(0x1F2B1A66), error = colors.danger,
    ) else darkColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, secondary = colors.accent, background = Color(0xFF100D18), onBackground = colors.text,
        surface = Color(0xFF191424), onSurface = colors.text, surfaceVariant = Color(0xFF2A2439), onSurfaceVariant = colors.muted,
        surfaceContainerLowest = Color(0xFF100D18), surfaceContainerLow = Color(0xFF191424), surfaceContainer = Color(0xFF1D182A),
        surfaceContainerHigh = Color(0xFF221C30), surfaceContainerHighest = Color(0xFF2A2439),
        primaryContainer = Color(0xFF3B2C6E), onPrimaryContainer = colors.text, secondaryContainer = Color(0xFF3B2C6E), onSecondaryContainer = colors.text,
        outline = Color(0x40C7B6FF), outlineVariant = Color(0x2EC7B6FF), error = colors.danger,
    )
    MaterialTheme(colorScheme = scheme) {
        // 界面不是套在 Material 的 Surface 里的（底下是 GL 画面），默认文字颜色要自己给
        CompositionLocalProvider(LocalInk provides colors, LocalContentColor provides colors.text, content = content)
    }
}

/** 整个应用的根：默认是白底紫罗兰的纸面；设置里可以把编辑面板换成深色。 */
@Composable
fun LumiTheme(content: @Composable () -> Unit) = InkTheme(PaperInk, content)

// ============================================================================ 基本件

/** 一条发丝线。 */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(HairWidth).background(ink.line))
}

/** 在一行的底边画发丝线。 */
@Composable
fun Modifier.bottomLine(): Modifier {
    val c = ink.line
    return drawBehind { drawLine(c, Offset(0f, size.height), Offset(size.width, size.height), HairWidth.toPx()) }
}

/** 没有底色的图标按钮（一行末尾的小动作、画面上的按钮）。label 是给读屏和测试用的说明，不显示出来。 */
@Composable
fun BareIcon(
    icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = ink.text,
    size: Dp = 40.dp, iconSize: Dp = 18.dp, onClick: () -> Unit,
) {
    Box(
        modifier.size(size).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(iconSize), tint = if (enabled) tint else ink.faint)
    }
}

/**
 * 列表里的一行：图标、名字、右边的值和动作；点一下执行动作或者往里进一层。
 * 每行一样高，底下一条发丝线。
 */
@Composable
fun ListRow(
    label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, value: String = "", valueColor: Color = ink.muted,
    chevron: Boolean = false, enabled: Boolean = true, color: Color = ink.text, labelWidth: Dp = Dp.Unspecified,
    end: (@Composable RowScope.() -> Unit)? = null, onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).bottomLine()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(17.dp), tint = if (enabled) color else ink.faint); HSpace(11) }
        Text(label, if (labelWidth != Dp.Unspecified) Modifier.width(labelWidth) else Modifier, fontSize = 14.sp, color = if (enabled) color else ink.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, Modifier.weight(1f).padding(start = 10.dp, end = if (chevron) 2.dp else 0.dp), fontSize = 12.sp, color = valueColor, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = if (labelWidth != Dp.Unspecified) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.End)
        if (end != null) end()
        if (chevron) Icon(LumiIcons.ChevronRight, null, Modifier.size(14.dp), tint = ink.faint)
    }
}

/**
 * 一个文件槽：里面放着什么；点这一行去挑文件（从手机里选，或者用导入过的），右边的叉是清空。
 * 清空的按钮一直在原位（空的时候变灰），这样整列上下对齐。
 */
@Composable
fun SlotRow(label: String, name: String, onOpen: () -> Unit, onRemove: () -> Unit, enabled: Boolean = true) {
    ListRow(label, Modifier.semantics { contentDescription = "选择$label" }, labelWidth = 44.dp, value = name.ifEmpty { "点这里选" }, valueColor = if (name.isEmpty()) ink.faint else ink.muted, enabled = enabled,
        end = { BareIcon(LumiIcons.Close, "清空$label", enabled = enabled && name.isNotEmpty(), onClick = onRemove) }, chevron = true, onClick = onOpen)
}

/** 一排可选的词：选中的是墨色加一条紫色下划线，没选中的是灰色。 */
@Composable
fun <T> Words(options: List<Pair<String, T>>, selected: T, modifier: Modifier = Modifier, fontSize: Int = 13, onSelect: (T) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((text, v) in options) Word(text, v == selected, fontSize = fontSize) { onSelect(v) }
    }
}

@Composable
fun Word(text: String, selected: Boolean, modifier: Modifier = Modifier, fontSize: Int = 13, color: Color = ink.text, dim: Color = ink.muted, onClick: () -> Unit) {
    val accent = ink.accent
    Box(
        modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).heightIn(min = 34.dp).padding(horizontal = 7.dp)
            .semantics(mergeDescendants = true) { this.selected = selected; role = Role.Tab },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = fontSize.sp, maxLines = 1, color = if (selected) color else dim, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.padding(vertical = 4.dp).drawBehind {
                if (selected) drawLine(accent, Offset(0f, size.height + 3.dp.toPx()), Offset(size.width, size.height + 3.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
            },
        )
    }
}

/**
 * 给横向滚动的一行用：哪一侧还有内容没露出来，那一侧的边缘就淡出去，
 * 这样被挡住的字是"渐渐没入"，而不是被旁边的按钮硬切一刀。写在 horizontalScroll 的前面。
 */
fun Modifier.fadeEdges(state: ScrollState, width: Dp = 18.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = minOf(width.toPx(), size.width / 2f)
        if (w <= 0f) return@drawWithContent
        if (state.value > 0) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = w), size = Size(w, size.height), blendMode = BlendMode.DstIn)
        }
        if (state.value < state.maxValue) {
            drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
                topLeft = Offset(size.width - w, 0f), size = Size(w, size.height), blendMode = BlendMode.DstIn)
        }
    }

/** 一个屏幕上最多一个的实心按钮（最主要的那个动作）。 */
@Composable
fun SolidButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, fill: Color = ink.text, content: Color = ink.paper, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(fill).clickable(onClick = onClick).heightIn(min = 34.dp).padding(start = if (icon != null) 10.dp else 14.dp, end = 14.dp)
            .semantics(mergeDescendants = true) { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(15.dp), tint = content); HSpace(5) }
        Text(text, fontSize = 13.sp, color = content, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 纸面上的对话框。 */
@Composable
fun LumiDialog(
    onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null,
) {
    InkTheme(PaperInk) {
        AlertDialog(
            onDismissRequest = onDismissRequest, confirmButton = confirmButton, modifier = modifier, dismissButton = dismissButton, title = title, text = text,
            shape = RoundedCornerShape(10.dp), containerColor = PaperInk.paper, titleContentColor = PaperInk.text, textContentColor = PaperInk.text, tonalElevation = 0.dp,
        )
    }
}

// ============================================================================ 面板里通用的控件（扁平样式）

/** 可选中的小标签按钮：细线框，选中时换成紫色。 */
@Composable
fun Chip(
    text: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true,
    icon: ImageVector? = null, onClick: () -> Unit,
) {
    val fg = if (!enabled) ink.faint else if (selected) ink.accent else ink.text
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .then(if (selected) Modifier.background(ink.accent.copy(alpha = 0.10f)) else Modifier)
            .border(HairWidth, if (selected) ink.accent.copy(alpha = 0.7f) else ink.line, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics(mergeDescendants = true) { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Icon(icon, null, Modifier.padding(end = 5.dp).size(15.dp), tint = fg)
        Text(text, color = fg, fontSize = 12.5.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
    }
}

/** 按按钮上的文字挑一个合适的图标（所有操作按钮都带图标）。 */
private fun iconFor(text: String): ImageVector = when {
    text.contains("导入文件夹") || text.contains("文件夹") -> LumiIcons.Folder
    text == "添加" -> LumiIcons.Plus
    text == "已添加" -> LumiIcons.Check
    text.contains("运行") -> LumiIcons.Play
    text == "复原" || text == "还原" -> LumiIcons.Undo
    text.contains("复制") -> LumiIcons.Copy
    text.contains("导入") || text.contains("添加") -> LumiIcons.Export
    text.contains("自动") -> LumiIcons.Check
    text.contains("所有角色") || text.startsWith("角色") -> LumiIcons.Person
    text.contains("舞台") -> LumiIcons.Home
    text.contains("配件") -> LumiIcons.Cube
    text.contains("叠加") -> LumiIcons.Layers
    text.contains("动作") -> LumiIcons.Play
    text.contains("姿势") && !text.contains("重置") -> LumiIcons.Bone
    text.contains("音乐") -> LumiIcons.Music
    text.contains("删除") || text.contains("移除") || text.contains("清除") || text.contains("清空") || text.contains("解除") -> LumiIcons.Trash
    text.contains("灯光") -> LumiIcons.Sun
    text.contains("落地") -> LumiIcons.Ground
    text.contains("一人高") -> LumiIcons.Scale
    text.contains("立起来") -> LumiIcons.StandUp
    text.contains("转身") -> LumiIcons.TurnAround
    text.contains("对准") -> LumiIcons.Focus
    text.contains("挂到") -> LumiIcons.Link
    text.contains("复位") || text.contains("重置") -> LumiIcons.Reset
    text.contains("截图") -> LumiIcons.Photo
    text.contains("MMD") || text.contains("柔光") || text.contains("鲜艳") || text.contains("电影") -> LumiIcons.Palette
    text.contains("VMD") || text.contains("PMX") || text.contains("存为") -> LumiIcons.Save
    else -> LumiIcons.Check
}

/** 细线框的操作按钮：图标 + 文字。 */
@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, color: Color = ink.text, enabled: Boolean = true, onClick: () -> Unit) {
    val c = if (enabled) color else ink.faint
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .border(HairWidth, if (color == ink.text) ink.line else c.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 34.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(iconFor(text), null, Modifier.size(15.dp), tint = c)
        Text(text, Modifier.padding(start = 6.dp), color = c, fontSize = 12.5.sp, maxLines = 1)
    }
}

/** 一组设置的小标题：一行不起眼的灰字。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 18.dp, bottom = 2.dp), color = ink.muted, fontSize = 11.5.sp)
}

/** 开关：一个小药丸，开着的时候是紫罗兰色（深色界面下是亮的墨色）。 */
@Composable
fun InkSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, label: String = "", onChange: (Boolean) -> Unit) {
    val on = if (ink.light) ink.accent else ink.text
    val off = ink.line
    val knob = if (checked) ink.paper.copy(alpha = 1f) else ink.muted
    Box(
        modifier.size(width = 46.dp, height = 40.dp).clip(RoundedCornerShape(50)).clickable(enabled = enabled) { onChange(!checked) }
            .semantics(mergeDescendants = true) { role = Role.Switch; this.selected = checked; if (label.isNotEmpty()) contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 34.dp, height = 20.dp).clip(RoundedCornerShape(50)).then(if (checked) Modifier.background(on) else Modifier.border(1.dp, off.copy(alpha = 0.55f), RoundedCornerShape(50))),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(horizontal = 3.dp).size(14.dp).clip(CircleShape).background(knob))
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, modifier: Modifier = Modifier, hint: String = "", onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).bottomLine().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
            Text(label, fontSize = 14.sp)
            if (hint.isNotEmpty()) Text(hint, fontSize = 11.sp, color = ink.muted, lineHeight = 14.sp)
        }
        InkSwitch(checked, onChange = onChange)
    }
}

/** 1、2、5、10… 这一串里不小于 x 的最小值（加减号一下走多少，取个整齐的数）。 */
internal fun niceStep(x: Float): Float {
    if (!(x > 0f) || x.isInfinite()) return 0.01f
    val base = Math.pow(10.0, Math.floor(Math.log10(x.toDouble()))).toFloat()
    val f = x / base
    return (if (f <= 1.0001f) 1f else if (f <= 2.0001f) 2f else if (f <= 5.0001f) 5f else 10f) * base
}

/** 没有上下限的滑杆（位置这类）：数值快到头时，量程按 1、2、5、10… 倍放大，回到小数值时再缩回来。 */
internal fun growRange(base: ClosedFloatingPointRange<Float>, v: Float): ClosedFloatingPointRange<Float> {
    val m = maxOf(kotlin.math.abs(base.start), kotlin.math.abs(base.endInclusive))
    if (!(m > 0f) || v != v) return base
    var k = 1f
    var i = 0
    while (kotlin.math.abs(v) > m * k * 0.85f && k < 1e6f) { k *= if (i % 3 == 1) 2.5f else 2f; i++ }
    return (base.start * k)..(base.endInclusive * k)
}

/** 输入框里显示的数：最多三位小数，去掉末尾的零。 */
internal fun plainNumber(v: Float): String {
    if (v != v || v.isInfinite()) return "0"
    val t = String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
    return if (t == "-0" || t.isEmpty()) "0" else t
}

/**
 * 带名字和数值的滑杆。
 *
 *     位置 X                         12.35      ← 点数值可以直接输入
 *     (−) ─────────●──────────────── (+)        ← 滑杆粗调，两头的加减号细调（按住不放会连续走）
 *
 * - [step]：加减号一下走多少；0 = 按量程自动取（量程的百分之一左右，取整齐的数）。
 * - [unbounded]：数值没有上下限（位置这类）。加减号和输入可以超出量程，量程会自动放大。
 * - [toNumber] / [fromNumber]：滑杆内部的值和输入框里的数不是同一个东西时用（比如对数刻度的缩放）。
 *
 * 拖动、点加减号之后显示的是本地的值——外部的值要经过渲染线程绕一圈才回来，直接用它会让珠子来回抖。
 */
@Composable
fun LabeledSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier,
    format: (Float) -> String = { "%.2f".format(it) }, onFinished: (() -> Unit)? = null,
    step: Float = 0f, unbounded: Boolean = false,
    toNumber: (Float) -> Float = { it }, fromNumber: (Float) -> Float = { it },
    onChange: (Float) -> Unit,
) {
    var local by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    var typing by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    // 松手 / 点完加减号之后，本地的值再留一会儿，等外部的值跟上来
    LaunchedEffect(tick, dragging) { if (!dragging && local != null) { delay(650); local = null } }
    // 万一传进来的不是个数（NaN / 无穷大），按量程里的 0（或者起点）画，不能让滑杆崩掉
    val current = (local ?: value).let { if (it.isNaN() || it.isInfinite()) 0f.coerceIn(range.start, range.endInclusive) else it }
    val eff = frozen ?: if (unbounded) growRange(range, current) else range
    val thumb = current.coerceIn(eff.start, eff.endInclusive)
    val shown = if (unbounded) current else current.coerceIn(range.start, range.endInclusive)
    val st = if (step > 0f) step else niceStep((range.endInclusive - range.start) / 100f)
    fun commit(v: Float) {
        if (v != v) return
        val c = if (unbounded) v else v.coerceIn(range.start, range.endInclusive)
        local = c; tick++
        onChange(c); onFinished?.invoke()
    }
    // 加减号：落在步长的整数倍上（0.37 加一下是 0.4，再加是 0.5）
    fun stepBy(dir: Int) = commit(Math.round((shown + dir * st) / st) * st)
    Column(modifier.fillMaxWidth().padding(top = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                format(shown),
                Modifier.clip(RoundedCornerShape(5.dp)).clickable { typing = true }.padding(horizontal = 5.dp, vertical = 1.dp)
                    .semantics { contentDescription = "$label 的数值"; role = Role.Button },
                fontSize = 11.5.sp, fontFamily = Mono, color = if (local != null) ink.accent else ink.muted, maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(LumiIcons.Minus, "$label 减", enabled = unbounded || shown > range.start) { stepBy(-1) }
            LumiSlider(
                value = thumb, valueRange = eff,
                onValueChange = { if (!dragging) { dragging = true; frozen = eff }; local = it; onChange(it) },
                onValueChangeFinished = { dragging = false; frozen = null; tick++; onFinished?.invoke() },
                modifier = Modifier.weight(1f).height(28.dp).semantics { contentDescription = label },
            )
            StepButton(LumiIcons.Plus, "$label 加", enabled = unbounded || shown < range.endInclusive) { stepBy(1) }
        }
    }
    if (typing) {
        NumberDialog(
            label, toNumber(shown),
            hint = if (unbounded) "" else "范围 ${plainNumber(toNumber(range.start))} ～ ${plainNumber(toNumber(range.endInclusive))}",
            onDismiss = { typing = false },
        ) { typing = false; commit(fromNumber(it)) }
    }
}

/**
 * 滑杆两头的加减号：点一下走一步；按住不放，过一会儿开始连续走，越走越快。
 */
@Composable
fun StepButton(icon: ImageVector, description: String, enabled: Boolean = true, size: Dp = 30.dp, onStep: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val step by rememberUpdatedState(onStep)
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        delay(420)
        var n = 0
        while (true) { repeated = true; step(); n++; delay(if (n < 10) 110L else if (n < 30) 60L else 30L) }
    }
    Box(
        Modifier.size(size).clip(CircleShape)
            // 连续走过之后松手的那一下不再多走一步
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled) { if (repeated) repeated = false else step() }
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = if (enabled) ink.text else ink.faint.copy(alpha = 0.5f))
    }
}

/**
 * 直接输入一个数：自带一个小键盘，不弹系统输入法。
 * （横屏时系统键盘会盖住大半个画面，而且数字键盘上不一定有负号和小数点。）
 * 打开时显示的是现在的值；第一下按数字就是重新输入，按退格 / 正负则是在原来的数上改。
 */
@Composable
fun NumberDialog(label: String, initial: Float, hint: String = "", onDismiss: () -> Unit, onDone: (Float) -> Unit) {
    var text by remember { mutableStateOf(plainNumber(initial)) }
    var fresh by remember { mutableStateOf(true) }
    val parsed = text.toFloatOrNull()?.takeIf { it == it && !it.isInfinite() }
    fun key(k: String) {
        when (k) {
            "退格" -> { text = text.dropLast(1); if (text == "-" || text.isEmpty()) text = "" }
            "正负" -> text = if (text.startsWith("-")) text.drop(1) else "-$text"
            "清空" -> text = ""
            "小数点" -> { if (fresh) text = "0"; if (!text.contains('.')) text = (if (text.isEmpty() || text == "-") text + "0" else text) + "." }
            else -> {
                if (fresh) text = ""
                if (text.length < 12) text = (if (text == "0") "" else if (text == "-0") "-" else text) + k
            }
        }
        fresh = false
    }
    Dialog(onDismissRequest = onDismiss) {
        InkTheme(PaperInk) {
            Column(Modifier.width(264.dp).clip(RoundedCornerShape(12.dp)).background(PaperInk.paper).padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), fontSize = 13.sp, color = ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (hint.isNotEmpty()) Text(hint, fontSize = 10.5.sp, color = ink.faint, maxLines = 1)
                }
                Text(
                    text.ifEmpty { "0" },
                    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp).clip(RoundedCornerShape(8.dp)).background(ink.well).padding(horizontal = 10.dp, vertical = 6.dp)
                        .semantics { contentDescription = "输入的数" },
                    fontSize = 22.sp, fontFamily = Mono, color = if (fresh) ink.muted else ink.text, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
                val rows = listOf(listOf("7", "8", "9", "退格"), listOf("4", "5", "6", "正负"), listOf("1", "2", "3", "清空"), listOf("0", "小数点", "取消", "确定"))
                for (row in rows) Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (k in row) {
                        val face = when (k) { "退格" -> "⌫"; "正负" -> "+/−"; "小数点" -> "."; else -> k }
                        val isOk = k == "确定"
                        val enabled = !isOk || parsed != null
                        Box(
                            Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (isOk) ink.accent.copy(alpha = if (enabled) 1f else 0.35f) else if (k.length == 1) ink.well else Color.Transparent)
                                .then(if (k.length == 1 || isOk) Modifier else Modifier.border(HairWidth, ink.line, RoundedCornerShape(8.dp)))
                                .clickable(enabled = enabled) {
                                    when (k) { "取消" -> onDismiss(); "确定" -> parsed?.let(onDone); else -> key(k) }
                                }
                                .semantics { contentDescription = if (isOk || k == "取消") k else "数字键 $k"; role = Role.Button },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(face, fontSize = if (k.length == 1) 17.sp else 13.sp, fontFamily = if (k.length == 1) Mono else null, color = if (isOk) ink.onAccent else ink.text)
                        }
                    }
                }
            }
        }
    }
}

/** 发丝线滑杆：一根细线，走过的一段和上面那颗小珠子是紫罗兰色（深色界面下是亮的墨色）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumiSlider(
    value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier,
    enabled: Boolean = true, onValueChangeFinished: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val active = if (!enabled) ink.faint else if (ink.light) ink.accent else ink.text
    val rest = ink.line
    Slider(
        value = value, onValueChange = onValueChange, valueRange = valueRange, enabled = enabled,
        onValueChangeFinished = onValueChangeFinished, modifier = modifier, interactionSource = interaction,
        thumb = { Box(Modifier.size(11.dp).clip(CircleShape).background(active)) },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(11.dp)) {
                val span = state.valueRange.endInclusive - state.valueRange.start
                val f = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
                val y = size.height / 2f
                drawLine(rest, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), StrokeCap.Round)
                drawLine(active, Offset(0f, y), Offset(size.width * f, y), 1.5.dp.toPx(), StrokeCap.Round)
            }
        },
    )
}

/** 角度显示：四舍五入到整数，并且不出现 "-0°"。 */
fun formatDegrees(v: Float): String {
    val r = Math.round(v)
    return "${if (r == 0) 0 else r}°"
}

/** 一行若干个单选的词。 */
@Composable
fun <T> ChoiceRow(label: String, options: List<Pair<String, T>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).bottomLine(), verticalAlignment = Alignment.CenterVertically) {
        if (label.isNotEmpty()) Text(label, Modifier.widthIn(min = 64.dp, max = 110.dp).padding(end = 6.dp), fontSize = 14.sp)
        // 字体放大或屏幕很窄时可以横向滑动，不会被挤出屏幕
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState(), reverseScrolling = true), horizontalArrangement = Arrangement.End) {
            Words(options, selected, onSelect = onSelect)
        }
    }
}

@Composable
fun HSpace(w: Int) = Spacer(Modifier.width(w.dp))

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))

fun formatTime(frame: Float): String {
    val total = (frame / 30f).toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}

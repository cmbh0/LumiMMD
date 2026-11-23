package com.lumi.mmd.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val IconTint: Color @Composable @ReadOnlyComposable get() = LocalInk.current.text
val IconDisabled: Color @Composable @ReadOnlyComposable get() = LocalInk.current.faint
val SelectedBg: Color @Composable @ReadOnlyComposable get() = LocalInk.current.well

/**
 * 只有图标的按钮（工具栏上用）。label 是给读屏和测试用的说明，不显示出来。
 * 选中时换成紫色、垫一层很淡的底；长按可以挂第二个动作。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IconBtn(
    icon: ImageVector, label: String, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true,
    tint: Color = IconTint, size: Dp = 36.dp, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SelectedBg else Color.Transparent)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = label; role = Role.Button; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = if (!enabled) IconDisabled else if (selected) Accent else tint)
    }
}

/** 图标在上、文字在下的工具按钮（面板里的工具格子用）。没有底色，选中时变成紫色。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolBtn(
    icon: ImageVector, label: String, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true,
    tint: Color = IconTint, width: Dp = 47.dp, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Column(
        modifier
            .width(width)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) SelectedBg else Color.Transparent)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) { role = Role.Button; this.selected = selected }
            .padding(vertical = 4.dp, horizontal = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(19.dp), tint = if (!enabled) IconDisabled else if (selected) Accent else tint)
        Text(label, Modifier.padding(top = 1.dp), fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Clip,
            color = if (!enabled) IconDisabled else if (selected) Accent else LocalInk.current.muted)
    }
}

/** 图标 + 文字横排的按钮（比较重要的单个动作）：细线框。 */
@Composable
fun IconTextBtn(icon: ImageVector, text: String, modifier: Modifier = Modifier, color: Color = LocalInk.current.text, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    val c = if (enabled) color else IconDisabled
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .then(if (filled) Modifier.background(c.copy(alpha = 0.12f)) else Modifier)
            .border(HairWidth, c.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .combinedClickableCompat(enabled, onClick)
            .heightIn(min = 34.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = c)
        Text(text, Modifier.padding(start = 6.dp), color = c, fontSize = 12.5.sp, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(enabled: Boolean, onClick: () -> Unit): Modifier = combinedClickable(enabled = enabled, onClick = onClick)

/** 面板里的小标题。 */
@Composable
fun PanelLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 10.dp, bottom = 3.dp), color = LocalInk.current.muted, fontSize = 11.sp)
}

/** 自动换行的工具格子。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolGrid(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}

/** 竖直分隔线（工具栏里分组用）。 */
@Composable
fun BarSep() {
    Box(Modifier.padding(horizontal = 5.dp).width(HairWidth).height(18.dp).background(LocalInk.current.line))
}

/**
 * 挡住触摸：面板的空白处不要把点击漏给底下的三维画面（否则点一下面板的空白就会误选模型）。
 * 只要挂了指针输入节点，底下的兄弟节点就收不到这个位置的事件。
 */
fun Modifier.blockTouches(): Modifier = pointerInput(Unit) { }

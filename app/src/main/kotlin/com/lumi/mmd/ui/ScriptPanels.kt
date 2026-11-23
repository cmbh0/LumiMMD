package com.lumi.mmd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.lumi.mmd.engine.script.LuaSyntax
import com.lumi.mmd.core.ModelUi
import com.lumi.mmd.core.SceneController
import com.lumi.mmd.core.ScriptUi
import com.lumi.mmd.core.Slot
import com.lumi.mmd.core.UiState
import com.lumi.mmd.engine.script.ScriptLibrary
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
private fun PageNote(text: String) {
    Text(text, Modifier.padding(top = 10.dp), fontSize = 11.5.sp, lineHeight = 17.sp, color = ink.muted)
}

// ============================================================================ 脚本

/** 给 Lua 代码上颜色（关键字、字符串、数字、注释、应用提供的表和函数）。颜色跟着当前这块界面是浅色还是深色走。 */
private class LuaHighlight(private val light: Boolean, private val accent: Color, private val comment: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val src = text.text
        // 特别长的代码就不上色了：每敲一个字都要重新排版，颜色段太多会拖慢输入
        if (src.length > 60_000) return TransformedText(text, OffsetMapping.Identity)
        val spans = LuaSyntax.highlight(src)
        val b = AnnotatedString.Builder(src)
        var i = 0
        while (i < spans.size) {
            val style = when (spans[i + 2]) {
                LuaSyntax.KEYWORD -> SpanStyle(color = accent, fontWeight = FontWeight.Medium)
                LuaSyntax.STRING -> SpanStyle(color = if (light) Color(0xFF1F7A4A) else Color(0xFF8ED6A5))
                LuaSyntax.NUMBER -> SpanStyle(color = if (light) Color(0xFFB0560F) else Color(0xFFF2B47A))
                LuaSyntax.COMMENT -> SpanStyle(color = comment, fontStyle = FontStyle.Italic)
                LuaSyntax.API -> SpanStyle(color = if (light) Color(0xFF1D62B8) else Color(0xFF8FC3FF))
                else -> SpanStyle(color = if (light) Color(0xFF9A2F8E) else Color(0xFFE7A1DE))
            }
            b.addStyle(style, spans[i], spans[i + 1])
            i += 3
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean = other is LuaHighlight && other.light == light && other.accent == accent && other.comment == comment
    override fun hashCode(): Int = accent.hashCode() * 31 + light.hashCode()
}

/** 手机键盘上不好找的符号：补全条的后半截。 */
private val CodeSymbols = listOf("(", ")", "\"", "=", ".", ":", ",", "{", "}", "[", "]", "-", "+", "*", "/", "<", ">", "~=", "==", "#", "..", "_")

/**
 * 写代码的框：等宽字，带颜色；正在输入时上面有一条补全（点一下把正在敲的词补完）和一排符号键。
 * 回车后自动跟上一行的缩进。
 * @param height 给了高度就固定这么高、里面自己滚动，补全条一直钉在最上面；不给就随内容长高
 */
@Composable
private fun CodeField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, hint: String = "",
    onFocus: ((Boolean) -> Unit)? = null, height: Dp? = null, minHeight: Dp = 0.dp,
) {
    val shape = RoundedCornerShape(8.dp)
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    // 外面把内容换掉了（还原、读到了文件）：跟过去，光标尽量留在原处
    if (field.text != value) field = TextFieldValue(value, TextRange(field.selection.start.coerceIn(0, value.length)))
    var focused by remember { mutableStateOf(false) }
    val highlight = LuaHighlight(ink.light, ink.accent, ink.muted)
    val completion = remember(field.text, field.selection, focused) {
        if (focused && field.selection.collapsed) LuaSyntax.complete(field.text, field.selection.start) else null
    }

    fun set(v: TextFieldValue) { field = v; if (v.text != value) onChange(v.text) }

    /** 把 [from, to) 换成 text，光标停在新内容的末尾往回 back 个字符处。 */
    fun replace(from: Int, to: Int, text: String, back: Int = 0) {
        val t = field.text
        val a = from.coerceIn(0, t.length); val z = to.coerceIn(a, t.length)
        set(TextFieldValue(t.substring(0, a) + text + t.substring(z), TextRange((a + text.length - back).coerceIn(a, a + text.length))))
    }

    val onType: (TextFieldValue) -> Unit = { v ->
        val old = field
        var next = v
        // 刚敲了一个回车：新的一行跟上一行对齐（上一行是 then / do / function(…) 结尾的再多缩进一层）
        if (v.selection.collapsed && old.selection.collapsed && v.text.length == old.text.length + 1) {
            val c = v.selection.start
            if (c > 0 && c == old.selection.start + 1 && v.text[c - 1] == '\n' && v.text.regionMatches(0, old.text, 0, c - 1) && v.text.regionMatches(c, old.text, c - 1, old.text.length - (c - 1))) {
                val ind = LuaSyntax.newlineIndent(v.text, c)
                if (ind.isNotEmpty()) next = TextFieldValue(v.text.substring(0, c) + ind + v.text.substring(c), TextRange(c + ind.length))
            }
        }
        set(next)
    }

    Column(if (height != null) modifier.height(height) else modifier) {
        if (focused) {
            Row(
                Modifier.fillMaxWidth().height(34.dp).horizontalScroll(rememberScrollState()).semantics { contentDescription = "代码补全" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (completion != null) {
                    for (s in completion.items) {
                        Text(
                            s.label,
                            Modifier.clip(RoundedCornerShape(6.dp)).background(ink.accent.copy(alpha = 0.14f)).clickable { replace(completion.start, completion.end, s.insert, s.cursorBack) }
                                .padding(horizontal = 8.dp, vertical = 5.dp).semantics { contentDescription = "补全 ${s.label}" },
                            fontSize = 12.sp, fontFamily = Mono, color = ink.accent, maxLines = 1,
                        )
                    }
                    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(16.dp).background(ink.line))
                }
                Text(
                    "缩进", Modifier.clip(RoundedCornerShape(6.dp)).background(ink.well).clickable { replace(field.selection.min, field.selection.max, "    ") }
                        .padding(horizontal = 8.dp, vertical = 5.dp).semantics { contentDescription = "插入缩进" }, fontSize = 11.5.sp, color = ink.muted, maxLines = 1,
                )
                for (sym in CodeSymbols) {
                    Text(
                        sym, Modifier.clip(RoundedCornerShape(6.dp)).background(ink.well).clickable {
                            // 括号和引号成对地放，光标停在中间
                            when (sym) {
                                "(" -> replace(field.selection.min, field.selection.max, "()", 1)
                                "{" -> replace(field.selection.min, field.selection.max, "{}", 1)
                                "[" -> replace(field.selection.min, field.selection.max, "[]", 1)
                                "\"" -> replace(field.selection.min, field.selection.max, "\"\"", 1)
                                else -> replace(field.selection.min, field.selection.max, sym)
                            }
                        }.widthIn(min = 28.dp).padding(horizontal = 8.dp, vertical = 5.dp).semantics { contentDescription = "输入 $sym" },
                        fontSize = 12.5.sp, fontFamily = Mono, color = ink.text, maxLines = 1, textAlign = TextAlign.Center,
                    )
                }
            }
        }
        val inner: @Composable (Modifier) -> Unit = { m ->
            // propagateMinConstraints：输入框撑满整个框，点空白的地方也能开始输入
            Box(m.clip(shape).background(ink.well).border(HairWidth, ink.line, shape).padding(horizontal = 9.dp, vertical = 8.dp), propagateMinConstraints = true) {
                if (field.text.isEmpty() && hint.isNotEmpty()) Text(hint, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = Mono, color = ink.faint)
                BasicTextField(
                    field, onType, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; onFocus?.invoke(it.isFocused) }.semantics { contentDescription = label },
                    textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp, color = ink.text),
                    cursorBrush = SolidColor(ink.accent), visualTransformation = highlight,
                    // 不要自动纠错和首字母大写（会把 end 改成 and 之类）；键盘类型保持普通文字，这样骨骼名、字幕里的中文日文照样能输入
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Text),
                )
            }
        }
        if (height != null) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val h = maxHeight
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { inner(Modifier.fillMaxWidth().heightIn(min = h)) }
            }
        } else inner(Modifier.fillMaxWidth().heightIn(min = minHeight))
    }
}

/** 脚本的输出：最近的几十行。 */
@Composable
private fun LogView(lines: List<String>, max: Int = 40) {
    if (lines.isEmpty()) { Text("（还没有输出。脚本里的 print(...) 和报错会显示在这里）", Modifier.padding(top = 6.dp), fontSize = 11.sp, color = ink.faint); return }
    val shape = RoundedCornerShape(8.dp)
    SelectionContainer {
        Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(shape).background(ink.well).padding(horizontal = 9.dp, vertical = 7.dp).semantics { contentDescription = "脚本输出" }) {
            for (l in lines.takeLast(max)) {
                Text(l, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = Mono, color = if (l.startsWith("✕") || l.contains("出错") || l.contains("error", true)) ink.danger else ink.muted)
            }
        }
    }
}

private fun scriptStatus(s: ScriptUi): String = when {
    s.error.isNotEmpty() -> "出错了"
    s.running -> "运行中"
    s.enabled -> "到它的时间段才运行"
    else -> "已停"
}

/** 菜单里的「脚本」：场景里的脚本、新建 / 导入、示例。 */
@Composable
internal fun ScriptSection(controller: SceneController, state: UiState, go: (String) -> Unit, onPick: (Int, Int) -> Unit) {
    val scripts = state.play.scripts
    var naming by remember { mutableStateOf(false) }
    if (scripts.isEmpty()) PageNote("脚本可以自己写，也可以让 AI 直接写进来并运行：在菜单的「AI 操作」里打开 MCP 服务，把地址填到 AI 客户端，然后告诉它想要什么效果。")
    for (s in scripts) {
        ListRow(
            s.name, value = scriptStatus(s), valueColor = if (s.error.isNotEmpty()) ink.danger else if (s.running) ink.accent else ink.muted, chevron = true,
            end = { InkSwitch(s.enabled, label = "运行 ${s.name}") { controller.play.setScriptEnabled(s.id, it) } },
        ) { go("lua${s.id}") }
    }
    ListRow("新建脚本", icon = LumiIcons.Plus, value = "自己写，或者把 AI 写的贴进来") { naming = true }
    ListRow("导入 .lua 文件", icon = LumiIcons.Export) { onPick(Slot.SCRIPT, -1) }
    ListRow("控制台", icon = LumiIcons.Terminal, value = "试一行代码", chevron = true) { go("luaconsole") }
    ListRow("接口说明", icon = LumiIcons.Help, value = "脚本能做什么", chevron = true) { go("luaapi") }
    SectionTitle("示例")
    for (ex in ScriptLibrary.EXAMPLES) {
        val added = scripts.any { it.name == ex.name }
        Row(Modifier.fillMaxWidth().bottomLine().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ex.name, fontSize = 13.5.sp)
                Text(ex.about, fontSize = 11.sp, lineHeight = 15.sp, color = ink.muted)
            }
            PillButton(if (added) "已添加" else "添加", Modifier.padding(start = 8.dp).semantics { contentDescription = "添加示例 ${ex.name}" }, enabled = !added) { controller.play.addExample(ex.name) }
        }
    }
    PageNote("脚本用 Lua 写，每一帧都在跑：可以转骨骼、改表情、改材质的颜色和透明度、调模型的上下层、吹风、放粒子和拖尾、新建几何体、接管镜头、往屏幕上放字和按钮。" +
        "脚本关掉后，它改过的东西会自动复原。\n让 AI 来写：在「AI 操作」里打开 MCP，AI 可以直接往场景里写脚本、运行、看报错再改；" +
        "或者把「接口说明」复制给任何一个 AI，让它写好后贴到「新建脚本」里。")
    if (naming) {
        var name by remember { mutableStateOf("我的脚本") }
        LumiDialog(onDismissRequest = { naming = false }, title = { Text("新建脚本") },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("名字") }) },
            confirmButton = {
                TextButton({
                    naming = false
                    controller.play.createScript(name.ifBlank { "我的脚本" }, ScriptLibrary.TEMPLATE, enable = false) { id -> go("lua$id") }
                }) { Text("新建") }
            },
            dismissButton = { TextButton({ naming = false }) { Text("取消") } })
    }
}

/** 一个脚本：开关、给谁用、代码、输出。 */
@Composable
internal fun ScriptPage(controller: SceneController, state: UiState, s: ScriptUi, back: () -> Unit, onEditing: (Boolean) -> Unit = {}) {
    var code by remember(s.id) { mutableStateOf<String?>(null) }
    var saved by remember(s.id) { mutableStateOf("") }
    // 正在输入代码：代码框占满整页，别的先收起来（横屏时键盘一出来就没多少地方了）
    var editing by remember(s.id) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(s.id) { controller.play.readScript(s.id) { code = it; saved = it } }
    DisposableEffect(Unit) { onDispose { onEditing(false) } }
    BackHandler(enabled = editing) { focus.clearFocus() }
    val log by controller.play.scriptLog.collectAsState()
    val text = code
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val avail = maxHeight
        Column(Modifier.fillMaxSize()) {
            // 这一行固定在最上面：键盘弹出来时也按得到
            if (text != null) {
                Row(Modifier.padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (text != saved) "保存并运行" else "重新运行", color = ink.accent) { controller.play.updateScript(s.id, text); saved = text }
                    PillButton("还原", enabled = text != saved) { code = saved }
                    if (editing) PillButton("完成", Modifier.semantics { contentDescription = "结束输入" }) { focus.clearFocus() }
                    else PillButton("删除", color = ink.danger) { controller.play.deleteScript(s.id); back() }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                if (!editing) {
                    SwitchRow("运行", s.enabled, hint = scriptStatus(s)) { controller.play.setScriptEnabled(s.id, it) }
                    val chars = state.models.filter { it.skinned && !it.transient }
                    if (chars.size > 1) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).bottomLine(), verticalAlignment = Alignment.CenterVertically) {
                            Text("给", fontSize = 12.sp, color = ink.muted)
                            Words(listOf("选中的角色" to 0) + chars.map { it.name to it.id }, if (chars.any { it.id == s.target }) s.target else 0) { controller.play.setScriptTarget(s.id, it) }
                        }
                    }
                }
                if (s.error.isNotEmpty()) {
                    Text(
                        s.error, Modifier.padding(top = 6.dp).semantics { contentDescription = "脚本错误" }, fontSize = 11.5.sp, lineHeight = 16.sp, fontFamily = Mono, color = ink.danger,
                        maxLines = if (editing) 2 else 12, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (text == null) Text("正在读取…", Modifier.padding(top = 10.dp), fontSize = 12.sp, color = ink.muted)
                else {
                    // 正在输入时代码框占满整页（补全条在它里面最上面一行），平时固定一个高度
                    val h = if (editing) (avail - 62.dp - (if (s.error.isNotEmpty()) 40.dp else 0.dp)).coerceAtLeast(118.dp) else 270.dp
                    CodeField(text, { code = it }, "脚本代码", Modifier.padding(top = 6.dp).fillMaxWidth(), onFocus = { editing = it; onEditing(it) }, height = h)
                }
                if (!editing) {
                    Text(
                        (if (s.end < 0) "时间段：全程。" else "时间段：第 ${s.start}–${s.end} 帧。") + "在「轨道」里拖这个脚本的那一条可以改。",
                        Modifier.padding(top = 10.dp), fontSize = 11.5.sp, lineHeight = 16.sp, color = ink.muted,
                    )
                    SectionTitle("输出")
                    // 输出是所有脚本共用的一份；这里和控制台里都能清
                    if (log.isNotEmpty()) PillButton("清空输出", Modifier.padding(top = 4.dp)) { controller.play.clearLog() }
                    LogView(log)
                }
            }
        }
    }
}

/** 控制台：敲一段代码马上执行，看看接口是什么效果。 */
@Composable
internal fun ConsolePage(controller: SceneController) {
    var line by rememberSaveable { mutableStateOf("") }
    val log by controller.play.scriptLog.collectAsState()
    CodeField(line, { line = it }, "控制台输入", Modifier.padding(top = 10.dp).fillMaxWidth(), hint = "scene.main().opacity = 0.5", minHeight = 84.dp)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton("运行", color = ink.accent) { if (line.isNotBlank()) controller.play.eval(line) }
        PillButton("清空输出") { controller.play.clearLog() }
        PillButton("复原", Modifier.semantics { contentDescription = "复原控制台改动" }) { controller.play.resetConsole() }
    }
    PageNote("这里改的东西一直留着，直到点「复原」或者关掉应用（控制台里的改动是临时的，不会存进工程；想一直保留就写成脚本）。可以试试：\n" +
        "  return scene.main().name\n  scene.main():bone(\"頭\"):rotate(0, 30, 0)\n  physics.wind(120, 0, 0)\n  fx.emitter{ texture = \"star\", rate = 40, pos = {0, 12, 0} }")
    SectionTitle("输出")
    LogView(log, 60)
}

/** 接口说明：全文，可以整段复制给 AI。 */
@Composable
internal fun ApiPage(controller: SceneController) {
    val clipboard = LocalClipboardManager.current
    // 说明是现拼的：固定的接口 + 此刻场景里每个模型真实的骨骼 / 表情 / 材质 / 刚体。场景变了点「刷新」
    var doc by remember { mutableStateOf(ScriptLibrary.REFERENCE) }
    var live by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { controller.play.apiDocument { doc = it; live = true } }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton("复制全文", color = ink.accent) {
            // 复制的那一刻再取一次，保证贴给 AI 的是最新的场景
            controller.play.apiDocument {
                doc = it; live = true
                clipboard.setText(AnnotatedString("请按下面的接口和场景内容，为 Lumi MMD 写一个 Lua 脚本。骨骼、表情、材质的名字要照「当前场景」里列出的原样写。\n\n" + it))
                controller.toast("已复制（含当前场景的骨骼 / 表情 / 材质）。贴给 AI，并告诉它你想要什么效果")
            }
        }
        PillButton("刷新") { controller.play.apiDocument { doc = it; live = true; controller.toast("已按当前场景重新生成") } }
    }
    Text(if (live) "下面是按当前场景现生成的：接口 + 每个模型真实的骨骼、表情、材质、刚体。共 ${doc.length} 字" else "正在读取场景……",
        Modifier.padding(top = 8.dp), fontSize = 11.sp, color = ink.faint)
    SelectionContainer {
        Text(doc, Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), fontSize = 10.5.sp, lineHeight = 15.sp, fontFamily = Mono, softWrap = false)
    }
}

// ============================================================================ 物理

private class Tune(val key: String, val label: String, val range: ClosedFloatingPointRange<Float>, val hint: String = "", val unit: String = "×")

private val FEEL = listOf(
    Tune("mass", "重量", 0.2f..5f, "越重垂得越直，甩起来也更慢"),
    Tune("stiffness", "弹簧硬度", 0f..5f, "越硬越贴着原来的造型，越软越飘"),
    Tune("angularDamping", "摆动阻尼", 0f..5f, "越大摆动停得越快"),
    Tune("damping", "移动阻尼", 0f..5f, "越大越像在水里"),
    Tune("limits", "关节活动范围", 0f..2f, "裙子头发最多能摆多开"),
    Tune("colliderSize", "碰撞体大小", 0.5f..2f, "裙子穿进腿里时调大一点"),
)

private fun fmtTune(v: Float, unit: String): String = if (unit == "×") "×%.2f".format(v) else "%.0f".format(v) + unit

/**
 * 一组物理参数的滑杆。
 * @param values 当前值（参数名 → 值）
 * @param own true = 这是某个模型自己的倍数（没有"精度"那几项）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhysicsControls(values: Map<String, Float>, own: Boolean, set: (String, Float) -> Unit) {
    fun v(k: String, d: Float) = values[k] ?: d
    SectionTitle(if (own) "重力和风（在全局的基础上再加）" else "重力和风")
    LabeledSlider(if (own) "重力倍数" else "重力", v("gravity", 1f), -1f..3f, format = { fmtTune(it, "×") }, step = 0.05f) { set("gravity", (it * 20f).roundToInt() / 20f) }
    // 风：用"风力 + 风向"两个滑杆，比三个分量好懂
    val wx = v("windX", 0f); val wz = v("windZ", 0f)
    val force = hypot(wx, wz)
    var angle by remember { mutableFloatStateOf(if (force > 0.5f) ((Math.toDegrees(atan2(wx, wz).toDouble()).toFloat() + 360f) % 360f) else 0f) }
    LabeledSlider("风力", force, 0f..300f, format = { "%.0f".format(it) }, step = 5f) { f ->
        val a = Math.toRadians(angle.toDouble())
        set("windX", (f * sin(a)).toFloat()); set("windZ", (f * cos(a)).toFloat())
    }
    LabeledSlider("风向", angle, 0f..360f, format = { d -> "%.0f°".format(d) + when (((d + 45f) / 90f).toInt() % 4) { 0 -> " 迎面吹"; 1 -> " 从右往左"; 2 -> " 从背后吹"; else -> " 从左往右" } }) { d ->
        angle = d
        val a = Math.toRadians(d.toDouble())
        if (force > 0.01f) { set("windX", (force * sin(a)).toFloat()); set("windZ", (force * cos(a)).toFloat()) }
    }
    LabeledSlider("上升气流", v("windY", 0f), -150f..150f, format = { "%.0f".format(it) }, step = 5f) { set("windY", it.roundToInt().toFloat()) }
    if (!own) LabeledSlider("阵风", v("turbulence", 0.5f), 0f..1f, format = { "%.0f%%".format(it * 100f) }) { set("turbulence", it) }
    SectionTitle(if (own) "手感（倍数）" else "手感")
    for (t in FEEL) {
        LabeledSlider(t.label, v(t.key, 1f), t.range, format = { fmtTune(it, t.unit) }, step = 0.05f) { set(t.key, (it * 20f).roundToInt() / 20f) }
    }
    if (!own) {
        SectionTitle("精度")
        ChoiceRow("每秒模拟", listOf("60 步" to 60, "90" to 90, "120" to 120, "240" to 240), v("hz", 60f).roundToInt().let { h -> listOf(60, 90, 120, 240).minByOrNull { kotlin.math.abs(it - h) } ?: 60 }) { set("hz", it.toFloat()) }
        LabeledSlider("约束迭代次数", v("iterations", 10f), 2f..40f, format = { "%.0f 次".format(it) }, step = 1f) { set("iterations", it.roundToInt().toFloat()) }
        LabeledSlider("掉帧时最多补几步", v("maxSubSteps", 3f), 1f..10f, format = { "%.0f 步".format(it) }, step = 1f) { set("maxSubSteps", it.roundToInt().toFloat()) }
        SwitchRow("地面会挡住头发和裙子", v("ground", 1f) != 0f) { set("ground", if (it) 1f else 0f) }
    }
}

/** 菜单里的「物理」：对整个场景生效的那一份。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhysicsSection(controller: SceneController, state: UiState, go: (String) -> Unit) {
    val values = state.play.physics
    SwitchRow("物理演算", state.physics, hint = "头发、裙摆、饰品的摆动") { controller.setPhysics(it) }
    SectionTitle("一键预设")
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        fun apply(vararg kv: Pair<String, Float>) { controller.play.resetPhysics(0); for ((k, x) in kv) controller.play.setPhysics(k, x) }
        Chip("原样", false) { apply() }
        Chip("轻盈", false) { apply("mass" to 0.6f, "stiffness" to 0.7f, "angularDamping" to 0.6f) }
        Chip("厚重", false) { apply("mass" to 2.2f, "stiffness" to 1.3f, "angularDamping" to 1.6f) }
        Chip("大风", false) { apply("windZ" to 140f, "turbulence" to 0.8f) }
        Chip("失重", false) { apply("gravity" to 0.05f, "damping" to 1.6f) }
        Chip("水下", false) { apply("gravity" to 0.35f, "damping" to 4f, "angularDamping" to 3f) }
    }
    PhysicsControls(values, own = false) { k, x -> controller.play.setPhysics(k, x) }
    ListRow("让头发裙子重新落下", icon = LumiIcons.Reset) { controller.play.settlePhysics() }
    ListRow("全部恢复默认", icon = LumiIcons.Eraser) { controller.play.resetPhysics(0) }
    val withBodies = state.models.filter { it.hasPhysics && !it.transient }
    if (withBodies.isNotEmpty()) {
        SectionTitle("单独调某个模型")
        for (m in withBodies) ListRow(m.name, value = "${m.bodies} 个刚体" + if (m.ownPhysics.isNotEmpty()) " · 已单独调过" else "", chevron = true) { go("phys${m.id}") }
    }
    PageNote("这些是在模型作者设定的基础上乘的倍数，×1 就是原样。改完马上生效，拖着滑杆看头发和裙子的反应就行。" +
        "调得太极端（硬度很大、迭代很少）时裙子可能会抖，把「每秒模拟」和「约束迭代次数」调高会稳下来。")
}

/** 某一个模型自己的物理倍数。 */
@Composable
internal fun ModelPhysicsPage(controller: SceneController, m: ModelUi) {
    SwitchRow("这个模型的物理", m.physics) { controller.setModelPhysics(m.id, it) }
    PhysicsControls(m.ownPhysics, own = true) { k, x -> controller.play.setPhysics(k, x, m.id) }
    ListRow("恢复成跟全局一样", icon = LumiIcons.Eraser) { controller.play.resetPhysics(m.id) }
    PageNote("这里的值和「物理」页里全局的那一份相乘（风和横向重力是相加）后才是这个模型实际用的。")
}

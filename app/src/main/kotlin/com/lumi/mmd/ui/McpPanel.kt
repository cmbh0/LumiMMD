package com.lumi.mmd.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.mmd.core.McpUi
import com.lumi.mmd.core.SceneController

/**
 * MCP 面板：一个开关 + 要填到 AI 客户端里的地址。
 * 打开开关后应用在局域网里提供一个 MCP 服务，AI 可以通过它查看场景、摆姿势、打关键帧、建模、截图，
 * 也可以写 Lua 脚本、马上运行、看报错和输出再接着改。
 */
@Composable
fun McpPanel(controller: SceneController, mcp: McpUi) {
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { controller.refreshMcp() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 通知权限：开着 MCP 时通知栏里有一条常驻通知（前台服务），没有它切到后台以后系统会把应用冻住
        val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { controller.refreshMcp() }
        val context = LocalContext.current
        SwitchRow("开启 MCP 服务", mcp.running, hint = "打开后，这台手机上或者同一个 Wi-Fi 里的 AI 客户端可以操作这个应用。") { on ->
            controller.setMcpEnabled(on)
            if (on && Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                try { askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) } catch (_: Throwable) {}
            }
        }
        if (mcp.error.isNotEmpty()) Text(mcp.error, Modifier.padding(vertical = 4.dp), fontSize = 12.sp, color = ink.warn)
        if (!mcp.running) {
            SectionTitle("这是什么")
            Text("MCP 是让 AI 助手调用外部工具的通用协议。打开开关后把地址填进支持 MCP 的 AI 客户端（Claude、Cursor、Cherry Studio、Cline 等），" +
                "就可以直接对它说“让她挥挥手再鞠个躬”“做一张小桌子放在她旁边”，AI 会自己在这里打关键帧、建模，并截图检查效果。", fontSize = 12.sp, lineHeight = 18.sp, color = TextDim)
            AiCanDo()
            return@Column
        }
        SectionTitle("把这个地址填到 AI 客户端")
        for (u in mcp.urls) {
            val local = u.contains("://127.0.0.1")
            if (local && mcp.urls.size == 1) UrlRow("Streamable HTTP · 本机（AI 客户端装在这台手机上）", u) { clipboard.setText(AnnotatedString(u)); controller.toast("已复制地址") }
            else if (local) UrlRow("AI 客户端也装在这台手机上时用这个（本机）", u) { clipboard.setText(AnnotatedString(u)); controller.toast("已复制地址") }
            else UrlRow("Streamable HTTP（推荐）", u) { clipboard.setText(AnnotatedString(u)); controller.toast("已复制地址") }
        }
        // 后台保活的状态：慢不慢主要看这个
        if (!mcp.keepAlive) Text("后台保活没有开起来：切到别的应用以后系统可能暂停本应用，AI 客户端会连得很慢。关掉再打开一次开关试试。",
            Modifier.padding(top = 4.dp), fontSize = 11.5.sp, lineHeight = 16.sp, color = ink.warn)
        for (u in mcp.legacyUrls.take(1)) UrlRow("SSE（旧式客户端用这个）", u) { clipboard.setText(AnnotatedString(u)); controller.toast("已复制地址") }
        val first = mcp.urls.firstOrNull() ?: ""
        // 其它接法：地址都是从第一个地址换个开头得来的，密钥相同
        if (first.contains("/mcp")) {
            val ws = first.replaceFirst("http://", "ws://").replaceFirst("/mcp", "/ws")
            val api = first.replaceFirst("/mcp", "/api") + "/tools"
            UrlRow("WebSocket（一帧一条 JSON-RPC）", ws) { clipboard.setText(AnnotatedString(ws)); controller.toast("已复制地址") }
            UrlRow("普通 HTTP 接口（不支持 MCP 的程序：GET 列出工具，POST …/tools/工具名 调用；…/api/密钥/openapi.json 是 OpenAPI 描述）", api) { clipboard.setText(AnnotatedString(api)); controller.toast("已复制地址") }
        }
        val config = "{\n  \"mcpServers\": {\n    \"lumi-mmd\": {\n      \"url\": \"$first\"\n    }\n  }\n}"
        SectionTitle("或者复制整段配置")
        CopyBlock(config, "复制 MCP 配置") { clipboard.setText(AnnotatedString(config)); controller.toast("已复制配置") }
        // 有的客户端不让把密钥写在地址里，要单独填在请求头里：两种给法服务都认
        val key = first.substringAfterLast('/', "")
        val bare = first.substringBeforeLast('/')
        if (key.isNotEmpty() && bare.endsWith("/mcp")) {
            val headerConfig = "{\n  \"mcpServers\": {\n    \"lumi-mmd\": {\n      \"url\": \"$bare\",\n      \"headers\": { \"Authorization\": \"Bearer $key\" }\n    }\n  }\n}"
            SectionTitle("密钥放在请求头里的写法")
            CopyBlock(headerConfig, "复制请求头方式的 MCP 配置") { clipboard.setText(AnnotatedString(headerConfig)); controller.toast("已复制配置") }
        }
        SectionTitle("怎么用")
        Text("1. AI 客户端在电脑上：手机和电脑连同一个 Wi-Fi（或手机开热点给电脑），用带 IP 的那个地址。AI 客户端就在这台手机上：用 127.0.0.1 那个地址。\n" +
            "2. 在 AI 客户端的 MCP 设置里添加一个服务器，类型选 Streamable HTTP（有的客户端叫 HTTP；只有 SSE 可选的就用 SSE 那个地址），地址填上面那一串。\n" +
            "3. 可以切到别的应用：MCP 开着时通知栏里有一条「MCP 服务运行中」，应用在后台照样马上应答，AI 也能截图看效果。" +
            "（在后台时画面不会自己往前走，AI 会用 advance 工具推进时间；想边聊边看就用分屏 / 小窗。）\n" +
            "4. 对 AI 说：“看看场景里有什么，然后让角色挥挥手。”", fontSize = 12.sp, lineHeight = 18.sp, color = ink.text)
        if (mcp.requests > 0) Text("已收到 ${mcp.requests} 条请求，最近一条：${mcp.lastRequest}，应用处理用了 ${"%.1f".format(mcp.lastRequestMs)} 毫秒。" +
            "（这个数很小而客户端那边却等了很久 = 请求在路上被系统拦住了，看下面的「允许后台运行」。）",
            Modifier.padding(top = 8.dp).semantics { contentDescription = "MCP 请求统计" }, fontSize = 11.5.sp, lineHeight = 16.sp, color = TextDim)
        if (!mcp.unrestricted) {
            Text("系统对本应用开着「电池优化」。大多数手机上有通知栏那条常驻通知就够了；如果切到 AI 客户端以后还是要等很久才连上，点下面这个把本应用设成不限制后台。",
                Modifier.padding(top = 8.dp), fontSize = 11.5.sp, lineHeight = 16.sp, color = TextDim)
            IconTextBtn(LumiIcons.Globe, "允许后台运行（不限制电池）", color = ink.accent) { controller.openBackgroundSettings() }
        }
        Text(if (mcp.calls > 0) "已处理 ${mcp.calls} 次调用，最近一次：${mcp.lastCall}" else "还没有收到调用。", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = if (mcp.calls > 0) ink.accent else TextDim)
        AiCanDo()
        Row(Modifier.padding(top = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconTextBtn(LumiIcons.Reset, "换一个密钥", color = ink.muted) { controller.resetMcpToken() }
            IconTextBtn(LumiIcons.Globe, "刷新地址", color = ink.muted) { controller.refreshMcp() }
        }
        Text("地址最后一段是访问密钥：只有知道完整地址的人才能连上来。AI 只能操作场景和资源库里的文件，碰不到手机上的其它东西。\n" +
            "服务按 MCP 规范实现：新的无状态协议（2026-07-28）和以前先握手的各版（2025-11-25 到 2024-11-05）都支持，客户端用哪一种都能连。",
            fontSize = 11.sp, lineHeight = 15.sp, color = TextDim)
    }
}

/** AI 连上之后能做什么：重点说清楚它可以写脚本并运行。 */
@Composable
private fun AiCanDo() {
    SectionTitle("AI 能做什么")
    Text("· 写 Lua 脚本并运行：AI 把脚本写进应用、马上跑起来，看到报错（带行号）和输出后自己改，直到效果对为止。写好的脚本留在「脚本与特效」里，存进工程，下次打开还在。\n" +
        "· 临时执行一段 Lua：查一下骨骼名、试一个效果，不留下脚本。\n" +
        "· 看场景、截图检查、摆姿势、打关键帧、导出 VMD。\n" +
        "· 在轨道上排动作和音乐：接几段、裁剪、分割、加过渡。\n" +
        "· 建模、调物理、用摇杆模式带着角色走。\n" +
        "· 改画面：三渲二、描边、调色、背景；改 / 删 / 加回地板；给模型加碰撞。", Modifier.semantics { contentDescription = "AI 能做什么" }, fontSize = 12.sp, lineHeight = 18.sp, color = ink.text)
    Text("可以这样说：\n“写个脚本，让她的头一直跟着镜头转，眨眼自然一点。”\n“加一个樱花飘落的特效，花瓣落到地上就消失。”\n“这个脚本第 12 行报错了，帮我修一下。”",
        Modifier.padding(top = 6.dp), fontSize = 12.sp, lineHeight = 18.sp, color = TextDim)
}

/** 一段可以点一下复制的配置。 */
@Composable
private fun CopyBlock(text: String, description: String, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ink.well).clickable(onClick = onCopy)
        .padding(10.dp).semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 10.5.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace, color = ink.text)
        Icon(LumiIcons.Copy, null, Modifier.size(18.dp), tint = Accent)
    }
}

@Composable
private fun UrlRow(label: String, url: String, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(ink.well).clickable(onClick = onCopy).padding(horizontal = 10.dp, vertical = 8.dp)
        .semantics(mergeDescendants = true) { contentDescription = "复制地址 $url" }) {
        Text(label, fontSize = 10.5.sp, color = TextDim)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(url, Modifier.weight(1f), fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = ink.accent)
            Icon(LumiIcons.Copy, null, Modifier.size(18.dp), tint = Accent)
        }
    }
}

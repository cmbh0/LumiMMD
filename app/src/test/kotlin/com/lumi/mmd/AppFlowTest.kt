package com.lumi.mmd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.window.DialogWindowProvider
import org.robolectric.Shadows.shadowOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import com.lumi.mmd.core.Lanes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ApplicationProvider
import com.lumi.mmd.core.ImportResult
import com.lumi.mmd.core.LibraryItem
import com.lumi.mmd.core.Modes
import com.lumi.mmd.core.SceneController
import com.lumi.mmd.engine.scene.Editor
import com.lumi.mmd.engine.scene.ModelInstance
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.drawText
import com.lumi.mmd.ui.drawClipRuler
import com.lumi.mmd.ui.drawLabel
import com.lumi.mmd.ui.LumiTheme
import com.lumi.mmd.ui.MainScreen
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/**
 * 在 JVM 上（Robolectric）把整个 App 走一遍（横屏）：新手教程 → 导入 → 加载 → 摆骨骼打关键帧 → 表情 / 镜头关键帧 →
 * 复制粘贴 → 播放 → 导出 VMD → 建模（改模型 + 从零新建）→ MCP（真的走 HTTP）→ 存盘恢复。
 *
 * 这里没有 GPU：渲染视图换成一个普通 View，[SceneController.drawFrame] 里的渲染器因为没有 GL 上下文会直接跳过，
 * 但模型解析、动画、物理、编辑器、状态发布和整套 Compose 界面都是真的在跑。
 * 「假 GL 线程」是一个单线程执行器，保持和真机上一样的线程约束（场景只在这一个线程上访问）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land-420dpi")
class AppFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val gl = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-gl") }
    private val controllers = ArrayList<SceneController>()
    private lateinit var controller: SceneController
    private val shotsDir: File? = System.getProperty("lumi.shots").orEmpty().ifEmpty { null }?.let { File(it).also { d -> d.mkdirs() } }
    private val fontFile: File? = System.getProperty("lumi.font").orEmpty().ifEmpty { null }?.let { File(it) }?.takeIf { it.isFile }
    private var backdrop: View? = null
    /** 竖屏的测试把它设成 true（渲染尺寸跟着换）。 */
    private var tall = false

    @After fun tearDown() {
        for (c in controllers) c.release()
        gl.shutdownNow()
    }

    // ------------------------------------------------------------------ 工具

    private fun newController(): SceneController {
        val c = SceneController(context)
        // 测试结束时界面还会往渲染线程发几条收尾命令，那时执行器可能已经关了：忽略即可
        c.glPost = { r -> try { gl.execute(r) } catch (_: java.util.concurrent.RejectedExecutionException) {} }
        c.glRequestRender = {}
        c.glSetContinuous = {}
        onGl { if (tall) c.onResize(1080, 2340) else c.onResize(2340, 1080) }
        controllers.add(c)
        return c
    }

    private fun <T> onGl(block: () -> T): T = gl.submit(Callable { block() }).get(60, TimeUnit.SECONDS)

    /** 跑 n 帧（动画 / 物理 / 状态发布）。 */
    private fun frames(n: Int = 1, c: SceneController = controller, sleepMs: Long = 0) {
        repeat(n) { onGl { c.drawFrame() }; if (sleepMs > 0) Thread.sleep(sleepMs) }
    }

    /** 等后台加载结束，再把界面跑到空闲。 */
    private fun settle(c: SceneController = controller) {
        await("加载完成", c) { c.loading.value.isEmpty() }
        frames(2, c)
        rule.waitForIdle()
    }

    private fun await(what: String, c: SceneController = controller, timeoutMs: Long = 120_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            frames(1, c)
            if (cond()) return
            check(System.currentTimeMillis() < end) { "等待超时：$what（${c.loading.value}）" }
            Thread.sleep(15)
        }
    }

    @Composable
    private fun TestRoot(content: @Composable () -> Unit) {
        LumiTheme {
            val file = fontFile
            if (file == null) content() else {
                // 测试机上的默认字体不一定带中日文字形；指定了字体文件就整套排版都换成它
                val family = FontFamily(Font(file))
                val t = MaterialTheme.typography
                fun TextStyle.f() = copy(fontFamily = family)
                MaterialTheme(
                    colorScheme = MaterialTheme.colorScheme,
                    typography = Typography(
                        t.displayLarge.f(), t.displayMedium.f(), t.displaySmall.f(), t.headlineLarge.f(), t.headlineMedium.f(), t.headlineSmall.f(),
                        t.titleLarge.f(), t.titleMedium.f(), t.titleSmall.f(), t.bodyLarge.f(), t.bodyMedium.f(), t.bodySmall.f(),
                        t.labelLarge.f(), t.labelMedium.f(), t.labelSmall.f()),
                    content = content,
                )
            }
        }
    }

    private fun start(backdropPng: File? = null) {
        controller = newController()
        val view = if (backdropPng != null && backdropPng.isFile) {
            ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(BitmapFactory.decodeFile(backdropPng.path)) }
        } else View(context).apply { setBackgroundColor(0xFFEDE8FF.toInt()) }
        backdrop = view
        rule.setContent { TestRoot { MainScreen(controller, view) } }
        settle()
    }

    /** 用 Robolectric 的硬件渲染后端把一个窗口画成位图。 */
    private fun capture(window: Window): Bitmap {
        val decor = window.decorView
        val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        PixelCopy.request(window, bmp, { latch.countDown() }, Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        check(latch.await(10, TimeUnit.SECONDS)) { "截图超时" }
        return bmp
    }

    private fun dialogWindow(): Window? {
        val nodes = rule.onAllNodes(isDialog()).fetchSemanticsNodes()
        if (nodes.isEmpty()) return null
        var v: Any? = (nodes[0].root as ViewRootForTest).view
        while (v is View) { if (v is DialogWindowProvider) return v.window; v = v.parent }
        return null
    }

    /** 把当前界面存成图片（只有设置了 LUMI_TEST_SHOTS 才会存）。弹窗是单独的窗口，叠到主界面上再存。 */
    private fun shot(name: String) {
        val dir = shotsDir ?: return
        rule.waitForIdle()
        val main = capture(rule.activity.window)
        var result = main
        val dialog = dialogWindow()
        if (dialog != null) {
            val top = capture(dialog)
            result = main.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(result)
            canvas.drawColor(0x99000000.toInt())
            canvas.drawBitmap(top, (main.width - top.width) / 2f, (main.height - top.height) / 2f, null)
        }
        File(dir, "$name.png").outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * 界面动作之后：先让 Compose 处理完（重组、LaunchedEffect 里发给控制器的命令），
     * 再跑两帧「渲染」（命令在假 GL 线程上执行、状态发布出来），最后让界面收到新状态。
     */
    private fun ui() {
        rule.waitForIdle()
        frames(2)
        rule.waitForIdle()
    }

    /** 这个控件是不是完整地露在外面（没有被可滚动的容器裁掉一部分）。 */
    private fun wordFullyShown(label: String): Boolean {
        rule.waitForIdle()
        val n = rule.onAllNodes(hasContentDescription(label)).onFirst().fetchSemanticsNode()
        val b = n.boundsInRoot
        return b.height > 0f && b.width >= n.size.width - 1f
    }

    /** 这个控件整个都在屏幕里（上下左右都没有被裁掉）。 */
    private fun fullyOnScreen(label: String): Boolean {
        rule.waitForIdle()
        val n = rule.onAllNodes(hasContentDescription(label)).onFirst().fetchSemanticsNode()
        val b = n.boundsInRoot
        return b.width >= n.size.width - 1f && b.height >= n.size.height - 1f && n.size.width > 0 && n.size.height > 0
    }

    /** 等某段文字出现在界面上（后台线程扫出来的列表之类）。lazy = 它在懒加载的长列表里，可能还没滚到。 */
    private fun waitForText(text: String, lazy: Boolean = false, timeoutMs: Long = 10_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            ui()
            if (rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            if (lazy) try {
                rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text)); rule.waitForIdle(); return
            } catch (_: Throwable) {}
            assertTrue("等不到「$text」出现", System.currentTimeMillis() < end)
            Thread.sleep(40)
        }
    }

    /** 点一个只有图标的按钮（按它的说明文字找）。 */
    private fun tapIcon(label: String) {
        val node = rule.onAllNodes(hasContentDescription(label)).onFirst()
        try { node.performScrollTo(); rule.waitForIdle() } catch (_: AssertionError) {}
        node.performClick(); ui()
    }
    /** 点一个带文字的按钮；它在可滚动的面板里时先滚到看得见的地方。 */
    private fun tapText(text: String) {
        val node = rule.onAllNodesWithText(text).onFirst()
        try { node.performScrollTo(); rule.waitForIdle() } catch (_: AssertionError) {}
        node.performClick(); ui()
    }
    /**
     * 切换工具。「浏览」= 点「完成」回到干净的画面；其它工具在「编辑」里（还没进编辑就先点右上角的铅笔）。
     * 面板换了内容之后还会向控制器要一次明细数据，所以多跑一轮。
     */
    private fun mode(name: String) {
        if (name == "浏览") {
            if (rule.onAllNodes(hasContentDescription("完成")).fetchSemanticsNodes().isNotEmpty()) tapIcon("完成")
        } else {
            if (rule.onAllNodes(hasContentDescription("模式 $name")).fetchSemanticsNodes().isEmpty()) tapIcon("编辑")
            tapIcon("模式 $name")
        }
        ui()
    }
    /** 资源库里点一个文件（列表是懒加载的，先滚到那一项）。 */
    private fun libraryItem(name: String) {
        rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(name))
        rule.onNodeWithText(name).performClick()
        rule.waitForIdle()
    }
    /** 打开菜单卡片，沿着 path 一层层点进去。 */
    private fun menu(vararg path: String) {
        if (rule.onAllNodes(hasContentDescription("菜单卡片")).fetchSemanticsNodes().isNotEmpty()) tapIcon("关闭")
        tapIcon("菜单")
        for (item in path) {
            // 卡片是最后画上去的：同样的字（比如画面上的场景名）在前面，卡片里的那一行在最后
            val node = rule.onAllNodesWithText(item).onLast()
            try { node.performScrollTo(); rule.waitForIdle() } catch (_: AssertionError) {}
            node.performClick(); ui()
        }
    }
    private fun closeSheet() { tapIcon("关闭") }

    /** 换一张背景图（真实素材测试里用无头渲染出来的画面当背景，截图才像真机）。 */
    private fun backdrop(name: String) {
        val dir = System.getProperty("lumi.assets").orEmpty().ifEmpty { null }?.let { File(it) } ?: return
        val f = File(dir, "backdrop_$name.png")
        val v = backdrop as? ImageView ?: return
        if (f.isFile) rule.runOnUiThread { v.setImageBitmap(BitmapFactory.decodeFile(f.path)) }
    }

    private fun http(url: String, body: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json, text/event-stream")
        c.connectTimeout = 5000; c.readTimeout = 60000
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes()) } ?: ""
        return "$code $text"
    }

    private var rpcId = 0
    /** 调一个 MCP 工具，返回结果里的文本（一段 JSON）。 */
    private fun mcpCall(url: String, tool: String, args: String = "{}"): String {
        val r = http(url, """{"jsonrpc":"2.0","id":${++rpcId},"method":"tools/call","params":{"name":"$tool","arguments":$args}}""")
        assertTrue("MCP 调用 $tool 失败：$r", r.startsWith("200 "))
        val root = com.lumi.mmd.engine.util.Json.parse(r.substring(4)) as Map<*, *>
        val result = root["result"] as? Map<*, *> ?: error("没有 result：$r")
        val text = ((result["content"] as List<*>)[0] as Map<*, *>)["text"] as String
        assertTrue("工具 $tool 报错：$text", result["isError"] != true)
        return text
    }

    private val isSlider = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun importZip(): ImportResult {
        val zip = File(context.cacheDir, "テスト素材.zip")
        TestAssets.writeSjisZip(zip)
        val latch = CountDownLatch(1)
        var result: ImportResult? = null
        controller.importUris(listOf(Uri.fromFile(zip)), false) { result = it; latch.countDown() }
        assertTrue("导入超时", latch.await(60, TimeUnit.SECONDS))
        settle()
        return result!!
    }

    private fun model(c: SceneController = controller): ModelInstance = onGl { c.scene.models.first() }

    // ------------------------------------------------------------------ 主流程

    /** 失败时把当时的界面存下来，方便排查。 */
    private fun guarded(name: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            try { shot("FAIL_$name") } catch (_: Throwable) {}
            throw e
        }
    }

    /** 脚本的输出是攒着每 0.2 秒推给界面一次的：等到某段文字出现。 */
    private fun awaitLog(text: String) {
        val end = System.currentTimeMillis() + 5_000
        while (controller.play.scriptLog.value.none { it.contains(text) }) {
            assertTrue("脚本输出里应当出现「$text」，现在是 ${controller.play.scriptLog.value}", System.currentTimeMillis() < end)
            frames(1); Thread.sleep(15)
        }
        rule.waitForIdle()
    }

    private fun skipTutorial() { controller.showTutorial(false); rule.waitForIdle() }

    @Test fun tutorialOnFirstLaunch() = guarded("tutorial") {
        start()
        // 第一次打开：显示教程，一页页翻到底
        assertTrue(controller.tutorial.value)
        rule.onNodeWithText("欢迎使用 Lumi MMD").assertExists()
        shot("00_tutorial_1")
        val titles = listOf("添加文件", "看：转、缩放、平移", "播放：画面跟着音乐走", "编辑：箭头和圆环", "做动画：摆姿势 → 打关键帧", "轨道：像剪视频那样排",
            "脚本与特效：Lua", "上下层和透明", "物理：头发和裙子的手感", "操控模式：摇杆带着角色跑", "建模：点、线、面", "让 AI 来帮你做", "几个小提示")
        for ((i, t) in titles.withIndex()) {
            rule.onNodeWithText("下一步").performClick()
            rule.waitForIdle()
            rule.onNodeWithText(t).assertExists()
            rule.onNodeWithText("${i + 2} / ${titles.size + 1}").assertExists()
            if (i in listOf(0, 2, 5, 6, 7, 8, 9)) shot("00_tutorial_${i + 2}")
        }
        rule.onNodeWithText("上一步").performClick(); rule.waitForIdle()
        rule.onNodeWithText("让 AI 来帮你做").assertExists()
        // 目录：点页码，直接跳到某一页
        tapIcon("教程目录")
        shot("00_tutorial_toc")
        tapText("操控模式：摇杆带着角色跑")
        rule.onNodeWithText("11 / 14").assertExists()
        rule.onNodeWithText("左边是摇杆：往哪推，角色就朝镜头的那个方向走；推过一半是跑。").assertExists()
        repeat(3) { rule.onNodeWithText("下一步").performClick(); rule.waitForIdle() }
        rule.onNodeWithText("开始使用").performClick()
        rule.waitForIdle()
        assertTrue(!controller.tutorial.value)
        rule.onNodeWithText("场景还是空的").assertExists()
        // 第二次打开不再显示；可以从菜单里再看
        val c2 = newController()
        assertTrue("教程只在第一次打开时自动显示", !c2.tutorial.value)
        menu("设置", "新手教程")
        rule.onNodeWithText("欢迎使用 Lumi MMD").assertExists()
        rule.onNodeWithText("跳过").performClick()
        rule.waitForIdle()
        assertTrue(!controller.tutorial.value)
    }

    @Test fun animateModelAndPersist() = guarded("flow") {
        start()
        skipTutorial()
        rule.onNodeWithText("场景还是空的").assertExists()
        shot("01_empty")

        // ---- 导入：Shift-JIS 文件名的 zip
        val imported = importZip()
        assertEquals("zip 里的日文文件名应当被正确解码", setOf("人形.gltf", "おじぎ.vmd", "ポーズ.vpd", "stage.obj"), imported.items.map { it.name }.toSet())

        // ---- 菜单 → 资源库 → 点模型 → 选「角色」
        menu("设置", "资源库")
        waitForText("人形.gltf", lazy = true)        // 文件列表是在后台线程扫出来的
        shot("02_library")
        libraryItem("人形.gltf")
        rule.onNodeWithText("角色（可套用动作、物理、摆姿势）").performClick()
        settle()
        assertTrue("加载之后菜单卡片应当自己收起来", rule.onAllNodes(hasContentDescription("菜单卡片")).fetchSemanticsNodes().isEmpty())
        var st = controller.state.value
        assertEquals(1, st.models.size)
        assertTrue("人偶应当带骨骼", st.models[0].skinned)
        rule.onNodeWithText("场景还是空的").assertDoesNotExist()
        var m = model()
        val spine = m.skeleton!!.bones.indexOfFirst { it.name == TestAssets.BONE_SPINE }
        val head = m.skeleton!!.bones.indexOfFirst { it.name == "head" }
        onGl { m.setMotion(null) }      // 去掉模型自带的动画，从零开始做
        frames(2)

        // ---- 骨骼模式：从右边的列表选 spine
        mode("骨骼")
        assertEquals(Modes.BONE, controller.state.value.mode)
        // 时间轴平时是收起来的，要用的时候点右上角的图标打开
        assertTrue(controller.timeline.value.tracks.isEmpty())
        tapIcon("时间轴")
        rule.onNodeWithText(TestAssets.BONE_SPINE).performClick()
        ui()
        st = controller.state.value
        assertEquals(TestAssets.BONE_SPINE, st.selectedBoneName)
        assertEquals("选中骨骼后工具应当是「旋转」", Editor.TOOL_ROTATE, st.tool)
        assertTrue("默认是局部轴", st.localAxes)
        tapIcon("局部轴（点击切换到全局）")
        assertTrue(!controller.state.value.localAxes)
        shot("03_bone_mode")

        // 第 0 帧：直接登记初始姿势
        tapIcon("登记关键帧")
        assertTrue("登记后这一帧应当有关键帧", controller.playback.value.boneKeyed)
        // 第 20 帧：用 Z 轴拖动块把 spine 转一个角度（和手指在屏幕右下角的蓝色块上拖一样）
        controller.seekFrame(20); frames(2)
        assertEquals(20f, controller.playback.value.frame, 0f)
        controller.padStart(); controller.padDrag(Editor.TOOL_ROTATE, 2, 200f); controller.padEnd()
        frames(2); rule.waitForIdle()
        assertEquals("有一根骨骼摆了姿势还没登记", 1, controller.state.value.pending)
        val rest = FloatArray(3); val bent = FloatArray(3)
        onGl { m.boneWorldPosition(head, bent, 0) }
        assertTrue("spine 转了之后头应当偏离中轴线：${bent.toList()}", abs(bent[0]) > 0.3f)
        tapIcon("登记关键帧")
        assertEquals(0, controller.state.value.pending)
        assertEquals(20, controller.playback.value.maxFrame)
        assertTrue(onGl { m.motionOwned })
        // 时间轴上出现这条轨道
        var tl = controller.timeline.value
        assertEquals(listOf(TestAssets.BONE_SPINE), tl.tracks.map { it.name })
        assertEquals(listOf(0, 20), tl.tracks[0].frames.toList())

        // 撤销登记：关键帧收回去，摆好的姿势还在（回到"摆了还没登记"）；然后重新登记
        tapIcon("撤销")
        assertEquals(listOf(0), controller.timeline.value.tracks[0].frames.toList())
        assertEquals("撤销登记后姿势应当还是没登记的状态", 1, controller.state.value.pending)
        val again = FloatArray(3)
        onGl { m.boneWorldPosition(head, again, 0) }
        assertEquals("撤销登记后画面上的姿势不变", bent[0], again[0], 1e-4f)
        tapIcon("登记关键帧")
        assertEquals(listOf(0, 20), controller.timeline.value.tracks[0].frames.toList())
        // 误删关键帧 → 撤销：关键帧回来，姿势也回来
        tapIcon("删除关键帧")
        assertEquals(listOf(0), controller.timeline.value.tracks[0].frames.toList())
        onGl { m.boneWorldPosition(head, again, 0) }
        assertTrue("删掉第 20 帧的关键帧后应当回到直立：${again[0]}", abs(again[0]) < 0.01f)
        tapIcon("撤销")
        assertEquals(listOf(0, 20), controller.timeline.value.tracks[0].frames.toList())
        onGl { m.boneWorldPosition(head, again, 0) }
        assertEquals("撤销删除后姿势回来", bent[0], again[0], 1e-4f)
        assertEquals(0, controller.state.value.pending)
        shot("04_two_keys")

        // 回到第 0 帧是直的，第 10 帧在两者之间
        controller.seekFrame(0); frames(2)
        onGl { m.boneWorldPosition(head, rest, 0) }
        assertTrue("第 0 帧头在中轴线上：${rest.toList()}", abs(rest[0]) < 0.01f)
        controller.seekFrame(10); frames(2)
        val mid = FloatArray(3)
        onGl { m.boneWorldPosition(head, mid, 0) }
        assertTrue("第 10 帧应当在中间：${mid[0]} vs ${bent[0]}", abs(mid[0]) > abs(bent[0]) * 0.2f && abs(mid[0]) < abs(bent[0]) * 0.8f)

        // ---- 复制第 0 帧 → 粘贴到第 40 帧（回到直立）
        controller.seekFrame(0); frames(2)
        tapIcon("复制这一帧")
        assertEquals(1, controller.state.value.clipboard)
        controller.seekFrame(40); frames(2)
        tapIcon("粘贴到这一帧")
        tl = controller.timeline.value
        assertEquals(listOf(0, 20, 40), tl.tracks[0].frames.toList())
        // 上一个 / 下一个关键帧
        tapIcon("上一个关键帧")
        assertEquals(20f, controller.playback.value.frame, 0f)
        tapIcon("上一帧")
        assertEquals(19f, controller.playback.value.frame, 0f)
        tapIcon("下一个关键帧")
        assertEquals(20f, controller.playback.value.frame, 0f)
        // 补间曲线
        tapText("补间")
        rule.onNodeWithText("缓入缓出").performClick(); ui()
        val curve = IntArray(4)
        assertTrue(onGl { com.lumi.mmd.engine.scene.KeyOps.getBoneCurve(m, spine, 20, 3, curve) })
        assertEquals(listOf(64, 0, 64, 127), curve.toList())

        // ---- 表情：滑杆拉到 0.8，点这一行的 ◆
        mode("表情")
        assertEquals(Modes.MORPH, controller.state.value.mode)
        rule.onNodeWithText(TestAssets.MORPH_BIG_HEAD).assertExists()
        rule.onNode(hasContentDescription("表情 ${TestAssets.MORPH_BIG_HEAD}")).performSemanticsAction(SemanticsActions.SetProgress) { it(0.8f) }
        ui()
        assertEquals(0.8f, onGl { m.morphs.userWeights[0] }, 1e-3f)
        assertEquals(1, controller.state.value.pending)
        shot("05_morph_mode")
        tapIcon("登记表情 ${TestAssets.MORPH_BIG_HEAD}")
        assertEquals(0f, onGl { m.morphs.userWeights[0] }, 0f)
        assertEquals(listOf(20), onGl { com.lumi.mmd.engine.scene.KeyOps.morphKeyFrames(m, 0).toList() })
        assertEquals(0.8f, onGl { com.lumi.mmd.engine.scene.KeyOps.morphWeight(m, 0, 20) }, 1e-3f)
        // 自动关键帧：换一帧直接拖滑杆就是关键帧
        tapIcon("自动关键帧：关")
        assertTrue(controller.state.value.autoKey)
        controller.seekFrame(40); frames(2)
        rule.onNode(hasContentDescription("表情 ${TestAssets.MORPH_BIG_HEAD}")).performSemanticsAction(SemanticsActions.SetProgress) { it(0.2f) }
        ui()
        assertEquals(listOf(20, 40), onGl { com.lumi.mmd.engine.scene.KeyOps.morphKeyFrames(m, 0).toList() })
        tapIcon("自动关键帧：开")

        // ---- 镜头：两个镜头关键帧
        mode("镜头")
        controller.seekFrame(0); frames(2)
        tapIcon("登记关键帧")
        controller.seekFrame(40); frames(2)
        onGl { controller.dragStart(1200f, 500f); controller.dragMove(240f, 0f, 1440f, 500f); controller.dragEnd() }   // 暂停时可以手动转镜头
        frames(2)
        val rotY40 = onGl { controller.scene.camera.rotY }
        assertTrue("暂停时应当能手动转动镜头", abs(rotY40) > 0.1f)
        tapText("登记镜头")
        st = controller.state.value
        assertEquals(2, st.cameraKeys)
        assertTrue(st.cameraDriven)
        controller.seekFrame(20); frames(2)
        assertEquals("第 20 帧的镜头在两个关键帧中间", rotY40 * 0.5f, onGl { controller.scene.camera.rotY }, abs(rotY40) * 0.02f + 1e-4f)
        shot("06_camera_mode")

        // ---- 播放
        controller.seekFrame(0); frames(1)
        tapIcon("播放")
        frames(8, sleepMs = 20)
        assertTrue(controller.playback.value.playing)
        assertTrue("播放后帧数应当前进", controller.playback.value.frame > 0.5f)
        rule.waitForIdle()
        tapIcon("暂停")
        assertTrue(!controller.playback.value.playing)

        // ---- 导出 VMD，再读回来检查
        menu("导出", "导出动作 VMD")
        closeSheet()
        val exported = File(controller.library.root, "自制动作").listFiles()!!.filter { it.name.endsWith(".vmd") && !it.name.startsWith("工程_") }
        assertEquals(1, exported.size)
        val back = com.lumi.mmd.engine.format.VmdReader.read(exported[0].readBytes())
        assertEquals(listOf(0, 20, 40), back.boneTracks[TestAssets.BONE_SPINE]!!.let { it.frames.copyOf(it.count).toList() })
        assertEquals(listOf(20, 40), back.morphTracks[TestAssets.MORPH_BIG_HEAD]!!.let { it.frames.copyOf(it.count).toList() })

        // ---- 存盘，再用一个全新的控制器恢复：关键帧动画、镜头动画都要回来
        mode("浏览")
        frames(2)
        controller.saveProjectNow()
        val c2 = newController()
        c2.restoreProject()
        await("恢复场景", c2) { c2.state.value.models.size == 1 && c2.loading.value.isEmpty() && c2.playback.value.maxFrame == 40 && c2.state.value.cameraKeys == 2 }
        frames(3, c2)
        val m2 = model(c2)
        assertEquals(listOf(0, 20, 40), onGl { com.lumi.mmd.engine.scene.KeyOps.boneKeyFrames(m2, spine).toList() })
        assertEquals(0.8f, onGl { com.lumi.mmd.engine.scene.KeyOps.morphWeight(m2, 0, 20) }, 1e-3f)
        c2.seekFrame(20); frames(3, c2)
        val bent2 = FloatArray(3)
        onGl { m2.boneWorldPosition(head, bent2, 0) }
        assertEquals("恢复后的动作和原来一样", bent[0], bent2[0], 1e-3f)
        m = m2
    }

    @Test fun modelling() = guarded("mesh") {
        start()
        skipTutorial()
        // ---- 从零新建（入口只有一个：菜单 → 角色 → 新建模型；空场景的提示里不再重复放按钮）
        rule.onNodeWithText("场景还是空的").assertExists()
        assertTrue("空场景的提示里不再有第二个「添加文件」/「新建模型」", rule.onAllNodesWithText("添加文件").fetchSemanticsNodes().isEmpty() && rule.onAllNodesWithText("新建模型").fetchSemanticsNodes().isEmpty())
        menu("角色", "新建模型")
        var mesh = controller.mesh.value
        assertTrue(mesh.active)
        assertEquals(Modes.MESH, controller.state.value.mode)
        assertEquals(8, mesh.points); assertEquals(6, mesh.faces)
        assertEquals(1, controller.state.value.models.size)
        val id = controller.state.value.models[0].id
        shot("10_mesh_new")

        // 选朝上的面（直接走会话的接口，相当于手指点了一下顶面）→ 挤出 → 用 Y 轴拖动块再往上拉
        onGl { controller.meshSession!!.selectFacesByNormal(0f, 1f, 0f, 10f) }
        ui()
        assertEquals(1, controller.mesh.value.selFaces)
        tapText("挤出")
        mesh = controller.mesh.value
        assertEquals(12, mesh.points); assertEquals(10, mesh.faces)
        assertEquals(1, mesh.selFaces)
        assertTrue(mesh.canUndo)
        val top0 = onGl { controller.scene.models[0].data.boundsMax[1] }
        controller.padStart(); controller.padDrag(Editor.TOOL_MOVE, 1, 300f); controller.padEnd()
        ui()
        val top1 = onGl { controller.scene.models[0].data.boundsMax[1] }
        assertTrue("拖动 Y 轴块应当把挤出的面抬高：$top0 → $top1", top1 > top0 + 0.5f)
        // 内插 → 细分 → 撤销两步
        tapText("内插")
        assertEquals(14, controller.mesh.value.faces)
        tapText("细分")
        assertEquals(17, controller.mesh.value.faces)
        tapIcon("撤销"); tapIcon("撤销")
        assertEquals(10, controller.mesh.value.faces)
        tapIcon("重做")
        assertEquals(14, controller.mesh.value.faces)

        // 点 / 线 / 面 模式切换，全选 / 取消
        tapText("点")
        assertEquals(0, controller.mesh.value.selMode)
        tapText("全选")
        assertEquals(controller.mesh.value.points, controller.mesh.value.selPoints)
        tapText("取消")
        assertEquals(0, controller.mesh.value.selPoints)
        tapText("面")

        // 加一个球，换一种新材质
        tapText("球")
        mesh = controller.mesh.value
        assertTrue("球加进来并且被选中：${mesh.selFaces}", mesh.selFaces > 100)
        tapIcon("新建材质")
        rule.onNode(hasContentDescription("颜色 F25C5C")).performClick()
        ui()
        mesh = controller.mesh.value
        assertEquals(2, mesh.materials.size)
        assertEquals(mesh.selFaces, mesh.materials[1].faces)
        assertEquals(0xF25C5C, mesh.materials[1].color and 0xFFFFFF)
        shot("11_mesh_edit")

        // 完成 → 自动存成 PMX；场景里还是同一个模型
        tapText("完成")
        assertTrue(!controller.mesh.value.active)
        assertEquals("点「完成」回到干净的画面", Modes.VIEW, controller.state.value.mode)
        assertEquals(id, controller.state.value.models[0].id)
        val saved = File(controller.library.root, "自建模型").listFiles()!!.filter { it.name.endsWith(".pmx") }
        assertEquals(1, saved.size)
        val reread = com.lumi.mmd.engine.format.ModelLoader.loadModel(saved[0])
        assertEquals(onGl { controller.scene.models[0].data.vertexCount }, reread.vertexCount)
        assertEquals(2, reread.materials.size)
        assertTrue(reread.warnings.isEmpty())

        // 做出来的模型自带一根骨骼：可以直接打关键帧让它动
        mode("骨骼")
        rule.onNodeWithText("センター").performClick(); ui()
        tapIcon("登记关键帧")
        controller.seekFrame(30); frames(2)
        tapText("移动"); ui()
        assertEquals(Editor.TOOL_MOVE, controller.state.value.tool)
        controller.padStart(); controller.padDrag(Editor.TOOL_MOVE, 1, 200f); controller.padEnd(); frames(2)
        tapIcon("登记关键帧")
        assertEquals(30, controller.playback.value.maxFrame)

        // 再进建模改一下（这次是改已有的文件）：存盘时覆盖同一个文件
        mode("建模")
        assertTrue(controller.mesh.value.active)
        tapText("全选"); tapText("松弛")
        tapText("完成")
        assertEquals(1, File(controller.library.root, "自建模型").listFiles()!!.count { it.name.endsWith(".pmx") })

        // 存盘 → 恢复：模型和它的动画都回来
        frames(2)
        controller.saveProjectNow()
        val c2 = newController()
        c2.restoreProject()
        await("恢复场景", c2) { c2.state.value.models.size == 1 && c2.loading.value.isEmpty() && c2.playback.value.maxFrame == 30 }
        assertEquals(reread.materials.size, onGl { c2.scene.models[0].data.materials.size })
    }

    /** 改一个导入的模型：加配件、绑到骨骼上，原文件不动。 */
    @Test fun editImportedModel() = guarded("meshImported") {
        start()
        skipTutorial()
        val imported = importZip()
        val path = imported.items.first { it.name == "人形.gltf" }.path
        controller.loadModel(path, ModelInstance.KIND_CHARACTER)
        settle()
        val before = onGl { controller.scene.models[0].data.vertexCount }
        mode("建模")
        assertTrue(controller.mesh.value.active)
        assertTrue("人偶有多根骨骼，应当出现「跟随骨骼」", controller.mesh.value.bones.contains("head"))
        tapText("圆环")
        tapText("把选中部分绑到骨骼…")
        rule.onNodeWithText("head").performClick(); ui()
        tapText("完成")
        val edited = File(File(path).parentFile, "人形_edit.pmx")
        assertTrue("改过的模型另存为 *_edit.pmx：${File(path).parentFile!!.list()?.toList()}", edited.isFile)
        assertTrue("原文件还在", File(path).isFile)
        val data = com.lumi.mmd.engine.format.ModelLoader.loadModel(edited)
        assertTrue(data.vertexCount > before)
        assertEquals(onGl { controller.scene.models[0].data.bones.size }, data.bones.size)
        assertEquals("表情还在", 1, data.morphs.size)
        // 新加的圆环跟着 head 动：转 spine 之后它的顶点也跟着偏
        val m = model()
        val headBone = m.skeleton!!.bones.indexOfFirst { it.name == "head" }
        val bi = m.data.boneIndices!!
        val bound = (0 until m.data.vertexCount).count { (bi[it * 4].toInt() and 0xFFFF) == headBone && m.data.boneWeights!![it * 4] == 1f }
        assertTrue("圆环的顶点应当全权重绑在 head 上：$bound", bound >= data.vertexCount - before)
    }

    /** MCP：打开开关，然后像 AI 客户端那样通过 HTTP 调工具。 */
    @Test fun mcpServer() = guarded("mcp") {
        start()
        skipTutorial()
        val imported = importZip()
        menu("AI 操作")
        rule.onNodeWithText("开启 MCP 服务").performClick()
        await("MCP 启动") { controller.mcp.value.running }
        rule.waitForIdle()
        val shown = controller.mcp.value.urls
        assertTrue("应当给出带密钥的地址：$shown", shown.isNotEmpty() && Regex("http://[0-9.]+:\\d+/mcp/[a-z0-9]{8}").matches(shown[0]))
        shot("12_mcp_panel")
        val url = "http://127.0.0.1:" + shown[0].substringAfter("://").substringAfter(":")
        // 握手
        val init = http(url, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        assertTrue(init, init.startsWith("200 ") && init.contains("\"serverInfo\"") && init.contains("lumi-mmd"))
        assertEquals("202 ", http(url, """{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        val list = http(url, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        for (t in listOf("get_scene", "set_bone_keys", "mesh_add_primitive", "mesh_op", "screenshot", "export_vmd")) assertTrue("工具清单里应当有 $t", list.contains("\"$t\""))
        // 密钥不对进不来
        assertTrue(http(url.substringBeforeLast('/') + "/wrongkey", "{}").startsWith("404"))

        // 加载模型 → 打关键帧 → 查询
        val lib = mcpCall(url, "list_library", """{"type":"model"}""")
        assertTrue(lib, lib.contains("人形.gltf"))
        val rel = File(imported.items.first { it.name == "人形.gltf" }.path).relativeTo(controller.library.root).path
        val loaded = mcpCall(url, "load_model", """{"path":"${rel.replace("\\", "/")}","kind":"character"}""")
        assertTrue(loaded, loaded.contains("\"bones\":"))
        settle()
        assertEquals(1, controller.state.value.models.size)
        mcpCall(url, "delete_keys", """{"all":true}""")
        val keys = mcpCall(url, "set_bone_keys", """{"keys":[{"bone":"spine","frame":0,"rot":[0,0,0]},{"bone":"spine","frame":30,"rot":[0,0,40],"curve":"ease_in_out"}]}""")
        assertTrue(keys, keys.contains("\"written\":2") && keys.contains("\"last_frame\":30"))
        mcpCall(url, "set_morph_keys", """{"keys":[{"morph":"bigHead","frame":0,"weight":0},{"morph":"bigHead","frame":30,"weight":1}]}""")
        frames(2); rule.waitForIdle()
        assertEquals(30, controller.playback.value.maxFrame)
        val m = model()
        val spine = m.skeleton!!.bones.indexOfFirst { it.name == "spine" }
        assertEquals(listOf(0, 30), onGl { com.lumi.mmd.engine.scene.KeyOps.boneKeyFrames(m, spine).toList() })
        val pose = mcpCall(url, "get_pose", """{"bones":["spine"]}""")
        assertTrue("第 30 帧 spine 绕 Z 转 40°：$pose", pose.contains("\"rot\":[0,0,40]"))
        assertTrue(mcpCall(url, "get_keys", "{}").contains("\"spine\""))
        val vmd = mcpCall(url, "export_vmd", """{"name":"ai_wave"}""")
        assertTrue(vmd, File(controller.library.root, "自制动作/ai_wave.vmd").isFile)

        // 从零建一个模型：方块 → 选顶面 → 挤出 → 材质 → 结束并保存
        val created = mcpCall(url, "create_model", """{"name":"AI 桌子"}""")
        assertTrue(created, created.contains("\"faces\":0"))
        mcpCall(url, "mesh_add_primitive", """{"type":"box","size":[10,1,6],"position":[0,7,0]}""")
        val sel = mcpCall(url, "mesh_select", """{"action":"normal","direction":[0,1,0],"angle":5}""")
        assertTrue(sel, sel.contains("\"faces\":1"))
        val ext = mcpCall(url, "mesh_op", """{"op":"extrude","distance":0.5}""")
        assertTrue(ext, ext.contains("\"faces\":10"))
        mcpCall(url, "mesh_material", """{"action":"set","material":0,"name":"wood","color":[0.7,0.5,0.3]}""")
        mcpCall(url, "mesh_add_geometry", """{"vertices":[[0,0,0],[1,0,0],[1,1,0],[0,1,0]],"faces":[[0,1,2,3]],"winding":"ccw","position":[0,8,0]}""")
        val info = mcpCall(url, "mesh_info", "{}")
        assertTrue(info, info.contains("\"faces\":11") && info.contains("\"wood\""))
        // 没选对东西时给 AI 一个看得懂的报错，而不是悄悄什么都不做
        mcpCall(url, "mesh_select", """{"action":"none"}""")
        val bad = http(url, """{"jsonrpc":"2.0","id":99,"method":"tools/call","params":{"name":"mesh_op","arguments":{"op":"extrude"}}}""")
        assertTrue(bad, bad.contains("\"isError\":true") && bad.contains("select"))
        val end = mcpCall(url, "mesh_end", "{}")
        assertTrue(end, end.contains("AI 桌子.pmx"))
        assertTrue(File(controller.library.root, "自建模型/AI 桌子.pmx").isFile)
        frames(2); rule.waitForIdle()
        assertEquals(2, controller.state.value.models.size)
        assertTrue("界面上应当显示处理过的调用数", controller.mcp.value.calls >= 15)
        assertEquals("mesh_end", controller.mcp.value.lastCall)

        // 关掉开关之后连不上
        rule.onNodeWithText("开启 MCP 服务").performClick()
        await("MCP 停止") { !controller.mcp.value.running }
        assertTrue(try { http(url, "{}"); false } catch (e: java.io.IOException) { true })
    }

    /** 4.2：地板可以删 / 改 / 加回来，模型有碰撞属性，三渲二；AI 改的画面设置界面跟得上，全部跟着工程存取。 */
    @Test fun floorCollisionAndToonStyle() = guarded("stage42") {
        start()
        skipTutorial()
        val imported = importZip()
        controller.loadModel(imported.items.first { it.name == "stage.obj" }.path)
        settle()
        val stage = controller.state.value.models[0]
        assertEquals("新加的模型默认没有碰撞", 0, stage.collision)
        assertTrue("新场景有一块默认地板", controller.state.value.floor.let { it.enabled && it.collide && it.height == 0f })

        // ---- 地板：在「舞台与背景」里删掉，再加回默认的
        menu("舞台与背景")
        shot("40_floor")
        tapText("默认地板")
        assertFalse("点了删除，地板应该没了", controller.state.value.floor.enabled)
        assertFalse(onGl { controller.scene.floor.solid })
        waitForText("添加默认地板")
        shot("41_floor_removed")
        tapText("添加默认地板")
        assertTrue(controller.state.value.floor.enabled)
        // 关掉碰撞、抬高、换底色
        tapText("碰撞")
        assertFalse(controller.state.value.floor.collide)
        tapText("木色")
        controller.editFloor { it.height = 2.5f }
        ui()
        val f = controller.state.value.floor
        assertEquals(2.5f, f.height, 0f); assertEquals(1f, f.color[3], 0f); assertEquals(0.56f, f.color[1], 1e-3f)
        closeSheet()

        // ---- 模型的碰撞属性：模型页里选「网格」
        menu("角色", stage.name)
        tapText("网格")
        assertEquals(2, controller.state.value.models[0].collision)
        assertEquals(1, onGl { controller.scene.colliders.size })
        shot("42_model_collision")
        closeSheet()

        // ---- 三渲二：「灯光与画面」里打开
        menu("灯光与画面")
        tapText("三级")
        assertEquals(2, controller.state.value.style.cel)
        assertTrue("打开三渲二时顺手给所有模型描边", controller.state.value.style.edgeAll)
        shot("43_toon")
        closeSheet()

        // ---- AI 通过 MCP 改：界面上的状态跟着变
        controller.setMcpEnabled(true)
        await("MCP 启动") { controller.mcp.value.running }
        assertTrue("本机地址也要列出来", controller.mcp.value.urls.last().startsWith("http://127.0.0.1:"))
        val url = controller.mcp.value.urls.last()
        val look = mcpCall(url, "render", """{"cel":4,"edge_color":[0.4,0.1,0.1],"bloom":0.6,"background":[0.1,0.2,0.3]}""")
        assertTrue(look, look.contains("\"cel\":4"))
        val fl = mcpCall(url, "floor", """{"height":-1,"collide":true}""")
        assertTrue(fl, fl.contains("\"height\":-1"))
        mcpCall(url, "set_model", """{"collision":"box"}""")
        frames(2); rule.waitForIdle()
        assertEquals(4, controller.state.value.style.cel)
        assertEquals(-1f, controller.state.value.floor.height, 0f)
        assertEquals(1, controller.state.value.models[0].collision)
        assertTrue("AI 开的泛光要同步到设置里，不然动一下别的开关就被盖回去", controller.prefs.value.bloom)
        // 动一个不相干的设置：AI 指定的背景色和三渲二不能被盖掉
        controller.updatePrefs(controller.prefs.value.copy(exposure = 1.1f))
        frames(2)
        assertEquals(0.2f, onGl { controller.renderer.settings.backgroundTop[1] }, 1e-4f)
        assertEquals(4, onGl { controller.renderer.settings.celBands })
        assertTrue("请求有计数和耗时", controller.mcp.value.requests >= 3 && controller.mcp.value.lastRequest == "tools/call")
        controller.setMcpEnabled(false)
        await("MCP 停止") { !controller.mcp.value.running }

        // ---- 存盘，换一个全新的控制器恢复：地板、碰撞、画风都要回来
        controller.saveProjectNow()
        val c2 = newController()
        c2.restoreProject()
        await("恢复场景", c2) { c2.state.value.models.size == 1 && c2.loading.value.isEmpty() }
        frames(3, c2)
        val st = c2.state.value
        assertEquals(-1f, st.floor.height, 0f); assertTrue(st.floor.collide); assertEquals(0.56f, st.floor.color[1], 1e-3f)
        assertEquals(1, st.models[0].collision)
        assertEquals(4, st.style.cel); assertTrue(st.style.edgeAll)
        assertEquals(0.2f, onGl { c2.renderer.settings.backgroundTop[1] }, 1e-4f)
        assertEquals(0.4f, onGl { c2.renderer.settings.edgeColor[0] }, 1e-4f)

        // 删掉地板后存盘：重开还是没有地板
        c2.removeFloor(); frames(2, c2)
        c2.saveProjectNow()
        val c3 = newController()
        c3.restoreProject()
        await("恢复场景", c3) { c3.state.value.models.size == 1 && c3.loading.value.isEmpty() }
        frames(3, c3)
        assertFalse(c3.state.value.floor.enabled)
        // 清空场景：地板和画风都回到默认
        c3.newScene(); frames(3, c3)
        assertTrue(c3.state.value.floor.let { it.enabled && it.height == 0f && it.collide })
        assertEquals(0, c3.state.value.style.cel); assertFalse(c3.state.value.style.custom)
    }

    /** 删除资源、清空场景这些带确认框的操作。 */
    @Test fun deleteAndClear() = guarded("delete") {
        start()
        skipTutorial()
        val imported = importZip()
        controller.loadModel(imported.items.first { it.name == "人形.gltf" }.path)
        settle()
        assertEquals(1, controller.state.value.models.size)

        mode("模型")
        tapText("移除")
        assertEquals(0, controller.state.value.models.size)

        controller.loadModel(imported.items.first { it.name == "stage.obj" }.path)
        settle()
        assertEquals("OBJ 舞台应当被自动判断为舞台或配件，而不是角色", false, controller.state.value.models[0].kind == ModelInstance.KIND_CHARACTER)
        menu("设置", "清空场景")
        rule.onNodeWithText("清空").performClick()
        ui()
        assertEquals(0, controller.state.value.models.size)
        assertTrue("清空场景后不应当还有可撤销的操作", !controller.state.value.canUndo)

        // 资源库里删除整包
        menu("设置", "资源库")
        waitForText("删除整包")          // 文件列表是在后台线程扫出来的
        rule.onNodeWithText("删除整包").performClick()
        rule.onNodeWithText("删除").performClick()
        rule.waitForIdle()
        await("整包删掉") { controller.library.scan().isEmpty() }
        waitForText("资源库是空的。回到画面点右上角的「添加」导入文件，导入过的都会留在这里")
        assertEquals(0, controller.library.scan().size)
    }

    /**
     * 可选：用真实的 MMD 素材再走一遍（素材不随仓库分发）。
     * 设置环境变量 LUMI_TEST_ASSETS=<目录>，目录里放 model.pmx（连同贴图）和 motion.vmd；
     * 再放几张 backdrop_*.png（无头渲染出来的画面）的话，截图会拿它们当三维画面的背景。
     */
    @Test fun realMmdAssets() = guarded("real") {
        val dir = System.getProperty("lumi.assets").orEmpty().ifEmpty { null }?.let { File(it) }
        assumeTrue("未设置 LUMI_TEST_ASSETS，跳过", dir != null && File(dir, "model.pmx").isFile)
        dir!!
        start(File(dir, "backdrop_view.png"))
        skipTutorial()
        // 直接放进资源库目录（真机上是用系统文件选择器导入）
        val pack = File(controller.library.root, "测试模型")
        dir.listFiles()!!.filter { !it.name.startsWith("backdrop") }.forEach { it.copyRecursively(File(pack, it.name), overwrite = true) }
        controller.notifyLibraryChanged()

        controller.loadModel(File(pack, "model.pmx").path)
        settle()
        assertEquals(1, controller.state.value.models.size)
        val m = model()
        assertNotNull(m.skeleton)
        if (File(pack, "motion.vmd").isFile) {
            controller.applyMotion(File(pack, "motion.vmd").path)
            settle()
            assertTrue(controller.playback.value.maxFrame > 0)
            // 物理 + IK 实打实地跑 60 帧
            controller.togglePlay()
            frames(60, sleepMs = 5)
            controller.pause(); frames(2)
            assertTrue(controller.playback.value.frame > 1f)
            assertTrue("骨骼矩阵里出现了 NaN", onGl { m.skeleton!!.global.none { it.isNaN() } })
            controller.seekFrame(300); frames(2)
        }
        rule.waitForIdle()
        shot("20_real_view")

        // 骨骼模式：选左腕，转一下，登记；时间轴上能看到整段舞蹈的关键帧
        backdrop("bone")
        mode("骨骼")
        tapIcon("时间轴")
        assertTrue(controller.detail.value.bones.isNotEmpty())
        val arm = m.skeleton!!.bones.indexOfFirst { it.name == "左腕" }
        if (arm >= 0) {
            controller.selectBone(arm); frames(2); rule.waitForIdle()
            controller.padStart(); controller.padDrag(Editor.TOOL_ROTATE, 2, -150f); controller.padEnd(); frames(2)
            tapIcon("登记关键帧")
            assertTrue(controller.playback.value.boneKeyed)
            assertTrue("共享的舞蹈动作被复制成了自己的一份", onGl { m.motionOwned })
        }
        assertTrue(controller.timeline.value.tracks.size > 10)
        shot("21_real_bone")
        backdrop("view")
        mode("表情"); shot("22_real_morph")
        mode("镜头"); shot("23_real_camera")
        mode("模型"); shot("24_real_model")
        mode("部件")
        assertTrue(controller.detail.value.parts.isNotEmpty())
        shot("25_real_part")
        // 建模：真实模型拆成可编辑网格
        backdrop("mesh")
        mode("建模")
        val mesh = controller.mesh.value
        assertTrue(mesh.active)
        assertTrue("点数应当比顶点数少（重复顶点并成了点）：${mesh.points}", mesh.points in 1000 until m.data.vertexCount)
        shot("26_real_mesh")
        tapText("完成")
        backdrop("view")
        menu(); shot("27_real_menu")
        menu("动作与音乐"); shot("28_real_clips")
        menu("角色", "Hutao"); shot("30_real_model_page")
        menu("灯光与画面"); shot("31_real_look"); closeSheet()
        tapIcon("隐藏界面"); shot("29_real_hidden")
        tapIcon("显示界面")

        // ---- 第四版：轨道、脚本、操控（真实模型有标准的腿部骨骼，走跑蹲趴都有动画）
        mode("轨道")
        assertTrue(controller.state.value.play.lanes.any { it.kind == Lanes.MODEL && it.clips.isNotEmpty() })
        shot("32_real_tracks")
        mode("浏览")
        controller.seekFrame(0); frames(2)
        controller.play.addExample("樱花飘落"); controller.play.addExample("手上的光点")
        frames(40, sleepMs = 4)
        assertTrue("示例脚本应当在运行：${controller.play.scriptLog.value}", controller.state.value.play.scripts.all { it.running && it.error.isEmpty() })
        assertTrue("粒子应当已经出来了", onGl { controller.runtime.effects.particleCount } > 20)
        menu("脚本与特效"); shot("33_real_scripts"); closeSheet()
        menu("物理"); shot("34_real_physics"); closeSheet()

        tapIcon("操控模式")
        var ctl = controller.state.value.play.control
        assertTrue(ctl.on)
        assertTrue("胡桃有足ＩＫ，应当有走跑的动画", ctl.animated)
        rule.onNode(hasContentDescription("摇杆")).assertExists()
        val start = FloatArray(3); val now = FloatArray(3)
        onGl { controller.runtime.controller!!.worldPosition(start) }
        controller.play.stick(0f, 1f)
        frames(50, sleepMs = 8)
        onGl { controller.runtime.controller!!.worldPosition(now) }
        val moved = kotlin.math.hypot(now[0] - start[0], now[2] - start[2])
        assertTrue("推满摇杆应当跑出去一段：$moved", moved > 5f)
        assertEquals("跑", controller.state.value.play.control.state)
        shot("35_real_control_run")
        tapIcon("下蹲")
        frames(20, sleepMs = 8)
        assertEquals(1, controller.state.value.play.control.stance)
        rule.waitForIdle()
        shot("36_real_control_crouch")
        tapIcon("趴下")
        frames(20, sleepMs = 8)
        assertEquals(2, controller.state.value.play.control.stance)
        controller.play.stick(0f, 0f)
        tapIcon("跳跃")      // 趴着按跳 = 先起来
        frames(10, sleepMs = 8)
        assertTrue(controller.state.value.play.control.stance < 2)
        assertTrue("骨骼矩阵里出现了 NaN", onGl { m.skeleton!!.global.none { it.isNaN() } })
        tapIcon("退出操控")
        assertTrue(!controller.state.value.play.control.on)
        rule.onNode(hasContentDescription("摇杆")).assertDoesNotExist()
    }

    /**
     * 竖屏：同一套界面，菜单卡片和工具面板换到下半屏，模型露在上面。
     * 有真实素材时用它们（截图好看），没有就用测试自己生成的人偶。
     */
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-port-420dpi")
    @Test fun portraitLayout() = guarded("portrait") {
        tall = true
        val dir = System.getProperty("lumi.assets").orEmpty().ifEmpty { null }?.let { File(it) }?.takeIf { File(it, "model.pmx").isFile }
        start(dir?.let { File(it, "backdrop_tall_view.png") })
        skipTutorial()
        rule.onNodeWithText("场景还是空的").assertExists()
        shot("P0_empty")
        val name: String
        if (dir != null) {
            val pack = File(controller.library.root, "测试模型")
            dir.listFiles()!!.filter { !it.name.startsWith("backdrop") }.forEach { it.copyRecursively(File(pack, it.name), overwrite = true) }
            controller.notifyLibraryChanged()
            controller.loadModel(File(pack, "model.pmx").path); settle()
            if (File(pack, "motion.vmd").isFile) { controller.applyMotion(File(pack, "motion.vmd").path); settle() }
            controller.seekFrame(300); frames(3)
            name = controller.state.value.models[0].name
        } else {
            val imported = importZip()
            controller.loadModel(imported.items.first { it.name == "人形.gltf" }.path, ModelInstance.KIND_CHARACTER); settle()
            name = controller.state.value.models[0].name
        }
        rule.waitForIdle()
        rule.onNodeWithText("场景还是空的").assertDoesNotExist()
        shot("P1_home")

        // 菜单卡片：在下半屏，一层层点进去
        menu(); shot("P2_menu")
        menu("动作与音乐")
        rule.onNode(hasContentDescription("清空动作")).assertExists()
        rule.onNode(hasContentDescription("选择音乐")).assertExists()
        shot("P3_clips")
        tapIcon("返回")
        rule.onAllNodesWithText("角色").onLast().assertExists()
        menu("角色", name); shot("P4_model_page")
        menu("灯光与画面"); shot("P5_look")
        closeSheet()
        assertTrue(rule.onAllNodes(hasContentDescription("菜单卡片")).fetchSemanticsNodes().isEmpty())

        // 编辑：工具面板在下面，时间轴夹在画面和面板之间
        backdrop("tall_bone")
        mode("骨骼")
        assertEquals(Modes.BONE, controller.state.value.mode)
        val bone = onGl { controller.scene.models[0].skeleton!!.bones.indexOfFirst { it.name == "左腕" || it.name == TestAssets.BONE_SPINE } }
        controller.selectBone(bone); frames(2); rule.waitForIdle()
        controller.padStart(); controller.padDrag(Editor.TOOL_ROTATE, 2, -120f); controller.padEnd(); frames(2)
        tapIcon("登记关键帧")
        assertTrue(controller.playback.value.boneKeyed)
        shot("P6_bone")
        tapIcon("时间轴"); ui()
        assertTrue(controller.timeline.value.tracks.isNotEmpty())
        shot("P7_timeline")
        mode("表情"); shot("P8_morph")
        // 竖屏放不下六个工具词，那一行可以左右滑。从别处（比如菜单的「编辑」页）直接切到排在最后的工具时，
        // 那一行要自己滑过去，让选中的词完整地露出来；再切回前面的也一样
        assertTrue("竖屏下最后一个词本来应该在屏幕外", !wordFullyShown("模式 建模"))
        controller.setMode(Modes.MESH); ui(); ui()
        assertEquals(Modes.MESH, controller.state.value.mode)
        assertTrue("选中的工具词应当滑到看得见的地方", wordFullyShown("模式 建模"))
        shot("P8b_last_tool")
        controller.setMode(Modes.MODEL); ui(); ui()
        assertTrue("切回第一个工具时也要露出来", wordFullyShown("模式 模型"))
        controller.setMode(Modes.MORPH); ui(); ui()
        backdrop("tall_view")
        mode("浏览")
        assertEquals(Modes.VIEW, controller.state.value.mode)

        // 屏幕方向可以在设置里锁定
        menu("设置")
        rule.onAllNodesWithText("横屏").onLast().performClick(); ui()
        assertEquals(1, controller.orientation.value)
        rule.onAllNodesWithText("自动").onLast().performClick(); ui()
        assertEquals(0, controller.orientation.value)
        shot("P9_settings")
        closeSheet()

        // 第四版的几块在竖屏下也要放得下
        mode("轨道")
        assertEquals(Modes.CLIPS, controller.state.value.mode)
        rule.onNode(hasContentDescription("轨道标尺")).assertExists()
        shot("P10_tracks")
        mode("浏览")
        menu("脚本与特效"); shot("P11_scripts"); closeSheet()
        menu("物理"); shot("P12_physics"); closeSheet()
        tapIcon("展开播放条")
        assertTrue(fullyOnScreen("进度") && fullyOnScreen("收起播放条"))
        shot("P13_bar")
        tapIcon("收起播放条")
        tapIcon("操控模式")
        assertTrue(controller.state.value.play.control.on)
        for (b in listOf("摇杆", "冲刺", "跳跃", "下蹲", "趴下", "退出操控")) assertTrue("竖屏下「$b」应当完整地在屏幕里", fullyOnScreen(b))
        shot("P14_control")
        tapIcon("退出操控")
        assertTrue(!controller.state.value.play.control.on)
    }

    /** 只选了一个 .pmx、应用又读不了它旁边的文件夹：弹出"贴图没有一起导入"，之后可以从「角色」里再找。 */
    @Test fun texturePrompt() = guarded("textures") {
        start()
        skipTutorial()
        val dir = File(context.cacheDir, "phone/Download/角色包").apply { mkdirs() }
        File(dir, "body.png").writeBytes(ByteArray(32) { 1 })
        File(dir, "face.png").writeBytes(ByteArray(32) { 2 })
        val pmx = File(dir, "小人.pmx")
        run {
            val em = com.lumi.mmd.engine.mesh.EditMesh.createEmpty("小人")
            com.lumi.mmd.engine.mesh.MeshPrimitives.box(em, 2f, 2f, 2f)
            val data = em.build().data
            data.textures.add(com.lumi.mmd.engine.model.TextureRef("body.png")); data.textures.add(com.lumi.mmd.engine.model.TextureRef("face.png"))
            data.materials[0].texture = 0; data.materials[0].sphereTexture = 1
            com.lumi.mmd.engine.format.PmxWriter.save(data, pmx)
        }
        assertTrue(dir.setReadable(false))       // 文件本身能读，文件夹不能列：没有存储权限时就是这样
        try {
            controller.addFiles(listOf(Uri.fromFile(pmx)))
            settle()
            await("提示出现") { controller.texturePrompt.value != null && controller.state.value.models.size == 1 }
            rule.waitForIdle()
            rule.onNodeWithText("贴图没有一起导入").assertExists()
            rule.onNodeWithText("允许访问所有文件").assertExists()
            rule.onNodeWithText("选择模型所在的文件夹").assertExists()
            shot("13_texture_prompt")
            rule.onNodeWithText("先不管").performClick(); ui()
            assertTrue(controller.texturePrompt.value == null)

            // 模型照样加载了（没有贴图）；「角色」里标着还缺几张
            menu("角色")
            assertEquals(2, controller.state.value.models[0].missingTextures)
            shot("14_cast_missing")
            // 之后文件夹能读了（给了权限）：点进模型那一页，点「缺 2 张贴图」把它们找回来
            dir.setReadable(true)
            rule.onAllNodesWithText("小人").onLast().performClick(); ui()
            rule.onAllNodesWithText("缺 2 张贴图").onLast().performClick()
            await("找回贴图") { controller.state.value.models.firstOrNull()?.missingTextures == 0 }
            settle()
            val lib = onGl { controller.scene.models[0].data.sourceFile!!.parentFile!! }
            assertTrue(File(lib, "body.png").isFile && File(lib, "face.png").isFile)
            assertTrue(rule.onAllNodesWithText("缺 2 张贴图").fetchSemanticsNodes().isEmpty())
        } finally { dir.setReadable(true) }
    }

    /**
     * 第四版新增的部分：播放条平时收着 → 轨道（分割 / 复制 / 删除 / 撤销 / 内置动作）→ 脚本（示例、改代码、报错、控制台）→
     * 物理参数 → 上下层 → 操控模式 → 导入 .lua → 存盘恢复。
     */
    @Test fun tracksScriptsPhysicsControl() = guarded("v4") {
        start()
        skipTutorial()
        val imported = importZip()
        controller.loadModel(imported.items.first { it.name == "人形.gltf" }.path, ModelInstance.KIND_CHARACTER); settle()
        val m = model()
        onGl { m.setMotion(null) }
        controller.applyMotion(imported.items.first { it.name == "おじぎ.vmd" }.path); settle()
        assertEquals(60, controller.playback.value.maxFrame)
        rule.waitForIdle()

        // ---- 播放条：平时只有一颗播放钮，点时间才展开
        assertTrue("播放条平时应当是收起来的", rule.onAllNodes(hasContentDescription("进度")).fetchSemanticsNodes().isEmpty())
        rule.onNode(hasContentDescription("播放")).assertExists()
        shot("40_home_collapsed")
        tapIcon("展开播放条")
        rule.onNode(hasContentDescription("进度")).assertExists()
        rule.onNode(hasContentDescription("播放速度")).assertExists()
        shot("41_home_bar")
        tapIcon("收起播放条")
        assertTrue(rule.onAllNodes(hasContentDescription("进度")).fetchSemanticsNodes().isEmpty())

        // ---- 轨道
        mode("轨道")
        assertEquals(Modes.CLIPS, controller.state.value.mode)
        fun lane() = controller.state.value.play.lanes.first { it.kind == Lanes.MODEL }
        assertEquals(1, lane().clips.size)
        val clipName = lane().clips[0].name
        assertEquals("おじぎ", clipName)
        rule.onNode(hasContentDescription("轨道 ${lane().name}")).assertExists()
        tapIcon("片段 $clipName")
        assertEquals(lane().clips[0].id, controller.state.value.play.clip)
        shot("42_tracks")
        controller.seekFrame(20); ui()
        tapText("分割")
        assertEquals(listOf(0 to 20, 20 to 60), lane().clips.map { it.start to it.end })
        tapText("复制")
        assertEquals(listOf(0 to 20, 20 to 60, 60 to 100), lane().clips.map { it.start to it.end })
        assertEquals(100, controller.playback.value.maxFrame)
        tapText("删除")
        assertEquals(2, lane().clips.size)
        tapIcon("撤销")
        assertEquals("撤销应当把删掉的片段拿回来", 3, lane().clips.size)
        // 拖动最后一段到后面去（界面上是按住拖；这里直接走同一个入口）
        val last = lane().clips.last()
        controller.play.dragClip(Lanes.MODEL, lane().id, last.id, move = true, start = 110, end = null, begin = true); ui()
        assertEquals(110 to 150, lane().clips.last().let { it.start to it.end })
        // 裁掉这一段的结尾
        controller.play.dragClip(Lanes.MODEL, lane().id, last.id, move = false, start = null, end = 130, begin = true); ui()
        assertEquals(110 to 130, lane().clips.last().let { it.start to it.end })
        assertEquals(130, controller.playback.value.maxFrame)
        // 空着的那一段（100..110）保持上一段结尾的姿势，不会弹回初始姿势
        val spine = m.skeleton!!.bones.indexOfFirst { it.name == TestAssets.BONE_SPINE }
        val head = m.skeleton!!.bones.indexOfFirst { it.name == "head" }
        val a = FloatArray(3); val b = FloatArray(3)
        controller.seekFrame(102); frames(2); onGl { m.boneWorldPosition(head, a, 0) }
        controller.seekFrame(107); frames(2); onGl { m.boneWorldPosition(head, b, 0) }
        assertEquals("两段之间保持姿势", a[0], b[0], 1e-4f)
        shot("43_tracks_edited")
        // 导出的 VMD 是按轨道上的摆放合成的
        controller.exportVmd(false); frames(2)
        val exported = File(controller.library.root, "自制动作").listFiles()!!.filter { it.name.endsWith(".vmd") && !it.name.startsWith("工程_") }
        assertEquals(1, exported.size)
        val baked = com.lumi.mmd.engine.format.VmdReader.read(exported[0].readBytes())
        assertEquals("合成后的总长", 130, baked.maxFrame)
        assertTrue(spine >= 0 && baked.boneTracks[TestAssets.BONE_SPINE]!!.count >= 4)

        // ---- 脚本：加一个示例
        mode("浏览")
        menu("脚本与特效")
        shot("44_scripts")
        tapIcon("添加示例 樱花飘落")
        frames(30, sleepMs = 4)
        var st = controller.state.value
        assertEquals(1, st.play.scripts.size)
        assertTrue("脚本应当在运行：${controller.play.scriptLog.value}", st.play.scripts[0].running && st.play.scripts[0].error.isEmpty())
        assertTrue("花瓣应当已经在飘了", onGl { controller.runtime.effects.particleCount } > 5)
        ui()
        rule.onNode(hasContentDescription("添加示例 樱花飘落")).assertIsNotEnabled()
        // 点进去改代码：先写一段有语法错误的
        tapText("樱花飘落")
        await("读出脚本代码") { rule.waitForIdle(); rule.onAllNodes(hasContentDescription("脚本代码")).fetchSemanticsNodes().isNotEmpty() }
        shot("45_script_page")
        rule.onNode(hasContentDescription("脚本代码")).performTextReplacement("this is not lua")
        rule.waitForIdle()
        // 开始输入之后：代码框占满整页（开关、输出先收起来），上面一行按钮一直在
        rule.onNode(hasContentDescription("结束输入")).assertExists()
        assertTrue(rule.onAllNodesWithText("输出").fetchSemanticsNodes().isEmpty())
        assertTrue("写代码时卡片应当放宽", rule.onNode(hasContentDescription("菜单卡片")).fetchSemanticsNode().size.width > rule.onRoot().fetchSemanticsNode().size.width * 0.8f)
        shot("45b_script_editing")
        tapText("保存并运行")
        st = controller.state.value
        assertTrue("语法错误的脚本不应当运行", !st.play.scripts[0].running)
        assertTrue("特效应当跟着脚本一起清掉", onGl { controller.runtime.effects.particleCount } == 0)
        awaitLog("syntax error")
        // 再写一段能跑的：把角色变半透明、放到上面一层
        rule.onNode(hasContentDescription("脚本代码")).performTextReplacement("local m = scene.main()\nfunction update(dt)\n  m.opacity = 0.5\n  m.layer = 3\nend\nprint('hello', m.name)")
        rule.waitForIdle()
        tapText("保存并运行")
        frames(3)
        assertEquals(0.5f, onGl { m.opacity }, 1e-4f)
        assertEquals(3, onGl { m.layer })
        awaitLog("hello")
        // 关掉脚本：它改过的东西自动复原
        tapIcon("结束输入")
        assertTrue("结束输入后卡片回到原来的宽度", rule.onNode(hasContentDescription("菜单卡片")).fetchSemanticsNode().size.width < rule.onRoot().fetchSemanticsNode().size.width * 0.5f)
        tapText("运行")
        frames(2)
        assertEquals("脚本关掉后透明度复原", 1f, onGl { m.opacity }, 1e-4f)
        assertEquals(0, onGl { m.layer })
        // 运行时出错的脚本：停下来，错误显示在页面上
        rule.onNode(hasContentDescription("脚本代码")).performTextReplacement("function update(dt)\n  local x = nil\n  x.y = 1\nend")
        rule.waitForIdle()
        tapText("保存并运行")
        frames(3); ui()
        st = controller.state.value
        assertTrue("运行出错应当记下来", st.play.scripts[0].error.isNotEmpty() && !st.play.scripts[0].running)
        rule.onNode(hasContentDescription("脚本错误")).assertExists()
        assertTrue("报错带行号、不带调用栈：${st.play.scripts[0].error}", st.play.scripts[0].error.startsWith("第 3 行：") && !st.play.scripts[0].error.contains("traceback"))
        tapIcon("结束输入")
        shot("46_script_error")
        tapText("删除")
        assertTrue(controller.state.value.play.scripts.isEmpty())

        // ---- 控制台
        menu("脚本与特效", "控制台")
        rule.onNode(hasContentDescription("控制台输入")).performTextReplacement("physics.wind(120, 0, 0)")
        rule.waitForIdle()
        tapText("运行")
        frames(2)
        assertEquals(120f, onGl { controller.scene.physics.windX }, 1e-3f)
        rule.onNode(hasContentDescription("控制台输入")).performTextReplacement("return scene.main().name")
        rule.waitForIdle()
        tapText("运行"); ui()
        awaitLog(st.models[0].name)
        shot("47_console")
        tapIcon("复原控制台改动")
        frames(2)
        assertEquals("复原后风停了", 0f, onGl { controller.scene.physics.windX }, 1e-3f)
        menu("脚本与特效", "接口说明")
        rule.onNodeWithText("复制全文").assertExists()
        closeSheet()

        // ---- 物理参数
        menu("物理")
        shot("48_physics")
        tapText("大风")
        assertEquals(140f, onGl { controller.scene.physics.windZ }, 1e-3f)
        assertEquals(0.8f, onGl { controller.scene.physics.turbulence }, 1e-3f)
        tapText("厚重")
        assertEquals(2.2f, onGl { controller.scene.physics.mass }, 1e-3f)
        assertEquals("换预设时先回到默认", 0f, onGl { controller.scene.physics.windZ }, 1e-3f)
        tapText("全部恢复默认")
        assertTrue(onGl { controller.scene.physics.isDefault })
        controller.play.setPhysics("stiffness", 1.5f); controller.play.setPhysics("gravity", 0.5f, m.id); frames(2)
        closeSheet()

        // ---- 上下层
        menu("角色", st.models[0].name)
        tapIcon("上移一层"); tapIcon("上移一层")
        assertEquals(2, onGl { m.layer })
        rule.onNode(hasContentDescription("层 2")).assertExists()
        shot("49_layer")
        closeSheet()

        // ---- 操控模式：这个人偶没有 MMD 的腿部骨骼，只能平移，但开关、摇杆、按钮都要能用
        // 入口只有一个：首页右下角的手柄（菜单里不再重复放一个开关）
        menu()
        assertTrue("菜单里不应当再有操控模式的开关", rule.onAllNodesWithText("操控模式").fetchSemanticsNodes().isEmpty())
        closeSheet()
        tapIcon("操控模式")
        var ctl = controller.state.value.play.control
        assertTrue(ctl.on)
        assertTrue(!ctl.animated)
        rule.onNode(hasContentDescription("摇杆")).assertExists()
        for (b in listOf("冲刺", "跳跃", "下蹲", "趴下")) rule.onNode(hasContentDescription(b)).assertExists()
        shot("50_control")
        val p0 = FloatArray(3); val p1 = FloatArray(3)
        onGl { controller.runtime.controller!!.worldPosition(p0) }
        // 真的在摇杆上按住往上推
        rule.onNode(hasContentDescription("摇杆")).performTouchInput { down(center); moveBy(Offset(0f, -height * 0.4f)) }
        frames(30, sleepMs = 8)
        onGl { controller.runtime.controller!!.worldPosition(p1) }
        assertTrue("推摇杆角色应当移动：${p1[0] - p0[0]}, ${p1[2] - p0[2]}", kotlin.math.hypot(p1[0] - p0[0], p1[2] - p0[2]) > 0.5f)
        rule.onNode(hasContentDescription("摇杆")).performTouchInput { up() }
        frames(2)
        assertEquals("松手后摇杆回中", 0f, controller.runtime.input.moveY, 1e-6f)
        tapIcon("下蹲")
        assertEquals(1, controller.state.value.play.control.stance)
        tapIcon("下蹲")
        assertEquals(0, controller.state.value.play.control.stance)
        tapIcon("冲刺")      // 点一下 = 锁定自动冲刺
        assertTrue(controller.state.value.play.control.sprintLock)
        tapIcon("冲刺")
        assertTrue(!controller.state.value.play.control.sprintLock)
        tapIcon("退出操控")
        ctl = controller.state.value.play.control
        assertTrue(!ctl.on)
        rule.onNode(hasContentDescription("摇杆")).assertDoesNotExist()
        rule.onNode(hasContentDescription("菜单")).assertExists()

        // ---- 导入一个 .lua：自动放进场景并运行；它加的屏幕按钮会显示出来
        val lua = File(context.cacheDir, "我的按钮.lua")
        lua.writeText("ui.button('boom', '放烟花', {x = 0.5, y = 0.7})\nui.text('cap', '字幕在这里', {x = 0.5, y = 0.9})\nhits = 0\nfunction on_button(id, down) if id == 'boom' and down then hits = hits + 1; physics.gravity(0.25) end end")
        val latch = CountDownLatch(1)
        controller.importUris(listOf(Uri.fromFile(lua)), false) { r -> assertEquals(LibraryItem.TYPE_SCRIPT, r.items.single().type); controller.useLibraryFile(r.items[0].path, r.items[0].type); latch.countDown() }
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        await("脚本加进场景") { controller.state.value.play.scripts.any { it.name == "我的按钮" && it.running } }
        ui()
        rule.onNode(hasContentDescription("脚本按钮 放烟花")).assertExists()
        rule.onNodeWithText("字幕在这里").assertExists()
        shot("51_script_overlay")
        tapIcon("脚本按钮 放烟花")
        frames(2)
        assertEquals("按屏幕按钮应当调到脚本的 on_button", 0.25f, onGl { controller.scene.physics.gravity }, 1e-4f)

        // ---- 存盘，再用一个全新的控制器恢复：片段的摆放、脚本、物理参数、层都要回来
        controller.saveProjectNow()
        val c2 = newController()
        c2.restoreProject()
        await("恢复场景", c2) { c2.state.value.models.size == 1 && c2.loading.value.isEmpty() && c2.playback.value.maxFrame == 130 && c2.state.value.play.scripts.size == 1 }
        frames(3, c2)
        val st2 = c2.state.value
        assertEquals(listOf(0 to 20, 20 to 60, 110 to 130), st2.play.lanes.first { it.kind == Lanes.MODEL }.clips.map { it.start to it.end })
        assertEquals("我的按钮", st2.play.scripts[0].name)
        assertTrue(st2.play.scripts[0].running)
        assertEquals(2, st2.models[0].layer)
        assertEquals(1.5f, st2.play.physics["stiffness"]!!, 1e-4f)
        assertEquals("模型自己的物理倍数", 0.5f, st2.models[0].ownPhysics["gravity"]!!, 1e-4f)
        // 脚本运行时改的东西（重力 0.25）不会被存成永久的
        c2.play.setScriptEnabled(st2.play.scripts[0].id, false); frames(3, c2)
        assertEquals(1f, onGl { c2.scene.physics.gravity }, 1e-4f)
    }

    // ------------------------------------------------------------------ 4.1

    /** 一个真的能被系统认出来的 WAV（8000Hz 单声道 16 位），seconds 秒长。 */
    private fun writeWav(f: File, seconds: Float) {
        val bytes = (8000 * seconds).toInt() * 2
        val out = java.io.ByteArrayOutputStream()
        fun le32(v: Int) { out.write(v and 255); out.write(v shr 8 and 255); out.write(v shr 16 and 255); out.write(v shr 24 and 255) }
        fun le16(v: Int) { out.write(v and 255); out.write(v shr 8 and 255) }
        out.write("RIFF".toByteArray()); le32(36 + bytes); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(8000); le32(16000); le16(2); le16(16)
        out.write("data".toByteArray()); le32(bytes); out.write(ByteArray(bytes))
        f.parentFile?.mkdirs()
        f.writeBytes(out.toByteArray())
    }

    /** 测试机上没有真的解码器：告诉假的 MediaPlayer 每个文件有多长（按 WAV 头里写的算）。 */
    private fun fakeDecoder() {
        val field = org.robolectric.shadows.util.DataSource::class.java.getDeclaredField("dataSource").apply { isAccessible = true }
        org.robolectric.shadows.ShadowMediaPlayer.setMediaInfoProvider { ds ->
            val path = field.get(ds) as? String
            val secs = path?.let { com.lumi.mmd.engine.scene.AudioDeck.wavSeconds(File(it)) } ?: 4f
            org.robolectric.shadows.ShadowMediaPlayer.MediaInfo((secs * 1000).toInt(), 0)
        }
    }

    private fun audioLane() = controller.state.value.play.lanes.firstOrNull { it.kind == Lanes.AUDIO }?.clips ?: emptyList()

    /**
     * 4.0 在真机上的崩溃（maxWidth(-7) must be >= than minWidth(0)）：轨道标尺上某个刻度的数字
     * 正好落在画布最右边 3dp 之内时，drawText 按"右边还剩多宽"算出了负的宽度。
     * 把标尺在各种滚动位置、缩放下都画一遍，哪一种都不许抛异常。
     */
    private var oldWay = false

    /**
     * 确认下面那个测试不是摆设：同样的画法，换回 4.0 里的写法（drawText 直接给字符串和位置），
     * 字的起点一超出画布右边缘就抛出和真机上一模一样的异常。
     */
    @Test fun oldRulerWayCrashes() {
        oldWay = true
        var thrown: Throwable? = null
        try { tracksRulerNeverCrashes() } catch (e: Throwable) { thrown = e }
        val chain = generateSequence(thrown) { it.cause }.map { it.toString() }.toList()
        println("OLD_RULER_WAY: $chain")
        assertTrue("旧写法应当崩：$chain", chain.any { it.contains("maxWidth") })
    }

    @Test fun tracksRulerNeverCrashes() = guarded("ruler") {
        var scroll by mutableFloatStateOf(0f)
        var ppf by mutableFloatStateOf(3f)
        var widthPx = 0f
        rule.setContent {
            TestRoot {
                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                androidx.compose.foundation.Canvas(Modifier.size(300.dp, 22.dp)) {
                    widthPx = size.width
                    drawClipRuler(measurer, scroll, ppf, 12f, Color.Gray, Color.White, Color.Red)
                    // 字的起点贴着右边缘、正好在边缘上、甚至在画布外面：都只是被裁掉
                    for (dx in listOf(-0.5f, 0f, 1f, 7f, 500f)) drawLabel(measurer, "12", Offset(size.width + dx - 4f, size.height + dx - 4f), TextStyle(fontSize = 9.sp))
                    if (oldWay) drawText(measurer, "12", Offset(size.width + 7f, 0f))
                }
            }
        }
        rule.waitForIdle()
        capture(rule.activity.window)
        assertTrue(widthPx > 100f)
        // 让某一个刻度（每 step 帧一个）落在离右边缘 d 像素的地方：d 从 0.2 到 12（3dp 约 8 像素）
        var drawn = 0
        for (zoom in listOf(0.4f, 3f, 11f)) {
            var d = 0.2f
            while (d < 12f) {
                rule.runOnUiThread { ppf = zoom; scroll = 6000f - (widthPx - d) / zoom }
                rule.waitForIdle()
                capture(rule.activity.window)
                drawn++
                d += 0.9f
            }
        }
        assertTrue(drawn > 30)
    }

    /** 轨道面板本身：放大、缩小、跳到各处、播放着滚动，标尺和片段（含过渡的斜角）都要画得出来。 */
    @Test fun tracksPanelDrawsAtAnyZoom() = guarded("tracks_zoom") {
        start()
        skipTutorial()
        val imported = importZip()
        controller.loadModel(imported.items.first { it.name == "人形.gltf" }.path, ModelInstance.KIND_CHARACTER); settle()
        val vmd = imported.items.first { it.name == "おじぎ.vmd" }.path
        controller.applyMotion(vmd); settle()
        controller.play.appendAuto(vmd, -1); settle()
        mode("轨道")
        fun lane() = controller.state.value.play.lanes.first { it.kind == Lanes.MODEL }
        assertEquals(listOf(0 to 60, 60 to 120), lane().clips.map { it.start to it.end })

        // ---- 过渡。这个动作是"弯下去再直起来"（第 30 帧弯得最深）：把第一段裁到第 30 帧，第二段接在后面，
        //      接缝处就是从弯着一下子跳回直立
        val first = lane().clips[0].id; val second = lane().clips[1].id
        controller.play.dragClip(Lanes.MODEL, lane().id, first, move = false, start = null, end = 30, begin = true); ui()
        controller.play.dragClip(Lanes.MODEL, lane().id, second, move = true, start = 30, end = null, begin = true); ui()
        assertEquals(listOf(0 to 30, 30 to 90), lane().clips.map { it.start to it.end })
        val m = model()
        val head = m.skeleton!!.bones.indexOfFirst { it.name == "head" }
        val a = FloatArray(3); val b = FloatArray(3); val c0 = FloatArray(3)
        fun dist(p: FloatArray, q: FloatArray) = kotlin.math.sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]) + (p[2] - q[2]) * (p[2] - q[2]))
        controller.seekFrame(29); frames(2); onGl { m.boneWorldPosition(head, a, 0) }
        controller.seekFrame(30); frames(2); onGl { m.boneWorldPosition(head, c0, 0) }
        assertTrue("不过渡：接缝处头的位置一下子跳了 ${dist(a, c0)}", dist(a, c0) > 0.05f)
        // 第一段前面没有东西，不能设过渡；选中第二段才行
        controller.play.selectClip(Lanes.MODEL, lane().id, second); ui()
        assertTrue(lane().clips[1].canFade && !lane().clips[0].canFade)
        tapText("过渡"); tapText("0.5 秒")
        assertEquals(15, lane().clips[1].fade)
        rule.onNode(hasContentDescription("片段信息")).assertTextContains("过渡 15 帧", substring = true)
        controller.seekFrame(30); frames(2); onGl { m.boneWorldPosition(head, b, 0) }
        assertTrue("加了过渡之后接缝处连着：${dist(a, b)} 对 ${dist(a, c0)}", dist(a, b) < dist(a, c0) * 0.2f)
        shot("60_tracks_fade")
        tapIcon("撤销")
        assertEquals("过渡可以撤销", 0, lane().clips[1].fade)
        tapText("过渡"); tapText("1 秒")
        assertEquals(30, lane().clips[1].fade)
        // 存盘再打开，过渡还在
        controller.saveProjectNow()
        val c2 = newController(); c2.restoreProject(); settle(c2)
        await("片段恢复", c2) { onGl { c2.scene.models.firstOrNull()?.clips?.size == 2 } }
        assertEquals(listOf(0, 30), onGl { c2.scene.models.first().clips.map { it.fadeIn } })

        // ---- 各种缩放和位置都画一遍（4.0 就是在这里崩的）
        var drawn = 0
        for (step in 0 until 9) {
            tapText(if (step < 5) "放大" else "缩小")
            for (f in listOf(0, 7, 33, 59, 60, 61, 88, 119, 120)) {
                controller.seekFrame(f); ui()
                capture(rule.activity.window); drawn++
            }
        }
        tapText("全览")
        capture(rule.activity.window)
        assertTrue(drawn >= 81)
    }

    /** 资源库：导入的东西不光能用，也能删（在挑文件的那一页和资源库页都行）；模型连贴图一起删。 */
    @Test fun libraryItemsCanBeDeleted() = guarded("library_delete") {
        start()
        skipTutorial()
        val imported = importZip()
        val root = controller.library.root
        // 再放一个"只有一个模型和它的贴图"的包（真机上这就是导入一个角色 zip 之后的样子）
        val solo = File(root, "初音").also { File(it, "tex").mkdirs() }
        val gltf = imported.items.first { it.name == "人形.gltf" }
        File(gltf.path).copyTo(File(solo, "初音.gltf"))
        File(solo, "tex/face.png").writeBytes(ByteArray(2048))
        File(solo, "tex/body.png").writeBytes(ByteArray(4096))
        controller.notifyLibraryChanged()
        controller.loadModel(File(solo, "初音.gltf").path, ModelInstance.KIND_CHARACTER); settle()
        controller.applyMotion(imported.items.first { it.name == "おじぎ.vmd" }.path); settle()
        assertEquals(1, controller.state.value.models.size)
        assertEquals(60, controller.playback.value.maxFrame)

        // ---- 角色 → 添加模型：这一页列着导入过的模型，每个右边有垃圾桶
        menu("角色", "添加模型")
        waitForText("初音.gltf")
        rule.onNodeWithText("从手机里选文件").assertExists()
        rule.onNodeWithText("选整个文件夹").assertExists()
        rule.onNode(hasContentDescription("删除 初音.gltf")).assertExists()
        rule.onNode(hasContentDescription("删除 人形.gltf")).assertExists()
        shot("61_pick_with_delete")
        tapIcon("删除 初音.gltf")
        rule.onNodeWithText("删除「初音.gltf」？").assertExists()
        rule.onNode(hasText("会把这个模型和它的贴图从应用的资源库里删掉", substring = true)).assertExists()
        shot("62_delete_confirm")
        rule.onNodeWithText("取消").performClick(); ui()
        assertTrue("取消就什么都不删", File(solo, "初音.gltf").isFile)
        tapIcon("删除 初音.gltf")
        rule.onNodeWithText("删除").performClick()
        await("模型和贴图一起删掉") { !solo.exists() }
        await("场景里用着它的模型一起移除") { ui(); controller.state.value.models.isEmpty() }
        assertTrue("别的包不受影响", File(gltf.path).isFile)
        waitForText("人形.gltf")
        await("列表里也没有它了") { rule.waitForIdle(); rule.onAllNodesWithText("初音.gltf").fetchSemanticsNodes().isEmpty() }

        // 同一个包里还有别的文件（动作、姿势）：只删模型文件本身
        tapIcon("删除 人形.gltf")
        rule.onNode(hasText("同一个包里还有", substring = true)).assertExists()
        rule.onNodeWithText("删除").performClick()
        await("只删这一个文件") { !File(gltf.path).exists() }
        assertTrue(File(imported.items.first { it.name == "おじぎ.vmd" }.path).isFile)

        // ---- 设置 → 资源库：每一行也有垃圾桶；删掉正在用的音乐，音乐轨道上的那一段跟着没了
        fakeDecoder()
        val wav = File(root, "音乐/bgm.wav").also { writeWav(it, 4f) }
        controller.notifyLibraryChanged()
        controller.setAudio(wav.path)
        await("音乐放上轨道") { ui(); audioLane().size == 1 }
        menu("设置", "资源库")
        waitForText("bgm.wav", lazy = true)
        assertTrue("资源库页不再重复放导入按钮", rule.onAllNodesWithText("导入文件 / zip").fetchSemanticsNodes().isEmpty())
        rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasContentDescription("删除 bgm.wav"))
        tapIcon("删除 bgm.wav")
        rule.onNodeWithText("删除").performClick()
        await("音乐文件删掉") { !wav.exists() }
        await("轨道上的那一段跟着没了") { ui(); audioLane().isEmpty() }
        assertEquals(0, controller.playback.value.maxFrame)
    }

    /** 音乐轨道：可以放好几段，每一段能挪、能裁、能切、能复制、能删、能撤销，存盘后还在；旧工程里的音乐照样读得出来。 */
    @Test fun musicClipsOnTheTrack() = guarded("music") {
        fakeDecoder()
        start()
        skipTutorial()
        val root = controller.library.root
        val song = File(root, "音乐/song.wav").also { writeWav(it, 4f) }       // 120 帧
        val jingle = File(root, "音乐/jingle.wav").also { writeWav(it, 1f) }   // 30 帧
        controller.notifyLibraryChanged()

        // 菜单 → 动作与音乐 → 音乐：点这一行去挑（行尾不再有第二个"选文件"的箭头）
        menu("动作与音乐")
        assertTrue(rule.onAllNodes(hasContentDescription("选择音乐文件")).fetchSemanticsNodes().isEmpty())
        tapIcon("选择音乐")
        waitForText("song.wav")
        tapText("song.wav")
        await("音乐放上轨道") { ui(); audioLane().size == 1 }
        assertEquals(listOf(0 to 120), audioLane().map { it.start to it.end })
        assertEquals("song.wav", controller.state.value.audioName)
        assertEquals(120, controller.playback.value.maxFrame)
        closeSheet()

        // ---- 轨道里：选中、在第 40 帧切一刀、把后一段挪到第 200 帧、裁掉它的开头
        mode("轨道")
        rule.onNode(hasContentDescription("轨道 音乐")).assertExists()
        tapIcon("片段 song.wav")
        rule.onNode(hasContentDescription("片段开头")).assertExists()          // 音乐片段现在也有裁剪的把手
        controller.seekFrame(40); ui()
        tapText("分割")
        assertEquals(listOf(0 to 40, 40 to 120), audioLane().map { it.start to it.end })
        assertEquals(40, audioLane()[1].trimIn)
        val second = audioLane()[1].id
        controller.play.dragClip(Lanes.AUDIO, 0, second, move = true, start = 200, end = null, begin = true); ui()
        assertEquals(listOf(0 to 40, 200 to 280), audioLane().map { it.start to it.end })
        assertEquals(280, controller.playback.value.maxFrame)
        controller.play.dragClip(Lanes.AUDIO, 0, second, move = false, start = 220, end = null, begin = true); ui()
        assertEquals(220 to 280, audioLane()[1].let { it.start to it.end })
        assertEquals("裁掉开头 20 帧：从这首歌的第 60 帧起", 60, audioLane()[1].trimIn)
        rule.onNode(hasContentDescription("片段信息")).assertTextContains("从这首歌的", substring = true)
        // 结尾不能拉得比文件长
        controller.play.dragClip(Lanes.AUDIO, 0, second, move = false, start = null, end = 900, begin = true); ui()
        assertEquals(280, audioLane()[1].end)
        // 挪到别的片段身上：不会重叠，贴到旁边
        controller.play.dragClip(Lanes.AUDIO, 0, second, move = true, start = 10, end = null, begin = true); ui()
        assertEquals(listOf(0 to 40, 40 to 100), audioLane().map { it.start to it.end })
        tapIcon("撤销")
        assertEquals("撤销挪动", 220 to 280, audioLane()[1].let { it.start to it.end })
        shot("63_music_clips")
        // 复制、删除
        tapText("复制")
        assertEquals(3, audioLane().size)
        assertEquals(280 to 340, audioLane()[2].let { it.start to it.end })
        tapText("删除")
        assertEquals(2, audioLane().size)

        // ---- 再接一首别的（轨道的「添加」→ 同一页里挑，接在最后）
        rule.onAllNodesWithText("添加").onLast().performClick(); ui()
        tapText("动作、镜头、音乐（接在后面）")
        waitForText("jingle.wav")
        rule.onNode(hasText("音乐 · 音乐", substring = true) and hasText("jingle.wav")).assertExists()
        tapText("jingle.wav")
        await("第二首接在最后") { ui(); audioLane().size == 3 }
        assertEquals(listOf("song.wav", "song.wav", "jingle.wav"), audioLane().map { it.name })
        assertEquals(280 to 310, audioLane()[2].let { it.start to it.end })
        assertEquals("song.wav 等 3 段", controller.state.value.audioName)
        closeSheet()

        // ---- 按播放、暂停：不崩；时间线停着的时候播放器被准备到当前位置
        controller.seekFrame(5); ui()
        controller.togglePlay(); frames(6, sleepMs = 10)
        controller.pause(); frames(3); ui()

        // ---- 存盘再打开：三段原样回来
        val before = audioLane().map { Triple(it.name, it.start to it.end, it.trimIn) }
        controller.saveProjectNow()
        val c2 = newController(); c2.restoreProject(); settle(c2)
        await("音乐轨道恢复", c2) { onGl { c2.scene.audioClips.size } == 3 }
        assertEquals(before, onGl { c2.scene.audioClips.map { Triple(it.name, it.start to it.end, it.trimIn) } })
        // 播放状态最多 0.1 秒发布一次：等它发布出来再看（直接读可能读到恢复之前的那一份）
        await("总长度发布出来", c2, timeoutMs = 10_000) { c2.playback.value.maxFrame == 310 }
        assertEquals(310, c2.playback.value.maxFrame)

        // ---- 4.0 存的工程只有一个 "audio"（整首从头放）：照样读得出来
        val project = File(context.filesDir, "project.json")
        val json = project.readText()
        val old = json.replace(Regex("\"audioClips\":\\[.*?\\],\"audio\""), "\"audio\"")
        assertTrue("测试自己的前提：把新格式的那一项去掉了", old != json && !old.contains("audioClips") && old.contains("\"audio\":\""))
        project.writeText(old)
        val c3 = newController(); c3.restoreProject(); settle(c3)
        await("旧工程的音乐", c3) { onGl { c3.scene.audioClips.size } == 1 }
        assertEquals(Triple("song.wav", 0 to 120, 0), onGl { c3.scene.audioClips[0].let { Triple(it.name, it.start to it.end, it.trimIn) } })

        // ---- 清空：菜单里音乐槽的叉
        menu("动作与音乐")
        tapIcon("清空音乐")
        assertTrue(audioLane().isEmpty())
        closeSheet()
        tapIcon("撤销")
        assertEquals("清空也能撤销", 3, audioLane().size)
        assertTrue(jingle.isFile)
    }

    /** 写代码的框：有颜色、有补全条、有符号键，回车后自动缩进。 */
    @Test fun codeBoxCompletesAndColours() = guarded("code_box") {
        start()
        skipTutorial()
        val imported = importZip()
        controller.loadModel(imported.items.first { it.name == "人形.gltf" }.path, ModelInstance.KIND_CHARACTER); settle()
        menu("脚本与特效")
        rule.onNode(hasText("让 AI 直接写进来并运行", substring = true)).assertExists()
        tapIcon("添加示例 呼吸和眨眼")
        frames(5)
        ui()
        tapText("呼吸和眨眼")
        await("读出脚本代码") { rule.waitForIdle(); rule.onAllNodes(hasContentDescription("脚本代码")).fetchSemanticsNodes().isNotEmpty() }
        val box = rule.onNode(hasContentDescription("脚本代码"))
        // 没在输入时不显示补全条
        assertTrue(rule.onAllNodes(hasContentDescription("代码补全")).fetchSemanticsNodes().isEmpty())
        shot("64_code_coloured")

        // 敲 "sc"：补全条上有 scene；点一下补完
        box.performTextReplacement("")
        box.performTextInput("local m = sc")
        rule.waitForIdle()
        rule.onNode(hasContentDescription("代码补全")).assertExists()
        rule.onNode(hasContentDescription("补全 scene")).assertExists()
        tapIcon("补全 scene")
        box.assertTextEquals("local m = scene")
        // 敲一个点：列出 scene 里的东西；点 main，括号补上、光标停在括号里
        box.performTextInput(".m")
        rule.waitForIdle()
        rule.onNode(hasContentDescription("补全 main")).assertExists()
        rule.onNode(hasContentDescription("补全 models")).assertExists()
        shot("65_code_completion")
        tapIcon("补全 main")
        box.assertTextEquals("local m = scene.main()")
        box.performTextInput("x")
        box.assertTextEquals("local m = scene.main(x)")
        // 符号键：手机键盘上不好找的那些；括号成对
        box.performTextReplacement("print")
        tapIcon("输入 (")
        tapIcon("输入 \"")
        box.performTextInput("hi")
        box.assertTextEquals("print(\"hi\")")
        // 一行开头敲 fu：给出整段的 function update(dt) … end
        box.performTextReplacement("")
        box.performTextInput("fu")
        rule.waitForIdle()
        tapIcon("补全 function update(dt) … end")
        box.assertTextEquals("function update(dt)\n    \nend")
        box.performTextInput("m:bo")
        rule.waitForIdle()
        tapIcon("补全 bone")
        box.assertTextEquals("function update(dt)\n    m:bone()\nend")
        // 回车：新的一行跟着上一行缩进
        box.performTextReplacement("function update(dt)")
        box.performTextInput("\n")
        box.assertTextEquals("function update(dt)\n    ")
        box.performTextInput("local x = 1")
        box.performTextInput("\n")
        box.assertTextEquals("function update(dt)\n    local x = 1\n    ")
        // 字符串和注释里不出补全
        box.performTextReplacement("")
        box.performTextInput("-- sc")
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasContentDescription("补全 scene")).fetchSemanticsNodes().isEmpty())
        rule.onNode(hasContentDescription("输入 (")).assertExists()          // 符号键一直在

        // 写一段能跑的，保存并运行
        box.performTextReplacement("local m = scene.main()\nfunction update(dt)\n  m.opacity = 0.4 -- 半透明\nend\nprint(\"好\")")
        tapText("保存并运行")
        frames(6); ui()
        val mm = model()
        assertEquals(0.4f, onGl { mm.opacity }, 1e-4f)
        shot("66_code_running")
        tapIcon("结束输入")
        assertTrue(rule.onAllNodes(hasContentDescription("代码补全")).fetchSemanticsNodes().isEmpty())
    }

    /** AI 通过应用自己的 MCP 服务写脚本、改脚本、运行脚本、立刻执行一段 Lua；写进来的脚本重启后还在。 */
    @Test fun mcpWritesAndRunsLua() = guarded("mcp_lua") {
        fakeDecoder()
        start()
        skipTutorial()
        val imported = importZip()
        controller.loadModel(imported.items.first { it.name == "人形.gltf" }.path, ModelInstance.KIND_CHARACTER); settle()
        menu("AI 操作")
        // 面板上写明 AI 能写脚本并运行
        rule.onNode(hasContentDescription("AI 能做什么")).assertExists()
        rule.onNode(hasText("写 Lua 脚本并运行", substring = true)).assertExists()
        shot("67_mcp_panel_lua")
        rule.onNodeWithText("开启 MCP 服务").performClick()
        await("MCP 启动") { controller.mcp.value.running }
        rule.waitForIdle()
        val url = "http://127.0.0.1:" + controller.mcp.value.urls[0].substringAfter("://").substringAfter(":")
        val init = http(url, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        assertTrue(init, init.contains("WRITE and RUN Lua scripts") && init.contains("script_write"))
        val list = http(url, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        for (t in listOf("script_api", "script_write", "script_edit", "script_run", "script_stop", "script_read", "script_list", "lua_eval", "script_log", "advance", "clips")) assertTrue("工具清单里应当有 $t", list.contains("\"$t\""))
        assertTrue(mcpCall(url, "script_api").contains("scene.main()"))

        // ---- 写一个脚本：马上在跑，输出带了回来，界面上的脚本列表里也有了
        val w = mcpCall(url, "script_write", """{"name":"AI 写的","code":"local m = scene.main()\nprint('你好', m.name)\nfunction update(dt)\n  m.opacity = 0.5\nend\n","screenshot":false}""")
        assertTrue(w, w.contains("\"running\":true") && w.contains("你好") && w.contains("脚本/AI 写的.lua"))
        frames(3); ui()
        val m = model()
        assertEquals(0.5f, onGl { m.opacity }, 1e-4f)
        assertEquals(listOf("AI 写的"), controller.state.value.play.scripts.map { it.name })
        assertTrue(controller.state.value.play.scripts[0].running)
        val file = File(controller.library.root, "脚本/AI 写的.lua")
        assertTrue(file.isFile)
        assertEquals("工程里记的是完整路径", file.path, onGl { controller.runtime.scripts.scripts[0].file })

        // ---- 语法错：什么都不动；运行时出错：isError、带行号，脚本停下、改动复原
        var bad = http(url, """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"script_write","arguments":{"name":"AI 写的","code":"local = 1"}}}""")
        assertTrue(bad, bad.contains("\"isError\":true") && bad.contains("nothing was changed"))
        assertEquals(0.5f, onGl { m.opacity }, 1e-4f)
        bad = http(url, """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"script_write","arguments":{"name":"坏的","code":"function update(dt)\n  local b = nil\n  b:rotate(1,2,3)\nend"}}}""")
        assertTrue(bad, bad.contains("\"isError\":true") && bad.contains("running") && bad.contains("3"))

        // ---- 只改一处
        val e = mcpCall(url, "script_edit", """{"script":"AI 写的","find":"m.opacity = 0.5","replace":"m.opacity = 0.2"}""")
        assertTrue(e, e.contains("\"replaced\":1") && e.contains("\"running\":true"))
        frames(3)
        assertEquals(0.2f, onGl { m.opacity }, 1e-4f)
        assertTrue(file.readText().contains("0.2"))

        // ---- 立刻执行一段 Lua
        val ev = mcpCall(url, "lua_eval", """{"code":"return scene.main().name, #scene.main():bones()"}""")
        assertTrue(ev, ev.contains("=>") && ev.contains(onGl { m.name }))
        bad = http(url, """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"lua_eval","arguments":{"code":"local x = nil; return x.y"}}}""")
        assertTrue(bad, bad.contains("\"isError\":true"))
        mcpCall(url, "lua_eval", """{"reset":true}""")

        // ---- 停下 / 再运行
        val st = mcpCall(url, "script_stop", """{"script":"AI 写的"}""")
        assertTrue(st, st.contains("\"stopped\":[\"AI 写的\"]"))
        frames(2)
        assertEquals(1f, onGl { m.opacity }, 1e-4f)
        val run = mcpCall(url, "script_run", """{"script":"AI 写的","seconds":0.2}""")
        assertTrue(run, run.contains("\"running\":true") && run.contains("你好"))
        assertEquals(0.2f, onGl { m.opacity }, 1e-4f)
        mcpCall(url, "script_set", """{"script":"坏的","delete":true}""")

        // ---- 轨道：给第二段动作加过渡；往音乐轨道上放一段
        val vmd = File(imported.items.first { it.name == "おじぎ.vmd" }.path).relativeTo(controller.library.root).path
        mcpCall(url, "clips", """{"action":"add","track":${m.id},"path":"$vmd"}""")
        val added = mcpCall(url, "clips", """{"action":"add","track":${m.id},"path":"$vmd","fade":12}""")
        assertTrue(added, added.contains("\"fade\":12"))
        writeWav(File(controller.library.root, "音乐/ai.wav"), 2f)
        val music = mcpCall(url, "clips", """{"action":"add","track":"music","path":"音乐/ai.wav","at":30}""")
        assertTrue(music, music.contains("\"start\":30") && music.contains("\"end\":90"))
        ui()
        assertEquals(listOf(30 to 90), audioLane().map { it.start to it.end })
        closeSheet()
        tapIcon("撤销")
        assertTrue("AI 放上去的音乐用户可以撤销", audioLane().isEmpty())

        // ---- 关掉应用再打开：AI 写的脚本还在，而且在跑（4.0 里记的是相对路径，重启后脚本就找不回来了）
        controller.saveProjectNow()
        val c2 = newController(); c2.restoreProject(); settle(c2)
        await("脚本恢复", c2) { c2.state.value.play.scripts.size == 1 && c2.state.value.models.isNotEmpty() }
        frames(4, c2)
        assertEquals("AI 写的", c2.state.value.play.scripts[0].name)
        assertTrue(c2.state.value.play.scripts[0].running)
        assertEquals(0.2f, onGl { c2.scene.models.first().opacity }, 1e-4f)
    }

    /**
     * 部件：位移 / 旋转 / 大小是滑杆，两头有加减号，点数值能直接输入；部件能删除，
     * 删了能撤销、能一键恢复、存盘后还是删掉的；另存为 PMX 时真的从模型里拿掉。
     */
    @Test fun partsSlidersAndDelete() = guarded("parts") {
        start()
        skipTutorial()
        val imported = importZip()
        val path = imported.items.first { it.name == "人形.gltf" }.path
        controller.loadModel(path, ModelInstance.KIND_CHARACTER)
        settle()
        val tris = onGl { controller.scene.models[0].data.triangleCount }
        mode("部件")
        await("部件列表") { controller.detail.value.parts.size == 2 }
        val body = controller.detail.value.parts[0]
        val face = controller.detail.value.parts[1]
        assertEquals("body", body.name); assertEquals("face", face.name)
        fun part(key: String) = onGl { controller.scene.models[0].parts.findByKey(key)!! }

        // 点一行：这一行下面展开滑杆
        rule.onAllNodesWithText("body").onLast().performClick(); ui()
        await("选中部件") { controller.state.value.selectedPart == body.key }
        ui()
        // 加减号：一下走 0.05，落在整数倍上
        tapIcon("位移 X 加"); tapIcon("位移 X 加")
        await("加号") { kotlin.math.abs(part(body.key).offset[0] - 0.10f) < 1e-4f }
        tapIcon("位移 X 减")
        await("减号") { kotlin.math.abs(part(body.key).offset[0] - 0.05f) < 1e-4f }
        // 滑杆
        rule.onNode(hasContentDescription("位移 Y")).performSemanticsAction(SemanticsActions.SetProgress) { it(2.5f) }; ui()
        await("滑杆") { kotlin.math.abs(part(body.key).offset[1] - 2.5f) < 1e-3f }
        // 点数值直接输入：超出滑杆量程的数也可以，量程跟着放大
        tapIcon("位移 Z 的数值")
        // 自带的小键盘（不弹系统输入法）：3 7 . 5，再按正负号
        for (k in listOf("3", "7", "小数点", "5", "正负")) { rule.onNode(hasContentDescription("数字键 $k")).performClick(); rule.waitForIdle() }
        assertEquals("-37.5", rule.onNode(hasContentDescription("输入的数")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text })
        shot("69_number_pad")
        rule.onNode(hasContentDescription("确定")).performClick(); ui()
        await("输入") { kotlin.math.abs(part(body.key).offset[2] + 37.5f) < 1e-3f }
        ui()
        val range = rule.onNode(hasContentDescription("位移 Z")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)!!.range
        assertTrue("量程应当放大到装得下 -37.5：$range", range.start <= -37.5f && range.endInclusive >= 37.5f)
        // 拖了这么多下，撤销只有寥寥几步（同一根滑杆连续动只记一次）
        shot("70_part_sliders")
        // 旋转、大小
        rule.onAllNodesWithText("旋转").onLast().performClick(); ui()
        tapIcon("绕 Y 转 加")
        await("旋转") { !com.lumi.mmd.engine.math.Quat.isIdentity(part(body.key).rotation, 0) }
        await("旋转回显") { controller.detail.value.parts.first { it.key == body.key }.rotation.let { kotlin.math.abs(it[1] - 1f) < 0.05f && kotlin.math.abs(it[0]) < 0.05f } }
        rule.onAllNodesWithText("大小").onLast().performClick(); ui()
        rule.onNode(hasContentDescription("整体大小")).performSemanticsAction(SemanticsActions.SetProgress) { it(kotlin.math.log10(2f)) }; ui()
        await("大小") { part(body.key).scale.all { kotlin.math.abs(it - 2f) < 1e-2f } }
        tapIcon("只改 Y 方向 的数值")
        // 打开时是现在的值（2）；退格删掉，重新输 0.5
        for (k in listOf("退格", "小数点", "5")) { rule.onNode(hasContentDescription("数字键 $k")).performClick(); rule.waitForIdle() }
        rule.onNode(hasContentDescription("确定")).performClick(); ui()
        await("单方向") { part(body.key).scale.let { kotlin.math.abs(it[1] - 0.5f) < 1e-3f && kotlin.math.abs(it[0] - 2f) < 1e-2f } }
        shot("71_part_scale")
        // 复位
        tapText("复位")
        await("复位") { !part(body.key).isEdited }

        // 模型本身的位置也是同一套滑杆
        mode("模型")
        tapIcon("位置 X 加")
        await("模型位置") { kotlin.math.abs(onGl { controller.scene.models[0].position[0] } - 0.1f) < 1e-4f }
        tapIcon("位置 X 减")
        rule.onAllNodesWithText("大小").onLast().performClick(); ui()
        tapIcon("缩放 的数值")
        rule.onNode(hasContentDescription("数字键 3")).performClick(); rule.waitForIdle()
        rule.onNode(hasContentDescription("确定")).performClick(); ui()
        await("模型大小") { kotlin.math.abs(onGl { controller.scene.models[0].scale } - 3f) < 1e-3f }
        shot("72_model_sliders")
        controller.setModelScale(controller.state.value.models[0].id, 1f)

        // ---- 删除
        mode("部件")
        await("部件列表") { controller.detail.value.parts.size == 2 }
        tapIcon("删除部件 face")
        await("删除") { controller.detail.value.parts.size == 1 && controller.detail.value.deletedParts == 1 }
        assertTrue(onGl { val m = controller.scene.models[0]; m.parts.findByKey(face.key)!!.deleted && !m.materials[face.material].visible })
        waitForText("已删除 1 个部件", lazy = true)
        shot("73_part_deleted")
        // 撤销找回来
        tapIcon("撤销")
        await("撤销删除") { controller.detail.value.parts.size == 2 && controller.detail.value.deletedParts == 0 }
        assertTrue(onGl { controller.scene.models[0].materials[face.material].visible })
        // 再删，用「恢复」找回来
        tapIcon("删除部件 face")
        await("删除") { controller.detail.value.deletedParts == 1 }
        tapText("恢复")
        await("恢复") { controller.detail.value.parts.size == 2 && controller.detail.value.deletedParts == 0 }
        // 再删；最后一个部件删不掉
        tapIcon("删除部件 face")
        await("删除") { controller.detail.value.deletedParts == 1 }
        tapIcon("删除部件 body")
        frames(3)
        assertEquals(1, controller.detail.value.parts.size)
        assertTrue(onGl { !controller.scene.models[0].parts.findByKey(body.key)!!.deleted })

        // 存盘再恢复：还是删掉的
        mode("浏览")
        frames(2)
        controller.saveProjectNow()
        val c2 = newController(); c2.restoreProject()
        await("恢复场景", c2) { c2.state.value.models.size == 1 && c2.loading.value.isEmpty() }
        assertTrue(onGl { val m = c2.scene.models[0]; m.parts.findByKey(face.key)!!.deleted && !m.materials[face.material].visible && !m.parts.findByKey(body.key)!!.deleted })

        // 另存为 PMX：文件里真的没有这个部件了，场景里的模型也换成了合并后的
        controller.selectModel(controller.state.value.models[0].id); frames(2)
        controller.saveModelAs("人形_无脸")
        val out = File(File(path).parentFile, "人形_无脸.pmx")
        await("另存为") { out.isFile && onGl { controller.scene.models[0].data.materials.size == 1 } }
        val saved = com.lumi.mmd.engine.format.ModelLoader.loadModel(out)
        assertEquals(1, saved.materials.size)
        assertEquals("body", saved.materials[0].name)
        assertTrue("面数应当变少：${saved.triangleCount} / $tris", saved.triangleCount in 1 until tris)
        assertEquals("骨骼还在", onGl { controller.scene.models[0].data.bones.size }, saved.bones.size)
        mode("部件")
        await("合并之后") { controller.detail.value.parts.size == 1 && controller.detail.value.deletedParts == 0 }
    }

    /** 引擎测试资源里那几个真 Blender 存出来的 .blend（各版本存的是同一个场景）。 */
    private fun blendResource(name: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "engine/src/test/resources/blend/$name")
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("找不到测试用的 $name")
    }

    /**
     * 直接导入 Blender 的工程文件：zip 里一个 .blend 加它的贴图文件夹 → 当成舞台加载 → 里面的物体一个一个列在「部件」里，
     * 可以单独挑出来调、删。只选了一个 .blend（贴图没跟来）时，知道缺的是哪张外部贴图。
     */
    @Test fun blendSceneImports() = guarded("blend") {
        start()
        skipTutorial()
        val zip = File(context.cacheDir, "舞台.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("stage/room.blend")); z.write(blendResource("b53_z.blend").readBytes()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("stage/textures/checker.png")); z.write(blendResource("textures/checker.png").readBytes()); z.closeEntry()
        }
        val latch = CountDownLatch(1)
        var result: ImportResult? = null
        controller.importUris(listOf(Uri.fromFile(zip)), false) { result = it; latch.countDown() }
        assertTrue("导入超时", latch.await(60, TimeUnit.SECONDS))
        settle()
        val item = result!!.items.firstOrNull { it.name == "room.blend" } ?: throw AssertionError("资源库里应当有 room.blend：${result!!.items.map { it.name }}")
        assertEquals("归在「模型 / 场景」里", LibraryItem.TYPE_OTHER_MODEL, item.type)
        // 不指定类别：按大小自动当成舞台
        controller.loadModel(item.path)
        await("加载 .blend") { controller.state.value.models.size == 1 && controller.loading.value.isEmpty() }
        val m = controller.state.value.models[0]
        assertEquals(ModelInstance.KIND_STAGE, m.kind)
        assertEquals("贴图都在：外部那张跟着 zip 来了，打包的那张在 .blend 里", 0, m.missingTextures)
        assertEquals(655, onGl { controller.scene.models[0].data.triangleCount })
        assertTrue("读不了的东西要说出来：${m.warnings}", m.warnings.any { it.contains("表面细分") } && m.warnings.any { it.contains("文字") })
        assertTrue(m.summary, m.summary.contains("Blender"))
        // 菜单里这个模型的那一页：能看到提醒
        menu("角色", m.name)
        waitForText("· " + m.warnings[0])
        shot("74_blend_model_page")
        closeSheet()
        // 「部件」里是 Blender 里的一个个物体
        controller.selectModel(m.id)
        mode("部件")
        await("部件列表") { controller.detail.value.parts.size == 19 }
        val names = controller.detail.value.parts.map { it.name }
        assertTrue("$names", names.containsAll(listOf("Floor", "Cube · Red", "Cube · Blue", "Ball", "PropCube", "ArrayPost")))
        shot("75_blend_parts")
        // 删掉其中一个物体
        controller.deletePart(controller.detail.value.parts.first { it.name == "SubsurfBox" }.key)
        await("删除") { controller.detail.value.parts.size == 18 && controller.detail.value.deletedParts == 1 }
        mode("浏览")

        // 只带来一个 .blend：缺的是那张外部贴图（打包的不算缺）
        val lone = File(context.cacheDir, "lone.blend").also { it.writeBytes(blendResource("b279_z.blend").readBytes()) }
        val latch2 = CountDownLatch(1)
        var result2: ImportResult? = null
        controller.importUris(listOf(Uri.fromFile(lone)), false) { result2 = it; latch2.countDown() }
        assertTrue("导入超时", latch2.await(60, TimeUnit.SECONDS))
        settle()
        val item2 = result2!!.items.first { it.name == "lone.blend" }
        controller.loadModel(item2.path)
        await("加载第二个 .blend") { controller.state.value.models.size == 2 && controller.loading.value.isEmpty() }
        assertEquals(1, controller.state.value.models[1].missingTextures)
    }
}

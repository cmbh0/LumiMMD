package com.lumi.mmd.engine

import com.lumi.mmd.engine.anim.Motion
import com.lumi.mmd.engine.anim.MotionClip
import com.lumi.mmd.engine.control.CharacterController
import com.lumi.mmd.engine.control.Locomotion
import com.lumi.mmd.engine.control.Rig
import com.lumi.mmd.engine.format.ModelLoader
import com.lumi.mmd.engine.fx.EffectTextures
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.render.RenderSettings
import com.lumi.mmd.engine.scene.ClipOps
import com.lumi.mmd.engine.scene.KeyOps
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.scene.Scene
import com.lumi.mmd.engine.scene.SceneRuntime
import com.lumi.mmd.engine.scene.SyncClock
import com.lumi.mmd.engine.script.Script
import com.lumi.mmd.engine.script.ScriptCallbacks
import com.lumi.mmd.engine.script.ScriptLibrary
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 第四版新增的部分：音乐同步时钟、片段轨道、Lua 脚本和特效、操控模式、物理调参。都不需要 GPU。 */
class PlayTest {
    private val assets: File? = System.getenv("LUMI_TEST_ASSETS")?.let { File(it) }?.takeIf { it.isDirectory }

    private class Host : ScriptCallbacks {
        val scene = Scene()
        val log = ArrayList<String>()
        val errors = ArrayList<String>()
        val texts = HashMap<String, String>()
        val buttons = HashMap<String, String>()
        val runtime = SceneRuntime(scene, RenderSettings(), this)
        override fun scriptLog(script: Script?, text: String, error: Boolean) { if (error) errors.add(text) else log.add(text) }
        override fun uiText(owner: Int, id: String, text: String?, x: Float, y: Float, size: Float, color: Int) { if (text == null) texts.remove(id) else texts[id] = text }
        override fun uiButton(owner: Int, id: String, label: String?, x: Float, y: Float) { if (label == null) buttons.remove(id) else buttons[id] = label }
        override fun uiClear(owner: Int) { texts.clear(); buttons.clear() }
        override fun setPlaying(on: Boolean) { scene.playing = on }
        override fun seekSeconds(seconds: Float) { scene.seekSeconds(seconds) }
        override fun addModel(data: ModelData, kind: Int): ModelInstance { val m = ModelInstance(data, kind); scene.add(m); return m }
        override fun removeModel(model: ModelInstance) { scene.remove(model) }
        fun run(seconds: Float) { var t = 0f; while (t < seconds - 1e-4f) { runtime.update(1f / 60f); t += 1f / 60f } }
        fun start(name: String, code: String): Script {
            val s = runtime.scripts.add(name, code)
            runtime.scripts.setEnabled(s, true)
            runtime.update(1f / 60f)
            return s
        }
    }

    private fun realModel(h: Host): ModelInstance {
        val f = assets?.let { File(it, "model.pmx") }?.takeIf { it.isFile }
        assumeTrue("没有真实 PMX（LUMI_TEST_ASSETS）", f != null)
        val m = ModelInstance(ModelLoader.loadModel(f!!), ModelInstance.KIND_CHARACTER)
        h.scene.add(m)
        return m
    }

    // ------------------------------------------------------------------ 音乐同步

    /**
     * 模拟一台"报的位置很糙"的手机：音乐比按下播放晚 150 毫秒才真正出声，报出来的位置每次都有几十毫秒的抖动，
     * 还是一格一格跳的，偶尔报一个离谱的数。画面的时间必须一帧一帧平滑地走，不能跟着跳。
     */
    @Test fun syncClockStaysSmoothWithJitteryAudio() {
        val clock = SyncClock()
        val rnd = java.util.Random(7)
        var real = 0.0          // 真实时间
        var scene = 0f          // 画面时间
        val latency = 0.150
        var worstStep = 0f
        var snaps = 0
        var lastErr = 0f
        for (i in 0 until 60 * 40) {
            // 帧间隔本身也不均匀
            val dt = (1.0 / 60.0 + (rnd.nextDouble() - 0.5) * 0.004).toFloat()
            real += dt
            val truth = real - latency
            var reported = if (truth < 0) Float.NaN else {
                val stepped = Math.floor(truth / 0.023) * 0.023                 // 一格 23 毫秒
                (stepped + (rnd.nextDouble() - 0.5) * 0.050).toFloat()          // ±25 毫秒的抖动
            }
            if (i % 400 == 399) reported += 0.4f                                // 偶尔一个离谱的读数
            val adv = clock.advance(dt, dt, scene, reported)
            if (clock.snapped) { snaps++; scene = reported } else {
                scene += adv
                worstStep = maxOf(worstStep, abs(adv - dt) / dt)
            }
            if (i > 60 * 10) lastErr = maxOf(lastErr, abs((truth - scene).toFloat()))
        }
        assertEquals("不应该出现整步跳变", 0, snaps)
        assertTrue("每一帧走的时间和真实经过的时间最多差 6%：实际最大 ${worstStep * 100}%", worstStep <= 0.0605f)
        assertTrue("稳定之后画面和音乐的偏差应当在 30 毫秒以内：实际 ${lastErr * 1000} 毫秒", lastErr < 0.030f)
    }

    @Test fun syncClockSnapsOnlyWhenReallyOff() {
        val clock = SyncClock()
        var scene = 10f
        // 应用卡了一下：音乐已经走到 11 秒了，画面还在 10 秒
        var snapped = false
        for (i in 0 until 30) {
            val adv = clock.advance(1f / 60f, 1f / 60f, scene, 11f + i / 60f)
            if (clock.snapped) { snapped = true; scene = 11f + i / 60f; break }
            scene += adv
        }
        assertTrue("差了整整一秒：应当直接跳过去", snapped)
        // 没有音乐时钟：完全按内部时钟走
        assertEquals(0.02f, clock.advance(0.02f, 0.02f, 5f, Float.NaN), 0f)
    }

    @Test fun sceneTimeFollowsAudioWithoutJumping() {
        val scene = Scene()
        scene.extraDuration = 3000
        scene.recomputeDuration()
        scene.playing = true
        var audio = 0f
        var last = 0f
        var maxStep = 0f
        // 音乐一开始没出声（hold），0.2 秒后开始走
        for (i in 0 until 600) {
            val t = i / 60f
            val hold = t < 0.2f
            if (!hold) audio = t - 0.2f + (if (i % 3 == 0) 0.02f else -0.015f)
            scene.update(1f / 60f, if (hold) Float.NaN else audio, hold)
            val step = scene.timeSeconds - last
            last = scene.timeSeconds
            if (i > 1) maxStep = maxOf(maxStep, step)
            assertTrue("时间不能倒退", step >= 0f)
            assertFalse(scene.resynced)
        }
        assertTrue("等音乐的那 0.2 秒画面不应该先走", last < 600 / 60f - 0.15f)
        assertTrue("最大的一步 $maxStep 秒", maxStep < 1f / 60f * 1.08f)
        assertEquals(600 / 60f - 0.2f, last, 0.03f)
    }

    // ------------------------------------------------------------------ 片段

    private fun motionWith(vararg frames: Int): Motion {
        val m = Motion()
        for (f in frames) m.boneTrack("センター").set(f, f.toFloat(), 0f, 0f, 0f, 0f, 0f, 1f)
        m.recomputeMaxFrame()
        return m
    }

    @Test fun clipsMoveTrimSplitDuplicate() {
        val lane = ArrayList<MotionClip>()
        val a = MotionClip(motionWith(0, 100), "A")
        val b = MotionClip(motionWith(0, 50), "B")
        ClipOps.insert(lane, a, 0)
        ClipOps.insert(lane, b, 30)                       // 想放在 30，但那里被 A 占着
        assertEquals("被占的位置：贴到 A 的后面", 100, b.start)
        assertEquals(150, ClipOps.end(lane))

        // 移动：放得下就放，放不下贴到最近的空位
        assertEquals(200, ClipOps.move(lane, b, 200))
        assertEquals(100, ClipOps.move(lane, b, 60))
        assertEquals(listOf("A", "B"), lane.map { it.name })

        // 裁剪：裁掉开头 20 帧，再裁掉结尾；动作本身不动
        ClipOps.trimStart(lane, a, 20)
        assertEquals(20, a.start); assertEquals(20, a.trimIn); assertEquals(100, a.end)
        assertEquals("时间轴第 20 帧对应动作里的第 20 帧", 20f, a.localFrame(20f), 0f)
        ClipOps.trimEnd(lane, a, 70)
        assertEquals(70, a.end); assertEquals(50, a.length)
        assertEquals("片段范围之外停在最后一帧", 70f, a.localFrame(90f), 0f)
        // 再拉回去：内容还在
        ClipOps.trimStart(lane, a, 0); ClipOps.trimEnd(lane, a, 100)
        assertEquals(0, a.start); assertEquals(0, a.trimIn); assertEquals(100, a.end)
        ClipOps.trimEnd(lane, a, 500)
        assertEquals("不能拉到盖住后面的片段", 100, a.end)

        // 分割：两段接在一起，播出来和没切一样
        val a2 = ClipOps.split(lane, a, 40)
        assertNotNull(a2)
        assertEquals(40, a.end); assertEquals(40, a2!!.start); assertEquals(100, a2.end)
        assertEquals(55f, a2.localFrame(55f), 0f)
        assertNull("切在片段外面：不做", ClipOps.split(lane, a, 40))
        assertEquals(listOf(0, 40, 100), lane.map { it.start })

        // 复制：接在最后
        val c = ClipOps.duplicate(lane, b)
        assertEquals(150, c.start); assertEquals(200, c.end)

        // 合成一份动作：每段的头尾都有关键帧，位置对得上
        ClipOps.move(lane, a2, 300)
        val baked = ClipOps.bake(lane)
        val t = baked.boneTracks["センター"]!!
        val out = FloatArray(3); val r = FloatArray(4)
        t.sample(20f, 0, out, 0, r, 0); assertEquals(20f, out[0], 0.01f)        // A 的前半段
        t.sample(320f, 0, out, 0, r, 0); assertEquals(60f, out[0], 0.01f)       // A 的后半段挪到了 300：原来的第 60 帧
        t.sample(125f, 0, out, 0, r, 0); assertEquals(25f, out[0], 0.01f)       // B
    }

    @Test fun keyEditsGoToTheClipUnderTheFrame() {
        val h = Host()
        val m = realModel(h)
        val bone = m.data.bones.indexOfFirst { it.name == "上半身" }
        assumeTrue(bone >= 0)
        val a = MotionClip(Motion(), "A"); val b = MotionClip(Motion(), "B")
        a.motion.boneTrack("上半身").set(0, 0f, 0f, 0f, 0f, 0f, 0f, 1f); a.motion.boneTrack("上半身").set(60, 0f, 0f, 0f, 0f, 0f, 0f, 1f); a.motion.recomputeMaxFrame()
        b.motion.boneTrack("上半身").set(0, 0f, 0f, 0f, 0f, 0f, 0f, 1f); b.motion.boneTrack("上半身").set(60, 0f, 0f, 0f, 0f, 0f, 0f, 1f); b.motion.recomputeMaxFrame()
        a.owned = true; b.owned = true
        ClipOps.insert(m.clips, a, 0); ClipOps.insert(m.clips, b, 200)
        h.scene.recomputeDuration()
        assertEquals(260, h.scene.maxFrame)
        // 在时间轴第 230 帧打关键帧：应当落在 B 里的第 30 帧，A 不变
        assertTrue(KeyOps.setBoneKey(m, h.scene, bone, 230, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
        assertTrue(b.motion.boneTracks["上半身"]!!.indexOf(30) >= 0)
        assertEquals(2, a.motion.boneTracks["上半身"]!!.count)
        assertTrue(KeyOps.hasBoneKey(m, bone, 230))
        assertFalse(KeyOps.hasBoneKey(m, bone, 30))
        // 时间轴上看到的帧号是换算回来的
        m.selectClip(230)
        assertTrue(KeyOps.boneKeyFrames(m, bone).contains(230))
        // 播放到两段之间的空档：停在 A 的最后一帧
        h.scene.seek(150f); h.scene.update(0f)
        assertTrue(m.activeClip === a)
        h.scene.seek(215f); h.scene.update(0f)
        assertTrue(m.activeClip === b)
        // 撤销用的快照：改了 B 的关键帧之后能原样放回去
        m.selectClip(230)
        val snap = m.snapshotClips(copyActive = true)
        KeyOps.setBoneKey(m, h.scene, bone, 240, 0f, 2f, 0f, 0f, 0f, 0f, 1f)
        ClipOps.move(m.clips, b, 400)
        m.restoreClips(snap)
        assertEquals(200, b.start)
        assertTrue(b.motion.boneTracks["上半身"]!!.indexOf(40) < 0)
        assertTrue(b.motion.boneTracks["上半身"]!!.indexOf(30) >= 0)
    }

    // ------------------------------------------------------------------ Lua

    @Test fun luaSandboxAndRunawayScripts() {
        val h = Host()
        // 危险的东西都不存在
        val s = h.start("探测", "print(tostring(io), tostring(os.execute), tostring(dofile), tostring(loadfile), tostring(luajava), tostring(debug), type(os.time()))")
        assertNull(s.error)
        assertEquals("nil\tnil\tnil\tnil\tnil\tnil\tnumber", h.log.last())
        // 死循环：被中止，脚本停下来，别的脚本不受影响
        val ok = h.start("正常", "n = 0 function update(dt) n = n + 1 end")
        val bad = h.start("死循环", "function update(dt) while true do end end")
        h.run(0.1f)
        assertNotNull(bad.error); assertTrue(bad.error!!.contains("死循环")); assertFalse(bad.running)
        assertTrue(ok.running); assertNull(ok.error)
        // 语法错误在运行之前就能查出来，带行号
        val err = h.runtime.scripts.check("local a = 1\nlocal b = = 2", "x")
        assertNotNull(err); assertTrue(err!!, err.contains("x:2"))
        assertNull(h.runtime.scripts.check("local a = 1"))
        // 运行时错误：带行号的说明，脚本停下
        val e2 = h.start("空值", "local m = scene.find('不存在')\nprint(m.name)")
        assertNotNull(e2.error); assertTrue(e2.error!!, e2.error!!.startsWith("第 2 行：") && !e2.error!!.contains("traceback"))
        // 改好代码之后可以重新运行
        h.runtime.scripts.setSource(e2, "print('好了')")
        h.runtime.scripts.setEnabled(e2, true)
        h.run(0.05f)
        assertNull(e2.error); assertEquals("好了", h.log.last())
    }

    @Test fun luaDrivesModelsEffectsAndCleansUp() {
        val h = Host()
        val s = h.start("搭场景", """
            box = scene.create("box", { name = "箱子", size = { 2, 2, 2 }, color = { 1, 0, 0, 1 }, pos = { 0, 5, 0 } })
            ball = scene.create("sphere", { radius = 1 })
            ball.layer = 3
            ball.opacity = 0.25
            e = fx.emitter{ rate = 100, life = 1, pos = { 0, 3, 0 }, texture = "spark" }
            tr = fx.trail{ model = box, width = 0.5, life = 1 }
            ui.text("t", "你好")
            ui.button("b", "按钮")
            physics.wind(50, 0, 0, 0.5)
            post.set{ bloom = 0.9, exposure = 1.5 }
            hits = 0
            function update(dt)
              local x = box:pos()
              box:set_pos(x + dt * 6, 5, 0)
              box:set_rot(0, time.real * 90, 0)
            end
            function on_button(id, down) if down then hits = hits + 1; print("按了", id, hits) end end
        """.trimIndent())
        assertNull(s.error, s.error)
        assertEquals(2, h.scene.models.size)
        val box = h.scene.models.first { it.name == "箱子" }
        val ball = h.scene.models.first { it !== box }
        assertEquals(3, ball.layer); assertEquals(0.25f, ball.opacity, 0f)
        assertEquals(1f, box.data.materials[0].diffuse[0], 0f); assertEquals(0f, box.data.materials[0].diffuse[1], 0f)
        h.run(1f)
        assertEquals(6f, box.position[0], 0.3f)
        assertTrue("粒子应该在不断发射", h.runtime.effects.particleCount in 60..140)
        assertTrue("拖尾跟着箱子走，有了好几个点", h.runtime.effects.trails[0].pointCount > 5)
        assertEquals("你好", h.texts["t"]); assertEquals("按钮", h.buttons["b"])
        assertEquals(50f, h.scene.physics.windX, 0f)
        assertTrue(h.runtime.settings.bloom); assertEquals(1.5f, h.runtime.settings.exposure, 0f)
        // 屏幕按钮
        h.runtime.input.button("b", true); h.runtime.input.button("b", false)
        h.run(0.05f)
        assertEquals("按了\tb\t1", h.log.last())
        // 关掉脚本：它建的模型、特效、文字都没了，改过的设置回到原样
        h.runtime.scripts.setEnabled(s, false)
        assertEquals(0, h.scene.models.size)
        assertTrue(h.runtime.effects.emitters.isEmpty() && h.runtime.effects.trails.isEmpty())
        assertTrue(h.texts.isEmpty() && h.buttons.isEmpty())
        assertEquals(0f, h.scene.physics.windX, 0f)
        assertFalse(h.runtime.settings.bloom); assertEquals(1f, h.runtime.settings.exposure, 0f)
    }

    /**
     * 存盘时要拿到「脚本没动过」的状态：脚本运行中改的位置、透明度、层、物理参数、灯光不能被存成永久的，
     * 存完之后脚本的效果又要原样接着跑。用户自己在界面上改的别的参数不受影响。
     */
    @Test fun scriptChangesAreLeftOutOfSaves() {
        val h = Host()
        val em = com.lumi.mmd.engine.mesh.EditMesh.createEmpty("箱子")
        com.lumi.mmd.engine.mesh.MeshPrimitives.box(em, 1f, 1f, 1f)
        val m = h.addModel(em.build().data, ModelInstance.KIND_ACCESSORY)
        m.setPosition(1f, 2f, 3f); m.opacity = 0.9f; m.layer = 1
        h.scene.physics.stiffness = 1.5f                   // 用户之前调的
        val a = h.start("甲", """
            local m = scene.models()[1]
            m.opacity = 0.3; m.layer = 5; m.visible = false
            m:set_pos(10, 0, 0); m:set_rot(0, 90, 0)
            m:physics_set("mass", 3)
            physics.wind(80, 0, 0); physics.gravity(0.2); physics.enabled(false)
            light.color(1, 0, 0)
        """.trimIndent())
        assertNull(a.error, a.error)
        // 第二个脚本后启动，改同一样东西
        val b = h.start("乙", "scene.models()[1].opacity = 0.6; physics.set('damping', 4)")
        assertNull(b.error, b.error)
        h.scene.physics.limits = 2f                        // 脚本运行期间，用户在界面上调了另一个参数
        assertEquals(0.6f, m.opacity, 0f); assertEquals(5, m.layer); assertFalse(m.visible)
        val lightBefore = h.scene.light.color.copyOf()

        var seen = false
        val r = h.runtime.scripts.withBaseState {
            seen = true
            assertEquals("透明度是脚本没动过时的值", 0.9f, m.opacity, 0f)
            assertEquals(1, m.layer); assertTrue(m.visible)
            assertEquals(1f, m.position[0], 0f); assertEquals(2f, m.position[1], 0f); assertEquals(3f, m.position[2], 0f)
            assertEquals(1f, m.rotation[3], 1e-6f)
            assertNull("模型自己的物理倍数本来没有", m.physicsTuning)
            assertEquals(0f, h.scene.physics.windX, 0f); assertEquals(1f, h.scene.physics.gravity, 0f); assertEquals(1f, h.scene.physics.damping, 0f)
            assertTrue(h.scene.physicsEnabled)
            assertEquals("用户调的参数照常", 1.5f, h.scene.physics.stiffness, 0f)
            assertEquals("脚本运行期间用户调的也照常", 2f, h.scene.physics.limits, 0f)
            assertTrue(h.scene.light.color[1] > 0.01f || h.scene.light.color[2] > 0.01f)
            42
        }
        assertTrue(seen); assertEquals(42, r)
        // 存完：脚本的效果原样回来
        assertEquals(0.6f, m.opacity, 0f); assertEquals(5, m.layer); assertFalse(m.visible)
        assertEquals(10f, m.position[0], 0f)
        assertTrue(abs(m.rotation[1]) > 0.5f)
        assertEquals(3f, m.physicsTuning!!.mass, 0f)
        assertEquals(80f, h.scene.physics.windX, 0f); assertEquals(0.2f, h.scene.physics.gravity, 0f); assertEquals(4f, h.scene.physics.damping, 0f)
        assertFalse(h.scene.physicsEnabled)
        assertEquals(lightBefore.toList(), h.scene.light.color.toList())
        // 脚本照常运行；关掉之后回到原样，用户调的两项都还在
        h.run(0.1f)
        h.runtime.scripts.setEnabled(b, false)
        assertEquals("乙关掉后回到甲的值", 0.3f, m.opacity, 0f)
        h.runtime.scripts.setEnabled(a, false)
        assertEquals(0.9f, m.opacity, 0f); assertEquals(1, m.layer); assertTrue(m.visible)
        assertEquals(1f, m.position[0], 0f)
        assertNull(m.physicsTuning)
        assertEquals(0f, h.scene.physics.windX, 0f); assertEquals(1f, h.scene.physics.damping, 0f); assertTrue(h.scene.physicsEnabled)
        assertEquals(1.5f, h.scene.physics.stiffness, 0f); assertEquals(2f, h.scene.physics.limits, 0f)
    }

    @Test fun luaPreludeTimersTweensCoroutines() {
        val h = Host()
        val s = h.start("小工具", """
            log = {}
            after(0.5, function() log[#log + 1] = "after" end)
            local n = 0
            local tick = every(0.25, function() n = n + 1 end)
            tween(1.0, function(k) value = k end, ease.linear, function() log[#log + 1] = "tween done" end)
            run(function()
              log[#log + 1] = "a"
              wait(0.3)
              log[#log + 1] = "b"
              wait(0.3)
              log[#log + 1] = "c"
              cancel(tick)
            end)
            function report() print(table.concat(log, ","), n, math.floor(value * 100 + 0.5)) end
        """.trimIndent())
        assertNull(s.error, s.error)
        h.run(0.45f)
        h.runtime.scripts.call(s, s.globals!!.get("report"), 1_000_000L)
        assertEquals("a,b\t1\t" + "47", h.log.last())
        h.run(1.0f)
        h.runtime.scripts.call(s, s.globals!!.get("report"), 1_000_000L)
        val parts = h.log.last().split("\t")
        assertEquals("a,b,after,c,tween done", parts[0])
        assertEquals("协程结束时取消了定时器，之后不再计数", "2", parts[1])
        assertEquals("100", parts[2])
        // 时间段：只在第 30~60 帧之间运行
        val ranged = h.runtime.scripts.add("限时", "ui.text('r', '在')")
        ranged.start = 30; ranged.end = 60
        h.scene.extraDuration = 300; h.scene.recomputeDuration()
        h.runtime.scripts.setEnabled(ranged, true)
        h.run(0.05f); assertFalse(ranged.running); assertNull(h.texts["r"])
        h.scene.seek(45f); h.run(0.05f); assertTrue(ranged.running); assertEquals("在", h.texts["r"])
        h.scene.seek(90f); h.run(0.05f); assertFalse(ranged.running); assertNull(h.texts["r"])
    }

    @Test fun luaConsoleKeepsState() {
        val h = Host()
        val e = h.runtime.scripts
        assertEquals("=> 3", e.eval("1 + 2"))
        assertEquals("", e.eval("x = 41"))
        assertEquals("=> 42", e.eval("x + 1"))
        assertEquals("hi\n=> 1\t2", e.eval("print('hi') return 1, 2"))
        assertTrue(e.eval("error('坏了')").contains("坏了"))
        assertEquals("出错了也不清掉之前的变量", "=> 41", e.eval("x"))
        e.eval("b = scene.create('box', {})")
        assertEquals(1, h.scene.models.size)
        e.resetConsole()
        assertEquals(0, h.scene.models.size)
    }

    @Test fun builtInTexturesAndExamplesAreValid() {
        for (n in EffectTextures.NAMES) {
            val px = EffectTextures.generate(n, 32)!!
            var sum = 0L
            for (i in 3 until px.size step 4) sum += px[i].toInt() and 0xFF
            assertTrue("贴图 $n 不应该是全透明或全不透明的", sum > 32 * 32 * 3 && sum < 32L * 32 * 250)
        }
        val h = Host()
        for (ex in ScriptLibrary.EXAMPLES) assertNull("示例「${ex.name}」有语法错误", h.runtime.scripts.check(ex.source, ex.name))
        // 没有任何模型的空场景里运行：不应该报错（示例里都处理了"没有角色"的情况）
        for (ex in ScriptLibrary.EXAMPLES) {
            val s = h.start(ex.name, ex.source)
            h.run(0.3f)
            assertNull("示例「${ex.name}」在空场景里出错：${s.error}", s.error)
            h.runtime.scripts.remove(s)
        }
        assertEquals(0, h.scene.models.size)
    }

    @Test fun examplesRunOnRealModel() {
        val h = Host()
        val m = realModel(h)
        h.run(0.1f)
        val rest = m.skeleton!!.global.copyOf()
        for (ex in ScriptLibrary.EXAMPLES) {
            val s = h.start(ex.name, ex.source)
            h.run(1.5f)
            assertNull("示例「${ex.name}」出错：${s.error}", s.error)
            assertTrue(s.running)
            for (v in m.skeleton!!.global) assertTrue("示例「${ex.name}」把骨骼算出了非法数值", v.isFinite())
            h.runtime.scripts.remove(s)
        }
        // 全部关掉之后：模型回到原样（材质、透明度、层、表情、物理参数）
        assertEquals(1, h.scene.models.size)
        assertEquals(1f, m.opacity, 0f); assertEquals(0, m.layer)
        assertTrue(m.materials.none { it.hasFx })
        assertFalse(m.skeleton!!.hasScriptLayer)
        assertFalse(m.morphs.anyScript)
        assertTrue(h.scene.physics.isDefault)
        assertTrue(h.runtime.effects.isEmpty)
        assertEquals(rest.size, m.skeleton!!.global.size)
    }

    @Test fun luaBonesMorphsMaterialsOnRealModel() {
        val h = Host()
        val m = realModel(h)
        h.scene.physicsEnabled = false
        h.run(0.05f)
        val arm = m.data.bones.indexOfFirst { it.name == "左ひじ" }
        assumeTrue(arm >= 0)
        val before = FloatArray(3); m.boneWorldPosition(arm, before, 0)
        val s = h.start("驱动", """
            local m = scene.main()
            m:bone("左腕"):rotate(0, 0, 60)          -- 抬左臂
            m:bone("頭"):scale(1.5)
            for _, mat in ipairs(m:materials()) do mat.alpha = 0.5 end
            local names = m:morphs()
            m:morph(names[1], 1)
            print(m.name, #m:bones(), m:bone("没有这根骨骼") == nil)
            function update(dt) m:root(3, 0, 0, 90) end
        """.trimIndent())
        assertNull(s.error, s.error)
        h.run(0.1f)
        val after = FloatArray(3); m.boneWorldPosition(arm, after, 0)
        assertTrue("左臂抬起来了：肘的位置应当变高", after[1] > before[1] + 1f)
        assertEquals(0.5f, m.materials[0].fxAlpha, 0f)
        assertEquals(1f, m.morphs.weightOf(0), 0f)
        assertTrue(m.skeleton!!.anyScriptScale && m.skeleton!!.rootActive)
        assertTrue(h.log.last().endsWith("true"))
        h.runtime.scripts.setEnabled(s, false)
        h.run(0.1f)
        val back = FloatArray(3); m.boneWorldPosition(arm, back, 0)
        for (k in 0 until 3) assertEquals("关掉脚本后骨骼回到原位", before[k], back[k], 1e-3f)
        assertEquals(1f, m.materials[0].fxAlpha, 0f)
        assertEquals(0f, m.morphs.weightOf(0), 0f)
    }

    // ------------------------------------------------------------------ 操控模式

    @Test fun controllerWalksRunsJumpsCrouchesAndCrawls() {
        val h = Host()
        val m = realModel(h)
        val rig = Rig(m.data)
        assumeTrue("测试模型没有标准的腿部骨骼", rig.usable)
        // 每个内置动作：首尾两帧姿势相同（能无缝循环），关键帧数量合理
        val loco = Locomotion(rig)
        assertEquals(11, loco.all.size)
        for (c in loco.all) {
            assertTrue("${c.title} 没有关键帧", c.motion.boneTracks.size >= 8)
            for ((name, t) in c.motion.boneTracks) {
                val a = FloatArray(3); val b = FloatArray(3); val qa = FloatArray(4); val qb = FloatArray(4)
                t.sample(0f, 0, a, 0, qa, 0); t.sample(c.frames.toFloat(), 0, b, 0, qb, 0)
                for (k in 0 until 3) assertEquals("${c.title} / $name 首尾不一致", a[k], b[k], 1e-3f)
                assertTrue("${c.title} / $name 首尾旋转不一致", abs(qa[0] * qb[0] + qa[1] * qb[1] + qa[2] * qb[2] + qa[3] * qb[3]) > 0.9999f)
            }
        }

        // 进入之前的视角：退出时要原样回来
        h.scene.camera.distance = 37f; h.scene.camera.rotY = 0.4f; h.scene.camera.target[1] = 11f
        val c = h.runtime.startControl(m)
        assertEquals("进入操控后镜头应在角色身后", Math.PI.toFloat() - c.yaw, h.scene.camera.rotY, 1e-4f)
        assertTrue(c.animated)
        assertTrue(m.animator === c && h.scene.cameraLocked)
        h.scene.camera.update(16f / 9f)
        val input = h.runtime.input
        fun go(x: Float, y: Float, seconds: Float) { input.moveX = x; input.moveY = y; var t = 0f; while (t < seconds) { h.scene.camera.update(16f / 9f); h.runtime.update(1f / 60f); t += 1f / 60f } }
        fun tap(b: String) { input.button(b, true); input.button(b, false) }
        fun sane() { for (v in m.skeleton!!.global) assertTrue("骨骼矩阵里出现了非法数值", v.isFinite()) }

        go(0f, 0f, 0.5f); assertEquals("待机", c.stateName); sane()
        // 轻推：走；推到底：跑；按住疾跑：更快。镜头在角色身后，往前推 = 离镜头远去（-Z）
        go(0f, 0.4f, 1.5f); val walk = c.speed; assertEquals("走", c.stateName)
        assertTrue("往前走了", c.z < -5f && abs(c.x) < 0.5f)
        go(0f, 1f, 1.5f); val run = c.speed; assertEquals("跑", c.stateName)
        h.runtime.sprintLock = true
        go(0f, 1f, 1.5f); val sprint = c.speed; assertEquals("疾跑", c.stateName)
        h.runtime.sprintLock = false
        assertTrue("走 $walk < 跑 $run < 疾跑 $sprint", walk > 1f && run > walk * 2f && sprint > run * 1.2f)
        sane()
        // 往右推：朝屏幕右边跑
        val x0 = c.x
        go(1f, 0f, 1f)
        val right = h.scene.camera.right
        assertTrue("朝屏幕右边移动", (c.x - x0) * right[0] > 5f)
        // 跳：离地，再落回来
        tap("jump"); go(1f, 0f, 0.2f)
        assertFalse(c.grounded); assertTrue(c.y > rig.leg * 0.3f)
        go(1f, 0f, 1f); assertTrue(c.grounded); assertEquals(0f, c.y, 0f)
        // 蹲：变慢，重心降低
        go(0f, 0f, 0.5f)
        val headBone = m.data.bones.indexOfFirst { it.name == "頭" }
        val p = FloatArray(3)
        m.boneWorldPosition(headBone, p, 0); val standHead = p[1]
        tap("crouch"); go(0f, 0f, 0.8f)
        assertEquals(CharacterController.CROUCH, c.stance); assertEquals("蹲", c.stateName)
        m.boneWorldPosition(headBone, p, 0); val crouchHead = p[1]
        go(0f, 1f, 1f); assertEquals("蹲着走", c.stateName); assertTrue(c.speed < run * 0.6f)
        // 趴：更低，爬得更慢；趴着按跳是起身
        go(0f, 0f, 0.3f); tap("prone"); go(0f, 0f, 1.2f)
        assertEquals(CharacterController.PRONE, c.stance)
        m.boneWorldPosition(headBone, p, 0); val proneHead = p[1]
        assertTrue("站 $standHead > 蹲 $crouchHead > 趴 $proneHead", standHead > crouchHead + 2f && crouchHead > proneHead + 2f)
        go(0f, 1f, 2f); assertEquals("匍匐", c.stateName); assertTrue(c.speed < walk)
        tap("jump"); go(0f, 0f, 0.5f); assertEquals(CharacterController.CROUCH, c.stance); assertTrue(c.grounded)
        sane()
        // 镜头的注视点跟着角色
        val w = FloatArray(3); c.worldPosition(w)
        assertEquals(w[0], h.scene.camera.target[0], 1.5f); assertEquals(w[2], h.scene.camera.target[2], 1.5f)
        // 退出：交还给时间轴上的片段
        h.runtime.stopControl()
        assertNull(m.animator); assertFalse(h.scene.cameraLocked); assertFalse(m.skeleton!!.rootActive)
        assertEquals(37f, h.scene.camera.distance, 0f); assertEquals(0.4f, h.scene.camera.rotY, 0f)
        assertEquals(0f, h.scene.camera.target[0], 0f); assertEquals(11f, h.scene.camera.target[1], 0f); assertEquals(0f, h.scene.camera.target[2], 0f)
    }

    /**
     * 镜头动作正在驱动相机的时候，脚本或操控模式接管镜头：接管期间不能被盖掉，
     * 放手之后交还给镜头动作，用户原来的自由视角也还在。
     */
    @Test fun takingTheCameraFromACameraMotion() {
        val h = Host()
        val s = h.scene
        s.camera.distance = 40f; s.camera.rotY = 0f; s.camera.target[1] = 10f
        KeyOps.registerCamera(s, 0)
        s.camera.distance = 20f; s.camera.rotY = 1f
        KeyOps.registerCamera(s, 60)
        // 用户自己的视角（镜头动作接手之前看到的）
        s.camera.distance = 33f; s.camera.rotY = 0.7f
        s.seek(30f); h.runtime.update(0f)
        assertTrue(s.cameraDriven)
        assertEquals(30f, s.camera.distance, 1e-3f); assertEquals(0.5f, s.camera.rotY, 1e-3f)
        assertEquals(33f, s.userCamera.distance, 0f)

        // 脚本只设一次镜头：之后每一帧都得保持住
        val sc = h.start("镜头", "camera.set{distance = 12, yaw = 90}")
        h.run(0.2f)
        assertTrue(h.errors.toString(), h.errors.isEmpty())
        assertTrue(s.cameraLocked); assertFalse(s.cameraDriven)
        assertEquals(12f, s.camera.distance, 1e-3f); assertEquals(90f, Math.toDegrees(s.camera.rotY.toDouble()).toFloat(), 1e-2f)
        // 停掉脚本：镜头动作接着驱动，自由视角没有被冲掉
        h.runtime.scripts.setEnabled(sc, false); h.runtime.update(0f)
        assertFalse(s.cameraLocked); assertTrue(s.cameraDriven)
        assertEquals(30f, s.camera.distance, 1e-3f); assertEquals(0.5f, s.camera.rotY, 1e-3f)
        assertEquals(33f, s.userCamera.distance, 0f); assertEquals(0.7f, s.userCamera.rotY, 0f)

        // 操控模式也一样：镜头留在角色身后，退出后回到镜头动作
        val f = assets?.let { File(it, "model.pmx") }?.takeIf { it.isFile }
        if (f != null) {
            val m = ModelInstance(ModelLoader.loadModel(f), ModelInstance.KIND_CHARACTER)
            s.add(m); h.runtime.update(0f)
            val c = h.runtime.startControl(m)
            h.run(0.2f)
            assertTrue(s.cameraLocked); assertFalse(s.cameraDriven)
            assertEquals(Math.PI.toFloat() - c.yaw, s.camera.rotY, 1e-4f)
            assertTrue(abs(s.camera.distance - 30f) > 1f)
            h.runtime.stopControl(); h.runtime.update(0f)
            assertFalse(s.cameraLocked); assertTrue(s.cameraDriven)
            assertEquals(30f, s.camera.distance, 1e-3f); assertEquals(0.5f, s.camera.rotY, 1e-3f)
            assertEquals(33f, s.userCamera.distance, 0f); assertEquals(0.7f, s.userCamera.rotY, 0f)
        }
        // 镜头动作关掉之后：回到用户自己的视角
        s.useCameraMotion = false; h.runtime.update(0f)
        assertFalse(s.cameraDriven)
        assertEquals(33f, s.camera.distance, 0f); assertEquals(0.7f, s.camera.rotY, 0f)
    }

    // ------------------------------------------------------------------ 物理参数

    @Test fun physicsTuningChangesTheSimulation() {
        fun hairAfter(setup: (Scene, ModelInstance) -> Unit): FloatArray {
            val h = Host()
            val m = realModel(h)
            assumeTrue(m.physics != null)
            setup(h.scene, m)
            h.run(1.5f)
            val ph = m.physics!!
            val i = (0 until ph.bodyCount).last { ph.bodyMode(it) != 0 }
            val b = ph.body(i)
            for (v in m.skeleton!!.global) assertTrue(v.isFinite())
            return floatArrayOf(b.px, b.py, b.pz)
        }
        val calm = hairAfter { _, _ -> }
        val windy = hairAfter { s, _ -> s.physics.windX = 300f; s.physics.turbulence = 0f }
        val floaty = hairAfter { s, _ -> s.physics.gravity = -0.5f }
        val own = hairAfter { s, m -> s.physics.turbulence = 0f; m.physicsTuning = com.lumi.mmd.engine.physics.PhysicsTuning().also { it.windX = 300f } }
        assertTrue("有风：被吹向 +X（${calm[0]} → ${windy[0]}）", windy[0] > calm[0] + 0.3f)
        assertTrue("重力反过来：往上飘（${calm[1]} → ${floaty[1]}）", floaty[1] > calm[1] + 0.3f)
        assertEquals("只给这个模型设的风，效果和全局的一样", windy[0], own[0], 0.05f)
        // 参数名和取值范围
        val t = com.lumi.mmd.engine.physics.PhysicsTuning()
        assertTrue(t.isDefault)
        for (n in com.lumi.mmd.engine.physics.PhysicsTuning.NAMES) assertNotNull(n, t.get(n))
        assertTrue(t.set("stiffness", 3f)); assertFalse(t.set("没有这个", 1f)); assertFalse(t.isDefault)
        assertTrue(sin(0f) == 0f)
    }
}

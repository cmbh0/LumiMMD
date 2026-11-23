package com.lumi.mmd.engine

import com.lumi.mmd.engine.anim.Motion
import com.lumi.mmd.engine.anim.MotionClip
import com.lumi.mmd.engine.math.Quat
import com.lumi.mmd.engine.mcp.McpHost
import com.lumi.mmd.engine.mcp.SceneTools
import com.lumi.mmd.engine.mcp.ToolResult
import com.lumi.mmd.engine.mesh.EditMesh
import com.lumi.mmd.engine.mesh.MeshPrimitives
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.render.RenderSettings
import com.lumi.mmd.engine.scene.AudioClip
import com.lumi.mmd.engine.scene.AudioDeck
import com.lumi.mmd.engine.scene.AudioVoice
import com.lumi.mmd.engine.scene.ClipOps
import com.lumi.mmd.engine.scene.Editor
import com.lumi.mmd.engine.scene.MeshSession
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.scene.Scene
import com.lumi.mmd.engine.scene.SceneRuntime
import com.lumi.mmd.engine.script.LuaSyntax
import com.lumi.mmd.engine.script.Script
import com.lumi.mmd.engine.script.ScriptCallbacks
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4.1 新增的部分：片段之间的过渡、音乐轨道（可以挪、裁、切）、代码的上色和补全、AI 通过 MCP 写脚本并运行。 */
class MixTest {
    private fun boxModel(): ModelData {
        val em = EditMesh.createEmpty("箱子")
        MeshPrimitives.box(em, 2f, 2f, 2f)
        em.recalcNormals()
        return em.build().data
    }

    /** 一段只有一根骨骼的动作：x 从 x0 匀速走到 x1（frames 帧），同时绕 Y 轴从 0 转到 deg 度。 */
    private fun slide(bone: String, frames: Int, x0: Float, x1: Float, deg: Float = 0f): Motion {
        val m = Motion()
        val q = FloatArray(4)
        m.boneTrack(bone).set(0, x0, 0f, 0f, 0f, 0f, 0f, 1f)
        Quat.fromAxisAngle(q, 0, 0f, 1f, 0f, Math.toRadians(deg.toDouble()).toFloat())
        m.boneTrack(bone).set(frames, x1, 0f, 0f, q[0], q[1], q[2], q[3])
        m.recomputeMaxFrame()
        return m
    }

    // ------------------------------------------------------------------ 过渡

    @Test fun clipsCrossFade() {
        val scene = Scene()
        val inst = ModelInstance(boxModel(), ModelInstance.KIND_CHARACTER)
        scene.add(inst)
        val sk = inst.skeleton!!
        val bone = inst.data.bones[0].name
        // A：x 从 0 走到 10（100 帧）。B：x 一直是 50，同时转到 90°。B 紧接在 A 后面
        val a = MotionClip(slide(bone, 100, 0f, 10f), "A")
        val b = MotionClip(slide(bone, 100, 50f, 50f, 90f), "B")
        ClipOps.insert(inst.clips, a, 0)
        ClipOps.insert(inst.clips, b, 100)
        scene.recomputeDuration()
        fun xAt(f: Float): Float { scene.seek(f); scene.update(0f); return sk.global[12] }

        // 没有过渡：到了第 100 帧一下子从 10 跳到 50
        assertEquals(9.9f, xAt(99f), 0.01f)
        assertEquals(50f, xAt(100f), 0.01f)

        // 加 20 帧的过渡：开头就是前一段的姿势，中间在两者之间，过渡结束后完全是这一段的
        b.fadeIn = 20
        assertEquals(20, b.fade)
        assertEquals("过渡的第一帧 = 前一段此刻的姿势，没有跳变", 10f, xAt(100f), 0.01f)
        val mid = xAt(110f)
        assertEquals("过渡走到一半：两段各占一半", 30f, mid, 0.01f)
        assertTrue(xAt(105f) < mid && mid < xAt(115f))
        assertEquals(50f, xAt(120f), 0.01f)
        assertEquals(50f, xAt(150f), 0.01f)
        // 一帧一帧走过去没有哪一步是大跳
        var prev = xAt(99f); var worst = 0f
        var f = 99.5f
        while (f <= 121f) { val x = xAt(f); worst = maxOf(worst, abs(x - prev)); prev = x; f += 0.5f }
        assertTrue("过渡期间每半帧最多挪 $worst（不过渡时是 40）", worst < 2.5f)
        // 旋转也跟着过渡（走的是球面插值）：一半的时候是 B 这一帧角度的一半
        scene.seek(110f); scene.update(0f)
        val cosHalf = sk.global[0]                      // 绕 Y 转 θ：矩阵第一项是 cos θ
        val bAngle = Math.toRadians(90.0 * 10 / 100)    // B 在自己的第 10 帧转了 9°
        assertEquals(Math.cos(bAngle / 2).toFloat(), cosHalf, 1e-3f)

        // 前一段后面有被裁掉的内容时，过渡期间它接着往下播（真正的交叉过渡），而不是停在最后一帧
        ClipOps.trimEnd(inst.clips, a, 50)              // A 只用前 50 帧（x 到 5），后面还有 50 帧被裁掉
        ClipOps.move(inst.clips, b, 50)
        b.fadeIn = 20
        assertEquals("过渡开头 = A 的第 50 帧", 5f, xAt(50f), 0.01f)
        // 第 60 帧：A 接着播到了自己的第 60 帧（x = 6），和 B（50）各占一半
        assertEquals((6f + 50f) / 2f, xAt(60f), 0.01f)
        // 中间隔着空隙时，前一段停在最后的姿势上
        ClipOps.move(inst.clips, b, 80)
        assertEquals("空隙里保持 A 最后的姿势", 5f, xAt(70f), 0.01f)
        assertEquals((5f + 50f) / 2f, xAt(90f), 0.01f)

        // 轨道上的第一段没有前一段：过渡不起作用
        a.fadeIn = 30
        assertEquals(0f, xAt(0f), 0.01f)
        assertNull(ClipOps.fadingFrom(inst.clips, a, 5f))
        // 过渡不会比片段本身长
        b.fadeIn = 500
        assertEquals(b.length, b.fade)

        // 撤销用的快照带着过渡；分割出来的后一段没有过渡（两段本来就是连着的）
        b.fadeIn = 20
        val snap = inst.snapshotClips(copyActive = false)
        b.fadeIn = 0
        inst.restoreClips(snap)
        assertEquals(20, b.fadeIn)
        val b2 = ClipOps.split(inst.clips, b, 120)!!
        assertEquals(0, b2.fadeIn); assertEquals(20, b.fadeIn)

        // 导出成一份 VMD：过渡的那几帧逐帧写了进去，播出来和轨道上一样
        ClipOps.remove(inst.clips, b2)
        ClipOps.trimEnd(inst.clips, b, 180)
        val expected = FloatArray(10) { xAt(80f + it * 2f) }
        val baked = ClipOps.bake(inst.clips)
        val t = baked.boneTracks[bone]!!
        val o = FloatArray(3); val r = FloatArray(4)
        for (i in 0 until 10) { t.sample(80f + i * 2f, 0, o, 0, r, 0); assertEquals("导出后第 ${80 + i * 2} 帧", expected[i], o[0], 0.02f) }
        t.sample(30f, 0, o, 0, r, 0); assertEquals(3f, o[0], 0.02f)
        t.sample(150f, 0, o, 0, r, 0); assertEquals(50f, o[0], 0.02f)
    }

    @Test fun cameraClipsCrossFade() {
        val scene = Scene()
        fun cam(distance: Float, yawDeg: Float): Motion {
            val m = Motion()
            val v = floatArrayOf(-distance, 0f, 10f, 0f, 0f, Math.toRadians(yawDeg.toDouble()).toFloat(), 0f, 30f)
            m.cameraTrack().set(0, v, 0, true); m.cameraTrack().set(60, v, 0, true)
            m.recomputeMaxFrame()
            return m
        }
        val a = MotionClip(cam(40f, 0f), "远"); val b = MotionClip(cam(20f, 350f), "近")
        ClipOps.insert(scene.cameraClips, a, 0); ClipOps.insert(scene.cameraClips, b, 60)
        scene.useCameraMotion = true
        scene.recomputeDuration()
        scene.seek(59f); scene.update(0f); assertEquals(40f, scene.camera.distance, 0.01f)
        scene.seek(60f); scene.update(0f); assertEquals("不过渡：直接切", 20f, scene.camera.distance, 0.01f)
        b.fadeIn = 30
        scene.seek(60f); scene.update(0f); assertEquals(40f, scene.camera.distance, 0.01f)
        scene.seek(75f); scene.update(0f)
        assertEquals(30f, scene.camera.distance, 0.01f)
        // 角度走近的那一边：0° → 350° 是往回转 10°，一半时是 -5°，而不是转大半圈到 175°
        assertEquals(-5f, Math.toDegrees(scene.camera.rotY.toDouble()).toFloat(), 0.05f)
        scene.seek(90f); scene.update(0f); assertEquals(20f, scene.camera.distance, 0.01f)
    }

    // ------------------------------------------------------------------ 音乐轨道

    @Test fun audioClipsOnTheMusicLane() {
        val scene = Scene()
        val lane = scene.audioClips
        val song = AudioClip("/music/a.mp3", "a.mp3", 900)              // 30 秒
        ClipOps.insert(lane, song, 0)
        scene.recomputeDuration()
        assertEquals("时间轴的长度算上音乐", 900, scene.maxFrame)
        // 挪到第 60 帧开始
        ClipOps.move(lane, song, 60)
        assertEquals(60, song.start); assertEquals(960, song.end)
        assertEquals("时间轴第 2 秒正好是这首歌的开头", 0f, song.sourceSeconds(2f), 1e-4f)
        // 只用其中一截：裁掉前 5 秒、裁掉结尾
        ClipOps.trimStart(lane, song, 210)
        assertEquals(210, song.start); assertEquals(150, song.trimIn)
        assertEquals("裁掉开头不改变其余部分对应的时间", 5f, song.sourceSeconds(7f), 1e-4f)
        ClipOps.trimEnd(lane, song, 510)
        assertEquals(510, song.end); assertEquals(300, song.length)
        // 音乐不能拉得比文件长（动作可以，多出来的停在最后一帧；音乐后面没东西可放）
        ClipOps.trimEnd(lane, song, 5000)
        assertEquals(960, song.end)
        ClipOps.trimStart(lane, song, 0)
        assertEquals("往前也只能拉到文件的开头", 60, song.start); assertEquals(0, song.trimIn)
        // 切一刀：两段挨着、位置连着
        val tail = ClipOps.splitAudio(lane, song, 360)!!
        assertEquals(360, song.end); assertEquals(360, tail.start); assertEquals(300, tail.trimIn); assertEquals(960, tail.end)
        assertEquals(song.offset, tail.offset)
        assertNull(ClipOps.splitAudio(lane, song, 360))
        // 再放一首：被占的位置放不下，贴到后面
        val other = AudioClip("/music/b.ogg", "b.ogg", 300)
        ClipOps.insert(lane, other, 100)
        assertEquals(960, other.start)
        // 复制一段接在最后
        val copy = ClipOps.duplicateAudio(lane, song)
        assertEquals(1260, copy.start); assertEquals(song.length, copy.length); assertEquals(song.path, copy.path)
        scene.recomputeDuration()
        assertEquals(1260 + 300, scene.maxFrame)
        assertTrue(scene.audioClipAt(100f) === song); assertTrue(scene.audioClipAt(360f) === tail); assertNull(scene.audioClipAt(30f))
        ClipOps.remove(lane, copy); ClipOps.remove(lane, other)
        scene.recomputeDuration()
        assertEquals(960, scene.maxFrame)
    }

    /** 假的播放器：按测试给的时钟往前走，跳转要花 latency 这么久才出声，做过的事都记下来。 */
    private class FakeVoice(val tag: String, val files: Map<String, Float>, val events: MutableList<String>) : AudioVoice {
        override var path: String? = null
        override var durationSeconds = 0f
        var now = 0L
        var latency = 0L
        private var playing = false
        private var pos = 0f
        private var startedAt = 0L
        private var speed = 1f
        override fun load(path: String): Boolean {
            val d = files[path] ?: run { events.add("$tag load ${File(path).name} 失败"); return false }
            this.path = path; durationSeconds = d; playing = false; pos = 0f
            events.add("$tag load ${File(path).name}")
            return true
        }
        private fun here(t: Long) = pos + maxOf(t - startedAt, 0L) / 1e9f * speed
        override fun play(seconds: Float, speed: Float): Boolean {
            val seek = abs(seconds - (if (playing) here(now) else pos)) > 0.03f
            events.add("$tag play %.2f".format(seconds) + if (seek) " 跳转" else "")
            pos = seconds; this.speed = speed; playing = true; startedAt = now + if (seek) latency else 0L
            return true
        }
        override fun pause() { if (playing) { pos = here(now); playing = false; events.add("$tag pause") } }
        override fun cue(seconds: Float) { if (!playing) { pos = seconds; events.add("$tag cue %.2f".format(seconds)) } }
        override fun position(nowNanos: Long): Float = when {
            !playing -> Float.NaN
            nowNanos < startedAt -> AudioDeck.HOLD
            here(nowNanos) >= durationSeconds -> Float.NaN
            else -> here(nowNanos)
        }
        override fun release() { if (path != null) events.add("$tag release"); path = null; playing = false }
    }

    private class DeckRig(files: Map<String, Float>) {
        val events = ArrayList<String>()
        val a = FakeVoice("甲", files, events); val b = FakeVoice("乙", files, events)
        val deck = AudioDeck(a, b) { it() }
        val clips = ArrayList<AudioClip>()
        var frame = 0f
        var now = 1_000_000_000L
        var holds = 0
        /** 时间轴每一步对应的"音乐说现在是第几秒"和画面自己的秒数的差（只在有音乐时记）。 */
        val drift = ArrayList<Float>()
        fun tick() { now += 16_666_667L; a.now = now; b.now = now }
        fun start(f: Float) { frame = f; tick(); deck.start(clips, frame, 1f, now) }
        /** 按 60fps 走到第 until 帧。 */
        fun play(until: Float) {
            var guard = 0
            while (frame < until && guard++ < 100_000) {
                tick()
                val p = deck.update(clips, frame, now)
                if (p == AudioDeck.HOLD) { holds++; continue }
                if (!p.isNaN()) drift.add(p - frame / 30f)
                frame += 0.5f
            }
        }
        fun took(prefix: String) = events.filter { it.contains(prefix) }
    }

    @Test fun audioDeckPlaysClipsInTurn() {
        // 两个文件：a 10 秒，b 5 秒。a 用其中第 2–5 秒，放在时间轴第 30–120 帧；b 整首放在第 150–300 帧
        val rig = DeckRig(mapOf("/m/a.mp3" to 10f, "/m/b.ogg" to 5f))
        val a = AudioClip("/m/a.mp3", "a.mp3", 300).also { it.start = 30; it.trimIn = 60; it.trimOut = 150 }
        val b = AudioClip("/m/b.ogg", "b.ogg", 150).also { it.start = 150 }
        rig.clips.add(a); rig.clips.add(b)

        // 从头播放：前 1 秒没有音乐，画面按自己的时钟走；这期间备用的播放器已经装好文件、挪到了 a 的起点
        rig.start(0f)
        rig.play(29f)
        assertFalse(rig.deck.sounding)
        assertTrue("空隙里就把下一段准备好：${rig.events}", rig.events.any { it.endsWith("load a.mp3") } && rig.events.any { it.endsWith("cue 2.00") })
        assertTrue(rig.took("play").isEmpty())
        // 走进 a：从文件的第 2 秒开始响，不用跳转（已经挪好了）
        rig.play(40f)
        assertTrue(rig.deck.current === a)
        assertEquals(listOf("play 2.00"), rig.took("play").map { it.substringAfter(' ') })
        // 音乐报的位置换算回时间轴，和画面对得上（差不到两帧，之后由同步时钟慢慢消掉）
        assertTrue("偏差 ${rig.drift}", rig.drift.isNotEmpty() && rig.drift.all { abs(it) < 0.07f })
        // a 响着的时候，另一个播放器去准备 b
        assertTrue(rig.events.any { it.endsWith("load b.ogg") } && rig.events.any { it.endsWith("cue 0.00") })
        // 走出 a：停下；空隙里没有音乐
        rig.play(125f)
        assertFalse(rig.deck.sounding)
        assertEquals(1, rig.took("pause").size)
        // 走进 b：换到另一个播放器，从头响，同样不用跳转
        rig.play(160f)
        assertTrue(rig.deck.current === b)
        assertEquals(listOf("play 2.00", "play 0.00"), rig.took("play").map { it.substringAfter(' ') })
        assertTrue("两段用的是不同的播放器", rig.took("play")[0][0] != rig.took("play")[1][0])
        assertEquals("整个过程画面一次都没有停下来等", 0, rig.holds)
        rig.play(310f)
        assertFalse(rig.deck.sounding)
    }

    @Test fun audioDeckSplitAndJumpCuts() {
        // 切了一刀没挪动：两段接着放，中间什么都不做，听起来和没切一样
        val rig = DeckRig(mapOf("/m/a.mp3" to 10f))
        val whole = AudioClip("/m/a.mp3", "a.mp3", 300)
        rig.clips.add(whole)
        val tail = ClipOps.splitAudio(rig.clips, whole, 100)!!
        rig.start(0f)
        rig.play(200f)
        assertTrue(rig.deck.current === tail)
        assertEquals("只按过一次播放，中间没有暂停也没有跳转：${rig.events}", listOf("甲 play 0.00"), rig.events.filter { it.contains("play") || it.contains("pause") })
        assertTrue(rig.drift.all { abs(it) < 0.05f })

        // 把后一段换成这首歌的另一处（跳着剪）：备用的播放器也装上同一个文件、提前挪到那里，到点直接换过去
        rig.deck.pause()
        rig.events.clear()
        tail.trimIn = 210; tail.trimOut = 300; tail.start = 100           // 第 100 帧起放这首歌的第 7 秒
        rig.start(40f)
        rig.play(99f)
        assertTrue("提前在另一个播放器上挪到第 7 秒：${rig.events}", rig.events.any { it.endsWith("cue 7.00") })
        rig.play(110f)
        assertTrue(rig.deck.current === tail)
        val plays = rig.took("play")
        assertEquals(2, plays.size)
        assertTrue("换过去时不用跳转：$plays", plays[1].endsWith("play 7.00"))
        assertEquals("前一个播放器停下", 1, rig.took("pause").size)
        // 换算回时间轴仍然对得上
        assertTrue(rig.drift.takeLast(10).all { abs(it) < 0.07f })

        // 播放途中片段被挪了：声音重新对到新的位置
        rig.events.clear()
        tail.start = 130                                                    // 现在第 110 帧落在空隙里
        rig.play(112f)
        assertFalse(rig.deck.sounding)
        rig.play(140f)
        assertTrue(rig.deck.sounding)
    }

    @Test fun audioDeckWaitsOnlyWhenPlayIsPressed() {
        // 按下播放时正好在一段音乐中间：要跳转，跳转期间画面等着（HOLD），声音和画面一起起步
        val rig = DeckRig(mapOf("/m/a.mp3" to 10f))
        rig.a.latency = 100_000_000L; rig.b.latency = 100_000_000L
        val song = AudioClip("/m/a.mp3", "a.mp3", 300)
        rig.clips.add(song)
        rig.start(45f)
        rig.play(60f)
        assertTrue("等了 ${rig.holds} 帧", rig.holds in 4..8)
        assertTrue(rig.events.any { it.endsWith("play 1.50 跳转") })
        assertTrue("起步之后音乐和画面是对齐的：${rig.drift.take(3)}", abs(rig.drift.first()) < 0.02f)

        // 暂停后先准备好（prepare），再按播放：不用跳转，一帧都不用等
        rig.deck.pause()
        rig.frame = 90f
        rig.deck.prepare(rig.clips, rig.frame)
        rig.events.clear(); rig.holds = 0
        rig.start(90f)
        rig.play(100f)
        assertEquals(0, rig.holds)
        assertEquals(listOf("play 3.00"), rig.took("play").map { it.substringAfter(' ') })

        // 放不了的文件：不会让画面一直等
        val bad = DeckRig(mapOf())
        bad.clips.add(AudioClip("/m/x.wma", "x.wma", 300))
        bad.start(10f)
        bad.play(40f)
        assertEquals(0, bad.holds)
        assertFalse(bad.deck.sounding)

        // 片段都删掉之后，准备的时候把不用的文件放掉
        rig.deck.pause()
        rig.clips.clear()
        rig.events.clear()
        rig.deck.prepare(rig.clips, 0f)
        assertTrue(rig.events.any { it.endsWith("release") })
    }

    @Test fun wavLengthIsReadFromTheHeader() {
        val dir = Files.createTempDirectory("lumi-wav").toFile()
        val f = File(dir, "tone.wav")
        // 8000Hz、单声道、16 位，2.5 秒
        val samples = 20000
        val data = ByteArray(samples * 2)
        val out = java.io.ByteArrayOutputStream()
        fun le32(v: Int) { out.write(v and 255); out.write(v shr 8 and 255); out.write(v shr 16 and 255); out.write(v shr 24 and 255) }
        fun le16(v: Int) { out.write(v and 255); out.write(v shr 8 and 255) }
        out.write("RIFF".toByteArray()); le32(36 + data.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(8000); le32(16000); le16(2); le16(16)
        out.write("data".toByteArray()); le32(data.size); out.write(data)
        f.writeBytes(out.toByteArray())
        assertEquals(2.5f, AudioDeck.wavSeconds(f)!!, 1e-3f)
        File(dir, "not.wav").writeText("这不是声音文件")
        assertNull(AudioDeck.wavSeconds(File(dir, "not.wav")))
        assertNull(AudioDeck.wavSeconds(File(dir, "没有这个文件.wav")))
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ 代码框：颜色和补全

    private fun kinds(code: String): List<Pair<String, Int>> {
        val s = LuaSyntax.highlight(code)
        return (0 until s.size / 3).map { code.substring(s[it * 3], s[it * 3 + 1]) to s[it * 3 + 2] }
    }

    @Test fun luaSyntaxHighlighting() {
        val code = "local m = scene.main() -- 角色\nfunction update(dt)\n  if m then m:bone(\"頭\"):rotate(0, 1.5e2, 0x1F) end\nend\nprint('a' .. 1 .. [[长\n字符串]])\n--[[ 块\n注释 ]] x = 2"
        val k = kinds(code)
        fun kind(text: String) = k.firstOrNull { it.first == text }?.second
        assertEquals(LuaSyntax.KEYWORD, kind("local")); assertEquals(LuaSyntax.KEYWORD, kind("function")); assertEquals(LuaSyntax.KEYWORD, kind("end"))
        assertEquals(LuaSyntax.API, kind("scene")); assertEquals(LuaSyntax.API, kind("print"))
        assertEquals("function 后面的名字单独一种颜色", LuaSyntax.DEFINE, kind("update"))
        assertEquals(LuaSyntax.COMMENT, kind("-- 角色")); assertEquals(LuaSyntax.COMMENT, kind("--[[ 块\n注释 ]]"))
        assertEquals(LuaSyntax.STRING, kind("\"頭\"")); assertEquals(LuaSyntax.STRING, kind("'a'")); assertEquals(LuaSyntax.STRING, kind("[[长\n字符串]]"))
        assertEquals(LuaSyntax.NUMBER, kind("1.5e2")); assertEquals(LuaSyntax.NUMBER, kind("0x1F")); assertEquals(LuaSyntax.NUMBER, kind("2"))
        assertEquals("1 .. 里的两个点不算进数字", LuaSyntax.NUMBER, kind("1"))
        // 成员名不当关键字 / 接口上色：m.end、x.scene
        assertNull(kinds("t.print = 1").firstOrNull { it.first == "print" })
        // 没写完的字符串、注释不会出错，也不会一直染到别的行
        assertEquals(listOf("\"abc" to LuaSyntax.STRING, "local" to LuaSyntax.KEYWORD), kinds("x = \"abc\nlocal y"))
        assertEquals(listOf("--[[ 没有结尾" to LuaSyntax.COMMENT), kinds("--[[ 没有结尾"))
        // 所有段都在范围之内、从前往后不重叠
        val s = LuaSyntax.highlight(code)
        var last = 0
        for (i in 0 until s.size / 3) { assertTrue(s[i * 3] >= last && s[i * 3 + 1] > s[i * 3] && s[i * 3 + 1] <= code.length); last = s[i * 3 + 1] }
        // 示例脚本都能过一遍
        for (e in com.lumi.mmd.engine.script.ScriptLibrary.EXAMPLES) assertTrue(e.name, LuaSyntax.highlight(e.source).isNotEmpty())
    }

    private fun labels(code: String): List<String> = LuaSyntax.complete(code, code.length)?.items?.map { it.label } ?: emptyList()

    /** 在 code 末尾点了某一条补全之后的文本，光标的位置用 | 标出来。 */
    private fun apply(code: String, label: String): String {
        val c = LuaSyntax.complete(code, code.length)!!
        val s = c.items.first { it.label == label }
        val out = code.substring(0, c.start) + s.insert
        val cur = out.length - s.cursorBack
        return out.substring(0, cur) + "|" + out.substring(cur)
    }

    @Test fun luaCompletion() {
        // 表名后面的点：这张表里的东西（从接口说明里读出来的）
        assertTrue(LuaSyntax.TABLES["scene"]!!.containsAll(listOf("main(", "find(", "models(", "create(")))
        assertTrue(LuaSyntax.TABLES["fx"]!!.containsAll(listOf("emitter{", "flash(", "trail{")))
        assertTrue(LuaSyntax.TABLES["time"]!!.containsAll(listOf("now", "frame", "dt", "seek(")))
        assertTrue(LuaSyntax.METHODS.containsAll(listOf("bone(", "morph(", "rotate(", "set_pos(", "color(", "impulse(", "burst(", "set{")))
        assertTrue(LuaSyntax.PROPERTIES.containsAll(listOf("opacity", "layer", "visible", "alpha", "mode")))
        assertEquals(listOf("main", "models"), labels("local m = scene.m"))
        assertEquals("local m = scene.main(|)", apply("local m = scene.m", "main"))
        assertEquals("fx.emitter{|}", apply("fx.em", "emitter"))
        assertEquals("x = time.now|", apply("x = time.n", "now"))
        assertTrue("点后面还没敲字：列出整张表", labels("physics.").containsAll(listOf("set", "get", "wind", "gravity")))
        // 冒号后面：对象的方法；别的名字后面的点：对象的属性
        assertEquals("m:bone(|)", apply("m:bo", "bone"))
        assertTrue(labels("scene.main():").contains("morph"))
        assertTrue(labels("m.op").contains("opacity"))
        // 光秃秃的词：应用的表和函数、关键字、这份代码里出现过的名字
        assertTrue(labels("sc").contains("scene"))
        assertTrue(labels("x = pr").contains("print"))
        assertEquals("x = print(|)", apply("x = pr", "print"))
        assertTrue(labels("local t = 1\nret").contains("return"))
        assertTrue("代码里用过的变量名", labels("local skirtBones = {}\nfor i, v in ipairs(ski").contains("skirtBones"))
        assertFalse("正在敲的这个词自己不算", labels("abcdef").contains("abcdef"))
        // 一行开头的整段写法，换行之后跟着当前的缩进
        assertEquals("function update(dt)\n    |\nend", apply("fu", "function update(dt) … end"))
        assertEquals("  for i = 1, 10 do\n      |\n  end", apply("  fo", "for i = 1, n do … end"))
        assertEquals("if | then\n    \nend", apply("if", "if … then … end"))
        assertFalse("不在行首就不给整段写法", labels("x = fu").any { it.contains("…") })
        // 字符串和注释里不补；数字后面的点不补；.. 不是取成员；光标在词中间不补
        assertNull(LuaSyntax.complete("m:bone(\"sc", 10))
        assertNull(LuaSyntax.complete("-- sc", 5))
        assertNull(LuaSyntax.complete("x = 3.", 6))
        assertNull(LuaSyntax.complete("s = a..", 7))
        assertNull(LuaSyntax.complete("scene", 3))
        assertNotNull("字符串结束之后可以补", LuaSyntax.complete("m:bone(\"頭\"):ro", 14))
        assertTrue(labels("m:bone(\"頭\"):ro").contains("rotate"))

        // 回车后的缩进
        fun indent(before: String) = LuaSyntax.newlineIndent(before + "\n", before.length + 1)
        assertEquals("", indent("local x = 1"))
        assertEquals("    ", indent("function update(dt)"))
        assertEquals("        ", indent("    if m then"))
        assertEquals("    ", indent("    m:bone(\"頭\"):rotate(0, 1, 0)"))
        assertEquals("    ", indent("for i = 1, 10 do -- 循环"))
        assertEquals("    ", indent("after(1, function()"))
        assertEquals("    ", indent("local e = fx.emitter{"))
        assertEquals("", indent("x = foo(1)"))
        assertEquals("      ", indent("  else"))
    }

    // ------------------------------------------------------------------ AI 通过 MCP 写脚本、运行脚本

    private class McpRig(val dir: File) : McpHost, ScriptCallbacks {
        override val scene = Scene()
        override val editor = Editor(scene)
        val log = ArrayList<String>()
        var total = 0L
        var audioUndo = 0
        var audioChanges = 0
        override val runtime = SceneRuntime(scene, RenderSettings(), this)
        val tools = SceneTools(this)
        override fun <T> render(block: () -> T): T = block()
        override val meshSession: MeshSession? get() = null
        override fun beginMesh(model: ModelInstance): MeshSession = throw UnsupportedOperationException()
        override fun endMesh() {}
        override fun libraryFiles(): List<Map<String, Any?>> = emptyList()
        override fun resolve(path: String): File? = File(dir, path).takeIf { it.isFile }
        override fun addModel(data: ModelData, kind: Int): ModelInstance { val m = ModelInstance(data, kind); scene.add(m); return m }
        override fun removeModel(model: ModelInstance) { scene.remove(model) }
        override fun saveModel(model: ModelInstance, name: String?): String = ""
        override fun saveMotion(name: String, bytes: ByteArray): String = ""
        override fun screenshot(maxSide: Int): ByteArray? = byteArrayOf(1, 2, 3)
        override fun changed() {}
        override fun setPlaying(on: Boolean) { scene.playing = on }
        override fun seekSeconds(seconds: Float) { scene.seekSeconds(seconds) }
        override fun advance(seconds: Float) { var t = 0f; while (t < seconds - 1e-4f) { runtime.update(1f / 60f); t += 1f / 60f } }
        override fun scriptNames(): List<String> = File(dir, "脚本").listFiles()?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()
        override fun readScript(name: String): String? = File(dir, "脚本/$name.lua").takeIf { it.isFile }?.readText()
        override fun saveScript(name: String, code: String): String { val f = File(dir, "脚本/$name.lua"); f.parentFile.mkdirs(); f.writeText(code); return f.absolutePath }
        override fun displayPath(path: String): String = try { File(path).relativeTo(dir).path } catch (_: Exception) { path }
        override fun scriptLog(): List<String> = log.toList()
        override fun scriptLogTotal(): Long = total
        override fun clearScriptLog() { log.clear() }
        override fun beforeAudioEdit() { audioUndo++ }
        override fun audioChanged() { audioChanges++ }
        // 日志故意只留 4 行：检验"哪些是新的"靠的是总行数而不是列表长度
        override fun scriptLog(script: Script?, text: String, error: Boolean) { log.add((if (error) "✕ " else "") + text); total++; while (log.size > 4) log.removeAt(0) }
        fun call(name: String, vararg args: Pair<String, Any?>): ToolResult = tools.call(name, linkedMapOf(*args))
    }

    /** 接口说明是当场生成的：通用的接口 + 场景里现在真有的名字；输出可以清空。 */
    @Test fun scriptDocumentIsLiveAndLogCanBeCleared() {
        val dir = Files.createTempDirectory("lumi-doc").toFile()
        val h = McpRig(dir)
        val empty = h.call("script_api").text
        assertTrue(empty.contains("现在的场景") && empty.contains("一个也没有"))
        // 新加的三组接口和示例都在文档里
        for (word in listOf("scene.main()", "canvas.new(", "mesh.new{", "sim.world{", "w.on_step", "sim.raycast(", "倒水", "重画瞳孔", "自己写的物理")) assertTrue("文档里应当有 $word", empty.contains(word))

        val m = h.addModel(boxModel(), ModelInstance.KIND_CHARACTER)
        h.editor.select(m)
        val doc = h.call("script_api").text
        assertFalse(doc.contains("一个也没有"))
        assertTrue(doc, doc.contains("#${m.id} \"${m.name}\"") && doc.contains("scene.main()（没有给脚本另外指定模型时）"))
        // 模型里真实的骨骼 / 材质名字原样出现
        for (b in m.data.bones.take(3)) assertTrue("骨骼 ${b.name}", doc.contains("\"${b.name}\""))
        for (mat in m.data.materials.take(2)) assertTrue("材质 ${mat.name}", doc.contains("\"${mat.name}\""))
        // 场景变了，文档跟着变
        val second = h.addModel(boxModel(), ModelInstance.KIND_STAGE)
        second.name = "第二个箱子"
        val both = h.call("script_api").text
        assertTrue(both.contains("模型：2 个") && both.contains("\"第二个箱子\"  stage 舞台"))
        val one = h.call("script_api", "model" to "第二个箱子").text
        assertTrue(one.contains("模型：2 个"))
        assertTrue(one.length < both.length)
        // 只要接口、不要场景；取一个示例的全文
        val bare = h.call("script_api", "scene" to false).text
        assertTrue(!bare.contains("现在的场景") && bare.contains("scene.main()") && bare.contains("布料披风"))
        val ex = h.call("script_api", "example" to "倒水")
        assertFalse(ex.text, ex.isError)
        assertTrue(ex.text, ex.text.contains("sim.world") && ex.text.contains("emit"))
        assertTrue(h.call("script_api", "example" to "没有这个").isError)
        // 写了脚本以后，文档里列着它
        val w = h.call("script_write", "name" to "打招呼", "code" to "print('一')\nprint('二')\n")
        assertFalse(w.text, w.isError)
        assertTrue(h.call("script_api").text.contains("\"打招呼\"  运行中"))

        // 清空输出：这一次还看得到，下一次就空了；之后的新输出照常出现
        val before = h.call("script_log", "clear" to true).text
        assertTrue(before, before.contains("一") && before.contains("二"))
        assertEquals("(empty)", h.call("script_log").text)
        assertTrue(h.log.isEmpty())
        h.call("lua_eval", "code" to "print('三')")
        val after = h.call("script_log").text
        assertTrue(after, after.contains("三") && !after.contains("二"))
        dir.deleteRecursively()
    }

    @Test fun mcpWritesEditsAndRunsLuaScripts() {
        val dir = Files.createTempDirectory("lumi-mcp").toFile()
        val h = McpRig(dir)
        val m = h.addModel(boxModel(), ModelInstance.KIND_CHARACTER)
        h.editor.select(m)
        val names = h.tools.list().map { it["name"] as String }
        for (t in listOf("script_api", "script_write", "script_edit", "script_run", "script_stop", "script_set", "script_read", "script_list", "lua_eval", "script_log", "advance")) assertTrue("工具清单里应当有 $t", t in names)
        assertTrue("总说明里要写明能写脚本、能运行", h.tools.instructions.contains("WRITE and RUN Lua scripts") && h.tools.instructions.contains("script_write"))
        assertTrue(h.call("script_api").text.contains("scene.main()"))

        // 写一个脚本：存盘、启动、把输出带回来
        val code = "local m = scene.main()\nprint('你好', m.name)\nlocal ticks = 0\nfunction update(dt)\n  ticks = ticks + 1\n  m.opacity = 0.5\nend\n"
        var r = h.call("script_write", "name" to "半透明", "code" to code)
        assertFalse(r.text, r.isError)
        assertTrue(r.text, r.text.contains("\"running\":true") && r.text.contains("你好") && r.text.contains("\"saved_as\":\"脚本/半透明.lua\""))
        assertEquals("脚本确实在跑", 0.5f, m.opacity, 1e-4f)
        val s = h.runtime.scripts.scripts.single()
        assertTrue("记下来的是完整路径（工程靠它把脚本找回来）：${s.file}", File(s.file).isAbsolute && File(s.file).isFile)
        assertEquals(code, File(s.file).readText())

        // 语法错：什么都不改，告诉 AI 第几行
        r = h.call("script_write", "name" to "半透明", "code" to "local x = = 1")
        assertTrue(r.text, r.isError && r.text.contains("nothing was changed") && r.text.contains("1"))
        assertEquals(0.5f, m.opacity, 1e-4f)
        assertEquals(code, s.source)

        // 运行时才出的错：结果标成出错，带行号；脚本停下，改动复原
        r = h.call("script_write", "name" to "半透明", "code" to "local m = scene.main()\nm.opacity = 0.2\nfunction update(dt)\n  local b = nil\n  b:rotate(1, 2, 3)\nend\n")
        assertTrue(r.text, r.isError && r.text.contains("\"running\":false") && r.text.contains("5") && r.text.contains("hint"))
        assertEquals("出错的脚本改过的东西自动复原", 1f, m.opacity, 1e-4f)

        // 只改其中一处（script_edit）：不用把整份代码再发一遍
        h.call("script_write", "name" to "半透明", "code" to code)
        r = h.call("script_edit", "script" to "半透明", "find" to "m.opacity = 0.5", "replace" to "m.opacity = 0.25")
        assertFalse(r.text, r.isError)
        assertTrue(r.text, r.text.contains("\"replaced\":1") && r.text.contains("\"running\":true"))
        assertEquals(0.25f, m.opacity, 1e-4f)
        assertTrue(File(s.file).readText().contains("0.25"))
        // 找不到 / 有好几处 / 改完语法不对：都明白地说出来，而且不动原来的
        r = h.call("script_edit", "script" to "半透明", "find" to "没有这一句", "replace" to "x")
        assertTrue(r.text, r.isError && r.text.contains("not found"))
        r = h.call("script_edit", "script" to "半透明", "find" to "ticks", "replace" to "k")
        assertTrue(r.text, r.isError && r.text.contains("matches 3 places"))
        r = h.call("script_edit", "script" to "半透明", "find" to "ticks = ticks + 1", "replace" to "ticks = = 2")
        assertTrue(r.text, r.isError && r.text.contains("nothing was changed"))
        assertEquals(0.25f, m.opacity, 1e-4f)
        r = h.call("script_edit", "script" to "半透明", "find" to "ticks", "replace" to "count", "all" to true)
        assertFalse(r.text, r.isError)
        assertTrue(h.call("script_read", "script" to "半透明").text.contains("count = count + 1"))

        // 停下：改动复原；再运行：又生效
        r = h.call("script_stop", "script" to "半透明")
        assertTrue(r.text, r.text.contains("\"stopped\":[\"半透明\"]"))
        assertEquals(1f, m.opacity, 1e-4f)
        r = h.call("script_run", "script" to "半透明", "screenshot" to true)
        assertFalse(r.text, r.isError)
        assertTrue(r.text.contains("\"running\":true"))
        assertNotNull("要了截图就带回来", r.image)
        assertEquals(0.25f, m.opacity, 1e-4f)
        assertTrue("输出只带这一次新出的那一行，而不是把旧的也带上：${r.text}", Regex("你好").findAll(r.text).count() == 1)

        // 脚本库里存着、场景里没有的脚本：script_run 把它放进场景再跑
        File(dir, "脚本/库里的.lua").writeText("print('从库里来')\nscene.main().layer = 3\n")
        r = h.call("script_run", "script" to "库里的")
        assertFalse(r.text, r.isError)
        assertTrue(r.text, r.text.contains("从库里来"))
        assertEquals(3, m.layer)
        assertEquals(2, h.runtime.scripts.scripts.size)
        r = h.call("script_run", "script" to "根本没有")
        assertTrue(r.isError)

        // 有时间段的脚本，时间线不在那一段里：说清楚为什么没在跑
        r = h.call("script_write", "name" to "后半段", "code" to "print('到点了')", "start_frame" to 300, "end_frame" to 400)
        assertTrue(r.text, !r.isError && r.text.contains("\"running\":false") && r.text.contains("outside the script's time range"))

        // 立刻执行一段 Lua：返回值打印出来（表会展开），出错标成出错
        r = h.call("lua_eval", "code" to "return 1 + 2, scene.main().name")
        assertTrue(r.text, !r.isError && r.text.contains("=> 3") && r.text.contains("箱子"))
        r = h.call("lua_eval", "code" to "return {1, 2, x = 'a', inner = {y = true}}")
        assertTrue(r.text, r.text.contains("{1, 2") && r.text.contains("x = \"a\"") && r.text.contains("inner = {y = true}"))
        r = h.call("lua_eval", "code" to "return scene.main():bones()")
        assertTrue("骨骼名的列表能直接看到：${r.text}", r.text.contains(m.data.bones[0].name))
        // 返回的是对象（骨骼、模型）时写出它是什么、叫什么，而不是一串 table: 1a2b3c
        r = h.call("lua_eval", "code" to "return scene.main():bones(''), scene.main()")
        assertTrue(r.text, !r.text.contains("table:") && r.text.contains("<bone 1 \"${m.data.bones[0].name}\">") && r.text.contains("<model ${m.id} \"箱子\">"))
        // print 也一样：表展开、对象写出名字；只读属性的报错说得出是哪种对象
        r = h.call("lua_eval", "code" to "print(scene.main(), {1, 'a', k = true})")
        assertTrue(r.text, r.text.contains("<model ${m.id} \"箱子\">\t{1, \"a\", k = true}"))
        r = h.call("lua_eval", "code" to "scene.main().id = 9")
        assertTrue(r.text, r.isError && r.text.contains("model.id 是只读的"))
        r = h.call("lua_eval", "code" to "local x = nil; return x.y")
        assertTrue(r.text, r.isError && r.text.contains("出错"))
        r = h.call("lua_eval", "code" to "scene.main().opacity = 0.9", "screenshot" to true)
        assertNotNull(r.image)
        // 让时间往前走：要截图就带一张，不要就只有文字
        r = h.call("advance", "seconds" to 0.2, "screenshot" to true)
        assertTrue(r.text, !r.isError && r.image != null && r.text.contains("scripts_running"))
        assertTrue(h.call("advance", "seconds" to 0.1).image == null)

        // 全部停下
        r = h.call("script_stop", "all" to true)
        assertTrue(r.text, h.runtime.scripts.scripts.none { it.running })
        dir.deleteRecursively()
    }

    @Test fun mcpArrangesMusicAndCrossFades() {
        val dir = Files.createTempDirectory("lumi-mcp2").toFile()
        val h = McpRig(dir)
        val m = h.addModel(boxModel(), ModelInstance.KIND_CHARACTER)
        val bone = m.data.bones[0].name
        // 两段动作
        ClipOps.insert(m.clips, MotionClip(slide(bone, 60, 0f, 6f), "A"), 0)
        ClipOps.insert(m.clips, MotionClip(slide(bone, 60, 20f, 20f), "B"), 60)
        h.scene.recomputeDuration()
        val second = m.clips[1].id
        var r = h.call("clips", "action" to "fade", "track" to m.id, "clip" to second, "fade" to 15)
        assertFalse(r.text, r.isError)
        assertTrue(r.text, r.text.contains("\"fade\":15"))
        assertEquals(15, m.clips[1].fadeIn)
        r = h.call("clips", "action" to "fade", "track" to m.id, "clip" to second, "fade" to 9999)
        assertEquals("过渡最长 3 秒", ClipOps.MAX_FADE, m.clips[1].fadeIn)

        // 音乐轨道：放一个 WAV（长度从文件头读出来），挪、裁、切、删
        val wav = File(dir, "bgm.wav")
        val out = java.io.ByteArrayOutputStream()
        fun le32(v: Int) { out.write(v and 255); out.write(v shr 8 and 255); out.write(v shr 16 and 255); out.write(v shr 24 and 255) }
        fun le16(v: Int) { out.write(v and 255); out.write(v shr 8 and 255) }
        val bytes = 8000 * 2 * 10
        out.write("RIFF".toByteArray()); le32(36 + bytes); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(8000); le32(16000); le16(2); le16(16)
        out.write("data".toByteArray()); le32(bytes); out.write(ByteArray(bytes))
        wav.writeBytes(out.toByteArray())
        r = h.call("clips", "action" to "add", "track" to "music", "path" to "bgm.wav", "at" to 30)
        assertFalse(r.text, r.isError)
        val song = h.scene.audioClips.single()
        assertEquals(30, song.start); assertEquals(300, song.sourceFrames); assertEquals(330, h.scene.maxFrame)
        assertTrue(r.text, r.text.contains("\"file\":\"bgm.wav\"") && r.text.contains("\"last_frame\":330"))
        h.call("clips", "action" to "trim", "track" to "music", "clip" to song.id, "start" to 90, "end" to 240)
        assertEquals(90, song.start); assertEquals(60, song.trimIn); assertEquals(240, song.end)
        r = h.call("clips", "action" to "split", "track" to "music", "clip" to song.id, "frame" to 150)
        assertEquals(2, h.scene.audioClips.size)
        h.call("clips", "action" to "move", "track" to "music", "clip" to h.scene.audioClips[1].id, "at" to 400)
        assertEquals(400, h.scene.audioClips[1].start)
        r = h.call("clips", "action" to "list")
        assertTrue(r.text, r.text.contains("\"track\":\"music\"") && r.text.contains("\"track\":\"camera\""))
        h.call("clips", "action" to "delete", "track" to "music", "clip" to song.id)
        assertEquals(1, h.scene.audioClips.size)
        assertTrue("每次改动之前都让宿主记一步撤销，改完让声音重新对上", h.audioUndo == 5 && h.audioChanges == 5)
        r = h.call("clips", "action" to "add", "track" to "music", "path" to "没有.mp3")
        assertTrue(r.isError)
        r = h.call("clips", "action" to "fade", "track" to "music", "clip" to 1, "fade" to 10)
        assertTrue(r.isError)
        dir.deleteRecursively()
    }
}

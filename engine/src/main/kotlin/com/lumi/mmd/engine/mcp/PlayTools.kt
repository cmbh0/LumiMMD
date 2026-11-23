package com.lumi.mmd.engine.mcp

import com.lumi.mmd.engine.anim.MotionClip
import com.lumi.mmd.engine.control.CharacterController
import com.lumi.mmd.engine.control.Locomotion
import com.lumi.mmd.engine.control.Rig
import com.lumi.mmd.engine.format.ModelLoader
import com.lumi.mmd.engine.physics.PhysicsTuning
import com.lumi.mmd.engine.scene.AudioClip
import com.lumi.mmd.engine.scene.ClipOps
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.script.Script
import com.lumi.mmd.engine.script.ScriptDoc
import com.lumi.mmd.engine.script.ScriptLibrary
import com.lumi.mmd.engine.util.jObj

/* 脚本、片段轨道、物理参数、操控模式的 MCP 工具。 */

private fun SceneTools.scriptOf(v: Any?): Script {
    val list = host.runtime.scripts.scripts
    return when (v) {
        is Number -> list.firstOrNull { it.id == v.toInt() }
        is String -> list.firstOrNull { it.name == v } ?: v.toIntOrNull()?.let { id -> list.firstOrNull { it.id == id } }
        else -> null
    } ?: fail("No script '$v' in the scene. Use script_list.")
}

private fun SceneTools.scriptInfo(s: Script): Map<String, Any?> = linkedMapOf(
    "id" to s.id, "name" to s.name, "enabled" to s.enabled, "running" to s.running, "error" to s.error,
    "target_model" to s.target.takeIf { it != 0 }, "start_frame" to s.start, "end_frame" to s.end.takeIf { it >= 0 }, "lines" to s.source.count { it == '\n' } + 1,
)

private fun SceneTools.clipInfo(c: MotionClip): Map<String, Any?> = linkedMapOf(
    "id" to c.id, "name" to c.name, "start" to c.start, "end" to c.end, "trim_in" to c.trimIn, "length" to c.length,
    "source_frames" to c.motion.maxFrame, "fade" to c.fadeIn, "edited" to c.owned, "file" to c.sources.firstOrNull(),
)

private fun SceneTools.audioInfo(c: AudioClip): Map<String, Any?> = linkedMapOf(
    "id" to c.id, "name" to c.name, "start" to c.start, "end" to c.end, "trim_in" to c.trimIn, "length" to c.length,
    "source_frames" to c.sourceFrames, "file" to host.displayPath(c.path),
)

/** 音乐轨道上的操作（clips 工具里 track = "music" 的那一半）。 */
private fun SceneTools.musicClips(a: Map<String, Any?>, action: String): ToolResult {
    // 读文件长度可能要花一点时间（要问系统的解码器）：在进渲染线程之前做
    var added: AudioClip? = null
    if (action == "add") {
        val path = a.s("path") ?: fail("add needs `path` (an audio file in the library; see list_library type=audio).")
        val f = host.resolve(path) ?: fail("No such file in the library: $path")
        val seconds = host.audioSeconds(f) ?: fail("Cannot read the length of $path (unsupported audio format here).")
        val frames = Math.round(seconds * 30f)
        if (frames < 1) fail("$path is empty.")
        added = AudioClip(f.absolutePath, f.name, frames)
    }
    return ok(host.render {
        val scene = host.scene
        val lane = scene.audioClips
        fun clip(): AudioClip = lane.firstOrNull { it.id == a.i("clip", -1) } ?: fail("No clip ${a["clip"]} on the music track. Use action=list.")
        when (action) {
            "list" -> {}
            "add" -> { host.beforeAudioEdit(); ClipOps.insert(lane, added!!, a.i("at", ClipOps.end(lane))) }
            "move" -> { host.beforeAudioEdit(); ClipOps.move(lane, clip(), a.i("at", 0)) }
            "trim" -> {
                host.beforeAudioEdit()
                val c = clip()
                if (a["start"] is Number) ClipOps.trimStart(lane, c, a.i("start", c.start))
                if (a["end"] is Number) ClipOps.trimEnd(lane, c, a.i("end", c.end))
            }
            "split" -> { host.beforeAudioEdit(); ClipOps.splitAudio(lane, clip(), a.i("frame", Math.round(scene.frame))) ?: fail("The frame is not inside that clip.") }
            "duplicate" -> { host.beforeAudioEdit(); ClipOps.duplicateAudio(lane, clip()) }
            "delete" -> { host.beforeAudioEdit(); ClipOps.remove(lane, clip()) }
            "fade" -> fail("Music clips have no cross-fade; `fade` is for motion and camera clips.")
            "builtins" -> fail("Built-in loops are for model tracks.")
            else -> fail("Unknown action '$action'.")
        }
        lane.sortBy { it.start }
        scene.recomputeDuration()
        if (action != "list") { host.audioChanged(); host.changed() }
        linkedMapOf<String, Any?>("clips" to lane.map { audioInfo(it) }, "last_frame" to scene.maxFrame)
    })
}

/**
 * 写 / 改 / 运行脚本之后的回报：先让场景跑一小会儿（运行时才出的错这时就会冒出来），
 * 再把脚本的状态、这段时间新出的输出带回去；要的话附一张截图。脚本出了错时整个结果标成出错，AI 一眼就知道要改。
 */
private fun SceneTools.scriptOutcome(a: Map<String, Any?>, defaultSeconds: Float, extra: Map<String, Any?>, body: () -> Script): ToolResult {
    val before = host.scriptLogTotal()
    var shot: ByteArray? = null
    var failed = false
    val info = host.render {
        val s = body()
        val secs = a.f("seconds", defaultSeconds).coerceIn(0f, 20f)
        // 至少走一帧，脚本才会真的启动
        if (s.enabled) host.advance(maxOf(secs, 0.02f))
        host.changed()
        failed = s.error != null
        val out = LinkedHashMap<String, Any?>(scriptInfo(s))
        if (s.enabled && !s.running && s.error == null && s.end >= 0) {
            out["note"] = "Not running right now: the timeline (frame ${Math.round(host.scene.frame)}) is outside the script's time range ${s.start}-${s.end}."
        }
        out.putAll(extra)
        if (a.b("screenshot", false)) shot = host.screenshot(a.i("size", 768).coerceIn(256, 1280))
        out
    }
    val all = host.scriptLog()
    val fresh = (host.scriptLogTotal() - before).coerceIn(0L, all.size.toLong()).toInt()
    val out = LinkedHashMap(info)
    out["log"] = all.takeLast(minOf(fresh, 40))
    if (failed) out["hint"] = "The script stopped with an error and everything it changed was reverted. Fix the code and call script_write or script_edit again."
    return ToolResult(com.lumi.mmd.engine.util.Json.stringify(out), shot, "image/jpeg", isError = failed)
}

/** 轨道：某个模型的动作轨，或者镜头轨（track = "camera"）。返回 (片段列表, 模型)。 */
private fun SceneTools.lane(a: Map<String, Any?>): Pair<MutableList<MotionClip>, ModelInstance?> {
    val t = a["track"]
    if (t is String && t.equals("camera", true)) return host.scene.cameraClips to null
    val m = model(a, "track")
    return m.clips to m
}

/** physics 工具里 set 的参数表：一项一项列出来（空的 properties 有些客户端不收）。 */
private fun SceneTools.physicsProps(): Map<String, Any?> = linkedMapOf(
    "gravity" to pNum("Gravity multiplier, 1 = normal (-5..5)."),
    "gravityX" to pNum("Sideways acceleration along X (-500..500)."),
    "gravityZ" to pNum("Sideways acceleration along Z (-500..500)."),
    "windX" to pNum("Wind acceleration along X; 50..200 is a visible breeze (-1000..1000)."),
    "windY" to pNum("Wind acceleration along Y (-1000..1000)."),
    "windZ" to pNum("Wind acceleration along Z (-1000..1000)."),
    "turbulence" to pNum("Gustiness of the wind, 0..1."),
    "mass" to pNum("Mass multiplier, 1 = as authored (0.05..20)."),
    "damping" to pNum("Linear damping multiplier (0..20)."),
    "angularDamping" to pNum("Angular damping multiplier: larger = swinging stops sooner (0..20)."),
    "stiffness" to pNum("Spring strength multiplier: larger = stiffer, keeps the authored shape (0..20)."),
    "limits" to pNum("Joint range multiplier (0..4)."),
    "colliderSize" to pNum("Collider size multiplier; raise it a little when a skirt clips through legs (0.2..3)."),
    "hz" to pInt("Simulation steps per second (30..240)."),
    "maxSubSteps" to pInt("Maximum catch-up steps per frame (1..12)."),
    "iterations" to pInt("Constraint solver iterations per step (1..60)."),
    "ground" to pBool("Whether hair / cloth collides with the ground."),
)

internal fun SceneTools.registerPlayTools() {
    val pModel = pRef("Model id or name.")

    // ------------------------------------------------------------------ Lua 脚本

    val pSeconds = pNum("Let the scene run this many simulated seconds before reporting, so errors that only show up after a while are caught (0-20).")
    val pShot = pBool("Also return a screenshot of the result (saves a separate `screenshot` call).")
    val pShotSize = pInt("Screenshot longest side in pixels (256-1280, default 768).")

    tool("script_api", "Read the complete scripting document, built at this moment: the Lua API (functions, conventions, limits) followed by what is actually in the scene right now - every model with its real bone names and positions, morphs, materials and rigid bodies, camera, light, physics, running scripts - and the list of built-in examples. Call this before writing a script and again after the scene changes; use the names exactly as listed. `model` limits the scene part to one model, `limit` caps how many names are listed per group, `scene=false` returns the API text only, `example` returns the full source of one example.",
        linkedMapOf("example" to pStr("Name of a built-in example to return in full."),
            "model" to pRef("Only describe this model (id or name). Default: every model, the script target first."),
            "limit" to pInt("Most names listed per group, e.g. bones of one model (default ${ScriptDoc.DEFAULT_LIMIT}, 20-5000)."),
            "scene" to pBool("Include the live scene part (default true)."))) { a ->
        val ex = a.s("example")
        if (ex != null) {
            val e = ScriptLibrary.EXAMPLES.firstOrNull { it.name == ex } ?: fail("No example named '$ex'. Examples: " + ScriptLibrary.EXAMPLES.joinToString { it.name })
            ToolResult("-- ${e.name}: ${e.about}\n" + e.source.trim())
        } else if (!a.b("scene", true)) {
            ToolResult(ScriptLibrary.REFERENCE + "\n\n" + ScriptDoc.examples())
        } else {
            val limit = a.i("limit", ScriptDoc.DEFAULT_LIMIT).coerceIn(20, 5000)
            ToolResult(host.render {
                val only = if (a["model"] != null) model(a) else null
                ScriptDoc.full(host.scene, host.runtime.scripts.scripts, host.editor.selectedModel, only, limit)
            })
        }
    }

    tool("script_list", "Scripts in the scene (id, name, enabled, running, error, time range, target model) and the names of scripts saved in the library.") {
        val saved = host.scriptNames()
        ok(host.render { linkedMapOf("scripts" to host.runtime.scripts.scripts.map { scriptInfo(it) }, "library" to saved, "console_active" to host.runtime.scripts.consoleActive) })
    }

    tool("script_write", "Write a Lua script and run it. Creates the script in the scene (or replaces the one with the same name), saves it to the script library and starts it. " +
        "The code is syntax-checked first (a syntax error changes nothing); then the scene runs for `seconds` so runtime errors show up. The result has `running`, `error` (with the line number) and the new `log` lines (print output). " +
        "A script that fails is stopped and everything it changed is reverted. Scripts keep running every frame and are saved with the project.\n" +
        "Minimal example:\n" +
        "  local m = scene.main()\n" +
        "  function update(dt)\n" +
        "    m:bone(\"頭\"):rotate(0, math.sin(time.real * 2) * 20, 0)\n" +
        "  end\n" +
        "Call script_api first for the full API. To change a few lines of an existing script use script_edit; for one-off code use lua_eval.",
        linkedMapOf("name" to pStr("Script name."), "code" to pStr("Lua source."), "enable" to pBool("Start it right away (default true)."),
            "target" to pRef("Model the script acts on (what scene.main() returns). Default: the selected character."),
            "start_frame" to pInt("Only run from this timeline frame..."), "end_frame" to pInt("...to this frame (omit to run all the time)."),
            "seconds" to pSeconds, "screenshot" to pShot, "size" to pShotSize),
        listOf("name", "code")) { a ->
        val name = (a.s("name") ?: fail("name is required")).trim().ifEmpty { fail("name is empty") }.take(60)
        val code = a.s("code") ?: fail("code is required")
        if (code.length > 200_000) fail("Script is too long.")
        val engine = host.runtime.scripts
        engine.check(code, name)?.let { return@tool ToolResult("Syntax error, nothing was changed: $it", isError = true) }
        val path = try { host.saveScript(name, code) } catch (e: Exception) { "" }
        scriptOutcome(a, 0.3f, linkedMapOf("saved_as" to host.displayPath(path))) {
            val s = engine.scripts.firstOrNull { it.name == name } ?: engine.add(name, code, path)
            engine.setSource(s, code)
            if (path.isNotEmpty()) s.file = path
            if (a.containsKey("target")) s.target = if (a["target"] == null || a["target"] == "") 0 else model(a, "target").id
            if (a["start_frame"] is Number) s.start = a.i("start_frame", 0).coerceAtLeast(0)
            s.end = if (a["end_frame"] is Number) a.i("end_frame", -1) else -1
            engine.setEnabled(s, a.b("enable", true))
            s
        }
    }

    tool("script_edit", "Change part of an existing scene script without resending all of it: replaces the text `find` with `replace`, saves, and restarts the script. " +
        "`find` must match exactly one place (make it longer if it matches several, or pass all=true). The new code is syntax-checked first; on a syntax error nothing changes. Reports like script_write.",
        linkedMapOf("script" to pRef("Script id or name."), "find" to pStr("Exact text to look for in the current source."), "replace" to pStr("Text to put in its place (empty string deletes it)."),
            "all" to pBool("Replace every occurrence instead of requiring exactly one."), "seconds" to pSeconds, "screenshot" to pShot, "size" to pShotSize),
        listOf("script", "find", "replace")) { a ->
        val find = a.s("find")?.takeIf { it.isNotEmpty() } ?: fail("`find` is empty.")
        val replace = a.s("replace") ?: fail("`replace` is required (use an empty string to delete).")
        val engine = host.runtime.scripts
        val (name, old) = host.render { scriptOf(a["script"]).let { it.name to it.source } }
        var count = 0; var i = old.indexOf(find)
        while (i >= 0) { count++; i = old.indexOf(find, i + find.length) }
        if (count == 0) fail("`find` was not found in '$name'. Call script_read to see the current source (whitespace must match exactly).")
        if (count > 1 && !a.b("all", false)) fail("`find` matches $count places in '$name'. Make it longer so it is unique, or pass all=true.")
        val code = old.replace(find, replace)
        engine.check(code, name)?.let { return@tool ToolResult("Syntax error after the edit, nothing was changed: $it", isError = true) }
        val path = try { host.saveScript(name, code) } catch (e: Exception) { "" }
        scriptOutcome(a, 0.3f, linkedMapOf("replaced" to count, "lines" to code.count { it == '\n' } + 1)) {
            val s = scriptOf(a["script"])
            val was = s.enabled || s.error != null
            engine.setSource(s, code)
            if (path.isNotEmpty()) s.file = path
            // 原来开着的（或者是因为出错停下的）改完接着跑；本来就关着的不擅自打开
            if (was) engine.setEnabled(s, true)
            s
        }
    }

    tool("script_run", "Run a script: start it (or restart it from the top) and report what happened — `running`, `error`, new `log` lines. " +
        "Works for a script in the scene, or one saved in the library (it is added to the scene first). A script only runs while the timeline is inside its time range, if it has one.",
        linkedMapOf("script" to pRef("Script id or name (scene or library)."), "target" to pRef("Model it acts on; \"\" (empty) = the selected character."),
            "seconds" to pSeconds, "screenshot" to pShot, "size" to pShotSize),
        listOf("script")) { a ->
        val engine = host.runtime.scripts
        val v = a["script"]
        val inScene = host.render { engine.scripts.any { it.name == v || (v is Number && it.id == v.toInt()) || (v is String && v.toIntOrNull() == it.id) } }
        // 场景里没有：去脚本库里找
        val fromLibrary = if (inScene) null else (v as? String)?.let { n -> host.readScript(n)?.let { n to it } } ?: fail("No script '$v' in the scene or the library. Use script_list.")
        val libPath = fromLibrary?.let { (n, code) -> try { host.saveScript(n, code) } catch (e: Exception) { "" } }
        scriptOutcome(a, 0.5f, emptyMap()) {
            val s = if (fromLibrary != null) engine.add(fromLibrary.first, fromLibrary.second, libPath ?: "") else scriptOf(v)
            if (a.containsKey("target")) s.target = if (a["target"] == null || a["target"] == "") 0 else model(a, "target").id
            engine.restart(s)
            engine.setEnabled(s, true)
            s
        }
    }

    tool("script_stop", "Stop a running script (or all of them). Everything the script changed is reverted and its effects, models and on-screen texts are removed. The script stays in the scene, switched off; script_run starts it again.",
        linkedMapOf("script" to pRef("Script id or name."), "all" to pBool("Stop every script in the scene."))) { a ->
        ok(host.render {
            val engine = host.runtime.scripts
            val list = if (a.b("all", false)) engine.scripts.toList() else listOf(scriptOf(a["script"] ?: fail("Pass `script` (id or name) or all=true.")))
            for (s in list) engine.setEnabled(s, false)
            host.advance(0.05f)
            host.changed()
            linkedMapOf<String, Any?>("stopped" to list.map { it.name }, "scripts" to engine.scripts.map { scriptInfo(it) })
        })
    }

    tool("script_set", "Change a scene script's settings: time range, target model, enable / disable, or delete it from the scene. (To run or stop a script use script_run / script_stop.)",
        linkedMapOf("script" to pRef("Script id or name."), "enable" to pBool("Run or stop."), "target" to pRef("Model it acts on; \"\" (empty) = the selected character."),
            "start_frame" to pInt("First frame of its time range."), "end_frame" to pInt("Last frame; -1 = no end."), "delete" to pBool("Remove it from the scene.")),
        listOf("script")) { a ->
        ok(host.render {
            val engine = host.runtime.scripts
            val s = scriptOf(a["script"])
            if (a.b("delete", false)) {
                engine.remove(s); host.changed()
                return@render linkedMapOf<String, Any?>("deleted" to s.name, "note" to "Removed from the scene and its changes reverted. The saved copy stays in the script library (script_run can bring it back; the user can delete the file in the app).")
            }
            if (a.containsKey("target")) s.target = if (a["target"] == null || a["target"] == "") 0 else model(a, "target").id
            if (a["start_frame"] is Number) s.start = a.i("start_frame", 0).coerceAtLeast(0)
            if (a["end_frame"] is Number) s.end = a.i("end_frame", -1)
            if (a["enable"] is Boolean) engine.setEnabled(s, a.b("enable", true))
            if (s.enabled) host.advance(0.1f)
            host.changed()
            scriptInfo(s)
        })
    }

    tool("script_read", "Return the source of a script in the scene, or of a saved script in the library.",
        linkedMapOf("script" to pRef("Script id or name.")), listOf("script")) { a ->
        val v = a["script"]
        val inScene = host.render { host.runtime.scripts.scripts.firstOrNull { it.name == v || (v is Number && it.id == v.toInt()) }?.source }
        ToolResult(inScene ?: (v as? String)?.let { host.readScript(it) } ?: fail("No script '$v'."))
    }

    tool("lua_eval", "Execute Lua code right now and return what it printed and returned (tables are printed out). Runs in the console environment, which keeps its variables, effects and changes between calls " +
        "(define update(dt) there and it runs every frame). Use it to look things up (`return scene.main():bones(\"裙\")`), to try an API call, or to run a one-off action. " +
        "reset=true wipes the console and reverts its changes. Console changes are temporary: they are not saved with the project — use script_write for anything that should last.",
        linkedMapOf("code" to pStr("Lua code (an expression or statements)."), "reset" to pBool("Clear the console environment first."),
            "seconds" to pSeconds, "screenshot" to pShot, "size" to pShotSize)) { a ->
        var shot: ByteArray? = null
        var failed = false
        val out = host.render {
            val engine = host.runtime.scripts
            if (a.b("reset", false)) engine.resetConsole()
            val code = a.s("code")
            val r = if (code.isNullOrBlank()) "" else engine.eval(code)
            failed = !code.isNullOrBlank() && engine.evalFailed
            host.advance(a.f("seconds", 0.05f).coerceIn(0f, 20f))
            host.changed()
            if (a.b("screenshot", false)) shot = host.screenshot(a.i("size", 768).coerceIn(256, 1280))
            r
        }
        ToolResult(out.ifEmpty { "ok" }, shot, "image/jpeg", isError = failed)
    }

    tool("script_log", "Recent script output: print() lines and errors, oldest first. Pass clear=true to empty the log afterwards, so the next read only shows new output.",
        linkedMapOf("limit" to pInt("How many lines (default 40)."), "clear" to pBool("Empty the log after returning these lines (the app's script panel is cleared too)."))) { a ->
        val text = host.scriptLog().takeLast(a.i("limit", 40).coerceIn(1, 300)).joinToString("\n").ifEmpty { "(empty)" }
        if (a.b("clear", false)) host.clearScriptLog()
        ToolResult(text)
    }

    tool("advance", "Let simulated time pass without waiting: scripts, physics, particles and the character controller run for this many seconds (the timeline only moves if it is playing). " +
        "Use it to see an effect after it has developed: screenshot=true returns the picture in the same call.",
        linkedMapOf("seconds" to pNum("How long to simulate (max 20)."), "screenshot" to pShot, "size" to pShotSize)) { a ->
        var shot: ByteArray? = null
        val out = host.render {
            host.advance(a.f("seconds", 1f).coerceIn(0f, 20f))
            val rt = host.runtime
            if (a.b("screenshot", false)) shot = host.screenshot(a.i("size", 768).coerceIn(256, 1280))
            linkedMapOf("frame" to Math.round(host.scene.frame), "particles" to rt.effects.particleCount,
                "scripts_running" to rt.scripts.scripts.count { it.running }, "script_errors" to rt.scripts.scripts.filter { it.error != null }.map { linkedMapOf("name" to it.name, "error" to it.error) })
        }
        ToolResult(com.lumi.mmd.engine.util.Json.stringify(out), shot, "image/jpeg")
    }

    // ------------------------------------------------------------------ 片段轨道

    tool("clips", "Arrange clips on tracks, like a video editor. Each model has one track of motion clips; the camera has a track (track = \"camera\"); the music has a track (track = \"music\"). " +
        "A motion clip plays a VMD (or a built-in locomotion loop) from `start`; a music clip plays a piece of an audio file. Moving, trimming and splitting do not touch the keyframes / the audio file. " +
        "Keyframe tools always edit the clip under the given frame.\n" +
        "`fade` (frames) on a motion or camera clip blends smoothly from the previous clip on the same track during the clip's first frames instead of cutting; 0 = hard cut.\n" +
        "actions: list | add | move | trim | split | duplicate | delete | fade | builtins",
        linkedMapOf("action" to pEnum("What to do.", "list", "add", "move", "trim", "split", "duplicate", "delete", "fade", "builtins"),
            "track" to pRef("Model id or name, \"camera\", or \"music\"."), "clip" to pInt("Clip id (from list)."),
            "path" to pStr("add: a .vmd (model / camera track) or an audio file (music track) in the library."),
            "builtin" to pStr("add: a built-in loop for this model: idle walk run sprint crouch crouch_walk prone crawl (see builtins)."),
            "at" to pInt("add / move: timeline frame where the clip should start (default for add: after the last clip)."), "repeat" to pInt("add builtin: how many times to loop it (default 4)."),
            "start" to pInt("trim: new first frame on the timeline (cuts the head)."), "end" to pInt("trim: new last frame on the timeline (cuts or extends the tail; music cannot extend past the file)."),
            "frame" to pInt("split: timeline frame to cut at."), "fade" to pInt("add / fade: cross-fade length in frames from the previous clip (0-90; 30 = one second).")),
        listOf("action")) { a ->
        val action = a.s("action") ?: "list"
        val isMusic = (a["track"] as? String)?.let { it.equals("music", true) || it.equals("audio", true) } == true
        if (isMusic) return@tool musicClips(a, action)
        ok(host.render {
            val scene = host.scene
            if (action == "list" && a["track"] == null) {
                return@render linkedMapOf<String, Any?>(
                    "tracks" to scene.models.map { m -> linkedMapOf("track" to m.id, "name" to m.name, "clips" to m.clips.map { clipInfo(it) }) } +
                        listOf(linkedMapOf("track" to "camera", "name" to "camera", "clips" to scene.cameraClips.map { clipInfo(it) })) +
                        listOf(linkedMapOf("track" to "music", "name" to "music", "clips" to scene.audioClips.map { audioInfo(it) })),
                    "last_frame" to scene.maxFrame)
            }
            if (action == "builtins") {
                val m = model(a, "track")
                val rig = Rig(m.data)
                if (!rig.usable) fail("${m.name} has no standard MMD leg / IK bones, so the built-in locomotion loops cannot be generated for it.")
                return@render linkedMapOf<String, Any?>("builtins" to Locomotion(rig).all.map { linkedMapOf("id" to it.id, "title" to it.title, "frames" to it.frames, "travels" to (it.stride > 0f)) })
            }
            val (lane, m) = lane(a)
            fun clip(): MotionClip = lane.firstOrNull { it.id == a.i("clip", -1) } ?: fail("No clip ${a["clip"]} on this track. Use action=list.")
            when (action) {
                "list" -> {}
                "add" -> {
                    host.beforeClipEdit(m)
                    val c: MotionClip
                    val builtin = a.s("builtin")
                    if (builtin != null) {
                        if (m == null) fail("Built-in loops are for models, not the camera.")
                        val rig = Rig(m.data)
                        if (!rig.usable) fail("${m.name} has no standard MMD leg / IK bones.")
                        val src = Locomotion(rig).find(builtin) ?: fail("No built-in loop '$builtin'.")
                        val motion = src.motion.copy()
                        c = MotionClip(motion, src.title)
                        c.owned = true
                        // 循环动作：把片段拉长到重复若干次（片段比动作长的部分停在最后一帧，所以这里直接把关键帧铺开）
                        val times = a.i("repeat", 4).coerceIn(1, 200)
                        if (times > 1 && src.frames > 0) for (k in 1 until times) for ((name, t) in src.motion.boneTracks) {
                            val dst = motion.boneTrack(name)
                            for (i in 0 until t.count) { val o = i * 7; dst.set(t.frames[i] + k * src.frames, t.values[o], t.values[o + 1], t.values[o + 2], t.values[o + 3], t.values[o + 4], t.values[o + 5], t.values[o + 6]) }
                        }
                        motion.recomputeMaxFrame()
                    } else {
                        val path = a.s("path") ?: fail("add needs `path` (a library .vmd) or `builtin`.")
                        val f = host.resolve(path) ?: fail("No such file in the library: $path")
                        val motion = try { ModelLoader.loadMotion(f) } catch (e: Exception) { fail("Cannot read $path: ${e.message}") }
                        if (m == null && !motion.hasCamera) fail("$path has no camera keyframes.")
                        if (m != null && !motion.hasModelMotion) fail("$path has no bone / morph keyframes (is it a camera motion? use track=\"camera\").")
                        c = MotionClip(motion, f.nameWithoutExtension)
                        c.sources = listOf(f.absolutePath)
                    }
                    if (a["fade"] is Number) c.fadeIn = a.i("fade", 0).coerceIn(0, ClipOps.MAX_FADE)
                    ClipOps.insert(lane, c, a.i("at", ClipOps.end(lane)))
                }
                "move" -> { host.beforeClipEdit(m); ClipOps.move(lane, clip(), a.i("at", 0)) }
                "trim" -> {
                    host.beforeClipEdit(m)
                    val c = clip()
                    if (a["start"] is Number) ClipOps.trimStart(lane, c, a.i("start", c.start))
                    if (a["end"] is Number) ClipOps.trimEnd(lane, c, a.i("end", c.end))
                }
                "split" -> { host.beforeClipEdit(m); ClipOps.split(lane, clip(), a.i("frame", Math.round(scene.frame))) ?: fail("The frame is not inside that clip.") }
                "duplicate" -> { host.beforeClipEdit(m); ClipOps.duplicate(lane, clip()) }
                "delete" -> { host.beforeClipEdit(m); ClipOps.remove(lane, clip()) }
                "fade" -> {
                    val c = clip()
                    if (a["fade"] !is Number) fail("fade needs `fade` (frames, 0 = hard cut).")
                    host.beforeClipEdit(m)
                    c.fadeIn = a.i("fade", 0).coerceIn(0, ClipOps.MAX_FADE)
                }
                else -> fail("Unknown action '$action'.")
            }
            if (m != null) m.sortClips() else scene.sortCameraClips()
            m?.let { it.motionRevision++; it.poseDirty = true }
            scene.recomputeDuration()
            host.changed()
            linkedMapOf<String, Any?>("clips" to lane.map { clipInfo(it) }, "last_frame" to scene.maxFrame)
        })
    }

    // ------------------------------------------------------------------ 物理参数

    tool("physics", "Read or tune the hair / cloth physics. Without `model` the values are global; with `model` they are that model's own multipliers on top of the global ones.\n" +
        "Parameters: gravity (multiplier, 1 = normal), gravityX, gravityZ, windX, windY, windZ (accelerations, 50..200 is a visible breeze), turbulence (0..1 gustiness), " +
        "mass, damping, angularDamping, stiffness (spring strength), limits (joint range), colliderSize — all multipliers, 1 = as authored; hz (steps per second), maxSubSteps, iterations, ground (true/false).",
        linkedMapOf("model" to pModel, "set" to pObj(physicsProps(), "Parameters to change, e.g. {\"windX\": 120, \"stiffness\": 0.5}. Only the ones given are touched."),
            "reset" to pBool("Back to defaults (applied before `set`)."), "enabled" to pBool("Turn physics on / off (globally, or for this model)."),
            "settle" to pBool("Snap hair / cloth back to the rest pose and let it fall again."))) { a ->
        ok(host.render {
            val scene = host.scene
            val m = if (a["model"] != null) model(a) else null
            val t: PhysicsTuning = if (m != null) (m.physicsTuning ?: PhysicsTuning().also { if (a["set"] != null || a.b("reset", false)) m.physicsTuning = it }) else scene.physics
            if (a.b("reset", false)) { if (m != null) m.physicsTuning = null else t.reset() }
            a["set"].jObj()?.let { set ->
                val target = if (m != null) (m.physicsTuning ?: PhysicsTuning().also { m.physicsTuning = it }) else scene.physics
                for ((k, v) in set) {
                    val f = when (v) {
                        is Number -> v.toFloat(); is Boolean -> if (v) 1f else 0f
                        is String -> v.trim().toFloatOrNull() ?: when (v.trim().lowercase()) { "true" -> 1f; "false" -> 0f; else -> fail("$k must be a number") }
                        null -> continue
                        else -> fail("$k must be a number")
                    }
                    if (!target.set(k, f)) fail("Unknown physics parameter '$k'. Known: " + PhysicsTuning.NAMES.joinToString(" "))
                }
            }
            if (a["enabled"] is Boolean) { if (m != null) m.physicsEnabled = a.b("enabled", true) else scene.physicsEnabled = a.b("enabled", true) }
            if (a.b("settle", false)) { if (m != null) m.physics?.requestReset() else for (x in scene.models) x.physics?.requestReset() }
            host.changed()
            val cur = if (m != null) m.physicsTuning else scene.physics
            linkedMapOf<String, Any?>(
                "scope" to (m?.name ?: "global"), "enabled" to (if (m != null) m.physicsEnabled else scene.physicsEnabled),
                "values" to (cur?.toMap() ?: "(this model has no overrides)"),
                "bodies" to m?.physics?.bodyCount, "joints" to m?.physics?.jointCount,
            )
        })
    }

    // ------------------------------------------------------------------ 操控模式

    tool("controller", "Third-person character control (the on-screen joystick mode): walk / run / sprint, crouch, prone, jump, with a follow camera. " +
        "enable=true takes over a character (its clips stop driving it until enable=false). `move` is the stick [x, y] relative to the camera (y = forward, length 0..1; " +
        "above 0.55 runs). `press` taps a button: jump, crouch (toggle), prone (toggle). `seconds` then simulates that much time with the input held, so a screenshot shows the result.",
        linkedMapOf("model" to pModel, "enable" to pBool("Start / stop controlling."), "move" to pVec(2, "Stick [x, y], camera-relative."),
            "sprint" to pBool("Hold sprint."), "press" to pEnum("Tap a button.", "jump", "crouch", "prone"), "seconds" to pNum("Simulate this long (max 20)."))) { a ->
        ok(host.render {
            val rt = host.runtime
            if (a["enable"] is Boolean) {
                if (a.b("enable", true)) {
                    val m = if (a["model"] != null) model(a) else host.editor.selectedModel ?: host.scene.models.firstOrNull { it.isCharacter } ?: fail("There is no character to control.")
                    if (m.skeleton == null) fail("${m.name} has no skeleton.")
                    rt.startControl(m)
                } else rt.stopControl()
                host.changed()
            }
            val c = rt.controller
            if (c != null) {
                a.vec("move", 2)?.let { rt.input.moveX = it[0].coerceIn(-1f, 1f); rt.input.moveY = it[1].coerceIn(-1f, 1f) }
                if (a["sprint"] is Boolean) rt.sprintLock = a.b("sprint", false)
                a.s("press")?.let { rt.input.button(it, true); rt.input.button(it, false) }
                if (a["seconds"] is Number) host.advance(a.f("seconds", 0f).coerceIn(0f, 20f))
            } else if (a["move"] != null || a["press"] != null) fail("Controller mode is off. Call controller {enable: true} first.")
            controllerInfo(rt.controller)
        })
    }
}

private fun SceneTools.controllerInfo(c: CharacterController?): Map<String, Any?> {
    if (c == null) return linkedMapOf("enabled" to false)
    val w = FloatArray(3)
    c.worldPosition(w)
    return linkedMapOf(
        "enabled" to true, "model" to c.model.id, "animated" to c.animated,
        "position" to vecOut(w), "facing_deg" to round(Math.toDegrees(c.yaw.toDouble()).toFloat()),
        "stance" to arrayOf("stand", "crouch", "prone")[c.stance], "on_ground" to c.grounded, "speed" to round(c.speed), "state" to c.stateName,
        "leg_length" to round(c.rig.leg),
    )
}

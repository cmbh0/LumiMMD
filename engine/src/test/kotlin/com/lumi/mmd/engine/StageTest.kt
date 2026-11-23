package com.lumi.mmd.engine

import com.lumi.mmd.engine.mcp.McpHost
import com.lumi.mmd.engine.mcp.SceneTools
import com.lumi.mmd.engine.mcp.ToolResult
import com.lumi.mmd.engine.mesh.EditMesh
import com.lumi.mmd.engine.mesh.MeshPrimitives
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.physics.MeshShape
import com.lumi.mmd.engine.render.RenderSettings
import com.lumi.mmd.engine.scene.Editor
import com.lumi.mmd.engine.scene.Floor
import com.lumi.mmd.engine.scene.MeshSession
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.scene.Scene
import com.lumi.mmd.engine.scene.SceneRuntime
import com.lumi.mmd.engine.script.Script
import com.lumi.mmd.engine.script.ScriptCallbacks
import com.lumi.mmd.engine.script.ScriptLibrary
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4.2 新增的部分：模型的碰撞属性、可以改 / 删 / 加回来的地板、三渲二和别的画面设置（脚本和 AI 都能动）。都不需要 GPU。 */
class StageTest {
    private class Rig : McpHost, ScriptCallbacks {
        override val scene = Scene()
        override val editor = Editor(scene)
        val settings = RenderSettings()
        val log = ArrayList<String>()
        val errors = ArrayList<String>()
        var looks = 0
        override val runtime = SceneRuntime(scene, settings, this)
        val tools = SceneTools(this)
        override fun <T> render(block: () -> T): T = block()
        override val meshSession: MeshSession? get() = null
        override fun beginMesh(model: ModelInstance): MeshSession = throw UnsupportedOperationException()
        override fun endMesh() {}
        override fun libraryFiles(): List<Map<String, Any?>> = emptyList()
        override fun resolve(path: String): File? = null
        override fun addModel(data: ModelData, kind: Int): ModelInstance { val m = ModelInstance(data, kind); scene.add(m); return m }
        override fun removeModel(model: ModelInstance) { scene.remove(model) }
        override fun saveModel(model: ModelInstance, name: String?): String = ""
        override fun saveMotion(name: String, bytes: ByteArray): String = ""
        override fun screenshot(maxSide: Int): ByteArray? = null
        override fun changed() {}
        override fun lookChanged() { looks++ }
        override fun setPlaying(on: Boolean) { scene.playing = on }
        override fun seekSeconds(seconds: Float) { scene.seekSeconds(seconds) }
        override fun advance(seconds: Float) = run(seconds)
        override fun scriptNames(): List<String> = emptyList()
        override fun readScript(name: String): String? = null
        override fun saveScript(name: String, code: String): String = ""
        override fun displayPath(path: String): String = path
        override fun scriptLog(): List<String> = log.toList()
        override fun scriptLogTotal(): Long = log.size.toLong()
        override fun clearScriptLog() { log.clear() }
        override fun scriptLog(script: Script?, text: String, error: Boolean) { if (error) errors.add(text) else log.add(text) }
        fun call(name: String, vararg args: Pair<String, Any?>): ToolResult = tools.call(name, linkedMapOf(*args))
        fun run(seconds: Float) { var t = 0f; while (t < seconds - 1e-4f) { runtime.update(1f / 60f); t += 1f / 60f } }
        fun start(code: String): Script {
            val s = runtime.scripts.add("t", code)
            runtime.scripts.setEnabled(s, true)
            runtime.update(1f / 60f)
            assertNull(errors.joinToString("\n"), s.error)
            return s
        }
        fun stop(s: Script) { runtime.scripts.setEnabled(s, false); runtime.update(1f / 60f) }
        /** 脚本里 print 出来的最后一行，按空格拆成数。 */
        fun numbers(): List<Float> = log.last().trim().split(Regex("\\s+")).map { it.toFloat() }
    }

    /** 一张桌子：宽 w、厚 h、深 d，中心在原点（桌面在 y = h / 2）。 */
    private fun slab(w: Float, h: Float, d: Float, name: String = "桌子"): ModelData {
        val em = EditMesh.createEmpty(name)
        MeshPrimitives.box(em, w, h, d)
        em.recalcNormals()
        return em.build().data
    }

    /** 往 (x, z) 上方丢一个半径 0.5 的球，过 seconds 秒看它停在多高。 */
    private fun drop(h: Rig, x: Float, z: Float, seconds: Float = 4f): Float {
        val s = h.start("""
            b = sim.body{shape = "sphere", size = 0.5, mass = 1, pos = {$x, 12, $z}}
            function update(dt) local x, y, z = b:pos(); print(y) end
        """.trimIndent())
        h.run(seconds)
        val y = h.numbers()[0]
        h.stop(s)
        return y
    }

    // ------------------------------------------------------------------ 模型的碰撞属性

    @Test fun triangleMeshShapeAnswersRaysAndContacts() {
        val d = slab(10f, 2f, 10f)
        val shape = MeshShape(d.positions, d.indices)
        assertEquals(12, shape.triangleCount)
        val out = FloatArray(6)
        // 从上往下：打在桌面上（y = 1），法线朝上
        val t = shape.raycast(1f, 9f, -2f, 0f, -1f, 0f, 100f, out)
        assertEquals(8f, t, 1e-3f)
        assertEquals(1f, out[1], 1e-3f)
        // 旁边没有东西
        assertTrue(shape.raycast(20f, 9f, 0f, 0f, -1f, 0f, 100f, out) < 0f)
        // 太短够不着
        assertTrue(shape.raycast(1f, 9f, -2f, 0f, -1f, 0f, 5f, out) < 0f)
    }

    @Test fun modelCollisionStopsScriptBodies() {
        val h = Rig()
        val desk = h.addModel(slab(10f, 2f, 10f), ModelInstance.KIND_STAGE)
        desk.position[1] = 5f                         // 桌面在 y = 6
        // 没有碰撞属性：球穿过去落到地板上
        assertEquals(ModelInstance.COLLISION_NONE, desk.collision)
        assertEquals(0.5f, drop(h, 0f, 0f), 0.15f)
        // 包围盒：停在桌面上
        desk.collision = ModelInstance.COLLISION_BOX
        assertEquals(6.5f, drop(h, 0f, 0f), 0.15f)
        // 网格：一样停在桌面上；桌子外面的照样落到地板
        desk.collision = ModelInstance.COLLISION_MESH
        assertEquals(6.5f, drop(h, 1f, -1f), 0.15f)
        assertEquals(0.5f, drop(h, 30f, 0f), 0.15f)
        // 桌子挪走 / 藏起来：不再挡
        desk.position[0] = 100f
        assertEquals(0.5f, drop(h, 0f, 0f), 0.15f)
        desk.position[0] = 0f; desk.visible = false
        assertEquals(0.5f, drop(h, 0f, 0f), 0.15f)
        desk.visible = true
        // 放大两倍：桌面在 5 + 1 × 2 = 7
        desk.scale = 2f
        assertEquals(7.5f, drop(h, 8f, 0f), 0.2f)
    }

    @Test fun collisionIsScriptableQueryableAndRestored() {
        val h = Rig()
        val desk = h.addModel(slab(10f, 2f, 10f), ModelInstance.KIND_STAGE)
        desk.position[1] = 5f
        val s = h.start("""
            local m = scene.models()[1]
            assert(m.collision == "none")
            m.collision = "mesh"
            assert(m.collision == "mesh")
            function update(dt)
              local d, hx, hy, hz, nx, ny, nz, who = sim.raycast(0, 20, 0, 0, -1, 0)
              local far = sim.raycast(40, 20, 0, 0, -1, 0)
              local near = sim.distance(0, 8, 0)
              print(d .. " " .. hy .. " " .. ny .. " " .. far .. " " .. near .. " " .. (who and 1 or 0))
            end
        """.trimIndent())
        h.run(0.1f)
        val n = h.numbers()
        assertEquals(14f, n[0], 0.01f); assertEquals(6f, n[1], 0.01f); assertEquals(1f, n[2], 0.01f)
        assertEquals("桌子外面打到的是地板", 20f, n[3], 0.01f)
        assertEquals("离桌面 2", 2f, n[4], 0.05f)
        assertEquals("打到的是哪个模型", 1f, n[5], 0f)
        assertEquals(ModelInstance.COLLISION_MESH, desk.collision)
        // 脚本停掉：碰撞属性回到原样
        h.stop(s)
        assertEquals(ModelInstance.COLLISION_NONE, desk.collision)
        // 写错名字要报错而不是悄悄当成别的
        val bad = h.runtime.scripts.add("bad", "scene.models()[1].collision = \"solid\"")
        h.runtime.scripts.setEnabled(bad, true); h.runtime.update(1f / 60f)
        assertTrue(bad.error != null)
    }

    @Test fun particlesAndClothLandOnCollidingModels() {
        val h = Rig()
        val desk = h.addModel(slab(8f, 2f, 8f), ModelInstance.KIND_STAGE)
        desk.position[1] = 3f                         // 桌面在 y = 4
        desk.collision = ModelInstance.COLLISION_MESH
        h.start("""
            w = sim.world{render = "none"}
            a = w:add(0, 10, 0, {radius = 0.2})        -- 桌子正上方
            b = w:add(20, 10, 0, {radius = 0.2})       -- 桌子外面
            function update(dt)
              local _, ya = w:pos(a); local _, yb = w:pos(b)
              print(ya .. " " .. yb)
            end
        """.trimIndent())
        h.run(3f)
        val n = h.numbers()
        assertEquals("落在桌面上", 4.2f, n[0], 0.15f)
        assertEquals("落在地板上", 0.2f, n[1], 0.15f)
    }

    // ------------------------------------------------------------------ 地板

    @Test fun floorCanBeMovedRemovedAndAddedBack() {
        val h = Rig()
        assertTrue(h.scene.floor.isDefault)
        // 抬高：东西落在新的高度上
        h.scene.floor.height = 3f
        assertEquals(3.5f, drop(h, 0f, 0f), 0.15f)
        // 关掉碰撞：穿过去
        h.scene.floor.collide = false
        assertTrue(drop(h, 0f, 0f, 3f) < -20f)
        // 删掉：一样一直往下掉
        h.scene.floor.reset(); h.scene.floor.enabled = false
        assertFalse(h.scene.floor.solid)
        assertTrue(drop(h, 0f, 0f, 3f) < -20f)
        // 加回默认的
        h.scene.floor.reset()
        assertTrue(h.scene.floor.isDefault)
        assertEquals(0.5f, drop(h, 0f, 0f), 0.15f)
    }

    @Test fun floorScriptApiAndRestore() {
        val h = Rig()
        val s = h.start("""
            local f = floor.get()
            assert(f.enabled and f.height == 0 and f.grid and f.shadow and f.collide and f.cell == 5)
            floor.set{height = 2, cell = 4, color = {0.9, 0.8, 0.7, 1}, grid = false}
            f = floor.get()
            assert(f.height == 2 and f.cell == 4 and not f.grid and math.abs(f.color[2] - 0.8) < 1e-5)
            b = sim.body{shape = "sphere", size = 0.5, mass = 1, pos = {0, 8, 0}}
            function update(dt) local x, y = b:pos(); print(y) end
        """.trimIndent())
        h.run(3f)
        assertEquals(2.5f, h.numbers()[0], 0.15f)
        assertEquals(2f, h.scene.floor.height, 0f)
        h.stop(s)
        assertTrue("脚本停掉以后地板回到原样", h.scene.floor.isDefault)

        val t = h.start("floor.remove(); assert(not floor.get().enabled); print(tostring(sim.raycast(0, 5, 0, 0, -1, 0)))")
        assertEquals("没有地板，射线什么也打不到", "nil", h.log.last())
        assertFalse(h.scene.floor.enabled)
        h.stop(t)
        assertTrue(h.scene.floor.enabled)

        val u = h.start("floor.set{height = 7}; floor.reset(); print(floor.get().height)")
        assertEquals(0f, h.log.last().toFloat(), 0f)
        h.stop(u)
    }

    @Test fun floorSurvivesSaveAndLoad() {
        val f = Floor()
        assertTrue(f.isDefault)
        f.height = -4.5f; f.grid = false; f.collide = false; f.cell = 2f
        f.color[0] = 0.1f; f.color[1] = 0.2f; f.color[2] = 0.3f; f.color[3] = 1f
        val g = Floor(); g.fromMap(f.toMap())
        assertEquals(-4.5f, g.height, 0f); assertFalse(g.grid); assertFalse(g.collide); assertEquals(2f, g.cell, 0f)
        assertEquals(0.2f, g.color[1], 1e-6f); assertEquals(1f, g.color[3], 0f)
        // 删掉的地板读回来还是删掉的
        f.enabled = false
        g.fromMap(f.toMap())
        assertFalse(g.enabled)
        // 老工程里没有这些项：就是默认地板
        val old = Floor(); old.fromMap(emptyMap())
        assertTrue(old.isDefault)
    }

    // ------------------------------------------------------------------ 画面设置 / 三渲二

    @Test fun renderStyleFromScriptAndBack() {
        val h = Rig()
        val st = h.settings
        assertTrue(st.styleIsDefault)
        val bg = st.backgroundTop.copyOf()
        val s = h.start("""
            render.toon{bands = 3, shade = {0.6, 0.5, 0.8}, edge = 2, rim = 0.4}
            local r = render.get()
            assert(r.cel == 3 and r.edges and r.edge_all, "三渲二应该已经打开")
            assert(math.abs(r.shade[3] - 0.8) < 1e-5 and math.abs(r.edge_width - 2) < 1e-5)
            render.set{contrast = 1.2, posterize = 6, grade = {1, 0.9, 0.8}, edge_color = {0.2, 0.1, 0.1}, cel_soft = 0.1}
            render.background(0.1, 0.2, 0.3)
            print("ok")
        """.trimIndent())
        assertEquals("ok", h.log.last())
        assertEquals(3, st.celBands); assertTrue(st.edgeAll && st.edges)
        assertEquals(1.2f, st.contrast, 1e-6f); assertEquals(6f, st.posterize.toFloat(), 0f)
        assertEquals(0.1f, st.backgroundTop[0], 1e-6f); assertTrue(st.customBackground)
        assertFalse(st.styleIsDefault)
        // 存进工程、再读出来是一样的
        val copy = RenderSettings(); copy.styleFromMap(st.styleToMap())
        assertEquals(3, copy.celBands); assertEquals(0.5f, copy.shadeColor[1], 1e-6f); assertEquals(1.2f, copy.contrast, 1e-6f)
        assertEquals(0.2f, copy.backgroundTop[1], 1e-6f); assertTrue(copy.customBackground)
        // 脚本停掉：全部回到原样
        h.stop(s)
        assertTrue("脚本停掉以后画风应该回到默认：${st.styleToMap()}", st.styleIsDefault)
        assertEquals(0, st.celBands); assertFalse(st.edgeAll); assertFalse(st.customBackground)
        assertEquals(bg[0], st.backgroundTop[0], 0f)

        // render.reset()：脚本自己中途改回去
        val t = h.start("render.set{cel = 2, saturation = 0}; render.reset(); print(render.get().cel .. \" \" .. render.get().saturation)")
        assertEquals(listOf(0f, 1f), h.numbers())
        h.stop(t)
        // 老名字 post.set 还能用
        val u = h.start("post.set{bloom = 0.8, saturation = 1.3}; print(render.get().saturation)")
        assertEquals(1.3f, h.numbers()[0], 1e-5f)
        h.stop(u)
        assertEquals(1f, st.saturation, 0f)
    }

    @Test fun materialsCanOptOutOfCel() {
        val h = Rig()
        val m = h.addModel(slab(2f, 2f, 2f, "脸"), ModelInstance.KIND_CHARACTER)
        val s = h.start("""
            local mat = scene.models()[1]:materials()[1]
            local own = mat.shading
            assert(own ~= nil and mat.cel == nil)
            mat.cel = false; mat.shading = "unlit"
            assert(mat.cel == false and mat.shading == "unlit")
            mat.cel = 4
            assert(mat.cel == 4)
            mat.cel = nil; mat.shading = nil
            assert(mat.cel == nil and mat.shading == own)
            mat.cel = 4; mat.shading = "unlit"
            print("ok")
        """.trimIndent())
        assertEquals("ok", h.log.last())
        assertEquals(4, m.materials[0].fxCel); assertEquals(2, m.materials[0].fxShading)
        h.stop(s)
        assertEquals(-1, m.materials[0].fxCel); assertEquals(-1, m.materials[0].fxShading)
    }

    // ------------------------------------------------------------------ MCP

    @Test fun mcpToolsChangeFloorCollisionAndLook() {
        val h = Rig()
        val desk = h.addModel(slab(10f, 2f, 10f), ModelInstance.KIND_STAGE)
        val names = h.tools.list().map { it["name"] }
        assertTrue(names.containsAll(listOf("floor", "render", "set_model")))

        // 地板：读、改、删、加回来
        assertTrue(h.call("floor").text.contains("\"enabled\":true"))
        var r = h.call("floor", "height" to 2.5, "color" to listOf(0.5, 0.5, 0.5, 1.0), "collide" to false)
        assertFalse(r.text, r.isError)
        assertEquals(2.5f, h.scene.floor.height, 0f); assertFalse(h.scene.floor.collide); assertEquals(1f, h.scene.floor.color[3], 0f)
        r = h.call("floor", "enabled" to false)
        assertFalse(h.scene.floor.enabled); assertTrue(r.text.contains("\"enabled\":false"))
        r = h.call("floor", "reset" to true)
        assertTrue(h.scene.floor.isDefault)

        // 碰撞属性
        r = h.call("set_model", "model" to desk.id, "collision" to "mesh")
        assertFalse(r.text, r.isError)
        assertEquals(ModelInstance.COLLISION_MESH, desk.collision)
        assertTrue(h.call("set_model", "model" to desk.id, "collision" to "jelly").isError)
        val scene = h.call("get_scene").text
        assertTrue(scene, scene.contains("\"collision\":\"mesh\"") && scene.contains("\"floor\""))

        // 画面：预设 + 单项；不带参数 = 只读，不算改动
        val before = h.looks
        assertTrue(h.call("render").text.contains("\"cel\":0"))
        assertEquals(before, h.looks)
        // 颜色给 3 个数也行（第 4 个默认 1）
        r = h.call("render", "edge_color" to listOf(0.5, 0.1, 0.1), "rim_color" to listOf(1.0, 0.8, 0.6, 0.5))
        assertFalse(r.text, r.isError)
        assertEquals(1f, h.settings.edgeColor[3], 0f); assertEquals(0.5f, h.settings.rimColor[3], 0f)
        assertEquals(before + 1, h.looks)
        h.call("floor", "color" to listOf(0.2, 0.3, 0.4))
        assertEquals(1f, h.scene.floor.color[3], 0f); h.scene.floor.reset()
        h.call("render", "preset" to "default")
        r = h.call("render", "preset" to "toon", "cel" to 3, "shade" to listOf(0.5, 0.5, 0.7), "background" to listOf(1.0, 0.9, 0.8), "contrast" to 1.1)
        assertFalse(r.text, r.isError)
        assertEquals(3, h.settings.celBands); assertTrue(h.settings.edgeAll); assertEquals(0.7f, h.settings.shadeColor[2], 1e-6f)
        assertTrue(h.settings.customBackground); assertEquals(0.9f, h.settings.backgroundTop[1], 1e-6f)
        assertEquals(before + 3, h.looks)
        assertTrue(r.text.contains("\"cel\":3"))
        // AI 改的不随脚本停止而撤销；preset=default 才回去
        h.call("render", "preset" to "default")
        assertEquals(0, h.settings.celBands); assertFalse(h.settings.edgeAll); assertEquals(1f, h.settings.contrast, 0f)
    }

    @Test fun newExamplesRunAndCleanUp() {
        val h = Rig()
        h.addModel(slab(2f, 2f, 2f, "角色"), ModelInstance.KIND_CHARACTER)
        for (name in listOf("三渲二", "水墨与漫画", "台阶和地板")) {
            val ex = ScriptLibrary.EXAMPLES.first { it.name == name }
            val s = h.runtime.scripts.add(ex.name, ex.source)
            h.runtime.scripts.setEnabled(s, true)
            h.run(14f)
            assertNull("示例「$name」出错：${s.error}\n${h.errors}", s.error)
            h.runtime.scripts.setEnabled(s, false); h.runtime.update(1f / 60f)
            h.runtime.scripts.remove(s)
            assertTrue("示例「$name」停掉以后画风应该还原：${h.settings.styleToMap()}", h.settings.styleIsDefault)
            assertTrue("示例「$name」停掉以后地板应该还原", h.scene.floor.isDefault)
            for (m in h.scene.models.toList()) if (m.data.name != "角色") h.scene.remove(m)
            assertEquals(1, h.scene.models.size)
        }
    }

    /** 说明文档里写的每一项 render.set / floor.set 都真的能用（写错名字会报错，所以跑一遍就知道）。 */
    @Test fun everyDocumentedRenderKeyIsAccepted() {
        val h = Rig()
        h.start("""
            render.set{cel = 2, cel_soft = 0.04, cel_shift = 0.1, shade = {0.7, 0.64, 0.8}, cel_specular = 0.6,
              edges = 1.5, edge_width = 1.5, edge_color = {0, 0, 0}, edge_all = true,
              shadows = 0.7, ground_shadow = true, rim = 0.4, rim_color = {1, 1, 1}, specular = 1,
              sky = {0.5, 0.5, 0.6}, ground = {0.3, 0.3, 0.3},
              bloom = 0.8, bloom_threshold = 0.82, exposure = 1, saturation = 1, contrast = 1, vignette = 0.2,
              posterize = 6, grade = {1, 0.95, 0.9}, background = {{0.1, 0.1, 0.2}, {0.3, 0.3, 0.4}}, grid = false}
            render.set{edge_color = false, rim_color = false, cel = false, edges = false, shadows = false, background = {0.2, 0.2, 0.2}}
            floor.set{height = 0, grid = true, shadow = true, collide = true, cell = 5, color = {1, 1, 1, 1}, grid_color = {0, 0, 0, 1}, enabled = true}
            floor.set{color = false}
            assert(floor.get().color[4] == 0)
            print("ok")
        """.trimIndent())
        assertEquals("ok", h.log.last())
    }
}

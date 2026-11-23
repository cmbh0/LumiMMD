package com.lumi.mmd.engine

import com.lumi.mmd.engine.fx.SimWorld
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.render.RenderSettings
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.scene.Scene
import com.lumi.mmd.engine.scene.SceneRuntime
import com.lumi.mmd.engine.script.Script
import com.lumi.mmd.engine.script.ScriptCallbacks
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 脚本自己造东西的那一层：画布贴图、网格、粒子物理（布 / 绳 / 液体 / 自己写的力）、刚体、查询。都不需要 GPU。 */
class CreateTest {
    private class Host : ScriptCallbacks {
        val scene = Scene()
        val log = ArrayList<String>()
        val errors = ArrayList<String>()
        val runtime = SceneRuntime(scene, RenderSettings(), this)
        val fx get() = runtime.effects
        override fun scriptLog(script: Script?, text: String, error: Boolean) { if (error) errors.add(text) else log.add(text) }
        override fun setPlaying(on: Boolean) { scene.playing = on }
        override fun seekSeconds(seconds: Float) { scene.seekSeconds(seconds) }
        override fun addModel(data: ModelData, kind: Int): ModelInstance { val m = ModelInstance(data, kind); scene.add(m); return m }
        override fun removeModel(model: ModelInstance) { scene.remove(model) }
        fun run(seconds: Float) { var t = 0f; while (t < seconds - 1e-4f) { runtime.update(1f / 60f); t += 1f / 60f } }
        fun start(code: String): Script {
            val s = runtime.scripts.add("t", code)
            runtime.scripts.setEnabled(s, true)
            runtime.update(1f / 60f)
            assertNull(errors.joinToString("\n"), s.error)
            return s
        }
    }

    @Test fun canvasDrawsShapesGradientsAndPixels() {
        val h = Host()
        val s = h.start("""
            c = canvas.new(64, 64)
            c:clear(0, 0, 0, 1)
            c:color(1, 0, 0, 1)
            c:circle(32, 32, 10)
            local r, g, b, a = c:pixel(32, 32)
            assert(r > 0.99 and g < 0.01 and a > 0.99, "圆心应该是红的")
            r = c:pixel(2, 2)
            assert(r < 0.01, "圆外面没动")
            -- 径向渐变：中心白，边缘蓝
            c:radial(32, 32, 0, 20, {1, 1, 1, 1}, {0, 0, 1, 1})
            c:circle(32, 32, 20)
            r, g, b = c:pixel(32, 32)
            assert(r > 0.9 and b > 0.9, "渐变的中心是白的")
            r, g, b = c:pixel(32 + 18, 32)
            assert(r < 0.2 and b > 0.9, "渐变的边缘是蓝的 " .. r)
            -- 擦除
            c:blend("erase"); c:color(1, 1, 1, 1); c:rect(0, 0, 8, 8)
            r, g, b, a = c:pixel(3, 3)
            assert(a < 0.01, "擦掉的地方是透明的")
            c:blend("over")
            -- 逐像素
            c:each(function(x, y, r, g, b, a) if x == 5 and y == 60 then return 0, 1, 0, 1 end end)
            r, g, b = c:pixel(5, 60)
            assert(g > 0.99 and r < 0.01)
            c:pixel(6, 60, 1, 1, 0, 1)
            r, g, b = c:pixel(6, 60)
            assert(r > 0.99 and g > 0.99 and b < 0.01)
            c:line(0, 40, 63, 40, 3); c:ring(32, 32, 25, 2); c:ellipse(32, 32, 6, 12, 30); c:poly({10, 50, 20, 50, 15, 58})
            c:feather(3); c:circle(50, 50, 6); c:feather(0)
            c:blur(1.5); c:noise(0.1, 4, 7)
            assert(c.width == 64 and c.name:sub(1, 7) == "canvas:")
            sp = fx.sprite{texture = c, size = 2}
            em = fx.emitter{texture = c, rate = 10}
            print("ok")
        """.trimIndent())
        assertEquals(listOf("ok"), h.log)
        assertEquals(1, h.fx.canvases.size)
        assertTrue(h.fx.sprites[0].texture.startsWith("canvas:"))
        h.runtime.scripts.setEnabled(s, false); h.runtime.update(1f / 60f)
        assertTrue(h.fx.canvases.isEmpty())
    }

    @Test fun meshesAreBuiltEditedAndFollowCloth() {
        val h = Host()
        val s = h.start("""
            g = mesh.new{color = {1, 0.5, 0.5, 1}, double_sided = true}
            g:grid(4, 3, 8, 6)
            assert(g.count == 20 and g.triangles == 24, g.count .. " " .. g.triangles)
            local x, y, z = g:vertex(1)
            assert(x == -4 and z == -3)
            g:vertex(1, -4, 2, -3); g:recalc()
            local nx, ny, nz = g:normal(1)
            assert(ny > 0.3 and math.abs(nx * nx + ny * ny + nz * nz - 1) < 1e-3)
            t = mesh.new{mode = "triangles"}
            t:build{positions = {0, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 1}, uvs = {0, 0, 1, 0, 0, 1, 1, 1}, indices = {1, 3, 2, 2, 3, 4}}
            assert(t.count == 4 and t.triangles == 2)
            nx, ny, nz = t:normal(1)
            assert(ny > 0.99, "法线朝上 " .. ny)
            t:move(0, 5, 0); t:rotate(0, 90, 0); t:scale(2)
            b = mesh.new(); b:box(2, 2, 2); assert(b.count == 24 and b.triangles == 12)
            sp = mesh.new(); sp:sphere(1, 12); assert(sp.triangles > 100)
            -- 布：质点的排法和 grid 一样，网格直接跟着走
            w = sim.world{max = 400, collide = false, ground = -100, damping = 0.7}
            first, n = w:cloth(4, 3, 8, 6, {pos = {0, 10, 0}})
            assert(n == 20)
            w:pin(first); w:pin(first + 4)
            g:follow(w, first)
            print("ok")
        """.trimIndent())
        assertEquals(listOf("ok"), h.log)
        h.run(5f)
        val g = h.fx.meshes[0]
        val w = h.fx.sims[0]
        // 两个角钉着，其余的垂下来；网格顶点和质点一致
        assertEquals(10f, w.pos[1], 1e-3f)
        assertTrue("布的下缘应该垂下来 ${w.pos[19 * 3 + 1]}", w.pos[19 * 3 + 1] < 5f)
        for (i in 0 until 20 * 3) assertEquals(w.pos[i], g.positions[i], 1e-6f)
        // 约束是刚性的：钉着的两个角之间那条边上，相邻点的距离没变
        assertEquals(2f, w.dist(0, 1), 0.05f)
        // 移位后的世界矩阵
        val t = h.fx.meshes[1]
        assertEquals(5f, t.world[13], 1e-5f)
        assertEquals(2f, sqrt(t.world[0] * t.world[0] + t.world[1] * t.world[1] + t.world[2] * t.world[2]), 1e-4f)
        h.runtime.scripts.setEnabled(s, false); h.runtime.update(1f / 60f)
        assertTrue(h.fx.meshes.isEmpty() && h.fx.sims.isEmpty())
    }

    @Test fun particlesFallSwingAndObeyScriptForces() {
        val h = Host()
        h.start("""
            -- 1) 自由落体：没有阻尼，1 秒后应该掉了 ½·g·t² = 49
            a = sim.world{damping = 0, ground = false, collide = false, substeps = 8}
            p = a:add(0, 100, 0)
            -- 2) 摆：一根刚性的绳子，长度不能变
            b = sim.world{damping = 0, ground = false, collide = false, substeps = 8}
            top = b:add(0, 50, 0, {mass = 0})
            bob = b:add(10, 50, 0)
            b:link(top, bob)
            -- 3) 脚本自己写的力：弹簧 F = -k·x，周期应该是 2π·sqrt(m/k)
            c = sim.world{gravity = 0, damping = 0, ground = false, collide = false, substeps = 16}
            q = c:add(1, 0, 0, {mass = 2})
            k = 8
            c.on_step = function(dt)
                local x, y, z = c:pos(q)
                c:force(q, -k * x, -k * y, -k * z)
            end
            crossed = nil
            last = 1
            function update(dt)
                local x = c:pos(q)
                if not crossed and last > 0 and x <= 0 then crossed = c.time end
                last = x
            end
        """.trimIndent())
        h.run(1f - 1f / 60f)
        val a = h.fx.sims[0]; val b = h.fx.sims[1]; val c = h.fx.sims[2]
        assertEquals(1f, a.time, 1e-3f)
        assertEquals(51f, a.pos[1], 0.5f)
        assertEquals(-98f, a.vel[1], 0.1f)
        assertEquals(10f, b.dist(0, 1), 0.01f)
        // 从水平放手的摆（大摆幅，周期约 2.37 秒）：1 秒时已经荡过最低点到了另一边，而且没有凭空多出能量（不会高过出发点）
        assertTrue("摆应该荡到另一边了 ${b.pos[3]}, ${b.pos[4]}", b.pos[3] < -5f && b.pos[4] > 40.5f && b.pos[4] < 50.05f)
        h.run(1f)
        // 四分之一周期 = π/2·sqrt(2/8) = 0.785 s
        val crossed = h.runtime.scripts.scripts[0].globals!!.get("crossed").tofloat()
        assertEquals(0.785f, crossed, 0.02f)
        // 能量守恒：½kA² = 4，过了两秒也应该还在
        val x = c.pos[0]; val v = c.vel[0]
        assertEquals(4f, 0.5f * 8f * x * x + 0.5f * 2f * v * v, 0.15f)
    }

    @Test fun liquidSettlesInAContainerWithoutExploding() {
        val h = Host()
        h.start("""
            w = sim.world{max = 1200, spacing = 0.5, collide = false, bounds = {-3, 0, -3, 3, 40, 3}, render = "liquid", substeps = 2, iterations = 3}
            first, n = w:fill(-2, 1, -2, 2, 7, 2)
            print(n)
            function on_button(name, down)
                if down then for i = 1, tonumber(name) do w:emit(0, 20, 0, 0, -5, 0) end end
            end
        """.trimIndent())
        val w = h.fx.sims[0]
        val n = w.count
        assertEquals(8 * 12 * 8, n)
        assertEquals(SimWorld.RENDER_LIQUID, w.renderMode)
        h.run(4f)
        var maxV = 0f; var top = 0f; var dens = 0f; var deep = 0
        for (i in 0 until n) {
            val o = i * 3
            for (k in 0 until 3) assertTrue("位置不是数", w.pos[o + k].isFinite())
            assertTrue(w.pos[o] >= -3f && w.pos[o] <= 3f && w.pos[o + 2] >= -3f && w.pos[o + 2] <= 3f && w.pos[o + 1] >= 0f)
            maxV = maxOf(maxV, sqrt(w.vel[o] * w.vel[o] + w.vel[o + 1] * w.vel[o + 1] + w.vel[o + 2] * w.vel[o + 2]))
            top = maxOf(top, w.pos[o + 1])
            if (w.pos[o + 1] > 1f && w.pos[o + 1] < 2f && abs(w.pos[o]) < 2f && abs(w.pos[o + 2]) < 2f) { dens += w.density[i]; deep++ }
        }
        // 768 个粒子 × 0.125 的体积 = 96，底面积 36 → 水深约 2.7；摊平了，没有堆成柱子也没有压成一层
        assertTrue("水面高度 $top", top > 1.8f && top < 4.5f)
        assertTrue("应该基本静下来了，最快的还有 $maxV", maxV < 12f)
        assertTrue(deep > 50)
        val d = dens / deep
        assertTrue("水里面的密度应该接近静止密度：$d", d > 0.85f && d < 1.25f)
        // 一直倒水：满了以后回收最早的粒子，总数不变
        h.runtime.scripts.button("600", true)
        assertEquals(1200, w.count)
        h.runtime.scripts.button("100", true)
        assertEquals(1200, w.count)
        h.run(0.5f)
        for (i in 0 until w.count * 3) assertTrue(w.pos[i].isFinite())
    }

    @Test fun collidersQueriesAndRigidBodies() {
        val h = Host()
        h.start("""
            w = sim.world{max = 100, damping = 0.1}
            ball = sim.collider{shape = "sphere", size = 2, pos = {0, 2, 0}}
            p = w:add(0.3, 8, 0, {radius = 0.2})
            wall = sim.collider{shape = "box", size = {4, 4, 1}, pos = {20, 2, 0}}
            -- 刚体：箱子落在地上；两个球用关节连着
            box = sim.body{shape = "box", size = {2, 2, 2}, pos = {-10, 6, 0}, mass = 2}
            s1 = sim.body{shape = "sphere", size = 0.5, pos = {-20, 10, 0}, mass = 0}
            s2 = sim.body{shape = "sphere", size = 0.5, pos = {-20, 8, 0}, mass = 1}
            sim.joint(s1, s2, {pos = {-20, 10, 0}, ang_lo = {-180, -180, -180}, ang_hi = {180, 180, 180}})
            s2:impulse(3, 0, 0)
            assert(box.mesh.count == 24)
            function on_button(name, down)
                if not down then return end
                local d, x, y, z, nx, ny, nz = sim.raycast(0, 20, 0, 0, -1, 0)
                assert(math.abs(d - 16) < 0.01 and ny > 0.99, "从上往下打到球顶 " .. d)
                d, x = sim.raycast(10, 2, 0, 1, 0, 0)
                assert(math.abs(x - 18) < 0.01, "打到盒子的侧面 " .. x)
                d = sim.raycast(0, 30, 50, 0, -1, 0)
                assert(math.abs(d - 30) < 0.01, "别处打到地面")
                assert(sim.raycast(0, 30, 50, 0, 1, 0) == nil)
                d = sim.distance(0, 5, 0)
                assert(math.abs(d - 1) < 0.01, "离球面 1：" .. d)
                local bx, by, bz = box:pos()
                assert(math.abs(by - 1) < 0.08, "箱子应该停在地上：" .. by)
                local ax, ay, az = s1:pos()
                local cx, cy, cz = s2:pos()
                local len = math.sqrt((ax - cx) ^ 2 + (ay - cy) ^ 2 + (az - cz) ^ 2)
                assert(math.abs(len - 2) < 0.15, "关节连着的两个球距离不变：" .. len)
                print("fine")
            end
        """.trimIndent())
        h.run(3f)
        val w = h.fx.sims[0]
        // 粒子从球上滑下来落到地上：不在球里面，也没有穿过地面
        val d = sqrt(w.pos[0] * w.pos[0] + (w.pos[1] - 2f) * (w.pos[1] - 2f) + w.pos[2] * w.pos[2])
        assertTrue("粒子不该在球里面：$d", d >= 2.19f)
        assertTrue(w.pos[1] >= 0.199f)
        h.runtime.scripts.button("check", true)
        assertNull(h.errors.joinToString(), h.runtime.scripts.scripts[0].error)
        assertTrue(h.log.contains("fine"))
    }

    /** 画布换到模型的材质上：改的是「这块材质现在用哪张图」，原来的贴图不动；换回去、停脚本都会复原。 */
    @Test fun canvasReplacesAMaterialTexture() {
        val h = Host()
        val em = com.lumi.mmd.engine.mesh.EditMesh.createEmpty("箱子")
        com.lumi.mmd.engine.mesh.MeshPrimitives.box(em, 2f, 2f, 2f)
        em.recalcNormals()
        val m = h.addModel(em.build().data, ModelInstance.KIND_CHARACTER)
        val s = h.start("""
            local m = scene.models()[1]
            mat = m:material(1)
            local u0, v0, u1, v1 = mat:uv_bounds()
            assert(u0 >= 0 and v0 >= 0 and u1 <= 1 and v1 <= 1 and u1 > u0 and v1 > v0, u0 .. " " .. v0 .. " " .. u1 .. " " .. v1)
            c = canvas.new(64)
            c:color(1, 0, 0, 1); c:circle((u0 + u1) / 2 * c.width, (v0 + v1) / 2 * c.height, 10)
            mat:texture(c)
            -- 没有贴图的材质拿不到原图：报的是说得清的错，不是崩掉
            local ok, err = pcall(function() c:image(mat) end)
            assert(not ok and tostring(err):find("没有贴图"), tostring(err))
            function on_button(name, down) if down then mat:texture(nil) end end
        """.trimIndent())
        val canvas = h.fx.canvases[0]
        assertEquals(canvas.id, m.materials[0].fxCanvas)
        h.runtime.scripts.button("back", true); h.runtime.update(1f / 60f)
        assertEquals(0, m.materials[0].fxCanvas)
        h.runtime.scripts.button("back", false)
        // 再换上，然后停掉脚本：材质回到原图，画布也清走了
        h.runtime.scripts.setEnabled(s, false); h.runtime.update(1f / 60f)
        val s2 = h.runtime.scripts.add("t2", "c = canvas.new(8); scene.models()[1]:material(1):texture(c)")
        h.runtime.scripts.setEnabled(s2, true); h.runtime.update(1f / 60f)
        assertNull(h.errors.joinToString("\n"), s2.error)
        assertTrue(m.materials[0].fxCanvas != 0)
        h.runtime.scripts.setEnabled(s2, false); h.runtime.update(1f / 60f)
        assertEquals(0, m.materials[0].fxCanvas)
        assertTrue(h.fx.canvases.isEmpty())
    }

    /** README 里那段「自己写的万有引力」：近圆轨道，绕了几圈半径还在原来附近。 */
    @Test fun scriptedGravityKeepsAnOrbit() {
        val h = Host()
        h.start("""
            g = sim.world{ gravity = 0, ground = false, collide = false, damping = 0, render = "balls" }
            sun = g:add(0, 10, 0, { mass = 0, radius = 1 })
            p = g:add(8, 10, 0, { vel = {0, 0, 12} })
            lo, hi, turns, lastz = 100, 0, 0, 0
            g.on_step = function(h)
              local x, y, z = g:pos(p)
              local dx, dy, dz = -x, 10 - y, -z
              local d = math.sqrt(dx * dx + dy * dy + dz * dz)
              local f = 1200 / (d * d * d)
              g:force(p, dx * f, dy * f, dz * f)
              lo = math.min(lo, d); hi = math.max(hi, d)
              if lastz < 0 and z >= 0 then turns = turns + 1 end
              lastz = z
            end
        """.trimIndent())
        h.run(12f)
        val gl = h.runtime.scripts.scripts[0].globals!!
        // v=12 比圆轨道速度 √(1200/8)=12.25 略小：椭圆，远点 8，近点约 7.4；周期约 4.1 秒
        assertEquals(8f, gl.get("hi").tofloat(), 0.1f)
        assertEquals(7.4f, gl.get("lo").tofloat(), 0.25f)
        assertTrue("绕了 ${gl.get("turns")} 圈", gl.get("turns").toint() in 2..3)
    }

    @Test fun scriptsCanStepThemselvesAndErrorsStopCleanly() {
        val h = Host()
        h.start("""
            w = sim.world{auto = false, damping = 0, ground = false, collide = false}
            p = w:add(0, 0, 0)
            for i = 1, 60 do w:step(1 / 60) end
            local x, y = w:pos(p)
            assert(math.abs(y + 49) < 1, "手动推进 1 秒：" .. y)
            assert(w.steps == 60)
        """.trimIndent())
        h.run(0.5f)
        assertEquals(1f, h.fx.sims[0].time, 1e-3f)      // auto=false：场景不会替它推进
        // on_step 里出错：脚本被停掉、报出来，世界跟着清走，不崩
        val h2 = Host()
        val s = h2.runtime.scripts.add("bad", "w = sim.world{}\nw:add(0, 5, 0)\nw.on_step = function(dt) error('炸了') end")
        h2.runtime.scripts.setEnabled(s, true)
        h2.run(0.2f)
        assertNotNull(s.error)
        assertTrue(s.error!!.contains("炸了"))
        assertTrue(h2.fx.sims.isEmpty())
    }
}

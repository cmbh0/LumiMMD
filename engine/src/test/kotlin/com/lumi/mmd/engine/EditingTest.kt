package com.lumi.mmd.engine

import com.lumi.mmd.engine.anim.BoneTrack
import com.lumi.mmd.engine.anim.Motion
import com.lumi.mmd.engine.format.ModelLoader
import com.lumi.mmd.engine.format.PmxReader
import com.lumi.mmd.engine.format.PmxWriter
import com.lumi.mmd.engine.format.VmdReader
import com.lumi.mmd.engine.format.VmdWriter
import com.lumi.mmd.engine.math.Quat
import com.lumi.mmd.engine.mesh.EditMesh
import com.lumi.mmd.engine.mesh.MeshOps
import com.lumi.mmd.engine.mesh.MeshPrimitives
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.MorphData
import com.lumi.mmd.engine.scene.KeyOps
import com.lumi.mmd.engine.scene.MeshSession
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.scene.Scene
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 第二版新增的"能改"的部分：关键帧编辑、VMD / PMX 导出、多边形建模。都不需要 GPU。 */
class EditingTest {
    private val assets: File? = System.getenv("LUMI_TEST_ASSETS")?.let { File(it) }?.takeIf { it.isDirectory }

    // ------------------------------------------------------------------ 关键帧轨道

    @Test fun boneTrackInsertOverwriteRemove() {
        val t = BoneTrack("センター", 0)
        t.set(30, 1f, 2f, 3f, 0f, 0f, 0f, 1f)
        t.set(0, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
        t.set(15, 5f, 5f, 5f, 0f, 0f, 0f, 2f)      // 四元数会被归一化
        t.set(60, 9f, 9f, 9f, 0f, 0f, 0f, 1f)
        t.set(45, 7f, 7f, 7f, 0f, 0f, 0f, 1f)
        assertEquals(5, t.count)
        assertArrayEquals(intArrayOf(0, 15, 30, 45, 60), t.frames.copyOf(t.count))
        assertEquals(5f, t.values[7], 0f)
        assertEquals(1f, t.values[7 + 6], 1e-6f)
        // 覆盖已有的帧不增加数量
        t.set(15, 6f, 6f, 6f, 0f, 0f, 0f, 1f)
        assertEquals(5, t.count)
        assertEquals(6f, t.values[7], 0f)
        // 新建的关键帧是线性补间：0→6 的正中间是 3
        val oT = FloatArray(3); val oR = FloatArray(4)
        t.sample(7.5f, 0, oT, 0, oR, 0)
        assertEquals(3f, oT[0], 1e-3f)
        // 游标乱给也能找对
        t.sample(50f, 999, oT, 0, oR, 0)
        assertEquals(7f + (9f - 7f) * (5f / 15f), oT[0], 1e-3f)
        assertTrue(t.remove(30))
        assertFalse(t.remove(31))
        assertArrayEquals(intArrayOf(0, 15, 45, 60), t.frames.copyOf(t.count))
        assertEquals(7f, t.values[2 * 7], 0f)
        val c = t.copy()
        c.set(100, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
        assertEquals(4, t.count); assertEquals(5, c.count)
    }

    private fun sampleMotion(): Motion {
        val m = Motion()
        m.modelName = "テストモデル"
        val q = FloatArray(4)
        val a = m.boneTrack("センター")
        a.set(0, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
        Quat.fromEulerYXZ(q, 0, 0.3f, -0.8f, 0.1f)
        a.set(40, 1.5f, -2f, 3.25f, q[0], q[1], q[2], q[3], ByteArray(16) { (10 + it * 7).toByte() })
        val b = m.boneTrack("左腕")
        Quat.fromEulerYXZ(q, 0, 0f, 0f, 0.7f)
        b.set(12, 0f, 0f, 0f, q[0], q[1], q[2], q[3])
        // Shift-JIS 表示不了的简体字名字、超过 15 字节的长名字
        m.boneTrack("头发飘带").set(3, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        m.boneTrack("とても長い名前のボーンです").set(5, 0f, 0f, 2f, 0f, 0f, 0f, 1f)
        m.morphTrack("あ").let { it.set(0, 0f); it.set(10, 1f); it.set(20, 0.25f) }
        m.morphTrack("まばたき").set(33, 0.5f)
        val cam = m.cameraTrack()
        cam.set(0, floatArrayOf(-45f, 0f, 10f, 0f, 0.1f, 0.2f, 0f, 30f), 0, true)
        cam.set(90, floatArrayOf(-20f, 1f, 12f, -2f, -0.3f, 1.2f, 0.05f, 45f), 0, true, ByteArray(24) { (it * 5).toByte() })
        m.lightTrack().set(0, floatArrayOf(0.6f, 0.6f, 0.6f, -0.5f, -1f, 0.5f), 0)
        m.ikTrack("左足ＩＫ").let { it.set(0, true); it.set(50, false) }
        m.recomputeMaxFrame()
        return m
    }

    private fun assertMotionsEqual(a: Motion, b: Motion, namesExact: Boolean) {
        assertEquals(a.boneTracks.size, b.boneTracks.size)
        val bt = b.boneTracks.values.toList()
        for ((i, ta) in a.boneTracks.values.withIndex()) {
            val tb = if (namesExact) b.boneTracks[ta.name] ?: error("缺少骨骼轨道 ${ta.name}") else bt[i]
            assertEquals("${ta.name} 关键帧数", ta.count, tb.count)
            assertArrayEquals(ta.frames.copyOf(ta.count), tb.frames.copyOf(tb.count))
            for (k in 0 until ta.count * 7) assertEquals("${ta.name} 值 $k", ta.values[k], tb.values[k], 1e-6f)
            assertArrayEquals("${ta.name} 补间曲线", ta.interp.copyOf(ta.count * 16), tb.interp.copyOf(tb.count * 16))
        }
        assertEquals(a.morphTracks.size, b.morphTracks.size)
        for (ta in a.morphTracks.values) {
            val tb = b.morphTracks[ta.name] ?: error("缺少表情轨道 ${ta.name}")
            assertArrayEquals(ta.frames.copyOf(ta.count), tb.frames.copyOf(tb.count))
            assertArrayEquals(ta.weights.copyOf(ta.count), tb.weights.copyOf(tb.count), 0f)
        }
        val ca = a.camera; val cb = b.camera
        assertEquals(ca?.count ?: 0, cb?.count ?: 0)
        if (ca != null && cb != null) {
            assertArrayEquals(ca.frames.copyOf(ca.count), cb.frames.copyOf(cb.count))
            assertArrayEquals(ca.values.copyOf(ca.count * 8), cb.values.copyOf(cb.count * 8), 1e-6f)
            assertArrayEquals(ca.interp.copyOf(ca.count * 24), cb.interp.copyOf(cb.count * 24))
        }
        assertEquals(a.light?.count ?: 0, b.light?.count ?: 0)
        assertEquals(a.ikTracks.keys, b.ikTracks.keys)
        for (ta in a.ikTracks.values) {
            val tb = b.ikTracks[ta.name]!!
            // 写出时各条 IK 轨道被合并到同一批帧上，所以比较"每一帧的状态"而不是关键帧个数
            for (f in 0..a.maxFrame) assertEquals("${ta.name} @ $f", ta.sample(f.toFloat(), 0), tb.sample(f.toFloat(), 0))
        }
        assertEquals(a.maxFrame, b.maxFrame)
    }

    @Test fun vmdRoundTrip() {
        val m = sampleMotion()
        val bytes = VmdWriter.write(m)
        assertTrue(VmdReader.isVmd(bytes))
        val r = VmdReader.read(bytes)
        assertEquals("テストモデル", r.modelName)
        assertNotNull(r.boneTracks["センター"]); assertNotNull(r.boneTracks["左腕"])
        // 长名字被截断到 15 字节，和读取时用的规则一致
        assertNotNull(r.boneTracks[VmdReader.nameKey("とても長い名前のボーンです")])
        // 简体字名字按 GBK 存，读回来是"乱码"，但和 gbkKey 给出的匹配键一致
        assertNotNull(r.boneTracks[VmdReader.gbkKey("头发飘带")])
        assertMotionsEqual(m, r, namesExact = false)
        // 再写一次，字节完全一样
        assertArrayEquals(bytes, VmdWriter.write(r, "テストモデル"))
    }

    @Test fun realVmdRoundTrip() {
        val f = assets?.let { File(it, "motion.vmd") }?.takeIf { it.isFile }
        assumeTrue("没有真实 VMD（LUMI_TEST_ASSETS）", f != null)
        val a = ModelLoader.loadMotion(f!!)
        assertTrue(a.boneTracks.size > 10)
        val b = VmdReader.read(VmdWriter.write(a))
        assertMotionsEqual(a, b, namesExact = true)
    }

    // ------------------------------------------------------------------ 打关键帧

    private fun boxModel(): ModelData {
        val em = EditMesh.createEmpty("箱子")
        MeshPrimitives.box(em, 2f, 2f, 2f)
        em.recalcNormals()
        return em.build().data
    }

    @Test fun registerBoneFoldsUserPoseIntoKey() {
        val scene = Scene()
        val inst = ModelInstance(boxModel(), ModelInstance.KIND_ACCESSORY)
        scene.add(inst)
        val sk = inst.skeleton!!
        assertEquals(1, sk.count)
        // 第 0 帧：初始姿势
        assertTrue(KeyOps.registerBone(inst, scene, 0, 0))
        // 第 30 帧：往上挪 4、绕 Y 转 90°
        sk.userT[1] = 4f
        Quat.fromAxisAngle(sk.userR, 0, 0f, 1f, 0f, (Math.PI / 2).toFloat())
        sk.anyUserPose = true
        assertEquals(1, KeyOps.registerPosed(inst, scene, 30))
        assertEquals(0f, sk.userT[1], 0f)
        assertTrue(Quat.isIdentity(sk.userR, 0))
        assertEquals(30, scene.maxFrame)
        assertTrue(inst.motionOwned)
        assertArrayEquals(intArrayOf(0, 30), KeyOps.boneKeyFrames(inst, 0))
        // 播放到一半：位置在中间
        scene.seek(15f)
        scene.update(0f)
        assertEquals(2f, sk.global[13], 1e-3f)
        scene.seek(30f)
        scene.update(0f)
        assertEquals(4f, sk.global[13], 1e-4f)
        // 在已有的姿势上再叠一层再登记：合成结果 = 手动层 · 动作层
        sk.userT[0] = 1f
        KeyOps.registerBone(inst, scene, 0, 30)
        val oT = FloatArray(3); val oR = FloatArray(4)
        KeyOps.bonePose(inst, 0, 30, oT, oR)
        assertEquals(1f, oT[0], 1e-6f); assertEquals(4f, oT[1], 1e-6f)
        // 复制第 30 帧，贴到第 60 帧
        val clip = KeyOps.copy(inst, 30)
        assertEquals(1, clip.size)
        assertEquals(1, KeyOps.paste(inst, scene, clip, 60))
        assertArrayEquals(intArrayOf(0, 30, 60), KeyOps.boneKeyFrames(inst, 0))
        assertEquals(60, scene.maxFrame)
        assertEquals(30, KeyOps.neighbourKey(inst, scene, 0, true))
        assertEquals(30, KeyOps.neighbourKey(inst, scene, 60, false))
        assertEquals(-1, KeyOps.neighbourKey(inst, scene, 60, true))
        // 从第 10 帧起整体后移 5 帧
        KeyOps.shiftKeys(inst, scene, 10, 5)
        assertArrayEquals(intArrayOf(0, 35, 65), KeyOps.boneKeyFrames(inst, 0))
        assertTrue(KeyOps.deleteBoneKey(inst, scene, 0, 35))
        assertFalse(KeyOps.deleteBoneKey(inst, scene, 0, 35))
        assertArrayEquals(intArrayOf(0, 65), KeyOps.boneKeyFrames(inst, 0))
        // 补间曲线
        assertTrue(KeyOps.setBoneCurve(inst, 0, 65, -1, 64, 0, 64, 127))
        val c = IntArray(4)
        assertTrue(KeyOps.getBoneCurve(inst, 0, 65, 3, c))
        assertArrayEquals(intArrayOf(64, 0, 64, 127), c)
        // 导出再读回来还是这些关键帧
        val back = VmdReader.read(VmdWriter.write(inst.motion!!.motion, inst.name))
        assertArrayEquals(intArrayOf(0, 65), back.boneTracks.values.first().let { it.frames.copyOf(it.count) })
    }

    @Test fun cameraKeysDriveCamera() {
        val scene = Scene()
        scene.camera.distance = 40f; scene.camera.target[1] = 10f
        KeyOps.registerCamera(scene, 0)
        scene.camera.distance = 10f; scene.camera.rotY = 1f
        KeyOps.registerCamera(scene, 20)
        assertArrayEquals(intArrayOf(0, 20), KeyOps.cameraKeyFrames(scene))
        scene.seek(10f); scene.update(0f)
        assertEquals(25f, scene.camera.distance, 1e-3f)
        assertEquals(0.5f, scene.camera.rotY, 1e-3f)
        // 暂停时手动转镜头：留在这一帧时不被镜头动作盖掉，换帧后恢复
        scene.camera.rotY = 2f
        scene.holdCamera()
        scene.update(0f)
        assertEquals(2f, scene.camera.rotY, 0f)
        scene.seek(20f); scene.update(0f)
        assertEquals(1f, scene.camera.rotY, 1e-5f)
        assertTrue(KeyOps.deleteCameraKey(scene, 20))
        assertEquals(0, scene.maxFrame)
    }

    // ------------------------------------------------------------------ 建模

    /** 封闭流形检查：每条边恰好被两个面以相反方向各走一次。 */
    private fun assertClosed(m: EditMesh, what: String) {
        val count = HashMap<Long, Int>()
        m.forEachEdge { a, b -> val k = (a.toLong() shl 32) or b.toLong(); count[k] = (count[k] ?: 0) + 1 }
        for ((k, n) in count) {
            assertEquals("$what：有向边被多个面使用", 1, n)
            val a = (k ushr 32).toInt(); val b = (k and 0xFFFFFFFFL).toInt()
            assertTrue("$what：边 $a-$b 没有对面的面（网格不封闭或朝向不一致）", count.containsKey((b.toLong() shl 32) or a.toLong()))
        }
    }

    /** 有向体积：按"法线 = cross(b-a, c-a) 朝外"的约定，封闭网格应为正。 */
    private fun volume(d: ModelData): Float {
        var v = 0.0
        val p = d.positions; val idx = d.indices
        var i = 0
        while (i < idx.size) {
            val a = idx[i] * 3; val b = idx[i + 1] * 3; val c = idx[i + 2] * 3
            v += p[a] * (p[b + 1].toDouble() * p[c + 2] - p[b + 2].toDouble() * p[c + 1]) +
                p[a + 1] * (p[b + 2].toDouble() * p[c] - p[b].toDouble() * p[c + 2]) +
                p[a + 2] * (p[b].toDouble() * p[c + 1] - p[b + 1].toDouble() * p[c])
            i += 3
        }
        return (v / 6.0).toFloat()
    }

    private fun finish(m: EditMesh): ModelData { m.recalcNormals(); return m.build().data }

    @Test fun primitivesAreClosedAndFaceOutward() {
        run {
            val m = EditMesh.createEmpty("box"); MeshPrimitives.box(m, 2f, 3f, 4f)
            assertEquals(8, m.livePointCount); assertEquals(6, m.liveFaceCount)
            assertClosed(m, "长方体")
            val d = finish(m)
            assertEquals(24f, volume(d), 1e-3f)
            assertEquals(12, d.triangleCount)
            // 平直着色：每个面 4 个独立顶点
            assertEquals(24, d.vertexCount)
        }
        run {
            val m = EditMesh.createEmpty("sphere"); MeshPrimitives.sphere(m, 1f, 32, 16)
            assertClosed(m, "球")
            val d = finish(m)
            assertEquals(4.0 / 3.0 * Math.PI, volume(d).toDouble(), 0.1)
            // 平滑着色：法线应当几乎就是位置方向
            for (v in 0 until d.vertexCount) {
                val dot = d.normals[v * 3] * d.positions[v * 3] + d.normals[v * 3 + 1] * d.positions[v * 3 + 1] + d.normals[v * 3 + 2] * d.positions[v * 3 + 2]
                assertTrue("球面法线朝外（顶点 $v：$dot）", dot > 0.97f)
            }
        }
        run {
            val m = EditMesh.createEmpty("cyl"); MeshPrimitives.cylinder(m, 1f, 1f, 2f, 48)
            assertClosed(m, "圆柱")
            assertEquals(2.0 * Math.PI, volume(finish(m)).toDouble(), 0.05)
        }
        run {
            val m = EditMesh.createEmpty("cone"); MeshPrimitives.cylinder(m, 1f, 0f, 3f, 48)
            assertClosed(m, "圆锥")
            assertEquals(Math.PI, volume(finish(m)).toDouble(), 0.05)
        }
        run {
            val m = EditMesh.createEmpty("torus"); MeshPrimitives.torus(m, 2f, 0.5f, 48, 24)
            assertClosed(m, "圆环")
            assertEquals(2.0 * Math.PI * Math.PI * 2.0 * 0.25, volume(finish(m)).toDouble(), 0.3)
        }
        run {
            // 车削一个上下封口的圆柱：轮廓从下往上
            val m = EditMesh.createEmpty("lathe"); MeshPrimitives.lathe(m, floatArrayOf(0f, 0f, 1f, 0f, 1f, 2f, 0f, 2f), 48)
            assertClosed(m, "车削")
            assertEquals(2.0 * Math.PI, volume(finish(m)).toDouble(), 0.05)
        }
        run {
            // L 形（凹多边形）棱柱：面积 3，高 2
            val m = EditMesh.createEmpty("prism")
            MeshPrimitives.prism(m, floatArrayOf(0f, 0f, 2f, 0f, 2f, 1f, 1f, 1f, 1f, 2f, 0f, 2f), 2f)
            assertClosed(m, "棱柱")
            assertEquals(6f, volume(finish(m)), 1e-3f)
        }
        run {
            // 带镜像的摆放矩阵也不能把面翻成朝里
            val m = EditMesh.createEmpty("mirrored")
            MeshPrimitives.box(m, 1f, 1f, 1f, 0, MeshSession.placement(3f, 0f, 0f, 0f, 30f, 0f, -2f, 1f, 1f))
            assertEquals(2f, volume(finish(m)), 1e-3f)
        }
    }

    @Test fun extrudeInsetSubdivideKeepMeshClosed() {
        val m = EditMesh.createEmpty("box")
        val faces = MeshPrimitives.box(m, 2f, 2f, 2f)
        val top = faces[4]
        val n = FloatArray(3)
        m.faceNormal(top, n)
        assertEquals(1f, n[1], 1e-6f)
        // 顶面往上挤出 1：体积 8 → 12
        MeshOps.extrude(m, intArrayOf(top), 1f)
        assertClosed(m, "挤出后")
        assertEquals(12, m.livePointCount); assertEquals(10, m.liveFaceCount)
        assertEquals(12f, volume(finish(m)), 1e-3f)
        val c = FloatArray(3)
        m.faceCenter(top, c)
        assertEquals(2f, c[1], 1e-6f)
        // 内插再往下压：挖出一个坑，体积变小
        MeshOps.inset(m, intArrayOf(top), 0.5f, -0.5f)
        assertClosed(m, "内插后")
        assertEquals(12f - 0.5f * (1f + 4f + 2f) / 3f, volume(finish(m)), 1e-3f)
        // 细分全部面：四边形一分为四，体积不变
        val all = IntArray(m.faceCount) { it }.filter { !m.fDead[it] }.toIntArray()
        val before = volume(finish(m))
        val sub = MeshOps.subdivide(m, all)
        assertEquals(all.size * 4, sub.size)
        assertClosed(m, "细分后")
        assertEquals(before, volume(finish(m)), 1e-3f)
        // 只细分一个面：相邻的面要接上新加的中点，不能裂开
        val one = MeshOps.subdivide(m, intArrayOf(sub[0]))
        assertEquals(4, one.size)
        assertClosed(m, "局部细分后")
        assertEquals(before, volume(finish(m)), 1e-3f)
        // 三角化、整理之后仍然封闭
        MeshOps.triangulate(m, IntArray(m.faceCount) { it })
        m.compact()
        assertClosed(m, "三角化 + 整理后")
        assertEquals(before, volume(finish(m)), 1e-3f)
        for (f in 0 until m.faceCount) assertEquals(3, m.fSize[f])
    }

    @Test fun loopCutMirrorWeldSolidify() {
        // 环切：2x2x2 的方块沿一条竖边切一圈 → 多 4 个点、4 个面
        val m = EditMesh.createEmpty("box")
        MeshPrimitives.box(m, 2f, 2f, 2f)
        // 找一条竖直的边
        var ea = -1; var eb = -1
        m.forEachEdge { a, b -> if (ea < 0 && m.pos[a * 3] == m.pos[b * 3] && m.pos[a * 3 + 2] == m.pos[b * 3 + 2]) { ea = a; eb = b } }
        val added = MeshOps.loopCut(m, ea, eb, 0.25f)
        assertEquals(4, added.size)
        assertEquals(12, m.livePointCount); assertEquals(10, m.liveFaceCount)
        assertClosed(m, "环切后")
        assertEquals(8f, volume(finish(m)), 1e-3f)
        // 新的一圈点都在同一高度上（四条竖边在同一比例处被切开）
        val ys = added.map { m.pos[it * 3 + 1] }.toSet()
        assertEquals("环切得到的点应在同一高度：$ys", 1, ys.size)
        assertEquals(4, m.edgeSel.size)

        // 镜像：半个方块（去掉贴着 x=0 的那个面）镜像后焊成一个整的
        val h = EditMesh.createEmpty("half")
        val hf = MeshPrimitives.box(h, 1f, 2f, 2f, 0, MeshSession.placement(0.5f, 0f, 0f))
        val nn = FloatArray(3)
        val inner = hf.first { h.faceNormal(it, nn); nn[0] < -0.9f }
        MeshOps.deleteFaces(h, intArrayOf(inner))
        MeshOps.mirror(h, hf.filter { it != inner }.toIntArray(), 0, true)
        assertClosed(h, "镜像后")
        assertEquals(12, h.livePointCount)
        assertEquals(8f, volume(finish(h)), 1e-3f)

        // 焊接：两个挨着的方块，重合的 4 个点并掉
        val w = EditMesh.createEmpty("weld")
        MeshPrimitives.box(w, 1f, 1f, 1f)
        MeshPrimitives.box(w, 1f, 1f, 1f, 0, MeshSession.placement(1f, 0f, 0f))
        assertEquals(16, w.livePointCount)
        assertEquals(4, MeshOps.weld(w, IntArray(w.pointCount) { it }, 1e-4f))
        assertEquals(12, w.livePointCount)

        // 加厚：一张平面变成一块板
        val s = EditMesh.createEmpty("slab")
        val pf = MeshPrimitives.plane(s, 2f, 2f, 2, 2)
        MeshOps.solidify(s, pf, 0.5f)
        assertClosed(s, "加厚后")
        assertEquals(2f, volume(finish(s)), 1e-3f)

        // 补面 + 合并：去掉方块的顶面再用四个点补回来；把顶面四个点并成一个得到四棱锥
        val f = EditMesh.createEmpty("fill")
        val ff = MeshPrimitives.box(f, 2f, 2f, 2f)
        val topPts = MeshOps.pointsOfFaces(f, intArrayOf(ff[4]))
        MeshOps.deleteFaces(f, intArrayOf(ff[4]))
        assertTrue(MeshOps.fill(f, topPts, 0, floatArrayOf(0f, 1f, 0f)) >= 0)
        assertClosed(f, "补面后")
        assertEquals(8f, volume(finish(f)), 1e-3f)
        MeshOps.mergeToCenter(f, topPts)
        assertClosed(f, "合并成锥后")
        assertEquals(5, f.livePointCount); assertEquals(5, f.liveFaceCount)
        assertEquals(8f / 3f, volume(finish(f)), 1e-3f)
    }

    @Test fun dissolveAndConnectAreInverse() {
        val m = EditMesh.createEmpty("plane")
        val f = MeshPrimitives.plane(m, 2f, 2f, 1, 1)[0]
        val pts = IntArray(4) { m.cPoint[m.fStart[f] + it] }
        assertTrue(MeshOps.connect(m, pts[0], pts[2]))
        assertEquals(2, m.liveFaceCount)
        assertTrue(MeshOps.dissolveEdge(m, pts[0], pts[2]))
        assertEquals(1, m.liveFaceCount)
        val live = (0 until m.faceCount).first { !m.fDead[it] }
        assertEquals(4, m.fSize[live])
        val n = FloatArray(3)
        m.faceNormal(live, n)
        assertEquals(1f, n[1], 1e-6f)
        // 在边上加点：四边形变五边形
        val np = MeshOps.splitEdge(m, pts[0], pts[1], 0.5f)
        assertTrue(np >= 0)
        assertEquals(5, m.fSize[(0 until m.faceCount).first { !m.fDead[it] }])
    }

    // ------------------------------------------------------------------ 会话 + PMX

    @Test fun sessionEditsReplaceInstanceAndUndo() {
        val scene = Scene()
        scene.camera.update(1f)
        var inst = ModelInstance(boxModel(), ModelInstance.KIND_ACCESSORY)
        scene.add(inst)
        val id = inst.id
        val s = MeshSession(scene, inst)
        var replaced = 0
        s.onReplaced = { _, n -> inst = n; replaced++ }
        assertEquals(id, s.model.id)
        assertTrue(scene.models.single() === s.model)
        assertEquals(8, s.stats().points)
        // 选朝上的面 → 挤出
        assertEquals(1, s.selectFacesByNormal(0f, 1f, 0f, 10f))
        assertEquals(1, s.extrude(1f))
        assertEquals(12, s.stats().points); assertEquals(10, s.stats().faces)
        assertEquals(1, replaced)
        assertTrue(scene.models.single() === inst)
        assertEquals(12f, volume(inst.data), 1e-3f)
        // 拖动（操纵器）：直接改顶点位置，结束时重建
        s.snapshot()
        s.translate(0f, 2f, 0f)
        assertEquals(1, replaced)
        var maxY = -1e9f
        for (v in 0 until inst.data.vertexCount) maxY = maxOf(maxY, inst.data.positions[v * 3 + 1])
        assertEquals(4f, maxY, 1e-5f)
        s.dragEnded()
        assertEquals(2, replaced)
        assertEquals(20f, volume(inst.data), 1e-3f)
        // 撤销两步回到方块，重做一步
        assertTrue(s.undo()); assertEquals(12f, volume(inst.data), 1e-3f)
        assertTrue(s.undo()); assertEquals(8f, volume(inst.data), 1e-3f)
        assertEquals(8, s.stats().points)
        assertFalse(s.undo())
        assertTrue(s.redo()); assertEquals(12f, volume(inst.data), 1e-3f)
        // 加一个球当"组件"，换一种材质
        val mat = s.addMaterial("红", 1f, 0f, 0f)
        assertEquals(1, mat)
        assertTrue(s.addSphere(0.5f, 12, 6, MeshSession.placement(0f, 3f, 0f), mat) > 0)
        assertEquals(2, inst.data.materials.size)
        assertTrue(inst.data.materials[1].indexCount > 0)
        assertEquals(inst.data.indices.size, inst.data.materials.sumOf { it.indexCount })
        s.finish()
        assertFalse(inst.restPose)
        assertTrue(s.dirty)
    }

    @Test fun pmxRoundTripOfBuiltModel() {
        val m = EditMesh.createEmpty("测试模型")
        MeshPrimitives.box(m, 2f, 2f, 2f)
        m.materials.add(EditMesh.defaultMaterial("第二材质", 0.2f, 0.4f, 0.9f, 0.5f))
        MeshPrimitives.sphere(m, 1f, 8, 4, 1, MeshSession.placement(0f, 3f, 0f))
        val d = finish(m)
        val back = PmxReader.read(PmxWriter.write(d))
        assertEquals("测试模型", back.name)
        assertEquals(d.vertexCount, back.vertexCount)
        assertArrayEquals(d.positions, back.positions, 0f)
        assertArrayEquals(d.normals, back.normals, 0f)
        assertArrayEquals(d.uvs, back.uvs, 0f)
        assertArrayEquals(d.indices, back.indices)
        assertEquals(2, back.materials.size)
        assertEquals("第二材质", back.materials[1].name)
        assertEquals(0.5f, back.materials[1].diffuse[3], 0f)
        assertEquals(d.materials[1].indexCount, back.materials[1].indexCount)
        assertEquals(1, back.bones.size)
        assertEquals("センター", back.bones[0].name)
        assertTrue(back.warnings.isEmpty())
    }

    @Test fun realPmxSurvivesWriteAndMeshRoundTrip() {
        val f = assets?.let { File(it, "model.pmx") }?.takeIf { it.isFile }
        assumeTrue("没有真实 PMX（LUMI_TEST_ASSETS）", f != null)
        val a = ModelLoader.loadModel(f!!)
        // 1) 原样写出再读回：所有数据一致
        val b = PmxReader.read(PmxWriter.write(a))
        assertEquals(a.vertexCount, b.vertexCount)
        assertArrayEquals(a.positions, b.positions, 0f)
        assertArrayEquals(a.normals, b.normals, 0f)
        assertArrayEquals(a.uvs, b.uvs, 0f)
        assertArrayEquals(a.indices, b.indices)
        assertArrayEquals(a.boneIndices, b.boneIndices)
        assertArrayEquals(a.boneWeights, b.boneWeights, 1e-6f)
        assertArrayEquals(a.edgeScales, b.edgeScales, 0f)
        assertEquals(a.hasSdef, b.hasSdef)
        if (a.hasSdef) {
            assertArrayEquals(a.sdefC, b.sdefC, 1e-4f); assertArrayEquals(a.sdefR0, b.sdefR0, 1e-4f); assertArrayEquals(a.sdefR1, b.sdefR1, 1e-4f)
            assertArrayEquals(a.skinTypes, b.skinTypes)
        }
        assertEquals(a.textures.map { it.path }, b.textures.map { it.path })
        assertEquals(a.materials.size, b.materials.size)
        for (i in a.materials.indices) {
            val x = a.materials[i]; val y = b.materials[i]
            assertEquals(x.name, y.name); assertEquals(x.indexCount, y.indexCount); assertEquals(x.indexOffset, y.indexOffset)
            assertArrayEquals(x.diffuse, y.diffuse, 0f); assertArrayEquals(x.ambient, y.ambient, 0f); assertArrayEquals(x.edgeColor, y.edgeColor, 0f)
            assertEquals(x.flags, y.flags); assertEquals(x.texture, y.texture); assertEquals(x.sphereTexture, y.sphereTexture)
            assertEquals(x.sphereMode, y.sphereMode); assertEquals(x.sharedToon, y.sharedToon); assertEquals(x.toonIndex, y.toonIndex)
        }
        assertEquals(a.bones.size, b.bones.size)
        for (i in a.bones.indices) {
            val x = a.bones[i]; val y = b.bones[i]
            assertEquals(x.name, y.name); assertEquals(x.parent, y.parent); assertEquals(x.flags, y.flags); assertEquals(x.level, y.level)
            assertArrayEquals(x.position, y.position, 0f); assertEquals(x.tailBone, y.tailBone); assertEquals(x.appendParent, y.appendParent)
            assertEquals(x.appendWeight, y.appendWeight, 0f)
            assertEquals(x.ik?.links?.size, y.ik?.links?.size); assertEquals(x.ik?.target, y.ik?.target)
        }
        assertEquals(a.morphs.size, b.morphs.size)
        for (i in a.morphs.indices) {
            val x = a.morphs[i]; val y = b.morphs[i]
            assertEquals(x.name, y.name); assertEquals(x.type, y.type); assertEquals(x.count, y.count)
            assertArrayEquals(x.indices.copyOf(x.count), y.indices.copyOf(y.count))
            assertArrayEquals(x.data.copyOf(x.count * x.stride), y.data.copyOf(y.count * y.stride), 0f)
        }
        assertEquals(a.displayFrames.size, b.displayFrames.size)
        assertEquals(a.rigidBodies.size, b.rigidBodies.size)
        assertEquals(a.joints.size, b.joints.size)
        for (i in a.rigidBodies.indices) {
            assertEquals(a.rigidBodies[i].bone, b.rigidBodies[i].bone); assertEquals(a.rigidBodies[i].mass, b.rigidBodies[i].mass, 0f)
            assertArrayEquals(a.rigidBodies[i].size, b.rigidBodies[i].size, 0f)
        }

        // 2) 拆成可编辑网格再原样拼回去：三角形一个不少，每个三角形的三个顶点位置 / 法线 / UV 都不变
        val em = EditMesh.fromModel(a)
        assertTrue("重复顶点应当被并成点：${em.livePointCount} < ${a.vertexCount}", em.livePointCount < a.vertexCount)
        var quads = 0
        for (fi in 0 until em.faceCount) if (!em.fDead[fi] && em.fSize[fi] == 4) quads++
        println("胡桃：${a.vertexCount} 顶点 / ${a.triangleCount} 三角形 → ${em.livePointCount} 点 / ${em.liveFaceCount} 面（其中四边形 $quads）")
        val c = em.build().data
        assertEquals(a.materials.size, c.materials.size)
        var dropped = 0
        var ia = 0
        for (mi in a.materials.indices) {
            val ma = a.materials[mi]; val mc = c.materials[mi]
            var ic = mc.indexOffset
            ia = ma.indexOffset
            while (ia < ma.indexOffset + ma.indexCount) {
                val a0 = a.indices[ia]; val a1 = a.indices[ia + 1]; val a2 = a.indices[ia + 2]
                ia += 3
                // 退化的三角形（两个顶点重合）会被丢掉
                fun same(p: Int, q: Int) = a.positions[p * 3] == a.positions[q * 3] && a.positions[p * 3 + 1] == a.positions[q * 3 + 1] && a.positions[p * 3 + 2] == a.positions[q * 3 + 2]
                if (same(a0, a1) || same(a1, a2) || same(a0, a2)) { dropped++; continue }
                // 并成四边形再拆开后，三角形的起始顶点可能轮换过（同一个三角形）
                var rot = -1
                for (r in 0 until 3) {
                    var ok = true
                    for (k in 0 until 3) {
                        val va = a.indices[ia - 3 + k]; val vc = c.indices[ic + (k + r) % 3]
                        for (j in 0 until 3) if (a.positions[va * 3 + j] != c.positions[vc * 3 + j]) ok = false
                    }
                    if (ok) { rot = r; break }
                }
                assertTrue("材质 ${ma.name} 第 ${(ia - 3 - ma.indexOffset) / 3} 个三角形对不上", rot >= 0)
                for (k in 0 until 3) {
                    val va = a.indices[ia - 3 + k]; val vc = c.indices[ic + (k + rot) % 3]
                    for (j in 0 until 3) {
                        assertEquals(a.positions[va * 3 + j], c.positions[vc * 3 + j], 0f)
                        assertEquals(a.normals[va * 3 + j], c.normals[vc * 3 + j], 0f)
                    }
                    assertEquals(a.uvs[va * 2], c.uvs[vc * 2], 0f); assertEquals(a.uvs[va * 2 + 1], c.uvs[vc * 2 + 1], 0f)
                    for (j in 0 until 4) {
                        assertEquals(a.boneWeights!![va * 4 + j], c.boneWeights!![vc * 4 + j], 0f)
                        if (a.boneWeights!![va * 4 + j] != 0f) assertEquals(a.boneIndices!![va * 4 + j], c.boneIndices!![vc * 4 + j])
                    }
                }
                ic += 3
            }
            assertEquals("材质 ${ma.name} 的三角形数", mc.indexOffset + mc.indexCount, ic)
        }
        assertTrue("被丢弃的退化三角形不应太多：$dropped", dropped < a.triangleCount / 50 + 10)
        // 顶点表情：每个表情影响到的"位置集合"和位移不变
        assertEquals(a.morphs.size, c.morphs.size)
        for (i in a.morphs.indices) {
            val x = a.morphs[i]; val y = c.morphs[i]
            if (x.type != MorphData.TYPE_VERTEX) continue
            fun keyset(d: ModelData, m: MorphData): Set<String> {
                val s = HashSet<String>()
                for (k in 0 until m.count) {
                    val v = m.indices[k]
                    s.add("${d.positions[v * 3]},${d.positions[v * 3 + 1]},${d.positions[v * 3 + 2]}|${m.data[k * 3]},${m.data[k * 3 + 1]},${m.data[k * 3 + 2]}")
                }
                return s
            }
            val ka = keyset(a, x); val kc = keyset(c, y)
            assertTrue("表情 ${x.name}：原有 ${ka.size} 项，重建后 ${kc.size} 项，缺 ${(ka - kc).size} 多 ${(kc - ka).size}", ka == kc)
        }
        // 3) 改一下再存成 PMX 还能读回来
        val pts = IntArray(em.pointCount) { it }.filter { !em.pDead[it] }.take(100).toIntArray()
        MeshOps.translatePoints(em, pts, 0f, 0.1f, 0f)
        val edited = em.build().data
        val reread = PmxReader.read(PmxWriter.write(edited))
        assertEquals(edited.vertexCount, reread.vertexCount)
        assertEquals(edited.bones.size, reread.bones.size)
        assertEquals(edited.morphs.size, reread.morphs.size)
        assertTrue(abs(edited.boundsMax[1] - reread.boundsMax[1]) < 1e-5f)
    }
}

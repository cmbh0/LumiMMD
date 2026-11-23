package com.lumi.mmd.engine

import com.lumi.mmd.engine.format.BlendReader
import com.lumi.mmd.engine.format.FormatException
import com.lumi.mmd.engine.format.ModelLoader
import com.lumi.mmd.engine.format.ModelRefs
import com.lumi.mmd.engine.mesh.EditMesh
import com.lumi.mmd.engine.mesh.MeshPrimitives
import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.ModelFormat
import com.lumi.mmd.engine.model.MorphData
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.scene.PartOps
import com.lumi.mmd.engine.scene.Scene
import com.lumi.mmd.engine.scene.SceneIO
import com.lumi.mmd.engine.scene.SceneOps
import com.lumi.mmd.engine.util.Zstd
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 直接读 .blend，以及「部件」的删除 / 合并。
 *
 * resources/blend 里的四个 .blend 是用四个真的 Blender（2.79、3.6、4.2、5.3）跑同一个脚本存出来的同一个场景，
 * 都是压缩保存的（2.79 是 gzip，其余是 zstd）。场景里有：父子关系、负缩放、四元数旋转、共用网格、集合实例、
 * 不渲染的物体和集合、镜像 / 阵列 / 细分修改器、凹多边形、物体级材质、打包的贴图和外部贴图、文字物体。
 * 下面核对的数字（三角形数、包围盒）是当时让 Blender 自己算出来的。
 */
class BlendTest {
    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/blend/$name")!!.use { it.readBytes() }

    private fun unpack(vararg names: String, textures: Boolean = true): File {
        val dir = Files.createTempDirectory("lumi-blend").toFile()
        for (n in names) File(dir, n).writeBytes(resource(n))
        if (textures) File(dir, "textures").also { it.mkdirs() }.resolve("checker.png").writeBytes(resource("textures/checker.png"))
        return dir
    }

    // ------------------------------------------------------------------ zstd

    @Test fun zstdDecodesWhatTheReferenceEncoderWrote() {
        // 用参考实现（各种压缩级别、流式、多帧、带校验和、可跳过帧……）压出来的数据
        val d = DataInputStream(ByteArrayInputStream(resource("zstd_cases.bin")))
        fun int(): Int { val b = ByteArray(4); d.readFully(b); return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int }
        val count = int()
        assertTrue(count >= 30)
        var bytes = 0L
        repeat(count) {
            val name = ByteArray(int()).also { d.readFully(it) }.toString(Charsets.UTF_8)
            val length = int()
            val crc = int()
            val comp = ByteArray(int()).also { d.readFully(it) }
            val out = try { Zstd.decompress(comp) } catch (e: Throwable) { throw AssertionError("$name：${e.message}", e) }
            assertEquals("$name 的长度", length, out.size)
            assertEquals("$name 的内容", crc, CRC32().also { it.update(out) }.value.toInt())
            bytes += out.size
        }
        assertTrue("一共解了 $bytes 字节", bytes > 4_500_000)
    }

    @Test fun zstdStreamsFrameByFrame() {
        // 大文件走的那条路：从（映射的）缓冲区一帧一帧解，直接写出去，结果和整个解到内存里一样
        val d = DataInputStream(ByteArrayInputStream(resource("zstd_cases.bin")))
        fun int(): Int { val b = ByteArray(4); d.readFully(b); return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int }
        val count = int()
        repeat(count) {
            val name = ByteArray(int()).also { d.readFully(it) }.toString(Charsets.UTF_8)
            val length = int()
            val crc = int()
            val comp = ByteArray(int()).also { d.readFully(it) }
            val sink = java.io.ByteArrayOutputStream()
            val n = try { Zstd.decompress(ByteBuffer.wrap(comp), sink) } catch (e: Throwable) { throw AssertionError("$name：${e.message}", e) }
            assertEquals("$name 的长度", length.toLong(), n)
            assertEquals("$name 的内容", crc, CRC32().also { it.update(sink.toByteArray()) }.value.toInt())
            // 截断的数据：报错，而不是崩
            if (comp.size > 200) try { Zstd.decompress(ByteBuffer.wrap(comp, 0, comp.size / 2).slice(), java.io.ByteArrayOutputStream()) } catch (_: Zstd.ZstdException) {}
        }
        // 超过上限：报错
        try { Zstd.decompress(ByteBuffer.wrap(resource("b42_z.blend")), java.io.ByteArrayOutputStream(), maxSize = 100_000); fail("超过上限应当报错") } catch (_: Zstd.ZstdException) {}
    }

    /**
     * 真机上的那次闪退：导入一个大场景时把堆内存用光了（应用被系统直接杀掉）。
     * 现在 .blend 不读进堆内存；场景比内存放得下的大时，只导入放得下的部分并说明，而不是硬撑。
     */
    @Test fun sceneLargerThanMemoryIsCutShortNotCrashed() {
        val dir = unpack("b53_z.blend")
        val file = File(dir, "b53_z.blend")
        BlendReader.triangleBudgetForTest = 300
        try {
            val m = ModelLoader.loadModel(file)
            assertTrue("只导入了放得下的部分：${m.triangleCount}", m.triangleCount in 1..300)
            assertTrue("要说明没有导完：${m.warnings}", m.warnings.any { it.startsWith("场景太大") && it.contains("没有导入") })
        } finally { BlendReader.triangleBudgetForTest = 0 }
        assertEquals(655, ModelLoader.loadModel(file).triangleCount)
        // 解压用的临时文件用完就删掉了
        val tmp = File(System.getProperty("java.io.tmpdir"))
        assertTrue(tmp.listFiles { f -> f.name.startsWith("lumi-blend-") && f.name.endsWith(".tmp") }.isNullOrEmpty())
    }

    @Test fun zstdRejectsBrokenData() {
        val d = DataInputStream(ByteArrayInputStream(resource("zstd_cases.bin")))
        fun int(): Int { val b = ByteArray(4); d.readFully(b); return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int }
        val count = int()
        var checked = 0
        repeat(count) {
            val name = ByteArray(int()).also { d.readFully(it) }.toString(Charsets.UTF_8)
            val length = int(); int()
            val comp = ByteArray(int()).also { d.readFully(it) }
            if (length < 1000 || comp.size < 200) return@repeat
            // 截断：要么报错，要么（正好断在帧的边界上）得到一段前缀；不能死循环，也不能抛别的异常
            for (cut in intArrayOf(comp.size / 2, comp.size - 6, 13)) {
                try {
                    val out = Zstd.decompress(comp.copyOf(cut))
                    // 断在末尾附近时可能只少了校验和 / 索引帧，数据本身是全的；断在一半就一定不全
                    if (cut <= comp.size / 2) assertTrue("$name 截断到 $cut 之后不应该解出完整的数据", out.size < length)
                } catch (_: Zstd.ZstdException) {}
            }
            // 中间改坏几个字节：不能崩
            val bad = comp.copyOf()
            for (k in 0 until 6) bad[20 + (k * 7919) % (bad.size - 40)] = (bad[20 + (k * 7919) % (bad.size - 40)] + 97 + k).toByte()
            try { Zstd.decompress(bad, maxSize = 16L * 1024 * 1024) } catch (_: Zstd.ZstdException) {}
            checked++
        }
        assertTrue(checked > 10)
        try { Zstd.decompress(ByteArray(64) { it.toByte() }); fail("不是 zstd 的数据应当报错") } catch (_: Zstd.ZstdException) {}
    }

    // ------------------------------------------------------------------ .blend

    private fun part(m: ModelData, name: String): MaterialData = m.materials.firstOrNull { it.name == name } ?: throw AssertionError("没有部件「$name」：${m.materials.map { it.name }}")

    /** 一个部件所有三角形的面积之和。 */
    private fun area(m: ModelData, mat: MaterialData): Float {
        var sum = 0.0
        val p = m.positions; val idx = m.indices
        var t = mat.indexOffset
        while (t + 2 < mat.indexOffset + mat.indexCount) {
            val a = idx[t] * 3; val b = idx[t + 1] * 3; val c = idx[t + 2] * 3
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            sum += 0.5 * sqrt((nx * nx + ny * ny + nz * nz).toDouble())
            t += 3
        }
        return sum.toFloat()
    }

    private fun checkScene(file: File, version: String, compression: String, nodes: Boolean) {
        val m = ModelLoader.loadModel(file)
        val tag = file.name
        assertEquals(ModelFormat.BLEND, m.format)
        assertTrue("$tag：${m.comment}", m.comment.contains("Blender $version") && m.comment.contains(compression) && m.comment.contains("18 个物体") && m.comment.contains("1 个实例"))
        // Blender 自己算的结果是 691 个三角形（不算文字物体）；细分修改器读不了，那个方块按原样的 12 个三角形进来，少 36 个
        assertEquals(tag, 655, m.triangleCount)
        // 包围盒：Blender 里是 (-6.5, -5, 0) ~ (7, 6.366, 3.2) 米，Z 向上；换成 MMD 的 Y 向上、1 米 = 12.5
        m.computeBounds()
        val lo = floatArrayOf(-81.25f, 0f, -62.5f); val hi = floatArrayOf(87.5f, 40f, 79.5753f)
        for (k in 0 until 3) { assertEquals("$tag min[$k]", lo[k], m.boundsMin[k], 2e-3f); assertEquals("$tag max[$k]", hi[k], m.boundsMax[k], 2e-3f) }
        // 物体不多：一个物体一个部件，两种材质的物体拆成两个
        assertEquals(tag, 19, m.materials.size)
        val tris = mapOf("Floor" to 2, "Cube · Red" to 10, "Cube · Blue" to 2, "Child" to 12, "Ball" to 120, "BallLinked" to 120, "Ngon" to 7, "Mirrored" to 14,
            "QuatBox" to 12, "NoMat" to 12, "ObjMat" to 12, "MirrorHalf" to 8, "ArrayPost" to 48, "SubsurfBox" to 12, "PropCube" to 12, "PropTop" to 120, "DecorBox" to 12, "SharpCyl" to 60, "AngleCyl" to 60)
        for ((name, n) in tris) assertEquals("$tag $name", n, part(m, name).indexCount / 3)
        assertTrue("不渲染的物体和集合不该进来", m.materials.none { it.name.contains("Hidden") || it.name.contains("OffBox") })

        // ---- 材质
        val red = part(m, "Cube · Red")
        assertEquals(0.9f, red.diffuse[0], 0.02f); assertEquals(0.1f, red.diffuse[1], 0.02f); assertEquals(0.1f, red.diffuse[2], 0.02f)
        assertTrue(red.texture < 0 && red.alphaMode == MaterialData.ALPHA_OPAQUE)
        assertTrue("Blender 默认两面都画", red.flags and MaterialData.FLAG_DOUBLE_SIDED != 0)
        val glass = part(m, "Ngon")
        assertEquals(0.35f, glass.diffuse[3], 0.01f)
        assertEquals(MaterialData.ALPHA_BLEND, glass.alphaMode)
        // 物体级材质：网格上挂的是 Plain（灰），物体上换成了 Blue
        val objMat = part(m, "ObjMat")
        assertEquals(0.9f, objMat.diffuse[2], 0.02f); assertEquals(0.1f, objMat.diffuse[0], 0.02f)
        assertEquals("没有材质的物体用默认的灰色", 0.8f, part(m, "NoMat").diffuse[0], 0.01f)
        if (nodes) {
            val blue = part(m, "Cube · Blue")
            assertEquals(0.5f, blue.emissive[2], 0.02f); assertEquals(0.1f, blue.emissive[1], 0.02f)
        }
        // ---- 贴图：外部文件按相对路径记着；打包在 .blend 里的直接取出来
        val floor = part(m, "Floor")
        assertTrue(floor.texture >= 0)
        assertEquals("textures/checker.png", m.textures[floor.texture].path)
        assertEquals(1f, floor.diffuse[0], 1e-3f)
        val ball = part(m, "Ball")
        // 打包的贴图不留在内存里：解到 .blend 旁边的 "<文件名>.tex/" 文件夹，和外部贴图一样按路径读
        val packedRef = m.textures[ball.texture]
        assertTrue("打包的贴图应当解到了磁盘上：${packedRef.path}", packedRef.embedded == null && packedRef.path.startsWith(file.name + ".tex/"))
        val packed = File(file.parentFile, packedRef.path).readBytes()
        assertTrue("取出来的应当是一张 PNG", packed.size > 60 && packed[1] == 'P'.code.toByte() && packed[2] == 'N'.code.toByte() && packed[3] == 'G'.code.toByte())
        assertEquals("共用同一张图的材质只存一份", ball.texture, part(m, "PropTop").texture)
        assertEquals(tag, 2, m.textures.size)
        assertTrue(ModelRefs.missing(file).isEmpty())

        // ---- 形状
        // 凹的六边形（L 形，3 平米）+ 一个 1 平米的四边形 + 半平米的三角形 = 4.5 平米；从第一个角拉扇形会得到 5.5
        assertEquals("$tag 凹多边形", 4.5f * 12.5f * 12.5f, area(m, part(m, "Ngon")), 0.05f)
        // 每个三角形的绕向都和顶点法线一致（包括负缩放的物体、镜像出来的那一半、实例）
        val p = m.positions; val nr = m.normals; val idx = m.indices
        var t = 0
        var volume = 0.0
        while (t + 2 < idx.size) {
            val a = idx[t] * 3; val b = idx[t + 1] * 3; val c = idx[t + 2] * 3
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (v in intArrayOf(a, b, c)) assertTrue("$tag 第 ${t / 3} 个三角形的绕向和法线相反", nx * nr[v] + ny * nr[v + 1] + nz * nr[v + 2] > 0f)
            t += 3
        }
        // 封闭的方块：体积是正的（面朝外），大小也对。Cube 边长 2 米 → 25 个单位
        for ((name, edge) in listOf("Child" to 6.25f, "QuatBox" to 10f, "PropCube" to 25f)) {
            val mat = part(m, name)
            volume = 0.0
            var k = mat.indexOffset
            while (k + 2 < mat.indexOffset + mat.indexCount) {
                val a = idx[k] * 3; val b = idx[k + 1] * 3; val c = idx[k + 2] * 3
                volume += (p[a] * (p[b + 1] * p[c + 2] - p[b + 2] * p[c + 1]) - p[a + 1] * (p[b] * p[c + 2] - p[b + 2] * p[c]) + p[a + 2] * (p[b] * p[c + 1] - p[b + 1] * p[c])) / 6.0
                k += 3
            }
            assertEquals("$tag $name 的体积", (edge * edge * edge).toDouble(), volume, edge * edge * edge * 0.002)
        }
        // 平滑着色的球：法线从球心指向外；平直着色的方块：每个面一条法线（24 个顶点）
        val ballVerts = HashSet<Int>()
        run { var k = ball.indexOffset; while (k < ball.indexOffset + ball.indexCount) { ballVerts.add(idx[k]); k++ } }
        for (v in ballVerts) {
            // 球心在 Blender 的 (-3, 1, 1) → MMD 的 (-37.5, 12.5, 12.5)，半径 12.5
            val dx = (p[v * 3] + 37.5f) / 12.5f; val dy = (p[v * 3 + 1] - 12.5f) / 12.5f; val dz = (p[v * 3 + 2] - 12.5f) / 12.5f
            assertTrue("$tag 球面法线", dx * nr[v * 3] + dy * nr[v * 3 + 1] + dz * nr[v * 3 + 2] > 0.93f)
        }
        val child = part(m, "Child")
        val boxVerts = HashSet<Int>()
        run { var k = child.indexOffset; while (k < child.indexOffset + child.indexCount) { boxVerts.add(idx[k]); k++ } }
        assertEquals("$tag 平直着色的方块", 24, boxVerts.size)
        // 两个圆柱都是"全部平滑着色"：一个把盖子的边标成了锐边，一个开了 40° 的自动平滑（4.1 起是个修改器）。
        // 侧面之间是平滑的（一圈 16 个顶点上下各一份 = 32），盖子和侧面之间是硬边（盖子另有 32 个）
        for (name in listOf("SharpCyl", "AngleCyl")) {
            val mat = part(m, name)
            val verts = HashSet<Int>()
            var k = mat.indexOffset
            while (k < mat.indexOffset + mat.indexCount) { verts.add(idx[k]); k++ }
            assertEquals("$tag $name 的硬边", 64, verts.size)
            // 盖子上的法线朝上 / 朝下，侧面的法线是水平的
            for (v in verts) { val ny = abs(nr[v * 3 + 1]); assertTrue("$tag $name 的法线 $ny", ny < 1e-3f || ny > 0.999f) }
        }
        // UV：地板的 UV 是 0~4（贴图平铺四遍）；V 方向翻成了左上角为原点
        val floorVerts = HashSet<Int>()
        run { var k = floor.indexOffset; while (k < floor.indexOffset + floor.indexCount) { floorVerts.add(idx[k]); k++ } }
        assertEquals(4, floorVerts.size)
        assertEquals(setOf("0.0,1.0", "4.0,1.0", "4.0,-3.0", "0.0,-3.0"), floorVerts.map { "${m.uvs[it * 2]},${m.uvs[it * 2 + 1]}" }.toSet())

        // ---- 提醒
        assertTrue("$tag：${m.warnings}", m.warnings.any { it.contains("表面细分 ×1") } && m.warnings.any { it.contains("文字 ×1") })
        assertTrue("「按角度平滑」不算读不了的修改器：${m.warnings}", m.warnings.none { it.contains("几何节点") })
        assertEquals("按尺寸判断成舞台", ModelInstance.KIND_STAGE, SceneOps.guessKind(m, file.name))
    }

    @Test fun readsBlender279() = checkScene(File(unpack("b279_z.blend"), "b279_z.blend"), "2.79", "gzip", false)
    @Test fun readsBlender36() = checkScene(File(unpack("b36_z.blend"), "b36_z.blend"), "3.6", "zstd", true)
    @Test fun readsBlender42() = checkScene(File(unpack("b42_z.blend"), "b42_z.blend"), "4.2", "zstd", true)
    @Test fun readsBlender53() = checkScene(File(unpack("b53_z.blend"), "b53_z.blend"), "5.3", "zstd", true)

    @Test fun blendTellsWhichTexturesAreMissing() {
        // 只带来了 .blend 本身：外部贴图算"缺的"（应用据此去原来的文件夹找），打包的那张不算
        val dir = unpack("b42_z.blend", textures = false)
        val file = File(dir, "b42_z.blend")
        assertEquals(listOf("textures/checker.png"), ModelRefs.missing(file))
        File(dir, "textures").mkdirs()
        File(dir, "textures/checker.png").writeBytes(resource("textures/checker.png"))
        assertTrue(ModelRefs.missing(file).isEmpty())
        assertTrue(ModelLoader.isModelFile("舞台.blend") && ModelLoader.isModelFile("角色.vrm"))
    }

    @Test fun brokenBlendFilesGiveReadableErrors() {
        val dir = Files.createTempDirectory("lumi-blend-bad").toFile()
        fun load(name: String, bytes: ByteArray): String = try { File(dir, name).writeBytes(bytes); ModelLoader.loadModel(File(dir, name)); "" } catch (e: FormatException) { e.message ?: "?" }
        assertTrue(load("a.blend", ByteArray(300) { (it * 7).toByte() }).contains("不是 .blend"))
        assertTrue(load("b.blend", "BLENDER-v279".toByteArray() + ByteArray(40)).isNotEmpty())
        // 压缩的文件只有前一半
        val z = resource("b42_z.blend")
        assertTrue(load("c.blend", z.copyOf(z.size / 2)).isNotEmpty())
        // 解压之后截断：块不完整
        val plain = Zstd.decompress(z)
        assertTrue(plain.size > 600_000 && String(plain, 0, 7, Charsets.ISO_8859_1) == "BLENDER")
        assertTrue(load("d.blend", plain.copyOf(plain.size / 3)).contains("不完整"))
        // 不压缩的也能读（内容一样）
        File(dir, "e.blend").writeBytes(plain)
        File(dir, "textures").mkdirs()
        assertEquals(655, ModelLoader.loadModel(File(dir, "e.blend")).triangleCount)
        // 扩展名写错了也认得出来
        File(dir, "f.fbx").writeBytes(plain)
        assertEquals(ModelFormat.BLEND, ModelLoader.loadModel(File(dir, "f.fbx")).format)
    }

    // ------------------------------------------------------------------ 部件：删除与合并

    private fun twoBoxes(): ModelData {
        // 两个材质：一个方块 + 另一个方块（分开的两块）
        val em = EditMesh.createEmpty("两个箱子")
        MeshPrimitives.box(em, 2f, 2f, 2f)
        em.materials.add(EditMesh.defaultMaterial("第二个", 0.9f, 0.2f, 0.2f))
        MeshPrimitives.box(em, 1f, 1f, 1f, material = 1)
        em.recalcNormals()
        val d = em.build().data
        // 加一个材质表情，分别指着两个材质
        val mo = MorphData()
        mo.name = "变色"; mo.type = MorphData.TYPE_MATERIAL
        mo.count = 2; mo.indices = intArrayOf(0, 1); mo.data = FloatArray(MorphData.MATERIAL_STRIDE * 2)
        d.morphs.add(mo)
        return d
    }

    @Test fun deletedPartsDisappearAndComeBack() {
        val scene = Scene()
        val m = ModelInstance(twoBoxes(), ModelInstance.KIND_ACCESSORY)
        scene.add(m)
        assertEquals(2, m.data.materials.size)
        val second = m.parts.materialParts[1]
        assertFalse(PartOps.hasEdits(m))
        // 删除：标记 + 材质不画 + 顶点缩成一点
        second.deleted = true
        m.materials[1].visible = false
        m.parts.apply(second)
        assertTrue(second.isEdited && PartOps.hasEdits(m))
        assertEquals(listOf(second), m.parts.deletedParts())
        val base = m.morphs.basePositions
        val first = second.vertices[0]
        for (v in second.vertices) for (k in 0 until 3) assertEquals(base[first * 3 + k], base[v * 3 + k], 1e-6f)
        // 存进工程再读回来：还是删掉的
        val saved = SceneIO.saveModel(m, scene)
        val again = ModelInstance(twoBoxes(), ModelInstance.KIND_ACCESSORY)
        SceneIO.restoreModel(again, saved)
        assertTrue(again.parts.materialParts[1].deleted && !again.materials[1].visible && !again.parts.materialParts[0].deleted)
        // 恢复
        second.reset(); m.materials[1].visible = true; m.parts.apply(second)
        assertFalse(PartOps.hasEdits(m))
        for (v in second.vertices) for (k in 0 until 3) assertEquals(m.data.positions[v * 3 + k], m.morphs.basePositions[v * 3 + k], 1e-6f)
    }

    @Test fun mergingPartEditsRewritesTheModel() {
        val scene = Scene()
        val m = ModelInstance(twoBoxes(), ModelInstance.KIND_ACCESSORY)
        scene.add(m)
        val tris = m.data.triangleCount
        val firstTris = m.data.materials[0].indexCount / 3
        // 第一个部件挪 5 个单位，第二个删掉
        val a = m.parts.materialParts[0]; val b = m.parts.materialParts[1]
        a.offset[0] = 5f; m.parts.apply(a)
        b.deleted = true; m.materials[1].visible = false; m.parts.apply(b)
        val baked = PartOps.bake(m)
        assertEquals(listOf(0, -1), baked.materialMap.toList())
        assertEquals(1, baked.data.materials.size)
        assertEquals(firstTris, baked.data.triangleCount)
        assertTrue(baked.data.triangleCount < tris)
        baked.data.computeBounds()
        assertEquals("挪过的位置写进了顶点", 4f, baked.data.boundsMin[0], 1e-4f)
        assertEquals(6f, baked.data.boundsMax[0], 1e-4f)
        assertTrue("没人用的顶点去掉了", baked.data.vertexCount < m.data.vertexCount)
        // 材质表情：指着被删材质的那一条去掉了，剩下的还指着第 0 个
        val mo = baked.data.morphs.first { it.type == MorphData.TYPE_MATERIAL }
        assertEquals(1, mo.count); assertEquals(0, mo.indices[0])
        // 换进场景：新实例没有部件调整，编号、位置、名字都沿用
        m.position[1] = 3f; m.name = "改过的"
        val neu = PartOps.merge(scene, m)
        assertTrue(neu !== m && scene.models.contains(neu) && !scene.models.contains(m))
        assertEquals(m.id, neu.id); assertEquals(3f, neu.position[1], 0f); assertEquals("改过的", neu.name)
        assertFalse(PartOps.hasEdits(neu))
        assertTrue(neu.materials.all { it.visible })
        assertEquals(1, neu.data.materials.size)
        // 没有调整时原样返回
        assertTrue(PartOps.merge(scene, neu) === neu)
        // 只剩一个材质时删不光：至少留一个
        val last = neu.parts.materialParts[0]
        last.deleted = true; neu.parts.apply(last)
        assertEquals(1, PartOps.bake(neu).data.materials.size)
    }
}

package com.lumi.mmd

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lumi.mmd.core.SceneController
import com.lumi.mmd.core.Slot
import com.lumi.mmd.core.SourceAccess
import com.lumi.mmd.engine.anim.Motion
import com.lumi.mmd.engine.format.PmxWriter
import com.lumi.mmd.engine.format.VmdWriter
import com.lumi.mmd.engine.mesh.EditMesh
import com.lumi.mmd.engine.mesh.MeshPrimitives
import com.lumi.mmd.engine.model.TextureRef
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「添加文件」这条路（不带界面）：只选一个 .pmx 时回原来的文件夹把贴图带上；读不了那个文件夹时提示用户授权；
 * 动作、表情、镜头按内容自动落到各自的槽里；一个包里有好几个模型时让用户挑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportFlowTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val gl = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-gl") }
    private lateinit var c: SceneController

    @After fun tearDown() {
        if (::c.isInitialized) c.release()
        gl.shutdownNow()
    }

    private fun <T> onGl(block: () -> T): T = gl.submit(Callable { block() }).get(60, TimeUnit.SECONDS)

    private fun start() {
        c = SceneController(context)
        c.glPost = { r -> try { gl.execute(r) } catch (_: java.util.concurrent.RejectedExecutionException) {} }
        c.glRequestRender = {}
        c.glSetContinuous = {}
        onGl { c.onResize(1080, 2340) }
    }

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 60_000
        while (true) {
            onGl { c.drawFrame() }
            if (cond()) return
            check(System.currentTimeMillis() < end) { "等待超时：$what（${c.loading.value}）" }
            Thread.sleep(15)
        }
    }

    /** 等后台的导入 / 加载都做完。 */
    private fun settle() {
        Thread.sleep(80)
        await("空闲") { c.loading.value.isEmpty() }
        repeat(3) { Thread.sleep(40); await("空闲") { c.loading.value.isEmpty() } }
        onGl { c.drawFrame(); c.drawFrame() }
    }

    /** 写一个带贴图引用的 PMX。 */
    private fun writePmx(file: File, textures: List<String>, name: String = "测试角色") {
        val em = EditMesh.createEmpty(name)
        MeshPrimitives.box(em, 2f, 2f, 2f)
        val data = em.build().data
        for (t in textures) data.textures.add(TextureRef(t))
        if (textures.isNotEmpty()) data.materials[0].texture = 0
        if (textures.size > 1) data.materials[0].sphereTexture = 1
        file.parentFile?.mkdirs()
        PmxWriter.save(data, file)
    }

    private fun bodyVmd(): ByteArray {
        val m = Motion()
        m.boneTrack("センター").set(0, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
        m.boneTrack("センター").set(30, 0f, 2f, 0f, 0f, 0f, 0f, 1f)
        m.morphTrack("あ").set(0, 0f)
        m.morphTrack("あ").set(30, 1f)
        m.recomputeMaxFrame()
        return VmdWriter.write(m, "测试角色")
    }

    private fun faceVmd(): ByteArray {
        val m = Motion()
        m.morphTrack("まばたき").set(0, 0f)
        m.morphTrack("まばたき").set(10, 1f)
        m.recomputeMaxFrame()
        return VmdWriter.write(m, "测试角色")
    }

    private fun cameraVmd(): ByteArray {
        val m = Motion()
        m.cameraTrack().set(0, floatArrayOf(-45f, 0f, 10f, 0f, 0f, 0f, 0f, 30f), 0, true)
        m.cameraTrack().set(60, floatArrayOf(-30f, 0f, 12f, 0f, 0.2f, 0.5f, 0f, 30f), 0, true)
        m.recomputeMaxFrame()
        return VmdWriter.write(m, "カメラ・照明")
    }

    @Test fun pmxAloneBringsItsTextures() {
        start()
        // 手机里解压出来的文件夹：模型、子文件夹里的贴图、上一层的共用贴图
        val phone = File(context.cacheDir, "phone/Download/模型包")
        val dir = File(phone, "角色").apply { mkdirs() }
        File(dir, "tex").mkdirs()
        File(dir, "tex/body.png").writeBytes(ByteArray(64) { 1 })
        File(dir, "face.tga").writeBytes(ByteArray(64) { 2 })                 // 模型里写的是大写扩展名
        File(phone, "共用").mkdirs()
        File(phone, "共用/toon.bmp").writeBytes(ByteArray(64) { 3 })          // 在模型文件夹的外面
        File(dir, "spa.sph").writeBytes(ByteArray(64) { 4 })                  // 模型里写的是作者电脑上的绝对路径
        File(dir, "无关的照片.png").writeBytes(ByteArray(64) { 9 })
        File(dir, "tex/hair.png").writeBytes(ByteArray(64) { 6 })             // 模型里写的是 hair.tga（贴图被转成了 png）
        val pmx = File(dir, "角色.pmx")
        writePmx(pmx, listOf("tex\\body.png", "face.TGA", "..\\共用\\toon.bmp", "C:\\Users\\author\\Desktop\\spa.sph", "没有的贴图.png", "tex\\hair.tga"))

        c.addFiles(listOf(Uri.fromFile(pmx)))
        settle()
        await("模型加载") { c.state.value.models.size == 1 }
        val m = c.state.value.models[0]
        assertEquals("只有那张确实不存在的贴图还缺着", 1, m.missingTextures)
        assertNull("能直接读原来的文件夹时不需要打扰用户", c.texturePrompt.value)
        val lib = onGl { c.scene.models[0].data.sourceFile!!.parentFile!! }
        assertTrue(File(lib, "tex/body.png").isFile)
        assertTrue("大小写不同的文件名按模型里写的存", File(lib, "face.TGA").isFile)
        assertTrue(File(lib, "共用/toon.bmp").isFile)
        assertTrue(File(lib, "spa.sph").isFile)
        assertTrue("没被模型引用的文件不应当被带进来", !File(lib, "无关的照片.png").exists())
        // 引擎能按模型里写的路径把它们都找到
        val data = onGl { c.scene.models[0].data }
        val resolved = data.textures.map { com.lumi.mmd.engine.render.TextureResolver.resolve(data.baseDir, it.path) != null }
        assertEquals(listOf(true, true, true, true, false, true), resolved)
        assertTrue("扩展名被转换过的贴图按它实际的名字存", File(lib, "tex/hair.png").isFile)
    }

    // Android 10：存储权限是普通的运行时权限，测试里可以直接授予（Android 11+ 要去系统设置里开，判断的逻辑是同一处）
    @Config(sdk = [29])
    @Test fun unreadableFolderAsksThenFinishesAfterPermission() {
        start()
        val dir = File(context.cacheDir, "phone2/模型").apply { mkdirs() }
        File(dir, "a.png").writeBytes(ByteArray(32) { 1 })
        File(dir, "b.png").writeBytes(ByteArray(32) { 2 })
        val pmx = File(dir, "锁着的.pmx")
        writePmx(pmx, listOf("a.png", "b.png"))
        // 没有存储权限时的样子：文件本身（用户选的那个）能读，文件夹不能列
        assertTrue(dir.setReadable(false))
        try {
            c.addFiles(listOf(Uri.fromFile(pmx)))
            settle()
            await("模型加载") { c.state.value.models.size == 1 }
            val id = c.state.value.models[0].id
            assertEquals(2, c.state.value.models[0].missingTextures)
            val p = c.texturePrompt.value
            assertNotNull("读不了原来的文件夹时应当请用户授权", p)
            assertEquals(listOf("a.png", "b.png"), p!!.missing)
            assertEquals("锁着的.pmx", p.name)

            // 还没给权限就回到应用：什么都不会发生
            c.retryTextures()
            settle()
            assertNotNull(c.texturePrompt.value)

            // 用户给了存储权限，回到应用
            assertTrue(!SourceAccess.canReadStorage(context))
            dir.setReadable(true)
            org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            assertTrue(SourceAccess.canReadStorage(context))
            c.retryTextures()
            await("贴图补齐") { c.texturePrompt.value == null && c.state.value.models.firstOrNull()?.missingTextures == 0 }
            settle()
            assertEquals("重新加载后还是同一个模型（位置、动作都保留）", id, c.state.value.models[0].id)
            val lib = onGl { c.scene.models[0].data.sourceFile!!.parentFile!! }
            assertTrue(File(lib, "a.png").isFile && File(lib, "b.png").isFile)
        } finally {
            dir.setReadable(true)
        }
    }

    // 没有存储权限、也推不出文件原来放在哪时的另一条路：用户用系统的文件夹选择器把那个文件夹授权给应用
    @Test fun pickedFolderBringsTheTextures() {
        start()
        val phone = File(context.cacheDir, "phone4").apply { deleteRecursively(); mkdirs() }
        val dir = File(phone, "模型包/角色").apply { mkdirs() }
        File(dir, "tex").mkdirs()
        File(dir, "tex/body.png").writeBytes(ByteArray(64) { 1 })
        File(dir, "face.tga").writeBytes(ByteArray(64) { 2 })                  // 模型里写的是大写扩展名
        File(phone, "模型包/共用").mkdirs()
        File(phone, "模型包/共用/toon.bmp").writeBytes(ByteArray(64) { 3 })    // 在模型文件夹的外面（上一层）
        File(dir, "spa.sph").writeBytes(ByteArray(64) { 4 })                   // 模型里写的是作者电脑上的绝对路径
        File(dir, "tex/hair.png").writeBytes(ByteArray(64) { 6 })              // 模型里写的是 hair.tga
        // 日文压缩包用中文系统的解压软件解出来，文件名会变成乱码：模型里写的还是「顔.png」
        val garbled = String("顔.png".toByteArray(java.nio.charset.Charset.forName("Shift_JIS")), java.nio.charset.Charset.forName("GBK"))
        assertTrue(garbled != "顔.png" && garbled.endsWith(".png"))
        File(dir, garbled).writeBytes(ByteArray(64) { 7 })
        File(dir, "无关的照片.png").writeBytes(ByteArray(64) { 9 })
        File(phone, "别的东西").mkdirs()
        File(phone, "别的东西/说明.txt").writeText("hello")
        val pmx = File(dir, "角色.pmx")
        // 「角色.png」不存在：不能因为主文件名相同就把 角色.pmx 当成贴图拿过来
        writePmx(pmx, listOf("tex\\body.png", "face.TGA", "..\\共用\\toon.bmp", "C:\\Users\\author\\Desktop\\spa.sph", "没有的贴图.png", "tex\\hair.tga", "顔.png", "角色.png"))
        val pmxSize = pmx.length()

        FakeDocs.root = phone
        FakeDocs.opened.clear()
        org.robolectric.Robolectric.buildContentProvider(FakeDocs::class.java).create(FakeDocs.AUTH)

        // 用户在系统的文件选择器里只点了 角色.pmx：应用拿到的是一个推不出真实路径的文档 Uri
        val picked = android.provider.DocumentsContract.buildDocumentUri(FakeDocs.AUTH, "root/模型包/角色/角色.pmx")
        assertEquals(emptyList<String>(), SourceAccess.guessPaths(context, picked))
        c.addFiles(listOf(picked))
        settle()
        await("模型加载") { c.state.value.models.size == 1 }
        val id = c.state.value.models[0].id
        assertEquals(8, c.state.value.models[0].missingTextures)
        val p = c.texturePrompt.value
        assertNotNull("读不到原来的文件夹时应当请用户授权", p)
        assertEquals("角色.pmx", p!!.name)
        assertEquals(8, p.missing.size)
        assertEquals("只读了用户选的那一个文件", listOf("角色.pmx"), FakeDocs.opened.toList())

        // 用户选了「选择模型所在的文件夹」，授权的是上两层的那个文件夹
        FakeDocs.opened.clear()
        c.texturesFromFolder(android.provider.DocumentsContract.buildTreeDocumentUri(FakeDocs.AUTH, "root"))
        await("贴图补齐") { c.texturePrompt.value == null && c.state.value.models.firstOrNull()?.missingTextures == 2 }
        settle()
        assertEquals("重新加载后还是同一个模型", id, c.state.value.models[0].id)
        val data = onGl { c.scene.models[0].data }
        val lib = data.sourceFile!!.parentFile!!
        assertTrue(File(lib, "tex/body.png").isFile)
        assertTrue(File(lib, "face.TGA").isFile)
        assertTrue(File(lib, "共用/toon.bmp").isFile)
        assertTrue(File(lib, "spa.sph").isFile)
        assertTrue(File(lib, "tex/hair.png").isFile)
        assertTrue("乱码名字的贴图按它实际的名字存", File(lib, garbled).isFile)
        assertTrue(!File(lib, "无关的照片.png").exists())
        assertEquals("模型文件本身没有被动过", pmxSize, data.sourceFile!!.length())
        val resolved = data.textures.map { com.lumi.mmd.engine.render.TextureResolver.resolve(data.baseDir, it.path) != null }
        assertEquals(listOf(true, true, true, true, false, true, true, false), resolved)
        // 只从那个文件夹里读了模型用到的文件
        assertEquals(setOf("body.png", "face.tga", "toon.bmp", "spa.sph", "hair.png", garbled), FakeDocs.opened.toSet())
        assertEquals(6, FakeDocs.opened.size)
    }

    @Test fun filesLandInTheirSlots() {
        start()
        val dir = File(context.cacheDir, "phone3").apply { mkdirs() }
        val pmx = File(dir, "角色.pmx"); writePmx(pmx, emptyList())
        val body = File(dir, "舞蹈.vmd").apply { writeBytes(bodyVmd()) }
        val face = File(dir, "口型.vmd").apply { writeBytes(faceVmd()) }
        val cam = File(dir, "镜头.vmd").apply { writeBytes(cameraVmd()) }

        // 一次全选：模型、身体动作、口型、镜头
        c.addFiles(listOf(pmx, body, face, cam).map { Uri.fromFile(it) })
        settle()
        await("都安排好") { val m = c.state.value.models.firstOrNull(); m != null && m.motionFile.isNotEmpty() && m.morphFile.isNotEmpty() && c.state.value.cameraFile.isNotEmpty() }
        var st = c.state.value
        assertEquals("舞蹈.vmd", st.models[0].motionFile)
        assertEquals("口型.vmd", st.models[0].morphFile)
        assertEquals("镜头.vmd", st.cameraFile)
        assertNull(c.texturePrompt.value)
        // 口型 VMD 把身体动作自带的表情轨道整个换掉，骨骼轨道留着
        val motion = onGl { c.scene.models[0].motion!!.motion }
        assertEquals(setOf("センター"), motion.boneTracks.keys)
        assertEquals(setOf("まばたき"), motion.morphTracks.keys)
        assertEquals(60, c.playback.value.maxFrame)

        // 表情槽里换一个文件：不是叠在上一个上面，而是换掉
        val face2 = File(dir, "口型2.vmd").apply { writeBytes(faceVmd()) }
        c.addFiles(listOf(Uri.fromFile(face2)), slot = Slot.MORPH)
        settle()
        await("换表情") { c.state.value.models[0].morphFile == "口型2.vmd" }
        assertEquals("舞蹈.vmd", c.state.value.models[0].motionFile)

        // 清空表情槽：回到只有身体动作（自带的表情轨道也回来）
        c.clearMorphSlot(c.state.value.models[0].id)
        await("清表情") { c.state.value.models[0].morphFile.isEmpty() }
        assertEquals(setOf("あ"), onGl { c.scene.models[0].motion!!.motion.morphTracks.keys.toSet() })

        // 把镜头文件放进「动作」槽：应用认得出来，放到相机那边，不动角色的动作
        c.clearCameraMotion()
        await("清镜头") { c.state.value.cameraFile.isEmpty() }
        c.addFiles(listOf(Uri.fromFile(cam)), slot = Slot.MOTION)
        settle()
        await("镜头归位") { c.state.value.cameraFile == "镜头.vmd" }
        st = c.state.value
        assertEquals("舞蹈.vmd", st.models[0].motionFile)
    }

    @Test fun packWithSeveralModelsAsksWhichOne() {
        start()
        val dir = File(context.cacheDir, "phone4").apply { mkdirs() }
        val a = File(dir, "a.pmx"); writePmx(a, emptyList(), "甲")
        val b = File(dir, "b.pmx"); writePmx(b, emptyList(), "乙")
        val zip = File(dir, "两个模型.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for (f in listOf(a, b)) { z.putNextEntry(ZipEntry("pack/" + f.name)); z.write(f.readBytes()); z.closeEntry() }
            z.putNextEntry(ZipEntry("pack/舞蹈.vmd")); z.write(bodyVmd()); z.closeEntry()
        }
        c.addFiles(listOf(Uri.fromFile(zip)))
        settle()
        await("询问") { c.modelChoice.value != null }
        val choice = c.modelChoice.value!!
        assertEquals(2, choice.items.size)
        assertEquals(0, c.state.value.models.size)
        c.chooseModel(choice.items.first { it.second.endsWith("b.pmx") }.first)
        settle()
        await("加载 + 套动作") { c.state.value.models.size == 1 && c.state.value.models[0].motionFile == "舞蹈.vmd" }
        assertEquals("乙", c.state.value.models[0].name)
        assertNull(c.modelChoice.value)
    }

    @Test fun guessesRealPathsFromPickerUris() {
        val root = android.os.Environment.getExternalStorageDirectory().path
        fun g(u: String) = SourceAccess.guessPaths(context, Uri.parse(u))
        // 系统文件选择器
        assertEquals(listOf("$root/Download/模型/a.pmx"), g("content://com.android.externalstorage.documents/document/primary%3ADownload%2F%E6%A8%A1%E5%9E%8B%2Fa.pmx"))
        assertEquals(listOf("/storage/1A2B-3C4D/mmd/a.pmx"), g("content://com.android.externalstorage.documents/document/1A2B-3C4D%3Ammd%2Fa.pmx"))
        assertEquals(listOf("/storage/emulated/0/Download/a.pmx"), g("content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fa.pmx"))
        // 文件管理器「打开方式」给的 FileProvider Uri
        assertTrue("$root/Download/x/a.pmx" in g("content://com.mi.android.globalFileexplorer.myprovider/external_files/Download/x/a.pmx"))
        assertTrue("/storage/emulated/0/Download/a.pmx" in g("content://com.example.fm.provider/root/storage/emulated/0/Download/a.pmx"))
        assertEquals(listOf("/sdcard/a.pmx"), g("file:///sdcard/a.pmx"))
        // 文件夹选择器一打开就停在模型所在的文件夹
        val hint = SourceAccess.folderHint(context, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fm%2Fa.pmx"), emptyList())
        assertEquals("primary:Download/m", android.provider.DocumentsContract.getDocumentId(hint))
        val hint2 = SourceAccess.folderHint(context, null, listOf("$root/Download/x/a.pmx"))
        assertEquals("primary:Download/x", android.provider.DocumentsContract.getDocumentId(hint2))
    }

    @Test fun objAloneBringsItsMaterialFileAndTextures() {
        start()
        // OBJ 要先拿到 .mtl 才知道用了哪些贴图：两步都要自动走完
        val dir = File(context.cacheDir, "phone5/舞台").apply { mkdirs() }
        File(dir, "tex").mkdirs()
        File(dir, "tex/wall.png").writeBytes(ByteArray(48) { 5 })
        File(dir, "stage.mtl").writeText("newmtl wall\nKd 1 1 1\nmap_Kd tex/wall.png\n")
        val obj = File(dir, "stage.obj")
        obj.writeText("mtllib stage.mtl\nv 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nusemtl wall\nf 1/1 2/2 3/3 4/4\n")
        assertEquals(listOf("stage.mtl"), com.lumi.mmd.engine.format.ModelRefs.missing(File(File(context.cacheDir, "lone").apply { mkdirs() }, "stage.obj").also { obj.copyTo(it, true) }))

        c.addFiles(listOf(Uri.fromFile(obj)))
        settle()
        await("模型加载") { c.state.value.models.size == 1 }
        assertEquals(0, c.state.value.models[0].missingTextures)
        assertNull(c.texturePrompt.value)
        val lib = onGl { c.scene.models[0].data.sourceFile!!.parentFile!! }
        assertTrue(File(lib, "stage.mtl").isFile)
        assertTrue(File(lib, "tex/wall.png").isFile)
        assertEquals(listOf("tex/wall.png"), onGl { c.scene.models[0].data.textures.map { it.path } })
    }

    @Test fun gltfListsItsExternalFiles() {
        // 分开存的 glTF：.bin 和图片都在 json 外面
        val dir = File(context.cacheDir, "gltf").apply { mkdirs() }
        val gltf = File(dir, "scene.gltf")
        gltf.writeText("""{"asset":{"version":"2.0"},"buffers":[{"uri":"scene.bin","byteLength":4},{"uri":"data:application/octet-stream;base64,AAAA"}],"images":[{"uri":"textures/base%20color.png"},{"bufferView":0}]}""")
        assertEquals(listOf("scene.bin", "textures/base color.png"), com.lumi.mmd.engine.format.ModelRefs.missing(gltf))
        File(dir, "scene.bin").writeBytes(ByteArray(4))
        assertEquals(listOf("textures/base color.png"), com.lumi.mmd.engine.format.ModelRefs.missing(gltf))
    }
}

/**
 * 假的"文件"应用：把一个真实文件夹当作文档树提供出来。
 * 用系统的文件 / 文件夹选择器选东西之后，应用拿到的就是这种 Uri，只能通过它查询和读取。
 */
class FakeDocs : android.content.ContentProvider() {
    companion object {
        const val AUTH = "com.lumi.test.docs"
        @Volatile var root: File? = null
        /** 被打开读取过的文件名，按顺序。 */
        val opened: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
    }

    private fun fileOf(id: String): File = if (id == "root") root!! else File(root!!, id.removePrefix("root/"))
    private fun idOf(f: File): String = if (f == root) "root" else "root/" + f.relativeTo(root!!).invariantSeparatorsPath
    private fun docId(uri: Uri): String { val seg = uri.pathSegments; return seg[seg.lastIndexOf("document") + 1] }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): android.database.Cursor {
        val cols = projection ?: arrayOf("document_id", "_display_name", "mime_type", "_size")
        val c = android.database.MatrixCursor(cols)
        val seg = uri.pathSegments
        val files = if (seg.last() == "children") (fileOf(seg[seg.size - 2]).listFiles() ?: emptyArray()).sortedBy { it.name } else listOf(fileOf(docId(uri)))
        for (f in files) c.addRow(cols.map { col ->
            when (col) {
                "document_id" -> idOf(f)
                "_display_name" -> f.name
                "mime_type" -> if (f.isDirectory) "vnd.android.document/directory" else "application/octet-stream"
                "_size" -> if (f.isDirectory) null else f.length()
                else -> null
            }
        })
        return c
    }

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
        val f = fileOf(docId(uri))
        opened.add(f.name)
        return android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}

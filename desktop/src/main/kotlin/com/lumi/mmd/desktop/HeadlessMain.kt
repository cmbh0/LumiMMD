package com.lumi.mmd.desktop

import android.opengl.GLES30
import com.lumi.mmd.engine.format.ModelLoader
import com.lumi.mmd.engine.image.ImageData
import com.lumi.mmd.engine.image.Images
import com.lumi.mmd.engine.image.PlatformImageDecoder
import com.lumi.mmd.engine.render.GlUtil
import com.lumi.mmd.engine.render.Renderer
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.scene.Scene
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.lwjgl.egl.EGL10.*
import org.lwjgl.egl.EGL12.EGL_OPENGL_ES_API
import org.lwjgl.egl.EGL12.eglBindAPI
import org.lwjgl.egl.EGL13.EGL_CONTEXT_CLIENT_VERSION
import org.lwjgl.egl.EGL15.EGL_OPENGL_ES3_BIT
import org.lwjgl.opengles.GLES
import org.lwjgl.system.MemoryStack

/**
 * 无头离屏渲染工具：在 PC 上用 Mesa 软件光栅器跑与手机端完全相同的引擎代码，把指定帧渲染成 PNG。
 * 用法（参数都是 key=value）：
 *   model=a.pmx[,b.pmx]  stage=s.pmx  motion=a.vmd[,b.vmd]  camera=cam.vmd
 *   frames=0,120,300  out=/tmp/shot  size=720x1280  msaa=4  physics=1
 *   view=距离,rx°,ry°,tx,ty,tz   warm=预热帧数
 */
fun main(args: Array<String>) {
    val opt = HashMap<String, String>()
    for (a in args) { val i = a.indexOf('='); if (i > 0) opt[a.substring(0, i)] = a.substring(i + 1) }
    val size = (opt["size"] ?: "720x1280").split('x')
    val width = size[0].toInt(); val height = size[1].toInt()
    opt["dumpshaders"]?.let { dumpShaders(File(it)); return }
    initEgl()
    Images.platformDecoder = PlatformImageDecoder { bytes, _ -> decodeWithImageIO(bytes) }

    val renderer = Renderer()
    renderer.onContextCreated()
    renderer.settings.msaa = (opt["msaa"] ?: "4").toInt()
    opt["grid"]?.let { renderer.settings.grid = it != "0" }
    opt["shadow"]?.let { renderer.settings.shadows = it != "0" }
    opt["edge"]?.let { renderer.settings.edges = it != "0" }
    opt["bloom"]?.let { renderer.settings.bloom = it != "0" }
    opt["sdef"]?.let { renderer.settings.sdef = it != "0" }
    val scene = Scene()
    scene.physicsEnabled = (opt["physics"] ?: "1") != "0"

    val motions = (opt["motion"] ?: "").split(',').filter { it.isNotBlank() }
    fun load(path: String, kind: Int): ModelInstance {
        val t0 = System.nanoTime()
        val data = ModelLoader.loadModel(File(path))
        val inst = ModelInstance(data, kind)
        val ms = (System.nanoTime() - t0) / 1e6
        println("加载 ${File(path).name}: 顶点 ${data.vertexCount} 面 ${data.triangleCount} 材质 ${data.materials.size} 骨骼 ${data.bones.size} " +
            "表情 ${data.morphs.size} 刚体 ${data.rigidBodies.size} 关节 ${data.joints.size} 贴图 ${data.textures.size}  (${"%.0f".format(ms)} ms)")
        for (w in data.warnings) println("  警告: $w")
        inst.scale = data.suggestedScale * (opt["scale"]?.toFloat() ?: 1f)
        if (data.animations.isNotEmpty()) {
            println("  自带动画: " + data.animations.joinToString { "${it.name}(${it.maxFrame}帧)" })
            inst.setMotion(data.animations[(opt["anim"] ?: "0").toInt().coerceIn(0, data.animations.size - 1)])
        }
        if (data.lights.isNotEmpty()) println("  灯光: " + data.lights.joinToString { "${it.name}[type=${it.type}]" })
        println("  包围盒: (%.1f, %.1f, %.1f) ~ (%.1f, %.1f, %.1f)  建议缩放 %.3f".format(
            data.boundsMin[0], data.boundsMin[1], data.boundsMin[2], data.boundsMax[0], data.boundsMax[1], data.boundsMax[2], data.suggestedScale))
        scene.add(inst)
        return inst
    }
    for (p in (opt["stage"] ?: "").split(',').filter { it.isNotBlank() }) load(p, ModelInstance.KIND_STAGE)
    val chars = (opt["model"] ?: "").split(',').filter { it.isNotBlank() }.map { load(it, ModelInstance.KIND_CHARACTER) }
    for ((i, m) in chars.withIndex()) {
        if (motions.isEmpty()) continue
        val merged = ModelLoader.loadMotion(File(motions[0]))
        for (k in 1 until motions.size) merged.mergeFrom(ModelLoader.loadMotion(File(motions[k])))
        m.setMotion(merged)
        val b = m.motion!!
        println("动作绑定[$i]: 骨骼轨道 ${b.matchedBones}/${b.totalBoneTracks} 表情轨道 ${b.matchedMorphs}/${b.totalMorphTracks} 时长 ${merged.maxFrame} 帧")
        opt["spacing"]?.let { m.setPosition((i - (chars.size - 1) * 0.5f) * it.toFloat(), 0f, 0f) }
    }
    if (opt["list"] == "1") for (m in chars) {
        for ((i, mat) in m.data.materials.withIndex()) println("  材质[$i] ${mat.name}  面 ${mat.indexCount / 3}")
    }
    // 部件编辑测试：part=材质序号[.连通块序号]:dx,dy,dz[:rx,ry,rz 度][:缩放]
    opt["part"]?.let { spec ->
        for (one in spec.split(';')) {
            val f = one.split(':')
            val m = chars[0]
            val key = f[0].split('.')
            val part = if (key.size > 1) m.parts.islandsOf(key[0].toInt())[key[1].toInt()] else m.parts.materialParts[key[0].toInt()]
            val d = f[1].split(',').map { it.toFloat() }
            part.offset[0] = d[0]; part.offset[1] = d[1]; part.offset[2] = d[2]
            if (f.size > 2) { val r = f[2].split(',').map { Math.toRadians(it.toDouble()).toFloat() }; com.lumi.mmd.engine.math.Quat.fromEulerYXZ(part.rotation, 0, r[0], r[1], r[2]) }
            if (f.size > 3) part.scale.fill(f[3].toFloat())
            m.parts.apply(part)
            println("部件 ${part.name}: 顶点 ${part.vertices.size} 面 ${part.triangleCount} 主骨骼 ${if (part.dominantBone >= 0) m.data.bones[part.dominantBone].name else "-"}")
            if (opt["highlight"] == "1") { renderer.highlightModel = m; renderer.highlightPart = part }
        }
    }
    opt["islands"]?.let { mi ->
        val m = chars[0]
        val isl = m.parts.islandsOf(mi.toInt())
        println("材质 $mi 的连通块: ${isl.size} 个；前几个面数: " + isl.take(8).joinToString { it.triangleCount.toString() })
    }
    opt["camera"]?.let { scene.cameraMotion = ModelLoader.loadMotion(File(it)); println("镜头: ${scene.cameraMotion!!.camera?.count} 帧") }
    scene.recomputeDuration()
    if (opt["fit"] == "1") {
        val mn = FloatArray(3); val mx = FloatArray(3)
        for (m in scene.models) m.updateWorldMatrix()
        scene.stepTo(0f, 0f)
        if (scene.characterBounds(mn, mx)) {
            scene.camera.update(width.toFloat() / height)
            scene.camera.frame(mn, mx)
            scene.camera.rotX = Math.toRadians(-12.0).toFloat(); scene.camera.rotY = Math.toRadians(25.0).toFloat()
            scene.userCamera.copyFrom(scene.camera)
        }
    }
    opt["view"]?.let {
        val v = it.split(',').map { s -> s.toFloat() }
        val c = scene.camera
        c.distance = v[0]
        if (v.size > 2) { c.rotX = Math.toRadians(v[1].toDouble()).toFloat(); c.rotY = Math.toRadians(v[2].toDouble()).toFloat() }
        if (v.size > 5) { c.target[0] = v[3]; c.target[1] = v[4]; c.target[2] = v[5] }
        if (v.size > 6) c.fovDeg = v[6]
        scene.userCamera.copyFrom(c)
    }

    // 输出用的帧缓冲
    val tex = GlUtil.genTexture()
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
    val fbo = GlUtil.genFramebuffer()
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
    GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)

    val frames = (opt["frames"] ?: "0").split(',').map { it.toFloat() }
    val out = opt["out"] ?: "/tmp/lumi"
    val warm = (opt["warm"] ?: "60").toInt()
    val pixels = GlUtil.byteBuffer(width * height * 4)
    var current = -1f
    var simNanos = 0L
    var simSteps = 0
    for (f in frames) {
        // 物理是有状态的：从目标帧之前 warm 帧开始逐帧模拟过去
        val t0 = System.nanoTime()
        if (current < 0f || f < current) {
            val start = maxOf(0f, f - warm)
            scene.seek(start)
            scene.stepTo(start, 0f)
            current = start
        }
        while (current < f) {
            current = minOf(f, current + 1f)
            scene.stepTo(current, 1f / 30f)
            simSteps++
        }
        simNanos += System.nanoTime() - t0
        // 先画一帧触发贴图加载，等解码完再画正式的一帧
        renderer.render(scene, fbo, width, height)
        var guard = 0
        while (renderer.textures.pendingCount > 0 && guard++ < 3000) { Thread.sleep(5); renderer.textures.pump(50_000_000L) }
        val r0 = System.nanoTime()
        renderer.render(scene, fbo, width, height)
        GLES30.glFinish()
        val renderMs = (System.nanoTime() - r0) / 1e6
        renderer.readPixels(fbo, width, height, pixels)
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            val o = ((height - 1 - y) * width + x) * 4
            val r = pixels.get(o).toInt() and 0xFF; val g = pixels.get(o + 1).toInt() and 0xFF; val b = pixels.get(o + 2).toInt() and 0xFF
            img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
        }
        val file = File("${out}_${f.toInt()}.png")
        ImageIO.write(img, "png", file)
        println("帧 ${f.toInt()} → ${file.path}  draw=${renderer.lastDrawCalls} tri=${renderer.lastTriangles} 渲染 ${"%.1f".format(renderMs)} ms")
    }
    if (simSteps > 0) println("模拟：$simSteps 帧，平均 ${"%.3f".format(simNanos / 1e6 / simSteps)} ms/帧（动画 + IK + 物理）")
    GlUtil.checkError("end")
    renderer.textures.shutdown()
}

internal fun decodeWithImageIO(bytes: ByteArray): ImageData? {
    val src = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
    val w = src.width; val h = src.height
    val argb = IntArray(w * h)
    src.getRGB(0, 0, w, h, argb, 0, w)
    val img = ImageData.allocate(w, h)
    val p = img.pixels
    for (i in 0 until w * h) {
        val c = argb[i]
        p.put((c shr 16).toByte()).put((c shr 8).toByte()).put(c.toByte()).put((c ushr 24).toByte())
    }
    p.position(0)
    return img
}

internal fun initEgl() {
    MemoryStack.stackPush().use { stack ->
        val display = eglGetDisplay(0L)
        val major = stack.mallocInt(1); val minor = stack.mallocInt(1)
        check(eglInitialize(display, major, minor)) { "eglInitialize failed" }
        eglBindAPI(EGL_OPENGL_ES_API)
        val attrs = stack.ints(
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, 0x3040, EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_NONE)
        val configs = stack.mallocPointer(1); val num = stack.mallocInt(1)
        check(eglChooseConfig(display, attrs, configs, num) && num[0] > 0) { "no EGL config" }
        val ctx = eglCreateContext(display, configs[0], EGL_NO_CONTEXT, stack.ints(EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE))
        check(ctx != EGL_NO_CONTEXT) { "no EGL context" }
        val surf = eglCreatePbufferSurface(display, configs[0], stack.ints(EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE))
        check(eglMakeCurrent(display, surf, surf, ctx)) { "eglMakeCurrent failed" }
        GLES.createCapabilities()
    }
}


/** 把所有着色器变体写成文件，供 glslangValidator 做严格的 GLSL ES 3.00 语法检查。 */
private fun dumpShaders(dir: File) {
    dir.mkdirs()
    val S = com.lumi.mmd.engine.render.Shaders
    fun w(name: String, src: String) = File(dir, name).writeText(src)
    for (flags in 0 until 8) {
        val d = S.defines(flags)
        w("model_$flags.vert", d + S.MODEL_VS); w("model_$flags.frag", d + S.MODEL_FS)
        w("edge_$flags.vert", d + S.EDGE_VS); w("edge_$flags.frag", d + S.EDGE_FS)
        w("depth_$flags.vert", d + S.DEPTH_VS); w("depth_$flags.frag", d + S.DEPTH_FS)
    }
    w("ground.vert", S.GROUND_VS); w("ground.frag", S.GROUND_FS)
    w("line.vert", S.LINE_VS); w("line.frag", S.LINE_FS)
    w("post.vert", S.POST_VS); w("post.frag", S.POST_FS)
    w("bloom.frag", S.BLOOM_FS); w("background.frag", S.BACKGROUND_FS)
    println("已写出 ${dir.list()?.size} 个着色器到 $dir")
}

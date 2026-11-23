package com.lumi.mmd.engine.render

import android.opengl.GLES30
import com.lumi.mmd.engine.math.Mat4
import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.fx.Effects
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.scene.Part
import com.lumi.mmd.engine.scene.Scene
import com.lumi.mmd.engine.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/** 画质 / 外观选项。可以在任意线程改字段，渲染线程下一帧生效。 */
class RenderSettings {
    /** MSAA 采样数：0/1 关闭，2，4。 */
    @Volatile var msaa = 4
    /** 渲染分辨率倍率（0.5 ~ 1.0，低端机可降低）。 */
    @Volatile var renderScale = 1f
    @Volatile var shadows = true
    @Volatile var shadowMapSize = 2048
    @Volatile var shadowStrength = 1f
    @Volatile var edges = true
    @Volatile var edgeWidth = 1f
    @Volatile var grid = true
    @Volatile var groundShadow = true
    @Volatile var sdef = true
    @Volatile var bloom = false
    @Volatile var bloomStrength = 0.6f
    @Volatile var bloomThreshold = 0.82f
    @Volatile var exposure = 1f
    @Volatile var vignette = 0f
    @Volatile var rim = 0f
    @Volatile var saturation = 1f
    val backgroundTop = floatArrayOf(0.886f, 0.858f, 0.992f)
    val backgroundBottom = floatArrayOf(0.976f, 0.968f, 1f)
    /** 通用材质（glTF / OBJ / FBX / X）的半球环境光。 */
    val skyColor = floatArrayOf(0.62f, 0.64f, 0.70f)
    val groundColor = floatArrayOf(0.36f, 0.34f, 0.33f)
    /** 透明背景输出（截图 / 导出带 alpha）。 */
    @Volatile var transparentBackground = false

    // ---- 画风（三渲二等）：脚本和 MCP 可以改；跟着工程一起保存 ----
    /** 三渲二：光照分成几级（1 = 亮 / 暗两种颜色，2 = 多一级中间色 ...）；0 = 关，用模型自己的 toon 和普通光照。 */
    @Volatile var celBands = 0
    /** 明暗交界线的柔和度：0 = 一刀切，1 = 完全平滑。 */
    @Volatile var celSoftness = 0.04f
    /** 交界线往哪边挪：正 = 亮的地方更多，负 = 暗的地方更多（-1..1）。 */
    @Volatile var celShift = 0f
    /** 三渲二里硬边高光的强度（0 = 不要高光）。 */
    @Volatile var celSpecular = 0f
    /** 三渲二：暗部乘的颜色。 */
    val shadeColor = floatArrayOf(0.70f, 0.64f, 0.80f)
    /** 高光倍数（普通光照）。 */
    @Volatile var specular = 1f
    /** 边缘光的颜色；第 4 个数 = 用多少（0 = 跟着灯光的颜色）。 */
    val rimColor = floatArrayOf(1f, 1f, 1f, 0f)
    /** 所有描边往这个颜色混；第 4 个数 = 混多少（0 = 各材质自己的描边色）。 */
    val edgeColor = floatArrayOf(0f, 0f, 0f, 0f)
    /** 没有描边设置的模型（glTF / OBJ / FBX、关了描边的材质）也画描边。 */
    @Volatile var edgeAll = false
    @Volatile var contrast = 1f
    /** 色调分离：每个通道只留这么多级颜色；0 = 关。 */
    @Volatile var posterize = 0
    /** 整个画面乘的颜色（调色）。 */
    val grade = floatArrayOf(1f, 1f, 1f)
    /** 背景色是脚本 / AI 指定的（不是从预设里挑的）：跟着工程保存，换别的设置时不要把它盖掉。 */
    @Volatile var customBackground = false

    /** 把「画面看起来什么样」的设置抄给 dst（不含分辨率、抗锯齿这些性能设置）。 */
    fun copyLookTo(d: RenderSettings) {
        d.shadows = shadows; d.shadowStrength = shadowStrength; d.edges = edges; d.edgeWidth = edgeWidth
        d.grid = grid; d.groundShadow = groundShadow
        d.bloom = bloom; d.bloomStrength = bloomStrength; d.bloomThreshold = bloomThreshold
        d.exposure = exposure; d.vignette = vignette; d.rim = rim; d.saturation = saturation
        System.arraycopy(backgroundTop, 0, d.backgroundTop, 0, 3); System.arraycopy(backgroundBottom, 0, d.backgroundBottom, 0, 3)
        System.arraycopy(skyColor, 0, d.skyColor, 0, 3); System.arraycopy(groundColor, 0, d.groundColor, 0, 3)
        copyStyleTo(d)
    }

    /** 只抄画风那一组（跟着工程保存的那些）。 */
    fun copyStyleTo(d: RenderSettings) {
        d.celBands = celBands; d.celSoftness = celSoftness; d.celShift = celShift; d.celSpecular = celSpecular
        System.arraycopy(shadeColor, 0, d.shadeColor, 0, 3); d.specular = specular
        System.arraycopy(rimColor, 0, d.rimColor, 0, 4); System.arraycopy(edgeColor, 0, d.edgeColor, 0, 4)
        d.edgeAll = edgeAll; d.contrast = contrast; d.posterize = posterize
        System.arraycopy(grade, 0, d.grade, 0, 3)
        d.customBackground = customBackground
        if (customBackground) { System.arraycopy(backgroundTop, 0, d.backgroundTop, 0, 3); System.arraycopy(backgroundBottom, 0, d.backgroundBottom, 0, 3) }
    }

    /** 一键三渲二：光照分两级、暗部偏冷、所有模型都描边。 */
    fun applyToon(bands: Int = 2) {
        celBands = bands.coerceIn(1, 8); celSoftness = 0.03f; celShift = 0f; celSpecular = 0f
        shadeColor[0] = 0.70f; shadeColor[1] = 0.64f; shadeColor[2] = 0.80f
        edges = true; edgeAll = true; edgeWidth = 1.3f
        saturation = 1.08f
    }

    fun resetStyle() { val bg = customBackground; RenderSettings().copyStyleTo(this); if (bg) { RenderSettings().let { System.arraycopy(it.backgroundTop, 0, backgroundTop, 0, 3); System.arraycopy(it.backgroundBottom, 0, backgroundBottom, 0, 3) } } }

    val styleIsDefault: Boolean get() = styleToMap().isEmpty()

    /** 画风里和默认值不一样的项（存进工程；全是默认时是空的）。 */
    fun styleToMap(): LinkedHashMap<String, Any?> {
        val o = LinkedHashMap<String, Any?>()
        val d = DEFAULTS
        if (celBands != d.celBands) o["cel"] = celBands
        if (celSoftness != d.celSoftness) o["celSoft"] = celSoftness
        if (celShift != d.celShift) o["celShift"] = celShift
        if (celSpecular != d.celSpecular) o["celSpecular"] = celSpecular
        if (!shadeColor.contentEquals(d.shadeColor)) o["shade"] = shadeColor.toList()
        if (specular != d.specular) o["specular"] = specular
        if (!rimColor.contentEquals(d.rimColor)) o["rimColor"] = rimColor.toList()
        if (!edgeColor.contentEquals(d.edgeColor)) o["edgeColor"] = edgeColor.toList()
        if (edgeAll != d.edgeAll) o["edgeAll"] = edgeAll
        if (contrast != d.contrast) o["contrast"] = contrast
        if (posterize != d.posterize) o["posterize"] = posterize
        if (!grade.contentEquals(d.grade)) o["grade"] = grade.toList()
        if (customBackground) o["background"] = backgroundTop.toList() + backgroundBottom.toList()
        return o
    }

    /** 从工程里读回画风；没写的项回到默认。 */
    fun styleFromMap(o: Map<String, Any?>?) {
        resetStyle()
        if (o == null) return
        fun f(k: String, d: Float) = (o[k] as? Number)?.toFloat()?.takeIf { !it.isNaN() } ?: d
        fun arr(k: String, dst: FloatArray) { val l = o[k] as? List<*> ?: return; for (i in dst.indices) (l.getOrNull(i) as? Number)?.let { dst[i] = it.toFloat().coerceIn(0f, 4f) } }
        celBands = f("cel", 0f).toInt().coerceIn(0, 8)
        celSoftness = f("celSoft", celSoftness).coerceIn(0f, 1f)
        celShift = f("celShift", 0f).coerceIn(-1f, 1f)
        celSpecular = f("celSpecular", 0f).coerceIn(0f, 4f)
        arr("shade", shadeColor)
        specular = f("specular", 1f).coerceIn(0f, 8f)
        arr("rimColor", rimColor); arr("edgeColor", edgeColor)
        edgeAll = o["edgeAll"] as? Boolean ?: false
        contrast = f("contrast", 1f).coerceIn(0f, 4f)
        posterize = f("posterize", 0f).toInt().coerceIn(0, 64)
        arr("grade", grade)
        (o["background"] as? List<*>)?.takeIf { it.size >= 6 }?.let { l ->
            customBackground = true
            for (i in 0 until 3) { backgroundTop[i] = (l[i] as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: 0f; backgroundBottom[i] = (l[i + 3] as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: 0f }
        }
    }

    companion object { private val DEFAULTS = RenderSettings() }
}

/**
 * 场景渲染器（OpenGL ES 3.0）。一帧的流程：
 *  1. 同步各模型的 GPU 数据（骨骼纹理、表情后的顶点）
 *  2. 阴影深度图（平行光，正交投影，范围贴合角色）
 *  3. 主通道：背景 → 舞台 → 角色 / 配件（材质按作者顺序、带深度写入的 alpha 混合，随后背面外扩描边）
 *     → 地面网格 / 接收阴影 → 通用材质的半透明部分 → 选中高亮与辅助线
 *  4. MSAA resolve → 泛光（可选）→ 输出
 */
class Renderer {
    val settings = RenderSettings()
    val textures = TextureManager()
    /** 辅助线（骨骼、操纵轴等），每帧由编辑器填充，画完自动清空。 */
    val overlay = LineBatch()
    /** 特效（粒子、拖尾、闪光……）。为 null 时不画。 */
    var effects: Effects? = null
    val effectRenderer = EffectRenderer()
    private val fxTmp = FloatArray(4)
    private var layerScratch = IntArray(8)

    /** 选中高亮的部件（null = 无）。 */
    var highlightModel: ModelInstance? = null
    var highlightPart: Part? = null
    private var highlightUploaded: Part? = null
    private var highlightIbo = 0
    private var highlightCount = 0

    private val gpu = HashMap<ModelInstance, ModelGpu>()
    private val scratch = Scratch()
    private val modelPrograms = arrayOfNulls<ModelProgram>(8)
    private val edgePrograms = arrayOfNulls<SimpleProgram>(4)
    private val depthPrograms = arrayOfNulls<SimpleProgram>(4)
    private var groundProgram = 0
    private var lineProgram = 0
    private var postProgram = 0
    private var bloomProgram = 0
    private var backgroundProgram = 0
    private var groundVao = 0
    private var groundVbo = 0
    private var emptyVao = 0

    // 渲染目标
    private var targetW = 0
    private var targetH = 0
    private var targetSamples = -1
    private var msaaFbo = 0
    private var msaaColor = 0
    private var msaaDepth = 0
    private var sceneFbo = 0
    private var sceneTex = 0
    private var sceneDepth = 0
    private val bloomFbo = IntArray(2)
    private val bloomTex = IntArray(2)
    private var bloomW = 0
    private var bloomH = 0
    private var shadowFbo = 0
    private var shadowTex = 0
    private var shadowSize = 0
    private var maxSamples = 4

    private val lightView = FloatArray(16)
    private val lightProj = FloatArray(16)
    private val lightViewProj = FloatArray(16)
    private val shadowMat = FloatArray(16)
    private val identity = FloatArray(16).also { Mat4.identity(it, 0) }
    private val toLight = FloatArray(3)
    private val bMin = FloatArray(3)
    private val bMax = FloatArray(3)
    private val tmpM = FloatArray(16)
    private var shadowActive = false
    private var contextReady = false
    private var startNanos = System.nanoTime()

    /** 统计信息。 */
    var lastDrawCalls = 0
        private set
    var lastTriangles = 0
        private set

    // ------------------------------------------------------------------ 生命周期

    /** GL 上下文创建（或丢失后重建）时调用：所有旧的 GL 对象都已失效。 */
    fun onContextCreated() {
        effectRenderer.onContextCreated()
        gpu.clear()
        garbage.clear()   // 旧上下文里的句柄已经失效，不能拿到新上下文里删除
        modelPrograms.fill(null); edgePrograms.fill(null); depthPrograms.fill(null)
        groundProgram = 0; lineProgram = 0; postProgram = 0; bloomProgram = 0; backgroundProgram = 0
        groundVao = 0; groundVbo = 0; emptyVao = 0
        targetW = 0; targetH = 0; targetSamples = -1
        msaaFbo = 0; msaaColor = 0; msaaDepth = 0; sceneFbo = 0; sceneTex = 0; sceneDepth = 0
        bloomFbo.fill(0); bloomTex.fill(0); bloomW = 0; bloomH = 0
        shadowFbo = 0; shadowTex = 0; shadowSize = 0
        highlightIbo = 0; highlightUploaded = null
        overlay.onContextCreated()
        textures.onContextCreated()
        maxSamples = GlUtil.getInteger(GLES30.GL_MAX_SAMPLES).coerceAtLeast(1)
        Log.i(TAG, "GL: ${GLES30.glGetString(GLES30.GL_RENDERER)} / ${GLES30.glGetString(GLES30.GL_VERSION)} / MSAA≤$maxSamples")

        groundProgram = GlUtil.linkProgram(Shaders.GROUND_VS, Shaders.GROUND_FS, Shaders.ATTRIBS)
        lineProgram = GlUtil.linkProgram(Shaders.LINE_VS, Shaders.LINE_FS, Shaders.ATTRIBS)
        postProgram = GlUtil.linkProgram(Shaders.POST_VS, Shaders.POST_FS, emptyArray())
        bloomProgram = GlUtil.linkProgram(Shaders.POST_VS, Shaders.BLOOM_FS, emptyArray())
        backgroundProgram = GlUtil.linkProgram(Shaders.POST_VS, Shaders.BACKGROUND_FS, emptyArray())
        emptyVao = GlUtil.genVertexArray()

        groundVao = GlUtil.genVertexArray()
        GLES30.glBindVertexArray(groundVao)
        groundVbo = GlUtil.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, groundVbo)
        val quad = floatArrayOf(-1f, 0f, -1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f, 1f)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, scratch.floats(quad, 0, quad.size), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        contextReady = true
    }

    /** 模型被移出场景时调用，释放它的 GPU 资源（必须在 GL 线程）。 */
    fun releaseModel(model: ModelInstance) {
        gpu.remove(model)?.let { garbage.add(it) }
        if (highlightModel === model) { highlightModel = null; highlightPart = null }
    }

    /** 让某个模型的 GPU 资源下一帧重建（例如切换了 SDEF 开关）。 */
    fun invalidateModel(model: ModelInstance) {
        gpu.remove(model)?.let { garbage.add(it) }
    }

    fun invalidateAllModels() {
        garbage.addAll(gpu.values)
        gpu.clear()
    }

    /**
     * 待释放的 GPU 资源。真正的 glDelete* 推迟到下一次 render() 里做：
     * Android 上 GL 线程的任务可能在没有当前 EGL 上下文时执行（界面在后台、表面还没建好），那时调用 GL 是无效的。
     */
    private val garbage = ArrayList<ModelGpu>()

    private fun collectGarbage() {
        if (garbage.isEmpty()) return
        for (g in garbage) g.destroy()
        garbage.clear()
    }

    private fun gpuFor(model: ModelInstance): ModelGpu {
        var g = gpu[model]
        if (g == null) {
            g = ModelGpu(model, textures, settings.sdef)
            g.create(scratch)
            gpu[model] = g
            scratch.trim()
        }
        return g
    }

    private fun modelProgram(flags: Int): ModelProgram {
        var p = modelPrograms[flags]
        if (p == null) {
            val d = Shaders.defines(flags)
            p = ModelProgram(GlUtil.linkProgram(d + Shaders.MODEL_VS, d + Shaders.MODEL_FS, Shaders.ATTRIBS))
            modelPrograms[flags] = p
        }
        return p
    }

    private fun simpleProgram(cache: Array<SimpleProgram?>, flags: Int, vs: String, fs: String): SimpleProgram {
        val key = flags and 3
        var p = cache[key]
        if (p == null) {
            val d = Shaders.defines(key)
            p = SimpleProgram(GlUtil.linkProgram(d + vs, d + fs, Shaders.ATTRIBS))
            cache[key] = p
        }
        return p
    }

    // ------------------------------------------------------------------ 渲染目标

    private fun ensureTargets(w: Int, h: Int, samples: Int) {
        if (w == targetW && h == targetH && samples == targetSamples) return
        GlUtil.deleteFramebuffer(msaaFbo); GlUtil.deleteRenderbuffer(msaaColor); GlUtil.deleteRenderbuffer(msaaDepth)
        GlUtil.deleteFramebuffer(sceneFbo); GlUtil.deleteTexture(sceneTex); GlUtil.deleteRenderbuffer(sceneDepth)
        msaaFbo = 0; msaaColor = 0; msaaDepth = 0; sceneDepth = 0
        targetW = w; targetH = h; targetSamples = samples

        sceneTex = GlUtil.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        sceneFbo = GlUtil.genFramebuffer()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, sceneTex, 0)
        if (samples > 1) {
            msaaFbo = GlUtil.genFramebuffer()
            msaaColor = GlUtil.genRenderbuffer()
            msaaDepth = GlUtil.genRenderbuffer()
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msaaColor)
            GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, GLES30.GL_RGBA8, w, h)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msaaDepth)
            GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, GLES30.GL_DEPTH24_STENCIL8, w, h)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msaaFbo)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, msaaColor)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_STENCIL_ATTACHMENT, GLES30.GL_RENDERBUFFER, msaaDepth)
            val st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (st != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(TAG, "MSAA 帧缓冲不可用 (0x${Integer.toHexString(st)})，退回无抗锯齿")
                GlUtil.deleteFramebuffer(msaaFbo); GlUtil.deleteRenderbuffer(msaaColor); GlUtil.deleteRenderbuffer(msaaDepth)
                msaaFbo = 0; msaaColor = 0; msaaDepth = 0
            }
        }
        if (msaaFbo == 0) {
            sceneDepth = GlUtil.genRenderbuffer()
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, sceneDepth)
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH24_STENCIL8, w, h)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_STENCIL_ATTACHMENT, GLES30.GL_RENDERBUFFER, sceneDepth)
        }
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)
    }

    private fun ensureBloom(w: Int, h: Int) {
        val bw = maxOf(w / 4, 8)
        val bh = maxOf(h / 4, 8)
        if (bw == bloomW && bh == bloomH && bloomFbo[0] != 0) return
        for (i in 0 until 2) { GlUtil.deleteFramebuffer(bloomFbo[i]); GlUtil.deleteTexture(bloomTex[i]) }
        bloomW = bw; bloomH = bh
        for (i in 0 until 2) {
            bloomTex[i] = GlUtil.genTexture()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTex[i])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, bw, bh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            bloomFbo[i] = GlUtil.genFramebuffer()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, bloomTex[i], 0)
        }
    }

    private fun ensureShadowMap(size: Int) {
        if (size == shadowSize && shadowFbo != 0) return
        GlUtil.deleteFramebuffer(shadowFbo); GlUtil.deleteTexture(shadowTex)
        shadowSize = size
        shadowTex = GlUtil.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, size, size, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        shadowFbo = GlUtil.genFramebuffer()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, shadowTex, 0)
        val none = intArrayOf(GLES30.GL_NONE)
        GLES30.glDrawBuffers(1, none, 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
    }

    // ------------------------------------------------------------------ 一帧

    /**
     * 渲染一帧到 outFbo（0 = 屏幕）。调用前应已执行 scene.update()。
     */
    fun render(scene: Scene, outFbo: Int, outW: Int, outH: Int) {
        if (!contextReady || outW <= 0 || outH <= 0) return
        val s = settings
        collectGarbage()
        textures.pump()
        effects?.let { effectRenderer.canvases.sync(it) }
        lastDrawCalls = 0
        lastTriangles = 0

        val scale = s.renderScale.coerceIn(0.25f, 2f)
        val w = maxOf((outW * scale + 0.5f).toInt(), 16)
        val h = maxOf((outH * scale + 0.5f).toInt(), 16)
        val samples = if (s.msaa >= 2) minOf(s.msaa, maxSamples) else 1
        ensureTargets(w, h, samples)

        val cam = scene.camera
        val hasBounds = scene.characterBounds(bMin, bMax)
        val radius = if (hasBounds) {
            val dx = bMax[0] - bMin[0]; val dy = bMax[1] - bMin[1]; val dz = bMax[2] - bMin[2]
            sqrt(dx * dx + dy * dy + dz * dz) * 0.5f
        } else 30f
        cam.update(w.toFloat() / h, maxOf(radius, 100f))
        // 镜头抖动：只在画这一帧时把视点挪一下，画完放回去
        val fx = effects
        var shaken = false
        val tx0 = cam.target[0]; val ty0 = cam.target[1]; val tz0 = cam.target[2]
        if (fx != null && fx.shakeOffset(fxTmp)) {
            val dx = fxTmp[0]; val dy = fxTmp[1]
            cam.target[0] += cam.right[0] * dx + cam.up[0] * dy
            cam.target[1] += cam.right[1] * dx + cam.up[1] * dy
            cam.target[2] += cam.right[2] * dx + cam.up[2] * dy
            cam.update(w.toFloat() / h, maxOf(radius, 100f))
            shaken = true
        }
        scene.light.toLight(toLight)

        // 同步 GPU 数据
        for (m in scene.models) {
            if (!m.visible || m.data.vertexCount == 0) continue
            gpuFor(m).sync(scratch)
        }

        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
        GLES30.glFrontFace(GLES30.GL_CW)
        GLES30.glColorMask(true, true, true, true)

        shadowActive = s.shadows && hasBounds && scene.models.any { it.visible && it.castShadow && it.kind != ModelInstance.KIND_STAGE }
        if (shadowActive) renderShadowMap(scene, radius)

        // ---- 主通道 ----
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, if (msaaFbo != 0) msaaFbo else sceneFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDepthMask(true)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClearDepthf(1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT or GLES30.GL_STENCIL_BUFFER_BIT)
        if (!s.transparentBackground) drawBackground()

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // 一层一层画：层号小的先画，换层时清掉深度，所以上面一层总是盖在下面一层上（默认都在第 0 层，就是普通的三维遮挡）。
        // 地面网格属于第 0 层。
        var layerCount = 0
        for (m in scene.models) {
            if (!m.visible || m.data.vertexCount == 0) continue
            var known = false
            for (i in 0 until layerCount) if (layerScratch[i] == m.layer) { known = true; break }
            if (known) continue
            if (layerCount == layerScratch.size) layerScratch = layerScratch.copyOf(layerCount * 2)
            layerScratch[layerCount++] = m.layer
        }
        java.util.Arrays.sort(layerScratch, 0, layerCount)
        val fl = scene.floor
        val wantGround = fl.enabled && ((s.grid && fl.grid) || (s.groundShadow && fl.shadow && shadowActive) || fl.color[3] > 0.003f)
        var groundDone = !wantGround
        for (li in 0 until layerCount) {
            val layer = layerScratch[li]
            if (layer > 0 && !groundDone) { drawGround(scene); groundDone = true }
            if (li > 0) {
                GLES30.glDepthMask(true)
                GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
            }
            // 舞台先画，然后是角色 / 配件
            for (pass in 0 until 2) {
                for (m in scene.models) {
                    if (!m.visible || m.data.vertexCount == 0 || m.layer != layer) continue
                    if ((m.kind == ModelInstance.KIND_STAGE) != (pass == 0)) continue
                    val g = gpuFor(m)
                    val faded = m.opacity < 0.999f
                    if (faded) {
                        // 整个模型半透明：先只写深度，再上色。这样只看得到最外面一层表面，不会透出身体里面的面
                        GLES30.glColorMask(false, false, false, false)
                        drawModel(scene, m, g, false, depthOnly = true)
                        GLES30.glColorMask(true, true, true, true)
                        GLES30.glDepthMask(false)
                    }
                    drawModel(scene, m, g, false)
                    if (s.edges && m.drawEdge && (g.skinned || s.edgeAll)) drawEdges(scene, m, g, w, h)
                    if (faded) GLES30.glDepthMask(true)
                }
            }
            // 通用材质的半透明部分放在这一层的最后
            for (m in scene.models) {
                if (!m.visible || m.data.vertexCount == 0 || m.layer != layer) continue
                if (m.data.materials.any { it.shading != MaterialData.SHADING_MMD && it.alphaMode == MaterialData.ALPHA_BLEND }) {
                    GLES30.glDepthMask(false)
                    drawModel(scene, m, gpuFor(m), true)
                    GLES30.glDepthMask(true)
                }
            }
        }
        if (!groundDone) drawGround(scene)
        if (fx != null) {
            effectRenderer.scripted.draw(fx, cam, scene.light, { name -> effectRenderer.texture(name, textures) }, textures.white)
            lastDrawCalls += effectRenderer.scripted.lastDrawCalls
            effectRenderer.draw(fx, cam, textures)
            lastDrawCalls += effectRenderer.lastDrawCalls
        }
        drawHighlight(scene)
        overlay.draw(lineProgram, cam.viewProj, w, h)
        if (fx != null && fx.overlayColor(fxTmp)) effectRenderer.drawOverlay(fxTmp[0], fxTmp[1], fxTmp[2], fxTmp[3])
        if (shaken) { cam.target[0] = tx0; cam.target[1] = ty0; cam.target[2] = tz0; cam.update(w.toFloat() / h, maxOf(radius, 100f)) }

        // ---- resolve + 后处理 ----
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        if (msaaFbo != 0) {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msaaFbo)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, sceneFbo)
            GLES30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        }
        GLES30.glBindVertexArray(emptyVao)
        val bloomOn = s.bloom && s.bloomStrength > 0f
        if (bloomOn) renderBloom(w, h)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glUseProgram(postProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(postProgram, "u_scene"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (bloomOn) bloomTex[0] else textures.white)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(postProgram, "u_bloom"), 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(postProgram, "u_bloomStrength"), if (bloomOn) s.bloomStrength else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(postProgram, "u_exposure"), s.exposure)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(postProgram, "u_vignette"), s.vignette)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(postProgram, "u_flipY"), 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(postProgram, "u_contrast"), s.contrast)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(postProgram, "u_posterize"), s.posterize.toFloat())
        GLES30.glUniform3f(GLES30.glGetUniformLocation(postProgram, "u_grade"), s.grade[0], s.grade[1], s.grade[2])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUseProgram(0)
    }

    private fun drawBackground() {
        val s = settings
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(backgroundProgram)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(backgroundProgram, "u_top"), s.backgroundTop[0], s.backgroundTop[1], s.backgroundTop[2])
        GLES30.glUniform3f(GLES30.glGetUniformLocation(backgroundProgram, "u_bottom"), s.backgroundBottom[0], s.backgroundBottom[1], s.backgroundBottom[2])
        GLES30.glBindVertexArray(emptyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDepthMask(true)
    }

    private fun renderShadowMap(scene: Scene, radius: Float) {
        val size = settings.shadowMapSize.coerceIn(256, 4096)
        ensureShadowMap(size)
        val r = maxOf(radius, 5f) * 1.05f
        val cx = (bMin[0] + bMax[0]) * 0.5f; val cy = (bMin[1] + bMax[1]) * 0.5f; val cz = (bMin[2] + bMax[2]) * 0.5f
        val lx = toLight[0]; val ly = toLight[1]; val lz = toLight[2]
        val upY = if (abs(ly) > 0.99f) 0f else 1f
        val upZ = if (abs(ly) > 0.99f) 1f else 0f
        Mat4.lookAtLH(lightView, 0, cx + lx * r * 2f, cy + ly * r * 2f, cz + lz * r * 2f, cx, cy, cz, 0f, upY, upZ)
        Mat4.orthoLH(lightProj, 0, -r, r, -r, r, 0.1f, r * 4f)
        Mat4.mul(lightViewProj, 0, lightProj, 0, lightView, 0)
        // 偏移矩阵：[-1,1] → [0,1]，并带一点深度偏置
        val bias = tmpM
        Mat4.identity(bias, 0)
        bias[0] = 0.5f; bias[5] = 0.5f; bias[10] = 0.5f
        bias[12] = 0.5f; bias[13] = 0.5f; bias[14] = 0.5f - 0.0012f
        Mat4.mul(shadowMat, 0, bias, 0, lightViewProj, 0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glViewport(0, 0, size, size)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        GLES30.glClearDepthf(1f)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(2f, 4f)
        for (m in scene.models) {
            if (!m.visible || !m.castShadow || m.kind == ModelInstance.KIND_STAGE || m.data.vertexCount == 0) continue
            val g = gpuFor(m)
            val p = simpleProgram(depthPrograms, g.flags, Shaders.DEPTH_VS, Shaders.DEPTH_FS)
            GLES30.glUseProgram(p.id)
            GLES30.glUniformMatrix4fv(p.uModel, 1, false, m.worldMatrix, 0)
            GLES30.glUniformMatrix4fv(p.uViewProj, 1, false, lightViewProj, 0)
            bindBones(p.uBoneTex, g)
            GLES30.glUniform1i(p.uTex, 0)
            GLES30.glBindVertexArray(g.vao)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            val mats = m.materials
            for (i in mats.indices) {
                val st = mats[i]
                val md = st.base
                if (!st.visible || !st.fxVisible || md.indexCount <= 0 || !md.castShadow) continue
                val alpha = st.diffuse[3] * st.fxAlpha * m.opacity
                if (alpha < 0.35f) continue
                val t = g.tex[i]
                val useTex = t != null && t.id != 0 && t.hasAlpha
                GLES30.glUniform1i(p.uTexMode, if (useTex) 1 else 0)
                GLES30.glUniform1f(p.uAlpha, alpha)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (useTex) t!!.id else textures.white)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, md.indexCount, g.indexType, md.indexOffset * g.indexBytes)
                lastDrawCalls++
            }
        }
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
    }

    private fun bindBones(uniform: Int, g: ModelGpu) {
        if (!g.skinned) return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, g.boneTex)
        GLES30.glUniform1i(uniform, 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun setupModelProgram(scene: Scene, m: ModelInstance, g: ModelGpu): ModelProgram {
        val s = settings
        val p = modelProgram(g.flags)
        val cam = scene.camera
        GLES30.glUseProgram(p.id)
        GLES30.glUniformMatrix4fv(p.uModel, 1, false, m.worldMatrix, 0)
        GLES30.glUniformMatrix4fv(p.uViewProj, 1, false, cam.viewProj, 0)
        GLES30.glUniformMatrix4fv(p.uView, 1, false, cam.view, 0)
        GLES30.glUniformMatrix4fv(p.uShadowMat, 1, false, if (shadowActive) shadowMat else identity, 0)
        GLES30.glUniform3f(p.uCameraPos, cam.position[0], cam.position[1], cam.position[2])
        GLES30.glUniform3f(p.uLightDir, toLight[0], toLight[1], toLight[2])
        val lc = scene.light.color
        GLES30.glUniform3f(p.uLightColor, lc[0], lc[1], lc[2])
        GLES30.glUniform3f(p.uSkyColor, s.skyColor[0], s.skyColor[1], s.skyColor[2])
        GLES30.glUniform3f(p.uGroundColor, s.groundColor[0], s.groundColor[1], s.groundColor[2])
        GLES30.glUniform2f(p.uShadowTexel, 1f / maxOf(shadowSize, 1), 1f / maxOf(shadowSize, 1))
        GLES30.glUniform1f(p.uRim, s.rim)
        val rk = s.rimColor[3].coerceIn(0f, 1f)
        GLES30.glUniform3f(p.uRimColor, lc[0] + (s.rimColor[0] - lc[0]) * rk, lc[1] + (s.rimColor[1] - lc[1]) * rk, lc[2] + (s.rimColor[2] - lc[2]) * rk)
        GLES30.glUniform3f(p.uShadeColor, s.shadeColor[0], s.shadeColor[1], s.shadeColor[2])
        GLES30.glUniform1f(p.uSpecScale, s.specular)
        GLES30.glUniform1f(p.uSaturation, s.saturation)
        GLES30.glUniform4f(p.uTint, 0f, 0f, 0f, 0f)
        GLES30.glUniform3f(p.uFxMul, 1f, 1f, 1f)
        GLES30.glUniform3f(p.uFxAdd, 0f, 0f, 0f)
        GLES30.glUniform1i(p.uTex, 0)
        GLES30.glUniform1i(p.uSphere, 1)
        GLES30.glUniform1i(p.uToon, 2)
        GLES30.glUniform1i(p.uShadowMap, 3)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (shadowActive) shadowTex else textures.dummyShadow)
        bindBones(p.uBoneTex, g)
        return p
    }

    /**
     * @param blendPass false = 常规通道（MMD 材质全部 + 通用材质的不透明 / 镂空部分）；true = 通用材质的半透明部分
     */
    private fun drawModel(scene: Scene, m: ModelInstance, g: ModelGpu, blendPass: Boolean, depthOnly: Boolean = false) {
        val s = settings
        val p = setupModelProgram(scene, m, g)
        GLES30.glBindVertexArray(g.vao)
        val mats = m.materials
        var cull = -1
        for (i in mats.indices) {
            val st = mats[i]
            val md = st.base
            if (!st.visible || !st.fxVisible || md.indexCount <= 0) continue
            val mmd = md.shading == MaterialData.SHADING_MMD
            val isBlend = !mmd && md.alphaMode == MaterialData.ALPHA_BLEND
            if (isBlend != blendPass) continue
            val alpha = st.diffuse[3] * st.fxAlpha * (if (depthOnly) 1f else m.opacity)
            if (alpha <= 0f) continue
            // 只写深度的那一遍：几乎全透明的材质（脚本淡出的部分）不参与遮挡
            if (depthOnly && alpha < 0.5f) continue

            val wantCull = if (md.doubleSided) 0 else 1
            if (wantCull != cull) {
                cull = wantCull
                if (cull == 1) { GLES30.glEnable(GLES30.GL_CULL_FACE); GLES30.glCullFace(GLES30.GL_BACK) } else GLES30.glDisable(GLES30.GL_CULL_FACE)
            }
            bindMaterial(p, m, g, i, alpha, s)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, md.indexCount, g.indexType, md.indexOffset * g.indexBytes)
            lastDrawCalls++
            lastTriangles += md.indexCount / 3
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun bindMaterial(p: ModelProgram, m: ModelInstance, g: ModelGpu, i: Int, alpha: Float, s: RenderSettings) {
        val st = m.materials[i]
        val md = st.base
        GLES30.glUniform4f(p.uDiffuse, st.diffuse[0], st.diffuse[1], st.diffuse[2], alpha)
        GLES30.glUniform3f(p.uFxMul, st.fxMul[0], st.fxMul[1], st.fxMul[2])
        GLES30.glUniform3f(p.uFxAdd, st.fxAdd[0], st.fxAdd[1], st.fxAdd[2])
        GLES30.glUniform4f(p.uTint, st.fxTint[0], st.fxTint[1], st.fxTint[2], st.fxTint[3])
        GLES30.glUniform3f(p.uAmbient, st.ambient[0], st.ambient[1], st.ambient[2])
        GLES30.glUniform3f(p.uSpecular, st.specular[0], st.specular[1], st.specular[2])
        GLES30.glUniform1f(p.uShininess, st.shininess)
        GLES30.glUniform3f(p.uEmissive, md.emissive[0], md.emissive[1], md.emissive[2])
        val shading = if (st.fxShading >= 0) st.fxShading else md.shading
        GLES30.glUniform1i(p.uShading, shading)
        val bands = if (st.fxCel >= 0) st.fxCel else s.celBands
        GLES30.glUniform4f(p.uCel, bands.toFloat(), s.celSoftness, s.celShift, s.celSpecular)
        GLES30.glUniform1f(p.uAlphaCutoff, if (md.alphaMode == MaterialData.ALPHA_MASK) md.alphaCutoff else 0.004f)
        val receive = shadowActive && m.receiveShadow && md.receiveShadow
        GLES30.glUniform1f(p.uShadowStrength, if (receive) s.shadowStrength else 0f)

        val t = g.tex[i]
        // 脚本给这块材质换了自己画的贴图（比如重画的瞳孔）
        val drawn = if (st.fxCanvas != 0) effectRenderer.canvases.get(st.fxCanvas) else 0
        val hasTex = drawn != 0 || (t != null && t.id != 0)
        GLES30.glUniform1i(p.uTexMode, if (hasTex) 1 else 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (drawn != 0) drawn else if (hasTex) t!!.id else textures.white)
        GLES30.glUniform4fv(p.uTexMul, 1, st.texMul, 0)
        GLES30.glUniform4fv(p.uTexAdd, 1, st.texAdd, 0)

        val sp = g.sphere[i]
        val hasSphere = sp != null && sp.id != 0
        var sphereMode = 0
        if (hasSphere) {
            sphereMode = when {
                shading != MaterialData.SHADING_MMD -> 4
                md.sphereMode == MaterialData.SPHERE_MUL -> 1
                md.sphereMode == MaterialData.SPHERE_ADD -> 2
                else -> 0
            }
        }
        GLES30.glUniform1i(p.uSphereMode, sphereMode)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (hasSphere) sp!!.id else textures.white)
        GLES30.glUniform4fv(p.uSphereMul, 1, st.sphereMul, 0)
        GLES30.glUniform4fv(p.uSphereAdd, 1, st.sphereAdd, 0)

        var toonId = g.sharedToon[i]
        if (toonId == 0) { val tt = g.toon[i]; if (tt != null) toonId = tt.id }
        GLES30.glUniform1i(p.uToonMode, if (toonId != 0) 1 else 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (toonId != 0) toonId else textures.white)
        GLES30.glUniform4fv(p.uToonMul, 1, st.toonMul, 0)
        GLES30.glUniform4fv(p.uToonAdd, 1, st.toonAdd, 0)
    }

    private fun drawEdges(scene: Scene, m: ModelInstance, g: ModelGpu, w: Int, h: Int) {
        val mats = m.materials
        var any = false
        val all = settings.edgeAll
        for (st in mats) if (st.visible && ((st.base.drawEdge && st.edgeSize > 0f) || all) && st.base.indexCount > 0) { any = true; break }
        if (!any) return
        val p = simpleProgram(edgePrograms, g.flags, Shaders.EDGE_VS, Shaders.EDGE_FS)
        // 没有蒙皮数据的模型没有「每个顶点的描边倍率」这一路：给个常数 1
        if (!g.skinned) GLES30.glVertexAttrib1f(5, 1f)
        val ec = settings.edgeColor
        val ek = ec[3].coerceIn(0f, 1f)
        val cam = scene.camera
        GLES30.glUseProgram(p.id)
        GLES30.glUniformMatrix4fv(p.uModel, 1, false, m.worldMatrix, 0)
        GLES30.glUniformMatrix4fv(p.uViewProj, 1, false, cam.viewProj, 0)
        GLES30.glUniform1f(p.uAspect, w.toFloat() / h)
        GLES30.glUniform1i(p.uTex, 0)
        bindBones(p.uBoneTex, g)
        GLES30.glBindVertexArray(g.vao)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_FRONT)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        // 像素宽度随分辨率缩放（以 1080p 为基准）
        val base = settings.edgeWidth * m.edgeScale * (h / 1080f) * 1.1f
        val proj11 = cam.proj[5]
        for (i in mats.indices) {
            val st = mats[i]
            val md = st.base
            val own = md.drawEdge && st.edgeSize > 0f
            if (!st.visible || !st.fxVisible || md.indexCount <= 0 || st.fxEdge <= 0f || (!own && !all)) continue
            val size = if (own) st.edgeSize else 1f
            var er = 0f; var eg = 0f; var eb = 0f; var ea = 1f
            if (own) { er = st.edgeColor[0]; eg = st.edgeColor[1]; eb = st.edgeColor[2]; ea = st.edgeColor[3] }
            er += (ec[0] - er) * ek; eg += (ec[1] - eg) * ek; eb += (ec[2] - eb) * ek; ea += (1f - ea) * ek
            val alpha = ea * st.diffuse[3] * st.fxAlpha * m.opacity
            if (alpha <= 0.01f) continue
            GLES30.glUniform4f(p.uEdgeColor, er, eg, eb, alpha)
            GLES30.glUniform1f(p.uEdgePixels, size * st.fxEdge * base * 2f / h)
            GLES30.glUniform1f(p.uEdgeWorld, size * st.fxEdge * 0.045f * m.scale * proj11)
            val t = g.tex[i]
            val useTex = t != null && t.id != 0 && t.hasAlpha
            GLES30.glUniform1i(p.uTexMode, if (useTex) 1 else 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (useTex) t!!.id else textures.white)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, md.indexCount, g.indexType, md.indexOffset * g.indexBytes)
            lastDrawCalls++
        }
        GLES30.glCullFace(GLES30.GL_BACK)
    }

    private fun drawGround(scene: Scene) {
        val s = settings
        val cam = scene.camera
        val p = groundProgram
        GLES30.glUseProgram(p)
        val fl = scene.floor
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        // 不透明的地板要写深度（后画的半透明物体才会被它挡住）；透明的不写
        GLES30.glDepthMask(fl.color[3] >= 0.999f)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(p, "u_viewProj"), 1, false, cam.viewProj, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(p, "u_shadowMat"), 1, false, if (shadowActive) shadowMat else identity, 0)
        val size = maxOf(600f, abs(cam.distance) * 12f)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(p, "u_center"), cam.target[0], fl.height, cam.target[2])
        GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "u_size"), size)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(p, "u_cameraPos"), cam.target[0], cam.target[1], cam.target[2])
        GLES30.glUniform4f(GLES30.glGetUniformLocation(p, "u_gridColor"), fl.gridColor[0], fl.gridColor[1], fl.gridColor[2], fl.gridColor[3])
        GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "u_gridOn"), if (s.grid && fl.grid) 1f else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "u_cell"), fl.cell.coerceAtLeast(0.01f))
        GLES30.glUniform4f(GLES30.glGetUniformLocation(p, "u_fill"), fl.color[0], fl.color[1], fl.color[2], fl.color[3])
        GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "u_fadeDist"), size * 0.6f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "u_shadowStrength"), if (shadowActive && s.groundShadow && fl.shadow) 0.42f * s.shadowStrength else 0f)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(p, "u_shadowTexel"), 1f / maxOf(shadowSize, 1), 1f / maxOf(shadowSize, 1))
        GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "u_shadowMap"), 3)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (shadowActive) shadowTex else textures.dummyShadow)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindVertexArray(groundVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDepthMask(true)
        lastDrawCalls++
    }

    /** 选中的部件：用带呼吸效果的半透明颜色再画一遍。 */
    private fun drawHighlight(scene: Scene) {
        val m = highlightModel ?: return
        val part = highlightPart ?: return
        if (!m.visible || part.hidden || !scene.models.contains(m)) return
        val g = gpu[m] ?: return
        if (highlightIbo == 0) highlightIbo = GlUtil.genBuffer()
        if (highlightUploaded !== part) {
            highlightUploaded = part
            val idx = m.data.indices
            val tris = part.triangles
            val buf = scratch.bytes(tris.size * 3 * g.indexBytes)
            if (g.indexBytes == 2) for (t in tris) { buf.putShort(idx[t].toShort()); buf.putShort(idx[t + 1].toShort()); buf.putShort(idx[t + 2].toShort()) }
            else for (t in tris) { buf.putInt(idx[t]); buf.putInt(idx[t + 1]); buf.putInt(idx[t + 2]) }
            buf.position(0)
            highlightCount = tris.size * 3
            GLES30.glBindVertexArray(0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, highlightIbo)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, highlightCount * g.indexBytes, buf, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        }
        if (highlightCount == 0) return
        val p = setupModelProgram(scene, m, g)
        val time = (System.nanoTime() - startNanos) / 1e9f
        val pulse = 0.34f + 0.16f * kotlin.math.sin(time * 5f)
        GLES30.glUniform4f(p.uDiffuse, 1f, 1f, 1f, pulse)
        GLES30.glUniform3f(p.uAmbient, 0f, 0f, 0f)
        GLES30.glUniform1i(p.uShading, 2)
        GLES30.glUniform4f(p.uCel, 0f, 0f, 0f, 0f)
        GLES30.glUniform1i(p.uTexMode, 0)
        GLES30.glUniform1i(p.uSphereMode, 0)
        GLES30.glUniform1i(p.uToonMode, 0)
        GLES30.glUniform3f(p.uEmissive, 0f, 0f, 0f)
        GLES30.glUniform1f(p.uAlphaCutoff, 0f)
        GLES30.glUniform1f(p.uShadowStrength, 0f)
        GLES30.glUniform4f(p.uTint, 1f, 0.62f, 0.1f, 1f)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(-1f, -2f)
        // VAO 里记录的是模型自己的索引缓冲：临时换成高亮用的，画完换回去
        GLES30.glBindVertexArray(g.vao)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, highlightIbo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, highlightCount, g.indexType, 0)
        g.rebindIndexBuffer()
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glDepthMask(true)
        GLES30.glUniform4f(p.uTint, 0f, 0f, 0f, 0f)
        lastDrawCalls++
    }

    private fun renderBloom(w: Int, h: Int) {
        ensureBloom(w, h)
        val p = bloomProgram
        GLES30.glUseProgram(p)
        GLES30.glViewport(0, 0, bloomW, bloomH)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "u_scene"), 0)
        val uMode = GLES30.glGetUniformLocation(p, "u_mode")
        val uDir = GLES30.glGetUniformLocation(p, "u_dir")
        GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "u_threshold"), settings.bloomThreshold)
        // 提取高亮
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[0])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)
        GLES30.glUniform1i(uMode, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        // 两轮可分离高斯模糊
        GLES30.glUniform1i(uMode, 1)
        for (i in 0 until 2) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[1])
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTex[0])
            GLES30.glUniform2f(uDir, (1f + i) / bloomW, 0f)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[0])
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTex[1])
            GLES30.glUniform2f(uDir, 0f, (1f + i) / bloomH)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
    }

    /** 读回某个帧缓冲的像素（RGBA，行序自下而上），截图 / 导出用。 */
    fun readPixels(fbo: Int, w: Int, h: Int, out: java.nio.ByteBuffer) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        out.position(0)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, out)
    }

    private class ModelProgram(val id: Int) {
        private fun u(name: String) = GLES30.glGetUniformLocation(id, name)
        val uModel = u("u_model"); val uViewProj = u("u_viewProj"); val uView = u("u_view"); val uShadowMat = u("u_shadowMat")
        val uBoneTex = u("u_boneTex")
        val uTex = u("u_tex"); val uSphere = u("u_sphere"); val uToon = u("u_toon"); val uShadowMap = u("u_shadowMap")
        val uDiffuse = u("u_diffuse"); val uAmbient = u("u_ambient"); val uSpecular = u("u_specular"); val uShininess = u("u_shininess")
        val uEmissive = u("u_emissive")
        val uLightDir = u("u_lightDir"); val uLightColor = u("u_lightColor"); val uCameraPos = u("u_cameraPos")
        val uSkyColor = u("u_skyColor"); val uGroundColor = u("u_groundColor")
        val uTexMode = u("u_texMode"); val uSphereMode = u("u_sphereMode"); val uToonMode = u("u_toonMode"); val uShading = u("u_shading")
        val uTexMul = u("u_texMul"); val uTexAdd = u("u_texAdd")
        val uSphereMul = u("u_sphereMul"); val uSphereAdd = u("u_sphereAdd")
        val uToonMul = u("u_toonMul"); val uToonAdd = u("u_toonAdd")
        val uAlphaCutoff = u("u_alphaCutoff"); val uShadowStrength = u("u_shadowStrength"); val uShadowTexel = u("u_shadowTexel")
        val uTint = u("u_tint"); val uRim = u("u_rim"); val uSaturation = u("u_saturation")
        val uRimColor = u("u_rimColor"); val uCel = u("u_cel"); val uShadeColor = u("u_shadeColor"); val uSpecScale = u("u_specScale")
        val uFxMul = u("u_fxMul"); val uFxAdd = u("u_fxAdd")
    }

    /** 描边 / 深度着色器共用的 uniform 位置。 */
    private class SimpleProgram(val id: Int) {
        private fun u(name: String) = GLES30.glGetUniformLocation(id, name)
        val uModel = u("u_model"); val uViewProj = u("u_viewProj"); val uBoneTex = u("u_boneTex")
        val uTex = u("u_tex"); val uTexMode = u("u_texMode"); val uAlpha = u("u_alpha")
        val uEdgeColor = u("u_edgeColor"); val uEdgePixels = u("u_edgePixels"); val uEdgeWorld = u("u_edgeWorld"); val uAspect = u("u_aspect")
    }

    companion object {
        private const val TAG = "Renderer"
    }
}

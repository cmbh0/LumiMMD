package com.lumi.mmd.engine.render

import android.opengl.GLES30
import com.lumi.mmd.engine.image.ImageData
import com.lumi.mmd.engine.image.Images
import com.lumi.mmd.engine.model.TextureRef
import com.lumi.mmd.engine.util.Log
import com.lumi.mmd.engine.util.TextCodec
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import com.lumi.mmd.engine.util.Memory

/** 一张 GPU 贴图的句柄。解码完成之前 [id] 为 0，绘制时用白色占位。 */
class GlTexture(val key: String, val kind: Int) {
    @Volatile var id: Int = 0
    @Volatile var hasAlpha: Boolean = false
    @Volatile var failed: Boolean = false
    var width = 0
    var height = 0
    var refCount = 0
    /** 所属 GL 上下文的代次；上下文丢失后旧句柄作废。 */
    var generation = 0

    companion object {
        const val KIND_COLOR = 0
        const val KIND_SPHERE = 1
        const val KIND_TOON = 2
    }
}

/**
 * 贴图缓存 + 异步加载。
 *
 * 大舞台动辄上百张贴图：解码放在后台线程池，GL 线程每帧只花几毫秒把解码好的图片上传，
 * 所以模型会先以白模出现，贴图再陆续"浮现"，不会卡住界面。
 * 相同文件的贴图在多个模型 / 材质之间共享（按规范化的绝对路径去重）。
 */
class TextureManager {
    private val cache = HashMap<String, GlTexture>()
    private val uploads = ConcurrentLinkedQueue<Pending>()
    private var executor: ExecutorService? = null
    private val pending = AtomicInteger(0)
    private var generation = 1

    /** 贴图最大边长（超出的在解码时降采样），0 = 不限制。 */
    @Volatile var maxTextureSize = 2048
    var anisotropy = 0f

    var white: Int = 0
        private set
    var dummyShadow: Int = 0
        private set
    private val sharedToons = IntArray(10)

    private class Pending(val tex: GlTexture, val image: ImageData?, val generation: Int)

    val pendingCount: Int get() = pending.get()

    /** 解码好了、还在排队等渲染线程上传的图片一共占多少字节（安卓上这块内存算在堆里）。 */
    private val backlog = java.util.concurrent.atomic.AtomicLong(0)
    /** 因为内存不够而没有加载的贴图张数（界面据此提示）。 */
    val skippedForMemory: Int get() = ownSkipped + Images.outOfMemoryCount
    @Volatile private var ownSkipped = 0
    val textureCount: Int get() = cache.size

    /** GL 上下文（重新）创建后调用。 */
    fun onContextCreated() {
        generation++
        cache.clear()
        uploads.clear()
        backlog.set(0)
        white = createFromImage(ImageData.solid(255, 255, 255), GlTexture.KIND_COLOR)
        // 1x1 深度纹理：阴影关闭时绑在阴影采样器上，保证采样器类型匹配
        dummyShadow = GlUtil.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dummyShadow)
        // 和真正的阴影贴图用同一种格式（DEPTH24 + UNSIGNED_INT），不额外依赖 32 位浮点深度的支持情况；全 1 = 最远
        val one = GlUtil.intBuffer(1).also { it.put(0, -1) }
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, 1, 1, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, one)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        for (i in 0 until 10) sharedToons[i] = createFromImage(DefaultToons.make(i), GlTexture.KIND_TOON)
    }

    fun sharedToon(index: Int): Int = sharedToons[index.coerceIn(0, 9)]

    /**
     * 取得（必要时开始加载）一张贴图。只能在 GL 线程调用。
     * @param baseDir 模型所在目录
     */
    fun acquire(ref: TextureRef, baseDir: File?, kind: Int): GlTexture {
        val embedded = ref.embedded
        val file = if (embedded == null) TextureResolver.resolve(baseDir, ref.path) else null
        val key = when {
            embedded != null -> "mem:" + System.identityHashCode(embedded) + ":" + kind
            file != null -> file.path + ":" + kind
            else -> "missing:" + (baseDir?.path ?: "") + "/" + ref.path + ":" + kind
        }
        cache[key]?.let { it.refCount++; return it }
        val tex = GlTexture(key, kind)
        tex.refCount = 1
        tex.generation = generation
        cache[key] = tex
        if (embedded == null && file == null) {
            // 共享 toon（toon01.bmp ~ toon10.bmp）不随模型分发时，用内置色带
            val shared = DefaultToons.sharedIndexOf(ref.path)
            if (shared >= 0) {
                tex.id = sharedToons[shared]
            } else {
                tex.failed = true
                if (ref.path.isNotEmpty()) Log.w(TAG, "找不到贴图: ${ref.path}")
            }
            return tex
        }
        val gen = generation
        val max = maxTextureSize
        pending.incrementAndGet()
        executor().execute {
            var img: ImageData? = null
            try {
                // 解码比上传快：渲染线程忙的时候（正在传一个大模型）解好的图会越攒越多，一张 2K 的图就是 16 MB。
                // 攒够一定量就等一等，让渲染线程先传掉一些；内存实在紧张时再多等一会儿，还是不够就放弃这一张，
                // 不能把堆用光（用光了整个应用会被杀掉）。
                var waited = 0
                while (backlog.get() > MAX_BACKLOG && gen == generation && waited < 1200) { Thread.sleep(25); waited++ }
                val encoded = embedded?.size?.toLong() ?: file!!.length()
                // 文件本身 + 一点解码用的空间；图太大放不下的话，平台解码器会自己缩小了再解
                val need = (if (embedded == null) encoded else 0L) + 8L * 1024 * 1024
                // 已经因为内存跳过一张了，后面的就别每张都等那么久
                val patience = if (skippedForMemory > 0) 10 else 50
                var tries = 0
                while (gen == generation && !Memory.fits(need, DECODE_RESERVE) && tries < patience) { Thread.sleep(100); tries++ }
                if (gen != generation) {
                    // 上下文已经换了，这张图不要了
                } else if (tries >= patience) {
                    ownSkipped++
                    Log.w(TAG, "内存不够，跳过贴图 ${ref.path}（${encoded / 1024} KB）")
                } else {
                    val bytes = embedded ?: file!!.readBytes()
                    img = Images.decode(bytes, ref.path, max)
                }
            } catch (e: Throwable) {
                if (e is OutOfMemoryError) ownSkipped++
                Log.w(TAG, "读取贴图失败 ${ref.path}: $e")
            }
            if (img != null) backlog.addAndGet(img.width.toLong() * img.height * 4)
            uploads.add(Pending(tex, img, gen))
        }
        return tex
    }

    fun release(tex: GlTexture) {
        if (--tex.refCount > 0) return
        cache.remove(tex.key)
        val id = tex.id
        tex.id = 0
        if (id != 0 && tex.generation == generation && id != white && sharedToons.none { it == id }) GlUtil.deleteTexture(id)
    }

    /** 每帧调用：在时间预算内上传已解码的贴图。返回本次上传的张数。 */
    fun pump(budgetNanos: Long = 6_000_000L): Int {
        val start = System.nanoTime()
        var n = 0
        while (true) {
            val p = uploads.poll() ?: break
            pending.decrementAndGet()
            p.image?.let { backlog.addAndGet(-(it.width.toLong() * it.height * 4)) }
            if (p.generation != generation || p.tex.refCount <= 0) continue
            val img = p.image
            if (img == null) {
                p.tex.failed = true
                continue
            }
            p.tex.id = createFromImage(img, p.tex.kind)
            p.tex.hasAlpha = img.hasAlpha
            p.tex.width = img.width
            p.tex.height = img.height
            n++
            if (System.nanoTime() - start > budgetNanos) break
        }
        return n
    }

    private fun createFromImage(img: ImageData, kind: Int): Int {
        val id = GlUtil.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        img.pixels.position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, img.width, img.height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, img.pixels)
        if (kind == GlTexture.KIND_COLOR) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            if (anisotropy > 1f) GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, anisotropy)
        } else {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        }
        return id
    }

    private fun executor(): ExecutorService {
        var e = executor
        if (e == null || e.isShutdown) {
            val counter = AtomicInteger()
            e = Executors.newFixedThreadPool(2) { r ->
                Thread(r, "tex-decode-" + counter.incrementAndGet()).also { it.isDaemon = true; it.priority = Thread.NORM_PRIORITY - 1 }
            }
            executor = e
        }
        return e!!
    }

    fun shutdown() {
        executor?.shutdownNow()
        executor = null
    }

    companion object {
        /** 排队等上传的图片最多攒这么多。 */
        private const val MAX_BACKLOG = 48L * 1024 * 1024
        /** 解码贴图时给应用其它部分留的余量。 */
        const val DECODE_RESERVE = 24L * 1024 * 1024
        private const val TAG = "Textures"
        private const val GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE
    }
}

/**
 * 按模型里记录的相对路径找贴图文件。依次尝试：
 * 原样 → 逐级忽略大小写 → 常见的编码错位（zip 里文件名用了别的编码）→ 只按文件名在模型目录树里搜索。
 */
object TextureResolver {
    private val dirCache = HashMap<String, Array<String>>()
    private val nameIndex = HashMap<String, HashMap<String, File>>()

    @Synchronized
    fun clearCache() {
        dirCache.clear(); nameIndex.clear()
    }

    @Synchronized
    fun resolve(baseDir: File?, rawPath: String): File? {
        if (rawPath.isEmpty()) return null
        val path = TextCodec.normalizePath(rawPath)
        val abs = File(path)
        if (abs.isAbsolute && abs.isFile) return abs
        if (baseDir == null) return null
        val direct = File(baseDir, path)
        if (direct.isFile) return direct
        resolveCaseInsensitive(baseDir, path)?.let { return it }
        for (alt in mojibakeVariants(path)) {
            resolveCaseInsensitive(baseDir, alt)?.let { return it }
        }
        // 只按文件名搜索（贴图被挪了目录，或路径写的是作者电脑上的绝对路径）
        val name = path.substringAfterLast('/').lowercase()
        val index = nameIndex.getOrPut(baseDir.path) { buildIndex(baseDir) }
        index[name]?.let { return it }
        for (alt in mojibakeVariants(name)) index[alt.lowercase()]?.let { return it }
        // 扩展名不同但主文件名相同（.tga 被转成了 .png 之类）。只在图片之间这样找：
        // 否则找 body.png 会找到 body.pmx，找 stage.mtl 会找到 stage.obj
        val stem = name.substringBeforeLast('.')
        if (stem.isNotEmpty() && name.substringAfterLast('.', "") in IMAGE_EXTENSIONS) {
            for ((k, v) in index) if (k.substringBeforeLast('.') == stem && k.substringAfterLast('.', "") in IMAGE_EXTENSIONS) return v
        }
        return null
    }

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "tga", "dds", "gif", "webp", "sph", "spa", "tif", "tiff", "psd")

    /** 文件名看起来是不是一张贴图（按扩展名）。 */
    fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    /**
     * 模型里写的一个路径在磁盘上可能叫的几个名字：先是它本来的写法，然后是文件名被解压软件弄成乱码之后的样子。
     * 分隔符统一成 /。给不走 [resolve]（比如通过系统的文件夹授权读文件）的地方用。
     */
    fun nameVariants(rawPath: String): List<String> {
        val path = TextCodec.normalizePath(rawPath)
        if (path.isEmpty()) return emptyList()
        return listOf(path) + mojibakeVariants(path)
    }

    private fun list(dir: File): Array<String> = dirCache.getOrPut(dir.path) { dir.list() ?: emptyArray() }

    private fun resolveCaseInsensitive(baseDir: File, path: String): File? {
        var cur = baseDir
        val parts = path.split('/')
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") { cur = cur.parentFile ?: return null; continue }
            val names = list(cur)
            val trimmed = part.trim()
            val match = names.firstOrNull { it == part } ?: names.firstOrNull { it.equals(part, ignoreCase = true) }
                ?: names.firstOrNull { it.trim().equals(trimmed, ignoreCase = true) } ?: return null
            cur = File(cur, match)
            if (i < parts.size - 1 && !cur.isDirectory) return null
        }
        return if (cur.isFile) cur else null
    }

    /** 文件名在 zip 里按 A 编码存、解压时按 B 编码读，会变成什么样。 */
    private fun mojibakeVariants(path: String): List<String> {
        if (TextCodec.isAscii(path.toByteArray(Charsets.UTF_8))) return emptyList()
        val out = ArrayList<String>(4)
        val sets = arrayOf(TextCodec.SJIS, TextCodec.GBK)
        for (from in sets) for (to in sets) {
            if (from == to) continue
            try {
                val s = String(path.toByteArray(from), to)
                if (s != path && !s.contains('\uFFFD')) out.add(s)
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun buildIndex(root: File): HashMap<String, File> {
        val map = HashMap<String, File>()
        var visited = 0
        fun walk(dir: File, depth: Int) {
            if (depth > 6 || visited > 20000) return
            val children = dir.listFiles() ?: return
            for (c in children) {
                visited++
                if (c.isDirectory) walk(c, depth + 1) else map.putIfAbsent(c.name.lowercase(), c)
            }
        }
        walk(root, 0)
        return map
    }
}

/** MMD 自带的 10 张共享 toon 色带的近似版本（程序生成，不分发原文件）。 */
internal object DefaultToons {
    // 每张色带暗部的颜色
    private val shade = arrayOf(
        intArrayOf(205, 205, 205), intArrayOf(245, 225, 225), intArrayOf(154, 154, 154), intArrayOf(248, 239, 235),
        intArrayOf(255, 230, 225), intArrayOf(255, 238, 180), intArrayOf(255, 255, 255), intArrayOf(240, 240, 255),
        intArrayOf(230, 230, 230), intArrayOf(255, 245, 235),
    )

    fun make(index: Int): ImageData {
        val w = 4
        val h = 32
        val img = ImageData.allocate(w, h)
        val s = shade[index.coerceIn(0, 9)]
        for (y in 0 until h) {
            // 上半亮、下半暗，中间留一小段过渡
            val t = ((y - (h / 2 - 2)) / 3f).coerceIn(0f, 1f)
            val r = (255 + (s[0] - 255) * t).toInt()
            val g = (255 + (s[1] - 255) * t).toInt()
            val b = (255 + (s[2] - 255) * t).toInt()
            for (x in 0 until w) img.pixels.put(r.toByte()).put(g.toByte()).put(b.toByte()).put(0xFF.toByte())
        }
        img.pixels.position(0)
        return img
    }

    /** "toon01.bmp" ~ "toon10.bmp" → 0..9，否则 -1。 */
    fun sharedIndexOf(path: String): Int {
        val name = path.substringAfterLast('/').lowercase()
        if (!name.startsWith("toon") || !name.endsWith(".bmp") || name.length != 10) return -1
        val n = name.substring(4, 6).toIntOrNull() ?: return -1
        return if (n in 1..10) n - 1 else -1
    }
}

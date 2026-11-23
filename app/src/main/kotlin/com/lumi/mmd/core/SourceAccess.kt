package com.lumi.mmd.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import com.lumi.mmd.engine.format.ModelRefs
import com.lumi.mmd.engine.render.TextureResolver
import java.io.File
import java.io.IOException

/**
 * 找回"模型旁边的文件"。
 *
 * 用系统的文件选择器只选一个 .pmx 时，Android 只把这一个文件交给应用，同一个文件夹里的贴图是读不到的。
 * 有两条路把贴图找回来：
 *  1. 有"所有文件访问权限"（老系统是存储权限）时，从选中文件的 Uri 推出它在手机里的真实路径，直接去那个文件夹里读；
 *  2. 没有权限时，请用户用系统的文件夹选择器把那个文件夹授权给应用，再在里面找。
 */
object SourceAccess {
    /** 能不能直接按路径读手机存储里的任意文件。 */
    fun canReadStorage(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    /** Android 11 及以上：存储权限要到系统设置里开；更老的系统弹普通的权限对话框即可。 */
    val needsSettingsPage: Boolean get() = Build.VERSION.SDK_INT >= 30

    /** 打开"允许访问所有文件"设置页。 */
    fun allFilesSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))

    private fun storageRoot(): String = try { Environment.getExternalStorageDirectory()?.path } catch (_: Throwable) { null } ?: "/storage/emulated/0"

    private fun documentId(uri: Uri): String? {
        val seg = uri.pathSegments ?: return null
        val i = seg.lastIndexOf("document")
        return if (i >= 0 && i + 1 < seg.size) seg[i + 1] else null
    }

    /**
     * 从文件的 Uri 猜它在手机存储里的真实路径。不同来源的 Uri 写法不一样，所以可能有好几个候选；
     * 没有读取权限时这些路径无法验证，等拿到权限后再用 [verify] 挑出对的那个。
     */
    fun guessPaths(context: Context, uri: Uri): List<String> {
        val out = LinkedHashSet<String>()
        val root = storageRoot()
        fun add(p: String?) { if (!p.isNullOrBlank() && p.startsWith("/") && p.length > 1) out.add(p) }
        if (uri.scheme == "file") { add(uri.path); return out.toList() }
        if (uri.scheme != "content") return emptyList()
        val auth = uri.authority ?: ""
        // 系统文件选择器（"文件"应用）给的文档 Uri
        val id = documentId(uri)
        if (id != null) {
            when {
                auth == "com.android.externalstorage.documents" -> {
                    val vol = id.substringBefore(':')
                    val rel = id.substringAfter(':', "")
                    when {
                        vol.equals("primary", true) -> add("$root/$rel")
                        vol.equals("home", true) -> add("$root/Documents/$rel")
                        else -> add("/storage/$vol/$rel")
                    }
                }
                id.startsWith("raw:") -> add(id.substring(4))
                id.startsWith("/") -> add(id)
                else -> {
                    // "下载" / "最近" 里选的文件：Uri 里只有媒体库的编号（msf:123、document:123）。有存储权限时可以向媒体库问出路径
                    val number = id.substringAfter(':').toLongOrNull()
                    if (number != null) try {
                        val files = android.provider.MediaStore.Files.getContentUri("external", number)
                        context.contentResolver.query(files, arrayOf("_data"), null, null, null)?.use { c ->
                            if (c.moveToFirst() && c.columnCount > 0) add(c.getString(0))
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        // 媒体库、下载、一些厂商的文件管理器肯直接告诉路径
        try {
            context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                if (c.moveToFirst() && c.columnCount > 0) add(c.getString(0))
            }
        } catch (_: Throwable) {
        }
        // "打开方式" / 分享过来的多半是 FileProvider：真实路径藏在 Uri 的路径里
        val p = uri.path ?: ""
        for (marker in arrayOf("/storage/", "/sdcard/", "/mnt/sdcard/")) {
            val i = p.indexOf(marker)
            if (i < 0) continue
            val tail = p.substring(i)
            add(when {
                tail.startsWith("/sdcard/") -> root + tail.substring(7)
                tail.startsWith("/mnt/sdcard/") -> root + tail.substring(11)
                else -> tail
            })
        }
        if (id == null) {
            // FileProvider 的第一段是各家自己起的根名字（external_files、external、root……），去掉它接到存储根目录下试试
            val rest = p.trimStart('/').substringAfter('/', "")
            if (rest.isNotEmpty()) { add("$root/$rest"); add("/$rest") }
        }
        return out.toList()
    }

    /** 资源库里存文件时会把文件名里不能用的字符换掉；比较名字前先做同样的处理。 */
    private fun plainName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.')

    /** 候选路径里确实是这个文件的那一个：存在、能读、名字和大小都对得上。 */
    fun verify(paths: List<String>, name: String, size: Long): File? {
        for (p in paths) {
            val f = File(p)
            try {
                if (!f.isFile || !f.canRead()) continue
                if (!f.name.equals(name, ignoreCase = true) && !plainName(f.name).equals(plainName(name), ignoreCase = true)) continue
                if (size > 0 && f.length() != size) continue
                return f
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /** 让文件夹选择器一打开就停在这个文件所在的文件夹（推不出来时返回 null，选择器会停在默认位置）。 */
    fun folderHint(context: Context, uri: Uri?, guesses: List<String>): Uri? {
        val auth = "com.android.externalstorage.documents"
        if (uri != null && uri.authority == auth) {
            val id = documentId(uri)
            if (id != null && id.contains(':')) {
                val vol = id.substringBefore(':')
                val rel = id.substringAfter(':')
                return DocumentsContract.buildDocumentUri(auth, vol + ":" + rel.substringBeforeLast('/', ""))
            }
        }
        val root = storageRoot()
        for (g in guesses) {
            val dir = g.substringBeforeLast('/', "")
            if (dir == root) return DocumentsContract.buildDocumentUri(auth, "primary:")
            if (dir.startsWith("$root/")) return DocumentsContract.buildDocumentUri(auth, "primary:" + dir.substring(root.length + 1))
        }
        return null
    }

    /** 模型里写的路径 → 放在模型文件夹里的位置。往上跳的（..）、带盘符的绝对路径都收进模型文件夹，引擎会按文件名找到它们。 */
    internal fun targetFor(dir: File, rel: String, actualName: String?): File? {
        val parts = rel.replace('\\', '/').split('/').map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." && !(it.length == 2 && it[1] == ':') }
            .map { it.replace(Regex("[:*?\"<>|\\u0000-\\u001f]"), "_") }
            .toMutableList()
        if (parts.isEmpty()) return null
        // 模型写的是作者电脑上的绝对路径时，只留文件名
        if (rel.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(rel)) { val last = parts.last(); parts.clear(); parts.add(last) }
        if (parts.size > 8) return null
        if (actualName != null && !actualName.equals(parts.last(), ignoreCase = true)) parts[parts.size - 1] = actualName.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
        val f = File(dir, parts.joinToString("/"))
        return if (f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) f else null
    }

    /**
     * 把模型缺的外部文件从 [source] 里找回来，放到模型旁边。
     * @return 找回了几个文件，以及仍然缺的清单
     */
    fun fetchMissing(model: File, source: SourceDir, progress: (Int) -> Unit = {}): Pair<Int, List<String>> {
        val dir = model.parentFile ?: return 0 to emptyList()
        val self = model.canonicalPath
        var copied = 0
        val tried = HashSet<String>()
        // OBJ / glTF 要先拿到 .mtl / .bin 才知道贴图清单，所以多转几轮
        for (round in 0 until 4) {
            val want = (ModelRefs.missing(model) + if (round == 0) ModelRefs.optional(model) else emptyList()).filter { tried.add(it) }
            if (want.isEmpty()) break
            var got = 0
            for (rel in want) {
                try {
                    // 无论来源里找到的是什么，都不能盖掉模型文件本身
                    if (source.copy(rel) { name -> targetFor(dir, rel, name)?.takeIf { it.canonicalPath != self } }) { got++; copied++; progress(copied) }
                } catch (_: IOException) {
                } catch (_: SecurityException) {
                }
            }
            TextureResolver.clearCache()
            if (got == 0) break
        }
        TextureResolver.clearCache()
        return copied to ModelRefs.missing(model)
    }
}

/** 一个可以按"模型里写的相对路径"取文件的来源。 */
interface SourceDir {
    /**
     * 找到 rel 指的文件并复制出来。target 由来源里文件的实际名字决定放在哪（返回 null 表示不要了）。
     * @return 找到并复制成功
     */
    fun copy(rel: String, target: (actualName: String) -> File?): Boolean
}

/** 手机存储里的一个真实文件夹（需要存储权限）。 */
class FileSourceDir(private val dir: File) : SourceDir {
    override fun copy(rel: String, target: (String) -> File?): Boolean {
        val src = TextureResolver.resolve(dir, rel) ?: return false
        if (!src.isFile || !src.canRead()) return false
        val dst = target(src.name) ?: return false
        dst.parentFile?.mkdirs()
        src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        return true
    }
}

/**
 * 用户用系统的文件夹选择器授权的一个文件夹。
 * 先在里面找到模型文件本身，再从它所在的那一层按相对路径找；找不到的按文件名在整个文件夹里搜。
 */
class TreeSourceDir(private val context: Context, private val tree: Uri, private val modelName: String, private val modelSize: Long) : SourceDir {
    private class Doc(val id: String, val name: String, val dir: Boolean, val size: Long)

    private val children = HashMap<String, List<Doc>>()
    private val parentOf = HashMap<String, String>()
    private val byName = HashMap<String, Doc>()
    private var base: String? = null
    private var scanned = false

    private fun list(id: String): List<Doc> = children.getOrPut(id) {
        val out = ArrayList<Doc>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        try {
            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val child = c.getString(0) ?: continue
                    out.add(Doc(child, c.getString(1) ?: "", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (c.isNull(3)) -1L else c.getLong(3)))
                    parentOf[child] = id
                }
            }
        } catch (_: Throwable) {
        }
        out
    }

    /** 把整个文件夹扫一遍（有上限）：建文件名索引，顺便找到模型文件在哪一层。 */
    private fun scan() {
        if (scanned) return
        scanned = true
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var visited = 0
        var best: Doc? = null
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rootId to 0)
        while (queue.isNotEmpty() && visited < 8000) {
            val (id, depth) = queue.removeFirst()
            for (d in list(id)) {
                visited++
                if (d.dir) { if (depth < 6) queue.add(d.id to depth + 1); continue }
                byName.putIfAbsent(d.name.lowercase(), d)
                if (d.name.equals(modelName, ignoreCase = true) && (best == null || (modelSize > 0 && d.size == modelSize && best.size != modelSize))) best = d
            }
        }
        base = best?.let { parentOf[it.id] } ?: rootId
    }

    /** 从模型所在的那一层按相对路径一段一段往下走。 */
    private fun walk(path: String): Doc? {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        var cur: String? = base
        for ((i, part) in parts.withIndex()) {
            val at = cur ?: return null
            if (part == "..") { cur = parentOf[at]; continue }
            val kids = list(at)
            val d = kids.firstOrNull { it.name == part } ?: kids.firstOrNull { it.name.equals(part, true) } ?: kids.firstOrNull { it.name.trim().equals(part.trim(), true) } ?: return null
            if (i == parts.size - 1) return if (d.dir) null else d
            if (!d.dir) return null
            cur = d.id
        }
        return null
    }

    private fun find(rel: String): Doc? {
        scan()
        // 模型里写的名字，以及它被解压软件弄成乱码之后的样子
        val variants = TextureResolver.nameVariants(rel)
        for (v in variants) walk(v)?.let { return it }
        // 路径对不上：只按文件名找（贴图被挪了位置，或者写的是作者电脑上的绝对路径）
        val names = variants.map { it.substringAfterLast('/').lowercase() }.filter { it.isNotEmpty() }
        for (n in names) byName[n]?.let { return it }
        // 再不行就找主文件名相同、扩展名不同的图片（.tga 被转成了 .png 之类）。只在图片之间这样找，
        // 否则找 body.png 会找到 body.pmx
        for (n in names) {
            if (!TextureResolver.isImageName(n)) continue
            val stem = n.substringBeforeLast('.')
            if (stem.isEmpty()) continue
            for ((k, v) in byName) if (k.substringBeforeLast('.') == stem && TextureResolver.isImageName(k)) return v
        }
        return null
    }

    override fun copy(rel: String, target: (String) -> File?): Boolean {
        val doc = find(rel) ?: return false
        val dst = target(doc.name) ?: return false
        dst.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, doc.id)) ?: return false
        input.use { i -> dst.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        return true
    }
}

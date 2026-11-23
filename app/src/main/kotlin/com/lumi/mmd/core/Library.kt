package com.lumi.mmd.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.lumi.mmd.engine.format.ModelLoader
import com.lumi.mmd.engine.render.TextureResolver
import com.lumi.mmd.engine.util.Log
import com.lumi.mmd.engine.util.TextCodec
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** 资源库里的一个可用文件。 */
data class LibraryItem(
    val path: String,
    val name: String,
    /** 所在的导入包（资源库下的一级目录名）。 */
    val pack: String,
    val type: Int,
    val sizeBytes: Long,
) {
    companion object {
        const val TYPE_MMD_MODEL = 0
        const val TYPE_OTHER_MODEL = 1
        const val TYPE_MOTION = 2
        const val TYPE_POSE = 3
        const val TYPE_AUDIO = 4
        const val TYPE_SCRIPT = 5
    }
}

/**
 * @param sources 单独选的（不是 zip / 文件夹里的）文件：资源库里的路径 → 用户选的那个 Uri。贴图没跟过来时靠它回原来的文件夹找。
 */
class ImportResult(val items: List<LibraryItem>, val message: String, val sources: Map<String, Uri> = emptyMap())

/**
 * 资源库：所有导入的东西都复制到 App 私有目录 files/library/<包名>/ 下。
 * 这样引擎可以直接用普通文件路径读取模型和贴图（相对路径、大小写不敏感匹配都在文件系统上做），
 * 也不依赖外部存储权限；用户用系统文件选择器挑选 zip / 文件夹 / 单个文件即可。
 */
class Library(private val context: Context) {
    val root: File = File(context.filesDir, "library").also { it.mkdirs() }

    private val audioExt = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus")
    private val archiveHint = setOf("rar", "7z", "tar", "gz", "lzh")

    private fun typeOf(name: String): Int {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "pmx" || ext == "pmd" -> LibraryItem.TYPE_MMD_MODEL
            ext in ModelLoader.MODEL_EXTENSIONS -> LibraryItem.TYPE_OTHER_MODEL
            ext == "vmd" -> LibraryItem.TYPE_MOTION
            ext == "vpd" -> LibraryItem.TYPE_POSE
            ext in audioExt -> LibraryItem.TYPE_AUDIO
            ext == "lua" -> LibraryItem.TYPE_SCRIPT
            else -> -1
        }
    }

    /** 扫描整个资源库（或其中一个包）。 */
    fun scan(pack: String? = null): List<LibraryItem> {
        val out = ArrayList<LibraryItem>()
        val packs = if (pack != null) listOf(File(root, pack)) else (root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList())
        for (p in packs) {
            var count = 0
            fun walk(dir: File, depth: Int) {
                if (depth > 8 || count > 5000) return
                val children = dir.listFiles() ?: return
                children.sortBy { it.name.lowercase() }
                for (c in children) {
                    if (c.isDirectory) { walk(c, depth + 1); continue }
                    val t = typeOf(c.name)
                    if (t < 0) continue
                    // glTF 引用的 .bin、OBJ 的 .mtl 等附属文件不算条目
                    out.add(LibraryItem(c.path, c.name, p.name, t, c.length()))
                    count++
                }
            }
            walk(p, 0)
        }
        return out
    }

    fun packs(): List<String> = root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sortedBy { it.lowercase() } ?: emptyList()

    fun deletePack(pack: String): Boolean {
        val dir = File(root, pack)
        if (!dir.canonicalPath.startsWith(root.canonicalPath + File.separator)) return false
        TextureResolver.clearCache()
        return dir.deleteRecursively()
    }

    fun deleteFile(path: String): Boolean {
        val f = File(path)
        if (!f.canonicalPath.startsWith(root.canonicalPath + File.separator)) return false
        TextureResolver.clearCache()
        val ok = f.delete()
        // 包目录空了就一起删掉
        var dir = f.parentFile
        while (dir != null && dir != root && dir.canonicalPath.startsWith(root.canonicalPath) && (dir.list()?.isEmpty() == true)) {
            dir.delete(); dir = dir.parentFile
        }
        return ok
    }

    /**
     * 删掉资源库里的一个条目，返回腾出了多少字节（没删成返回 -1）。
     * 它所在的包里没有别的条目了（比如一个模型和它的贴图）就把整个包删掉，贴图不会留下来占地方；
     * 包里还有别的模型 / 动作时只删这一个文件（贴图可能是它们共用的）。
     */
    fun deleteItem(path: String): Long {
        val f = File(path)
        val rootPath = root.canonicalPath + File.separator
        if (!f.canonicalPath.startsWith(rootPath) || !f.isFile) return -1
        val pack = f.canonicalPath.substring(rootPath.length).substringBefore(File.separatorChar)
        val packDir = File(root, pack)
        val others = if (packDir.isDirectory && packDir != f) scan(pack).count { File(it.path).canonicalPath != f.canonicalPath } else 1
        if (others == 0 && packDir.isDirectory) {
            val bytes = packBytes(pack)
            return if (deletePack(pack)) bytes else -1
        }
        val bytes = f.length()
        return if (deleteFile(path)) bytes else -1
    }

    fun packBytes(pack: String): Long = File(root, pack).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun uniqueDir(base: String): File {
        val clean = sanitize(base).ifBlank { "导入" }
        var dir = File(root, clean)
        var n = 2
        while (dir.exists()) { dir = File(root, "$clean ($n)"); n++ }
        dir.mkdirs()
        return dir
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').take(80)

    /** 文件大小（字节）；问不到时返回 -1。 */
    fun sizeOf(uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let { File(it).length() } ?: -1L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (_: Exception) {}
        return -1L
    }

    fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (_: Exception) {}
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /**
     * 导入用户选的若干个文件。zip 会解压成一个包；其它文件放进同一个"散装文件"包。
     * @param progress 进度提示（任意线程回调）
     */
    fun importFiles(uris: List<Uri>, progress: (String) -> Unit): ImportResult {
        val imported = ArrayList<LibraryItem>()
        val notes = ArrayList<String>()
        val sources = LinkedHashMap<String, Uri>()
        var looseDir: File? = null
        for (uri in uris) {
            val name = displayName(uri)
            val ext = name.substringAfterLast('.', "").lowercase()
            try {
                if (ext == "zip") {
                    progress("正在解压 $name…")
                    val tmp = File(context.cacheDir, "import_${System.nanoTime()}.zip")
                    try {
                        copyUri(uri, tmp)
                        val dir = uniqueDir(name.substringBeforeLast('.'))
                        val n = unzip(tmp, dir, progress)
                        flatten(dir)
                        val items = scan(dir.name)
                        if (items.isEmpty()) {
                            dir.deleteRecursively()
                            notes.add("$name 里没有找到可用的模型 / 动作（解压了 $n 个文件）")
                        } else imported.addAll(items)
                    } finally { tmp.delete() }
                } else if (ext in archiveHint) {
                    notes.add("$name：暂不支持 .$ext 压缩包，请先解压，再用「导入文件夹」")
                } else if (typeOf(name) < 0 && !isAuxiliary(ext)) {
                    notes.add("$name：不支持的文件类型")
                } else {
                    progress("正在导入 $name…")
                    val dir = looseDir ?: uniqueDir(if (uris.size == 1) name.substringBeforeLast('.') else "导入文件").also { looseDir = it }
                    val dst = File(dir, sanitize(name))
                    copyUri(uri, dst)
                    if (typeOf(name) >= 0) { imported.add(LibraryItem(dst.path, dst.name, dir.name, typeOf(name), dst.length())); sources[dst.path] = uri }
                }
            } catch (e: ZipException) {
                notes.add("$name：压缩包损坏或带有密码（带密码的压缩包请先解压，再用「导入文件夹」）")
            } catch (e: IOException) {
                notes.add("$name：读取失败（${e.message ?: "未知错误"}）")
            } catch (e: SecurityException) {
                notes.add("$name：没有读取权限")
            }
        }
        TextureResolver.clearCache()
        return ImportResult(imported, notes.joinToString("\n"), sources)
    }

    private fun isAuxiliary(ext: String) = ext in setOf("png", "jpg", "jpeg", "bmp", "tga", "dds", "sph", "spa", "gif", "webp", "mtl", "bin", "txt")

    /** 导入一整个文件夹（系统文件选择器返回的目录树 Uri）。 */
    fun importTree(treeUri: Uri, progress: (String) -> Unit): ImportResult {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = rootId.substringAfterLast('/').substringAfterLast(':').ifBlank { "文件夹" }
        val dir = uniqueDir(rootName)
        var files = 0
        var bytes = 0L
        val notes = ArrayList<String>()
        fun walk(docId: String, into: File, depth: Int) {
            if (depth > 10) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            val list = ArrayList<Triple<String, String, String>>()
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) list.add(Triple(c.getString(0), c.getString(1) ?: "file", c.getString(2) ?: ""))
            }
            for ((id, name, mime) in list) {
                if (files > 20000) return
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val sub = File(into, sanitize(name)); sub.mkdirs()
                    walk(id, sub, depth + 1)
                } else {
                    val dst = File(into, sanitize(name))
                    try {
                        copyUri(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), dst)
                        files++; bytes += dst.length()
                        if (files % 10 == 0) progress("正在导入文件夹… $files 个文件（${bytes / (1024 * 1024)} MB）")
                    } catch (e: Exception) { notes.add("$name：复制失败") }
                }
            }
        }
        try {
            progress("正在导入文件夹…")
            walk(rootId, dir, 0)
        } catch (e: Exception) {
            Log.w("Library", "导入文件夹失败", e)
            notes.add("读取文件夹失败：${e.message ?: e.javaClass.simpleName}")
        }
        TextureResolver.clearCache()
        val items = scan(dir.name)
        if (items.isEmpty()) {
            dir.deleteRecursively()
            notes.add("这个文件夹里没有找到可用的模型 / 动作文件")
        }
        return ImportResult(items, notes.take(4).joinToString("\n"))
    }

    private fun copyUri(uri: Uri, dst: File) {
        dst.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法打开文件")
        input.use { i -> dst.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
    }

    /**
     * 解压。老的 zip（尤其是日文 / 中文环境打包的 MMD 模型）文件名不是 UTF-8，
     * 这里先按 Latin-1 无损读出文件名的原始字节，再整体判断是 Shift-JIS 还是 GBK。
     */
    private fun unzip(zip: File, dir: File, progress: (String) -> Unit): Int {
        var count = 0
        ZipFile(zip, Charsets.ISO_8859_1).use { zf ->
            val entries = zf.entries().toList()
            // 需要重新解码的名字（没有 UTF-8 标志的条目会原样落在 0..255）
            val raw = entries.map { e -> e.name.takeIf { n -> n.all { it.code < 256 } && n.any { it.code >= 128 } } }
            val blob = raw.filterNotNull().joinToString("\n").toByteArray(Charsets.ISO_8859_1)
            val decoded = if (blob.isNotEmpty()) TextCodec.decodeAuto(blob, preferGbk = java.util.Locale.getDefault().language == "zh").split('\n') else emptyList()
            var di = 0
            val canonicalRoot = dir.canonicalPath + File.separator
            var total = 0L
            for ((i, e) in entries.withIndex()) {
                var name = e.name
                if (raw[i] != null) { name = decoded.getOrNull(di) ?: name; di++ }
                name = name.replace('\\', '/')
                if (e.isDirectory || name.endsWith("/")) continue
                val safe = name.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/") { sanitize(it) }
                if (safe.isEmpty()) continue
                val dst = File(dir, safe)
                if (!dst.canonicalPath.startsWith(canonicalRoot)) continue
                if (e.size > MAX_ENTRY_BYTES) continue
                dst.parentFile?.mkdirs()
                zf.getInputStream(e).use { input -> dst.outputStream().use { o -> total += input.copyTo(o, 1 shl 16) } }
                count++
                if (count % 20 == 0) progress("正在解压… $count 个文件（${total / (1024 * 1024)} MB）")
                if (total > MAX_TOTAL_BYTES) throw IOException("压缩包解压后超过 ${MAX_TOTAL_BYTES / (1024 * 1024 * 1024)} GB")
            }
        }
        return count
    }

    /** 压缩包里只有一个顶层文件夹时，把里面的内容提上来一层，路径短一些。 */
    private fun flatten(dir: File) {
        val children = dir.listFiles() ?: return
        if (children.size != 1 || !children[0].isDirectory) return
        val inner = children[0]
        val tmp = File(dir.parentFile, dir.name + ".tmp" + System.nanoTime())
        if (!inner.renameTo(tmp)) return
        if (dir.delete() && tmp.renameTo(dir)) return
        // 回滚失败的情况极少，保底：把内容移回去
        dir.mkdirs()
        tmp.renameTo(File(dir, inner.name))
    }

    companion object {
        private const val MAX_ENTRY_BYTES = 1024L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
    }
}

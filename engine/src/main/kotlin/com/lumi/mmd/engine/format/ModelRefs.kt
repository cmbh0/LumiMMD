package com.lumi.mmd.engine.format

import com.lumi.mmd.engine.render.TextureResolver
import com.lumi.mmd.engine.util.Json
import com.lumi.mmd.engine.util.arr
import com.lumi.mmd.engine.util.jObj
import com.lumi.mmd.engine.util.str
import java.io.File
import java.net.URLDecoder

/**
 * 模型文件引用的外部文件：贴图、OBJ 的 .mtl、glTF 的 .bin。
 *
 * 用户只选了模型文件本身（没有连文件夹一起导入）时，用它知道还缺哪些文件，
 * 再去模型原来所在的文件夹里把它们找回来。
 */
object ModelRefs {
    /**
     * 现在还找不到的外部文件，值是模型里写的相对路径（已统一成 '/' 分隔）。
     *
     * OBJ 和 glTF 要先有 .mtl / .bin 才能知道贴图清单，所以它们缺的时候只返回这些文件；
     * 补齐后再调一次就能拿到贴图。文件读不了时返回空表。
     */
    fun missing(file: File): List<String> {
        val dir = file.parentFile ?: return emptyList()
        val out = LinkedHashSet<String>()
        try {
            when (ModelLoader.extensionOf(file.name)) {
                "obj" -> for (name in objMaterialLibs(file)) if (TextureResolver.resolve(dir, name) == null) out.add(name)
                "gltf" -> for (uri in gltfUris(file)) if (TextureResolver.resolve(dir, uri) == null) out.add(uri)
            }
            if (out.isNotEmpty()) return out.toList()
            val data = ModelLoader.loadModel(file)
            for (t in data.textures) {
                if (t.embedded != null || t.path.isBlank()) continue
                if (TextureResolver.resolve(dir, t.path) == null) out.add(t.path)
            }
        } catch (_: Throwable) {
        }
        return out.toList()
    }

    /** 有就带上、没有也不算缺的文件（OBJ 没写 mtllib 时，同名的 .mtl）。 */
    fun optional(file: File): List<String> {
        if (ModelLoader.extensionOf(file.name) != "obj") return emptyList()
        val name = file.nameWithoutExtension + ".mtl"
        return if (File(file.parentFile, name).isFile) emptyList() else listOf(name)
    }

    private fun objMaterialLibs(file: File): List<String> {
        val out = ArrayList<String>()
        file.bufferedReader(Charsets.ISO_8859_1).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (!line.startsWith("mtllib", ignoreCase = true) || line.length < 8) continue
                // 文件名里可能有空格，也可能一行写了好几个；两种都试
                val rest = String(line.substring(6).trim().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                if (rest.lowercase().endsWith(".mtl") && rest.lowercase().indexOf(".mtl ") < 0) out.add(rest.replace('\\', '/'))
                else for (part in rest.split(' ', '\t')) if (part.isNotBlank()) out.add(part.replace('\\', '/'))
            }
        }
        return out
    }

    private fun gltfUris(file: File): List<String> {
        if (file.length() > 64L * 1024 * 1024) return emptyList()
        val root = Json.parse(file.readText(Charsets.UTF_8)).jObj() ?: return emptyList()
        val out = ArrayList<String>()
        for (key in arrayOf("buffers", "images")) {
            for (item in root.arr(key) ?: emptyList()) {
                val uri = item.jObj()?.str("uri") ?: continue
                if (uri.startsWith("data:") || uri.isBlank()) continue
                out.add(try { URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { uri })
            }
        }
        return out
    }
}

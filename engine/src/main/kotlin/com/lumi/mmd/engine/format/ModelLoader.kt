package com.lumi.mmd.engine.format

import com.lumi.mmd.engine.anim.Motion
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.util.Memory
import java.io.File

/**
 * 按文件类型分发到各个解析器的统一入口。
 * 所有格式最终都变成同一种 [ModelData]（MMD 左手坐标系），上层不需要关心来源。
 */
object ModelLoader {
    /** 支持导入的模型 / 场景扩展名。 */
    val MODEL_EXTENSIONS = setOf("pmx", "pmd", "x", "glb", "gltf", "vrm", "obj", "fbx", "stl", "ply", "blend")
    val MOTION_EXTENSIONS = setOf("vmd")
    val POSE_EXTENSIONS = setOf("vpd")

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isModelFile(name: String): Boolean = extensionOf(name) in MODEL_EXTENSIONS
    fun isMotionFile(name: String): Boolean = extensionOf(name) in MOTION_EXTENSIONS

    /** 该格式是否是 MMD 原生格式（单位、朝向都不需要换算）。 */
    fun isMmdFormat(name: String): Boolean = extensionOf(name).let { it == "pmx" || it == "pmd" }

    fun loadModel(file: File): ModelData {
        if (!file.isFile) throw FormatException("文件不存在：${file.name}")
        val head = ByteArray(16)
        val got = try { java.io.FileInputStream(file).use { it.read(head) } } catch (e: java.io.IOException) { throw FormatException("读不了这个文件：${e.message}") }
        val start = if (got > 0) head.copyOf(got) else ByteArray(0)
        val model = if (BlendFile.looksLike(start) || (extensionOf(file.name) == "blend" && (BlendFile.isGzip(start) || com.lumi.mmd.engine.util.Zstd.isZstd(start)))) {
            // .blend 动辄几百 MB：不整个读进内存
            BlendReader.read(file)
        } else {
            val size = file.length()
            if (size > MAX_FILE_BYTES) throw FormatException("文件过大（${size / (1024 * 1024)} MB），超出可导入的上限")
            // 文件本身 + 解析出来的数据，大约要文件大小的两倍多。放不下就先说清楚，不要硬读——
            // 把堆用光的话整个应用会被直接杀掉，连提示都来不及给
            val need = size * 22 / 10 + 16L * 1024 * 1024
            if (size > 24L * 1024 * 1024 && !Memory.fits(need)) {
                throw FormatException("文件太大（${Memory.mb(size)} MB）：读它大约要 ${Memory.mb(need)} MB 内存，现在只剩 ${Memory.mb(maxOf(0L, Memory.available() - Memory.RESERVE))} MB" +
                    "（应用最多能用 ${Memory.mb(Memory.max())} MB）。可以先移除场景里的其它模型再试")
            }
            parse(file.readBytes(), file)
        }
        model.sourceFile = file
        model.baseDir = file.parentFile
        if (model.name.isBlank()) model.name = file.nameWithoutExtension
        return model
    }

    private fun parse(bytes: ByteArray, file: File): ModelData {
        // 先看文件内容，再看扩展名（改错扩展名的模型很常见）
        if (PmxReader.isPmx(bytes)) return PmxReader.read(bytes)
        if (PmdReader.isPmd(bytes) && extensionOf(file.name) != "x") return PmdReader.read(bytes)
        if (BlendFile.looksLike(bytes)) return BlendReader.read(bytes, file)
        if (extensionOf(file.name) == "blend") return BlendReader.read(bytes, file)
        return when (val ext = extensionOf(file.name)) {
            "pmx" -> PmxReader.read(bytes)
            "pmd" -> PmdReader.read(bytes)
            "x" -> XReader.read(bytes)
            "glb", "gltf", "vrm" -> GltfReader.read(bytes, file)
            "obj" -> ObjReader.read(bytes, file)
            "fbx" -> FbxReader.read(bytes)
            "stl" -> StlReader.read(bytes)
            "ply" -> PlyReader.read(bytes)
            "blend" -> BlendReader.read(bytes, file)
            else -> throw FormatException("不支持的模型格式：.$ext")
        }
    }

    fun loadMotion(file: File): Motion {
        if (!file.isFile) throw FormatException("文件不存在：${file.name}")
        return VmdReader.read(file.readBytes(), file.nameWithoutExtension)
    }

    private const val MAX_FILE_BYTES = 768L * 1024 * 1024
}

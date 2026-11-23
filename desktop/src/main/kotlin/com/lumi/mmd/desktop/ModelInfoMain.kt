package com.lumi.mmd.desktop

import com.lumi.mmd.engine.format.ModelLoader
import com.lumi.mmd.engine.util.Json
import java.io.File

/**
 * 调试用：把模型文件读进来，打印统计信息（JSON，一行一个文件）。
 * 用来和 Blender 自己导出的基准数据对比，检查各种格式的导入器。
 * 输出的坐标已经是 MMD 坐标系（Y 向上、左手）、MMD 单位。
 */
fun main(args: Array<String>) {
    for (path in args) {
        val file = File(path)
        val out = LinkedHashMap<String, Any?>()
        out["file"] = file.name
        try {
            val t0 = System.nanoTime()
            val m = ModelLoader.loadModel(file)
            out["ms"] = (System.nanoTime() - t0) / 1_000_000
            m.computeBounds()
            out["format"] = m.format.name
            out["vertices"] = m.vertexCount
            out["triangles"] = m.triangleCount
            out["min"] = m.boundsMin.toList()
            out["max"] = m.boundsMax.toList()
            out["comment"] = m.comment
            out["suggested_scale"] = m.suggestedScale
            // 有多少三角形是正面朝外的：用"质心到面的方向和法线同向"粗略判断不了凹物体，这里只给面积为零的个数
            var degenerate = 0
            val p = m.positions; val idx = m.indices
            var signedVolume = 0.0
            var t = 0
            while (t + 2 < idx.size) {
                val a = idx[t] * 3; val b = idx[t + 1] * 3; val c = idx[t + 2] * 3
                val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
                val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                if (nx * nx + ny * ny + nz * nz < 1e-20f) degenerate++
                t += 3
            }
            out["degenerate"] = degenerate
            out["materials"] = m.materials.map { mat ->
                // 这个材质的三角形里，面法线和顶点法线同向的比例（绕序 / 法线是否一致）
                var agree = 0; var total = 0
                var k = mat.indexOffset
                var vol = 0.0
                while (k + 2 < mat.indexOffset + mat.indexCount) {
                    val a = idx[k] * 3; val b = idx[k + 1] * 3; val c = idx[k + 2] * 3
                    val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
                    val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
                    // 左手系 + 顺时针为正面：面法线 = u × v
                    val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                    val d = nx * m.normals[a] + ny * m.normals[a + 1] + nz * m.normals[a + 2]
                    if (d > 0f) agree++
                    total++
                    vol += (p[a] * (p[b + 1] * p[c + 2] - p[b + 2] * p[c + 1]) - p[a + 1] * (p[b] * p[c + 2] - p[b + 2] * p[c]) + p[a + 2] * (p[b] * p[c + 1] - p[b + 1] * p[c])) / 6.0
                    k += 3
                }
                signedVolume += vol
                linkedMapOf(
                    "name" to mat.name, "tris" to mat.indexCount / 3, "diffuse" to mat.diffuse.toList(), "emissive" to mat.emissive.toList(),
                    "texture" to (if (mat.texture >= 0) m.textures[mat.texture].let { tx -> if (tx.embedded != null) "embedded:${tx.path}:${tx.embedded!!.size}" else tx.path } else null),
                    "alpha_mode" to mat.alphaMode, "flags" to mat.flags, "shading" to mat.shading, "normal_agree" to (if (total > 0) agree.toFloat() / total else 1f), "volume" to vol,
                )
            }
            out["volume"] = signedVolume
            out["warnings"] = m.warnings.toList()
            if (System.getenv("DUMP_UV") != null) out["uv_range"] = listOf(m.uvs.filterIndexed { i, _ -> i % 2 == 0 }.minOrNull(), m.uvs.filterIndexed { i, _ -> i % 2 == 0 }.maxOrNull(), m.uvs.filterIndexed { i, _ -> i % 2 == 1 }.minOrNull(), m.uvs.filterIndexed { i, _ -> i % 2 == 1 }.maxOrNull())
        } catch (e: Throwable) {
            out["error"] = (e.message ?: e.toString())
            if (System.getenv("TRACE") != null) e.printStackTrace()
        }
        println(Json.stringify(out))
    }
}

package com.lumi.mmd.engine.format

import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.ModelFormat
import com.lumi.mmd.engine.util.FloatList
import com.lumi.mmd.engine.util.TextCodec
import java.io.File

/**
 * Wavefront OBJ + MTL。支持多边形面（扇形三角化）、负索引、顶点色（v x y z r g b）、
 * usemtl 分组，MTL 里的 Kd / Ks / Ns / Ke / d / Tr / map_Kd / map_Ke。
 * 直接在字节上扫描，几十 MB 的 OBJ 也不会产生海量临时字符串。
 */
object ObjReader {
    fun read(bytes: ByteArray, file: File?): ModelData {
        val b = MeshBuilder(ModelFormat.OBJ)
        b.setBasisRightHandedYUp()
        b.flipV = true
        val pos = FloatList(4096)
        val col = FloatList(16)
        var hasColor = false
        val nrm = FloatList(4096)
        val tex = FloatList(4096)
        val materialByName = HashMap<String, Int>()
        val mtlLibs = ArrayList<String>()
        var current = -1
        val vertexMap = com.lumi.mmd.engine.util.LongIntMap(1 shl 14)
        val corner = IntArray(64)

        val sc = ByteScanner(bytes)
        while (!sc.atEnd()) {
            val t = sc.nextToken()
            if (t < 0) { sc.skipLine(); continue }
            val len = sc.tokenEnd - t
            val c0 = bytes[t].toInt().toChar()
            if (c0 == '#') { sc.skipLine(); continue }
            if (len == 1 && c0 == 'v') {
                val x = sc.nextFloat(); val y = sc.nextFloat(); val z = sc.nextFloat()
                pos.add(x, y, z)
                if (!sc.atLineEnd()) {
                    val r = sc.nextFloat(1f); val g = sc.nextFloat(1f); val bl = sc.nextFloat(1f)
                    if (!sc.atLineEnd()) { /* v x y z w r g b 这种少见写法忽略 */ }
                    col.add(r, g, bl); hasColor = true
                } else col.add(1f, 1f, 1f)
            } else if (len == 2 && c0 == 'v' && bytes[t + 1] == 'n'.code.toByte()) {
                nrm.add(sc.nextFloat(), sc.nextFloat(), sc.nextFloat())
            } else if (len == 2 && c0 == 'v' && bytes[t + 1] == 't'.code.toByte()) {
                tex.add(sc.nextFloat(), sc.nextFloat())
            } else if (len == 1 && c0 == 'f') {
                if (current < 0) {
                    current = materialByName.getOrPut("") { b.addMaterial(b.newMaterial("默认材质")) }
                }
                var n = 0
                while (n < corner.size) {
                    val s = sc.nextToken()
                    if (s < 0) break
                    // v/vt/vn
                    var p = s
                    val e = sc.tokenEnd
                    var vi = 0; var ti = 0; var ni = 0
                    var field = 0
                    var start = p
                    while (p <= e) {
                        if (p == e || bytes[p] == '/'.code.toByte()) {
                            val v = if (p > start) ByteScanner.parseInt(bytes, start, p, 0) else 0
                            when (field) { 0 -> vi = v; 1 -> ti = v; 2 -> ni = v }
                            field++
                            start = p + 1
                        }
                        p++
                    }
                    val pc = pos.size / 3
                    if (vi < 0) vi += pc + 1
                    if (ti < 0) ti += tex.size / 2 + 1
                    if (ni < 0) ni += nrm.size / 3 + 1
                    if (vi < 1 || vi > pc) continue
                    if (ti > tex.size / 2) ti = 0
                    if (ni > nrm.size / 3) ni = 0
                    val key = MeshBuilder.hashKey(vi, ti, ni, 0)
                    var idx = vertexMap.get(key)
                    if (idx < 0) {
                        val po = (vi - 1) * 3
                        var nx = 0f; var ny = 0f; var nz = 0f
                        if (ni > 0) { val no = (ni - 1) * 3; nx = nrm[no]; ny = nrm[no + 1]; nz = nrm[no + 2] }
                        var u = 0f; var v = 0f
                        if (ti > 0) { val to = (ti - 1) * 2; u = tex[to]; v = tex[to + 1] }
                        idx = b.addVertex(pos[po], pos[po + 1], pos[po + 2], nx, ny, nz, u, v)
                        if (hasColor) {
                            b.enableColors()
                            b.setColor(idx, col[po], col[po + 1], col[po + 2], 1f)
                        }
                        vertexMap.put(key, idx)
                    }
                    corner[n++] = idx
                }
                for (k in 1 until n - 1) b.addTriangle(current, corner[0], corner[k], corner[k + 1])
            } else if (len == 6 && sc.tokenString(t) == "usemtl") {
                val name = TextCodec.decodeAuto(sc.restOfLine())
                current = materialByName.getOrPut(name) { b.addMaterial(b.newMaterial(name)) }
                continue
            } else if (len == 6 && sc.tokenString(t) == "mtllib") {
                mtlLibs.add(TextCodec.decodeAuto(sc.restOfLine()))
                continue
            }
            sc.skipLine()
        }
        if (hasColor) for (m in b.materials) m.flags = m.flags or MaterialData.FLAG_VERTEX_COLOR

        // MTL
        val dir = file?.parentFile
        if (dir != null) {
            val candidates = ArrayList<File>()
            for (lib in mtlLibs) {
                com.lumi.mmd.engine.render.TextureResolver.resolve(dir, lib)?.let { candidates.add(it) }
            }
            if (candidates.isEmpty()) File(dir, file.nameWithoutExtension + ".mtl").let { if (it.isFile) candidates.add(it) }
            for (f in candidates) {
                try { readMtl(f.readBytes(), b, materialByName) } catch (e: Exception) { b.warnings.add("读取 ${f.name} 失败：${e.message}") }
            }
            if (mtlLibs.isNotEmpty() && candidates.isEmpty()) b.warnings.add("找不到材质库 ${mtlLibs.joinToString()}，模型将没有贴图")
        }
        val model = b.build(GltfReader.UNIT_SCALE)
        model.suggestedScale = MeshBuilder.suggestScale(model, 1f)
        model.sanitize()
        return model
    }

    private fun readMtl(bytes: ByteArray, b: MeshBuilder, byName: Map<String, Int>) {
        var m: MaterialData? = null
        val text = TextCodec.decodeAuto(bytes)
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') continue
            val sp = line.indexOfFirst { it == ' ' || it == '\t' }
            val key = (if (sp < 0) line else line.substring(0, sp)).lowercase()
            val rest = if (sp < 0) "" else line.substring(sp + 1).trim()
            if (key == "newmtl") { m = byName[rest]?.let { b.materials[it] }; continue }
            val mat = m ?: continue
            val nums = rest.split(' ', '\t').filter { it.isNotEmpty() }
            fun f(i: Int, d: Float) = nums.getOrNull(i)?.toFloatOrNull() ?: d
            when (key) {
                "kd" -> { mat.diffuse[0] = f(0, 0.8f); mat.diffuse[1] = f(1, mat.diffuse[0]); mat.diffuse[2] = f(2, mat.diffuse[0]) }
                "ks" -> { mat.specular[0] = f(0, 0f); mat.specular[1] = f(1, mat.specular[0]); mat.specular[2] = f(2, mat.specular[0]) }
                "ke" -> { mat.emissive[0] = f(0, 0f); mat.emissive[1] = f(1, mat.emissive[0]); mat.emissive[2] = f(2, mat.emissive[0]) }
                "ns" -> mat.shininess = f(0, 0f).coerceIn(0f, 1000f)
                "d" -> { mat.diffuse[3] = f(0, 1f).coerceIn(0f, 1f); if (mat.diffuse[3] < 0.999f) mat.alphaMode = MaterialData.ALPHA_BLEND }
                "tr" -> { mat.diffuse[3] = (1f - f(0, 0f)).coerceIn(0f, 1f); if (mat.diffuse[3] < 0.999f) mat.alphaMode = MaterialData.ALPHA_BLEND }
                "map_kd" -> { mat.texture = b.texture(mapPath(rest)); if (mat.diffuse[0] == 0f && mat.diffuse[1] == 0f && mat.diffuse[2] == 0f) { mat.diffuse[0] = 1f; mat.diffuse[1] = 1f; mat.diffuse[2] = 1f } }
                "map_ke" -> { mat.sphereTexture = b.texture(mapPath(rest)); mat.sphereMode = MaterialData.SPHERE_ADD; if (mat.emissive.all { it == 0f }) mat.emissive.fill(1f) }
                "map_d" -> if (mat.alphaMode != MaterialData.ALPHA_BLEND) mat.alphaMode = MaterialData.ALPHA_MASK
            }
        }
        // 高光太弱或没有 Ns 时不算高光
        for (mat in b.materials) if (mat.shininess <= 1f) { mat.shininess = 0f }
    }

    /** map_Kd 后面可以带 -s 1 1 1 之类的选项：去掉选项，剩下的整段当文件名（文件名里可以有空格）。 */
    private fun mapPath(rest: String): String {
        val toks = rest.split(' ', '\t').filter { it.isNotEmpty() }
        var i = 0
        while (i < toks.size && toks[i].startsWith("-")) {
            val opt = toks[i].lowercase()
            i += when (opt) {
                "-s", "-o", "-t" -> 4
                "-mm" -> 3
                "-blendu", "-blendv", "-clamp", "-cc", "-bm", "-boost", "-texres", "-imfchan", "-type" -> 2
                else -> 2
            }
        }
        if (i >= toks.size) return toks.lastOrNull() ?: ""
        return toks.subList(i, toks.size).joinToString(" ")
    }
}

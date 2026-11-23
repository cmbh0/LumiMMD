package com.lumi.mmd.engine.format

import com.lumi.mmd.engine.util.TextCodec

/** VPD 姿势文件里的一根骨骼。 */
class PoseBone(val name: String, val translation: FloatArray, val rotation: FloatArray)

/** VPD（Vocaloid Pose Data）：文本格式的单帧姿势，Shift-JIS 编码。 */
object VpdReader {
    fun isVpd(bytes: ByteArray): Boolean {
        val magic = "Vocaloid Pose Data file"
        if (bytes.size < magic.length) return false
        for (i in magic.indices) if (bytes[i] != magic[i].code.toByte()) return false
        return true
    }

    fun read(bytes: ByteArray): List<PoseBone> {
        if (!isVpd(bytes)) throw FormatException("不是 VPD 姿势文件")
        val text = String(bytes, TextCodec.SJIS)
        val out = ArrayList<PoseBone>()
        val lines = text.lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            // Bone0{右腕
            val brace = line.indexOf('{')
            if (line.startsWith("Bone") && brace > 0) {
                val name = line.substring(brace + 1).trim()
                val t = numbers(lines.getOrNull(i + 1), 3)
                val r = numbers(lines.getOrNull(i + 2), 4)
                if (t != null && r != null) out.add(PoseBone(name, t, r))
                i += 3
                while (i < lines.size && lines[i] != "}") { if (lines[i].startsWith("Bone")) break; i++ }
                if (i < lines.size && lines[i] == "}") i++
            } else i++
        }
        if (out.isEmpty()) throw FormatException("VPD 文件里没有骨骼数据")
        return out
    }

    private fun numbers(line: String?, count: Int): FloatArray? {
        if (line == null) return null
        val parts = line.trimEnd(';').split(',').map { it.trim().toFloatOrNull() ?: return null }
        if (parts.size < count) return null
        return FloatArray(count) { parts[it] }
    }
}

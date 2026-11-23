package com.lumi.mmd.engine.format

import com.lumi.mmd.engine.anim.BoneTrack
import com.lumi.mmd.engine.anim.CameraTrack
import com.lumi.mmd.engine.anim.IkTrack
import com.lumi.mmd.engine.anim.LightTrack
import com.lumi.mmd.engine.anim.MorphTrack
import com.lumi.mmd.engine.anim.Motion
import com.lumi.mmd.engine.anim.SelfShadowTrack
import com.lumi.mmd.engine.anim.VisibilityTrack
import com.lumi.mmd.engine.util.BinaryReader
import com.lumi.mmd.engine.util.TextCodec
import kotlin.math.sqrt

/**
 * VMD（Vocaloid Motion Data）解析器：骨骼帧、表情帧、镜头帧、光照帧、自阴影帧、IK/显示帧。
 *
 * 大文件（几十 MB、几十万关键帧）也按"两遍扫描 + 基本类型数组"处理，不为每个关键帧创建对象：
 * 第一遍按名字统计每条轨道的帧数，第二遍把 (帧号, 记录序号) 打包成 Long 排序后直接填进轨道数组。
 */
object VmdReader {
    private const val BONE_RECORD = 111
    private const val MORPH_RECORD = 23
    private const val CAMERA_RECORD = 61
    private const val LIGHT_RECORD = 28
    private const val SHADOW_RECORD = 9

    fun isVmd(bytes: ByteArray): Boolean {
        val magic = "Vocaloid Motion Data"
        if (bytes.size < 30 + 10 + 4) return false
        for (i in magic.indices) if (bytes[i] != magic[i].code.toByte()) return false
        return true
    }

    /** VMD 里骨骼/表情名是定长 15 字节的 Shift-JIS，长名字会被截断；模型侧用同样的规则生成匹配用的 key。 */
    fun nameKey(name: String): String {
        val raw = name.toByteArray(TextCodec.SJIS)
        if (raw.size <= 15) return name
        return String(raw, 0, 15, TextCodec.SJIS)
    }

    /**
     * 名字里有 Shift-JIS 表示不了的字（比如简体中文骨骼名）时，VMD 里只能按 GBK 存；
     * 这里给出"GBK 字节被当成 Shift-JIS 读出来"的样子，用来和读到的轨道名对上号。可表示时返回 null。
     */
    fun gbkKey(name: String): String? {
        if (canEncode(name, TextCodec.SJIS)) return null
        val raw = name.toByteArray(TextCodec.GBK)
        return String(raw, 0, minOf(raw.size, 15), TextCodec.SJIS)
    }

    fun canEncode(s: String, cs: java.nio.charset.Charset): Boolean = try { cs.newEncoder().canEncode(s) } catch (_: Exception) { false }

    private fun i32At(d: ByteArray, p: Int): Int =
        (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8) or
            ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 3].toInt() and 0xFF) shl 24)

    private fun f32At(d: ByteArray, p: Int): Float = Float.fromBits(i32At(d, p))

    private fun rawName(d: ByteArray, p: Int, max: Int): String {
        var len = 0
        while (len < max && d[p + len].toInt() != 0) len++
        return String(d, p, len, TextCodec.LATIN1)
    }

    private fun decodeName(latin1: String): String = String(latin1.toByteArray(TextCodec.LATIN1), TextCodec.SJIS)

    fun read(bytes: ByteArray, name: String = ""): Motion {
        if (!isVmd(bytes)) throw FormatException("不是 VMD 动作文件")
        val r = BinaryReader(bytes)
        val header = r.fixedString(30, TextCodec.LATIN1)
        val modelNameLen = if (header.startsWith("Vocaloid Motion Data file")) 10 else 20
        val motion = Motion()
        motion.name = name
        motion.modelName = r.fixedString(modelNameLen, TextCodec.SJIS)

        try {
            readBones(r, motion)
            if (r.hasRemaining(4)) readMorphs(r, motion)
            if (r.hasRemaining(4)) readCamera(r, motion)
            if (r.hasRemaining(4)) readLight(r, motion)
            if (r.hasRemaining(4)) readSelfShadow(r, motion)
            if (r.hasRemaining(4)) readIk(r, motion)
        } catch (e: java.io.EOFException) {
            // 很多 VMD 在可选段落处直接截断，已读到的部分照常使用
        }
        motion.recomputeMaxFrame()
        return motion
    }

    private fun readBones(r: BinaryReader, motion: Motion) {
        val d = r.data
        val total = r.i32()
        if (total < 0 || total.toLong() * BONE_RECORD > r.remaining) throw FormatException("VMD 骨骼帧数异常：$total")
        val start = r.position
        // 第一遍：按名字分组计数
        val ids = HashMap<String, Int>()
        val names = ArrayList<String>()
        var counts = IntArray(64)
        val trackOf = IntArray(total)
        for (i in 0 until total) {
            val key = rawName(d, start + i * BONE_RECORD, 15)
            var id = ids[key]
            if (id == null) {
                id = names.size
                ids[key] = id
                names.add(key)
                if (id >= counts.size) counts = counts.copyOf(counts.size * 2)
            }
            counts[id]++
            trackOf[i] = id
        }
        // 第二遍：每条轨道收集 (帧号<<32 | 记录序号) 并排序
        val keys = Array(names.size) { LongArray(counts[it]) }
        val fill = IntArray(names.size)
        for (i in 0 until total) {
            val id = trackOf[i]
            val frame = maxOf(0, i32At(d, start + i * BONE_RECORD + 15))
            keys[id][fill[id]++] = (frame.toLong() shl 32) or i.toLong()
        }
        for (id in names.indices) {
            val ks = keys[id]
            ks.sort()
            // 同一帧出现多次时保留最后写入的那条
            var unique = 0
            for (k in ks.indices) {
                if (k + 1 < ks.size && (ks[k + 1] ushr 32) == (ks[k] ushr 32)) continue
                ks[unique++] = ks[k]
            }
            val track = BoneTrack(decodeName(names[id]), unique)
            track.rawName = names[id].toByteArray(TextCodec.LATIN1)
            for (k in 0 until unique) {
                val rec = start + (ks[k] and 0xFFFFFFFFL).toInt() * BONE_RECORD
                track.frames[k] = (ks[k] ushr 32).toInt()
                val o = k * 7
                val v = track.values
                v[o] = f32At(d, rec + 19); v[o + 1] = f32At(d, rec + 23); v[o + 2] = f32At(d, rec + 27)
                var qx = f32At(d, rec + 31); var qy = f32At(d, rec + 35); var qz = f32At(d, rec + 39); var qw = f32At(d, rec + 43)
                val n = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
                if (n < 1e-6f || n != n) { qx = 0f; qy = 0f; qz = 0f; qw = 1f } else { qx /= n; qy /= n; qz /= n; qw /= n }
                v[o + 3] = qx; v[o + 4] = qy; v[o + 5] = qz; v[o + 6] = qw
                for (c in 0 until 3) if (v[o + c] != v[o + c]) v[o + c] = 0f
                // 64 字节补间块的前 16 字节：X_x1,Y_x1,Z_x1,R_x1, X_y1,Y_y1,Z_y1,R_y1, X_x2,..., X_y2,...
                val ip = rec + 47
                val b = track.interp
                val bo = k * 16
                for (axis in 0 until 4) {
                    b[bo + axis * 4] = clamp127(d[ip + axis])
                    b[bo + axis * 4 + 1] = clamp127(d[ip + 4 + axis])
                    b[bo + axis * 4 + 2] = clamp127(d[ip + 8 + axis])
                    b[bo + axis * 4 + 3] = clamp127(d[ip + 12 + axis])
                }
            }
            if (unique > 0) motion.boneTracks[track.name] = track
        }
        r.position = start + total * BONE_RECORD
    }

    private fun clamp127(b: Byte): Byte {
        val v = b.toInt()
        return if (v < 0) 127 else if (v > 127) 127 else b
    }

    private fun readMorphs(r: BinaryReader, motion: Motion) {
        val d = r.data
        val total = r.i32()
        if (total < 0 || total.toLong() * MORPH_RECORD > r.remaining) throw java.io.EOFException()
        val start = r.position
        val ids = HashMap<String, Int>()
        val names = ArrayList<String>()
        var counts = IntArray(64)
        val trackOf = IntArray(total)
        for (i in 0 until total) {
            val key = rawName(d, start + i * MORPH_RECORD, 15)
            var id = ids[key]
            if (id == null) {
                id = names.size
                ids[key] = id
                names.add(key)
                if (id >= counts.size) counts = counts.copyOf(counts.size * 2)
            }
            counts[id]++
            trackOf[i] = id
        }
        val keys = Array(names.size) { LongArray(counts[it]) }
        val fill = IntArray(names.size)
        for (i in 0 until total) {
            val id = trackOf[i]
            val frame = maxOf(0, i32At(d, start + i * MORPH_RECORD + 15))
            keys[id][fill[id]++] = (frame.toLong() shl 32) or i.toLong()
        }
        for (id in names.indices) {
            val ks = keys[id]
            ks.sort()
            var unique = 0
            for (k in ks.indices) {
                if (k + 1 < ks.size && (ks[k + 1] ushr 32) == (ks[k] ushr 32)) continue
                ks[unique++] = ks[k]
            }
            val track = MorphTrack(decodeName(names[id]), unique)
            track.rawName = names[id].toByteArray(TextCodec.LATIN1)
            for (k in 0 until unique) {
                val rec = start + (ks[k] and 0xFFFFFFFFL).toInt() * MORPH_RECORD
                track.frames[k] = (ks[k] ushr 32).toInt()
                val w = f32At(d, rec + 19)
                track.weights[k] = if (w != w) 0f else w
            }
            if (unique > 0) motion.morphTracks[track.name] = track
        }
        r.position = start + total * MORPH_RECORD
    }

    private fun sortedOrder(r: BinaryReader, total: Int, recordSize: Int): LongArray {
        val d = r.data
        val start = r.position
        val ks = LongArray(total)
        for (i in 0 until total) {
            val frame = maxOf(0, i32At(d, start + i * recordSize))
            ks[i] = (frame.toLong() shl 32) or i.toLong()
        }
        ks.sort()
        return ks
    }

    private fun dedupe(ks: LongArray): Int {
        var unique = 0
        for (k in ks.indices) {
            if (k + 1 < ks.size && (ks[k + 1] ushr 32) == (ks[k] ushr 32)) continue
            ks[unique++] = ks[k]
        }
        return unique
    }

    private fun readCamera(r: BinaryReader, motion: Motion) {
        val d = r.data
        val total = r.i32()
        if (total < 0 || total.toLong() * CAMERA_RECORD > r.remaining) throw java.io.EOFException()
        val start = r.position
        if (total > 0) {
            val ks = sortedOrder(r, total, CAMERA_RECORD)
            val n = dedupe(ks)
            val track = CameraTrack(n)
            for (k in 0 until n) {
                val rec = start + (ks[k] and 0xFFFFFFFFL).toInt() * CAMERA_RECORD
                track.frames[k] = (ks[k] ushr 32).toInt()
                val o = k * 8
                val v = track.values
                v[o] = f32At(d, rec + 4)                       // distance
                v[o + 1] = f32At(d, rec + 8); v[o + 2] = f32At(d, rec + 12); v[o + 3] = f32At(d, rec + 16)   // 注视点
                v[o + 4] = f32At(d, rec + 20); v[o + 5] = f32At(d, rec + 24); v[o + 6] = f32At(d, rec + 28) // 欧拉角
                for (c in 0 until 24) track.interp[k * 24 + c] = clamp127(d[rec + 32 + c])
                v[o + 7] = i32At(d, rec + 56).toFloat()         // 视角（度）
                track.perspective[k] = d[rec + 60].toInt() == 0
            }
            motion.camera = track
        }
        r.position = start + total * CAMERA_RECORD
    }

    private fun readLight(r: BinaryReader, motion: Motion) {
        val d = r.data
        val total = r.i32()
        if (total < 0 || total.toLong() * LIGHT_RECORD > r.remaining) throw java.io.EOFException()
        val start = r.position
        if (total > 0) {
            val ks = sortedOrder(r, total, LIGHT_RECORD)
            val n = dedupe(ks)
            val track = LightTrack(n)
            for (k in 0 until n) {
                val rec = start + (ks[k] and 0xFFFFFFFFL).toInt() * LIGHT_RECORD
                track.frames[k] = (ks[k] ushr 32).toInt()
                for (c in 0 until 6) track.values[k * 6 + c] = f32At(d, rec + 4 + c * 4)
            }
            motion.light = track
        }
        r.position = start + total * LIGHT_RECORD
    }

    private fun readSelfShadow(r: BinaryReader, motion: Motion) {
        val d = r.data
        val total = r.i32()
        if (total < 0 || total.toLong() * SHADOW_RECORD > r.remaining) throw java.io.EOFException()
        val start = r.position
        if (total > 0) {
            val ks = sortedOrder(r, total, SHADOW_RECORD)
            val n = dedupe(ks)
            val track = SelfShadowTrack(n)
            for (k in 0 until n) {
                val rec = start + (ks[k] and 0xFFFFFFFFL).toInt() * SHADOW_RECORD
                track.frames[k] = (ks[k] ushr 32).toInt()
                track.modes[k] = d[rec + 4]
                track.distances[k] = f32At(d, rec + 5)
            }
            motion.selfShadow = track
        }
        r.position = start + total * SHADOW_RECORD
    }

    private fun readIk(r: BinaryReader, motion: Motion) {
        val total = r.i32()
        if (total <= 0 || total > 1_000_000) return
        val frames = IntArray(total)
        val shows = BooleanArray(total)
        val ikNames = ArrayList<String>()
        val ikIds = HashMap<String, Int>()
        // 每条记录：ikId -> enabled
        val perRecord = ArrayList<IntArray>(total)
        for (i in 0 until total) {
            frames[i] = maxOf(0, r.i32())
            shows[i] = r.u8() != 0
            val count = r.i32()
            if (count < 0 || count.toLong() * 21 > r.remaining) throw java.io.EOFException()
            val states = IntArray(count * 2)
            for (k in 0 until count) {
                val nm = r.fixedString(20, TextCodec.SJIS)
                val en = r.u8() != 0
                val id = ikIds.getOrPut(nm) { ikNames.add(nm); ikNames.size - 1 }
                states[k * 2] = id
                states[k * 2 + 1] = if (en) 1 else 0
            }
            perRecord.add(states)
        }
        val order = (0 until total).sortedBy { frames[it] }
        val vis = VisibilityTrack(total)
        for ((k, i) in order.withIndex()) {
            vis.frames[k] = frames[i]
            vis.visible[k] = shows[i]
        }
        motion.visibility = vis
        for (id in ikNames.indices) {
            val fs = ArrayList<Int>()
            val es = ArrayList<Boolean>()
            for (i in order) {
                val st = perRecord[i]
                var k = 0
                while (k < st.size) {
                    if (st[k] == id) { fs.add(frames[i]); es.add(st[k + 1] != 0) }
                    k += 2
                }
            }
            if (fs.isEmpty()) continue
            val t = IkTrack(ikNames[id], fs.size)
            for (k in fs.indices) { t.frames[k] = fs[k]; t.enabled[k] = es[k] }
            motion.ikTracks[t.name] = t
        }
    }
}

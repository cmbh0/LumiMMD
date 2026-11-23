package com.lumi.mmd.engine.format

import com.lumi.mmd.engine.anim.Motion
import com.lumi.mmd.engine.util.BinaryWriter
import com.lumi.mmd.engine.util.TextCodec

/**
 * 把 [Motion] 写成 VMD（Vocaloid Motion Data 0002），MikuMikuDance / MMM / Blender mmd_tools 等都能读。
 *
 * 名字是定长的 Shift-JIS：骨骼 / 表情名 15 字节，模型名 20 字节。超长的名字和 MMD 一样直接按字节截断
 * （可能截在半个字符上——读取时就是按"前 15 个字节"来匹配骨骼的，所以必须保持这个行为）。
 * Shift-JIS 表示不了的名字（简体中文骨骼名等）改用 GBK 写——中文系统上的 MMD 就是这么存的。
 */
object VmdWriter {
    fun nameBytes(name: String, max: Int): ByteArray {
        val cs = if (VmdReader.canEncode(name, TextCodec.SJIS)) TextCodec.SJIS else TextCodec.GBK
        val raw = name.toByteArray(cs)
        return if (raw.size <= max) raw else raw.copyOf(max)
    }

    /**
     * @param modelName 写进文件头的模型名；镜头 / 灯光动作应传 "カメラ・照明"
     */
    fun write(motion: Motion, modelName: String = motion.modelName): ByteArray {
        val w = BinaryWriter(1 shl 16)
        val magic = "Vocaloid Motion Data 0002".toByteArray(TextCodec.LATIN1)
        w.fixed(magic, 30)
        w.fixed(nameBytes(modelName.ifEmpty { "Lumi MMD" }, 20), 20)

        // ---- 骨骼
        var total = 0
        for (t in motion.boneTracks.values) total += t.count
        w.i32(total)
        for (t in motion.boneTracks.values) {
            val nm = t.rawName ?: nameBytes(t.name, 15)
            val v = t.values
            val ip = t.interp
            for (k in 0 until t.count) {
                w.fixed(nm, 15)
                w.i32(t.frames[k])
                val o = k * 7
                w.f32(v[o]); w.f32(v[o + 1]); w.f32(v[o + 2])
                w.f32(v[o + 3]); w.f32(v[o + 4]); w.f32(v[o + 5]); w.f32(v[o + 6])
                // 16 字节序列：X_x1,Y_x1,Z_x1,R_x1, X_y1,…, X_x2,…, X_y2,…；整块 64 字节是它逐行左移一位的四份
                val seq = ByteArray(16)
                val b = k * 16
                for (axis in 0 until 4) {
                    seq[axis] = ip[b + axis * 4]
                    seq[4 + axis] = ip[b + axis * 4 + 1]
                    seq[8 + axis] = ip[b + axis * 4 + 2]
                    seq[12 + axis] = ip[b + axis * 4 + 3]
                }
                for (row in 0 until 4) {
                    for (c in 0 until 16) {
                        val src = c + row
                        w.u8(if (src < 16) seq[src].toInt() else if (src == 16) 1 else 0)
                    }
                }
            }
        }

        // ---- 表情
        total = 0
        for (t in motion.morphTracks.values) total += t.count
        w.i32(total)
        for (t in motion.morphTracks.values) {
            val nm = t.rawName ?: nameBytes(t.name, 15)
            for (k in 0 until t.count) {
                w.fixed(nm, 15)
                w.i32(t.frames[k])
                w.f32(t.weights[k])
            }
        }

        // ---- 镜头
        val cam = motion.camera
        w.i32(cam?.count ?: 0)
        if (cam != null) {
            val v = cam.values
            for (k in 0 until cam.count) {
                val o = k * 8
                w.i32(cam.frames[k])
                w.f32(v[o])
                w.f32(v[o + 1]); w.f32(v[o + 2]); w.f32(v[o + 3])
                w.f32(v[o + 4]); w.f32(v[o + 5]); w.f32(v[o + 6])
                w.bytes(cam.interp, k * 24, 24)
                w.i32(Math.round(v[o + 7]))
                w.u8(if (cam.perspective[k]) 0 else 1)
            }
        }

        // ---- 灯光
        val light = motion.light
        w.i32(light?.count ?: 0)
        if (light != null) {
            for (k in 0 until light.count) {
                w.i32(light.frames[k])
                for (c in 0 until 6) w.f32(light.values[k * 6 + c])
            }
        }

        // ---- 自阴影
        val sh = motion.selfShadow
        w.i32(sh?.count ?: 0)
        if (sh != null) {
            for (k in 0 until sh.count) { w.i32(sh.frames[k]); w.u8(sh.modes[k].toInt()); w.f32(sh.distances[k]) }
        }

        // ---- 显示 / IK 开关：把各条 IK 轨道按帧号合并成记录
        val frames = java.util.TreeSet<Int>()
        for (t in motion.ikTracks.values) for (k in 0 until t.count) frames.add(t.frames[k])
        val vis = motion.visibility
        if (vis != null) for (k in 0 until vis.count) frames.add(vis.frames[k])
        w.i32(frames.size)
        for (f in frames) {
            w.i32(f)
            w.u8(if (vis == null || vis.sample(f.toFloat())) 1 else 0)
            w.i32(motion.ikTracks.size)
            for (t in motion.ikTracks.values) {
                w.fixed(nameBytes(t.name, 20), 20)
                w.u8(if (t.sample(f.toFloat(), 0)) 1 else 0)
            }
        }
        return w.toByteArray()
    }
}

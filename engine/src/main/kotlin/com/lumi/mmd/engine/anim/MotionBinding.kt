package com.lumi.mmd.engine.anim

import com.lumi.mmd.engine.format.VmdReader
import com.lumi.mmd.engine.model.ModelData

/**
 * 把一段 [Motion] 绑定到某个模型上：按名字把轨道对应到骨骼 / 表情序号，并保存每条轨道的播放游标。
 * 轨道里的关键帧可以随时增删（游标只是提示，越界会自动回到二分查找）；
 * 但新增 / 删除了整条轨道之后必须重新绑定。
 */
class MotionBinding(val motion: Motion, model: ModelData) {
    private val boneTracks: Array<BoneTrack>
    private val boneIndex: IntArray
    private val boneCursor: IntArray
    private val morphTracks: Array<MorphTrack>
    private val morphIndex: IntArray
    private val morphCursor: IntArray
    private val ikTracks: Array<IkTrack>
    private val ikIndex: IntArray

    /** 命中的骨骼 / 表情轨道数（给 UI 提示"动作与模型匹配度"）。 */
    val matchedBones: Int get() = boneTracks.size
    val matchedMorphs: Int get() = morphTracks.size
    val totalBoneTracks: Int = motion.boneTracks.size
    val totalMorphTracks: Int = motion.morphTracks.size

    init {
        // VMD 的名字被截断到 15 字节（Shift-JIS），模型侧用同样的规则生成 key
        val boneByKey = HashMap<String, Int>(model.bones.size * 2)
        for (i in model.bones.indices.reversed()) {
            val b = model.bones[i]
            if (b.nameEn.isNotEmpty()) boneByKey.putIfAbsent("\u0000en:" + b.nameEn.lowercase(), i)
        }
        // 中文系统的 MMD 用 GBK 写名字，按 Shift-JIS 读出来是乱码：把模型侧的名字也"乱码化"一份用来匹配
        for (i in model.bones.indices.reversed()) VmdReader.gbkKey(model.bones[i].name)?.let { boneByKey[it] = i }
        for (i in model.bones.indices.reversed()) {
            val name = model.bones[i].name
            boneByKey[name] = i
            boneByKey[VmdReader.nameKey(name)] = i
        }
        val bt = ArrayList<BoneTrack>()
        val bi = ArrayList<Int>()
        for ((name, track) in motion.boneTracks) {
            val idx = boneByKey[name] ?: boneByKey["\u0000en:" + name.lowercase()] ?: continue
            bt.add(track); bi.add(idx)
        }
        boneTracks = bt.toTypedArray()
        boneIndex = bi.toIntArray()
        boneCursor = IntArray(boneTracks.size)

        val morphByKey = HashMap<String, Int>(model.morphs.size * 2)
        for (i in model.morphs.indices.reversed()) VmdReader.gbkKey(model.morphs[i].name)?.let { morphByKey[it] = i }
        for (i in model.morphs.indices.reversed()) {
            val name = model.morphs[i].name
            morphByKey[name] = i
            morphByKey[VmdReader.nameKey(name)] = i
        }
        val mt = ArrayList<MorphTrack>()
        val mi = ArrayList<Int>()
        for ((name, track) in motion.morphTracks) {
            val idx = morphByKey[name] ?: continue
            mt.add(track); mi.add(idx)
        }
        morphTracks = mt.toTypedArray()
        morphIndex = mi.toIntArray()
        morphCursor = IntArray(morphTracks.size)

        val it = ArrayList<IkTrack>()
        val ii = ArrayList<Int>()
        for ((name, track) in motion.ikTracks) {
            val idx = boneByKey[name] ?: continue
            if (model.bones[idx].ik == null) continue
            it.add(track); ii.add(idx)
        }
        ikTracks = it.toTypedArray()
        ikIndex = ii.toIntArray()
    }

    /** 驱动第 bone 根骨骼的轨道（没有则为 null）。多条轨道对应同一根骨骼时取实际生效的最后一条。 */
    fun trackOfBone(bone: Int): BoneTrack? {
        for (i in boneIndex.indices.reversed()) if (boneIndex[i] == bone) return boneTracks[i]
        return null
    }

    fun trackOfMorph(morph: Int): MorphTrack? {
        for (i in morphIndex.indices.reversed()) if (morphIndex[i] == morph) return morphTracks[i]
        return null
    }

    /** 第 i 条已绑定的骨骼轨道对应的骨骼序号。 */
    fun boneOfTrack(i: Int): Int = boneIndex[i]
    fun boneTrackAt(i: Int): BoneTrack = boneTracks[i]
    fun morphOfTrack(i: Int): Int = morphIndex[i]
    fun morphTrackAt(i: Int): MorphTrack = morphTracks[i]

    /** 把 frame（30fps 的帧号，可为小数）时刻的动作写进骨骼与表情的"动作层"。 */
    fun apply(frame: Float, skeleton: Skeleton?, morphs: MorphController?) {
        if (skeleton != null) {
            val t = skeleton.animT
            val r = skeleton.animR
            val tracks = boneTracks
            val idx = boneIndex
            val cur = boneCursor
            for (i in tracks.indices) {
                val b = idx[i]
                cur[i] = tracks[i].sample(frame, cur[i], t, b * 3, r, b * 4)
            }
            for (i in ikTracks.indices) {
                skeleton.ikEnabled[ikIndex[i]] = ikTracks[i].sample(frame, 0)
            }
        }
        if (morphs != null) {
            val w = morphs.animWeights
            val tracks = morphTracks
            val idx = morphIndex
            val cur = morphCursor
            for (i in tracks.indices) {
                cur[i] = tracks[i].sample(frame, cur[i], w, idx[i])
            }
        }
    }
}

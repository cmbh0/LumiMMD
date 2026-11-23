package com.lumi.mmd.engine.scene

import com.lumi.mmd.engine.anim.BoneTrack
import com.lumi.mmd.engine.anim.Motion
import com.lumi.mmd.engine.anim.MotionClip
import com.lumi.mmd.engine.anim.TrackClip
import com.lumi.mmd.engine.math.Quat

/**
 * 轨道上片段的操作：移动、裁剪、分割、复制、删除。
 * 一条轨道 = 一个按开始位置排好、互不重叠的片段列表（某个角色的动作轨、镜头轨，或者音乐轨）。
 * 这些操作只改片段的摆放，不动动作里的关键帧 / 声音文件本身。
 */
object ClipOps {
    /** 片段最短留多少帧。 */
    const val MIN_LENGTH = 1
    /** 过渡最长多少帧（3 秒）。 */
    const val MAX_FADE = 90

    private fun overlaps(lane: List<TrackClip>, self: TrackClip?, start: Int, length: Int): Boolean {
        val end = start + length
        for (c in lane) {
            if (c === self) continue
            // 首尾相接不算重叠
            if (start < c.end && end > c.start) return true
            if (length == 0 && start >= c.start && start < c.end) return true
        }
        return false
    }

    /**
     * 离 want 最近的、放得下 length 帧而不和别的片段重叠的开始位置。
     * 想放的地方被占了，就贴到挡路的片段的前面或后面。
     */
    fun freeStart(lane: List<TrackClip>, self: TrackClip?, want: Int, length: Int): Int {
        val w = maxOf(want, 0)
        if (!overlaps(lane, self, w, length)) return w
        var best = -1
        fun consider(s: Int) {
            if (s < 0 || overlaps(lane, self, s, length)) return
            if (best < 0 || kotlin.math.abs(s - w) < kotlin.math.abs(best - w)) best = s
        }
        for (c in lane) {
            if (c === self) continue
            consider(c.end)
            consider(c.start - length)
        }
        if (best >= 0) return best
        // 实在没有空位：排到最后
        var end = 0
        for (c in lane) if (c !== self) end = maxOf(end, c.end)
        return end
    }

    /** 把片段挪到 want 附近（不会和别的片段重叠）。返回它实际的开始位置。 */
    fun <T : TrackClip> move(lane: MutableList<T>, clip: T, want: Int): Int {
        clip.start = freeStart(lane, clip, want, clip.length)
        lane.sortBy { it.start }
        return clip.start
    }

    /** 拖片段的左边缘到时间轴的 frame：开头被裁掉（或把之前裁掉的放回来）。 */
    fun trimStart(lane: List<TrackClip>, clip: TrackClip, frame: Int) {
        var f = frame
        // 不能越过前一个片段的结尾，不能把已经裁到头的再往外拉，也不能越过自己的结尾
        var floor = clip.start - clip.trimIn
        for (c in lane) if (c !== clip && c.end <= clip.start) floor = maxOf(floor, c.end)
        f = f.coerceIn(maxOf(floor, 0), maxOf(clip.end - MIN_LENGTH, maxOf(floor, 0)))
        val d = f - clip.start
        if (clip.trimOut < 0) clip.trimOut = clip.outFrame   // 结尾先定下来，否则长度会跟着变
        clip.trimIn += d
        clip.start = f
    }

    /**
     * 拖片段的右边缘到时间轴的 frame。
     * 动作可以拉得比它本身长：多出来的部分停在最后一个姿势上；音乐最多拉到文件的结尾。
     */
    fun trimEnd(lane: List<TrackClip>, clip: TrackClip, frame: Int) {
        var ceil = Int.MAX_VALUE
        for (c in lane) if (c !== clip && c.start >= clip.end) ceil = minOf(ceil, c.start)
        if (clip.maxOut != Int.MAX_VALUE) ceil = minOf(ceil, clip.start + clip.maxOut - clip.trimIn)
        val f = frame.coerceIn(clip.start + MIN_LENGTH, maxOf(ceil, clip.start + MIN_LENGTH))
        clip.trimOut = clip.trimIn + (f - clip.start)
    }

    /**
     * 在时间轴的 frame 处把片段切成两段，返回后一段（frame 不在片段中间时返回 null）。
     * 两段共用同一份动作数据；已经改过关键帧的动作（独占的）后一段会拿到自己的一份拷贝，之后各改各的。
     */
    fun split(lane: MutableList<MotionClip>, clip: MotionClip, frame: Int): MotionClip? {
        if (frame <= clip.start || frame >= clip.end) return null
        val cut = clip.trimIn + (frame - clip.start)
        val out = clip.outFrame
        val b = MotionClip(if (clip.owned) clip.motion.copy() else clip.motion, clip.name)
        b.owned = clip.owned
        b.sources = if (clip.owned) emptyList() else clip.sources
        b.start = frame
        b.trimIn = cut
        b.trimOut = out
        clip.trimOut = cut
        lane.add(b)
        lane.sortBy { it.start }
        return b
    }

    /** 把一段音乐在时间轴的 frame 处切成两段，返回后一段。两段接着放，听起来和没切一样。 */
    fun splitAudio(lane: MutableList<AudioClip>, clip: AudioClip, frame: Int): AudioClip? {
        if (frame <= clip.start || frame >= clip.end) return null
        val cut = clip.trimIn + (frame - clip.start)
        val out = clip.outFrame
        val b = AudioClip(clip.path, clip.name, clip.sourceFrames)
        b.start = frame
        b.trimIn = cut
        b.trimOut = out
        clip.trimOut = cut
        lane.add(b)
        lane.sortBy { it.start }
        return b
    }

    /** 复制一个片段，接在轨道最后面。 */
    fun duplicate(lane: MutableList<MotionClip>, clip: MotionClip): MotionClip {
        val b = MotionClip(if (clip.owned) clip.motion.copy() else clip.motion, clip.name)
        b.owned = clip.owned
        b.sources = if (clip.owned) emptyList() else clip.sources
        b.trimIn = clip.trimIn
        b.trimOut = clip.trimOut
        var end = 0
        for (c in lane) end = maxOf(end, c.end)
        b.start = end
        lane.add(b)
        lane.sortBy { it.start }
        return b
    }

    fun duplicateAudio(lane: MutableList<AudioClip>, clip: AudioClip): AudioClip {
        val b = clip.copy()
        b.start = end(lane)
        lane.add(b)
        lane.sortBy { it.start }
        return b
    }

    /** 往轨道里加一个片段：放在 at 附近的空位上。 */
    fun <T : TrackClip> insert(lane: MutableList<T>, clip: T, at: Int) {
        clip.start = freeStart(lane, null, at, clip.length)
        lane.add(clip)
        lane.sortBy { it.start }
    }

    fun <T : TrackClip> remove(lane: MutableList<T>, clip: T): Boolean = lane.remove(clip)

    /** 整条轨道的结尾（帧）。 */
    fun end(lane: List<TrackClip>): Int { var e = 0; for (c in lane) e = maxOf(e, c.end); return e }

    // ------------------------------------------------------------------ 过渡

    /** 轨道上排在 clip 前面的那一段（没有则为 null）。 */
    fun previous(lane: List<MotionClip>, clip: MotionClip): MotionClip? {
        var best: MotionClip? = null
        for (c in lane) if (c !== clip && c.start < clip.start && (best == null || c.start > best.start)) best = c
        return best
    }

    /**
     * 时间轴的 frame 处，clip 是不是正在从前一段过渡过来：是的话返回前一段。
     * 过渡占的是 clip 开头的 [MotionClip.fade] 帧。
     */
    fun fadingFrom(lane: List<MotionClip>, clip: MotionClip, frame: Float): MotionClip? {
        val f = clip.fade
        if (f <= 0 || frame < clip.start || frame >= clip.start + f) return null
        return previous(lane, clip)
    }

    /** 过渡走到第几成（0..1）时，后一段占多少比重：两头平缓、中间快。 */
    fun fadeWeight(k: Float): Float {
        val t = k.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** clip 在时间轴的 frame 处占的比重（见 [fadeWeight]）。 */
    fun fadeWeight(clip: MotionClip, frame: Float): Float = fadeWeight((frame - clip.start) / maxOf(clip.fade, 1))

    /** 两组镜头参数（VMD 的 8 个数：距离、目标 xyz、角度 xyz、视野）按 w 混合到 out；角度走近的那一边。 */
    fun blendCamera(a: FloatArray, b: FloatArray, w: Float, out: FloatArray) {
        for (i in 0 until 8) {
            var d = b[i] - a[i]
            if (i in 4..6) d -= TWO_PI * Math.round(d / TWO_PI)
            out[i] = a[i] + d * w
        }
    }

    private const val TWO_PI = (Math.PI * 2.0).toFloat()

    /**
     * 把一条轨道上的全部片段合成一份动作（导出 VMD 用）：
     * 每段只取它用到的那一截，挪到它在时间轴上的位置；每段的头尾各补一个关键帧，保证截断处的姿势和播放时一样；
     * 两段之间有空隙时，前一段的最后姿势一直保持到后一段开始的前一帧；
     * 两段之间有过渡时，过渡的那几帧逐帧写成混合后的姿势；
     * 某一段没有、别的段有的轨道，在那一段里补成"原位"（播放时就是这样）。
     */
    fun bake(lane: List<MotionClip>): Motion {
        val clips = lane.sortedBy { it.start }
        if (clips.size == 1 && clips[0].start == 0 && clips[0].trimIn == 0 && clips[0].trimOut < 0) return clips[0].motion.copy()
        val out = Motion()
        clips.firstOrNull()?.let { out.name = it.motion.name; out.modelName = it.motion.modelName }
        val t = FloatArray(3); val r = FloatArray(4); val w = FloatArray(1)
        val t0 = FloatArray(3); val r0 = FloatArray(4); val w0 = FloatArray(1)
        val multi = clips.size > 1
        val allBones = LinkedHashSet<String>(); val allMorphs = LinkedHashSet<String>(); val allIk = LinkedHashSet<String>()
        for (c in clips) {
            for ((n, tr) in c.motion.boneTracks) if (tr.count > 0) allBones.add(n)
            for ((n, tr) in c.motion.morphTracks) if (tr.count > 0) allMorphs.add(n)
            allIk.addAll(c.motion.ikTracks.keys)
        }
        for ((ci, c) in clips.withIndex()) {
            val a = c.trimIn; val off = c.offset
            val next = clips.getOrNull(ci + 1)
            val prev = clips.getOrNull(ci - 1)
            // 后面紧接着另一段时，这一段只播到它开始的前一帧（那一帧已经属于下一段了）
            val z = if (next != null && next.start <= c.end && c.outFrame > a) c.outFrame - 1 else c.outFrame
            val holdTo = if (next != null && next.start - 1 > c.end) next.start - 1 else -1
            // 过渡占这一段开头的 fade 帧：这几帧逐帧写，这一段自己的关键帧从过渡结束的地方（素材的第 a2 帧）开始
            val fade = if (prev != null) minOf(c.fade, maxOf(z - a, 0)) else 0
            val a2 = a + fade
            for (name in allBones) {
                val src = c.motion.boneTracks[name]?.takeIf { it.count > 0 }
                val ps = if (fade > 0) prev!!.motion.boneTracks[name]?.takeIf { it.count > 0 } else null
                if (src == null && ps == null && !multi) continue
                val dst = out.boneTrack(name)
                if (src != null) {
                    dst.rawName = dst.rawName ?: src.rawName
                    src.sample(a2.toFloat(), 0, t, 0, r, 0)
                    dst.set(a2 + off, t[0], t[1], t[2], r[0], r[1], r[2], r[3])
                    copyInner(src, dst, a2, z, off)
                    src.sample(z.toFloat(), 0, t, 0, r, 0)
                    val zi = src.indexOf(z)
                    dst.set(z + off, t[0], t[1], t[2], r[0], r[1], r[2], r[3], if (zi >= 0 && z > a2) src.interp else null, if (zi >= 0 && z > a2) zi * 16 else 0)
                    if (holdTo >= 0) dst.set(holdTo, t[0], t[1], t[2], r[0], r[1], r[2], r[3])
                } else {
                    dst.set(a2 + off, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
                    dst.set(z + off, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
                    if (holdTo >= 0) dst.set(holdTo, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
                }
                for (k in 0 until fade) {
                    val f = c.start + k
                    val k1 = fadeWeight(k / fade.toFloat())
                    if (ps != null) ps.sample(prev!!.fadeOutFrame(f.toFloat(), c), 0, t0, 0, r0, 0) else { t0.fill(0f); r0[0] = 0f; r0[1] = 0f; r0[2] = 0f; r0[3] = 1f }
                    if (src != null) src.sample((f - off).toFloat(), 0, t, 0, r, 0) else { t.fill(0f); r[0] = 0f; r[1] = 0f; r[2] = 0f; r[3] = 1f }
                    Quat.slerp(r, 0, r0, 0, r, 0, k1)
                    dst.set(f, t0[0] + (t[0] - t0[0]) * k1, t0[1] + (t[1] - t0[1]) * k1, t0[2] + (t[2] - t0[2]) * k1, r[0], r[1], r[2], r[3])
                }
            }
            for (name in allMorphs) {
                val src = c.motion.morphTracks[name]?.takeIf { it.count > 0 }
                val ps = if (fade > 0) prev!!.motion.morphTracks[name]?.takeIf { it.count > 0 } else null
                if (src == null && ps == null && !multi) continue
                val dst = out.morphTrack(name)
                if (src != null) {
                    dst.rawName = dst.rawName ?: src.rawName
                    src.sample(a2.toFloat(), 0, w, 0)
                    dst.set(a2 + off, w[0])
                    for (i in 0 until src.count) { val f = src.frames[i]; if (f > a2 && f < z) dst.set(f + off, src.weights[i]) }
                    src.sample(z.toFloat(), 0, w, 0)
                    dst.set(z + off, w[0])
                    if (holdTo >= 0) dst.set(holdTo, w[0])
                } else {
                    dst.set(a2 + off, 0f)
                    dst.set(z + off, 0f)
                    if (holdTo >= 0) dst.set(holdTo, 0f)
                }
                for (k in 0 until fade) {
                    val f = c.start + k
                    val k1 = fadeWeight(k / fade.toFloat())
                    if (ps != null) ps.sample(prev!!.fadeOutFrame(f.toFloat(), c), 0, w0, 0) else w0[0] = 0f
                    if (src != null) src.sample((f - off).toFloat(), 0, w, 0) else w[0] = 0f
                    dst.set(f, w0[0] + (w[0] - w0[0]) * k1)
                }
            }
            c.motion.camera?.let { src ->
                if (src.count > 0) {
                    val dst = out.cameraTrack()
                    val v = FloatArray(8)
                    var persp = src.sample(a2.toFloat(), v)
                    dst.set(a2 + off, v, 0, persp)
                    for (i in 0 until src.count) {
                        val f = src.frames[i]
                        if (f > a2 && f < z) dst.set(f + off, src.values, i * 8, src.perspective[i], src.interp, i * 24)
                    }
                    persp = src.sample(z.toFloat(), v)
                    val zi = src.indexOf(z)
                    dst.set(z + off, v, 0, persp, if (zi >= 0 && z > a2) src.interp else null, if (zi >= 0 && z > a2) zi * 24 else 0)
                    if (holdTo >= 0) dst.set(holdTo, v, 0, persp)
                    val pc = if (fade > 0) prev!!.motion.camera?.takeIf { it.count > 0 } else null
                    if (pc != null) {
                        val v0 = FloatArray(8)
                        for (k in 0 until fade) {
                            val f = c.start + k
                            pc.sample(prev!!.fadeOutFrame(f.toFloat(), c), v0)
                            persp = src.sample((f - off).toFloat(), v)
                            blendCamera(v0, v, fadeWeight(k / fade.toFloat()), v)
                            dst.set(f, v, 0, persp)
                        }
                    } else if (fade > 0) {
                        for (k in 0 until fade) { val f = c.start + k; persp = src.sample((f - off).toFloat(), v); dst.set(f, v, 0, persp) }
                    }
                }
            }
            for (name in allIk) {
                val src = c.motion.ikTracks[name]
                val dst = out.ikTrack(name)
                if (src == null) { if (multi) dst.set(c.start, true); continue }
                dst.set(a + off, src.sample(a.toFloat(), 0))
                for (i in 0 until src.count) { val f = src.frames[i]; if (f > a && f < z) dst.set(f + off, src.enabled[i]) }
            }
        }
        out.recomputeMaxFrame()
        return out
    }

    private fun copyInner(src: BoneTrack, dst: BoneTrack, a: Int, z: Int, off: Int) {
        val v = src.values
        for (i in 0 until src.count) {
            val f = src.frames[i]
            if (f <= a || f >= z) continue
            val o = i * 7
            dst.set(f + off, v[o], v[o + 1], v[o + 2], v[o + 3], v[o + 4], v[o + 5], v[o + 6], src.interp, i * 16)
        }
    }
}

package com.lumi.mmd.engine.anim

import com.lumi.mmd.engine.math.Bezier
import com.lumi.mmd.engine.math.Quat

/**
 * 一段动作数据（VMD 解析后的紧凑形式）。
 * 每条轨道把关键帧按帧号排好序、拆成几个基本类型数组，播放时二分 + 游标定位，零分配。
 * 从文件读出来的 Motion 可以同时被多个模型共享；游标等运行时状态放在 [MotionBinding] 里。
 * 要编辑（打关键帧）时先 [copy] 出一份自己的，再用各轨道的 set / remove 修改——数组按需扩容，
 * 只有 [count] 个元素有效。
 */
class Motion {
    var name: String = ""
    /** VMD 头里记录的模型名（镜头/光照动作里是 "カメラ・照明"）。 */
    var modelName: String = ""
    val boneTracks = LinkedHashMap<String, BoneTrack>()
    val morphTracks = LinkedHashMap<String, MorphTrack>()
    val ikTracks = LinkedHashMap<String, IkTrack>()
    var camera: CameraTrack? = null
    var light: LightTrack? = null
    var selfShadow: SelfShadowTrack? = null
    var visibility: VisibilityTrack? = null
    /** 最后一个关键帧的帧号（30fps）。 */
    var maxFrame: Int = 0

    val hasModelMotion: Boolean get() = boneTracks.isNotEmpty() || morphTracks.isNotEmpty()
    val hasCamera: Boolean get() = (camera?.count ?: 0) > 0
    val hasLight: Boolean get() = (light?.count ?: 0) > 0
    val durationSeconds: Float get() = maxFrame / FPS

    fun recomputeMaxFrame() {
        var m = 0
        for (t in boneTracks.values) if (t.count > 0) m = maxOf(m, t.frames[t.count - 1])
        for (t in morphTracks.values) if (t.count > 0) m = maxOf(m, t.frames[t.count - 1])
        for (t in ikTracks.values) if (t.count > 0) m = maxOf(m, t.frames[t.count - 1])
        camera?.let { if (it.count > 0) m = maxOf(m, it.frames[it.count - 1]) }
        light?.let { if (it.count > 0) m = maxOf(m, it.frames[it.count - 1]) }
        maxFrame = m
    }

    /**
     * 把另一段动作叠加进来（常见用法：身体动作 + 表情动作 + 口型动作分成多个 VMD）。
     * 同名轨道以后加载的为准。
     */
    fun mergeFrom(other: Motion) {
        for ((k, v) in other.boneTracks) boneTracks[k] = v
        for ((k, v) in other.morphTracks) morphTracks[k] = v
        for ((k, v) in other.ikTracks) ikTracks[k] = v
        if (other.hasCamera) camera = other.camera
        if (other.hasLight) light = other.light
        if (other.selfShadow != null) selfShadow = other.selfShadow
        if (other.visibility != null) visibility = other.visibility
        if (name.isEmpty()) name = other.name else if (other.name.isNotEmpty()) name = "$name + ${other.name}"
        recomputeMaxFrame()
    }

    /**
     * 叠加一个"表情 / 口型"动作：只有表情轨道的 VMD 会把原来的表情轨道整个换掉
     * （身体动作自带的口型和单独的口型 VMD 混在一起会打架）；带骨骼的 VMD 仍按同名轨道覆盖。
     */
    fun overlay(other: Motion) {
        if (other.boneTracks.isEmpty() && other.morphTracks.isNotEmpty()) {
            morphTracks.clear()
            for ((k, v) in other.morphTracks) morphTracks[k] = v
            if (other.name.isNotEmpty()) name = if (name.isEmpty()) other.name else "$name + ${other.name}"
            recomputeMaxFrame()
        } else mergeFrom(other)
    }

    /** 取（或新建）一根骨骼的轨道。新建了轨道之后要重新绑定（[MotionBinding]）才会生效。 */
    fun boneTrack(name: String): BoneTrack = boneTracks.getOrPut(name) { BoneTrack(name, 0) }
    fun morphTrack(name: String): MorphTrack = morphTracks.getOrPut(name) { MorphTrack(name, 0) }
    fun ikTrack(name: String): IkTrack = ikTracks.getOrPut(name) { IkTrack(name, 0) }
    fun cameraTrack(): CameraTrack = camera ?: CameraTrack(0).also { camera = it }
    fun lightTrack(): LightTrack = light ?: LightTrack(0).also { light = it }

    /** 去掉已经没有关键帧的轨道。返回是否有轨道被删掉（需要重新绑定）。 */
    fun pruneEmptyTracks(): Boolean {
        val a = boneTracks.size + morphTracks.size + ikTracks.size
        boneTracks.values.removeAll { it.count == 0 }
        morphTracks.values.removeAll { it.count == 0 }
        ikTracks.values.removeAll { it.count == 0 }
        return a != boneTracks.size + morphTracks.size + ikTracks.size
    }

    /** 关键帧总数（骨骼 + 表情 + 镜头）。 */
    val keyCount: Int get() {
        var n = 0
        for (t in boneTracks.values) n += t.count
        for (t in morphTracks.values) n += t.count
        n += camera?.count ?: 0
        return n
    }

    /** 深拷贝：得到一份可以放心修改的动作。 */
    fun copy(): Motion {
        val m = Motion()
        m.name = name; m.modelName = modelName
        for ((k, v) in boneTracks) m.boneTracks[k] = v.copy()
        for ((k, v) in morphTracks) m.morphTracks[k] = v.copy()
        for ((k, v) in ikTracks) m.ikTracks[k] = v.copy()
        m.camera = camera?.copy()
        m.light = light?.copy()
        m.selfShadow = selfShadow?.copy()
        m.visibility = visibility?.copy()
        m.maxFrame = maxFrame
        return m
    }

    companion object {
        const val FPS = 30f

        /** 在升序帧号数组里找帧号恰好等于 frame 的关键帧；没有则返回 -(插入位置) - 1。 */
        @JvmStatic
        fun indexOfFrame(frames: IntArray, count: Int, frame: Int): Int {
            var lo = 0
            var hi = count - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val f = frames[mid]
                if (f < frame) lo = mid + 1 else if (f > frame) hi = mid - 1 else return mid
            }
            return -(lo + 1)
        }

        /** 线性补间：两个控制点在对角线上（MMD 的默认值 20,20 / 107,107）。 */
        @JvmStatic
        fun linearInterp(dst: ByteArray, offset: Int, channels: Int) {
            for (c in 0 until channels) {
                dst[offset + c * 4] = 20; dst[offset + c * 4 + 1] = 20; dst[offset + c * 4 + 2] = 107; dst[offset + c * 4 + 3] = 107
            }
        }

        /**
         * 在升序帧号数组里找到 frames[i] <= frame 的最大 i（frame 在第一个关键帧之前返回 -1）。
         * hint 是上一次的结果：顺序播放时 O(1) 命中。
         */
        @JvmStatic
        fun findSegment(frames: IntArray, count: Int, frame: Float, hint: Int): Int {
            if (count == 0 || frame < frames[0]) return -1
            if (hint in 0 until count && frames[hint] <= frame) {
                if (hint + 1 >= count || frame < frames[hint + 1]) return hint
                if (hint + 2 >= count || frame < frames[hint + 2]) return hint + 1
            }
            var lo = 0
            var hi = count - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (frames[mid] <= frame) lo = mid else hi = mid - 1
            }
            return lo
        }
    }
}

/** 一根骨骼的关键帧轨道。 */
class BoneTrack(val name: String, count: Int) {
    /** 从 VMD 读进来时名字的原始字节（可能在半个字符处被截断）；导出时原样写回，保证名字不走样。 */
    var rawName: ByteArray? = null
    var count: Int = count
        private set
    var frames = IntArray(count)
        private set
    /** 每帧 7 个：tx,ty,tz, qx,qy,qz,qw。 */
    var values = FloatArray(count * 7)
        private set
    /** 每帧 16 个字节：X(x1,y1,x2,y2) Y(...) Z(...) R(...)，取值 0..127。补间曲线属于"到达该帧"的那一段。 */
    var interp = ByteArray(count * 16)
        private set

    fun indexOf(frame: Int): Int = Motion.indexOfFrame(frames, count, frame)

    /**
     * 在 frame 处写入一个关键帧（已有则覆盖）。
     * @param curve 16 字节补间曲线；null = 新建的帧用线性，覆盖的帧保留原来的曲线
     * @return 该关键帧的序号
     */
    fun set(frame: Int, tx: Float, ty: Float, tz: Float, qx: Float, qy: Float, qz: Float, qw: Float, curve: ByteArray? = null, curveOffset: Int = 0): Int {
        var i = indexOf(frame)
        if (i < 0) {
            i = -(i + 1)
            val n = count
            if (n + 1 > frames.size) {
                val cap = maxOf(4, n * 2)
                frames = frames.copyOf(cap); values = values.copyOf(cap * 7); interp = interp.copyOf(cap * 16)
            }
            System.arraycopy(frames, i, frames, i + 1, n - i)
            System.arraycopy(values, i * 7, values, (i + 1) * 7, (n - i) * 7)
            System.arraycopy(interp, i * 16, interp, (i + 1) * 16, (n - i) * 16)
            frames[i] = frame
            Motion.linearInterp(interp, i * 16, 4)
            count = n + 1
        }
        val o = i * 7
        val v = values
        v[o] = tx; v[o + 1] = ty; v[o + 2] = tz
        var l = kotlin.math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        if (l < 1e-9f || l != l) { v[o + 3] = 0f; v[o + 4] = 0f; v[o + 5] = 0f; v[o + 6] = 1f }
        else { l = 1f / l; v[o + 3] = qx * l; v[o + 4] = qy * l; v[o + 5] = qz * l; v[o + 6] = qw * l }
        if (curve != null) for (k in 0 until 16) interp[i * 16 + k] = curve[curveOffset + k].toInt().coerceIn(0, 127).toByte()
        return i
    }

    fun removeAt(i: Int) {
        val n = count
        if (i < 0 || i >= n) return
        System.arraycopy(frames, i + 1, frames, i, n - i - 1)
        System.arraycopy(values, (i + 1) * 7, values, i * 7, (n - i - 1) * 7)
        System.arraycopy(interp, (i + 1) * 16, interp, i * 16, (n - i - 1) * 16)
        count = n - 1
    }

    fun remove(frame: Int): Boolean {
        val i = indexOf(frame)
        if (i < 0) return false
        removeAt(i)
        return true
    }

    fun clear() { count = 0 }

    fun copy(): BoneTrack {
        val t = BoneTrack(name, count)
        t.rawName = rawName
        System.arraycopy(frames, 0, t.frames, 0, count)
        System.arraycopy(values, 0, t.values, 0, count * 7)
        System.arraycopy(interp, 0, t.interp, 0, count * 16)
        return t
    }

    /**
     * 求 frame 时刻的平移与旋转，写到 outT[to..to+2]、outR[ro..ro+3]。
     * @return 新的游标（传给下一次调用的 hint）
     */
    fun sample(frame: Float, hint: Int, outT: FloatArray, to: Int, outR: FloatArray, ro: Int): Int {
        val n = count
        if (n == 0) return 0
        val i = Motion.findSegment(frames, n, frame, hint)
        val v = values
        if (i < 0 || i >= n - 1) {
            val o = (if (i < 0) 0 else n - 1) * 7
            outT[to] = v[o]; outT[to + 1] = v[o + 1]; outT[to + 2] = v[o + 2]
            outR[ro] = v[o + 3]; outR[ro + 1] = v[o + 4]; outR[ro + 2] = v[o + 5]; outR[ro + 3] = v[o + 6]
            return if (i < 0) 0 else i
        }
        val f0 = frames[i]
        val f1 = frames[i + 1]
        val t = (frame - f0) / (f1 - f0).toFloat()
        val o0 = i * 7
        val o1 = o0 + 7
        val ip = (i + 1) * 16
        val b = interp
        val k = 1f / 127f
        val wx = Bezier.eval(b[ip] * k, b[ip + 1] * k, b[ip + 2] * k, b[ip + 3] * k, t)
        val wy = Bezier.eval(b[ip + 4] * k, b[ip + 5] * k, b[ip + 6] * k, b[ip + 7] * k, t)
        val wz = Bezier.eval(b[ip + 8] * k, b[ip + 9] * k, b[ip + 10] * k, b[ip + 11] * k, t)
        val wr = Bezier.eval(b[ip + 12] * k, b[ip + 13] * k, b[ip + 14] * k, b[ip + 15] * k, t)
        outT[to] = v[o0] + (v[o1] - v[o0]) * wx
        outT[to + 1] = v[o0 + 1] + (v[o1 + 1] - v[o0 + 1]) * wy
        outT[to + 2] = v[o0 + 2] + (v[o1 + 2] - v[o0 + 2]) * wz
        Quat.slerp(outR, ro, v, o0 + 3, v, o1 + 3, wr)
        return i
    }
}

/** 表情权重轨道（线性补间）。 */
class MorphTrack(val name: String, count: Int) {
    var rawName: ByteArray? = null
    var count: Int = count
        private set
    var frames = IntArray(count)
        private set
    var weights = FloatArray(count)
        private set

    fun indexOf(frame: Int): Int = Motion.indexOfFrame(frames, count, frame)

    fun set(frame: Int, weight: Float): Int {
        var i = indexOf(frame)
        if (i < 0) {
            i = -(i + 1)
            val n = count
            if (n + 1 > frames.size) { val cap = maxOf(4, n * 2); frames = frames.copyOf(cap); weights = weights.copyOf(cap) }
            System.arraycopy(frames, i, frames, i + 1, n - i)
            System.arraycopy(weights, i, weights, i + 1, n - i)
            frames[i] = frame
            count = n + 1
        }
        weights[i] = if (weight != weight) 0f else weight
        return i
    }

    fun removeAt(i: Int) {
        val n = count
        if (i < 0 || i >= n) return
        System.arraycopy(frames, i + 1, frames, i, n - i - 1)
        System.arraycopy(weights, i + 1, weights, i, n - i - 1)
        count = n - 1
    }

    fun remove(frame: Int): Boolean {
        val i = indexOf(frame)
        if (i < 0) return false
        removeAt(i)
        return true
    }

    fun clear() { count = 0 }

    fun copy(): MorphTrack {
        val t = MorphTrack(name, count)
        t.rawName = rawName
        System.arraycopy(frames, 0, t.frames, 0, count)
        System.arraycopy(weights, 0, t.weights, 0, count)
        return t
    }

    fun sample(frame: Float, hint: Int, out: FloatArray, oo: Int): Int {
        val n = count
        if (n == 0) return 0
        val i = Motion.findSegment(frames, n, frame, hint)
        if (i < 0) { out[oo] = weights[0]; return 0 }
        if (i >= n - 1) { out[oo] = weights[n - 1]; return i }
        val f0 = frames[i]
        val f1 = frames[i + 1]
        val t = (frame - f0) / (f1 - f0).toFloat()
        out[oo] = weights[i] + (weights[i + 1] - weights[i]) * t
        return i
    }
}

/** 某个 IK 骨的开/关轨道（阶跃，不补间）。 */
class IkTrack(val name: String, count: Int) {
    var count: Int = count
        private set
    var frames = IntArray(count)
        private set
    var enabled = BooleanArray(count)
        private set

    fun indexOf(frame: Int): Int = Motion.indexOfFrame(frames, count, frame)

    fun set(frame: Int, on: Boolean): Int {
        var i = indexOf(frame)
        if (i < 0) {
            i = -(i + 1)
            val n = count
            if (n + 1 > frames.size) { val cap = maxOf(4, n * 2); frames = frames.copyOf(cap); enabled = enabled.copyOf(cap) }
            System.arraycopy(frames, i, frames, i + 1, n - i)
            System.arraycopy(enabled, i, enabled, i + 1, n - i)
            frames[i] = frame
            count = n + 1
        }
        enabled[i] = on
        return i
    }

    fun remove(frame: Int): Boolean {
        val i = indexOf(frame)
        if (i < 0) return false
        val n = count
        System.arraycopy(frames, i + 1, frames, i, n - i - 1)
        System.arraycopy(enabled, i + 1, enabled, i, n - i - 1)
        count = n - 1
        return true
    }

    fun copy(): IkTrack {
        val t = IkTrack(name, count)
        System.arraycopy(frames, 0, t.frames, 0, count)
        System.arraycopy(enabled, 0, t.enabled, 0, count)
        return t
    }

    fun sample(frame: Float, hint: Int): Boolean {
        if (count == 0) return true
        val i = Motion.findSegment(frames, count, frame, hint)
        return if (i < 0) enabled[0] else enabled[i]
    }
}

/** 模型显示/隐藏轨道。 */
class VisibilityTrack(val count: Int) {
    val frames = IntArray(count)
    val visible = BooleanArray(count)
    fun copy(): VisibilityTrack {
        val t = VisibilityTrack(count)
        System.arraycopy(frames, 0, t.frames, 0, count)
        System.arraycopy(visible, 0, t.visible, 0, count)
        return t
    }

    fun sample(frame: Float): Boolean {
        if (count == 0) return true
        val i = Motion.findSegment(frames, count, frame, 0)
        return if (i < 0) visible[0] else visible[i]
    }
}

/** 镜头轨道。 */
class CameraTrack(count: Int) {
    var count: Int = count
        private set
    var frames = IntArray(count)
        private set
    /** 每帧 8 个：distance, px,py,pz, rx,ry,rz, fov(度)。 */
    var values = FloatArray(count * 8)
        private set
    /** 每帧 24 字节：X,Y,Z,R,距离,视角 各 (x1,x2,y1,y2)。 */
    var interp = ByteArray(count * 24)
        private set
    var perspective = BooleanArray(count)
        private set
    private var cursor = 0

    fun indexOf(frame: Int): Int = Motion.indexOfFrame(frames, count, frame)

    /** 写入一个镜头关键帧。src[so..so+7] = distance, px,py,pz, rx,ry,rz, fov。 */
    fun set(frame: Int, src: FloatArray, so: Int, persp: Boolean, curve: ByteArray? = null, curveOffset: Int = 0): Int {
        var i = indexOf(frame)
        if (i < 0) {
            i = -(i + 1)
            val n = count
            if (n + 1 > frames.size) {
                val cap = maxOf(4, n * 2)
                frames = frames.copyOf(cap); values = values.copyOf(cap * 8); interp = interp.copyOf(cap * 24); perspective = perspective.copyOf(cap)
            }
            System.arraycopy(frames, i, frames, i + 1, n - i)
            System.arraycopy(values, i * 8, values, (i + 1) * 8, (n - i) * 8)
            System.arraycopy(interp, i * 24, interp, (i + 1) * 24, (n - i) * 24)
            System.arraycopy(perspective, i, perspective, i + 1, n - i)
            frames[i] = frame
            // 镜头曲线的字节顺序是 x1,x2,y1,y2
            for (c in 0 until 6) { interp[i * 24 + c * 4] = 20; interp[i * 24 + c * 4 + 1] = 107; interp[i * 24 + c * 4 + 2] = 20; interp[i * 24 + c * 4 + 3] = 107 }
            count = n + 1
        }
        System.arraycopy(src, so, values, i * 8, 8)
        perspective[i] = persp
        if (curve != null) for (k in 0 until 24) interp[i * 24 + k] = curve[curveOffset + k].toInt().coerceIn(0, 127).toByte()
        cursor = 0
        return i
    }

    fun removeAt(i: Int) {
        val n = count
        if (i < 0 || i >= n) return
        System.arraycopy(frames, i + 1, frames, i, n - i - 1)
        System.arraycopy(values, (i + 1) * 8, values, i * 8, (n - i - 1) * 8)
        System.arraycopy(interp, (i + 1) * 24, interp, i * 24, (n - i - 1) * 24)
        System.arraycopy(perspective, i + 1, perspective, i, n - i - 1)
        count = n - 1
        cursor = 0
    }

    fun remove(frame: Int): Boolean {
        val i = indexOf(frame)
        if (i < 0) return false
        removeAt(i)
        return true
    }

    fun clear() { count = 0; cursor = 0 }

    fun copy(): CameraTrack {
        val t = CameraTrack(count)
        System.arraycopy(frames, 0, t.frames, 0, count)
        System.arraycopy(values, 0, t.values, 0, count * 8)
        System.arraycopy(interp, 0, t.interp, 0, count * 24)
        System.arraycopy(perspective, 0, t.perspective, 0, count)
        return t
    }

    /** out: distance, px,py,pz, rx,ry,rz, fov；返回是否透视。 */
    fun sample(frame: Float, out: FloatArray): Boolean {
        val n = count
        if (n == 0) return true
        val i = Motion.findSegment(frames, n, frame, cursor)
        val v = values
        if (i < 0 || i >= n - 1) {
            val k = if (i < 0) 0 else n - 1
            System.arraycopy(v, k * 8, out, 0, 8)
            cursor = k
            return perspective[k]
        }
        cursor = i
        val f0 = frames[i]
        val f1 = frames[i + 1]
        val o0 = i * 8
        val o1 = o0 + 8
        // MMD 的习惯：相邻两帧（只差 1 帧）是"切镜头"，不做补间
        if (f1 - f0 <= 1) {
            System.arraycopy(v, o0, out, 0, 8)
            return perspective[i]
        }
        val t = (frame - f0) / (f1 - f0).toFloat()
        val ip = (i + 1) * 24
        val b = interp
        val k = 1f / 127f
        val wx = Bezier.eval(b[ip] * k, b[ip + 2] * k, b[ip + 1] * k, b[ip + 3] * k, t)
        val wy = Bezier.eval(b[ip + 4] * k, b[ip + 6] * k, b[ip + 5] * k, b[ip + 7] * k, t)
        val wz = Bezier.eval(b[ip + 8] * k, b[ip + 10] * k, b[ip + 9] * k, b[ip + 11] * k, t)
        val wr = Bezier.eval(b[ip + 12] * k, b[ip + 14] * k, b[ip + 13] * k, b[ip + 15] * k, t)
        val wd = Bezier.eval(b[ip + 16] * k, b[ip + 18] * k, b[ip + 17] * k, b[ip + 19] * k, t)
        val wv = Bezier.eval(b[ip + 20] * k, b[ip + 22] * k, b[ip + 21] * k, b[ip + 23] * k, t)
        out[0] = v[o0] + (v[o1] - v[o0]) * wd
        out[1] = v[o0 + 1] + (v[o1 + 1] - v[o0 + 1]) * wx
        out[2] = v[o0 + 2] + (v[o1 + 2] - v[o0 + 2]) * wy
        out[3] = v[o0 + 3] + (v[o1 + 3] - v[o0 + 3]) * wz
        out[4] = v[o0 + 4] + (v[o1 + 4] - v[o0 + 4]) * wr
        out[5] = v[o0 + 5] + (v[o1 + 5] - v[o0 + 5]) * wr
        out[6] = v[o0 + 6] + (v[o1 + 6] - v[o0 + 6]) * wr
        out[7] = v[o0 + 7] + (v[o1 + 7] - v[o0 + 7]) * wv
        return perspective[i]
    }
}

/** 光照轨道（线性补间）。 */
class LightTrack(count: Int) {
    var count: Int = count
        private set
    var frames = IntArray(count)
        private set
    /** 每帧 6 个：r,g,b, dx,dy,dz（光线方向）。 */
    var values = FloatArray(count * 6)
        private set
    private var cursor = 0

    fun indexOf(frame: Int): Int = Motion.indexOfFrame(frames, count, frame)

    fun set(frame: Int, src: FloatArray, so: Int): Int {
        var i = indexOf(frame)
        if (i < 0) {
            i = -(i + 1)
            val n = count
            if (n + 1 > frames.size) { val cap = maxOf(4, n * 2); frames = frames.copyOf(cap); values = values.copyOf(cap * 6) }
            System.arraycopy(frames, i, frames, i + 1, n - i)
            System.arraycopy(values, i * 6, values, (i + 1) * 6, (n - i) * 6)
            frames[i] = frame
            count = n + 1
        }
        System.arraycopy(src, so, values, i * 6, 6)
        cursor = 0
        return i
    }

    fun remove(frame: Int): Boolean {
        val i = indexOf(frame)
        if (i < 0) return false
        val n = count
        System.arraycopy(frames, i + 1, frames, i, n - i - 1)
        System.arraycopy(values, (i + 1) * 6, values, i * 6, (n - i - 1) * 6)
        count = n - 1
        cursor = 0
        return true
    }

    fun copy(): LightTrack {
        val t = LightTrack(count)
        System.arraycopy(frames, 0, t.frames, 0, count)
        System.arraycopy(values, 0, t.values, 0, count * 6)
        return t
    }

    fun sample(frame: Float, out: FloatArray) {
        val n = count
        if (n == 0) return
        val i = Motion.findSegment(frames, n, frame, cursor)
        val v = values
        if (i < 0 || i >= n - 1) {
            val k = if (i < 0) 0 else n - 1
            System.arraycopy(v, k * 6, out, 0, 6)
            cursor = k
            return
        }
        cursor = i
        val t = (frame - frames[i]) / (frames[i + 1] - frames[i]).toFloat()
        val o0 = i * 6
        for (k in 0 until 6) out[k] = v[o0 + k] + (v[o0 + 6 + k] - v[o0 + k]) * t
    }
}

class SelfShadowTrack(val count: Int) {
    val frames = IntArray(count)
    val modes = ByteArray(count)
    val distances = FloatArray(count)
    fun copy(): SelfShadowTrack {
        val t = SelfShadowTrack(count)
        System.arraycopy(frames, 0, t.frames, 0, count)
        System.arraycopy(modes, 0, t.modes, 0, count)
        System.arraycopy(distances, 0, t.distances, 0, count)
        return t
    }
}

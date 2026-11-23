package com.lumi.mmd.engine.scene

import com.lumi.mmd.engine.anim.BoneTrack
import com.lumi.mmd.engine.anim.Motion
import com.lumi.mmd.engine.math.Quat

/**
 * 关键帧编辑（做动画）：把骨骼 / 表情 / 镜头当前的状态登记成关键帧，删除、复制、粘贴、移动关键帧，改补间曲线。
 *
 * 和 MMD 一样的思路：先把骨骼摆成想要的样子（"手动姿势"层，叠加在动作之上），再"登记"到当前帧；
 * 登记时把 动作 + 手动姿势 合成写进轨道，并清掉这根骨骼的手动姿势——登记前后看到的姿势完全一样。
 *
 * 帧号都是场景时间轴上的整数帧（30fps）。只在渲染线程上调用。
 */
object KeyOps {
    private val tt = FloatArray(3)
    private val tr = FloatArray(4)
    private val tq = FloatArray(4)

    private fun localFrame(m: ModelInstance, frame: Int): Int = maxOf(0, frame - Math.round(m.motionOffset))

    private fun afterEdit(m: ModelInstance, scene: Scene?, rebind: Boolean) {
        val mo = m.motion?.motion ?: return
        mo.recomputeMaxFrame()
        if (rebind) m.rebindMotion()
        m.poseDirty = true
        m.motionRevision++
        scene?.recomputeDuration()
    }

    // ------------------------------------------------------------------ 骨骼

    /** 骨骼在 frame 处"动作 + 手动姿势"合成后的值 → outT[0..2]、outR[0..3]。 */
    fun bonePose(m: ModelInstance, bone: Int, frame: Int, outT: FloatArray, outR: FloatArray) {
        m.selectClip(frame)
        val sk = m.skeleton ?: return
        val track = m.motion?.trackOfBone(bone)
        tt.fill(0f); Quat.identity(tr, 0)
        if (track != null && track.count > 0) track.sample(localFrame(m, frame).toFloat(), 0, tt, 0, tr, 0)
        outT[0] = tt[0] + sk.userT[bone * 3]; outT[1] = tt[1] + sk.userT[bone * 3 + 1]; outT[2] = tt[2] + sk.userT[bone * 3 + 2]
        Quat.mul(outR, 0, sk.userR, bone * 4, tr, 0)
        Quat.normalize(outR, 0)
    }

    private fun registerBoneNoFinish(m: ModelInstance, bone: Int, frame: Int): Boolean {
        val sk = m.skeleton ?: return false
        if (bone !in 0 until sk.count) return false
        val motion = m.editableMotion()
        var track = m.motion?.trackOfBone(bone)
        var created = false
        if (track == null) { track = motion.boneTrack(sk.bones[bone].name); created = true }
        val oT = FloatArray(3); val oR = FloatArray(4)
        // 新建的轨道还没绑定：trackOfBone 查不到它，但它是空的，采样结果就是初始姿势
        bonePose(m, bone, frame, oT, oR)
        track.set(localFrame(m, frame), oT[0], oT[1], oT[2], oR[0], oR[1], oR[2], oR[3])
        sk.userT[bone * 3] = 0f; sk.userT[bone * 3 + 1] = 0f; sk.userT[bone * 3 + 2] = 0f
        Quat.identity(sk.userR, bone * 4)
        return created
    }

    /** 把一根骨骼当前的姿势登记到 frame。 */
    fun registerBone(m: ModelInstance, scene: Scene?, bone: Int, frame: Int): Boolean {
        m.selectClip(frame)
        val sk = m.skeleton ?: return false
        if (bone !in 0 until sk.count) return false
        val created = registerBoneNoFinish(m, bone, frame)
        afterEdit(m, scene, created)
        return true
    }

    /** 有手动姿势（还没登记）的骨骼。 */
    fun posedBones(m: ModelInstance): IntArray {
        val sk = m.skeleton ?: return IntArray(0)
        val out = ArrayList<Int>()
        for (b in 0 until sk.count) {
            if (sk.userT[b * 3] != 0f || sk.userT[b * 3 + 1] != 0f || sk.userT[b * 3 + 2] != 0f || !Quat.isIdentity(sk.userR, b * 4)) out.add(b)
        }
        return out.toIntArray()
    }

    /**
     * 登记所有摆过姿势的骨骼，外加 extra 指定的骨骼（通常是当前选中的那根）。
     * @return 登记了多少根
     */
    fun registerPosed(m: ModelInstance, scene: Scene?, frame: Int, extra: Int = -1): Int {
        m.selectClip(frame)
        val sk = m.skeleton ?: return 0
        val bones = posedBones(m).toMutableList()
        if (extra in 0 until sk.count && extra !in bones) bones.add(extra)
        if (bones.isEmpty()) return 0
        var created = false
        for (b in bones) if (registerBoneNoFinish(m, b, frame)) created = true
        afterEdit(m, scene, created)
        return bones.size
    }

    /** 直接写一个骨骼关键帧（MCP / 脚本用）：平移 + 旋转四元数，不看当前姿势。 */
    fun setBoneKey(m: ModelInstance, scene: Scene?, bone: Int, frame: Int, tx: Float, ty: Float, tz: Float, qx: Float, qy: Float, qz: Float, qw: Float, curve: ByteArray? = null): Boolean {
        m.selectClip(frame)
        val sk = m.skeleton ?: return false
        if (bone !in 0 until sk.count) return false
        val motion = m.editableMotion()
        var track = m.motion?.trackOfBone(bone)
        var created = false
        if (track == null) { track = motion.boneTrack(sk.bones[bone].name); created = true }
        track.set(localFrame(m, frame), tx, ty, tz, qx, qy, qz, qw, curve)
        afterEdit(m, scene, created)
        return true
    }

    fun deleteBoneKey(m: ModelInstance, scene: Scene?, bone: Int, frame: Int): Boolean {
        m.selectClip(frame)
        if (m.motion?.trackOfBone(bone)?.let { it.indexOf(localFrame(m, frame)) >= 0 } != true) return false
        // 共享的动作会先复制一份，所以要重新取轨道
        m.editableMotion()
        val ok = m.motion?.trackOfBone(bone)?.remove(localFrame(m, frame)) ?: false
        afterEdit(m, scene, false)
        return ok
    }

    fun hasBoneKey(m: ModelInstance, bone: Int, frame: Int): Boolean {
        m.selectClip(frame)
        val track = m.motion?.trackOfBone(bone) ?: return false
        return track.indexOf(localFrame(m, frame)) >= 0
    }

    // ------------------------------------------------------------------ 表情

    /** 表情在 frame 处的权重（动作 + 手动）。 */
    fun morphWeight(m: ModelInstance, morph: Int, frame: Int): Float {
        m.selectClip(frame)
        val track = m.motion?.trackOfMorph(morph)
        var w = 0f
        if (track != null && track.count > 0) { track.sample(localFrame(m, frame).toFloat(), 0, tt, 0); w = tt[0] }
        return w + m.morphs.userWeights[morph]
    }

    fun registerMorph(m: ModelInstance, scene: Scene?, morph: Int, frame: Int): Boolean {
        m.selectClip(frame)
        if (morph !in 0 until m.morphs.count) return false
        val w = morphWeight(m, morph, frame)
        return setMorphKey(m, scene, morph, frame, w)
    }

    fun setMorphKey(m: ModelInstance, scene: Scene?, morph: Int, frame: Int, weight: Float): Boolean {
        m.selectClip(frame)
        if (morph !in 0 until m.morphs.count) return false
        val motion = m.editableMotion()
        var track = m.motion?.trackOfMorph(morph)
        var created = false
        if (track == null) { track = motion.morphTrack(m.data.morphs[morph].name); created = true }
        track.set(localFrame(m, frame), weight)
        m.morphs.userWeights[morph] = 0f
        afterEdit(m, scene, created)
        return true
    }

    /** 登记所有手动调过的表情。 */
    fun registerPosedMorphs(m: ModelInstance, scene: Scene?, frame: Int): Int {
        m.selectClip(frame)
        var n = 0
        for (i in 0 until m.morphs.count) if (m.morphs.userWeights[i] != 0f) { registerMorph(m, scene, i, frame); n++ }
        return n
    }

    fun deleteMorphKey(m: ModelInstance, scene: Scene?, morph: Int, frame: Int): Boolean {
        m.selectClip(frame)
        if (m.motion?.trackOfMorph(morph)?.let { it.indexOf(localFrame(m, frame)) >= 0 } != true) return false
        m.editableMotion()
        val ok = m.motion?.trackOfMorph(morph)?.remove(localFrame(m, frame)) ?: false
        afterEdit(m, scene, false)
        return ok
    }

    // ------------------------------------------------------------------ 整帧操作

    /** 删除 frame 处的所有关键帧（骨骼 + 表情）。bones 非空时只删这些骨骼的。 */
    fun deleteFrame(m: ModelInstance, scene: Scene?, frame: Int, bones: IntArray? = null): Int {
        m.selectClip(frame)
        if (m.motion == null) return 0
        m.editableMotion()
        val b = m.motion ?: return 0
        val f = localFrame(m, frame)
        var n = 0
        if (bones != null) {
            for (bone in bones) if (b.trackOfBone(bone)?.remove(f) == true) n++
        } else {
            for (t in b.motion.boneTracks.values) if (t.remove(f)) n++
            for (t in b.motion.morphTracks.values) if (t.remove(f)) n++
        }
        afterEdit(m, scene, false)
        return n
    }

    /** 删除 [from, to] 区间里的关键帧。 */
    fun deleteRange(m: ModelInstance, scene: Scene?, from: Int, to: Int): Int {
        m.selectClip(from)
        if (m.motion == null) return 0
        m.editableMotion()
        val mo = m.motion?.motion ?: return 0
        val a = localFrame(m, minOf(from, to)); val z = localFrame(m, maxOf(from, to))
        var n = 0
        for (t in mo.boneTracks.values) { var i = t.count - 1; while (i >= 0) { if (t.frames[i] in a..z) { t.removeAt(i); n++ }; i-- } }
        for (t in mo.morphTracks.values) { var i = t.count - 1; while (i >= 0) { if (t.frames[i] in a..z) { t.removeAt(i); n++ }; i-- } }
        afterEdit(m, scene, false)
        return n
    }

    /**
     * 从 from 帧开始，把后面所有关键帧整体平移 delta 帧（正 = 插入空白，负 = 删掉一段时间）。
     * 往前移时落到 from 之前的关键帧会被丢弃。
     */
    fun shiftKeys(m: ModelInstance, scene: Scene?, from: Int, delta: Int): Int {
        m.selectClip(from)
        if (m.motion == null || delta == 0) return 0
        m.editableMotion()
        val mo = m.motion?.motion ?: return 0
        val a = localFrame(m, from)
        var n = 0
        for (t in mo.boneTracks.values.toList()) {
            val nt = BoneTrack(t.name, 0)
            nt.rawName = t.rawName
            for (i in 0 until t.count) {
                var f = t.frames[i]
                if (f >= a) { f += delta; if (f < a) continue; n++ }
                val o = i * 7
                nt.set(f, t.values[o], t.values[o + 1], t.values[o + 2], t.values[o + 3], t.values[o + 4], t.values[o + 5], t.values[o + 6], t.interp, i * 16)
            }
            mo.boneTracks[t.name] = nt
        }
        for (t in mo.morphTracks.values.toList()) {
            val nt = com.lumi.mmd.engine.anim.MorphTrack(t.name, 0)
            nt.rawName = t.rawName
            for (i in 0 until t.count) {
                var f = t.frames[i]
                if (f >= a) { f += delta; if (f < a) continue; n++ }
                nt.set(f, t.weights[i])
            }
            mo.morphTracks[t.name] = nt
        }
        afterEdit(m, scene, true)
        return n
    }

    /** 清空整段动作的关键帧。 */
    fun clearAll(m: ModelInstance, scene: Scene?) {
        m.setMotion(null)
        m.motionSources = emptyList()
        m.motionRevision++
        scene?.recomputeDuration()
    }

    // ------------------------------------------------------------------ 复制 / 粘贴

    /** 一帧的姿势（剪贴板）。 */
    class Clip {
        val bones = LinkedHashMap<String, FloatArray>()      // 名字 → tx,ty,tz, qx,qy,qz,qw
        val curves = HashMap<String, ByteArray>()
        val morphs = LinkedHashMap<String, Float>()
        val isEmpty: Boolean get() = bones.isEmpty() && morphs.isEmpty()
        val size: Int get() = bones.size + morphs.size
    }

    /**
     * 复制 frame 处的关键帧。
     * @param bones 只复制这些骨骼（null = 这一帧上所有有关键帧的骨骼和表情）
     * @param pose true = 即使这一帧没有关键帧也复制（取补间出来的当前姿势，含手动姿势）
     */
    fun copy(m: ModelInstance, frame: Int, bones: IntArray? = null, pose: Boolean = false): Clip {
        m.selectClip(frame)
        val clip = Clip()
        val sk = m.skeleton
        val b = m.motion
        val f = localFrame(m, frame)
        val oT = FloatArray(3); val oR = FloatArray(4)
        if (sk != null) {
            val list = bones ?: IntArray(sk.count) { it }
            for (bone in list) {
                if (bone !in 0 until sk.count) continue
                val track = b?.trackOfBone(bone)
                val ki = track?.indexOf(f) ?: -1
                if (ki < 0 && !pose) continue
                bonePose(m, bone, frame, oT, oR)
                clip.bones[sk.bones[bone].name] = floatArrayOf(oT[0], oT[1], oT[2], oR[0], oR[1], oR[2], oR[3])
                if (ki >= 0 && track != null) clip.curves[sk.bones[bone].name] = track.interp.copyOfRange(ki * 16, ki * 16 + 16)
            }
        }
        if (bones == null) {
            for (i in 0 until m.morphs.count) {
                val track = b?.trackOfMorph(i)
                val has = track != null && track.indexOf(f) >= 0
                if (!has && !(pose && m.morphs.userWeights[i] != 0f)) continue
                clip.morphs[m.data.morphs[i].name] = morphWeight(m, i, frame)
            }
        }
        return clip
    }

    private fun mirrorName(n: String): String = when {
        n.startsWith("左") -> "右" + n.substring(1)
        n.startsWith("右") -> "左" + n.substring(1)
        else -> n
    }

    /**
     * 粘贴到 frame。
     * @param mirror 左右反转粘贴（左手的姿势贴到右手上，并把姿势镜像）
     * @return 写入的关键帧数
     */
    fun paste(m: ModelInstance, scene: Scene?, clip: Clip, frame: Int, mirror: Boolean = false): Int {
        m.selectClip(frame)
        if (clip.isEmpty) return 0
        val sk = m.skeleton
        val motion = m.editableMotion()
        val f = localFrame(m, frame)
        var n = 0
        var created = false
        if (sk != null) {
            val byName = HashMap<String, Int>()
            for (i in sk.bones.indices.reversed()) byName[sk.bones[i].name] = i
            for ((name0, v) in clip.bones) {
                val name = if (mirror) mirrorName(name0) else name0
                val bone = byName[name] ?: continue
                var track = m.motion?.trackOfBone(bone)
                if (track == null) { track = motion.boneTracks[name] ?: motion.boneTrack(name).also { created = true } }
                if (mirror) track.set(f, -v[0], v[1], v[2], v[3], -v[4], -v[5], v[6], clip.curves[name0])
                else track.set(f, v[0], v[1], v[2], v[3], v[4], v[5], v[6], clip.curves[name0])
                sk.userT[bone * 3] = 0f; sk.userT[bone * 3 + 1] = 0f; sk.userT[bone * 3 + 2] = 0f
                Quat.identity(sk.userR, bone * 4)
                n++
            }
        }
        val morphByName = HashMap<String, Int>()
        for (i in 0 until m.morphs.count) morphByName.putIfAbsent(m.data.morphs[i].name, i)
        for ((name, w) in clip.morphs) {
            val mi = morphByName[name] ?: continue
            var track = m.motion?.trackOfMorph(mi)
            if (track == null) { track = motion.morphTracks[name] ?: motion.morphTrack(name).also { created = true } }
            track.set(f, w)
            m.morphs.userWeights[mi] = 0f
            n++
        }
        afterEdit(m, scene, created)
        return n
    }

    // ------------------------------------------------------------------ 补间曲线

    /**
     * 设置"到达 frame 这个关键帧"那一段的补间曲线。
     * @param channel 0=X 1=Y 2=Z 3=旋转，-1 = 全部
     * @param x1,y1,x2,y2 贝塞尔的两个控制点，取值 0..127（线性 = 20,20,107,107）
     */
    fun setBoneCurve(m: ModelInstance, bone: Int, frame: Int, channel: Int, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        m.selectClip(frame)
        if (m.motion?.trackOfBone(bone)?.let { it.indexOf(localFrame(m, frame)) >= 0 } != true) return false
        m.editableMotion()
        val track = m.motion?.trackOfBone(bone) ?: return false
        val i = track.indexOf(localFrame(m, frame))
        if (i < 0) return false
        val range = if (channel in 0..3) channel..channel else 0..3
        for (c in range) {
            val o = i * 16 + c * 4
            track.interp[o] = x1.coerceIn(0, 127).toByte(); track.interp[o + 1] = y1.coerceIn(0, 127).toByte()
            track.interp[o + 2] = x2.coerceIn(0, 127).toByte(); track.interp[o + 3] = y2.coerceIn(0, 127).toByte()
        }
        m.poseDirty = true
        m.motionRevision++
        return true
    }

    /** 读补间曲线 → out[0..3] = x1,y1,x2,y2；该帧没有关键帧时返回 false。 */
    fun getBoneCurve(m: ModelInstance, bone: Int, frame: Int, channel: Int, out: IntArray): Boolean {
        m.selectClip(frame)
        val track = m.motion?.trackOfBone(bone) ?: return false
        val i = track.indexOf(localFrame(m, frame))
        if (i < 0) return false
        val o = i * 16 + channel.coerceIn(0, 3) * 4
        for (k in 0 until 4) out[k] = track.interp[o + k].toInt()
        return true
    }

    // ------------------------------------------------------------------ 镜头

    private fun cameraValues(scene: Scene, out: FloatArray) {
        val c = scene.camera
        out[0] = -c.distance
        out[1] = c.target[0]; out[2] = c.target[1]; out[3] = c.target[2]
        out[4] = c.rotX; out[5] = c.rotY; out[6] = c.rotZ
        out[7] = c.fovDeg
    }

    /** 把当前镜头登记成 frame 处的镜头关键帧。 */
    fun registerCamera(scene: Scene, frame: Int): Boolean {
        scene.selectCameraClip(frame.toFloat())
        val motion = scene.cameraMotion ?: Motion().also { it.name = "镜头"; it.modelName = CAMERA_MODEL_NAME; scene.cameraMotion = it; scene.activeCameraClip?.owned = true }
        val v = FloatArray(8)
        cameraValues(scene, v)
        motion.cameraTrack().set(maxOf(0, frame - scene.cameraOffset), v, 0, scene.camera.perspective)
        motion.recomputeMaxFrame()
        scene.useCameraMotion = true
        scene.releaseCamera()
        scene.recomputeDuration()
        return true
    }

    fun setCameraKey(scene: Scene, frame: Int, distance: Float, tx: Float, ty: Float, tz: Float, rx: Float, ry: Float, rz: Float, fov: Float, perspective: Boolean = true): Boolean {
        scene.selectCameraClip(frame.toFloat())
        val motion = scene.cameraMotion ?: Motion().also { it.name = "镜头"; it.modelName = CAMERA_MODEL_NAME; scene.cameraMotion = it; scene.activeCameraClip?.owned = true }
        motion.cameraTrack().set(maxOf(0, frame - scene.cameraOffset), floatArrayOf(-distance, tx, ty, tz, rx, ry, rz, fov), 0, perspective)
        motion.recomputeMaxFrame()
        scene.useCameraMotion = true
        scene.releaseCamera()
        scene.recomputeDuration()
        return true
    }

    fun deleteCameraKey(scene: Scene, frame: Int): Boolean {
        scene.selectCameraClip(frame.toFloat())
        val motion = scene.cameraMotion ?: return false
        val track = motion.camera ?: return false
        if (!track.remove(frame - scene.cameraOffset)) return false
        motion.recomputeMaxFrame()
        scene.releaseCamera()
        scene.recomputeDuration()
        return true
    }

    fun cameraKeyFrames(scene: Scene): IntArray {
        val t = scene.cameraMotion?.camera ?: return IntArray(0)
        val off = scene.cameraOffset
        return IntArray(t.count) { t.frames[it] + off }
    }

    // ------------------------------------------------------------------ 时间轴数据

    /** 一行轨道：名字 + 关键帧所在的帧号（升序，已换算到场景时间轴）。 */
    class Row(val kind: Int, val index: Int, val name: String, val frames: IntArray)

    const val ROW_BONE = 0
    const val ROW_MORPH = 1
    const val ROW_CAMERA = 2
    const val CAMERA_MODEL_NAME = "カメラ・照明"

    /** 某根骨骼的关键帧帧号。 */
    fun boneKeyFrames(m: ModelInstance, bone: Int): IntArray {
        val t = m.motion?.trackOfBone(bone) ?: return IntArray(0)
        val off = Math.round(m.motionOffset)
        return IntArray(t.count) { t.frames[it] + off }
    }

    fun morphKeyFrames(m: ModelInstance, morph: Int): IntArray {
        val t = m.motion?.trackOfMorph(morph) ?: return IntArray(0)
        val off = Math.round(m.motionOffset)
        return IntArray(t.count) { t.frames[it] + off }
    }

    /** 模型所有有关键帧的轨道（骨骼按骨骼序号排，表情在后）。 */
    fun rows(m: ModelInstance): List<Row> {
        val b = m.motion ?: return emptyList()
        val out = ArrayList<Row>()
        val off = Math.round(m.motionOffset)
        val sk = m.skeleton
        if (sk != null) {
            val seen = BooleanArray(sk.count)
            val tmp = ArrayList<Row>()
            for (i in b.matchedBones - 1 downTo 0) {
                val bone = b.boneOfTrack(i)
                if (seen[bone]) continue
                seen[bone] = true
                val t = b.boneTrackAt(i)
                if (t.count == 0) continue
                tmp.add(Row(ROW_BONE, bone, sk.bones[bone].name, IntArray(t.count) { t.frames[it] + off }))
            }
            tmp.sortBy { it.index }
            out.addAll(tmp)
        }
        val seenM = BooleanArray(m.morphs.count)
        val tmp = ArrayList<Row>()
        for (i in b.matchedMorphs - 1 downTo 0) {
            val mi = b.morphOfTrack(i)
            if (seenM[mi]) continue
            seenM[mi] = true
            val t = b.morphTrackAt(i)
            if (t.count == 0) continue
            tmp.add(Row(ROW_MORPH, mi, m.data.morphs[mi].name, IntArray(t.count) { t.frames[it] + off }))
        }
        tmp.sortBy { it.index }
        out.addAll(tmp)
        return out
    }

    /** 上一个 / 下一个有关键帧的帧（任意轨道）。没有则返回 -1。 */
    fun neighbourKey(m: ModelInstance?, scene: Scene, frame: Int, forward: Boolean, bone: Int = -1): Int {
        var best = -1
        fun consider(frames: IntArray, count: Int, off: Int) {
            val f = frame - off
            val i = Motion.indexOfFrame(frames, count, f)
            val k = if (forward) (if (i >= 0) i + 1 else -(i + 1)) else (if (i >= 0) i - 1 else -(i + 1) - 1)
            if (k < 0 || k >= count) return
            val v = frames[k] + off
            if (best < 0 || (forward && v < best) || (!forward && v > best)) best = v
        }
        if (m != null) {
            val b = m.motion
            if (b != null) {
                val off = Math.round(m.motionOffset)
                if (bone >= 0) b.trackOfBone(bone)?.let { consider(it.frames, it.count, off) }
                else {
                    for (t in b.motion.boneTracks.values) consider(t.frames, t.count, off)
                    for (t in b.motion.morphTracks.values) consider(t.frames, t.count, off)
                }
            }
        }
        if (bone < 0) scene.cameraMotion?.camera?.let { consider(it.frames, it.count, scene.cameraOffset) }
        return best
    }
}

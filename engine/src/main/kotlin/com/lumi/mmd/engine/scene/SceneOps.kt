package com.lumi.mmd.engine.scene

import com.lumi.mmd.engine.format.PoseBone
import com.lumi.mmd.engine.math.Quat
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.ModelFormat
import com.lumi.mmd.engine.util.arr
import com.lumi.mmd.engine.util.bool
import com.lumi.mmd.engine.util.float
import com.lumi.mmd.engine.util.floats
import com.lumi.mmd.engine.util.int
import com.lumi.mmd.engine.util.jObj
import com.lumi.mmd.engine.util.jStr
import com.lumi.mmd.engine.util.obj
import com.lumi.mmd.engine.util.str
import kotlin.math.sqrt

/** 场景层面的一些小工具：猜模型类别、套用姿势、应用舞台灯光。 */
object SceneOps {
    private val stageWords = arrayOf("stage", "ステージ", "舞台", "场景", "場景", "背景", "room", "部屋", "房间", "スカイ", "sky", "dome", "ドーム", "floor", "床")

    /** 根据文件名与模型内容猜它是角色、舞台还是配件（用户可以再改）。 */
    fun guessKind(data: ModelData, fileName: String): Int {
        val lower = (fileName + " " + data.name).lowercase()
        val named = stageWords.any { lower.contains(it) }
        val sx = data.boundsMax[0] - data.boundsMin[0]
        val sy = data.boundsMax[1] - data.boundsMin[1]
        val sz = data.boundsMax[2] - data.boundsMin[2]
        val big = maxOf(sx, sz) * data.suggestedScale > 60f
        if (data.format.isMmd) {
            val hasIk = data.bones.any { it.ik != null }
            val humanoid = data.bones.any { it.name == "頭" || it.name == "上半身" || it.name == "センター" }
            if (humanoid && (hasIk || data.morphs.size > 5) && !named) return ModelInstance.KIND_CHARACTER
            if (named || big || data.bones.size <= 3) return if (big || named) ModelInstance.KIND_STAGE else ModelInstance.KIND_ACCESSORY
            return ModelInstance.KIND_CHARACTER
        }
        if (data.animations.isNotEmpty() && !big) return ModelInstance.KIND_CHARACTER
        if (data.isSkinned && !big && !named) return ModelInstance.KIND_CHARACTER
        if (named || big) return ModelInstance.KIND_STAGE
        // 比一个人小得多的东西当配件
        return if (maxOf(sx, sy, sz) * data.suggestedScale < 12f || data.format == ModelFormat.X) ModelInstance.KIND_ACCESSORY else ModelInstance.KIND_STAGE
    }

    /** 把 VPD 姿势写进模型的"用户姿势层"。返回匹配上的骨骼数。 */
    fun applyPose(model: ModelInstance, pose: List<PoseBone>): Int {
        val sk = model.skeleton ?: return 0
        val index = HashMap<String, Int>()
        for (i in sk.bones.indices.reversed()) index[sk.bones[i].name] = i
        sk.clearUserPose()
        var n = 0
        for (p in pose) {
            val b = index[p.name] ?: continue
            System.arraycopy(p.translation, 0, sk.userT, b * 3, 3)
            System.arraycopy(p.rotation, 0, sk.userR, b * 4, 4)
            Quat.normalize(sk.userR, b * 4)
            n++
        }
        sk.anyUserPose = n > 0
        model.poseDirty = true
        model.physics?.requestReset()
        return n
    }

    /** 用舞台文件自带的平行光（Blender 的"日光"）作为场景主光。返回是否应用了。 */
    fun applyStageLight(scene: Scene, data: ModelData): Boolean {
        val sun = data.lights.firstOrNull { it.type == 0 } ?: return false
        var x = sun.direction[0]; var y = sun.direction[1]; var z = sun.direction[2]
        val l = sqrt(x * x + y * y + z * z)
        if (l < 1e-6f) return false
        x /= l; y /= l; z /= l
        // 光从正下方往上照没有意义
        if (y > -0.05f) y = -0.05f
        scene.light.direction[0] = x; scene.light.direction[1] = y; scene.light.direction[2] = z
        val maxC = maxOf(sun.color[0], sun.color[1], sun.color[2], 1e-3f)
        for (k in 0 until 3) scene.light.color[k] = (sun.color[k] / maxC * 0.75f).coerceIn(0.2f, 1f)
        return true
    }
}

/** 工程文件（JSON）与场景对象之间的转换。模型本身不存进去，只存文件路径与各种调整。 */
object SceneIO {
    /** 一个片段的摆放信息（动作本身存在 sources 指向的文件里）。 */
    fun saveClip(c: com.lumi.mmd.engine.anim.MotionClip): Map<String, Any?> =
        linkedMapOf("name" to c.name, "sources" to ArrayList(c.sources), "start" to c.start, "in" to c.trimIn, "out" to c.trimOut, "fade" to c.fadeIn)

    /** 音乐轨道上的一段。frames = 保存时文件的长度（帧），打开工程时文件读不出长度就用它。 */
    fun saveAudioClip(c: AudioClip): Map<String, Any?> =
        linkedMapOf("file" to c.path, "name" to c.name, "start" to c.start, "in" to c.trimIn, "out" to c.trimOut, "frames" to c.sourceFrames)

    /**
     * @param order 存进工程的模型列表（挂载关系按它里面的序号记）；脚本临时创建的模型不在里面
     */
    fun saveModel(m: ModelInstance, scene: Scene, order: List<ModelInstance> = scene.models): Map<String, Any?> {
        val o = LinkedHashMap<String, Any?>()
        o["file"] = m.data.sourceFile?.path ?: ""
        o["name"] = m.name
        o["kind"] = m.kind
        o["visible"] = m.visible
        o["position"] = m.position.copyOf()
        o["rotation"] = m.rotation.copyOf()
        o["scale"] = m.scale
        o["physics"] = m.physicsEnabled
        o["castShadow"] = m.castShadow
        o["receiveShadow"] = m.receiveShadow
        o["edge"] = m.drawEdge
        o["edgeScale"] = m.edgeScale
        o["opacity"] = m.opacity
        o["layer"] = m.layer
        if (m.collision != ModelInstance.COLLISION_NONE) o["collision"] = ModelInstance.COLLISION_NAMES[m.collision]
        o["clips"] = m.clips.map { saveClip(it) }
        m.physicsTuning?.let { if (!it.isDefault) o["physicsTuning"] = it.toMap() }
        val parent = m.attachParent
        if (parent != null && order.contains(parent)) {
            o["attachTo"] = order.indexOf(parent)
            o["attachBone"] = parent.skeleton?.bones?.getOrNull(m.attachBone)?.name ?: ""
        }
        val hidden = ArrayList<Int>()
        for ((i, st) in m.materials.withIndex()) if (!st.visible) hidden.add(i)
        if (hidden.isNotEmpty()) o["hiddenMaterials"] = hidden
        if (m.partsInitialized) {
            val parts = ArrayList<Map<String, Any?>>()
            for (p in m.parts.editedParts()) {
                val po = linkedMapOf<String, Any?>("key" to p.key, "offset" to p.offset.copyOf(), "rotation" to p.rotation.copyOf(), "scale" to p.scale.copyOf(), "hidden" to p.hidden)
                if (p.deleted) po["deleted"] = true
                parts.add(po)
            }
            if (parts.isNotEmpty()) o["parts"] = parts
        }
        val morphs = LinkedHashMap<String, Any?>()
        for (i in 0 until m.morphs.count) { val w = m.morphs.userWeights[i]; if (w != 0f) morphs[m.data.morphs[i].name] = w }
        if (morphs.isNotEmpty()) o["morphs"] = morphs
        val sk = m.skeleton
        if (sk != null && sk.anyUserPose) {
            val pose = ArrayList<Map<String, Any?>>()
            for (b in 0 until sk.count) {
                val moved = sk.userT[b * 3] != 0f || sk.userT[b * 3 + 1] != 0f || sk.userT[b * 3 + 2] != 0f
                if (!moved && Quat.isIdentity(sk.userR, b * 4)) continue
                pose.add(linkedMapOf("bone" to sk.bones[b].name, "t" to sk.userT.copyOfRange(b * 3, b * 3 + 3), "r" to sk.userR.copyOfRange(b * 4, b * 4 + 4)))
            }
            if (pose.isNotEmpty()) o["pose"] = pose
        }
        return o
    }

    /** 把保存的调整恢复到刚加载好的模型上（挂载关系由调用方在所有模型都加载后处理）。 */
    fun restoreModel(m: ModelInstance, o: Map<String, Any?>) {
        o.str("name")?.let { if (it.isNotBlank()) m.name = it }
        m.kind = o.int("kind", m.kind).coerceIn(0, 2)
        m.visible = o.bool("visible", true)
        o.floats("position", 3)?.let { System.arraycopy(it, 0, m.position, 0, 3) }
        o.floats("rotation", 4)?.let { System.arraycopy(it, 0, m.rotation, 0, 4); Quat.normalize(m.rotation, 0) }
        m.scale = o.float("scale", m.scale).let { if (it > 0f && it == it) it else 1f }
        m.physicsEnabled = o.bool("physics", true)
        m.castShadow = o.bool("castShadow", true)
        m.receiveShadow = o.bool("receiveShadow", true)
        m.drawEdge = o.bool("edge", true)
        m.edgeScale = o.float("edgeScale", 1f)
        m.opacity = o.float("opacity", 1f).coerceIn(0f, 1f)
        m.layer = o.int("layer", 0).coerceIn(-100, 100)
        m.collision = ModelInstance.COLLISION_NAMES.indexOf(o["collision"] as? String ?: "none").coerceAtLeast(0)
        o["physicsTuning"].jObj()?.let { saved ->
            val t = com.lumi.mmd.engine.physics.PhysicsTuning()
            for ((k, v) in saved) when (v) { is Number -> t.set(k, v.toFloat()); is Boolean -> t.set(k, if (v) 1f else 0f); else -> {} }
            if (!t.isDefault) m.physicsTuning = t
        }
        for (h in o.arr("hiddenMaterials") ?: emptyList()) {
            val i = (h as? Number)?.toInt() ?: continue
            if (i in m.materials.indices) m.materials[i].visible = false
        }
        for (pj in o.arr("parts") ?: emptyList()) {
            val po = pj.jObj() ?: continue
            val part = m.parts.findByKey(po.str("key") ?: continue) ?: continue
            po.floats("offset", 3)?.let { System.arraycopy(it, 0, part.offset, 0, 3) }
            po.floats("rotation", 4)?.let { System.arraycopy(it, 0, part.rotation, 0, 4); Quat.normalize(part.rotation, 0) }
            po.floats("scale", 3)?.let { System.arraycopy(it, 0, part.scale, 0, 3) }
            part.hidden = po.bool("hidden")
            part.deleted = po.bool("deleted")
            if (part.deleted && part.island < 0) m.materials.getOrNull(part.material)?.visible = false
            m.parts.apply(part)
        }
        val morphs = o.obj("morphs")
        if (morphs != null) {
            for (i in 0 until m.morphs.count) {
                val w = (morphs[m.data.morphs[i].name] as? Number)?.toFloat() ?: continue
                m.morphs.userWeights[i] = w
            }
        }
        val sk = m.skeleton
        val pose = o.arr("pose")
        if (sk != null && pose != null) {
            val index = HashMap<String, Int>()
            for (i in sk.bones.indices.reversed()) index[sk.bones[i].name] = i
            for (pj in pose) {
                val po = pj.jObj() ?: continue
                val b = index[po["bone"].jStr() ?: continue] ?: continue
                po.floats("t", 3)?.let { System.arraycopy(it, 0, sk.userT, b * 3, 3) }
                po.floats("r", 4)?.let { System.arraycopy(it, 0, sk.userR, b * 4, 4); Quat.normalize(sk.userR, b * 4) }
                sk.anyUserPose = true
            }
        }
        m.poseDirty = true
    }

    fun saveCamera(c: Camera): Map<String, Any?> = linkedMapOf(
        "target" to c.target.copyOf(), "distance" to c.distance, "rot" to floatArrayOf(c.rotX, c.rotY, c.rotZ), "fov" to c.fovDeg, "perspective" to c.perspective)

    fun restoreCamera(c: Camera, o: Map<String, Any?>) {
        o.floats("target", 3)?.let { System.arraycopy(it, 0, c.target, 0, 3) }
        c.distance = o.float("distance", 45f).coerceIn(0.5f, 5000f)
        o.floats("rot", 3)?.let { c.rotX = it[0]; c.rotY = it[1]; c.rotZ = it[2] }
        c.fovDeg = o.float("fov", 30f).coerceIn(1f, 150f)
        c.perspective = o.bool("perspective", true)
    }

    fun saveLight(l: Light): Map<String, Any?> = linkedMapOf("direction" to l.direction.copyOf(), "color" to l.color.copyOf())

    fun restoreLight(l: Light, o: Map<String, Any?>) {
        o.floats("direction", 3)?.let { System.arraycopy(it, 0, l.direction, 0, 3) }
        o.floats("color", 3)?.let { System.arraycopy(it, 0, l.color, 0, 3) }
    }
}

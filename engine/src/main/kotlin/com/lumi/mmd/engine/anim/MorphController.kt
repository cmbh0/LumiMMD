package com.lumi.mmd.engine.anim

import com.lumi.mmd.engine.math.Quat
import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.MorphData

/**
 * 材质的运行时状态：基础值 + 材质表情的叠加结果。渲染器只读这里的数据。
 */
class MaterialState(val base: MaterialData) {
    val diffuse = FloatArray(4)
    val specular = FloatArray(3)
    var shininess = 0f
    val ambient = FloatArray(3)
    val edgeColor = FloatArray(4)
    var edgeSize = 0f
    /** 贴图 / sphere / toon 的乘算系数与加算系数（RGBA）。 */
    val texMul = FloatArray(4)
    val texAdd = FloatArray(4)
    val sphereMul = FloatArray(4)
    val sphereAdd = FloatArray(4)
    val toonMul = FloatArray(4)
    val toonAdd = FloatArray(4)
    /** 用户在"部件"面板里的显示开关。 */
    var visible = true

    // ---- 脚本对这块材质的改动（不受表情求值影响；脚本停掉时清回默认）----
    /** 透明度再乘上这个数。 */
    var fxAlpha = 1f
    var fxVisible = true
    /** 最终颜色乘上这个。 */
    val fxMul = floatArrayOf(1f, 1f, 1f)
    /** 最终颜色再加上这个（发光）。 */
    val fxAdd = floatArrayOf(0f, 0f, 0f)
    /** 往这个颜色混：rgb + 混多少。 */
    val fxTint = floatArrayOf(0f, 0f, 0f, 0f)
    /** 描边粗细的倍数。 */
    var fxEdge = 1f
    /** 脚本画的贴图（画布编号），代替这块材质原来的主贴图；0 = 用原来的。 */
    var fxCanvas = 0
    /** 脚本指定的光照方式（0 MMD / 1 普通 / 2 不受光）；-1 = 材质自己的。 */
    var fxShading = -1
    /** 脚本给这块材质单独指定的三渲二级数（0 = 这块不要三渲二）；-1 = 跟着全局。 */
    var fxCel = -1
    val hasFx: Boolean get() = fxAlpha != 1f || !fxVisible || fxEdge != 1f || fxTint[3] != 0f || fxShading >= 0 || fxCel >= 0 ||
        fxMul[0] != 1f || fxMul[1] != 1f || fxMul[2] != 1f || fxAdd[0] != 0f || fxAdd[1] != 0f || fxAdd[2] != 0f

    fun clearFx() {
        fxAlpha = 1f; fxVisible = true; fxEdge = 1f; fxCanvas = 0; fxShading = -1; fxCel = -1
        fxMul.fill(1f); fxAdd.fill(0f); fxTint.fill(0f)
    }

    init { reset() }

    fun reset() {
        System.arraycopy(base.diffuse, 0, diffuse, 0, 4)
        System.arraycopy(base.specular, 0, specular, 0, 3)
        shininess = base.shininess
        System.arraycopy(base.ambient, 0, ambient, 0, 3)
        System.arraycopy(base.edgeColor, 0, edgeColor, 0, 4)
        edgeSize = base.edgeSize
        texMul.fill(1f); texAdd.fill(0f)
        sphereMul.fill(1f); sphereAdd.fill(0f)
        toonMul.fill(1f); toonAdd.fill(0f)
    }
}

/**
 * 表情（モーフ）求值器：顶点 / UV / 骨骼 / 材质 / 组 / 翻转 表情。
 *
 * 顶点表情在 CPU 上做增量更新：只有权重发生变化时才重算，并且只碰"上一帧或这一帧激活的表情"涉及的顶点，
 * 同时记录脏区间，渲染器据此用 glBufferSubData 只上传变化的那一段。
 */
class MorphController(private val model: ModelData, private val skeleton: Skeleton?, val materials: Array<MaterialState>) {
    val count: Int = model.morphs.size
    private val morphs: List<MorphData> = model.morphs

    /** 动作数据给的权重（每帧由 MotionBinding 写入）。 */
    val animWeights = FloatArray(count)
    /** 用户用滑杆设定的权重（叠加在动作之上）。 */
    val userWeights = FloatArray(count)

    private val effective = FloatArray(count)
    private val lastVertexWeights = FloatArray(count)
    private val lastUvWeights = FloatArray(count)

    /** 未变形的基础顶点坐标（部件编辑后的结果），由 ModelInstance 提供；替换后需调用 [invalidatePositions]。 */
    var basePositions: FloatArray = model.positions
    /** 叠加表情之后的顶点坐标（上传给 GPU 的就是它）。 */
    val positions: FloatArray = model.positions.copyOf()
    /** 叠加 UV 表情之后的 UV；模型没有 UV 表情时就是原数组。 */
    val uvs: FloatArray

    val hasVertexMorphs: Boolean
    val hasUvMorphs: Boolean
    private val hasBoneMorphs: Boolean
    private val hasMaterialMorphs: Boolean
    private val hasGroupMorphs: Boolean

    /** 顶点坐标脏区间 [posDirtyMin, posDirtyMax]（顶点序号）；Max < Min 表示没有变化。 */
    var posDirtyMin = Int.MAX_VALUE
    var posDirtyMax = -1
    var uvDirtyMin = Int.MAX_VALUE
    var uvDirtyMax = -1
    private var forceVertex = true
    private var materialsDirty = false

    private val tmpQ = FloatArray(8)

    init {
        var v = false; var u = false; var b = false; var m = false; var g = false
        for (mo in morphs) {
            when (mo.type) {
                MorphData.TYPE_VERTEX -> v = true
                MorphData.TYPE_UV -> u = true
                MorphData.TYPE_BONE -> b = true
                MorphData.TYPE_MATERIAL -> m = true
                MorphData.TYPE_GROUP, MorphData.TYPE_FLIP -> g = true
            }
        }
        hasVertexMorphs = v; hasUvMorphs = u; hasBoneMorphs = b; hasMaterialMorphs = m; hasGroupMorphs = g
        uvs = if (u) model.uvs.copyOf() else model.uvs
    }

    fun beginFrame() {
        animWeights.fill(0f)
    }

    /** 基础顶点（部件编辑）变了：下次求值时整体重算并整体上传。 */
    fun invalidatePositions() {
        forceVertex = true
    }

    fun clearDirty() {
        posDirtyMin = Int.MAX_VALUE; posDirtyMax = -1
        uvDirtyMin = Int.MAX_VALUE; uvDirtyMax = -1
    }

    fun resetUserWeights() {
        userWeights.fill(0f)
    }

    /** 当前生效的权重（动作 + 用户）。 */
    /** 脚本给表情设的值；scriptSet[i] 为 true 时取代动作和手动的值。 */
    val scriptWeights = FloatArray(count)
    val scriptSet = BooleanArray(count)
    var anyScript = false

    fun setScriptWeight(index: Int, weight: Float) {
        scriptWeights[index] = weight; scriptSet[index] = true; anyScript = true
    }

    fun clearScriptWeights() {
        scriptWeights.fill(0f); scriptSet.fill(false); anyScript = false
    }

    fun weightOf(index: Int): Float = if (anyScript && scriptSet[index]) scriptWeights[index] else animWeights[index] + userWeights[index]

    fun evaluate() {
        val n = count
        val eff = effective
        for (i in 0 until n) eff[i] = animWeights[i] + userWeights[i]
        if (anyScript) for (i in 0 until n) if (scriptSet[i]) eff[i] = scriptWeights[i]

        // 组表情 / 翻转表情：把权重摊到成员上（成员若也是组表情则再展开，限制深度防止成环）
        if (hasGroupMorphs) {
            for (depth in 0 until 4) {
                var expanded = false
                for (i in 0 until n) {
                    val mo = morphs[i]
                    if (mo.type != MorphData.TYPE_GROUP && mo.type != MorphData.TYPE_FLIP) continue
                    val w = eff[i]
                    if (w == 0f) continue
                    eff[i] = 0f
                    for (k in 0 until mo.count) {
                        val target = mo.indices[k]
                        if (target < 0 || target >= n || target == i) continue
                        eff[target] += w * mo.data[k]
                        expanded = true
                    }
                }
                if (!expanded) break
            }
            for (i in 0 until n) {
                val t = morphs[i].type
                if (t == MorphData.TYPE_GROUP || t == MorphData.TYPE_FLIP) eff[i] = 0f
            }
        }

        if (hasVertexMorphs || forceVertex) evaluateVertex()
        if (hasUvMorphs) evaluateUv()
        if (skeleton != null && (hasBoneMorphs || skeleton.anyMorphBone)) evaluateBone(skeleton)
        if (hasMaterialMorphs) evaluateMaterial()
    }

    private fun evaluateVertex() {
        val n = count
        val eff = effective
        val last = lastVertexWeights
        val pos = positions
        val base = basePositions
        if (forceVertex) {
            forceVertex = false
            System.arraycopy(base, 0, pos, 0, minOf(base.size, pos.size))
            for (i in 0 until n) {
                val mo = morphs[i]
                if (mo.type != MorphData.TYPE_VERTEX) continue
                val w = eff[i]
                last[i] = w
                if (w != 0f) addVertexMorph(mo, w, pos)
            }
            posDirtyMin = 0
            posDirtyMax = model.vertexCount - 1
            return
        }
        var changed = false
        for (i in 0 until n) {
            if (morphs[i].type == MorphData.TYPE_VERTEX && eff[i] != last[i]) { changed = true; break }
        }
        if (!changed) return
        var dMin = posDirtyMin
        var dMax = posDirtyMax
        // 1) 把上一帧或这一帧激活的表情涉及的顶点恢复成基础值
        for (i in 0 until n) {
            val mo = morphs[i]
            if (mo.type != MorphData.TYPE_VERTEX) continue
            if (last[i] == 0f && eff[i] == 0f) continue
            val idx = mo.indices
            for (k in 0 until mo.count) {
                val v = idx[k]
                val o = v * 3
                pos[o] = base[o]; pos[o + 1] = base[o + 1]; pos[o + 2] = base[o + 2]
                if (v < dMin) dMin = v
                if (v > dMax) dMax = v
            }
        }
        // 2) 重新叠加所有激活的表情
        for (i in 0 until n) {
            val mo = morphs[i]
            if (mo.type != MorphData.TYPE_VERTEX) continue
            val w = eff[i]
            last[i] = w
            if (w != 0f) addVertexMorph(mo, w, pos)
        }
        posDirtyMin = dMin
        posDirtyMax = dMax
    }

    private fun addVertexMorph(mo: MorphData, w: Float, pos: FloatArray) {
        val idx = mo.indices
        val d = mo.data
        val vc = model.vertexCount
        for (k in 0 until mo.count) {
            val v = idx[k]
            if (v < 0 || v >= vc) continue
            val o = v * 3
            val s = k * 3
            pos[o] += d[s] * w; pos[o + 1] += d[s + 1] * w; pos[o + 2] += d[s + 2] * w
        }
    }

    private fun evaluateUv() {
        val n = count
        val eff = effective
        val last = lastUvWeights
        var changed = false
        for (i in 0 until n) {
            if (morphs[i].type == MorphData.TYPE_UV && eff[i] != last[i]) { changed = true; break }
        }
        if (!changed) return
        val uv = uvs
        val base = model.uvs
        val vc = model.vertexCount
        var dMin = uvDirtyMin
        var dMax = uvDirtyMax
        for (i in 0 until n) {
            val mo = morphs[i]
            if (mo.type != MorphData.TYPE_UV) continue
            if (last[i] == 0f && eff[i] == 0f) continue
            for (k in 0 until mo.count) {
                val v = mo.indices[k]
                if (v < 0 || v >= vc) continue
                uv[v * 2] = base[v * 2]; uv[v * 2 + 1] = base[v * 2 + 1]
                if (v < dMin) dMin = v
                if (v > dMax) dMax = v
            }
        }
        for (i in 0 until n) {
            val mo = morphs[i]
            if (mo.type != MorphData.TYPE_UV) continue
            val w = eff[i]
            last[i] = w
            if (w == 0f) continue
            for (k in 0 until mo.count) {
                val v = mo.indices[k]
                if (v < 0 || v >= vc) continue
                uv[v * 2] += mo.data[k * 4] * w
                uv[v * 2 + 1] += mo.data[k * 4 + 1] * w
            }
        }
        uvDirtyMin = dMin
        uvDirtyMax = dMax
    }

    private fun evaluateBone(sk: Skeleton) {
        val n = count
        val eff = effective
        val bc = sk.count
        if (sk.anyMorphBone) {
            sk.morphT.fill(0f)
            for (b in 0 until bc) Quat.identity(sk.morphR, b * 4)
            sk.anyMorphBone = false
        }
        val q = tmpQ
        Quat.identity(q, 4)
        for (i in 0 until n) {
            val mo = morphs[i]
            if (mo.type != MorphData.TYPE_BONE) continue
            val w = eff[i]
            if (w == 0f) continue
            for (k in 0 until mo.count) {
                val b = mo.indices[k]
                if (b < 0 || b >= bc) continue
                val o = k * 7
                sk.morphT[b * 3] += mo.data[o] * w
                sk.morphT[b * 3 + 1] += mo.data[o + 1] * w
                sk.morphT[b * 3 + 2] += mo.data[o + 2] * w
                Quat.slerp(q, 0, q, 4, mo.data, o + 3, w)
                Quat.mul(sk.morphR, b * 4, q, 0, sk.morphR, b * 4)
                Quat.normalize(sk.morphR, b * 4)
            }
            sk.anyMorphBone = true
        }
    }

    private fun evaluateMaterial() {
        val n = count
        val eff = effective
        var any = false
        for (i in 0 until n) {
            if (morphs[i].type == MorphData.TYPE_MATERIAL && eff[i] != 0f) { any = true; break }
        }
        if (!any && !materialsDirty) return
        for (m in materials) m.reset()
        materialsDirty = any
        if (!any) return
        for (i in 0 until n) {
            val mo = morphs[i]
            if (mo.type != MorphData.TYPE_MATERIAL) continue
            val w = eff[i]
            if (w == 0f) continue
            for (k in 0 until mo.count) {
                val target = mo.indices[k]
                val o = k * MorphData.MATERIAL_STRIDE
                if (target < 0) {
                    for (m in materials) applyMaterial(m, mo.data, o, w)
                } else if (target < materials.size) {
                    applyMaterial(materials[target], mo.data, o, w)
                }
            }
        }
    }

    private fun applyMaterial(m: MaterialState, d: FloatArray, o: Int, w: Float) {
        val add = d[o] != 0f
        if (add) {
            for (c in 0 until 4) m.diffuse[c] += d[o + 1 + c] * w
            for (c in 0 until 3) m.specular[c] += d[o + 5 + c] * w
            m.shininess += d[o + 8] * w
            for (c in 0 until 3) m.ambient[c] += d[o + 9 + c] * w
            for (c in 0 until 4) m.edgeColor[c] += d[o + 12 + c] * w
            m.edgeSize += d[o + 16] * w
            for (c in 0 until 4) {
                m.texAdd[c] += d[o + 17 + c] * w
                m.sphereAdd[c] += d[o + 21 + c] * w
                m.toonAdd[c] += d[o + 25 + c] * w
            }
        } else {
            for (c in 0 until 4) m.diffuse[c] *= 1f + (d[o + 1 + c] - 1f) * w
            for (c in 0 until 3) m.specular[c] *= 1f + (d[o + 5 + c] - 1f) * w
            m.shininess *= 1f + (d[o + 8] - 1f) * w
            for (c in 0 until 3) m.ambient[c] *= 1f + (d[o + 9 + c] - 1f) * w
            for (c in 0 until 4) m.edgeColor[c] *= 1f + (d[o + 12 + c] - 1f) * w
            m.edgeSize *= 1f + (d[o + 16] - 1f) * w
            for (c in 0 until 4) {
                m.texMul[c] *= 1f + (d[o + 17 + c] - 1f) * w
                m.sphereMul[c] *= 1f + (d[o + 21 + c] - 1f) * w
                m.toonMul[c] *= 1f + (d[o + 25 + c] - 1f) * w
            }
        }
    }
}

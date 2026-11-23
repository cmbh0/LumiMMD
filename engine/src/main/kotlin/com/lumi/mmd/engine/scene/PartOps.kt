package com.lumi.mmd.engine.scene

import com.lumi.mmd.engine.mesh.EditMesh
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.MorphData

/**
 * 把「部件」里做的调整合并进模型数据。
 *
 * 部件的移动 / 旋转 / 缩放 / 隐藏 / 删除平时只是记在 [Part] 上的参数（存在工程里，随时能改回来）。
 * 要把模型交给别处用——进建模、另存为 PMX——的时候，才真正写进顶点：
 *  - 挪过的部件：新位置成为模型的初始位置；
 *  - 删掉的部件（以及隐藏的连通块）：三角形拿掉，没人用的顶点跟着去掉；
 *  - 整块被删掉的材质：材质本身也去掉，指着它的材质表情跟着改号。
 */
object PartOps {
    class Baked(
        val data: ModelData,
        /** 原来的第 i 个材质现在是第几个（-1 = 被删掉了）。 */
        val materialMap: IntArray,
    )

    fun hasEdits(model: ModelInstance): Boolean = model.partsInitialized && model.parts.editedParts().isNotEmpty()

    fun bake(model: ModelInstance): Baked {
        val d = model.data
        val src = model.morphs.basePositions
        // 删掉 / 隐藏的部件在 basePositions 里已经缩成一个点，拆网格时这种面积为零的三角形会被丢掉
        val mesh = EditMesh.fromModel(d, if (src.size >= d.vertexCount * 3) src else d.positions)
        val count = d.materials.size
        val map = IntArray(count) { it }
        if (model.partsInitialized && count > 0) {
            val alive = IntArray(count)
            for (f in 0 until mesh.faceCount) if (!mesh.fDead[f]) alive[mesh.fMat[f].coerceIn(0, count - 1)]++
            val gone = BooleanArray(count)
            var left = count
            for (p in model.parts.materialParts) {
                // 至少留一个材质（全删光的模型没法存）
                if (p.deleted && alive[p.material] == 0 && left > 1) { gone[p.material] = true; left-- }
            }
            if (left < count) {
                var n = 0
                for (i in 0 until count) map[i] = if (gone[i]) -1 else n++
                for (f in 0 until mesh.faceCount) {
                    val m = map[mesh.fMat[f].coerceIn(0, count - 1)]
                    mesh.fMat[f] = if (m < 0) 0 else m
                }
                for (i in count - 1 downTo 0) if (gone[i]) mesh.materials.removeAt(i)
            }
        }
        mesh.removeOrphanPoints()
        val out = mesh.build().data
        if (map.any { it < 0 }) remapMaterialMorphs(out, map)
        out.computeBounds()
        return Baked(out, map)
    }

    /** 材质表情里记的是材质序号：删掉的那些条目去掉，其余改成新序号。 */
    private fun remapMaterialMorphs(d: ModelData, map: IntArray) {
        for ((i, m) in d.morphs.withIndex()) {
            if (m.type != MorphData.TYPE_MATERIAL) continue
            val stride = m.stride
            val idx = ArrayList<Int>(m.count)
            val data = ArrayList<Float>(m.count * stride)
            for (k in 0 until m.count) {
                val old = m.indices[k]
                val now = if (old < 0) old else if (old < map.size) map[old] else -1
                if (old >= 0 && now < 0) continue
                idx.add(now)
                for (c in 0 until stride) data.add(m.data[k * stride + c])
            }
            val o = MorphData()
            o.name = m.name; o.nameEn = m.nameEn; o.panel = m.panel; o.type = m.type
            o.count = idx.size; o.indices = idx.toIntArray(); o.data = data.toFloatArray()
            d.morphs[i] = o
        }
    }

    /**
     * 合并并把场景里的实例换成新的（部件参数清零，因为已经写进顶点了）。没有调整时原样返回。
     * 调用方要释放旧实例的 GPU 资源，并把"选中的模型"指向返回值。
     */
    fun merge(scene: Scene, model: ModelInstance): ModelInstance {
        if (!hasEdits(model)) return model
        val baked = bake(model)
        val neu = model.replacedBy(baked.data)
        // replacedBy 按序号抄了材质的显示状态；材质少了之后序号对不上，按对应表重抄一遍
        for (st in neu.materials) st.visible = true
        for (i in model.materials.indices) {
            val j = baked.materialMap.getOrElse(i) { -1 }
            if (j >= 0 && j < neu.materials.size) neu.materials[j].visible = model.materials[i].visible
        }
        scene.replace(model, neu)
        neu.updateWorldMatrix()
        return neu
    }
}

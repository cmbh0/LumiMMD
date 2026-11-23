package com.lumi.mmd.engine.scene

import com.lumi.mmd.engine.math.Quat

/**
 * 模型的一个"部件"：一个材质，或材质里的一个连通块（一朵发饰、一条飘带……）。
 * 部件可以单独移动 / 旋转 / 缩放 / 隐藏。变换作用在绑定姿势上（蒙皮之前），
 * 所以挪过位置的头饰、衣服配件仍然会跟着原来的骨骼一起动。
 */
class Part(val set: PartSet, val material: Int, val island: Int, var name: String) {
    /** 属于本部件的顶点序号（去重）。 */
    var vertices: IntArray = IntArray(0)
    /** 属于本部件的三角形（在索引缓冲里的起始下标，即 3 的倍数）。 */
    var triangles: IntArray = IntArray(0)
    /** 绑定姿势下的中心点。 */
    val pivot = FloatArray(3)
    /** 权重占比最大的骨骼（-1 = 静态模型）。 */
    var dominantBone = -1

    // ---- 用户编辑 ----
    val offset = FloatArray(3)
    val rotation = floatArrayOf(0f, 0f, 0f, 1f)
    val scale = floatArrayOf(1f, 1f, 1f)
    var hidden = false
    /**
     * 被用户删掉了：画面上、列表里都没有它，存进工程；撤销或者「全部复位」能找回来。
     * 真正从模型数据里拿掉，是在把部件调整合并进网格的时候（进建模、另存为 PMX，见 [PartOps]）。
     */
    var deleted = false

    val isEdited: Boolean
        get() = hidden || deleted || offset[0] != 0f || offset[1] != 0f || offset[2] != 0f ||
            !Quat.isIdentity(rotation, 0) || scale[0] != 1f || scale[1] != 1f || scale[2] != 1f

    val triangleCount: Int get() = triangles.size
    val key: String get() = if (island < 0) "m$material" else "m$material.i$island"

    fun reset() {
        offset.fill(0f); Quat.identity(rotation, 0); scale.fill(1f); hidden = false; deleted = false
    }
}

/**
 * 一个模型的全部部件，以及把部件编辑烘焙进顶点数据的逻辑。
 * 材质级部件在创建时就建好；连通块按需计算（并查集 + 按坐标焊接 UV 接缝处被拆开的顶点）。
 */
class PartSet(val model: ModelInstance) {
    private val data = model.data
    val materialParts: List<Part>
    private val islands = arrayOfNulls<List<Part>>(data.materials.size)

    /** 应用了部件编辑后的绑定姿势顶点 / 法线（没有任何编辑时为 null，直接用原数据）。 */
    private var editedPositions: FloatArray? = null
    var editedNormals: FloatArray? = null
        private set
    /** 法线被改动的顶点区间，渲染器上传后清空。 */
    var normalDirtyMin = Int.MAX_VALUE
    var normalDirtyMax = -1

    private val tq = FloatArray(12)

    init {
        model.markPartsCreated()
        val list = ArrayList<Part>(data.materials.size)
        val mark = IntArray(data.vertexCount) { -1 }
        for ((mi, m) in data.materials.withIndex()) {
            val p = Part(this, mi, -1, m.name.ifBlank { "材质${mi + 1}" })
            val tris = IntArray(m.indexCount / 3) { m.indexOffset + it * 3 }
            fill(p, tris, mark, mi)
            list.add(p)
        }
        materialParts = list
    }

    private fun fill(p: Part, tris: IntArray, mark: IntArray, stamp: Int) {
        val idx = data.indices
        var count = 0
        for (t in tris) for (k in 0 until 3) {
            val v = idx[t + k]
            if (mark[v] != stamp) { mark[v] = stamp; count++ }
        }
        val verts = IntArray(count)
        var n = 0
        // 第二遍收集（用 -stamp-2 作为"已收集"标记，避免再开一个数组）
        val done = -stamp - 2
        for (t in tris) for (k in 0 until 3) {
            val v = idx[t + k]
            if (mark[v] == stamp) { mark[v] = done; verts[n++] = v }
        }
        p.vertices = verts
        p.triangles = tris
        // 中心点：包围盒中心
        val pos = data.positions
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (v in verts) {
            val x = pos[v * 3]; val y = pos[v * 3 + 1]; val z = pos[v * 3 + 2]
            if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
            if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
        }
        if (verts.isNotEmpty()) {
            p.pivot[0] = (minX + maxX) * 0.5f; p.pivot[1] = (minY + maxY) * 0.5f; p.pivot[2] = (minZ + maxZ) * 0.5f
        }
        // 主骨骼
        val bi = data.boneIndices
        val bw = data.boneWeights
        if (bi != null && bw != null && data.bones.isNotEmpty()) {
            val acc = HashMap<Int, Float>()
            for (v in verts) for (k in 0 until 4) {
                val w = bw[v * 4 + k]
                if (w > 0f) {
                    val b = bi[v * 4 + k].toInt() and 0xFFFF
                    acc[b] = (acc[b] ?: 0f) + w
                }
            }
            p.dominantBone = acc.maxByOrNull { it.value }?.key ?: -1
        }
    }

    /** 某个材质里的连通块（按面数从大到小）。只有一个连通块时返回空表。 */
    fun islandsOf(material: Int): List<Part> {
        islands[material]?.let { return it }
        val m = data.materials[material]
        val idx = data.indices
        val pos = data.positions
        val triCount = m.indexCount / 3
        val mp = materialParts[material]
        val verts = mp.vertices
        // 顶点序号 → 局部编号
        val local = HashMap<Int, Int>(verts.size * 2)
        for ((i, v) in verts.withIndex()) local[v] = i
        val parent = IntArray(verts.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }
        // 位置相同的顶点焊在一起（UV 接缝 / 硬边会把顶点拆开）
        val weld = HashMap<Long, Int>(verts.size * 2)
        for ((i, v) in verts.withIndex()) {
            val kx = Math.round(pos[v * 3] * 2000f).toLong() and 0x1FFFFF
            val ky = Math.round(pos[v * 3 + 1] * 2000f).toLong() and 0x1FFFFF
            val kz = Math.round(pos[v * 3 + 2] * 2000f).toLong() and 0x1FFFFF
            val key = kx or (ky shl 21) or (kz shl 42)
            val prev = weld.putIfAbsent(key, i)
            if (prev != null) union(prev, i)
        }
        for (t in 0 until triCount) {
            val o = m.indexOffset + t * 3
            val a = local[idx[o]]!!; val b = local[idx[o + 1]]!!; val c = local[idx[o + 2]]!!
            union(a, b); union(b, c)
        }
        val groups = HashMap<Int, ArrayList<Int>>()
        for (t in 0 until triCount) {
            val o = m.indexOffset + t * 3
            groups.getOrPut(find(local[idx[o]]!!)) { ArrayList() }.add(o)
        }
        val result: List<Part>
        if (groups.size <= 1) {
            result = emptyList()
        } else {
            val sorted = groups.values.sortedByDescending { it.size }
            val mark = IntArray(data.vertexCount) { -1 }
            val out = ArrayList<Part>(sorted.size)
            for ((i, g) in sorted.withIndex()) {
                val p = Part(this, material, i, "${mp.name} #${i + 1}")
                fill(p, g.toIntArray(), mark, i)
                out.add(p)
            }
            result = out
        }
        islands[material] = result
        return result
    }

    fun islandsComputed(material: Int): List<Part>? = islands[material]

    /** 找到包含某个三角形的部件。 */
    fun partOfTriangle(indexOffset: Int, wantIsland: Boolean): Part? {
        var mat = -1
        for ((i, m) in data.materials.withIndex()) {
            if (indexOffset >= m.indexOffset && indexOffset < m.indexOffset + m.indexCount) { mat = i; break }
        }
        if (mat < 0) return null
        if (wantIsland) {
            for (p in islandsOf(mat)) {
                // 连通块的三角形下标是升序的 → 二分
                if (p.triangles.binarySearch(indexOffset) >= 0) return p
            }
        }
        return materialParts[mat]
    }

    fun findByKey(key: String): Part? {
        val dot = key.indexOf(".i")
        val mat = (if (dot > 0) key.substring(1, dot) else key.substring(1)).toIntOrNull() ?: return null
        if (mat !in materialParts.indices) return null
        if (dot < 0) return materialParts[mat]
        val isl = key.substring(dot + 2).toIntOrNull() ?: return null
        return islandsOf(mat).getOrNull(isl)
    }

    /** 所有被编辑过的部件。 */
    fun editedParts(): List<Part> {
        val out = ArrayList<Part>()
        for (p in materialParts) if (p.isEdited) out.add(p)
        for (list in islands) if (list != null) for (p in list) if (p.isEdited) out.add(p)
        return out
    }

    /**
     * 部件的编辑参数变化后调用：重新烘焙该材质所有顶点的绑定姿势位置与法线。
     * 顺序是 先连通块、后材质（材质级的变换作用在连通块变换的结果上）。
     */
    fun apply(part: Part) {
        val mi = part.material
        var ep = editedPositions
        if (ep == null) {
            ep = data.positions.copyOf()
            editedPositions = ep
            model.morphs.basePositions = ep
        }
        val src = data.positions
        val srcN = data.normals
        val mp = materialParts[mi]
        val isl = islands[mi]
        val needNormals = !Quat.isIdentity(mp.rotation, 0) || (isl?.any { !Quat.isIdentity(it.rotation, 0) } ?: false) || editedNormals != null
        var en = editedNormals
        if (needNormals && en == null) { en = srcN.copyOf(); editedNormals = en }
        // 1) 先恢复成原始值
        for (v in mp.vertices) {
            val o = v * 3
            ep[o] = src[o]; ep[o + 1] = src[o + 1]; ep[o + 2] = src[o + 2]
            if (en != null) { en[o] = srcN[o]; en[o + 1] = srcN[o + 1]; en[o + 2] = srcN[o + 2] }
        }
        // 2) 连通块
        if (isl != null) for (p in isl) if (p.isEdited) transform(p, ep, en)
        // 3) 材质
        if (mp.isEdited) transform(mp, ep, en)
        model.morphs.invalidatePositions()
        if (en != null && mp.vertices.isNotEmpty()) {
            var lo = normalDirtyMin; var hi = normalDirtyMax
            for (v in mp.vertices) { if (v < lo) lo = v; if (v > hi) hi = v }
            normalDirtyMin = lo; normalDirtyMax = hi
        }
        model.poseDirty = true
    }

    private fun transform(p: Part, pos: FloatArray, nrm: FloatArray?) {
        val q = p.rotation
        val rot = !Quat.isIdentity(q, 0)
        // 隐藏 / 删掉的部件：全部顶点缩到中心一点，三角形面积为零，画不出来也点不中
        val gone = p.hidden || p.deleted
        val sx = if (gone) 0f else p.scale[0]
        val sy = if (gone) 0f else p.scale[1]
        val sz = if (gone) 0f else p.scale[2]
        val cx = p.pivot[0]; val cy = p.pivot[1]; val cz = p.pivot[2]
        val t = tq
        for (v in p.vertices) {
            val o = v * 3
            var x = (pos[o] - cx) * sx
            var y = (pos[o + 1] - cy) * sy
            var z = (pos[o + 2] - cz) * sz
            if (rot) {
                Quat.rotate(q, 0, x, y, z, t, 0)
                x = t[0]; y = t[1]; z = t[2]
                if (nrm != null) {
                    Quat.rotate(q, 0, nrm[o], nrm[o + 1], nrm[o + 2], t, 4)
                    nrm[o] = t[4]; nrm[o + 1] = t[5]; nrm[o + 2] = t[6]
                }
            }
            pos[o] = x + cx + p.offset[0]
            pos[o + 1] = y + cy + p.offset[1]
            pos[o + 2] = z + cz + p.offset[2]
        }
    }

    /** 被删掉的部件（材质级的和连通块级的）。 */
    fun deletedParts(): List<Part> = editedParts().filter { it.deleted }

    /** 撤销所有部件编辑。 */
    fun resetAll() {
        val edited = editedParts()
        for (p in edited) p.reset()
        val mats = edited.map { it.material }.toSet()
        for (mi in mats) apply(materialParts[mi])
    }

    /** 部件当前的中心点（绑定姿势空间，含编辑偏移）。 */
    fun currentPivot(p: Part, out: FloatArray, oo: Int) {
        out[oo] = p.pivot[0] + p.offset[0]; out[oo + 1] = p.pivot[1] + p.offset[1]; out[oo + 2] = p.pivot[2] + p.offset[2]
        if (p.island >= 0) {
            val mp = materialParts[p.material]
            out[oo] += mp.offset[0]; out[oo + 1] += mp.offset[1]; out[oo + 2] += mp.offset[2]
        }
    }
}

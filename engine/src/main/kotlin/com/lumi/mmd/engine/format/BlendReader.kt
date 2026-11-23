package com.lumi.mmd.engine.format

import com.lumi.mmd.engine.mesh.EditMesh
import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.ModelFormat
import com.lumi.mmd.engine.util.LongIntMap
import com.lumi.mmd.engine.util.Memory
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 直接读 Blender 的工程文件（.blend），把里面的场景当成一个静态模型（舞台 / 道具）导入。
 *
 * 读进来的是：当前场景里参与渲染的网格物体（连同父子关系算出来的位置）、集合实例、共用同一份网格的物体；
 * 每个面的材质、UV、平滑 / 平直着色；材质的基础色、透明度、自发光和基础色上连着的贴图（打包在文件里的直接取出来）。
 * 2.5 到 5.x 的文件都能读，包括压缩保存的。
 *
 * 读不进来的（Blender 里它们是打开文件之后现算的，文件里没有结果）：
 *  - 修改器：只处理最常见的「镜像」和「阵列」（按简单情况复制），其余的按修改器之前的形状导入并给出提醒；
 *  - 曲线、文字、融球这些不是网格的物体；粒子、毛发、几何节点生成的东西；
 *  - 骨架和动画（场景按存盘那一刻的样子导入）；从别的 .blend 链接进来、不在这个文件里的东西。
 */
object BlendReader {
    /** 超过这么多三角形就不再往里加（手机带不动，也防止实例套实例把内存吃光）。 */
    const val MAX_TRIANGLES = 4_000_000
    /** 测试用：把"内存放得下多少个三角形"强行设成这个数（0 = 按实际剩余的内存算）。 */
    @Volatile var triangleBudgetForTest = 0
    /** 导入过程中每个三角形大约占多少堆内存（顶点、法线、UV、索引，加上组装时的中间数据；按最费的情况估）。 */
    private const val BYTES_PER_TRIANGLE = 260L
    private const val GEO_CACHE_CORNERS = 1_500_000L
    /** 场景里的物体不多时，每个物体（的每个材质）单独成一个部件，方便在「部件」里一个一个地挑；太多就按材质合并。 */
    private const val MAX_OBJECT_PARTS = 240

    /** 内存里的一份 .blend（小文件）。 */
    fun read(bytes: ByteArray, file: File?): ModelData = run(file) { BlendFile.of(bytes) }

    /**
     * 直接从文件读。文件内容不进堆内存（见 [BlendFile.open]），打包在里面的贴图解到 .blend 旁边的文件夹里，
     * 场景太大时按还剩多少内存只导入放得下的部分——几百 MB 的场景在手机上也不会把内存用光。
     */
    fun read(file: File): ModelData = run(file) { BlendFile.open(file) }

    private fun run(file: File?, open: () -> BlendFile): ModelData {
        val f = try { open() } catch (e: OutOfMemoryError) { throw FormatException("这个 .blend 太大了，内存不够") }
        val model = try { Importer(f, file).run() } catch (e: FormatException) { throw e } catch (e: OutOfMemoryError) {
            throw FormatException("这个 .blend 里的场景太大了，内存不够")
        } catch (e: RuntimeException) {
            throw FormatException("读取 .blend 时出错（Blender ${versionName(f.version)} 存的文件）：${e.message ?: e.javaClass.simpleName}", e)
        }
        if (model.name.isBlank()) model.name = file?.nameWithoutExtension ?: ""
        return model
    }

    fun versionName(v: Int): String = if (v >= 1000) "$v" else "${v / 100}.${v % 100}"

    // ====================================================================== 网格

    private class Geo(
        val verts: FloatArray,
        /** 每个角用的顶点。 */
        val corners: IntArray,
        /** 每个面的第一个角；长度 = 面数 + 1。 */
        val polyStart: IntArray,
        val polyMat: IntArray,
        val polySmooth: BooleanArray,
        /** 每个角的 UV（没有 UV 时为 null）。 */
        val uv: FloatArray?,
        /** 平滑着色时，相邻面的夹角超过它就不再平滑过去（弧度；小于 0 = 不限）。 */
        val crease: Float,
    ) {
        /** 标成「锐边」的边（两个顶点序号拼成的键）：法线不跨过它平滑。 */
        var sharp: HashSet<Long>? = null
        val polyCount: Int get() = polyMat.size
        /** 三角形：每三个一组，值是角的序号。 */
        var tris = IntArray(0)
        /** 每个三角形属于哪个面。 */
        var triPoly = IntArray(0)
        /** 去重之后的输出顶点：角 → 输出顶点；每个输出顶点取自哪个角。 */
        var cornerOut = IntArray(0)
        var outCorner = IntArray(0)
        /** 每个角的法线（物体空间）。 */
        var normals = FloatArray(0)
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var usedSlots = IntArray(0)
    }

    private class Importer(val f: BlendFile, val source: File?) {
        val b = MeshBuilder(ModelFormat.BLEND)
        val geos = HashMap<String, Geo?>()
        /** 缓存着的网格一共有多少个角；太多就清掉（只有被好几个物体共用的网格才值得留着）。 */
        var cachedCorners = 0L
        /** 这台设备现在的内存放得下多少个三角形。 */
        var triangleBudget = MAX_TRIANGLES
        var outOfMemory = false
        var packedToDisk = 0
        val worlds = HashMap<Long, FloatArray>()
        val textures = HashMap<Long, Int>()
        val baseMaterials = HashMap<Long, MaterialData>()
        val parts = HashMap<String, Int>()
        var defaultMaterial: MaterialData? = null
        var perObject = false
        var triangles = 0
        var truncated = false
        // 提醒用的统计
        var meshObjects = 0
        var hiddenObjects = 0
        var instances = 0
        val nonMesh = LinkedHashMap<String, Int>()
        val modifiersSkipped = LinkedHashMap<String, Int>()
        var objectsWithSkipped = 0
        var linkedMissing = 0
        var otherDupli = 0
        var missingMesh = 0

        fun run(): ModelData {
            b.setBasisRightHandedZUp()
            b.flipV = true
            // 先量一量还剩多少内存：导入过程中每个三角形大约要占 [BYTES_PER_TRIANGLE]（含中间过程），超出的部分不导入
            System.gc()
            triangleBudget = if (triangleBudgetForTest > 0) triangleBudgetForTest
                else ((Memory.available() - Memory.RESERVE) / BYTES_PER_TRIANGLE).coerceIn(20_000L, MAX_TRIANGLES.toLong()).toInt()
            val scene = currentScene() ?: throw FormatException("这个 .blend 里没有场景")
            val roots = sceneObjects(scene)
            // 先数一数：物体不多就一个物体一个部件
            var count = 0
            for (ob in roots) if (ob.int("type") == OB_MESH) count += maxOf(1, ob.int("totcol"))
            perObject = count in 1..MAX_OBJECT_PARTS
            for (ob in roots) {
                if (truncated) break
                addObject(ob, null, 0)
            }
            val unit = scene.sub("unit")
            var scale = GltfReader.UNIT_SCALE
            if (unit != null && unit.int("system") != 0) {
                val s = unit.float("scale_length", default = 1f)
                if (s > 1e-6f && s < 1e6f) scale *= s
            }
            if (b.triangleCount == 0) {
                throw FormatException(when {
                    linkedMissing > 0 -> "这个 .blend 里的东西是从别的 .blend 链接进来的，本身不带模型数据。请在 Blender 里「文件 → 外部数据 → 全部本地化」之后再保存"
                    nonMesh.isNotEmpty() -> "这个 .blend 里没有网格物体（只有${nonMesh.keys.joinToString("、")}）。请在 Blender 里把它们转换成网格之后再保存"
                    hiddenObjects > 0 -> "这个 .blend 里的物体都设成了「不渲染」，没有可以导入的东西"
                    else -> "这个 .blend 的场景里没有网格物体"
                })
            }
            if (objectsWithSkipped > 0) {
                b.warnings.add("有 $objectsWithSkipped 个物体带着读不了的修改器（" + modifiersSkipped.entries.joinToString("、") { "${it.key} ×${it.value}" } +
                    "），导入的是加修改器之前的形状。想要一模一样：在 Blender 里应用修改器之后再保存")
            }
            if (nonMesh.isNotEmpty()) b.warnings.add("跳过了不是网格的物体：" + nonMesh.entries.joinToString("、") { "${it.key} ×${it.value}" } + "（在 Blender 里转换成网格之后才能导入）")
            if (linkedMissing > 0) b.warnings.add("有 $linkedMissing 个物体是从别的 .blend 链接进来的，不在这个文件里，没有导入")
            if (otherDupli > 0) b.warnings.add("有 $otherDupli 个物体用了顶点 / 面实例，没有展开")
            if (truncated) b.warnings.add(
                if (outOfMemory || triangleBudget < MAX_TRIANGLES) "场景太大：这台设备的内存只放得下大约 ${maxOf(1, triangles / 10000)} 万个三角形，后面的物体没有导入（应用最多能用 ${Memory.mb(Memory.max())} MB 内存）。可以在 Blender 里把场景拆成几个文件、或者减面之后再导入"
                else "场景超过 ${MAX_TRIANGLES / 10000} 万个三角形，后面的物体没有导入")
            val model = b.build(scale)
            model.comment = "Blender ${versionName(f.version)}" + (if (f.compressed.isNotEmpty()) "（${f.compressed} 压缩）" else "") +
                " · $meshObjects 个物体" + (if (instances > 0) " · $instances 个实例" else "") + (if (hiddenObjects > 0) " · $hiddenObjects 个不渲染的没有导入" else "")
            model.suggestedScale = MeshBuilder.suggestScale(model, 1f)
            return model
        }

        // ------------------------------------------------------------------ 场景

        private fun idName(r: BlendFile.Ref): String {
            // 从别的文件链接进来的资源在这里只有一个占位的 ID 结构
            val n = (r.sub("id") ?: r.takeIf { it.struct.name == "ID" })?.str("name") ?: ""
            return if (n.length > 2) n.substring(2) else n
        }

        private fun isLinked(r: BlendFile.Ref): Boolean = ((r.sub("id") ?: r.takeIf { it.struct.name == "ID" })?.ptr("lib") ?: 0L) != 0L

        private fun currentScene(): BlendFile.Ref? {
            // GLOB 块里记着存盘时正开着的场景
            for (g in f.blocksOf("GLOB")) {
                val r = f.ref(g, "FileGlobal") ?: continue
                val a = r.ptr("curscene")
                if (a != 0L) f.at(g.owner, a, "Scene")?.let { if (it.block.code == "SC") return it }
            }
            return f.blocksOf("SC").firstOrNull()?.let { f.ref(it, "Scene") }
        }

        /** 当前场景里要导入的物体（按出现的先后，去重）。 */
        private fun sceneObjects(scene: BlendFile.Ref): List<BlendFile.Ref> {
            val out = ArrayList<BlendFile.Ref>()
            val seen = HashSet<Long>()
            fun add(ob: BlendFile.Ref?) { if (ob != null && ob.struct.name == "Object" && seen.add(ob.address)) out.add(ob) }
            val master = scene.ref("master_collection", "Collection")
            if (master != null) {
                // 2.8 起：集合。视图层里被排除的、设成不渲染的集合整个跳过
                val excluded = HashSet<Long>()
                fun layer(lc: BlendFile.Ref, off: Boolean) {
                    val ex = off || (lc.int("flag") and LAYER_COLLECTION_EXCLUDE) != 0
                    if (ex) excluded.add(lc.ptr("collection"))
                    for (c in lc.list("layer_collections", "LayerCollection")) layer(c, ex)
                }
                scene.list("view_layers", "ViewLayer").firstOrNull()?.let { vl -> for (lc in vl.list("layer_collections", "LayerCollection")) layer(lc, false) }
                val walked = HashSet<Long>()
                fun walk(c: BlendFile.Ref, depth: Int) {
                    if (depth > 64 || !walked.add(c.address)) return
                    if (depth > 0 && (c.address in excluded || (c.int("flag") and COLLECTION_HIDE_RENDER) != 0)) return
                    for (co in c.list("gobject", "CollectionObject")) add(co.ref("ob", "Object"))
                    for (ch in c.list("children", "CollectionChild")) ch.ref("collection", "Collection")?.let { walk(it, depth + 1) }
                }
                walk(master, 0)
            } else {
                // 2.7x 及更早：场景的物体表 + 图层
                val lay = scene.int("lay", default = -1)
                for (base in scene.list("base", "Base")) {
                    if (lay != 0 && (base.int("lay", default = -1) and lay) == 0) continue
                    add(base.ref("object", "Object"))
                }
            }
            return out
        }

        /** 一个集合 / 组里的物体（含子集合）。 */
        private fun groupObjects(g: BlendFile.Ref, out: ArrayList<BlendFile.Ref>, depth: Int, walked: HashSet<Long>) {
            if (depth > 32 || !walked.add(g.address)) return
            val item = if (f.struct("CollectionObject") != null) "CollectionObject" else "GroupObject"
            for (go in g.list("gobject", item)) go.ref("ob", "Object")?.let { if (it.struct.name == "Object") out.add(it) }
            if (g.has("children")) for (ch in g.list("children", "CollectionChild")) ch.ref("collection", "Collection")?.let { groupObjects(it, out, depth + 1, walked) }
        }

        private fun addObject(ob: BlendFile.Ref, matrix: FloatArray?, depth: Int) {
            if ((ob.int("restrictflag") and OB_HIDE_RENDER) != 0) { if (depth == 0) hiddenObjects++; return }
            val m = matrix ?: world(ob, 0)
            when (val type = ob.int("type")) {
                OB_MESH -> emitMesh(ob, m, depth)
                OB_CURVE, OB_SURF, OB_FONT, OB_MBALL, OB_CURVES, OB_POINTCLOUD, OB_VOLUME -> if (depth == 0) {
                    val name = when (type) { OB_CURVE -> "曲线"; OB_SURF -> "曲面"; OB_FONT -> "文字"; OB_MBALL -> "融球"; OB_CURVES -> "毛发曲线"; OB_POINTCLOUD -> "点云"; else -> "体积" }
                    nonMesh[name] = (nonMesh[name] ?: 0) + 1
                }
            }
            val flag = ob.int("transflag")
            if ((flag and OB_DUPLICOLLECTION) != 0) {
                val group = ob.ref("dup_group") ?: run { if (ob.ptr("dup_group") != 0L) linkedMissing++; return }
                if (depth >= 6) return
                val members = ArrayList<BlendFile.Ref>()
                groupObjects(group, members, 0, HashSet())
                if (members.isEmpty() && isLinked(group)) { linkedMissing++; return }
                val ofs = group.floats("dupli_ofs", 3)
                val base = if (ofs != null && (ofs[0] != 0f || ofs[1] != 0f || ofs[2] != 0f)) mul(m, translation(-ofs[0], -ofs[1], -ofs[2])) else m
                instances++
                for (member in members) {
                    if (truncated) return
                    addObject(member, mul(base, world(member, 0)), depth + 1)
                }
            } else if ((flag and (OB_DUPLIVERTS or OB_DUPLIFACES)) != 0 && depth == 0) otherDupli++
        }

        // ------------------------------------------------------------------ 变换

        private fun world(ob: BlendFile.Ref, depth: Int): FloatArray {
            worlds[ob.address]?.let { return it }
            val parent = if (depth < 64) ob.ref("parent", "Object")?.takeIf { it.struct.name == "Object" } else null
            // 有约束、挂在骨骼 / 顶点上的物体，位置是 Blender 现算的；老版本把算好的矩阵存在了文件里，有就用它
            val evaluated = (ob.sub("constraints")?.ptr("first") ?: 0L) != 0L || (parent != null && ob.int("partype") != 0)
            var m: FloatArray? = null
            if (evaluated) m = ob.floats("obmat", 16)?.takeIf { sane(it) }
            if (m == null) {
                val local = local(ob)
                m = if (parent != null) {
                    val inv = ob.floats("parentinv", 16)?.takeIf { sane(it) } ?: identity()
                    mul(mul(world(parent, depth + 1), inv), local)
                } else local
            }
            worlds[ob.address] = m
            return m
        }

        private fun sane(m: FloatArray): Boolean {
            for (v in m) if (v != v || v.isInfinite()) return false
            if (abs(m[15] - 1f) > 1e-3f) return false
            return abs(m[0]) + abs(m[1]) + abs(m[2]) + abs(m[4]) + abs(m[5]) + abs(m[6]) + abs(m[8]) + abs(m[9]) + abs(m[10]) > 1e-12f
        }

        private fun local(ob: BlendFile.Ref): FloatArray {
            val loc = ob.floats("loc", 3) ?: FloatArray(3)
            val dloc = ob.floats("dloc", 3) ?: FloatArray(3)
            val size = ob.floats("size", 3) ?: floatArrayOf(1f, 1f, 1f)
            val ds = ob.floats("dscale", 3)?.takeIf { it[0] != 0f || it[1] != 0f || it[2] != 0f } ?: floatArrayOf(1f, 1f, 1f)
            val mode = if (ob.has("rotmode")) ob.int("rotmode") else 1
            val r: FloatArray
            val d: FloatArray
            when {
                mode > 0 -> { r = euler(ob.floats("rot", 3) ?: FloatArray(3), mode); d = euler(ob.floats("drot", 3) ?: FloatArray(3), mode) }
                mode == 0 -> { r = quat(ob.floats("quat", 4) ?: floatArrayOf(1f, 0f, 0f, 0f)); d = quat(ob.floats("dquat", 4) ?: floatArrayOf(1f, 0f, 0f, 0f)) }
                else -> {
                    r = axisAngle(ob.floats("rotAxis", 3) ?: floatArrayOf(0f, 1f, 0f), ob.float("rotAngle"))
                    d = axisAngle(ob.floats("drotAxis", 3) ?: floatArrayOf(0f, 1f, 0f), ob.float("drotAngle"))
                }
            }
            val rot = mul3(d, r)
            val m = identity()
            for (c in 0 until 3) {
                val s = size[c] * ds[c]
                m[c * 4] = rot[c * 3] * s; m[c * 4 + 1] = rot[c * 3 + 1] * s; m[c * 4 + 2] = rot[c * 3 + 2] * s
            }
            m[12] = loc[0] + dloc[0]; m[13] = loc[1] + dloc[1]; m[14] = loc[2] + dloc[2]
            return m
        }

        // ------------------------------------------------------------------ 修改器

        /** 镜像 / 阵列按最简单的情况展开成"再摆几份"；返回每一份相对物体自己的变换。 */
        private fun modifierCopies(ob: BlendFile.Ref, geo: Geo): List<FloatArray> {
            var copies: List<FloatArray> = listOf(identity())
            var lo = geo.min.copyOf(); var hi = geo.max.copyOf()
            var skipped = false
            for (md in ob.list("modifiers", "ModifierData")) {
                if ((md.int("mode") and MOD_RENDER) == 0) continue
                when (val type = md.int("type")) {
                    MOD_MIRROR -> {
                        val mm = f.ref(md.block, "MirrorModifierData") ?: continue
                        if (mm.ptr("mirror_ob") != 0L) { skip("镜像（以别的物体为中心）"); skipped = true; continue }
                        var flag = mm.int("flag")
                        // 很老的文件用 axis 记方向：0 = X，1 = Y，2 = Z
                        if (flag and (MOD_MIR_AXIS_X or MOD_MIR_AXIS_Y or MOD_MIR_AXIS_Z) == 0) flag = flag or (MOD_MIR_AXIS_X shl mm.int("axis").coerceIn(0, 2))
                        for (axis in 0 until 3) {
                            if (flag and (MOD_MIR_AXIS_X shl axis) == 0) continue
                            val mir = identity(); mir[axis * 5] = -1f
                            copies = copies + copies.map { mul(mir, it) }
                            val a = lo[axis]; val c = hi[axis]
                            lo[axis] = minOf(a, -c); hi[axis] = maxOf(c, -a)
                        }
                    }
                    MOD_ARRAY -> {
                        val am = f.ref(md.block, "ArrayModifierData") ?: continue
                        val offsetType = am.int("offset_type")
                        if (am.int("fit_type") != 0 || (offsetType and MOD_ARR_OFF_OBJ) != 0) { skip("阵列（按长度 / 曲线 / 物体偏移）"); skipped = true; continue }
                        val n = am.int("count").coerceIn(1, 2000)
                        val step = FloatArray(3)
                        if (offsetType and MOD_ARR_OFF_CONST != 0) am.floats("offset", 3)?.let { for (k in 0 until 3) step[k] += it[k] }
                        if (offsetType and MOD_ARR_OFF_RELATIVE != 0) am.floats("scale", 3)?.let { for (k in 0 until 3) step[k] += it[k] * (hi[k] - lo[k]) }
                        if (n > 1) {
                            val next = ArrayList<FloatArray>(copies.size * n)
                            for (i in 0 until n) { val t = translation(step[0] * i, step[1] * i, step[2] * i); for (c in copies) next.add(mul(t, c)) }
                            copies = next
                            for (k in 0 until 3) { val e = step[k] * (n - 1); if (e > 0) hi[k] += e else lo[k] += e }
                        }
                    }
                    MOD_NODES -> if (!isSmoothByAngle(md)) { skip("几何节点"); skipped = true }
                    else -> MODIFIER_NAMES[type]?.let { skip(it); skipped = true }
                }
                if (copies.size > 20000) break
            }
            if (skipped) objectsWithSkipped++
            return copies
        }

        private fun skip(name: String) { modifiersSkipped[name] = (modifiersSkipped[name] ?: 0) + 1 }

        /**
         * 几何节点修改器用的是不是 Blender 自带的「按角度平滑」。
         * 4.1 起在界面上点「自动平滑着色」加的是 Smooth by Angle；老文件在 4.1 里打开时自动换成的叫 Auto Smooth。
         */
        private fun isSmoothByAngle(md: BlendFile.Ref): Boolean {
            if (md.int("type") != MOD_NODES) return false
            fun known(n: String) = n.startsWith("Smooth by Angle") || n.startsWith("Auto Smooth")
            val nm = f.ref(md.block, "NodesModifierData") ?: return false
            val group = nm.ref("node_group") ?: return known(md.str("name"))
            return known(idName(group))
        }

        /** 物体上「按角度平滑」修改器的夹角（弧度）；没有这个修改器返回 -1。 */
        private fun smoothByAngle(ob: BlendFile.Ref): Float {
            for (md in ob.list("modifiers", "ModifierData")) {
                if (!isSmoothByAngle(md)) continue
                var angle = 0.5235988f
                val props = f.ref(md.block, "NodesModifierData")?.sub("settings")?.ref("properties", "IDProperty")
                // 修改器的参数里第一个小数就是夹角（名字随版本不同：Input_1 / Socket_1 / Socket_2）
                if (props != null) for (p in props.sub("data")?.list("group", "IDProperty") ?: emptyList()) {
                    val d = p.sub("data") ?: continue
                    val field = d.struct.fields["val"] ?: continue
                    val v = when (p.int("type")) {
                        IDP_FLOAT -> f.f32(d.pos + field.offset)
                        IDP_DOUBLE -> java.lang.Double.longBitsToDouble(f.i64(d.pos + field.offset)).toFloat()
                        else -> continue
                    }
                    if (v > 0f && v < 3.15f) angle = v
                    break
                }
                return angle
            }
            return -1f
        }

        // ------------------------------------------------------------------ 网格 → 模型

        private fun emitMesh(ob: BlendFile.Ref, m: FloatArray, depth: Int) {
            val me = ob.ref("data")
            if (me == null || me.struct.name != "Mesh") { if (ob.ptr("data") != 0L) { if (isLinked(ob)) linkedMissing++ else missingMesh++ }; return }
            // 4.1 起「自动平滑」是物体上的一个修改器（按角度平滑），夹角记在修改器里
            val angle = smoothByAngle(ob)
            val key = "${me.address}/$angle"
            var geo = geos[key]
            if (geo == null && !geos.containsKey(key)) {
                // 一个网格的中间数据（法线、三角化）比它本身大好几倍：先看放不放得下
                val corners = me.int("totloop").toLong() + me.int("totface") * 4L
                if (!Memory.fits(corners * 120 + me.int("totvert") * 16L, 40L * 1024 * 1024)) { truncated = true; outOfMemory = true; return }
                geo = try { geometry(me, angle) } catch (e: IndexOutOfBoundsException) { null }
                if (cachedCorners > GEO_CACHE_CORNERS) { geos.clear(); cachedCorners = 0 }
                geos[key] = geo
                cachedCorners += geo?.corners?.size ?: 0
            }
            if (geo == null || geo.tris.isEmpty()) { if (geo == null && isLinked(me)) linkedMissing++; return }
            val copies = modifierCopies(ob, geo)
            val total = geo.tris.size / 3L * copies.size
            if (triangles + total > triangleBudget) { truncated = true; return }
            // 再量一次真实的余量（别的地方也在用内存）：放不下就到此为止，已经导入的照常用
            val vertsToAdd = geo.outCorner.size.toLong() * copies.size
            if (!Memory.fits(vertsToAdd * 96 + total * 24, 40L * 1024 * 1024)) { truncated = true; outOfMemory = true; return }
            b.reserve(vertsToAdd.toInt())
            triangles += total.toInt()
            meshObjects++
            // 材质槽：物体上的优先（matbits），否则用网格上的
            val meshCols = me.int("totcol")
            val obCols = ob.int("totcol")
            val meshMats = me.target("mat")
            val obMats = ob.target("mat")
            val bits = ob.target("matbits")
            val obName = idName(ob)
            val used = geo.usedSlots
            val slotMaterial = IntArray(maxOf(1, used.size))
            for ((k, slot) in used.withIndex()) {
                var addr = 0L
                val fromObject = bits != null && slot < obCols && slot < bits.length && f.u8(bits.offset + slot) != 0
                if (fromObject && obMats != null && (slot + 1) * f.pointerSize <= obMats.length) addr = f.pointer(obMats.offset + slot * f.pointerSize)
                else if (meshMats != null && slot < meshCols && (slot + 1) * f.pointerSize <= meshMats.length) addr = f.pointer(meshMats.offset + slot * f.pointerSize)
                slotMaterial[k] = part(if (fromObject) ob.owner else me.owner, addr, obName, used.size > 1)
            }
            val slotIndex = IntArray((used.maxOrNull() ?: 0) + 1) { -1 }
            for ((k, slot) in used.withIndex()) slotIndex[slot] = k
            for (copy in copies) emit(geo, if (copies.size == 1) m else mul(m, copy), slotMaterial, slotIndex)
        }

        private fun emit(geo: Geo, m: FloatArray, slotMaterial: IntArray, slotIndex: IntArray) {
            // 法线用 3x3 部分的余子式矩阵变换（缩放不均匀时方向才对）；行列式为负 = 镜像，三角形要反过来绕
            val c00 = m[5] * m[10] - m[6] * m[9]; val c01 = m[6] * m[8] - m[4] * m[10]; val c02 = m[4] * m[9] - m[5] * m[8]
            val c10 = m[2] * m[9] - m[1] * m[10]; val c11 = m[0] * m[10] - m[2] * m[8]; val c12 = m[1] * m[8] - m[0] * m[9]
            val c20 = m[1] * m[6] - m[2] * m[5]; val c21 = m[2] * m[4] - m[0] * m[6]; val c22 = m[0] * m[5] - m[1] * m[4]
            val det = m[0] * c00 + m[1] * c01 + m[2] * c02
            val flip = det < 0f
            val sg = if (flip) -1f else 1f
            val base = b.vertexCount
            val v = geo.verts; val nr = geo.normals; val uv = geo.uv
            for (o in geo.outCorner.indices) {
                val corner = geo.outCorner[o]
                val vi = geo.corners[corner] * 3
                val x = v[vi]; val y = v[vi + 1]; val z = v[vi + 2]
                val nx = nr[corner * 3]; val ny = nr[corner * 3 + 1]; val nz = nr[corner * 3 + 2]
                // m 按列存：m[c * 4 + r]；余子式矩阵 cXY 的 X 是行
                var wx = (c00 * nx + c10 * ny + c20 * nz) * sg
                var wy = (c01 * nx + c11 * ny + c21 * nz) * sg
                var wz = (c02 * nx + c12 * ny + c22 * nz) * sg
                val l = sqrt(wx * wx + wy * wy + wz * wz)
                if (l > 1e-20f) { wx /= l; wy /= l; wz /= l } else { wx = 0f; wy = 0f; wz = 0f }
                b.addVertex(
                    m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14],
                    wx, wy, wz, if (uv != null) uv[corner * 2] else 0f, if (uv != null) uv[corner * 2 + 1] else 0f,
                )
            }
            val tris = geo.tris; val out = geo.cornerOut
            var t = 0
            while (t + 2 < tris.size) {
                val slot = geo.polyMat[geo.triPoly[t / 3]]
                val mat = slotMaterial[if (slot < slotIndex.size && slotIndex[slot] >= 0) slotIndex[slot] else 0]
                val a = base + out[tris[t]]; val bb = base + out[tris[t + 1]]; val c = base + out[tris[t + 2]]
                if (flip) b.addTriangle(mat, a, c, bb) else b.addTriangle(mat, a, bb, c)
                t += 3
            }
        }

        /** 这个物体的这个材质对应模型里的哪个部件。 */
        private fun part(owner: Int, materialAddress: Long, objectName: String, multi: Boolean): Int {
            val src = material(owner, materialAddress)
            val key = if (perObject) "$objectName\u0000$materialAddress" else "\u0000$materialAddress"
            return parts.getOrPut(key) {
                val m = EditMesh.copyMaterial(src)
                if (perObject) m.name = if (multi && src.name.isNotBlank()) "$objectName · ${src.name}" else objectName.ifBlank { src.name }
                b.addMaterial(m)
            }
        }

        // ------------------------------------------------------------------ 网格数据

        private class Layer(val type: Int, val name: String, val data: BlendFile.Block?, val activeRender: Int)

        private fun layers(me: BlendFile.Ref, which: String): List<Layer> {
            val cd = me.sub(which) ?: return emptyList()
            val n = cd.int("totlayer")
            val block = cd.target("layers") ?: return emptyList()
            val out = ArrayList<Layer>(n)
            for (i in 0 until n) {
                val l = f.ref(block, "CustomDataLayer", i) ?: break
                out.add(Layer(l.int("type"), l.str("name"), l.target("data"), l.int("active_rnd")))
            }
            return out
        }

        /** 5.0 起的属性：名字、数据类型、作用域、数据块、是不是"所有元素同一个值"。 */
        private class Attr(val name: String, val type: Int, val domain: Int, val data: BlendFile.Block?, val single: Boolean)

        private fun attributes(me: BlendFile.Ref): List<Attr> {
            val st = me.sub("attribute_storage") ?: return emptyList()
            val n = st.int("dna_attributes_num")
            val block = st.target("dna_attributes") ?: return emptyList()
            val out = ArrayList<Attr>(n)
            for (i in 0 until n) {
                val a = f.ref(block, "Attribute", i) ?: break
                val nameBlock = a.target("name")
                val name = if (nameBlock != null) f.string(nameBlock.offset, nameBlock.length) else ""
                val single = a.int("storage_type") == 1
                val holder = a.ref("data", if (single) "AttributeSingle" else "AttributeArray")
                out.add(Attr(name, a.int("data_type"), a.int("domain"), holder?.target("data"), single))
            }
            return out
        }

        private fun floatsOf(block: BlendFile.Block?, count: Int, stride: Int, offset: Int, n: Int): FloatArray? {
            if (block == null || count <= 0 || stride <= 0 || count.toLong() * stride > block.length) return null
            val out = FloatArray(count * n)
            var p = block.offset + offset
            for (i in 0 until count) { for (k in 0 until n) out[i * n + k] = f.f32(p + k * 4); p += stride }
            return out
        }

        private fun intsOf(block: BlendFile.Block?, count: Int, stride: Int, offset: Int, bytes: Int = 4): IntArray? {
            if (block == null || count <= 0 || stride <= 0 || count.toLong() * stride > block.length) return null
            val out = IntArray(count)
            var p = block.offset + offset
            for (i in 0 until count) { out[i] = when (bytes) { 1 -> f.u8(p); 2 -> f.i16(p); else -> f.i32(p) }; p += stride }
            return out
        }

        private fun geometry(me: BlendFile.Ref, angle: Float): Geo? {
            val totvert = me.int("totvert")
            val totpoly = me.int("totpoly")
            val totloop = me.int("totloop")
            val totface = me.int("totface")
            if (totvert <= 0 || totvert > 50_000_000) return null
            val vdata = layers(me, "vdata")
            val attrs = attributes(me)
            fun attr(name: String, domain: Int): Attr? = attrs.firstOrNull { it.domain == domain && it.name == name }

            var verts: FloatArray? = null
            var corners: IntArray? = null
            var polyStart: IntArray? = null
            var polyMat: IntArray? = null
            var smooth: BooleanArray? = null
            var uv: FloatArray? = null

            // ---- 顶点位置
            attr("position", DOMAIN_POINT)?.let { if (!it.single) verts = floatsOf(it.data, totvert, 12, 0, 3) }
            if (verts == null) vdata.firstOrNull { it.type == CD_PROP_FLOAT3 && it.name == "position" }?.let { verts = floatsOf(it.data, totvert, 12, 0, 3) }
            if (verts == null) {
                val mv = f.struct("MVert")
                val block = vdata.firstOrNull { it.type == CD_MVERT }?.data ?: me.target("mvert")
                if (mv != null) verts = floatsOf(block, totvert, mv.size, mv.fields["co"]?.offset ?: 0, 3)
            }
            val v = verts ?: return null

            if (totpoly > 0 && totloop > 0) {
                val ldata = layers(me, "ldata")
                val pdata = layers(me, "pdata")
                // ---- 每个角用哪个顶点
                attr(".corner_vert", DOMAIN_CORNER)?.let { if (!it.single) corners = intsOf(it.data, totloop, 4, 0) }
                if (corners == null) ldata.firstOrNull { it.type == CD_PROP_INT32 && it.name == ".corner_vert" }?.let { corners = intsOf(it.data, totloop, 4, 0) }
                if (corners == null) {
                    val ml = f.struct("MLoop")
                    val block = ldata.firstOrNull { it.type == CD_MLOOP }?.data ?: me.target("mloop")
                    if (ml != null) corners = intsOf(block, totloop, ml.size, ml.fields["v"]?.offset ?: 0)
                }
                // ---- 每个面从哪个角开始
                me.target("poly_offset_indices")?.let { blk -> intsOf(blk, totpoly + 1, 4, 0)?.let { polyStart = it } }
                var legacyFlags: IntArray? = null
                if (polyStart == null) {
                    val mp = f.struct("MPoly")
                    val block = pdata.firstOrNull { it.type == CD_MPOLY }?.data ?: me.target("mpoly")
                    if (mp != null && block != null) {
                        val starts = intsOf(block, totpoly, mp.size, mp.fields["loopstart"]?.offset ?: 0)
                        val counts = intsOf(block, totpoly, mp.size, mp.fields["totloop"]?.offset ?: 4)
                        if (starts != null && counts != null) {
                            // 老格式的面不一定按角的顺序排：整理成"起点表"的样子（角重新排一遍）
                            val ordered = (0 until totpoly).all { i -> starts[i] == (if (i == 0) 0 else starts[i - 1] + counts[i - 1]) } && starts[totpoly - 1] + counts[totpoly - 1] <= totloop
                            val src = corners
                            if (ordered) {
                                polyStart = IntArray(totpoly + 1).also { ps -> for (i in 0 until totpoly) ps[i] = starts[i]; ps[totpoly] = starts[totpoly - 1] + counts[totpoly - 1] }
                            } else if (src != null) {
                                return unorderedPolys(me, v, src, starts, counts, block, mp, ldata, angle)
                            }
                            polyMat = intsOf(block, totpoly, mp.size, mp.fields["mat_nr"]?.offset ?: 8, 2)
                            legacyFlags = intsOf(block, totpoly, mp.size, mp.fields["flag"]?.offset ?: 10, 1)
                        }
                    }
                }
                val cs = corners
                val ps = polyStart
                if (cs != null && ps != null) {
                    // ---- 面的材质槽
                    if (polyMat == null) {
                        val a = attr("material_index", DOMAIN_FACE)
                        if (a != null && a.data != null) {
                            polyMat = if (a.single) IntArray(totpoly) { f.i32(a.data.offset) } else intsOf(a.data, totpoly, 4, 0)
                        }
                        if (polyMat == null) pdata.firstOrNull { it.type == CD_PROP_INT32 && it.name == "material_index" }?.let { polyMat = intsOf(it.data, totpoly, 4, 0) }
                    }
                    // ---- 平滑 / 平直
                    val flags = legacyFlags
                    if (flags != null) smooth = BooleanArray(totpoly) { (flags[it] and ME_SMOOTH) != 0 }
                    else {
                        var sharp: BooleanArray? = null
                        val a = attr("sharp_face", DOMAIN_FACE)
                        if (a != null && a.data != null) {
                            sharp = if (a.single) BooleanArray(totpoly) { f.u8(a.data.offset) != 0 } else if (a.data.length >= totpoly) BooleanArray(totpoly) { f.u8(a.data.offset + it) != 0 } else null
                        }
                        if (sharp == null) pdata.firstOrNull { it.type == CD_PROP_BOOL && it.name == "sharp_face" }?.data?.let { blk -> if (blk.length >= totpoly) sharp = BooleanArray(totpoly) { f.u8(blk.offset + it) != 0 } }
                        val s = sharp
                        smooth = BooleanArray(totpoly) { s == null || !s[it] }
                    }
                    // ---- UV
                    var uvName = ""
                    for (key in arrayOf("default_uv_map_attribute", "active_uv_map_attribute")) {
                        if (uvName.isNotEmpty()) break
                        me.target(key)?.let { uvName = f.string(it.offset, it.length) }
                    }
                    val uvAttr = attrs.firstOrNull { it.domain == DOMAIN_CORNER && it.type == ATTR_FLOAT2 && it.name == uvName && !it.single }
                        ?: attrs.firstOrNull { it.domain == DOMAIN_CORNER && it.type == ATTR_FLOAT2 && !it.name.startsWith(".") && !it.single }
                    if (uvAttr != null) uv = floatsOf(uvAttr.data, totloop, 8, 0, 2)
                    if (uv == null) {
                        val newStyle = ldata.filter { it.type == CD_PROP_FLOAT2 && !it.name.startsWith(".") }
                        val oldStyle = ldata.filter { it.type == CD_MLOOPUV }
                        if (newStyle.isNotEmpty()) uv = floatsOf((newStyle.getOrNull(newStyle[0].activeRender) ?: newStyle[0]).data, totloop, 8, 0, 2)
                        else if (oldStyle.isNotEmpty()) {
                            val st = f.struct("MLoopUV")
                            uv = floatsOf((oldStyle.getOrNull(oldStyle[0].activeRender) ?: oldStyle[0]).data, totloop, st?.size ?: 12, st?.fields?.get("uv")?.offset ?: 0, 2)
                        }
                    }
                    val geo = Geo(v, cs, ps, polyMat ?: IntArray(totpoly), smooth ?: BooleanArray(totpoly) { true }, uv, crease(me, angle))
                    geo.sharp = sharpEdges(me, attrs, flags != null, geo.crease)
                    return finish(me, geo)
                }
            }
            // ---- 2.62 及更早：只有三角形 / 四边形的 MFace
            if (totface > 0) {
                val fdata = layers(me, "fdata")
                val mf = f.struct("MFace") ?: return null
                val block = fdata.firstOrNull { it.type == CD_MFACE }?.data ?: me.target("mface") ?: return null
                if (totface.toLong() * mf.size > block.length) return null
                val tf = f.struct("MTFace")
                val uvBlock = fdata.filter { it.type == CD_MTFACE }.let { it.getOrNull(it.firstOrNull()?.activeRender ?: 0) ?: it.firstOrNull() }?.data ?: me.target("mtface")
                val hasUv = tf != null && uvBlock != null && totface.toLong() * tf.size <= uvBlock.length
                val cornerList = IntArray(totface * 4)
                val start = IntArray(totface + 1)
                val mats = IntArray(totface)
                val sm = BooleanArray(totface)
                val uvs = if (hasUv) FloatArray(totface * 8) else null
                var n = 0
                for (i in 0 until totface) {
                    val p = block.offset + i * mf.size
                    val v4 = f.i32(p + 12)
                    val count = if (v4 != 0) 4 else 3
                    start[i] = n
                    for (k in 0 until count) {
                        cornerList[n] = f.i32(p + k * 4)
                        if (uvs != null && uvBlock != null && tf != null) { val q = uvBlock.offset + i * tf.size + k * 8; uvs[n * 2] = f.f32(q); uvs[n * 2 + 1] = f.f32(q + 4) }
                        n++
                    }
                    mats[i] = f.i16(p + (mf.fields["mat_nr"]?.offset ?: 16))
                    sm[i] = (f.u8(p + (mf.fields["flag"]?.offset ?: 19)) and ME_SMOOTH) != 0
                }
                start[totface] = n
                return finish(me, Geo(v, cornerList.copyOf(n), start, mats, sm, uvs?.copyOf(n * 2), crease(me, angle)))
            }
            return null
        }

        /**
         * 标成锐边的边。4.1 起锐边总是算数（「按角度平滑」、导入别的格式时带进来的硬边都记在这里）；
         * 更早的版本只有开了「自动平滑」才看它。
         */
        private fun sharpEdges(me: BlendFile.Ref, attrs: List<Attr>, legacy: Boolean, crease: Float): HashSet<Long>? {
            val totedge = me.int("totedge")
            if (totedge <= 0) return null
            val edata = layers(me, "edata")
            var pairs: IntArray? = null
            var flags: BooleanArray? = null
            if (legacy) {
                if (crease < 0f) return null
                val st = f.struct("MEdge") ?: return null
                val block = edata.firstOrNull { it.type == CD_MEDGE }?.data ?: me.target("medge") ?: return null
                if (totedge.toLong() * st.size > block.length) return null
                val o1 = st.fields["v1"]?.offset ?: 0; val o2 = st.fields["v2"]?.offset ?: 4; val of = st.fields["flag"]?.offset ?: 10
                pairs = IntArray(totedge * 2) { f.i32(block.offset + (it / 2) * st.size + (if (it % 2 == 0) o1 else o2)) }
                flags = BooleanArray(totedge) { (f.i16(block.offset + it * st.size + of) and ME_SHARP) != 0 }
            } else {
                val sa = attrs.firstOrNull { it.domain == DOMAIN_EDGE && it.name == "sharp_edge" }
                if (sa != null && sa.data != null) {
                    flags = if (sa.single) { val on = f.u8(sa.data.offset) != 0; if (!on) return null; BooleanArray(totedge) { true } }
                    else if (sa.data.length >= totedge) BooleanArray(totedge) { f.u8(sa.data.offset + it) != 0 } else null
                }
                if (flags == null) edata.firstOrNull { it.type == CD_PROP_BOOL && it.name == "sharp_edge" }?.data?.let { blk -> if (blk.length >= totedge) flags = BooleanArray(totedge) { f.u8(blk.offset + it) != 0 } }
                if (flags == null) return null
                attrs.firstOrNull { it.domain == DOMAIN_EDGE && it.name == ".edge_verts" && !it.single }?.data?.let { blk -> if (blk.length >= totedge * 8) pairs = IntArray(totedge * 2) { f.i32(blk.offset + it * 4) } }
                if (pairs == null) edata.firstOrNull { it.type == CD_PROP_INT32_2D && it.name == ".edge_verts" }?.data?.let { blk -> if (blk.length >= totedge * 8) pairs = IntArray(totedge * 2) { f.i32(blk.offset + it * 4) } }
                if (pairs == null) {
                    val st = f.struct("MEdge")
                    val block = edata.firstOrNull { it.type == CD_MEDGE }?.data ?: me.target("medge")
                    if (st != null && block != null && totedge.toLong() * st.size <= block.length) pairs = IntArray(totedge * 2) { f.i32(block.offset + (it / 2) * st.size + (it % 2) * 4) }
                }
            }
            val pr = pairs ?: return null
            val fl = flags ?: return null
            val out = HashSet<Long>()
            for (e in 0 until totedge) if (fl[e]) out.add(edgeKey(pr[e * 2], pr[e * 2 + 1]))
            return if (out.isEmpty()) null else out
        }

        private fun edgeKey(a: Int, b: Int): Long = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()

        /** 老格式里面的顺序和角的顺序对不上的情况：把角按面重新排一遍。 */
        private fun unorderedPolys(me: BlendFile.Ref, v: FloatArray, src: IntArray, starts: IntArray, counts: IntArray, block: BlendFile.Block, mp: BlendFile.Struct, ldata: List<Layer>, angle: Float): Geo? {
            val n = starts.size
            val ps = IntArray(n + 1)
            var total = 0
            for (i in 0 until n) { ps[i] = total; if (counts[i] < 0 || starts[i] < 0 || starts[i] + counts[i] > src.size) return null; total += counts[i] }
            ps[n] = total
            val cs = IntArray(total)
            val order = IntArray(total)
            for (i in 0 until n) for (k in 0 until counts[i]) { cs[ps[i] + k] = src[starts[i] + k]; order[ps[i] + k] = starts[i] + k }
            var uv: FloatArray? = null
            val old = ldata.filter { it.type == CD_MLOOPUV }
            if (old.isNotEmpty()) {
                val st = f.struct("MLoopUV")
                floatsOf((old.getOrNull(old[0].activeRender) ?: old[0]).data, src.size, st?.size ?: 12, 0, 2)?.let { all -> uv = FloatArray(total * 2) { all[order[it / 2] * 2 + it % 2] } }
            }
            val mats = intsOf(block, n, mp.size, mp.fields["mat_nr"]?.offset ?: 8, 2) ?: IntArray(n)
            val flags = intsOf(block, n, mp.size, mp.fields["flag"]?.offset ?: 10, 1) ?: IntArray(n)
            return finish(me, Geo(v, cs, ps, mats, BooleanArray(n) { (flags[it] and ME_SMOOTH) != 0 }, uv, crease(me, angle)))
        }

        /** 「自动平滑」的夹角（4.1 之前存在网格上）。 */
        private fun crease(me: BlendFile.Ref, angle: Float): Float {
            if (angle > 0f) return angle
            if (f.version >= 401 || (me.int("flag") and ME_AUTOSMOOTH) == 0) return -1f
            val fl = me.struct.fields["smoothresh"] ?: return -1f
            val a = if (fl.type == "float") me.float("smoothresh") else Math.toRadians(me.int("smoothresh").toDouble()).toFloat()
            // 夹角设成 180° 也要算"开着自动平滑"：这时只有标出来的锐边起作用
            return if (a > 0f) minOf(a, 3.1415927f) else -1f
        }

        /** 三角化、算每个角的法线、合并重复的角。 */
        private fun finish(me: BlendFile.Ref, g: Geo): Geo? {
            val v = g.verts
            val nv = v.size / 3
            val cs = g.corners
            val ps = g.polyStart
            val np = g.polyCount
            if (ps.size != np + 1) return null
            for (i in 0 until np) if (ps[i] < 0 || ps[i + 1] < ps[i] || ps[i + 1] > cs.size) return null
            for (c in cs) if (c < 0 || c >= nv) return null
            for (i in 0 until nv) for (k in 0 until 3) { val x = v[i * 3 + k]; if (x == x) { if (x < g.min[k]) g.min[k] = x; if (x > g.max[k]) g.max[k] = x } else v[i * 3 + k] = 0f }
            // ---- 面法线（Newell 法：凹多边形也对；长度 = 面积的两倍，正好当权重）
            val fn = FloatArray(np * 3)
            var triCount = 0
            val slots = HashSet<Int>()
            for (p in 0 until np) {
                val s = ps[p]; val e = ps[p + 1]
                if (e - s < 3) continue
                triCount += e - s - 2
                if (g.polyMat[p] < 0 || g.polyMat[p] > 32767) g.polyMat[p] = 0
                slots.add(g.polyMat[p])
                var nx = 0f; var ny = 0f; var nz = 0f
                var a = cs[e - 1] * 3
                for (c in s until e) {
                    val bI = cs[c] * 3
                    nx += (v[a + 1] - v[bI + 1]) * (v[a + 2] + v[bI + 2])
                    ny += (v[a + 2] - v[bI + 2]) * (v[a] + v[bI])
                    nz += (v[a] - v[bI]) * (v[a + 1] + v[bI + 1])
                    a = bI
                }
                fn[p * 3] = nx; fn[p * 3 + 1] = ny; fn[p * 3 + 2] = nz
            }
            g.usedSlots = slots.sorted().toIntArray()
            // ---- 三角化
            val tris = IntArray(triCount * 3)
            val triPoly = IntArray(triCount)
            var t = 0
            val scratch = Triangulator()
            for (p in 0 until np) {
                val s = ps[p]; val n = ps[p + 1] - s
                if (n < 3) continue
                val before = t
                if (n == 3) { tris[t++] = s; tris[t++] = s + 1; tris[t++] = s + 2 }
                else t = scratch.run(v, cs, s, n, fn[p * 3], fn[p * 3 + 1], fn[p * 3 + 2], tris, t)
                for (k in before / 3 until t / 3) triPoly[k] = p
            }
            g.tris = tris; g.triPoly = triPoly
            // ---- 每个角的法线
            val normals = FloatArray(cs.size * 3)
            val cornerPoly = IntArray(cs.size)
            for (p in 0 until np) for (c in ps[p] until ps[p + 1]) cornerPoly[c] = p
            val unit = FloatArray(np * 3)
            for (p in 0 until np) {
                val l = sqrt(fn[p * 3] * fn[p * 3] + fn[p * 3 + 1] * fn[p * 3 + 1] + fn[p * 3 + 2] * fn[p * 3 + 2])
                if (l > 1e-30f) { unit[p * 3] = fn[p * 3] / l; unit[p * 3 + 1] = fn[p * 3 + 1] / l; unit[p * 3 + 2] = fn[p * 3 + 2] / l }
            }
            val sharp = g.sharp
            if (g.crease < 0f && sharp == null) {
                // 没有锐边也不限夹角：一个顶点周围所有平滑面的法线按面积加权平均
                val vn = FloatArray(nv * 3)
                for (c in cs.indices) {
                    val p = cornerPoly[c]
                    if (!g.polySmooth[p]) continue
                    val o = cs[c] * 3
                    vn[o] += fn[p * 3]; vn[o + 1] += fn[p * 3 + 1]; vn[o + 2] += fn[p * 3 + 2]
                }
                for (c in cs.indices) {
                    val p = cornerPoly[c]
                    val o = cs[c] * 3
                    var x = unit[p * 3]; var y = unit[p * 3 + 1]; var z = unit[p * 3 + 2]
                    if (g.polySmooth[p]) {
                        val l = sqrt(vn[o] * vn[o] + vn[o + 1] * vn[o + 1] + vn[o + 2] * vn[o + 2])
                        if (l > 1e-30f) { x = vn[o] / l; y = vn[o + 1] / l; z = vn[o + 2] / l }
                    }
                    normals[c * 3] = x; normals[c * 3 + 1] = y; normals[c * 3 + 2] = z
                }
            } else {
                // 有锐边 / 「自动平滑」的夹角：绕着每个顶点，隔着一条"平滑的边"相邻的面才并在一起平均。
                // 平滑的边 = 正好被两个平滑面共用、没标成锐边、两个面的夹角不超过阈值。
                val group = IntArray(cs.size) { it }
                fun find(x: Int): Int { var r = x; while (group[r] != r) r = group[r]; var c = x; while (group[c] != r) { val n = group[c]; group[c] = r; c = n }; return r }
                fun union(a: Int, b2: Int) { val ra = find(a); val rb = find(b2); if (ra != rb) group[rb] = ra }
                val limit = if (g.crease >= 0f) cos(g.crease) else -2f
                val firstUse = LongIntMap(cs.size)
                for (p in 0 until np) {
                    if (!g.polySmooth[p]) continue
                    val s = ps[p]; val e = ps[p + 1]
                    if (e - s < 3) continue
                    for (c in s until e) {
                        val nextC = if (c + 1 < e) c + 1 else s
                        val key = edgeKey(cs[c], cs[nextC])
                        if (sharp != null && key in sharp) continue
                        val other = firstUse.get(key)
                        if (other == -1) { firstUse.put(key, c); continue }
                        if (other < 0) continue                       // 三个以上的面共用一条边：当成锐边
                        firstUse.put(key, -2)
                        val q = cornerPoly[other]
                        if (unit[p * 3] * unit[q * 3] + unit[p * 3 + 1] * unit[q * 3 + 1] + unit[p * 3 + 2] * unit[q * 3 + 2] < limit) continue
                        val otherNext = if (other + 1 < ps[q + 1]) other + 1 else ps[q]
                        if (cs[c] == cs[otherNext] && cs[nextC] == cs[other]) { union(c, otherNext); union(nextC, other) }
                        else if (cs[c] == cs[other] && cs[nextC] == cs[otherNext]) { union(c, other); union(nextC, otherNext) }
                    }
                }
                val sum = FloatArray(cs.size * 3)
                for (c in cs.indices) {
                    val p = cornerPoly[c]
                    if (!g.polySmooth[p]) continue
                    val r = find(c) * 3
                    sum[r] += fn[p * 3]; sum[r + 1] += fn[p * 3 + 1]; sum[r + 2] += fn[p * 3 + 2]
                }
                for (c in cs.indices) {
                    val p = cornerPoly[c]
                    var x = unit[p * 3]; var y = unit[p * 3 + 1]; var z = unit[p * 3 + 2]
                    if (g.polySmooth[p]) {
                        val r = find(c) * 3
                        val l = sqrt(sum[r] * sum[r] + sum[r + 1] * sum[r + 1] + sum[r + 2] * sum[r + 2])
                        if (l > 1e-30f) { x = sum[r] / l; y = sum[r + 1] / l; z = sum[r + 2] / l }
                    }
                    normals[c * 3] = x; normals[c * 3 + 1] = y; normals[c * 3 + 2] = z
                }
            }
            g.normals = normals
            // ---- 位置、法线、UV 都一样的角并成一个输出顶点
            val uv = g.uv
            val map = LongIntMap(tris.size)
            val cornerOut = IntArray(cs.size) { -1 }
            val outCorner = com.lumi.mmd.engine.util.IntList(cs.size)
            for (k in tris) {
                if (cornerOut[k] >= 0) continue
                val nKey = (Math.round(normals[k * 3] * 511f) + 512) or ((Math.round(normals[k * 3 + 1] * 511f) + 512) shl 10) or ((Math.round(normals[k * 3 + 2] * 511f) + 512) shl 20)
                val key = MeshBuilder.hashKey(cs[k], nKey, if (uv != null) java.lang.Float.floatToRawIntBits(uv[k * 2] + 0f) else 0, if (uv != null) java.lang.Float.floatToRawIntBits(uv[k * 2 + 1] + 0f) else 0)
                val found = map.get(key)
                if (found >= 0) cornerOut[k] = found
                else { val o = outCorner.size; outCorner.add(k); map.put(key, o); cornerOut[k] = o }
            }
            g.cornerOut = cornerOut
            g.outCorner = outCorner.toArray()
            return g
        }

        // ------------------------------------------------------------------ 材质

        private fun material(owner: Int, address: Long): MaterialData {
            if (address == 0L) return defaultMaterial ?: b.newMaterial("默认材质").also {
                it.alphaMode = MaterialData.ALPHA_OPAQUE
                it.flags = it.flags or MaterialData.FLAG_DOUBLE_SIDED
                defaultMaterial = it
            }
            return baseMaterials.getOrPut(address) {
                val mat = f.at(owner, address, "Material")?.takeIf { it.block.code == "MA" }
                if (mat == null) b.newMaterial("材质").also { it.flags = it.flags or MaterialData.FLAG_DOUBLE_SIDED } else convert(mat)
            }
        }

        private fun convert(mat: BlendFile.Ref): MaterialData {
            val m = b.newMaterial(idName(mat))
            val rgb = floatArrayOf(mat.float("r", default = 0.8f), mat.float("g", default = 0.8f), mat.float("b", default = 0.8f))
            var alpha = 1f
            var image: BlendFile.Ref? = null
            var rough = 0.5f
            var metal = 0f
            var emission: FloatArray? = null
            var alphaFromTexture = false
            var unlit = false
            val tree = if (mat.int("use_nodes") != 0 || f.version >= 500) mat.ref("nodetree", "bNodeTree") else null
            val nodes = tree?.list("nodes", "bNode") ?: emptyList()
            if (nodes.isNotEmpty() && tree != null) {
                val into = HashMap<Long, BlendFile.Ref>()
                for (l in tree.list("links", "bNodeLink")) into[l.ptr("tosock")] = l
                val byAddress = HashMap<Long, BlendFile.Ref>()
                for (n in nodes) byAddress[n.address] = n
                fun inputs(n: BlendFile.Ref) = n.list("inputs", "bNodeSocket")
                fun socket(n: BlendFile.Ref, vararg names: String): BlendFile.Ref? {
                    val all = inputs(n)
                    for (name in names) all.firstOrNull { it.str("identifier") == name || it.str("name") == name }?.let { return it }
                    return null
                }
                fun source(s: BlendFile.Ref?): BlendFile.Ref? = s?.let { into[it.address] }?.let { byAddress[it.ptr("fromnode")] }
                fun rgba(s: BlendFile.Ref?): FloatArray? = s?.ref("default_value", "bNodeSocketValueRGBA")?.floats("value", 4)
                // 接了线的输入口，上面留着的默认值是不算数的
                fun number(s: BlendFile.Ref?, d: Float): Float = if (s == null || source(s) != null) d else s.ref("default_value", "bNodeSocketValueFloat")?.float("value", default = d) ?: d
                fun imageOf(s: BlendFile.Ref?, depth: Int): BlendFile.Ref? {
                    if (depth > 8) return null
                    val n = source(s) ?: return null
                    if (n.str("idname") == "ShaderNodeTexImage") return n.ref("id", "Image")?.takeIf { it.block.code == "IM" }
                    for (i in inputs(n)) imageOf(i, depth + 1)?.let { return it }
                    return null
                }
                fun isShader(n: BlendFile.Ref): Boolean { val id = n.str("idname"); return id.startsWith("ShaderNodeBsdf") || id == "ShaderNodeEmission" || id == "ShaderNodeEeveeSpecular" || id == "ShaderNodeSubsurfaceScattering" }
                // 从「材质输出」的表面往上找到第一个着色器（中间可能隔着混合着色器）
                fun shaderOf(s: BlendFile.Ref?, depth: Int): BlendFile.Ref? {
                    if (depth > 8) return null
                    val n = source(s) ?: return null
                    if (isShader(n)) return n
                    for (i in inputs(n)) shaderOf(i, depth + 1)?.let { return it }
                    return null
                }
                val output = nodes.firstOrNull { it.str("idname") == "ShaderNodeOutputMaterial" && source(socket(it, "Surface")) != null }
                val shader = (output?.let { shaderOf(socket(it, "Surface"), 0) })
                    ?: nodes.firstOrNull { it.str("idname") == "ShaderNodeBsdfPrincipled" } ?: nodes.firstOrNull { isShader(it) }
                if (shader != null) {
                    val kind = shader.str("idname")
                    val colorSocket = socket(shader, "Base Color", "Color")
                    rgba(colorSocket)?.let { rgb[0] = it[0]; rgb[1] = it[1]; rgb[2] = it[2] }
                    image = imageOf(colorSocket, 0)
                    if (kind == "ShaderNodeBsdfPrincipled") {
                        val a = socket(shader, "Alpha")
                        alpha = number(a, 1f).coerceIn(0f, 1f)
                        if (source(a) != null) alphaFromTexture = true
                        rough = number(socket(shader, "Roughness"), 0.5f)
                        metal = number(socket(shader, "Metallic"), 0f)
                        val es = socket(shader, "Emission Color", "Emission")
                        val strengthSocket = socket(shader, "Emission Strength")
                        val strength = if (strengthSocket != null) number(strengthSocket, if (source(strengthSocket) != null) 1f else 0f) else 1f
                        if (source(es) == null) rgba(es)?.let { c -> if (strength > 0f && (c[0] > 0f || c[1] > 0f || c[2] > 0f)) emission = floatArrayOf(c[0] * strength, c[1] * strength, c[2] * strength) }
                    } else if (kind == "ShaderNodeEmission") {
                        unlit = true
                    } else if (kind == "ShaderNodeBsdfGlossy" || kind == "ShaderNodeBsdfAnisotropic") {
                        rough = number(socket(shader, "Roughness"), 0.3f); metal = 0.8f
                    } else if (kind == "ShaderNodeBsdfTransparent" || kind == "ShaderNodeBsdfGlass" || kind == "ShaderNodeBsdfRefraction") {
                        alpha = 0.35f
                    }
                }
                // 没找到认识的着色器（比如整个包在节点组里）：树里随便哪张图当基础色贴图
                if (image == null && shader == null) image = nodes.firstOrNull { it.str("idname") == "ShaderNodeTexImage" && it.ptr("id") != 0L }?.ref("id", "Image")?.takeIf { it.block.code == "IM" }
            } else {
                // 没用节点：2.7x 的 Blender Internal 材质，或者只设了视图颜色
                if (mat.has("mtex")) {
                    if ((mat.int("mode") and MA_TRANSP) != 0) alpha = mat.float("alpha", default = 1f).coerceIn(0f, 1f)
                    for (i in 0 until 18) {
                        val mt = mat.ref("mtex", "MTex", i) ?: continue
                        if ((mt.int("mapto") and MAP_COL) == 0) continue
                        val tex = mt.ref("tex", "Tex") ?: continue
                        if (tex.int("type") != TEX_IMAGE) continue
                        image = tex.ref("ima", "Image")?.takeIf { it.block.code == "IM" } ?: continue
                        break
                    }
                    rough = 1f - mat.float("spec", default = 0.5f).coerceIn(0f, 1f) * 0.6f
                } else {
                    rough = mat.float("roughness", default = 0.5f)
                    metal = mat.float("metallic", default = 0f)
                }
            }
            val tex = image?.let { texture(it) } ?: -1
            m.diffuse[0] = rgb[0].coerceIn(0f, 1f); m.diffuse[1] = rgb[1].coerceIn(0f, 1f); m.diffuse[2] = rgb[2].coerceIn(0f, 1f); m.diffuse[3] = alpha
            if (tex >= 0) { m.texture = tex; m.diffuse[0] = 1f; m.diffuse[1] = 1f; m.diffuse[2] = 1f }
            // PBR → Blinn-Phong 的粗略换算（和 glTF 导入用同一套）
            val r = rough.coerceIn(0.05f, 1f)
            val mt = metal.coerceIn(0f, 1f)
            val gloss = 1f - r
            m.shininess = if (gloss > 0.02f) (2f / (r * r * r * r) - 2f).coerceIn(4f, 400f) else 0f
            val specBase = 0.04f + 0.5f * gloss * gloss
            for (k in 0 until 3) m.specular[k] = (specBase * (1f - mt) + m.diffuse[k] * mt * (0.25f + 0.75f * gloss)).coerceIn(0f, 1f)
            if (mt > 0.5f) for (k in 0 until 3) m.diffuse[k] *= 1f - 0.45f * mt
            emission?.let { for (k in 0 until 3) m.emissive[k] = it[k].coerceIn(0f, 4f) }
            if (unlit) m.shading = MaterialData.SHADING_UNLIT
            val blend = mat.int("blend_method")
            m.alphaMode = when {
                alpha < 0.999f -> MaterialData.ALPHA_BLEND
                tex < 0 -> MaterialData.ALPHA_OPAQUE
                blend == MA_BM_BLEND || blend == MA_BM_HASHED -> if (alphaFromTexture) MaterialData.ALPHA_BLEND else MaterialData.ALPHA_MASK
                else -> MaterialData.ALPHA_MASK
            }
            if (m.alphaMode == MaterialData.ALPHA_MASK) m.alphaCutoff = if (blend == MA_BM_CLIP) mat.float("alpha_threshold", default = 0.5f).coerceIn(0.01f, 0.99f) else 0.5f
            // Blender 默认两面都画；只有勾了「背面剔除」才是单面
            if ((mat.int("blend_flag") and MA_BL_CULL_BACKFACE) == 0) m.flags = m.flags or MaterialData.FLAG_DOUBLE_SIDED
            return m
        }

        private fun texture(image: BlendFile.Ref): Int = textures.getOrPut(image.address) {
            var path = image.str("name").ifEmpty { image.str("filepath") }.replace('\\', '/')
            val packed = image.ref("packedfile", "PackedFile") ?: image.list("packedfiles", "ImagePackedFile").firstOrNull()?.ref("packedfile", "PackedFile")
            if (packed != null) {
                val size = packed.int("size")
                val data = packed.target("data")
                if (data != null && size > 0 && size <= data.length) {
                    val name = path.substringAfterLast('/').ifEmpty { idName(image) }
                    // 打包在 .blend 里的贴图加起来常常有几百 MB：不留在内存里，解到 .blend 旁边的文件夹，之后和普通的外部贴图一样按需读
                    unpack(name, data.offset, size)?.let { return@getOrPut b.texture(it) }
                    if (!Memory.fits(size.toLong() * 2)) return@getOrPut -1
                    return@getOrPut b.embeddedTexture(name, f.bytes(data.offset, size))
                }
            }
            if (path.isBlank()) return@getOrPut -1
            path = path.replace("<UDIM>", "1001")
            // "//" 开头 = 相对 .blend 所在的文件夹；别的是作者电脑上的绝对路径，只留文件名
            path = if (path.startsWith("//")) path.substring(2) else path.substringAfterLast('/')
            // 指到 .blend 所在文件夹外面去的（"../贴图/墙.png"）：把前面的 ../ 去掉，剩下的交给按文件名查找
            while (path.startsWith("../")) path = path.substring(3)
            if (path.isBlank()) -1 else b.texture(path)
        }

        /** 把一张打包的贴图写到 "<名字>.blend.tex/" 里，返回相对 .blend 所在文件夹的路径；写不了（只读的地方）返回 null。 */
        private fun unpack(name: String, offset: Int, size: Int): String? {
            val src = source ?: return null
            val dir = File(src.parentFile ?: return null, src.name + ".tex")
            val safe = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").ifBlank { "image" }
            val file = File(dir, "%03d_%s".format(packedToDisk, safe))
            packedToDisk++
            try {
                // 上次已经解过（同名、同样大小）就不再写
                if (!file.isFile || file.length() != size.toLong()) {
                    if (!dir.isDirectory && !dir.mkdirs()) return null
                    val tmp = File(dir, file.name + ".part")
                    java.io.BufferedOutputStream(java.io.FileOutputStream(tmp), 64 * 1024).use { f.copyTo(offset, size, it) }
                    if (file.exists()) file.delete()
                    if (!tmp.renameTo(file)) { tmp.delete(); return null }
                }
                return dir.name + "/" + file.name
            } catch (e: java.io.IOException) {
                return null
            } catch (e: SecurityException) {
                return null
            }
        }

        // ------------------------------------------------------------------ 矩阵（和 Blender 一样按列存：m[列 * 4 + 行]）

        private fun identity(): FloatArray = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        private fun translation(x: Float, y: Float, z: Float): FloatArray = identity().also { it[12] = x; it[13] = y; it[14] = z }

        private fun mul(a: FloatArray, c: FloatArray): FloatArray {
            val o = FloatArray(16)
            for (col in 0 until 4) for (row in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += a[k * 4 + row] * c[col * 4 + k]
                o[col * 4 + row] = s
            }
            return o
        }

        private fun mul3(a: FloatArray, c: FloatArray): FloatArray {
            val o = FloatArray(9)
            for (col in 0 until 3) for (row in 0 until 3) {
                var s = 0f
                for (k in 0 until 3) s += a[k * 3 + row] * c[col * 3 + k]
                o[col * 3 + row] = s
            }
            return o
        }

        private fun axisRotation(axis: Int, angle: Float): FloatArray {
            val c = cos(angle); val s = sin(angle)
            val m = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            val a = (axis + 1) % 3; val bx = (axis + 2) % 3
            m[a * 3 + a] = c; m[a * 3 + bx] = s; m[bx * 3 + a] = -s; m[bx * 3 + bx] = c
            return m
        }

        /** 欧拉角：mode 1..6 = XYZ XZY YXZ YZX ZXY ZYX（先绕第一个字母的轴转）。 */
        private fun euler(e: FloatArray, mode: Int): FloatArray {
            val order = when (mode) { 2 -> intArrayOf(0, 2, 1); 3 -> intArrayOf(1, 0, 2); 4 -> intArrayOf(1, 2, 0); 5 -> intArrayOf(2, 0, 1); 6 -> intArrayOf(2, 1, 0); else -> intArrayOf(0, 1, 2) }
            return mul3(axisRotation(order[2], e[order[2]]), mul3(axisRotation(order[1], e[order[1]]), axisRotation(order[0], e[order[0]])))
        }

        /** 四元数（w, x, y, z）。 */
        private fun quat(q: FloatArray): FloatArray {
            var w = q[0]; var x = q[1]; var y = q[2]; var z = q[3]
            val l = sqrt(w * w + x * x + y * y + z * z)
            if (l < 1e-12f) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            w /= l; x /= l; y /= l; z /= l
            return floatArrayOf(
                1f - 2f * (y * y + z * z), 2f * (x * y + w * z), 2f * (x * z - w * y),
                2f * (x * y - w * z), 1f - 2f * (x * x + z * z), 2f * (y * z + w * x),
                2f * (x * z + w * y), 2f * (y * z - w * x), 1f - 2f * (x * x + y * y),
            )
        }

        private fun axisAngle(axis: FloatArray, angle: Float): FloatArray {
            val l = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
            if (l < 1e-12f) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            val s = sin(angle * 0.5f) / l
            return quat(floatArrayOf(cos(angle * 0.5f), axis[0] * s, axis[1] * s, axis[2] * s))
        }
    }

    /** 多边形三角化（耳切法）：凹的也能切对；切不动的退化情况退回扇形，保证每个 n 边形出 n-2 个三角形。 */
    private class Triangulator {
        private var idx = IntArray(16)
        private var px = FloatArray(16)
        private var py = FloatArray(16)

        fun run(v: FloatArray, corners: IntArray, start: Int, n: Int, nx: Float, ny: Float, nz: Float, out: IntArray, at: Int): Int {
            var t = at
            if (n > idx.size) { idx = IntArray(n * 2); px = FloatArray(n * 2); py = FloatArray(n * 2) }
            // 投影到法线分量最大的那个平面上，并保证投影后是逆时针
            val ax = abs(nx); val ay = abs(ny); val az = abs(nz)
            val drop = if (ax >= ay && ax >= az) 0 else if (ay >= az) 1 else 2
            val sign = (if (drop == 0) nx else if (drop == 1) ny else nz) >= 0f
            val u = (drop + 1) % 3; val w = (drop + 2) % 3
            for (i in 0 until n) {
                val o = corners[start + i] * 3
                idx[i] = start + i
                px[i] = v[o + u]; py[i] = if (sign) v[o + w] else -v[o + w]
            }
            var count = n
            var guard = 0
            while (count > 3 && guard < n * n + 16) {
                var cut = false
                var i = 0
                while (i < count) {
                    val a = (i + count - 1) % count; val c = (i + 1) % count
                    val x0 = px[a]; val y0 = py[a]; val x1 = px[i]; val y1 = py[i]; val x2 = px[c]; val y2 = py[c]
                    val cross = (x1 - x0) * (y2 - y1) - (y1 - y0) * (x2 - x1)
                    if (cross > 1e-12f) {
                        var inside = false
                        for (k in 0 until count) {
                            if (k == a || k == i || k == c) continue
                            val x = px[k]; val y = py[k]
                            // 和耳朵的某个角重合的点不算"在里面"（同一个位置用了两次的多边形）
                            if ((x == x0 && y == y0) || (x == x1 && y == y1) || (x == x2 && y == y2)) continue
                            val d0 = (x1 - x0) * (y - y0) - (y1 - y0) * (x - x0)
                            val d1 = (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1)
                            val d2 = (x0 - x2) * (y - y2) - (y0 - y2) * (x - x2)
                            if (d0 >= 0f && d1 >= 0f && d2 >= 0f) { inside = true; break }
                        }
                        if (!inside) {
                            out[t++] = idx[a]; out[t++] = idx[i]; out[t++] = idx[c]
                            for (k in i until count - 1) { idx[k] = idx[k + 1]; px[k] = px[k + 1]; py[k] = py[k + 1] }
                            count--
                            cut = true
                            break
                        }
                    }
                    i++
                }
                guard++
                if (!cut) break
            }
            // 剩下的（正常情况下正好一个三角形）按扇形切完
            for (k in 1 until count - 1) { out[t++] = idx[0]; out[t++] = idx[k]; out[t++] = idx[k + 1] }
            return t
        }
    }

    // ---- Blender 里的常量 ----
    private const val OB_MESH = 1
    private const val OB_CURVE = 2
    private const val OB_SURF = 3
    private const val OB_FONT = 4
    private const val OB_MBALL = 5
    private const val OB_CURVES = 27
    private const val OB_POINTCLOUD = 28
    private const val OB_VOLUME = 29
    private const val OB_HIDE_RENDER = 1 shl 2
    private const val OB_DUPLIVERTS = 1 shl 4
    private const val OB_DUPLICOLLECTION = 1 shl 8
    private const val OB_DUPLIFACES = 1 shl 9
    private const val COLLECTION_HIDE_RENDER = 1 shl 3
    private const val LAYER_COLLECTION_EXCLUDE = 1 shl 4
    private const val ME_SMOOTH = 1
    private const val ME_AUTOSMOOTH = 1 shl 5
    private const val ME_SHARP = 1 shl 9
    private const val CD_MVERT = 0
    private const val CD_MEDGE = 3
    private const val CD_MFACE = 4
    private const val CD_MTFACE = 5
    private const val CD_PROP_INT32 = 11
    private const val CD_MLOOPUV = 16
    private const val CD_MPOLY = 25
    private const val CD_MLOOP = 26
    private const val CD_PROP_INT32_2D = 46
    private const val CD_PROP_FLOAT3 = 48
    private const val CD_PROP_FLOAT2 = 49
    private const val CD_PROP_BOOL = 50
    private const val DOMAIN_POINT = 0
    private const val DOMAIN_EDGE = 1
    private const val DOMAIN_FACE = 2
    private const val DOMAIN_CORNER = 3
    private const val ATTR_FLOAT2 = 6
    private const val MOD_RENDER = 1 shl 1
    private const val MOD_MIRROR = 5
    private const val MOD_ARRAY = 12
    private const val MOD_NODES = 57
    private const val IDP_FLOAT = 2
    private const val IDP_DOUBLE = 8
    private const val MOD_MIR_AXIS_X = 1 shl 3
    private const val MOD_MIR_AXIS_Y = 1 shl 4
    private const val MOD_MIR_AXIS_Z = 1 shl 5
    private const val MOD_ARR_OFF_CONST = 1
    private const val MOD_ARR_OFF_RELATIVE = 2
    private const val MOD_ARR_OFF_OBJ = 4
    private const val MA_TRANSP = 1 shl 16
    private const val MAP_COL = 1
    private const val TEX_IMAGE = 8
    private const val MA_BM_CLIP = 3
    private const val MA_BM_HASHED = 4
    private const val MA_BM_BLEND = 5
    private const val MA_BL_CULL_BACKFACE = 1 shl 2

    /** 会改变形状、但这里读不了的修改器（不改形状的那些不提）。 */
    private val MODIFIER_NAMES = mapOf(
        1 to "表面细分", 2 to "晶格", 3 to "曲线", 4 to "建形", 6 to "精简", 7 to "波浪", 8 to "骨架", 9 to "钩挂", 11 to "布尔", 14 to "置换", 16 to "平滑",
        17 to "铸型", 18 to "网格形变", 20 to "粒子实例", 21 to "爆破", 22 to "布料", 24 to "倒角", 25 to "缩裹", 27 to "遮罩", 28 to "简易形变", 29 to "多级精度",
        33 to "实体化", 34 to "螺旋", 35 to "弯绕", 39 to "洋面", 41 to "重构网格", 42 to "蒙皮", 43 to "拉普拉斯平滑", 46 to "网格缓存", 47 to "拉普拉斯形变",
        48 to "线框", 51 to "矫正平滑", 52 to "网格序列缓存", 53 to "表面形变",
    )
}

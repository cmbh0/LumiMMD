package com.lumi.mmd.engine.script

import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.model.MorphData
import com.lumi.mmd.engine.model.RigidBodyData
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.scene.Scene
import java.util.WeakHashMap

/**
 * 给写脚本的人（主要是 AI）看的完整文档 = 固定的接口说明 + 「现在的场景」。
 *
 * 光有接口说明，AI 只能猜名字：骨骼叫「頭」还是「Head」、裙子的骨骼名字里带不带「スカート」、眨眼的表情叫什么、
 * 第几块材质是头发——猜错了脚本拿到的就是 nil。所以每次要文档的时候，把场景里每个模型实际有的骨骼、表情、材质、刚体
 * 当场读出来，按「脚本里怎么用」分好类，和接口说明拼成一份。
 *
 * 只读场景，不改任何东西；要在渲染线程上调用。
 */
object ScriptDoc {
    /** 每一类名字最多列多少个；超出的只说还有多少（m:bones() 这些接口随时能取到全部）。 */
    const val DEFAULT_LIMIT = 400

    /** 没有给脚本指定模型时 scene.main() 是谁：界面里选中的，否则第一个角色，否则第一个模型。 */
    fun mainModel(scene: Scene, selected: ModelInstance?, target: Int = 0): ModelInstance? {
        if (target != 0) scene.findById(target)?.let { return it }
        selected?.let { if (scene.models.contains(it)) return it }
        return scene.models.firstOrNull { it.isCharacter } ?: scene.models.firstOrNull()
    }

    /** 完整的一份：接口说明 + 现在的场景 + 示例的目录。 */
    fun full(scene: Scene, scripts: List<Script>, selected: ModelInstance?, only: ModelInstance? = null, limit: Int = DEFAULT_LIMIT): String =
        ScriptLibrary.REFERENCE + "\n\n\n" + live(scene, scripts, selected, only, limit) + "\n\n\n" + examples()

    /** 内置示例的目录。 */
    fun examples(): String = "内置示例\n--------\n" + ScriptLibrary.EXAMPLES.joinToString("\n") { "  ${it.name} — ${it.about}" }

    /**
     * 「现在的场景」：时间轴、镜头、物理、脚本，以及每个模型的骨骼 / 表情 / 材质 / 刚体。
     * @param only 只写这一个模型（场景那几行照写）
     */
    fun live(scene: Scene, scripts: List<Script>, selected: ModelInstance?, only: ModelInstance? = null, limit: Int = DEFAULT_LIMIT): String {
        val cap = if (limit <= 0) Int.MAX_VALUE else limit
        val sb = StringBuilder()
        sb.append("现在的场景（每次取这份文档时当场从应用里读出来的）\n")
        sb.append("======================================================================\n")
        sb.append("下面的名字是场景里真实存在的，写脚本时原样照抄（含全角字符和大小写），不要凭印象写别的模型的叫法。\n")
        sb.append("场景变了（加了模型、换了角色）之后这一段就旧了，重新取一次。\n\n")

        val main = mainModel(scene, selected)
        sb.append("场景\n")
        if (scene.models.isEmpty()) {
            sb.append("  模型：一个也没有。scene.main() 现在返回 nil——脚本要先判断，或者用 scene.create(...) 自己建几何体。\n")
        } else {
            sb.append("  模型：").append(scene.models.size).append(" 个\n")
            for (m in scene.models) {
                sb.append("    #").append(m.id).append(' ').append(q(m.name)).append("  ").append(kindName(m))
                if (m === main) sb.append("  ← scene.main()（没有给脚本另外指定模型时）")
                if (m.transient) sb.append("  （脚本临时创建的）")
                sb.append('\n')
            }
            sb.append("    按 id 取：scene.get(id)；按名字取：scene.find(\"名字\")。\n")
        }
        sb.append("  时间轴：共 ").append(scene.maxFrame).append(" 帧（").append(num(scene.durationSeconds)).append(" 秒，30 帧 / 秒），现在在第 ")
            .append(Math.round(scene.frame)).append(" 帧，").append(if (scene.playing) "正在播放" else "暂停着").append(if (scene.loop) "，循环播放" else "").append('\n')
        val cam = scene.camera
        sb.append("  镜头：看着 (").append(vec(cam.target)).append(")，距离 ").append(num(cam.distance)).append("，视角 ").append(num(cam.fovDeg)).append("°")
        if (scene.cameraMotion != null && scene.useCameraMotion) sb.append("；场景里有镜头动作，播放时镜头跟着它走（camera.set 会接管，camera.release 交还）")
        sb.append('\n')
        val ph = scene.physics
        sb.append("  物理：").append(if (scene.physicsEnabled) "开" else "关").append("，重力 ×").append(num(ph.gravity))
            .append("，风 (").append(num(ph.windX)).append(", ").append(num(ph.windY)).append(", ").append(num(ph.windZ)).append(")\n")
        val fl = scene.floor
        if (!fl.enabled) sb.append("  地板：没有（被删掉了，东西会一直往下掉；floor.reset() 加回默认的）\n")
        else {
            sb.append("  地板：高度 ").append(num(fl.height))
            if (!fl.collide) sb.append("，不挡东西")
            if (!fl.grid) sb.append("，不画网格")
            if (fl.color[3] > 0.003f) sb.append("，有底色")
            sb.append('\n')
        }
        val solid = scene.models.filter { it.collision != ModelInstance.COLLISION_NONE }
        if (solid.isNotEmpty()) sb.append("  带碰撞的模型：").append(solid.joinToString("，") { "#" + it.id + "（" + ModelInstance.COLLISION_NAMES[it.collision] + "）" }).append('\n')
        if (scene.audioClips.isNotEmpty()) sb.append("  音乐：").append(scene.audioClips.size).append(" 段\n")
        if (scripts.isNotEmpty()) {
            sb.append("  场景里已有的脚本（同名的 script_write 会替换它）：\n")
            for (s in scripts) {
                sb.append("    ").append(q(s.name)).append("  ").append(
                    when { s.error != null -> "出错停了：" + s.error!!.lineSequence().first().take(160); s.running -> "运行中"; s.enabled -> "开着，没到它的时间段"; else -> "已停" },
                )
                if (s.end >= 0) sb.append("  时间段 ").append(s.start).append('–').append(s.end).append(" 帧")
                if (s.target != 0) sb.append("  作用于 #").append(s.target)
                sb.append('\n')
            }
        }

        val list = if (only != null) listOf(only) else scene.models.toList()
        for (m in list) {
            sb.append('\n')
            model(sb, m, m === main, cap)
        }
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------------ 一个模型

    private fun model(sb: StringBuilder, m: ModelInstance, isMain: Boolean, cap: Int) {
        val d = m.data
        val title = "模型 #${m.id} ${q(m.name)}（${kindName(m)}）" + if (isMain) " ← scene.main()" else ""
        sb.append(title).append('\n').append("-".repeat(40)).append('\n')
        val lo = FloatArray(3); val hi = FloatArray(3)
        m.worldBounds(lo, hi)
        val e = FloatArray(3); m.getRotationEuler(e)
        sb.append("  取它：local m = scene.get(").append(m.id).append(")").append(if (isMain) "   或 scene.main()" else "").append('\n')
        sb.append("  位置 (").append(vec(m.position)).append(")  旋转 (").append(vec(e)).append(")  缩放 ").append(num(m.scale))
            .append("  占的空间 (").append(vec(lo)).append(") 到 (").append(vec(hi)).append(")，高约 ").append(num(hi[1] - lo[1])).append('\n')
        val flags = ArrayList<String>()
        if (!m.visible) flags.add("隐藏着")
        if (m.opacity < 0.999f) flags.add("透明度 " + num(m.opacity))
        if (m.layer != 0) flags.add("层 " + m.layer)
        if (m.collision != ModelInstance.COLLISION_NONE) flags.add("带碰撞（" + ModelInstance.COLLISION_NAMES[m.collision] + "）")
        if (m.physics != null) flags.add(if (m.physicsEnabled) "有物理（头发 / 裙子会自己摆）" else "物理关着")
        m.attachParent?.let { p -> flags.add("挂在 #${p.id} 的骨骼 " + q(p.data.bones.getOrNull(m.attachBone)?.let { boneName(it.name, it.nameEn) } ?: "?") + " 上") }
        if (m.clips.isNotEmpty()) flags.add("带着 ${m.clips.size} 段动作")
        if (flags.isNotEmpty()) sb.append("  ").append(flags.joinToString("；")).append('\n')

        bones(sb, m, cap)
        morphs(sb, m, cap)
        materials(sb, m, cap)
        bodies(sb, m, cap)
        if (d.bones.isEmpty() && d.morphs.isEmpty()) sb.append("  没有骨骼和表情：只能整体挪动 / 旋转 / 缩放、改透明度和材质。\n")
    }

    private fun bones(sb: StringBuilder, m: ModelInstance, cap: Int) {
        val d = m.data
        val sk = m.skeleton
        if (d.bones.isEmpty() || sk == null) return
        // 被物理带着动的骨骼：挂着「物理」或「物理 + 对齐」刚体的那些（看的是此刻的模式，脚本改过就按改过的算）
        val physical = BooleanArray(d.bones.size)
        val p = m.physics
        if (p != null) {
            for (i in 0 until p.bodyCount) { val b = p.bodyBone(i); if (b in physical.indices && p.bodyMode(i) != RigidBodyData.MODE_STATIC) physical[b] = true }
        } else {
            for (rb in d.rigidBodies) if (rb.bone in physical.indices && rb.mode != RigidBodyData.MODE_STATIC) physical[rb.bone] = true
        }
        val normal = ArrayList<String>(); val ik = ArrayList<String>(); val phys = ArrayList<String>(); val helper = ArrayList<String>(); val movable = ArrayList<String>()
        var unnamed = 0
        for ((i, b) in d.bones.withIndex()) {
            val name = boneName(b.name, b.nameEn)
            if (name.isEmpty()) { unnamed++; continue }
            when {
                physical[i] -> phys.add(q(name))
                b.ik != null -> {
                    val links = b.ik!!.links.mapNotNull { l -> d.bones.getOrNull(l.bone)?.let { boneName(it.name, it.nameEn) } }.filter { it.isNotEmpty() }
                    ik.add(q(name) + if (links.isEmpty()) "" else "（带动 " + links.joinToString(" ") + "）")
                }
                !b.isVisible || !b.isOperable -> helper.add(q(name))
                else -> normal.add(q(name))
            }
            if (b.isTranslatable && !physical[i]) movable.add(q(name))
        }
        sb.append("  骨骼 ").append(d.bones.size).append(" 根 —— m:bone(\"名字\")；名字里含某段字的一批：m:bones(\"スカート\")\n")
        standard(sb, m)
        line(sb, "    一般的骨骼（b:rotate / b:set_rot 直接有效）", normal, cap, "m:bones()")
        line(sb, "    其中模型作者标了可以移动的（b:move 用在这些上：挪全身、挪手脚的 IK）", movable, cap, "")
        line(sb, "    IK 骨（移动它，括号里的骨骼跟着弯）", ik, cap, "")
        line(sb, "    被物理带着摆的（转不动；要自己驱动先把它的刚体改成跟骨骼：for _, r in ipairs(m:bodies(\"关键字\")) do r.mode = \"bone\" end）", phys, cap, "m:bones()")
        line(sb, "    辅助骨（界面里不显示，一般不用动）", helper, cap, "m:bones()")
        if (unnamed > 0) sb.append("    另有 ").append(unnamed).append(" 根没有名字，只能从别的骨骼的 b.parent / b:children() 走到。\n")
    }

    /** 人形骨架里常用的那些骨骼在这个模型里叫什么、此刻在哪儿。 */
    private fun standard(sb: StringBuilder, m: ModelInstance) {
        val d = m.data
        val found = ArrayList<String>(); val missing = ArrayList<String>()
        val pos = FloatArray(3)
        for (s in STANDARD) {
            var i = -1
            for (n in s.names) { i = d.bones.indexOfFirst { it.name == n }; if (i >= 0) break }
            if (i < 0 && s.en.isNotEmpty()) i = d.bones.indexOfFirst { b -> norm(b.name) in s.en || norm(b.nameEn) in s.en }
            if (i < 0) { if (s.important) missing.add(s.label); continue }
            m.boneWorldPosition(i, pos, 0)
            found.add(s.label + " = " + q(boneName(d.bones[i].name, d.bones[i].nameEn)) + " (" + vec(pos) + ")")
        }
        if (found.isEmpty()) {
            sb.append("    这不是标准的人形骨架：没有找到头、手臂、腿这些常见骨骼，示例脚本里写死的名字（頭、上半身、右手首……）在这个模型上都要换成下面列出的。\n")
            return
        }
        sb.append("    常用骨骼在这个模型里的名字（括号里是它此刻的世界坐标，放特效 / 对镜头时用得上）：\n")
        for (chunk in found.chunked(3)) sb.append("      ").append(chunk.joinToString("   ")).append('\n')
        if (missing.isNotEmpty()) sb.append("    这个模型没有：").append(missing.joinToString("、")).append("（用到它们的写法要换）\n")
    }

    private fun morphs(sb: StringBuilder, m: ModelInstance, cap: Int) {
        val d = m.data
        if (d.morphs.isEmpty()) return
        val groups = linkedMapOf(2 to ArrayList<String>(), 3 to ArrayList(), 1 to ArrayList(), 4 to ArrayList(), 0 to ArrayList())
        var unnamed = 0
        for ((i, mo) in d.morphs.withIndex()) {
            if (mo.name.isEmpty()) { unnamed++; continue }
            val w = m.morphs.weightOf(i)
            val kind = when (mo.type) {
                MorphData.TYPE_GROUP -> "组合"; MorphData.TYPE_BONE -> "动骨骼"; MorphData.TYPE_MATERIAL -> "改材质"
                MorphData.TYPE_FLIP -> "切换"; MorphData.TYPE_IMPULSE -> "推刚体"; else -> ""
            }
            val notes = listOfNotNull(kind.takeIf { it.isNotEmpty() }, if (kotlin.math.abs(w) > 0.001f) "现在 " + num(w) else null)
            (groups[mo.panel] ?: groups.getValue(4)).add(q(mo.name) + if (notes.isEmpty()) "" else "（" + notes.joinToString("，") + "）")
        }
        sb.append("  表情 ").append(d.morphs.size).append(" 个 —— m:morph(\"名字\", 0..1) 设值，m:morph(\"名字\") 读值\n")
        // 眨眼、口型在这个模型里叫什么
        val known = ArrayList<String>()
        for ((label, names) in MORPHS) {
            val hit = d.morphs.firstOrNull { mo -> names.any { it == mo.name } } ?: d.morphs.firstOrNull { mo -> names.any { it.equals(mo.name, true) } }
            if (hit != null) known.add(label + " = " + q(hit.name))
        }
        if (known.isNotEmpty()) sb.append("    常用的：").append(known.joinToString("   ")).append('\n')
        line(sb, "    眼睛", groups.getValue(2), cap, "m:morphs()")
        line(sb, "    嘴", groups.getValue(3), cap, "m:morphs()")
        line(sb, "    眉毛", groups.getValue(1), cap, "m:morphs()")
        line(sb, "    其它", groups.getValue(4), cap, "m:morphs()")
        line(sb, "    系统（一般不用）", groups.getValue(0), cap, "m:morphs()")
        if (unnamed > 0) sb.append("    另有 ").append(unnamed).append(" 个没有名字，脚本里取不到。\n")
    }

    private fun materials(sb: StringBuilder, m: ModelInstance, cap: Int) {
        val d = m.data
        if (d.materials.isEmpty()) return
        val follow = dominantBones(d)
        sb.append("  材质（部件）").append(d.materials.size).append(" 块 —— m:material(序号或\"名字\")，序号从 1 开始；名字里含某段字的一批：m:materials(\"裙\")\n")
        for ((i, md) in d.materials.withIndex()) {
            if (i >= cap) { sb.append("    …… 还有 ").append(d.materials.size - cap).append(" 块（m:materials() 能取到全部）\n"); break }
            sb.append("    ").append(i + 1).append("  ").append(if (md.name.isEmpty()) "（没有名字，只能用序号）" else q(md.name)).append("  ").append(md.indexCount / 3).append(" 面")
            val b = follow.getOrElse(i) { -1 }
            d.bones.getOrNull(b)?.let { bone -> boneName(bone.name, bone.nameEn).takeIf { it.isNotEmpty() }?.let { sb.append("  主要跟着骨骼 ").append(q(it)) } }
            d.textures.getOrNull(md.texture)?.let { t -> sb.append("  贴图 ").append(t.path.substringAfterLast('/').substringAfterLast('\\')) }
            if (md.diffuse[3] < 0.999f) sb.append("  本身半透明 ").append(num(md.diffuse[3]))
            val st = m.materials.getOrNull(i)
            if (st != null && !st.visible) sb.append("  用户在界面里把它隐藏了")
            sb.append('\n')
        }
    }

    private fun bodies(sb: StringBuilder, m: ModelInstance, cap: Int) {
        val p = m.physics ?: return
        if (p.bodyCount == 0) return
        val dyn = ArrayList<String>(); val kin = ArrayList<String>()
        for (i in 0 until p.bodyCount) {
            val name = p.bodyName(i)
            if (name.isEmpty()) continue
            (if (p.bodyMode(i) == RigidBodyData.MODE_STATIC) kin else dyn).add(q(name))
        }
        sb.append("  刚体 ").append(p.bodyCount).append(" 个 —— m:bodies(\"关键字\") 按名字里含的字取一批；m:bodies() 是全部\n")
        line(sb, "    物理在带着动的（头发、裙子、飘带；r:impulse 推它，r.mode = \"bone\" 把它交还给骨骼）", dyn, cap, "m:bodies()")
        line(sb, "    跟着骨骼的（身体的碰撞体，一般不用动）", kin, cap, "m:bodies()")
    }

    // ------------------------------------------------------------------ 小工具

    private fun line(sb: StringBuilder, label: String, names: List<String>, cap: Int, all: String) {
        if (names.isEmpty()) return
        sb.append(label).append("，").append(names.size).append(" 个：\n      ")
        sb.append(names.take(cap).joinToString(" "))
        if (names.size > cap) sb.append(" …… 还有 ").append(names.size - cap).append(" 个").append(if (all.isEmpty()) "" else "（$all 能取到全部）")
        sb.append('\n')
    }

    private fun q(s: String): String = "\"" + s + "\""
    private fun boneName(name: String, en: String): String = name.ifEmpty { en }
    private fun kindName(m: ModelInstance): String = when (m.kind) { ModelInstance.KIND_CHARACTER -> "character 角色"; ModelInstance.KIND_STAGE -> "stage 舞台"; else -> "accessory 道具" }

    private fun num(v: Float): String {
        if (v.isNaN() || v.isInfinite()) return "0"
        val r = Math.round(v * 100f) / 100f
        return if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()
    }
    private fun vec(v: FloatArray): String = num(v[0]) + ", " + num(v[1]) + ", " + num(v[2])

    /** 英文骨骼名归一：去掉符号和大小写，去掉 Mixamo / VRM 的前缀（mixamorig:Head、J_Bip_C_Head、J_Bip_L_UpperArm）。 */
    private fun norm(s: String): String {
        if (s.isEmpty()) return ""
        var t = s.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        t = t.removePrefix("mixamorig")
        if (t.startsWith("jbip") && t.length > 5) t = when (t[4]) { 'l' -> "left" + t.substring(5); 'r' -> "right" + t.substring(5); 'c' -> t.substring(5); else -> t.substring(4) }
        return t
    }

    private class Std(val label: String, val names: List<String>, val en: Set<String>, val important: Boolean)

    private val STANDARD: List<Std> = ArrayList<Std>().apply {
        add(Std("全身的根", listOf("全ての親"), setOf("root"), false))
        add(Std("重心（挪全身用它）", listOf("センター"), setOf("hips", "pelvis", "hip"), true))
        add(Std("下半身", listOf("下半身"), emptySet(), false))
        add(Std("上半身", listOf("上半身"), setOf("spine"), true))
        add(Std("胸", listOf("上半身2", "上半身２"), setOf("chest", "spine1", "spine2", "upperchest"), false))
        add(Std("脖子", listOf("首"), setOf("neck"), false))
        add(Std("头", listOf("頭"), setOf("head"), true))
        add(Std("双眼", listOf("両目"), emptySet(), false))
        for ((zh, jp, en) in listOf(Triple("左", "左", "left"), Triple("右", "右", "right"))) {
            add(Std("${zh}眼", listOf("${jp}目"), setOf("${en}eye"), false))
            add(Std("${zh}肩", listOf("${jp}肩"), setOf("${en}shoulder"), false))
            add(Std("${zh}上臂", listOf("${jp}腕"), setOf("${en}arm", "${en}upperarm"), true))
            add(Std("${zh}肘", listOf("${jp}ひじ"), setOf("${en}forearm", "${en}lowerarm"), true))
            add(Std("${zh}手腕", listOf("${jp}手首"), setOf("${en}hand"), true))
            add(Std("${zh}大腿", listOf("${jp}足"), setOf("${en}upleg", "${en}upperleg"), true))
            add(Std("${zh}膝", listOf("${jp}ひざ"), setOf("${en}leg", "${en}lowerleg"), true))
            add(Std("${zh}脚踝", listOf("${jp}足首"), setOf("${en}foot"), false))
            add(Std("${zh}脚 IK", listOf("${jp}足ＩＫ", "${jp}足IK"), emptySet(), false))
        }
    }

    private val MORPHS: List<Pair<String, List<String>>> = listOf(
        "眨眼" to listOf("まばたき", "眨眼", "blink", "Fcl_EYE_Close"),
        "笑眼" to listOf("笑い", "笑眼", "Fcl_EYE_Joy"),
        "口型 あ" to listOf("あ", "aa", "a", "Fcl_MTH_A"),
        "口型 い" to listOf("い", "ih", "i", "Fcl_MTH_I"),
        "口型 う" to listOf("う", "ou", "u", "Fcl_MTH_U"),
        "口型 え" to listOf("え", "ee", "e", "Fcl_MTH_E"),
        "口型 お" to listOf("お", "oh", "o", "Fcl_MTH_O"),
    )

    // 每块材质的顶点主要绑在哪根骨骼上（「这块材质是身体的哪一部分」）。算一次要走一遍全部顶点，按模型数据缓存。
    private class Follow(val materials: Int, val indices: Int, val bones: IntArray)
    private val followCache = WeakHashMap<ModelData, Follow>()

    private fun dominantBones(d: ModelData): IntArray {
        synchronized(followCache) { followCache[d]?.let { if (it.materials == d.materials.size && it.indices == d.indices.size) return it.bones } }
        val out = IntArray(d.materials.size) { -1 }
        val bi = d.boneIndices; val bw = d.boneWeights
        if (bi != null && bw != null && d.bones.isNotEmpty()) {
            val acc = FloatArray(d.bones.size)
            val seen = IntArray(d.vertexCount) { -1 }
            val idx = d.indices
            for ((mi, md) in d.materials.withIndex()) {
                acc.fill(0f)
                val end = minOf(md.indexOffset + md.indexCount, idx.size)
                for (k in md.indexOffset.coerceAtLeast(0) until end) {
                    val v = idx[k]
                    if (v < 0 || v >= seen.size || seen[v] == mi) continue
                    seen[v] = mi
                    for (j in 0 until 4) {
                        val w = bw[v * 4 + j]
                        if (w > 0f) { val b = bi[v * 4 + j].toInt() and 0xFFFF; if (b < acc.size) acc[b] += w }
                    }
                }
                var best = -1; var bestW = 0f
                for (b in acc.indices) if (acc[b] > bestW) { bestW = acc[b]; best = b }
                out[mi] = best
            }
        }
        synchronized(followCache) { followCache[d] = Follow(d.materials.size, d.indices.size, out) }
        return out
    }
}

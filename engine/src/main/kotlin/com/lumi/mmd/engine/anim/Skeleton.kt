package com.lumi.mmd.engine.anim

import com.lumi.mmd.engine.math.Mat4
import com.lumi.mmd.engine.math.Quat
import com.lumi.mmd.engine.model.BoneData
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 一个模型实例的骨骼运行时。
 *
 * 更新流程参考 saba（benikabocha/saba）的 PMXModel::UpdateNodeAnimation：
 *  1. 按「变形阶层 → 骨骼序号」排序；
 *  2. 先算所有骨的局部矩阵并从根刷新一遍全局矩阵；
 *  3. 再按顺序处理付与（付与親的旋转/移动按付与率叠加）和 IK（CCD，带单轴/欧拉角限制）；
 *  4. 物理演算之后，对"物理后变形"的骨重复 1~3。
 *
 * 所有数据都是按骨骼序号排布的扁平 FloatArray，逐帧更新不产生任何对象。
 * 坐标保持 MMD 左手系，因此 PMX 里的 IK 角度限制可以原样使用，无需换算。
 */
class Skeleton(val bones: List<BoneData>) {
    val count: Int = bones.size

    val parent = IntArray(count)
    /** 相对父骨的静止偏移。 */
    val rest = FloatArray(count * 3)
    /** 绑定姿势下的绝对位置。 */
    val bindPos = FloatArray(count * 3)

    // ---- 各层输入 ----
    /** 动作数据（VMD）给出的平移/旋转。 */
    val animT = FloatArray(count * 3)
    val animR = FloatArray(count * 4)
    /** 骨骼表情（ボーンモーフ）给出的平移/旋转。 */
    val morphT = FloatArray(count * 3)
    val morphR = FloatArray(count * 4)
    /** 用户手动摆姿势的修正层，叠加在动作之上：R = userR · animR · morphR。 */
    val userT = FloatArray(count * 3)
    val userR = FloatArray(count * 4)

    // ---- 每帧中间量 ----
    private val baseT = FloatArray(count * 3)
    private val baseR = FloatArray(count * 4)
    private val ikR = FloatArray(count * 4)
    private val appendT = FloatArray(count * 3)
    private val appendR = FloatArray(count * 4)

    val local = FloatArray(count * 16)
    val global = FloatArray(count * 16)

    /** 该骨是否是某条 IK 链的链节。 */
    private val isIkLink = BooleanArray(count)
    private val afterPhysics = BooleanArray(count)
    private val hasAppendRotate = BooleanArray(count)
    private val hasAppendTranslate = BooleanArray(count)
    private val appendLocal = BooleanArray(count)
    private val appendParent = IntArray(count)
    private val appendWeight = FloatArray(count)
    private val parentHasAppend = BooleanArray(count)

    /** 按（变形阶层, 序号）排序后的骨骼序号。 */
    private val sorted: IntArray
    /** 父在前、子在后的深度优先顺序；dfsPos[b]..dfsEnd[b] 恰好是 b 的整棵子树。 */
    private val dfs = IntArray(count)
    private val dfsPos = IntArray(count)
    private val dfsEnd = IntArray(count)

    private val ikSolvers = arrayOfNulls<IkSolver>(count)
    /** 每个 IK 骨的开关（VMD 的 IK 帧或用户切换）。 */
    val ikEnabled = BooleanArray(count) { true }
    /** IK 总开关。 */
    var ikGlobalEnabled = true

    var anyMorphBone = false
    var anyUserPose = false

    // ---- 脚本层：脚本对骨骼的改动，在动作取完之后、手动姿势之前生效 ----
    /** 每根骨的旋转 / 平移怎么用：0 = 不动，1 = 叠加在动作上，2 = 取代动作。 */
    val scriptRMode = ByteArray(count)
    val scriptTMode = ByteArray(count)
    val scriptR = FloatArray(count * 4).also { for (i in 0 until count) it[i * 4 + 3] = 1f }
    val scriptT = FloatArray(count * 3)
    /** 每根骨的缩放（1 = 原样）：子骨离它的距离跟着缩放，绑在它上面的顶点也跟着缩放。 */
    val scriptScale = FloatArray(count * 3).also { it.fill(1f) }
    var anyScript = false
    var anyScriptScale = false
    /** 整个骨架在模型空间里的位移 / 转向（操控模式、脚本的 root）：作用在所有没有父骨的骨上。 */
    val rootT = FloatArray(3)
    val rootR = floatArrayOf(0f, 0f, 0f, 1f)
    var rootActive = false
    val hasScriptLayer: Boolean get() = anyScript || anyScriptScale || rootActive

    fun setScriptRotation(b: Int, mode: Int, x: Float, y: Float, z: Float, w: Float) {
        val q = b * 4
        scriptR[q] = x; scriptR[q + 1] = y; scriptR[q + 2] = z; scriptR[q + 3] = w
        scriptRMode[b] = mode.toByte()
        if (mode != 0) anyScript = true
    }

    fun setScriptTranslation(b: Int, mode: Int, x: Float, y: Float, z: Float) {
        val t = b * 3
        scriptT[t] = x; scriptT[t + 1] = y; scriptT[t + 2] = z
        scriptTMode[b] = mode.toByte()
        if (mode != 0) anyScript = true
    }

    fun setScriptScale(b: Int, x: Float, y: Float, z: Float) {
        val t = b * 3
        scriptScale[t] = x; scriptScale[t + 1] = y; scriptScale[t + 2] = z
        if (x != 1f || y != 1f || z != 1f) anyScriptScale = true
    }

    fun setRoot(x: Float, y: Float, z: Float, qx: Float, qy: Float, qz: Float, qw: Float) {
        rootT[0] = x; rootT[1] = y; rootT[2] = z
        rootR[0] = qx; rootR[1] = qy; rootR[2] = qz; rootR[3] = qw
        rootActive = true
    }

    fun clearRoot() {
        rootT.fill(0f); rootR[0] = 0f; rootR[1] = 0f; rootR[2] = 0f; rootR[3] = 1f
        rootActive = false
    }

    /** 清掉脚本对骨骼的所有改动。 */
    fun clearScriptLayer() {
        scriptRMode.fill(0); scriptTMode.fill(0)
        scriptT.fill(0f)
        for (i in 0 until count) Quat.identity(scriptR, i * 4)
        scriptScale.fill(1f)
        anyScript = false; anyScriptScale = false
    }

    /** 动作取完之后调用：把脚本层和整体位移合进动作层。 */
    fun applyScriptLayer() {
        if (anyScript) {
            for (b in 0 until count) {
                val rm = scriptRMode[b].toInt()
                if (rm != 0) {
                    val q = b * 4
                    if (rm == 2) Quat.copy(animR, q, scriptR, q) else Quat.mul(animR, q, scriptR, q, animR, q)
                }
                val tm = scriptTMode[b].toInt()
                if (tm != 0) {
                    val t = b * 3
                    if (tm == 2) { animT[t] = scriptT[t]; animT[t + 1] = scriptT[t + 1]; animT[t + 2] = scriptT[t + 2] }
                    else { animT[t] += scriptT[t]; animT[t + 1] += scriptT[t + 1]; animT[t + 2] += scriptT[t + 2] }
                }
            }
        }
        if (rootActive) {
            val r = rootR
            for (b in 0 until count) {
                if (parent[b] >= 0) continue
                val t = b * 3
                val px = rest[t]; val py = rest[t + 1]; val pz = rest[t + 2]
                // 新的平移 = R·(原位置 + 动作平移) − 原位置 + T
                Quat.rotate(r, 0, px + animT[t], py + animT[t + 1], pz + animT[t + 2], tmp, 0)
                animT[t] = tmp[0] - px + rootT[0]; animT[t + 1] = tmp[1] - py + rootT[1]; animT[t + 2] = tmp[2] - pz + rootT[2]
                Quat.mul(animR, b * 4, r, 0, animR, b * 4)
            }
        }
    }

    private val tmp = FloatArray(48)
    private val tmpM = FloatArray(16)

    private class IkLinkState(val bone: Int, val hasLimit: Boolean, val lower: FloatArray, val upper: FloatArray) {
        val prevAngle = FloatArray(3)
        var planeAngle = 0f
        val saved = floatArrayOf(0f, 0f, 0f, 1f)
        /** 0 = 通用求解；1/2/3 = 只绕 X/Y/Z 轴的平面求解。 */
        var planeAxis = 0
    }

    private class IkSolver(val ikBone: Int, val target: Int, val loopCount: Int, val limitAngle: Float, val links: Array<IkLinkState>)

    init {
        for (i in 0 until count) {
            val b = bones[i]
            parent[i] = b.parent
            bindPos[i * 3] = b.position[0]; bindPos[i * 3 + 1] = b.position[1]; bindPos[i * 3 + 2] = b.position[2]
        }
        for (i in 0 until count) {
            val p = parent[i]
            if (p >= 0) {
                rest[i * 3] = bindPos[i * 3] - bindPos[p * 3]
                rest[i * 3 + 1] = bindPos[i * 3 + 1] - bindPos[p * 3 + 1]
                rest[i * 3 + 2] = bindPos[i * 3 + 2] - bindPos[p * 3 + 2]
            } else {
                rest[i * 3] = bindPos[i * 3]; rest[i * 3 + 1] = bindPos[i * 3 + 1]; rest[i * 3 + 2] = bindPos[i * 3 + 2]
            }
            val b = bones[i]
            afterPhysics[i] = b.afterPhysics
            appendParent[i] = b.appendParent
            appendWeight[i] = b.appendWeight
            hasAppendRotate[i] = b.appendRotate && b.appendParent >= 0
            hasAppendTranslate[i] = b.appendTranslate && b.appendParent >= 0
            appendLocal[i] = b.appendLocal
        }
        for (i in 0 until count) {
            val ap = appendParent[i]
            if (ap >= 0) parentHasAppend[i] = hasAppendRotate[ap] || hasAppendTranslate[ap]
        }

        // 排序（稳定）
        sorted = (0 until count).sortedWith(compareBy<Int> { bones[it].level }.thenBy { it }).toIntArray()

        // 深度优先顺序（父在前、子在后；同级按序号升序）
        val childHead = IntArray(count) { -1 }
        val childNext = IntArray(count) { -1 }
        for (i in 0 until count) {
            val p = parent[i]
            if (p >= 0) { childNext[i] = childHead[p]; childHead[p] = i } // 链表头是序号最大的孩子
        }
        var n = 0
        val stack = IntArray(count + 1)
        var sp = 0
        for (i in count - 1 downTo 0) if (parent[i] < 0) stack[sp++] = i
        while (sp > 0) {
            val b = stack[--sp]
            dfsPos[b] = n
            dfs[n++] = b
            var c = childHead[b]
            while (c >= 0) { stack[sp++] = c; c = childNext[c] } // 序号大的先入栈 → 序号小的先出栈
        }
        // 子树大小：逆序累加
        val size = IntArray(count)
        for (k in n - 1 downTo 0) {
            val b = dfs[k]
            size[b] += 1
            val p = parent[b]
            if (p >= 0) size[p] += size[b]
        }
        for (b in 0 until count) dfsEnd[b] = dfsPos[b] + size[b]

        // IK
        for (i in 0 until count) {
            val ik = bones[i].ik ?: continue
            val links = Array(ik.links.size) { k ->
                val l = ik.links[k]
                val st = IkLinkState(l.bone, l.hasLimit, l.lower.copyOf(), l.upper.copyOf())
                if (l.hasLimit) {
                    val lo = l.lower
                    val hi = l.upper
                    // 与 saba 一致的判定：只有一个轴给了范围、其余两轴被锁住时走平面求解（膝盖）
                    st.planeAxis = when {
                        (lo[0] != 0f || hi[0] != 0f) && (lo[1] == 0f || hi[1] == 0f) && (lo[2] == 0f || hi[2] == 0f) -> 1
                        (lo[1] != 0f || hi[1] != 0f) && (lo[0] == 0f || hi[0] == 0f) && (lo[2] == 0f || hi[2] == 0f) -> 2
                        (lo[2] != 0f || hi[2] != 0f) && (lo[0] == 0f || hi[0] == 0f) && (lo[1] == 0f || hi[1] == 0f) -> 3
                        else -> 0
                    }
                }
                isIkLink[l.bone] = true
                st
            }
            ikSolvers[i] = IkSolver(i, ik.target, ik.loopCount.coerceIn(0, 255), ik.limitAngle, links)
        }

        resetPose()
    }

    val hasIk: Boolean get() = ikSolvers.any { it != null }

    fun isIkBone(bone: Int): Boolean = ikSolvers[bone] != null

    /** 回到绑定姿势（清空所有输入层）。 */
    fun resetPose() {
        animT.fill(0f); morphT.fill(0f); userT.fill(0f); appendT.fill(0f)
        for (i in 0 until count) {
            Quat.identity(animR, i * 4); Quat.identity(morphR, i * 4); Quat.identity(userR, i * 4)
            Quat.identity(ikR, i * 4); Quat.identity(appendR, i * 4); Quat.identity(baseR, i * 4)
        }
        anyMorphBone = false
        anyUserPose = false
        baseT.fill(0f)
        for (i in 0 until count) {
            Mat4.setTranslation(local, i * 16, rest[i * 3], rest[i * 3 + 1], rest[i * 3 + 2])
        }
        updateAllGlobals()
    }

    /** 每帧开始：清掉上一帧动作层的数据（没有关键帧的骨保持静止姿势）。 */
    fun beginFrame() {
        animT.fill(0f)
        val r = animR
        var i = 0
        val n = count * 4
        while (i < n) { r[i] = 0f; r[i + 1] = 0f; r[i + 2] = 0f; r[i + 3] = 1f; i += 4 }
    }

    fun clearUserPose() {
        userT.fill(0f)
        for (i in 0 until count) Quat.identity(userR, i * 4)
        anyUserPose = false
    }

    /** 合成各输入层 → 每根骨的基础平移/旋转，并清空 IK / 付与的中间量。 */
    fun prepare(withUserPose: Boolean = true) {
        val n = count
        if (!withUserPose) {
            // 建模时：只留动作层（此时已被清零），忽略表情骨骼和手动姿势
            System.arraycopy(animT, 0, baseT, 0, n * 3)
            System.arraycopy(animR, 0, baseR, 0, n * 4)
        } else if (!anyMorphBone && !anyUserPose) {
            System.arraycopy(animT, 0, baseT, 0, n * 3)
            System.arraycopy(animR, 0, baseR, 0, n * 4)
        } else {
            for (b in 0 until n) {
                val t = b * 3
                baseT[t] = animT[t] + morphT[t] + userT[t]
                baseT[t + 1] = animT[t + 1] + morphT[t + 1] + userT[t + 1]
                baseT[t + 2] = animT[t + 2] + morphT[t + 2] + userT[t + 2]
                val q = b * 4
                Quat.mul(baseR, q, animR, q, morphR, q)
                Quat.mul(baseR, q, userR, q, baseR, q)
            }
        }
        appendT.fill(0f)
        var i = 0
        val m = n * 4
        val a = appendR
        val k = ikR
        while (i < m) {
            a[i] = 0f; a[i + 1] = 0f; a[i + 2] = 0f; a[i + 3] = 1f
            k[i] = 0f; k[i + 1] = 0f; k[i + 2] = 0f; k[i + 3] = 1f
            i += 4
        }
    }

    /**
     * 骨骼更新的一个阶段。
     * @param afterPhysicsPass false = 物理前变形的骨；true = 物理后变形的骨
     */
    fun update(afterPhysicsPass: Boolean) {
        val order = sorted
        for (b in order) {
            if (afterPhysics[b] != afterPhysicsPass) continue
            computeLocal(b)
        }
        updateAllGlobals()
        var touched = false
        for (b in order) {
            if (afterPhysics[b] != afterPhysicsPass) continue
            if (hasAppendRotate[b] || hasAppendTranslate[b]) {
                updateAppend(b)
                updateSubtree(b)
                touched = true
            }
            val solver = ikSolvers[b]
            if (solver != null && ikGlobalEnabled && ikEnabled[b]) {
                solveIk(solver)
                updateSubtree(b)
                touched = true
            }
        }
        if (touched) updateAllGlobals()
    }

    private fun computeLocal(b: Int) {
        val t = b * 3
        var tx = rest[t]; var ty = rest[t + 1]; var tz = rest[t + 2]
        if (anyScriptScale) {
            val p = parent[b]
            if (p >= 0) { tx *= scriptScale[p * 3]; ty *= scriptScale[p * 3 + 1]; tz *= scriptScale[p * 3 + 2] }
        }
        tx += baseT[t]; ty += baseT[t + 1]; tz += baseT[t + 2]
        if (hasAppendTranslate[b]) {
            tx += appendT[t]; ty += appendT[t + 1]; tz += appendT[t + 2]
        }
        val q = b * 4
        var qx = baseR[q]; var qy = baseR[q + 1]; var qz = baseR[q + 2]; var qw = baseR[q + 3]
        if (isIkLink[b]) {
            // r = ikR ⊗ r
            val ax = ikR[q]; val ay = ikR[q + 1]; val az = ikR[q + 2]; val aw = ikR[q + 3]
            val x = aw * qx + ax * qw + ay * qz - az * qy
            val y = aw * qy - ax * qz + ay * qw + az * qx
            val z = aw * qz + ax * qy - ay * qx + az * qw
            val w = aw * qw - ax * qx - ay * qy - az * qz
            qx = x; qy = y; qz = z; qw = w
        }
        if (hasAppendRotate[b]) {
            // r = r ⊗ appendR
            val bx = appendR[q]; val by = appendR[q + 1]; val bz = appendR[q + 2]; val bw = appendR[q + 3]
            val x = qw * bx + qx * bw + qy * bz - qz * by
            val y = qw * by - qx * bz + qy * bw + qz * bx
            val z = qw * bz + qx * by - qy * bx + qz * bw
            val w = qw * bw - qx * bx - qy * by - qz * bz
            qx = x; qy = y; qz = z; qw = w
        }
        Mat4.setTR(local, b * 16, tx, ty, tz, qx, qy, qz, qw)
    }

    private fun updateAppend(b: Int) {
        val src = appendParent[b]
        val w = appendWeight[b]
        val q = b * 4
        val sq = src * 4
        if (hasAppendRotate[b]) {
            val tq = tmp
            if (!appendLocal[b] && parentHasAppend[b]) {
                Quat.copy(tq, 0, appendR, sq)
            } else {
                Quat.copy(tq, 0, baseR, sq)
            }
            if (isIkLink[src]) Quat.mul(tq, 0, ikR, sq, tq, 0)
            Quat.pow(appendR, q, tq, 0, w)
        }
        if (hasAppendTranslate[b]) {
            val t = b * 3
            val st = src * 3
            if (!appendLocal[b] && parentHasAppend[b]) {
                appendT[t] = appendT[st] * w; appendT[t + 1] = appendT[st + 1] * w; appendT[t + 2] = appendT[st + 2] * w
            } else {
                appendT[t] = baseT[st] * w; appendT[t + 1] = baseT[st + 1] * w; appendT[t + 2] = baseT[st + 2] * w
            }
        }
        computeLocal(b)
    }

    /** 从根开始按层级刷新所有全局矩阵。 */
    fun updateAllGlobals() {
        val order = dfs
        val g = global
        val l = local
        val par = parent
        for (k in 0 until count) {
            val b = order[k]
            val p = par[b]
            if (p >= 0) Mat4.mulAffine(g, b * 16, g, p * 16, l, b * 16)
            else System.arraycopy(l, b * 16, g, b * 16, 16)
        }
    }

    /** 只刷新 b 及其子树的全局矩阵。 */
    fun updateSubtree(b: Int) {
        val order = dfs
        val g = global
        val l = local
        val par = parent
        val end = dfsEnd[b]
        for (k in dfsPos[b] until end) {
            val c = order[k]
            val p = par[c]
            if (p >= 0) Mat4.mulAffine(g, c * 16, g, p * 16, l, c * 16)
            else System.arraycopy(l, c * 16, g, c * 16, 16)
        }
    }

    /** 物理把骨的全局矩阵改写之后，反算它的局部矩阵。 */
    fun recomputeLocalFromGlobal(b: Int) {
        val p = parent[b]
        if (p < 0) {
            System.arraycopy(global, b * 16, local, b * 16, 16)
        } else {
            Mat4.invertRigid(tmpM, 0, global, p * 16)
            Mat4.mulAffine(local, b * 16, tmpM, 0, global, b * 16)
        }
    }

    // ------------------------------------------------------------------ IK

    private fun solveIk(s: IkSolver) {
        val links = s.links
        for (link in links) {
            link.prevAngle[0] = 0f; link.prevAngle[1] = 0f; link.prevAngle[2] = 0f
            link.planeAngle = 0f
            Quat.identity(ikR, link.bone * 4)
            computeLocal(link.bone)
            updateSubtree(link.bone)
        }
        val g = global
        val ik = s.ikBone * 16
        val tg = s.target * 16
        var best = Float.MAX_VALUE
        for (it in 0 until s.loopCount) {
            solveCore(s, it)
            val dx = g[tg + 12] - g[ik + 12]
            val dy = g[tg + 13] - g[ik + 13]
            val dz = g[tg + 14] - g[ik + 14]
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist < best) {
                best = dist
                for (link in links) Quat.copy(link.saved, 0, ikR, link.bone * 4)
                if (dist < 1e-4f) break
            } else {
                for (link in links) {
                    Quat.copy(ikR, link.bone * 4, link.saved, 0)
                    computeLocal(link.bone)
                    updateSubtree(link.bone)
                }
                break
            }
        }
    }

    private fun solveCore(s: IkSolver, iteration: Int) {
        val g = global
        val t = tmp
        val ik = s.ikBone * 16
        val ikx = g[ik + 12]; val iky = g[ik + 13]; val ikz = g[ik + 14]
        for (link in s.links) {
            val b = link.bone
            if (b == s.target) continue
            if (link.hasLimit && link.planeAxis != 0) {
                solvePlane(s, link, iteration)
                continue
            }
            val tg = s.target * 16
            val tgx = g[tg + 12]; val tgy = g[tg + 13]; val tgz = g[tg + 14]

            // 把 IK 目标位置和末端位置变换到链节的局部空间
            Mat4.invertRigid(tmpM, 0, g, b * 16)
            Mat4.transformPoint(tmpM, 0, ikx, iky, ikz, t, 0)
            Mat4.transformPoint(tmpM, 0, tgx, tgy, tgz, t, 3)
            var l = sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2])
            if (l < 1e-8f) continue
            val ix = t[0] / l; val iy = t[1] / l; val iz = t[2] / l
            l = sqrt(t[3] * t[3] + t[4] * t[4] + t[5] * t[5])
            if (l < 1e-8f) continue
            val ex = t[3] / l; val ey = t[4] / l; val ez = t[5] / l

            var dot = ex * ix + ey * iy + ez * iz
            if (dot > 1f) dot = 1f else if (dot < -1f) dot = -1f
            var angle = acos(dot)
            if (angle < 1.7453292e-5f) continue // 1e-3 度
            if (angle > s.limitAngle) angle = s.limitAngle
            // 轴 = 末端方向 × 目标方向
            var cx = ey * iz - ez * iy
            var cy = ez * ix - ex * iz
            var cz = ex * iy - ey * ix
            l = sqrt(cx * cx + cy * cy + cz * cz)
            if (l < 1e-8f) continue
            cx /= l; cy /= l; cz /= l
            Quat.fromAxisAngle(t, 8, cx, cy, cz, angle)

            // chainRot = ikR ⊗ base ⊗ rot
            val q = b * 4
            Quat.mul(t, 12, ikR, q, baseR, q)
            Quat.mul(t, 12, t, 12, t, 8)
            if (link.hasLimit) {
                decomposeZYX(t, 12, link.prevAngle, t, 16)
                val lim = s.limitAngle
                for (a in 0 until 3) {
                    var v = t[16 + a]
                    if (v < link.lower[a]) v = link.lower[a] else if (v > link.upper[a]) v = link.upper[a]
                    var d = v - link.prevAngle[a]
                    if (d > lim) d = lim else if (d < -lim) d = -lim
                    v = d + link.prevAngle[a]
                    t[16 + a] = v
                    link.prevAngle[a] = v
                }
                composeZYX(t[16], t[17], t[18], t, 12)
            }
            // ikR = chainRot ⊗ base⁻¹
            Quat.conjugate(t, 20, baseR, q)
            Quat.mul(ikR, q, t, 12, t, 20)
            Quat.normalize(ikR, q)
            computeLocal(b)
            updateSubtree(b)
        }
    }

    /** 只允许绕单个轴旋转的链节（典型：膝盖）。 */
    private fun solvePlane(s: IkSolver, link: IkLinkState, iteration: Int) {
        val g = global
        val t = tmp
        val axis = link.planeAxis - 1
        val b = link.bone
        val ik = s.ikBone * 16
        val tg = s.target * 16

        Mat4.invertRigid(tmpM, 0, g, b * 16)
        Mat4.transformPoint(tmpM, 0, g[ik + 12], g[ik + 13], g[ik + 14], t, 0)
        Mat4.transformPoint(tmpM, 0, g[tg + 12], g[tg + 13], g[tg + 14], t, 3)
        var l = sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2])
        if (l < 1e-8f) return
        val ix = t[0] / l; val iy = t[1] / l; val iz = t[2] / l
        l = sqrt(t[3] * t[3] + t[4] * t[4] + t[5] * t[5])
        if (l < 1e-8f) return
        val ex = t[3] / l; val ey = t[4] / l; val ez = t[5] / l

        var dot = ex * ix + ey * iy + ez * iz
        if (dot > 1f) dot = 1f else if (dot < -1f) dot = -1f
        var angle = acos(dot)
        if (angle > s.limitAngle) angle = s.limitAngle

        val ax = if (axis == 0) 1f else 0f
        val ay = if (axis == 1) 1f else 0f
        val az = if (axis == 2) 1f else 0f

        // 试正负两个方向，看哪个让末端更靠近目标
        Quat.fromAxisAngle(t, 8, ax, ay, az, angle)
        Quat.rotate(t, 8, ex, ey, ez, t, 12)
        val dot1 = t[12] * ix + t[13] * iy + t[14] * iz
        Quat.fromAxisAngle(t, 8, ax, ay, az, -angle)
        Quat.rotate(t, 8, ex, ey, ez, t, 12)
        val dot2 = t[12] * ix + t[13] * iy + t[14] * iz

        var newAngle = link.planeAngle
        if (dot1 > dot2) newAngle += angle else newAngle -= angle
        val lo = link.lower[axis]
        val hi = link.upper[axis]
        if (iteration == 0) {
            if (newAngle < lo || newAngle > hi) {
                if (-newAngle > lo && -newAngle < hi) {
                    newAngle = -newAngle
                } else {
                    val half = (lo + hi) * 0.5f
                    if (abs(half - newAngle) > abs(half + newAngle)) newAngle = -newAngle
                }
            }
        }
        if (newAngle < lo) newAngle = lo else if (newAngle > hi) newAngle = hi
        link.planeAngle = newAngle

        // ikR = R(axis, newAngle) ⊗ base⁻¹
        val q = b * 4
        Quat.fromAxisAngle(t, 8, ax, ay, az, newAngle)
        Quat.conjugate(t, 20, baseR, q)
        Quat.mul(ikR, q, t, 8, t, 20)
        computeLocal(b)
        updateSubtree(b)
    }

    /** 把四元数分解成 R = Rz·Ry·Rx 的欧拉角，并在等价解里挑最接近 before 的一组。 */
    private fun decomposeZYX(qa: FloatArray, qo: Int, before: FloatArray, out: FloatArray, oo: Int) {
        val x = qa[qo]; val y = qa[qo + 1]; val z = qa[qo + 2]; val w = qa[qo + 3]
        val m00 = 1f - 2f * (y * y + z * z)
        val m10 = 2f * (x * y + w * z)
        val m20 = 2f * (x * z - w * y)
        val m21 = 2f * (y * z + w * x)
        val m22 = 1f - 2f * (x * x + y * y)
        val m01 = 2f * (x * y - w * z)
        val m12 = 2f * (y * z - w * x)
        var rx: Float
        val ry: Float
        var rz: Float
        var sy = -m20
        if (sy > 1f) sy = 1f else if (sy < -1f) sy = -1f
        if (1f - abs(sy) < 1e-6f) {
            ry = asin(sy)
            val sx = sin(before[0])
            val sz = sin(before[2])
            if (abs(sx) < abs(sz)) {
                if (cos(before[0]) > 0f) { rx = 0f; rz = asin((-m01).coerceIn(-1f, 1f)) }
                else { rx = PI; rz = asin(m01.coerceIn(-1f, 1f)) }
            } else {
                if (cos(before[2]) > 0f) { rz = 0f; rx = asin((-m12).coerceIn(-1f, 1f)) }
                else { rz = PI; rx = asin(m12.coerceIn(-1f, 1f)) }
            }
        } else {
            rx = atan2(m21, m22)
            ry = asin(sy)
            rz = atan2(m10, m00)
        }
        var bestX = rx; var bestY = ry; var bestZ = rz
        var minErr = abs(diffAngle(rx, before[0])) + abs(diffAngle(ry, before[1])) + abs(diffAngle(rz, before[2]))
        for (k in 0 until 8) {
            val tx = rx + if (k and 4 == 0) PI else -PI
            val ty = (if (k and 2 == 0) PI else -PI) - ry
            val tz = rz + if (k and 1 == 0) PI else -PI
            val err = abs(diffAngle(tx, before[0])) + abs(diffAngle(ty, before[1])) + abs(diffAngle(tz, before[2]))
            if (err < minErr) { minErr = err; bestX = tx; bestY = ty; bestZ = tz }
        }
        out[oo] = bestX; out[oo + 1] = bestY; out[oo + 2] = bestZ
    }

    /** q = qz ⊗ qy ⊗ qx。 */
    private fun composeZYX(rx: Float, ry: Float, rz: Float, out: FloatArray, oo: Int) {
        val cx = cos(rx * 0.5f); val sx = sin(rx * 0.5f)
        val cy = cos(ry * 0.5f); val sy = sin(ry * 0.5f)
        val cz = cos(rz * 0.5f); val sz = sin(rz * 0.5f)
        out[oo] = sx * cy * cz - cx * sy * sz
        out[oo + 1] = cx * sy * cz + sx * cy * sz
        out[oo + 2] = cx * cy * sz - sx * sy * cz
        out[oo + 3] = cx * cy * cz + sx * sy * sz
    }

    private fun normalizeAngle(a: Float): Float {
        var r = a % TWO_PI
        if (r < 0f) r += TWO_PI
        return r
    }

    private fun diffAngle(a: Float, b: Float): Float {
        val d = normalizeAngle(a) - normalizeAngle(b)
        return if (d > PI) d - TWO_PI else if (d < -PI) d + TWO_PI else d
    }

    // ------------------------------------------------------------------ 查询

    fun globalPosition(bone: Int, out: FloatArray, oo: Int) {
        val o = bone * 16
        out[oo] = global[o + 12]; out[oo + 1] = global[o + 13]; out[oo + 2] = global[o + 14]
    }

    /** 骨骼当前的基础旋转（动作 + 表情 + 用户修正），IK/付与之前。 */
    fun baseRotation(bone: Int, out: FloatArray, oo: Int) = Quat.copy(out, oo, baseR, bone * 4)

    companion object {
        private const val PI = Math.PI.toFloat()
        private const val TWO_PI = (Math.PI * 2.0).toFloat()
    }
}

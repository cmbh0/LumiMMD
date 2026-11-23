package com.lumi.mmd.engine.control

import com.lumi.mmd.engine.model.ModelData
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 一个角色的"身材"：走跑跳的动画要按每个模型自己的腿长、肩宽、手臂原本的角度来做，
 * 否则同一套数字放到不同的模型上，不是脚够不着地，就是手臂插进身体。
 * 只认 MMD 标准骨骼的名字（センター、上半身、左足ＩＫ……）。
 */
class Rig(val model: ModelData) {
    private fun find(vararg names: String): Int {
        for (n in names) { val i = model.bones.indexOfFirst { it.name == n }; if (i >= 0) return i }
        return -1
    }
    private fun name(i: Int): String = if (i >= 0) model.bones[i].name else ""
    private fun pos(i: Int): FloatArray = model.bones[i].position

    val center = find("センター")
    val groove = find("グルーブ")
    val upper = find("上半身")
    val upper2 = find("上半身2", "上半身２")
    val lower = find("下半身")
    val neck = find("首")
    val head = find("頭")
    val legL = find("左足"); val legR = find("右足")
    val kneeL = find("左ひざ"); val kneeR = find("右ひざ")
    val ankleL = find("左足首"); val ankleR = find("右足首")
    val ikL = find("左足ＩＫ", "左足IK"); val ikR = find("右足ＩＫ", "右足IK")
    val toeIkL = find("左つま先ＩＫ", "左つま先IK"); val toeIkR = find("右つま先ＩＫ", "右つま先IK")
    val shoulderL = find("左肩"); val shoulderR = find("右肩")
    val armL = find("左腕"); val armR = find("右腕")
    val elbowL = find("左ひじ"); val elbowR = find("右ひじ")
    val wristL = find("左手首"); val wristR = find("右手首")

    val nCenter = name(center); val nGroove = name(groove); val nUpper = name(upper); val nUpper2 = name(upper2); val nLower = name(lower)
    val nNeck = name(neck); val nHead = name(head)
    val nIkL = name(ikL); val nIkR = name(ikR)
    val nArmL = name(armL); val nArmR = name(armR); val nElbowL = name(elbowL); val nElbowR = name(elbowR)
    val nShoulderL = name(shoulderL); val nShoulderR = name(shoulderR)
    val nWristL = name(wristL); val nWristR = name(wristR)

    /** 有没有做走跑跳动画必需的骨骼（センター、两条腿和它们的 IK）。 */
    val usable: Boolean = center >= 0 && legL >= 0 && legR >= 0 && kneeL >= 0 && kneeR >= 0 && ankleL >= 0 && ankleR >= 0 && ikL >= 0 && ikR >= 0 &&
        model.bones[ikL].ik != null && model.bones[ikR].ik != null

    /** 腿长（髋到膝 + 膝到踝）。所有距离、速度都以它为单位。 */
    val leg: Float
    /** 髋关节离地的高度、离中线的距离。 */
    val hipY: Float
    val hipX: Float
    /** 踝关节离地的高度。 */
    val ankleY: Float
    /** センター 骨离地的高度（趴下时身体绕它转）。 */
    val centerY: Float
    /** 身高（头顶大概的位置）。 */
    val height: Float
    /** 手臂在绑定姿势里比水平低多少度（T 字 = 0，A 字约 35~45）。 */
    val armDrop: Float
    /** 上臂、前臂的长度。 */
    val upperArm: Float
    val foreArm: Float

    init {
        if (usable) {
            val h = pos(legL); val k = pos(kneeL); val a = pos(ankleL)
            leg = dist(h, k) + dist(k, a)
            hipY = h[1]; hipX = abs(h[0]); ankleY = a[1]
            centerY = pos(center)[1]
        } else {
            leg = maxOf(model.boundsMax[1] - model.boundsMin[1], 1f) * 0.45f
            hipY = leg; hipX = leg * 0.1f; ankleY = leg * 0.08f; centerY = leg * 0.7f
        }
        height = if (head >= 0) pos(head)[1] + leg * 0.28f else maxOf(model.boundsMax[1], leg * 2f)
        if (armL >= 0 && elbowL >= 0) {
            val s = pos(armL); val e = pos(elbowL)
            armDrop = Math.toDegrees(atan2((s[1] - e[1]).toDouble(), abs(e[0] - s[0]).toDouble())).toFloat().coerceIn(-20f, 85f)
            upperArm = dist(s, e)
            foreArm = if (wristL >= 0) dist(e, pos(wristL)) else upperArm
        } else { armDrop = 40f; upperArm = leg * 0.3f; foreArm = leg * 0.3f }
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
        return sqrt(x * x + y * y + z * z)
    }
}

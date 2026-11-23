package com.lumi.mmd.engine.physics

/**
 * 物理的可调参数。场景有一份（对所有模型生效），每个模型还可以有自己的一份倍数，两份相乘后生效。
 * 默认值就是模型作者设定的原样。
 */
class PhysicsTuning {
    /** 重力的倍数（1 = MMD 的标准重力，0 = 失重，负数 = 往上飘）。 */
    var gravity = 1f
    /** 横向的重力（加速度）：让头发裙子整体往一边偏。 */
    var gravityX = 0f
    var gravityZ = 0f
    /** 风（加速度）。 */
    var windX = 0f
    var windY = 0f
    var windZ = 0f
    /** 阵风强弱 0..1。 */
    var turbulence = 0.5f
    /** 质量的倍数：越重越不容易被弹簧拉回去，垂坠感更强。 */
    var mass = 1f
    /** 移动阻尼的倍数。 */
    var damping = 1f
    /** 旋转阻尼的倍数：越大摆动停得越快。 */
    var angularDamping = 1f
    /** 弹簧硬度的倍数：越大越硬、越贴近原来的造型。 */
    var stiffness = 1f
    /** 关节可转动范围的倍数。 */
    var limits = 1f
    /** 碰撞体大小的倍数（裙子穿腿时可以调大一点）。 */
    var colliderSize = 1f
    /** 每秒模拟多少步。 */
    var hz = 60
    /** 一帧里最多补多少步（掉帧时）。 */
    var maxSubSteps = 3
    /** 每一步里求解约束迭代多少次：越多越稳定也越费电。 */
    var iterations = 10
    /** 头发裙子会不会被地面挡住。 */
    var ground = true

    fun copyFrom(o: PhysicsTuning) {
        gravity = o.gravity; gravityX = o.gravityX; gravityZ = o.gravityZ
        windX = o.windX; windY = o.windY; windZ = o.windZ; turbulence = o.turbulence
        mass = o.mass; damping = o.damping; angularDamping = o.angularDamping; stiffness = o.stiffness
        limits = o.limits; colliderSize = o.colliderSize; hz = o.hz; maxSubSteps = o.maxSubSteps; iterations = o.iterations; ground = o.ground
    }

    /** this = 全局参数 a 叠上某个模型自己的倍数 b。 */
    fun combine(a: PhysicsTuning, b: PhysicsTuning?) {
        copyFrom(a)
        if (b == null) return
        gravity *= b.gravity; gravityX += b.gravityX; gravityZ += b.gravityZ
        windX += b.windX; windY += b.windY; windZ += b.windZ
        mass *= b.mass; damping *= b.damping; angularDamping *= b.angularDamping; stiffness *= b.stiffness
        limits *= b.limits; colliderSize *= b.colliderSize
    }

    fun reset() = copyFrom(DEFAULT)

    val isDefault: Boolean get() = gravity == 1f && gravityX == 0f && gravityZ == 0f && windX == 0f && windY == 0f && windZ == 0f &&
        mass == 1f && damping == 1f && angularDamping == 1f && stiffness == 1f && limits == 1f && colliderSize == 1f &&
        hz == 60 && maxSubSteps == 3 && iterations == 10 && ground

    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "gravity" to gravity, "gravityX" to gravityX, "gravityZ" to gravityZ,
        "windX" to windX, "windY" to windY, "windZ" to windZ, "turbulence" to turbulence,
        "mass" to mass, "damping" to damping, "angularDamping" to angularDamping, "stiffness" to stiffness,
        "limits" to limits, "colliderSize" to colliderSize, "hz" to hz, "maxSubSteps" to maxSubSteps, "iterations" to iterations, "ground" to ground,
    )

    /** 按名字设一个参数（界面、脚本、MCP 共用）。返回 false = 没有这个名字。 */
    fun set(name: String, v: Float): Boolean {
        when (name) {
            "gravity" -> gravity = v.coerceIn(-5f, 5f)
            "gravityX" -> gravityX = v.coerceIn(-500f, 500f)
            "gravityZ" -> gravityZ = v.coerceIn(-500f, 500f)
            "windX" -> windX = v.coerceIn(-1000f, 1000f)
            "windY" -> windY = v.coerceIn(-1000f, 1000f)
            "windZ" -> windZ = v.coerceIn(-1000f, 1000f)
            "turbulence" -> turbulence = v.coerceIn(0f, 1f)
            "mass" -> mass = v.coerceIn(0.05f, 20f)
            "damping" -> damping = v.coerceIn(0f, 20f)
            "angularDamping" -> angularDamping = v.coerceIn(0f, 20f)
            "stiffness" -> stiffness = v.coerceIn(0f, 20f)
            "limits" -> limits = v.coerceIn(0f, 4f)
            "colliderSize" -> colliderSize = v.coerceIn(0.2f, 3f)
            "hz" -> hz = v.toInt().coerceIn(30, 240)
            "maxSubSteps" -> maxSubSteps = v.toInt().coerceIn(1, 12)
            "iterations" -> iterations = v.toInt().coerceIn(1, 60)
            "ground" -> ground = v != 0f
            else -> return false
        }
        return true
    }

    fun get(name: String): Float? = when (name) {
        "gravity" -> gravity; "gravityX" -> gravityX; "gravityZ" -> gravityZ
        "windX" -> windX; "windY" -> windY; "windZ" -> windZ; "turbulence" -> turbulence
        "mass" -> mass; "damping" -> damping; "angularDamping" -> angularDamping; "stiffness" -> stiffness
        "limits" -> limits; "colliderSize" -> colliderSize; "hz" -> hz.toFloat(); "maxSubSteps" -> maxSubSteps.toFloat()
        "iterations" -> iterations.toFloat(); "ground" -> if (ground) 1f else 0f
        else -> null
    }

    companion object {
        val DEFAULT = PhysicsTuning()
        val NAMES = listOf("gravity", "gravityX", "gravityZ", "windX", "windY", "windZ", "turbulence", "mass", "damping", "angularDamping",
            "stiffness", "limits", "colliderSize", "hz", "maxSubSteps", "iterations", "ground")
    }
}

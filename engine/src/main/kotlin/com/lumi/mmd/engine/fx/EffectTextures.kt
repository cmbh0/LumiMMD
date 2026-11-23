package com.lumi.mmd.engine.fx

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 粒子用的内置贴图：全部由公式算出来（白色 + 形状透明度），颜色在发射器上给。
 * 这样安装包里不用带图片，脚本里直接写名字就能用。
 */
object EffectTextures {
    val NAMES = listOf("soft", "dot", "spark", "star", "ring", "petal", "smoke", "heart", "square", "flare", "snow", "bubble")

    fun has(name: String): Boolean = name in NAMES

    /** 生成 size×size 的 RGBA 图（未预乘）。名字不认识时返回 null。 */
    fun generate(name: String, size: Int = 64): ByteArray? {
        if (name !in NAMES) return null
        val out = ByteArray(size * size * 4)
        for (py in 0 until size) for (px in 0 until size) {
            // 每个像素取 2×2 个样本，边缘不出锯齿
            var acc = 0f
            for (sy in 0 until 2) for (sx in 0 until 2) {
                val x = ((px + 0.25f + sx * 0.5f) / size) * 2f - 1f
                val y = 1f - ((py + 0.25f + sy * 0.5f) / size) * 2f
                acc += shape(name, x, y)
            }
            val a = (acc * 0.25f).coerceIn(0f, 1f)
            val o = (py * size + px) * 4
            out[o] = -1; out[o + 1] = -1; out[o + 2] = -1
            out[o + 3] = (a * 255f + 0.5f).toInt().toByte()
        }
        return out
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun shape(name: String, x: Float, y: Float): Float {
        val r = sqrt(x * x + y * y)
        return when (name) {
            "soft" -> { val k = (1f - r).coerceIn(0f, 1f); k * k }
            "dot" -> 1f - smooth(0.72f, 0.92f, r)
            "ring" -> smooth(0.55f, 0.68f, r) * (1f - smooth(0.80f, 0.94f, r))
            "bubble" -> (smooth(0.62f, 0.86f, r) * 0.85f + 0.12f) * (1f - smooth(0.86f, 0.96f, r)) +
                exp(-((x + 0.35f) * (x + 0.35f) + (y - 0.38f) * (y - 0.38f)) * 60f) * 0.9f
            "spark" -> {
                // 四角星芒：中心一个亮点，加上横竖两道细光
                val core = exp(-r * r * 18f)
                val rays = exp(-abs(x * y) * 55f) * (1f - r).coerceIn(0f, 1f).let { it * it }
                (core + rays * 0.9f)
            }
            "flare" -> exp(-y * y * 90f) * (1f - abs(x)).coerceIn(0f, 1f).let { it * it } + exp(-r * r * 30f)
            "star" -> {
                // 五角星：极坐标下半径在内外之间折线变化
                val a = atan2(x, y)
                val seg = (Math.PI * 2.0 / 5.0).toFloat()
                var t = (a % seg + seg) % seg / seg
                t = abs(t * 2f - 1f)
                val edge = 0.40f + (0.94f - 0.40f) * t * t * (3f - 2f * t) * 0.9f + (0.94f - 0.40f) * t * 0.1f
                1f - smooth(edge - 0.05f, edge + 0.03f, r)
            }
            "petal" -> {
                // 花瓣：下尖上圆，顶端有个小缺口
                val yy = (y + 0.9f) / 1.8f
                if (yy < 0f || yy > 1f) 0f else {
                    val half = 0.62f * sin(yy * Math.PI.toFloat()).let { sqrt(it.coerceAtLeast(0f)) } * (0.35f + 0.65f * yy)
                    val notch = if (yy > 0.86f) (yy - 0.86f) * 2.2f * (1f - (abs(x) / 0.25f).coerceIn(0f, 1f)) else 0f
                    (1f - smooth(half - 0.06f, half + 0.02f, abs(x))) * (1f - smooth(0f, 0.08f, notch)) * (0.75f + 0.25f * yy)
                }
            }
            "heart" -> {
                val hx = x * 1.25f; val hy = y * 1.25f + 0.25f
                val v = (hx * hx + hy * hy - 1f)
                val f = v * v * v - hx * hx * hy * hy * hy
                1f - smooth(-0.04f, 0.04f, f)
            }
            "smoke" -> {
                val n = Effects.noise(x * 2.3f + 7f, y * 2.3f + 3f, 0.5f) * 0.5f + Effects.noise(x * 5.1f, y * 5.1f, 2.5f) * 0.25f
                val k = (1f - r * (1f + n * 0.5f)).coerceIn(0f, 1f)
                k * k * (0.75f + n * 0.4f)
            }
            "snow" -> {
                // 六角雪花：六条细臂 + 中心
                val a = atan2(y, x)
                val arm = abs(sin(a * 3f))
                val line = exp(-arm * arm * 60f * r) * (1f - smooth(0.7f, 0.95f, r))
                maxOf(line, exp(-r * r * 40f))
            }
            "square" -> (1f - smooth(0.80f, 0.92f, abs(x))) * (1f - smooth(0.80f, 0.92f, abs(y)))
            else -> 0f
        }
    }

    @Suppress("unused")
    private fun rot(x: Float, y: Float, a: Float, out: FloatArray) { out[0] = x * cos(a) - y * sin(a); out[1] = x * sin(a) + y * cos(a) }
}

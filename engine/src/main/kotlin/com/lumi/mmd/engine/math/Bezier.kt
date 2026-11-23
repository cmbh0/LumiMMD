package com.lumi.mmd.engine.math

/**
 * VMD 关键帧补间用的三次贝塞尔曲线：端点固定为 (0,0)、(1,1)，
 * 两个控制点 (x1,y1)、(x2,y2) 取值 0..1（文件里是 0..127）。
 */
object Bezier {

    /** 给定归一化时间 x（0..1），返回曲线上的 y。 */
    @JvmStatic
    fun eval(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        // 线性补间（绝大多数关键帧）直接返回
        if (x1 == y1 && x2 == y2) return x

        // X(t) 在 x1,x2∈[0,1] 时单调，先用牛顿迭代，失败再二分
        var t = x
        var solved = false
        for (i in 0 until 6) {
            val it = 1f - t
            val fx = 3f * it * it * t * x1 + 3f * it * t * t * x2 + t * t * t - x
            if (fx > -1e-5f && fx < 1e-5f) { solved = true; break }
            val dfx = 3f * it * it * x1 + 6f * it * t * (x2 - x1) + 3f * t * t * (1f - x2)
            if (dfx < 1e-6f) break
            t -= fx / dfx
            if (t < 0f || t > 1f) break
        }
        if (!solved) {
            var lo = 0f
            var hi = 1f
            t = x
            for (i in 0 until 16) {
                val it = 1f - t
                val fx = 3f * it * it * t * x1 + 3f * it * t * t * x2 + t * t * t - x
                if (fx > -1e-5f && fx < 1e-5f) break
                if (fx < 0f) lo = t else hi = t
                t = (lo + hi) * 0.5f
            }
        }
        val it = 1f - t
        return 3f * it * it * t * y1 + 3f * it * t * t * y2 + t * t * t
    }
}

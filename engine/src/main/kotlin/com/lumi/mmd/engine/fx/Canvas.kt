package com.lumi.mmd.engine.fx

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 脚本自己画的贴图：一块 RGBA 画布，可以画点、线、圆、椭圆、多边形、渐变，也可以逐像素算。
 * 画好以后能贴到模型的材质上（比如重画瞳孔）、给粒子 / 贴片 / 脚本网格当贴图。
 *
 * 坐标是像素，左上角是 (0, 0)，和贴图的 UV 方向一致（u 向右、v 向下）。颜色 0..1，未预乘。
 * 图形的边缘按覆盖率抗锯齿；[feather] 再把边缘虚化若干像素。
 * 只在渲染线程上用。内容变了 [version] 会加一，渲染器据此重新上传。
 */
class Canvas(val id: Int, val owner: Int, val width: Int, val height: Int) {
    val pixels = ByteArray(width * height * 4)
    var version = 1
        private set

    /** 贴到模型上时是否重复平铺（否则边缘拉伸）。 */
    var repeat = false
    /** 放大时是否保持像素颗粒（否则平滑过渡）。 */
    var nearest = false

    // ---- 画笔 ----
    private val c0 = floatArrayOf(1f, 1f, 1f, 1f)
    private val c1 = floatArrayOf(1f, 1f, 1f, 1f)
    private var paint = PAINT_SOLID
    private var gx0 = 0f; private var gy0 = 0f; private var gx1 = 0f; private var gy1 = 0f
    var blend = BLEND_OVER
    /** 边缘虚化的宽度（像素）。0 = 只做抗锯齿。 */
    var feather = 0f
    private val tmp = FloatArray(4)

    fun touch() { version++ }

    fun color(r: Float, g: Float, b: Float, a: Float) { c0[0] = r; c0[1] = g; c0[2] = b; c0[3] = a; paint = PAINT_SOLID }

    /** 线性渐变画笔：从 (x0,y0) 的颜色 a 过渡到 (x1,y1) 的颜色 b。 */
    fun linear(x0: Float, y0: Float, x1: Float, y1: Float, a: FloatArray, b: FloatArray) {
        gx0 = x0; gy0 = y0; gx1 = x1; gy1 = y1
        System.arraycopy(a, 0, c0, 0, 4); System.arraycopy(b, 0, c1, 0, 4); paint = PAINT_LINEAR
    }

    /** 径向渐变画笔：圆心 (cx,cy)，半径 r0 以内是颜色 a，到 r1 过渡成颜色 b。 */
    fun radial(cx: Float, cy: Float, r0: Float, r1: Float, a: FloatArray, b: FloatArray) {
        gx0 = cx; gy0 = cy; gx1 = r0; gy1 = max(r1, r0 + 1e-3f)
        System.arraycopy(a, 0, c0, 0, 4); System.arraycopy(b, 0, c1, 0, 4); paint = PAINT_RADIAL
    }

    private fun paintAt(x: Float, y: Float, out: FloatArray) {
        val t = when (paint) {
            PAINT_LINEAR -> {
                val dx = gx1 - gx0; val dy = gy1 - gy0
                val l = dx * dx + dy * dy
                if (l < 1e-9f) 0f else (((x - gx0) * dx + (y - gy0) * dy) / l).coerceIn(0f, 1f)
            }
            PAINT_RADIAL -> {
                val d = sqrt((x - gx0) * (x - gx0) + (y - gy0) * (y - gy0))
                ((d - gx1) / (gy1 - gx1)).coerceIn(0f, 1f)
            }
            else -> { System.arraycopy(c0, 0, out, 0, 4); return }
        }
        for (k in 0 until 4) out[k] = c0[k] + (c1[k] - c0[k]) * t
    }

    // ---- 像素 ----

    fun clear(r: Float, g: Float, b: Float, a: Float) {
        val br = b8(r); val bg = b8(g); val bb = b8(b); val ba = b8(a)
        var o = 0
        while (o < pixels.size) { pixels[o] = br; pixels[o + 1] = bg; pixels[o + 2] = bb; pixels[o + 3] = ba; o += 4 }
        version++
    }

    fun get(x: Int, y: Int, out: FloatArray): Boolean {
        if (x < 0 || y < 0 || x >= width || y >= height) return false
        val o = (y * width + x) * 4
        for (k in 0 until 4) out[k] = (pixels[o + k].toInt() and 255) / 255f
        return true
    }

    /** 直接写一个像素（不混合）。 */
    fun set(x: Int, y: Int, r: Float, g: Float, b: Float, a: Float) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val o = (y * width + x) * 4
        pixels[o] = b8(r); pixels[o + 1] = b8(g); pixels[o + 2] = b8(b); pixels[o + 3] = b8(a)
        version++
    }

    /** 把颜色 (r,g,b,a) 按覆盖率 cov 混到像素上。 */
    private fun mix(o: Int, r: Float, g: Float, b: Float, a: Float, cov: Float) {
        val sa = (a * cov).coerceIn(0f, 1f)
        if (sa <= 0f && blend != BLEND_REPLACE) return
        val dr = (pixels[o].toInt() and 255) / 255f
        val dg = (pixels[o + 1].toInt() and 255) / 255f
        val db = (pixels[o + 2].toInt() and 255) / 255f
        val da = (pixels[o + 3].toInt() and 255) / 255f
        val nr: Float; val ng: Float; val nb: Float; val na: Float
        when (blend) {
            BLEND_ADD -> { nr = dr + r * sa; ng = dg + g * sa; nb = db + b * sa; na = max(da, sa) }
            BLEND_MULTIPLY -> { nr = dr * (1f + (r - 1f) * sa); ng = dg * (1f + (g - 1f) * sa); nb = db * (1f + (b - 1f) * sa); na = da }
            BLEND_SCREEN -> { nr = 1f - (1f - dr) * (1f - r * sa); ng = 1f - (1f - dg) * (1f - g * sa); nb = 1f - (1f - db) * (1f - b * sa); na = max(da, sa) }
            BLEND_ERASE -> { nr = dr; ng = dg; nb = db; na = da * (1f - sa) }
            BLEND_REPLACE -> { nr = dr + (r - dr) * cov; ng = dg + (g - dg) * cov; nb = db + (b - db) * cov; na = da + (a - da) * cov }
            else -> {
                // 普通叠加（未预乘）
                na = sa + da * (1f - sa)
                if (na <= 1e-6f) { nr = 0f; ng = 0f; nb = 0f }
                else { nr = (r * sa + dr * da * (1f - sa)) / na; ng = (g * sa + dg * da * (1f - sa)) / na; nb = (b * sa + db * da * (1f - sa)) / na }
            }
        }
        pixels[o] = b8(nr); pixels[o + 1] = b8(ng); pixels[o + 2] = b8(nb); pixels[o + 3] = b8(na)
    }

    /** 用当前画笔画一个点（带混合）。 */
    fun dot(x: Int, y: Int, cov: Float = 1f) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        paintAt(x + 0.5f, y + 0.5f, tmp)
        mix((y * width + x) * 4, tmp[0], tmp[1], tmp[2], tmp[3], cov)
        version++
    }

    /**
     * 所有图形的公共部分：在包围盒里逐像素算「到图形边界的有符号距离」（里面为负），换算成覆盖率后上色。
     */
    private inline fun fill(x0: Float, y0: Float, x1: Float, y1: Float, sdf: (Float, Float) -> Float) {
        val pad = feather + 1f
        val ix0 = max(0, (x0 - pad).toInt()); val iy0 = max(0, (y0 - pad).toInt())
        val ix1 = min(width - 1, (x1 + pad).toInt() + 1); val iy1 = min(height - 1, (y1 + pad).toInt() + 1)
        val f = feather
        for (y in iy0..iy1) {
            var o = (y * width + ix0) * 4
            for (x in ix0..ix1) {
                val d = sdf(x + 0.5f, y + 0.5f)
                val cov = if (f > 0f) { val t = (0.5f - d / (f * 2f)).coerceIn(0f, 1f); t * t * (3f - 2f * t) } else (0.5f - d).coerceIn(0f, 1f)
                if (cov > 0f) { paintAt(x + 0.5f, y + 0.5f, tmp); mix(o, tmp[0], tmp[1], tmp[2], tmp[3], cov) }
                o += 4
            }
        }
        version++
    }

    fun circle(cx: Float, cy: Float, r: Float) = fill(cx - r, cy - r, cx + r, cy + r) { x, y -> sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) - r }

    /** 圆环：半径 r，线宽 w。 */
    fun ring(cx: Float, cy: Float, r: Float, w: Float) {
        val h = w * 0.5f
        fill(cx - r - h, cy - r - h, cx + r + h, cy + r + h) { x, y -> abs(sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) - r) - h }
    }

    /** 椭圆：半轴 rx / ry，可以转一个角度（弧度）。 */
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, angle: Float = 0f) {
        val m = max(rx, ry)
        val ca = cos(angle); val sa = sin(angle)
        val ax = max(rx, 1e-3f); val ay = max(ry, 1e-3f)
        fill(cx - m, cy - m, cx + m, cy + m) { x, y ->
            val dx = x - cx; val dy = y - cy
            val lx = dx * ca + dy * sa; val ly = -dx * sa + dy * ca
            val k0 = sqrt(lx * lx / (ax * ax) + ly * ly / (ay * ay))
            val k1 = sqrt(lx * lx / (ax * ax * ax * ax) + ly * ly / (ay * ay * ay * ay))
            if (k1 < 1e-9f) -min(ax, ay) else k0 * (k0 - 1f) / k1
        }
    }

    /** 矩形，radius 是圆角。 */
    fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float = 0f) {
        val cx = x + w * 0.5f; val cy = y + h * 0.5f
        val r = radius.coerceIn(0f, min(w, h) * 0.5f)
        val hx = w * 0.5f - r; val hy = h * 0.5f - r
        fill(x, y, x + w, y + h) { px, py ->
            val qx = abs(px - cx) - hx; val qy = abs(py - cy) - hy
            val ox = max(qx, 0f); val oy = max(qy, 0f)
            sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - r
        }
    }

    /** 线段，两头是圆的。 */
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, w: Float) {
        val h = max(w, 0.01f) * 0.5f
        val bx = x1 - x0; val by = y1 - y0
        val l = bx * bx + by * by
        fill(min(x0, x1) - h, min(y0, y1) - h, max(x0, x1) + h, max(y0, y1) + h) { x, y ->
            val ax = x - x0; val ay = y - y0
            val t = if (l < 1e-9f) 0f else ((ax * bx + ay * by) / l).coerceIn(0f, 1f)
            val dx = ax - bx * t; val dy = ay - by * t
            sqrt(dx * dx + dy * dy) - h
        }
    }

    /** 实心多边形：pts = x0,y0,x1,y1,…（至少 3 个点）。 */
    fun polygon(pts: FloatArray) {
        val n = pts.size / 2
        if (n < 3) return
        var minX = pts[0]; var maxX = pts[0]; var minY = pts[1]; var maxY = pts[1]
        for (i in 1 until n) { minX = min(minX, pts[i * 2]); maxX = max(maxX, pts[i * 2]); minY = min(minY, pts[i * 2 + 1]); maxY = max(maxY, pts[i * 2 + 1]) }
        fill(minX, minY, maxX, maxY) { x, y ->
            var d = Float.MAX_VALUE
            var inside = false
            var j = n - 1
            for (i in 0 until n) {
                val xi = pts[i * 2]; val yi = pts[i * 2 + 1]; val xj = pts[j * 2]; val yj = pts[j * 2 + 1]
                val ex = xj - xi; val ey = yj - yi
                val wx = x - xi; val wy = y - yi
                val l = ex * ex + ey * ey
                val t = if (l < 1e-9f) 0f else ((wx * ex + wy * ey) / l).coerceIn(0f, 1f)
                val bx = wx - ex * t; val by = wy - ey * t
                d = min(d, bx * bx + by * by)
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            if (inside) -sqrt(d) else sqrt(d)
        }
    }

    /** 整张画布加一层噪点 / 云纹：amount 是强度，scale 是纹理的粗细（像素）。 */
    fun noise(amount: Float, scale: Float, seed: Int) {
        val s = 1f / max(scale, 0.5f)
        var o = 0
        for (y in 0 until height) for (x in 0 until width) {
            val k = 1f + Effects.noise(x * s, y * s, seed * 0.37f) * amount
            for (c in 0 until 3) pixels[o + c] = b8(((pixels[o + c].toInt() and 255) / 255f) * k)
            o += 4
        }
        version++
    }

    /** 整张画布模糊：radius 是像素。 */
    fun blur(radius: Float) {
        val r = radius.coerceIn(0f, 64f)
        if (r < 0.3f) return
        val n = (r * 2f).toInt() + 1
        val k = FloatArray(n * 2 + 1)
        var sum = 0f
        for (i in -n..n) { k[i + n] = exp(-(i * i) / (2f * r * r)); sum += k[i + n] }
        for (i in k.indices) k[i] /= sum
        val src = FloatArray(pixels.size)
        // 先乘上透明度再模糊，不然透明处的颜色会渗出来
        var o = 0
        while (o < pixels.size) {
            val a = (pixels[o + 3].toInt() and 255) / 255f
            src[o] = (pixels[o].toInt() and 255) / 255f * a; src[o + 1] = (pixels[o + 1].toInt() and 255) / 255f * a
            src[o + 2] = (pixels[o + 2].toInt() and 255) / 255f * a; src[o + 3] = a
            o += 4
        }
        val mid = FloatArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            val d = (y * width + x) * 4
            for (i in -n..n) {
                val sx = (x + i).coerceIn(0, width - 1)
                val so = (y * width + sx) * 4; val w = k[i + n]
                mid[d] += src[so] * w; mid[d + 1] += src[so + 1] * w; mid[d + 2] += src[so + 2] * w; mid[d + 3] += src[so + 3] * w
            }
        }
        for (y in 0 until height) for (x in 0 until width) {
            val d = (y * width + x) * 4
            var r0 = 0f; var g0 = 0f; var b0 = 0f; var a0 = 0f
            for (i in -n..n) {
                val sy = (y + i).coerceIn(0, height - 1)
                val so = (sy * width + x) * 4; val w = k[i + n]
                r0 += mid[so] * w; g0 += mid[so + 1] * w; b0 += mid[so + 2] * w; a0 += mid[so + 3] * w
            }
            if (a0 > 1e-5f) { pixels[d] = b8(r0 / a0); pixels[d + 1] = b8(g0 / a0); pixels[d + 2] = b8(b0 / a0) }
            pixels[d + 3] = b8(a0)
        }
        version++
    }

    /**
     * 把一张图（RGBA，sw×sh）画到 (dx,dy) 起、dw×dh 大小的区域里，双线性缩放，按当前混合方式叠上去。
     * 用来把模型原来的贴图、图片文件或另一块画布拿过来接着画。
     */
    fun drawImage(src: (Int) -> Int, sw: Int, sh: Int, dx: Float, dy: Float, dw: Float, dh: Float, alpha: Float) {
        if (sw <= 0 || sh <= 0 || dw <= 0f || dh <= 0f) return
        val x0 = max(0, dx.toInt()); val y0 = max(0, dy.toInt())
        val x1 = min(width - 1, (dx + dw).toInt()); val y1 = min(height - 1, (dy + dh).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val u = ((x + 0.5f - dx) / dw * sw - 0.5f).coerceIn(0f, sw - 1f)
            val v = ((y + 0.5f - dy) / dh * sh - 0.5f).coerceIn(0f, sh - 1f)
            val ux = u.toInt(); val vy = v.toInt()
            val ux1 = min(ux + 1, sw - 1); val vy1 = min(vy + 1, sh - 1)
            val fu = u - ux; val fv = v - vy
            var r = 0f; var g = 0f; var b = 0f; var a = 0f
            for (k in 0 until 4) {
                val so = ((if (k >= 2) vy1 else vy) * sw + (if (k % 2 == 1) ux1 else ux)) * 4
                val w = (if (k % 2 == 1) fu else 1f - fu) * (if (k >= 2) fv else 1f - fv)
                r += (src(so) and 255) * w; g += (src(so + 1) and 255) * w; b += (src(so + 2) and 255) * w; a += (src(so + 3) and 255) * w
            }
            mix((y * width + x) * 4, r / 255f, g / 255f, b / 255f, a / 255f, alpha)
        }
        version++
    }

    fun drawCanvas(c: Canvas, dx: Float, dy: Float, dw: Float, dh: Float, alpha: Float) {
        val p = if (c === this) pixels.copyOf() else c.pixels
        drawImage({ p[it].toInt() }, c.width, c.height, dx, dy, dw, dh, alpha)
    }

    private fun b8(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    companion object {
        const val MAX_SIZE = 1024
        const val PAINT_SOLID = 0
        const val PAINT_LINEAR = 1
        const val PAINT_RADIAL = 2
        const val BLEND_OVER = 0
        const val BLEND_ADD = 1
        const val BLEND_MULTIPLY = 2
        const val BLEND_SCREEN = 3
        const val BLEND_ERASE = 4
        const val BLEND_REPLACE = 5
        val BLEND_NAMES = listOf("over", "add", "multiply", "screen", "erase", "replace")
    }
}

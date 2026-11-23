package com.lumi.mmd.engine.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 四元数运算，存储为 FloatArray 中连续的 (x, y, z, w)。全部无分配。 */
object Quat {

    @JvmStatic
    fun identity(q: FloatArray, o: Int) {
        q[o] = 0f; q[o + 1] = 0f; q[o + 2] = 0f; q[o + 3] = 1f
    }

    @JvmStatic
    fun set(q: FloatArray, o: Int, x: Float, y: Float, z: Float, w: Float) {
        q[o] = x; q[o + 1] = y; q[o + 2] = z; q[o + 3] = w
    }

    @JvmStatic
    fun copy(dst: FloatArray, d: Int, src: FloatArray, s: Int) {
        dst[d] = src[s]; dst[d + 1] = src[s + 1]; dst[d + 2] = src[s + 2]; dst[d + 3] = src[s + 3]
    }

    /** dst = a ⊗ b（先转 b 再转 a）。dst 可与 a/b 重合。 */
    @JvmStatic
    fun mul(dst: FloatArray, d: Int, a: FloatArray, ao: Int, b: FloatArray, bo: Int) {
        val ax = a[ao]; val ay = a[ao + 1]; val az = a[ao + 2]; val aw = a[ao + 3]
        val bx = b[bo]; val by = b[bo + 1]; val bz = b[bo + 2]; val bw = b[bo + 3]
        dst[d] = aw * bx + ax * bw + ay * bz - az * by
        dst[d + 1] = aw * by - ax * bz + ay * bw + az * bx
        dst[d + 2] = aw * bz + ax * by - ay * bx + az * bw
        dst[d + 3] = aw * bw - ax * bx - ay * by - az * bz
    }

    @JvmStatic
    fun normalize(q: FloatArray, o: Int) {
        val x = q[o]; val y = q[o + 1]; val z = q[o + 2]; val w = q[o + 3]
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n < 1e-12f) {
            q[o] = 0f; q[o + 1] = 0f; q[o + 2] = 0f; q[o + 3] = 1f
        } else {
            val inv = 1f / n
            q[o] = x * inv; q[o + 1] = y * inv; q[o + 2] = z * inv; q[o + 3] = w * inv
        }
    }

    @JvmStatic
    fun conjugate(dst: FloatArray, d: Int, src: FloatArray, s: Int) {
        dst[d] = -src[s]; dst[d + 1] = -src[s + 1]; dst[d + 2] = -src[s + 2]; dst[d + 3] = src[s + 3]
    }

    @JvmStatic
    fun isIdentity(q: FloatArray, o: Int): Boolean =
        q[o] == 0f && q[o + 1] == 0f && q[o + 2] == 0f && (q[o + 3] == 1f || q[o + 3] == -1f)

    /** 最短弧球面插值。dst 可与 a/b 重合。 */
    @JvmStatic
    fun slerp(dst: FloatArray, d: Int, a: FloatArray, ao: Int, b: FloatArray, bo: Int, t: Float) {
        val ax = a[ao]; val ay = a[ao + 1]; val az = a[ao + 2]; val aw = a[ao + 3]
        var bx = b[bo]; var by = b[bo + 1]; var bz = b[bo + 2]; var bw = b[bo + 3]
        var cosom = ax * bx + ay * by + az * bz + aw * bw
        if (cosom < 0f) {
            cosom = -cosom; bx = -bx; by = -by; bz = -bz; bw = -bw
        }
        val s0: Float
        val s1: Float
        if (cosom > 0.9995f) {
            s0 = 1f - t; s1 = t
        } else {
            val omega = acos(cosom)
            val sinom = sin(omega)
            s0 = sin((1f - t) * omega) / sinom
            s1 = sin(t * omega) / sinom
        }
        val x = s0 * ax + s1 * bx
        val y = s0 * ay + s1 * by
        val z = s0 * az + s1 * bz
        val w = s0 * aw + s1 * bw
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n < 1e-12f) {
            dst[d] = 0f; dst[d + 1] = 0f; dst[d + 2] = 0f; dst[d + 3] = 1f
        } else {
            val inv = 1f / n
            dst[d] = x * inv; dst[d + 1] = y * inv; dst[d + 2] = z * inv; dst[d + 3] = w * inv
        }
    }

    /** 绕单位轴 (ax,ay,az) 旋转 angle 弧度。 */
    @JvmStatic
    fun fromAxisAngle(dst: FloatArray, d: Int, ax: Float, ay: Float, az: Float, angle: Float) {
        val h = angle * 0.5f
        val s = sin(h)
        dst[d] = ax * s; dst[d + 1] = ay * s; dst[d + 2] = az * s; dst[d + 3] = cos(h)
    }

    /**
     * MMD 的欧拉角约定：R = Ry · Rx · Rz（先绕 Z，再绕 X，最后绕 Y）。
     * 刚体/关节的初始姿态、骨骼欧拉角显示都用这个顺序。
     */
    @JvmStatic
    fun fromEulerYXZ(dst: FloatArray, d: Int, rx: Float, ry: Float, rz: Float) {
        val cx = cos(rx * 0.5f); val sx = sin(rx * 0.5f)
        val cy = cos(ry * 0.5f); val sy = sin(ry * 0.5f)
        val cz = cos(rz * 0.5f); val sz = sin(rz * 0.5f)
        dst[d] = cy * sx * cz + sy * cx * sz
        dst[d + 1] = sy * cx * cz - cy * sx * sz
        dst[d + 2] = cy * cx * sz - sy * sx * cz
        dst[d + 3] = cy * cx * cz + sy * sx * sz
    }

    /** [fromEulerYXZ] 的逆：把四元数分解成 R = Ry·Rx·Rz 的 (rx, ry, rz)。 */
    @JvmStatic
    fun toEulerYXZ(q: FloatArray, o: Int, out: FloatArray, oo: Int) {
        val x = q[o]; val y = q[o + 1]; val z = q[o + 2]; val w = q[o + 3]
        // 旋转矩阵元素（行,列）
        val m12 = 2f * (y * z - w * x)
        val sx = (-m12).coerceIn(-1f, 1f)
        val rx = kotlin.math.asin(sx)
        val ry: Float
        val rz: Float
        if (abs(sx) < 0.99999f) {
            val m02 = 2f * (x * z + w * y)
            val m22 = 1f - 2f * (x * x + y * y)
            val m10 = 2f * (x * y + w * z)
            val m11 = 1f - 2f * (x * x + z * z)
            ry = atan2(m02, m22)
            rz = atan2(m10, m11)
        } else {
            val m20 = 2f * (x * z - w * y)
            val m00 = 1f - 2f * (y * y + z * z)
            ry = atan2(-m20, m00)
            rz = 0f
        }
        out[oo] = rx; out[oo + 1] = ry; out[oo + 2] = rz
    }

    /** dst = q^weight：保持转轴、把转角乘以 weight（付与骨的"付与率"，可为负或大于 1）。 */
    @JvmStatic
    fun pow(dst: FloatArray, d: Int, q: FloatArray, o: Int, weight: Float) {
        var x = q[o]; var y = q[o + 1]; var z = q[o + 2]; var w = q[o + 3]
        if (w < 0f) { x = -x; y = -y; z = -z; w = -w }
        val sinHalf = sqrt(x * x + y * y + z * z)
        if (sinHalf < 1e-7f || weight == 0f) {
            dst[d] = 0f; dst[d + 1] = 0f; dst[d + 2] = 0f; dst[d + 3] = 1f
            return
        }
        if (weight == 1f) {
            dst[d] = x; dst[d + 1] = y; dst[d + 2] = z; dst[d + 3] = w
            return
        }
        val half = atan2(sinHalf, w) * weight
        val s = sin(half) / sinHalf
        dst[d] = x * s; dst[d + 1] = y * s; dst[d + 2] = z * s; dst[d + 3] = cos(half)
    }

    /** out = q 旋转向量 (vx,vy,vz)。 */
    @JvmStatic
    fun rotate(q: FloatArray, o: Int, vx: Float, vy: Float, vz: Float, out: FloatArray, oo: Int) {
        val x = q[o]; val y = q[o + 1]; val z = q[o + 2]; val w = q[o + 3]
        // t = 2 * cross(q.xyz, v)
        val tx = 2f * (y * vz - z * vy)
        val ty = 2f * (z * vx - x * vz)
        val tz = 2f * (x * vy - y * vx)
        out[oo] = vx + w * tx + (y * tz - z * ty)
        out[oo + 1] = vy + w * ty + (z * tx - x * tz)
        out[oo + 2] = vz + w * tz + (x * ty - y * tx)
    }

    /** 把 from 方向转到 to 方向的最短旋转（两者都须已归一化）。 */
    @JvmStatic
    fun fromTo(dst: FloatArray, d: Int, fx: Float, fy: Float, fz: Float, tx: Float, ty: Float, tz: Float) {
        val dot = fx * tx + fy * ty + fz * tz
        if (dot > 0.999999f) { identity(dst, d); return }
        if (dot < -0.999999f) {
            // 反向：任取一个垂直轴转 180°
            var ax = 0f; var ay = -fz; var az = fy
            if (abs(fx) < 0.6f) { ax = 0f; ay = -fz; az = fy } else { ax = -fy; ay = fx; az = 0f }
            val l = sqrt(ax * ax + ay * ay + az * az)
            dst[d] = ax / l; dst[d + 1] = ay / l; dst[d + 2] = az / l; dst[d + 3] = 0f
            return
        }
        val cx = fy * tz - fz * ty
        val cy = fz * tx - fx * tz
        val cz = fx * ty - fy * tx
        val w = 1f + dot
        val n = sqrt(cx * cx + cy * cy + cz * cz + w * w)
        dst[d] = cx / n; dst[d + 1] = cy / n; dst[d + 2] = cz / n; dst[d + 3] = w / n
    }
}

package com.lumi.mmd.engine.math

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 4x4 矩阵运算，全部直接作用在 FloatArray + 偏移上（列主序，与 OpenGL 一致），
 * 逐帧热路径里不产生任何对象分配。
 *
 * 约定：向量是列向量，`mul(dst, a, b)` 得到 a·b（先应用 b 再应用 a）。
 * 整个引擎保持 MMD 原生的左手坐标系（X 右、Y 上、Z 向前），
 * 只有视图/投影矩阵（[lookAtLH]/[perspectiveLH]/[orthoLH]）体现"左手"。
 */
object Mat4 {

    @JvmStatic
    fun identity(m: FloatArray, o: Int) {
        m[o] = 1f; m[o + 1] = 0f; m[o + 2] = 0f; m[o + 3] = 0f
        m[o + 4] = 0f; m[o + 5] = 1f; m[o + 6] = 0f; m[o + 7] = 0f
        m[o + 8] = 0f; m[o + 9] = 0f; m[o + 10] = 1f; m[o + 11] = 0f
        m[o + 12] = 0f; m[o + 13] = 0f; m[o + 14] = 0f; m[o + 15] = 1f
    }

    @JvmStatic
    fun copy(dst: FloatArray, d: Int, src: FloatArray, s: Int) {
        System.arraycopy(src, s, dst, d, 16)
    }

    /** dst = a · b（通用 4x4）。dst 可以与 a 或 b 完全重合。 */
    @JvmStatic
    fun mul(dst: FloatArray, d: Int, a: FloatArray, ao: Int, b: FloatArray, bo: Int) {
        val a00 = a[ao]; val a10 = a[ao + 1]; val a20 = a[ao + 2]; val a30 = a[ao + 3]
        val a01 = a[ao + 4]; val a11 = a[ao + 5]; val a21 = a[ao + 6]; val a31 = a[ao + 7]
        val a02 = a[ao + 8]; val a12 = a[ao + 9]; val a22 = a[ao + 10]; val a32 = a[ao + 11]
        val a03 = a[ao + 12]; val a13 = a[ao + 13]; val a23 = a[ao + 14]; val a33 = a[ao + 15]
        var b0 = b[bo]; var b1 = b[bo + 1]; var b2 = b[bo + 2]; var b3 = b[bo + 3]
        dst[d] = a00 * b0 + a01 * b1 + a02 * b2 + a03 * b3
        dst[d + 1] = a10 * b0 + a11 * b1 + a12 * b2 + a13 * b3
        dst[d + 2] = a20 * b0 + a21 * b1 + a22 * b2 + a23 * b3
        dst[d + 3] = a30 * b0 + a31 * b1 + a32 * b2 + a33 * b3
        b0 = b[bo + 4]; b1 = b[bo + 5]; b2 = b[bo + 6]; b3 = b[bo + 7]
        dst[d + 4] = a00 * b0 + a01 * b1 + a02 * b2 + a03 * b3
        dst[d + 5] = a10 * b0 + a11 * b1 + a12 * b2 + a13 * b3
        dst[d + 6] = a20 * b0 + a21 * b1 + a22 * b2 + a23 * b3
        dst[d + 7] = a30 * b0 + a31 * b1 + a32 * b2 + a33 * b3
        b0 = b[bo + 8]; b1 = b[bo + 9]; b2 = b[bo + 10]; b3 = b[bo + 11]
        dst[d + 8] = a00 * b0 + a01 * b1 + a02 * b2 + a03 * b3
        dst[d + 9] = a10 * b0 + a11 * b1 + a12 * b2 + a13 * b3
        dst[d + 10] = a20 * b0 + a21 * b1 + a22 * b2 + a23 * b3
        dst[d + 11] = a30 * b0 + a31 * b1 + a32 * b2 + a33 * b3
        b0 = b[bo + 12]; b1 = b[bo + 13]; b2 = b[bo + 14]; b3 = b[bo + 15]
        dst[d + 12] = a00 * b0 + a01 * b1 + a02 * b2 + a03 * b3
        dst[d + 13] = a10 * b0 + a11 * b1 + a12 * b2 + a13 * b3
        dst[d + 14] = a20 * b0 + a21 * b1 + a22 * b2 + a23 * b3
        dst[d + 15] = a30 * b0 + a31 * b1 + a32 * b2 + a33 * b3
    }

    /** dst = a · b，假定两者都是仿射矩阵（末行 0,0,0,1）。骨骼层级更新用这个，省掉近一半乘法。 */
    @JvmStatic
    fun mulAffine(dst: FloatArray, d: Int, a: FloatArray, ao: Int, b: FloatArray, bo: Int) {
        val a00 = a[ao]; val a10 = a[ao + 1]; val a20 = a[ao + 2]
        val a01 = a[ao + 4]; val a11 = a[ao + 5]; val a21 = a[ao + 6]
        val a02 = a[ao + 8]; val a12 = a[ao + 9]; val a22 = a[ao + 10]
        val a03 = a[ao + 12]; val a13 = a[ao + 13]; val a23 = a[ao + 14]
        var b0 = b[bo]; var b1 = b[bo + 1]; var b2 = b[bo + 2]
        dst[d] = a00 * b0 + a01 * b1 + a02 * b2
        dst[d + 1] = a10 * b0 + a11 * b1 + a12 * b2
        dst[d + 2] = a20 * b0 + a21 * b1 + a22 * b2
        dst[d + 3] = 0f
        b0 = b[bo + 4]; b1 = b[bo + 5]; b2 = b[bo + 6]
        dst[d + 4] = a00 * b0 + a01 * b1 + a02 * b2
        dst[d + 5] = a10 * b0 + a11 * b1 + a12 * b2
        dst[d + 6] = a20 * b0 + a21 * b1 + a22 * b2
        dst[d + 7] = 0f
        b0 = b[bo + 8]; b1 = b[bo + 9]; b2 = b[bo + 10]
        dst[d + 8] = a00 * b0 + a01 * b1 + a02 * b2
        dst[d + 9] = a10 * b0 + a11 * b1 + a12 * b2
        dst[d + 10] = a20 * b0 + a21 * b1 + a22 * b2
        dst[d + 11] = 0f
        b0 = b[bo + 12]; b1 = b[bo + 13]; b2 = b[bo + 14]
        dst[d + 12] = a00 * b0 + a01 * b1 + a02 * b2 + a03
        dst[d + 13] = a10 * b0 + a11 * b1 + a12 * b2 + a13
        dst[d + 14] = a20 * b0 + a21 * b1 + a22 * b2 + a23
        dst[d + 15] = 1f
    }

    /** m = T(tx,ty,tz) · R(q)。四元数须为单位四元数。 */
    @JvmStatic
    fun setTR(m: FloatArray, o: Int, tx: Float, ty: Float, tz: Float, qx: Float, qy: Float, qz: Float, qw: Float) {
        val x2 = qx + qx; val y2 = qy + qy; val z2 = qz + qz
        val xx = qx * x2; val xy = qx * y2; val xz = qx * z2
        val yy = qy * y2; val yz = qy * z2; val zz = qz * z2
        val wx = qw * x2; val wy = qw * y2; val wz = qw * z2
        m[o] = 1f - (yy + zz); m[o + 1] = xy + wz; m[o + 2] = xz - wy; m[o + 3] = 0f
        m[o + 4] = xy - wz; m[o + 5] = 1f - (xx + zz); m[o + 6] = yz + wx; m[o + 7] = 0f
        m[o + 8] = xz + wy; m[o + 9] = yz - wx; m[o + 10] = 1f - (xx + yy); m[o + 11] = 0f
        m[o + 12] = tx; m[o + 13] = ty; m[o + 14] = tz; m[o + 15] = 1f
    }

    /** m = T · R(q) · S(sx,sy,sz)。 */
    @JvmStatic
    fun setTRS(
        m: FloatArray, o: Int,
        tx: Float, ty: Float, tz: Float,
        qx: Float, qy: Float, qz: Float, qw: Float,
        sx: Float, sy: Float, sz: Float
    ) {
        setTR(m, o, tx, ty, tz, qx, qy, qz, qw)
        m[o] *= sx; m[o + 1] *= sx; m[o + 2] *= sx
        m[o + 4] *= sy; m[o + 5] *= sy; m[o + 6] *= sy
        m[o + 8] *= sz; m[o + 9] *= sz; m[o + 10] *= sz
    }

    @JvmStatic
    fun setTranslation(m: FloatArray, o: Int, tx: Float, ty: Float, tz: Float) {
        identity(m, o)
        m[o + 12] = tx; m[o + 13] = ty; m[o + 14] = tz
    }

    @JvmStatic
    fun setScale(m: FloatArray, o: Int, sx: Float, sy: Float, sz: Float) {
        identity(m, o)
        m[o] = sx; m[o + 5] = sy; m[o + 10] = sz
    }

    /** 仿射矩阵求逆（3x3 部分可带缩放/剪切）。奇异时写入单位阵并返回 false。dst 可与 src 重合。 */
    @JvmStatic
    fun invertAffine(dst: FloatArray, d: Int, s: FloatArray, so: Int): Boolean {
        val a00 = s[so]; val a10 = s[so + 1]; val a20 = s[so + 2]
        val a01 = s[so + 4]; val a11 = s[so + 5]; val a21 = s[so + 6]
        val a02 = s[so + 8]; val a12 = s[so + 9]; val a22 = s[so + 10]
        val tx = s[so + 12]; val ty = s[so + 13]; val tz = s[so + 14]
        val c00 = a11 * a22 - a12 * a21
        val c01 = a12 * a20 - a10 * a22
        val c02 = a10 * a21 - a11 * a20
        val det = a00 * c00 + a01 * c01 + a02 * c02
        if (abs(det) < 1e-30f) {
            identity(dst, d)
            return false
        }
        val inv = 1f / det
        val i00 = c00 * inv
        val i10 = c01 * inv
        val i20 = c02 * inv
        val i01 = (a02 * a21 - a01 * a22) * inv
        val i11 = (a00 * a22 - a02 * a20) * inv
        val i21 = (a01 * a20 - a00 * a21) * inv
        val i02 = (a01 * a12 - a02 * a11) * inv
        val i12 = (a02 * a10 - a00 * a12) * inv
        val i22 = (a00 * a11 - a01 * a10) * inv
        dst[d] = i00; dst[d + 1] = i10; dst[d + 2] = i20; dst[d + 3] = 0f
        dst[d + 4] = i01; dst[d + 5] = i11; dst[d + 6] = i21; dst[d + 7] = 0f
        dst[d + 8] = i02; dst[d + 9] = i12; dst[d + 10] = i22; dst[d + 11] = 0f
        dst[d + 12] = -(i00 * tx + i01 * ty + i02 * tz)
        dst[d + 13] = -(i10 * tx + i11 * ty + i12 * tz)
        dst[d + 14] = -(i20 * tx + i21 * ty + i22 * tz)
        dst[d + 15] = 1f
        return true
    }

    /** 刚体变换（旋转+平移，无缩放）求逆：转置旋转部分即可。dst 可与 src 重合。 */
    @JvmStatic
    fun invertRigid(dst: FloatArray, d: Int, s: FloatArray, so: Int) {
        val a00 = s[so]; val a10 = s[so + 1]; val a20 = s[so + 2]
        val a01 = s[so + 4]; val a11 = s[so + 5]; val a21 = s[so + 6]
        val a02 = s[so + 8]; val a12 = s[so + 9]; val a22 = s[so + 10]
        val tx = s[so + 12]; val ty = s[so + 13]; val tz = s[so + 14]
        dst[d] = a00; dst[d + 1] = a01; dst[d + 2] = a02; dst[d + 3] = 0f
        dst[d + 4] = a10; dst[d + 5] = a11; dst[d + 6] = a12; dst[d + 7] = 0f
        dst[d + 8] = a20; dst[d + 9] = a21; dst[d + 10] = a22; dst[d + 11] = 0f
        dst[d + 12] = -(a00 * tx + a10 * ty + a20 * tz)
        dst[d + 13] = -(a01 * tx + a11 * ty + a21 * tz)
        dst[d + 14] = -(a02 * tx + a12 * ty + a22 * tz)
        dst[d + 15] = 1f
    }

    /** 通用 4x4 求逆（视图投影矩阵反投影用）。dst 不可与 src 重合。 */
    @JvmStatic
    fun invert(dst: FloatArray, d: Int, m: FloatArray, o: Int): Boolean {
        val m0 = m[o]; val m1 = m[o + 1]; val m2 = m[o + 2]; val m3 = m[o + 3]
        val m4 = m[o + 4]; val m5 = m[o + 5]; val m6 = m[o + 6]; val m7 = m[o + 7]
        val m8 = m[o + 8]; val m9 = m[o + 9]; val m10 = m[o + 10]; val m11 = m[o + 11]
        val m12 = m[o + 12]; val m13 = m[o + 13]; val m14 = m[o + 14]; val m15 = m[o + 15]

        val i0 = m5 * m10 * m15 - m5 * m11 * m14 - m9 * m6 * m15 + m9 * m7 * m14 + m13 * m6 * m11 - m13 * m7 * m10
        val i4 = -m4 * m10 * m15 + m4 * m11 * m14 + m8 * m6 * m15 - m8 * m7 * m14 - m12 * m6 * m11 + m12 * m7 * m10
        val i8 = m4 * m9 * m15 - m4 * m11 * m13 - m8 * m5 * m15 + m8 * m7 * m13 + m12 * m5 * m11 - m12 * m7 * m9
        val i12 = -m4 * m9 * m14 + m4 * m10 * m13 + m8 * m5 * m14 - m8 * m6 * m13 - m12 * m5 * m10 + m12 * m6 * m9
        val det = m0 * i0 + m1 * i4 + m2 * i8 + m3 * i12
        if (abs(det) < 1e-30f) {
            identity(dst, d)
            return false
        }
        val inv = 1f / det
        dst[d] = i0 * inv
        dst[d + 4] = i4 * inv
        dst[d + 8] = i8 * inv
        dst[d + 12] = i12 * inv
        dst[d + 1] = (-m1 * m10 * m15 + m1 * m11 * m14 + m9 * m2 * m15 - m9 * m3 * m14 - m13 * m2 * m11 + m13 * m3 * m10) * inv
        dst[d + 5] = (m0 * m10 * m15 - m0 * m11 * m14 - m8 * m2 * m15 + m8 * m3 * m14 + m12 * m2 * m11 - m12 * m3 * m10) * inv
        dst[d + 9] = (-m0 * m9 * m15 + m0 * m11 * m13 + m8 * m1 * m15 - m8 * m3 * m13 - m12 * m1 * m11 + m12 * m3 * m9) * inv
        dst[d + 13] = (m0 * m9 * m14 - m0 * m10 * m13 - m8 * m1 * m14 + m8 * m2 * m13 + m12 * m1 * m10 - m12 * m2 * m9) * inv
        dst[d + 2] = (m1 * m6 * m15 - m1 * m7 * m14 - m5 * m2 * m15 + m5 * m3 * m14 + m13 * m2 * m7 - m13 * m3 * m6) * inv
        dst[d + 6] = (-m0 * m6 * m15 + m0 * m7 * m14 + m4 * m2 * m15 - m4 * m3 * m14 - m12 * m2 * m7 + m12 * m3 * m6) * inv
        dst[d + 10] = (m0 * m5 * m15 - m0 * m7 * m13 - m4 * m1 * m15 + m4 * m3 * m13 + m12 * m1 * m7 - m12 * m3 * m5) * inv
        dst[d + 14] = (-m0 * m5 * m14 + m0 * m6 * m13 + m4 * m1 * m14 - m4 * m2 * m13 - m12 * m1 * m6 + m12 * m2 * m5) * inv
        dst[d + 3] = (-m1 * m6 * m11 + m1 * m7 * m10 + m5 * m2 * m11 - m5 * m3 * m10 - m9 * m2 * m7 + m9 * m3 * m6) * inv
        dst[d + 7] = (m0 * m6 * m11 - m0 * m7 * m10 - m4 * m2 * m11 + m4 * m3 * m10 + m8 * m2 * m7 - m8 * m3 * m6) * inv
        dst[d + 11] = (-m0 * m5 * m11 + m0 * m7 * m9 + m4 * m1 * m11 - m4 * m3 * m9 - m8 * m1 * m7 + m8 * m3 * m5) * inv
        dst[d + 15] = (m0 * m5 * m10 - m0 * m6 * m9 - m4 * m1 * m10 + m4 * m2 * m9 + m8 * m1 * m6 - m8 * m2 * m5) * inv
        return true
    }

    /** out = m · (x,y,z,1) 的 xyz（仿射）。 */
    @JvmStatic
    fun transformPoint(m: FloatArray, o: Int, x: Float, y: Float, z: Float, out: FloatArray, oo: Int) {
        out[oo] = m[o] * x + m[o + 4] * y + m[o + 8] * z + m[o + 12]
        out[oo + 1] = m[o + 1] * x + m[o + 5] * y + m[o + 9] * z + m[o + 13]
        out[oo + 2] = m[o + 2] * x + m[o + 6] * y + m[o + 10] * z + m[o + 14]
    }

    /** out = m 的 3x3 部分 · (x,y,z)。 */
    @JvmStatic
    fun transformDir(m: FloatArray, o: Int, x: Float, y: Float, z: Float, out: FloatArray, oo: Int) {
        out[oo] = m[o] * x + m[o + 4] * y + m[o + 8] * z
        out[oo + 1] = m[o + 1] * x + m[o + 5] * y + m[o + 9] * z
        out[oo + 2] = m[o + 2] * x + m[o + 6] * y + m[o + 10] * z
    }

    /** out(4) = m · (x,y,z,w)。 */
    @JvmStatic
    fun transformVec4(m: FloatArray, o: Int, x: Float, y: Float, z: Float, w: Float, out: FloatArray, oo: Int) {
        out[oo] = m[o] * x + m[o + 4] * y + m[o + 8] * z + m[o + 12] * w
        out[oo + 1] = m[o + 1] * x + m[o + 5] * y + m[o + 9] * z + m[o + 13] * w
        out[oo + 2] = m[o + 2] * x + m[o + 6] * y + m[o + 10] * z + m[o + 14] * w
        out[oo + 3] = m[o + 3] * x + m[o + 7] * y + m[o + 11] * z + m[o + 15] * w
    }

    /** 左手系透视投影，深度映射到 GL 的 [-1,1]。fovY 为弧度。 */
    @JvmStatic
    fun perspectiveLH(m: FloatArray, o: Int, fovY: Float, aspect: Float, near: Float, far: Float) {
        val f = 1f / tan(fovY * 0.5f)
        for (i in 0 until 16) m[o + i] = 0f
        m[o] = f / aspect
        m[o + 5] = f
        m[o + 10] = (far + near) / (far - near)
        m[o + 11] = 1f
        m[o + 14] = -2f * far * near / (far - near)
    }

    /** 左手系正交投影。 */
    @JvmStatic
    fun orthoLH(m: FloatArray, o: Int, l: Float, r: Float, b: Float, t: Float, near: Float, far: Float) {
        for (i in 0 until 16) m[o + i] = 0f
        m[o] = 2f / (r - l)
        m[o + 5] = 2f / (t - b)
        m[o + 10] = 2f / (far - near)
        m[o + 12] = -(r + l) / (r - l)
        m[o + 13] = -(t + b) / (t - b)
        m[o + 14] = -(far + near) / (far - near)
        m[o + 15] = 1f
    }

    /** 左手系 LookAt 视图矩阵。 */
    @JvmStatic
    fun lookAtLH(
        m: FloatArray, o: Int,
        ex: Float, ey: Float, ez: Float,
        tx: Float, ty: Float, tz: Float,
        ux: Float, uy: Float, uz: Float
    ) {
        var zx = tx - ex; var zy = ty - ey; var zz = tz - ez
        var l = sqrt(zx * zx + zy * zy + zz * zz)
        if (l < 1e-12f) { zx = 0f; zy = 0f; zz = 1f } else { l = 1f / l; zx *= l; zy *= l; zz *= l }
        // x = up × z
        var xx = uy * zz - uz * zy
        var xy = uz * zx - ux * zz
        var xz = ux * zy - uy * zx
        l = sqrt(xx * xx + xy * xy + xz * xz)
        if (l < 1e-12f) {
            // up 与视线平行：换一个参考轴
            xx = zz; xy = 0f; xz = -zx
            l = sqrt(xx * xx + xy * xy + xz * xz)
            if (l < 1e-12f) { xx = 1f; xy = 0f; xz = 0f; l = 1f }
        }
        l = 1f / l; xx *= l; xy *= l; xz *= l
        // y = z × x
        val yx = zy * xz - zz * xy
        val yy = zz * xx - zx * xz
        val yz = zx * xy - zy * xx
        m[o] = xx; m[o + 1] = yx; m[o + 2] = zx; m[o + 3] = 0f
        m[o + 4] = xy; m[o + 5] = yy; m[o + 6] = zy; m[o + 7] = 0f
        m[o + 8] = xz; m[o + 9] = yz; m[o + 10] = zz; m[o + 11] = 0f
        m[o + 12] = -(xx * ex + xy * ey + xz * ez)
        m[o + 13] = -(yx * ex + yy * ey + yz * ez)
        m[o + 14] = -(zx * ex + zy * ey + zz * ez)
        m[o + 15] = 1f
    }

    /** 从仿射矩阵的旋转部分提取单位四元数（先去掉缩放）。 */
    @JvmStatic
    fun toQuat(m: FloatArray, o: Int, q: FloatArray, qo: Int) {
        var m00 = m[o]; var m10 = m[o + 1]; var m20 = m[o + 2]
        var m01 = m[o + 4]; var m11 = m[o + 5]; var m21 = m[o + 6]
        var m02 = m[o + 8]; var m12 = m[o + 9]; var m22 = m[o + 10]
        var l = sqrt(m00 * m00 + m10 * m10 + m20 * m20)
        if (l > 1e-12f) { l = 1f / l; m00 *= l; m10 *= l; m20 *= l }
        l = sqrt(m01 * m01 + m11 * m11 + m21 * m21)
        if (l > 1e-12f) { l = 1f / l; m01 *= l; m11 *= l; m21 *= l }
        l = sqrt(m02 * m02 + m12 * m12 + m22 * m22)
        if (l > 1e-12f) { l = 1f / l; m02 *= l; m12 *= l; m22 *= l }
        val tr = m00 + m11 + m22
        val x: Float; val y: Float; val z: Float; val w: Float
        if (tr > 0f) {
            val s = sqrt(tr + 1f) * 2f
            w = 0.25f * s
            x = (m21 - m12) / s
            y = (m02 - m20) / s
            z = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            w = (m21 - m12) / s
            x = 0.25f * s
            y = (m01 + m10) / s
            z = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            w = (m02 - m20) / s
            x = (m01 + m10) / s
            y = 0.25f * s
            z = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            w = (m10 - m01) / s
            x = (m02 + m20) / s
            y = (m12 + m21) / s
            z = 0.25f * s
        }
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n < 1e-12f) {
            q[qo] = 0f; q[qo + 1] = 0f; q[qo + 2] = 0f; q[qo + 3] = 1f
        } else {
            val inv = 1f / n
            q[qo] = x * inv; q[qo + 1] = y * inv; q[qo + 2] = z * inv; q[qo + 3] = w * inv
        }
    }
}

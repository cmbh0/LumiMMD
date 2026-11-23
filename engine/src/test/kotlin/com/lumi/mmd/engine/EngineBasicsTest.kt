package com.lumi.mmd.engine

import com.lumi.mmd.engine.math.Mat4
import com.lumi.mmd.engine.math.Quat
import com.lumi.mmd.engine.util.Base64Lite
import com.lumi.mmd.engine.util.Json
import com.lumi.mmd.engine.util.jObj
import com.lumi.mmd.engine.util.arr
import com.lumi.mmd.engine.util.int
import com.lumi.mmd.engine.util.str
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 不依赖 GPU 的基础件：四元数 / 矩阵 / JSON / Base64。更完整的流程测试在 app 模块的 AppFlowTest 里。 */
class EngineBasicsTest {
    private fun assertQuat(expected: FloatArray, actual: FloatArray, eps: Float = 1e-4f) {
        // q 和 -q 表示同一个旋转
        val sign = if (expected[0] * actual[0] + expected[1] * actual[1] + expected[2] * actual[2] + expected[3] * actual[3] < 0f) -1f else 1f
        for (i in 0 until 4) assertEquals("分量 $i", expected[i], actual[i] * sign, eps)
    }

    @Test fun quaternionEulerRoundTrip() {
        val q = FloatArray(4); val e = FloatArray(3); val q2 = FloatArray(4)
        for (angles in listOf(floatArrayOf(0.3f, -1.1f, 0.6f), floatArrayOf(-0.9f, 2.4f, -0.2f), floatArrayOf(0f, 0f, 0f), floatArrayOf(1.2f, 0.1f, 3.0f))) {
            Quat.fromEulerYXZ(q, 0, angles[0], angles[1], angles[2])
            Quat.toEulerYXZ(q, 0, e, 0)
            Quat.fromEulerYXZ(q2, 0, e[0], e[1], e[2])
            assertQuat(q, q2)
        }
    }

    @Test fun quaternionRotatesVectors() {
        val q = FloatArray(4); val out = FloatArray(3)
        // 绕 Y 轴转 90°：左手系里 +X 转到 -Z
        Quat.fromAxisAngle(q, 0, 0f, 1f, 0f, (Math.PI / 2).toFloat())
        Quat.rotate(q, 0, 1f, 0f, 0f, out, 0)
        assertEquals(0f, out[0], 1e-5f); assertEquals(0f, out[1], 1e-5f); assertEquals(1f, abs(out[2]), 1e-5f)
        // 两次 90° = 一次 180°
        val q2 = FloatArray(4); val q180 = FloatArray(4)
        Quat.mul(q2, 0, q, 0, q, 0)
        Quat.fromAxisAngle(q180, 0, 0f, 1f, 0f, Math.PI.toFloat())
        assertQuat(q180, q2)
        // 共轭是逆
        val c = FloatArray(4); val id = FloatArray(4)
        Quat.conjugate(c, 0, q, 0)
        Quat.mul(id, 0, q, 0, c, 0)
        assertQuat(floatArrayOf(0f, 0f, 0f, 1f), id, 1e-6f)
    }

    @Test fun slerpEndpointsAndMidpoint() {
        val a = FloatArray(4); val b = FloatArray(4); val r = FloatArray(4); val half = FloatArray(4)
        Quat.identity(a, 0)
        Quat.fromAxisAngle(b, 0, 0f, 0f, 1f, 1.2f)
        Quat.slerp(r, 0, a, 0, b, 0, 0f); assertQuat(a, r)
        Quat.slerp(r, 0, a, 0, b, 0, 1f); assertQuat(b, r)
        Quat.slerp(r, 0, a, 0, b, 0, 0.5f)
        Quat.fromAxisAngle(half, 0, 0f, 0f, 1f, 0.6f)
        assertQuat(half, r)
        assertEquals(1f, r[0] * r[0] + r[1] * r[1] + r[2] * r[2] + r[3] * r[3], 1e-5f)
    }

    @Test fun matrixInverseAndCompose() {
        val q = FloatArray(4)
        Quat.fromEulerYXZ(q, 0, 0.4f, -0.7f, 1.3f)
        val m = FloatArray(16); val inv = FloatArray(16); val p = FloatArray(16)
        Mat4.setTRS(m, 0, 3f, -2f, 5f, q[0], q[1], q[2], q[3], 2f, 2f, 2f)
        assertTrue(Mat4.invert(inv, 0, m, 0))
        Mat4.mul(p, 0, m, 0, inv, 0)
        val id = FloatArray(16).also { Mat4.identity(it, 0) }
        assertArrayEquals(id, p, 1e-4f)
        // 点变换：原点 → 平移量
        val out = FloatArray(3)
        Mat4.transformPoint(m, 0, 0f, 0f, 0f, out, 0)
        assertArrayEquals(floatArrayOf(3f, -2f, 5f), out, 1e-5f)
    }

    @Test fun jsonRoundTrip() {
        val text = """{"a": 1, "b": [true, null, 2.5, "x\u4e2d\n"], "c": {"d": -3e2}}"""
        val root = Json.parse(text).jObj()!!
        assertEquals(1, root.int("a"))
        val b = root.arr("b")!!
        assertEquals(4, b.size)
        assertEquals("x中\n", b[3])
        assertEquals(-300.0, (root["c"].jObj()!!["d"] as Number).toDouble(), 0.0)
        val again = Json.parse(Json.stringify(root)).jObj()!!
        assertEquals(root.int("a"), again.int("a"))
        assertEquals(b[3], again.arr("b")!![3])
        assertNull(root.str("missing"))
        assertEquals(-1, root.int("missing"))
    }

    @Test fun base64Decodes() {
        assertArrayEquals("Lumi MMD".toByteArray(), Base64Lite.decode("THVtaSBNTUQ="))
        assertArrayEquals(byteArrayOf(0, -1, 16), Base64Lite.decode("AP8Q"))
        // 容忍换行和 URL 安全字符
        assertArrayEquals(byteArrayOf(-5, -1), Base64Lite.decode("-_8=\n"))
    }

    @Test fun trigSanity() {
        // 保证测试里用的左手系约定和引擎一致：绕 Z 轴旋转 θ，把 +X 转向 +Y
        val q = FloatArray(4); val out = FloatArray(3)
        val t = 0.5f
        Quat.fromAxisAngle(q, 0, 0f, 0f, 1f, t)
        Quat.rotate(q, 0, 1f, 0f, 0f, out, 0)
        assertEquals(cos(t), out[0], 1e-5f)
        assertEquals(sin(t), abs(out[1]), 1e-5f)
    }
}

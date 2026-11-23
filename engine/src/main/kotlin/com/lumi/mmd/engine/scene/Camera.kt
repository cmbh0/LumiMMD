package com.lumi.mmd.engine.scene

import com.lumi.mmd.engine.math.Mat4
import com.lumi.mmd.engine.math.Quat
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * MMD 式的轨道相机：注视点 + 距离 + 欧拉角 + 视角。
 * 与 VMD 镜头数据使用同一套参数，所以"手动转视角"和"播放镜头动作"可以无缝切换。
 * 坐标系为左手系：角度全为 0 时相机位于注视点的 -Z 方向、朝 +Z 看（正对模型正面）。
 */
class Camera {
    val target = floatArrayOf(0f, 10f, 0f)
    /** 相机到注视点的距离（正数）。 */
    var distance = 45f
    /** 欧拉角（弧度），与 VMD 里存储的数值同号。 */
    var rotX = 0f
    var rotY = 0f
    var rotZ = 0f
    var fovDeg = 30f
    var perspective = true

    val view = FloatArray(16)
    val proj = FloatArray(16)
    val viewProj = FloatArray(16)
    val invViewProj = FloatArray(16)
    val position = FloatArray(3)
    val right = FloatArray(3)
    val up = FloatArray(3)
    val forward = FloatArray(3)
    var near = 1f
        private set
    var far = 10000f
        private set
    var aspect = 1f
        private set

    private val q = FloatArray(4)
    private val t = FloatArray(16)

    fun reset() {
        target[0] = 0f; target[1] = 10f; target[2] = 0f
        distance = 45f; rotX = 0f; rotY = 0f; rotZ = 0f; fovDeg = 30f; perspective = true
    }

    fun copyFrom(o: Camera) {
        System.arraycopy(o.target, 0, target, 0, 3)
        distance = o.distance; rotX = o.rotX; rotY = o.rotY; rotZ = o.rotZ; fovDeg = o.fovDeg; perspective = o.perspective
    }

    /** 根据当前参数重算各矩阵。 */
    fun update(aspect: Float, sceneRadius: Float = 200f) {
        this.aspect = aspect
        // R = Ry(-ry) · Rx(-rx) · Rz(-rz)
        Quat.fromEulerYXZ(q, 0, -rotX, -rotY, -rotZ)
        Quat.rotate(q, 0, 0f, 0f, 1f, forward, 0)
        Quat.rotate(q, 0, 0f, 1f, 0f, up, 0)
        Quat.rotate(q, 0, 1f, 0f, 0f, right, 0)
        position[0] = target[0] - forward[0] * distance
        position[1] = target[1] - forward[1] * distance
        position[2] = target[2] - forward[2] * distance
        Mat4.lookAtLH(view, 0, position[0], position[1], position[2],
            position[0] + forward[0], position[1] + forward[1], position[2] + forward[2], up[0], up[1], up[2])
        val d = kotlin.math.abs(distance)
        near = (d * 0.04f).coerceIn(0.3f, 8f)
        far = maxOf(near * 4000f, d + sceneRadius * 4f)
        if (perspective) {
            Mat4.perspectiveLH(proj, 0, fovDeg.coerceIn(1f, 150f) * DEG, aspect, near, far)
        } else {
            val h = maxOf(d, 1f) * tan(fovDeg.coerceIn(1f, 150f) * DEG * 0.5f)
            Mat4.orthoLH(proj, 0, -h * aspect, h * aspect, -h, h, -far, far)
        }
        Mat4.mul(viewProj, 0, proj, 0, view, 0)
        Mat4.invert(invViewProj, 0, viewProj, 0)
    }

    /** VMD 镜头帧：distance, px,py,pz, rx,ry,rz, fov。 */
    fun setFromVmd(v: FloatArray, perspective: Boolean) {
        distance = -v[0]
        target[0] = v[1]; target[1] = v[2]; target[2] = v[3]
        rotX = v[4]; rotY = v[5]; rotZ = v[6]
        fovDeg = v[7]
        this.perspective = perspective
    }

    /** 单指拖动：绕注视点旋转。dx/dy 为像素位移。 */
    fun orbit(dx: Float, dy: Float, viewHeight: Int) {
        val k = 3.6f / maxOf(viewHeight, 1)
        rotY -= dx * k
        rotX = (rotX - dy * k).coerceIn(-1.553f, 1.553f)
    }

    /** 双指拖动：平移注视点。 */
    fun pan(dx: Float, dy: Float, viewHeight: Int) {
        val unitsPerPixel = 2f * kotlin.math.abs(distance) * tan(fovDeg * DEG * 0.5f) / maxOf(viewHeight, 1)
        val mx = -dx * unitsPerPixel
        val my = dy * unitsPerPixel
        target[0] += right[0] * mx + up[0] * my
        target[1] += right[1] * mx + up[1] * my
        target[2] += right[2] * mx + up[2] * my
    }

    /** 双指捏合：factor > 1 拉近。 */
    fun zoom(factor: Float) {
        if (factor <= 0f || factor != factor) return
        distance = (distance / factor).coerceIn(0.5f, 5000f)
    }

    fun roll(radians: Float) { rotZ += radians }

    /** 屏幕坐标（像素，原点左上）→ 世界空间射线。 */
    fun screenRay(sx: Float, sy: Float, width: Int, height: Int, origin: FloatArray, dir: FloatArray) {
        val nx = sx / width * 2f - 1f
        val ny = 1f - sy / height * 2f
        Mat4.transformVec4(invViewProj, 0, nx, ny, -1f, 1f, t, 0)
        Mat4.transformVec4(invViewProj, 0, nx, ny, 1f, 1f, t, 4)
        val ax = t[0] / t[3]; val ay = t[1] / t[3]; val az = t[2] / t[3]
        val bx = t[4] / t[7]; val by = t[5] / t[7]; val bz = t[6] / t[7]
        origin[0] = ax; origin[1] = ay; origin[2] = az
        var dx = bx - ax; var dy = by - ay; var dz = bz - az
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        if (l > 1e-12f) { dx /= l; dy /= l; dz /= l }
        dir[0] = dx; dir[1] = dy; dir[2] = dz
    }

    /** 世界坐标 → 屏幕像素；返回 false 表示在相机后面。out = (x, y, 深度w)。 */
    fun project(x: Float, y: Float, z: Float, width: Int, height: Int, out: FloatArray): Boolean {
        Mat4.transformVec4(viewProj, 0, x, y, z, 1f, t, 8)
        val w = t[11]
        if (w <= 1e-6f) return false
        out[0] = (t[8] / w * 0.5f + 0.5f) * width
        out[1] = (0.5f - t[9] / w * 0.5f) * height
        out[2] = w
        return true
    }

    /** 在给定深度处，屏幕上 1 像素对应的世界长度。 */
    fun worldPerPixel(depthW: Float, viewHeight: Int): Float {
        return if (perspective) 2f * depthW * tan(fovDeg * DEG * 0.5f) / maxOf(viewHeight, 1)
        else 2f * maxOf(kotlin.math.abs(distance), 1f) * tan(fovDeg * DEG * 0.5f) / maxOf(viewHeight, 1)
    }

    /** 让相机正对并框住一个包围盒。 */
    fun frame(min: FloatArray, max: FloatArray) {
        target[0] = (min[0] + max[0]) * 0.5f
        target[1] = (min[1] + max[1]) * 0.5f
        target[2] = (min[2] + max[2]) * 0.5f
        val h = maxOf(max[1] - min[1], (max[0] - min[0]) / maxOf(aspect, 0.3f), 1f)
        distance = (h * 0.5f / tan(fovDeg * DEG * 0.5f) * 1.25f + (max[2] - min[2]) * 0.5f).coerceIn(2f, 4000f)
    }

    companion object {
        const val DEG = (Math.PI / 180.0).toFloat()
    }
}

class Light {
    /** 光线传播方向（MMD 默认 -0.5, -1.0, 0.5）。 */
    val direction = floatArrayOf(-0.5f, -1.0f, 0.5f)
    /** MMD 默认 154/255。 */
    val color = floatArrayOf(0.6f, 0.6f, 0.6f)

    fun reset() {
        direction[0] = -0.5f; direction[1] = -1f; direction[2] = 0.5f
        color.fill(0.6f)
    }

    /** 指向光源的单位向量。 */
    fun toLight(out: FloatArray) {
        var x = -direction[0]; var y = -direction[1]; var z = -direction[2]
        val l = sqrt(x * x + y * y + z * z)
        if (l < 1e-6f) { x = 0.5f; y = 1f; z = -0.5f } else { x /= l; y /= l; z /= l }
        out[0] = x; out[1] = y; out[2] = z
    }
}

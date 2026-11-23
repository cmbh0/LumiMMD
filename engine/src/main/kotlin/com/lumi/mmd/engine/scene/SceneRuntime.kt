package com.lumi.mmd.engine.scene

import com.lumi.mmd.engine.control.CharacterController
import com.lumi.mmd.engine.control.InputState
import com.lumi.mmd.engine.fx.Effects
import com.lumi.mmd.engine.render.LineBatch
import com.lumi.mmd.engine.render.RenderSettings
import com.lumi.mmd.engine.script.ScriptCallbacks
import com.lumi.mmd.engine.script.ScriptEngine
import com.lumi.mmd.engine.script.ScriptHost

/**
 * 场景"活起来"的那一部分：脚本、特效、操控模式，以及把它们和场景更新按正确顺序串起来的一帧。
 * 手机应用和桌面工具各有自己的渲染循环，但每一帧里做的事是同一套，就放在这里。只在渲染线程上用。
 */
class SceneRuntime(
    override val scene: Scene,
    override val settings: RenderSettings,
    callbacks: ScriptCallbacks,
) : ScriptHost, ScriptCallbacks by callbacks {
    override val effects = Effects()
    override val input = InputState()
    val scripts = ScriptEngine(this)

    /** 操控模式正在控制的角色（没有开时为 null）。 */
    var controller: CharacterController? = null
        private set
    /** 疾跑锁定（把摇杆推到最上面时锁住，不用一直按着）。 */
    @Volatile var sprintLock = false

    private val events = ArrayList<Pair<String, Boolean>>()
    private var wasPlaying = false

    /** 开始操控某个角色：接管它的动作和镜头。 */
    fun startControl(model: ModelInstance): CharacterController {
        stopControl()
        val c = CharacterController(model)
        c.attach()
        controller = c
        cameraBeforeControl.copyFrom(scene.camera)
        scene.cameraLocked = true
        c.placeCameraBehind(scene.camera)
        return c
    }

    /** 退出操控：角色回到原来的位置和动作，镜头回到进入之前的视角。 */
    fun stopControl() {
        val c = controller ?: return
        c.detach()
        controller = null
        scene.cameraLocked = false
        scene.camera.copyFrom(cameraBeforeControl)
        sprintLock = false
        input.moveX = 0f; input.moveY = 0f
    }

    private val cameraBeforeControl = com.lumi.mmd.engine.scene.Camera()

    /**
     * 推进一帧：按钮事件 → 操控 → 脚本 → 场景（动作、物理）→ 镜头跟随 → 特效。
     * 参数和返回值同 [Scene.update]。
     */
    fun update(dt: Float, audioSeconds: Float = Float.NaN, hold: Boolean = false): Boolean {
        input.drain(events)
        val c = controller
        if (c != null && !scene.models.contains(c.model)) stopControl()
        for ((name, down) in events) {
            if (down) controller?.press(name)
            scripts.button(name, down)
        }
        controller?.let { ctl ->
            ctl.moveX = input.moveX; ctl.moveY = input.moveY
            ctl.sprint = sprintLock || input.isDown("sprint")
            val cam = scene.camera
            groundUnder(ctl)
            ctl.step(dt, cam.forward[0], cam.forward[2])
            blockByColliders(ctl)
        }
        scripts.lineCount = 0
        if (scene.playing != wasPlaying) { wasPlaying = scene.playing; scripts.event(if (scene.playing) "play" else "pause") }
        scripts.update(dt)
        val ended = scene.update(dt, audioSeconds, hold)
        if (scene.looped) scripts.event("loop")
        controller?.followCamera(scene.camera, dt)
        effects.update(dt, scene)
        return ended
    }

    private val ctlTmp = FloatArray(6)

    /**
     * 操控的角色脚下的地面：地板，或者带「碰撞」属性的模型（能走上台阶、站在台子上）。
     * 只找「从膝盖往下」的：比膝盖高的是墙，不是地。什么都没有时留在原来的高度，不会一直掉下去。
     */
    private fun groundUnder(ctl: CharacterController) {
        val w = ctl.model.worldMatrix
        if (kotlin.math.abs(w[5]) < 1e-4f) return
        val s = kotlin.math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
        val wx = w[0] * ctl.x + w[4] * ctl.y + w[8] * ctl.z + w[12]
        val wy = w[1] * ctl.x + w[5] * ctl.y + w[9] * ctl.z + w[13]
        val wz = w[2] * ctl.x + w[6] * ctl.y + w[10] * ctl.z + w[14]
        val g = scene.groundHeight(wx, wy + STEP_UP * s, wz, ctl.model.id)
        if (!g.isNaN()) ctl.groundY = (g - w[13] - w[1] * ctl.x - w[9] * ctl.z) / w[5]
    }

    /** 带碰撞的模型挡住操控的角色：在腰的高度放一个球，被推开多少，角色就水平挪多少。 */
    private fun blockByColliders(ctl: CharacterController) {
        if (scene.colliders.isEmpty()) return
        val w = ctl.model.worldMatrix
        val s = kotlin.math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
        if (s < 1e-6f) return
        val y = ctl.y + WAIST
        val wx = w[0] * ctl.x + w[4] * y + w[8] * ctl.z + w[12]
        val wy = w[1] * ctl.x + w[5] * y + w[9] * ctl.z + w[13]
        val wz = w[2] * ctl.x + w[6] * y + w[10] * ctl.z + w[14]
        for (c in scene.colliders) {
            if (c.id == ctl.model.id) continue
            if (!c.contact(wx, wy, wz, BODY_RADIUS * s, ctlTmp)) continue
            if (kotlin.math.abs(ctlTmp[1]) > 0.7f) continue          // 朝上 / 朝下的面是地和顶，不是墙
            val dx = ctlTmp[0] * ctlTmp[3]; val dz = ctlTmp[2] * ctlTmp[3]
            ctl.x += (w[0] * dx + w[2] * dz) / (s * s)
            ctl.z += (w[8] * dx + w[10] * dz) / (s * s)
        }
    }

    /** 把脚本这一帧要画的线交给渲染器（每帧在 render 之前调用）。 */
    fun fillOverlay(batch: LineBatch) {
        val n = scripts.lineCount
        val l = scripts.lines
        for (i in 0 until n) {
            val o = i * 11
            batch.line(l[o], l[o + 1], l[o + 2], l[o + 3], l[o + 4], l[o + 5], l[o + 6], l[o + 7], l[o + 8], l[o + 9], l[o + 10], true)
        }
    }

    /** 清空场景时调用：停掉操控，清掉脚本和特效。 */
    fun reset() {
        stopControl()
        scripts.clear()
        effects.clear()
        input.reset()
    }

    companion object {
        /** 能直接迈上去的高度（模型单位）。 */
        private const val STEP_UP = 4f
        private const val WAIST = 9f
        private const val BODY_RADIUS = 2.2f
    }
}

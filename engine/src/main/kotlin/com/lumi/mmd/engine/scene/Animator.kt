package com.lumi.mmd.engine.scene

import com.lumi.mmd.engine.anim.MorphController
import com.lumi.mmd.engine.anim.Skeleton

/** 接管一个模型的动作（不再按时间轴上的片段取）：操控模式的走跑跳就是这样接进来的。 */
interface Animator {
    /** 把这一帧的姿势写进骨骼的动作层（animT / animR）。dt 是距上一帧的秒数。 */
    fun apply(model: ModelInstance, skeleton: Skeleton, morphs: MorphController, dt: Float)
}

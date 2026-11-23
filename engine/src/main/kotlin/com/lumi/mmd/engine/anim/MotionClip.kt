package com.lumi.mmd.engine.anim

import com.lumi.mmd.engine.model.ModelData
import java.util.concurrent.atomic.AtomicInteger

/**
 * 轨道上一个片段的摆放信息：从时间轴的第几帧开始、素材掐头去尾各多少。
 * 动作片段（[MotionClip]）和音乐片段（AudioClip）共用，移动 / 裁剪这些操作只认这个接口。
 */
interface TrackClip {
    val id: Int
    /** 片段开头在时间轴上的位置（帧）。 */
    var start: Int
    /** 从素材的第几帧开始用。 */
    var trimIn: Int
    /** 用到素材的第几帧为止；-1 = 一直到素材的结尾。 */
    var trimOut: Int
    /** 素材本身到第几帧为止。 */
    val sourceEnd: Int
    /** 结尾最多能拉到素材的第几帧（动作可以一直拉长，多出来的部分停在最后的姿势上；音乐不能超过文件的长度）。 */
    val maxOut: Int get() = Int.MAX_VALUE

    val outFrame: Int get() = if (trimOut >= 0) trimOut else maxOf(sourceEnd, trimIn)
    val length: Int get() = maxOf(outFrame - trimIn, 0)
    /** 片段结尾在时间轴上的位置（帧）。 */
    val end: Int get() = start + length
    /** 时间轴帧号 − offset = 素材里的帧号。 */
    val offset: Int get() = start - trimIn
}

/**
 * 时间轴上的一段动作：一份 [Motion] 加上它摆在哪（从第几帧开始、掐头去尾各多少）。
 * 和剪辑软件里的"片段"是一个意思：移动、裁剪、分割都只改这里的几个数，不动动作本身的关键帧，
 * 所以裁短了再拉长，原来的内容还在。
 */
class MotionClip(motion: Motion, var name: String) : TrackClip {
    override val id: Int = nextId.getAndIncrement()

    var motion: Motion = motion
        set(v) { field = v; binding = null }

    /**
     * 来源，保存工程时用：[身体动作的文件, 之后叠上去的表情文件…]，或 ["embedded:序号"]（模型自带的动画）。
     * 自己从零做的动作为空，保存时会写成工程里的一个 VMD。
     */
    var sources: List<String> = emptyList()

    /** 片段开头在时间轴上的位置（帧）。 */
    override var start = 0
    /** 从动作的第几帧开始用。 */
    override var trimIn = 0
    /** 用到动作的第几帧为止；-1 = 一直到动作的最后一个关键帧（继续往后打关键帧，片段会跟着变长）。 */
    override var trimOut = -1
    override val sourceEnd: Int get() = motion.maxFrame
    /**
     * 和前一段之间的过渡（帧）：这一段开头的这么多帧里，姿势从前一段平滑地变成这一段，而不是一下子切过去。
     * 0 = 直接切换。轨道上它前面没有别的片段时不起作用。
     */
    var fadeIn = 0
    /** 实际生效的过渡长度：不会比片段本身长。 */
    val fade: Int get() = fadeIn.coerceIn(0, length)
    /** 这份动作是不是这个片段独占的（可以直接改）。从文件读进来的动作可能被好几个片段共用，改之前要先复制。 */
    var owned = false

    private var binding: MotionBinding? = null
    private var boundModel: ModelData? = null

    fun binding(model: ModelData): MotionBinding {
        val b = binding
        if (b != null && boundModel === model) return b
        return MotionBinding(motion, model).also { binding = it; boundModel = model }
    }

    /** 动作里新增 / 删除了整条轨道之后调用。 */
    fun rebind() { binding = null }

    /** 时间轴上的 frame 对应动作里的第几帧（片段范围之外的停在头 / 尾那一帧）。 */
    fun localFrame(frame: Float): Float = (frame - offset).coerceIn(trimIn.toFloat(), outFrame.toFloat())

    /**
     * 后一段正在从这一段过渡过去时，这一段该取动作里的第几帧。
     * 两段首尾相接、而这一段后面还有被裁掉的内容时，接着往下播（真正的交叉过渡）；
     * 没有多余的内容，或者中间隔着空隙，就停在最后的姿势上。
     */
    fun fadeOutFrame(frame: Float, next: TrackClip): Float =
        if (next.start <= end) maxOf(frame - offset, trimIn.toFloat()) else outFrame.toFloat()

    fun contains(frame: Float): Boolean = frame >= start && frame <= end

    /** 摆放信息相同、共用同一份动作的另一个片段。 */
    fun placedCopy(): MotionClip {
        val c = MotionClip(motion, name)
        c.sources = sources; c.start = start; c.trimIn = trimIn; c.trimOut = trimOut; c.owned = false; c.fadeIn = fadeIn
        return c
    }

    companion object {
        private val nextId = AtomicInteger(1)
    }
}

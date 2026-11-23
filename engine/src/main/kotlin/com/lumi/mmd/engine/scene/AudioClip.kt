package com.lumi.mmd.engine.scene

import com.lumi.mmd.engine.anim.TrackClip
import java.util.concurrent.atomic.AtomicInteger

/**
 * 音乐轨道上的一段：一个声音文件加上它摆在哪。
 * 和动作片段一样可以挪动、裁头裁尾、切开、复制；同一个文件可以放好几段。
 * 时间一律按 30fps 的帧算（和时间轴一致）。
 */
class AudioClip(
    /** 声音文件的路径。 */
    val path: String,
    var name: String,
    /** 文件本身有多长（帧）。 */
    val sourceFrames: Int,
) : TrackClip {
    override val id: Int = nextId.getAndIncrement()
    override var start = 0
    override var trimIn = 0
    override var trimOut = -1
    override val sourceEnd: Int get() = sourceFrames
    override val maxOut: Int get() = sourceFrames

    /** 时间轴的 frame 落在这一段里面。 */
    fun contains(frame: Float): Boolean = frame >= start && frame < end

    /** 时间轴的 seconds 对应文件里的第几秒。 */
    fun sourceSeconds(timelineSeconds: Float): Float = timelineSeconds - offset / FPS

    fun copy(): AudioClip {
        val c = AudioClip(path, name, sourceFrames)
        c.start = start; c.trimIn = trimIn; c.trimOut = trimOut
        return c
    }

    companion object {
        private const val FPS = 30f
        private val nextId = AtomicInteger(1)
    }
}

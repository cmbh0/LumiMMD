package com.lumi.mmd.engine.scene

import kotlin.math.abs
import kotlin.math.exp

/**
 * 画面时间和音乐对齐用的"软同步"时钟。
 *
 * 画面的时间始终按"真实经过的时间"一帧一帧平滑地往前走，从不直接取音乐播放器报出来的位置
 * （播放器报的位置有延迟、有抖动，直接拿来用画面就会一卡一卡地来回跳）。
 * 音乐的位置只用来算"画面比音乐快了还是慢了多少"，这个偏差先做平滑，然后用最多百分之几的变速慢慢消掉——
 * 人眼看不出动作快了 5%，但一次 50 毫秒的跳变非常明显。
 * 只有偏差真的很大并且持续存在（比如应用被卡住了半秒）时，才让画面一步跳到音乐的位置。
 */
class SyncClock {
    /** 平滑后的偏差（秒）：音乐位置 − 画面时间。正数 = 画面落后。 */
    var error = 0f
        private set
    /** 最近一次 [advance] 是否要求画面直接跳到音乐的位置。 */
    var snapped = false
        private set

    private var primed = false
    private var outliers = 0

    fun reset() {
        primed = false; error = 0f; outliers = 0; snapped = false
    }

    /**
     * @param nominal 这一帧按内部时钟该走多少秒（真实经过的时间 × 播放速度）
     * @param dt 真实经过的秒数
     * @param sceneTime 画面当前的时间（秒）
     * @param audioTime 音乐此刻的位置（秒）；NaN = 现在没有可用的音乐时钟（没有音乐、音乐放完了、读不到位置）
     * @return 画面这一帧实际要走的秒数。[snapped] 为 true 时请改为直接跳到 audioTime。
     */
    fun advance(nominal: Float, dt: Float, sceneTime: Float, audioTime: Float): Float {
        snapped = false
        if (audioTime.isNaN()) { reset(); return maxOf(nominal, 0f) }
        if (nominal <= 0f || dt <= 0f) return 0f
        val raw = audioTime - sceneTime
        if (!primed) { primed = true; error = raw; outliers = 0 }
        if (abs(raw - error) > OUTLIER) {
            // 孤零零一次离谱的读数不理它；连着好几次都这样，说明是真的走散了
            if (++outliers >= OUTLIER_FRAMES) { error = raw; outliers = 0 }
        } else {
            outliers = 0
            error += (raw - error) * (1f - exp(-dt / SMOOTH_SECONDS))
        }
        if (abs(error) > SNAP_SECONDS) {
            reset()
            snapped = true
            return 0f
        }
        var corr = 0f
        if (abs(error) > DEAD_SECONDS) {
            val limit = MAX_RATE * nominal
            corr = (error * GAIN * dt).coerceIn(-limit, limit)
        }
        error -= corr
        return nominal + corr
    }

    companion object {
        /** 偏差的平滑时间常数。 */
        const val SMOOTH_SECONDS = 0.30f
        /** 小于这个偏差不去追（听不出也看不出，追反而会把读数的抖动带进画面）。 */
        const val DEAD_SECONDS = 0.004f
        /** 每秒消掉偏差的比例。 */
        const val GAIN = 2.0f
        /** 最多变速多少（0.06 = 快或慢 6%）。 */
        const val MAX_RATE = 0.06f
        /** 一次读数和平滑值差这么多就当成离群点。 */
        const val OUTLIER = 0.12f
        const val OUTLIER_FRAMES = 6
        /** 平滑后的偏差超过这么多：直接跳过去。 */
        const val SNAP_SECONDS = 0.30f
    }
}

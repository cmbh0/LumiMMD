package com.lumi.mmd.core

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.lumi.mmd.engine.util.Log
import java.io.File

/**
 * 一个放音乐的播放器（音乐轨道用两个轮流放，见引擎里的 AudioDeck）。
 *
 * 音乐按它自己的速度放，画面去对它（见引擎里的 SyncClock）：这里只负责"问到音乐现在放到哪了"。
 * MediaPlayer 报的位置有延迟、有抖动，按下播放后也要过一小会儿才真正出声，所以：
 *  - 位置由一个后台线程隔几十毫秒读一次，连同读它的时刻一起存下来；画面线程用的时候按系统时钟外推，
 *    渲染循环里不做任何可能卡住的跨进程调用；
 *  - 刚按下播放（或者刚跳转）还没出声的那一小会儿，[position] 返回 [HOLD]，画面等它，
 *    这样画面和声音是同时起步的，而不是画面先走、之后再去追。
 *
 * 位置本身不做任何"校正"：读到多少就是多少，平滑是 SyncClock 的事。
 */
class AudioPlayer : com.lumi.mmd.engine.scene.AudioVoice {
    private var player: MediaPlayer? = null
    @Volatile private var prepared = false
    @Volatile var file: File? = null
        private set
    @Volatile override var durationSeconds = 0f
        private set
    private val lock = Any()
    /** 每次装载 / 释放加一：后台装了一半时如果已经被释放或者改装别的了，那一份就丢掉。 */
    @Volatile private var loadSeq = 0

    override val path: String? get() = file?.path
    override fun load(path: String): Boolean = load(File(path))

    // ---- 播放状态（任意线程读）----
    @Volatile private var state = IDLE
    @Volatile private var speed = 1f
    /** 最近一次读到的位置（毫秒）和读它的时刻（System.nanoTime）。 */
    @Volatile private var anchorMs = 0L
    @Volatile private var anchorNanos = 0L
    /** 按下播放时要求的起点（毫秒）和时刻。 */
    @Volatile private var startMs = 0L
    @Volatile private var startNanos = 0L
    private var sampler: Thread? = null
    private var generation = 0

    val hasAudio: Boolean get() = prepared
    val isPlaying: Boolean get() = state == STARTING || state == PLAYING

    /**
     * 装载一个文件。解码器的准备（prepare）可能要几百毫秒，放在锁外面做：
     * 这期间渲染线程调 [pause] / [position] 不会被卡住。
     */
    fun load(f: File): Boolean {
        val seq = synchronized(lock) { release(); ++loadSeq }
        var p: MediaPlayer? = null
        val ok = try {
            p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(f.path)
            p.prepare()
            p.duration > 0
        } catch (e: Throwable) {
            Log.w("Audio", "无法播放 ${f.name}: $e")
            false
        }
        synchronized(lock) {
            if (!ok || seq != loadSeq) {
                try { p?.release() } catch (_: Throwable) {}
                return false
            }
            val mp = p!!
            mp.setOnCompletionListener { if (player === mp) state = ENDED }
            player = mp
            prepared = true
            file = f
            durationSeconds = mp.duration / 1000f
            state = IDLE
            return true
        }
    }

    override fun release() = synchronized(lock) {
        loadSeq++
        generation++
        state = IDLE
        try { player?.release() } catch (_: Exception) {}
        player = null; prepared = false; file = null; durationSeconds = 0f
        sampler = null
    }

    /**
     * 从 seconds 处开始播放。超出音乐长度则不播放（返回 false，画面按自己的时钟走）。
     * 跳转是异步的：跳到位之后才开始出声，这期间 [position] 返回 [HOLD]。
     */
    override fun play(seconds: Float, speed: Float): Boolean = synchronized(lock) {
        val p = player ?: return false
        if (!prepared || seconds >= durationSeconds - 0.05f) { state = ENDED; return false }
        return try {
            val gen = ++generation
            this.speed = speed
            val ms = (seconds * 1000f).toLong().coerceAtLeast(0L)
            startMs = ms
            startNanos = System.nanoTime()
            state = STARTING
            val begin = Runnable {
                synchronized(lock) {
                    if (gen != generation || player !== p) return@Runnable
                    try {
                        try { p.playbackParams = p.playbackParams.setSpeed(speed) } catch (_: Exception) {}
                        p.start()
                    } catch (e: Exception) { state = IDLE }
                }
            }
            val here = try { p.currentPosition.toLong() } catch (_: Exception) { -1L }
            if (kotlin.math.abs(here - ms) <= 30) {
                // 已经停在要播的位置上（暂停后原地继续）：不用跳转，直接播
                begin.run()
            } else {
                p.setOnSeekCompleteListener { begin.run() }
                if (android.os.Build.VERSION.SDK_INT >= 26) p.seekTo(ms, MediaPlayer.SEEK_CLOSEST) else p.seekTo(ms.toInt())
            }
            startSampler(gen, p)
            true
        } catch (e: Exception) { state = IDLE; false }
    }

    override fun pause() = synchronized(lock) {
        generation++
        state = IDLE
        try { player?.takeIf { it.isPlaying }?.pause() } catch (_: Exception) {}
        sampler = null
    }

    /** 暂停着的时候把音乐也挪到这个位置，下次按播放就不用再等跳转。 */
    override fun cue(seconds: Float): Unit = synchronized(lock) {
        val p = player ?: return
        if (!prepared || isPlaying) return
        try {
            val ms = (seconds * 1000f).toLong().coerceIn(0L, (durationSeconds * 1000f).toLong())
            p.setOnSeekCompleteListener(null)
            if (android.os.Build.VERSION.SDK_INT >= 26) p.seekTo(ms, MediaPlayer.SEEK_CLOSEST) else p.seekTo(ms.toInt())
        } catch (_: Exception) {}
    }

    private fun startSampler(gen: Int, p: MediaPlayer) {
        val t = Thread({
            while (true) {
                val now: Long
                val pos: Long
                synchronized(lock) {
                    if (gen != generation || player !== p) return@Thread
                    pos = try { if (p.isPlaying) p.currentPosition.toLong() else -1L } catch (_: Exception) { -1L }
                    now = System.nanoTime()
                }
                if (pos >= 0) {
                    if (state == STARTING) {
                        // 位置开始往前走了，才算真的出声了
                        if (pos > startMs + 5 || now - startNanos > START_TIMEOUT_NANOS) { anchorMs = pos; anchorNanos = now; state = PLAYING }
                    } else if (state == PLAYING) { anchorMs = pos; anchorNanos = now }
                } else if (state == STARTING && now - startNanos > START_TIMEOUT_NANOS * 3) {
                    // 一直没能开始播放：别让画面干等
                    state = IDLE
                    return@Thread
                }
                try { Thread.sleep(if (state == STARTING) 8L else 40L) } catch (_: InterruptedException) { return@Thread }
            }
        }, "lumi-audio-clock")
        t.isDaemon = true
        sampler = t
        t.start()
    }

    /**
     * 音乐此刻放到哪了（秒）。
     * @return 位置；NaN = 没有可用的音乐时钟（没在播、放完了、读不到）；[HOLD] = 已经按了播放但还没出声，画面请等一下
     */
    override fun position(nowNanos: Long): Float {
        return when (state) {
            STARTING -> HOLD
            PLAYING -> {
                val age = nowNanos - anchorNanos
                if (age > STALE_NANOS) return Float.NaN
                val pos = (anchorMs + age / 1e6 * speed) / 1000.0
                if (pos >= durationSeconds) Float.NaN else pos.toFloat()
            }
            else -> Float.NaN
        }
    }

    companion object {
        /** [position] 的特殊返回值：画面先别走。 */
        const val HOLD = com.lumi.mmd.engine.scene.AudioDeck.HOLD
        private const val IDLE = 0
        private const val STARTING = 1
        private const val PLAYING = 2
        private const val ENDED = 3
        /** 等音乐出声最多等这么久，超过就当它已经开始了。 */
        private const val START_TIMEOUT_NANOS = 350_000_000L
        /** 位置这么久没更新过就不再相信它。 */
        private const val STALE_NANOS = 500_000_000L
    }
}

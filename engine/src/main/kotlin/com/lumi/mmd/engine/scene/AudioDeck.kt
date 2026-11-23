package com.lumi.mmd.engine.scene

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/** 一个能放一个声音文件的播放器（应用里是包了一层的 MediaPlayer；测试里是假的）。 */
interface AudioVoice {
    /** 现在装着哪个文件（没有则为 null）。 */
    val path: String?
    val durationSeconds: Float
    /** 装载一个文件。会卡一会儿，所以只在后台线程调用。 */
    fun load(path: String): Boolean
    /** 从文件的第 seconds 秒开始放。已经停在这个位置上时马上出声，否则要先跳转。 */
    fun play(seconds: Float, speed: Float): Boolean
    fun pause()
    /** 没在放的时候先挪到这个位置，之后从这里开始放就不用等跳转。 */
    fun cue(seconds: Float)
    /** 此刻放到文件的第几秒；NaN = 没在放 / 放完了；[AudioDeck.HOLD] = 已经按了播放但还没出声。任意线程。 */
    fun position(nowNanos: Long): Float
    fun release()
}

/**
 * 音乐轨道的播放：把时间轴上的若干段音乐（[AudioClip]）交给两个播放器轮流放。
 *
 * 正在响的那一段用一个播放器；另一个提前装好下一段的文件、挪到下一段的起点等着，
 * 时间线一走到那里就换过去，不用等跳转，所以两段之间不会卡一下。
 * 同一个文件切开的两段如果还挨在一起（只是切了一刀没挪动），就一直放下去，中间什么都不做，听起来和没切一样。
 * 两段之间的空隙里没有声音，画面按自己的时钟走。
 *
 * 除了装载文件在后台线程，其它方法都只在渲染线程调用。
 */
class AudioDeck(a: AudioVoice, b: AudioVoice, private val background: (() -> Unit) -> Unit) {
    private class Slot(val voice: AudioVoice) {
        /** 已经预先挪到了哪一段的哪个位置（文件里的秒）。 */
        var cuedClip: AudioClip? = null
        var cued = -1f
        /** 正在后台装载文件：这期间渲染线程不碰这个播放器。 */
        @Volatile var loading = false
        /** 这个文件装不进来（放不了）：别每一帧都重试。 */
        @Volatile var failed: String? = null
    }

    private var active = Slot(a)
    private var standby = Slot(b)
    /** 正在响的那一段，以及开始放它时它的 offset（片段被挪动 / 裁剪后会变，变了要重新对位置）。 */
    private var playingClip: AudioClip? = null
    private var playingOffset = 0
    private var speed = 1f
    /** 刚按下播放：这一段出声之前让画面等一下（有时限）。播放途中走进别的片段时不等。 */
    private var holdClip: AudioClip? = null
    private var holdUntil = 0L

    /** 现在有没有哪一段正在响。 */
    val sounding: Boolean get() = playingClip != null

    /** 正在响的那一段（给界面 / 测试看）。 */
    val current: AudioClip? get() = playingClip

    private fun swap() { val t = active; active = standby; standby = t }

    private fun clipAt(clips: List<AudioClip>, frame: Float): AudioClip? {
        for (c in clips) if (c.contains(frame)) return c
        return null
    }

    private fun nextAfter(clips: List<AudioClip>, frame: Float, not: AudioClip?): AudioClip? {
        var best: AudioClip? = null
        for (c in clips) if (c !== not && c.start > frame && (best == null || c.start < best.start)) best = c
        return best
    }

    /** next 是不是 prev 原样接下去（同一个文件、位置连着）：是的话不用换播放器，接着放就行。 */
    private fun continues(prev: AudioClip, prevOffset: Int, next: AudioClip): Boolean = next.path == prev.path && next.offset == prevOffset

    /** 让 slot 装好 c 的文件。返回 true = 已经装好了。 */
    private fun loaded(slot: Slot, c: AudioClip): Boolean {
        if (slot.loading) return false
        if (slot.voice.path == c.path) return true
        if (slot.failed == c.path) return false
        slot.loading = true
        slot.cuedClip = null; slot.cued = -1f
        val v = slot.voice
        val path = c.path
        background {
            val ok = try { v.load(path) } catch (_: Throwable) { false }
            slot.failed = if (ok) null else path
            slot.loading = false
        }
        return !slot.loading && slot.voice.path == c.path
    }

    private fun stopActive() {
        if (playingClip != null && !active.loading) active.voice.pause()
        playingClip = null
    }

    /**
     * 让 c 从时间轴的 frame 处响起来。
     * @param initial 用户刚按下播放 / 跳转：位置要准，宁可让画面等一下；否则是播放途中走进了这一段：不能让画面等
     */
    private fun enter(c: AudioClip, frame: Float, initial: Boolean) {
        val want = c.sourceSeconds(frame / FPS)
        val useStandby = when {
            // 备用的播放器已经为这一段挪好了位置
            standby.cuedClip === c && !standby.loading && standby.voice.path == c.path -> true
            active.voice.path == c.path && !active.loading -> false
            standby.voice.path == c.path && !standby.loading -> true
            // 两个都没装这个文件：用空着的那个去装
            else -> active.loading && !standby.loading
        }
        if (useStandby) { stopActive(); swap() }
        else if (playingClip != null && active.voice.path != c.path) stopActive()
        if (!loaded(active, c)) { playingClip = null; return }
        val cuedHere = active.cuedClip === c && active.cued >= 0f && abs(want - active.cued) < CUE_SLACK
        val at = when {
            initial -> want
            // 已经挪好了位置：就从那里放（差的那一两帧由画面慢慢对上），这样不用跳转、马上出声
            cuedHere -> active.cued
            // 没来得及准备：跳转要花一点时间，把这点时间先算进去
            else -> want + SEEK_LEAD
        }
        active.voice.play(at, speed)
        active.cuedClip = null; active.cued = -1f
        playingClip = c
        playingOffset = c.offset
    }

    /** 把 slot 预先挪到 c 的第 at 秒（已经在那里就不动）。 */
    private fun cue(slot: Slot, c: AudioClip, at: Float) {
        if (!loaded(slot, c)) return
        if (slot.cuedClip === c && abs(slot.cued - at) < 0.005f) return
        slot.voice.cue(at)
        slot.cuedClip = c; slot.cued = at
    }

    /** 给下一段做准备：装文件、挪到它的起点。cur = 正在响的那一段（没有则为 null）。 */
    private fun preroll(clips: List<AudioClip>, frame: Float, cur: AudioClip?) {
        val n = nextAfter(clips, frame, cur) ?: return
        if (cur != null && continues(cur, cur.offset, n)) return
        // 现在没有声音时两个播放器都闲着：哪个已经装着这个文件就用哪个
        val slot = if (cur == null && !active.loading && active.voice.path == n.path) active else standby
        cue(slot, n, n.trimIn / FPS)
    }

    /**
     * 开始播放、跳转、变速、片段被改动之后调用：让音乐从时间轴的 frame 处重新对上。
     * 音乐真正出声之前 [update] 返回 [HOLD]（最多等 [HOLD_NANOS]），画面和声音一起起步。
     */
    fun start(clips: List<AudioClip>, frame: Float, speed: Float, nowNanos: Long) {
        this.speed = speed
        active.failed = null; standby.failed = null
        val c = clipAt(clips, frame)
        holdClip = c
        holdUntil = nowNanos + HOLD_NANOS
        if (c == null) stopActive() else enter(c, frame, initial = true)
    }

    fun pause() {
        stopActive()
        holdClip = null
    }

    /**
     * 时间轴在播放时每一帧调用。
     * @return 音乐此刻对应时间轴的第几秒；NaN = 这会儿没有音乐（空隙、放完了）；[HOLD] = 刚按下播放、音乐还没出声，画面请等一下
     */
    fun update(clips: List<AudioClip>, frame: Float, nowNanos: Long): Float {
        val c = clipAt(clips, frame)
        val pc = playingClip
        if (holdClip != null && (c !== holdClip || nowNanos >= holdUntil)) holdClip = null
        val holding = holdClip != null
        when {
            c == null -> stopActive()
            c === pc && c.offset == playingOffset -> {}
            pc != null && continues(pc, playingOffset, c) -> playingClip = c
            // 刚按下播放时文件还没装好：画面在等，装好了照样从准确的位置开始
            else -> enter(c, frame, initial = holding)
        }
        preroll(clips, frame, playingClip)
        if (playingClip == null) {
            // 文件放不了（装载失败）就别让画面干等
            val dead = c != null && (active.failed == c.path || standby.failed == c.path)
            return if (holding && !dead) HOLD else Float.NaN
        }
        val p = active.voice.position(nowNanos)
        if (p == HOLD) return if (holding) HOLD else Float.NaN
        if (p.isNaN()) return Float.NaN
        holdClip = null
        return p + playingOffset / FPS
    }

    /**
     * 暂停着的时候调用：把播放器准备到 frame 处（装文件、挪位置），按下播放就能马上出声。
     * 顺便放掉已经没有任何片段在用的文件。
     */
    fun prepare(clips: List<AudioClip>, frame: Float) {
        if (playingClip != null) return
        for (s in arrayOf(active, standby)) {
            val p = s.voice.path
            if (!s.loading && p != null && clips.none { it.path == p }) { s.voice.release(); s.cuedClip = null; s.cued = -1f }
        }
        val c = clipAt(clips, frame) ?: nextAfter(clips, frame, null) ?: return
        val at = if (c.contains(frame)) c.sourceSeconds(frame / FPS) else c.trimIn / FPS
        if (active.voice.path != c.path && standby.voice.path == c.path && !standby.loading) swap()
        cue(active, c, at)
    }

    fun release() {
        playingClip = null
        holdClip = null
        for (s in arrayOf(active, standby)) { s.cuedClip = null; s.cued = -1f; s.failed = null; try { s.voice.release() } catch (_: Throwable) {} }
    }

    companion object {
        /** [update] 的特殊返回值：画面先别走。 */
        const val HOLD = -1e9f
        private const val FPS = 30f
        /** 预先挪好的位置和实际要放的位置差在这么多以内，就直接从挪好的位置放。 */
        const val CUE_SLACK = 0.12f
        /** 播放途中临时跳转时，预估跳转要花的时间。 */
        const val SEEK_LEAD = 0.1f
        /** 刚按下播放时，画面最多等音乐这么久。 */
        const val HOLD_NANOS = 1_200_000_000L

        /**
         * WAV 文件有多长（秒）。不是 WAV、或者读不懂时返回 null（其它格式由宿主用系统的解码器去问）。
         */
        fun wavSeconds(file: File): Float? = try {
            RandomAccessFile(file, "r").use { f ->
                val head = ByteArray(12)
                if (f.read(head) != 12 || String(head, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(head, 8, 4, Charsets.ISO_8859_1) != "WAVE") return@use null
                var byteRate = 0L
                val h = ByteArray(8)
                while (f.read(h) == 8) {
                    val id = String(h, 0, 4, Charsets.ISO_8859_1)
                    val size = (h[4].toLong() and 0xff) or ((h[5].toLong() and 0xff) shl 8) or ((h[6].toLong() and 0xff) shl 16) or ((h[7].toLong() and 0xff) shl 24)
                    if (id == "fmt ") {
                        val fmt = ByteArray(16)
                        if (f.read(fmt) != 16) return@use null
                        byteRate = (fmt[8].toLong() and 0xff) or ((fmt[9].toLong() and 0xff) shl 8) or ((fmt[10].toLong() and 0xff) shl 16) or ((fmt[11].toLong() and 0xff) shl 24)
                        f.seek(f.filePointer + (size - 16) + (size and 1))
                    } else if (id == "data") {
                        if (byteRate <= 0) return@use null
                        val left = f.length() - f.filePointer
                        val n = if (size <= 0L || size > left) left else size
                        return@use n / byteRate.toFloat()
                    } else f.seek(f.filePointer + size + (size and 1))
                }
                null
            }
        } catch (_: Exception) { null }
    }
}

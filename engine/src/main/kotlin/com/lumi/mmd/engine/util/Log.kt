package com.lumi.mmd.engine.util

/**
 * 平台无关的日志出口。引擎模块不直接依赖 android.util.Log，
 * App 启动时把 [sink] 换成 Logcat 实现即可；桌面无头工具默认打到 stderr。
 */
object Log {
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    fun interface Sink {
        fun log(level: Int, tag: String, msg: String, t: Throwable?)
    }

    @Volatile
    @JvmStatic
    var sink: Sink = Sink { level, tag, msg, t ->
        val l = when (level) { DEBUG -> "D"; INFO -> "I"; WARN -> "W"; else -> "E" }
        System.err.println("$l/$tag: $msg")
        t?.printStackTrace()
    }

    @Volatile
    @JvmStatic
    var minLevel: Int = INFO

    @JvmStatic fun d(tag: String, msg: String) { if (minLevel <= DEBUG) sink.log(DEBUG, tag, msg, null) }
    @JvmStatic fun i(tag: String, msg: String) { if (minLevel <= INFO) sink.log(INFO, tag, msg, null) }
    @JvmStatic fun w(tag: String, msg: String, t: Throwable? = null) { if (minLevel <= WARN) sink.log(WARN, tag, msg, t) }
    @JvmStatic fun e(tag: String, msg: String, t: Throwable? = null) { sink.log(ERROR, tag, msg, t) }
}

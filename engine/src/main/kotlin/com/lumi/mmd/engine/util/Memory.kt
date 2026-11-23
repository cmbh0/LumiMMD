package com.lumi.mmd.engine.util

/**
 * 堆内存的余量。
 *
 * 安卓给每个应用的堆有上限（常见的是 256 ~ 512 MB）。真把它用光的话，出事的不只是正在干活的那个线程——
 * 别的线程（系统的消息循环、渲染线程）连几十个字节都要不到，整个应用直接被杀掉，来不及提示。
 * 所以读大文件之前、读的过程中都先量一量：不够就停下来说清楚，而不是硬撑到撑不住。
 */
object Memory {
    /** 留给应用其它部分（界面、渲染、贴图解码）的余量。 */
    const val RESERVE = 72L * 1024 * 1024

    /** 堆的上限。 */
    fun max(): Long = Runtime.getRuntime().maxMemory()

    /** 还能再用多少字节。还没回收的垃圾也算作"已用"，所以这个数偏保守。 */
    fun available(): Long {
        val rt = Runtime.getRuntime()
        return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
    }

    /**
     * 还放得下 [bytes] 吗（在留出 [reserve] 之后）。量出来不够时先让垃圾回收跑一遍再量一次，
     * 免得因为一堆还没回收的垃圾误判。
     */
    fun fits(bytes: Long, reserve: Long = RESERVE): Boolean {
        if (available() - reserve >= bytes) return true
        System.gc()
        return available() - reserve >= bytes
    }

    fun mb(bytes: Long): Long = (bytes + 512 * 1024) / (1024 * 1024)
}

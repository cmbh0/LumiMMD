package com.lumi.mmd.engine.util

/** 可增长的 float 数组，避免装箱（导入器构建顶点流时用）。 */
class FloatList(capacity: Int = 256) {
    var data = FloatArray(maxOf(capacity, 16))
        private set
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > data.size) {
            var n = data.size * 2
            while (n < size + extra) n *= 2
            data = data.copyOf(n)
        }
    }

    fun add(v: Float) { ensure(1); data[size++] = v }
    fun add(a: Float, b: Float) { ensure(2); data[size++] = a; data[size++] = b }
    fun add(a: Float, b: Float, c: Float) { ensure(3); data[size++] = a; data[size++] = b; data[size++] = c }
    fun add(a: Float, b: Float, c: Float, d: Float) { ensure(4); data[size++] = a; data[size++] = b; data[size++] = c; data[size++] = d }
    fun addAll(src: FloatArray, offset: Int = 0, count: Int = src.size) {
        ensure(count); System.arraycopy(src, offset, data, size, count); size += count
    }
    operator fun get(i: Int): Float = data[i]
    operator fun set(i: Int, v: Float) { data[i] = v }
    fun clear() { size = 0 }
    fun toArray(): FloatArray = data.copyOf(size)
    /** 把内容交出去并清空自己：正好装满时不用再复制一份；交出去之后这个表里不再占着内存。 */
    fun detach(): FloatArray {
        val out = if (size == data.size) data else data.copyOf(size)
        data = FloatArray(16); size = 0
        return out
    }
    /** 预先留出还能再放 [extra] 个的位置。不够时只多长四分之一（而不是翻倍），大数组不会白占一倍内存。 */
    fun reserve(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(size + extra, data.size + data.size / 4))
    }
}

class IntList(capacity: Int = 256) {
    var data = IntArray(maxOf(capacity, 16))
        private set
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > data.size) {
            var n = data.size * 2
            while (n < size + extra) n *= 2
            data = data.copyOf(n)
        }
    }

    fun add(v: Int) { ensure(1); data[size++] = v }
    fun add(a: Int, b: Int, c: Int) { ensure(3); data[size++] = a; data[size++] = b; data[size++] = c }
    fun addAll(src: IntArray, offset: Int = 0, count: Int = src.size) {
        ensure(count); System.arraycopy(src, offset, data, size, count); size += count
    }
    operator fun get(i: Int): Int = data[i]
    operator fun set(i: Int, v: Int) { data[i] = v }
    fun clear() { size = 0 }
    fun toArray(): IntArray = data.copyOf(size)
    fun detach(): IntArray {
        val out = if (size == data.size) data else data.copyOf(size)
        data = IntArray(16); size = 0
        return out
    }
    fun reserve(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(size + extra, data.size + data.size / 4))
    }
}

/** 开放寻址的 Long → Int 哈希表（导入器给顶点去重用，避免装箱；不支持删除）。 */
class LongIntMap(capacity: Int = 1024) {
    private var keys = LongArray(tableSize(capacity))
    private var values = IntArray(keys.size)
    private var used = BooleanArray(keys.size)
    var size = 0
        private set

    private fun tableSize(n: Int): Int {
        var s = 16
        while (s < n * 2) s = s shl 1
        return s
    }

    private fun slot(key: Long, mask: Int): Int {
        var h = key * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        return h.toInt() and mask
    }

    /** 没有该键时返回 -1。 */
    fun get(key: Long): Int {
        val mask = keys.size - 1
        var i = slot(key, mask)
        while (used[i]) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and mask
        }
        return -1
    }

    fun put(key: Long, value: Int) {
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var i = slot(key, mask)
        while (used[i]) {
            if (keys[i] == key) { values[i] = value; return }
            i = (i + 1) and mask
        }
        used[i] = true; keys[i] = key; values[i] = value
        size++
    }

    private fun grow() {
        val ok = keys; val ov = values; val ou = used
        keys = LongArray(ok.size * 2); values = IntArray(keys.size); used = BooleanArray(keys.size)
        size = 0
        for (i in ok.indices) if (ou[i]) put(ok[i], ov[i])
    }
}

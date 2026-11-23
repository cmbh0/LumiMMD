package com.lumi.mmd.engine.control

/**
 * 屏幕上的摇杆和按钮此刻的状态。界面线程写，渲染线程读。
 * 操控模式和脚本（input.*）读的是同一份。
 */
class InputState {
    /** 摇杆：x 向右为正，y 向前（屏幕上方）为正，长度不超过 1。 */
    @Volatile var moveX = 0f
    @Volatile var moveY = 0f

    private val down = HashSet<String>()
    private val events = ArrayList<Pair<String, Boolean>>()

    /** 按钮按下 / 松开（界面线程）。 */
    fun button(name: String, isDown: Boolean) {
        synchronized(down) {
            val changed = if (isDown) down.add(name) else down.remove(name)
            if (changed) events.add(name to isDown)
        }
    }

    fun isDown(name: String): Boolean = synchronized(down) { name in down }

    /** 取走上一帧以来的按钮事件（渲染线程，每帧一次）。 */
    fun drain(out: ArrayList<Pair<String, Boolean>>) {
        out.clear()
        synchronized(down) {
            if (events.isNotEmpty()) { out.addAll(events); events.clear() }
        }
    }

    fun reset() {
        moveX = 0f; moveY = 0f
        synchronized(down) { down.clear(); events.clear() }
    }
}

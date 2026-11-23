package com.lumi.mmd.engine.scene

/**
 * 默认的地板：高度 [height] 处一个无限大的水平面。它负责三件事——画网格、接影子、挡住会掉下来的东西
 *（头发裙子的刚体、脚本的质点和刚体、操控模式里的角色）。
 *
 * [enabled] = false 就是「没有地板」：三件事都不做，东西会一直掉下去。删掉以后随时可以用 [reset] 加回一块默认的。
 * 跟着工程一起存盘。只在渲染线程上读写。
 */
class Floor {
    var enabled = true
    /** 地板的高度（世界坐标 y）。 */
    var height = 0f
    /** 画不画网格线。 */
    var grid = true
    /** 接不接影子。 */
    var shadow = true
    /** 挡不挡东西。 */
    var collide = true
    /** 网格一小格的边长（每 5 小格一条粗线）。 */
    var cell = 5f
    /** 实心的底色；a = 0 就是透明的（只有网格线和影子）。 */
    val color = floatArrayOf(0.55f, 0.56f, 0.62f, 0f)
    val gridColor = floatArrayOf(0.62f, 0.66f, 0.74f, 0.55f)

    /** 挡东西的那个面现在在不在。 */
    val solid: Boolean get() = enabled && collide

    /** 变回一块默认的地板（「添加默认地板」）。 */
    fun reset() { copyFrom(DEFAULT) }

    fun copyFrom(o: Floor) {
        enabled = o.enabled; height = o.height; grid = o.grid; shadow = o.shadow; collide = o.collide; cell = o.cell
        System.arraycopy(o.color, 0, color, 0, 4); System.arraycopy(o.gridColor, 0, gridColor, 0, 4)
    }

    val isDefault: Boolean get() = enabled && height == 0f && grid && shadow && collide && cell == 5f &&
        color.contentEquals(DEFAULT.color) && gridColor.contentEquals(DEFAULT.gridColor)

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "enabled" to enabled, "height" to height, "grid" to grid, "shadow" to shadow, "collide" to collide, "cell" to cell,
        "color" to color.toList(), "gridColor" to gridColor.toList(),
    )

    fun fromMap(o: Map<String, Any?>) {
        reset()
        (o["enabled"] as? Boolean)?.let { enabled = it }
        (o["height"] as? Number)?.let { height = it.toFloat() }
        (o["grid"] as? Boolean)?.let { grid = it }
        (o["shadow"] as? Boolean)?.let { shadow = it }
        (o["collide"] as? Boolean)?.let { collide = it }
        (o["cell"] as? Number)?.let { cell = it.toFloat().coerceIn(0.1f, 1000f) }
        (o["color"] as? List<*>)?.let { l -> for (i in 0 until minOf(4, l.size)) color[i] = (l[i] as? Number)?.toFloat() ?: color[i] }
        (o["gridColor"] as? List<*>)?.let { l -> for (i in 0 until minOf(4, l.size)) gridColor[i] = (l[i] as? Number)?.toFloat() ?: gridColor[i] }
    }

    companion object {
        private val DEFAULT = Floor()
    }
}

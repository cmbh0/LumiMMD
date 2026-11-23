package com.lumi.mmd.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 应用自己的一套线条图标（24×24 网格，圆头描边）。
 *
 * 三维编辑器要用到的图标（移动 / 旋转 / 骨骼 / 关键帧 / 挤出 / 环切……）Material 的基础图标库里都没有，
 * 整套扩展图标库又太大，所以直接用 SVG 路径画。每个图标由若干段路径组成，以 "F:" 开头的那段是实心的。
 */
object LumiIcons {
    private fun icon(vararg paths: String): ImageVector {
        val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (p in paths) {
            if (p.startsWith("F:")) b.addPath(pathData = addPathNodes(p.substring(2)), fill = SolidColor(Color.Black))
            else b.addPath(pathData = addPathNodes(p), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        return b.build()
    }

    private fun dot(cx: Float, cy: Float, r: Float) = "F:M${cx + r} ${cy}a$r $r 0 1 1 ${-2 * r} 0a$r $r 0 1 1 ${2 * r} 0z"
    private fun ring(cx: Float, cy: Float, r: Float) = "M${cx + r} ${cy}a$r $r 0 1 1 ${-2 * r} 0a$r $r 0 1 1 ${2 * r} 0z"

    // ---- 通用 ----
    val Menu = icon("M4 6h16", "M4 12h16", "M4 18h16")
    val Edit = icon("M4 20h4L19 9l-4-4L4 16v4z", "M13.5 6.5l4 4")
    val Close = icon("M6 6l12 12", "M18 6L6 18")
    val Check = icon("M5 12.5l5 5L19 7")
    val Plus = icon("M12 5v14", "M5 12h14")
    val Minus = icon("M5 12h14")
    val ChevronLeft = icon("M15 5l-7 7 7 7")
    val ChevronRight = icon("M9 5l7 7-7 7")
    val ChevronDown = icon("M5 9l7 7 7-7")
    val ChevronUp = icon("M5 15l7-7 7 7")
    val Undo = icon("M9 14L4 9l5-5", "M4 9h10a6 6 0 0 1 0 12h-3")
    val Redo = icon("M15 14l5-5-5-5", "M20 9H10a6 6 0 0 0 0 12h3")
    val Trash = icon("M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13", "M10 11v6", "M14 11v6")
    val Folder = icon("M3 6h6l2 2h10v11H3z")
    val Settings = icon(ring(12f, 12f, 3f), "M12 3v3", "M12 18v3", "M3 12h3", "M18 12h3", "M5.6 5.6l2.1 2.1", "M16.3 16.3l2.1 2.1", "M5.6 18.4l2.1-2.1", "M16.3 7.7l2.1-2.1")
    val Help = icon(ring(12f, 12f, 9f), "M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.4-1 .9-1 1.7", "M12 17v.4")
    val Info = icon(ring(12f, 12f, 9f), "M12 11v6", "M12 7.5v.4")
    val Eye = icon("M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z", ring(12f, 12f, 3f))
    val EyeOff = icon("M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z", ring(12f, 12f, 3f), "M4 4l16 16")
    val Save = icon("M5 4h11l3 3v13H5z", "M8 4v5h7V4", "M8 20v-6h8v6")
    val Export = icon("M12 15V4", "M8 8l4-4 4 4", "M5 13v7h14v-7")
    val Photo = icon("M3 7h4l2-2h6l2 2h4v12H3z", ring(12f, 13f, 3.5f))
    val Fullscreen = icon("M4 9V4h5", "M20 9V4h-5", "M4 15v5h5", "M20 15v5h-5")
    val PanelLeft = icon("M3 5h18v14H3z", "M9 5v14", "F:M4.5 6.5h3v11h-3z")
    val PanelRight = icon("M3 5h18v14H3z", "M15 5v14", "F:M16.5 6.5h3v11h-3z")
    val NewFile = icon("M6 3h8l4 4v14H6z", "M14 3v4h4", "M12 11v6", "M9 14h6")
    val Home = icon("M4 11l8-7 8 7", "M6 10v10h12V10")
    val Lock = icon("M6 11h12v9H6z", "M8 11V8a4 4 0 0 1 8 0v3")
    val Code = icon("M8 7l-5 5 5 5", "M16 7l5 5-5 5", "M13.5 5l-3 14")
    val Terminal = icon("M3 5h18v14H3z", "M7 10l3 2.5L7 15", "M12.5 15h4.5")
    val Gamepad = icon("M7 8h10a5 5 0 0 1 5 5v1.5a3 3 0 0 1-5.4 1.8L15.5 15h-7l-1.1 1.3A3 3 0 0 1 2 14.5V13a5 5 0 0 1 5-5z", "M6.5 11.5v3", "M5 13h3", dot(16.2f, 11.8f, 0.9f), dot(18.3f, 13.9f, 0.9f))
    val Scissors = icon(ring(6f, 6.5f, 2.5f), ring(6f, 17.5f, 2.5f), "M8 8l12 9", "M8 16l12-9")
    val Tracks = icon("M3 5h12v4H3z", "M8 10.5h13v4H8z", "M3 16h9v4H3z")
    val Wind = icon("M3 9h10a2.5 2.5 0 1 0-2.5-2.5", "M3 13h15a2.5 2.5 0 1 1-2.5 2.5", "M3 17h7")
    val Jump = icon(dot(12f, 4.5f, 1.8f), "M12 7.5v6", "M8 9.5l4-1.5 4 1.5", "M12 13.5l-3 4", "M12 13.5l3 3.5", "M6 21h12")
    val Crouch = icon(dot(14f, 7f, 1.8f), "M13 9.5l-4 3.5 4 2.5-1 4.5", "M9 13l-3 1", "M13 15.5h4")
    val Prone = icon(dot(5f, 14f, 1.8f), "M8 15.5h8l5 2", "M10 15.5l-2 3.5", "M3 20h18")
    val Sprint = icon(dot(15f, 4.8f, 1.8f), "M14 8l-4 3 3.5 3-2 5.5", "M10 11l-4-.5", "M13.5 14l4.5 1", "M12.5 9.5l4 1.5 2.5-1.5", "M3 15h3", "M2 18.5h4.5")
    val Music = icon("M9 18V5l10-2v13", ring(6f, 18f, 3f), ring(16f, 16f, 3f))
    val Robot = icon("M5 9h14v10H5z", "M12 9V5", dot(12f, 4.5f, 1.3f), "M9.5 13v2", "M14.5 13v2", "M2.5 13v3", "M21.5 13v3")
    val Plug = icon("M9 3v5", "M15 3v5", "M7 8h10v4a5 5 0 0 1-10 0z", "M12 17v4")
    val Link = icon("M10 14a4 4 0 0 1 0-5.7l3-3a4 4 0 0 1 5.7 5.7l-1.5 1.5", "M14 10a4 4 0 0 1 0 5.7l-3 3a4 4 0 0 1-5.7-5.7l1.5-1.5")
    val Person = icon(ring(12f, 7f, 4f), "M4 21c0-4 3.6-7 8-7s8 3 8 7")
    val Eraser = icon("M4 16l9-9 6 6-7 7H8z", "M4 20h16")
    val Sun = icon(ring(12f, 12f, 4f), "M12 2v3", "M12 19v3", "M2 12h3", "M19 12h3", "M4.9 4.9l2.1 2.1", "M17 17l2.1 2.1", "M4.9 19.1L7 17", "M17 7l2.1-2.1")

    // ---- 播放 / 关键帧 ----
    val Play = icon("F:M8 5v14l11-7z")
    val Pause = icon("F:M7 5h3.5v14H7z", "F:M13.5 5H17v14h-3.5z")
    val ToStart = icon("F:M5 5h2.5v14H5z", "F:M19 5v14l-10-7z")
    val PrevKey = icon("M8 7l-5 5 5 5", "M16 7l5 5-5 5-5-5z")
    val NextKey = icon("M16 7l5 5-5 5", "M8 7l5 5-5 5-5-5z")
    val Key = icon("F:M12 3l9 9-9 9-9-9z")
    val KeyAdd = icon("M12 3l9 9-9 9-9-9z", "M12 8.5v7", "M8.5 12h7")
    val KeyDelete = icon("M12 3l9 9-9 9-9-9z", "M9.5 9.5l5 5", "M14.5 9.5l-5 5")
    val KeyAuto = icon("M12 3l9 9-9 9-9-9z", "M9.3 15l2.7-7 2.7 7", "M10.2 12.8h3.6")
    val Copy = icon("M9 9h11v11H9z", "M5 15H4V4h11v1")
    val Paste = icon("M8 5H6v15h12V5h-2", "M9 3h6v4H9z")
    val PasteMirror = icon("M8 5H6v15h12V5h-2", "M9 3h6v4H9z", "M9 13.5h6", "M10.5 12L9 13.5l1.5 1.5", "M13.5 12l1.5 1.5-1.5 1.5")
    val Loop = icon("M17 3l3 3-3 3", "M4 11V9a3 3 0 0 1 3-3h13", "M7 21l-3-3 3-3", "M20 13v2a3 3 0 0 1-3 3H4")
    val Curve = icon("M4 4v16h16", "M5 19C13 19 12 6 20 6")
    val Timeline = icon("M3 6h18", "M3 12h18", "M3 18h18", "F:M8 3.5L10.5 6 8 8.5 5.5 6z", "F:M15 9.5l2.5 2.5-2.5 2.5-2.5-2.5z", "F:M10 15.5l2.5 2.5-2.5 2.5L7.5 18z")
    val ShiftRight = icon("M4 12h12", "M12 7l5 5-5 5", "M20 5v14")
    val ShiftLeft = icon("M20 12H8", "M12 7l-5 5 5 5", "M4 5v14")

    // ---- 模式 ----
    val Cube = icon("M12 3l8 4.5v9L12 21l-8-4.5v-9z", "M12 12l8-4.5", "M12 12L4 7.5", "M12 12v9")
    val Bone = icon("M8.3 8.3l7.4 7.4", ring(6.5f, 6.5f, 2.6f), ring(17.5f, 17.5f, 2.6f))
    val Face = icon(ring(12f, 12f, 9f), "M8 14.5c1 1.5 2.5 2.2 4 2.2s3-.7 4-2.2", "M9 9.3v1.2", "M15 9.3v1.2")
    val Camera = icon("M3 7h12v10H3z", "M15 10.5l6-3.5v10l-6-3.5")
    val Layers = icon("M12 3l9 5-9 5-9-5z", "M3 12.5l9 5 9-5", "M3 16.5l9 5 9-5")
    val Mesh = icon("M4 4h16v16H4z", "M4 12h16", "M12 4v16", dot(12f, 12f, 1.8f), dot(4f, 4f, 1.8f), dot(20f, 4f, 1.8f), dot(4f, 20f, 1.8f), dot(20f, 20f, 1.8f))

    // ---- 操纵 ----
    val Move = icon("M12 3v18", "M3 12h18", "M9 6l3-3 3 3", "M9 18l3 3 3-3", "M6 9l-3 3 3 3", "M18 9l3 3-3 3")
    val Rotate = icon("M20 12a8 8 0 1 1-2.6-5.9", "M20 4v5h-5")
    val Scale = icon("M4 13h7v7H4z", "M13 11l7-7", "M14 4h6v6")
    val Select = icon("M5 3l14 8-6 2-2 6z")
    val Local = icon("M6 18V5", "M6 18h13", "M6 18l8-6", "M4 7l2-2 2 2", "M17 16l2 2-2 2")
    val Globe = icon(ring(12f, 12f, 9f), "M3 12h18", "M12 3c3.2 3 3.2 15 0 18", "M12 3c-3.2 3-3.2 15 0 18")
    val Focus = icon("M12 3v4", "M12 17v4", "M3 12h4", "M17 12h4", ring(12f, 12f, 4f))
    val Ground = icon("M4 20h16", "M12 4v11", "M8 11l4 4 4-4")
    val Reset = icon("M4 12a8 8 0 1 0 2.6-5.9", "M4 4v5h5")
    val StandUp = icon("M5 20h14", "M12 16V4", "M8 8l4-4 4 4")
    val TurnAround = icon("M4 12a8 4 0 1 0 16 0", "M20 12a8 4 0 0 0-11-3.7", "M9 4.5v4h4")

    // ---- 建模 ----
    val Point = icon("M5 5h14v14H5z", dot(12f, 12f, 2.8f))
    val Edge = icon("M6.5 17.5l11-11", dot(6f, 18f, 2.4f), dot(18f, 6f, 2.4f))
    val Polygon = icon("F:M5 8.5l8-4.5 6.5 5-2.5 9-9 1z")
    val BoxSelect = icon("M4 4h3", "M10 4h4", "M17 4h3", "M20 7v0", "M20 10v4", "M20 17v3", "M4 20h3", "M10 20h4", "M17 20h0", "M4 7v0", "M4 10v4", "M4 17v0")
    val MultiSelect = icon("M4 4h10v10H4z", "M10 10h10v10H10z")
    val SelectAll = icon("M4 4h16v16H4z", "F:M8 8h8v8H8z")
    val SelectNone = icon("M4 4h16v16H4z")
    val Invert = icon("M4 4h16v16H4z", "F:M4 4l16 16H4z")
    val Grow = icon(ring(12f, 12f, 9f), "M12 8v8", "M8 12h8")
    val Extrude = icon("M5 21v-6h14v6", "M12 14V3", "M8 7l4-4 4 4")
    val Inset = icon("M4 4h16v16H4z", "M8.5 8.5h7v7h-7z")
    val Subdivide = icon("M4 4h16v16H4z", "M12 4v16", "M4 12h16")
    val LoopCut = icon("M4 6h16v12H4z", "M12 2v20")
    val SplitEdge = icon("M5 19L19 5", dot(5f, 19f, 2f), dot(19f, 5f, 2f), dot(12f, 12f, 2.4f))
    val Dissolve = icon("M4 12h3", "M10.5 12h3", "M17 12h3", "M4 5h16v14H4z")
    val Duplicate = icon("M9 9h11v11H9z", "M5 15H4V4h11v1", "M14.5 12v5", "M12 14.5h5")
    val Flip = icon("M8 4v16", "M5 7l3-3 3 3", "M16 20V4", "M13 17l3 3 3-3")
    val Merge = icon("M4 5l7 7-7 7", "M20 5l-7 7 7 7", dot(12f, 12f, 1.8f))
    val Weld = icon(dot(6f, 12f, 2.2f), dot(18f, 12f, 2.2f), "M10 12h4", "M12.5 10l1.8 2-1.8 2", "M11.5 10l-1.8 2 1.8 2")
    val Fill = icon("M5 8.5l8-4.5 6.5 5-2.5 9-9 1z", "M8.5 11l6 6", "M11 8l6.5 6.5")
    val Mirror = icon("M12 3v18", "M9 7l-5 5 5 5z", "M15 7l5 5-5 5z")
    val Solidify = icon("M4 8h16", "M4 16h16", "M12 8v8", "M10 10.5l2-2.5 2 2.5", "M10 13.5l2 2.5 2-2.5")
    val Smooth = icon("M3 14c3-8 6-8 9 0s6 8 9 0")
    val ShadeSmooth = icon(ring(12f, 12f, 9f), "M8 9.5a5 5 0 0 1 4.5-3")
    val ShadeFlat = icon("M12 3l8 5v8l-8 5-8-5V8z", "M4 8l16 8", "M12 3v18")
    val Triangulate = icon("M4 4h16v16H4z", "M4 20L20 4")
    val Uv = icon("M4 4h16v16H4z", "M4 10h16", "M10 4v16", "F:M11.5 11.5h7v7h-7z")
    val Sphere = icon(ring(12f, 12f, 9f), "M3 12c0 2 4 3.5 9 3.5s9-1.5 9-3.5")
    val Cylinder = icon("M5 6c0-1.7 3.1-3 7-3s7 1.3 7 3v12c0 1.7-3.1 3-7 3s-7-1.3-7-3z", "M5 6c0 1.7 3.1 3 7 3s7-1.3 7-3")
    val Cone = icon("M12 3L5 18c0 1.7 3.1 3 7 3s7-1.3 7-3z", "M5 18c0-1.3 3.1-2.4 7-2.4s7 1.1 7 2.4")
    val Torus = icon("M21 12c0 3.3-4 6-9 6s-9-2.7-9-6 4-6 9-6 9 2.7 9 6z", "M15.5 11.3c0 1.1-1.6 2-3.5 2s-3.5-.9-3.5-2")
    val Plane = icon("M3 17l5-10h13l-5 10z")
    val Palette = icon("M12 3a9 9 0 1 0 0 18c1.5 0 2-1 2-2s-.5-1.5-.5-2.5S14.5 15 16 15h2a3 3 0 0 0 3-3c0-5-4-9-9-9z", dot(7.5f, 11f, 1.2f), dot(10f, 7.2f, 1.2f), dot(14.5f, 7.2f, 1.2f))
}

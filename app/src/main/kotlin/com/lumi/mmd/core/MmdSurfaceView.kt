package com.lumi.mmd.core

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ViewConfiguration
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 原生 OpenGL ES 3.0 渲染视图。
 *
 * 窗口表面本身不需要深度 / 模板 / MSAA——场景先画到引擎自己的离屏帧缓冲里再输出。
 * 手势：单指拖动 = 转视角或操纵选中的对象；双指 = 平移 + 缩放；单击 = 选择；双击 = 把注视点移到点中的位置。
 */
@SuppressLint("ViewConstructor")
class MmdSurfaceView(context: Context, private val controller: SceneController) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var dragging = false
    private var multi = false
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastSpan = 0f
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        controller.glPost = { r -> queueEvent(r) }
        controller.glRequestRender = { requestRender() }
        controller.glSetContinuous = { on -> renderMode = if (on) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY }
        controller.glHasContext = { EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT }
        controller.glBorrowContext = { borrowContext() }
        controller.glReturnContext = { returnContext() }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        rememberContext()
        controller.onContextCreated()
    }

    // ---- 应用在后台时借用 GL 上下文 ----
    //
    // 切到后台以后窗口表面被系统收走，GLSurfaceView 把上下文从渲染线程上摘下来（上下文本身留着，回前台接着用）。
    // 这时 AI 通过 MCP 发来的操作照样在渲染线程上执行，但没有「当前上下文」，凡是碰 GL 的（截图、释放模型的缓冲）都会落空。
    // 所以在后台执行这类任务时，临时把留着的那个上下文接到一块 1×1 的离屏表面上，做完再摘下来——
    // 画面本来就是先画进引擎自己的帧缓冲的，不需要窗口。回到前台时 GLSurfaceView 看到的状态和它离开时一样。

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: android.opengl.EGLConfig? = null
    private var offscreen: EGLSurface = EGL14.EGL_NO_SURFACE

    /** 渲染线程，上下文刚建好时：记下它是谁。 */
    private fun rememberContext() {
        try {
            val display = EGL14.eglGetCurrentDisplay()
            val context = EGL14.eglGetCurrentContext()
            if (display == EGL14.EGL_NO_DISPLAY || context == EGL14.EGL_NO_CONTEXT) return
            // 换了新的上下文：旧的离屏表面跟着作废
            if (offscreen != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglDestroySurface(eglDisplay, offscreen)
            offscreen = EGL14.EGL_NO_SURFACE
            eglDisplay = display; eglContext = context
            val id = IntArray(1)
            EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0)
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val n = IntArray(1)
            EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, configs, 0, 1, n, 0)
            eglConfig = if (n[0] > 0) configs[0] else null
        } catch (_: Throwable) {
            eglContext = EGL14.EGL_NO_CONTEXT
        }
    }

    /**
     * 渲染线程：现在没有当前上下文（应用在后台）时把留着的上下文接上。
     * @return true = 是这次调用接上的，用完要 [returnContext]；false = 本来就有，或者接不上（上下文已经被系统收走）
     */
    private fun borrowContext(): Boolean {
        try {
            if (EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT) return false
            if (eglContext == EGL14.EGL_NO_CONTEXT || eglDisplay == EGL14.EGL_NO_DISPLAY) return false
            if (offscreen == EGL14.EGL_NO_SURFACE) {
                val cfg = eglConfig
                if (cfg != null) {
                    offscreen = EGL14.eglCreatePbufferSurface(eglDisplay, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0) ?: EGL14.EGL_NO_SURFACE
                    if (offscreen == EGL14.EGL_NO_SURFACE) EGL14.eglGetError()
                }
            }
            if (offscreen != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(eglDisplay, offscreen, offscreen, eglContext)) return true
            // 这个配置开不了离屏表面：试试不带表面直接接上（多数新一点的驱动支持）
            if (EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext)) return true
            EGL14.eglGetError()
        } catch (_: Throwable) {
        }
        return false
    }

    private fun returnContext() {
        try { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) } catch (_: Throwable) {}
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = controller.onResize(width, height)

    override fun onDrawFrame(gl: GL10?) = controller.drawFrame()

    private fun gl(block: () -> Unit) {
        queueEvent(block)
        requestRender()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                downTime = e.eventTime
                moved = false; dragging = false; multi = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragging) { dragging = false; gl { controller.dragEnd() } }
                multi = true
                moved = true
                if (e.pointerCount >= 2) {
                    lastMidX = (e.getX(0) + e.getX(1)) * 0.5f
                    lastMidY = (e.getY(0) + e.getY(1)) * 0.5f
                    lastSpan = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    if (e.pointerCount >= 2) {
                        val mx = (e.getX(0) + e.getX(1)) * 0.5f
                        val my = (e.getY(0) + e.getY(1)) * 0.5f
                        val span = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        val dx = mx - lastMidX; val dy = my - lastMidY
                        val zoom = if (lastSpan > 10f && span > 10f) span / lastSpan else 1f
                        lastMidX = mx; lastMidY = my; lastSpan = span
                        if (dx != 0f || dy != 0f || zoom != 1f) gl { controller.twoFinger(dx, dy, zoom) }
                    }
                } else {
                    val x = e.x; val y = e.y
                    if (!moved && (abs(x - downX) > slop || abs(y - downY) > slop)) {
                        moved = true
                        dragging = true
                        val sx = downX; val sy = downY
                        gl { controller.dragStart(sx, sy) }
                        lastX = downX; lastY = downY
                    }
                    if (dragging) {
                        val dx = x - lastX; val dy = y - lastY
                        lastX = x; lastY = y
                        if (dx != 0f || dy != 0f) gl { controller.dragMove(dx, dy, x, y) }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 还剩一根手指时不切回单指拖动，避免视角突然跳一下
                if (e.pointerCount - 1 >= 2) {
                    val skip = e.actionIndex
                    val ids = (0 until e.pointerCount).filter { it != skip }
                    lastMidX = (e.getX(ids[0]) + e.getX(ids[1])) * 0.5f
                    lastMidY = (e.getY(ids[0]) + e.getY(ids[1])) * 0.5f
                    lastSpan = hypot(e.getX(ids[0]) - e.getX(ids[1]), e.getY(ids[0]) - e.getY(ids[1]))
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    gl { controller.dragEnd() }
                } else if (!moved && !multi && e.eventTime - downTime < 400) {
                    val x = e.x; val y = e.y
                    val isDouble = e.eventTime - lastTapTime < doubleTapTimeout && abs(x - lastTapX) < slop * 3 && abs(y - lastTapY) < slop * 3
                    if (isDouble) {
                        lastTapTime = 0L
                        gl { controller.touchDoubleTap(x, y) }
                    } else {
                        lastTapTime = e.eventTime; lastTapX = x; lastTapY = y
                        gl { controller.touchTap(x, y) }
                    }
                }
                multi = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) { dragging = false; gl { controller.dragEnd() } }
                multi = false
            }
        }
        return true
    }
}

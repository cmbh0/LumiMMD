package com.lumi.mmd.engine.render

import android.opengl.GLES30
import com.lumi.mmd.engine.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer

/** OpenGL ES 3.0 的薄封装：只做"把数组参数的样板代码收起来"这类事，不引入额外的抽象层。 */
object GlUtil {
    private const val TAG = "GL"
    private val tmp = IntArray(4)

    fun byteBuffer(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    fun floatBuffer(count: Int): FloatBuffer = byteBuffer(count * 4).asFloatBuffer()
    fun intBuffer(count: Int): IntBuffer = byteBuffer(count * 4).asIntBuffer()
    fun shortBuffer(count: Int): ShortBuffer = byteBuffer(count * 2).asShortBuffer()

    fun genBuffer(): Int { GLES30.glGenBuffers(1, tmp, 0); return tmp[0] }
    fun deleteBuffer(id: Int) { if (id != 0) { tmp[0] = id; GLES30.glDeleteBuffers(1, tmp, 0) } }
    fun genTexture(): Int { GLES30.glGenTextures(1, tmp, 0); return tmp[0] }
    fun deleteTexture(id: Int) { if (id != 0) { tmp[0] = id; GLES30.glDeleteTextures(1, tmp, 0) } }
    fun genVertexArray(): Int { GLES30.glGenVertexArrays(1, tmp, 0); return tmp[0] }
    fun deleteVertexArray(id: Int) { if (id != 0) { tmp[0] = id; GLES30.glDeleteVertexArrays(1, tmp, 0) } }
    fun genFramebuffer(): Int { GLES30.glGenFramebuffers(1, tmp, 0); return tmp[0] }
    fun deleteFramebuffer(id: Int) { if (id != 0) { tmp[0] = id; GLES30.glDeleteFramebuffers(1, tmp, 0) } }
    fun genRenderbuffer(): Int { GLES30.glGenRenderbuffers(1, tmp, 0); return tmp[0] }
    fun deleteRenderbuffer(id: Int) { if (id != 0) { tmp[0] = id; GLES30.glDeleteRenderbuffers(1, tmp, 0) } }

    fun getInteger(name: Int): Int { tmp[0] = 0; GLES30.glGetIntegerv(name, tmp, 0); return tmp[0] }

    fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, tmp, 0)
        if (tmp[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            val kind = if (type == GLES30.GL_VERTEX_SHADER) "顶点" else "片元"
            Log.e(TAG, "${kind}着色器编译失败:\n$log")
            throw RuntimeException("${kind}着色器编译失败: $log")
        }
        return shader
    }

    /** 编译并链接；attribs 是 (名字 → location) 的绑定表。 */
    fun linkProgram(vertexSrc: String, fragmentSrc: String, attribs: Array<String>): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        for (i in attribs.indices) if (attribs[i].isNotEmpty()) GLES30.glBindAttribLocation(program, i, attribs[i])
        GLES30.glLinkProgram(program)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, tmp, 0)
        val ok = tmp[0] != 0
        val log = if (ok) "" else GLES30.glGetProgramInfoLog(program)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (!ok) {
            GLES30.glDeleteProgram(program)
            Log.e(TAG, "着色器链接失败:\n$log")
            throw RuntimeException("着色器链接失败: $log")
        }
        return program
    }

    fun checkError(where: String) {
        var err = GLES30.glGetError()
        var n = 0
        while (err != GLES30.GL_NO_ERROR && n < 8) {
            Log.w(TAG, "GL 错误 0x${Integer.toHexString(err)} @ $where")
            err = GLES30.glGetError()
            n++
        }
    }
}

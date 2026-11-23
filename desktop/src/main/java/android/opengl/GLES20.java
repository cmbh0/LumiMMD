package android.opengl;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * 桌面无头渲染工具用的垫片：与 Android 的 android.opengl.GLES20 同名同签名，内部转发给 LWJGL。
 * 这样 engine 模块的渲染代码一行不改就能在 PC（Mesa llvmpipe）上跑，用来做离屏渲染回归测试。
 * 只实现了引擎实际用到的那部分函数。
 */
public class GLES20 {
    public static final int GL_NO_ERROR = 0;
    public static final int GL_NONE = 0;
    public static final int GL_TRIANGLES = 0x0004;
    public static final int GL_TRIANGLE_STRIP = 0x0005;
    public static final int GL_LINES = 0x0001;
    public static final int GL_POINTS = 0x0000;
    public static final int GL_DEPTH_BUFFER_BIT = 0x0100;
    public static final int GL_STENCIL_BUFFER_BIT = 0x0400;
    public static final int GL_COLOR_BUFFER_BIT = 0x4000;
    public static final int GL_SRC_ALPHA = 0x0302;
    public static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
    public static final int GL_ONE = 1;
    public static final int GL_ZERO = 0;
    public static final int GL_FRONT = 0x0404;
    public static final int GL_BACK = 0x0405;
    public static final int GL_CW = 0x0900;
    public static final int GL_CCW = 0x0901;
    public static final int GL_CULL_FACE = 0x0B44;
    public static final int GL_DEPTH_TEST = 0x0B71;
    public static final int GL_STENCIL_TEST = 0x0B90;
    public static final int GL_BLEND = 0x0BE2;
    public static final int GL_SCISSOR_TEST = 0x0C11;
    public static final int GL_POLYGON_OFFSET_FILL = 0x8037;
    public static final int GL_LEQUAL = 0x0203;
    public static final int GL_LESS = 0x0201;
    public static final int GL_ALWAYS = 0x0207;
    public static final int GL_UNPACK_ALIGNMENT = 0x0CF5;
    public static final int GL_PACK_ALIGNMENT = 0x0D05;
    public static final int GL_TEXTURE_2D = 0x0DE1;
    public static final int GL_BYTE = 0x1400;
    public static final int GL_UNSIGNED_BYTE = 0x1401;
    public static final int GL_SHORT = 0x1402;
    public static final int GL_UNSIGNED_SHORT = 0x1403;
    public static final int GL_INT = 0x1404;
    public static final int GL_UNSIGNED_INT = 0x1405;
    public static final int GL_FLOAT = 0x1406;
    public static final int GL_DEPTH_COMPONENT = 0x1902;
    public static final int GL_RGB = 0x1907;
    public static final int GL_RGBA = 0x1908;
    public static final int GL_NEAREST = 0x2600;
    public static final int GL_LINEAR = 0x2601;
    public static final int GL_LINEAR_MIPMAP_LINEAR = 0x2703;
    public static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    public static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    public static final int GL_TEXTURE_WRAP_S = 0x2802;
    public static final int GL_TEXTURE_WRAP_T = 0x2803;
    public static final int GL_REPEAT = 0x2901;
    public static final int GL_CLAMP_TO_EDGE = 0x812F;
    public static final int GL_TEXTURE0 = 0x84C0;
    public static final int GL_TEXTURE1 = 0x84C1;
    public static final int GL_TEXTURE2 = 0x84C2;
    public static final int GL_TEXTURE3 = 0x84C3;
    public static final int GL_TEXTURE4 = 0x84C4;
    public static final int GL_TEXTURE5 = 0x84C5;
    public static final int GL_ARRAY_BUFFER = 0x8892;
    public static final int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
    public static final int GL_STATIC_DRAW = 0x88E4;
    public static final int GL_DYNAMIC_DRAW = 0x88E8;
    public static final int GL_STREAM_DRAW = 0x88E0;
    public static final int GL_FRAGMENT_SHADER = 0x8B30;
    public static final int GL_VERTEX_SHADER = 0x8B31;
    public static final int GL_COMPILE_STATUS = 0x8B81;
    public static final int GL_LINK_STATUS = 0x8B82;
    public static final int GL_FRAMEBUFFER = 0x8D40;
    public static final int GL_RENDERBUFFER = 0x8D41;
    public static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    public static final int GL_DEPTH_ATTACHMENT = 0x8D00;
    public static final int GL_STENCIL_ATTACHMENT = 0x8D20;
    public static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    public static final int GL_DEPTH_COMPONENT16 = 0x81A5;
    public static final int GL_VENDOR = 0x1F00;
    public static final int GL_RENDERER = 0x1F01;
    public static final int GL_VERSION = 0x1F02;
    public static final int GL_EXTENSIONS = 0x1F03;
    public static final int GL_MAX_TEXTURE_SIZE = 0x0D33;
    public static final int GL_KEEP = 0x1E00;
    public static final int GL_REPLACE = 0x1E01;
    public static final int GL_EQUAL = 0x0202;
    public static final int GL_NOTEQUAL = 0x0205;

    protected static long addr(Buffer b) {
        if (b == null) return 0L;
        if (b instanceof ByteBuffer) return MemoryUtil.memAddress((ByteBuffer) b);
        if (b instanceof FloatBuffer) return MemoryUtil.memAddress((FloatBuffer) b);
        if (b instanceof IntBuffer) return MemoryUtil.memAddress((IntBuffer) b);
        if (b instanceof ShortBuffer) return MemoryUtil.memAddress((ShortBuffer) b);
        throw new IllegalArgumentException("unsupported buffer type " + b.getClass());
    }

    private static float[] slice(float[] v, int offset, int count) {
        if (offset == 0 && v.length == count) return v;
        float[] out = new float[count];
        System.arraycopy(v, offset, out, 0, count);
        return out;
    }

    public static void glEnable(int cap) { org.lwjgl.opengles.GLES20.glEnable(cap); }
    public static void glDisable(int cap) { org.lwjgl.opengles.GLES20.glDisable(cap); }
    public static void glClear(int mask) { org.lwjgl.opengles.GLES20.glClear(mask); }
    public static void glClearColor(float r, float g, float b, float a) { org.lwjgl.opengles.GLES20.glClearColor(r, g, b, a); }
    public static void glClearDepthf(float d) { org.lwjgl.opengles.GLES20.glClearDepthf(d); }
    public static void glViewport(int x, int y, int w, int h) { org.lwjgl.opengles.GLES20.glViewport(x, y, w, h); }
    public static void glScissor(int x, int y, int w, int h) { org.lwjgl.opengles.GLES20.glScissor(x, y, w, h); }
    public static void glDepthFunc(int f) { org.lwjgl.opengles.GLES20.glDepthFunc(f); }
    public static void glDepthMask(boolean f) { org.lwjgl.opengles.GLES20.glDepthMask(f); }
    public static void glColorMask(boolean r, boolean g, boolean b, boolean a) { org.lwjgl.opengles.GLES20.glColorMask(r, g, b, a); }
    public static void glBlendFunc(int s, int d) { org.lwjgl.opengles.GLES20.glBlendFunc(s, d); }
    public static void glBlendFuncSeparate(int a, int b, int c, int d) { org.lwjgl.opengles.GLES20.glBlendFuncSeparate(a, b, c, d); }
    public static void glCullFace(int mode) { org.lwjgl.opengles.GLES20.glCullFace(mode); }
    public static void glFrontFace(int mode) { org.lwjgl.opengles.GLES20.glFrontFace(mode); }
    public static void glPolygonOffset(float factor, float units) { org.lwjgl.opengles.GLES20.glPolygonOffset(factor, units); }
    public static void glLineWidth(float w) { org.lwjgl.opengles.GLES20.glLineWidth(w); }
    public static void glStencilFunc(int func, int ref, int mask) { org.lwjgl.opengles.GLES20.glStencilFunc(func, ref, mask); }
    public static void glStencilOp(int fail, int zfail, int zpass) { org.lwjgl.opengles.GLES20.glStencilOp(fail, zfail, zpass); }
    public static void glStencilMask(int mask) { org.lwjgl.opengles.GLES20.glStencilMask(mask); }
    public static void glFinish() { org.lwjgl.opengles.GLES20.glFinish(); }
    public static void glFlush() { org.lwjgl.opengles.GLES20.glFlush(); }
    public static int glGetError() { return org.lwjgl.opengles.GLES20.glGetError(); }
    public static String glGetString(int name) { return org.lwjgl.opengles.GLES20.glGetString(name); }
    public static void glGetIntegerv(int pname, int[] params, int offset) { params[offset] = org.lwjgl.opengles.GLES20.glGetInteger(pname); }
    public static void glPixelStorei(int pname, int param) { org.lwjgl.opengles.GLES20.glPixelStorei(pname, param); }

    public static int glCreateShader(int type) { return org.lwjgl.opengles.GLES20.glCreateShader(type); }
    public static void glShaderSource(int shader, String src) { org.lwjgl.opengles.GLES20.glShaderSource(shader, src); }
    public static void glCompileShader(int shader) { org.lwjgl.opengles.GLES20.glCompileShader(shader); }
    public static void glGetShaderiv(int shader, int pname, int[] params, int offset) { params[offset] = org.lwjgl.opengles.GLES20.glGetShaderi(shader, pname); }
    public static String glGetShaderInfoLog(int shader) { return org.lwjgl.opengles.GLES20.glGetShaderInfoLog(shader); }
    public static void glDeleteShader(int shader) { org.lwjgl.opengles.GLES20.glDeleteShader(shader); }
    public static int glCreateProgram() { return org.lwjgl.opengles.GLES20.glCreateProgram(); }
    public static void glAttachShader(int p, int s) { org.lwjgl.opengles.GLES20.glAttachShader(p, s); }
    public static void glBindAttribLocation(int p, int index, String name) { org.lwjgl.opengles.GLES20.glBindAttribLocation(p, index, name); }
    public static void glLinkProgram(int p) { org.lwjgl.opengles.GLES20.glLinkProgram(p); }
    public static void glGetProgramiv(int p, int pname, int[] params, int offset) { params[offset] = org.lwjgl.opengles.GLES20.glGetProgrami(p, pname); }
    public static String glGetProgramInfoLog(int p) { return org.lwjgl.opengles.GLES20.glGetProgramInfoLog(p); }
    public static void glDeleteProgram(int p) { org.lwjgl.opengles.GLES20.glDeleteProgram(p); }
    public static void glUseProgram(int p) { org.lwjgl.opengles.GLES20.glUseProgram(p); }
    public static int glGetUniformLocation(int p, String name) { return org.lwjgl.opengles.GLES20.glGetUniformLocation(p, name); }
    public static void glUniform1i(int loc, int v) { org.lwjgl.opengles.GLES20.glUniform1i(loc, v); }
    public static void glUniform1f(int loc, float v) { org.lwjgl.opengles.GLES20.glUniform1f(loc, v); }
    public static void glUniform2f(int loc, float a, float b) { org.lwjgl.opengles.GLES20.glUniform2f(loc, a, b); }
    public static void glUniform3f(int loc, float a, float b, float c) { org.lwjgl.opengles.GLES20.glUniform3f(loc, a, b, c); }
    public static void glUniform4f(int loc, float a, float b, float c, float d) { org.lwjgl.opengles.GLES20.glUniform4f(loc, a, b, c, d); }
    public static void glUniform3fv(int loc, int count, float[] v, int offset) { org.lwjgl.opengles.GLES20.glUniform3fv(loc, slice(v, offset, count * 3)); }
    public static void glUniform4fv(int loc, int count, float[] v, int offset) { org.lwjgl.opengles.GLES20.glUniform4fv(loc, slice(v, offset, count * 4)); }
    public static void glUniformMatrix4fv(int loc, int count, boolean transpose, float[] v, int offset) { org.lwjgl.opengles.GLES20.glUniformMatrix4fv(loc, transpose, slice(v, offset, count * 16)); }

    public static void glGenBuffers(int n, int[] out, int offset) { for (int i = 0; i < n; i++) out[offset + i] = org.lwjgl.opengles.GLES20.glGenBuffers(); }
    public static void glDeleteBuffers(int n, int[] ids, int offset) { for (int i = 0; i < n; i++) org.lwjgl.opengles.GLES20.glDeleteBuffers(ids[offset + i]); }
    public static void glBindBuffer(int target, int id) { org.lwjgl.opengles.GLES20.glBindBuffer(target, id); }
    public static void glBufferData(int target, int size, Buffer data, int usage) { org.lwjgl.opengles.GLES20.nglBufferData(target, size, addr(data), usage); }
    public static void glBufferSubData(int target, int offset, int size, Buffer data) { org.lwjgl.opengles.GLES20.nglBufferSubData(target, offset, size, addr(data)); }
    public static void glEnableVertexAttribArray(int i) { org.lwjgl.opengles.GLES20.glEnableVertexAttribArray(i); }
    public static void glDisableVertexAttribArray(int i) { org.lwjgl.opengles.GLES20.glDisableVertexAttribArray(i); }
    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int offset) { org.lwjgl.opengles.GLES20.glVertexAttribPointer(index, size, type, normalized, stride, (long) offset); }
    public static void glVertexAttrib4f(int index, float a, float b, float c, float d) { org.lwjgl.opengles.GLES20.glVertexAttrib4f(index, a, b, c, d); }
    public static void glVertexAttrib1f(int index, float a) { org.lwjgl.opengles.GLES20.glVertexAttrib1f(index, a); }
    public static void glDrawArrays(int mode, int first, int count) { org.lwjgl.opengles.GLES20.glDrawArrays(mode, first, count); }
    public static void glDrawElements(int mode, int count, int type, int offset) { org.lwjgl.opengles.GLES20.glDrawElements(mode, count, type, (long) offset); }

    public static void glGenTextures(int n, int[] out, int offset) { for (int i = 0; i < n; i++) out[offset + i] = org.lwjgl.opengles.GLES20.glGenTextures(); }
    public static void glDeleteTextures(int n, int[] ids, int offset) { for (int i = 0; i < n; i++) org.lwjgl.opengles.GLES20.glDeleteTextures(ids[offset + i]); }
    public static void glBindTexture(int target, int id) { org.lwjgl.opengles.GLES20.glBindTexture(target, id); }
    public static void glActiveTexture(int unit) { org.lwjgl.opengles.GLES20.glActiveTexture(unit); }
    public static void glTexParameteri(int target, int pname, int param) { org.lwjgl.opengles.GLES20.glTexParameteri(target, pname, param); }
    public static void glTexParameterf(int target, int pname, float param) { org.lwjgl.opengles.GLES20.glTexParameterf(target, pname, param); }
    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, Buffer pixels) {
        org.lwjgl.opengles.GLES20.nglTexImage2D(target, level, internalformat, width, height, border, format, type, addr(pixels));
    }
    public static void glTexSubImage2D(int target, int level, int x, int y, int width, int height, int format, int type, Buffer pixels) {
        org.lwjgl.opengles.GLES20.nglTexSubImage2D(target, level, x, y, width, height, format, type, addr(pixels));
    }
    public static void glGenerateMipmap(int target) { org.lwjgl.opengles.GLES20.glGenerateMipmap(target); }
    public static void glReadPixels(int x, int y, int w, int h, int format, int type, Buffer pixels) { org.lwjgl.opengles.GLES20.nglReadPixels(x, y, w, h, format, type, addr(pixels)); }

    public static void glGenFramebuffers(int n, int[] out, int offset) { for (int i = 0; i < n; i++) out[offset + i] = org.lwjgl.opengles.GLES20.glGenFramebuffers(); }
    public static void glDeleteFramebuffers(int n, int[] ids, int offset) { for (int i = 0; i < n; i++) org.lwjgl.opengles.GLES20.glDeleteFramebuffers(ids[offset + i]); }
    public static void glBindFramebuffer(int target, int id) { org.lwjgl.opengles.GLES20.glBindFramebuffer(target, id); }
    public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) { org.lwjgl.opengles.GLES20.glFramebufferTexture2D(target, attachment, textarget, texture, level); }
    public static void glFramebufferRenderbuffer(int target, int attachment, int rbtarget, int rb) { org.lwjgl.opengles.GLES20.glFramebufferRenderbuffer(target, attachment, rbtarget, rb); }
    public static int glCheckFramebufferStatus(int target) { return org.lwjgl.opengles.GLES20.glCheckFramebufferStatus(target); }
    public static void glGenRenderbuffers(int n, int[] out, int offset) { for (int i = 0; i < n; i++) out[offset + i] = org.lwjgl.opengles.GLES20.glGenRenderbuffers(); }
    public static void glDeleteRenderbuffers(int n, int[] ids, int offset) { for (int i = 0; i < n; i++) org.lwjgl.opengles.GLES20.glDeleteRenderbuffers(ids[offset + i]); }
    public static void glBindRenderbuffer(int target, int id) { org.lwjgl.opengles.GLES20.glBindRenderbuffer(target, id); }
    public static void glRenderbufferStorage(int target, int internalformat, int w, int h) { org.lwjgl.opengles.GLES20.glRenderbufferStorage(target, internalformat, w, h); }
}

package android.opengl;

/** 见 {@link GLES20}：桌面垫片的 ES 3.0 部分。 */
public class GLES30 extends GLES20 {
    public static final int GL_READ_BUFFER = 0x0C02;
    public static final int GL_RGBA8 = 0x8058;
    public static final int GL_RGB8 = 0x8051;
    public static final int GL_RGBA32F = 0x8814;
    public static final int GL_RGBA16F = 0x881A;
    public static final int GL_DEPTH_COMPONENT24 = 0x81A6;
    public static final int GL_DEPTH_COMPONENT32F = 0x8CAC;
    public static final int GL_DEPTH24_STENCIL8 = 0x88F0;
    public static final int GL_DEPTH_STENCIL_ATTACHMENT = 0x821A;
    public static final int GL_READ_FRAMEBUFFER = 0x8CA8;
    public static final int GL_DRAW_FRAMEBUFFER = 0x8CA9;
    public static final int GL_MAX_SAMPLES = 0x8D57;
    public static final int GL_TEXTURE_COMPARE_MODE = 0x884C;
    public static final int GL_TEXTURE_COMPARE_FUNC = 0x884D;
    public static final int GL_COMPARE_REF_TO_TEXTURE = 0x884E;
    public static final int GL_HALF_FLOAT = 0x140B;
    public static final int GL_RED = 0x1903;
    public static final int GL_R8 = 0x8229;

    public static void glGenVertexArrays(int n, int[] out, int offset) { for (int i = 0; i < n; i++) out[offset + i] = org.lwjgl.opengles.GLES30.glGenVertexArrays(); }
    public static void glDeleteVertexArrays(int n, int[] ids, int offset) { for (int i = 0; i < n; i++) org.lwjgl.opengles.GLES30.glDeleteVertexArrays(ids[offset + i]); }
    public static void glBindVertexArray(int id) { org.lwjgl.opengles.GLES30.glBindVertexArray(id); }
    public static void glVertexAttribDivisor(int index, int divisor) { org.lwjgl.opengles.GLES30.glVertexAttribDivisor(index, divisor); }
    public static void glDrawArraysInstanced(int mode, int first, int count, int instances) { org.lwjgl.opengles.GLES30.glDrawArraysInstanced(mode, first, count, instances); }
    public static void glVertexAttribIPointer(int index, int size, int type, int stride, int offset) { org.lwjgl.opengles.GLES30.glVertexAttribIPointer(index, size, type, stride, (long) offset); }
    public static void glBlitFramebuffer(int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask, int filter) {
        org.lwjgl.opengles.GLES30.glBlitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter);
    }
    public static void glRenderbufferStorageMultisample(int target, int samples, int internalformat, int w, int h) {
        org.lwjgl.opengles.GLES30.glRenderbufferStorageMultisample(target, samples, internalformat, w, h);
    }
    public static void glDrawBuffers(int n, int[] bufs, int offset) {
        int[] b = new int[n];
        System.arraycopy(bufs, offset, b, 0, n);
        org.lwjgl.opengles.GLES30.glDrawBuffers(b);
    }
    public static void glReadBuffer(int mode) { org.lwjgl.opengles.GLES30.glReadBuffer(mode); }
}

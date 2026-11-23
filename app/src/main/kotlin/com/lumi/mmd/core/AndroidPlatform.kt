package com.lumi.mmd.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.lumi.mmd.engine.image.ImageData
import com.lumi.mmd.engine.image.Images
import com.lumi.mmd.engine.image.PlatformImageDecoder
import com.lumi.mmd.engine.util.Log

/** 把引擎里与平台相关的两个出口（日志、PNG/JPEG/WebP 解码）接到 Android 上。 */
object AndroidPlatform {
    @Volatile private var installed = false

    fun install() {
        if (installed) return
        installed = true
        Log.sink = Log.Sink { level, tag, msg, t ->
            val full = if (t != null) msg + "\n" + android.util.Log.getStackTraceString(t) else msg
            android.util.Log.println(level.coerceIn(android.util.Log.VERBOSE, android.util.Log.ERROR), "Lumi/$tag", full)
        }
        Images.platformDecoder = PlatformImageDecoder { bytes, maxSize -> decode(bytes, maxSize) }
    }

    private fun decode(bytes: ByteArray, maxSize: Int): ImageData? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // 超过上限的大图在解码阶段就按 2 的幂降采样，省内存也省时间
        var sample = 1
        if (maxSize > 0) {
            while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
        }
        // 内存紧张时宁可把图再缩小一半，也不要把堆撑爆（像素缓冲算在堆里；安卓 8 之前位图本身也在堆里）
        val copies = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 1L else 2L
        while (sample < 32) {
            val out = (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) * 4L * copies
            if (out < 4L * 1024 * 1024 || com.lumi.mmd.engine.util.Memory.fits(out, 24L * 1024 * 1024)) break
            sample *= 2
        }
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        // 引擎需要非预乘 alpha 的原始像素
        opts.inPremultiplied = false
        opts.inScaled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            opts.inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
        }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (bmp.config != Bitmap.Config.ARGB_8888) {
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
            bmp.recycle()
            bmp = copy ?: return null
        }
        val img = ImageData.allocate(bmp.width, bmp.height)
        bmp.copyPixelsToBuffer(img.pixels)
        img.pixels.position(0)
        bmp.recycle()
        return img
    }
}

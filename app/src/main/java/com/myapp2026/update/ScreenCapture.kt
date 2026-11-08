package com.myapp2026.update

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ScreenCapture {
    fun capture(mp: MediaProjection): Bitmap? {
        val wm = MyApp.app.getSystemService(WindowManager::class.java) ?: return null
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        val w = m.widthPixels; val h = m.heightPixels; val dpi = m.densityDpi

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1)
        val vd = mp.createVirtualDisplay(
            "sc", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, Handler(Looper.getMainLooper())
        )

        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val rowPixels = plane.rowStride / plane.pixelStride
                val b = Bitmap.createBitmap(rowPixels, img.height, Bitmap.Config.ARGB_8888)
                b.copyPixelsFromBuffer(plane.buffer)
                bmp = if (rowPixels == w) b else Bitmap.createBitmap(b, 0, 0, w, h)
            } finally { img.close(); latch.countDown() }
        }, null)

        latch.await(5, TimeUnit.SECONDS)
        vd.release(); reader.close()
        return bmp
    }
}

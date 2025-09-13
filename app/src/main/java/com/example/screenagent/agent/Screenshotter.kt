package com.example.screenagent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Screenshotter(private val ctx: Context) {

    fun captureBase64(): String? {
        return try {
            Log.d("ScreenAgent", "Attempting to capture screenshot...")

            // Method 1: Try using AccessibilityService.takeScreenshot (API 30+)
            if (ctx is AccessibilityService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val executor = ctx.mainExecutor
                    var screenshotResultBitmap: Bitmap? = null
                    val latch = CountDownLatch(1)

                    ctx.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            try {
                                val hardwareBuffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                if (wrapped != null) {
                                    // Copy to software bitmap for JPEG compression
                                    screenshotResultBitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                                    wrapped.recycle()
                                }
                                hardwareBuffer.close()
                            } catch (t: Throwable) {
                                Log.e("ScreenAgent", "Error processing screenshot result", t)
                            } finally {
                                latch.countDown()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w("ScreenAgent", "AccessibilityService.takeScreenshot failed with code: $errorCode")
                            latch.countDown()
                        }
                    })

                    // Wait for the callback to complete (with timeout)
                    if (latch.await(5, TimeUnit.SECONDS)) {
                        if (screenshotResultBitmap != null) {
                            Log.d("ScreenAgent", "Screenshot captured using AccessibilityService.takeScreenshot")
                            return convertBitmapToBase64(screenshotResultBitmap!!)
                        } else {
                            Log.w("ScreenAgent", "Screenshot callback completed but bitmap is null")
                        }
                    } else {
                        Log.w("ScreenAgent", "Screenshot timeout")
                    }
                } catch (e: Exception) {
                    Log.w("ScreenAgent", "AccessibilityService.takeScreenshot invocation failed: ${e.message}")
                }
            }

            // Method 2: Create a test pattern instead of black screen (debug aid)
            try {
                val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val display = windowManager.defaultDisplay
                val metrics = DisplayMetrics()
                display.getRealMetrics(metrics)

                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val testBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                
                // Fill with a test pattern so it's obvious when fallbacks are used
                testBitmap.eraseColor(android.graphics.Color.RED)
                Log.w("ScreenAgent", "Using test pattern (screenshot methods failed)")
                return convertBitmapToBase64(testBitmap)
            } catch (e: Exception) {
                Log.e("ScreenAgent", "Test pattern fallback failed", e)
            }

            Log.w("ScreenAgent", "No screenshot method succeeded.")
            null
        } catch (e: Exception) {
            Log.e("ScreenAgent", "Failed to capture screenshot (general error)", e)
            null
        }
    }

    private fun convertBitmapToBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return b64
    }
}
package com.example.screenagent.agent

import android.content.Context
import android.graphics.*
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class OverlayHud(ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        // Ensure the overlay covers the entire screen including status bar area
        x = 0
        y = 0
    }

    private val root = FrameLayout(ctx)
    private val statusBar = TextView(ctx).apply {
        textSize = 16f
        setPadding(18, 18, 18, 18)
        setBackgroundColor(0x88000000.toInt())
        setTextColor(Color.WHITE)
        text = "Agent idle"
    }
    private val painter = AffordancePainter(ctx)

    // track attach state to avoid "view already added" crashes
    private var attached = false

    init {
        root.addView(
            painter,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            statusBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        safeAdd()
    }

    private fun safeAdd() {
        if (attached) return
        try {
            wm.addView(root, params)
            attached = true
        } catch (_: Throwable) {
            // ignore; will try again on next use
        }
    }

    fun destroy() {
        if (!attached) return
        try {
            wm.removeView(root)
        } catch (_: Throwable) {
        } finally {
            attached = false
        }
    }

    fun show(msg: String) {
        statusBar.post { statusBar.text = msg }
    }

    fun drawAffordances(list: List<Affordance>) {
        painter.setAffordances(list)
    }
    
    fun clearAffordances() {
        painter.setAffordances(emptyList())
    }

    private class AffordancePainter(ctx: Context) : View(ctx) {
        private val outlinePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.GREEN
        }
        private val boxFillPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = 0x2200FF00
        }
        private val labelBgPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = 0x88000000.toInt()
        }
        private val labelTextPaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val indexTextPaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val whitePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        private var items: List<Affordance> = emptyList()
        fun setAffordances(a: List<Affordance>) { items = a; postInvalidate() }
        
        private fun getStatusBarHeight(): Int {
            var result = 0
            val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) {
                result = context.resources.getDimensionPixelSize(resourceId)
            }
            return result
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            items.forEachIndexed { index, a ->
                if (a.bounds.size == 4) {
                    val (l, t, r, b) = a.bounds
                    // Debug: log the bounds to see what we're getting
                    android.util.Log.d("OverlayHud", "Drawing bounds for ${a.id}: left=$l, top=$t, right=$r, bottom=$b")
                    
                    // Get status bar height to adjust coordinates
                    val statusBarHeight = getStatusBarHeight()
                    android.util.Log.d("OverlayHud", "Status bar height: $statusBarHeight")
                    
                    // Adjust coordinates to account for status bar
                    val adjustedTop = t - statusBarHeight
                    val adjustedBottom = b - statusBarHeight
                    
                    val rect = RectF(l.toFloat(), adjustedTop.toFloat(), r.toFloat(), adjustedBottom.toFloat())

                    c.drawRect(rect, boxFillPaint)
                    c.drawRect(rect, outlinePaint)

                    val cx = (l + r) / 2f
                    val cy = adjustedTop + (adjustedBottom - adjustedTop) / 2f

                    val label = a.id
                    val tw = labelTextPaint.measureText(label)
                    val th = labelTextPaint.textSize
                    val padX = 10f; val padY = 8f
                    c.drawRoundRect(
                        cx - tw / 2 - padX, cy - th,
                        cx + tw / 2 + padX, cy + padY,
                        10f, 10f, labelBgPaint
                    )
                    c.drawText(label, cx - tw / 2, cy - 4f, labelTextPaint)

                    val badgeCx = l.toFloat() + 18f
                    val badgeCy = adjustedTop + 18f
                    c.drawCircle(badgeCx, badgeCy, 14f, whitePaint)
                    val idxText = "${index + 1}"
                    val idxTw = indexTextPaint.measureText(idxText)
                    c.drawText(idxText, badgeCx - idxTw / 2, badgeCy + indexTextPaint.textSize / 3, indexTextPaint)
                }
            }
        }
    }
}

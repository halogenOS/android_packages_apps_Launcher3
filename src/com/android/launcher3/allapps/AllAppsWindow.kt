/*
 * Copyright (C) 2026 The halogenOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.allapps

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.SurfaceControl
import android.view.View
import android.view.RoundedCorner
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import com.android.launcher3.Launcher
import com.android.launcher3.R

/**
 * Manages the separate window that hosts the all apps drawer.
 *
 * The window is a full-screen panel attached to the launcher activity.
 * Content positioning is controlled via [View.setTranslationY] on the root
 * view. Background blur is applied via [SurfaceControl.Transaction.setBackgroundBlurRadius]
 * and clipped to the visible drawer area via [SurfaceControl.Transaction.setCrop].
 */
class AllAppsWindow(private val launcher: Launcher) {

    private val windowManager = launcher.getSystemService(WindowManager::class.java)!!
    private val layoutParams = createLayoutParams()

    private var windowRootView: AllAppsDragLayer? = null
    var appsView: ActivityAllAppsContainerView<Launcher>? = null
    var dragLayer: AllAppsDragLayer? = null
        private set
    var isAttached = false
        private set
    private var screenHeight = 0
    private var screenWidth = 0
    private var screenCornerRadius = 0f
    private var maxCornerRadius = 0f
    private var strokeWidth = 0f
    private val borderDrawable = HighlightBorderDrawable()
    private val transaction = SurfaceControl.Transaction()
    private val cropRect = Rect()

    private fun createLayoutParams() = LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.MATCH_PARENT,
        LayoutParams.TYPE_APPLICATION_PANEL,
        LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or LayoutParams.FLAG_NOT_TOUCHABLE
                or LayoutParams.FLAG_NOT_FOCUSABLE
                or LayoutParams.FLAG_SPLIT_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply {
        title = "AllAppsDrawer"
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode =
            LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
    }

    fun attach(): ActivityAllAppsContainerView<Launcher> {
        screenHeight = getRealScreenHeight()
        screenWidth = launcher.deviceProfile.deviceProperties.widthPx
        screenCornerRadius = launcher.display
            ?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f
        maxCornerRadius = (56f * launcher.resources.displayMetrics.density)
            .coerceAtLeast(screenCornerRadius)

        val root = LayoutInflater.from(launcher)
            .inflate(R.layout.all_apps, null) as AllAppsDragLayer
        windowRootView = root
        dragLayer = root
        appsView = root.findViewById(R.id.apps_view)

        root.translationY = screenHeight.toFloat()
        strokeWidth = 1f * launcher.resources.displayMetrics.density
        root.foreground = borderDrawable

        val decorView = launcher.window?.decorView ?: return appsView!!
        val token = decorView.windowToken
        if (token != null) {
            addWindow(root, token)
        } else {
            decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    decorView.removeOnAttachStateChangeListener(this)
                    addWindow(root, decorView.windowToken ?: return)
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }

        return appsView!!
    }

    private fun addWindow(root: View, token: android.os.IBinder) {
        layoutParams.token = token
        windowManager.addView(root, layoutParams)
        isAttached = true
    }

    fun detach() {
        if (isAttached) {
            windowManager.removeViewImmediate(windowRootView)
            isAttached = false
        }
    }

    /**
     * Positions the drawer content based on transition progress.
     *
     * @param progress 0 = fully open, 1 = fully hidden
     */
    fun setProgress(progress: Float) {
        if (!isAttached) return
        val root = windowRootView ?: return

        root.translationY = progress * screenHeight

        // Apply blur and crop directly on the SurfaceControl every frame.
        // setCrop limits the blur to the visible drawer area only.
        val sc = root.viewRootImpl?.surfaceControl ?: return
        if (!sc.isValid) return

        if (progress >= 1f) {
            cropRect.set(0, 0, 0, 0)
            transaction
                .setBackgroundBlurRadius(sc, 0)
                .setCrop(sc, cropRect)
                .apply()
            return
        }

        val top = (progress * screenHeight).toInt()
        val cornerRadius = screenCornerRadius + (maxCornerRadius - screenCornerRadius) * progress
        borderDrawable.update(cornerRadius, strokeWidth)
        cropRect.set(0, top, screenWidth, screenHeight)
        transaction
            .setBackgroundBlurRadius(sc, BLUR_RADIUS)
            .setCrop(sc, cropRect)
            .setCornerRadius(sc, cornerRadius, cornerRadius, 0f, 0f)
            .apply()
    }

    fun setTouchable(touchable: Boolean) {
        if (!isAttached) return
        val currentlyTouchable = (layoutParams.flags and LayoutParams.FLAG_NOT_TOUCHABLE) == 0
        if (touchable == currentlyTouchable) return

        if (touchable) {
            layoutParams.flags = layoutParams.flags and
                (LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_NOT_FOCUSABLE).inv()
        } else {
            layoutParams.flags = layoutParams.flags or
                LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(windowRootView, layoutParams)
    }

    fun onDeviceProfileChanged() {
        screenHeight = getRealScreenHeight()
        screenWidth = launcher.deviceProfile.deviceProperties.widthPx
    }

    private fun getRealScreenHeight(): Int {
        val display = launcher.display ?: return launcher.deviceProfile.deviceProperties.heightPx
        val metrics = android.util.DisplayMetrics()
        display.getRealMetrics(metrics)
        return metrics.heightPixels
    }

    /**
     * Draws a rounded-rect outline whose brightness fades from top to bottom.
     * The top edge is brightest; the stroke fades as it curves down the sides.
     */
    private class HighlightBorderDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val path = Path()
        private val rect = RectF()
        private val radii = FloatArray(8)
        private var cornerRadius = 0f
        private var dirty = true

        fun update(radius: Float, strokeWidth: Float) {
            if (radius != cornerRadius || paint.strokeWidth != strokeWidth) {
                cornerRadius = radius
                paint.strokeWidth = strokeWidth
                dirty = true
                invalidateSelf()
            }
        }

        private fun rebuild() {
            val b = bounds
            val inset = paint.strokeWidth / 2f
            rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
            radii[0] = cornerRadius; radii[1] = cornerRadius
            radii[2] = cornerRadius; radii[3] = cornerRadius
            path.reset()
            path.addRoundRect(rect, radii, Path.Direction.CW)
            paint.shader = LinearGradient(
                0f, b.top.toFloat(),
                0f, b.top + cornerRadius * 2f,
                0x4DFFFFFF, 0x4D000000,
                Shader.TileMode.CLAMP
            )
            dirty = false
        }

        override fun draw(canvas: Canvas) {
            if (dirty) rebuild()
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val BLUR_RADIUS = 80
    }
}

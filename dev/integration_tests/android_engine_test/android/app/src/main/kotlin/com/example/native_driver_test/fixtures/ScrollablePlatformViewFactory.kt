// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

@file:Suppress("PackageName")

package com.example.android_engine_test.fixtures

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class ScrollablePlatformViewFactory(
    private val messenger: BinaryMessenger? = null
) : PlatformViewFactory(null) {
    override fun create(
        context: Context,
        viewId: Int,
        args: Any?
    ): PlatformView = RedGridSurfaceViewPlatformView(context, viewId, messenger)
}

private class RedGridSurfaceViewPlatformView(
    context: Context,
    viewId: Int,
    messenger: BinaryMessenger?
) : SurfaceView(context),
    PlatformView,
    SurfaceHolder.Callback,
    MethodChannel.MethodCallHandler {

    private val methodChannel: MethodChannel? = if (messenger != null) {
        MethodChannel(messenger, "scrollable_platform_view_$viewId").apply {
            setMethodCallHandler(this@RedGridSurfaceViewPlatformView)
        }
    } else null

    private val gridPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#D32F2F") // Red surface
    }

    private var offsetX = 0f
    private var offsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        holder.addCallback(this)
    }

    override fun getView(): View = this

    override fun dispose() {
        methodChannel?.setMethodCallHandler(null)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        drawGrid()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        drawGrid()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    private fun drawGrid() {
        val canvas = holder.lockCanvas() ?: return
        try {
            // Draw red surface
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

            // Draw grid lines offset by panning
            val gridSize = 100f
            var x = (offsetX % gridSize)
            while (x < width) {
                canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
                x += gridSize
            }

            var y = (offsetY % gridSize)
            while (y < height) {
                canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
                y += gridSize
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                offsetX += dx
                offsetY += dy
                lastTouchX = event.x
                lastTouchY = event.y
                drawGrid()

                // Notify Flutter of camera move on every touch frame!
                methodChannel?.invokeMethod(
                    "onCameraMove",
                    mapOf("posX" to offsetX, "posY" to offsetY)
                )
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "panBy" -> {
                val dx = (call.argument<Number>("dx") ?: 0).toFloat()
                val dy = (call.argument<Number>("dy") ?: 0).toFloat()
                offsetX += dx
                offsetY += dy
                drawGrid()
                methodChannel?.invokeMethod(
                    "onCameraMove",
                    mapOf("posX" to offsetX, "posY" to offsetY)
                )
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }
}

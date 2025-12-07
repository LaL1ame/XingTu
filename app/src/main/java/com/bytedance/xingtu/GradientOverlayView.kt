package com.bytedance.xingtu

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 自定义渐变覆盖层 View
 * 用于在轮播图上添加从透明到白色的渐变效果，增强视觉效果
 */
class GradientOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gradientPaint = Paint().apply {
        isAntiAlias = true
    }

    private var gradient: LinearGradient? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 创建从透明到白色的线性渐变（从上到下）
        gradient = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                0x00000000,  // 完全透明
                0x80FFFFFF,  // 半透明白色
                0xFFFFFFFF  // 完全不透明白色
            ),
            floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        gradientPaint.shader = gradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 绘制渐变覆盖层
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientPaint)
    }
}


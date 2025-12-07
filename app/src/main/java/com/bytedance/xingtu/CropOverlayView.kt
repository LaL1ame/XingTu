package com.bytedance.xingtu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 裁剪框覆盖层 View
 * 负责绘制裁剪框 UI 和处理用户交互
 * 不参与 OpenGL 渲染，仅作为覆盖层
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 裁剪框矩形（屏幕坐标）
    private var cropRect = RectF()
    
    // 裁剪框是否可见
    var isVisible = false
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            invalidate()
        }
    
    // 当前锁定的宽高比（null 表示自由比例）
    var aspectRatio: Float? = null
        set(value) {
            field = value
            // 如果 View 已布局，立即应用宽高比
            if (width > 0 && height > 0) {
                // 如果裁剪框还未初始化，先初始化
                if (cropRect.isEmpty || cropRect.width() <= 0 || cropRect.height() <= 0) {
                    // 先初始化裁剪框（不应用宽高比，因为下面会统一处理）
                    val padding = 50f
                    cropRect = RectF(
                        padding,
                        padding,
                        width - padding,
                        height - padding
                    )
                }
                // 如果设置了宽高比，应用它
                if (value != null) {
                    // 确保裁剪框已初始化后再应用宽高比
                    adjustToAspectRatio()
                    invalidate()
                } else {
                    // 清除宽高比限制时，刷新显示
                    invalidate()
                }
            }
        }
    
    // 触摸相关
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchMode = TouchMode.NONE
    
    // 触摸区域类型
    private enum class TouchMode {
        NONE,           // 无触摸
        MOVE,            // 移动整个裁剪框
        RESIZE_LEFT,     // 调整左边
        RESIZE_RIGHT,    // 调整右边
        RESIZE_TOP,      // 调整上边
        RESIZE_BOTTOM,   // 调整下边
        RESIZE_TOP_LEFT, // 调整左上角
        RESIZE_TOP_RIGHT,// 调整右上角
        RESIZE_BOTTOM_LEFT,  // 调整左下角
        RESIZE_BOTTOM_RIGHT  // 调整右下角
    }
    
    // 触摸区域大小（像素）
    private val touchAreaSize = 50f
    
    // 最小裁剪框尺寸
    private val minCropSize = 100f
    
    // 绘制相关
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80000000.toInt() // 半透明黑色遮罩
        style = Paint.Style.FILL
    }
    
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt() // 白色边框
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt() // 半透明白色网格
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    
    /**
     * 初始化裁剪框（覆盖整个 View）
     */
    fun initCropRect() {
        if (width <= 0 || height <= 0) {
            android.util.Log.w("CropOverlayView", "View size is 0, cannot init crop rect")
            return
        }
        val padding = 50f
        cropRect = RectF(
            padding,
            padding,
            width - padding,
            height - padding
        )
        if (aspectRatio != null) {
            adjustToAspectRatio()
        }
        invalidate()
    }
    
    /**
     * 根据宽高比调整裁剪框
     */
    private fun adjustToAspectRatio() {
        val ratio = aspectRatio ?: return
        if (width <= 0 || height <= 0) {
            return
        }
        
        // 如果裁剪框为空或无效，先初始化（但不在这里调用 initCropRect，避免递归）
        if (cropRect.isEmpty || cropRect.width() <= 0 || cropRect.height() <= 0) {
            // 直接初始化裁剪框，不调用 initCropRect 避免递归
            val padding = 50f
            cropRect = RectF(
                padding,
                padding,
                width - padding,
                height - padding
            )
            // 继续执行下面的逻辑来应用宽高比
        }
        
        val centerX = cropRect.centerX()
        val centerY = cropRect.centerY()
        val currentWidth = cropRect.width()
        val currentHeight = cropRect.height()
        
        if (currentWidth <= 0 || currentHeight <= 0) {
            return
        }
        
        val currentRatio = currentWidth / currentHeight
        
        var newWidth = currentWidth
        var newHeight = currentHeight
        
        if (currentRatio > ratio) {
            // 当前更宽，调整宽度
            newWidth = currentHeight * ratio
        } else {
            // 当前更高，调整高度
            newHeight = currentWidth / ratio
        }
        
        // 计算新的裁剪框，确保在边界内
        var newLeft = centerX - newWidth / 2
        var newTop = centerY - newHeight / 2
        var newRight = centerX + newWidth / 2
        var newBottom = centerY + newHeight / 2
        
        // 如果超出边界，调整位置和大小
        if (newLeft < 0) {
            val offset = -newLeft
            newLeft = 0f
            newRight += offset
        }
        if (newTop < 0) {
            val offset = -newTop
            newTop = 0f
            newBottom += offset
        }
        if (newRight > width) {
            val offset = newRight - width
            newRight = width.toFloat()
            newLeft -= offset
        }
        if (newBottom > height) {
            val offset = newBottom - height
            newBottom = height.toFloat()
            newTop -= offset
        }
        
        // 如果调整后仍然超出边界，需要缩小尺寸
        val actualWidth = newRight - newLeft
        val actualHeight = newBottom - newTop
        val actualRatio = actualWidth / actualHeight
        
        if (actualRatio != ratio) {
            // 需要根据实际可用空间重新计算
            val maxWidth = width.toFloat()
            val maxHeight = height.toFloat()
            val maxWidthByHeight = maxHeight * ratio
            val maxHeightByWidth = maxWidth / ratio
            
            if (maxWidthByHeight <= maxWidth) {
                // 高度受限
                newHeight = maxHeight
                newWidth = newHeight * ratio
            } else {
                // 宽度受限
                newWidth = maxWidth
                newHeight = newWidth / ratio
            }
            
            // 居中放置
            newLeft = (width - newWidth) / 2
            newTop = (height - newHeight) / 2
            newRight = newLeft + newWidth
            newBottom = newTop + newHeight
        }
        
        cropRect = RectF(newLeft, newTop, newRight, newBottom)
    }
    
    /**
     * 限制裁剪框在 View 边界内
     */
    private fun constrainToBounds() {
        if (width <= 0 || height <= 0) {
            return
        }
        cropRect.left = cropRect.left.coerceAtLeast(0f)
        cropRect.top = cropRect.top.coerceAtLeast(0f)
        cropRect.right = cropRect.right.coerceAtMost(width.toFloat())
        cropRect.bottom = cropRect.bottom.coerceAtMost(height.toFloat())
    }
    
    /**
     * 获取裁剪框矩形（屏幕坐标）
     */
    fun getCropRect(): RectF {
        if (cropRect.isEmpty && width > 0 && height > 0) {
            // 如果裁剪框为空但 View 已布局，初始化一个默认裁剪框
            initCropRect()
        }
        return RectF(cropRect)
    }
    
    /**
     * 设置裁剪框矩形（屏幕坐标）
     */
    fun setCropRect(rect: RectF) {
        cropRect = RectF(rect)
        constrainToBounds()
        // 如果设置了宽高比，需要重新应用
        if (aspectRatio != null) {
            adjustToAspectRatio()
        }
        invalidate()
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cropRect.isEmpty) {
            initCropRect()
        } else {
            constrainToBounds()
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (!isVisible || cropRect.isEmpty || width <= 0 || height <= 0) {
            return
        }
        
        // 绘制半透明遮罩（裁剪区域外的部分）
        drawOverlay(canvas)
        
        // 绘制裁剪框边框
        canvas.drawRect(cropRect, borderPaint)
        
        // 绘制 3x3 网格
        drawGrid(canvas)
        
        // 绘制四个角的控制点
        drawCorners(canvas)
    }
    
    /**
     * 绘制半透明遮罩
     */
    private fun drawOverlay(canvas: Canvas) {
        val path = Path()
        
        // 外框（整个 View）
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        
        // 内框（裁剪区域，需要排除）
        path.addRect(cropRect, Path.Direction.CCW)
        
        canvas.drawPath(path, overlayPaint)
    }
    
    /**
     * 绘制 3x3 网格
     */
    private fun drawGrid(canvas: Canvas) {
        val left = cropRect.left
        val top = cropRect.top
        val right = cropRect.right
        val bottom = cropRect.bottom
        val width = cropRect.width()
        val height = cropRect.height()
        
        // 垂直线（2条，分成3列）
        canvas.drawLine(left + width / 3, top, left + width / 3, bottom, gridPaint)
        canvas.drawLine(left + width * 2 / 3, top, left + width * 2 / 3, bottom, gridPaint)
        
        // 水平线（2条，分成3行）
        canvas.drawLine(left, top + height / 3, right, top + height / 3, gridPaint)
        canvas.drawLine(left, top + height * 2 / 3, right, top + height * 2 / 3, gridPaint)
    }
    
    /**
     * 绘制四个角的控制点
     */
    private fun drawCorners(canvas: Canvas) {
        val cornerSize = 20f
        
        // 左上角
        canvas.drawRect(
            cropRect.left - cornerSize / 2,
            cropRect.top - cornerSize / 2,
            cropRect.left + cornerSize / 2,
            cropRect.top + cornerSize / 2,
            cornerPaint
        )
        
        // 右上角
        canvas.drawRect(
            cropRect.right - cornerSize / 2,
            cropRect.top - cornerSize / 2,
            cropRect.right + cornerSize / 2,
            cropRect.top + cornerSize / 2,
            cornerPaint
        )
        
        // 左下角
        canvas.drawRect(
            cropRect.left - cornerSize / 2,
            cropRect.bottom - cornerSize / 2,
            cropRect.left + cornerSize / 2,
            cropRect.bottom + cornerSize / 2,
            cornerPaint
        )
        
        // 右下角
        canvas.drawRect(
            cropRect.right - cornerSize / 2,
            cropRect.bottom - cornerSize / 2,
            cropRect.right + cornerSize / 2,
            cropRect.bottom + cornerSize / 2,
            cornerPaint
        )
    }
    
    /**
     * 检测触摸点所在的区域
     */
    private fun getTouchMode(x: Float, y: Float): TouchMode {
        val left = cropRect.left
        val top = cropRect.top
        val right = cropRect.right
        val bottom = cropRect.bottom
        
        // 检查四个角
        if (abs(x - left) < touchAreaSize && abs(y - top) < touchAreaSize) {
            return TouchMode.RESIZE_TOP_LEFT
        }
        if (abs(x - right) < touchAreaSize && abs(y - top) < touchAreaSize) {
            return TouchMode.RESIZE_TOP_RIGHT
        }
        if (abs(x - left) < touchAreaSize && abs(y - bottom) < touchAreaSize) {
            return TouchMode.RESIZE_BOTTOM_LEFT
        }
        if (abs(x - right) < touchAreaSize && abs(y - bottom) < touchAreaSize) {
            return TouchMode.RESIZE_BOTTOM_RIGHT
        }
        
        // 检查四条边
        if (abs(x - left) < touchAreaSize && y >= top && y <= bottom) {
            return TouchMode.RESIZE_LEFT
        }
        if (abs(x - right) < touchAreaSize && y >= top && y <= bottom) {
            return TouchMode.RESIZE_RIGHT
        }
        if (abs(y - top) < touchAreaSize && x >= left && x <= right) {
            return TouchMode.RESIZE_TOP
        }
        if (abs(y - bottom) < touchAreaSize && x >= left && x <= right) {
            return TouchMode.RESIZE_BOTTOM
        }
        
        // 检查是否在裁剪框内部（移动）
        if (x >= left && x <= right && y >= top && y <= bottom) {
            return TouchMode.MOVE
        }
        
        return TouchMode.NONE
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isVisible) {
            return false
        }
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                touchMode = getTouchMode(event.x, event.y)
                return touchMode != TouchMode.NONE
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (touchMode == TouchMode.NONE) {
                    return false
                }
                
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                
                when (touchMode) {
                    TouchMode.MOVE -> {
                        // 移动整个裁剪框
                        cropRect.offset(dx, dy)
                        constrainToBounds()
                    }
                    
                    TouchMode.RESIZE_LEFT -> {
                        cropRect.left += dx
                        if (aspectRatio != null) {
                            adjustResizeWithAspectRatio(TouchMode.RESIZE_LEFT, dx, dy)
                        } else {
                            if (cropRect.width() < minCropSize) {
                                cropRect.left = cropRect.right - minCropSize
                            }
                        }
                        constrainToBounds()
                    }
                    
                    TouchMode.RESIZE_RIGHT -> {
                        cropRect.right += dx
                        if (aspectRatio != null) {
                            adjustResizeWithAspectRatio(TouchMode.RESIZE_RIGHT, dx, dy)
                        } else {
                            if (cropRect.width() < minCropSize) {
                                cropRect.right = cropRect.left + minCropSize
                            }
                        }
                        constrainToBounds()
                    }
                    
                    TouchMode.RESIZE_TOP -> {
                        cropRect.top += dy
                        if (aspectRatio != null) {
                            adjustResizeWithAspectRatio(TouchMode.RESIZE_TOP, dx, dy)
                        } else {
                            if (cropRect.height() < minCropSize) {
                                cropRect.top = cropRect.bottom - minCropSize
                            }
                        }
                        constrainToBounds()
                    }
                    
                    TouchMode.RESIZE_BOTTOM -> {
                        cropRect.bottom += dy
                        if (aspectRatio != null) {
                            adjustResizeWithAspectRatio(TouchMode.RESIZE_BOTTOM, dx, dy)
                        } else {
                            if (cropRect.height() < minCropSize) {
                                cropRect.bottom = cropRect.top + minCropSize
                            }
                        }
                        constrainToBounds()
                    }
                    
                    TouchMode.RESIZE_TOP_LEFT -> {
                        cropRect.left += dx
                        cropRect.top += dy
                        if (aspectRatio != null) {
                            adjustResizeWithAspectRatio(TouchMode.RESIZE_TOP_LEFT, dx, dy)
                        } else {
                            if (cropRect.width() < minCropSize) {
                                cropRect.left = cropRect.right - minCropSize
                            }
                            if (cropRect.height() < minCropSize) {
                                cropRect.top = cropRect.bottom - minCropSize
                            }
                        }
                        constrainToBounds()
                    }
                    
                    TouchMode.RESIZE_TOP_RIGHT -> {
                        cropRect.right += dx
                        cropRect.top += dy
                        if (aspectRatio != null) {
                            adjustResizeWithAspectRatio(TouchMode.RESIZE_TOP_RIGHT, dx, dy)
                        } else {
                            if (cropRect.width() < minCropSize) {
                                cropRect.right = cropRect.left + minCropSize
                            }
                            if (cropRect.height() < minCropSize) {
                                cropRect.top = cropRect.bottom - minCropSize
                            }
                        }
                        constrainToBounds()
                    }
                    
                    TouchMode.RESIZE_BOTTOM_LEFT -> {
                        cropRect.left += dx
                        cropRect.bottom += dy
                        if (aspectRatio != null) {
                            adjustResizeWithAspectRatio(TouchMode.RESIZE_BOTTOM_LEFT, dx, dy)
                        } else {
                            if (cropRect.width() < minCropSize) {
                                cropRect.left = cropRect.right - minCropSize
                            }
                            if (cropRect.height() < minCropSize) {
                                cropRect.bottom = cropRect.top + minCropSize
                            }
                        }
                        constrainToBounds()
                    }
                    
                    TouchMode.RESIZE_BOTTOM_RIGHT -> {
                        cropRect.right += dx
                        cropRect.bottom += dy
                        if (aspectRatio != null) {
                            adjustResizeWithAspectRatio(TouchMode.RESIZE_BOTTOM_RIGHT, dx, dy)
                        } else {
                            if (cropRect.width() < minCropSize) {
                                cropRect.right = cropRect.left + minCropSize
                            }
                            if (cropRect.height() < minCropSize) {
                                cropRect.bottom = cropRect.top + minCropSize
                            }
                        }
                        constrainToBounds()
                    }
                    
                    else -> {}
                }
                
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
                return true
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchMode = TouchMode.NONE
                return true
            }
        }
        
        return false
    }
    
    /**
     * 在保持宽高比的情况下调整大小
     */
    private fun adjustResizeWithAspectRatio(mode: TouchMode, dx: Float, dy: Float) {
        val ratio = aspectRatio ?: return
        val currentWidth = cropRect.width()
        val currentHeight = cropRect.height()
        
        when (mode) {
            TouchMode.RESIZE_LEFT, TouchMode.RESIZE_RIGHT -> {
                val newWidth = if (mode == TouchMode.RESIZE_LEFT) {
                    currentWidth - dx
                } else {
                    currentWidth + dx
                }
                val newHeight = newWidth / ratio
                val centerY = cropRect.centerY()
                
                if (mode == TouchMode.RESIZE_LEFT) {
                    cropRect.left = cropRect.right - newWidth
                } else {
                    cropRect.right = cropRect.left + newWidth
                }
                cropRect.top = centerY - newHeight / 2
                cropRect.bottom = centerY + newHeight / 2
            }
            
            TouchMode.RESIZE_TOP, TouchMode.RESIZE_BOTTOM -> {
                val newHeight = if (mode == TouchMode.RESIZE_TOP) {
                    currentHeight - dy
                } else {
                    currentHeight + dy
                }
                val newWidth = newHeight * ratio
                val centerX = cropRect.centerX()
                
                cropRect.left = centerX - newWidth / 2
                cropRect.right = centerX + newWidth / 2
                if (mode == TouchMode.RESIZE_TOP) {
                    cropRect.top = cropRect.bottom - newHeight
                } else {
                    cropRect.bottom = cropRect.top + newHeight
                }
            }
            
            TouchMode.RESIZE_TOP_LEFT -> {
                val newWidth = currentWidth - dx
                val newHeight = newWidth / ratio
                cropRect.left = cropRect.right - newWidth
                cropRect.top = cropRect.bottom - newHeight
            }
            
            TouchMode.RESIZE_TOP_RIGHT -> {
                val newWidth = currentWidth + dx
                val newHeight = newWidth / ratio
                cropRect.right = cropRect.left + newWidth
                cropRect.top = cropRect.bottom - newHeight
            }
            
            TouchMode.RESIZE_BOTTOM_LEFT -> {
                val newWidth = currentWidth - dx
                val newHeight = newWidth / ratio
                cropRect.left = cropRect.right - newWidth
                cropRect.bottom = cropRect.top + newHeight
            }
            
            TouchMode.RESIZE_BOTTOM_RIGHT -> {
                val newWidth = currentWidth + dx
                val newHeight = newWidth / ratio
                cropRect.right = cropRect.left + newWidth
                cropRect.bottom = cropRect.top + newHeight
            }
            
            else -> {}
        }
        
        // 确保最小尺寸
        if (cropRect.width() < minCropSize) {
            val centerX = cropRect.centerX()
            val newWidth = minCropSize
            val newHeight = newWidth / ratio
            cropRect.left = centerX - newWidth / 2
            cropRect.right = centerX + newWidth / 2
            cropRect.top = cropRect.centerY() - newHeight / 2
            cropRect.bottom = cropRect.centerY() + newHeight / 2
        }
    }
}






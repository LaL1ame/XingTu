package com.bytedance.xingtu

import android.graphics.Bitmap

/**
 * 编辑状态，用于 Undo/Redo
 */
data class EditState(
    val scale: Float,
    val translateX: Float,
    val translateY: Float,
    val rotation: Float,
    val cropRect: CropRect? = null,
    val bitmap: Bitmap? = null  // 裁剪前的图片（用于撤销裁剪操作）
)

/**
 * 裁剪区域
 */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * 操作历史管理器
 */
class EditHistoryManager {
    private val history = mutableListOf<EditState>()
    private var currentIndex = -1
    private val maxHistorySize = 50

    /**
     * 添加新状态
     */
    fun addState(state: EditState) {
        // 移除当前位置之后的所有状态（如果有新的操作）
        if (currentIndex < history.size - 1) {
            history.subList(currentIndex + 1, history.size).clear()
        }
        
        history.add(state)
        currentIndex = history.size - 1
        
        // 限制历史记录大小
        if (history.size > maxHistorySize) {
            history.removeAt(0)
            currentIndex--
        }
    }

    /**
     * 撤销
     */
    fun undo(): EditState? {
        if (canUndo()) {
            currentIndex--
            return history[currentIndex]
        }
        return null
    }

    /**
     * 重做
     */
    fun redo(): EditState? {
        if (canRedo()) {
            currentIndex++
            return history[currentIndex]
        }
        return null
    }

    /**
     * 是否可以撤销
     */
    fun canUndo(): Boolean = currentIndex > 0

    /**
     * 是否可以重做
     */
    fun canRedo(): Boolean = currentIndex < history.size - 1

    /**
     * 清空历史
     */
    fun clear() {
        history.clear()
        currentIndex = -1
    }

    /**
     * 获取当前状态
     */
    fun getCurrentState(): EditState? {
        return if (currentIndex >= 0 && currentIndex < history.size) {
            history[currentIndex]
        } else {
            null
        }
    }
}




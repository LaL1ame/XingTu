package com.bytedance.xingtu

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ImageRenderer(private val context: Context) : GLSurfaceView.Renderer {

    // 用于在主线程执行回调
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    /**
     * 获取主线程 Handler（用于从 GL 线程切换回主线程）
     */
    fun getMainHandler(): android.os.Handler {
        return mainHandler
    }
    
    /**
     * 获取当前图片宽度
     */
    fun getImageWidth(): Int = imageWidth
    
    /**
     * 获取当前图片高度
     */
    fun getImageHeight(): Int = imageHeight

    private var textureId = 0
    private var imageWidth = 0
    private var imageHeight = 0
    private var pendingBitmap: Bitmap? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // 帧捕获相关
    @Volatile
    private var shouldCaptureFrame = false
    private var captureCallback: ((Bitmap?) -> Unit)? = null

    // FBO (Framebuffer Object) 离屏渲染相关
    private var offscreenFBO: Int = 0
    private var isFBOInitialized = false

    // 变换矩阵
    private val mvpMatrix = FloatArray(16) // Model-View-Projection 矩阵
    private var scale = 1.0f
    private var translateX = 0.0f
    private var translateY = 0.0f
    private var rotation = 0.0f

    // 初始缩放比例（适应屏幕）
    private var initialScale = 1.0f

    // 是否自动适应屏幕（用于控制裁剪后的图片是否自动缩放）
    private var shouldAutoFit = true

    // 裁剪相关（已弃用，保留用于兼容）
    @Deprecated("使用 CropOverlayView 替代")
    var cropRect: CropRect? = null
        set(value) {
            field = value
            updateMatrix()
        }

    @Deprecated("使用 CropOverlayView 替代")
    var showCropOverlay = false
        set(value) {
            field = value
        }

    // 顶点着色器代码（支持变换矩阵）
    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    // 片段着色器代码
    private val fragmentShaderCode = """
        precision mediump float;
        uniform sampler2D uTexture;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """.trimIndent()

    // 顶点坐标（全屏四边形）
    private val vertices = floatArrayOf(
        -1.0f, -1.0f, 0.0f,  // 左下
        1.0f, -1.0f, 0.0f,  // 右下
        -1.0f,  1.0f, 0.0f,  // 左上
        1.0f,  1.0f, 0.0f   // 右上
    )

    // 纹理坐标
    private val texCoords = floatArrayOf(
        0.0f, 1.0f,  // 左下
        1.0f, 1.0f,  // 右下
        0.0f, 0.0f,  // 左上
        1.0f, 0.0f   // 右上
    )

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureHandle = 0
    private var mvpMatrixHandle = 0

    init {
        // 初始化顶点缓冲区
        val vertexByteBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
        vertexByteBuffer.order(ByteOrder.nativeOrder())
        vertexBuffer = vertexByteBuffer.asFloatBuffer()
        vertexBuffer?.put(vertices)
        vertexBuffer?.position(0)

        // 初始化纹理坐标缓冲区
        val texCoordByteBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
        texCoordByteBuffer.order(ByteOrder.nativeOrder())
        texCoordBuffer = texCoordByteBuffer.asFloatBuffer()
        texCoordBuffer?.put(texCoords)
        texCoordBuffer?.position(0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // 设置背景色为黑色
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // 编译着色器
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        // 创建程序
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        // 获取属性位置
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")

        // 创建纹理
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        // 初始化矩阵
        Matrix.setIdentityM(mvpMatrix, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        surfaceWidth = width
        surfaceHeight = height

        // 清理旧的 FBO（如果尺寸变化，需要重新创建）
        if (isFBOInitialized) {
            cleanupOffscreenFBO()
        }

        // 只有在需要自动适应时才更新缩放
        if (shouldAutoFit && imageWidth > 0 && imageHeight > 0) {
            updateInitialScale(width, height)
        }
    }

    /**
     * 更新初始缩放比例
     */
    @Synchronized
    fun updateInitialScale(width: Int, height: Int) {
        if (imageWidth > 0 && imageHeight > 0 && width > 0 && height > 0) {
            // 计算旋转后的有效宽高（90度或270度旋转时，宽高互换）
            val effectiveWidth: Float
            val effectiveHeight: Float
            val rotationMod = (rotation % 360f + 360f) % 360f
            if (rotationMod == 90f || rotationMod == 270f) {
                // 旋转90度或270度，宽高互换
                effectiveWidth = imageHeight.toFloat()
                effectiveHeight = imageWidth.toFloat()
            } else {
                // 0度或180度，宽高不变
                effectiveWidth = imageWidth.toFloat()
                effectiveHeight = imageHeight.toFloat()
            }

            val scaleX = width.toFloat() / effectiveWidth
            val scaleY = height.toFloat() / effectiveHeight
            initialScale = scaleX.coerceAtMost(scaleY) * 0.9f // 留一点边距
            if (scale == 1.0f || scale == initialScale) { // 只在初始状态下更新，或者当前是初始缩放
                scale = initialScale
                updateMatrix()
            }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        // 清除颜色缓冲区
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 如果有待加载的位图，在 OpenGL 线程上加载纹理
        synchronized(this) {
            pendingBitmap?.let { bitmap ->
                imageWidth = bitmap.width
                imageHeight = bitmap.height
                loadTexture(bitmap)
                pendingBitmap = null
            }
        }

        // 如果还没有加载纹理，不绘制
        if (textureId == 0) {
            return
        }

        // 使用程序
        GLES20.glUseProgram(program)

        // 设置变换矩阵
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        // 绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        // 启用顶点属性
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(
            positionHandle, 3, GLES20.GL_FLOAT, false,
            0, vertexBuffer
        )

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(
            texCoordHandle, 2, GLES20.GL_FLOAT, false,
            0, texCoordBuffer
        )

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 禁用顶点属性
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        // 如果需要捕获帧，在渲染完成后立即捕获
        if (shouldCaptureFrame) {
            shouldCaptureFrame = false
            // 确保所有渲染命令完成
            GLES20.glFinish()
            val capturedBitmap = captureFrameInternal()
            android.util.Log.d("ImageRenderer", "Frame captured: ${capturedBitmap != null}, size: ${capturedBitmap?.width}x${capturedBitmap?.height}")

            // 在主线程上执行回调
            val callback = captureCallback
            captureCallback = null
            if (callback != null) {
                mainHandler.post {
                    android.util.Log.d("ImageRenderer", "Invoking capture callback on main thread")
                    callback(capturedBitmap)
                }
            }
        }

        // 裁剪框已移至 CropOverlayView，不再在 OpenGL 中绘制
    }

    // 旧的裁剪框绘制代码已移除，现在使用 CropOverlayView 作为覆盖层

    @Synchronized
    fun loadImage(bitmap: Bitmap, autoFit: Boolean = true) {
        try {
            // 直接使用传入的 Bitmap（不复制）
            // 注意：loadTexture 不会回收 Bitmap，由调用者管理生命周期
            pendingBitmap = bitmap
            imageWidth = bitmap.width
            imageHeight = bitmap.height
            // 保存自动适应标志
            shouldAutoFit = autoFit
            // 重置变换
            scale = 1.0f
            translateX = 0.0f
            translateY = 0.0f
            rotation = 0.0f
            cropRect = null // 清除裁剪框
            // 如果 surface 已经创建，根据 autoFit 参数决定是否自动适应屏幕
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                if (autoFit) {
                    updateInitialScale(surfaceWidth, surfaceHeight)
                } else {
                    // 不进行等比例缩放适应屏幕，保持图片原始宽高比
                    // 计算缩放比例，使图片以原始像素尺寸显示，保持宽高比
                    // OpenGL 坐标系：(-1, -1) 到 (1, 1)，宽度和高度都是 2
                    // 将图片像素尺寸转换为 OpenGL 坐标尺寸
                    val scaleX = (imageWidth.toFloat() / surfaceWidth.toFloat()) * 2.0f
                    val scaleY = (imageHeight.toFloat() / surfaceHeight.toFloat()) * 2.0f

                    // 使用相同的缩放比例（取较小的），确保图片保持宽高比且完全可见
                    // 这样 1:1 的图片会以 1:1 显示，不会被拉伸
                    scale = scaleX.coerceAtMost(scaleY)

                    // 居中显示（translateX 和 translateY 保持为 0，因为 OpenGL 坐标原点在中心）
                    translateX = 0.0f
                    translateY = 0.0f

                    // 立即更新矩阵（这会应用宽高比补偿）
                    updateMatrix()

                    android.util.Log.d("ImageRenderer", "Load image without autoFit: ${imageWidth}x${imageHeight}, scale=$scale, screen=${surfaceWidth}x${surfaceHeight}")
                }
            }
            android.util.Log.d("ImageRenderer", "Image loaded: ${bitmap.width}x${bitmap.height}, autoFit=$autoFit")
        } catch (e: Exception) {
            android.util.Log.e("ImageRenderer", "Error loading image", e)
        }
    }

    /**
     * 更新变换矩阵
     */
    private fun updateMatrix() {
        Matrix.setIdentityM(mvpMatrix, 0)

        // 计算旋转后的有效宽高（90度或270度旋转时，宽高互换）
        val effectiveWidth: Float
        val effectiveHeight: Float
        val rotationMod = (rotation % 360f + 360f) % 360f
        if (rotationMod == 90f || rotationMod == 270f) {
            // 旋转90度或270度，宽高互换
            effectiveWidth = imageHeight.toFloat()
            effectiveHeight = imageWidth.toFloat()
        } else {
            // 0度或180度，宽高不变
            effectiveWidth = imageWidth.toFloat()
            effectiveHeight = imageHeight.toFloat()
        }

        // 根据旋转后的图片和屏幕的宽高比调整，避免拉伸
        // 无论是 autoFit 还是非 autoFit，都需要应用宽高比补偿，因为 OpenGL 视口是屏幕宽高比
        if (effectiveWidth > 0 && effectiveHeight > 0 && surfaceWidth > 0 && surfaceHeight > 0) {
            // 计算旋转后的图片和屏幕的宽高比
            val imageAspect = effectiveWidth / effectiveHeight
            val screenAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()

            android.util.Log.d("ImageRenderer", "updateMatrix: rotation=$rotation, effectiveSize=${effectiveWidth}x${effectiveHeight}, imageAspect=$imageAspect, screenAspect=$screenAspect, autoFit=$shouldAutoFit")

            // 如果宽高比不同，需要调整缩放以保持图片宽高比
            // 先应用宽高比修正，再应用用户缩放
            // 注意：OpenGL 视口是屏幕宽高比，顶点是正方形，所以需要补偿
            if (imageAspect > screenAspect) {
                // 图片更宽（相对于屏幕），屏幕更窄
                // 需要压缩 Y 轴，使图片在屏幕上保持宽高比
                val aspectCorrection = screenAspect / imageAspect
                Matrix.scaleM(mvpMatrix, 0, 1.0f, aspectCorrection, 1.0f)
                android.util.Log.d("ImageRenderer", "Image wider than screen, Y correction: $aspectCorrection")
            } else {
                // 图片更高（相对于屏幕），屏幕更宽
                // 需要压缩 X 轴，使图片在屏幕上保持宽高比
                val aspectCorrection = imageAspect / screenAspect
                Matrix.scaleM(mvpMatrix, 0, aspectCorrection, 1.0f, 1.0f)
                android.util.Log.d("ImageRenderer", "Image taller than screen, X correction: $aspectCorrection")
            }
        }

        // 应用变换：先旋转，再缩放，最后平移
        // 注意：变换顺序很重要，先旋转可以确保缩放基于旋转后的尺寸
        Matrix.translateM(mvpMatrix, 0, translateX, translateY, 0.0f)
        Matrix.scaleM(mvpMatrix, 0, scale, scale, 1.0f)
        Matrix.rotateM(mvpMatrix, 0, rotation, 0.0f, 0.0f, 1.0f)
    }

    /**
     * 设置缩放
     */
    @Synchronized
    fun setScale(newScale: Float) {
        scale = newScale.coerceIn(0.1f, 5.0f) // 限制缩放范围
        updateMatrix()
    }

    /**
     * 缩放增量
     */
    @Synchronized
    fun scaleBy(factor: Float) {
        scale = (scale * factor).coerceIn(0.1f, 5.0f)
        updateMatrix()
    }

    /**
     * 设置平移
     */
    @Synchronized
    fun setTranslate(dx: Float, dy: Float) {
        translateX += dx
        translateY += dy
        updateMatrix()
    }

    /**
     * 设置旋转
     */
    @Synchronized
    fun setRotation(angle: Float) {
        rotation = angle
        updateMatrix()
    }

    /**
     * 旋转增量
     */
    @Synchronized
    fun rotateBy(angle: Float) {
        rotation = (rotation + angle) % 360.0f
        updateMatrix()
    }

    /**
     * 重置所有变换
     */
    @Synchronized
    fun resetTransform() {
        scale = initialScale
        translateX = 0.0f
        translateY = 0.0f
        rotation = 0.0f
        cropRect = null
        updateMatrix()
    }

    /**
     * 获取当前编辑状态
     */
    @Synchronized
    fun getEditState(): EditState {
        return EditState(scale, translateX, translateY, rotation, cropRect)
    }
    
    /**
     * 获取当前旋转角度
     */
    @Synchronized
    fun getRotation(): Float {
        return rotation
    }
    
    /**
     * 获取旋转后的实际显示尺寸（考虑旋转90度或270度时宽高互换）
     */
    @Synchronized
    fun getEffectiveSize(): Pair<Int, Int> {
        val rotationMod = (rotation % 360f + 360f) % 360f
        return if (rotationMod == 90f || rotationMod == 270f) {
            Pair(imageHeight, imageWidth)
        } else {
            Pair(imageWidth, imageHeight)
        }
    }

    /**
     * 应用编辑状态
     */
    @Synchronized
    fun applyEditState(state: EditState) {
        scale = state.scale
        translateX = state.translateX
        translateY = state.translateY
        rotation = state.rotation
        cropRect = state.cropRect
        updateMatrix()
    }

    /**
     * 将屏幕坐标转换为原始图像像素坐标
     * @param screenX 屏幕 X 坐标
     * @param screenY 屏幕 Y 坐标
     * @param viewWidth View 宽度
     * @param viewHeight View 高度
     * @return 原始图像中的像素坐标 (x, y)，如果坐标在图像外返回 null
     */
    @Synchronized
    fun screenToImageCoordinates(
        screenX: Float,
        screenY: Float,
        viewWidth: Int,
        viewHeight: Int
    ): Pair<Float, Float>? {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return null
        }

        // 1. 屏幕坐标转换为 OpenGL 标准化坐标 (-1 到 1)
        // OpenGL 坐标系统：左下角 (-1, -1)，右上角 (1, 1)
        val glX = (screenX / viewWidth) * 2.0f - 1.0f
        val glY = 1.0f - (screenY / viewHeight) * 2.0f  // Y 轴翻转（屏幕左上角对应 OpenGL 左下角）

        // 2. 应用逆变换矩阵（撤销当前的平移、旋转、缩放）
        // 注意：变换顺序是 平移 -> 旋转 -> 缩放，所以逆变换是 逆缩放 -> 逆旋转 -> 逆平移
        val inverseMatrix = FloatArray(16)
        val success = Matrix.invertM(inverseMatrix, 0, mvpMatrix, 0)
        if (!success) {
            android.util.Log.e("ImageRenderer", "Failed to invert matrix")
            return null
        }

        // 将 OpenGL 坐标转换为齐次坐标
        val glPoint = floatArrayOf(glX, glY, 0.0f, 1.0f)
        val transformedPoint = FloatArray(4)
        Matrix.multiplyMV(transformedPoint, 0, inverseMatrix, 0, glPoint, 0)

        // 3. OpenGL 标准化坐标转换为纹理坐标 (0 到 1)
        // 纹理坐标系统：左下角 (0, 1)，右上角 (1, 0)
        val texX = (transformedPoint[0] + 1.0f) / 2.0f
        val texY = (transformedPoint[1] + 1.0f) / 2.0f

        // 4. 纹理坐标转换为图像像素坐标
        // 图像坐标系统：左上角 (0, 0)，右下角 (width, height)
        val imgX = texX * imageWidth
        val imgY = (1.0f - texY) * imageHeight  // Y 轴翻转（纹理左下角，图像左上角）

        // 检查是否在图像范围内（允许稍微超出边界，因为浮点数精度问题）
        val margin = 1.0f
        if (imgX < -margin || imgX > imageWidth + margin || imgY < -margin || imgY > imageHeight + margin) {
            return null
        }

        return Pair(imgX.coerceIn(0f, imageWidth.toFloat()), imgY.coerceIn(0f, imageHeight.toFloat()))
    }

    /**
     * 将屏幕矩形转换为原始图像像素矩形
     * @param screenRect 屏幕坐标矩形 (left, top, right, bottom)
     * @param viewWidth View 宽度
     * @param viewHeight View 高度
     * @return 原始图像中的像素矩形 (left, top, right, bottom)，如果无效返回 null
     */
    @Synchronized
    fun screenRectToImageRect(
        screenRect: android.graphics.RectF,
        viewWidth: Int,
        viewHeight: Int
    ): android.graphics.RectF? {
        val topLeft = screenToImageCoordinates(screenRect.left, screenRect.top, viewWidth, viewHeight)
        val bottomRight = screenToImageCoordinates(screenRect.right, screenRect.bottom, viewWidth, viewHeight)

        if (topLeft == null || bottomRight == null) {
            return null
        }

        val left = topLeft.first.coerceIn(0f, imageWidth.toFloat())
        val top = topLeft.second.coerceIn(0f, imageHeight.toFloat())
        val right = bottomRight.first.coerceIn(0f, imageWidth.toFloat())
        val bottom = bottomRight.second.coerceIn(0f, imageHeight.toFloat())

        if (right <= left || bottom <= top) {
            return null
        }

        return android.graphics.RectF(left, top, right, bottom)
    }

    /**
     * 应用裁剪到图片（返回裁剪后的 Bitmap）
     * 新方法：使用屏幕坐标矩形
     * @param bitmap 原始图片
     * @param screenRect 屏幕坐标矩形
     * @param viewWidth View 宽度
     * @param viewHeight View 高度
     * @return 裁剪后的 Bitmap
     */
    fun applyCropFromScreenRect(
        bitmap: Bitmap,
        screenRect: android.graphics.RectF,
        viewWidth: Int,
        viewHeight: Int
    ): Bitmap? {
        try {
            // 检查 Bitmap 是否已被回收
            if (bitmap.isRecycled) {
                android.util.Log.e("ImageRenderer", "Bitmap is recycled, cannot crop")
                return null
            }

            // 转换屏幕坐标到图像坐标
            val imageRect = screenRectToImageRect(screenRect, viewWidth, viewHeight)
                ?: return null

            val cropLeft = imageRect.left.toInt().coerceIn(0, bitmap.width)
            val cropTop = imageRect.top.toInt().coerceIn(0, bitmap.height)
            val cropRight = imageRect.right.toInt().coerceIn(0, bitmap.width)
            val cropBottom = imageRect.bottom.toInt().coerceIn(0, bitmap.height)

            val cropWidth = cropRight - cropLeft
            val cropHeight = cropBottom - cropTop

            if (cropWidth <= 0 || cropHeight <= 0) {
                android.util.Log.w("ImageRenderer", "Invalid crop dimensions: width=$cropWidth, height=$cropHeight")
                return null
            }

            android.util.Log.d("ImageRenderer", "Crop from screen: screenRect=$screenRect, imageRect=$imageRect, crop=$cropLeft,$cropTop,$cropWidth,$cropHeight")

            try {
                val cropped = Bitmap.createBitmap(
                    bitmap,
                    cropLeft,
                    cropTop,
                    cropWidth,
                    cropHeight
                )
                android.util.Log.d("ImageRenderer", "Cropped bitmap created: ${cropped.width} x ${cropped.height}")
                return cropped
            } catch (e: IllegalArgumentException) {
                android.util.Log.e("ImageRenderer", "Error creating cropped bitmap: ${e.message}", e)
                return null
            } catch (e: Exception) {
                android.util.Log.e("ImageRenderer", "Error creating cropped bitmap", e)
                return null
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageRenderer", "Error in applyCropFromScreenRect", e)
            return null
        }
    }

    /**
     * 应用裁剪到图片（返回裁剪后的 Bitmap）
     * 旧方法（已弃用）：裁剪框坐标是 OpenGL 标准化坐标（-1 到 1），需要转换为图片像素坐标
     */
    @Deprecated("使用 applyCropFromScreenRect 替代")
    fun applyCrop(bitmap: Bitmap): Bitmap? {
        try {
            val rect = cropRect ?: return bitmap

            if (imageWidth <= 0 || imageHeight <= 0) {
                android.util.Log.e("ImageRenderer", "Invalid image dimensions: $imageWidth x $imageHeight")
                return bitmap
            }

            // 将 OpenGL 坐标（-1 到 1）转换为纹理坐标（0 到 1）
            // OpenGL 坐标：左下角是 (-1, -1)，右上角是 (1, 1)
            // 纹理坐标：左下角是 (0, 1)，右上角是 (1, 0)
            val texLeft = ((rect.left + 1f) / 2f).coerceIn(0f, 1f)
            val texRight = ((rect.right + 1f) / 2f).coerceIn(0f, 1f)
            val texBottom = ((rect.bottom + 1f) / 2f).coerceIn(0f, 1f)
            val texTop = ((rect.top + 1f) / 2f).coerceIn(0f, 1f)

            // 纹理坐标转换为图片像素坐标
            // 注意：纹理坐标的 Y 轴方向与图片相反（纹理 0,0 在左下，图片 0,0 在左上）
            val imgLeft = (texLeft * imageWidth).toInt().coerceIn(0, imageWidth)
            val imgRight = (texRight * imageWidth).toInt().coerceIn(0, imageWidth)
            val imgTop = ((1f - texTop) * imageHeight).toInt().coerceIn(0, imageHeight)
            val imgBottom = ((1f - texBottom) * imageHeight).toInt().coerceIn(0, imageHeight)

            // 确保裁剪区域有效
            val cropWidth = imgRight - imgLeft
            val cropHeight = imgBottom - imgTop

            android.util.Log.d("ImageRenderer", "Crop: left=$imgLeft, top=$imgTop, right=$imgRight, bottom=$imgBottom, width=$cropWidth, height=$cropHeight")

            if (cropWidth > 0 && cropHeight > 0 && imgLeft < imageWidth && imgTop < imageHeight) {
                try {
                    val cropped = Bitmap.createBitmap(
                        bitmap,
                        imgLeft,
                        imgTop,
                        cropWidth,
                        cropHeight
                    )
                    android.util.Log.d("ImageRenderer", "Cropped bitmap created: ${cropped.width} x ${cropped.height}")
                    return cropped
                } catch (e: IllegalArgumentException) {
                    android.util.Log.e("ImageRenderer", "Invalid crop parameters: left=$imgLeft, top=$imgTop, width=$cropWidth, height=$cropHeight, bitmap=${bitmap.width}x${bitmap.height}", e)
                    return bitmap
                } catch (e: Exception) {
                    android.util.Log.e("ImageRenderer", "Error creating cropped bitmap", e)
                    return bitmap
                }
            } else {
                android.util.Log.w("ImageRenderer", "Invalid crop dimensions: width=$cropWidth, height=$cropHeight")
                return bitmap
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageRenderer", "Error in applyCrop", e)
            return bitmap
        }
    }

    /**
     * 从 OpenGL 帧缓冲区读取像素并生成 Bitmap
     * 注意：必须在 OpenGL 线程上调用，且在当前帧渲染完成后调用
     */
    /**
     * 请求捕获当前帧（异步）
     * 会在下一次 onDrawFrame 完成后调用回调
     */
    fun requestCaptureFrame(callback: (Bitmap?) -> Unit) {
        if (textureId == 0 || surfaceWidth == 0 || surfaceHeight == 0) {
            android.util.Log.w("ImageRenderer", "Cannot request capture: textureId=$textureId, size=${surfaceWidth}x${surfaceHeight}")
            callback(null)
            return
        }
        android.util.Log.d("ImageRenderer", "Requesting frame capture, size: ${surfaceWidth}x${surfaceHeight}")
        shouldCaptureFrame = true
        captureCallback = callback
    }

    /**
     * 内部方法：在 onDrawFrame 中调用，渲染完成后立即捕获
     */
    private fun captureFrameInternal(): Bitmap? {
        if (textureId == 0 || surfaceWidth == 0 || surfaceHeight == 0) {
            android.util.Log.w("ImageRenderer", "Cannot capture: textureId=$textureId, size=${surfaceWidth}x${surfaceHeight}")
            return null
        }

        try {
            // 读取像素数据（glFinish 已在 onDrawFrame 中调用）
            val buffer = ByteBuffer.allocate(surfaceWidth * surfaceHeight * 4)
            buffer.order(ByteOrder.nativeOrder())

            // 清除之前的错误
            GLES20.glGetError()

            GLES20.glReadPixels(
                0, 0, surfaceWidth, surfaceHeight,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
            )

            // 检查是否有错误
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                android.util.Log.e("ImageRenderer", "OpenGL error during glReadPixels: $error")
                return null
            }

            // 检查缓冲区是否有数据（检查前几个像素是否全为0）
            buffer.rewind()
            val firstPixel = buffer.getInt()
            if (firstPixel == 0) {
                android.util.Log.w("ImageRenderer", "Warning: First pixel is 0, may be reading empty buffer")
            }
            buffer.rewind()

            // 创建 Bitmap（注意 OpenGL 的坐标原点在左下角，需要翻转）
            val bitmap = Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ARGB_8888)
            buffer.rewind()

            // 读取 RGBA 数据并转换为 ARGB
            val pixels = IntArray(surfaceWidth * surfaceHeight)
            for (i in pixels.indices) {
                val r = buffer.get().toInt() and 0xFF
                val g = buffer.get().toInt() and 0xFF
                val b = buffer.get().toInt() and 0xFF
                val a = buffer.get().toInt() and 0xFF
                // 转换为 ARGB 格式
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }

            // 翻转 Y 轴（OpenGL 坐标原点在左下，Bitmap 在左上）
            val flippedPixels = IntArray(surfaceWidth * surfaceHeight)
            for (y in 0 until surfaceHeight) {
                for (x in 0 until surfaceWidth) {
                    flippedPixels[(surfaceHeight - 1 - y) * surfaceWidth + x] = pixels[y * surfaceWidth + x]
                }
            }

            bitmap.setPixels(flippedPixels, 0, surfaceWidth, 0, 0, surfaceWidth, surfaceHeight)
            return bitmap
        } catch (e: Exception) {
            android.util.Log.e("ImageRenderer", "Error capturing frame", e)
            return null
        }
    }

    /**
     * 直接捕获当前帧（已弃用，使用 requestCaptureFrame 替代）
     * 注意：此方法可能在某些设备上读取到空缓冲区
     */
    @Deprecated("使用 requestCaptureFrame 替代，确保在渲染完成后捕获")
    fun captureFrame(): Bitmap? {
        return captureFrameInternal()
    }

    private fun loadTexture(bitmap: Bitmap) {
        // 绑定纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

        // 设置纹理参数
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        // 加载位图到纹理
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        // 注意：不要在这里回收 Bitmap
        // 因为传入的 Bitmap 可能是原始 Bitmap（用于裁剪），需要由调用者管理生命周期
        // 如果传入的是副本，调用者会在适当时机回收
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    /**
     * 清理离屏 FBO 资源
     */
    private fun cleanupOffscreenFBO() {
        if (offscreenFBO != 0) {
            val framebuffers = intArrayOf(offscreenFBO)
            GLES20.glDeleteFramebuffers(1, framebuffers, 0)
            offscreenFBO = 0
        }
        isFBOInitialized = false
    }

    /**
     * 使用 FBO 进行离屏渲染并导出 Bitmap（高保真方案）
     * * @param outputWidth 输出图片宽度（例如原始图片宽度）
     * @param outputHeight 输出图片高度（例如原始图片高度）
     * @return 渲染后的 Bitmap，失败返回 null
     */
    @Synchronized
    fun renderToBitmapWithFBO(outputWidth: Int, outputHeight: Int): Bitmap? {
        if (textureId == 0 || imageWidth == 0 || imageHeight == 0) {
            android.util.Log.e("ImageRenderer", "Cannot render to FBO: textureId=$textureId, imageSize=${imageWidth}x${imageHeight}")
            return null
        }

        if (outputWidth <= 0 || outputHeight <= 0) {
            android.util.Log.e("ImageRenderer", "Invalid output size: ${outputWidth}x${outputHeight}")
            return null
        }

        android.util.Log.d("ImageRenderer", "===== Starting FBO off-screen rendering =====")
        android.util.Log.d("ImageRenderer", "Output size: ${outputWidth}x${outputHeight}, image size: ${imageWidth}x${imageHeight}")

        // 临时变量，用于存储 FBO 和纹理 ID，以便在 cleanup 时使用
        var fboId = 0
        var targetTextureId = 0
        val frameBuffer = IntArray(1)
        val renderTargetTexture = IntArray(1)

        // 保存当前 OpenGL 状态（在 try 块外定义，以便在 catch 中使用）
        val oldViewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, oldViewport, 0)
        val oldFramebuffer = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, oldFramebuffer, 0)

        try {

            // 1. 创建新纹理作为 FBO 的渲染目标
            GLES20.glGenTextures(1, renderTargetTexture, 0)
            targetTextureId = renderTargetTexture[0]

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targetTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // 分配纹理存储空间
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                outputWidth, outputHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )

            // 2. 创建 FBO
            GLES20.glGenFramebuffers(1, frameBuffer, 0)
            fboId = frameBuffer[0]

            // 3. 绑定 FBO
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)

            // 4. 将新纹理绑定到 FBO 的颜色附件
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                targetTextureId,
                0
            )

            // 5. 检查 FBO 完整性
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                android.util.Log.e("ImageRenderer", "FBO not complete! Status: 0x${Integer.toHexString(status)}")
                throw RuntimeException("FBO not complete!") // 跳到 catch 进行清理
            }

            // 6. 设置视口并清除（使用透明背景，避免黑色边框）
            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f) // 透明背景
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            
            // 启用混合以支持透明背景
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            // 7. 计算输出尺寸对应的变换矩阵
            // 输出尺寸应该等于图片旋转后的实际尺寸，不需要宽高比补偿
            val outputMVP = FloatArray(16)
            Matrix.setIdentityM(outputMVP, 0)

            // 计算旋转后的有效尺寸
            val effectiveWidth: Float
            val effectiveHeight: Float
            val rotationMod = (rotation % 360f + 360f) % 360f
            if (rotationMod == 90f || rotationMod == 270f) {
                effectiveWidth = imageHeight.toFloat()
                effectiveHeight = imageWidth.toFloat()
            } else {
                effectiveWidth = imageWidth.toFloat()
                effectiveHeight = imageHeight.toFloat()
            }
            
            // 只应用旋转，不应用缩放和平移，直接导出完整的原始图片
            // 输出尺寸已经等于旋转后的图片尺寸，所以只需要旋转即可
            Matrix.rotateM(outputMVP, 0, rotation, 0.0f, 0.0f, 1.0f)

            // 8. 使用程序并设置参数
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, outputMVP, 0)

            // 9. 绑定原始纹理 (修复点：确保采样的是图片纹理, 而不是 FBO 的目标纹理)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId) // <-- 确保绑定的是源图片纹理
            GLES20.glUniform1i(textureHandle, 0)

            // 10. 启用顶点属性并绘制
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(
                positionHandle, 3, GLES20.GL_FLOAT, false,
                0, vertexBuffer
            )

            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(
                texCoordHandle, 2, GLES20.GL_FLOAT, false,
                0, texCoordBuffer
            )

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // 禁用顶点属性
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)

            // 11. 确保绘制完成 (真机上很重要)
            GLES20.glFlush()
            GLES20.glFinish()

            // 检查绘制错误
            val drawError = GLES20.glGetError()
            if (drawError != GLES20.GL_NO_ERROR) {
                android.util.Log.e("ImageRenderer", "OpenGL error after glDrawArrays: 0x${Integer.toHexString(drawError)}")
            }

            // 12. 从 FBO 读取像素数据
            val bufferSize = outputWidth * outputHeight * 4
            val rgbaBuf = ByteBuffer.allocateDirect(bufferSize)
            rgbaBuf.order(ByteOrder.nativeOrder())

            GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1) // 确保像素对齐
            GLES20.glReadPixels(
                0, 0, outputWidth, outputHeight,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgbaBuf
            )

            val readError = GLES20.glGetError()
            if (readError != GLES20.GL_NO_ERROR) {
                android.util.Log.e("ImageRenderer", "OpenGL error after glReadPixels: 0x${Integer.toHexString(readError)}")
                throw RuntimeException("glReadPixels failed")
            }

            // 13. 恢复 OpenGL 状态
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, oldFramebuffer[0])
            GLES20.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3])

            // 14. 清理 FBO 和临时纹理
            GLES20.glDeleteFramebuffers(1, frameBuffer, 0)
            GLES20.glDeleteTextures(1, renderTargetTexture, 0)

            // 15. 转换像素数据为 Bitmap (并翻转 Y 轴)
            rgbaBuf.rewind()
            val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(outputWidth * outputHeight)

            for (y in 0 until outputHeight) {
                val destY = outputHeight - 1 - y // Y 轴翻转
                for (x in 0 until outputWidth) {
                    val r = rgbaBuf.get().toInt() and 0xFF
                    val g = rgbaBuf.get().toInt() and 0xFF
                    val b = rgbaBuf.get().toInt() and 0xFF
                    val a = rgbaBuf.get().toInt() and 0xFF
                    // 转换为 ARGB 格式
                    val pixel = (a shl 24) or (r shl 16) or (g shl 8) or b
                    pixels[destY * outputWidth + x] = pixel
                }
            }

            bitmap.setPixels(pixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)

            android.util.Log.d("ImageRenderer", "===== FBO rendering completed successfully =====")
            return bitmap

        } catch (e: Exception) {
            android.util.Log.e("ImageRenderer", "Error in renderToBitmapWithFBO", e)
            e.printStackTrace()

            // 确保清理资源
            if (fboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            if (targetTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(targetTextureId), 0)

            // 恢复 FBO 绑定和视口
            try {
                // 仅在 FBO 设置成功前抛出异常时需要恢复
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, oldFramebuffer[0])
                GLES20.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3])
            } catch (e2: Exception) {
                android.util.Log.e("ImageRenderer", "Error restoring state", e2)
            }

            return null
        }
    }
}

package com.bytedance.xingtu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bytedance.xingtu.databinding.ActivityEditorBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var renderer: ImageRenderer
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    
    // 操作历史
    private val historyManager = EditHistoryManager()
    private var originalBitmap: Bitmap? = null  // 当前用于裁剪的图片（可能已被裁剪）
    private var initialBitmap: Bitmap? = null  // 初始加载的图片（用于重置）
    
    // 触摸手势
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    
    // 裁剪相关（新实现）
    private var isCropMode = false
    private var currentCropRatio: Float? = null // null 表示自由裁剪
    
    // 权限请求后的操作类型
    private var pendingAction: (() -> Unit)? = null

    private val albumLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                loadImageFromUri(uri)
            }
        }
    }

    private val writePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        android.util.Log.e("EditorActivity", "===== Permission result callback: isGranted=$isGranted =====")
        System.out.println("EditorActivity: Permission result: $isGranted")
        if (isGranted) {
            android.util.Log.e("EditorActivity", "Permission granted, executing pending action")
            pendingAction?.invoke()
            pendingAction = null
        } else {
            android.util.Log.e("EditorActivity", "Permission denied by user")
            System.out.println("EditorActivity: Permission denied")
            Toast.makeText(this, "需要存储权限才能保存图片，请在设置中授予权限", Toast.LENGTH_LONG).show()
            pendingAction = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 立即输出日志确认 onCreate 被调用
        android.util.Log.e("EditorActivity", "========================================")
        android.util.Log.e("EditorActivity", "===== onCreate() CALLED ======")
        android.util.Log.e("EditorActivity", "========================================")
        System.out.println("========================================")
        System.out.println("EditorActivity: onCreate() CALLED")
        System.out.println("========================================")
        
        enableEdgeToEdge()
        
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 设置 OpenGL ES 版本
        binding.glSurfaceView.setEGLContextClientVersion(2)
        
        // 创建渲染器
        renderer = ImageRenderer(this)
        binding.glSurfaceView.setRenderer(renderer)
        binding.glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        
        // 设置触摸监听
        setupTouchHandlers()

        // 选择图片按钮 - 跳转到我们自己的相册页面
        binding.btnSelectImage.setOnClickListener {
            val intent = Intent(this, AlbumActivity::class.java).apply {
                putExtra("from_editor", true)
            }
            albumLauncher.launch(intent)
        }

        // Undo 按钮
        binding.btnUndo.setOnClickListener {
            historyManager.undo()?.let { state ->
                // 如果状态中包含裁剪前的图片，需要恢复图片
                state.bitmap?.let { bitmapBeforeCrop ->
                    // 恢复裁剪前的图片
                    if (!bitmapBeforeCrop.isRecycled) {
                        // 回收当前的 originalBitmap
                        originalBitmap?.recycle()
                        // 恢复裁剪前的图片到 originalBitmap
                        originalBitmap = bitmapBeforeCrop.copy(bitmapBeforeCrop.config ?: Bitmap.Config.ARGB_8888, false)
                        // 加载到渲染器（使用 autoFit=true 自动适应屏幕）
                        val renderBitmap = originalBitmap!!.copy(originalBitmap!!.config ?: Bitmap.Config.ARGB_8888, false)
                        renderer.loadImage(renderBitmap, autoFit = true)
                        // 等待一帧，确保图片加载完成后再应用状态
                        binding.glSurfaceView.post {
                            renderer.updateInitialScale(binding.glSurfaceView.width, binding.glSurfaceView.height)
                            // 应用变换状态（缩放、平移、旋转等）
                            renderer.applyEditState(state)
                            binding.glSurfaceView.requestRender()
                        }
                    } else {
                        // 如果图片已被回收，只应用变换状态
                        renderer.applyEditState(state)
                        binding.glSurfaceView.requestRender()
                    }
                } ?: run {
                    // 如果没有图片需要恢复，只应用变换状态（普通撤销操作）
                    renderer.applyEditState(state)
                    binding.glSurfaceView.requestRender()
                }
                updateUndoRedoButtons()
            }
        }

        // Redo 按钮
        binding.btnRedo.setOnClickListener {
            historyManager.redo()?.let { state ->
                // 如果状态中包含图片，需要恢复图片（用于重做裁剪操作）
                state.bitmap?.let { bitmapToRestore ->
                    // 恢复图片
                    if (!bitmapToRestore.isRecycled) {
                        // 回收当前的 originalBitmap
                        originalBitmap?.recycle()
                        // 恢复图片到 originalBitmap
                        originalBitmap = bitmapToRestore.copy(bitmapToRestore.config ?: Bitmap.Config.ARGB_8888, false)
                        // 加载到渲染器（使用 autoFit=true 自动适应屏幕）
                        val renderBitmap = originalBitmap!!.copy(originalBitmap!!.config ?: Bitmap.Config.ARGB_8888, false)
                        renderer.loadImage(renderBitmap, autoFit = true)
                        // 等待一帧，确保图片加载完成后再应用状态
                        binding.glSurfaceView.post {
                            renderer.updateInitialScale(binding.glSurfaceView.width, binding.glSurfaceView.height)
                            // 应用变换状态（缩放、平移、旋转等）
                            renderer.applyEditState(state)
                            binding.glSurfaceView.requestRender()
                        }
                    } else {
                        // 如果图片已被回收，只应用变换状态
                        renderer.applyEditState(state)
                        binding.glSurfaceView.requestRender()
                    }
                } ?: run {
                    // 如果没有图片需要恢复，只应用变换状态（普通重做操作）
                    renderer.applyEditState(state)
                    binding.glSurfaceView.requestRender()
                }
                updateUndoRedoButtons()
            }
        }

        // 裁剪按钮
        binding.btnCrop.setOnClickListener {
            toggleCropMode()
        }

        // 裁剪比例按钮
        binding.btnCropRatioFree.setOnClickListener {
            setCropRatio(null)
        }
        findViewById<android.widget.Button>(R.id.btnCropRatio1_1).setOnClickListener {
            setCropRatio(1.0f)
        }
        findViewById<android.widget.Button>(R.id.btnCropRatio3_4).setOnClickListener {
            setCropRatio(3.0f / 4.0f)
        }
        findViewById<android.widget.Button>(R.id.btnCropRatio9_16).setOnClickListener {
            setCropRatio(9.0f / 16.0f)
        }

        // 应用裁剪
        binding.btnCropApply.setOnClickListener {
            applyCropNew()
        }

        // 取消裁剪
        binding.btnCropCancel.setOnClickListener {
            cancelCrop()
        }

        // 重置按钮
        binding.btnReset.setOnClickListener {
            // 恢复到初始图片
            initialBitmap?.let { initial ->
                if (!initial.isRecycled) {
                    // 回收当前的 originalBitmap
                    originalBitmap?.recycle()
                    // 恢复初始图片
                    originalBitmap = initial.copy(initial.config ?: Bitmap.Config.ARGB_8888, false)
                    // 加载到渲染器
                    val renderBitmap = originalBitmap!!.copy(originalBitmap!!.config ?: Bitmap.Config.ARGB_8888, false)
                    renderer.loadImage(renderBitmap, autoFit = true)
                    // 重置变换
                    renderer.resetTransform()
                    binding.glSurfaceView.post {
                        renderer.updateInitialScale(binding.glSurfaceView.width, binding.glSurfaceView.height)
                        saveState() // 保存重置后的状态
                        binding.glSurfaceView.requestRender()
                    }
                } else {
                    // 如果初始图片已被回收，只重置变换
                    renderer.resetTransform()
                    saveState()
                    binding.glSurfaceView.requestRender()
                }
            } ?: run {
                // 如果没有初始图片，只重置变换
                renderer.resetTransform()
                saveState()
                binding.glSurfaceView.requestRender()
            }
        }

        // 旋转按钮
        binding.btnRotate.setOnClickListener {
            renderer.rotateBy(90.0f)
            saveState()
            binding.glSurfaceView.requestRender()
        }
        
        // 初始化按钮状态
        updateUndoRedoButtons()

        // 导出按钮
        android.util.Log.e("EditorActivity", "Setting up export button listener...")
        try {
            binding.btnExport.setOnClickListener {
                // 使用多种方式确保日志输出（在 try-catch 之前）
                try {
                    android.util.Log.e("EditorActivity", "========================================")
                    android.util.Log.e("EditorActivity", "===== EXPORT BUTTON CLICKED ======")
                    android.util.Log.e("EditorActivity", "===== Thread: ${Thread.currentThread().name} ======")
                    android.util.Log.e("EditorActivity", "========================================")
                    System.out.println("========================================")
                    System.out.println("EditorActivity: EXPORT BUTTON CLICKED")
                    System.out.println("Thread: ${Thread.currentThread().name}")
                    System.out.println("========================================")
                    
                    // 立即显示 Toast 确认按钮被点击（已经在主线程，不需要 runOnUiThread）
                    Toast.makeText(this@EditorActivity, "导出按钮被点击，开始导出...", Toast.LENGTH_SHORT).show()
                    
                    android.util.Log.e("EditorActivity", "About to call checkWritePermissionAndExport()...")
                    checkWritePermissionAndExport()
                    android.util.Log.e("EditorActivity", "checkWritePermissionAndExport() returned")
                } catch (e: Exception) {
                    android.util.Log.e("EditorActivity", "ERROR in export button click handler", e)
                    System.out.println("EditorActivity ERROR in click: ${e.message}")
                    e.printStackTrace()
                    runOnUiThread {
                        Toast.makeText(this@EditorActivity, "导出出错: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            android.util.Log.e("EditorActivity", "Export button listener set successfully")
            
            // 验证按钮状态
            binding.btnExport.post {
                android.util.Log.e("EditorActivity", "Export button state: enabled=${binding.btnExport.isEnabled}, " +
                        "visible=${binding.btnExport.visibility == View.VISIBLE}, " +
                        "clickable=${binding.btnExport.isClickable}, " +
                        "width=${binding.btnExport.width}, height=${binding.btnExport.height}, " +
                        "alpha=${binding.btnExport.alpha}")
                
                // 强制确保按钮可点击
                binding.btnExport.isEnabled = true
                binding.btnExport.isClickable = true
                binding.btnExport.visibility = View.VISIBLE
                
                // 添加触摸监听器作为备用（真机上可能有触摸事件拦截）
                binding.btnExport.setOnTouchListener { view, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            android.util.Log.e("EditorActivity", "===== Export button TOUCH DOWN detected =====")
                            System.out.println("EditorActivity: Export button TOUCH DOWN")
                            false // 返回 false 让点击事件继续传递
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            android.util.Log.e("EditorActivity", "===== Export button TOUCH UP detected =====")
                            System.out.println("EditorActivity: Export button TOUCH UP")
                            false
                        }
                        else -> false
                    }
                }
                
                android.util.Log.e("EditorActivity", "Export button setup completed")
            }
        } catch (e: Exception) {
            android.util.Log.e("EditorActivity", "ERROR setting export button listener", e)
            e.printStackTrace()
        }

        

        // 返回按钮
        binding.btnBack.setOnClickListener {
            finish()
        }

        // 如果从 Intent 传递了图片 URI，直接加载
        intent?.data?.let { uri ->
            loadImageFromUri(uri)
        }
    }

    private fun loadImageFromUri(uri: Uri) {
        coroutineScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    inputStream?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            bitmap?.let { bmp ->
                // 回收旧的原始 Bitmap（如果有）
                originalBitmap?.recycle()
                initialBitmap?.recycle()
                // 保存初始 Bitmap（用于重置）
                initialBitmap = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
                // 保存原始 Bitmap 用于裁剪（不回收）
                originalBitmap = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
                // 创建副本用于渲染（renderer 不会回收，但为了安全起见创建副本）
                val renderBitmap: Bitmap = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
                renderer.loadImage(renderBitmap)
                // 渲染用的副本会在不再需要时由 GC 回收
                historyManager.clear()
                // 更新初始缩放比例
                binding.glSurfaceView.post {
                    renderer.updateInitialScale(binding.glSurfaceView.width, binding.glSurfaceView.height)
                    saveState() // 保存初始状态
                }
                binding.glSurfaceView.requestRender()
            }
        }
    }
    
    /**
     * 保存当前状态到历史记录
     * @param bitmapBeforeCrop 裁剪前的图片（如果当前操作是裁剪，需要传入裁剪前的图片）
     */
    private fun saveState(bitmapBeforeCrop: Bitmap? = null) {
        val state = renderer.getEditState().copy(bitmap = bitmapBeforeCrop)
        historyManager.addState(state)
        updateUndoRedoButtons()
    }
    
    /**
     * 更新 Undo/Redo 按钮状态
     */
    private fun updateUndoRedoButtons() {
        binding.btnUndo.isEnabled = historyManager.canUndo()
        binding.btnRedo.isEnabled = historyManager.canRedo()
    }
    
    /**
     * 切换裁剪模式（新实现）
     */
    private fun toggleCropMode() {
        isCropMode = !isCropMode
        if (isCropMode) {
            binding.cropRatioLayout.visibility = View.VISIBLE
            binding.cropOverlayView.isVisible = true
            // 等待 View 布局完成后再初始化裁剪框
            binding.cropOverlayView.post {
                if (binding.cropOverlayView.width > 0 && binding.cropOverlayView.height > 0) {
                    binding.cropOverlayView.initCropRect()
                    binding.cropOverlayView.aspectRatio = currentCropRatio
                }
            }
        } else {
            binding.cropRatioLayout.visibility = View.GONE
            binding.cropOverlayView.isVisible = false
        }
    }
    
    /**
     * 设置裁剪比例（新实现）
     */
    private fun setCropRatio(ratio: Float?) {
        currentCropRatio = ratio
        if (binding.cropOverlayView.isVisible) {
            binding.cropOverlayView.aspectRatio = ratio
        }
    }
    
    /**
     * 应用裁剪（新实现）
     */
    private fun applyCropNew() {
        try {
            if (!binding.cropOverlayView.isVisible) {
                Toast.makeText(this, "请先进入裁剪模式", Toast.LENGTH_SHORT).show()
                return
            }
            
            originalBitmap?.let { bitmap ->
                // 获取裁剪框屏幕坐标
                val cropRect = binding.cropOverlayView.getCropRect()
                
                if (cropRect.isEmpty || cropRect.width() <= 0 || cropRect.height() <= 0) {
                    Toast.makeText(this, "请先设置裁剪区域", Toast.LENGTH_SHORT).show()
                    return
                }
                
                // ✅ 保存裁剪前的状态（包含图片和变换）到历史记录（用于撤销）
                val bitmapBeforeCrop = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                val stateBeforeCrop = renderer.getEditState().copy(bitmap = bitmapBeforeCrop)
                
                coroutineScope.launch {
                    try {
                        // 显示加载提示
                        Toast.makeText(this@EditorActivity, "正在裁剪...", Toast.LENGTH_SHORT).show()
                        
                        val croppedBitmap = withContext(Dispatchers.IO) {
                            try {
                                val viewWidth = binding.glSurfaceView.width
                                val viewHeight = binding.glSurfaceView.height
                                
                                if (viewWidth <= 0 || viewHeight <= 0) {
                                    android.util.Log.e("EditorActivity", "Invalid view size: $viewWidth x $viewHeight")
                                    return@withContext null
                                }
                                
                                // 检查原始 Bitmap 是否可用
                                if (bitmap.isRecycled) {
                                    android.util.Log.e("EditorActivity", "Original bitmap is recycled")
                                    return@withContext null
                                }
                                
                                // 使用新的坐标转换方法进行裁剪
                                renderer.applyCropFromScreenRect(
                                    bitmap,
                                    cropRect,
                                    viewWidth,
                                    viewHeight
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("EditorActivity", "Error in applyCropNew", e)
                                e.printStackTrace()
                                null
                            }
                        }
                        
                        croppedBitmap?.let { cropped ->
                            // 更新原始位图为裁剪后的图片
                            // 回收旧的 Bitmap
                            originalBitmap?.recycle()
                            // 创建新的副本用于后续裁剪
                            originalBitmap = cropped.copy(cropped.config ?: Bitmap.Config.ARGB_8888, false)
                            
                            // 在主线程上更新 UI
                            withContext(Dispatchers.Main) {
                                try {
                                    // 清除裁剪状态
                                    binding.cropOverlayView.isVisible = false
                                    binding.cropRatioLayout.visibility = View.GONE
                                    isCropMode = false
                                    
                                    // 加载裁剪后的图片到渲染器（使用副本）
                                    // 不进行等比例缩放，直接以原始尺寸居中显示
                                    val renderBitmap: Bitmap = cropped.copy(cropped.config ?: Bitmap.Config.ARGB_8888, false)
                                    renderer.loadImage(renderBitmap, autoFit = false)
                                    
                                    // 不需要更新缩放，直接居中显示
                                    binding.glSurfaceView.post {
                                        try {
                                            // ✅ 先保存裁剪前的状态（包含裁剪前的图片和变换）
                                            historyManager.addState(stateBeforeCrop)
                                            // ✅ 然后保存裁剪后的状态（包含裁剪后的图片，用于重做）
                                            val bitmapAfterCrop = cropped.copy(cropped.config ?: Bitmap.Config.ARGB_8888, false)
                                            val stateAfterCrop = renderer.getEditState().copy(bitmap = bitmapAfterCrop)
                                            historyManager.addState(stateAfterCrop)
                                            updateUndoRedoButtons()
                                            binding.glSurfaceView.requestRender()
                                        } catch (e: Exception) {
                                            android.util.Log.e("EditorActivity", "Error updating UI", e)
                                        }
                                    }
                                    
                                    binding.glSurfaceView.requestRender()
                                    Toast.makeText(this@EditorActivity, "裁剪完成", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    android.util.Log.e("EditorActivity", "Error updating UI", e)
                                    e.printStackTrace()
                                    Toast.makeText(this@EditorActivity, "裁剪完成，但更新显示时出错: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        } ?: run {
                            Toast.makeText(this@EditorActivity, "裁剪失败，请重试", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("EditorActivity", "Error in applyCropNew coroutine", e)
                        Toast.makeText(this@EditorActivity, "裁剪时发生错误: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } ?: run {
                Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("EditorActivity", "Error in applyCropNew", e)
            Toast.makeText(this, "裁剪时发生错误", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 取消裁剪（新实现）
     */
    private fun cancelCrop() {
        binding.cropOverlayView.isVisible = false
        binding.cropRatioLayout.visibility = View.GONE
        isCropMode = false
    }
    
    private fun setupTouchHandlers() {
        // 缩放手势检测器
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // 裁剪模式下，裁剪框 View 会处理缩放，这里不拦截
                if (!isCropMode) {
                    val scaleFactor = detector.scaleFactor
                    renderer.scaleBy(scaleFactor)
                    binding.glSurfaceView.requestRender()
                }
                return !isCropMode
            }
        })
        
        // 触摸事件处理
        binding.glSurfaceView.setOnTouchListener { _, event ->
            // 裁剪模式下，让裁剪框 View 处理触摸事件
            if (isCropMode) {
                // 裁剪框 View 会处理触摸，这里不拦截
                false
            } else {
                handleNormalTouch(event)
                true
            }
        }
    }
    
    private fun handleNormalTouch(event: MotionEvent) {
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && event.pointerCount == 1) {
                    // 单指平移
                    val dx = (event.x - lastTouchX) / binding.glSurfaceView.width * 2.0f
                    val dy = -(event.y - lastTouchY) / binding.glSurfaceView.height * 2.0f // Y轴翻转
                    renderer.setTranslate(dx, dy)
                    binding.glSurfaceView.requestRender()
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    saveState()
                }
                isDragging = false
            }
        }
        
        // 处理缩放手势
        scaleGestureDetector.onTouchEvent(event)
    }
    
    // handleCropTouch 已移除，裁剪框 View 自己处理触摸事件

    override fun onResume() {
        super.onResume()
        binding.glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.glSurfaceView.onPause()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 回收 Bitmap 资源
        originalBitmap?.recycle()
        originalBitmap = null
        initialBitmap?.recycle()
        initialBitmap = null
    }

    private fun checkWritePermissionAndExport() {
        android.util.Log.e("EditorActivity", "===== checkWritePermissionAndExport() called =====")
        android.util.Log.e("EditorActivity", "SDK Version: ${Build.VERSION.SDK_INT}, Code: ${Build.VERSION_CODES.P}")
        System.out.println("EditorActivity: checkWritePermissionAndExport called, SDK=${Build.VERSION.SDK_INT}")
        
        try {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES // Android 13+ 不需要写权限
            } else {
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            }

            android.util.Log.d("EditorActivity", "Checking permission: $permission")
            val hasPermission = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            android.util.Log.d("EditorActivity", "Permission check result: hasPermission=$hasPermission")

            if (hasPermission) {
                android.util.Log.d("EditorActivity", "Permission granted, calling exportImage()")
                exportImage()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Android 13+ 不需要写权限，直接导出
                    android.util.Log.d("EditorActivity", "Android 13+, exporting without permission")
                    exportImage()
                } else {
                    android.util.Log.d("EditorActivity", "Requesting permission: $permission")
                    pendingAction = { 
                        android.util.Log.d("EditorActivity", "Pending action executed after permission granted")
                        exportImage() 
                    }
                    writePermissionLauncher.launch(permission)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("EditorActivity", "ERROR in checkWritePermissionAndExport()", e)
            e.printStackTrace()
            Toast.makeText(this, "权限检查出错: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportImage() {
        android.util.Log.e("EditorActivity", "===== exportImage() called =====")
        System.out.println("EditorActivity: exportImage() called")
        
        try {
            // 检查 GLSurfaceView 状态
            android.util.Log.d("EditorActivity", "GLSurfaceView visibility: ${binding.glSurfaceView.visibility}, " +
                    "width: ${binding.glSurfaceView.width}, height: ${binding.glSurfaceView.height}")
            
            // 检查渲染器状态
            if (!::renderer.isInitialized) {
                android.util.Log.e("EditorActivity", "Renderer not initialized!")
                Toast.makeText(this, "渲染器未初始化", Toast.LENGTH_SHORT).show()
                return
            }
            
            // ✅ 终极修复：使用 queueEvent() 确保在 GL 渲染线程上执行
            // 强制将 renderToBitmapWithFBO 的执行封装到 GL 渲染线程中
            binding.glSurfaceView.queueEvent {
                android.util.Log.e("EditorActivity", "===== In OpenGL thread (queueEvent), starting FBO rendering =====")
                System.out.println("EditorActivity: In OpenGL thread, starting FBO rendering")
                
                try {
                    // 获取旋转后的实际显示尺寸（避免黑色边框）
                    val (effectiveWidth, effectiveHeight) = renderer.getEffectiveSize()
                    val outputWidth = effectiveWidth
                    val outputHeight = effectiveHeight
                    
                    android.util.Log.e("EditorActivity", "FBO render: outputSize=${outputWidth}x${outputHeight}, " +
                            "imageSize=${renderer.getImageWidth()}x${renderer.getImageHeight()}")
                    System.out.println("EditorActivity: FBO render - output: ${outputWidth}x${outputHeight}, " +
                            "image: ${renderer.getImageWidth()}x${renderer.getImageHeight()}")
                    
                    // 1. 强制在 GL 渲染线程上执行 FBO 渲染
                    val renderedBitmap = renderer.renderToBitmapWithFBO(outputWidth, outputHeight)
                    
                    // 2. 使用 renderer 的 mainHandler 将结果切换回主线程（关键修复）
                    renderer.getMainHandler().post {
                        if (renderedBitmap != null) {
                            android.util.Log.e("EditorActivity", "===== FBO rendering successful =====")
                            android.util.Log.e("EditorActivity", "Bitmap details: ${renderedBitmap.width}x${renderedBitmap.height}, " +
                                    "config=${renderedBitmap.config}, isRecycled=${renderedBitmap.isRecycled}")
                            System.out.println("EditorActivity: Successfully rendered Bitmap, size: ${renderedBitmap.width}x${renderedBitmap.height}")
                            
                            // 直接保存，不再进行耗时的像素验证（getPixel()在大图片上很慢）
                            android.util.Log.e("EditorActivity", "===== Calling saveImageToGallery() with FBO bitmap =====")
                            Toast.makeText(this@EditorActivity, "渲染成功，开始保存...", Toast.LENGTH_SHORT).show()
                            saveImageToGallery(renderedBitmap)
                        } else {
                            // 失败：导出失败，通常是因为线程问题或纹理未加载
                            android.util.Log.e("EditorActivity", "FBO rendering failed or returned null")
                            System.out.println("EditorActivity: FBO rendering failed or returned null")
                            
                            // 回退到屏幕捕获方案
                            android.util.Log.w("EditorActivity", "Falling back to screen capture...")
                            binding.glSurfaceView.requestRender()
                            renderer.requestCaptureFrame { capturedBitmap ->
                                if (capturedBitmap != null) {
                                    android.util.Log.e("EditorActivity", "Screen capture succeeded, calling saveImageToGallery...")
                                    Toast.makeText(this@EditorActivity, "屏幕捕获成功，开始保存...", Toast.LENGTH_SHORT).show()
                                    saveImageToGallery(capturedBitmap)
                                } else {
                                    android.util.Log.e("EditorActivity", "ERROR: Both FBO and screen capture failed!")
                                    Toast.makeText(this@EditorActivity, "无法导出图片：渲染失败", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EditorActivity", "ERROR in OpenGL thread FBO rendering", e)
                    e.printStackTrace()
                    runOnUiThread {
                        Toast.makeText(this@EditorActivity, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("EditorActivity", "ERROR in exportImage()", e)
            e.printStackTrace()
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImageToGallery(bitmap: Bitmap) {
        android.util.Log.d("EditorActivity", "===== saveImageToGallery() called =====")
        android.util.Log.d("EditorActivity", "Bitmap: ${bitmap.width}x${bitmap.height}, config=${bitmap.config}, " +
                "isRecycled=${bitmap.isRecycled}, SDK=${Build.VERSION.SDK_INT}")
        
        try {
            // 检查 Bitmap 是否有效
            if (bitmap.isRecycled) {
                android.util.Log.e("EditorActivity", "ERROR: Bitmap is already recycled!")
                Toast.makeText(this, "图片已释放，无法保存", Toast.LENGTH_SHORT).show()
                return
            }
            
            // 生成文件名（带时间戳）
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "edited_$timestamp.png"
            android.util.Log.d("EditorActivity", "Saving file: $fileName")
            
            // 保存到相册
            coroutineScope.launch {
                val uri = withContext(Dispatchers.IO) {
                    try {
                        android.util.Log.d("EditorActivity", "Starting save operation in IO thread...")
                        
                        // 再次检查 Bitmap 状态
                        if (bitmap.isRecycled) {
                            android.util.Log.e("EditorActivity", "ERROR: Bitmap recycled before save!")
                            return@withContext null
                        }
                        
                        val result = bitmap.saveToAlbum(
                            context = this@EditorActivity,
                            fileName = fileName,
                            relativePath = "FirstApp" // 保存到 Pictures/FirstApp 文件夹
                        )
                        
                        android.util.Log.d("EditorActivity", "Save operation completed, URI: $result")
                        result
                    } catch (e: Exception) {
                        android.util.Log.e("EditorActivity", "ERROR during save operation", e)
                        e.printStackTrace()
                        null
                    }
                }
                
                withContext(Dispatchers.Main) {
                    if (uri != null) {
                        android.util.Log.e("EditorActivity", "========================================")
                        android.util.Log.e("EditorActivity", "===== SAVE SUCCESS =====")
                        android.util.Log.e("EditorActivity", "URI: $uri")
                        android.util.Log.e("EditorActivity", "========================================")
                        System.out.println("EditorActivity: SAVE SUCCESS, URI: $uri")
                        Toast.makeText(this@EditorActivity, "✅ 编辑图片已保存到相册\n文件: $fileName", Toast.LENGTH_LONG).show()
                    } else {
                        android.util.Log.e("EditorActivity", "========================================")
                        android.util.Log.e("EditorActivity", "===== SAVE FAILED =====")
                        android.util.Log.e("EditorActivity", "URI is null, save operation returned null")
                        android.util.Log.e("EditorActivity", "========================================")
                        System.out.println("EditorActivity: SAVE FAILED, URI is null")
                        Toast.makeText(this@EditorActivity, "❌ 保存失败，请检查权限和存储空间", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("EditorActivity", "ERROR in saveImageToGallery", e)
            e.printStackTrace()
            Toast.makeText(this, "保存出错：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkWritePermissionAndTestSave() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES // Android 13+ 不需要写权限
        } else {
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            saveTestBitmap()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 不需要写权限，直接保存
                saveTestBitmap()
            } else {
                pendingAction = { saveTestBitmap() }
                writePermissionLauncher.launch(permission)
            }
        }
    }

    private fun saveTestBitmap() {
        try {
            // 创建一个测试 Bitmap
            val bitmap = createTestBitmap()
            
            // 生成文件名（带时间戳）
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "FirstAppTest_$timestamp.png"
            
            // 保存到相册
            coroutineScope.launch {
                val uri = withContext(Dispatchers.IO) {
                    try {
                        bitmap.saveToAlbum(
                            context = this@EditorActivity,
                            fileName = fileName,
                            relativePath = "FirstApp" // 保存到 Pictures/FirstApp 文件夹
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }
                
                withContext(Dispatchers.Main) {
                    if (uri != null) {
                        Toast.makeText(this@EditorActivity, "✅ 编辑图片已保存到相册\n文件: $fileName", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@EditorActivity, "保存失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "保存出错：${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }
    
    private fun createTestBitmap(): Bitmap {
        val width = 400
        val height = 400
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // 绘制背景
        canvas.drawColor(Color.LTGRAY)
        
        // 绘制文字
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 48f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        
        val text = "FirstApp\nTest Image"
        val x = width / 2f
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2
        
        canvas.drawText(text, x, y, paint)
        
        // 绘制时间戳
        val timePaint = Paint().apply {
            color = Color.BLUE
            textSize = 32f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        canvas.drawText(timestamp, x, y + 100, timePaint)
        
        return bitmap
    }
}

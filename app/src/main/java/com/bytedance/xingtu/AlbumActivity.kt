package com.bytedance.xingtu

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.bytedance.xingtu.databinding.ActivityAlbumBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlbumActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlbumBinding
    private val adapter = MediaAdapter { item ->
        // 点击图片时的处理
        if (item.isImage) {
            // 如果是从 EditorActivity 启动的（用于选择图片），返回结果
            if (intent.getBooleanExtra("from_editor", false)) {
                val resultIntent = Intent().apply {
                    data = item.uri
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            } else {
                // 否则显示确认对话框
                showConfirmDialog(item.uri)
            }
        }
    }
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            loadMediaItems()
        } else {
            // 权限被拒绝，显示引导提示
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        binding = ActivityAlbumBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.recyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerView.adapter = adapter

        // 设置"前往设置"按钮点击事件
        binding.btnOpenSettings.setOnClickListener {
            openAppSettings()
        }

        checkPermissionsAndLoad()
    }

    override fun onResume() {
        super.onResume()
        // 当从其他 Activity 返回时，重新加载媒体列表以显示新保存的图片
        // 检查权限，如果有权限则重新加载
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            loadMediaItems()
        }
    }

    private fun checkPermissionsAndLoad() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            loadMediaItems()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun loadMediaItems() {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.emptyStateLayout.visibility = android.view.View.GONE
        
        coroutineScope.launch {
            val mediaItems = withContext(Dispatchers.IO) {
                loadImagesAndVideos()
            }
            adapter.updateItems(mediaItems)
            binding.progressBar.visibility = android.view.View.GONE
            
            // 如果加载的媒体项为空，可能是权限问题
            if (mediaItems.isEmpty()) {
                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO
                    )
                } else {
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                
                val allGranted = permissions.all {
                    ContextCompat.checkSelfPermission(this@AlbumActivity, it) == PackageManager.PERMISSION_GRANTED
                }
                
                if (!allGranted) {
                    showEmptyState()
                }
            }
        }
    }

    private fun loadImagesAndVideos(): List<MediaItem> {
        val mediaItems = mutableListOf<MediaItem>()

        // ========== 查询图片 ==========

        // 1. 定义要查询的列（投影）
        val imageProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE
        )

        // 2. 定义排序方式（按日期倒序，最新的在前）
        val imageSortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        // 3. 执行查询
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            null,
            null,
            imageSortOrder
        )?.use { cursor ->
            // 4. 获取列的索引
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            // 5. 遍历查询结果
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val dateAdded = cursor.getLong(dateColumn)
                val mimeType = cursor.getString(mimeColumn)
                val size = cursor.getLong(sizeColumn)

                // 6. 构建图片 URI
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                // 7. 创建 MediaItem 对象并添加到列表
                mediaItems.add(
                    MediaItem(id, uri, name, dateAdded, mimeType, size)
                )
            }
        }

        // 查询视频
        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE
        )

        val videoSortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            null,
            null,
            videoSortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val dateAdded = cursor.getLong(dateColumn)
                val mimeType = cursor.getString(mimeColumn)
                val size = cursor.getLong(sizeColumn)
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                mediaItems.add(
                    MediaItem(id, uri, name, dateAdded, mimeType, size)
                )
            }
        }

        // 按日期排序（最新的在前）
        return mediaItems.sortedByDescending { it.dateAdded }
    }

    /**
     * 显示权限被拒绝的引导对话框
     */
    private fun showPermissionDeniedDialog() {
        val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "需要访问您的相册权限才能浏览图片和视频。\n\n" +
            "请在系统设置中授予「照片和视频」权限。"
        } else {
            "需要访问您的存储权限才能浏览图片和视频。\n\n" +
            "请在系统设置中授予「存储」权限。"
        }
        
        AlertDialog.Builder(this)
            .setTitle("需要权限")
            .setMessage(message)
            .setPositiveButton("去设置") { _, _ ->
                // 跳转到应用设置页面
                openAppSettings()
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
                // 显示空状态提示
                showEmptyState()
            }
            .setCancelable(false) // 不允许点击外部区域取消，必须做出选择
            .show()
    }
    
    /**
     * 打开应用设置页面
     */
    private fun openAppSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("AlbumActivity", "Failed to open app settings", e)
            // 如果无法打开设置页面，显示 Toast 提示
            android.widget.Toast.makeText(
                this,
                "请手动前往系统设置 > 应用 > 醒图 > 权限，授予存储权限",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
    
    /**
     * 显示空状态提示（无权限时）
     */
    private fun showEmptyState() {
        binding.progressBar.visibility = android.view.View.GONE
        binding.emptyStateLayout.visibility = android.view.View.VISIBLE
        adapter.updateItems(emptyList()) // 清空列表
    }

    /**
     * 显示确认对话框
     */
    private fun showConfirmDialog(imageUri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("确认选择")
            .setMessage("确定要编辑这张图片吗？")
            .setPositiveButton("确认") { _, _ ->
                // 点击确认，跳转到编辑器
                val intent = Intent(this, EditorActivity::class.java).apply {
                    data = imageUri
                }
                startActivity(intent)
            }
            .setNegativeButton("取消") { dialog, _ ->
                // 点击取消，关闭对话框，留在相册页面
                dialog.dismiss()
            }
            .setCancelable(true) // 允许点击外部区域取消
            .show()
    }
}

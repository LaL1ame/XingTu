@file:JvmName("ImageExt")
@file:Suppress("unused")

package com.bytedance.xingtu

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream


private const val TAG = "ImageExt"

private val ALBUM_DIR = Environment.DIRECTORY_PICTURES

private class OutputFileTaker(var file: File? = null)

/**
 * 复制图片文件到相册的Pictures文件夹
 *
 * @param context 上下文
 * @param fileName 文件名。 需要携带后缀
 * @param relativePath 相对于Pictures的路径
 */
fun File.copyToAlbum(context: Context, fileName: String, relativePath: String?): Uri? {
    if (!this.canRead() || !this.exists()) {
        Log.w(TAG, "check: read file error: $this")
        return null
    }
    return this.inputStream().use {
        it.saveToAlbum(context, fileName, relativePath)
    }
}

/**
 * 保存图片Stream到相册的Pictures文件夹
 *
 * @param context 上下文
 * @param fileName 文件名。 需要携带后缀
 * @param relativePath 相对于Pictures的路径
 */
fun InputStream.saveToAlbum(context: Context, fileName: String, relativePath: String?): Uri? {
    val resolver = context.contentResolver
    val outputFile = OutputFileTaker()
    val imageUri = resolver.insertMediaImage(fileName, relativePath, outputFile)
    if (imageUri == null) {
        Log.w(TAG, "insert: error: uri == null")
        return null
    }

    (imageUri.outputStream(resolver) ?: return null).use { output ->
        this.use { input ->
            input.copyTo(output)
            imageUri.finishPending(context, resolver, outputFile.file)
        }
    }
    return imageUri
}

/**
 * 保存Bitmap到相册的Pictures文件夹
 *
 * https://developer.android.google.cn/training/data-storage/shared/media
 *
 * @param context 上下文
 * @param fileName 文件名。 需要携带后缀
 * @param relativePath 相对于Pictures的路径
 * @param quality 质量
 */
fun Bitmap.saveToAlbum(
    context: Context,
    fileName: String,
    relativePath: String? = null,
    quality: Int = 75,
): Uri? {
    Log.d(TAG, "===== Bitmap.saveToAlbum() called =====")
    Log.d(TAG, "fileName: $fileName, relativePath: $relativePath, SDK=${Build.VERSION.SDK_INT}")
    Log.d(TAG, "Bitmap: ${this.width}x${this.height}, config=${this.config}, isRecycled=${this.isRecycled}")
    
    try {
        // 检查 Bitmap 状态
        if (this.isRecycled) {
            Log.e(TAG, "ERROR: Bitmap is already recycled!")
            return null
        }
        
        // 插入图片信息
        val resolver = context.contentResolver
        val outputFile = OutputFileTaker()
        
        Log.d(TAG, "Calling insertMediaImage...")
        val imageUri = resolver.insertMediaImage(fileName, relativePath, outputFile)
        if (imageUri == null) {
            Log.e(TAG, "ERROR: insertMediaImage returned null URI")
            return null
        }
        
        Log.d(TAG, "MediaStore URI created: $imageUri")
        Log.d(TAG, "Output file path: ${outputFile.file?.absolutePath}")

        // 保存图片
        val outputStream = imageUri.outputStream(resolver)
        if (outputStream == null) {
            Log.e(TAG, "ERROR: Failed to open output stream for URI: $imageUri")
            return null
        }
        
        outputStream.use { stream ->
            try {
                val format = fileName.getBitmapFormat()
                Log.d(TAG, "Compressing bitmap to format: $format, quality: $quality")
                
                val success = this@saveToAlbum.compress(format, quality, stream)
                if (!success) {
                    Log.e(TAG, "ERROR: Bitmap.compress() returned false")
                    return null
                }
                
                Log.d(TAG, "Bitmap compressed successfully")
                stream.flush()
                
                imageUri.finishPending(context, resolver, outputFile.file)
                Log.d(TAG, "===== Bitmap.saveToAlbum() SUCCESS =====")
                return imageUri
            } catch (e: Exception) {
                Log.e(TAG, "ERROR during bitmap compression or flush", e)
                e.printStackTrace()
                return null
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "ERROR in Bitmap.saveToAlbum()", e)
        e.printStackTrace()
        return null
    }
}

private fun Uri.outputStream(resolver: ContentResolver): OutputStream? {
    return try {
        resolver.openOutputStream(this)
    } catch (e: FileNotFoundException) {
        Log.e(TAG, "save: open stream error: $e")
        null
    }
}

private fun Uri.finishPending(
    context: Context,
    resolver: ContentResolver,
    outputFile: File?,
) {
    try {
        val imageValues = ContentValues()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 9 及以下版本
            Log.d(TAG, "finishPending: Android ${Build.VERSION.SDK_INT}, updating file size and notifying media scanner")
            
            if (outputFile != null) {
                val fileSize = outputFile.length()
                Log.d(TAG, "File size: $fileSize bytes")
                imageValues.put(MediaStore.Images.Media.SIZE, fileSize)
            }
            
            val updateCount = resolver.update(this, imageValues, null, null)
            Log.d(TAG, "MediaStore update result: $updateCount rows updated")
            
            // 通知媒体库更新
            // ACTION_MEDIA_SCANNER_SCAN_FILE 需要 file:// URI，而不是 content:// URI
            if (outputFile != null && outputFile.exists()) {
                val fileUri = Uri.fromFile(outputFile)
                Log.d(TAG, "Sending media scanner broadcast for: $fileUri")
                try {
                    val intent = Intent(@Suppress("DEPRECATION") Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri)
                    context.sendBroadcast(intent)
                    Log.d(TAG, "Media scanner broadcast sent successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "ERROR sending media scanner broadcast", e)
                    // 不抛出异常，因为这不应该阻止保存
                }
            } else {
                Log.w(TAG, "Output file is null or doesn't exist, skipping media scanner")
            }
        } else {
            // Android Q+ 添加了IS_PENDING状态，为0时其他应用才可见
            Log.d(TAG, "finishPending: Android Q+, setting IS_PENDING=0")
            imageValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            val updateCount = resolver.update(this, imageValues, null, null)
            if (updateCount == 0) {
                Log.w(TAG, "Warning: Failed to update IS_PENDING status for URI: $this")
            } else {
                Log.d(TAG, "IS_PENDING updated successfully")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "ERROR in finishPending()", e)
        e.printStackTrace()
        // 不抛出异常，因为这不应该阻止保存
    }
}

private fun String.getBitmapFormat(): Bitmap.CompressFormat {
    val fileName = this.lowercase()
    return when {
        fileName.endsWith(".png") -> Bitmap.CompressFormat.PNG
        fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> Bitmap.CompressFormat.JPEG
        fileName.endsWith(".webp") -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.PNG
    }
}

private fun String.getMimeType(): String? {
    val fileName = this.lowercase()
    return when {
        fileName.endsWith(".png") -> "image/png"
        fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
        fileName.endsWith(".webp") -> "image/webp"
        fileName.endsWith(".gif") -> "image/gif"
        else -> null
    }
}

/**
 * 插入图片到媒体库
 */
private fun ContentResolver.insertMediaImage(
    fileName: String,
    relativePath: String?,
    outputFileTaker: OutputFileTaker? = null,
): Uri? {
    // 图片信息
    val imageValues = ContentValues().apply {
        val mimeType = fileName.getMimeType()
        if (mimeType != null) {
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        }
        val date = System.currentTimeMillis() / 1000
        put(MediaStore.Images.Media.DATE_ADDED, date)
        put(MediaStore.Images.Media.DATE_MODIFIED, date)
    }
    // 保存的位置
    val collection: Uri
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val path = if (relativePath != null) "${ALBUM_DIR}/${relativePath}" else ALBUM_DIR
        imageValues.apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // 高版本不用查重直接插入，会自动重命名
    } else {
        // 老版本（Android 9 及以下）
        Log.d(TAG, "Using legacy storage for Android ${Build.VERSION.SDK_INT}")
        
        // 检查外部存储是否可用
        val externalStorageState = @Suppress("DEPRECATION") Environment.getExternalStorageState()
        if (externalStorageState != Environment.MEDIA_MOUNTED) {
            Log.e(TAG, "ERROR: External storage not mounted. State: $externalStorageState")
            return null
        }
        
        val pictures =
            @Suppress("DEPRECATION") Environment.getExternalStoragePublicDirectory(ALBUM_DIR)
        val saveDir = if (relativePath != null) File(pictures, relativePath) else pictures

        Log.d(TAG, "Save directory: ${saveDir.absolutePath}")
        
        if (!saveDir.exists()) {
            Log.d(TAG, "Directory doesn't exist, creating: ${saveDir.absolutePath}")
            val created = saveDir.mkdirs()
            if (!created) {
                Log.e(TAG, "ERROR: Failed to create Pictures directory: ${saveDir.absolutePath}")
                return null
            }
            Log.d(TAG, "Directory created successfully")
        } else {
            Log.d(TAG, "Directory already exists")
        }
        
        // 检查目录是否可写
        if (!saveDir.canWrite()) {
            Log.e(TAG, "ERROR: Directory is not writable: ${saveDir.absolutePath}")
            return null
        }

        // 文件路径查重，重复的话在文件名后拼接数字
        var imageFile = File(saveDir, fileName)
        val fileNameWithoutExtension = imageFile.nameWithoutExtension
        val fileExtension = imageFile.extension

        var queryUri = this.queryMediaImage28(imageFile.absolutePath)
        var suffix = 1
        while (queryUri != null) {
            val newName = fileNameWithoutExtension + "(${suffix++})." + fileExtension
            imageFile = File(saveDir, newName)
            queryUri = this.queryMediaImage28(imageFile.absolutePath)
        }

        imageValues.apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, imageFile.name)
            // 保存路径
            val imagePath = imageFile.absolutePath
            Log.v(TAG, "save file: $imagePath")
            put(@Suppress("DEPRECATION") MediaStore.Images.Media.DATA, imagePath)
        }
        outputFileTaker?.file = imageFile// 回传文件路径，用于设置文件大小
        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    // 插入图片信息
    Log.d(TAG, "Inserting into MediaStore, collection: $collection")
    Log.d(TAG, "ContentValues: $imageValues")
    
    val insertedUri = try {
        this.insert(collection, imageValues)
    } catch (e: Exception) {
        Log.e(TAG, "ERROR: Exception during MediaStore insert", e)
        e.printStackTrace()
        null
    }
    
    if (insertedUri == null) {
        Log.e(TAG, "ERROR: MediaStore.insert() returned null")
    } else {
        Log.d(TAG, "MediaStore insert successful: $insertedUri")
    }
    
    return insertedUri
}

/**
 * Android Q以下版本，查询媒体库中当前路径是否存在
 * @return Uri 返回null时说明不存在，可以进行图片插入逻辑
 */
private fun ContentResolver.queryMediaImage28(imagePath: String): Uri? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return null

    val imageFile = File(imagePath)
    if (imageFile.canRead() && imageFile.exists()) {
        Log.v(TAG, "query: path: $imagePath exists")
        // 文件已存在，返回一个file://xxx的uri
        return Uri.fromFile(imageFile)
    }
    // 保存的位置
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    // 查询是否已经存在相同图片
    val query = this.query(
        collection,
        arrayOf(MediaStore.Images.Media._ID, @Suppress("DEPRECATION") MediaStore.Images.Media.DATA),
        "${@Suppress("DEPRECATION") MediaStore.Images.Media.DATA} == ?",
        arrayOf(imagePath), null
    )
    query?.use {
        while (it.moveToNext()) {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val id = it.getLong(idColumn)
            val existsUri = ContentUris.withAppendedId(collection, id)
            Log.v(TAG, "query: path: $imagePath exists uri: $existsUri")
            return existsUri
        }
    }
    return null
}


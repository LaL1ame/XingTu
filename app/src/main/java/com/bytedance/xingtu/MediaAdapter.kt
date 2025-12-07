package com.bytedance.xingtu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bytedance.xingtu.databinding.ItemMediaBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MediaAdapter(
    private var mediaItems: List<MediaItem> = emptyList(),
    private val onItemClick: ((MediaItem) -> Unit)? = null
) : RecyclerView.Adapter<MediaAdapter.MediaViewHolder>() {

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    class MediaViewHolder(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemMediaBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val item = mediaItems[position]
        val context = holder.itemView.context
        
        // 如果是视频，显示播放图标
        holder.binding.videoIndicator.visibility = if (item.isVideo) View.VISIBLE else View.GONE
        
        // 先设置占位图或清空
        holder.binding.imageView.setImageBitmap(null)
        
        // 设置点击监听器（仅图片可点击）
        if (item.isImage) {
            holder.itemView.setOnClickListener {
                onItemClick?.invoke(item)
            }
        } else {
            holder.itemView.setOnClickListener(null)
        }
        
        // 异步加载缩略图
        coroutineScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                loadThumbnail(item, context)
            }
            bitmap?.let {
                // 检查 holder 是否仍然绑定到同一个位置
                if (holder.bindingAdapterPosition == position) {
                    holder.binding.imageView.setImageBitmap(it)
                }
            }
        }
    }

    override fun getItemCount(): Int = mediaItems.size

    fun updateItems(newItems: List<MediaItem>) {
        mediaItems = newItems
        notifyDataSetChanged()
    }

    private fun loadThumbnail(item: MediaItem, context: android.content.Context): Bitmap? {
        return try {
            if (item.isVideo) {
                // 加载视频缩略图 - 使用 MediaStore 获取缩略图
                val thumbnail = MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    item.id,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null
                )
                thumbnail ?: run {
                    // 如果获取失败，尝试直接加载视频文件的第一帧
                    try {
                        val inputStream = context.contentResolver.openInputStream(item.uri)
                        inputStream?.use {
                            // 对于视频，这里只是简单处理，实际应该使用 MediaMetadataRetriever
                            null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            } else {
                // 加载图片缩略图 - 使用 MediaStore 获取缩略图
                val thumbnail = MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver,
                    item.id,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null
                )
                thumbnail ?: run {
                    // 如果缩略图不存在，直接加载原图（需要缩放）
                    val inputStream = context.contentResolver.openInputStream(item.uri)
                    inputStream?.use {
                        val options = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeStream(it, null, options)
                        
                        // 计算缩放比例
                        val scale = calculateInSampleSize(options, 200, 200)
                        
                        // 重新打开流并解码
                        val inputStream2 = context.contentResolver.openInputStream(item.uri)
                        inputStream2?.use { stream ->
                            val options2 = BitmapFactory.Options().apply {
                                inSampleSize = scale
                            }
                            BitmapFactory.decodeStream(stream, null, options2)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }
}

package com.bytedance.xingtu

import android.graphics.BitmapFactory
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.hypot

/**
 * 混合媒体适配器（支持视频和图片）
 */
class MixedMediaAdapter(
    private val mediaItems: List<MediaContentItem>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var currentPlayingPosition = -1
    private var currentPlayerView: PlayerView? = null

    companion object {
        private const val TYPE_VIDEO = 0
        private const val TYPE_IMAGE = 1
    }

    // 视频 ViewHolder
    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val coverImage: ImageView = itemView.findViewById(R.id.coverImage)
        val playerView: PlayerView = itemView.findViewById(R.id.playerView)
        val playIcon: ImageView = itemView.findViewById(R.id.playIcon)

        private var boundUri: Uri? = null
        private var downX = 0f
        private var downY = 0f
        private var isPotentialPlay = false
        private val touchSlop = ViewConfiguration.get(itemView.context).scaledTouchSlop

        fun bind(item: MediaContentItem.Video) {
            boundUri = item.uri
            android.util.Log.d("MixedMediaAdapter", "Binding video: ${item.displayName}, URI: ${item.uri}")

            // 确保 playerView 默认未绑定
            if (playerView.player != null && playerView.player != VideoPlayerHolder.getPlayer()) {
                playerView.player = null
            }

            // 重置状态
            playerView.visibility = View.GONE
            coverImage.visibility = View.VISIBLE
            playIcon.visibility = View.VISIBLE

            // 加载视频第一帧作为封面
            try {
                loadVideoThumbnail(item.uri)
            } catch (e: Exception) {
                android.util.Log.e("MixedMediaAdapter", "Failed to load video thumbnail", e)
                coverImage.setImageResource(android.R.color.darker_gray)
            }

            // 如果这是当前正在播放的项，恢复绑定
            val position = bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION && position == currentPlayingPosition && currentPlayerView == playerView) {
                bindPlayer()
            } else {
                unbindPlayer()
            }

            // 触摸处理
            itemView.setOnTouchListener(object : View.OnTouchListener {
                override fun onTouch(v: View?, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            isPotentialPlay = true
                            itemView.post {
                                val currentPosition = bindingAdapterPosition
                                if (currentPosition != RecyclerView.NO_POSITION) {
                                    tryPlayIfEligible()
                                }
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (isPotentialPlay) {
                                val distance = hypot(event.x - downX, event.y - downY)
                                if (distance > touchSlop) {
                                    isPotentialPlay = false
                                }
                            }
                        }
                    }
                    return false
                }
            })
        }

        private fun loadVideoThumbnail(uri: Uri) {
            try {
                android.util.Log.d("MixedMediaAdapter", "Loading video thumbnail: $uri")
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(itemView.context, uri)
                val bitmap = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                
                if (bitmap != null) {
                    android.util.Log.d("MixedMediaAdapter", "Thumbnail loaded successfully, size: ${bitmap.width}x${bitmap.height}")
                    coverImage.setImageBitmap(bitmap)
                } else {
                    android.util.Log.w("MixedMediaAdapter", "Thumbnail is null, using placeholder")
                    coverImage.setImageResource(android.R.color.darker_gray)
                }
            } catch (e: Exception) {
                android.util.Log.e("MixedMediaAdapter", "Error loading thumbnail: $uri", e)
                e.printStackTrace()
                coverImage.setImageResource(android.R.color.darker_gray)
            }
        }

        private fun tryPlayIfEligible() {
            if (!isPotentialPlay || boundUri == null) return

            val player = VideoPlayerHolder.getPlayer() ?: return
            val uri = boundUri ?: return

            // 切换播放
            currentPlayerView?.let { oldView ->
                if (oldView != playerView) {
                    oldView.player = null
                    oldView.visibility = View.GONE
                    val oldPosition = currentPlayingPosition
                    if (oldPosition >= 0 && oldPosition < itemCount) {
                        notifyItemChanged(oldPosition)
                    }
                }
            }

            currentPlayerView = playerView
            val position = bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                currentPlayingPosition = position
            }

            bindPlayer()

            val currentUri = VideoPlayerHolder.getCurrentUri()
            if (currentUri != uri) {
                try {
                    val mediaItem = MediaItem.fromUri(uri)
                    player.setMediaItem(mediaItem)
                    player.prepare()
                } catch (e: Exception) {
                    android.util.Log.e("MixedMediaAdapter", "Failed to load video: $uri", e)
                    return
                }
            }

            try {
                player.play()
            } catch (e: Exception) {
                android.util.Log.e("MixedMediaAdapter", "Failed to play video", e)
            }

            playerView.visibility = View.VISIBLE
            coverImage.visibility = View.GONE
            playIcon.visibility = View.GONE
        }

        private fun bindPlayer() {
            val player = VideoPlayerHolder.getPlayer() ?: return
            if (playerView.player != player) {
                playerView.player = player
            }
        }

        fun unbindPlayer() {
            val player = VideoPlayerHolder.getPlayer()
            if (playerView.player == player) {
                playerView.player = null
            }
            playerView.visibility = View.GONE
            coverImage.visibility = View.VISIBLE
            playIcon.visibility = View.VISIBLE
        }
    }

    // 图片 ViewHolder
    inner class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.imageView)

        fun bind(item: MediaContentItem.Image) {
            android.util.Log.d("MixedMediaAdapter", "Binding image: ${item.displayName}, URI: ${item.uri}")

            // 加载图片
            loadImage(item.uri)
        }

        private fun loadImage(uri: Uri) {
            try {
                val inputStream = itemView.context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                } else {
                    imageView.setImageResource(android.R.color.darker_gray)
                }
            } catch (e: Exception) {
                android.util.Log.e("MixedMediaAdapter", "Error loading image: $uri", e)
                imageView.setImageResource(android.R.color.darker_gray)
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = mediaItems[position]
        val type = when (item) {
            is MediaContentItem.Video -> TYPE_VIDEO
            is MediaContentItem.Image -> TYPE_IMAGE
        }
        android.util.Log.d("MixedMediaAdapter", "getItemViewType: position=$position, type=$type, item=${item.displayName}")
        return type
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        android.util.Log.d("MixedMediaAdapter", "onCreateViewHolder: viewType=$viewType")
        return when (viewType) {
            TYPE_VIDEO -> {
                android.util.Log.d("MixedMediaAdapter", "Creating VideoViewHolder")
                val view = android.view.LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_video, parent, false)
                VideoViewHolder(view)
            }
            TYPE_IMAGE -> {
                android.util.Log.d("MixedMediaAdapter", "Creating ImageViewHolder")
                val view = android.view.LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_image, parent, false)
                ImageViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        android.util.Log.d("MixedMediaAdapter", "onBindViewHolder: position=$position, holder=${holder.javaClass.simpleName}")
        when (val item = mediaItems[position]) {
            is MediaContentItem.Video -> {
                android.util.Log.d("MixedMediaAdapter", "Binding video item at position $position")
                (holder as VideoViewHolder).bind(item)
            }
            is MediaContentItem.Image -> {
                android.util.Log.d("MixedMediaAdapter", "Binding image item at position $position")
                (holder as ImageViewHolder).bind(item)
            }
        }
    }

    override fun getItemCount(): Int = mediaItems.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is VideoViewHolder -> {
                if (holder.adapterPosition == currentPlayingPosition) {
                    holder.unbindPlayer()
                    currentPlayerView = null
                    currentPlayingPosition = -1
                } else {
                    holder.unbindPlayer()
                }
            }
            is ImageViewHolder -> {
                // 图片 ViewHolder 无需特殊处理
            }
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (holder is VideoViewHolder && holder.adapterPosition == currentPlayingPosition) {
            holder.unbindPlayer()
            VideoPlayerHolder.pause()
            currentPlayerView = null
            currentPlayingPosition = -1
        }
    }
}






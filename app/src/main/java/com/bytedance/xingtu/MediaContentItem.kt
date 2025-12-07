package com.bytedance.xingtu

import android.net.Uri

/**
 * 媒体内容项（可以是视频或图片）
 */
sealed class MediaContentItem {
    abstract val id: Long
    abstract val uri: Uri
    abstract val displayName: String
    abstract val resourceName: String

    data class Video(
        override val id: Long,
        override val uri: Uri,
        override val displayName: String,
        override val resourceName: String
    ) : MediaContentItem()

    data class Image(
        override val id: Long,
        override val uri: Uri,
        override val displayName: String,
        override val resourceName: String
    ) : MediaContentItem()
}






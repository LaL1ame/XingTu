package com.bytedance.xingtu

import android.net.Uri

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long,
    val mimeType: String,
    val size: Long
) {
    val isImage: Boolean
        get() = mimeType.startsWith("image/")
    
    val isVideo: Boolean
        get() = mimeType.startsWith("video/")
}







package com.bytedance.xingtu

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer

/**
 * 单例播放器管理类
 * 全局只使用一个 ExoPlayer 实例
 */
object VideoPlayerHolder {
    private var player: ExoPlayer? = null

    /**
     * 初始化播放器
     */
    fun init(context: Context) {
        if (player == null) {
            player = ExoPlayer.Builder(context.applicationContext).build().apply {
                repeatMode = ExoPlayer.REPEAT_MODE_OFF
                volume = 0f // 默认静音
            }
        }
    }

    /**
     * 获取播放器实例
     */
    fun getPlayer(): ExoPlayer? = player

    /**
     * 暂停播放
     */
    fun pause() {
        player?.pause()
    }

    /**
     * 释放播放器资源
     */
    fun release() {
        player?.release()
        player = null
    }

    /**
     * 获取当前播放的 URI
     */
    fun getCurrentUri(): android.net.Uri? {
        return player?.currentMediaItem?.localConfiguration?.uri
    }
}






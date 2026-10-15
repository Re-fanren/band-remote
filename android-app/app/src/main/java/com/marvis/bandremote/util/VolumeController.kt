package com.marvis.bandremote.util

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * 音量调节封装
 * 统一控制媒体流（STREAM_MUSIC），并附带系统音量条 UI
 */
object VolumeController {

    private const val TAG = "VolumeController"

    fun volumeUp(context: Context): Boolean = adjust(context, AudioManager.ADJUST_RAISE)

    fun volumeDown(context: Context): Boolean = adjust(context, AudioManager.ADJUST_LOWER)

    /**
     * 静音 / 取消静音 切换
     */
    @Suppress("DEPRECATION")
    fun toggleMute(context: Context): Boolean {
        val am = audioManager(context) ?: return false
        return try {
            val muted = am.isStreamMute(AudioManager.STREAM_MUSIC)
            val dir = if (muted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) {
            Log.e(TAG, "toggleMute 失败", e)
            false
        }
    }

    private fun adjust(context: Context, direction: Int): Boolean {
        val am = audioManager(context) ?: return false
        return try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) {
            Log.e(TAG, "adjust 失败", e)
            false
        }
    }

    private fun audioManager(context: Context): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
}

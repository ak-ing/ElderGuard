package com.elderguard.app.guard

import android.content.Context
import android.media.AudioManager

/** 是否在通话或响铃中（含微信等网络通话）。基于音频模式，不需要电话权限 */
fun isInCall(context: Context): Boolean =
  when (context.getSystemService(AudioManager::class.java).mode) {
    AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE -> true
    else -> false
  }

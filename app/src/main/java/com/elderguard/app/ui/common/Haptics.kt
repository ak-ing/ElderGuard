package com.elderguard.app.ui.common

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 密码键盘的震动。直接用振动器，不走 View 的触感反馈：
 * 后者受系统「触摸振动」开关控制，很多手机默认关着，按了没感觉。
 * 没有振动器的设备（部分平板、车机）什么也不做。
 */
class PinHaptics(context: Context) {
  private val vibrator: Vibrator? =
    if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

  // 预设效果（Android 10+）手感更清脆；更早的系统用短时长单次震动

  /** 按键：短促一下 */
  fun key() =
    play(
      if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
      else VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
    )

  /** 密码正确：稍重一下 */
  fun success() =
    play(
      if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
      else VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
    )

  /** 密码错误：连震两下，配合圆点抖动 */
  fun error() = play(VibrationEffect.createWaveform(longArrayOf(0, 45, 70, 45), -1))

  private fun play(effect: VibrationEffect) {
    vibrator?.takeIf { it.hasVibrator() }?.vibrate(effect)
  }
}

@Composable
fun rememberPinHaptics(): PinHaptics {
  val context = LocalContext.current
  return remember(context) { PinHaptics(context.applicationContext) }
}

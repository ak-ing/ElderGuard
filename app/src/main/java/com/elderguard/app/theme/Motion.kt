package com.elderguard.app.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/** Material 3 动效规范的缓动与时长 */
object Motion {
  val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
  val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
  val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

  const val DURATION_SHORT = 150
  const val DURATION_MEDIUM = 300
  const val DURATION_LONG = 450

  /** 共享轴 X：进入方向 [forward] 为 true 时从右侧滑入，返回时从左侧 */
  fun sharedAxisX(forward: Boolean, slideFraction: Float = 0.12f): ContentTransform {
    val sign = if (forward) 1 else -1
    val enter =
      slideInHorizontally(tween(DURATION_MEDIUM, easing = EmphasizedDecelerate)) { (it * slideFraction).toInt() * sign } +
        fadeIn(tween(210, delayMillis = 90, easing = EmphasizedDecelerate))
    val exit =
      slideOutHorizontally(tween(DURATION_MEDIUM, easing = EmphasizedAccelerate)) { -(it * slideFraction).toInt() * sign } +
        fadeOut(tween(90, easing = EmphasizedAccelerate))
    return enter togetherWith exit
  }
}

fun <S> AnimatedContentTransitionScope<S>.sharedAxisForward(): ContentTransform = Motion.sharedAxisX(forward = true)

fun <S> AnimatedContentTransitionScope<S>.sharedAxisBackward(): ContentTransform = Motion.sharedAxisX(forward = false)

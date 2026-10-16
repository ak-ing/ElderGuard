package com.elderguard.app.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/** Material 3 动效规范的缓动与时长 */
object Motion {
  val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
  val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
  val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

  const val DURATION_SHORT = 150
  const val DURATION_MEDIUM = 300
  const val DURATION_LONG = 450

  /** 设置向导分步切换的时长 */
  const val DURATION_PAGE = 400

  /** 页面跳转的时长，和车机桌面（Home 工程）的导航动画一致 */
  const val DURATION_NAV = 500

  /**
   * 页面跳转（前进、返回、预测性返回都用这一个），沿用车机桌面 Home 工程的导航动画：
   * 新页面从 0.9 倍放大到原大并淡入，旧页面放大到 1.1 倍并淡出
   */
  fun page(): ContentTransform {
    val spec = tween<Float>(DURATION_NAV)
    return (scaleIn(spec, initialScale = 0.9f) + fadeIn(spec)) togetherWith (scaleOut(spec, targetScale = 1.1f) + fadeOut(spec))
  }

  /** 同一页里的分步内容（设置向导）：整块左右滑动，内容背景透明，所以前后两步不重叠 */
  fun slideStep(forward: Boolean): ContentTransform {
    val sign = if (forward) 1 else -1
    val spec = tween<IntOffset>(DURATION_PAGE, easing = Emphasized)
    return (slideInHorizontally(spec) { it * sign } + fadeIn(tween(DURATION_PAGE / 2, delayMillis = DURATION_PAGE / 4))) togetherWith
      (slideOutHorizontally(spec) { -it * sign } + fadeOut(tween(DURATION_PAGE / 2)))
  }

}

fun <S> AnimatedContentTransitionScope<S>.pageTransition(): ContentTransform = Motion.page()

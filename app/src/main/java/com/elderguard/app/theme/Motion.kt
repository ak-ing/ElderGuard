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

  /** 页面跳转的时长 */
  const val DURATION_NAV = 500

  /**
   * 页面跳转（前进、返回、预测性返回都用这一个）：
   * 新页面从 0.9 倍放大到原大并淡入，旧页面放大到 1.1 倍并淡出
   */
  fun page(): ContentTransform {
    val spec = tween<Float>(DURATION_NAV)
    return (scaleIn(spec, initialScale = 0.9f) + fadeIn(spec)) togetherWith (scaleOut(spec, targetScale = 1.1f) + fadeOut(spec))
  }

  /**
   * 内容替换（数字、说明文字、状态）：旧的先快速淡出，新的稍后淡入（Material 的 fade through）。
   * 两边同时半透明叠在一起会看到重影
   */
  fun fadeThrough(): ContentTransform =
    fadeIn(tween(DURATION_SHORT + 60, delayMillis = 90)) togetherWith fadeOut(tween(90))

  /** 图标替换：旧图标先缩小淡出，新图标再放大淡入，两个不叠在一起 */
  fun popSwap(): ContentTransform {
    val enter = tween<Float>(DURATION_SHORT + 60, delayMillis = 90, easing = EmphasizedDecelerate)
    val exit = tween<Float>(90)
    return (scaleIn(enter, initialScale = 0.6f) + fadeIn(enter)) togetherWith (scaleOut(exit, targetScale = 0.6f) + fadeOut(exit))
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

fun <S> AnimatedContentTransitionScope<S>.fadeThrough(): ContentTransform = Motion.fadeThrough()

fun <S> AnimatedContentTransitionScope<S>.popSwap(): ContentTransform = Motion.popSwap()

/*
 * 列表项增删（展开、收起、标记后移出列表）：
 * 移走的项立刻消失、不淡出——否则下面的项往上移时会从它半透明的影子上滑过去，看到残影；
 * 新出现的项等下面的项让开位置后再淡入，同样不和移动中的项叠在一起
 */
val ListPlacement = tween<IntOffset>(Motion.DURATION_MEDIUM - 50, easing = Motion.Emphasized)
val ListFadeIn = tween<Float>(Motion.DURATION_SHORT, delayMillis = Motion.DURATION_MEDIUM - 150)

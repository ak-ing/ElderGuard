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
import androidx.compose.ui.unit.IntOffset

/** Material 3 动效规范的缓动与时长 */
object Motion {
  val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
  val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
  val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

  const val DURATION_SHORT = 150
  const val DURATION_MEDIUM = 300
  const val DURATION_LONG = 450

  /** 页面跳转用的时长：整页移动比组件动画长一些，看得清方向 */
  const val DURATION_PAGE = 400

  /**
   * 页面推入/退出（和系统打开新页面一致）：
   * 前进时新页面从右边整页滑入、盖在上面，旧页面向左挪 1/4 并变暗；返回时反过来，旧页面在下面露出来。
   * 页面都是不透明的 Scaffold，叠在一起不会透出来
   */
  fun push(forward: Boolean): ContentTransform {
    val spec = tween<IntOffset>(DURATION_PAGE, easing = Emphasized)
    val fade = tween<Float>(DURATION_PAGE, easing = Emphasized)
    return if (forward) {
      ContentTransform(
        targetContentEnter = slideInHorizontally(spec) { it },
        initialContentExit = slideOutHorizontally(spec) { -it / 4 } + fadeOut(fade, targetAlpha = 0.5f),
      )
    } else {
      ContentTransform(
        targetContentEnter = slideInHorizontally(spec) { -it / 4 } + fadeIn(fade, initialAlpha = 0.5f),
        initialContentExit = slideOutHorizontally(spec) { it },
        // 返回时露出来的页面在下面，离开的页面盖在上面滑走
        targetContentZIndex = -1f,
      )
    }
  }

  /** 同一页里的分步内容（设置向导）：整块左右滑动，内容背景透明，所以前后两步不重叠 */
  fun slideStep(forward: Boolean): ContentTransform {
    val sign = if (forward) 1 else -1
    val spec = tween<IntOffset>(DURATION_PAGE, easing = Emphasized)
    return (slideInHorizontally(spec) { it * sign } + fadeIn(tween(DURATION_PAGE / 2, delayMillis = DURATION_PAGE / 4))) togetherWith
      (slideOutHorizontally(spec) { -it * sign } + fadeOut(tween(DURATION_PAGE / 2)))
  }

}

fun <S> AnimatedContentTransitionScope<S>.pushForward(): ContentTransform = Motion.push(forward = true)

fun <S> AnimatedContentTransitionScope<S>.pushBackward(): ContentTransform = Motion.push(forward = false)

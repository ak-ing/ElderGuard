package com.elderguard.app.guard

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.elderguard.app.R

/**
 * 拦截后给长辈的短提示：无障碍浮层（无需悬浮窗权限），不可点击、不抢焦点，2.5 秒后淡出。
 * 配色取主题的 inverseSurface / inversePrimary，与 Material 3 Snackbar 一致。
 */
class ElderHint(private val service: AccessibilityService) {
  private val wm = service.getSystemService(WindowManager::class.java)
  private val handler = Handler(Looper.getMainLooper())
  private var current: View? = null

  fun show(text: String) {
    dismissNow()
    val view = build(text)
    val params =
      WindowManager.LayoutParams(
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
          PixelFormat.TRANSLUCENT,
        )
        .apply {
          gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
          y = dp(96)
        }
    runCatching { wm.addView(view, params) }
      .onFailure {
        Log.w("ElderGuard", "提示浮层显示失败", it)
        return
      }
    current = view
    Log.i("ElderGuard", "显示提示：$text")
    view.alpha = 0f
    view.translationY = dp(24).toFloat()
    view.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(DECELERATE).start()
    handler.postDelayed({ dismiss(view) }, SHOW_MS)
  }

  fun dismissNow() {
    handler.removeCallbacksAndMessages(null)
    current?.let { runCatching { wm.removeView(it) } }
    current = null
  }

  private fun dismiss(view: View) {
    view.animate().alpha(0f).translationY(dp(12).toFloat()).setDuration(200).setInterpolator(ACCELERATE).withEndAction {
      runCatching { wm.removeView(view) }
      if (current === view) current = null
    }.start()
  }

  private fun build(text: String): View {
    val dark = (service.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
      android.content.res.Configuration.UI_MODE_NIGHT_YES
    val bg = if (dark) 0xFFDDE4E2.toInt() else 0xFF2B3231.toInt()
    val fg = if (dark) 0xFF2B3231.toInt() else 0xFFECF2F0.toInt()
    val accent = if (dark) 0xFF006A63.toInt() else 0xFF82D5CB.toInt()
    return LinearLayout(service).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      background = GradientDrawable().apply {
        cornerRadius = dp(28).toFloat()
        setColor(bg)
      }
      elevation = dp(6).toFloat()
      setPadding(dp(20), dp(14), dp(24), dp(14))
      addView(
        ImageView(service).apply {
          setImageDrawable(ContextCompat.getDrawable(service, R.drawable.ic_shield_check)?.mutate()?.apply { setTint(accent) })
        },
        LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) },
      )
      addView(
        TextView(service).apply {
          this.text = text
          setTextColor(fg)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
      )
    }
  }

  private fun dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), service.resources.displayMetrics).toInt()

  private companion object {
    const val SHOW_MS = 2500L
    val DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
  }
}

package com.elderguard.app.guard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.elderguard.app.container
import com.elderguard.app.core.Detection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 日常守护的入口：把无障碍事件喂给 [Detector]，把判定结果交给 [Responder] */
class GuardAccessibilityService : AccessibilityService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val main = Handler(Looper.getMainLooper())
  private lateinit var detector: Detector
  private lateinit var responder: Responder
  private lateinit var hint: ElderHint

  private val systemReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
          Intent.ACTION_USER_PRESENT -> detector.onScreenUnlocked()
          // 装卸了桌面或输入法后刷新受保护名单
          Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED -> refreshSystemPackages()
        }
      }
    }

  override fun onServiceConnected() {
    val c = container
    detector = Detector(c.rules, SystemClock::elapsedRealtime).apply { selfPackage = packageName }
    hint = ElderHint(this)
    responder =
      Responder(
        c.guardRepository,
        scope,
        c.rules.autoActionCooldownMs,
        isInCall = { isInCall(this) },
        hint = { text -> main.post { hint.show(text) } },
      )
    refreshSystemPackages()

    ContextCompat.registerReceiver(
      this,
      systemReceiver,
      IntentFilter(Intent.ACTION_USER_PRESENT),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    ContextCompat.registerReceiver(
      this,
      systemReceiver,
      IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addDataScheme("package")
      },
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    scope.launch { c.guardRepository.verdicts.collect { detector.verdicts = it } }
    scope.launch { c.settings.guardMode.collect { responder.mode = it } }
    GuardNotifier.cancelStopped(this)
    _running.value = true
    Log.i(TAG, "守护已启动")
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (!::detector.isInitialized) return
    when (event.eventType) {
      AccessibilityEvent.TYPE_VIEW_CLICKED,
      AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
      AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> detector.onUserInteraction()
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (!container.appInfo.isActivity(pkg, cls)) return
        val detection = detector.onForegroundChanged(pkg, cls) ?: return
        Log.i(TAG, "发现骚扰：$detection")
        responder.handle(detection) { goBack(detection) }
      }
    }
  }

  /** 先「返回」；部分广告页会拦截返回键，稍后它若仍在前台就回到桌面 */
  private fun goBack(detection: Detection): Boolean {
    if (!performGlobalAction(GLOBAL_ACTION_BACK)) return false
    main.postDelayed(
      {
        if (detector.currentPackage == detection.targetPkg) {
          Log.i(TAG, "返回无效，回到桌面：${detection.targetPkg}")
          performGlobalAction(GLOBAL_ACTION_HOME)
        }
      },
      BACK_VERIFY_MS,
    )
    return true
  }

  override fun onInterrupt() {}

  override fun onUnbind(intent: Intent?): Boolean {
    // 被关闭（系统设置里关掉、省电策略等）：立刻提醒；之后由巡检持续提醒
    GuardNotifier.notifyStopped(this)
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    _running.value = false
    main.removeCallbacksAndMessages(null)
    if (::hint.isInitialized) hint.dismissNow()
    runCatching { unregisterReceiver(systemReceiver) }
    scope.cancel()
    super.onDestroy()
  }

  private fun refreshSystemPackages() {
    detector.systemPackages = container.appInfo.systemPackages()
  }

  companion object {
    private const val TAG = "ElderGuard"
    private const val BACK_VERIFY_MS = 800L
    private val _running = MutableStateFlow(false)
    /** 本进程内服务是否处于连接状态（设置里开着但被系统杀掉时为 false） */
    val running: StateFlow<Boolean> = _running.asStateFlow()
  }
}

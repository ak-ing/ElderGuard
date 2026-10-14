package com.elderguard.app.guard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.elderguard.app.container
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
  private lateinit var detector: Detector
  private lateinit var responder: Responder

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
    responder = Responder(applicationContext, c.guardRepository, scope, c.rules.autoActionCooldownMs)
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
        responder.handle(detection) { performGlobalAction(GLOBAL_ACTION_BACK) }
      }
    }
  }

  override fun onInterrupt() {}

  override fun onDestroy() {
    _running.value = false
    runCatching { unregisterReceiver(systemReceiver) }
    scope.cancel()
    super.onDestroy()
  }

  private fun refreshSystemPackages() {
    detector.systemPackages = container.appInfo.systemPackages()
  }

  companion object {
    private const val TAG = "ElderGuard"
    private val _running = MutableStateFlow(false)
    /** 本进程内服务是否处于连接状态（设置里开着但被系统杀掉时为 false） */
    val running: StateFlow<Boolean> = _running.asStateFlow()
  }
}

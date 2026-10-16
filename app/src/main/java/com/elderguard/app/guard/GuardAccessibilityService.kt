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
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.elderguard.app.AppContainer
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

/**
 * 日常守护的入口：前台切换、窗口变化交给 [Detector] / [OverlayWatcher]，判定结果交给 [Responder]；
 * 同时记下新装的应用。
 */
class GuardAccessibilityService : AccessibilityService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val main = Handler(Looper.getMainLooper())
  private lateinit var detector: Detector
  private lateinit var overlays: OverlayWatcher
  private lateinit var responder: Responder
  private lateinit var hint: ElderHint
  /** 窗口 id → 所属 App；取一次窗口内容要跨进程，只对新窗口取 */
  private val windowPackages = HashMap<Int, String>()
  /** 还没认出所属 App 的窗口 → 已尝试次数；刚弹出、还没布局好的窗口过一会儿就能认出来 */
  private val unresolvedTries = HashMap<Int, Int>()

  private val systemReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
          // 换默认桌面、输入法没有广播，解锁时顺便刷新
          Intent.ACTION_USER_PRESENT -> refreshSystemPackages()
          Intent.ACTION_PACKAGE_ADDED -> {
            refreshSystemPackages()
            val pkg = intent.data?.schemeSpecificPart
            // 只记新装，不记更新
            if (pkg != null && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) recordInstall(pkg)
          }
          // 装卸了桌面或输入法后刷新受保护名单
          Intent.ACTION_PACKAGE_REMOVED -> refreshSystemPackages()
        }
      }
    }

  override fun onServiceConnected() {
    val c = container
    detector = Detector(c.rules).apply { selfPackage = packageName }
    overlays = OverlayWatcher(SystemClock::elapsedRealtime)
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
    scope.launch { c.settings.blockQuickApps.collect { detector.blockQuickApps = it } }
    // 服务没在运行、或安装广播被厂商系统丢掉时装上的应用，按系统记的首次安装时间补上
    scope.launch { syncInstalls(c) }
    detector.privilegeRequestsAllowed = { c.maintenance.active || c.privileged.isRequestingPermission() }
    // 以当前窗口为基准，服务启动前就在的悬浮窗不记
    onWindowsChanged()
    GuardNotifier.cancelStopped(this)
    _running.value = true
    Log.i(TAG, "守护已启动")
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (!::detector.isInitialized) return
    when (event.eventType) {
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // 对话框、Toast 等不是整页界面，不算前台切换
        if (!container.appInfo.isActivity(pkg, cls)) return
        EventTrace.add("前台 $pkg/$cls")
        val before = detector.currentPackage
        val detection = detector.onForegroundChanged(pkg, cls)
        if (detector.currentPackage != before) overlays.onForegroundChanged(before)
        detection ?: return
        EventTrace.add("${detection.type.label}（之前在 ${detector.previous}）")
        Log.i(TAG, "需要处理（之前在 ${detector.previous}）：$detection")
        responder.handle(detection) { goBack(detection) }
      }
      AccessibilityEvent.TYPE_WINDOWS_CHANGED -> onWindowsChanged()
      // 配置里只订阅了上面这两类
      else -> Unit
    }
  }

  private fun onWindowsChanged() {
    val list = runCatching { windows }.getOrNull() ?: return
    val ids = list.map { it.id }.toSet()
    windowPackages.keys.retainAll(ids)
    unresolvedTries.keys.retainAll(ids)
    val snapshots =
      list.map { w ->
        val pkg = windowPackages[w.id] ?: resolvePackage(w)
        WindowSnapshot(w.id, pkg, overlay = w.type == AccessibilityWindowInfo.TYPE_SYSTEM, active = w.isActive)
      }
    overlays
      .onWindows(snapshots, detector.currentPackage)
      // 厂商预装应用的悬浮按钮、悬浮球属于系统功能
      .filterNot { container.appInfo.isPreinstalled(it) }
      .forEach { pkg ->
        val detection = detector.onOverlay(pkg) ?: return@forEach
        EventTrace.add("悬浮窗 $pkg")
        Log.i(TAG, "弹出悬浮窗：$pkg")
        // 别人的悬浮窗按返回关不掉，只记录，由家人收回它的悬浮窗权限
        responder.handle(detection) { false }
      }
  }

  /** 取窗口所属 App；一直读不到（如安全窗口）的试几次就不再跨进程去取 */
  private fun resolvePackage(w: AccessibilityWindowInfo): String? {
    val tries = unresolvedTries[w.id] ?: 0
    if (tries >= MAX_RESOLVE_TRIES) return null
    val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull()
    if (pkg != null) {
      windowPackages[w.id] = pkg
      unresolvedTries.remove(w.id)
    } else {
      unresolvedTries[w.id] = tries + 1
    }
    return pkg
  }

  /**
   * 当前真正在前台的 App：取最上层的应用窗口。
   * 不能直接用活动窗口：活动的可能是别的 App 的悬浮窗，把它当前台会让判定器漏掉它之后真正打开的界面
   */
  private fun foregroundPackage(): String? {
    val list = runCatching { windows }.getOrNull() ?: return null
    val app = list.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return null
    return windowPackages[app.id] ?: runCatching { app.root?.packageName?.toString() }.getOrNull()
  }

  /** 先「返回」；部分广告页会拦截返回键，稍后它若仍在前台就回到桌面 */
  private fun goBack(detection: Detection): Boolean {
    if (!performGlobalAction(GLOBAL_ACTION_BACK)) return false
    main.postDelayed(
      {
        // 回到已有界面时不一定有窗口切换事件，以最上层的应用窗口为准；读不到时才用判定器记录的前台
        val foreground = foregroundPackage() ?: detector.currentPackage
        if (foreground != detection.targetPkg) {
          // 已经退回上一个界面：同步给判定器（通知栏等系统窗口不算前台 App）
          foreground?.takeUnless { it == SYSTEM_UI }?.let(detector::syncForeground)
        } else {
          Log.i(TAG, "返回无效，回到桌面：${detection.targetPkg}")
          performGlobalAction(GLOBAL_ACTION_HOME)
        }
      },
      BACK_VERIFY_MS,
    )
    return true
  }

  private fun recordInstall(pkg: String) {
    val c = container
    if (pkg == packageName) return
    EventTrace.add("新装 $pkg")
    scope.launch {
      val record = c.appInfo.installRecord(pkg)
      c.guardRepository.recordInstall(record)
      Log.i(TAG, "新装应用：$pkg 来源 ${c.appInfo.sourceLabel(record) ?: "未知"}")
    }
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
    private const val MAX_RESOLVE_TRIES = 5
    private const val SYSTEM_UI = "com.android.systemui"
    private val _running = MutableStateFlow(false)
    /** 本进程内服务是否处于连接状态（设置里开着但被系统杀掉时为 false） */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * 按系统记的首次安装时间补齐「新装的应用」（守护没在运行时装的、安装广播被厂商系统丢掉的）。
     * 补上的写进事件记录，从自检报告能看出是广播收到的还是补记的
     */
    suspend fun syncInstalls(c: AppContainer) {
      c.guardRepository.syncInstalls(c.appInfo.installedSince(c.guardRepository.installWindowStart())).forEach {
        EventTrace.add("补记新装 ${it.pkg}（按系统记的安装时间）")
      }
    }
  }
}

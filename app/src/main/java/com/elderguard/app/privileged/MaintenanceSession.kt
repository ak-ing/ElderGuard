package com.elderguard.app.privileged

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.elderguard.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 维护模式下可以直接执行的处置 */
enum class AppAction(val title: String, val supporting: String, val done: String) {
  REVOKE_OVERLAY("收回悬浮窗", "不能再显示在其他应用上层", "已收回悬浮窗"),
  RESTRICT_BACKGROUND("禁止后台运行", "退出后不能再自己启动、弹窗", "已禁止后台运行"),
  REVOKE_NOTIFICATIONS("关闭通知", "不能再发通知", "已关闭通知"),
  DISABLE_ACCESSIBILITY("关闭它的无障碍", "不能再读取屏幕、代替长辈点击", "已关闭它的无障碍"),
  FREEZE("冻结", "从桌面消失、完全不能运行，随时可以解冻", "已冻结"),
  UNFREEZE("解冻", "恢复成正常应用", "已解冻"),
  UNINSTALL("直接卸载", "不弹确认，立即卸载", "已卸载"),
}

/**
 * 维护模式：家人临时借助 Shizuku 获得 adb shell 权限，直接处置流氓应用。
 * 用完即关：结束时（手动、30 分钟无操作、或 App 进程被杀后下次启动时）关闭无线调试并释放高权限进程。
 * 维护模式之外，Shizuku 授权弹窗都会被守护挡掉（本 App 刚发起的除外），见 Detector。
 */
class MaintenanceSession(
  private val context: Context,
  private val executor: PrivilegedExecutor,
  private val settings: SettingsRepository,
  private val scope: CoroutineScope,
) {
  /** 自动结束的时间（墙上时间）；null 表示未在维护 */
  private val _endsAt = MutableStateFlow<Long?>(null)
  val endsAt: StateFlow<Long?> = _endsAt.asStateFlow()

  val active: Boolean
    get() = _endsAt.value != null

  private val lock = Mutex()
  private var timer: Job? = null

  suspend fun start(): Boolean =
    lock.withLock {
      executor.refresh()
      if (executor.status.value != PrivilegeStatus.READY) return false
      extend()
      true
    }

  suspend fun end() {
    lock.withLock {
      if (!active) return
      timer?.cancel()
      closeWirelessDebugging()
      executor.release()
      _endsAt.value = null
      settings.setMaintenanceEndsAt(null)
      Log.i(TAG, "维护模式已结束")
    }
  }

  /** App 进程在维护中途被杀：重启后补做收尾，避免无线调试一直开着 */
  suspend fun recoverAfterRestart() {
    if (settings.maintenanceEndsAt() == null || active) return
    val ready = withTimeoutOrNull(5_000) { executor.status.first { it == PrivilegeStatus.READY } } != null
    if (ready) closeWirelessDebugging()
    executor.release()
    settings.setMaintenanceEndsAt(null)
    Log.i(TAG, "补做维护收尾，Shizuku ${if (ready) "可用" else "不可用"}")
  }

  /** 当前适用于 [pkg] 的处置 */
  fun actionsFor(pkg: String): List<AppAction> {
    val pm = context.packageManager
    val info = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull() ?: return emptyList()
    val perms = info.requestedPermissions.orEmpty().toSet()
    val frozen = info.applicationInfo?.enabled == false
    return buildList {
      if (frozen) {
        add(AppAction.UNFREEZE)
      } else {
        if (Manifest.permission.SYSTEM_ALERT_WINDOW in perms) add(AppAction.REVOKE_OVERLAY)
        add(AppAction.RESTRICT_BACKGROUND)
        add(AppAction.REVOKE_NOTIFICATIONS)
        if (accessibilityServicesOf(pkg).isNotEmpty()) add(AppAction.DISABLE_ACCESSIBILITY)
        add(AppAction.FREEZE)
      }
      add(AppAction.UNINSTALL)
    }
  }

  /** 执行处置；返回给家人看的结果 */
  suspend fun perform(pkg: String, action: AppAction): Result<String> {
    if (!active) return Result.failure(IllegalStateException("维护模式未开启"))
    extend()
    val user = (Process.myUid() / PER_USER_RANGE).toString()
    val commands: List<List<String>> =
      when (action) {
        AppAction.REVOKE_OVERLAY ->
          listOf(listOf("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "ignore"), listOf("am", "force-stop", pkg))
        AppAction.RESTRICT_BACKGROUND ->
          buildList {
            add(listOf("appops", "set", pkg, "RUN_IN_BACKGROUND", "ignore"))
            if (Build.VERSION.SDK_INT >= 28) add(listOf("appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "ignore"))
            add(listOf("am", "force-stop", pkg))
          }
        AppAction.REVOKE_NOTIFICATIONS ->
          if (Build.VERSION.SDK_INT >= 33) listOf(listOf("pm", "revoke", pkg, Manifest.permission.POST_NOTIFICATIONS))
          else listOf(listOf("appops", "set", pkg, "POST_NOTIFICATION", "ignore"))
        AppAction.DISABLE_ACCESSIBILITY -> {
          val others = enabledAccessibility().filterNot { it.packageName == pkg }.joinToString(":") { it.flattenToString() }
          listOf(
            if (others.isEmpty()) listOf("settings", "delete", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            else listOf("settings", "put", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others)
          )
        }
        AppAction.FREEZE -> listOf(listOf("am", "force-stop", pkg), listOf("pm", "disable-user", "--user", user, pkg))
        AppAction.UNFREEZE -> listOf(listOf("pm", "enable", "--user", user, pkg))
        AppAction.UNINSTALL -> listOf(listOf("pm", "uninstall", "--user", user, pkg))
      }
    for (command in commands) {
      val r = executor.exec(*command.toTypedArray())
      // pm uninstall 失败时退出码也可能是 0，要看输出
      val failed = !r.ok || r.stdout.startsWith("Failure") || r.stderr.contains("Exception")
      if (failed) return Result.failure(IllegalStateException(explain(r)))
    }
    return Result.success(action.done)
  }

  private fun explain(r: ShellResult): String {
    val text = (r.stdout + " " + r.stderr).trim()
    return when {
      "DEVICE_POLICY_MANAGER" in text -> "它是设备管理器，请先在系统设置里取消它的设备管理器权限"
      text.isEmpty() -> "执行失败"
      else -> "执行失败：${text.take(80)}"
    }
  }

  private fun enabledAccessibility(): List<ComponentName> =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
      .orEmpty()
      .split(':')
      .mapNotNull { ComponentName.unflattenFromString(it) }

  private fun accessibilityServicesOf(pkg: String) = enabledAccessibility().filter { it.packageName == pkg }

  private suspend fun closeWirelessDebugging() {
    // 无线调试开关 Android 11 才有；USB 调试保留，家人可能正连着电脑
    if (Build.VERSION.SDK_INT >= 30) executor.exec("settings", "put", "global", ADB_WIFI_ENABLED, "0")
  }

  /** 每次操作都顺延自动结束时间 */
  private suspend fun extend() {
    val endsAt = System.currentTimeMillis() + IDLE_TIMEOUT_MS
    _endsAt.value = endsAt
    settings.setMaintenanceEndsAt(endsAt)
    timer?.cancel()
    timer =
      scope.launch {
        delay(IDLE_TIMEOUT_MS)
        // 先摘掉自己，end() 里取消 timer 不能取消正在执行收尾的这个协程
        timer = null
        end()
      }
  }

  private companion object {
    const val TAG = "ElderGuard"
    const val IDLE_TIMEOUT_MS = 30 * 60 * 1000L
    const val PER_USER_RANGE = 100_000
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
  }
}

package com.elderguard.app.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.elderguard.app.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

enum class PrivilegeStatus(val label: String) {
  NOT_INSTALLED("未安装"),
  NOT_RUNNING("未启动"),
  NEEDS_PERMISSION("待授权"),
  READY("已就绪"),
}

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
  val ok: Boolean
    get() = exitCode == 0
}

/**
 * 高权限执行器：维护模式下用来直接执行处置（收回悬浮窗、冻结、卸载…）。
 * 业务只依赖此接口，底层用 Shizuku。
 * 不内置 ADB：连本机 adb 需要联网权限，而本 App 承诺不联网；Shizuku 走 Binder，本 App 不需要联网权限。
 */
interface PrivilegedExecutor {
  val status: StateFlow<PrivilegeStatus>

  /** 重新检查（如从安装页、Shizuku 返回后） */
  fun refresh()

  fun requestPermission()

  /** 本 App 刚发起授权、弹窗还可能在显示（守护据此放行自己的授权弹窗） */
  fun isRequestingPermission(): Boolean

  suspend fun exec(vararg command: String): ShellResult

  /** 结束维护时释放高权限进程 */
  fun release()
}

class ShizukuExecutor(private val context: Context) : PrivilegedExecutor {
  private val _status = MutableStateFlow(PrivilegeStatus.NOT_INSTALLED)
  override val status: StateFlow<PrivilegeStatus> = _status.asStateFlow()

  private val bindLock = Mutex()
  @Volatile private var service: IBinder? = null

  private val serviceArgs =
    Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
      // 非常驻：本 App 进程退出时服务进程随之结束，高权限不会遗留
      .daemon(false)
      .processNameSuffix("shell")
      .debuggable(BuildConfig.DEBUG)
      .version(BuildConfig.VERSION_CODE)

  private val connection =
    object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = binder
        pendingBind?.complete(binder)
      }

      override fun onServiceDisconnected(name: ComponentName) {
        service = null
      }
    }
  @Volatile private var pendingBind: CompletableDeferred<IBinder>? = null

  init {
    // Shizuku 启动/停止、授权结果都会推送过来，状态随之更新
    Shizuku.addBinderReceivedListenerSticky { refresh() }
    Shizuku.addBinderDeadListener {
      service = null
      refresh()
    }
    Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
    refresh()
  }

  override fun refresh() {
    _status.value =
      when {
        !isShizukuInstalled() -> PrivilegeStatus.NOT_INSTALLED
        !Shizuku.pingBinder() -> PrivilegeStatus.NOT_RUNNING
        Shizuku.isPreV11() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
          PrivilegeStatus.NEEDS_PERMISSION
        else -> PrivilegeStatus.READY
      }
  }

  @Volatile private var requestedAt = Long.MIN_VALUE / 2

  override fun requestPermission() {
    if (!Shizuku.pingBinder()) return
    requestedAt = SystemClock.elapsedRealtime()
    Shizuku.requestPermission(REQUEST_CODE)
  }

  override fun isRequestingPermission(): Boolean = SystemClock.elapsedRealtime() - requestedAt <= SELF_REQUEST_WINDOW_MS

  override suspend fun exec(vararg command: String): ShellResult {
    refresh()
    if (status.value != PrivilegeStatus.READY) return ShellResult(-1, "", "Shizuku ${status.value.label}")
    val binder = bind() ?: return ShellResult(-1, "", "无法启动高权限服务")
    return withContext(Dispatchers.IO) {
      runCatching { ShellService.exec(binder, command) }
        .getOrElse { ShellResult(-1, "", it.message.orEmpty()) }
        .also { Log.i(TAG, "执行 ${command.joinToString(" ")} → ${it.exitCode} ${it.stdout} ${it.stderr}") }
    }
  }

  override fun release() {
    if (!Shizuku.pingBinder()) return
    runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
    service = null
  }

  private suspend fun bind(): IBinder? =
    bindLock.withLock {
      service?.takeIf { it.pingBinder() }?.let { return it }
      val deferred = CompletableDeferred<IBinder>().also { pendingBind = it }
      withContext(Dispatchers.Main) { Shizuku.bindUserService(serviceArgs, connection) }
      withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }.also { pendingBind = null }
    }

  private fun isShizukuInstalled(): Boolean =
    runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess

  companion object {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    /** Shizuku 的授权对话框 */
    const val SHIZUKU_PERMISSION_ACTIVITY = "moe.shizuku.manager.authorization.RequestPermissionActivity"
    private const val TAG = "ElderGuard"
    private const val REQUEST_CODE = 1001
    private const val BIND_TIMEOUT_MS = 10_000L
    private const val SELF_REQUEST_WINDOW_MS = 15_000L
  }
}
